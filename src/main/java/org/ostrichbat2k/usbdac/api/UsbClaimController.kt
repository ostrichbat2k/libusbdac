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

package org.ostrichbat2k.usbdac.api

import android.hardware.usb.UsbDevice
import org.ostrichbat2k.usbdac.UsbAudioSession

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

    /** Whether we currently hold the raw interface claim right now. */
    fun isClaimed(): Boolean

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
     */
    fun releaseClaim(delayMs: Long, awaitAndroidTakeover: Boolean, deactivateOnTimeout: Boolean = false)
}
