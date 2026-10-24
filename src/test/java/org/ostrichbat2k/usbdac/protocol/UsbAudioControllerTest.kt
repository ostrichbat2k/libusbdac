/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioControllerTest.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [UsbAudioController]'s pure byte-math/request-selection helpers -- everything else in
 * that object drives a live [android.hardware.usb.UsbDeviceConnection] control transfer and is
 * out of scope here.
 */
class UsbAudioControllerTest {

    private fun topology(uacVersion: Int) =
        UacPlaybackTopology(
            configurationValue = 1,
            controlInterfaceNumber = 0,
            streamingInterfaceNumber = 1,
            uacVersion = uacVersion,
            clockSourceId = 2,
            outputFeatureUnitId = 3,
            mixerUnitId = -1,
            mixerInputChannelBase = -1,
            powerDomainId = -1,
            powerDomainD1RecoveryUs = 0,
            powerDomainD2RecoveryUs = 0,
            channelCount = 2,
            formats = emptyList(),
        )

    @Test
    fun `curRequest uses the UAC1-only GET_CUR code only for a UAC1 GET`() {
        assertEquals(0x81, UsbAudioController.curRequest(topology(1), get = true))
        assertEquals(0x01, UsbAudioController.curRequest(topology(1), get = false))
        assertEquals(0x01, UsbAudioController.curRequest(topology(2), get = true))
        assertEquals(0x01, UsbAudioController.curRequest(topology(2), get = false))
        assertEquals(0x01, UsbAudioController.curRequest(topology(3), get = true))
        assertEquals(0x01, UsbAudioController.curRequest(topology(3), get = false))
    }

    @Test
    fun `leUInt decodes little-endian bytes of every supported width`() = with(UsbAudioController) {
        assertEquals(0x42, byteArrayOf(0x42).leUInt(0, 1))
        assertEquals(0x1234, byteArrayOf(0x34, 0x12).leUInt(0, 2))
        assertEquals(0x030201, byteArrayOf(0x01, 0x02, 0x03).leUInt(0, 3))
        assertEquals(0x04030201, byteArrayOf(0x01, 0x02, 0x03, 0x04).leUInt(0, 4))
    }

    @Test
    fun `leUInt reads starting at the given offset, ignoring surrounding bytes`() = with(UsbAudioController) {
        val bytes = byteArrayOf(0x00, 0x00, 0x34, 0x12, 0x00)
        assertEquals(0x1234, bytes.leUInt(2, 2))
    }

    @Test
    fun `leUInt treats each byte as unsigned`() = with(UsbAudioController) {
        // 0xFF as a signed Byte is -1; the decoded Int must still be 255, not -1.
        assertEquals(0xFF, byteArrayOf(0xFF.toByte()).leUInt(0, 1))
        assertEquals(0xFFFF, byteArrayOf(0xFF.toByte(), 0xFF.toByte()).leUInt(0, 2))
    }

    @Test
    fun `leBytes is the exact inverse of leUInt across widths`() = with(UsbAudioController) {
        for (len in 1..4) {
            val maxValue = if (len == 4) Int.MAX_VALUE else (1 shl (8 * len)) - 1
            for (value in listOf(0, 1, maxValue / 2, maxValue)) {
                assertEquals("len=$len value=$value", value, leBytes(value, len).leUInt(0, len))
            }
        }
    }

    @Test
    fun `leBytes encodes least-significant byte first`() = with(UsbAudioController) {
        assertArrayEquals(byteArrayOf(0x34, 0x12), leBytes(0x1234, 2))
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), leBytes(0x04030201, 4))
    }

    @Test
    fun `defaultVolumeTarget is the midpoint of the device's reported range`() {
        assertEquals(50, UsbAudioController.defaultVolumeTarget(0 to 100))
        assertEquals(-64, UsbAudioController.defaultVolumeTarget(-128 to 0))
        assertEquals(5, UsbAudioController.defaultVolumeTarget(0 to 10))
    }

    @Test
    fun `defaultVolumeTarget is zero when the device never reported a range`() {
        assertEquals(0, UsbAudioController.defaultVolumeTarget(null))
    }
}
