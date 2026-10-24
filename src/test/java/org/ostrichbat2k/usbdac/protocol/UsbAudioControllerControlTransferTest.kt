/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioControllerControlTransferTest.kt is part of libusbdac.
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
import android.hardware.usb.UsbInterface
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [UsbAudioController]'s class-specific control-transfer requests: the wValue/wIndex
 * address encoding for each entity type (Clock Source, Feature Unit, Mixer Unit, Power Domain,
 * UAC1 endpoint) and the response-decoding/short-read-rejection logic. [activate]/[listInterfaces]
 * /[reassertKernelEviction]/[releaseToKernel]/[reclaimFromKernel]/[release] (interface
 * claim/config orchestration, not register-level requests) are out of scope here.
 */
class UsbAudioControllerControlTransferTest {

    private fun topology(
        uacVersion: Int = 2,
        clockSourceId: Int = 2,
        outputFeatureUnitId: Int = 3,
    ) =
        UacPlaybackTopology(
            configurationValue = 1,
            controlInterfaceNumber = 0,
            streamingInterfaceNumber = 1,
            uacVersion = uacVersion,
            clockSourceId = clockSourceId,
            outputFeatureUnitId = outputFeatureUnitId,
            mixerUnitId = -1,
            mixerInputChannelBase = -1,
            powerDomainId = -1,
            powerDomainD1RecoveryUs = 0,
            powerDomainD2RecoveryUs = 0,
            channelCount = 2,
            formats = emptyList(),
        )

    private fun fakeSession(topology: UacPlaybackTopology = topology(), controlInterfaceId: Int = 0): UsbAudioSession {
        val controlInterface = mockk<UsbInterface>(relaxed = true)
        every { controlInterface.id } returns controlInterfaceId
        return UsbAudioSession(
            device = mockk(relaxed = true),
            connection = mockk(relaxed = true),
            topology = topology,
            controlInterface = controlInterface,
            streamingAltSettings = emptyMap(),
        )
    }

    private fun stub(connection: UsbDeviceConnection, type: Int, request: Int, value: Int, index: Int, response: ByteArray, result: Int = response.size) {
        every { connection.controlTransfer(type, request, value, index, any(), any(), any()) } answers {
            response.copyInto(arg(4))
            result
        }
    }

    private fun le(value: Int, len: Int) = with(UsbAudioController) { leBytes(value, len) }

    // ---- clock frequency (UAC2/3) ----

    @Test
    fun `getClockFrequency decodes a 4-byte little-endian Hz value`() {
        val session = fakeSession(topology(clockSourceId = 2), controlInterfaceId = 0)
        stub(session.connection, 0xA1, 0x01, 0x0100, 0x0200, le(48000, 4))

        assertEquals(48000, UsbAudioController.getClockFrequency(session))
    }

    @Test
    fun `getClockFrequency returns null on a short read`() {
        val session = fakeSession()
        every { session.connection.controlTransfer(any(), any(), any(), any(), any(), any(), any()) } returns 2
        assertNull(UsbAudioController.getClockFrequency(session))
    }

    @Test
    fun `getClockFrequencyRanges parses every fully-present subrange`() {
        val session = fakeSession(topology(clockSourceId = 2), controlInterfaceId = 0)
        val body = le(2, 2) + le(44100, 4) + le(44100, 4) + le(0, 4) + le(44100, 4) + le(192000, 4) + le(0, 4)
        stub(session.connection, 0xA1, 0x02, 0x0100, 0x0200, body)

        assertEquals(listOf(44100 to 44100, 44100 to 192000), UsbAudioController.getClockFrequencyRanges(session))
    }

    @Test
    fun `getClockFrequencyRanges stops at a subrange the device declared but didn't actually send`() {
        val session = fakeSession()
        // Declares 3 subranges but the transfer only actually carried 1's worth of bytes.
        val body = le(3, 2) + le(44100, 4) + le(44100, 4) + le(0, 4)
        every { session.connection.controlTransfer(any(), any(), any(), any(), any(), any(), any()) } answers {
            body.copyInto(arg(4))
            body.size
        }

        assertEquals(listOf(44100 to 44100), UsbAudioController.getClockFrequencyRanges(session))
    }

