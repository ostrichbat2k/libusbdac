/*
 * Copyright (c) 2026 libusbdac Project
 * UsbIsoStreamEngineTest.kt is part of libusbdac.
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

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ostrichbat2k.usbdac.protocol.SyncType
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession

/**
 * [UsbIsoStreamEngine] is the one thing #73 exists to make reachable from a plain JVM test at
 * all -- the real [UsbNative] object's `init {}` throws `UnsatisfiedLinkError` the instant
 * anything merely references it, which is exactly why every fix to this code's long history of
 * concurrency bugs (#68, #59, #60, #70, #71) could only ever be verified by hand on real
 * hardware before this. [FakeNativeBridge] below stands in for the real native calls entirely.
 *
 * Deliberately narrow in scope: this covers the shapes those past bugs took (a stuck thread must
 * not free native memory out from under it; a failed resubmit is retried, not silently dropped;
 * position only advances on confirmed completion, not submission), not full real-time pacing
 * fidelity -- that still needs real hardware, and #73's own issue text says so explicitly.
 */
class UsbIsoStreamEngineTest {

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

    // syncType left at its default (SYNCHRONOUS, no feedback endpoint) -- the feedback thread
    // (#25) is a separate, independent loop this test file doesn't need to cover to test the
    // main stream's lifetime/retry/position-tracking behavior.
    private val testFormat =
        UacStreamFormat(
            alternateSetting = 1,
            bitResolution = 16,
            subslotSize = 2,
            clusterDescriptorId = -1,
            endpointAddress = 0x02,
            maxPacketSize = 192,
        )

    private val testSession =
        UsbAudioSession(
            device = mockk<UsbDevice>(relaxed = true),
            connection = mockk<UsbDeviceConnection>(relaxed = true) { every { fileDescriptor } returns 42 },
            topology = testTopology,
            controlInterface = mockk<UsbInterface>(relaxed = true) { every { id } returns 0 },
            streamingAltSettings = emptyMap(),
        )

    /**
     * Records every call for assertions and lets each test script [submitResults]/[reapResults]
     * per call index (falling back to "succeed"/[UsbNativeBridge.REAP_ERROR_FATAL] once a script
     * runs out), plus an optional [beforeReap] hook for tests that need to synchronize with, or
     * block, the background thread at a specific reapIso() call.
     */
    private class FakeNativeBridge(
        private val openHandle: Long = 1L,
        private val submitResults: List<Boolean> = emptyList(),
        private val reapResults: List<Int> = emptyList(),
        private val beforeReap: (callIndex: Int) -> Unit = {},
    ) : UsbNativeBridge {
        val openIsoStreamCallCount = AtomicInteger(0)
        val submitIsoCallCount = AtomicInteger(0)
        val reapIsoCallCount = AtomicInteger(0)
        val cancelIsoHandles = CopyOnWriteArrayList<Long>()
        val closeIsoStreamHandles = CopyOnWriteArrayList<Long>()

        override fun disconnectKernelDriver(fd: Int, interfaceNumber: Int) = true

        override fun getUsbSpeed(fd: Int) = 2 // full-speed: 1ms/packet, the simplest pacing to reason about.

        override fun openIsoStream(
            fd: Int,
            endpointAddress: Int,
            maxPacketSize: Int,
            packetsPerUrb: Int,
            numUrbs: Int,
        ): Long {
            openIsoStreamCallCount.incrementAndGet()
            return openHandle
        }

        override fun submitIso(handle: Long, slot: Int, data: ByteArray, packetLengths: IntArray): Boolean {
            val idx = submitIsoCallCount.getAndIncrement()
            return submitResults.getOrElse(idx) { true }
        }

        override fun reapIso(handle: Long): Int {
            val idx = reapIsoCallCount.getAndIncrement()
            beforeReap(idx)
            return reapResults.getOrElse(idx) { UsbNativeBridge.REAP_ERROR_FATAL }
        }

        override fun cancelIso(handle: Long) {
            cancelIsoHandles.add(handle)
        }

        override fun closeIsoStream(handle: Long) {
            closeIsoStreamHandles.add(handle)
        }

        override fun submitIsoRead(handle: Long, slot: Int, packetLengths: IntArray) = true

        override fun readIsoSlotData(handle: Long, slot: Int, dst: ByteArray) = -1

        override fun getPacketErrorStats(handle: Long) = longArrayOf(0L, 0L)
    }

    private fun engineWith(bridge: UsbNativeBridge, paused: Boolean = true) =
        UsbIsoStreamEngine(
            session = testSession,
            format = testFormat,
            channels = testTopology.channelCount,
            sampleRateHz = 44100,
            ringBuffer = PcmRingBuffer(4096),
            paused = { paused },
            reportPipelineStats = {},
            nativeBridge = bridge,
        )

