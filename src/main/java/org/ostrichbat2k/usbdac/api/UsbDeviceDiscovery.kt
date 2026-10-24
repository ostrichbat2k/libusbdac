/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDeviceDiscovery.kt is part of libusbdac.
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
import kotlinx.coroutines.flow.StateFlow
import org.ostrichbat2k.usbdac.UsbAudioDevice

/**
 * Everything the rest of the library needs to know about *which* USB Audio Class devices are
 * physically attached and whether we're permitted to use them -- device attach/detach tracking
 * and permission requests, nothing about DAC activation, claimed interfaces, or live control
 * state. [UsbAudioManager] is this contract's only consumer; its own doc comment on the
 * implementation has the full history of why this concern is split out on its own.
 *
 * Internal rather than part of the library's public (host-app-facing) API: no host app needs to
 * depend on this directly, only [UsbAudioManager] does.
 */
internal interface UsbDeviceDiscovery {
    /** Every USB Audio Class device currently attached, and whether we have permission to use it. */
    val devices: StateFlow<List<UsbAudioDevice>>

    /** Begins listening for USB device attach/detach/permission events. Call once at startup. */
    fun start()

    /** Requests permission to use [device]. The result arrives asynchronously via broadcast. */
    fun requestPermission(device: UsbDevice)
}
