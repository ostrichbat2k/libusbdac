/*
 * Copyright (c) 2026 Auxio Project
 * UsbAudioOutput.kt is part of Auxio.
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

package org.ostrichbat2k.usbdac

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer
import timber.log.Timber as L

/**
 * An [AudioOutput] that streams PCM directly to a UAC1/2/3 device's isochronous OUT endpoint via
 * raw USBDEVFS URBs (see [UsbNative]), bypassing AudioTrack and Android's mixer entirely.
 *
 * One instance is constructed per sample rate (see [UsbAudioOutputProvider.getAudioOutput]) --
 * all the one-time-per-rate setup (clock negotiation, power domain wake, mute/volume, settle
 * delay) happens in the constructor, mirroring [AudioOutput.canReuseAudioOutput]'s default
 * "different config means a new instance" contract.
 *
 * This used to be a full, hand-rolled [androidx.media3.exoplayer.audio.AudioSink]
 * (`UsbAudioSink`), but that meant reimplementing all of [DefaultAudioSink][
 * androidx.media3.exoplayer.audio.DefaultAudioSink]'s buffering/retry/position-tracking/stall-
 * recovery logic from scratch -- and missing a real piece of it turned out to be exactly what
 * caused a reproduced, permanent playback stall: `DefaultAudioSink.handleBuffer()` detects a
 * genuinely stalled output (via [isStalled]) and self-recovers by flushing, which the old
 * [AudioSink][androidx.media3.exoplayer.audio.AudioSink] implementation here had no equivalent
 * of at all. Implementing this narrower [AudioOutput] interface instead and handing it to a
 * plain `DefaultAudioSink` via [UsbAudioOutputProvider] means all of that proven logic (including
 * [isStalled] recovery) applies to USB output the same way it already does for normal AudioTrack
 * output, and this class only has to handle the parts that are genuinely USB-specific: writing
 * PCM to the isochronous endpoint and reading the device's own clock/volume state back.
 *
 * 44100 Hz doesn't divide evenly into USB's one-packet-per-millisecond isochronous schedule (44.1
 * samples/packet), so packet sizes vary slightly call to call via [IsoPacketScheduler] rather than
 * always being the endpoint's declared max -- see that class for how.
 */