    @Test
    fun `start does nothing and opens no thread when openIsoStream fails`() {
        val bridge = FakeNativeBridge(openHandle = 0L)
        val engine = engineWith(bridge)

        engine.start()

        assertEquals(1, bridge.openIsoStreamCallCount.get())
        assertEquals(0, bridge.submitIsoCallCount.get())
        assertEquals(0, bridge.reapIsoCallCount.get())
    }

    @Test
    fun `stop is a safe no-op when the stream was never started`() {
        val bridge = FakeNativeBridge()
        val engine = engineWith(bridge)

        engine.stop()

        assertEquals(0, bridge.cancelIsoHandles.size)
        assertEquals(0, bridge.closeIsoStreamHandles.size)
    }

    /** Regression shape for #59: a slot whose initial submitIso fails must be retried once, not abandoned. */
    @Test
    fun `a failed initial submitIso is retried once before moving on to the next slot`() {
        val reachedReap = CountDownLatch(1)
        val blockForever = CountDownLatch(1)
        // Slot 0's first attempt fails, its retry succeeds; every other slot succeeds first try.
        val bridge =
            FakeNativeBridge(
                submitResults = listOf(false, true, true, true, true),
                beforeReap = { reachedReap.countDown(); blockForever.await() })
        val engine = engineWith(bridge)

        engine.start()
        assertTrue(reachedReap.await(2, TimeUnit.SECONDS), "background thread never reached reapIso()")

        // UsbAudioTuning.NUM_URBS (4) slots, with slot 0 needing one retry: 5 total calls.
        assertEquals(5, bridge.submitIsoCallCount.get())

        // Release the background thread before stop() so its join() doesn't have to wait out
        // the full STREAM_THREAD_JOIN_TIMEOUT_MS -- that scenario has its own dedicated test below.
        blockForever.countDown()
        engine.stop()
    }

    /** Regression shape for #71: a slot only credits framesOutput once reapIso() confirms completion. */
    @Test
    fun `framesOutput only advances once reapIso confirms a slot completed, not at submission`() {
        val readyToReap = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val bridge =
            FakeNativeBridge(
                // First reap confirms slot 0 (prefilled, so creditable); the second ends the loop.
                reapResults = listOf(0, UsbNativeBridge.REAP_ERROR_FATAL),
                beforeReap = { idx ->
                    if (idx == 0) {
                        readyToReap.countDown()
                        proceed.await()
                    }
                })
        val engine = engineWith(bridge)

        engine.start()
        assertTrue(readyToReap.await(2, TimeUnit.SECONDS), "background thread never reached its first reapIso()")
        // Submission (the prefill loop) has already happened by now, but nothing has been
        // *reaped* yet -- the background thread is parked inside reapIso()'s first call.
        assertEquals(0L, engine.framesOutput)

        proceed.countDown()
        val deadline = System.currentTimeMillis() + 2000
        while (engine.framesOutput == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertTrue(engine.framesOutput > 0L, "framesOutput should have credited slot 0's completion")

        engine.stop()
    }

    /** Regression shape for #68/#76: never free the native stream while the thread might still be using it. */
    @Test
    fun `stop leaves the native stream open rather than close it while the background thread is still stuck`() {
        val reachedReap = CountDownLatch(1)
        val blockForever = CountDownLatch(1)
        // cancelIso() is a no-op in this fake -- nothing ever unblocks this reapIso() call,
        // simulating exactly the "cancelIso() failed to unblock it in time" scenario #68 fixed
        // from the Kotlin caller side and #76 hardened at the native layer.
        val bridge = FakeNativeBridge(beforeReap = { reachedReap.countDown(); blockForever.await() })
        val engine = engineWith(bridge)

        engine.start()
        assertTrue(reachedReap.await(2, TimeUnit.SECONDS), "background thread never reached reapIso()")

        engine.stop()

        assertEquals(1, bridge.cancelIsoHandles.size, "stop() must still try to cancel")
        assertTrue(
            bridge.closeIsoStreamHandles.isEmpty(),
            "closeIsoStream must not run while the thread might still be using the handle")

        blockForever.countDown()
    }

    /** [framesOutput] only ever credits a slot once, via [resetPosition]'s counterpart in #71's own fix. */
    @Test
    fun `resetPosition zeroes framesOutput after a credited slot`() {
        val readyToReap = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val bridge =
            FakeNativeBridge(
                reapResults = listOf(0, UsbNativeBridge.REAP_ERROR_FATAL),
                beforeReap = { idx ->
                    if (idx == 0) {
                        readyToReap.countDown()
                        proceed.await()
                    }
                })
        val engine = engineWith(bridge)
        engine.start()
        assertTrue(readyToReap.await(2, TimeUnit.SECONDS))
        proceed.countDown()
        val deadline = System.currentTimeMillis() + 2000
        while (engine.framesOutput == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertTrue(engine.framesOutput > 0L)

        engine.resetPosition()

        assertEquals(0L, engine.framesOutput)
        engine.stop()
    }
}
