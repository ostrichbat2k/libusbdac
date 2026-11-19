/*
 * Copyright (c) 2026 libusbdac Project
 * AsyncFeedbackEndToEndTest.kt is part of libusbdac.
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
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
 * Runs the real [UsbIsoStreamEngine] -- its streaming loop, feedback loop, [IsoPacketScheduler]
 * and feedback decoder, unmodified -- against [AsyncDacSimulator], and checks the one thing an
 * Asynchronous device needs from its host: that the rate of frames sent follows the device's
 * clock as reported on the feedback endpoint, not the nominal rate (#119, #120).
 *
 * Every case uses a device clock 1000 ppm away from nominal. Real crystals are within about
 * 100 ppm; the exaggeration only makes "followed the feedback" and "stayed at nominal"
 * unmistakably different within a few seconds.
 */
class AsyncFeedbackEndToEndTest {
    private companion object {
        const val USB_SPEED_FULL = 2
        const val USB_SPEED_HIGH = 3
        const val CHANNELS = 2
        const val SUBSLOT_BYTES = 2
        const val FEEDBACK_ENDPOINT = 0x81
        const val DEVICE_CLOCK_OFFSET = 1.001
        const val SETTLE_MS = 500L
        const val MEASURE_MS = 2500L
        // Sending the nominal rate instead would be off by 1000 ppm.
        const val MAX_RATE_ERROR_PPM = 50.0
        // One packet's rounding at either end of the window, with margin.
        const val MAX_BUFFER_DRIFT_FRAMES = 8.0
        const val SHARED_QUEUE_MEASURE_MS = 1000L
        // The feedback endpoint completes about once a millisecond; a third of that is plenty.
        const val MIN_FEEDBACK_READS = 300
    }

    private val topology =
        UacPlaybackTopology(
            configurationValue = 1,
            controlInterfaceNumber = 0,
            streamingInterfaceNumber = 1,
            uacVersion = 2,
            clockSourceId = 1,
            outputFeatureUnitId = -1,
            mixerUnitId = -1,
            mixerInputChannelBase = -1,
            powerDomainId = -1,
            powerDomainD1RecoveryUs = 0,
            powerDomainD2RecoveryUs = 0,
            channelCount = CHANNELS,
            formats = emptyList(),
        )

    private val session =
        UsbAudioSession(
            device = mockk<UsbDevice>(relaxed = true),
            connection = mockk<UsbDeviceConnection>(relaxed = true) { every { fileDescriptor } returns 42 },
            topology = topology,
            controlInterface = mockk<UsbInterface>(relaxed = true) { every { id } returns 0 },
            streamingAltSettings = emptyMap(),
        )

    private data class Case(
        val usbSpeed: Int,
        val dataBInterval: Int,
        val nominalRateHz: Int,
        val wire: FeedbackWire = FeedbackWire.SPEC,
        val feedbackPacketBytes: Int = 4,
        val clockOffset: Double = DEVICE_CLOCK_OFFSET,
    )