    @Test
    fun `getClockFrequencyRanges returns null on a too-short read`() {
        val session = fakeSession()
        every { session.connection.controlTransfer(any(), any(), any(), any(), any(), any(), any()) } returns 1
        assertNull(UsbAudioController.getClockFrequencyRanges(session))
    }

    @Test
    fun `getClockValid decodes bit 0 of the response byte`() {
        val session = fakeSession(topology(clockSourceId = 2), controlInterfaceId = 0)
        stub(session.connection, 0xA1, 0x01, 0x0200, 0x0200, byteArrayOf(1))
        assertEquals(true, UsbAudioController.getClockValid(session))
    }

    @Test
    fun `setClockFrequency returns true only when the readback matches what was written`() {
        val session = fakeSession(topology(clockSourceId = 2), controlInterfaceId = 0)
        every {
            session.connection.controlTransfer(0x21, 0x01, 0x0100, 0x0200, match<ByteArray> { it.contentEquals(le(48000, 4)) }, 4, 1000)
        } returns 4
        stub(session.connection, 0xA1, 0x01, 0x0100, 0x0200, le(48000, 4))

        assertTrue(UsbAudioController.setClockFrequency(session, 48000))
    }

    @Test
    fun `setClockFrequency returns false when the write succeeds but the readback disagrees`() {
        val session = fakeSession(topology(clockSourceId = 2), controlInterfaceId = 0)
        every { session.connection.controlTransfer(0x21, any(), any(), any(), any(), any(), any()) } returns 4
        stub(session.connection, 0xA1, 0x01, 0x0100, 0x0200, le(44100, 4)) // device actually stayed at the old rate

        assertFalse(UsbAudioController.setClockFrequency(session, 48000))
    }

    @Test
    fun `setClockFrequency returns false and never reads back when the write itself fails`() {
        val session = fakeSession()
        every { session.connection.controlTransfer(0x21, any(), any(), any(), any(), any(), any()) } returns -1

        assertFalse(UsbAudioController.setClockFrequency(session, 48000))
        verify(exactly = 0) { session.connection.controlTransfer(0xA1, any(), any(), any(), any(), any(), any()) }
    }

    // ---- UAC1 endpoint sample rate ----

    @Test
    fun `getUac1EndpointSampleRate decodes a 3-byte Hz value from the endpoint, not an entity`() {
        val session = fakeSession(topology(uacVersion = 1))
        stub(session.connection, 0xA2, 0x81, 0x0100, 0x82, le(44100, 3))
        assertEquals(44100, UsbAudioController.getUac1EndpointSampleRate(session, endpointAddress = 0x82))
    }

    @Test
    fun `setUac1EndpointSampleRate writes with SET_CUR 0x01 (not the GET-only 0x81 quirk)`() {
        val session = fakeSession(topology(uacVersion = 1))
        every {
            session.connection.controlTransfer(0x22, 0x01, 0x0100, 0x82, match<ByteArray> { it.contentEquals(le(44100, 3)) }, 3, 1000)
        } returns 3
        stub(session.connection, 0xA2, 0x81, 0x0100, 0x82, le(44100, 3))

        assertTrue(UsbAudioController.setUac1EndpointSampleRate(session, endpointAddress = 0x82, hz = 44100))
    }

    // ---- mute ----

