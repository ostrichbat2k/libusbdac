/*
 * Copyright (c) 2026 libusbdac Project
 * UsbClaimControllerImplTest.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.claim

import android.hardware.usb.UsbDevice
import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.control.UsbVolumeController
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UsbAudioController
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession

/**
 * Deliberately does not exercise awaitAndroidOutputTakeover's real timeout path (the
 * AudioDeviceCallback/Handler(Looper.getMainLooper()) machinery, or UsbAudioTuning
 * .AWAIT_TAKEOVER_TIMEOUT_MS actually elapsing) -- every test here stubs [AudioManager.getDevices]
 * to already report the live session's device as a recognized output, so that wait's fast,
 * no-callback-needed path always short-circuits. That machinery is thin, real-hardware-timing-
 * driven plumbing on top of the state machine this class actually owns, which is what these
 * tests cover: claim/release sequencing, the active-streaming release guard, and delayed-release
 * scheduling/cancellation.
 */
class UsbClaimControllerImplTest {

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

    private fun fakeDevice(productName: String? = "Test DAC", deviceName: String = "/dev/bus/usb/001/002"): UsbDevice {
        val device = mockk<UsbDevice>(relaxed = true)
        every { device.productName } returns productName
        every { device.deviceName } returns deviceName
        return device
    }

    private fun fakeSession(device: UsbDevice = fakeDevice()) =
        UsbAudioSession(
            device = device,
            connection = mockk(relaxed = true),
            topology = testTopology,
            controlInterface = mockk(relaxed = true),
            streamingAltSettings = emptyMap(),
        )

    /** Reports [device] as an already-recognized Android audio output -- see the class doc. */
    private fun stubAlreadyRecognized(device: UsbDevice) {
        val targetName = device.productName ?: device.deviceName
        val info =
            mockk<AudioDeviceInfo>(relaxed = true) {
                every { type } returns AudioDeviceInfo.TYPE_USB_HEADSET
                every { productName } returns "USB-Audio - $targetName"
            }
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(info)
    }

    private lateinit var audioManager: AudioManager
    private lateinit var volumeController: UsbVolumeController
    private var activeSession: UsbAudioSession? = null
    private var reclaimedCalls = 0
    private var claimFailedCalls = 0
    private var takeoverTimeoutCalls = 0
    private lateinit var controller: UsbClaimControllerImpl

    @Before
    fun setUp() {
        mockkObject(UsbAudioController)
        audioManager = mockk(relaxed = true)
        volumeController = mockk(relaxed = true)
        activeSession = null
        controller =
            UsbClaimControllerImpl(
                audioManager = audioManager,
                volumeController = volumeController,
                getActiveSession = { activeSession },
                onReclaimed = { reclaimedCalls++ },
                onClaimFailed = { claimFailedCalls++ },
                onTakeoverTimeout = { takeoverTimeoutCalls++ },
            )
    }

    @After
    fun tearDown() {
        unmockkObject(UsbAudioController)
    }

    @Test
    fun `isClaimed reflects markAlreadyClaimed and markReleased`() {
        assertFalse(controller.isClaimed())
        controller.markAlreadyClaimed()
        assertTrue(controller.isClaimed())
        controller.markReleased()
        assertFalse(controller.isClaimed())
    }

    @Test
    fun `requestClaim returns ALREADY_CLAIMED without touching the device when already claimed`() {
        controller.markAlreadyClaimed()
        val result = controller.requestClaim(fakeSession())
        assertEquals(UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED, result)
        verify(exactly = 0) { UsbAudioController.reclaimFromKernel(any()) }
    }

    @Test
    fun `requestClaim returns FAILED without touching the device when there is no session`() {
        val result = controller.requestClaim(null)
        assertEquals(UsbAudioManager.ClaimOutcome.FAILED, result)
        verify(exactly = 0) { UsbAudioController.reclaimFromKernel(any()) }
    }

    @Test
    fun `requestClaim marks claimed and fires onReclaimed on a successful reclaim`() {
        val session = fakeSession()
        every { UsbAudioController.reclaimFromKernel(session) } returns true

        val result = controller.requestClaim(session)

        assertEquals(UsbAudioManager.ClaimOutcome.RECLAIMED, result)
        assertTrue(controller.isClaimed())
        assertEquals(1, reclaimedCalls)
        assertEquals(0, claimFailedCalls)
    }

    @Test
    fun `requestClaim stays unclaimed and fires onClaimFailed on a failed reclaim`() {
        val session = fakeSession()
        every { UsbAudioController.reclaimFromKernel(session) } returns false

        val result = controller.requestClaim(session)

        assertEquals(UsbAudioManager.ClaimOutcome.FAILED, result)
        assertFalse(controller.isClaimed())
        assertEquals(0, reclaimedCalls)
        assertEquals(1, claimFailedCalls)
    }

