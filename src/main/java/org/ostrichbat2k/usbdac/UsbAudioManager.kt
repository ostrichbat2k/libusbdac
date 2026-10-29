/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioManager.kt is part of libusbdac.
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

import org.ostrichbat2k.usbdac.control.UsbControlState
import org.ostrichbat2k.usbdac.discovery.UsbAudioDevice
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbDeviceCapabilities
import org.ostrichbat2k.usbdac.streaming.UsbPipelineStats
import android.hardware.usb.UsbDevice
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.flow.StateFlow

/**
 * The library's main entry point: detects attached USB Audio Class devices, manages permission
 * requests, and once permitted, activates a persistent session and hands out an [AudioSink] wired
 * to it for the host app's player to use in place of its normal output. The host app constructs
 * one implementation (`UsbAudioManagerImpl`) at startup, calls [start] once, and drives everything
 * else -- playback routing, the Settings UI, Now Playing display -- through this contract alone.
 *
 * Also a [UsbDacPreferences.Listener]: an implementation reacts to the host app's own settings
 * changes (enabling/disabling the feature, toggling bit-perfect mode) the same way it reacts to
 * device attach/detach.
 */
interface UsbAudioManager : UsbDacPreferences.Listener {
    /**
     * The real sample rate Android's shared audio HAL mixer runs at -- what any non-direct
     * (fallback) track actually gets resampled to on its way out, regardless of its own native
     * rate. For display only (e.g. labeling [PlaybackRoute.Fallback] accurately).
     */
    fun halOutputSampleRate(): Int

    /** Most-recent-first log of activation events, for a diagnostic/settings screen. */
    val diagnosticLog: StateFlow<List<String>>

    /**
     * Live ring-buffer/USB-transfer stats for a "Pipeline" diagnostic screen. Null whenever
     * nothing is actually streaming right now, not just a transient pause-silence gap.
     */
    val pipelineStats: StateFlow<UsbPipelineStats?>

    /** Live, read-back-from-the-device state of the active session's adjustable controls. */
    val controlState: StateFlow<UsbControlState?>

    /** Everything found on the attached device's descriptors. */
    val capabilities: StateFlow<UsbDeviceCapabilities?>

    /**
     * Where the *currently configured track* is actually going out -- not just whether a DAC
     * session exists, since a live bit-perfect session doesn't mean every individual track can
     * use it. Purely informational, for a Now Playing screen -- nothing here feeds back into any
     * routing decision.
     */
    val playbackRoute: StateFlow<PlaybackRoute>

    /** Every USB Audio Class device currently attached, and whether we have permission to use it. */
    val devices: StateFlow<List<UsbAudioDevice>>

    /** Reports [route] as the real, currently-configured track's route. Not meant for host-app callers. */
    fun reportPlaybackRoute(route: PlaybackRoute)

    /**
     * Whether the *current* track was secretly routed bit-perfect (`true`) or HAL fallback
     * (`false`) during an ABX trial, or `null` if no trial applies to this track. Only meant for
     * the code deciding routing -- a host app should read [abxPendingGuess] instead, which is
     * what's safe to act on without leaking the current track's route.
     */
    val abxGroundTruth: StateFlow<Boolean?>

    /**
     * The ground truth of the track that *just finished* its ABX trial, still ungraded -- `null`
     * once [submitAbxGuess] consumes it, or if that track had no trial running. A host app should
     * prompt for a guess the moment this goes non-null.
     */
    val abxPendingGuess: StateFlow<Boolean?>

    /** Cumulative ABX test score across this process's lifetime. */
    data class AbxStats(val correct: Int = 0, val total: Int = 0)

    /** See [AbxStats]. */
    val abxStats: StateFlow<AbxStats>

    /** Rerolls the current track's hidden ABX coin flip. Not meant for host-app callers. */
    fun rollAbxTrial()

    /**
     * Grades [guessedBitPerfect] against [abxPendingGuess], updates [abxStats], and consumes the
     * trial. Returns null if there was nothing pending.
     */
    fun submitAbxGuess(guessedBitPerfect: Boolean): Boolean?

    /** Where a track is actually being sent. */
    sealed interface PlaybackRoute {
        /**
         * Playing through Android's normal audio HAL (speaker, or whatever it last routed to).
         * [bitDepth]/[sampleRateHz]/[channels] are the *file's* own decoded format, not what HAL
         * is actually doing with it (HAL's shared mixer resamples every fallback track to its own
         * fixed output rate -- see [halOutputSampleRate]).
         */
        data class Fallback(val bitDepth: Int?, val sampleRateHz: Int, val channels: Int) : PlaybackRoute

        /** Streaming bit-perfect PCM directly to the USB DAC at these real, per-track values. */
        data class Direct(val bitDepth: Int?, val sampleRateHz: Int, val channels: Int) : PlaybackRoute

        /** A real hand-off between [Fallback] and [Direct] is in progress. */
        data object Switching : PlaybackRoute
    }

    /** Called once a caller is actually claimed and streaming -- see [releaseClaim]. */
    fun notifyStreamingStarted()

    /** The inverse of [notifyStreamingStarted] -- call before, not after, releasing anything. */
    fun notifyStreamingStopped()

    /**
     * See [org.ostrichbat2k.usbdac.claim.UsbClaimController.beginClaimAttempt] -- exposed on this
     * facade so [org.ostrichbat2k.usbdac.streaming.UsbAudioOutput] can bracket its whole
     * `activateAndStream()` attempt with this and [endClaimAttempt], not just [requestClaim]
     * itself.
     */
    fun beginClaimAttempt()

    /** The inverse of [beginClaimAttempt] -- call exactly once per [beginClaimAttempt], always. */
    fun endClaimAttempt()

