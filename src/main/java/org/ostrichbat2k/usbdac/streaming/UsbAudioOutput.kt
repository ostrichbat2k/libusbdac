/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioOutput.kt is part of libusbdac.
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

import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.UsbAudioTuning
import org.ostrichbat2k.usbdac.UsbDacPreferences
import org.ostrichbat2k.usbdac.control.UsbVolumeController
import org.ostrichbat2k.usbdac.control.UsbVolumeSafetyException
import org.ostrichbat2k.usbdac.protocol.SyncType
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioController
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession
import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import timber.log.Timber as L

/**
 * An [AudioOutput] that streams PCM directly to a UAC1/2/3 device's isochronous OUT endpoint via
 * raw USBDEVFS URBs (see [UsbNative]), bypassing AudioTrack and Android's mixer entirely.
 *
 * One instance is constructed per sample rate (see [UsbAudioOutputProvider.getAudioOutput]), but
 * the one-time-per-rate setup (claiming the interface, clock negotiation, power domain wake,
 * mute/volume, settle delay) is deliberately NOT done in the constructor -- see [activateAndStream]
 * and [play]'s doc comments for why it waits for a real [play] call instead. Everything else about
 * this class (one instance per rate) still mirrors [AudioOutput.canReuseAudioOutput]'s default
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
    // Read fresh (not captured once) by restoreVolumeAfterRelock -- see that function's own doc
    // comment for the real, confirmed bug a one-time capture caused: a volume-key change made
    // mid-track, with this same instance still alive (no format change), was silently reverted
    // to whatever was persisted back when this instance was first built.
    private val usbSettings: UsbDacPreferences,
    // How write()'s buffer needs to be converted before landing in ringBuffer as format's own
    // real integer PCM -- see write()'s own doc comment for what each one actually does and why
    // UsbAudioOutputProvider picked it.
    private val inputConversion: InputConversion = InputConversion.NONE,
    // So pause()/play() can hand the raw interface back to Android's own USB-audio HAL whenever
    // this instance genuinely stops being used, and reclaim it when actually needed again --
    // see activateAndStream()/pause()'s doc comments.
    private val usbAudioManager: UsbAudioManager,
) : AudioOutput {

    /** What [write]'s `buffer` holds, and therefore how it needs to be converted -- see [write]'s own doc comment. */
    enum class InputConversion {
        /** `buffer` already holds exactly [format]'s own encoding -- plain byte copy. */
        NONE,

        /**
         * `buffer` holds 32-bit float PCM -- the only way wide (>16-bit) content could reach this
         * class before #67's fix, since `DefaultAudioSink` used to unconditionally force any
         * wider-than-16-bit input through a float round-trip before an `AudioOutputProvider` was
         * ever consulted.
         */
        FLOAT_TO_INT,

        /**
         * `buffer` holds real, [format]-native-depth content left-shift-padded into a wider
         * 4-byte-per-sample container (FFmpeg's `OUTPUT_MODE_NATIVE_INTEGER`'s only output
         * container wider than 16-bit, `AV_SAMPLE_FMT_S32`, has no native 24-bit option -- see
         * `UsbAudioOutputProvider.realBitsPerSample`'s doc comment) -- [format] here is always the
         * device's own narrower real alt-setting (e.g. 24-bit), chosen specifically to avoid
         * wasting ~33% of USB bandwidth on padding bytes that carry no real information (#67).
         */
        DROP_PADDING_BYTE,
    }

    private val bytesPerFrame = channels * format.subslotSize
    // Computed in Long first -- confirmed, reproduced: at 32-bit/2ch/96000Hz (the float-decode
    // conversion target now reachable via UsbAudioOutputProvider.wideIntTargetFormat preferring
    // 32-bit over 24-bit), bytesPerFrame(8) * sampleRateHz(96000) * UsbAudioTuning.RING_BUFFER_MS(5000) is
    // 3.84 billion, which overflows Int (max ~2.147 billion) to a negative value before the
    // /1000 ever runs, crashing PcmRingBuffer's ByteArray allocation with
    // NegativeArraySizeException. Every format this device can actually declare still fits
    // comfortably in an Int once divided down to UsbAudioTuning.RING_BUFFER_MS worth of bytes.
    private var ringBuffer =
        PcmRingBuffer((bytesPerFrame.toLong() * sampleRateHz * UsbAudioTuning.RING_BUFFER_MS / 1000).toInt())
    private var framesWritten = 0L
    // Only used for the matching InputConversion mode -- reused across write() calls the same
    // way UsbAudioOutput's own URB buffers are, to avoid allocating on every single call. Grown
    // (never shrunk) to fit the largest buffer ExoPlayer has handed write() so far.
    private var floatConversionBuffer = ByteArray(0)
    private var paddingDropBuffer = ByteArray(0)

    // Owns the native handle(s), the URB submit/reap loop(s), and frames-output position
    // accounting -- see UsbIsoStreamEngine's own class doc comment for why this is a separate
    // class (#73). `paused` is read fresh on every URB, not captured once here -- see that
    // class's own doc comment for why that has to stay live.
    private val streamEngine =
        UsbIsoStreamEngine(
            session = session,
            format = format,
            channels = channels,
            sampleRateHz = sampleRateHz,
            ringBuffer = ringBuffer,
            paused = { paused },
            reportPipelineStats = usbAudioManager::reportPipelineStats,
        )

    // The isochronous schedule can't actually stop (the USB clock must keep ticking), so "pause"
    // means substituting silence for whatever's next, *without* draining it out of [ringBuffer] --
    // otherwise up to UsbAudioTuning.RING_BUFFER_MS of already-decoded audio keeps playing out audibly after
    // pause, since nothing upstream of this class has any other way to silence output early.
    // Confirmed: with no pause handling here at all, hitting pause took up to 5s to actually go
    // quiet, which read as "the pause button doesn't do anything".
    //
    // Starts true, not false: a real AudioTrack stays silent until an explicit play() call even
    // if data's already been written to it, and this must match that, because DefaultAudioSink
    // itself doesn't guarantee play() arrives before this class is even constructed -- confirmed,
    // reproduced: restoring saved playback state with playWhenReady left false (app relaunched,
    // auto-resume off) still has ExoPlayer decode and write real audio ahead into [ringBuffer] to
    // pre-buffer for whenever play() eventually comes. [activateAndStream] no longer runs at
    // construction at all (see [play]'s doc comment), so the native streaming thread that would
    // actually drain [ringBuffer] doesn't exist yet either in that scenario -- but this still has
    // to start true rather than assuming that's protection enough on its own: the first genuine
    // [play] call sets this false *before* calling [activateAndStream], specifically so whatever
    // got pre-buffered is audible the moment the stream actually starts, not silenced one extra
    // cycle behind it.
    @Volatile private var paused = true
    // Whether this instance currently holds the USB claim and has a live native stream running.
    // False right after pause() hands the interface back to Android (see pause()'s doc comment);
    // play() re-runs activateAndStream() from scratch when it finds this false, since the clock/
    // mute state can't be assumed to have survived however long Android (or another app) had the
    // device in the meantime.
    @Volatile private var activated = false
    // True once this exact instance has completed the full clock/mute/volume relock sequence
    // below at least once -- see activateAndStream()'s ALREADY_CLAIMED branch for why this, not
    // just the claim outcome alone, has to gate the fast path.
    @Volatile private var hasConfiguredHardware = false
    // True from the moment play() hands activateAndStream() off to activationExecutor until that
    // task finishes (success or failure) -- see play()'s own doc comment for why this exists and
    // isStalled()'s doc comment for why it has to suppress that check for exactly this window.
    @Volatile private var activating = false
    // Set by release() so a background activation that's still running when this instance is
    // abandoned (e.g. a track skip landing mid-relock) hands back whatever it (re)claimed instead
    // of starting a stream nothing will ever read from -- see play()'s completion callback.
    @Volatile private var released = false

    // Serializes every place that decides whether to start/stop the native stream together with
    // flipping [activated] -- onActivationAttemptFinished() (on activationExecutor's thread) and
    // pause()/release() (on whatever thread calls them). Without this, a pause()/release() landing
    // in the narrow window between startStreamingThread() actually running and activated being set
    // true reads activated as still false, skips stopStreamingThread() entirely, and leaves the
    // just-started stream running with nothing left to ever stop it (#70). Each of those three call
    // sites does its own check-and-act (start-or-release-claim; stop-and-flip) entirely inside this
    // lock, so they can't interleave.
    private val activationLock = Any()

    // Runs activateAndStream() off the thread that calls play() -- see play()'s own doc comment
    // for why. Single-threaded like UsbControlWriterImpl's volumeExecutor/
    // UsbClaimControllerImpl's claimScheduler, the same pattern elsewhere in this codebase for
    // "blocking USB I/O that a caller shouldn't have to wait on": a second play() call while one
    // is already in flight is a no-op (see the `!activating` guard below), so this never actually
    // queues more than one task at a time in practice.
    private val activationExecutor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "UsbAudioOutput-Activation").apply { isDaemon = true }
        }

    // Deliberately no init{} block here anymore. This used to call activateAndStream()
    // unconditionally at construction -- claiming the raw USB interface and starting the native
    // streaming thread the moment this instance existed, regardless of whether play() had ever
    // been called. UsbAudioOutputProvider.getAudioOutput() (and therefore this constructor) runs
    // whenever DefaultAudioSink decides it needs a new AudioOutput, which includes merely
    // preparing a track -- e.g. opening the Now Playing screen alone was enough to trigger it.
    // paused defaulting to true (see its own doc comment) already stopped that from producing
    // audible garbage, but it still locked the DAC's interface away from Android's kernel driver
    // -- and therefore every other app -- for as long as nothing happened to call pause(), which
    // a bare prepare()-without-play() never does. Confirmed, reproduced: visiting Now Playing was
    // enough to hold the device hostage until a real play()-then-pause() cycle ran pause()'s
    // releaseClaim() -- play() alone never did, since [activated] was already true from this very
    // init block by the time play() ran, so its own `if (!activated)` guard never fired.
    //
    // activated starts false (its declared default) and nothing here changes that anymore, so a
    // brand-new instance is now in exactly the state [pause] already leaves a resumed one in --
    // [play]'s existing `if (!activated) { activateAndStream() }` branch is what actually claims
    // and streams anything, the first time it's genuinely called, not construction. A
    // construction-time activation failure used to surface here as a thrown
    // AudioOutputProvider.InitializationException; now a first-play activation failure surfaces
    // the same graceful way a post-pause resume failure already did -- play() logs a warning and
    // leaves [isStalled] to notice, which DefaultAudioSink's own stall recovery already handles.

    /**
     * Claims the interface and starts the native streaming thread -- everything needed to go from
     * "not currently using the DAC" to "audibly streaming to it". Run from [play] the first time
     * it's genuinely called on a fresh instance, and again every time [pause] has stopped the
     * stream in the meantime.
     *
     * Only actually renegotiates the clock/mute/volume when [UsbAudioManager.requestClaim] reports
     * [UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED] *and* [hasConfiguredHardware] is already true
     * -- i.e. this exact instance itself previously completed a real relock, and the interface
     * claim never lapsed since (see [UsbAudioManager.releaseClaim]'s grace period) -- rather than
     * [UsbAudioManager.ClaimOutcome.RECLAIMED], which means a genuine hand-off happened and
     * anything could have changed.
     *
     * [hasConfiguredHardware] matters separately from the claim outcome: a brand-new instance
     * (a different target format/sample rate, constructed by
     * [UsbAudioOutputProvider.getAudioOutput] for the next track) sees ALREADY_CLAIMED on its
     * very first activation too, simply because that same provider's own preparatory
     * [UsbAudioManager.ensureFormatActive] call already (re)claimed the interface moments earlier
     * in the same call chain -- not because *this* instance ever configured anything. Gating the
     * fast path on the claim outcome alone was confirmed, reproduced, to silently skip the actual
     * clock-frequency SET_CUR on every single per-track rate change once the interface claim
     * stopped lapsing between tracks (i.e. ordinary back-to-back playback): the DAC stayed
     * pinned at whichever rate was last genuinely set, permanently, while [sampleRateHz] and
     * [UsbAudioManager]'s own bookkeeping (`activeRateHz`) kept claiming the new rate was active
     * -- heard as pitched/sped-up playback the moment a track's real rate actually differed from
     * that stuck one, with zero errors anywhere. [pause] always stops the stream, but doesn't
     * necessarily let the *claim* lapse -- so a quick pause/resume of *this* instance within that
     * grace window correctly hits the cheap path, and only a resume after a genuine release, or a
     * fresh instance's first-ever activation, pays the full relock cost.
     *
     * [UsbAudioManager.consumeHardwareReset] additionally gates the fast path: [requestClaim]'s
     * [UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED] only means "`interfaceClaimed` didn't just
     * flip false->true in *this* call" -- it says nothing about whether it flipped true->false
     * and back again in between, via a caller other than this instance. Confirmed, reproduced:
     * [UsbAudioManager.releaseClaim]'s own Android-takeover verification can silently reclaim the
     * interface back to us on a timeout (a device that never gets recognized as an output while
     * our own connection stays open) after already forcing every channel's gain to max for the
     * handoff attempt -- leaving the hardware sitting at that forced level, with [requestClaim]
     * later reporting ALREADY_CLAIMED to this exact resume as if nothing had happened. Without
     * this check, that resume took the fast path below, never restoring the real volume: heard as
     * "pause mid-track, leave it idle a few minutes, resume -> volume jumps to max until the next
     * volume key press." Consumed (read-and-cleared) on every call, not just when [claim] is
     * ALREADY_CLAIMED, so a genuine RECLAIMED hand-off -- which already pays the full relock cost
     * below regardless -- doesn't leave this set and force an unnecessary extra one next time.
     *
     * Returns false on failure instead of throwing -- a failure here, whether on the very first
     * genuine [play] call or a later resume from [pause], shouldn't crash playback, it should
     * just leave this instance non-functional; [isStalled] has a dedicated check for exactly that
     * so [androidx.media3.exoplayer.audio.DefaultAudioSink]'s own stall-recovery eventually
     * notices and flushes, same as any other genuine stall.
     *
     * Deliberately does not itself start the streaming thread or set [activated] -- only that the
     * interface is claimed and the hardware is correctly configured. [play] runs this on
     * [activationExecutor] and decides, once it returns, whether this instance is still even
     * supposed to be playing by then -- see [play]'s own doc comment.
     */
    private fun activateAndStream(): Boolean {
        val claim = usbAudioManager.requestClaim()
        if (claim == UsbAudioManager.ClaimOutcome.FAILED) {
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

        // Always consumed, regardless of claim -- see this function's own doc comment.
        val hardwareWasForcedForHandoff = usbAudioManager.consumeHardwareReset()

        if (claim == UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED &&
            hasConfiguredHardware &&
            !hardwareWasForcedForHandoff) {
            // Nothing else could have touched the device since this same instance last set it up
            // -- requestClaim() never actually let go of the interface (the release grace period
            // canceled before it ever fired), so the clock lock, mute state, and volume are
            // exactly how this instance last left them; ensureFormatActive() above already
            // confirmed the alt-setting itself didn't need reselecting either. Confirmed,
            // reproduced: redoing the full relock/volume/settle/poll dance below unconditionally
            // on *every* resume -- even one that never actually lost the claim -- cost 600ms-2s+
            // of silence every single pause/resume, the exact "real hand-off cost" the release
            // grace period was supposed to be sparing callers from in the first place, but never
            // actually did (the grace period only ever saved the cheap ensureFormatActive()
            // alt-setting reselect, not any of this).
            //
            // The hasConfiguredHardware check is load-bearing, not redundant -- see this
            // function's own doc comment for the real bug it closes (a brand-new instance for a
            // new track also sees ALREADY_CLAIMED, every time, because of its own provider's
            // preparatory ensureFormatActive() call).
            return true
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
        fun trySetClock(): UsbAudioController.ClockWriteOutcome =
            if (session.topology.uacVersion == 1) {
                UsbAudioController.setUac1EndpointSampleRate(session, format.endpointAddress, sampleRateHz)
            } else {
                UsbAudioController.setClockFrequency(session, sampleRateHz)
            }
        var clockResult = trySetClock()
        var clockSetAttempt = 0
        while (clockResult !is UsbAudioController.ClockWriteOutcome.Confirmed &&
            clockSetAttempt < UsbAudioTuning.CLOCK_SET_MAX_RETRIES) {
            Thread.sleep(UsbAudioTuning.DAC_SETTLE_DELAY_MS)
            clockResult = trySetClock()
            clockSetAttempt++
        }
        if (clockResult !is UsbAudioController.ClockWriteOutcome.Confirmed) {
            L.w(
                "UsbAudioOutput: failed to set DAC clock to $sampleRateHz Hz after $clockSetAttempt " +
                    "retry(s): $clockResult")
            return false
        } else if (clockSetAttempt > 0) {
            L.i("UsbAudioOutput: set DAC clock to $sampleRateHz Hz after $clockSetAttempt retry(s)")
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

        // See UsbAudioTuning.DAC_SETTLE_DELAY_MS: give the clock change just above time to actually lock
        // before the first packet goes out. Deliberately not calling reassertKernelEviction
        // here -- see UsbAudioManager.activateDac's comment on that call: evicting a driver
        // after the alt-setting is already selected resets the interface back to alt 0 as a
        // side effect, which would undo the activation this sink depends on.
        Thread.sleep(UsbAudioTuning.DAC_SETTLE_DELAY_MS)

        // Every per-track rate change used to never check this at all -- SET_CUR + a GET_CUR
        // readback both succeeding only proves the device's control-register accepted and
        // stored the new rate; it's a separate, real question whether the device's actual
        // output clock (the PLL feeding its DAC chip) has physically relocked to it yet. A
        // control register that reports the new value immediately while the real analog clock
        // is still running at the *previous* rate would look completely clean here -- 0 USB
        // errors, correct readback -- while every sample actually gets converted at the wrong
        // rate: pitched-up, sped-up playback. Polling a few times (rather than one fixed wait)
        // covers devices whose relock is slower than UsbAudioTuning.DAC_SETTLE_DELAY_MS without always paying
        // that cost on devices that lock fast.
        if (session.topology.uacVersion != 1) {
            var valid = UsbAudioController.getClockValid(session)
            var attempt = 0
            while (valid == false && attempt < UsbAudioTuning.CLOCK_VALID_MAX_RETRIES) {
                Thread.sleep(UsbAudioTuning.DAC_SETTLE_DELAY_MS)
                valid = UsbAudioController.getClockValid(session)
                attempt++
            }
            L.i("UsbAudioOutput: clock valid at ${sampleRateHz}Hz after $attempt extra wait(s): $valid")
        }

        // Re-apply volume after every clock change: unknown whether reconfiguring the clock
        // resets the Feature Unit's volume state on this hardware, but it's cheap to confirm.
        // Mute itself is deliberately left alone here -- see the setMute(true) call above and
        // the matching setMute(false) right below.
        //
        // Runs here, after the settle delay and clock-valid poll above -- not immediately after
        // the clock SET_CUR, where this used to run. Confirmed, reproduced on real hardware: the
        // Feature Unit's own control transfers aren't reliable that soon after a clock change --
        // channel 0 (the first one this loop touches) consistently failed its GET_CUR *and*
        // SET_CUR (result=-1) immediately after the clock change, while channels 1/2 -- tried
        // only a few ms later -- succeeded every time. The old ordering never retried that failed
        // SET_CUR, so channel 0 unmuted moments later still holding its *previous* volume while
        // channels 1/2 held the correct new one -- an audible per-channel level mismatch right at
        // the unmute instant, heard as a crackle, on every clock-rate change that needed the full
        // relock path (e.g. 44.1kHz -> 96kHz mid-playback). Moving this after the device is
        // already confirmed responsive removes the race rather than papering over it with a
        // retry.
        //
        // Starts at 0 (master), not 1 -- this used to skip the master channel entirely, which
        // was harmless as long as nothing else ever touched it independently of a live
        // UsbAudioManager.setVolume() call (which always included it, via its own 0..channels
        // loop). That stopped being true once UsbVolumeController.prepareForHalHandoff() started
        // forcing *every* channel, master included, to unity gain before releasing to HAL (see
        // its doc comment) -- without restoring master here too, it stayed stuck at unity after
        // every HAL->DAC switch, which UsbVolumeProvider.applyRange() then read back as "the
        // current volume", computing every subsequent adjustment from a wrong (maxed) baseline.
        // Confirmed, reproduced: "switch to DAC, press volume down once, volume jumps to max".
        restoreVolumeAfterRelock()

        // Last-instant safety read-back, right before this instance would otherwise unmute and
        // audio would actually reach the device: confirm the master channel's real, live
        // hardware volume -- not just what restoreVolumeAfterRelock() above believes it wrote --
        // is still within UsbDacPreferences.maxVolumePercent's cap. This is deliberately
        // defense-in-depth on top of that write-time clamp (and UsbControlWriterImpl.setVolume's
        // own), not a replacement for it -- it exists to catch a SET_CUR that silently failed on
        // the wire (this function never re-reads after writing) or any future write path that
        // forgets to clamp, since getting this wrong means genuinely loud, unexpected audio
        // reaching whatever's plugged into the DAC. Checking channel 0 (master) alone mirrors
        // this same function's and UsbDacActivatorImpl.activate()'s own precedent for why master
        // specifically matters here (an independent gain stage separate from the per-channel
        // ones). Stays muted and aborts activation entirely rather than ever let this through.
        val masterVolume = UsbAudioController.getVolume(session, channel = 0)
        val masterRange = UsbAudioController.getVolumeRange(session, channel = 0)
        val masterCap = UsbAudioController.capToMaxPercent(masterRange?.second ?: 0, masterRange, usbSettings.maxVolumePercent)
        if (masterVolume != null && masterRange != null && masterVolume > masterCap) {
            L.e(
                "UsbAudioOutput: refusing to stream -- master volume=$masterVolume exceeds the " +
                    "${usbSettings.maxVolumePercent}% safety cap (=$masterCap) of range=$masterRange")
            throw UsbVolumeSafetyException(
                "USB DAC volume ($masterVolume) exceeds the ${usbSettings.maxVolumePercent}% safety limit ($masterCap)")
        }

        // Only unmute now that the clock is confirmed (or assumed, if unreadable) locked, and the
        // volume reapply above has had a settled device to work with -- see the setMute(true)
        // call above for why this has to bracket the whole relock sequence rather than just being
        // corrected once, after the fact.
        UsbAudioController.setMute(session, muted = false)

        hasConfiguredHardware = true
        return true
    }

    /**
     * Restores every channel's Feature Unit volume to [UsbDacPreferences.volumeDb] (or, failing
     * that, the midpoint of the device's own reported range -- see
     * [UsbAudioController.defaultVolumeTarget]) after a clock relock -- pulled out of
     * [activateAndStream] so a test can call it directly without ever reaching
     * [startStreamingThread]'s [UsbNative] reference. Pure extraction, same behavior as before --
     * see [activateAndStream]'s own call site for why this has to start at channel 0 and run
     * after the clock/power-domain settle.
     *
     * internal, not private: lets a test construct a real [UsbAudioOutput] and call this one
     * piece of [activateAndStream] in isolation, same seam pattern as [UsbAudioManagerImpl][
     * org.ostrichbat2k.usbdac.UsbAudioManagerImpl]'s `internal var dacActivator`/`deviceDiscovery`.
     *
     * Reads [usbSettings] (via [UsbDacPreferences.volumeDb]) fresh on every call, not a value
     * captured once at construction -- confirmed, reproduced: capturing it once meant a volume-key
     * change made mid-track (no format change, so no new [UsbAudioOutput] instance -- see
     * [UsbAudioOutputProvider.getAudioOutput]'s own doc comment on when a fresh instance, and so a
     * fresh read, actually happens) was silently discarded and reverted to the stale
     * construction-time value the next time this same instance survived a real release+reclaim
     * resume (e.g. a pause that outlasted the release grace period) and re-ran this function --
     * a second, distinct "volume jumps" mechanism from the channel-0-skipped bug this function's
     * own history is otherwise about. See [org.ostrichbat2k.usbdac.streaming.UsbAudioOutputVolumeRestoreTest]
     * for the test that caught this.
     */
    internal fun restoreVolumeAfterRelock() {
        for (channel in 0..channels) {
            val volume = UsbAudioController.getVolume(session, channel)
            val range = UsbAudioController.getVolumeRange(session, channel)
            // Prefer the user's last explicitly-set volume (persisted in UsbDacPreferences, see
            // UsbAudioManager.setVolume) over a fixed default -- otherwise every activation
            // (app restart, DAC reconnect, new track that triggers a clock change) reset
            // whatever the user had it at back to this fixed point, which is exactly what was
            // reported as "volume resets after reinstall". Only a genuinely first-ever-seen
            // device (volumeDb null) falls back to the midpoint of its own reported range (not
            // its max/unity gain) -- see UsbAudioController.defaultVolumeTarget.
            //
            // Capped against UsbDacPreferences.maxVolumePercent either way -- not just the
            // midpoint-default branch -- so a persisted volumeDb from before the cap existed (or
            // from before it was lowered) can't bypass it on the very next relock/reactivation.
            val target =
                UsbAudioController.capToMaxPercent(
                    usbSettings.volumeDb ?: UsbAudioController.defaultVolumeTarget(range),
                    range,
                    usbSettings.maxVolumePercent)
            L.i("UsbAudioOutput: post-clock-change channel $channel volume=$volume target=$target")
            if (volume == null || volume != target) {
                UsbAudioController.setVolume(session, value = target, channel = channel)
            }
        }
    }

    /**
     * Either this is a fresh instance's very first play() (activated starts false -- see the
     * comment where init{} used to be), or pause() released the claim (or activateAndStream()
     * failed outright last time) -- either way redo the whole clock/mute dance from scratch
     * rather than just starting the thread, since the hardware clock/mute state can't be assumed
     * to be already correct.
     *
     * Runs that dance ([activateAndStream]) on [activationExecutor] rather than inline, so this
     * call returns immediately instead of blocking the caller (ExoPlayer's playback thread) for
     * however long a genuine relock takes -- observed on real hardware as 1.6-2.9s once #28's fix
     * made that relock actually happen whenever it's needed, instead of being silently (and
     * incorrectly) skipped. [paused] still flips synchronously so [write] starts buffering
     * immediately either way.
     *
     * The background task re-checks [paused] (and [released]) right before it would actually
     * start the stream thread, not just at the point [play] was first called -- a [pause] (or
     * [release]) landing anywhere during the relock is otherwise silently overwritten the moment
     * the relock finishes, audibly starting playback again after the caller already stopped it.
     * When that re-check fails, this hands back whatever it just (re)claimed instead of leaving
     * the interface claimed and the hardware configured for a stream that's never going to run.
     * Rapid play/pause/play toggling during a single relock is safe without any explicit
     * cancellation: the `!activating` guard below means at most one task is ever in flight, and
     * that task's own re-check always reflects whichever call was most recent by the time it
     * finishes.
     */
    override fun play() {
        L.i("UsbAudioOutput: play()")
        paused = false
        if (!activated && !activating) {
            activating = true
            activationExecutor.execute {
                // Brackets the whole attempt, not just requestClaim() itself -- see
                // UsbClaimController.beginClaimAttempt's own doc comment for why
                // notifyStreamingStarted() alone (only called below once this succeeds) isn't
                // early enough a guard against a concurrent releaseClaim() mid-relock.
                usbAudioManager.beginClaimAttempt()
                try {
                    val succeeded =
                        try {
                            activateAndStream()
                        } catch (e: UsbVolumeSafetyException) {
                            // See activateAndStream()'s own doc comment on this check -- surfaced
                            // to the host app as a real, user-visible error (not just a log line)
                            // rather than silently falling back to isStalled()'s generic stuck-
                            // player detection, since this is specifically about not streaming
                            // audio the user never had a chance to react to.
                            L.e("UsbAudioOutput: aborting activation -- ${e.message}")
                            usbAudioManager.reportVolumeSafetyViolation()
                            false
                        }
                    onActivationAttemptFinished(succeeded)
                } finally {
                    usbAudioManager.endClaimAttempt()
                    activating = false
                }
            }
        }
    }

    /**
     * Decides what to do once a background [activateAndStream] attempt from [play] finishes --
     * pulled out of [play] itself purely so a test can drive this one decision directly, the same
     * seam pattern [restoreVolumeAfterRelock] already uses to stay reachable without ever
     * touching [UsbNative] (the `succeeded = true, still playing` branch below still does, via
     * [startStreamingThread], so a test exercises the other branches instead).
     *
     * internal, not private: see [restoreVolumeAfterRelock]'s own doc comment for why.
     */
    internal fun onActivationAttemptFinished(succeeded: Boolean) {
        if (!succeeded) {
            L.w("UsbAudioOutput: failed to resume streaming -- isStalled() will reflect this")
            return
        }
        synchronized(activationLock) {
            if (!paused && !released) {
                startStreamingThread()
                activated = true
                // Tells UsbAudioManager's Settings-screen mutators (setMuted/setVolume/
                // setMixerGain) not to release the claim out from under this still-running
                // stream just because their own one-off control write finished -- see
                // notifyStreamingStarted's doc comment.
                usbAudioManager.notifyStreamingStarted()
                usbAudioManager.setDirectPlaybackActive(true)
            } else {
                usbAudioManager.releaseClaim()
            }
        }
    }

    override fun pause() {
        // See [paused]: substitutes silence immediately rather than draining real buffered
        // audio out over up to UsbAudioTuning.RING_BUFFER_MS.
        L.i("UsbAudioOutput: pause()")
        paused = true
        synchronized(activationLock) {
            if (activated) {
                stopStreamingThread()
                activated = false
                usbAudioManager.notifyStreamingStopped()
                usbAudioManager.setDirectPlaybackActive(false)
                // Manual mode (#31): the user has opted out of the courtesy release entirely --
                // the interface stays claimed, hardware state untouched, so a later resume hits
                // the fast ALREADY_CLAIMED && hasConfiguredHardware path above instantly, with no
                // relock needed since nothing forced its state in the meantime. The user decides
                // whether to hand it back via usbAudioManager.setManualClaim(false) instead.
                if (!usbSettings.manualReleaseControl) {
                    // Hand the interface back to Android after a short grace period (see
                    // UsbAudioManager.releaseClaim's doc comment) rather than immediately -- a
                    // quick pause/resume shouldn't pay the real hand-off cost, but genuinely
                    // leaving playback paused should free the device for every other app.
                    usbAudioManager.releaseClaim()
                }
            }
        }
    }

    /**
     * [ringBuffer] (and therefore the native layer feeding the device) only ever holds the
     * target device format's own real integer PCM -- [format]'s `subslotSize` bytes per sample,
     * signed little-endian. For most tracks ExoPlayer's decoder already produces exactly that,
     * so this is a plain byte copy ([InputConversion.NONE] below, unchanged from before float
     * input existed).
     *
     * [InputConversion.FLOAT_TO_INT]: [buffer] instead holds 32-bit float PCM (one 4-byte float
     * per channel sample, nominal range -1.0..1.0, native byte order) -- how wide (>16-bit)
     * content reached this class before `media#6` let a provider accept a literal wide-integer
     * encoding directly: `DefaultAudioSink.configure()` used to unconditionally convert any raw
     * PCM input wider than 16-bit to float before an `AudioOutputProvider` ever saw it, since it
     * never asked the provider first whether it could take the real encoding as-is.
     * `FfmpegAudioDecoder.OUTPUT_MODE_NATIVE_INTEGER` decodes a >16-bit source to exact integer
     * PCM internally so no earlier, *unnecessary* float round-trip happens for a merely-16-bit
     * source -- but once the real source genuinely is wider than 16 bits, this one float stage
     * (ExoPlayer's own standard, exact int32<->float32 conversion) is unavoidable on a device
     * with no direct-wide-integer bypass available, and is a lossless round-trip for real
     * ≤24-bit content either way (float32's mantissa has enough bits to represent it exactly).
     * This is the one place that ever has to convert it back: each float sample is scaled to
     * [format]'s real full-scale integer range and packed down to its actual `subslotSize`, so
     * what lands in [ringBuffer] -- and therefore what the native streaming thread submits -- is
     * genuine integer PCM at the device's own bit depth either way, never raw float bytes.
     *
     * [InputConversion.DROP_PADDING_BYTE]: [buffer] holds real, [format]-native-depth content
     * left-shift-padded into a wider 4-byte container (see the enum's own doc comment) -- dropping
     * the bottom (least-significant, zero) byte of each 4-byte little-endian sample is an exact,
     * lossless recovery of the real value, no scaling or rounding needed, unlike the float path
     * above (#67).
     *
     * Converts (and attempts to buffer) everything [buffer] currently offers in one call rather
     * than clamping up front to how much room [ringBuffer] actually has -- simpler, and this
     * runs on the player's own thread well below the native streaming thread's real-time
     * deadlines, so the extra scratch-buffer work on an occasional over-sized conversion is not
     * time-critical the way anything on that other thread is. [ringBuffer.write] itself still
     * only accepts as much as fits, exactly like the plain integer path below; what doesn't fit
     * stays unconsumed in [buffer] (via the position math below) for DefaultAudioSink to retry
     * on its next call, the same backpressure contract [write] already has for integer input.
     */
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        when (inputConversion) {
            InputConversion.NONE -> {
                val written = ringBuffer.write(buffer)
                framesWritten += written / bytesPerFrame
                return !buffer.hasRemaining()
            }
            InputConversion.DROP_PADDING_BYTE -> return writeDroppingPaddingByte(buffer)
            InputConversion.FLOAT_TO_INT -> {}
        }

        val subslot = format.subslotSize
        val pos = buffer.position()
        val limit = buffer.limit()
        val availableFloats = (limit - pos) / 4
        if (availableFloats == 0) return true

        val neededBytes = availableFloats * subslot
        if (floatConversionBuffer.size < neededBytes) {
            floatConversionBuffer = ByteArray(neededBytes)
        }

        // ByteBuffer.getFloat(index) already reads in whatever order() the buffer is actually
        // in -- there's no need to (and, critically, no correct way to) force a specific order
        // first. An earlier version of this forced LITTLE_ENDIAN before reading when the buffer's
        // real order was BIG_ENDIAN, which doesn't convert anything: it just re-reads the same
        // big-endian bytes as if they were little-endian, byte-swapping every sample into
        // garbage. Reading directly in the buffer's own order is both simpler and correct for
        // either order.
        val maxValue = (1L shl (subslot * 8 - 1)) - 1
        val minValue = -(1L shl (subslot * 8 - 1))
        var outIdx = 0
        for (i in 0 until availableFloats) {
            val floatPos = pos + i * 4
            val raw = buffer.getFloat(floatPos)
            val sample = (raw * (maxValue + 1)).toLong().coerceIn(minValue, maxValue)
            for (b in 0 until subslot) {
                floatConversionBuffer[outIdx + b] = ((sample shr (8 * b)) and 0xFF).toByte()
            }
            outIdx += subslot
        }

        val writtenBytes = ringBuffer.write(ByteBuffer.wrap(floatConversionBuffer, 0, neededBytes))
        // Maps bytes actually accepted by ringBuffer back to how many whole input floats that
        // corresponds to (1 float in -> subslot bytes out, exactly), so only the floats that
        // genuinely made it into the ring buffer are marked consumed -- the rest stay in
        // [buffer] (position left where they start) for a retry on the next write() call,
        // mirroring the plain integer path's existing backpressure contract above.
        val consumedFloats = writtenBytes / subslot
        buffer.position(pos + consumedFloats * 4)
        framesWritten += consumedFloats / channels
        return consumedFloats == availableFloats
    }

    /**
     * [buffer] holds [format]'s real content padded into a 4-byte-per-sample container, real
     * bits at the top, zero-padding at the bottom (little-endian byte 0) -- see
     * [InputConversion.DROP_PADDING_BYTE]'s doc comment. Dropping the bottom
     * `4 - format.subslotSize` bytes of each 4-byte sample recovers the real value exactly: no
     * scaling, rounding, or clamping needed, unlike [write]'s float path.
     */
    private fun writeDroppingPaddingByte(buffer: ByteBuffer): Boolean {
        val subslot = format.subslotSize
        val padding = 4 - subslot
        val pos = buffer.position()
        val limit = buffer.limit()
        val availableSamples = (limit - pos) / 4
        if (availableSamples == 0) return true

        val neededBytes = availableSamples * subslot
        if (paddingDropBuffer.size < neededBytes) {
            paddingDropBuffer = ByteArray(neededBytes)
        }

        var outIdx = 0
        for (i in 0 until availableSamples) {
            val inOffset = pos + i * 4 + padding
            for (b in 0 until subslot) {
                paddingDropBuffer[outIdx + b] = buffer.get(inOffset + b)
            }
            outIdx += subslot
        }

        val writtenBytes = ringBuffer.write(ByteBuffer.wrap(paddingDropBuffer, 0, neededBytes))
        // Same backpressure contract as the plain and float paths above -- only samples that
        // genuinely made it into ringBuffer are marked consumed.
        val consumedSamples = writtenBytes / subslot
        buffer.position(pos + consumedSamples * 4)
        framesWritten += consumedSamples / channels
        return consumedSamples == availableSamples
    }

    override fun flush() {
        L.i("UsbAudioOutput: flush()")
        ringBuffer.clear()
        framesWritten = 0L
        streamEngine.resetPosition()
    }

    override fun stop() {
        L.i("UsbAudioOutput: stop(), ${ringBuffer.availableToRead()} bytes still buffered")
    }

    override fun release() {
        // See play()'s doc comment: lets a background activation still in flight for this
        // instance (a track skip landing mid-relock) hand back its claim instead of starting a
        // stream nothing will ever read from.
        released = true
        synchronized(activationLock) {
            stopStreamingThread()
            if (activated) {
                activated = false
                usbAudioManager.notifyStreamingStopped()
                usbAudioManager.setDirectPlaybackActive(false)
                // The player is genuinely done with this instance -- release immediately rather
                // than waiting out the usual grace period, which exists only to absorb a quick
                // pause/resume that this is specifically not.
                usbAudioManager.releaseClaim(delayMs = 0)
            }
        }
        // Not shutdownNow(): a task already running (an activation this release() landed in the
        // middle of) still needs to finish and see [released] itself -- see play()'s doc comment.
        // shutdown() just stops the (daemon, so harmless either way) thread from idling forever
        // once it does, since one instance is constructed per sample rate and a long session can
        // accumulate many.
        activationExecutor.shutdown()
    }

    override fun setVolume(volume: Float) {
        // Intentionally a no-op. This is DefaultAudioSink re-applying ExoPlayer's own internal
        // Player.volume on essentially every reconfigure (track change, clock change, etc.) -- a
        // host app that never actually changes that from its default of 1.0 (unity) used to see
        // this fire on every single reconfigure with the same fixed value, silently overriding
        // whatever hardware volume the user had actually set via UsbAudioController.setVolume /
        // UsbAudioManager.setVolume (see UsbAudioOutput's constructor for the activation-time
        // equivalent of this same problem). Now that real hardware volume control exists, this
        // call has no remaining purpose and would only still exist to fight with it.
        L.i("UsbAudioOutput: setVolume($volume) ignored -- hardware volume is user-controlled")
    }

    override fun isOffloadedPlayback(): Boolean = false

    override fun getAudioSessionId(): Int = C.AUDIO_SESSION_ID_UNSET

    override fun getSampleRate(): Int = sampleRateHz

    override fun getBufferSizeInFrames(): Long =
        ((ringBuffer.availableToWrite() + ringBuffer.availableToRead()) / bytesPerFrame).toLong()

    // [streamEngine.framesOutput] is frames actually handed to the device via a paced, submitted
    // URB -- i.e. audibly played, not just sitting in [ringBuffer] -- and MUST be what's reported
    // here, not [framesWritten]: DefaultAudioSink.hasAudioOutputPendingData() compares its own
    // written-frame count against audioOutput.getPositionUs() to decide whether there's still
    // unplayed data. Reporting framesWritten there made that comparison always ~equal, so
    // hasPendingData() was false (and isEnded() true) up to UsbAudioTuning.RING_BUFFER_MS early --
    // DefaultAudioSink believed playout had already finished while up to 5s of real audio was
    // still draining out of the ring buffer, and permanently stopped calling write() as a result.
    // Confirmed via instrumentation: write() call count froze completely (not just slowed) the
    // moment this desync crossed zero.
    override fun getPositionUs(): Long = streamEngine.framesOutput * 1_000_000L / sampleRateHz

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
        // play() just handed activateAndStream() off to activationExecutor and hasn't heard back
        // yet -- this is no longer the instantaneous window it used to be when play() ran that
        // dance inline (a genuine relock takes 1.6-2.9s on real hardware), so it has to be
        // excluded here explicitly rather than relying on the check below never having time to
        // see it. If activation goes on to fail, activating clears with activated still false,
        // and the check below catches it on the very next call -- same as before.
        if (activating) return false
        // Not paused, but activateAndStream() never got the claim/clock lock back after the last
        // pause() released it -- nothing will ever make lastUrbSubmittedAtMs move on its own in
        // this state (streaming itself is false), so the check below alone would never catch it.
        if (!paused && !activated) return true
        return streamEngine.streaming &&
            (System.currentTimeMillis() - streamEngine.lastUrbSubmittedAtMs) > UsbAudioTuning.STALL_THRESHOLD_MS
    }

    override fun addListener(listener: AudioOutput.Listener) {}

    override fun removeListener(listener: AudioOutput.Listener) {}

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}

    override fun setOffloadEndOfStream() {}

    override fun attachAuxEffect(effectId: Int) {}

    override fun setAuxEffectSendLevel(level: Float) {}

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {}

    /** See [UsbIsoStreamEngine.start] -- the native handle lifetime/URB loop/position tracking live there (#73). */
    private fun startStreamingThread() = streamEngine.start()

    /** See [UsbIsoStreamEngine.stop] -- the native handle lifetime/URB loop/position tracking live there (#73). */
    private fun stopStreamingThread() = streamEngine.stop()

    /**
     * Whether [thread] is still running after [stopStreamingThread]'s join -- i.e.
     * [UsbNative.cancelIso] failed to unblock it within
     * [UsbAudioTuning.STREAM_THREAD_JOIN_TIMEOUT_MS]. `internal`, not `private`, purely so a test
     * can drive this one decision directly without needing to reference [UsbNative] at all (see
     * [UsbAudioTuning.STREAM_THREAD_JOIN_TIMEOUT_MS]'s doc comment for why this must gate whether
     * [stopStreamingThread] is allowed to free the native stream).
     *
     * Kept here, not forwarded to [UsbIsoStreamEngine]'s own identical private check, so this
     * existing test seam (see [org.ostrichbat2k.usbdac.streaming.UsbAudioOutputVolumeRestoreTest])
     * keeps working exactly as it did before #73's extraction -- this function was never actually
     * part of what moved.
     */
    internal fun isStreamThreadStuck(thread: Thread?): Boolean = thread?.isAlive == true
}