class UsbAudioOutput(
    private val session: UsbAudioSession,
    private val format: UacStreamFormat,
    private val channels: Int,
    private val sampleRateHz: Int,
    private val initialVolumeDb: Int?,
    // So pause()/play() can hand the raw interface back to Android's own USB-audio HAL whenever
    // this instance genuinely stops being used, and reclaim it when actually needed again --
    // see activateAndStream()/pause()'s doc comments.
    private val usbAudioManager: UsbAudioManager,
) : AudioOutput {

    private companion object {
        // The target real URB duration at full-speed (1 packet == 1ms): 8 packets == 8ms. Scaled
        // up at runtime for a high-speed device (1 packet == 125us instead) so the *real* amount
        // of audio buffered per URB -- and therefore the host-controller lookahead cushion NUM_URBS
        // of them provide against scheduling jitter -- stays the same regardless of negotiated
        // speed; only each individual packet's content size (computed from the true packet
        // duration, not assumed) changes. See startStreamingThread's packetsPerUrb/packetsPerSecond.
        const val BASE_PACKETS_PER_URB = 8
        const val NUM_URBS = 4
        const val RING_BUFFER_MS = 5000
        // How many extra DAC_SETTLE_DELAY_MS waits to give a per-track clock change before
        // giving up on CLOCK_VALID ever reporting true and streaming anyway (better than hanging
        // forever on a device that simply doesn't implement this control, which getClockValid
        // already treats as a read failure, same as "invalid").
        const val CLOCK_VALID_MAX_RETRIES = 3
        // isStalled() fires if the native streaming thread hasn't managed to submit a single URB
        // in this long -- i.e. our own write-side infrastructure has stopped entirely (crashed
        // thread, native-level hang), not just normal ring-buffer backpressure, which resolves on
        // its own as the thread keeps draining at its paced ~8ms-of-real-audio-per-URB rate.
        const val STALL_THRESHOLD_MS = 1000L
    }

    private val bytesPerFrame = channels * format.subslotSize
    private var ringBuffer = PcmRingBuffer(bytesPerFrame * sampleRateHz * RING_BUFFER_MS / 1000)
    private var framesWritten = 0L
    // Frames actually handed to the device via a paced, submitted URB -- i.e. audibly played,
    // not just sitting in [ringBuffer]. [getPositionUs] MUST report this, not [framesWritten]:
    // DefaultAudioSink.hasAudioOutputPendingData() compares its own written-frame count against
    // audioOutput.getPositionUs() to decide whether there's still unplayed data. Reporting
    // framesWritten there made that comparison always ~equal, so hasPendingData() was false (and
    // isEnded() true) up to RING_BUFFER_MS early -- DefaultAudioSink believed playout had already
    // finished while up to 5s of real audio was still draining out of the ring buffer, and
    // permanently stopped calling write() as a result. Confirmed via instrumentation: write()
    // call count froze completely (not just slowed) the moment this desync crossed zero.
    @Volatile private var framesOutput = 0L

    private var isoHandle = 0L
    private var streamThread: Thread? = null
    @Volatile private var streaming = false
    @Volatile private var lastUrbSubmittedAtMs = 0L
    // Only ever read/written from the single native streaming thread -- not actually volatile for
    // cross-thread visibility, just convenient to declare alongside the other stream-lifetime state.
    private var underrunCount = 0
    // The isochronous schedule can't actually stop (the USB clock must keep ticking), so "pause"
    // means substituting silence for whatever's next, *without* draining it out of [ringBuffer] --
    // otherwise up to RING_BUFFER_MS of already-decoded audio keeps playing out audibly after
    // pause, since nothing upstream of this class has any other way to silence output early.
    // Confirmed: with no pause handling here at all, hitting pause took up to 5s to actually go
    // quiet, which read as "the pause button doesn't do anything".
    //
    // Starts true, not false: a real AudioTrack stays silent until an explicit play() call even
    // if data's already been written to it, and this must match that, because DefaultAudioSink
    // itself doesn't guarantee play() arrives before this class is even constructed. Confirmed,
    // reproduced: restoring saved playback state with playWhenReady left false (app relaunched,
    // auto-resume off) still has ExoPlayer decode and write real audio ahead into [ringBuffer] to
    // pre-buffer for whenever play() eventually comes -- with this defaulting to false, the
    // streaming thread (which starts immediately in init{}, before any play()/pause() call could
    // possibly have happened yet) played that real buffered audio out audibly despite the player
    // correctly considering itself paused the whole time. The Now Playing UI was actually correct
    // (frozen, showing paused) -- the DAC was what was wrong, playing anyway.
    @Volatile private var paused = true
    // Whether this instance currently holds the USB claim and has a live native stream running.
    // False right after pause() hands the interface back to Android (see pause()'s doc comment);
    // play() re-runs activateAndStream() from scratch when it finds this false, since the clock/
    // mute state can't be assumed to have survived however long Android (or another app) had the
    // device in the meantime.
    @Volatile private var activated = false

    init {
        if (!activateAndStream()) {
            throw AudioOutputProvider.InitializationException()
        }
    }

    /**
     * Claims the interface, negotiates the clock, and starts the native streaming thread --
     * everything needed to go from "not currently using the DAC" to "audibly streaming to it".
     * Run once at construction (see [init]), and again from [play] whenever [pause] has released
     * the claim back to Android in the meantime, since the hardware clock/mute state can't be
     * assumed to have survived that -- another app (or Android's own UsbAlsaManager) may well
     * have used the device for something else entirely while we didn't hold it.
     *
     * Returns false on failure instead of throwing, unlike the exception [init] turns a false
     * return into -- a failure here while resuming from [pause] shouldn't crash playback, it
     * should just leave this instance non-functional; [isStalled] has a dedicated check for
     * exactly that so [androidx.media3.exoplayer.audio.DefaultAudioSink]'s own stall-recovery
     * eventually notices and flushes, same as any other genuine stall.
     */
    private fun activateAndStream(): Boolean {
        if (!usbAudioManager.requestClaim()) {
            L.w("UsbAudioOutput: could not claim USB interface for streaming")
            return false
        }

        // requestClaim() only re-claims the control interface and the streaming interface's
        // alt-0 -- NOT necessarily this instance's actual target alt-setting. Calling this here
        // unconditionally (not just relying on UsbAudioOutputProvider.getAudioOutput() having
        // already called it moments ago, which doesn't happen at all on a pause()-then-play()
        // resume of this *same* instance -- DefaultAudioSink never decides the format "changed"
        // for that, so getAudioOutput() never runs again) guarantees the device is really on
        // [format]'s alt-setting before opening the iso stream below.
        if (!usbAudioManager.ensureFormatActive(format, sampleRateHz)) {
            L.w("UsbAudioOutput: failed to select alt-setting for ${format.bitResolution}-bit")
            return false
        }

        // Mute before touching the clock at all, not after -- a clock-frequency change makes the
        // DAC chip's PLL re-lock to a new rate, and many real DACs produce an audible transient
        // (a "thunk"/pop, the same reason higher-end gear often has an audible relay click on a
        // rate change: it's muting output for exactly this reason) while that happens. The old
        // code only ever checked/corrected mute state *after* the clock change, at the single
        // worst possible time for that check to matter -- it could confirm the device was
        // unmuted, but only after any relock transient had already played out audibly. Left
        // muted until right before startStreamingThread(), once the clock is confirmed locked.
        UsbAudioController.setMute(session, muted = true)

        // UAC 1.0 has no Clock Source entity -- its rate lives on the streaming endpoint itself
        // (see UsbAudioManager.activateDac's comment on the same split). Using
        // setClockFrequency unconditionally here was the actual cause of a real, reproduced
        // infinite playback-error loop against a UAC 1.0 device: every reconfigure failed this
        // control transfer (wrong addressing entirely -- clockSourceId is always -1).
        val clockOk =
            if (session.topology.uacVersion == 1) {
                UsbAudioController.setUac1EndpointSampleRate(session, format.endpointAddress, sampleRateHz)
            } else {
                UsbAudioController.setClockFrequency(session, sampleRateHz)
            }
        if (!clockOk) {
            L.w("UsbAudioOutput: failed to set DAC clock to $sampleRateHz Hz")
            return false
        }

        // See UsbAudioManager.activateDac's comment on wakePowerDomain for why: a clean,
        // 0-error transfer into a UAC3 Power Domain that's still mid-wake (or never woken at
        // all) looks identical, at the USB protocol level, to one going to a fully-powered
        // device. Unlike reassertKernelEviction, this is safe to call again here -- it's a
        // power-state control transfer, not an interface claim, so it can't reset alt-setting.
        val powerDomainId = session.topology.powerDomainId
        if (powerDomainId >= 0) {
            UsbAudioController.wakePowerDomain(
                session,
                powerDomainId,
                session.topology.powerDomainD1RecoveryUs,
                session.topology.powerDomainD2RecoveryUs)
        }

        // Re-apply volume after every clock change: unknown whether reconfiguring the clock
        // resets the Feature Unit's volume state on this hardware, but it's cheap to confirm.
        // Mute itself is deliberately left alone here -- see the setMute(true) call above and
        // the matching setMute(false) right before startStreamingThread() below.
        //
        // Starts at 0 (master), not 1 -- this used to skip the master channel entirely, which
        // was harmless as long as nothing else ever touched it independently of a live
        // UsbAudioManager.setVolume() call (which always included it, via its own 0..channels
        // loop). That stopped being true once UsbAudioController.setUnityVolume() started
        // forcing *every* channel, master included, to unity gain before releasing to HAL (see
        // its doc comment) -- without restoring master here too, it stayed stuck at unity after
        // every HAL->DAC switch, which UsbVolumeProvider.applyRange() then read back as "the
        // current volume", computing every subsequent adjustment from a wrong (maxed) baseline.
        // Confirmed, reproduced: "switch to DAC, press volume down once, volume jumps to max".
        for (channel in 0..channels) {
            val volume = UsbAudioController.getVolume(session, channel)
            // Prefer the user's last explicitly-set volume (persisted in UsbDacPreferences, see
            // UsbAudioManager.setVolume) over a fixed default -- otherwise every activation
            // (app restart, DAC reconnect, new track that triggers a clock change) reset
            // whatever the user had it at back to this fixed point, which is exactly what was
            // reported as "volume resets after reinstall". Only a genuinely first-ever-seen
            // device (initialVolumeDb null) falls back to the midpoint of its own reported range
            // (not its max/unity gain) -- see UsbAudioController.defaultVolumeTarget.
            val target =
                initialVolumeDb
                    ?: UsbAudioController.defaultVolumeTarget(UsbAudioController.getVolumeRange(session, channel))
            L.i("UsbAudioOutput: post-clock-change channel $channel volume=$volume target=$target")
            if (volume == null || volume != target) {
                UsbAudioController.setVolume(session, value = target, channel = channel)
            }
        }

        // See DAC_SETTLE_DELAY_MS: give the clock change just above time to actually lock
        // before the first packet goes out. Deliberately not calling reassertKernelEviction
        // here -- see UsbAudioManager.activateDac's comment on that call: evicting a driver
        // after the alt-setting is already selected resets the interface back to alt 0 as a
        // side effect, which would undo the activation this sink depends on.
        Thread.sleep(DAC_SETTLE_DELAY_MS)

        // Every per-track rate change used to never check this at all -- SET_CUR + a GET_CUR
        // readback both succeeding only proves the device's control-register accepted and
        // stored the new rate; it's a separate, real question whether the device's actual
        // output clock (the PLL feeding its DAC chip) has physically relocked to it yet. A
        // control register that reports the new value immediately while the real analog clock
        // is still running at the *previous* rate would look completely clean here -- 0 USB
        // errors, correct readback -- while every sample actually gets converted at the wrong
        // rate: pitched-up, sped-up playback. Polling a few times (rather than one fixed wait)
        // covers devices whose relock is slower than DAC_SETTLE_DELAY_MS without always paying
        // that cost on devices that lock fast.
        if (session.topology.uacVersion != 1) {
            var valid = UsbAudioController.getClockValid(session)
            var attempt = 0
            while (valid == false && attempt < CLOCK_VALID_MAX_RETRIES) {
                Thread.sleep(DAC_SETTLE_DELAY_MS)
                valid = UsbAudioController.getClockValid(session)
                attempt++
            }
            L.i("UsbAudioOutput: clock valid at ${sampleRateHz}Hz after $attempt extra wait(s): $valid")
        }

        // Only unmute now that the clock is confirmed (or assumed, if unreadable) locked -- see
        // the setMute(true) call above for why this has to bracket the whole relock sequence
        // rather than just being corrected once, after the fact.
        UsbAudioController.setMute(session, muted = false)

        startStreamingThread()
        activated = true
        // Tells UsbAudioManager's Settings-screen mutators (setMuted/setVolume/setMixerGain)
        // not to release the claim out from under this still-running stream just because their
        // own one-off control write finished -- see notifyStreamingStarted's doc comment.
        usbAudioManager.notifyStreamingStarted()
        return true
    }

    override fun play() {
        L.i("UsbAudioOutput: play()")
        paused = false
        if (!activated) {
            // pause() released the claim (or activateAndStream() failed outright last time) --
            // redo the whole clock/mute dance from scratch rather than just restarting the
            // thread, since the hardware clock/mute state can't be assumed to have survived
            // however long we didn't hold the interface.
            if (!activateAndStream()) {
                L.w("UsbAudioOutput: failed to resume streaming -- isStalled() will reflect this")
            }
        }
    }

    override fun pause() {
        // See [paused]: substitutes silence immediately rather than draining real buffered
        // audio out over up to RING_BUFFER_MS.
        L.i("UsbAudioOutput: pause()")
        paused = true
        if (activated) {
            stopStreamingThread()
            activated = false
            usbAudioManager.notifyStreamingStopped()
            // Hand the interface back to Android after a short grace period (see
            // UsbAudioManager.releaseClaim's doc comment) rather than immediately -- a quick
            // pause/resume shouldn't pay the real hand-off cost, but genuinely leaving playback
            // paused should free the device for every other app.
            usbAudioManager.releaseClaim()
        }
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val written = ringBuffer.write(buffer)
        framesWritten += written / bytesPerFrame
        return !buffer.hasRemaining()
    }

    override fun flush() {
        L.i("UsbAudioOutput: flush()")
        ringBuffer.clear()
        framesWritten = 0L
        framesOutput = 0L
    }

    override fun stop() {
        L.i("UsbAudioOutput: stop(), ${ringBuffer.availableToRead()} bytes still buffered")
    }

    override fun release() {
        stopStreamingThread()
        if (activated) {
            activated = false
            usbAudioManager.notifyStreamingStopped()
            // The player is genuinely done with this instance -- release immediately rather
            // than waiting out the usual grace period, which exists only to absorb a quick
            // pause/resume that this is specifically not.
            usbAudioManager.releaseClaim(delayMs = 0)
        }
    }

    override fun setVolume(volume: Float) {
        // Intentionally a no-op. This is DefaultAudioSink re-applying ExoPlayer's own internal
        // Player.volume on essentially every reconfigure (track change, clock change, etc.) --
        // Auxio never actually changes that from its default of 1.0 (unity), so this used to fire
        // on every single reconfigure with the same fixed value, silently overriding whatever
        // hardware volume the user had actually set via the physical volume keys or the Settings
        // screen slider (both go through UsbAudioController.setVolume directly, via
        // UsbAudioManager.setVolume -- see UsbAudioOutput's constructor for the activation-time
        // equivalent of this same problem). Now that real hardware volume control exists, this
        // call has no remaining purpose and would only still exist to fight with it.
        L.i("UsbAudioOutput: setVolume($volume) ignored -- hardware volume is user-controlled")
    }

    override fun isOffloadedPlayback(): Boolean = false

    override fun getAudioSessionId(): Int = C.AUDIO_SESSION_ID_UNSET

    override fun getSampleRate(): Int = sampleRateHz

    override fun getBufferSizeInFrames(): Long =
        ((ringBuffer.availableToWrite() + ringBuffer.availableToRead()) / bytesPerFrame).toLong()

    override fun getPositionUs(): Long = framesOutput * 1_000_000L / sampleRateHz

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {
        // Direct USB output can't resample/pitch-shift on the fly -- nothing to do, the sink
        // already reports PlaybackParameters.DEFAULT back regardless of what's requested.
    }

    /**
     * Whether our own write-side infrastructure has genuinely stopped, not just hit normal
     * ring-buffer backpressure (which resolves on its own as the streaming thread keeps draining
     * at its paced rate). [DefaultAudioSink][androidx.media3.exoplayer.audio.DefaultAudioSink]'s
     * `handleBuffer()` calls this specifically when [write] would otherwise report "buffer full"
     * forever, and self-recovers (flushes) if so -- exactly the safeguard the old hand-rolled
     * sink implementation here never had, which left a genuinely stuck ring buffer with no way
     * back short of the user manually skipping the track.
     */
    override fun isStalled(): Boolean {
        // Not paused, but activateAndStream() never got the claim/clock lock back after the last
        // pause() released it -- nothing will ever make lastUrbSubmittedAtMs move on its own in
        // this state (streaming itself is false), so the check below alone would never catch it.
        if (!paused && !activated) return true
        return streaming && (System.currentTimeMillis() - lastUrbSubmittedAtMs) > STALL_THRESHOLD_MS
    }

    override fun addListener(listener: AudioOutput.Listener) {}

    override fun removeListener(listener: AudioOutput.Listener) {}

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}

    override fun setOffloadEndOfStream() {}

    override fun attachAuxEffect(effectId: Int) {}

    override fun setAuxEffectSendLevel(level: Float) {}

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {}

    private fun startStreamingThread() {
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
        // BASE_PACKETS_PER_URB's doc comment) so the *real* amount of audio -- and host-controller
        // lookahead cushion -- one URB represents stays the same regardless of negotiated speed;
        // only each packet's own content size shrinks to match its true, shorter duration.
        val usbSpeed = UsbNative.getUsbSpeed(fd)
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
        val packetsPerSecond = if (usbSpeed == 3) 8000 else 1000
        val packetDurationNs = 1_000_000_000L / packetsPerSecond
        val packetsPerUrb = BASE_PACKETS_PER_URB * (packetsPerSecond / 1000)

        val handle =
            UsbNative.openIsoStream(fd, format.endpointAddress, format.maxPacketSize, packetsPerUrb, NUM_URBS)
        if (handle == 0L) {
            L.w("UsbAudioOutput: failed to open iso stream")
            return
        }
        L.i(
            "UsbAudioOutput: opened iso stream fd=$fd endpoint=${format.endpointAddress} " +
                "maxPacketSize=${format.maxPacketSize} handle=$handle usbSpeed=$speedName " +
                "(pacing $packetsPerSecond packets/sec, ${packetDurationNs / 1000}us/packet, " +
                "$packetsPerUrb packets/urb)")
        isoHandle = handle
        val scheduler = IsoPacketScheduler(sampleRateHz, packetsPerSecond)
        val frameBytes = bytesPerFrame

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

        /** Fills [urbBuffer]/[packetLengths] in place; returns how many bytes of [urbBuffer] are used. */
        fun buildUrb(): Int {
            var total = 0
            for (p in 0 until packetsPerUrb) {
                val bytes = scheduler.nextPacketSampleCount() * frameBytes
                packetLengths[p] = bytes
                total += bytes
            }
            if (!paused) {
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
                            "UsbAudioOutput: ring buffer underrun #$underrunCount -- wanted $total " +
                                "bytes, had $read (short by ${total - read})")
                    }
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
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                    L.i("UsbAudioOutput: streaming thread starting ($sampleRateHz Hz)")
                    for (slot in 0 until NUM_URBS) {
                        val total = buildUrb()
                        val ok = UsbNative.submitIso(handle, slot, urbBuffer, packetLengths)
                        if (!ok) {
                            L.w("UsbAudioOutput: initial submitIso failed for slot $slot")
                        } else {
                            framesOutput += total / frameBytes
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
                        val slot = UsbNative.reapIso(handle)
                        if (!streaming) break
                        if (slot < 0) {
                            reapErrors++
                            continue
                        }

                        urbsPaced++
                        val waitNs = (streamStartNs + urbsPaced * urbRealTimeNs) - System.nanoTime()
                        if (waitNs > 0) {
                            Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                        }

                        val total = buildUrb()
                        if (UsbNative.submitIso(handle, slot, urbBuffer, packetLengths)) {
                            submitted++
                            lastUrbSubmittedAtMs = System.currentTimeMillis()
                            // Not while paused: this is synthetic silence standing in for the USB
                            // clock's unstoppable schedule, not real consumed track audio -- see
                            // [paused]'s doc comment. Advancing it here would let getPositionUs()
                            // catch up to framesWritten mid-pause and re-trigger the exact
                            // premature-isEnded() stall this counter exists to prevent.
                            if (!paused) {
                                framesOutput += total / frameBytes
                            }
                            if (submitted % 500 == 0) {
                                val realElapsedMs = (System.nanoTime() - streamStartNs) / 1_000_000L
                                val pacedMs = urbsPaced * urbRealTimeNs / 1_000_000L
                                L.i(
                                    "UsbAudioOutput: $submitted URBs submitted, $reapErrors reap " +
                                        "errors, ${ringBuffer.availableToRead()} bytes buffered, " +
                                        "${pacedMs}ms of audio paced over ${realElapsedMs}ms real time")
                            }
                        }
                    }
                    L.i("UsbAudioOutput: streaming thread exiting ($submitted submitted, $reapErrors reap errors)")
                }
                .apply {
                    isDaemon = true
                    name = "UsbAudioOutput-stream"
                    start()
                }
    }

    private fun stopStreamingThread() {
        if (!streaming && isoHandle == 0L) return
        streaming = false
        if (isoHandle != 0L) {
            UsbNative.cancelIso(isoHandle)
        }
        streamThread?.join(2000)
        streamThread = null
        if (isoHandle != 0L) {
            UsbNative.closeIsoStream(isoHandle)
            isoHandle = 0L
        }
    }
}
