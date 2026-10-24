/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDacActivatorImplTest.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.activation

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioController
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorParserUac1
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorParserUac2
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorParserUac3
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession
import org.ostrichbat2k.usbdac.protocol.UsbDeviceCapabilities

/**
 * Covers the orchestration in [UsbDacActivatorImpl.activate]: the cache-hit/cache-miss descriptor
 * fetch split, UAC3->UAC2->UAC1 parser fallback, format selection, the UAC1-vs-UAC2/3 branches for
 * clock/rate handling, the UAC3-only Cluster Descriptor channel-count patch and its onCapabilities
 * republish, the channel-count floor, and the release-on-failure paths. Every function on
 * [UsbAudioController] and the descriptor helper/parsers is mocked wholesale -- this class's own
 * value is entirely in how it sequences and branches on their results, not in any wire-level logic
 * of its own (that's [UsbAudioControllerControlTransferTest]'s job). A real
 * [UsbAudioTuning.DAC_SETTLE_DELAY_MS] (600ms) sleep still happens on every path that reaches a
 * final result, so the success-path tests here are each genuinely ~600ms, not a mocked instant.
 */
class UsbDacActivatorImplTest {

    private fun format(
        bitResolution: Int = 24,
        clusterDescriptorId: Int = -1,
        sampleRates: List<Int> = emptyList(),
    ) =
        UacStreamFormat(
            alternateSetting = 1,
            bitResolution = bitResolution,
            subslotSize = bitResolution / 8,
            clusterDescriptorId = clusterDescriptorId,
            endpointAddress = 0x02,
            maxPacketSize = 192,
            isAsync = true,
            sampleRates = sampleRates,
        )

    private fun topology(
        uacVersion: Int = 3,
        channelCount: Int = 2,
        mixerUnitId: Int = -1,
        mixerInputChannelBase: Int = -1,
        powerDomainId: Int = -1,
        formats: List<UacStreamFormat> = listOf(format()),
    ) =
        UacPlaybackTopology(
            configurationValue = 1,
            controlInterfaceNumber = 0,
            streamingInterfaceNumber = 1,
            uacVersion = uacVersion,
            clockSourceId = if (uacVersion == 1) -1 else 2,
            outputFeatureUnitId = 3,
            mixerUnitId = mixerUnitId,
            mixerInputChannelBase = mixerInputChannelBase,
            powerDomainId = powerDomainId,
            powerDomainD1RecoveryUs = 0,
            powerDomainD2RecoveryUs = 0,
            channelCount = channelCount,
            formats = formats,
        )

    private fun fakeDevice(vendorId: Int = 0x1234, productId: Int = 0x5678): UsbDevice {
        val device = mockk<UsbDevice>(relaxed = true)
        every { device.productName } returns "Test DAC"
        every { device.deviceName } returns "/dev/bus/usb/001/002"
        every { device.vendorId } returns vendorId
        every { device.productId } returns productId
        every { device.configurationCount } returns 1
        return device
    }

