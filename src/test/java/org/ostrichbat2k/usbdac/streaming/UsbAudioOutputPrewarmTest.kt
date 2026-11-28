/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioOutputPrewarmTest.kt is part of libusbdac.
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
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.UsbAudioManager.OutputWarmState
import org.ostrichbat2k.usbdac.UsbDacPreferences
import org.ostrichbat2k.usbdac.protocol.SyncType
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession

/**
 * [UsbAudioOutput.prewarm] (#156): the relock a host wants to wait for before it starts playback.
 * The success path ends in `startStreamingThread()`, which needs [UsbNative] and so cannot run in a
 * plain JVM test (same limit the other [UsbAudioOutput] tests work around); these drive the
 * parts that don't -- registration, what a pre-warm does and does not start, how it ends when
 * it is cancelled or fails, and the state it reports -- through a mocked [UsbAudioManager].
 */
class UsbAudioOutputPrewarmTest {
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

    private val session =
        UsbAudioSession(
            device = mockk(relaxed = true),
            connection = mockk<UsbDeviceConnection>(relaxed = true),
            topology = topology,
            controlInterface = mockk<UsbInterface>(relaxed = true) { every { id } returns 0 },
            streamingAltSettings = emptyMap(),
        )

    private fun output(manager: UsbAudioManager) =
        UsbAudioOutput(
            session = session,
            format = format,
            channels = 2,
            sampleRateHz = 44100,
            usbSettings = mockk<UsbDacPreferences>(relaxed = true) { every { maxVolumePercent } returns 100 },
            usbAudioManager = manager,
        )

    private fun lastOf(states: List<OutputWarmState>) = synchronized(states) { states.lastOrNull() }

    /** Records every state the output reports, in order. */
    private fun UsbAudioManager.recordWarmStates(): MutableList<OutputWarmState> {
        val states = mutableListOf<OutputWarmState>()
        every { reportOutputWarmState(any()) } answers {
            synchronized(states) { states += firstArg<OutputWarmState>() }
        }
        return states
    }

    @Test
    fun `a new output registers itself as the one prewarmOutput warms, and release unregisters it`() {
        val manager = mockk<UsbAudioManager>(relaxed = true)
        val output = output(manager)
        verify { manager.registerOutputPrewarm(output, any()) }

        output.release()

        verify { manager.unregisterOutputPrewarm(output) }
    }

    @Test
    fun `prewarm starts the same claim attempt play() would, while staying paused`() {
        val manager = mockk<UsbAudioManager>(relaxed = true)
        val states = manager.recordWarmStates()
        val claimed = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        every { manager.requestClaim() } answers {
            claimed.countDown()
            proceed.await(2, TimeUnit.SECONDS)
            UsbAudioManager.ClaimOutcome.FAILED
        }
        val output = output(manager)

        output.prewarm()

        assertTrue(claimed.await(2, TimeUnit.SECONDS), "prewarm never reached requestClaim()")
        // Mid-relock, not paused-and-idle: isStalled() must not call a warming output stalled.
        assertEquals(false, output.isStalled())
        assertEquals(OutputWarmState.Warming, synchronized(states) { states.first() })
        proceed.countDown()
        output.release()
    }

    @Test
    fun `a prewarm that cannot claim the device reports Failed, not Ready`() {
        val manager = mockk<UsbAudioManager>(relaxed = true)
        val states = manager.recordWarmStates()
        val done = CountDownLatch(1)
        every { manager.requestClaim() } returns UsbAudioManager.ClaimOutcome.FAILED
        every { manager.endClaimAttempt() } answers { done.countDown() }
        val output = output(manager)

        output.prewarm()

        assertTrue(done.await(2, TimeUnit.SECONDS))
        // endClaimAttempt() runs just before the outcome is reported.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (lastOf(states) != OutputWarmState.Failed && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(listOf(OutputWarmState.Warming, OutputWarmState.Failed), synchronized(states) { states.toList() })
        output.release()
    }

    @Test
    fun `pause during a prewarm cancels it, reports Idle, and hands the claim back instead of streaming`() {
        val manager = mockk<UsbAudioManager>(relaxed = true)
        val states = manager.recordWarmStates()
        val claimed = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val done = CountDownLatch(1)
        every { manager.requestClaim() } answers {
            claimed.countDown()
            proceed.await(2, TimeUnit.SECONDS)
            UsbAudioManager.ClaimOutcome.FAILED
        }
        every { manager.endClaimAttempt() } answers { done.countDown() }
        val output = output(manager)

        output.prewarm()
        assertTrue(claimed.await(2, TimeUnit.SECONDS))
        output.pause()
        proceed.countDown()

        assertTrue(done.await(2, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (lastOf(states) == OutputWarmState.Warming && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(OutputWarmState.Idle, synchronized(states) { states.last() })
        verify(exactly = 0) { manager.notifyStreamingStarted() }
        output.release()
    }

    @Test
    fun `prewarm after release does nothing`() {
        val manager = mockk<UsbAudioManager>(relaxed = true)
        val output = output(manager)
        output.release()

        output.prewarm()

        verify(exactly = 0) { manager.requestClaim() }
    }
}
