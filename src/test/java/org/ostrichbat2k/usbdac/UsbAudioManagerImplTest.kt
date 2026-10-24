/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioManagerImplTest.kt is part of libusbdac.
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ostrichbat2k.usbdac.activation.UsbDacActivator
import org.ostrichbat2k.usbdac.discovery.UsbAudioDevice
import org.ostrichbat2k.usbdac.discovery.UsbDeviceDiscovery
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession

/**
 * Covers [UsbAudioManagerImpl]'s own orchestration responsibility: the [SessionState] machine
 * (Idle/Activating/Active), [UsbAudioManagerImpl.ensureActivated]'s once-per-attachment laziness
 * vs. [UsbAudioManagerImpl.retryActivation]'s explicit bypass, [UsbAudioManagerImpl.blockPlayback]
 * tracking activation in flight, and the enable/disable + bit-resolution-change re-activation
 * paths -- not the hardware-level work any of its collaborators do themselves (already covered by
 * their own test files).
 *
 * [dacActivator] and [deviceDiscovery] are both `internal var` specifically so this test can swap
 * in fakes for them (see their own comment in the production file) -- every other collaborator
 * (volumeController, claimController, controlWriter, abxTester, the real Room-backed
 * descriptorCache) is left as the real, internally-constructed instance, which is safe here only
 * because every test topology below sentinels every unit/clock/power-domain ID to -1: every
 * [org.ostrichbat2k.usbdac.protocol.UsbAudioController] read this drives (volume/mute/mixer/clock)
 * already short-circuits to null on a negative ID before ever touching the mocked
 * [android.hardware.usb.UsbDeviceConnection] -- confirmed against those functions' own source,
 * not assumed. The one real I/O this can't avoid is [UsbAudioManagerImpl.activateDac]'s own
 * post-success [UsbAudioManager.releaseClaim] call, which blocks (briefly) in
 * `awaitAndroidOutputTakeover` -- avoided here by stubbing [AudioManager.getDevices] to report
 * the device already taken over, which takes that function's fast path instead of its real
 * [android.media.AudioDeviceCallback]/`Looper.getMainLooper()` wait (which would throw outright in
 * a plain JVM unit test with no Robolectric).
 *
 * Deliberately out of scope, same tier as the rest of this module's hardware wrappers:
 * [UsbAudioManagerImpl.resolveActiveSink]'s *deep* success path (actually constructing a
 * [androidx.media3.exoplayer.audio.DefaultAudioSink]) -- reaching [isBitPerfectCapable] == true
 * would require also mocking [org.ostrichbat2k.usbdac.protocol.UsbAudioController]'s object
 * functions to fake a real volume-range readback, which is really exercising
 * [org.ostrichbat2k.usbdac.streaming.UsbAudioOutputProvider]'s own territory, not this class's
 * orchestration.
 */