    /** Stubs every call along the default success path; tests override specific stubs after calling this. */
    private fun stubHappyPath(
        device: UsbDevice,
        found: UacPlaybackTopology,
        cache: CachedUsbDacDescriptor? = null,
    ): UsbAudioSession {
        // Capture these as plain locals first -- calling them directly inside every{}'s argument
        // list is itself a mock invocation mockk's matcher recording intercepts, which silently
        // breaks the match (confirmed: it falls through to an unrelated relaxed-mock fallback).
        val vendorId = device.vendorId
        val productId = device.productId
        every { descriptorCache.get(vendorId, productId) } returns cache
        every { usbManager.openDevice(device) } returns mockk<UsbDeviceConnection>(relaxed = true)
        every { UsbAudioDescriptorHelper.fetchConfigurationDescriptor(any(), any()) } returns byteArrayOf(1)
        every { UsbAudioDescriptorHelper.walk(any()) } returns emptyList()
        every { UsbAudioDescriptorParserUac3.dumpAcTopology(any<List<Pair<Int, ByteArray>>>()) } just Runs
        every { UsbAudioDescriptorParserUac3.parseUac3Playback(any<List<Pair<Int, ByteArray>>>()) } returns
            if (found.uacVersion == 3) found else null
        every { UsbAudioDescriptorParserUac2.parseUac2Playback(any<List<Pair<Int, ByteArray>>>()) } returns
            if (found.uacVersion == 2) found else null
        every { UsbAudioDescriptorParserUac1.parseUac1Playback(any<List<Pair<Int, ByteArray>>>()) } returns
            if (found.uacVersion == 1) found else null

        every { UsbAudioController.listInterfaces(any(), any()) } returns emptyList()
        val session =
            UsbAudioSession(
                device = device,
                connection = mockk(relaxed = true),
                topology = found,
                controlInterface = mockk(relaxed = true),
                streamingAltSettings = emptyMap(),
            )
        every { UsbAudioController.activate(context, device, found) } returns session
        every { UsbAudioController.getClockFrequencyRanges(any()) } returns emptyList()
        every { UsbAudioController.getClusterChannelCount(any(), any()) } returns null
        every { UsbAudioController.wakePowerDomain(any(), any(), any(), any()) } just Runs
        every { UsbAudioController.setClockFrequency(any(), any()) } returns true
        every { UsbAudioController.getMute(any()) } returns false
        every { UsbAudioController.setMute(any(), any()) } returns true
        every { UsbAudioController.getVolume(any(), any()) } returns 0
        every { UsbAudioController.getVolumeRange(any(), any()) } returns (0 to 0)
        every { UsbAudioController.defaultVolumeTarget(any()) } returns 0
        every { UsbAudioController.setVolume(any(), any(), any()) } returns true
        every { UsbAudioController.getMixerGain(any(), any(), any(), any()) } returns 0
        every { UsbAudioController.getMixerGainRange(any(), any(), any(), any()) } returns (0 to 0)
        every { UsbAudioController.setMixerGain(any(), any(), any(), any(), any()) } returns true
        every { UsbAudioController.reassertKernelEviction(any()) } just Runs
        every { UsbAudioController.selectFormat(any(), any()) } returns true
        every { UsbAudioController.setUac1EndpointSampleRate(any(), any(), any()) } returns true
        every { UsbAudioController.getClockValid(any()) } returns true
        every { UsbAudioController.release(any()) } just Runs
        return session
    }

    private lateinit var context: Context
    private lateinit var usbManager: UsbManager
    private lateinit var descriptorCache: UsbDacDescriptorCache
    private val capabilities = mutableListOf<UsbDeviceCapabilities?>()
    private var preferredBitResolution = 24
    private lateinit var activator: UsbDacActivatorImpl

    @Before
    fun setUp() {
        mockkObject(
            UsbAudioController,
            UsbAudioDescriptorHelper,
            UsbAudioDescriptorParserUac1,
            UsbAudioDescriptorParserUac2,
            UsbAudioDescriptorParserUac3,
        )
        context = mockk(relaxed = true)
        usbManager = mockk(relaxed = true)
        descriptorCache = mockk(relaxed = true)
        capabilities.clear()
        preferredBitResolution = 24
        activator =
            UsbDacActivatorImpl(
                context = context,
                usbManager = usbManager,
                descriptorCache = descriptorCache,
                getPreferredBitResolution = { preferredBitResolution },
                onCapabilities = { capabilities += it },
                log = {},
            )
    }

    @After
    fun tearDown() {
        unmockkObject(
            UsbAudioController,
            UsbAudioDescriptorHelper,
            UsbAudioDescriptorParserUac1,
            UsbAudioDescriptorParserUac2,
            UsbAudioDescriptorParserUac3,
        )
    }

    @Test
    fun `a cache hit never opens a connection, fetches descriptors, or re-writes the cache`() {
        val device = fakeDevice()
        val found = topology()
        stubHappyPath(device, found, cache = CachedUsbDacDescriptor(found, emptyList()))

        val result = activator.activate(device)

        assertTrue(result != null)
        verify(exactly = 0) { usbManager.openDevice(any()) }
        verify(exactly = 0) { UsbAudioDescriptorHelper.fetchConfigurationDescriptor(any(), any()) }
        verify(exactly = 0) { descriptorCache.put(any(), any(), any(), any()) }
    }

