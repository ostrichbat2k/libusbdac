/*
 * Copyright (c) 2026 libusbdac Project
 * RoutingAudioSink.kt is part of libusbdac.
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

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import org.ostrichbat2k.usbdac.api.UsbAudioManager

/**
 * Picks between a USB-direct sink (via [usbAudioManager]) and [fallback] the first time
 * [configure] is called, rather than when the renderer (and this sink) is constructed.
 *
 * [UsbAudioManager] activates its DAC session asynchronously, off the main thread, as soon as USB
 * permission is granted -- but permission-grant latency itself is unpredictable (observed
 * anywhere from ~100ms to 3+ seconds), and the player is typically constructed well before that
 * resolves. Checking at construction time means losing the race whenever that happens. Checking
 * instead at the first real `configure()` call -- which only happens once the user has navigated
 * the UI and started playback -- gives the background activation a realistic chance to finish
 * first, since that step itself is fast (under a second) once permission actually lands.
 *
 * [supportsFormat]/[getFormatSupport] must NOT commit to a delegate via [resolveForConfigure] --
 * ExoPlayer calls these during track/format selection, which happens *before* [configure], and
 * this genuinely did lose the race there even with the above fix: the sink got locked to the
 * fallback the moment a format-support query ran before USB activation finished. They call
 * [UsbAudioManager.resolveActiveSink] with `trigger = false` instead, which checks USB
 * availability fresh every call without ever caching, and -- unlike [resolveForConfigure]'s own
 * call -- never itself provokes an activation attempt (see that parameter's own doc comment for
 * why these specific callers must not).
 *
 * Even with that fixed, real playback was still observed going to the fallback sink indefinitely:
 * the very *first* [configure] call for a freshly-launched player -- e.g. auto-resuming saved
 * playback state right at app launch, well before USB permission has even been granted yet, let
 * alone the DAC activated -- has every chance of losing this race too, and unlike the
 * supportsFormat/getFormatSupport case there's no way around resolving *something* real inside
 * configure() (the chosen sink actually needs configuring). The fix is to not treat the fallback
 * as a permanent choice: [resolveForConfigure] re-checks USB availability on *every* call for as
 * long as the current delegate is still the fallback, so losing the race on track 1 just means
 * falling back to the speaker for that one track -- the next [configure] call (next track, or a
 * seek/format change on the same one) gets another chance, instead of being stuck on the
 * fallback for the rest of the player's lifetime.
 *
 * Once actually upgraded to a USB sink, [resolveForConfigure] still re-validates it's the same
 * instance [usbAudioManager] currently considers live on every call -- unplugging the DAC
 * mid-stream (or swapping it for a different one) makes [UsbAudioManager.resolveActiveSink] start
 * returning null or a new instance, and without this check every subsequent [configure] kept
 * reusing the old, now-dead session's sink forever: every call failed identically (a closed
 * [android.hardware.usb.UsbDeviceConnection] can't issue control transfers), which fed into a
 * host app's own unconditional retry-next-track error-recovery loop (observed in Auxio's
 * `ExoPlaybackStateHolder.onPlayerError`, which has no backoff) -- a real, reproduced infinite
 * error loop, not a hypothetical. [reset] still clears the delegate outright for other cases
 * (e.g. a hard seek).
 *
 * [resolveForConfigure] and [current] both pass their own [isPlaying] as
 * [UsbAudioManager.resolveActiveSink]'s `trigger` -- confirmed, reproduced: a cold app launch's
 * paused `RestoreState` prepare reached all the way into [UsbAudioManager]'s activation (which
 * evicts whatever else currently holds the device) despite `playWhenReady` being false and no
 * [play] ever having been called, purely because preparing a track runs the renderer pipeline far
 * enough to call [configure] regardless of play intent. Gating the trigger on [isPlaying] instead
 * of on [configure]/[current] being called at all means a paused prepare only ever *looks* at
 * whatever's already active (an existing session from earlier this process, or nothing) without
 * provoking a fresh one, while ongoing fallback playback (`isPlaying` already true) still keeps
 * retrying on every call exactly as before -- e.g. noticing the DAC becoming available mid-stream.
 */
