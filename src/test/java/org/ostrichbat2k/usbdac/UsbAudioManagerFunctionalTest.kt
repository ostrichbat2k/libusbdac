/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioManagerFunctionalTest.kt is part of libusbdac.
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
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ostrichbat2k.usbdac.UsbAudioManager.ClaimOutcome
import org.ostrichbat2k.usbdac.activation.UsbDacActivator
import org.ostrichbat2k.usbdac.control.UsbVolumeControllerImpl
import org.ostrichbat2k.usbdac.discovery.UsbAudioDevice
import org.ostrichbat2k.usbdac.discovery.UsbDeviceDiscovery
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioController
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession
import org.ostrichbat2k.usbdac.testutil.FakeFeatureUnit

/**
 * Scope A functional tests (see [[project-auxio-testing-coverage]]'s memory entry for the
 * two-phase plan this follows): the *real* [UsbAudioManagerImpl] wired to its *real*
 * [org.ostrichbat2k.usbdac.control.UsbVolumeControllerImpl], real
 * [org.ostrichbat2k.usbdac.claim.UsbClaimControllerImpl], real
 * [org.ostrichbat2k.usbdac.control.UsbControlWriterImpl], and real [UsbAudioController] --
 * exactly as production constructs them. Only [UsbAudioManagerImpl.dacActivator] and
 * [UsbAudioManagerImpl.deviceDiscovery] are swapped for fakes (the same two seams
 * [UsbAudioManagerImplTest] uses), and only the actual system boundary is mocked: [Context],
 * [UsbManager], [AudioManager], [UsbDevice], [UsbDeviceConnection].
 *
 * Unlike the per-class unit tests, every topology below uses *real* (non-sentinel) entity IDs, so
 * [UsbAudioController]'s actual control-transfer wire logic runs for real against
 * [FakeFeatureUnit]'s stateful register instead of short-circuiting on a `-1` ID.
 *
 * Motivated by a real, previously-reproduced regression (see `UsbAudioOutput.kt`'s own comment:
 * "switch to DAC, press volume down once, volume jumps to max") that has zero coverage today --
 * [UsbVolumeControllerImpl.prepareForHalHandoff]'s force-to-unity side is unit-tested, but nothing
 * exercises it *together* with [UsbVolumeControllerImpl.mirrorDbToStreamMusic] through the real
 * manager the way production actually sequences them.
 */
class UsbAudioManagerFunctionalTest {

    private val testTopology =
        UacPlaybackTopology(
            configurationValue = 1,
            controlInterfaceNumber = 0,
            streamingInterfaceNumber = 1,
            uacVersion = 2,
            clockSourceId = -1,
            outputFeatureUnitId = 7,
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

    private lateinit var context: Context
    private lateinit var usbManager: UsbManager
    private lateinit var audioManager: AudioManager
    private lateinit var usbSettings: UsbDacPreferences
    private lateinit var discovery: UsbDeviceDiscovery
    private lateinit var devicesFlow: MutableStateFlow<List<UsbAudioDevice>>
    private lateinit var dacActivator: UsbDacActivator
    private lateinit var manager: UsbAudioManagerImpl

    private var volumeDbBacking: Int? = null

    @Before
    fun setUp() {
        usbManager = mockk(relaxed = true)
        audioManager = mockk(relaxed = true)
        // Fast-paths awaitAndroidOutputTakeover (same technique as UsbAudioManagerImplTest) --
        // real recognition latency isn't what this file is testing.
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
                every { volumeDb = any() } answers { volumeDbBacking = it.invocation.args[0] as Int? }
                every { volumeDb } answers { volumeDbBacking }
            }
        devicesFlow = MutableStateFlow(emptyList())
        discovery = mockk(relaxed = true) { every { devices } returns devicesFlow }
        dacActivator = mockk(relaxed = true)

        manager = UsbAudioManagerImpl(context, usbSettings)
        manager.deviceDiscovery = discovery
        manager.dacActivator = dacActivator
    }

    private fun fakeDevice(name: String = "Test DAC"): UsbDevice =
        mockk(relaxed = true) {
            every { deviceName } returns name
            every { productName } returns name
            every { vendorId } returns 0x1234
            every { productId } returns 0x5678
        }

    /** Activates a real session backed by [featureUnit], claiming the device and awaiting it. */
    private fun activate(featureUnit: FakeFeatureUnit): UsbAudioSession {
        val device = fakeDevice()
        val connection = mockk<UsbDeviceConnection>(relaxed = true)
        every { connection.claimInterface(any(), any()) } returns true
        every { connection.setInterface(any()) } returns true
        featureUnit.install(connection)
        val session =
            UsbAudioSession(
                device = device,
                connection = connection,
                topology = testTopology,
                controlInterface = mockk<UsbInterface>(relaxed = true) { every { id } returns 0 },
                streamingAltSettings = mapOf(1 to mockk(relaxed = true)),
            )
        every { dacActivator.activate(device) } returns UsbDacActivator.Result(session, testFormat, 44100)
        devicesFlow.value = listOf(UsbAudioDevice(device, hasPermission = true))
        manager.triggerActivation()
        runBlocking { manager.awaitActivation() }
        // activateDac's own tail release (awaitAndroidTakeover=true, hard-coded -- see its doc
        // comment) blocks on a real Handler(Looper.getMainLooper())/AudioDeviceCallback path that
        // is genuinely racy under this module's full test suite's heavy background-thread load
        // (confirmed, reproduced: solo this file is always deterministic; alongside ~300 other
        // tests' own lingering executors, the fast-path getDevices() check this test stubs for
        // sometimes loses the race, non-deterministically leaving the claim re-acquired instead
        // of released). Re-normalizing here with awaitAndroidTakeover=false -- the same
        // non-blocking variant every *other* real call site but RoutingAudioSink's fallback path
        // actually uses -- forces a deterministic unclaimed baseline synchronously (the
        // prepareForHalHandoff/releaseToKernel/interfaceClaimed=false part of doReleaseClaim never
        // depends on awaitAndroidTakeover), pushing the racy wait itself onto a background
        // scheduler thread this test never has to synchronize with.
        manager.releaseClaim(delayMs = 0, awaitAndroidTakeover = false, deactivateOnTimeout = false)
        return session
    }

