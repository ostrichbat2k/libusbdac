/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioControllerInterfaceOrchestrationTest.kt is part of libusbdac.
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

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [UsbAudioController.listInterfaces] and [UsbAudioController.reclaimFromKernel] --
 * the two interface-claim/config-orchestration functions that don't touch
 * [org.ostrichbat2k.usbdac.streaming.UsbNative] (unlike [UsbAudioController.activate] and
 * [UsbAudioController.reassertKernelEviction], which call its `disconnectKernelDriver` native
 * method and so load a real native library the instant that object is even referenced --
 * confirmed empirically: `mockkObject(UsbNative)` alone throws `UnsatisfiedLinkError` in this
 * plain-JVM test environment, with no Robolectric to provide a fake implementation. Those two
 * stay untested until either Robolectric is added or `UsbNative` gets a seam of its own).
 * [reassertKernelEviction]/[releaseToKernel]/[release] (claim/register-level requests) are
 * otherwise out of scope here -- see [UsbAudioControllerControlTransferTest].
 */
class UsbAudioControllerInterfaceOrchestrationTest {

    private fun topology(
        configurationValue: Int = 1,
        controlInterfaceNumber: Int = 0,
        streamingInterfaceNumber: Int = 1,
    ) =
        UacPlaybackTopology(
            configurationValue = configurationValue,
            controlInterfaceNumber = controlInterfaceNumber,
            streamingInterfaceNumber = streamingInterfaceNumber,
            uacVersion = 2,
            clockSourceId = -1,
            outputFeatureUnitId = -1,
            mixerUnitId = -1,
            mixerInputChannelBase = -1,
            powerDomainId = -1,
            powerDomainD1RecoveryUs = 0,
            powerDomainD2RecoveryUs = 0,
            channelCount = 2,
            formats = emptyList(),
        )

    private fun fakeInterface(ifaceId: Int, altSetting: Int, ifaceClass: Int): UsbInterface =
        mockk(relaxed = true) {
            every { id } returns ifaceId
            every { alternateSetting } returns altSetting
            every { interfaceClass } returns ifaceClass
        }

    private fun fakeConfiguration(configId: Int, interfaces: List<UsbInterface>): UsbConfiguration =
        mockk(relaxed = true) {
            every { id } returns configId
            every { interfaceCount } returns interfaces.size
            every { getInterface(any()) } answers { interfaces[firstArg()] }
        }

    private fun fakeDevice(configurations: List<UsbConfiguration>): UsbDevice =
        mockk(relaxed = true) {
            every { configurationCount } returns configurations.size
            every { getConfiguration(any()) } answers { configurations[firstArg()] }
        }

    // ---- listInterfaces ----

    @Test
    fun `returns empty when no configuration matches the topology's configurationValue`() {
        val device = fakeDevice(listOf(fakeConfiguration(configId = 99, interfaces = emptyList())))
        assertEquals(emptyList<UsbInterfaceInfo>(), UsbAudioController.listInterfaces(device, topology(configurationValue = 1)))
    }

    @Test
    fun `classifies control, streaming (merging alt settings), other-audio, and non-audio roles`() {
        val control = fakeInterface(ifaceId = 0, altSetting = 0, ifaceClass = UsbConstants.USB_CLASS_AUDIO)
        val streaming0 = fakeInterface(ifaceId = 1, altSetting = 0, ifaceClass = UsbConstants.USB_CLASS_AUDIO)
        val streaming1 = fakeInterface(ifaceId = 1, altSetting = 1, ifaceClass = UsbConstants.USB_CLASS_AUDIO)
        val otherAudio = fakeInterface(ifaceId = 2, altSetting = 0, ifaceClass = UsbConstants.USB_CLASS_AUDIO)
        val hid = fakeInterface(ifaceId = 3, altSetting = 0, ifaceClass = UsbConstants.USB_CLASS_HID)
        val config = fakeConfiguration(configId = 1, interfaces = listOf(control, streaming0, streaming1, otherAudio, hid))
        val device = fakeDevice(listOf(config))

        val result =
            UsbAudioController.listInterfaces(
                device, topology(configurationValue = 1, controlInterfaceNumber = 0, streamingInterfaceNumber = 1))

        assertEquals(
            listOf(
                UsbInterfaceInfo(0, UsbConstants.USB_CLASS_AUDIO, 1, UsbInterfaceInfo.Role.CONTROL),
                UsbInterfaceInfo(1, UsbConstants.USB_CLASS_AUDIO, 2, UsbInterfaceInfo.Role.STREAMING),
                UsbInterfaceInfo(2, UsbConstants.USB_CLASS_AUDIO, 1, UsbInterfaceInfo.Role.OTHER_AUDIO),
                UsbInterfaceInfo(3, UsbConstants.USB_CLASS_HID, 1, UsbInterfaceInfo.Role.NON_AUDIO)),
            result)
    }