    private fun assertHostFollowsDeviceClock(case: Case) {
        val usbSpeed = case.usbSpeed
        val dataBInterval = case.dataBInterval
        val nominalRateHz = case.nominalRateHz
        val clockOffset = case.clockOffset
        val deviceRateHz = nominalRateHz * clockOffset
        val dac =
            AsyncDacSimulator(
                usbSpeed = usbSpeed,
                dataBInterval = dataBInterval,
                deviceRateHz = deviceRateHz,
                frameBytes = CHANNELS * SUBSLOT_BYTES,
                feedbackWire = case.wire,
                feedbackEndpointAddress = FEEDBACK_ENDPOINT,
            )
        val format =
            UacStreamFormat(
                alternateSetting = 1,
                bitResolution = 16,
                subslotSize = SUBSLOT_BYTES,
                clusterDescriptorId = -1,
                endpointAddress = 0x01,
                maxPacketSize = 1024,
                syncType = SyncType.ASYNC,
                bInterval = dataBInterval,
                feedbackEndpoint = UacFeedbackEndpoint(FEEDBACK_ENDPOINT, case.feedbackPacketBytes, bInterval = 4),
            )
        val engine =
            UsbIsoStreamEngine(
                session = session,
                format = format,
                channels = CHANNELS,
                sampleRateHz = nominalRateHz,
                ringBuffer = PcmRingBuffer(1 shl 16),
                // Paused means the engine sends silence at the scheduled packet sizes, which is
                // all this test looks at -- and keeps the empty ring buffer from logging underruns.
                paused = { true },
                reportPipelineStats = {},
                nativeBridge = dac,
            )
        engine.start()
        try {
            Thread.sleep(SETTLE_MS)
            val from = dac.snapshot()
            Thread.sleep(MEASURE_MS)
            val to = dac.snapshot()

            val delivered = dac.deliveredRateHz(from, to)
            val errorPpm = abs(delivered - deviceRateHz) / deviceRateHz * 1e6
            val drift = dac.bufferDriftFrames(from, to)
            val detail =
                "nominal=$nominalRateHz device=$deviceRateHz delivered=$delivered " +
                    "(${"%.1f".format(errorPpm)} ppm off) bufferDrift=${"%.1f".format(drift)} frames over " +
                    "${to.packets - from.packets} packets, packet sizes " +
                    "${dac.smallestPacketFrames}..${dac.largestPacketFrames} frames"
            assertTrue(errorPpm < MAX_RATE_ERROR_PPM, "host did not follow the device clock: $detail")
            assertTrue(abs(drift) < MAX_BUFFER_DRIFT_FRAMES, "device buffer drifted: $detail")
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `high speed, spec feedback, 44100 Hz`() = assertHostFollowsDeviceClock(Case(USB_SPEED_HIGH, 1, 44100))

    @Test
    fun `high speed, spec feedback, 192000 Hz`() = assertHostFollowsDeviceClock(Case(USB_SPEED_HIGH, 1, 192000))

    @Test
    fun `high speed, 1 ms data packets, spec feedback per microframe`() =
        assertHostFollowsDeviceClock(Case(USB_SPEED_HIGH, 4, 48000))

    @Test
    fun `high speed, 1 ms data packets, Linux gadget feedback per packet`() =
        assertHostFollowsDeviceClock(Case(USB_SPEED_HIGH, 4, 44100, FeedbackWire.LINUX_GADGET))

    @Test
    fun `high speed device that sends the full-speed format in four bytes`() =
        assertHostFollowsDeviceClock(Case(USB_SPEED_HIGH, 1, 96000, FeedbackWire.Q10_14_PER_FRAME_IN_4_BYTES))

    @Test
    fun `full speed, three byte feedback, 48000 Hz`() =
        assertHostFollowsDeviceClock(Case(USB_SPEED_FULL, 1, 48000, feedbackPacketBytes = 3))

    @Test
    fun `full speed, three byte feedback, 44100 Hz`() =
        assertHostFollowsDeviceClock(Case(USB_SPEED_FULL, 1, 44100, feedbackPacketBytes = 3))

    @Test
    fun `device clock slower than nominal`() =
        assertHostFollowsDeviceClock(Case(USB_SPEED_HIGH, 1, 44100, clockOffset = 0.999))

    @Test
    fun `device clock exactly nominal stays exact`() =
        assertHostFollowsDeviceClock(Case(USB_SPEED_HIGH, 1, 44100, clockOffset = 1.0))

    // ---- #139: both streams share one fd, and so one completion list ----

    @Test
    fun `main and feedback URBs reaped from one shared completion list stay with their own stream`() {
        val dac =
            AsyncDacSimulator(
                usbSpeed = USB_SPEED_HIGH,
                dataBInterval = 1,
                deviceRateHz = 48000 * DEVICE_CLOCK_OFFSET,
                frameBytes = CHANNELS * SUBSLOT_BYTES,
                feedbackWire = FeedbackWire.SPEC,
                feedbackEndpointAddress = FEEDBACK_ENDPOINT,
            )
        val format =
            UacStreamFormat(
                alternateSetting = 1,
                bitResolution = 16,
                subslotSize = SUBSLOT_BYTES,
                clusterDescriptorId = -1,
                endpointAddress = 0x01,
                maxPacketSize = 1024,
                syncType = SyncType.ASYNC,
                feedbackEndpoint = UacFeedbackEndpoint(FEEDBACK_ENDPOINT, 4, bInterval = 4),
            )
        val engine =
            UsbIsoStreamEngine(
                session = session,
                format = format,
                channels = CHANNELS,
                sampleRateHz = 48000,
                ringBuffer = PcmRingBuffer(1 shl 16),
                paused = { true },
                reportPipelineStats = {},
                nativeBridge = dac,
            )
        engine.start()
        var minMainInFlight = Int.MAX_VALUE
        try {
            Thread.sleep(SETTLE_MS)
            val until = System.currentTimeMillis() + SHARED_QUEUE_MEASURE_MS
            while (System.currentTimeMillis() < until) {
                minMainInFlight = minOf(minMainInFlight, dac.mainInFlight())
                Thread.sleep(1)
            }
        } finally {
            engine.stop()
        }
        assertEquals(0, dac.doubleSubmissions.get(), "a slot was resubmitted while its URB was still in flight")
        // Between one reap and its resubmit a single slot is briefly out; never more than that.
        assertTrue(
            minMainInFlight >= engine.numUrbs - 1,
            "main URBs dropped out of rotation: as few as $minMainInFlight of ${engine.numUrbs} in flight")
        assertTrue(dac.feedbackReads.get() > MIN_FEEDBACK_READS, "feedback serviced only ${dac.feedbackReads.get()}x")
    }

    // ---- #124: feedback as a cross-check on the clock change ----

    /** Streams to a device whose clock really runs at [deviceRateHz] after [requestedRateHz] was asked for. */
    private fun mismatchReportedWithin(requestedRateHz: Int, deviceRateHz: Double, waitMs: Long): Pair<Int, Int>? {
        val dac =
            AsyncDacSimulator(
                usbSpeed = USB_SPEED_HIGH,
                dataBInterval = 1,
                deviceRateHz = deviceRateHz,
                frameBytes = CHANNELS * SUBSLOT_BYTES,
                feedbackWire = FeedbackWire.SPEC,
                feedbackEndpointAddress = FEEDBACK_ENDPOINT,
            )
        val format =
            UacStreamFormat(
                alternateSetting = 1,
                bitResolution = 16,
                subslotSize = SUBSLOT_BYTES,
                clusterDescriptorId = -1,
                endpointAddress = 0x01,
                maxPacketSize = 1024,
                syncType = SyncType.ASYNC,
                feedbackEndpoint = UacFeedbackEndpoint(FEEDBACK_ENDPOINT, 4, bInterval = 4),
            )
        val reported = CountDownLatch(1)
        var report: Pair<Int, Int>? = null
        val engine =
            UsbIsoStreamEngine(
                session = session,
                format = format,
                channels = CHANNELS,
                sampleRateHz = requestedRateHz,
                ringBuffer = PcmRingBuffer(1 shl 16),
                paused = { true },
                reportPipelineStats = {},
                nativeBridge = dac,
                onFeedbackRateMismatch = { requested, observed ->
                    report = requested to observed
                    reported.countDown()
                },
            )
        engine.start()
        try {
            reported.await(waitMs, TimeUnit.MILLISECONDS)
        } finally {
            engine.stop()
        }
        return report
    }

    @Test
    fun `a device whose clock stayed in the other rate family is reported`() {
        // 44.1 kHz asked for, 48 kHz running: within the decoder's 10% tolerance, so the host
        // would follow it, playing everything 8.8% fast, without this check.
        assertEquals(44100 to 48000, mismatchReportedWithin(44100, 48000.0, waitMs = 2000))
    }

    @Test
    fun `a device at the requested rate plus ordinary clock error is not reported`() {
        assertFalse(mismatchReportedWithin(44100, 44100 * DEVICE_CLOCK_OFFSET, waitMs = 600) != null)
    }
}