    // ---- Scenario: claim-lapse distinction (ALREADY_CLAIMED vs RECLAIMED) end to end ----

    @Test
    fun `requestClaim reclaims from the kernel right after activation, then is a no-op while already held`() {
        val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)
        activate(featureUnit)

        // activate()'s own helper already handed the interface back to the kernel (see its doc
        // comment) -- the first real caller-side requestClaim() genuinely has to reclaim it.
        assertEquals(ClaimOutcome.RECLAIMED, manager.requestClaim())
        // A second call with nothing having released it in between must be a pure no-op.
        assertEquals(ClaimOutcome.ALREADY_CLAIMED, manager.requestClaim())
    }

    @Test
    fun `requestClaim reclaims again after a real releaseClaim hands the interface back`() {
        val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)
        activate(featureUnit)
        manager.requestClaim()

        manager.releaseClaim(delayMs = 0, awaitAndroidTakeover = false, deactivateOnTimeout = false)

        assertEquals(ClaimOutcome.RECLAIMED, manager.requestClaim())
    }

    // ---- Scenario: route-switch loudness matching (the volume-jump regression's own invariant) ----

    @Test
    fun `releasing a live session forces the Feature Unit to unity while STREAM_MUSIC already matches the last bit-perfect dB`() {
        val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)
        val session = activate(featureUnit)
        manager.requestClaim()
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 3

        // "Direct playback at some dB", seeded via the real UsbAudioController.setVolume directly
        // -- deliberately not manager.setVolume(), whose own background write executor would race
        // this test's own assertions below under a loaded JVM (confirmed, reproduced: the write
        // sometimes lands *after* this test's own unity-forcing assertions, re-overwriting them).
        // UsbControlWriterImpl's own async write path is already covered by its own unit test.
        for (channel in 0..2) UsbAudioController.setVolume(session, -4352, channel)

        // A second, throwaway UsbVolumeControllerImpl wired to the manager's own real
        // controlState/playbackRoute flows -- the real class, just not the one instance
        // UsbAudioManagerImpl happens to build internally, so this can call
        // mirrorDbToStreamMusic() directly and synchronously instead of going through
        // manager.setVolume()'s async write path to reach it.
        val volumeController =
            UsbVolumeControllerImpl(audioManager, usbSettings, manager.controlState, manager.playbackRoute, logEvent = {})
        // -4352 (-17dB*256) lands exactly on a mediaVolumeCurve breakpoint (60% of a 0..15 range).
        volumeController.mirrorDbToStreamMusic(-4352)
        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0) }

        // Now the track switches away from bit-perfect (a pause, a track that can't use the DAC)
        // and the interface gets handed back to the kernel for HAL to pick up -- the real
        // orchestration path (UsbAudioManagerImpl -> UsbClaimControllerImpl ->
        // UsbVolumeControllerImpl.prepareForHalHandoff), not a direct call this time.
        manager.releaseClaim(delayMs = 0, awaitAndroidTakeover = false, deactivateOnTimeout = false)

        // prepareForHalHandoff's real effect: every channel forced to the Feature Unit's own
        // reported max (unity), not left wherever the last bit-perfect write put it -- this is
        // exactly the invariant whose absence (starting the force-to-unity loop at channel 1, not
        // 0) caused the real "volume jumps to max" regression this test is motivated by.
        assertEquals(0, featureUnit.volumeOf(0))
        assertEquals(0, featureUnit.volumeOf(1))
        assertEquals(0, featureUnit.volumeOf(2))
    }

    // ---- Scenario: ABX trial volume sync through the real tester + volume controller ----

    @Test
    fun `a bit-perfect ABX trial syncs the hardware to STREAM_MUSIC's current loudness before playing`() {
        val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)
        activate(featureUnit)
        every { usbSettings.abxTestMode } returns true
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        // Index 9 of 0..15, the same 60%-of-range breakpoint as the other scenario (curve maps
        // it to exactly -17dB, i.e. -4352 in 1/256dB units) -- the direction here is index->dB
        // (curveDbAtPercent), not dB->index, so this has to be picked independently of that
        // scenario's own dB input, not assumed to match it.
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 9

        // rollTrial()'s coin flip is genuinely random -- loop until it lands on bit-perfect
        // (overwhelmingly likely well within this bound) rather than mocking kotlin.random.Random.
        var isBitPerfect = false
        repeat(50) {
            manager.rollAbxTrial()
            if (manager.abxGroundTruth.value == true) isBitPerfect = true
        }
        assertTrue("rollAbxTrial never landed on bit-perfect in 50 tries", isBitPerfect)

        // syncAbxLoudnessToBitPerfect's real effect: manager.setVolume() got called with exactly
        // the dB abxSyncTargetDb computed from STREAM_MUSIC's current index (-4352, same
        // breakpoint as the other scenario) -- checked via setVolume()'s own synchronous
        // usbSettings.volumeDb write, not the asynchronous hardware round-trip that follows it
        // (already covered by UsbControlWriterImplTest).
        assertEquals(-4352, volumeDbBacking)
    }
}
