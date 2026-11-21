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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ostrichbat2k.usbdac.protocol.SyncType
import org.ostrichbat2k.usbdac.protocol.UacFeedbackEndpoint
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

    // syncType left at its default (SYNCHRONOUS, no feedback endpoint) -- the feedback stream
    // (#25) is only opened for asyncTestFormat below.
    private val testFormat =
        UacStreamFormat(
            alternateSetting = 1,
            bitResolution = 16,
            subslotSize = 2,
            clusterDescriptorId = -1,
            endpointAddress = 0x02,
            maxPacketSize = 192,
        )

    // For the feedback-stream tests (#107, #139): an ASYNC format with a feedback endpoint, at a
    // distinct address from the main format so FakeNativeBridge hands it its own handle.
    private val feedbackEndpoint = UacFeedbackEndpoint(address = 0x82, maxPacketSize = 4, bInterval = 1)
    private val asyncTestFormat = testFormat.copy(syncType = SyncType.ASYNC, feedbackEndpoint = feedbackEndpoint)

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
     * block, the background thread at a specific reapIsoAny() call.
     *
     * Like the kernel, there is one reap script for the whole fd (#139): each entry is a
     * completion for either stream -- [mainSlot]/[feedbackSlot] -- or an error code.
     */
    private class FakeNativeBridge(
        private val openHandle: Long = MAIN_HANDLE,
        private val feedbackOpenHandle: Long = FEEDBACK_HANDLE,
        private val feedbackEndpointAddress: Int = -1,
        private val submitResults: List<Boolean> = emptyList(),
        private val reapResults: List<Long> = emptyList(),
        private val feedbackSubmitReadResults: List<Boolean> = emptyList(),
        private val beforeReap: (callIndex: Int) -> Unit = {},
    ) : UsbNativeBridge {
        val openIsoStreamCallCount = AtomicInteger(0)
        val submitIsoCallCount = AtomicInteger(0)
        val reapIsoCallCount = AtomicInteger(0)
        val feedbackSubmitIsoReadCallCount = AtomicInteger(0)
        val readIsoSlotDataCalls = CopyOnWriteArrayList<Pair<Long, Int>>()
        val reapHandles = CopyOnWriteArrayList<Long>()
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
            return if (endpointAddress == feedbackEndpointAddress) feedbackOpenHandle else openHandle
        }

        override fun submitIso(handle: Long, slot: Int, data: ByteArray, packetLengths: IntArray): Boolean {
            val idx = submitIsoCallCount.getAndIncrement()
            return submitResults.getOrElse(idx) { true }
        }

        override fun reapIsoAny(handle: Long): Long {
            reapHandles.add(handle)
            val idx = reapIsoCallCount.getAndIncrement()
            beforeReap(idx)
            return reapResults.getOrElse(idx) { FATAL }
        }

        override fun cancelIso(handle: Long) {
            cancelIsoHandles.add(handle)
        }

        override fun closeIsoStream(handle: Long) {
            closeIsoStreamHandles.add(handle)
        }

        override fun submitIsoRead(handle: Long, slot: Int, packetLengths: IntArray): Boolean {
            val idx = feedbackSubmitIsoReadCallCount.getAndIncrement()
            return feedbackSubmitReadResults.getOrElse(idx) { true }
        }

        override fun readIsoSlotData(handle: Long, slot: Int, dst: ByteArray): Int {
            readIsoSlotDataCalls.add(handle to slot)
            return -1
        }

        override fun getPacketErrorStats(handle: Long) = longArrayOf(0L, 0L)
    }

    private companion object {
        const val MAIN_HANDLE = 1L
        const val FEEDBACK_HANDLE = 2L
        const val FATAL = UsbNativeBridge.REAP_ERROR_FATAL.toLong()

        fun mainSlot(slot: Int) = UsbNativeBridge.reapResult(MAIN_HANDLE, slot)

        fun feedbackSlot(slot: Int) = UsbNativeBridge.reapResult(FEEDBACK_HANDLE, slot)
    }

    /** Polls [condition] for up to two seconds. */
    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 2000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        return condition()
    }

    private fun engineWith(
        bridge: UsbNativeBridge,
        paused: Boolean = true,
        format: UacStreamFormat = testFormat,
        onFatalStreamError: () -> Unit = {},
        urbQueue: UrbQueueShape = UrbQueueShape.DEFAULT,
    ) = UsbIsoStreamEngine(
        session = testSession,
        format = format,
        channels = testTopology.channelCount,
        sampleRateHz = 44100,
        ringBuffer = PcmRingBuffer(4096),
        paused = { paused },
        reportPipelineStats = {},
        nativeBridge = bridge,
        onFatalStreamError = onFatalStreamError,
        urbQueue = urbQueue,
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
        assertTrue(reachedReap.await(2, TimeUnit.SECONDS), "background thread never reached reapIsoAny()")

        // UsbAudioTuning.NUM_URBS (4) slots, with slot 0 needing one retry: 5 total calls.
        assertEquals(5, bridge.submitIsoCallCount.get())

        // Release the background thread before stop() so its join() doesn't have to wait out
        // the full STREAM_THREAD_JOIN_TIMEOUT_MS -- that scenario has its own dedicated test below.
        blockForever.countDown()
        engine.stop()
    }

    /** Regression shape for #71: a slot only credits framesOutput once reapIsoAny() confirms completion. */
    @Test
    fun `framesOutput only advances once reapIsoAny confirms a slot completed, not at submission`() {
        val readyToReap = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val bridge =
            FakeNativeBridge(
                // First reap confirms slot 0 (prefilled with audio, so creditable); the second ends the loop.
                reapResults = listOf(mainSlot(0), FATAL),
                beforeReap = { idx ->
                    if (idx == 0) {
                        readyToReap.countDown()
                        proceed.await()
                    }
                })
        // Not paused: only URBs that carried track audio are credited (#127), so a paused
        // engine's prefill -- silence -- never would be.
        val engine = engineWith(bridge, paused = false)

        engine.start()
        assertTrue(readyToReap.await(2, TimeUnit.SECONDS), "background thread never reached its first reapIsoAny()")
        // Submission (the prefill loop) has already happened by now, but nothing has been
        // *reaped* yet -- the background thread is parked inside reapIsoAny()'s first call.
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
        // cancelIso() is a no-op in this fake -- nothing ever unblocks this reapIsoAny() call,
        // simulating exactly the "cancelIso() failed to unblock it in time" scenario #68 fixed
        // from the Kotlin caller side and #76 hardened at the native layer.
        val bridge = FakeNativeBridge(beforeReap = { reachedReap.countDown(); blockForever.await() })
        val engine = engineWith(bridge)

        engine.start()
        assertTrue(reachedReap.await(2, TimeUnit.SECONDS), "background thread never reached reapIsoAny()")

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
                reapResults = listOf(mainSlot(0), FATAL),
                beforeReap = { idx ->
                    if (idx == 0) {
                        readyToReap.countDown()
                        proceed.await()
                    }
                })
        // Not paused: only URBs that carried track audio are credited (#127), so a paused
        // engine's prefill -- silence -- never would be.
        val engine = engineWith(bridge, paused = false)
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

    /**
     * Regression shape for #148: a flush with URBs in flight. The four prefilled URBs carry
     * pre-seek audio and must not count once they complete after the flush; slot 0 refilled after
     * the flush must.
     */
    @Test
    fun `after resetPosition only URBs filled since the flush are credited`() {
        val readyToReap = CountDownLatch(1)
        val flushed = CountDownLatch(1)
        val bridge =
            FakeNativeBridge(
                reapResults = listOf(mainSlot(0), mainSlot(1), mainSlot(2), mainSlot(3), mainSlot(0), FATAL),
                beforeReap = { idx ->
                    if (idx == 0) {
                        readyToReap.countDown()
                        flushed.await()
                    }
                })
        var fatal = false
        val engine = engineWith(bridge, paused = false, onFatalStreamError = { fatal = true })

        engine.start()
        assertTrue(readyToReap.await(2, TimeUnit.SECONDS))
        engine.resetPosition()
        assertEquals(0L, engine.framesOutput)
        flushed.countDown()
        assertTrue(waitUntil { fatal })

        // Exactly one URB: 8 full-speed packets of 44 or 45 frames at 44.1 kHz.
        val frames = engine.framesOutput
        assertTrue(frames in 352L..360L, "expected one post-flush URB credited, got $frames frames")
        engine.stop()
    }

    /** Regression shape for #107: a feedback submitIsoRead failure must not abandon later slots' prefill. */
    @Test
    fun `a feedback submitIsoRead failing twice for a slot is logged and prefill still continues to the next slot`() {
        val bridge =
            FakeNativeBridge(
                feedbackEndpointAddress = feedbackEndpoint.address,
                reapResults = listOf(FATAL),
                // Slot 0's prefill resubmit fails both attempts (2 calls); slot 1's succeeds on
                // the first (falls back to "succeed" once the script runs out) -- 3 calls total.
                feedbackSubmitReadResults = listOf(false, false))
        val engine = engineWith(bridge, format = asyncTestFormat)

        engine.start()
        waitUntil { bridge.feedbackSubmitIsoReadCallCount.get() >= 3 }

        assertEquals(
            3,
            bridge.feedbackSubmitIsoReadCallCount.get(),
            "slot 0 should get both retry attempts, then prefill should still move on to slot 1")

        engine.stop()
    }

    /**
     * Regression shape for #51: a fatal main-loop error has to let the caller react right now,
     * not just leave isStalled() to poll it.
     */
    @Test
    fun `onFatalStreamError fires exactly once when the main loop hits a fatal reapIsoAny error`() {
        val bridge = FakeNativeBridge(reapResults = listOf(FATAL))
        var fatalCalls = 0
        val engine = engineWith(bridge, onFatalStreamError = { fatalCalls++ })

        engine.start()
        waitUntil { fatalCalls != 0 }

        assertEquals(1, fatalCalls)
        engine.stop()
        // Still exactly 1 -- stop()'s own cancelIso/join must not trigger a second call.
        assertEquals(1, fatalCalls)
    }

    /**
     * Regression shape for #139: the two streams share one fd and so one completion list. A
     * feedback completion is the feedback stream's -- read back and resubmitted as feedback --
     * and must never be taken for a main slot (no main resubmit, no position credit).
     */
    @Test
    fun `a feedback completion is serviced as feedback and never as a main slot`() {
        val reachedThirdReap = CountDownLatch(1)
        val blockForever = CountDownLatch(1)
        val bridge =
            FakeNativeBridge(
                feedbackEndpointAddress = feedbackEndpoint.address,
                reapResults = listOf(feedbackSlot(0), feedbackSlot(1)),
                beforeReap = { idx ->
                    if (idx == 2) {
                        reachedThirdReap.countDown()
                        blockForever.await()
                    }
                })
        val engine = engineWith(bridge, paused = false, format = asyncTestFormat)

        engine.start()
        assertTrue(reachedThirdReap.await(2, TimeUnit.SECONDS), "streaming thread never reached its third reap")

        assertEquals(listOf(FEEDBACK_HANDLE to 0, FEEDBACK_HANDLE to 1), bridge.readIsoSlotDataCalls.toList())
        // 2 primed + 2 resubmitted after their completions.
        assertEquals(4, bridge.feedbackSubmitIsoReadCallCount.get())
        assertEquals(4, bridge.submitIsoCallCount.get(), "a feedback completion must not resubmit a main slot")
        assertEquals(0L, engine.framesOutput, "a feedback completion must not credit main position")
        assertEquals(0L, engine.urbsReaped)

        blockForever.countDown()
        engine.stop()
    }

    /** #139: one thread reaps the fd, so every reap goes through the main handle. */
    @Test
    fun `one thread does all the reaping, through the main handle`() {
        val bridge =
            FakeNativeBridge(
                feedbackEndpointAddress = feedbackEndpoint.address,
                reapResults = listOf(mainSlot(0), feedbackSlot(0), mainSlot(1), FATAL))
        val engine = engineWith(bridge, format = asyncTestFormat)

        engine.start()
        waitUntil { bridge.reapIsoCallCount.get() >= 4 }
        Thread.sleep(50) // A second reaper, if there were one, would show up here.
        engine.stop()

        assertEquals(4, bridge.reapIsoCallCount.get())
        assertEquals(setOf(MAIN_HANDLE), bridge.reapHandles.toSet())
        assertEquals(2L, engine.urbsReaped)
    }

    /** A completion for a handle this engine doesn't own is skipped, not mistaken for a main slot. */
    @Test
    fun `a completion owned by an unknown handle is ignored`() {
        val bridge = FakeNativeBridge(reapResults = listOf(UsbNativeBridge.reapResult(99L, 0), mainSlot(0), FATAL))
        var fatalCalls = 0
        val engine = engineWith(bridge, onFatalStreamError = { fatalCalls++ })

        engine.start()
        waitUntil { fatalCalls != 0 }
        engine.stop()

        assertEquals(1L, engine.urbsReaped)
        // 4 prefill + the one real main completion's resubmit.
        assertEquals(5, bridge.submitIsoCallCount.get())
    }

    /** Regression shape for #107/#139: stop() cancels and closes both streams once the one thread has exited. */
    @Test
    fun `stop cancels and closes both streams`() {
        val bridge = FakeNativeBridge(feedbackEndpointAddress = feedbackEndpoint.address, reapResults = listOf(FATAL))
        val engine = engineWith(bridge, format = asyncTestFormat)

        engine.start()
        waitUntil { bridge.reapIsoCallCount.get() > 0 }
        engine.stop()

        assertEquals(setOf(MAIN_HANDLE, FEEDBACK_HANDLE), bridge.cancelIsoHandles.toSet())
        assertEquals(setOf(MAIN_HANDLE, FEEDBACK_HANDLE), bridge.closeIsoStreamHandles.toSet())
    }

    /** Regression shape for #68/#107: a stuck thread uses both streams, so neither may be freed. */
    @Test
    fun `stop leaves both native streams open while the streaming thread is still stuck`() {
        val reachedReap = CountDownLatch(1)
        val blockForever = CountDownLatch(1)
        val bridge =
            FakeNativeBridge(
                feedbackEndpointAddress = feedbackEndpoint.address,
                beforeReap = { reachedReap.countDown(); blockForever.await() })
        val engine = engineWith(bridge, format = asyncTestFormat)

        engine.start()
        assertTrue(reachedReap.await(2, TimeUnit.SECONDS), "streaming thread never reached reapIsoAny()")

        engine.stop()

        assertEquals(
            setOf(MAIN_HANDLE, FEEDBACK_HANDLE),
            bridge.cancelIsoHandles.toSet(),
            "stop() must still try to cancel both")
        assertTrue(
            bridge.closeIsoStreamHandles.isEmpty(),
            "closeIsoStream must not run for either stream while the thread might still be using it")

        blockForever.countDown()
    }

    /**
     * A bridge whose first stream's reap ignores cancelIso (blocks until [unblockFirst]), then
     * returns one ordinary completion; later streams reap normally and end on cancelIso. Counts
     * submissions per handle.
     */
    private class StuckFirstStreamBridge : UsbNativeBridge {
        val reachedFirstReap = CountDownLatch(1)
        val unblockFirst = CountDownLatch(1)
        val opened = CopyOnWriteArrayList<Long>()
        val closed = CopyOnWriteArrayList<Long>()
        val submitsByHandle = java.util.concurrent.ConcurrentHashMap<Long, AtomicInteger>()
        private val cancelled = java.util.concurrent.ConcurrentHashMap<Long, CountDownLatch>()
        private var nextHandle = 1L

        override fun disconnectKernelDriver(fd: Int, interfaceNumber: Int) = true

        override fun getUsbSpeed(fd: Int) = 2

        override fun openIsoStream(
            fd: Int,
            endpointAddress: Int,
            maxPacketSize: Int,
            packetsPerUrb: Int,
            numUrbs: Int,
        ) =
            nextHandle++.also {
                opened += it
                cancelled[it] = CountDownLatch(1)
            }

        override fun submitIso(handle: Long, slot: Int, data: ByteArray, packetLengths: IntArray): Boolean {
            submitsByHandle.getOrPut(handle) { AtomicInteger() }.incrementAndGet()
            return true
        }

        override fun reapIsoAny(handle: Long): Long {
            if (handle == 1L) {
                reachedFirstReap.countDown()
                unblockFirst.await()
                return UsbNativeBridge.reapResult(handle, 0)
            }
            cancelled.getValue(handle).await()
            return UsbNativeBridge.REAP_ERROR_FATAL.toLong()
        }

        override fun cancelIso(handle: Long) {
            cancelled[handle]?.countDown()
        }

        override fun closeIsoStream(handle: Long) {
            closed += handle
        }

        override fun submitIsoRead(handle: Long, slot: Int, packetLengths: IntArray) = true

        override fun readIsoSlotData(handle: Long, slot: Int, dst: ByteArray) = -1

        override fun getPacketErrorStats(handle: Long) = longArrayOf(0L, 0L)
    }

    /** Regression shape for #147: a run stop() had to leave stuck is never revived or overwritten. */
    @Test
    fun `a stuck run is never revived by the next start, and its handle is closed once it exits`() {
        val bridge = StuckFirstStreamBridge()
        val engine = engineWith(bridge)

        engine.start()
        assertTrue(bridge.reachedFirstReap.await(2, TimeUnit.SECONDS))
        engine.stop() // stuck: leaves handle 1 open
        assertEquals(emptyList<Long>(), bridge.closed.toList())

        engine.start() // retries the stop, still stuck: must refuse rather than open over it
        assertEquals(listOf(1L), bridge.opened.toList(), "start() must not open a new stream over a stuck one")
        assertFalse(engine.streaming)

        // The stuck reap returns an ordinary completion. A revived run would resubmit it.
        val prefill = bridge.submitsByHandle.getValue(1L).get()
        bridge.unblockFirst.countDown()
        Thread.sleep(100)
        assertEquals(prefill, bridge.submitsByHandle.getValue(1L).get(), "the stopped run kept streaming")

        engine.start() // the old thread has exited: its handle is closed, then a new run opens
        assertEquals(listOf(1L), bridge.closed.toList())
        assertEquals(listOf(1L, 2L), bridge.opened.toList())
        assertTrue(engine.streaming)
        engine.stop()
        assertEquals(listOf(1L, 2L), bridge.closed.toList())
    }

    /** #123: a queue-shape override reaches the native layer and the prefill, not just the default 4. */
    @Test
    fun `a queue-shape override sets how many URBs are opened and prefilled`() {
        val reachedReap = CountDownLatch(1)
        val bridge = FakeNativeBridge(beforeReap = { reachedReap.countDown() })
        val engine = engineWith(bridge, paused = false, urbQueue = UrbQueueShape(numUrbs = 6, basePacketsPerUrb = 2))

        engine.start()
        assertTrue(reachedReap.await(2, TimeUnit.SECONDS), "background thread never reached reapIsoAny()")

        assertEquals(6, engine.numUrbs)
        assertEquals(6, bridge.submitIsoCallCount.get())
        engine.stop()
    }
}