    @Test
    fun `a cache miss fetches and parses, then writes the resolved topology back to the cache`() {
        val device = fakeDevice()
        val found = topology(uacVersion = 1) // forces the UAC3 -> UAC2 -> UAC1 fallback to run in full
        stubHappyPath(device, found)
        val vendorId = device.vendorId
        val productId = device.productId

        activator.activate(device)

        verify(exactly = 1) { usbManager.openDevice(device) }
        verifyOrder {
            UsbAudioDescriptorParserUac3.parseUac3Playback(any<List<Pair<Int, ByteArray>>>())
            UsbAudioDescriptorParserUac2.parseUac2Playback(any<List<Pair<Int, ByteArray>>>())
            UsbAudioDescriptorParserUac1.parseUac1Playback(any<List<Pair<Int, ByteArray>>>())
        }
        verify { descriptorCache.put(vendorId, productId, found, emptyList()) }
    }

    @Test
    fun `returns null without parsing anything when the device can't be opened`() {
        val device = fakeDevice()
        every { descriptorCache.get(any(), any()) } returns null
        every { usbManager.openDevice(device) } returns null

        val result = activator.activate(device)

        assertNull(result)
        verify(exactly = 0) { UsbAudioDescriptorHelper.fetchConfigurationDescriptor(any(), any()) }
    }

    @Test
    fun `returns null when no parser recognizes the descriptors, without ever calling UsbAudioController activate`() {
        val device = fakeDevice()
        stubHappyPath(device, topology())
        every { UsbAudioDescriptorParserUac3.parseUac3Playback(any<List<Pair<Int, ByteArray>>>()) } returns null

        val result = activator.activate(device)

        assertNull(result)
        verify(exactly = 0) { UsbAudioController.activate(any(), any(), any()) }
        assertTrue(capabilities.isEmpty())
    }

    @Test
    fun `publishes capabilities even when the device declares no usable format`() {
        val device = fakeDevice()
        stubHappyPath(device, topology(formats = emptyList()))

        val result = activator.activate(device)

        assertNull(result)
        assertEquals(1, capabilities.size)
        verify(exactly = 0) { UsbAudioController.activate(any(), any(), any()) }
    }

    @Test
    fun `returns null and never writes the cache when UsbAudioController activate fails`() {
        val device = fakeDevice()
        val found = topology()
        stubHappyPath(device, found)
        every { UsbAudioController.activate(context, device, found) } returns null

        val result = activator.activate(device)

        assertNull(result)
        assertEquals(1, capabilities.size) // capabilities were already published before this point
        verify(exactly = 0) { descriptorCache.put(any(), any(), any(), any()) }
    }

    @Test
    fun `selects the format matching the preferred bit resolution over the first-declared one`() {
        val device = fakeDevice()
        val sixteenBit = format(bitResolution = 16)
        val twentyFourBit = format(bitResolution = 24)
        val found = topology(formats = listOf(sixteenBit, twentyFourBit))
        stubHappyPath(device, found)
        preferredBitResolution = 24

        val result = activator.activate(device)

        assertEquals(twentyFourBit, result?.format)
        verify { UsbAudioController.selectFormat(any(), twentyFourBit) }
    }

    @Test
    fun `falls back to the first declared format when none matches the preferred bit resolution`() {
        val device = fakeDevice()
        val onlyFormat = format(bitResolution = 16)
        val found = topology(formats = listOf(onlyFormat))
        stubHappyPath(device, found)
        preferredBitResolution = 32

        val result = activator.activate(device)

        assertEquals(onlyFormat, result?.format)
    }

    @Test
    fun `UAC1 sessions never query Clock Source frequency ranges`() {
        val device = fakeDevice()
        stubHappyPath(device, topology(uacVersion = 1, formats = listOf(format(sampleRates = listOf(44100)))))

        val result = activator.activate(device)

        verify(exactly = 0) { UsbAudioController.getClockFrequencyRanges(any()) }
        assertEquals(emptyList<Pair<Int, Int>>(), result?.session?.clockSupportedRates)
    }

    @Test
    fun `UAC2+3 sessions query live Clock Source ranges on a cache miss`() {
        val device = fakeDevice()
        val found = topology(uacVersion = 2)
        stubHappyPath(device, found)
        every { UsbAudioController.getClockFrequencyRanges(any()) } returns listOf(44100 to 48000)

        val result = activator.activate(device)

        verify(exactly = 1) { UsbAudioController.getClockFrequencyRanges(any()) }
        assertEquals(listOf(44100 to 48000), result?.session?.clockSupportedRates)
    }

