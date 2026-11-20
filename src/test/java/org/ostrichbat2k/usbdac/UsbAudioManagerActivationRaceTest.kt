/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioManagerActivationRaceTest.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ostrichbat2k.usbdac.activation.UsbDacActivator
import org.ostrichbat2k.usbdac.discovery.UsbAudioDevice
import org.ostrichbat2k.usbdac.discovery.UsbDeviceDiscovery
import org.ostrichbat2k.usbdac.protocol.SyncType
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession

/**
 * #144: [UsbAudioManagerImpl]'s session-state transitions are tied to the activation that made
 * them -- overlapping activations, a deactivation mid-activation, and a stale activation settling
 * after a newer one has started. Same fixture as [UsbAudioManagerImplTest] (see its doc comment
 * for why the real collaborators are safe here), with an activator scripted per call.
 */
class UsbAudioManagerActivationRaceTest {
    private val testTopology =
        UacPlaybackTopology(
            configurationValue = 1,
            controlInterfaceNumber = 0,
            streamingInterfaceNumber = 1,
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

    private val testFormat =
        UacStreamFormat(
            alternateSetting = 1,
            bitResolution = 24,
            subslotSize = 4,
            clusterDescriptorId = -1,
            endpointAddress = 0x02,
            maxPacketSize = 192,
            syncType = SyncType.ASYNC,
        )

    private fun fakeDevice(): UsbDevice =
        mockk(relaxed = true) {
            every { deviceName } returns "Test DAC"
            every { productName } returns "Test DAC"
            every { vendorId } returns 0x1234
            every { productId } returns 0x5678
        }

    private fun fakeSession(device: UsbDevice) =
        UsbAudioSession(
            device = device,
            connection = mockk(relaxed = true),
            topology = testTopology,
            controlInterface = mockk(relaxed = true),
            streamingAltSettings = emptyMap(),
        )

    private lateinit var usbSettings: UsbDacPreferences
    private lateinit var devicesFlow: MutableStateFlow<List<UsbAudioDevice>>
    private lateinit var dacActivator: UsbDacActivator
    private lateinit var manager: UsbAudioManagerImpl

    @BeforeEach
    fun setUp() {
        val usbManager = mockk<UsbManager>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        // Fast-paths awaitAndroidOutputTakeover, as in UsbAudioManagerImplTest.
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns
            arrayOf(
                mockk<AudioDeviceInfo>(relaxed = true) {
                    every { type } returns AudioDeviceInfo.TYPE_USB_DEVICE
                    every { productName } returns "USB-Audio - Test DAC"
                })
        val context =
            mockk<Context>(relaxed = true) {
                every { getSystemService(Context.USB_SERVICE) } returns usbManager
                every { getSystemService(Context.AUDIO_SERVICE) } returns audioManager
                every { packageName } returns "org.ostrichbat2k.usbdac.test"
            }
        usbSettings =
            mockk(relaxed = true) {
                every { enabled } returns true
                every { bitPerfectMode } returns true
                every { abxTestMode } returns false
            }
        devicesFlow = MutableStateFlow(emptyList())
        dacActivator = mockk(relaxed = true)
        manager = UsbAudioManagerImpl(context, usbSettings)
        manager.deviceDiscovery = mockk<UsbDeviceDiscovery>(relaxed = true) { every { devices } returns devicesFlow }
        manager.dacActivator = dacActivator
    }

    @AfterEach
    fun tearDown() {
        manager.joinActivationThreadForTest()
        manager.shutdownExecutorsForTest()
    }

    /**
     * A device whose [dacActivator] answers each activation in turn from [results], each first
     * waiting for its own latch in [gates] (count 0 = don't wait). Returns the device.
     */
    private fun scriptedActivations(
        results: List<UsbDacActivator.Result?>,
        gates: List<CountDownLatch>,
        device: UsbDevice = fakeDevice(),
    ): UsbDevice {
        val calls = AtomicInteger()
        every { dacActivator.activate(device) } answers
            {
                val i = calls.getAndIncrement()
                gates[i].await(2, TimeUnit.SECONDS)
                results[i]
            }
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = true))
        return device
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    @Test
    fun `a retryActivation during an activation leaves exactly one session, the newer one`() {
        val device = fakeDevice()
        val first = fakeSession(device)
        val second = fakeSession(device)
        val firstGate = CountDownLatch(1)
        scriptedActivations(
            listOf(
                UsbDacActivator.Result(first, testFormat, 44100),
                UsbDacActivator.Result(second, testFormat, 44100)),
            listOf(firstGate, CountDownLatch(0)),
            device)

        manager.triggerActivation()
        manager.retryActivation() // the first activation is still blocked in activate()
        waitFor { manager.controlState.value != null }
        val secondThread = manager.activationThreadForTest()
        firstGate.countDown()
        Thread.sleep(200) // lets the first activation finish
        secondThread?.join(2000)

        verify(exactly = 1) { first.connection.close() }
        verify(exactly = 0) { second.connection.close() }
        assertSame(second, manager.activeSessionForTest())
    }

    @Test
    fun `a deactivation during an activation closes the late session and stays idle`() {
        val device = fakeDevice()
        val late = fakeSession(device)
        val gate = CountDownLatch(1)
        scriptedActivations(listOf(UsbDacActivator.Result(late, testFormat, 44100)), listOf(gate), device)

        manager.triggerActivation()
        every { usbSettings.enabled } returns false
        manager.onUsbEnabledChanged() // USB output turned off mid-activation
        gate.countDown()
        manager.joinActivationThreadForTest()

        verify(exactly = 1) { late.connection.close() }
        assertNull(manager.activeSessionForTest())
        assertNull(manager.controlState.value)
    }

    @Test
    fun `a stale activation failing does not clear a newer activation's Activating`() {
        val device = fakeDevice()
        val firstGate = CountDownLatch(1)
        val secondGate = CountDownLatch(1)
        scriptedActivations(
            listOf(null, UsbDacActivator.Result(fakeSession(device), testFormat, 44100)),
            listOf(firstGate, secondGate),
            device)

        manager.triggerActivation()
        manager.retryActivation() // a second activation, now blocked in activate()
        firstGate.countDown() // the first one fails and settles
        Thread.sleep(200)

        assertTrue(manager.blockPlayback.value, "the newer activation is still running")
        secondGate.countDown()
        manager.joinActivationThreadForTest()
        assertFalse(manager.blockPlayback.value)
    }

    @Test
    fun `deactivating twice closes the session once`() {
        val device = fakeDevice()
        val session = fakeSession(device)
        scriptedActivations(
            listOf(UsbDacActivator.Result(session, testFormat, 44100)),
            listOf(CountDownLatch(0)),
            device)
        manager.triggerActivation()
        runBlocking { manager.awaitActivation() }
        every { usbSettings.enabled } returns false

        manager.onUsbEnabledChanged()
        manager.onUsbEnabledChanged()

        verify(exactly = 1) { session.connection.close() }
    }
}
