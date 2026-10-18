/*
 * Copyright (c) 2026 Auxio Project
 * UsbAudioDevice.kt is part of Auxio.
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

import android.hardware.usb.UsbDevice

/** A USB device that exposes at least one USB Audio Class interface, and its permission state. */
data class UsbAudioDevice(val device: UsbDevice, val hasPermission: Boolean) {
    val displayName: String
        get() =
            device.productName
                ?: "USB Audio Device (%04x:%04x)".format(device.vendorId, device.productId)
}