    @Test
    fun `a cache hit reuses the cached Clock Source ranges instead of querying them again`() {
        val device = fakeDevice()
        val found = topology(uacVersion = 2)
        stubHappyPath(device, found, cache = CachedUsbDacDescriptor(found, listOf(44100 to 48000)))

        val result = activator.activate(device)

        verify(exactly = 0) { UsbAudioController.getClockFrequencyRanges(any()) }
        assertEquals(listOf(44100 to 48000), result?.session?.clockSupportedRates)
    }

    @Test
    fun `an unresolved UAC3 channel count is patched from the Cluster Descriptor and republished`() {
        val device = fakeDevice()
        val clusterFormat = format(clusterDescriptorId = 9)
        val found = topology(uacVersion = 3, channelCount = -1, formats = listOf(clusterFormat))
        stubHappyPath(device, found)
        every { UsbAudioController.getClusterChannelCount(any(), 9) } returns 6

        val result = activator.activate(device)

        verify { UsbAudioController.getClusterChannelCount(any(), 9) }
        assertEquals(2, capabilities.size) // published once before the patch, once after
        assertEquals(6, capabilities.last()?.topology?.channelCount)
        assertEquals(6, result?.session?.topology?.channelCount)
    }

    @Test
    fun `the Cluster Descriptor fetch is skipped once the channel count is already known`() {
        val device = fakeDevice()
        val clusterFormat = format(clusterDescriptorId = 9)
        val found = topology(uacVersion = 3, channelCount = 2, formats = listOf(clusterFormat))
        stubHappyPath(device, found)

        activator.activate(device)

        verify(exactly = 0) { UsbAudioController.getClusterChannelCount(any(), any()) }
    }

    @Test
    fun `a sub-2 channel count is floored to 2 without a second capabilities publish`() {
        val device = fakeDevice()
        val found = topology(uacVersion = 2, channelCount = 1)
        stubHappyPath(device, found)

        val result = activator.activate(device)

        assertEquals(2, result?.session?.topology?.channelCount)
        assertEquals(1, capabilities.size) // the floor fix itself doesn't trigger another publish
    }

    @Test
    fun `wakePowerDomain is called only when a power domain was found`() {
        val device = fakeDevice()
        stubHappyPath(device, topology(powerDomainId = 5))

        activator.activate(device)

        verify { UsbAudioController.wakePowerDomain(any(), 5, any(), any()) }
    }

    @Test
    fun `wakePowerDomain is never called without a power domain`() {
        val device = fakeDevice()
        stubHappyPath(device, topology(powerDomainId = -1))

        activator.activate(device)

        verify(exactly = 0) { UsbAudioController.wakePowerDomain(any(), any(), any(), any()) }
    }

    @Test
    fun `an initially-muted or indeterminate Feature Unit is explicitly unmuted`() {
        val device = fakeDevice()
        stubHappyPath(device, topology())
        every { UsbAudioController.getMute(any()) } returns true

        activator.activate(device)

        verify(exactly = 1) { UsbAudioController.setMute(any(), muted = false) }
    }

    @Test
    fun `an already-unmuted Feature Unit is left alone`() {
        val device = fakeDevice()
        stubHappyPath(device, topology())
        every { UsbAudioController.getMute(any()) } returns false

        activator.activate(device)

        verify(exactly = 0) { UsbAudioController.setMute(any(), any()) }
    }

    @Test
    fun `the volume loop covers channel 0 through channelCount inclusive, not just stereo`() {
        val device = fakeDevice()
        stubHappyPath(device, topology(channelCount = 2))

        activator.activate(device)

        verify { UsbAudioController.getVolume(any(), 0) }
        verify { UsbAudioController.getVolume(any(), 1) }
        verify { UsbAudioController.getVolume(any(), 2) }
        verify(exactly = 0) { UsbAudioController.getVolume(any(), 3) }
    }

