/*
 * Copyright (c) 2026 libusbdac Project
 * UsbVolumeControllerImplTest.kt is part of libusbdac.
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

import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.UsbDacPreferences
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioController
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession

/**
 * The curve math below (mediaVolumeCurve: 1%->-58dB, 20%->-40dB, 60%->-17dB, 100%->0dB) is
 * exercised via one shared fixed point throughout: index 9 of a 0..15 STREAM_MUSIC range is 60%,
 * which the curve places at exactly -17dB (-4352 in the 1/256dB units [UsbDacPreferences
 * .volumeDb] stores), chosen because it lands squarely on a curve breakpoint instead of needing
 * interpolation to double-check by hand.
 */
class UsbVolumeControllerImplTest {

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

    private fun controlState(volumeRange: Pair<Int, Int>? = -12800 to 0) =
        UsbControlState(
            deviceName = "Test DAC",
            formats = listOf(format),
            activeFormat = format,
            sampleRateHz = 44100,
            muted = false,
            volume = 0,
            volumeRange = volumeRange,
            mixerChannels = emptyList(),
            mixerGains = emptyMap(),
            mixerGainRange = null,
            clockSupportedRates = emptyList(),
        )

    private val testTopology =
        UacPlaybackTopology(
            configurationValue = 1,
            controlInterfaceNumber = 0,
            streamingInterfaceNumber = 1,
            uacVersion = 2,
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

    private fun fakeSession() =
        UsbAudioSession(
            device = mockk(relaxed = true),
            connection = mockk(relaxed = true),
            topology = testTopology,
            controlInterface = mockk(relaxed = true),
            streamingAltSettings = emptyMap(),
        )

    private lateinit var audioManager: AudioManager
    private lateinit var usbSettings: UsbDacPreferences
    private lateinit var controlStateFlow: MutableStateFlow<UsbControlState?>
    private lateinit var playbackRouteFlow: MutableStateFlow<UsbAudioManager.PlaybackRoute>
    private lateinit var controller: UsbVolumeControllerImpl

    @Before
    fun setUp() {
        mockkObject(UsbAudioController)
        audioManager = mockk(relaxed = true)
        usbSettings = mockk(relaxed = true)
        controlStateFlow = MutableStateFlow(controlState())
        playbackRouteFlow =
            MutableStateFlow(UsbAudioManager.PlaybackRoute.Fallback(bitDepth = 16, sampleRateHz = 44100, channels = 2))
        controller =
            UsbVolumeControllerImpl(
                audioManager = audioManager,
                usbSettings = usbSettings,
                controlState = controlStateFlow,
                playbackRoute = playbackRouteFlow,
                logEvent = {},
            )
    }

    @After
    fun tearDown() {
        unmockkObject(UsbAudioController)
    }

    @Test
    fun `prepareForHalHandoff mutes, sets every channel to its reported max, then unmutes`() {
        val session = fakeSession()
        every { UsbAudioController.getVolumeRange(session, 0) } returns (0 to 100)
        every { UsbAudioController.getVolumeRange(session, 1) } returns (-20 to 20)
        every { UsbAudioController.getVolumeRange(session, 2) } returns (0 to 50)

        controller.prepareForHalHandoff(session, channelCount = 2)

        verifyOrder {
            UsbAudioController.setMute(session, muted = true)
            UsbAudioController.setVolume(session, 100, 0)
            UsbAudioController.setVolume(session, 20, 1)
            UsbAudioController.setVolume(session, 50, 2)
            UsbAudioController.setMute(session, muted = false)
        }
    }

    @Test
    fun `prepareForHalHandoff skips a channel with no reported volume range`() {
        val session = fakeSession()
        every { UsbAudioController.getVolumeRange(session, 0) } returns (0 to 100)
        every { UsbAudioController.getVolumeRange(session, 1) } returns null
        every { UsbAudioController.getVolumeRange(session, 2) } returns (0 to 50)

        controller.prepareForHalHandoff(session, channelCount = 2)

        verify(exactly = 0) { UsbAudioController.setVolume(session, any(), 1) }
        verify { UsbAudioController.setVolume(session, 100, 0) }
        verify { UsbAudioController.setVolume(session, 50, 2) }
    }

    @Test
    fun `prepareForHalHandoff always covers at least channels 0 through 2`() {
        val session = fakeSession()
        every { UsbAudioController.getVolumeRange(session, any()) } returns (0 to 10)

        controller.prepareForHalHandoff(session, channelCount = 0)

        verify { UsbAudioController.getVolumeRange(session, 0) }
        verify { UsbAudioController.getVolumeRange(session, 1) }
        verify { UsbAudioController.getVolumeRange(session, 2) }
    }

    @Test
    fun `remuteForUnconfirmedHandoff only mutes, never touches volume`() {
        val session = fakeSession()
        controller.remuteForUnconfirmedHandoff(session)
        verify(exactly = 1) { UsbAudioController.setMute(session, muted = true) }
        verify(exactly = 0) { UsbAudioController.setVolume(session, any(), any()) }
    }

    @Test
    fun `mirrorDbToStreamMusic does nothing when there is no live control state`() {
        controlStateFlow.value = null
        controller.mirrorDbToStreamMusic(-4352)
        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
    }

    @Test
    fun `mirrorDbToStreamMusic is a no-op when the curve-mapped index already matches`() {
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 9

        controller.mirrorDbToStreamMusic(-4352)

        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
    }

    @Test
    fun `mirrorDbToStreamMusic pushes the curve-mapped index when it differs from the current one`() {
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 3

        controller.mirrorDbToStreamMusic(-4352)

        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0) }
    }

    @Test
    fun `mirrorDbToStreamMusic's own write is ignored by the very next onStreamMusicChanged`() {
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 3
        every { usbSettings.bitPerfectMode } returns true
        controller.mirrorDbToStreamMusic(-4352)

        controller.onStreamMusicChanged(9)

        verify(exactly = 0) { usbSettings.volumeDb = any() }
    }

    @Test
    fun `onStreamMusicChanged ignores a non-fallback route`() {
        every { usbSettings.bitPerfectMode } returns true
        playbackRouteFlow.value = UsbAudioManager.PlaybackRoute.Direct(bitDepth = 24, sampleRateHz = 96000, channels = 2)
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15

        controller.onStreamMusicChanged(9)

        verify(exactly = 0) { usbSettings.volumeDb = any() }
    }

    @Test
    fun `onStreamMusicChanged ignores changes while bit-perfect mode is off`() {
        every { usbSettings.bitPerfectMode } returns false

        controller.onStreamMusicChanged(9)

        verify(exactly = 0) { usbSettings.volumeDb = any() }
    }

    @Test
    fun `onStreamMusicChanged ignores changes with no live control state`() {
        every { usbSettings.bitPerfectMode } returns true
        controlStateFlow.value = null

        controller.onStreamMusicChanged(9)

        verify(exactly = 0) { usbSettings.volumeDb = any() }
    }

    @Test
    fun `onStreamMusicChanged ignores a degenerate (zero-width) stream volume range`() {
        every { usbSettings.bitPerfectMode } returns true
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 0

        controller.onStreamMusicChanged(0)

        verify(exactly = 0) { usbSettings.volumeDb = any() }
    }

    @Test
    fun `onStreamMusicChanged persists the curve-mapped dB value for a real external change`() {
        every { usbSettings.bitPerfectMode } returns true
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15

        controller.onStreamMusicChanged(9)

        verify { usbSettings.volumeDb = -4352 }
    }

    @Test
    fun `onStreamMusicChanged clamps the computed dB to the device's reported range`() {
        controlStateFlow.value = controlState(volumeRange = -1000 to 0)
        every { usbSettings.bitPerfectMode } returns true
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15

        controller.onStreamMusicChanged(9)

        verify { usbSettings.volumeDb = -1000 }
    }

    @Test
    fun `abxSyncTargetDb returns null with no live control state`() {
        controlStateFlow.value = null
        assertNull(controller.abxSyncTargetDb())
    }

    @Test
    fun `abxSyncTargetDb returns null for a degenerate stream volume range`() {
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 0
        assertNull(controller.abxSyncTargetDb())
    }

    @Test
    fun `abxSyncTargetDb maps the current STREAM_MUSIC index through the curve`() {
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 9

        assertEquals(-4352, controller.abxSyncTargetDb())
    }
}
