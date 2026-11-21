/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDeviceQuirksTest.kt is part of libusbdac.
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

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #132: the kernel-derived quirk table and the app override. */
class UsbDeviceQuirksTest {
    @AfterEach
    fun tearDown() {
        UsbDeviceQuirks.setOverride(0x21b4, 0x0081, null)
        UsbDeviceQuirks.setOverride(0x21b4, null, null)
        UsbDeviceQuirks.setOverride(0x1234, 0x5678, null)
    }

    @Test
    fun `a device in the table gets its flags`() {
        // AudioQuest DragonFly, quirks.c:2507.
        assertEquals(setOf(UsbDeviceQuirk.SKIP_SAMPLE_RATE_READBACK), UsbDeviceQuirks.quirksFor(0x21b4, 0x0081))
        // MOONDROP Moonriver2 Ti, quirks.c:2541.
        assertEquals(setOf(UsbDeviceQuirk.CONTROL_DELAY_20MS), UsbDeviceQuirks.quirksFor(0x2fc6, 0xf06b))
    }

    @Test
    fun `a product without its own entry gets its vendor's`() {
        // TEAC: 0x0644:0x8043 has its own entry; 0x0644:0x9999 falls to the vendor-wide one.
        val teac = setOf(UsbDeviceQuirk.CONTROL_DELAY_20MS, UsbDeviceQuirk.INTERFACE_DELAY)
        assertEquals(teac, UsbDeviceQuirks.quirksFor(0x0644, 0x8043))
        assertEquals(teac, UsbDeviceQuirks.quirksFor(0x0644, 0x9999))
    }

    @Test
    fun `an unknown device has no quirks`() {
        assertEquals(emptySet<UsbDeviceQuirk>(), UsbDeviceQuirks.quirksFor(0x1234, 0x5678))
        assertEquals("none", UsbDeviceQuirks.resolve(0x1234, 0x5678).second)
    }

    @Test
    fun `an app override replaces the table, device before vendor, and can be removed`() {
        UsbDeviceQuirks.setOverride(0x21b4, null, setOf(UsbDeviceQuirk.INTERFACE_DELAY))
        assertEquals(setOf(UsbDeviceQuirk.INTERFACE_DELAY), UsbDeviceQuirks.quirksFor(0x21b4, 0x0081))

        UsbDeviceQuirks.setOverride(0x21b4, 0x0081, emptySet())
        assertEquals(emptySet<UsbDeviceQuirk>(), UsbDeviceQuirks.quirksFor(0x21b4, 0x0081))
        assertEquals("app override for this device", UsbDeviceQuirks.resolve(0x21b4, 0x0081).second)

        UsbDeviceQuirks.setOverride(0x21b4, 0x0081, null)
        UsbDeviceQuirks.setOverride(0x21b4, null, null)
        assertEquals(setOf(UsbDeviceQuirk.SKIP_SAMPLE_RATE_READBACK), UsbDeviceQuirks.quirksFor(0x21b4, 0x0081))
    }

    @Test
    fun `the source of the flags names the kernel line`() {
        assertEquals("kernel quirks.c:2507 (AudioQuest DragonFly)", UsbDeviceQuirks.resolve(0x21b4, 0x0081).second)
    }

    @Test
    fun `the table is well formed`() {
        val entries = UsbDeviceQuirkTable.entries
        assertTrue(entries.all { it.quirks.isNotEmpty() }, "an entry with no flags")
        val keys = entries.map { it.vendorId to it.productId }
        assertEquals(keys.size, keys.toSet().size, "duplicate entries")
        // First match wins, so a vendor-wide entry must not come before a product entry of the same vendor.
        entries.forEachIndexed { i, e ->
            if (e.productId == null) {
                assertTrue(
                    entries.drop(i + 1).none { it.vendorId == e.vendorId && it.productId != null },
                    "vendor entry for 0x${e.vendorId.toString(16)} shadows a later product entry")
            }
        }
    }
}
