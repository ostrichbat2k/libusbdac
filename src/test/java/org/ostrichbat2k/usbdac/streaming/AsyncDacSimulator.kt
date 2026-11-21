/*
 * Copyright (c) 2026 libusbdac Project
 * AsyncDacSimulator.kt is part of libusbdac.
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

import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** How [AsyncDacSimulator] encodes its clock on the feedback endpoint. All four exist in the wild. */
internal enum class FeedbackWire {
    /**
     * What the specifications say: full speed sends 3 bytes, 10.14 samples per 1 ms frame (UAC 1.0
     * 3.7.2.2 / USB 2.0 5.12.4.2); high speed sends 4 bytes, 16.16 samples per 125 us microframe.
     */
    SPEC,

    /**
     * What Linux's own UAC2 gadget sends (`u_audio_set_fback_frequency`, drivers/usb/gadget/
     * function/u_audio.c): at high speed, samples per *data packet* -- the per-microframe value
     * scaled by the data endpoint's bInterval -- because, per its own comment, the Windows and
     * macOS host drivers expect that. Identical to [SPEC] when the data endpoint's bInterval is 1.
     */
    LINUX_GADGET,

    /** A high-speed device that kept the full-speed value (10.14 per 1 ms frame) in 4 bytes. */
    Q10_14_PER_FRAME_IN_4_BYTES,
}

/**
 * A software Asynchronous USB DAC, standing in for the native layer behind [UsbNativeBridge] so
 * the real [UsbIsoStreamEngine], [IsoPacketScheduler] and feedback decoder can be run end to end
 * with no hardware (#126).
 *
 * What it models: a device whose own clock runs at [deviceRateHz] measured against the USB bus
 * clock -- deliberately not the nominal rate the host was told -- and which reports that on its
 * feedback endpoint in one of the [FeedbackWire] encodings, exactly as a real Asynchronous device
 * does. It counts every frame and packet the host sends. A host that follows the feedback sends
 * [deviceRateHz] frames per second of bus time; one that ignores it sends the nominal rate, and
 * the difference accumulates in the device's buffer until it over- or underruns.
 *
 * Bus time is counted in packets, not wall-clock time: one data packet is one service interval by
 * definition, so [deliveredRateHz] is exact and does not depend on how the test machine schedules
 * threads.
 *
 * Completions behave like usbfs (#139): both streams are opened on the same fd, and the kernel
 * keeps one completion list per open file (`usb_dev_state.async_completed`, `reap_as()` in
 * drivers/usb/core/devio.c), so a reap returns the next finished URB on the fd whichever stream
 * submitted it. This simulator keeps one shared queue the same way, and counts every submission
 * of a slot that is still in flight ([doubleSubmissions]) -- what a host that confuses the two
 * streams' URBs does. Main URBs complete in bus time, one after another, as the device plays them;
 * feedback URBs a millisecond after they are submitted.
 *
 * What it does not model: anything electrical, a control loop inside the device (the reported
 * value is constant), lost packets, or host-controller scheduling. A Linux `dummy_hcd` gadget was
 * the first idea for this and cannot do it: `dummy_hcd` fails every isochronous transfer.
 */
