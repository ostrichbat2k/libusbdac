/*
 * Copyright (c) 2026 libusbdac Project
 * UsbIsoStreamEngine.kt is part of libusbdac.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.ostrichbat2k.usbdac.streaming

import org.ostrichbat2k.usbdac.UsbAudioTuning
import org.ostrichbat2k.usbdac.protocol.SyncType
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession
import timber.log.Timber as L

/**
 * Owns the native isochronous handle(s), the URB submit/reap loop(s) (main OUT stream plus the
 * optional feedback IN stream, #25), and frames-submitted/frames-completed position accounting
 * -- pulled out of [UsbAudioOutput] (#73) so this, the one part of the library every serious
 * concurrency/correctness bug from two independent reviews has lived in (#68, #59, #60, #70,
 * #71), is reachable by a test at all: [UsbAudioOutput] itself still can't be driven through
 * [UsbAudioOutput.play]'s real streaming path in a plain JVM test (that still goes through the
 * real [UsbNative] object, whose `init {}` throws `UnsatisfiedLinkError` outside a real device
 * process), but this class takes its native calls through an injectable [nativeBridge] instead,
 * defaulting to the real adapter ([UsbNativeBridgeImpl]) -- the same seam pattern
 * `UsbAudioManagerImpl`'s own `dacActivator`/`deviceDiscovery` already use.
 *
 * Pure indirection relative to the code this was extracted from: no argument order, timing, or
 * calling convention on any real native call changed. [paused] and [reportPipelineStats] are
 * read/called live (not captured once), exactly matching the original closures' own behavior --
 * [paused] in particular still has to reflect [UsbAudioOutput]'s own `@Volatile` flag at the
 * instant each URB is built, not a value frozen when this engine was constructed.
 */