    @Test
    fun `requestClaim cancels a pending delayed release`() {
        val device = fakeDevice()
        val session = fakeSession(device)
        stubAlreadyRecognized(device)
        activeSession = session
        controller.markAlreadyClaimed()

        controller.releaseClaim(delayMs = 150, awaitAndroidTakeover = false)
        val result = controller.requestClaim(session) // still claimed -- cancels the pending release

        assertEquals(UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED, result)
        Thread.sleep(300)
        verify(exactly = 0) { UsbAudioController.releaseToKernel(any()) }
    }

    @Test
    fun `releaseClaim(0) releases a claimed active session synchronously, muting before releasing`() {
        val device = fakeDevice()
        val session = fakeSession(device)
        stubAlreadyRecognized(device)
        activeSession = session
        controller.markAlreadyClaimed()

        controller.releaseClaim(delayMs = 0, awaitAndroidTakeover = false)

        assertFalse(controller.isClaimed())
        verifyOrder {
            volumeController.prepareForHalHandoff(session, session.topology.channelCount)
            UsbAudioController.releaseToKernel(session)
        }
    }

    @Test
    fun `releaseClaim(0) does nothing when there is no active session`() {
        controller.markAlreadyClaimed()
        activeSession = null

        controller.releaseClaim(delayMs = 0, awaitAndroidTakeover = false)

        assertTrue(controller.isClaimed())
        verify(exactly = 0) { UsbAudioController.releaseToKernel(any()) }
    }

    @Test
    fun `releaseClaim(0) does nothing when the interface isn't currently claimed`() {
        val device = fakeDevice()
        val session = fakeSession(device)
        stubAlreadyRecognized(device)
        activeSession = session
        // never claimed

        controller.releaseClaim(delayMs = 0, awaitAndroidTakeover = false)

        verify(exactly = 0) { UsbAudioController.releaseToKernel(any()) }
    }

    @Test
    fun `releaseClaim is suppressed entirely while a stream is actively using the claim`() {
        val device = fakeDevice()
        val session = fakeSession(device)
        stubAlreadyRecognized(device)
        activeSession = session
        controller.markAlreadyClaimed()
        controller.notifyStreamingStarted()

        controller.releaseClaim(delayMs = 0, awaitAndroidTakeover = false)

        assertTrue(controller.isClaimed())
        verify(exactly = 0) { UsbAudioController.releaseToKernel(any()) }
    }

    @Test
    fun `releaseClaim proceeds once the active stream count returns to zero`() {
        val device = fakeDevice()
        val session = fakeSession(device)
        stubAlreadyRecognized(device)
        activeSession = session
        controller.markAlreadyClaimed()
        controller.notifyStreamingStarted()
        controller.notifyStreamingStopped()

        controller.releaseClaim(delayMs = 0, awaitAndroidTakeover = false)

        assertFalse(controller.isClaimed())
        verify { UsbAudioController.releaseToKernel(session) }
    }

    @Test
    fun `notifyStreamingStopped never drives the active count below zero`() {
        val device = fakeDevice()
        val session = fakeSession(device)
        stubAlreadyRecognized(device)
        activeSession = session
        controller.markAlreadyClaimed()

        controller.notifyStreamingStopped() // no matching start -- must clamp at 0, not go negative
        controller.notifyStreamingStopped()
        controller.releaseClaim(delayMs = 0, awaitAndroidTakeover = false)

        assertFalse(controller.isClaimed())
        verify { UsbAudioController.releaseToKernel(session) }
    }

    @Test
    fun `releaseClaim with a positive delay does not release immediately but does after it elapses`() {
        val device = fakeDevice()
        val session = fakeSession(device)
        stubAlreadyRecognized(device)
        activeSession = session
        controller.markAlreadyClaimed()

        controller.releaseClaim(delayMs = 100, awaitAndroidTakeover = false)

        verify(exactly = 0) { UsbAudioController.releaseToKernel(any()) }
        verify(timeout = 1000) { UsbAudioController.releaseToKernel(session) }
    }

    @Test
    fun `cancelPendingRelease cancels a scheduled release before it fires`() {
        val device = fakeDevice()
        val session = fakeSession(device)
        stubAlreadyRecognized(device)
        activeSession = session
        controller.markAlreadyClaimed()

        controller.releaseClaim(delayMs = 100, awaitAndroidTakeover = false)
        controller.cancelPendingRelease()

        Thread.sleep(300)
        verify(exactly = 0) { UsbAudioController.releaseToKernel(any()) }
        assertTrue(controller.isClaimed())
    }

    @Test
    fun `releaseClaim awaiting Android takeover completes without hanging when already recognized`() {
        val device = fakeDevice(productName = null, deviceName = "usb-device-7")
        val session = fakeSession(device)
        stubAlreadyRecognized(device) // exercises the null-productName falls back to deviceName branch
        activeSession = session
        controller.markAlreadyClaimed()

        controller.releaseClaim(delayMs = 0, awaitAndroidTakeover = true)

        assertFalse(controller.isClaimed())
        verify { UsbAudioController.releaseToKernel(session) }
    }
}
