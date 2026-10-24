/*
 * Copyright (c) 2026 libusbdac Project
 * UsbControlWriterImplTest.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.control

import android.hardware.usb.UsbDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ostrichbat2k.usbdac.UsbAudioManager.ClaimOutcome
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioController
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession

class UsbControlWriterImplTest {

    private val format =
        UacStreamFormat(
            alternateSetting = 1,
            bitResolution = 24,
            subslotSize = 4,
            clusterDescriptorId = -1,
            endpointAddress = 0x02,
            maxPacketSize = 192,
            isAsync = true,
        )

    private fun topology(
        mixerUnitId: Int = -1,
        mixerInputChannelBase: Int = -1,
        channelCount: Int = 2,
    ) =
        UacPlaybackTopology(
            configurationValue = 1,
            controlInterfaceNumber = 0,
            streamingInterfaceNumber = 1,
            uacVersion = 2,
            clockSourceId = 2,
            outputFeatureUnitId = 3,
            mixerUnitId = mixerUnitId,
            mixerInputChannelBase = mixerInputChannelBase,
            powerDomainId = -1,
            powerDomainD1RecoveryUs = 0,
            powerDomainD2RecoveryUs = 0,
            channelCount = channelCount,
            formats = listOf(format),
        )

    private fun fakeSession(topology: UacPlaybackTopology = topology()): UsbAudioSession {
        val device = mockk<UsbDevice>(relaxed = true)
        every { device.productName } returns "Test DAC"
        every { device.deviceName } returns "/dev/bus/usb/001/002"
        return UsbAudioSession(
            device = device,
            connection = mockk(relaxed = true),
            topology = topology,
            controlInterface = mockk(relaxed = true),
            streamingAltSettings = emptyMap(),
            clockSupportedRates = listOf(44100 to 44100),
        )
    }

    private lateinit var usbSettings: org.ostrichbat2k.usbdac.UsbDacPreferences
    private lateinit var volumeController: UsbVolumeController
    private var activeSession: UsbAudioSession? = null
    private var activeFormat: UacStreamFormat? = format
    private var activeRateHz = 44100
    private var claimResult = ClaimOutcome.RECLAIMED
    private var requestClaimCalls = 0
    private var releaseClaimCalls = 0
    private var controlState: UsbControlState? = null
    private val publishedStates = mutableListOf<UsbControlState?>()
    private val loggedEvents = mutableListOf<String>()
    private lateinit var writer: UsbControlWriterImpl

    @Before
    fun setUp() {
        mockkObject(UsbAudioController)
        usbSettings = mockk(relaxed = true)
        volumeController = mockk(relaxed = true)
        activeSession = null
        activeFormat = format
        activeRateHz = 44100
        claimResult = ClaimOutcome.RECLAIMED
        requestClaimCalls = 0
        releaseClaimCalls = 0
        controlState = null
        publishedStates.clear()

        writer =
            UsbControlWriterImpl(
                usbSettings = usbSettings,
                volumeController = volumeController,
                getActiveSession = { activeSession },
                getActiveFormat = { activeFormat },
                getActiveRateHz = { activeRateHz },
                requestClaim = { requestClaimCalls++; claimResult },
                releaseClaim = { releaseClaimCalls++ },
                getControlState = { controlState },
                publishControlState = { controlState = it; publishedStates += it },
                log = { loggedEvents += it },
            )
    }

    @After
    fun tearDown() {
        unmockkObject(UsbAudioController)
    }

    // ---- refreshControlState ----

    @Test
    fun `refreshControlState reports an empty mixer grid when the device has no mixer unit`() {
        val session = fakeSession(topology(mixerUnitId = -1))
        every { UsbAudioController.getVolume(session, any()) } returns 10
        every { UsbAudioController.getVolumeRange(session, any()) } returns (0 to 20)

        writer.refreshControlState(session, format, "Test DAC")

        val state = publishedStates.last()!!
        assertEquals(emptyList<Pair<Int, Int>>(), state.mixerChannels)
        assertEquals(emptyMap<Pair<Int, Int>, Int?>(), state.mixerGains)
        assertEquals(null, state.mixerGainRange)
    }

    @Test
    fun `refreshControlState builds the full input-output grid for a device with a mixer unit`() {
        val session = fakeSession(topology(mixerUnitId = 5, mixerInputChannelBase = 1, channelCount = 2))
        every { UsbAudioController.getVolume(session, any()) } returns 10
        every { UsbAudioController.getVolumeRange(session, any()) } returns (0 to 20)
        every { UsbAudioController.getMixerGain(session, 5, any(), any()) } returns -6
        every { UsbAudioController.getMixerGainRange(session, 5, 1, 1) } returns (-60 to 0)

        writer.refreshControlState(session, format, "Test DAC")

        val state = publishedStates.last()!!
        // mixerBase=1, channelCount=2 -> input channels {1,2}, each against output channels {1,2}
        assertEquals(setOf(1 to 1, 1 to 2, 2 to 1, 2 to 2), state.mixerChannels.toSet())
        assertEquals(4, state.mixerGains.size)
        assertEquals(-6, state.mixerGains[1 to 1])
        assertEquals(-60 to 0, state.mixerGainRange) // only ever probed via the *first* pair
        verify(exactly = 1) { UsbAudioController.getMixerGainRange(any(), any(), any(), any()) }
    }

    @Test
    fun `refreshControlState's volume probe returns the first channel that answers`() {
        val session = fakeSession(topology(channelCount = 2))
        every { UsbAudioController.getVolume(session, 0) } returns null
        every { UsbAudioController.getVolume(session, 1) } returns 7
        every { UsbAudioController.getVolume(session, 2) } returns 99
        every { UsbAudioController.getVolumeRange(session, any()) } returns null

        writer.refreshControlState(session, format, "Test DAC")

        assertEquals(7, publishedStates.last()!!.volume)
    }

    @Test
    fun `refreshControlState falls back to the last known volume when every channel is silent`() {
        val session = fakeSession(topology(channelCount = 2))
        controlState =
            UsbControlState(
                deviceName = "Test DAC",
                formats = listOf(format),
                activeFormat = format,
                sampleRateHz = 44100,
                muted = false,
                volume = 42,
                volumeRange = null,
                mixerChannels = emptyList(),
                mixerGains = emptyMap(),
                mixerGainRange = null,
                clockSupportedRates = emptyList(),
            )
        every { UsbAudioController.getVolume(session, any()) } returns null
        every { UsbAudioController.getVolumeRange(session, any()) } returns null

        writer.refreshControlState(session, format, "Test DAC")

        assertEquals(42, publishedStates.last()!!.volume)
    }

    @Test
    fun `refreshControlState reads mute without probing a channel range`() {
        val session = fakeSession()
        every { UsbAudioController.getMute(session) } returns true
        every { UsbAudioController.getVolume(session, any()) } returns null
        every { UsbAudioController.getVolumeRange(session, any()) } returns null

        writer.refreshControlState(session, format, "Test DAC")

        assertEquals(true, publishedStates.last()!!.muted)
    }

    @Test
    fun `refreshControlState publishes the given deviceName, format, sample rate, and clock ranges verbatim`() {
        val session = fakeSession()
        every { UsbAudioController.getVolume(session, any()) } returns null
        every { UsbAudioController.getVolumeRange(session, any()) } returns null
        activeRateHz = 96000

        writer.refreshControlState(session, format, "Explicit Name")

        val state = publishedStates.last()!!
        assertEquals("Explicit Name", state.deviceName)
        assertEquals(format, state.activeFormat)
        assertEquals(96000, state.sampleRateHz)
        assertEquals(listOf(44100 to 44100), state.clockSupportedRates)
    }

    // ---- setMuted ----

    @Test
    fun `setMuted is a no-op with no active session`() {
        activeSession = null
        writer.setMuted(true)
        verify(exactly = 0) { UsbAudioController.setMute(any(), any()) }
        assertEquals(0, requestClaimCalls)
    }

    @Test
    fun `setMuted is a no-op with no active format`() {
        activeSession = fakeSession()
        activeFormat = null
        writer.setMuted(true)
        verify(exactly = 0) { UsbAudioController.setMute(any(), any()) }
    }

    @Test
    fun `setMuted does nothing further when the claim fails`() {
        activeSession = fakeSession()
        claimResult = ClaimOutcome.FAILED

        writer.setMuted(true)

        verify(exactly = 0) { UsbAudioController.setMute(any(), any()) }
        assertEquals(0, releaseClaimCalls)
    }

    @Test
    fun `setMuted claims, writes mute, refreshes state, then releases, in that order`() {
        val session = fakeSession()
        activeSession = session
        every { UsbAudioController.getVolume(session, any()) } returns null
        every { UsbAudioController.getVolumeRange(session, any()) } returns null

        writer.setMuted(true)

        verifyOrder {
            UsbAudioController.setMute(session, true)
            UsbAudioController.getMute(session)
        }
        assertEquals(1, requestClaimCalls)
        assertEquals(1, releaseClaimCalls)
        assertEquals("Test DAC", publishedStates.last()!!.deviceName)
    }

    // ---- setVolume ----

    @Test
    fun `setVolume immediately republishes the optimistic value and persists and mirrors it`() {
        controlState =
            UsbControlState(
                deviceName = "Test DAC",
                formats = listOf(format),
                activeFormat = format,
                sampleRateHz = 44100,
                muted = false,
                volume = 10,
                volumeRange = 0 to 20,
                mixerChannels = emptyList(),
                mixerGains = emptyMap(),
                mixerGainRange = null,
                clockSupportedRates = emptyList(),
            )
        activeSession = null // no hardware round trip needed to observe this part

        writer.setVolume(15)

        assertEquals(15, controlState?.volume)
        verify { usbSettings.volumeDb = 15 }
        verify { volumeController.mirrorDbToStreamMusic(15) }
    }

    @Test
    fun `setVolume does not touch hardware when there is no active session`() {
        activeSession = null
        writer.setVolume(15)
        verify(exactly = 0) { UsbAudioController.setVolume(any(), any(), any()) }
    }

    @Test
    fun `setVolume writes every channel, refreshes, and releases on the background executor`() {
        val session = fakeSession(topology(channelCount = 2))
        activeSession = session
        every { UsbAudioController.getVolume(session, any()) } returns null
        every { UsbAudioController.getVolumeRange(session, any()) } returns null

        writer.setVolume(-10)

        verify(timeout = 1000) { UsbAudioController.setVolume(session, -10, 0) }
        verify(timeout = 1000) { UsbAudioController.setVolume(session, -10, 1) }
        verify(timeout = 1000) { UsbAudioController.setVolume(session, -10, 2) }
        verify(timeout = 1000) { UsbAudioController.getMute(session) } // refreshControlState ran
        assertEquals(1, releaseClaimCalls)
    }

    @Test
    fun `a later setVolume call coalesces onto the latest target before hardware catches up`() {
        val session = fakeSession(topology(channelCount = 1))
        activeSession = session
        every { UsbAudioController.getVolume(session, any()) } returns null
        every { UsbAudioController.getVolumeRange(session, any()) } returns null

        writer.setVolume(10)
        writer.setVolume(20)

        verify(timeout = 1000) { UsbAudioController.setVolume(session, 20, 0) }
    }

    @Test
    fun `a failed claim during the background write does not leave volume writes permanently stuck`() {
        val session = fakeSession(topology(channelCount = 1))
        activeSession = session
        claimResult = ClaimOutcome.FAILED

        writer.setVolume(10)
        Thread.sleep(300)
        verify(exactly = 0) { UsbAudioController.setVolume(any(), any(), any()) }

        claimResult = ClaimOutcome.RECLAIMED
        every { UsbAudioController.getVolume(session, any()) } returns null
        every { UsbAudioController.getVolumeRange(session, any()) } returns null
        writer.setVolume(30)

        verify(timeout = 1000) { UsbAudioController.setVolume(session, 30, 0) }
    }

    // ---- setMixerGain ----

    @Test
    fun `setMixerGain is a no-op with no active session`() {
        activeSession = null
        writer.setMixerGain(1, 1, -6)
        verify(exactly = 0) { UsbAudioController.setMixerGain(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setMixerGain is a no-op when the device has no mixer unit`() {
        activeSession = fakeSession(topology(mixerUnitId = -1))
        writer.setMixerGain(1, 1, -6)
        verify(exactly = 0) { UsbAudioController.setMixerGain(any(), any(), any(), any(), any()) }
        assertEquals(0, requestClaimCalls)
    }

    @Test
    fun `setMixerGain does nothing further when the claim fails`() {
        activeSession = fakeSession(topology(mixerUnitId = 5))
        claimResult = ClaimOutcome.FAILED
        writer.setMixerGain(1, 1, -6)
        verify(exactly = 0) { UsbAudioController.setMixerGain(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setMixerGain writes the pair, refreshes, and releases`() {
        val session = fakeSession(topology(mixerUnitId = 5, mixerInputChannelBase = 1, channelCount = 2))
        activeSession = session
        every { UsbAudioController.getVolume(session, any()) } returns null
        every { UsbAudioController.getVolumeRange(session, any()) } returns null
        every { UsbAudioController.getMixerGain(session, 5, any(), any()) } returns null
        every { UsbAudioController.getMixerGainRange(session, any(), any(), any()) } returns null

        writer.setMixerGain(1, 2, -6)

        verify { UsbAudioController.setMixerGain(session, 5, 1, 2, -6) }
        assertEquals(1, releaseClaimCalls)
    }
}
