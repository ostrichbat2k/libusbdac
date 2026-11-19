/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDeviceQuirk.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.quirks

import java.util.concurrent.ConcurrentHashMap

/**
 * Device-specific behaviour (#132), named after the Linux `snd-usb-audio` quirk flag each one
 * mirrors (sound/usb/usbaudio.h, `QUIRK_FLAG_*`).
 */
enum class UsbDeviceQuirk {
    /**
     * `GET_SAMPLE_RATE`: UAC 1.0 -- don't read the rate back after setting it; the device answers
     * wrongly or with an error.
     */
    SKIP_SAMPLE_RATE_READBACK,

    /** `CTL_MSG_DELAY`: wait 20 ms after every class control request. */
    CONTROL_DELAY_20MS,

    /** `CTL_MSG_DELAY_1M`: wait 1 ms after every class control request. */
    CONTROL_DELAY_1MS,

    /** `CTL_MSG_DELAY_5M`: wait 5 ms after every class control request. */
    CONTROL_DELAY_5MS,

    /** `IFACE_DELAY`: wait 50 ms after every SET_INTERFACE. */
    INTERFACE_DELAY,

    /**
     * `SET_IFACE_FIRST`: select the streaming alt-setting before setting the rate, even on
     * UAC 2.0/3.0 (undoes #122's order).
     */
    SET_INTERFACE_FIRST,

    /** `SKIP_CLOCK_SELECTOR`: never write the Clock Selector (#129). */
    SKIP_CLOCK_SELECTOR,
}

/**
 * Which [UsbDeviceQuirk]s apply to a device (#132): an app's override if it set one, otherwise
 * the built-in table taken from the kernel. The override exists so a device can be fixed without
 * a library release; it replaces the table's flags for that device rather than adding to them.
 */
object UsbDeviceQuirks {
    private data class Key(val vendorId: Int, val productId: Int?)

    private val overrides = ConcurrentHashMap<Key, Set<UsbDeviceQuirk>>()

    /**
     * Sets the quirks for [vendorId]/[productId] ([productId] null: every product of the vendor),
     * replacing the built-in table's; null removes the override. Read when a device is activated.
     */
    fun setOverride(vendorId: Int, productId: Int?, quirks: Set<UsbDeviceQuirk>?) {
        val key = Key(vendorId, productId)
        if (quirks == null) overrides.remove(key) else overrides[key] = quirks.toSet()
    }

    /** The quirks in effect for a device, and where they came from, for the diagnostic log. */
    fun resolve(vendorId: Int, productId: Int): Pair<Set<UsbDeviceQuirk>, String> {
        val deviceOverride = overrides[Key(vendorId, productId)]
        val vendorOverride = overrides[Key(vendorId, null)]
        val entry = UsbDeviceQuirkTable.lookup(vendorId, productId)
        return when {
            deviceOverride != null -> deviceOverride to "app override for this device"
            vendorOverride != null -> vendorOverride to "app override for this vendor"
            entry != null -> entry.quirks to "kernel quirks.c:${entry.kernelLine} (${entry.name})"
            else -> emptySet<UsbDeviceQuirk>() to "none"
        }
    }

    /** The quirks in effect for a device. */
    fun quirksFor(vendorId: Int, productId: Int): Set<UsbDeviceQuirk> = resolve(vendorId, productId).first
}
