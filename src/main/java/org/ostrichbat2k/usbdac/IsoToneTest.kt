/*
 * Copyright (c) 2026 Auxio Project
 * IsoToneTest.kt is part of Auxio.
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

import kotlin.math.PI
import kotlin.math.sin
import timber.log.Timber as L

/**
 * Generates a continuous sine tone and streams it directly to a USB Audio Class isochronous OUT
 * endpoint via raw URB submission (see UsbNative), bypassing Android's entire audio stack --
 * AudioTrack, the mixer, everything. Proof of concept for direct USB output: if this is audible,
 * bit-perfect playback works end to end at the protocol level.
 */
object IsoToneTest {
    // Target real URB duration at full-speed (8 packets * 1ms); scaled up for high-speed devices
    // below, same reasoning as UsbAudioOutput.BASE_PACKETS_PER_URB.
    private const val BASE_PACKETS_PER_URB = 8
    private const val NUM_URBS = 4
    private const val FREQUENCY_HZ = 440.0
    private const val FALLBACK_CHANNELS = 2
    private const val AMPLITUDE = 0.2 // quiet on purpose, this goes straight into someone's ears

    /**
     * Plays a sine tone for [durationMs] at [sampleRateHz] using [format]'s packet/bit layout,
     * on every channel the device actually has ([channels], from its own Input Terminal
     * descriptor -- not assumed stereo) in phase with each other.
     */
    fun play(
        session: UsbAudioSession,
        format: UacStreamFormat,
        sampleRateHz: Int,
        durationMs: Long,
        channels: Int = FALLBACK_CHANNELS,
    ) {
        val bytesPerSample = format.subslotSize
        val bytesPerFrame = channels * bytesPerSample

        val fd = session.connection.fileDescriptor

        // Same fix as UsbAudioOutput's streaming thread: a high-speed device schedules one iso
        // packet per 125us microframe, not per 1ms frame. Packing a full millisecond's worth of
        // samples into each packet (the old, speed-unaware behavior here) fed the device audio
        // 8x faster than its own playback clock could drain it -- the exact bug that made real
        // playback crackle on this hardware, and this tool shares the same native plumbing, so it
        // shared the bug too. See UsbAudioOutput.startStreamingThread's doc comment for the full
        // explanation.
        val usbSpeed = UsbNative.getUsbSpeed(fd)
        val packetsPerSecond = if (usbSpeed == 3) 8000 else 1000
        val packetDurationNs = 1_000_000_000L / packetsPerSecond
        val packetsPerUrb = BASE_PACKETS_PER_URB * (packetsPerSecond / 1000)

        val handle =
            UsbNative.openIsoStream(fd, format.endpointAddress, format.maxPacketSize, packetsPerUrb, NUM_URBS)
        if (handle == 0L) {
            L.w("Failed to open iso stream")
            return
        }

        var phase = 0.0
        val phaseStep = 2.0 * PI * FREQUENCY_HZ / sampleRateHz
        val peak = maxAmplitudeFor(bytesPerSample)
        val scheduler = IsoPacketScheduler(sampleRateHz, packetsPerSecond)

        fun buildUrb(): Pair<ByteArray, IntArray> {
            val packetLengths = IntArray(packetsPerUrb)
            var total = 0
            for (p in 0 until packetsPerUrb) {
                val bytes = scheduler.nextPacketSampleCount() * bytesPerFrame
                packetLengths[p] = bytes
                total += bytes
            }
            val buf = ByteArray(total)
            var offset = 0
            while (offset < total) {
                val value = (sin(phase) * AMPLITUDE * peak).toInt()
                phase += phaseStep
                if (phase > 2.0 * PI) phase -= 2.0 * PI
                repeat(channels) {
                    writeSample(buf, offset, value, bytesPerSample)
                    offset += bytesPerSample
                }
            }
            return buf to packetLengths
        }

        L.i(
            "Starting iso tone test: $sampleRateHz Hz, ${format.bitResolution}-bit, $durationMs ms, " +
                "usbSpeed=$usbSpeed ($packetsPerSecond packets/sec, $packetsPerUrb packets/urb)")
        try {
            for (slot in 0 until NUM_URBS) {
                val (chunk, lengths) = buildUrb()
                UsbNative.submitIso(handle, slot, chunk, lengths)
            }

            // Explicit wall-clock pacing, not just reapIso()'s own timing -- confirmed, on real
            // hardware, that reapIso() alone races ahead of real-time by however much faster the
            // true packet duration is than this loop assumes (the same discrepancy
            // UsbAudioOutput's streaming thread had to correct for).
            val streamStartNs = System.nanoTime()
            val urbRealTimeNs = packetsPerUrb * packetDurationNs
            var urbsPaced = 0L
            val deadline = System.currentTimeMillis() + durationMs
            var submitted = 0
            var errors = 0
            var submitFailures = 0
            while (System.currentTimeMillis() < deadline) {
                val slot = UsbNative.reapIso(handle)
                if (slot < 0) {
                    errors++
                    if (errors > 10) {
                        L.w("Too many reap errors, stopping tone test early")
                        break
                    }
                    continue
                }
                urbsPaced++
                val waitNs = (streamStartNs + urbsPaced * urbRealTimeNs) - System.nanoTime()
                if (waitNs > 0) {
                    Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                }
                val (chunk, lengths) = buildUrb()
                // The return value here was previously ignored, which let URBs silently fail to
                // resubmit (SUBMITURB can fail with ENODEV/EBUSY/ESHUTDOWN even when reapIso()
                // keeps succeeding for other in-flight URBs) while "0 reap errors" made it look
                // like a perfectly clean transfer -- exactly the gap that was hiding a real,
                // intermittent submission failure during silent-but-"clean" runs.
                if (UsbNative.submitIso(handle, slot, chunk, lengths)) {
                    submitted++
                } else {
                    submitFailures++
                }
            }
            L.i(
                "Iso tone test done: $submitted URBs submitted, $errors reap errors, " +
                    "$submitFailures submit failures")
        } finally {
            UsbNative.closeIsoStream(handle)
        }
    }

    private fun maxAmplitudeFor(bytesPerSample: Int): Double =
        when (bytesPerSample) {
            2 -> 32767.0
            3 -> 8388607.0
            else -> 127.0
        }

    private fun writeSample(buf: ByteArray, offset: Int, value: Int, bytesPerSample: Int) {
        for (b in 0 until bytesPerSample) {
            buf[offset + b] = ((value shr (8 * b)) and 0xFF).toByte()
        }
    }
}
