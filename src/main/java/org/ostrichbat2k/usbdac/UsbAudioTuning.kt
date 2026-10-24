/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioTuning.kt is part of libusbdac.
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

/**
 * Every tuning/config knob used across the library's components, in one place rather than one
 * private constant per class -- timing margins, retry counts, buffer sizing, and the couple of
 * fixed protocol strings that aren't part of the USB Audio Class spec itself (those stay next to
 * the descriptor-parsing/control-transfer code that interprets them, in
 * [UsbAudioDescriptorHelper], the UAC1/2/3 parsers, and [UsbAudioController]). Several of these
 * were independently tuned against the same real hardware over time (see each one's own doc
 * comment for its actual history) and are easiest to find, compare, and re-tune together here.
 */
internal object UsbAudioTuning {
    /**
     * Default grace period before [UsbAudioManager.releaseClaim] actually hands the interface
     * back to the kernel -- long enough that a quick pause/resume never pays the real hand-off
     * cost, short enough that genuinely idle playback frees the device for every other app
     * promptly.
     */
    const val RELEASE_GRACE_MS = 3000L

    /**
     * How long [UsbClaimControllerImpl.awaitAndroidOutputTakeover] will wait before giving up --
     * confirmed via logcat that real recognition landed in ~100-300ms on the one device tested,
     * so this is a generous multiple of that, not a tight bound.
     */
    const val AWAIT_TAKEOVER_TIMEOUT_MS = 1500L

    /**
     * How long to wait after a clock-frequency or alternate-setting change before streaming to
     * this device: both were observed to sometimes complete (readback confirms the new rate, iso
     * transfers report zero errors) before the DAC's own clock/output stage has actually locked
     * onto it, producing clean, error-free, but silent transfers for however long it stays
     * unlocked.
     *
     * Was 300ms; bumped after reproducing audible distortion at the very start of a track whose
     * rate differs from the previous one (e.g. 44.1kHz <-> 96kHz) despite
     * [UsbAudioController.getClockValid] reporting locked immediately (0 retries) every time --
     * consistent with this device's CLOCK_VALID control register not being a reliable signal of
     * real analog PLL settling (a clean, zero-error transfer doesn't guarantee the clock has
     * actually locked), so a bigger fixed margin is the only lever available for it specifically.
     */
    const val DAC_SETTLE_DELAY_MS = 600L

    /**
     * The real sample rate this library asks every UAC2/3 device's Clock Source for, and the
     * fallback used whenever a UAC1 device's own declared rates don't include it or 44.1kHz.
     * Also [UsbAudioManagerImpl]'s own `activeRateHz` fallback default for display when nothing
     * is active.
     */
    const val SUPPORTED_SAMPLE_RATE_HZ = 48000

    /** How many most-recent lines [UsbDiagnosticLog] keeps before evicting the oldest. */
    const val LOG_CAPACITY = 50

    // AudioManager.VOLUME_CHANGED_ACTION/EXTRA_VOLUME_STREAM_TYPE: long-standing, widely used
    // broadcast and extra, but not actually part of the public SDK stub (hence not referenceable
    // as AudioManager.* from UsbAudioManagerImpl) -- a protected system broadcast, so
    // registering for it with RECEIVER_NOT_EXPORTED (see UsbAudioManagerImpl.start()) still
    // receives it normally.
    const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    /**
     * The target real URB duration at full-speed (1 packet == 1ms): 8 packets == 8ms. Scaled up
     * at runtime for a high-speed device (1 packet == 125us instead) so the *real* amount of
     * audio buffered per URB -- and therefore the host-controller lookahead cushion [NUM_URBS]
     * of them provide against scheduling jitter -- stays the same regardless of negotiated
     * speed; only each individual packet's content size (computed from the true packet duration,
     * not assumed) changes. See [UsbAudioOutput]'s `startStreamingThread`'s
     * packetsPerUrb/packetsPerSecond.
     */
    const val BASE_PACKETS_PER_URB = 8
    const val NUM_URBS = 4

    /**
     * How much audio (in ms) the native ring buffer between the decoder and the isochronous
     * streaming thread can hold -- see [UsbAudioOutput]'s own `PcmRingBuffer` sizing and the
     * stall/early-ended-detection comments at its usage sites for the tradeoffs of this exact
     * value.
     */
    const val RING_BUFFER_MS = 5000

    /**
     * How many extra [DAC_SETTLE_DELAY_MS] waits to give a per-track clock change before giving
     * up on CLOCK_VALID ever reporting true and streaming anyway (better than hanging forever on
     * a device that simply doesn't implement this control, which
     * [UsbAudioController.getClockValid] already treats as a read failure, same as "invalid").
     */
    const val CLOCK_VALID_MAX_RETRIES = 3

    /**
     * SET_CUR clock frequency is a single control transfer right after claiming the
     * interface/selecting the alt-setting -- confirmed, reproduced on real hardware: it can fail
     * outright (result=-1, not just a readback mismatch) if the device isn't quite ready yet
     * immediately after that claim, with no way to tell apart from a genuine failure except
     * trying again shortly after. Unlike [CLOCK_VALID_MAX_RETRIES] (which retries reading back a
     * clock that's already been successfully set), this retries the SET_CUR itself. Confirmed,
     * reproduced: the very first track of a cold DAC session can hit this and throw out of
     * `activateAndStream()` with no retry at all, which ExoPlayer surfaces as a playback error
     * and nothing substitutes audible output for -- the user has to force a reconfigure (e.g.
     * switching tracks) before it's retried at all.
     */
    const val CLOCK_SET_MAX_RETRIES = 3

    /**
     * `isStalled()` fires if the native streaming thread hasn't managed to submit a single URB
     * in this long -- i.e. our own write-side infrastructure has stopped entirely (crashed
     * thread, native-level hang), not just normal ring-buffer backpressure, which resolves on
     * its own as the thread keeps draining at its paced ~8ms-of-real-audio-per-URB rate.
     */
    const val STALL_THRESHOLD_MS = 1000L

    /**
     * How many trailing frames of real audio get ramped down to silence instead of cut dead when
     * a URB comes up short -- see [UsbAudioOutput]'s own `buildUrb`'s `fadeTailToSilence` doc
     * comment. Small enough to be inaudible as an added gap at any real sample rate (well under
     * 1ms even at 44.1kHz) but enough samples to smooth over the discontinuity a hard cut
     * produces.
     */
    const val UNDERRUN_FADE_FRAMES = 32
}