    @Test
    fun `getMute and setMute are no-ops with no Feature Unit`() {
        val session = fakeSession(topology(outputFeatureUnitId = -1))
        assertNull(UsbAudioController.getMute(session))
        assertFalse(UsbAudioController.setMute(session, true))
        verify(exactly = 0) { session.connection.controlTransfer(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `getMute uses the UAC1 GET_CUR quirk only for a UAC1 session`() {
        val session = fakeSession(topology(uacVersion = 1, outputFeatureUnitId = 3), controlInterfaceId = 0)
        // wValue = (FU_MUTE_CONTROL=0x01 << 8) | channel(0); wIndex = (unit=3 << 8) | iface(0)
        stub(session.connection, 0xA1, 0x81, 0x0100, 0x0300, byteArrayOf(1))
        assertEquals(true, UsbAudioController.getMute(session, channel = 0))
    }

    @Test
    fun `setMute always uses 0x01 regardless of UAC version`() {
        val session = fakeSession(topology(uacVersion = 1, outputFeatureUnitId = 3), controlInterfaceId = 0)
        every {
            session.connection.controlTransfer(0x21, 0x01, 0x0100, 0x0300, match<ByteArray> { it.contentEquals(byteArrayOf(1)) }, 1, 1000)
        } returns 1
        assertTrue(UsbAudioController.setMute(session, muted = true, channel = 0))
    }

    @Test
    fun `setMute addresses the requested channel in the low byte of wValue`() {
        val session = fakeSession(topology(outputFeatureUnitId = 3), controlInterfaceId = 0)
        every { session.connection.controlTransfer(0x21, 0x01, 0x0102, 0x0300, any(), any(), any()) } returns 1
        assertTrue(UsbAudioController.setMute(session, muted = false, channel = 2))
    }

    // ---- volume ----

    @Test
    fun `getVolume decodes a negative (attenuated) signed 16-bit value correctly`() {
        val session = fakeSession(topology(outputFeatureUnitId = 3), controlInterfaceId = 0)
        // -17dB in 1/256dB units = -4352 = 0xEF00 LE -> bytes [0x00, 0xEF]
        stub(session.connection, 0xA1, 0x01, 0x0200, 0x0300, byteArrayOf(0x00, 0xEF.toByte()))
        assertEquals(-4352, UsbAudioController.getVolume(session, channel = 0))
    }

    @Test
    fun `setVolume encodes a negative value as the matching little-endian bytes`() {
        val session = fakeSession(topology(outputFeatureUnitId = 3), controlInterfaceId = 0)
        every {
            session.connection.controlTransfer(
                0x21, 0x01, 0x0200, 0x0300, match<ByteArray> { it.contentEquals(byteArrayOf(0x00, 0xEF.toByte())) }, 2, 1000)
        } returns 2
        assertTrue(UsbAudioController.setVolume(session, value = -4352, channel = 0))
    }

    @Test
    fun `getVolumeRange decodes min and max from the RANGE response`() {
        val session = fakeSession(topology(outputFeatureUnitId = 3), controlInterfaceId = 0)
        val body = le(1, 2) + byteArrayOf(0x00, 0x80.toByte()) + byteArrayOf(0x00, 0x00) + le(1, 2) // min=-32768, max=0
        stub(session.connection, 0xA1, 0x02, 0x0200, 0x0300, body)
        assertEquals(-32768 to 0, UsbAudioController.getVolumeRange(session, channel = 0))
    }

    @Test
    fun `getVolume and getVolumeRange are no-ops with no Feature Unit`() {
        val session = fakeSession(topology(outputFeatureUnitId = -1))
        assertNull(UsbAudioController.getVolume(session, channel = 0))
        assertNull(UsbAudioController.getVolumeRange(session, channel = 0))
    }

    // ---- mixer gain ----

    @Test
    fun `getMixerGain addresses input and output channel as a (control, selector) pair`() {
        val session = fakeSession(controlInterfaceId = 0)
        // wValue = (inputChannel=3 << 8) | outputChannel(1); wIndex = (mixerUnitId=7 << 8) | iface(0)
        stub(session.connection, 0xA1, 0x01, 0x0301, 0x0700, byteArrayOf(0x00, 0x00))
        assertEquals(0, UsbAudioController.getMixerGain(session, mixerUnitId = 7, inputChannel = 3, outputChannel = 1))
    }

    @Test
    fun `setMixerGain writes the pair at the same address getMixerGain reads from`() {
        val session = fakeSession(controlInterfaceId = 0)
        every { session.connection.controlTransfer(0x21, 0x01, 0x0301, 0x0700, any(), any(), any()) } returns 2
        assertTrue(UsbAudioController.setMixerGain(session, mixerUnitId = 7, inputChannel = 3, outputChannel = 1, value = -256))
    }

    @Test
    fun `getMixerGainRange decodes min and max`() {
        val session = fakeSession(controlInterfaceId = 0)
        val body = le(1, 2) + le(0, 2) + le(100, 2) + le(1, 2)
        stub(session.connection, 0xA1, 0x02, 0x0301, 0x0700, body)
        assertEquals(0 to 100, UsbAudioController.getMixerGainRange(session, mixerUnitId = 7, inputChannel = 3, outputChannel = 1))
    }

    // ---- power domain ----

    @Test
    fun `getPowerDomainState and setPowerDomainState address the domain id, not a fixed unit`() {
        val session = fakeSession(controlInterfaceId = 0)
        stub(session.connection, 0xA1, 0x01, 0x0200, 0x0500, byteArrayOf(1)) // domain 5, currently D1
        assertEquals(1, UsbAudioController.getPowerDomainState(session, powerDomainId = 5))

        every {
            session.connection.controlTransfer(0x21, 0x01, 0x0200, 0x0500, match<ByteArray> { it.contentEquals(byteArrayOf(0)) }, 1, 1000)
        } returns 1
        assertTrue(UsbAudioController.setPowerDomainState(session, powerDomainId = 5, state = 0))
    }

    @Test
    fun `wakePowerDomain does nothing when the domain is already in D0`() {
        val session = fakeSession(controlInterfaceId = 0)
        stub(session.connection, 0xA1, 0x01, 0x0200, 0x0500, byteArrayOf(0))

        UsbAudioController.wakePowerDomain(session, powerDomainId = 5, d1RecoveryUs = 0, d2RecoveryUs = 0)

        verify(exactly = 0) { session.connection.controlTransfer(0x21, any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `wakePowerDomain wakes a sleeping domain to D0 and waits out its declared recovery time`() {
        val session = fakeSession(controlInterfaceId = 0)
        stub(session.connection, 0xA1, 0x01, 0x0200, 0x0500, byteArrayOf(1)) // currently D1
        every {
            session.connection.controlTransfer(0x21, 0x01, 0x0200, 0x0500, match<ByteArray> { it.contentEquals(byteArrayOf(0)) }, 1, 1000)
        } returns 1

        UsbAudioController.wakePowerDomain(session, powerDomainId = 5, d1RecoveryUs = 500, d2RecoveryUs = 999_000)

        verify {
            session.connection.controlTransfer(0x21, 0x01, 0x0200, 0x0500, match<ByteArray> { it.contentEquals(byteArrayOf(0)) }, 1, 1000)
        }
    }

    @Test
    fun `wakePowerDomain skips the wake attempt entirely when the state can't be read`() {
        val session = fakeSession(controlInterfaceId = 0)
        every { session.connection.controlTransfer(any(), any(), any(), any(), any(), any(), any()) } returns -1

        UsbAudioController.wakePowerDomain(session, powerDomainId = 5, d1RecoveryUs = 0, d2RecoveryUs = 0)

        verify(exactly = 0) { session.connection.controlTransfer(0x21, any(), any(), any(), any(), any(), any()) }
    }

    // ---- cluster channel count (UAC3) ----

    @Test
    fun `getClusterChannelCount reads the last byte of the cluster descriptor directly, unshifted`() {
        val session = fakeSession(controlInterfaceId = 4)
        val descriptor = byteArrayOf(7, 0, 0, 0, 0, 0, 6) // bNrChannels = 6
        stub(session.connection, 0xA1, 0x06, 42, 4, descriptor)
        assertEquals(6, UsbAudioController.getClusterChannelCount(session, clusterId = 42))
    }

    // ---- format selection ----

    @Test
    fun `selectFormat delegates to setInterface for a known alternate setting`() {
        val altInterface = mockk<UsbInterface>(relaxed = true)
        val format =
            UacStreamFormat(
                alternateSetting = 2,
                bitResolution = 24,
                subslotSize = 4,
                clusterDescriptorId = -1,
                endpointAddress = 0x02,
                maxPacketSize = 192,
                isAsync = true,
            )
        val session =
            UsbAudioSession(
                device = mockk(relaxed = true),
                connection = mockk(relaxed = true),
                topology = topology(),
                controlInterface = mockk(relaxed = true),
                streamingAltSettings = mapOf(2 to altInterface),
            )
        every { session.connection.setInterface(altInterface) } returns true

        assertTrue(UsbAudioController.selectFormat(session, format))
    }

    @Test
    fun `selectFormat fails without touching the connection for an unknown alternate setting`() {
        val format =
            UacStreamFormat(
                alternateSetting = 9,
                bitResolution = 24,
                subslotSize = 4,
                clusterDescriptorId = -1,
                endpointAddress = 0x02,
                maxPacketSize = 192,
                isAsync = true,
            )
        val session = fakeSession()

        assertFalse(UsbAudioController.selectFormat(session, format))
        verify(exactly = 0) { session.connection.setInterface(any()) }
    }
}
