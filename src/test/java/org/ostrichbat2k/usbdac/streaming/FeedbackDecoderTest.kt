/*
 * Copyright (c) 2026 libusbdac Project
 * FeedbackDecoderTest.kt is part of libusbdac.
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

import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeedbackDecoderTest {
    private companion object {
        const val FULL_SPEED_PPS = 1000
        const val HIGH_SPEED_PPS = 8000
        const val REJECTED = FeedbackDecoder.REJECTED
    }

    private fun le(value: Long, size: Int) = ByteArray(size) { i -> ((value shr (8 * i)) and 0xFF).toByte() }

    /** Spec full-speed payload: 10.14 samples per 1 ms frame, 3 bytes. */
    private fun fullSpeed(rateHz: Double) = le(Math.round(rateHz * (1 shl 14) / 1000), 3)

    /** Spec high-speed payload: 16.16 samples per 125 us microframe, 4 bytes. */
    private fun highSpeed(rateHz: Double) = le(Math.round(rateHz * (1 shl 16) / 8000), 4)

    /**
     * What Linux's UAC2 gadget puts on the wire at high speed, ported from
     * `u_audio_set_fback_frequency` (drivers/usb/gadget/function/u_audio.c) at unity pitch:
     * samples per data packet, i.e. the per-microframe value scaled by the data endpoint's bInterval.
     */
    private fun linuxGadgetHighSpeed(rateHz: Long, dataBInterval: Int): ByteArray {
        val freq = rateHz shl (4 + (dataBInterval - 1))
        return le((freq * 1_000_000 + 1_953_125 / 2) / 1_953_125, 4)
    }

    private fun Long.toHz(packetsPerSecond: Int) = this.toDouble() * packetsPerSecond / 65536.0

    private fun decodeOnce(payload: ByteArray, nominalHz: Int, pps: Int): Long =
        FeedbackDecoder(nominalHz, pps).decode(payload, payload.size)

    @Test
    fun `decodes bytes captured from a real high-speed DAC at 44100 Hz`() {
        // 33 83 05 00, read off a Cayin RU7's feedback endpoint and published in decent-player's
        // driver notes (docs/driver/07-verification-and-diagnostics.md, MIT). 0x00058333 / 65536
        // = 5.5125 samples per microframe = 44100 Hz. The previous decoder read these same bytes
        // as 5512 Hz and discarded them.
        val captured = byteArrayOf(0x33, 0x83.toByte(), 0x05, 0x00)
        val decoder = FeedbackDecoder(44100, HIGH_SPEED_PPS)
        val q16 = decoder.decode(captured, captured.size)
        assertEquals(0x00058333L, q16)
        assertEquals(44100.0, q16.toHz(HIGH_SPEED_PPS), 0.1)
        assertEquals(0, decoder.detectedShift)
    }

    @Test
    fun `spec high-speed payloads decode to the device's rate at every common rate`() {
        for (rateHz in listOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 768000)) {
            val q16 = decodeOnce(highSpeed(rateHz.toDouble()), rateHz, HIGH_SPEED_PPS)
            assertEquals(rateHz.toDouble(), q16.toHz(HIGH_SPEED_PPS), 0.2, "rateHz=$rateHz")
        }
    }

    @Test
    fun `spec full-speed payloads decode to the device's rate`() {
        for (rateHz in listOf(8000, 44100, 48000, 96000)) {
            val decoder = FeedbackDecoder(rateHz, FULL_SPEED_PPS)
            val payload = fullSpeed(rateHz.toDouble())
            val q16 = decoder.decode(payload, payload.size)
            assertEquals(rateHz.toDouble(), q16.toHz(FULL_SPEED_PPS), 0.1, "rateHz=$rateHz")
            assertEquals(2, decoder.detectedShift, "10.14 is Q16.16 shifted right by two")
        }
    }

    @Test
    fun `a device clock a little off nominal is reported as it is, not snapped to nominal`() {
        for (ppm in listOf(-500, -50, 50, 500)) {
            val deviceHz = 44100.0 * (1 + ppm / 1e6)
            val q16 = decodeOnce(highSpeed(deviceHz), 44100, HIGH_SPEED_PPS)
            assertEquals(deviceHz, q16.toHz(HIGH_SPEED_PPS), 0.1, "ppm=$ppm")
        }
    }

    @Test
    fun `a high-speed device that sends the full-speed 10_14 per-frame value in four bytes`() {
        val payload = fullSpeed(48000.0) + byteArrayOf(0)
        val q16 = decodeOnce(payload, 48000, HIGH_SPEED_PPS)
        assertEquals(48000.0, q16.toHz(HIGH_SPEED_PPS), 0.1)
    }

    @Test
    fun `per-microframe value with a slower data endpoint is scaled to the data packet interval`() {
        // Data endpoint bInterval 4 at high speed is one packet per 1 ms, so the answer has to be
        // samples per 1 ms although the device reported samples per 125 us.
        val decoder = FeedbackDecoder(48000, packetsPerSecond = 1000)
        val payload = highSpeed(48048.0)
        val q16 = decoder.decode(payload, payload.size)
        assertEquals(48048.0, q16.toHz(1000), 0.2)
        assertEquals(3, decoder.detectedShift)
    }

    @Test
    fun `Linux gadget payloads, which are per data packet, decode at every data bInterval`() {
        for (dataBInterval in 1..4) {
            val pps = 8000 shr (dataBInterval - 1)
            for (rateHz in listOf(44100L, 48000L, 96000L)) {
                val decoder = FeedbackDecoder(rateHz.toInt(), pps)
                val payload = linuxGadgetHighSpeed(rateHz, dataBInterval)
                val q16 = decoder.decode(payload, payload.size)
                assertEquals(rateHz.toDouble(), q16.toHz(pps), 0.2, "rate=$rateHz bInterval=$dataBInterval")
                assertEquals(0, decoder.detectedShift, "already per packet")
            }
        }
    }

    @Test
    fun `the top nibble of a four byte value is not part of the number`() {
        val payload = highSpeed(44100.0)
        payload[3] = (payload[3].toInt() or 0xF0).toByte()
        assertEquals(44100.0, decodeOnce(payload, 44100, HIGH_SPEED_PPS).toHz(HIGH_SPEED_PPS), 0.1)
    }

    @Test
    fun `only the reported length is read from a larger buffer`() {
        val buffer = fullSpeed(48000.0) + byteArrayOf(0x7F, 0x7F, 0x7F)
        val q16 = FeedbackDecoder(48000, FULL_SPEED_PPS).decode(buffer, 3)
        assertEquals(48000.0, q16.toHz(FULL_SPEED_PPS), 0.1)
    }

    @Test
    fun `payloads longer than four bytes are read as four`() {
        val buffer = highSpeed(96000.0) + byteArrayOf(0x55, 0x55)
        val q16 = FeedbackDecoder(96000, HIGH_SPEED_PPS).decode(buffer, buffer.size)
        assertEquals(96000.0, q16.toHz(HIGH_SPEED_PPS), 0.2)
    }

    @Test
    fun `short, zero and overlong-length payloads are rejected`() {
        val decoder = FeedbackDecoder(44100, HIGH_SPEED_PPS)
        for (length in 0..2) assertEquals(REJECTED, decoder.decode(ByteArray(4) { 0x11 }, length), "length=$length")
        assertEquals(REJECTED, decoder.decode(ByteArray(4), 4), "all zero")
        assertEquals(REJECTED, decoder.decode(ByteArray(3) { 0x11 }, 4), "length beyond the buffer")
    }

    @Test
    fun `a value outside the tolerance band under every shift is rejected`() {
        // 1.2x nominal: inside the detection window, so no shift moves it, and outside +-10%.
        assertEquals(REJECTED, decodeOnce(highSpeed(44100.0 * 1.2), 44100, HIGH_SPEED_PPS))
        assertEquals(REJECTED, decodeOnce(highSpeed(44100.0 * 0.8), 44100, HIGH_SPEED_PPS))
    }

    @Test
    fun `a tightened tolerance rejects what the default accepts`() {
        val payload = highSpeed(44100.0 * 1.02)
        assertTrue(FeedbackDecoder(44100, HIGH_SPEED_PPS).decode(payload, 4) > 0)
        assertEquals(REJECTED, FeedbackDecoder(44100, HIGH_SPEED_PPS, toleranceFraction = 0.01).decode(payload, 4))
    }

    @Test
    fun `a value asking for more than a packet can hold is rejected`() {
        // 48000 Hz full speed is exactly 48 samples per packet; with a 48-sample ceiling anything
        // faster cannot be honoured.
        val decoder = FeedbackDecoder(48000, FULL_SPEED_PPS, maxSamplesPerPacket = 48)
        assertTrue(decoder.decode(fullSpeed(48000.0), 3) > 0)
        assertEquals(REJECTED, decoder.decode(fullSpeed(48100.0), 3))
    }

    @Test
    fun `the detected format is remembered, and detected again after a rejected value`() {
        val decoder = FeedbackDecoder(48000, HIGH_SPEED_PPS)
        assertEquals(FeedbackDecoder.SHIFT_UNKNOWN, decoder.detectedShift)

        assertTrue(decoder.decode(highSpeed(48000.0), 4) > 0)
        assertEquals(0, decoder.detectedShift)

        // With the format locked, a value at half scale is not re-interpreted: it is rejected...
        assertEquals(REJECTED, decoder.decode(highSpeed(24000.0), 4))
        assertEquals(FeedbackDecoder.SHIFT_UNKNOWN, decoder.detectedShift)

        // ...and the next value starts detection over, so a device that really changed format
        // (or one bad packet) costs one sample, not the stream.
        val fullSpeedStyle = fullSpeed(48000.0) + byteArrayOf(0)
        assertEquals(48000.0, decoder.decode(fullSpeedStyle, 4).toHz(HIGH_SPEED_PPS), 0.1)
        assertEquals(-1, decoder.detectedShift)
    }

    @Test
    fun `a non-positive nominal rate or packet rate never accepts anything`() {
        assertEquals(REJECTED, FeedbackDecoder(0, HIGH_SPEED_PPS).decode(highSpeed(44100.0), 4))
        assertEquals(REJECTED, FeedbackDecoder(-1, HIGH_SPEED_PPS).decode(highSpeed(44100.0), 4))
        assertEquals(REJECTED, FeedbackDecoder(44100, 0).decode(highSpeed(44100.0), 4))
    }

    @Test
    fun `decoding and applying feedback allocates nothing once warm`() {
        // #121: this runs about a thousand times a second on a thread at urgent-audio priority,
        // where garbage eventually means a collector pause and an audible gap. Measured, not
        // inspected, in the manner of usbfs-iso's counting-allocator test.
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val decoder = FeedbackDecoder(44100, HIGH_SPEED_PPS)
        val scheduler = IsoPacketScheduler(44100, HIGH_SPEED_PPS)
        val payload = highSpeed(44144.1)
        var sink = 0L
        fun run(times: Int) {
            repeat(times) {
                val q16 = decoder.decode(payload, payload.size)
                if (q16 != REJECTED) scheduler.updateSamplesPerPacketQ16(q16)
                sink += scheduler.nextPacketSampleCount()
            }
        }
        run(200_000) // let the JIT finish with these methods first
        val self = Thread.currentThread().threadId()
        val before = threads.getThreadAllocatedBytes(self)
        run(100_000)
        val allocated = threads.getThreadAllocatedBytes(self) - before
        assertTrue(sink > 0)
        // 100000 iterations: even one boxed value per iteration would be over a megabyte.
        assertTrue(allocated < 4096, "allocated $allocated bytes over 100000 iterations")
    }
}