    @Test
    fun `setVolume is only called for a channel whose current volume doesn't already match the target`() {
        val device = fakeDevice()
        stubHappyPath(device, topology(channelCount = 1))
        every { UsbAudioController.getVolume(any(), 0) } returns -100
        every { UsbAudioController.getVolume(any(), 1) } returns -200
        every { UsbAudioController.getVolumeRange(any(), any()) } returns (-200 to 0)
        every { UsbAudioController.defaultVolumeTarget(-200 to 0) } returns -200

        activator.activate(device)

        verify(exactly = 0) { UsbAudioController.setVolume(any(), any(), 1) } // already at target
        verify { UsbAudioController.setVolume(any(), -200, 0) }
    }

    @Test
    fun `the mixer gain grid scales with the device's real channel count, not a fixed stereo grid`() {
        val device = fakeDevice()
        stubHappyPath(device, topology(mixerUnitId = 7, mixerInputChannelBase = 5, channelCount = 2))

        activator.activate(device)

        verify { UsbAudioController.getMixerGain(any(), 7, 5, 1) }
        verify { UsbAudioController.getMixerGain(any(), 7, 5, 2) }
        verify { UsbAudioController.getMixerGain(any(), 7, 6, 1) }
        verify { UsbAudioController.getMixerGain(any(), 7, 6, 2) }
    }

    @Test
    fun `the mixer gain grid is skipped entirely with no Mixer Unit`() {
        val device = fakeDevice()
        stubHappyPath(device, topology(mixerUnitId = -1))

        activator.activate(device)

        verify(exactly = 0) { UsbAudioController.getMixerGain(any(), any(), any(), any()) }
    }

    @Test
    fun `kernel eviction is reasserted before selecting the alternate setting, not after`() {
        val device = fakeDevice()
        stubHappyPath(device, topology())

        activator.activate(device)

        verifyOrder {
            UsbAudioController.reassertKernelEviction(any())
            UsbAudioController.selectFormat(any(), any())
        }
    }

    @Test
    fun `UAC1 picks 48kHz over any other declared rate`() {
        val device = fakeDevice()
        val fmt = format(sampleRates = listOf(44100, 48000, 96000))
        stubHappyPath(device, topology(uacVersion = 1, formats = listOf(fmt)))

        val result = activator.activate(device)

        assertEquals(48000, result?.rateHz)
        verify { UsbAudioController.setUac1EndpointSampleRate(any(), fmt.endpointAddress, 48000) }
    }

    @Test
    fun `UAC1 falls back to 44dot1kHz, then to whatever is first, when 48kHz isn't offered`() {
        val device = fakeDevice()
        stubHappyPath(device, topology(uacVersion = 1, formats = listOf(format(sampleRates = listOf(44100, 96000)))))
        assertEquals(44100, activator.activate(device)?.rateHz)

        stubHappyPath(device, topology(uacVersion = 1, formats = listOf(format(sampleRates = listOf(96000)))))
        assertEquals(96000, activator.activate(device)?.rateHz)
    }

    @Test
    fun `UAC1 with no declared sample rate fails and releases the session without ever setting one`() {
        val device = fakeDevice()
        stubHappyPath(device, topology(uacVersion = 1, formats = listOf(format(sampleRates = emptyList()))))

        val result = activator.activate(device)

        assertNull(result)
        verify(exactly = 0) { UsbAudioController.setUac1EndpointSampleRate(any(), any(), any()) }
        verify { UsbAudioController.release(any()) }
    }

    @Test
    fun `UAC2+3 never call setUac1EndpointSampleRate -- rate success reuses the clock-set result`() {
        val device = fakeDevice()
        stubHappyPath(device, topology(uacVersion = 2))

        val result = activator.activate(device)

        assertEquals(48000, result?.rateHz)
        verify(exactly = 0) { UsbAudioController.setUac1EndpointSampleRate(any(), any(), any()) }
    }

    @Test
    fun `a failed alternate-setting selection releases the session and returns null even if the clock was set`() {
        val device = fakeDevice()
        stubHappyPath(device, topology())
        every { UsbAudioController.selectFormat(any(), any()) } returns false

        val result = activator.activate(device)

        assertNull(result)
        verify { UsbAudioController.release(any()) }
    }

    @Test
    fun `a failed clock set releases the session and returns null even if the alt-setting selection succeeded`() {
        val device = fakeDevice()
        stubHappyPath(device, topology(uacVersion = 2))
        every { UsbAudioController.setClockFrequency(any(), any()) } returns false

        val result = activator.activate(device)

        assertNull(result)
        verify { UsbAudioController.release(any()) }
    }
}
