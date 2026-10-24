/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioDescriptorHelperTest.kt is part of libusbdac.
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

import android.hardware.usb.UsbDeviceConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [UsbAudioDescriptorHelper.recordPendingFormat] (the PCM/non-PCM/malformed-alt-setting
 * decision shared by the UAC2/UAC3 parsers) and [UsbAudioDescriptorHelper.fetchConfigurationDescriptor]
 * (the two-stage header-then-full control-transfer read) -- [UsbAudioDescriptorHelper.walk] has
 * its own coverage in [UsbAudioDescriptorParserWalkTest].
 */
class UsbAudioDescriptorHelperTest {

    // ---- recordPendingFormat ----

    private fun record(
        interfaceNumber: Int = 1,
        altSetting: Int = 1,
        endpointAddress: Int = 0x02,
        endpointSync: Int = UsbAudioDescriptorHelper.SYNC_TYPE_ASYNC,
        maxPacketSize: Int = 192,
        bitResolution: Int = 24,
        subslotSize: Int = 4,
        clusterDescriptorId: Int = -1,
        isTerminalLinked: Boolean = true,
        isDeclaredPcm: Boolean = true,
        isRawData: Boolean = false,
        formats: MutableMap<Int, MutableList<UacStreamFormat>> = mutableMapOf(),
        nonPcm: MutableMap<Int, MutableList<UacNonPcmFormat>> = mutableMapOf(),
    ): Pair<MutableMap<Int, MutableList<UacStreamFormat>>, MutableMap<Int, MutableList<UacNonPcmFormat>>> {
        UsbAudioDescriptorHelper.recordPendingFormat(
            interfaceNumber,
            altSetting,
            endpointAddress,
            endpointSync,
            maxPacketSize,
            bitResolution,
            subslotSize,
            clusterDescriptorId,
            isTerminalLinked,
            isDeclaredPcm,
            isRawData,
            formats,
            nonPcm)
        return formats to nonPcm
    }

    @Test
    fun `a positive bitResolution is recorded as a PCM format`() {
        val (formats, nonPcm) = record(bitResolution = 24, subslotSize = 4, endpointSync = UsbAudioDescriptorHelper.SYNC_TYPE_ASYNC)

        assertEquals(1, formats.getValue(1).size)
        assertTrue(nonPcm.isEmpty())
        val format = formats.getValue(1).single()
        assertEquals(24, format.bitResolution)
        assertEquals(4, format.subslotSize)
        assertTrue(format.isAsync)
    }

    @Test
    fun `isAsync is false when the endpoint's sync type isn't SYNC_TYPE_ASYNC`() {
        val (formats, _) = record(bitResolution = 16, endpointSync = 0x3 /* e.g. synchronous */)
        assertFalse(formats.getValue(1).single().isAsync)
    }

    @Test
    fun `a zero bitResolution on our own streaming interface, not declared PCM, is recorded as non-PCM`() {
        val (formats, nonPcm) = record(bitResolution = 0, isTerminalLinked = true, isDeclaredPcm = false, isRawData = true)

        assertTrue(formats.isEmpty())
        assertEquals(1, nonPcm.getValue(1).size)
        assertTrue(nonPcm.getValue(1).single().isRawData)
    }

    @Test
    fun `a zero bitResolution that was declared PCM (incomplete alt setting) is dropped entirely`() {
        val (formats, nonPcm) = record(bitResolution = 0, isTerminalLinked = true, isDeclaredPcm = true)
        assertTrue(formats.isEmpty())
        assertTrue(nonPcm.isEmpty())
    }

    @Test
    fun `a zero bitResolution not linked to our own streaming interface is dropped entirely`() {
        val (formats, nonPcm) = record(bitResolution = 0, isTerminalLinked = false, isDeclaredPcm = false)
        assertTrue(formats.isEmpty())
        assertTrue(nonPcm.isEmpty())
    }

