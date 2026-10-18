/*
 * Copyright (c) 2026 Auxio Project
 * RoutingAudioSink.kt is part of Auxio.
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
 * fallback the moment a format-support query ran before USB activation finished. They use [peek]
 * instead, which checks USB availability fresh every call without ever caching.
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
 * mid-stream (or swapping it for a different one) makes [UsbAudioManager.createAudioSink] start
 * returning null or a new instance, and without this check every subsequent [configure] kept
 * reusing the old, now-dead session's sink forever: every call failed identically (a closed
 * [android.hardware.usb.UsbDeviceConnection] can't issue control transfers), which fed into
 * [org.oxycblt.auxio.playback.service.ExoPlaybackStateHolder.onPlayerError]'s unconditional
 * retry-next-track loop with no backoff -- a real, reproduced infinite error loop, not a
 * hypothetical. [reset] still clears the delegate outright for other cases (e.g. a hard seek).
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

    /** Whatever's already been decided, defaulting to [fallback] without caching that default. */
    private fun current(): AudioSink = delegate ?: fallback

    // A live USB session doesn't mean this *specific* format can actually go out over it -- e.g.
    // a device whose Clock Source only declares 44100/48000 can't take a 96kHz track at all.
    // Must check per-format, not just session liveness, or every track gets stuck on whichever
    // delegate createAudioSink() handed back regardless of whether it can actually play it.
    private fun AudioSink.reallySupports(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    private fun resolveForConfigure(format: Format): AudioSink {
        val existing = delegate
        val liveUsbSink = usbAudioManager.createAudioSink()
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
                // activeSink, so createAudioSink() now returns null or a *different* instance for
                // a newly activated device), or the live session just can't take this specific
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
            usbAudioManager.releaseClaim(delayMs = 0, awaitAndroidTakeover = true)
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
     * What's actually audible for a fallback track: HAL's own fixed shared-mixer rate (see
     * [UsbAudioManager.halOutputSampleRate]), in stereo (the mixer's own fixed channel count,
     * independent of the file's own channel count) -- not the file's own decoded format, which
     * [PlaybackRoute.Fallback]'s doc comment explains isn't what's actually playing. No single
     * real bit depth to report here either (HAL's internal mixing format isn't one fixed,
     * confidently-knowable value the way [PlaybackRoute.Direct]'s raw path is), hence null.
     */
    private fun fallbackRoute(): UsbAudioManager.PlaybackRoute.Fallback =
        UsbAudioManager.PlaybackRoute.Fallback(
            bitDepth = null, sampleRateHz = usbAudioManager.halOutputSampleRate(), channels = 2)

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
        val usbSink = usbAudioManager.createAudioSink()
        return (usbSink != null && usbSink.reallySupports(format)) || fallback.supportsFormat(format)
    }

    override fun getFormatSupport(format: Format): Int {
        val usbSink = usbAudioManager.createAudioSink()
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
        val previousDelegate = delegate
        val chosen = resolveForConfigure(inputFormat)
        try {
            chosen.configure(inputFormat, specifiedBufferSize, outputChannels)
        } catch (e: Exception) {
            // Whatever configure() was trying to do (claim the interface, lock the clock) didn't
            // pan out -- don't leave the Now Playing screen stuck on "Switching" forever.
            usbAudioManager.reportPlaybackRoute(fallbackRoute())
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
                fallbackRoute()
            } else {
                UsbAudioManager.PlaybackRoute.Direct(
                    bitDepth = bitDepthFor(inputFormat.pcmEncoding),
                    sampleRateHz = inputFormat.sampleRate,
                    channels = inputFormat.channelCount)
            })
    }

    override fun play() {
        isPlaying = true
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