    @Test
    fun `picks the configuration matching configurationValue, not configuration index`() {
        val wrongConfig = fakeConfiguration(configId = 5, interfaces = listOf(fakeInterface(9, 0, UsbConstants.USB_CLASS_HID)))
        val rightConfig = fakeConfiguration(configId = 7, interfaces = listOf(fakeInterface(0, 0, UsbConstants.USB_CLASS_AUDIO)))
        val device = fakeDevice(listOf(wrongConfig, rightConfig))

        val result = UsbAudioController.listInterfaces(device, topology(configurationValue = 7, controlInterfaceNumber = 0))

        assertEquals(1, result.size)
        assertEquals(UsbInterfaceInfo.Role.CONTROL, result.single().role)
    }

    // ---- reclaimFromKernel ----

    private fun fakeSession(controlIface: UsbInterface, altZero: UsbInterface?) =
        UsbAudioSession(
            device = mockk(relaxed = true),
            connection = mockk(relaxed = true),
            topology = topology(),
            controlInterface = controlIface,
            streamingAltSettings = altZero?.let { mapOf(0 to it) } ?: emptyMap(),
        )

    @Test
    fun `claims control and streaming alt-0, returns true on success`() {
        val control = mockk<UsbInterface>(relaxed = true)
        val altZero = mockk<UsbInterface>(relaxed = true)
        val session = fakeSession(control, altZero)
        every { session.connection.claimInterface(control, true) } returns true
        every { session.connection.claimInterface(altZero, true) } returns true

        assertTrue(UsbAudioController.reclaimFromKernel(session))

        verify { session.connection.claimInterface(control, true) }
        verify { session.connection.claimInterface(altZero, true) }
        verify(exactly = 0) { session.connection.releaseInterface(any()) }
    }

    @Test
    fun `returns false and never attempts streaming when claiming control fails`() {
        val control = mockk<UsbInterface>(relaxed = true)
        val altZero = mockk<UsbInterface>(relaxed = true)
        val session = fakeSession(control, altZero)
        every { session.connection.claimInterface(control, true) } returns false

        assertFalse(UsbAudioController.reclaimFromKernel(session))

        verify(exactly = 0) { session.connection.claimInterface(altZero, true) }
    }

    @Test
    fun `releases control and returns false when claiming streaming fails after control succeeded`() {
        val control = mockk<UsbInterface>(relaxed = true)
        val altZero = mockk<UsbInterface>(relaxed = true)
        val session = fakeSession(control, altZero)
        every { session.connection.claimInterface(control, true) } returns true
        every { session.connection.claimInterface(altZero, true) } returns false

        assertFalse(UsbAudioController.reclaimFromKernel(session))

        verify { session.connection.releaseInterface(control) }
    }

    @Test
    fun `succeeds with no streaming alt-0 present, claiming only control`() {
        val control = mockk<UsbInterface>(relaxed = true)
        val session = fakeSession(control, altZero = null)
        every { session.connection.claimInterface(control, true) } returns true

        assertTrue(UsbAudioController.reclaimFromKernel(session))

        verify(exactly = 1) { session.connection.claimInterface(any(), true) }
    }
}
