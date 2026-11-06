/*
 * Copyright (c) 2026 libusbdac Project
 * FeedbackRateDecoderTest.kt is part of libusbdac.
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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FeedbackRateDecoderTest {

    // Both the 10.14 (3-byte) and 16.16 (4-byte) formats encode samples-per-1ms-frame, little-
    // endian (UAC 1.0 Sec. 9.2.2) -- e.g. a 44100Hz device reports ~44.1 samples/frame, not 44100
    // directly. [FeedbackRateDecoder] must convert back to Hz by multiplying by the 1000Hz frame
    // rate, not just by undoing the fixed-point scale.
    private fun encode10_14(rateHz: Int): ByteArray {
        val raw = Math.round(rateHz.toDouble() / 1000 * (1 shl 14))
        return byteArrayOf((raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte(), ((raw shr 16) and 0xFF).toByte())
    }

    private fun encode16_16(rateHz: Int): ByteArray {
        val raw = Math.round(rateHz.toDouble() / 1000 * (1 shl 16))
        return byteArrayOf(
            (raw and 0xFF).toByte(),
            ((raw shr 8) and 0xFF).toByte(),
            ((raw shr 16) and 0xFF).toByte(),
            ((raw shr 24) and 0xFF).toByte())
    }

    @Test
    fun `decodes a genuine 3-byte 10_14 payload to the real rate in Hz, not samples-per-frame`() {
        for (rateHz in listOf(44100, 48000, 96000, 192000, 384000)) {
            val decoded = FeedbackRateDecoder.decode(encode10_14(rateHz), nominalRateHz = rateHz)
            assertEquals(rateHz, decoded, "rateHz=$rateHz")
        }
    }

    @Test
    fun `decodes a genuine 4-byte 16_16 payload to the real rate in Hz`() {
        for (rateHz in listOf(44100, 48000, 96000, 192000, 384000)) {
            val decoded = FeedbackRateDecoder.decode(encode16_16(rateHz), nominalRateHz = rateHz)
            assertEquals(rateHz, decoded, "rateHz=$rateHz")
        }
    }

    @Test
    fun `decodes a 4-byte payload that is really a zero-padded 10_14 value, not a genuine 16_16 one`() {
        // The top byte is zero -- same bytes a 3-byte 10.14 payload would have, just carried in a
        // 4-byte transfer. The genuine-16.16 candidate interpretation of these same bytes lands
        // nowhere near the nominal rate and must be rejected in favor of the 10.14 one.
        val rateHz = 48000
        val padded = encode10_14(rateHz) + byteArrayOf(0)
        assertEquals(rateHz, FeedbackRateDecoder.decode(padded, nominalRateHz = rateHz))
    }

    @Test
    fun `a decode within tolerance of a slightly-drifted nominal rate is still accepted`() {
        val reportedHz = 44110 // a real device's clock, slightly off from the nominal 44100
        val decoded = FeedbackRateDecoder.decode(encode10_14(reportedHz), nominalRateHz = 44100)
        assertEquals(reportedHz, decoded)
    }

    @Test
    fun `a decode wildly outside toleranceFraction of nominalRateHz is rejected as a misdecode`() {
        // Encoded for 96000Hz but the caller expects 44100Hz -- outside the default 10% band.
        val decoded = FeedbackRateDecoder.decode(encode10_14(96000), nominalRateHz = 44100)
        assertNull(decoded)
    }

    @Test
    fun `a decode just outside a tightened toleranceFraction is rejected`() {
        val rateHz = 44100
        val reportedHz = (rateHz * 1.02).toInt() // 2% off
        val decoded =
            FeedbackRateDecoder.decode(encode10_14(reportedHz), nominalRateHz = rateHz, toleranceFraction = 0.01)
        assertNull(decoded)
    }

    @Test
    fun `an unexpected payload length is rejected regardless of its bytes`() {
        for (length in listOf(0, 1, 2, 5, 8)) {
            val decoded = FeedbackRateDecoder.decode(ByteArray(length), nominalRateHz = 44100)
            assertNull(decoded, "length=$length")
        }
    }

    @Test
    fun `a non-positive nominalRateHz never matches, however the bytes decode`() {
        assertNull(FeedbackRateDecoder.decode(encode10_14(44100), nominalRateHz = 0))
        assertNull(FeedbackRateDecoder.decode(encode10_14(44100), nominalRateHz = -1))
    }
}