    @Test
    fun `a negative altSetting is ignored entirely, even with a positive bitResolution`() {
        val (formats, nonPcm) = record(altSetting = -1, bitResolution = 24)
        assertTrue(formats.isEmpty())
        assertTrue(nonPcm.isEmpty())
    }

    @Test
    fun `a negative endpointAddress is ignored entirely`() {
        val (formats, nonPcm) = record(endpointAddress = -1, bitResolution = 24)
        assertTrue(formats.isEmpty())
        assertTrue(nonPcm.isEmpty())
    }

    @Test
    fun `multiple formats for the same interface number accumulate in one list`() {
        val formats = mutableMapOf<Int, MutableList<UacStreamFormat>>()
        val nonPcm = mutableMapOf<Int, MutableList<UacNonPcmFormat>>()
        record(interfaceNumber = 1, altSetting = 1, bitResolution = 16, formats = formats, nonPcm = nonPcm)
        record(interfaceNumber = 1, altSetting = 2, bitResolution = 24, formats = formats, nonPcm = nonPcm)

        assertEquals(2, formats.getValue(1).size)
        assertEquals(listOf(1, 2), formats.getValue(1).map { it.alternateSetting })
    }

    // ---- fetchConfigurationDescriptor ----

    private fun stubHeaderThenFull(
        connection: UsbDeviceConnection,
        header: ByteArray,
        headerResult: Int,
        full: ByteArray,
        fullResult: Int,
    ) {
        every { connection.controlTransfer(0x80, 0x06, any(), 0, any(), any(), any()) } answers
            {
                val buffer = arg<ByteArray>(4)
                if (buffer.size == 9) {
                    header.copyInto(buffer)
                    headerResult
                } else {
                    full.copyInto(buffer)
                    fullResult
                }
            }
    }

    private fun headerDeclaring(totalLength: Int) = ByteArray(9).also { it[2] = (totalLength and 0xFF).toByte(); it[3] = ((totalLength shr 8) and 0xFF).toByte() }

    @Test
    fun `returns null when the header read is short`() {
        val connection = mockk<UsbDeviceConnection>(relaxed = true)
        every { connection.controlTransfer(any(), any(), any(), any(), any(), any(), any()) } returns 2

        assertNull(UsbAudioDescriptorHelper.fetchConfigurationDescriptor(connection, configIndex = 0))
    }

    @Test
    fun `returns the full descriptor bytes on a clean read`() {
        val connection = mockk<UsbDeviceConnection>(relaxed = true)
        val full = ByteArray(20) { (it + 1).toByte() }
        stubHeaderThenFull(connection, headerDeclaring(20), 9, full, 20)

        assertArrayEquals(full, UsbAudioDescriptorHelper.fetchConfigurationDescriptor(connection, configIndex = 0))
    }

    @Test
    fun `returns the partial bytes actually read on a short full read, not null`() {
        val connection = mockk<UsbDeviceConnection>(relaxed = true)
        val full = ByteArray(20) { (it + 1).toByte() }
        stubHeaderThenFull(connection, headerDeclaring(20), 9, full, 12)

        val result = UsbAudioDescriptorHelper.fetchConfigurationDescriptor(connection, configIndex = 0)
        assertEquals(12, result?.size)
        assertArrayEquals(full.copyOf(12), result)
    }

    @Test
    fun `returns null when the full read comes back empty`() {
        val connection = mockk<UsbDeviceConnection>(relaxed = true)
        stubHeaderThenFull(connection, headerDeclaring(20), 9, ByteArray(20), 0)

        assertNull(UsbAudioDescriptorHelper.fetchConfigurationDescriptor(connection, configIndex = 0))
    }

    @Test
    fun `encodes the configuration index into wValue, descriptor type into the high byte`() {
        val connection = mockk<UsbDeviceConnection>(relaxed = true)
        every { connection.controlTransfer(any(), any(), any(), any(), any(), any(), any()) } returns 2

        UsbAudioDescriptorHelper.fetchConfigurationDescriptor(connection, configIndex = 1)

        verify { connection.controlTransfer(0x80, 0x06, 0x0201, 0, any(), 9, 1000) }
    }
}