    /**
     * Reports live pipeline stats, called periodically (not per-buffer) by whatever is actively
     * streaming, and with `null` the moment it genuinely stops -- see [pipelineStats].
     */
    fun reportPipelineStats(stats: UsbPipelineStats?)

    /** [requestClaim]'s result. */
    enum class ClaimOutcome {
        /** We already held the interface; nothing was touched, nothing needs re-verifying. */
        ALREADY_CLAIMED,

        /**
         * A genuine hand-off from the kernel happened -- anything it could have changed (clock
         * lock, mute, volume) must be re-verified before trusting it.
         */
        RECLAIMED,
        FAILED,
    }

    /**
     * Claims the raw USB interface (evicting whatever the kernel driver currently has bound) if
     * not already held -- idempotent and cheap to call when already claimed. Blocking.
     */
    fun requestClaim(): ClaimOutcome

    /**
     * See [org.ostrichbat2k.usbdac.claim.UsbClaimController.consumeHardwareReset] -- exposed on
     * this facade so [org.ostrichbat2k.usbdac.streaming.UsbAudioOutput], which only ever talks to
     * the claim machinery through here, can consume it on every `activateAndStream()` call.
     */
    fun consumeHardwareReset(): Boolean

    /**
     * Schedules handing the interface back to the kernel's own driver after [delayMs], canceling
     * any release already pending. Pass 0 to release immediately.
     *
     * [awaitAndroidTakeover] blocks this call until Android's own audio framework has recognized
     * the device as an output, when a caller's next step depends on that. [deactivateOnTimeout]
     * decides what happens if that verification times out: `true` means a concrete track
     * genuinely needs HAL to reach this device right now, so a timeout fully deactivates instead
     * of just reclaiming the interface back -- every other caller should leave it `false`.
     */
    fun releaseClaim(
        delayMs: Long = UsbAudioTuning.RELEASE_GRACE_MS,
        awaitAndroidTakeover: Boolean = false,
        deactivateOnTimeout: Boolean = false,
    )

    /**
     * True whenever a host UI's play control should hold off starting playback rather than let it
     * land on [PlaybackRoute.Fallback] purely because activation hasn't finished yet. False the
     * rest of the time, including whenever there's no DAC in the picture at all, so a host UI
     * binding its play button to this costs nothing outside the one real race window.
     */
    val blockPlayback: StateFlow<Boolean>

    /** Begins listening for USB device attach/detach/permission events. Call once at startup. */
    fun start()

    /** Requests permission to use [device]. The result arrives asynchronously. */
    fun requestPermission(device: UsbDevice)

    /**
     * Waits, bounded by [timeoutMs], for the most recently started activation to finish -- a
     * no-op if nothing's currently activating. [resolveActiveSink] already calls this internally
     * when triggering, so most callers don't need to; this stays here for a caller that
     * specifically wants to wait *before* doing anything else (e.g. a cold-launch restore path
     * awaiting once up front).
     */
    suspend fun awaitActivation(timeoutMs: Long = 8000L)

    /**
     * Non-blocking counterpart to passing `trigger = true` into [resolveActiveSink] -- kicks off
     * activation (if nothing has tried yet) without also waiting on [awaitActivation], for a
     * caller that has genuine play intent but can't afford to block before producing sound.
     */
    fun triggerActivation()

    /**
     * Returns an [AudioSink] wired to the active DAC session, or null if none is ready, bit-perfect
     * mode is off, or the device isn't [isBitPerfectCapable]. [trigger] gates whether this also
     * kicks off (and, if already in flight, blocks bounded via [awaitActivation] on) activation
     * when nothing's activated yet -- pass the caller's own genuine, current playback intent, not
     * a merely-preparing-but-paused or pure-capability-query call.
     */
    fun resolveActiveSink(trigger: Boolean): AudioSink?

    /**
     * Switches the DAC's active alt-setting to [format] if it isn't already, and records
     * [sampleRateHz] as the real currently-active rate -- called for every track, so a library
     * mixing different bit depths/sample rates gets a device format that actually matches each
     * one. Returns whether the switch succeeded.
     */
    fun ensureFormatActive(format: UacStreamFormat, sampleRateHz: Int): Boolean

    /**
     * Whether the active device is bit-perfect capable: its output Feature Unit was found *and*
     * its volume range was successfully read back. Gates whether a "Bit-perfect mode" toggle is
     * interactable at all; false if nothing is active right now.
     */
    fun isBitPerfectCapable(): Boolean

    /** Reads the active session's Power Domain state right now, or null if not applicable. */
    fun getPowerDomainState(): Int?

    /** Reads the active session's Clock Source frequency and valid/locked state right now, or null if not applicable. */
    fun getClockInfo(): Pair<Int?, Boolean?>?

    /** Live-adjusts the active session's mute state, if any; reflected in [controlState]. */
    fun setMuted(muted: Boolean)

    /** Live-adjusts the active session's master/channel volume, if any; reflected in [controlState]. */
    fun setVolume(valueDb: Int)

    /** Live-adjusts one Mixer Unit (input, output) channel pair's gain, if the device has one. */
    fun setMixerGain(inputChannel: Int, outputChannel: Int, valueDb: Int)

    /**
     * Changes which bit resolution to prefer and immediately re-activates with it, if a format at
     * that resolution exists on the current device (falls back to whatever's first otherwise).
     */
    fun setPreferredBitResolution(bitResolution: Int)

    /**
     * Re-runs DAC activation for whatever USB audio device is currently attached. Requests
     * permission if needed, or re-activates directly if already permitted -- the manual escape
     * hatch for forcing an activation attempt right now (a device that failed to activate, or one
     * the user wants to inspect without first pressing play).
     */
    fun retryActivation()
}
