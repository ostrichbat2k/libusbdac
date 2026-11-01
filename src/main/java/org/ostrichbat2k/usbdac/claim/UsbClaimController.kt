/*
 * Copyright (c) 2026 libusbdac Project
 * UsbClaimController.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.claim

import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession
import org.ostrichbat2k.usbdac.streaming.UsbAudioOutput
import android.hardware.usb.UsbDevice

/**
 * Everything the rest of the library decides about *who* currently holds the raw USB interface --
 * this library's own bit-perfect streaming path, or Android's normal kernel-driver-backed
 * USB-audio HAL -- and the hand-off between them. See the implementation's own doc comment for
 * why this is the single most invariant-heavy piece of the library.
 *
 * Internal rather than part of the library's public (host-app-facing) API: no host app needs to
 * depend on this directly, only [UsbAudioManager] and [UsbAudioOutput] (indirectly, via
 * [UsbAudioManager.requestClaim]/[UsbAudioManager.releaseClaim]) do.
 */
internal interface UsbClaimController {
    /** Called once a claiming caller is actually claimed and streaming -- see [releaseClaim]. */
    fun notifyStreamingStarted()

    /** The inverse of [notifyStreamingStarted] -- call before, not after, releasing anything. */
    fun notifyStreamingStopped()

    /**
     * Marks a [requestClaim]-through-activation attempt as in progress, so [releaseClaim] defers
     * rather than runs concurrently -- see [releaseClaim]'s own doc comment on why a caller's
     * [notifyStreamingStarted] alone (only called once an attempt *succeeds*) isn't early enough
     * a guard. Every call must be matched by exactly one [endClaimAttempt], including on failure.
     */
    fun beginClaimAttempt()

    /** The inverse of [beginClaimAttempt] -- call exactly once per [beginClaimAttempt], always. */
    fun endClaimAttempt()

    /** Whether we currently hold the raw interface claim right now. */
    fun isClaimed(): Boolean

    /**
     * True, exactly once, if the Feature Unit's hardware mute/volume was forced to a HAL-handoff
     * state (see [org.ostrichbat2k.usbdac.control.UsbVolumeController.prepareForHalHandoff]) since
     * the last time this returned true -- regardless of whether the claim came back to us via a
     * normal [requestClaim] [UsbAudioManager.ClaimOutcome.RECLAIMED] or was silently reclaimed by
     * [releaseClaim]'s own Android-takeover verification timing out. [UsbAudioOutput]'s
     * `activateAndStream()` consumes this on every call, forcing its full relock/volume-restore
     * path even when [requestClaim] alone would otherwise report
     * [UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED] -- see that call site's own doc comment for
     * the real, reproduced bug this closes.
     */
    fun consumeHardwareReset(): Boolean

    /** Records that the interface is already claimed by some other, earlier one-time call. */
    fun markAlreadyClaimed()

    /** Marks the claim gone without going through the normal [releaseClaim] dance. */
    fun markReleased()

    /** Cancels any release scheduled before a full session teardown. */
    fun cancelPendingRelease()

    /** Records which device to wait for in [releaseClaim]'s own Android-takeover verification. */
    fun recordDeactivatedDevice(device: UsbDevice?)

    /**
     * Claims the interface (evicting whatever the kernel driver currently has bound) if we don't
     * already hold it -- idempotent and cheap to call when already claimed. Blocking.
     */
    fun requestClaim(session: UsbAudioSession?): UsbAudioManager.ClaimOutcome

    /**
     * Schedules handing the interface back to the kernel's own driver after [delayMs], canceling
     * any release already pending. Pass 0 to release immediately. See the implementation's own
     * doc comment for [awaitAndroidTakeover]/[deactivateOnTimeout]'s real-hardware-driven
     * semantics.
     *
     * Deferred the same way an [notifyStreamingStarted]-covered active stream is (see that
     * function's doc comment) while a [beginClaimAttempt]/[endClaimAttempt]-bracketed attempt is
     * in progress -- without this, a release arriving between [requestClaim] succeeding and the
     * attempt's eventual [notifyStreamingStarted] call could run concurrently with that attempt's
     * own in-progress clock/mute/volume relock, handing the interface back and forcing hardware
     * gain to a HAL-handoff level out from under it.
     */
    fun releaseClaim(delayMs: Long, awaitAndroidTakeover: Boolean, deactivateOnTimeout: Boolean = false)
}