internal class AsyncDacSimulator(
    private val usbSpeed: Int,
    private val dataBInterval: Int,
    private val deviceRateHz: Double,
    private val frameBytes: Int,
    private val feedbackWire: FeedbackWire,
    private val feedbackEndpointAddress: Int,
) : UsbNativeBridge {
    private companion object {
        const val MAIN_HANDLE = 1L
        const val FEEDBACK_HANDLE = 2L
        const val USB_SPEED_FULL = 2
        const val CANCELLED = Int.MIN_VALUE
        const val FEEDBACK_PERIOD_NS = 1_000_000L
    }

    data class Snapshot(val frames: Long, val packets: Long)

    /** One finished URB on the shared per-fd completion list, available once [dueNs] has passed. */
    private class Completion(val handle: Long, val slot: Int, private val dueNs: Long) : Delayed {
        override fun getDelay(unit: TimeUnit): Long = unit.convert(dueNs - System.nanoTime(), TimeUnit.NANOSECONDS)

        override fun compareTo(other: Delayed): Int =
            getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
    }

    private val isHighSpeed = usbSpeed != USB_SPEED_FULL

    /** Data packets per second of bus time -- the same rule [IsoPacing.forSpeed] implements. */
    val packetsPerSecond: Int = if (isHighSpeed) 8000 shr (dataBInterval - 1) else 1000

    // One list for the whole fd, like the kernel's -- see this class's doc comment.
    private val completions = DelayQueue<Completion>()
    private val inFlight = mapOf(MAIN_HANDLE to mutableSetOf<Int>(), FEEDBACK_HANDLE to mutableSetOf())

    // When the last main URB submitted finishes playing on the bus. Guarded by inFlight.
    private var mainBusEndNs = 0L
    private val packetDurationNs = 1_000_000_000L / packetsPerSecond

    // Updated and read together under countLock: a snapshot taken between the two additions
    // would be off by a whole URB of frames, which is far more than the tests' tolerance.
    private val countLock = Any()
    private var frames = 0L
    private var packets = 0L

    @Volatile var largestPacketFrames = 0
        private set

    @Volatile var smallestPacketFrames = Int.MAX_VALUE
        private set

    /** Submissions of a slot whose previous URB had not completed yet. Always 0 for a correct host. */
    val doubleSubmissions = AtomicInteger(0)

    /** Feedback payloads the host read back -- how often it actually serviced the feedback stream. */
    val feedbackReads = AtomicInteger(0)

    /** Main-stream slots currently submitted and not yet reaped. */
    fun mainInFlight(): Int = synchronized(inFlight) { inFlight.getValue(MAIN_HANDLE).size }

    fun snapshot() = synchronized(countLock) { Snapshot(frames, packets) }

    /** Frames per second of bus time the host actually sent between two snapshots. */
    fun deliveredRateHz(from: Snapshot, to: Snapshot): Double =
        (to.frames - from.frames).toDouble() * packetsPerSecond / (to.packets - from.packets)

    /**
     * How far the device's buffer level moved between two snapshots, in frames: what the host sent
     * minus what the device's clock consumed over the same bus time. Stays near zero for a host
     * that follows the feedback; grows without bound otherwise.
     */
    fun bufferDriftFrames(from: Snapshot, to: Snapshot): Double =
        (to.frames - from.frames) - (to.packets - from.packets) * deviceRateHz / packetsPerSecond

    /** The feedback endpoint's payload for [deviceRateHz], little-endian. */
    fun feedbackBytes(): ByteArray {
        val rate = deviceRateHz
        return when {
            !isHighSpeed -> le(Math.round(rate * (1 shl 14) / 1000), 3)
            feedbackWire == FeedbackWire.SPEC -> le(Math.round(rate * (1 shl 16) / 8000), 4)
            feedbackWire == FeedbackWire.LINUX_GADGET ->
                // u_audio.c: freq <<= 4 + (bInterval - 1); ff = freq * pitch / 1953125, with
                // pitch (1000000 == unity) folded into deviceRateHz here.
                le(Math.round(rate * (1 shl (4 + dataBInterval - 1)) * 1_000_000 / 1_953_125), 4)
            else -> le(Math.round(rate * (1 shl 14) / 1000), 4)
        }
    }

    private fun le(value: Long, size: Int) = ByteArray(size) { i -> ((value shr (8 * i)) and 0xFF).toByte() }

    private fun submit(handle: Long, slot: Int, packets: Int) {
        val dueNs =
            synchronized(inFlight) {
                if (!inFlight.getValue(handle).add(slot)) doubleSubmissions.incrementAndGet()
                val now = System.nanoTime()
                if (handle == MAIN_HANDLE) {
                    mainBusEndNs = maxOf(now, mainBusEndNs) + packets * packetDurationNs
                    mainBusEndNs
                } else {
                    now + FEEDBACK_PERIOD_NS
                }
            }
        completions.put(Completion(handle, slot, dueNs))
    }

    /** The next finished URB on the fd, whichever stream it belongs to -- what REAPURB does. */
    private fun reapNext(): Completion {
        val c = completions.take()
        if (c.slot != CANCELLED) synchronized(inFlight) { inFlight.getValue(c.handle).remove(c.slot) }
        return c
    }

    override fun disconnectKernelDriver(fd: Int, interfaceNumber: Int) = true

    override fun getUsbSpeed(fd: Int) = usbSpeed

    override fun openIsoStream(fd: Int, endpointAddress: Int, maxPacketSize: Int, packetsPerUrb: Int, numUrbs: Int) =
        if (endpointAddress == feedbackEndpointAddress) FEEDBACK_HANDLE else MAIN_HANDLE

    override fun submitIso(handle: Long, slot: Int, data: ByteArray, packetLengths: IntArray): Boolean {
        var urbFrames = 0L
        for (bytes in packetLengths) {
            val packetFrames = bytes / frameBytes
            urbFrames += packetFrames
            if (packetFrames > largestPacketFrames) largestPacketFrames = packetFrames
            if (packetFrames < smallestPacketFrames) smallestPacketFrames = packetFrames
        }
        synchronized(countLock) {
            frames += urbFrames
            packets += packetLengths.size
        }
        submit(MAIN_HANDLE, slot, packetLengths.size)
        return true
    }

    override fun reapIsoAny(handle: Long): Long {
        val c = reapNext()
        return if (c.slot == CANCELLED) {
            UsbNativeBridge.REAP_ERROR_FATAL.toLong()
        } else {
            UsbNativeBridge.reapResult(c.handle, c.slot)
        }
    }

    override fun cancelIso(handle: Long) {
        completions.put(Completion(handle, CANCELLED, System.nanoTime()))
    }

    override fun closeIsoStream(handle: Long) = Unit

    override fun submitIsoRead(handle: Long, slot: Int, packetLengths: IntArray): Boolean {
        submit(FEEDBACK_HANDLE, slot, packetLengths.size)
        return true
    }

    override fun readIsoSlotData(handle: Long, slot: Int, dst: ByteArray): Int {
        feedbackReads.incrementAndGet()
        val bytes = feedbackBytes()
        val n = minOf(bytes.size, dst.size)
        bytes.copyInto(dst, 0, 0, n)
        return n
    }

    override fun getPacketErrorStats(handle: Long) = longArrayOf(0L, 0L)
}
