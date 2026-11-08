/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDacPreferences.kt is part of libusbdac.
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

import org.ostrichbat2k.usbdac.streaming.RoutingAudioSink
import org.ostrichbat2k.usbdac.streaming.UsbAudioOutput
/**
 * User configuration specific to direct USB DAC output. The host app provides its own
 * implementation (persisted however fits its own settings architecture) and wires it in via
 * whatever constructs [UsbAudioManager].
 */
interface UsbDacPreferences {
    /**
     * Whether direct USB DAC output is enabled. This is an experimental, opt-in feature -- off by
     * default -- since it claims the USB interface away from Android's own audio framework.
     */
    val enabled: Boolean

    /**
     * Whether USB output should actually be used for playback. Distinct from [enabled]: a device
     * can be activated (and its Controls/Capabilities screens usable) without this -- audio only
     * ever routes to it once this is also on. Only meaningful (and only ever shown in the UI as
     * toggleable) when the attached device is bit-perfect capable; see
     * [UsbAudioManager.isBitPerfectCapable].
     */
    val bitPerfectMode: Boolean

    /**
     * The last hardware volume (raw dB*256 Feature Unit units) explicitly set via
     * [UsbAudioManager.setVolume] -- by the physical volume keys or the host app's own settings
     * UI -- or null if never set. Applied as the starting volume the next time a device is
     * activated (see [UsbAudioOutput]'s constructor) instead of a fixed default, so the user's
     * chosen level survives an app restart/DAC reconnect instead of resetting every time.
     */
    var volumeDb: Int?

    /**
     * Whether a blind ABX listening test is in progress. While on, [UsbAudioManager] secretly
     * randomizes bit-perfect vs HAL fallback on every new track (instead of respecting
     * [bitPerfectMode]) whenever the device is actually capable of both for that track, and the
     * host app is expected to hide which one is active (see [UsbAudioManager.abxGroundTruth])
     * until the user submits a guess via [UsbAudioManager.submitAbxGuess].
     */
    val abxTestMode: Boolean

    /**
     * How much of the DAC's full native volume range (whatever the attached device's own
     * min..max Feature Unit range happens to be -- it varies per device, see
     * [UsbDeviceCapabilities]) one physical-volume-key/system-volume-UI press should move,
     * expressed as a percentage from 1 (finest) to [MAX_VOLUME_STEP_PERCENT] (coarsest). A
     * percentage rather than a fixed step count because the same raw dB*256 delta means wildly
     * different perceived jumps on different devices' Feature Unit ranges. Not a hard contract:
     * implementations should coerce into `1..MAX_VOLUME_STEP_PERCENT` rather than assume a
     * caller-supplied value is already in range.
     */
    val volumeStepPercent: Int

    /**
     * A hard safety ceiling on the DAC's hardware output volume, expressed as a percentage of
     * the device's own native min..max Feature Unit range (same per-device-relative scheme as
     * [volumeStepPercent], for the same reason -- a fixed dB number means wildly different
     * perceived loudness across devices' ranges). No volume this library ever writes to the
     * Feature Unit for bit-perfect playback -- a user's own key/slider adjustment, the midpoint
     * default a fresh activation starts from, a restore after a clock relock -- is allowed to
     * exceed this, at any point in time; see [UsbAudioController.capToMaxPercent][
     * org.ostrichbat2k.usbdac.protocol.UsbAudioController.capToMaxPercent]. Defaults
     * conservatively low (see [DEFAULT_MAX_VOLUME_PERCENT]) since a bit-perfect DAC's own gain
     * stage sits entirely outside Android's usual safe-listening volume limiter, and
     * headphones/IEMs driven directly by one can be genuinely startling at even a modest-looking
     * slider position. Deliberately does not apply to the HAL-fallback path's unity-gain handoff
     * ([UsbVolumeController.prepareForHalHandoff][
     * org.ostrichbat2k.usbdac.control.UsbVolumeController.prepareForHalHandoff]) -- the real
     * loudness there is governed by Android's own software volume curve on top of that fixed
     * hardware gain, not by this setting. Not a hard contract: implementations should coerce into
     * `1..100` rather than assume a caller-supplied value is already in range.
     */
    val maxVolumePercent: Int

    /**
     * Whether [UsbAudioOutput.pause] should skip its automatic release-to-kernel entirely,
     * leaving the interface claimed (hardware state untouched) until the user explicitly hands
     * it back via [UsbAudioManager.setManualClaim]. `false` (Automatic, the default) is today's
     * existing behavior: a pause releases the claim after a short grace period so Android's own
     * USB audio HAL can use the device while idle. Does not affect
     * [RoutingAudioSink.resolveForConfigure]'s forced-fallback release -- that release is
     * required for a track that genuinely can't play on the DAC to be audible at all, not a
     * courtesy release of idle time, so it always runs regardless of this setting. Switching this
     * mid-session doesn't retroactively force a claim or release either way -- it only changes
     * what the *next* [UsbAudioOutput.pause] call does.
     */
    val manualReleaseControl: Boolean

    /**
     * Add a listener to be notified of settings changes.
     *
     * @param listener The listener to add.
     */
    fun registerListener(listener: Listener)

    /**
     * Unregister a listener, preventing any further settings updates from being sent to it.
     *
     * @param listener The listener to unregister, must be the same as the current listener.
     */
    fun unregisterListener(listener: Listener)

    interface Listener {
        /** Called when [enabled] has changed. */
        fun onUsbEnabledChanged() {}

        /** Called when [bitPerfectMode] has changed. */
        fun onBitPerfectModeChanged() {}

        /** Called when [abxTestMode] has changed. */
        fun onAbxTestModeChanged() {}

        /** Called when [volumeStepPercent] has changed. */
        fun onVolumeStepPercentChanged() {}

        /** Called when [maxVolumePercent] has changed. */
        fun onMaxVolumePercentChanged() {}

        /** Called when [manualReleaseControl] has changed. */
        fun onManualReleaseControlChanged() {}
    }

    companion object {
        /** The upper bound [volumeStepPercent] may be set to. */
        const val MAX_VOLUME_STEP_PERCENT = 10

        /** [maxVolumePercent]'s conservative out-of-the-box value -- see its own doc comment. */
        const val DEFAULT_MAX_VOLUME_PERCENT = 40
    }
}