class UsbAudioManagerImplTest {

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
            isAsync = true,
        )

    private fun fakeDevice(name: String = "Test DAC"): UsbDevice =
        mockk(relaxed = true) {
            every { deviceName } returns name
            every { productName } returns name
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

    private lateinit var context: Context
    private lateinit var usbManager: UsbManager
    private lateinit var audioManager: AudioManager
    private lateinit var usbSettings: UsbDacPreferences
    private lateinit var discovery: UsbDeviceDiscovery
    private lateinit var devicesFlow: MutableStateFlow<List<UsbAudioDevice>>
    private lateinit var dacActivator: UsbDacActivator
    private lateinit var manager: UsbAudioManagerImpl

    @Before
    fun setUp() {
        usbManager = mockk(relaxed = true)
        audioManager = mockk(relaxed = true)
        // Fast-paths awaitAndroidOutputTakeover (see this class's own doc comment) for every
        // device name used below -- a real device-name match isn't the point of this test file.
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns
            arrayOf(
                mockk<AudioDeviceInfo>(relaxed = true) {
                    every { type } returns AudioDeviceInfo.TYPE_USB_DEVICE
                    every { productName } returns "USB-Audio - Test DAC"
                })
        context =
            mockk(relaxed = true) {
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
        discovery = mockk(relaxed = true) { every { devices } returns devicesFlow }
        dacActivator = mockk(relaxed = true)

        manager = UsbAudioManagerImpl(context, usbSettings)
        manager.deviceDiscovery = discovery
        manager.dacActivator = dacActivator
    }

    @Test
    fun `start registers the settings listener and begins device discovery exactly once`() {
        manager.start()
        manager.start()

        verify(exactly = 1) { usbSettings.registerListener(manager) }
        verify(exactly = 1) { discovery.start() }
    }

    @Test
    fun `requestPermission delegates to device discovery`() {
        val device = fakeDevice()
        manager.requestPermission(device)
        verify { discovery.requestPermission(device) }
    }

    @Test
    fun `triggerActivation is a no-op when USB DAC output is disabled`() {
        every { usbSettings.enabled } returns false
        devicesFlow.value = listOf(UsbAudioDevice(fakeDevice(), hasPermission = true))

        manager.triggerActivation()

        verify(exactly = 0) { dacActivator.activate(any()) }
    }

    @Test
    fun `triggerActivation is a no-op when no attached device has permission`() {
        devicesFlow.value = listOf(UsbAudioDevice(fakeDevice(), hasPermission = false))

        manager.triggerActivation()

        verify(exactly = 0) { dacActivator.activate(any()) }
    }

    @Test
    fun `triggerActivation activates the first permitted device and publishes its controlState`() {
        val device = fakeDevice()
        every { dacActivator.activate(device) } returns UsbDacActivator.Result(fakeSession(device), testFormat, 44100)
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = true))

        manager.triggerActivation()
        runBlocking { manager.awaitActivation() }

        verify(exactly = 1) { dacActivator.activate(device) }
        assertNotNull(manager.controlState.value)
        org.junit.Assert.assertEquals(testFormat, manager.controlState.value?.activeFormat)
        assertTrue(manager.diagnosticLog.value.any { it.contains("DAC session ready") })
    }

    @Test
    fun `triggerActivation does not retry a device already attempted this attachment, but retryActivation does`() {
        val device = fakeDevice()
        every { dacActivator.activate(device) } returns UsbDacActivator.Result(fakeSession(device), testFormat, 44100)
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = true))

        manager.triggerActivation()
        runBlocking { manager.awaitActivation() }
        verify(exactly = 1) { dacActivator.activate(device) }

        manager.triggerActivation()
        runBlocking { manager.awaitActivation() }
        verify(exactly = 1) { dacActivator.activate(device) }

        manager.retryActivation()
        runBlocking { manager.awaitActivation() }
        verify(exactly = 2) { dacActivator.activate(device) }
    }

    @Test
    fun `a failed activation settles back to idle and is not retried by triggerActivation`() {
        val device = fakeDevice()
        every { dacActivator.activate(device) } returns null
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = true))

        manager.triggerActivation()
        runBlocking { manager.awaitActivation() }
        assertNull(manager.controlState.value)
        verify(exactly = 1) { dacActivator.activate(device) }

        manager.triggerActivation()
        runBlocking { manager.awaitActivation() }
        verify(exactly = 1) { dacActivator.activate(device) }
    }

    @Test
    fun `retryActivation requests permission instead of activating when the device isn't permitted yet`() {
        val device = fakeDevice()
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = false))

        manager.retryActivation()

        verify { discovery.requestPermission(device) }
        verify(exactly = 0) { dacActivator.activate(any()) }
    }

    @Test
    fun `blockPlayback is true the instant activation starts and false again once it settles`() {
        val device = fakeDevice()
        // A small artificial delay so the background activation thread (see runActivation)
        // can't race ahead of this test's very next assertion -- sessionState is set to
        // Activating synchronously before the thread even starts, but without this, a fast
        // mocked activate() can let the whole thing settle back to Idle before the main thread's
        // next line runs at all.
        every { dacActivator.activate(device) } answers
            {
                Thread.sleep(50)
                UsbDacActivator.Result(fakeSession(device), testFormat, 44100)
            }
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = true))

        assertFalse(manager.blockPlayback.value)
        manager.triggerActivation()
        assertTrue(manager.blockPlayback.value)

        runBlocking { manager.awaitActivation() }
        assertFalse(manager.blockPlayback.value)
    }

    @Test
    fun `blockPlayback stays false while activating when bit-perfect mode is off`() {
        every { usbSettings.bitPerfectMode } returns false
        val device = fakeDevice()
        every { dacActivator.activate(device) } returns UsbDacActivator.Result(fakeSession(device), testFormat, 44100)
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = true))

        manager.triggerActivation()
        assertFalse(manager.blockPlayback.value)
        runBlocking { manager.awaitActivation() }
    }

    @Test
    fun `onBitPerfectModeChanged recomputes blockPlayback for an activation already in flight`() {
        val device = fakeDevice()
        every { dacActivator.activate(device) } answers
            {
                Thread.sleep(50)
                UsbDacActivator.Result(fakeSession(device), testFormat, 44100)
            }
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = true))

        manager.triggerActivation()
        assertTrue(manager.blockPlayback.value)

        every { usbSettings.bitPerfectMode } returns false
        manager.onBitPerfectModeChanged()
        assertFalse(manager.blockPlayback.value)

        runBlocking { manager.awaitActivation() }
    }

    @Test
    fun `resolveActiveSink with trigger=false never activates and returns null`() {
        devicesFlow.value = listOf(UsbAudioDevice(fakeDevice(), hasPermission = true))

        val sink = manager.resolveActiveSink(trigger = false)

        assertNull(sink)
        verify(exactly = 0) { dacActivator.activate(any()) }
    }

    @Test
    fun `resolveActiveSink with trigger=true activates and awaits before deciding`() {
        val device = fakeDevice()
        every { dacActivator.activate(device) } returns UsbDacActivator.Result(fakeSession(device), testFormat, 44100)
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = true))

        // Not bit-perfect-capable (volumeRange is null -- outputFeatureUnitId is -1), so still
        // null -- but the activation it triggered must have been awaited and completed, not left
        // racing in the background.
        val sink = manager.resolveActiveSink(trigger = true)

        assertNull(sink)
        verify(exactly = 1) { dacActivator.activate(device) }
        assertNotNull(manager.controlState.value)
    }

    @Test
    fun `resolveActiveSink returns null once bit-perfect mode is turned off, even with a live session`() {
        val device = fakeDevice()
        every { dacActivator.activate(device) } returns UsbDacActivator.Result(fakeSession(device), testFormat, 44100)
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = true))
        manager.triggerActivation()
        runBlocking { manager.awaitActivation() }
        assertNotNull(manager.controlState.value)

        every { usbSettings.bitPerfectMode } returns false

        assertNull(manager.resolveActiveSink(trigger = false))
    }

    @Test
    fun `setPreferredBitResolution re-activates the current device`() {
        val device = fakeDevice()
        every { dacActivator.activate(device) } returns UsbDacActivator.Result(fakeSession(device), testFormat, 44100)
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = true))

        manager.setPreferredBitResolution(24)
        runBlocking { manager.awaitActivation() }

        verify(exactly = 1) { dacActivator.activate(device) }
    }

    @Test
    fun `onUsbEnabledChanged(false) deactivates an active session and clears controlState`() {
        val device = fakeDevice()
        every { dacActivator.activate(device) } returns UsbDacActivator.Result(fakeSession(device), testFormat, 44100)
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = true))
        manager.triggerActivation()
        runBlocking { manager.awaitActivation() }
        assertNotNull(manager.controlState.value)

        every { usbSettings.enabled } returns false
        manager.onUsbEnabledChanged()

        assertNull(manager.controlState.value)
        assertFalse(manager.blockPlayback.value)
    }

    @Test
    fun `onUsbEnabledChanged(true) attempts activation of the attached device`() {
        val device = fakeDevice()
        every { dacActivator.activate(device) } returns UsbDacActivator.Result(fakeSession(device), testFormat, 44100)
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = true))

        manager.onUsbEnabledChanged()
        runBlocking { manager.awaitActivation() }

        verify(exactly = 1) { dacActivator.activate(device) }
    }

    @Test
    fun `getPowerDomainState and getClockInfo are null with no active session`() {
        assertNull(manager.getPowerDomainState())
        assertNull(manager.getClockInfo())
    }

    @Test
    fun `ensureFormatActive returns false with no active session`() {
        assertFalse(manager.ensureFormatActive(testFormat, 44100))
    }

    @Test
    fun `isBitPerfectCapable is false with no active session`() {
        assertFalse(manager.isBitPerfectCapable())
    }

    @Test
    fun `halOutputSampleRate reads AudioManager's own output-sample-rate property`() {
        every { audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) } returns "48000"
        org.junit.Assert.assertEquals(48000, manager.halOutputSampleRate())
    }

    @Test
    fun `reportPlaybackRoute publishes to playbackRoute`() {
        val route = UsbAudioManager.PlaybackRoute.Direct(bitDepth = 24, sampleRateHz = 44100, channels = 2)
        manager.reportPlaybackRoute(route)
        org.junit.Assert.assertEquals(route, manager.playbackRoute.value)
    }

    @Test
    fun `reportPipelineStats publishes to pipelineStats, including clearing it back to null`() {
        val stats =
            org.ostrichbat2k.usbdac.streaming.UsbPipelineStats(
                atMs = 0L,
                urbsSubmitted = 100L,
                reapErrors = 0,
                underrunCount = 0,
                bufferedBytes = 2048,
                bufferCapacityBytes = 4096)
        manager.reportPipelineStats(stats)
        org.junit.Assert.assertEquals(stats, manager.pipelineStats.value)

        manager.reportPipelineStats(null)
        assertNull(manager.pipelineStats.value)
    }
}
