/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioOutputActivationTest.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.streaming

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.UsbDacPreferences
import org.ostrichbat2k.usbdac.protocol.SyncType
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioController
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession
import org.ostrichbat2k.usbdac.protocol.UsbSampleRateConfigurator
import org.ostrichbat2k.usbdac.testutil.FakeFeatureUnit

/**
 * Drives [UsbAudioOutput.play]'s real background activation attempt -- claim, format, mute,
 * clock change -- with the clock write stubbed, along the paths that never reach [UsbNative]
 * (an attempt that ends up not starting the stream). Covers how that attempt interacts with
 * [UsbAudioOutput.pause]/[UsbAudioOutput.play] calls landing while it runs.
 */
class UsbAudioOutputActivationTest {
    private val topology =
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

    private val format =
        UacStreamFormat(
            alternateSetting = 1,
            bitResolution = 24,
            subslotSize = 4,
            clusterDescriptorId = -1,
            endpointAddress = 0x02,
            maxPacketSize = 192,
            syncType = SyncType.ASYNC,
        )

    private val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)

    private val session: UsbAudioSession =
        mockk<UsbDeviceConnection>(relaxed = true).let { connection ->
            featureUnit.install(connection)
            UsbAudioSession(
                device = mockk(relaxed = true),
                connection = connection,
                topology = topology,
                controlInterface = mockk<UsbInterface>(relaxed = true) { every { id } returns 0 },
                streamingAltSettings = emptyMap(),
            )
        }

    private val settings: UsbDacPreferences =
        mockk(relaxed = true) {
            every { volumeDb } returns -2000
            every { maxVolumePercent } returns 100
            // Relaxed, this would be a mocked queue shape of 0 URBs.
            every { urbQueueOverride } returns null
        }

    private val manager = mockk<UsbAudioManager>(relaxed = true)

    /** What the stubbed clock write runs just before returning, to land calls mid-attempt. */
    private var duringClockWrite: (hz: Int) -> Unit = {}

    /** What the stubbed phase-2 clock-settled poll runs just before returning. */
    private var duringClockSettle: () -> Unit = {}

    /**
     * A native stream that completes one URB per millisecond until cancelled, so the real engine
     * runs and [UsbAudioOutput]'s phase 2 sees the pre-roll established.
     */
    private open class TickingBridge(private val fatalAfterReaps: Int = Int.MAX_VALUE) : UsbNativeBridge {
        @Volatile private var cancelled = false
        private var slot = 0
        private var reaps = 0

        /** The thread that last reaped, i.e. the engine's streaming thread. */
        @Volatile var streamingThread: Thread? = null
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun disconnectKernelDriver(fd: Int, interfaceNumber: Int) = true

        override fun getUsbSpeed(fd: Int) = 3

        override fun openIsoStream(
            fd: Int,
            endpointAddress: Int,
            maxPacketSize: Int,
            packetsPerUrb: Int,
            numUrbs: Int,
        ): Long {
            cancelled = false
            return 1L
        }

        override fun submitIso(handle: Long, slot: Int, data: ByteArray, packetLengths: IntArray) = true

        override fun reapIsoAny(handle: Long): Long {
            streamingThread = Thread.currentThread()
            if (cancelled || ++reaps > fatalAfterReaps) return UsbNativeBridge.REAP_ERROR_FATAL.toLong()
            Thread.sleep(1)
            return UsbNativeBridge.reapResult(handle, slot++ % UrbQueueShape.DEFAULT.numUrbs)
        }

        override fun cancelIso(handle: Long) {
            cancelled = true
        }

        override fun closeIsoStream(handle: Long) {
            events += "closeIsoStream"
        }

        override fun submitIsoRead(handle: Long, slot: Int, packetLengths: IntArray) = true

        override fun readIsoSlotData(handle: Long, slot: Int, dst: ByteArray) = -1

        override fun getPacketErrorStats(handle: Long) = longArrayOf(0L, 0L)
    }

    private fun newOutput(sampleRateHz: Int = 44100, bridge: UsbNativeBridge = TickingBridge()) =
        UsbAudioOutput(
            session = session,
            format = format,
            channels = topology.channelCount,
            sampleRateHz = sampleRateHz,
            usbSettings = settings,
            usbAudioManager = manager,
            nativeBridge = bridge,
        )

    private lateinit var output: UsbAudioOutput

    @BeforeEach
    fun setUp() {
        mockkObject(UsbSampleRateConfigurator)
        every { UsbSampleRateConfigurator.writeSampleRateWithStreamIdle(any(), any(), any(), any(), any()) } answers
            {
                val hz = arg<Int>(2)
                duringClockWrite(hz)
                UsbSampleRateConfigurator.IdleRateWrite(
                    UsbSampleRateConfigurator.RateWrite(
                        UsbAudioController.ClockWriteOutcome.Confirmed(hz), retries = 0),
                    altRestored = true)
            }
        every { UsbSampleRateConfigurator.awaitClockSettled(any(), any(), any()) } answers
            {
                duringClockSettle()
                UsbSampleRateConfigurator.ClockSettle(valid = true, extraWaits = 0)
            }
        every { manager.ensureFormatActive(any(), any()) } returns true
        every { manager.consumeHardwareReset() } returns false
        output = newOutput()
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(UsbSampleRateConfigurator)
    }

    /**
     * Regression shape for #149: pause during the relock, so the attempt declines; then a play()
     * arrives after that decision but before the attempt has finished. That play() used to see
     * `activating` still set and do nothing, leaving nothing streaming. Now the attempt notices
     * on its way out and activates again -- here a second attempt whose claim fails, so it ends
     * there instead of reaching the native stream.
     */
    @Test
    fun `a play arriving as a declined activation finishes starts another attempt`() {
        every { manager.requestClaim() } returnsMany
            listOf(UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED, UsbAudioManager.ClaimOutcome.FAILED)
        duringClockWrite = { output.pause() }
        var lateplay = true
        every { manager.endClaimAttempt() } answers
            {
                if (lateplay) {
                    lateplay = false
                    output.play()
                }
            }

        output.play()

        verify(timeout = 2000, exactly = 2) { manager.requestClaim() }
        verify(exactly = 0) { manager.notifyStreamingStarted() }
    }

    /** #149: a pause that stays paused never retriggers -- the re-check isn't a retry loop. */
    @Test
    fun `a declined activation with no later play does not activate again`() {
        every { manager.requestClaim() } returns UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED
        duringClockWrite = { output.pause() }

        output.play()

        verify(timeout = 2000) { manager.endClaimAttempt() }
        Thread.sleep(100)
        verify(exactly = 1) { manager.requestClaim() }
    }

    /**
     * Regression shape for #143: a track change releases this output while its phase 2 is
     * running. It must not go on to restore volume or unmute -- the next output may be relocking
     * the device by then.
     */
    @Test
    fun `release during phase 2 stops before the volume restore and unmute`() {
        every { manager.requestClaim() } returns UsbAudioManager.ClaimOutcome.RECLAIMED
        duringClockSettle = { output.release() }

        output.play()

        verify(timeout = 2000) { manager.endClaimAttempt() }
        assertTrue(featureUnit.isMuted(0), "a released output unmuted the device")
        assertEquals(0, featureUnit.volumeOf(1), "a released output restored its volume")
    }

    /** Sanity check for the test above: without a release, phase 2 restores volume and unmutes. */
    @Test
    fun `phase 2 restores volume and unmutes when not released`() {
        every { manager.requestClaim() } returns UsbAudioManager.ClaimOutcome.RECLAIMED

        output.play()

        verify(timeout = 2000) { manager.endClaimAttempt() }
        assertTrue(!featureUnit.isMuted(0))
        assertEquals(-2000, featureUnit.volumeOf(1).toShort().toInt())
        output.release()
    }

    /**
     * #143: two outputs on one session (a track change) never interleave their device sequences.
     * The new output's relock waits until the old one's phase 2 has finished.
     */
    @Test
    fun `a second output's relock waits for the first output's phase 2`() {
        every { manager.requestClaim() } returns UsbAudioManager.ClaimOutcome.RECLAIMED
        val inSettle = CountDownLatch(1)
        val finishSettle = CountDownLatch(1)
        val clockWrites = java.util.concurrent.CopyOnWriteArrayList<Int>()
        duringClockWrite = { hz -> clockWrites += hz }
        duringClockSettle = {
            if (clockWrites.size == 1) {
                inSettle.countDown()
                finishSettle.await()
            }
        }

        output.play()
        assertTrue(inSettle.await(2, TimeUnit.SECONDS))
        val next = newOutput(sampleRateHz = 48000)
        next.play()
        Thread.sleep(200)
        assertEquals(listOf(44100), clockWrites.toList(), "the second relock overlapped the first's phase 2")

        finishSettle.countDown()
        verify(timeout = 2000) {
            UsbSampleRateConfigurator.writeSampleRateWithStreamIdle(any(), any(), 48000, any(), any())
        }
        assertEquals(listOf(44100, 48000), clockWrites.toList())
        output.release()
        next.release()
    }

    /**
     * Regression shape for #145: the stream dies mid-play. The session teardown must not run on
     * the streaming thread, and only after the engine has closed its native stream -- not with
     * the stream still open on the fd the teardown closes.
     */
    @Test
    fun `a fatal stream error tears the session down off the streaming thread, after the stream closes`() {
        every { manager.requestClaim() } returns UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED
        every { manager.consumeHardwareReset() } returns false
        val bridge = TickingBridge(fatalAfterReaps = 50)
        var fatalThread: Thread? = null
        every { manager.notifyStreamFatalError() } answers
            {
                fatalThread = Thread.currentThread()
                bridge.events += "notifyStreamFatalError"
            }
        val dying = newOutput(bridge = bridge)

        dying.play()

        verify(timeout = 3000) { manager.notifyStreamFatalError() }
        assertEquals(listOf("closeIsoStream", "notifyStreamFatalError"), bridge.events.toList())
        assertTrue(fatalThread != null && fatalThread !== bridge.streamingThread, "torn down on the streaming thread")
        verify { manager.notifyStreamingStopped() }
        dying.release()
    }
}