internal class UsbIsoStreamEngine(
    private val session: UsbAudioSession,
    private val format: UacStreamFormat,
    private val channels: Int,
    private val sampleRateHz: Int,
    private val ringBuffer: PcmRingBuffer,
    private val paused: () -> Boolean,
    private val reportPipelineStats: (UsbPipelineStats?) -> Unit,
    private val nativeBridge: UsbNativeBridge = UsbNativeBridgeImpl,
) {
    private companion object {
        // factor(i) = 1.0 - (i+1)/UsbAudioTuning.UNDERRUN_FADE_FRAMES for the common case where a short read's
        // fade window is the full UsbAudioTuning.UNDERRUN_FADE_FRAMES (true whenever the read has at least that
        // many real frames in it, the overwhelming majority of underruns) -- computed once here
        // rather than with a floating-point division per frame per channel on every single
        // underrun on the real-time native streaming thread. Only the rare case where the read
        // itself is shorter than UsbAudioTuning.UNDERRUN_FADE_FRAMES still computes its own (necessarily
        // differently-shaped) factors on the fly -- see fadeTailToSilence.
        val UNDERRUN_FADE_FACTORS = DoubleArray(UsbAudioTuning.UNDERRUN_FADE_FRAMES) { i ->
            1.0 - (i + 1).toDouble() / UsbAudioTuning.UNDERRUN_FADE_FRAMES
        }
    }

    private val frameBytes = channels * format.subslotSize

    private var isoHandle = 0L
    private var streamThread: Thread? = null

    @Volatile var streaming: Boolean = false
        private set

    @Volatile var lastUrbSubmittedAtMs: Long = 0L
        private set

    // Frames actually handed to the device via a paced, submitted URB -- i.e. audibly played --
    // see UsbAudioOutput.getPositionUs's own doc comment for why this, not framesWritten, has to
    // be what position reporting is based on.
    @Volatile var framesOutput: Long = 0L
        private set

    // Feedback-clocked async support (#25) -- see UacFeedbackEndpoint's own doc comment. Stay at
    // their zero/null/false defaults whenever this format isn't SyncType.ASYNC with a feedback
    // endpoint, or when opening the feedback stream itself failed; a feedback failure never
    // affects the main fields above -- see stop()'s mirrored-but-independent shutdown of these.
    private var feedbackHandle = 0L
    private var feedbackThread: Thread? = null

    @Volatile private var feedbackStreaming = false

    // Only ever read/written from the single native streaming thread -- not actually volatile for
    // cross-thread visibility, just convenient to declare alongside the other stream-lifetime state.
    private var underrunCount = 0

    /** Resets position accounting -- called from [UsbAudioOutput.flush]. */
    fun resetPosition() {
        framesOutput = 0L
    }

    fun start() {
        if (streaming) return
        val fd = session.connection.fileDescriptor

        // THE pacing fix: a high-speed device schedules one iso packet per 125us *microframe*,
        // not per 1ms frame -- confirmed via UsbNative.getUsbSpeed on real hardware (this FiiO
        // device negotiates high-speed). Assuming 1000 packets/sec unconditionally (true only for
        // full-speed) packed a full millisecond's worth of samples into a slot the device only
        // actually allots 125us of real playback-clock time to -- the USBDEVFS-level transfer
        // still completes with zero errors either way (the wire itself has no problem physically
        // carrying that many bytes in one microframe), but the device's own internal clock
        // consumes its receive buffer at the *real*, correct sample rate regardless of how fast
        // we hand it data, so this meant feeding it audio 8x faster than it could actually play
        // the data back -- invisible to every error counter this code has, since the corruption
        // happens inside the device's own silicon once its buffer can't absorb the overfill
        // anymore, not on the USB wire. Confirmed, reproduced: audible crackle that was worse and
        // more frequent at 96kHz than 44.1/48kHz, consistent with the *absolute* excess bytes/sec
        // being larger at a higher sample rate against the same fixed-size device buffer.
        //
        // packetsPerUrb is scaled up by the same factor as packetsPerSecond (see
        // UsbAudioTuning.BASE_PACKETS_PER_URB's doc comment) so the *real* amount of audio -- and host-controller
        // lookahead cushion -- one URB represents stays the same regardless of negotiated speed;
        // only each packet's own content size shrinks to match its true, shorter duration.
        val usbSpeed = nativeBridge.getUsbSpeed(fd)
        val speedName =
            when (usbSpeed) {
                1 -> "low"
                2 -> "full"
                3 -> "high"
                4 -> "wireless"
                5 -> "super"
                6 -> "super+"
                else -> "unknown"
            }
        val pacing = IsoPacing.forSpeed(usbSpeed, UsbAudioTuning.BASE_PACKETS_PER_URB, format.bInterval)
        val packetsPerSecond = pacing.packetsPerSecond
        val packetDurationNs = pacing.packetDurationNs
        val packetsPerUrb = pacing.packetsPerUrb

        val handle =
            nativeBridge.openIsoStream(
                fd, format.endpointAddress, format.maxPacketSize, packetsPerUrb, UsbAudioTuning.NUM_URBS)
        if (handle == 0L) {
            L.w("UsbIsoStreamEngine: failed to open iso stream")
            return
        }
        L.i(
            "UsbIsoStreamEngine: opened iso stream fd=$fd endpoint=${format.endpointAddress} " +
                "maxPacketSize=${format.maxPacketSize} handle=$handle usbSpeed=$speedName " +
                "bInterval=${format.bInterval} (pacing $packetsPerSecond packets/sec, " +
                "${packetDurationNs / 1000}us/packet, $packetsPerUrb packets/urb)")
        isoHandle = handle
        val scheduler = IsoPacketScheduler(sampleRateHz, packetsPerSecond)

        // Reused across every call instead of allocating a fresh ByteArray/IntArray each time --
        // this runs every URB, indefinitely, for the entire stream. Confirmed, reproduced:
        // audible crackling that could start spontaneously a minute or more into otherwise-clean
        // playback, and got worse at higher data rates (96kHz) -- consistent with the garbage
        // from thousands of allocations a minute eventually triggering a GC pause long enough to
        // stall this time-critical thread past the lookahead the in-flight URBs provide, starving
        // the device's isochronous schedule. Sized for the largest a packet can ever be for this
        // sampleRateHz (IsoPacketScheduler's Bresenham remainder adds at most one extra sample).
        val maxBytesPerPacket = (sampleRateHz / packetsPerSecond + 1) * frameBytes
        val urbBuffer = ByteArray(packetsPerUrb * maxBytesPerPacket)
        val packetLengths = IntArray(packetsPerUrb)
        // What's currently loaded into each URB slot, for framesOutput to credit once reapIso()
        // confirms *that* slot's URB actually completed -- not when it was merely submitted (#71).
        // Reused across this stream's whole lifetime the same way urbBuffer/packetLengths are:
        // indexed by slot, overwritten every time that slot is resubmitted.
        val slotFrames = LongArray(UsbAudioTuning.NUM_URBS)
        val slotCreditable = BooleanArray(UsbAudioTuning.NUM_URBS)

        /**
         * Ramps the last [UsbAudioTuning.UNDERRUN_FADE_FRAMES] frames of real audio already in [buf] (ending at
         * [readBytes]) down to silence, in place, instead of leaving the hard edge
         * [PcmRingBuffer.readOrZeroPad]'s zero-padding creates right at that boundary.
         *
         * A real underrun's audible symptom isn't the padding itself -- confirmed, reproduced: a
         * *fully* empty read (zero real bytes, the whole URB zero-padded) is inaudible, just a
         * fraction more silence before sound starts/resumes. It's specifically a *partial* read
         * (some real signal, then a dead stop at [readBytes]) that's heard as a click/crackle,
         * because real waveforms essentially never cross zero exactly at that boundary -- cutting
         * straight to silence there is a genuine discontinuity, not just an early mute. Fading the
         * tail down smoothly removes that discontinuity without changing anything else about how
         * underruns are handled (still logged, still rate-limited, still just silence beyond
         * [readBytes]) -- it only asks "how do the last few real samples transition into that
         * silence" instead of "do they transition at all".
         */
        fun fadeTailToSilence(buf: ByteArray, readBytes: Int) {
            val subslot = format.subslotSize
            val totalFrames = readBytes / frameBytes
            val fadeFrames = minOf(UsbAudioTuning.UNDERRUN_FADE_FRAMES, totalFrames)
            if (fadeFrames <= 0) return
            val signBit = 1L shl (subslot * 8 - 1)
            val maxValue = signBit - 1
            val minValue = -signBit
            for (i in 0 until fadeFrames) {
                val frameIndex = totalFrames - fadeFrames + i
                // Reaches exactly 0 at the last faded frame, right where the zero-padded region
                // -- already silent -- begins, so the two halves meet smoothly instead of at a
                // sudden edge. Uses the precomputed table whenever fadeFrames is the usual full
                // UsbAudioTuning.UNDERRUN_FADE_FRAMES window; only the rare shorter-read case (fadeFrames <
                // UsbAudioTuning.UNDERRUN_FADE_FRAMES) still divides on the fly, since that shape isn't in the
                // table.
                val factor =
                    if (fadeFrames == UsbAudioTuning.UNDERRUN_FADE_FRAMES) {
                        UNDERRUN_FADE_FACTORS[i]
                    } else {
                        1.0 - (i + 1).toDouble() / fadeFrames
                    }
                val frameOffset = frameIndex * frameBytes
                for (ch in 0 until channels) {
                    val sampleOffset = frameOffset + ch * subslot
                    var sample = 0L
                    for (b in 0 until subslot) {
                        sample = sample or ((buf[sampleOffset + b].toLong() and 0xFF) shl (8 * b))
                    }
                    if (sample and signBit != 0L) sample -= (signBit shl 1)
                    val faded = (sample * factor).toLong().coerceIn(minValue, maxValue)
                    for (b in 0 until subslot) {
                        buf[sampleOffset + b] = ((faded shr (8 * b)) and 0xFF).toByte()
                    }
                }
            }
        }

        /** Fills [urbBuffer]/[packetLengths] in place; returns how many bytes of [urbBuffer] are used. */
        fun buildUrb(): Int {
            var total = 0
            for (p in 0 until packetsPerUrb) {
                val bytes = scheduler.nextPacketSampleCount() * frameBytes
                packetLengths[p] = bytes
                total += bytes
            }
            if (!paused()) {
                val read = ringBuffer.readOrZeroPad(urbBuffer, total)
                // A genuine underrun (decoder/renderer thread couldn't keep up, ring buffer ran
                // dry) rather than the deliberate pause-silence case above -- heard as a click/
                // crackle, since real audio briefly drops to silence mid-stream. Rate-limited:
                // this is checking a hunch that this is rate-dependent (more bytes/sec to decode
                // in real time at 96kHz than 44.1/48kHz for the same ring buffer *time* depth), so
                // what matters is whether it happens at all and roughly how often, not a line per
                // occurrence.
                if (read < total) {
                    underrunCount++
                    if (underrunCount == 1 || underrunCount % 50 == 0) {
                        L.w(
                            "UsbIsoStreamEngine: ring buffer underrun #$underrunCount -- wanted $total " +
                                "bytes, had $read (short by ${total - read})")
                    }
                    // Only a partial read has a real edge to smooth -- a fully empty one is
                    // already just silence, nothing to fade (see fadeTailToSilence's doc comment).
                    if (read > 0) fadeTailToSilence(urbBuffer, read)
                }
            } else {
                // Reused buffer may still hold real audio from a prior, unpaused call -- must
                // explicitly silence it rather than relying on a fresh zero-filled array.
                urbBuffer.fill(0, 0, total)
            }
            return total
        }

        streaming = true
        lastUrbSubmittedAtMs = System.currentTimeMillis()
        streamThread =
            Thread {
                    // Default thread priority competes on equal footing with the decoder/GC/UI
                    // for CPU time -- fine at 44.1/48kHz's lower data rate (~176-192 KB/s), but
                    // confirmed, reproduced at 96kHz (~384 KB/s): audible crackling through the
                    // DAC specifically (clean on speaker, same files), with zero errors anywhere
                    // in the native USB transfer layer (no reap errors, no short/failed packets,
                    // ring buffer never empty) -- i.e. real, correctly-paced data was being
                    // submitted, just not always with enough real-time precision under
                    // scheduling contention to avoid an audible gap at the higher data rate.
                    // THREAD_PRIORITY_URGENT_AUDIO is the standard Android fix for exactly this:
                    // the same elevated real-time scheduling class AudioTrack's own internal
                    // mixer thread runs at, so this thread gets prioritized the same way a real
                    // audio output thread would.
                    setUrgentAudioPriority()
                    L.i("UsbIsoStreamEngine: streaming thread starting ($sampleRateHz Hz)")
                    for (slot in 0 until UsbAudioTuning.NUM_URBS) {
                        val total = buildUrb()
                        // Retry once immediately before giving up on this slot -- see #59: a
                        // slot that's never submitted here never enters the reap/resubmit
                        // rotation at all, permanently shrinking it by one for the rest of the
                        // stream's life. A device not yet fully settled right after claim is the
                        // one case actually seen to fail transiently this early (see
                        // UsbAudioTuning.DAC_SETTLE_DELAY_MS's own history); a second attempt a
                        // few microseconds later costs nothing against that.
                        val ok =
                            nativeBridge.submitIso(handle, slot, urbBuffer, packetLengths) ||
                                nativeBridge.submitIso(handle, slot, urbBuffer, packetLengths)
                        if (!ok) {
                            L.e(
                                "UsbIsoStreamEngine: initial submitIso failed twice for slot $slot " +
                                    "-- this URB stays unsubmitted")
                        } else {
                            // Credited once reapIso() confirms this slot's URB actually
                            // completed, in the steady-state loop below -- not now (#71). Prefill
                            // always runs right after start() was called with paused already
                            // confirmed false, so always creditable, same as the old
                            // unconditional credit here.
                            slotFrames[slot] = (total / frameBytes).toLong()
                            slotCreditable[slot] = true
                        }
                    }

                    // USBDEVFS_REAPURB is supposed to block until a submitted URB has genuinely
                    // completed in real USB bus time, so correct real-time pacing should come for
                    // free from the kernel/host controller blocking on it. Confirmed, on a real
                    // device, that assumption doesn't hold on its own: reapIso() was observed
                    // completing URBs roughly 7x faster than real time allows (now understood: on
                    // this high-speed device, the true packet duration is 125us, and this
                    // mismeasurement -- taken against an assumed 1ms -- was actually consistent
                    // with that, within measurement noise, rather than a genuinely unexplained
                    // host-controller quirk). Pacing explicitly against wall-clock time here,
                    // rather than trusting reapIso()'s own timing, is still the right approach
                    // regardless -- it just needs packetDurationNs to be correct for this
                    // device's real negotiated speed (see above) to actually pace against the
                    // real rate instead of papering over the symptom with an equally-wrong delay.
                    val streamStartNs = System.nanoTime()
                    val urbRealTimeNs = packetsPerUrb * packetDurationNs
                    var urbsPaced = 0L
                    var submitted = 0
                    var reapErrors = 0
                    while (streaming) {
                        val slot = nativeBridge.reapIso(handle)
                        if (!streaming) break
                        if (slot < 0) {
                            reapErrors++
                            if (slot == UsbNativeBridge.REAP_ERROR_FATAL) {
                                // The stream cannot produce anything more (device gone, most
                                // likely) -- see #60: looping back to reapIso() here is exactly
                                // the busy-spin this check exists to stop. isStalled() (already
                                // polled by callers, e.g. play()'s retry path) picks this up on
                                // its own once lastUrbSubmittedAtMs stops advancing, so nothing
                                // further needs to be signalled from here.
                                L.e("UsbIsoStreamEngine: reapIso() reported a fatal error -- stopping the streaming thread")
                                break
                            }
                            continue
                        }

                        // This slot's URB has now genuinely completed (reapIso() returning it is
                        // exactly that confirmation) -- credit framesOutput for whatever was
                        // loaded into it back when IT was submitted (prefill above, or a previous
                        // trip through this loop), not for the new content built below (#71:
                        // submission time != completion time -- crediting at submission there
                        // reported playback as running ahead of what the device had actually
                        // consumed, by up to the whole prefill depth).
                        if (slotCreditable[slot]) {
                            framesOutput += slotFrames[slot]
                        }

                        urbsPaced++
                        val waitNs = (streamStartNs + urbsPaced * urbRealTimeNs) - System.nanoTime()
                        if (waitNs > 0) {
                            Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                        }

                        val total = buildUrb()
                        // Retry once immediately before giving up on this slot -- see #59: unlike
                        // every other slot, which keeps cycling through reapIso() even after a
                        // failed resubmit (so a future reap just finds it still full of stale
                        // data), a slot that's never resubmitted here never comes back through
                        // reapIso() at all, permanently shrinking the rotation by one.
                        val resubmitted =
                            nativeBridge.submitIso(handle, slot, urbBuffer, packetLengths) ||
                                nativeBridge.submitIso(handle, slot, urbBuffer, packetLengths)
                        if (!resubmitted) {
                            // Lost for good now -- same reasoning as the fatal reapIso() branch
                            // above: stop rather than keep draining the ring buffer into a
                            // shrinking, eventually-empty set of URB slots.
                            L.e(
                                "UsbIsoStreamEngine: submitIso() failed twice in a row for slot " +
                                    "$slot -- stopping the streaming thread")
                            break
                        }
                        submitted++
                        lastUrbSubmittedAtMs = System.currentTimeMillis()
                        // Recorded now, credited above next time this slot comes back around
                        // through reapIso() confirming it completed. Not creditable while paused:
                        // this is synthetic silence standing in for the USB clock's unstoppable
                        // schedule, not real consumed track audio -- see UsbAudioOutput.paused's
                        // doc comment. Crediting it would let getPositionUs() catch up to
                        // framesWritten mid-pause and re-trigger the exact premature-isEnded()
                        // stall this counter exists to prevent.
                        slotFrames[slot] = (total / frameBytes).toLong()
                        slotCreditable[slot] = !paused()
                        // ~20 URBs at this class's ~8ms-of-real-audio-per-URB pacing (see
                        // UsbAudioTuning.BASE_PACKETS_PER_URB) is roughly a 160ms cadence -- frequent enough
                        // for a live pipeline visualizer to feel real-time without pushing a
                        // new StateFlow value (and the GC churn that implies) on every single
                        // URB, which happens ~125 times/sec.
                        if (submitted % 20 == 0) {
                            val packetErrorStats = nativeBridge.getPacketErrorStats(handle)
                            reportPipelineStats(
                                UsbPipelineStats(
                                    atMs = System.currentTimeMillis(),
                                    urbsSubmitted = submitted.toLong(),
                                    reapErrors = reapErrors,
                                    underrunCount = underrunCount,
                                    bufferedBytes = ringBuffer.availableToRead(),
                                    bufferCapacityBytes = ringBuffer.availableToRead() + ringBuffer.availableToWrite(),
                                    badPacketCount = packetErrorStats[0],
                                    shortBytesCount = packetErrorStats[1]))
                        }
                        if (submitted % 500 == 0) {
                            val realElapsedMs = (System.nanoTime() - streamStartNs) / 1_000_000L
                            val pacedMs = urbsPaced * urbRealTimeNs / 1_000_000L
                            L.i(
                                "UsbIsoStreamEngine: $submitted URBs submitted, $reapErrors reap " +
                                    "errors, ${ringBuffer.availableToRead()} bytes buffered, " +
                                    "${pacedMs}ms of audio paced over ${realElapsedMs}ms real time")
                        }
                    }
                    L.i("UsbIsoStreamEngine: streaming thread exiting ($submitted submitted, $reapErrors reap errors)")
                }
                .apply {
                    isDaemon = true
                    name = "UsbAudioOutput-stream"
                    start()
                }

        startFeedbackThreadIfApplicable(fd, scheduler)
    }

    /**
     * Opens and starts the feedback-endpoint reader thread (#25) when [format] is
     * [SyncType.ASYNC] and declares a [org.ostrichbat2k.usbdac.protocol.UacFeedbackEndpoint] -- a
     * second, independent blocking native loop on a second handle/fd pair, same
     * [UsbNativeBridge.openIsoStream]/[UsbNativeBridge.reapIso] pattern [start]'s own main loop
     * already uses, just reading instead of writing. [scheduler] is the exact same instance the
     * main loop paces packets against -- captured here by closure, not promoted to an instance
     * field, since both threads are started from within this one call to [start] and never need
     * to reach it from anywhere else.
     *
     * Every failure here (open failed, fatal reap error) is logged and leaves this thread/handle
     * stopped, but never touches [streaming]/[isoHandle]/the main loop -- a feedback failure
     * means the scheduler stops getting corrected and stays at whatever rate was last set, not
     * that playback itself is at risk.
     */
    private fun startFeedbackThreadIfApplicable(fd: Int, scheduler: IsoPacketScheduler) {
        val feedback = format.feedbackEndpoint
        if (format.syncType != SyncType.ASYNC || feedback == null) return

        val handle =
            nativeBridge.openIsoStream(
                fd,
                feedback.address,
                feedback.maxPacketSize,
                UsbAudioTuning.FEEDBACK_PACKETS_PER_URB,
                UsbAudioTuning.FEEDBACK_NUM_URBS)
        if (handle == 0L) {
            L.w("UsbIsoStreamEngine: failed to open feedback iso stream -- continuing without rate correction")
            return
        }
        L.i("UsbIsoStreamEngine: opened feedback iso stream endpoint=${feedback.address} handle=$handle")
        feedbackHandle = handle
        feedbackStreaming = true

        feedbackThread =
            Thread { runFeedbackLoop(handle, feedback.maxPacketSize, scheduler) }
                .apply {
                    isDaemon = true
                    name = "UsbAudioOutput-feedback"
                    start()
                }
    }

    /**
     * The feedback thread's body, split out of [startFeedbackThreadIfApplicable] purely to keep
     * that function's own cyclomatic complexity within detekt's threshold -- behaves identically
     * either way, since [handle]/[maxPacketSize]/[scheduler] are exactly what the closure captured
     * before this split.
     */
    private fun runFeedbackLoop(handle: Long, maxPacketSize: Int, scheduler: IsoPacketScheduler) {
        val packetLengths = IntArray(UsbAudioTuning.FEEDBACK_PACKETS_PER_URB) { maxPacketSize }
        val buffer = ByteArray(maxPacketSize)

        fun resubmit(slot: Int) {
            val ok =
                nativeBridge.submitIsoRead(handle, slot, packetLengths) ||
                    nativeBridge.submitIsoRead(handle, slot, packetLengths)
            if (!ok) {
                L.w("UsbIsoStreamEngine: feedback submitIsoRead failed twice for slot $slot -- this URB stays unsubmitted")
            }
        }

        setUrgentAudioPriority()
        L.i("UsbIsoStreamEngine: feedback thread starting")
        for (slot in 0 until UsbAudioTuning.FEEDBACK_NUM_URBS) resubmit(slot)

        while (feedbackStreaming) {
            val slot = nativeBridge.reapIso(handle)
            if (!feedbackStreaming) break
            if (slot < 0) {
                if (slot == UsbNativeBridge.REAP_ERROR_FATAL) {
                    L.e(
                        "UsbIsoStreamEngine: feedback reapIso() reported a fatal error -- stopping " +
                            "the feedback thread only")
                    break
                }
                continue
            }
            val bytesRead = nativeBridge.readIsoSlotData(handle, slot, buffer)
            if (bytesRead > 0) {
                val decoded = FeedbackRateDecoder.decode(buffer.copyOf(bytesRead), sampleRateHz)
                if (decoded != null) scheduler.updateRate(decoded)
            }
            resubmit(slot)
        }
        L.i("UsbIsoStreamEngine: feedback thread exiting")
    }

    fun stop() {
        val mainIdle = !streaming && isoHandle == 0L
        val feedbackIdle = !feedbackStreaming && feedbackHandle == 0L
        if (mainIdle && feedbackIdle) return
        streaming = false
        if (isoHandle != 0L) {
            nativeBridge.cancelIso(isoHandle)
        }
        feedbackStreaming = false
        if (feedbackHandle != 0L) {
            nativeBridge.cancelIso(feedbackHandle)
        }
        streamThread?.join(UsbAudioTuning.STREAM_THREAD_JOIN_TIMEOUT_MS)
        feedbackThread?.join(UsbAudioTuning.STREAM_THREAD_JOIN_TIMEOUT_MS)

        // The feedback stream (#25) is shut down independently of the main one below: a stuck
        // feedback thread must never block freeing the main stream, and vice versa -- the same
        // documented-but-structurally-unfixed concurrent-close contract #76 already tracks for a
        // single stream applies separately to each of these two, not jointly.
        val mainStuck = isStreamThreadStuck(streamThread)
        if (mainStuck) {
            // cancelIso() failed to unblock the thread's native call within the timeout --
            // freeing isoHandle now would race a still-running reapIso()/submitIso() over memory
            // closeIsoStream() frees underneath it, a genuine use-after-free (confirmed,
            // reproduced -- see UsbAudioTuning.STREAM_THREAD_JOIN_TIMEOUT_MS's doc comment).
            // Leaking the handle and the stuck thread is the safe failure mode here: [streaming]
            // is already false, so nothing further gets submitted from this instance, and a
            // later call into this same function (e.g. a retried pause()/release()) will attempt
            // the same cancel-and-join sequence again rather than being permanently blocked.
            L.e(
                "UsbIsoStreamEngine: streaming thread did not stop within " +
                    "${UsbAudioTuning.STREAM_THREAD_JOIN_TIMEOUT_MS}ms of cancelIso() -- leaving " +
                    "the native stream open rather than risk a use-after-free")
        } else {
            streamThread = null
            if (isoHandle != 0L) {
                nativeBridge.closeIsoStream(isoHandle)
                isoHandle = 0L
            }
        }

        val feedbackStuck = isStreamThreadStuck(feedbackThread)
        if (feedbackStuck) {
            L.e(
                "UsbIsoStreamEngine: feedback thread did not stop within " +
                    "${UsbAudioTuning.STREAM_THREAD_JOIN_TIMEOUT_MS}ms of cancelIso() -- leaving " +
                    "the native feedback stream open rather than risk a use-after-free")
        } else {
            feedbackThread = null
            if (feedbackHandle != 0L) {
                nativeBridge.closeIsoStream(feedbackHandle)
                feedbackHandle = 0L
            }
        }

        if (mainStuck) return
        // Stream genuinely stopped (not a transient pause-silence gap, which doesn't call this at
        // all) -- clear rather than leave the pipeline visualizer showing numbers frozen from a
        // track that isn't playing anymore.
        reportPipelineStats(null)
    }

    /**
     * Whether [thread] is still running after [stop]'s join -- i.e. [UsbNativeBridge.cancelIso]
     * failed to unblock it within [UsbAudioTuning.STREAM_THREAD_JOIN_TIMEOUT_MS]. A private copy
     * of the exact same one-line check [UsbAudioOutput.isStreamThreadStuck] keeps (not a shared
     * call into it, and not moved out of there) -- that one is an existing, already-tested
     * seam a test drives directly against a real [UsbAudioOutput] instance without ever
     * reaching this class at all; duplicating this trivial, stateless check here instead of
     * having either class call into the other keeps that seam exactly as it was.
     */
    private fun isStreamThreadStuck(thread: Thread?): Boolean = thread?.isAlive == true

    /**
     * Raises the calling (streaming or feedback) thread to [android.os.Process.THREAD_PRIORITY_URGENT_AUDIO]
     * -- see [start]'s own doc comment on that call for why. Caught, not left to crash the whole
     * thread: real Android never throws here for this constant, but
     * [android.os.Process.setThreadPriority] is documented as being able to (e.g. a
     * `SecurityException` if something ever restricts it) -- losing the priority hint is far
     * better than losing the entire stream over it, and a plain-JVM test environment with no
     * Android runtime throws here unconditionally (`"not mocked"`), which this also lets a test
     * run past harmlessly.
     */
    @Suppress("TooGenericExceptionCaught") // Deliberately broad -- see this function's own doc comment.
    private fun setUrgentAudioPriority() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: RuntimeException) {
            L.w("UsbIsoStreamEngine: failed to raise thread priority: ${e.message}")
        }
    }
}