class RoutingAudioSink(private val usbAudioManager: UsbAudioManager, private val fallback: AudioSink) :
    AudioSink {
    private var delegate: AudioSink? = null
    private var pendingListener: AudioSink.Listener? = null
    // Whether ExoPlayer currently considers this sink "playing" -- set by play()/pause(), the
    // only two calls it makes to tell us that. Needed because ExoPlayer calls play() once per
    // play *intent*, not again every time configure() causes this class to internally swap which
    // delegate it's forwarding to for a new track -- it has no idea that happened at all, since
    // it only ever sees this outer RoutingAudioSink. Confirmed, reproduced: a track transition
    // that switched delegates while already playing (no real pause()/play() pair from
    // ExoPlayer's side, just an ordinary queue advance) left the newly-constructed
    // UsbAudioOutput sitting in its default paused=true state forever -- its native thread ran
    // and kept submitting (silent) URBs just fine, so nothing in the USB layer ever looked
    // broken, but position never advanced (paused output deliberately doesn't advance
    // UsbAudioOutput.framesOutput -- see its own doc comment), eventually tripping Media3's
    // StuckPlayerDetector after 10s of no progress and skipping the track via onPlayerError.
    @Volatile private var isPlaying = false
    // The most recent configure() call's format/bufferSize/outputChannels -- current()'s own
    // liveness self-heal below needs the format to report a correct PlaybackRoute.Fallback (real
    // bit depth/sample rate/channels, not placeholder values), and needs all three to actually
    // configure() fallback itself with before routing calls to it (see current()'s doc comment on
    // why that configure() call, not just the switch, is required).
    private var lastConfiguredFormat: Format? = null
    private var lastConfiguredBufferSize: Int = 0
    private var lastConfiguredOutputChannels: IntArray? = null

    /**
     * Whatever's already been decided, defaulting to [fallback] without caching that default --
     * except when the current delegate is a USB sink whose session has since died (e.g. the
     * device was physically unplugged) and nothing has called [configure] again yet to notice.
     * [configure] only re-runs around a real track/format change, not a plain [pause]/[play]
     * resume of an already-configured track -- confirmed, reproduced: resuming playback right
     * after an unplug called [play] (and, continuously, [handleBuffer]) directly on the dead
     * delegate, which tried to rebuild its closed USB connection and crashed the renderer with an
     * [androidx.media3.exoplayer.audio.AudioSink.InitializationException] instead of silently
     * falling back. Checking liveness here, in the one place every [AudioSink] method in this
     * class already goes through, fixes it for all of them at once instead of one at a time --
     * and only costs anything when [delegate] is actually a USB sink in the first place: ongoing
     * HAL playback (the overwhelming majority of calls into this, including every single
     * [handleBuffer]) skips straight past the check below.
     *
     * Calls [fallback]'s own `configure()` here, with the last real [configure] call's cached
     * parameters, before ever routing a call to it -- confirmed, reproduced: a track that had
     * gone straight to USB from its very first [configure] call (so [fallback] had never once been
     * configured for it) crashed differently once this self-heal switched [delegate] to it without
     * this -- not the graceful [androidx.media3.exoplayer.audio.AudioSink.InitializationException]
     * above, but a raw `NullPointerException` deep inside Media3's own
     * `DefaultAudioSink.buildAudioOutputWithRetry`, since it assumes `configure()` always precedes
     * `handleBuffer()`. Only runs once per dead-delegate detection -- [delegate] is null on every
     * subsequent [current] call until the next real [configure], so this doesn't re-configure
     * [fallback] on every single later [handleBuffer].
     *
     * Also reports the fallback route here, same as [configure] would -- confirmed, reproduced:
     * without this, audio correctly recovered onto [fallback] but the Now Playing screen's status
     * row kept showing the dead USB route forever, since nothing else that runs between here and
     * the next real [configure] call (next track) ever touches [UsbAudioManager.playbackRoute] at
     * all.
     */
    private fun current(): AudioSink {
        val existing = delegate
        if (existing != null && existing !== fallback && existing !== usbAudioManager.resolveActiveSink(trigger = isPlaying)) {
            delegate = null
            pendingListener?.let(fallback::setListener)
            lastConfiguredFormat?.let {
                fallback.configure(it, lastConfiguredBufferSize, lastConfiguredOutputChannels)
                usbAudioManager.reportPlaybackRoute(fallbackRoute(it))
            }
            return fallback
        }
        return existing ?: fallback
    }

    // A live USB session doesn't mean this *specific* format can actually go out over it -- e.g.
    // a device whose Clock Source only declares 44100/48000 can't take a 96kHz track at all.
    // Must check per-format, not just session liveness, or every track gets stuck on whichever
    // delegate resolveActiveSink() handed back regardless of whether it can actually play it.
    private fun AudioSink.reallySupports(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    private fun resolveForConfigure(format: Format): AudioSink {
        val existing = delegate
        val liveUsbSink = usbAudioManager.resolveActiveSink(trigger = isPlaying)
        val chosen =
            if (existing != null &&
                existing !== fallback &&
                existing === liveUsbSink &&
                existing.reallySupports(format)) {
                // Already upgraded to USB, that session is still the one UsbAudioManager
                // considers live, and it can actually play this track's format -- never go back
                // down to fallback on our own initiative.
                existing
            } else {
                // Either never upgraded, `existing` is a USB sink whose session has since died
                // (device unplugged mid-stream: UsbAudioManager.deactivateDac() cleared
                // activeSink, so resolveActiveSink() now returns null or a *different* instance
                // for a newly activated device), or the live session just can't take this specific
                // format (e.g. a 96kHz track against a 44.1/48kHz-only device) -- route that one
                // track to fallback without treating it as a session death, so the next
                // configure() (next track) gets a fresh look instead of being stuck on either
                // delegate for a reason that no longer applies. Continuing to use a dead
                // session's sink was observed spinning configure() into a permanent error loop --
                // every call failing identically with no way out, since nothing else here ever
                // re-checks liveness once "upgraded". Falling through here re-resolves against
                // what's actually live and actually usable right now, same as a fresh player
                // would.
                liveUsbSink?.takeIf { it.reallySupports(format) } ?: fallback
            }
        if (chosen !== existing) {
            if (existing != null && existing !== fallback) {
                // Abandoning a live USB delegate -- without this, its UsbAudioOutput keeps its
                // native thread running and the USB interface claimed forever, since nothing
                // else here ever calls pause()/release() on a delegate this class has simply
                // stopped forwarding calls to. pause() is exactly the hook that already hands
                // the claim back to Android after a grace period (see UsbAudioOutput.pause()),
                // so reusing it here (rather than a one-off release-only path) is deliberate --
                // the same session's cached USB sink can come right back for a later compatible
                // track, same as resuming from a real user pause.
                existing.pause()
            }
            pendingListener?.let(chosen::setListener)
            // A real hand-off is happening (not just re-confirming the same delegate) -- let the
            // Now Playing screen show that something's in progress rather than looking frozen
            // while configure() below does its (possibly slow: reclaim + clock relock) work.
            usbAudioManager.reportPlaybackRoute(UsbAudioManager.PlaybackRoute.Switching)
        }
        if (chosen === fallback) {
            // Force this synchronously, right here, rather than trusting that some earlier
            // release already landed -- Android's AudioPolicyManager picks an AudioTrack's
            // output device essentially instantly when fallback.configure() below opens it, and
            // never revisits that choice later. A background release that lands even ~30ms after
            // that point is already too late -- the track is stuck on the speaker for its entire
            // lifetime, having missed its one chance to see the DAC as an available route. This
            // call is cheap and idempotent (a no-op if already released), so paying it on every
            // fallback configure() is fine. awaitAndroidTakeover blocks (briefly, bounded) until
            // Android's own device list actually reports the USB device as available -- the
            // kernel-side decision is synchronous (see UsbAudioController.releaseToKernel's doc
            // comment) but Android's own userspace recognition of it still has real latency.
            // deactivateOnTimeout=true: this track genuinely needs HAL to reach the device right
            // now, so on timeout the session should fully deactivate rather than reclaim the
            // interface back to us -- see UsbAudioManager.releaseClaim's own doc comment.
            usbAudioManager.releaseClaim(delayMs = 0, awaitAndroidTakeover = true, deactivateOnTimeout = true)
        }
        delegate = chosen
        return chosen
    }

    private fun bitDepthFor(pcmEncoding: Int): Int? =
        when (pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            // Covers anything else -- no real device bit depth to report in that case.
            else -> null
        }

    /**
     * Reports the *file's* own decoded format, same as [PlaybackRoute.Direct] does -- not what's
     * actually audible for a fallback track, which is always HAL's own fixed shared-mixer rate
     * (see [UsbAudioManager.halOutputSampleRate]) regardless of the file. Deliberately so: the Now
     * Playing screen shows this alongside an explicit "not honored" label, specifically so a user
     * can see what the file *is* next to the fact that HAL isn't honoring it, instead of the field
     * just going blank.
     */
    private fun fallbackRoute(inputFormat: Format): UsbAudioManager.PlaybackRoute.Fallback =
        UsbAudioManager.PlaybackRoute.Fallback(
            bitDepth = bitDepthFor(inputFormat.pcmEncoding),
            sampleRateHz = inputFormat.sampleRate,
            channels = inputFormat.channelCount)

    override fun setListener(listener: AudioSink.Listener) {
        pendingListener = listener
        delegate?.setListener(listener)
    }

    // Same per-format reasoning as resolveForConfigure: a live USB sink reporting a format
    // unsupported must fall through to asking fallback, not be taken as "unsupported, period" --
    // otherwise the renderer sees no sink anywhere that can play e.g. a 96kHz track against a
    // 44.1/48kHz-only DAC, and the track produces no output at all instead of routing to the
    // normal Android audio path.
    override fun supportsFormat(format: Format): Boolean {
        val usbSink = usbAudioManager.resolveActiveSink(trigger = false)
        return (usbSink != null && usbSink.reallySupports(format)) || fallback.supportsFormat(format)
    }

    override fun getFormatSupport(format: Format): Int {
        val usbSink = usbAudioManager.resolveActiveSink(trigger = false)
        val usbSupport = usbSink?.getFormatSupport(format)
        return if (usbSupport != null && usbSupport != AudioSink.SINK_FORMAT_UNSUPPORTED) {
            usbSupport
        } else {
            fallback.getFormatSupport(format)
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = current().getCurrentPositionUs(sourceEnded)

    @Deprecated("Deprecated in AudioSink interface itself; this is still the signature callers use.")
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val previousFormat = lastConfiguredFormat
        lastConfiguredFormat = inputFormat
        lastConfiguredBufferSize = specifiedBufferSize
        lastConfiguredOutputChannels = outputChannels
        // Must run before resolveForConfigure() -- it decides (or clears) this track's hidden
        // ABX assignment, which resolveActiveSink() (called from resolveForConfigure() below)
        // consults in place of the user's own bitPerfectMode toggle while a test is active.
        usbAudioManager.rollAbxTrial()
        val previousDelegate = delegate
        val chosen = resolveForConfigure(inputFormat)
        try {
            chosen.configure(inputFormat, specifiedBufferSize, outputChannels)
        } catch (e: Exception) {
            // Whatever configure() was trying to do (claim the interface, lock the clock) didn't
            // pan out -- don't leave the Now Playing screen stuck on "Switching" forever.
            usbAudioManager.reportPlaybackRoute(fallbackRoute(inputFormat))
            throw e
        }
        // ExoPlayer only calls play() on *this* outer sink once per play intent -- it never
        // learns that resolveForConfigure() just switched which delegate we forward to for this
        // new track, so if we're already supposed to be playing, the new delegate needs an
        // explicit nudge or it just sits in its own default-paused state forever. See
        // isPlaying's doc comment for the concrete failure this fixes.
        if (chosen !== previousDelegate && isPlaying) {
            chosen.play()
        }
        // Reported fresh on every real configure() call, not just when resolveForConfigure()
        // decided to switch delegates -- back-to-back tracks on the same (already-settled)
        // delegate never hit that branch, which would otherwise leave this stuck on whatever was
        // reported for the *previous* format decision (e.g. still Fallback after a 96kHz track if
        // the very next track is a 44.1kHz one the device supports fine).
        usbAudioManager.reportPlaybackRoute(
            if (chosen === fallback) {
                fallbackRoute(inputFormat)
            } else {
                UsbAudioManager.PlaybackRoute.Direct(
                    // controlState.activeFormat is only trustworthy here when this track's
                    // encoding/rate match the previous configure() call: in that case
                    // DefaultAudioSink reuses the existing AudioOutput and never calls
                    // UsbAudioOutputProvider.getAudioOutput() again (see
                    // AudioOutput.canReuseAudioOutput's default contract), so activeFormat still
                    // genuinely describes this track, not a leftover. It also covers the
                    // bit-perfect-via-float-conversion path correctly (where inputFormat.pcmEncoding
                    // is PCM_FLOAT, not a real bit depth at all).
                    //
                    // When the format actually changed (or this is the very first track),
                    // getAudioOutput() -> ensureFormatActive() -- the only place that resolves the
                    // real target format -- hasn't run yet at this point: DefaultAudioSink defers
                    // building/replacing the AudioOutput until the first handleBuffer() call, well
                    // after configure() returns. Confirmed, reproduced: trusting
                    // controlState.activeFormat unconditionally here showed the *previous* track's
                    // bit depth for a moment on any track whose format differs. Reporting null
                    // instead of guessing lets getAudioOutput()'s own authoritative report --
                    // posted moments later once it actually resolves the real target -- be the
                    // first thing the user sees.
                    bitDepth = if (previousFormat != null &&
                            previousFormat.pcmEncoding == inputFormat.pcmEncoding &&
                            previousFormat.sampleRate == inputFormat.sampleRate) {
                        usbAudioManager.controlState.value?.activeFormat?.bitResolution
                    } else {
                        null
                    },
                    sampleRateHz = inputFormat.sampleRate,
                    channels = inputFormat.channelCount)
            })
    }

    override fun play() {
        isPlaying = true
        // current()'s own liveness re-check (see its doc comment) only ever calls into
        // UsbAudioManager when already on a USB delegate -- never from fallback, which is exactly
        // where a paused state-restore's resolveForConfigure() call now deliberately leaves things
        // (see UsbAudioManager.ensureActivated's doc comment). A genuine play press is real intent
        // that current() alone won't surface, so trigger activation explicitly and
        // fire-and-forget here -- UsbAudioManager.triggerActivation's own doc comment covers why
        // this doesn't block on it, and how the eventual upgrade to USB still happens (the same
        // mid-track mechanism as a DAC plugged in during an already-playing track).
        usbAudioManager.triggerActivation()
        current().play()
    }

    override fun handleDiscontinuity() = current().handleDiscontinuity()

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean = current().handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

    override fun playToEndOfStream() = current().playToEndOfStream()

    override fun isEnded(): Boolean = current().isEnded()

    override fun hasPendingData(): Boolean = current().hasPendingData()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) =
        current().setPlaybackParameters(playbackParameters)

    override fun getPlaybackParameters(): PlaybackParameters = current().getPlaybackParameters()

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) =
        current().setSkipSilenceEnabled(skipSilenceEnabled)

    override fun getSkipSilenceEnabled(): Boolean = current().getSkipSilenceEnabled()

    override fun setAudioAttributes(audioAttributes: AudioAttributes) = current().setAudioAttributes(audioAttributes)

    override fun getAudioAttributes(): AudioAttributes? = current().getAudioAttributes()

    override fun setAudioSessionId(audioSessionId: Int) = current().setAudioSessionId(audioSessionId)

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = current().setAuxEffectInfo(auxEffectInfo)

    override fun getAudioTrackBufferSizeUs(): Long = current().audioTrackBufferSizeUs

    override fun enableTunnelingV21() = current().enableTunnelingV21()

    override fun disableTunneling() = current().disableTunneling()

    override fun setVolume(volume: Float) = current().setVolume(volume)

    override fun pause() {
        isPlaying = false
        current().pause()
    }

    override fun flush() = current().flush()

    override fun reset() {
        isPlaying = false
        // Let the next configure() re-evaluate USB availability (e.g. a DAC plugged in or
        // unplugged since the last track) rather than sticking with whatever was chosen before.
        delegate?.reset()
        delegate = null
    }

    override fun release() {
        isPlaying = false
        delegate?.release()
        delegate = null
    }
}
