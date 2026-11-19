/*
 * Copyright (c) 2026 libusbdac Project
 * FeedbackRateCrossCheckTest.kt is part of libusbdac.
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

/** #124: when feedback says the clock is at another standard rate, that is reported once. */
class FeedbackRateCrossCheckTest {
    private companion object {
        const val PACKETS_PER_SECOND = 8000
    }

    private fun q16(rateHz: Double): Long = (rateHz / PACKETS_PER_SECOND * 65536).toLong()

    private fun FeedbackRateCrossCheck.feed(rateHz: Double, times: Int): List<Int?> =
        List(times) { observe(q16(rateHz)) }

    @Test
    fun `a steady different standard rate is reported once, after enough values`() {
        val check = FeedbackRateCrossCheck(44100, PACKETS_PER_SECOND)
        val results = check.feed(48000.0, FeedbackRateCrossCheck.CONSECUTIVE_TO_REPORT + 10)
        assertEquals(
            List(FeedbackRateCrossCheck.CONSECUTIVE_TO_REPORT - 1) { null },
            results.take(FeedbackRateCrossCheck.CONSECUTIVE_TO_REPORT - 1))
        assertEquals(48000, results[FeedbackRateCrossCheck.CONSECUTIVE_TO_REPORT - 1])
        assertEquals(List(10) { null }, results.drop(FeedbackRateCrossCheck.CONSECUTIVE_TO_REPORT))
    }

    @Test
    fun `the requested rate with ordinary clock error is never reported`() {
        val check = FeedbackRateCrossCheck(44100, PACKETS_PER_SECOND)
        assertEquals(List(200) { null }, check.feed(44100 * 1.001, 200))
    }

    @Test
    fun `a rate that isn't a standard one is not reported`() {
        val check = FeedbackRateCrossCheck(44100, PACKETS_PER_SECOND)
        assertEquals(List(200) { null }, check.feed(46000.0, 200))
    }

    @Test
    fun `a run interrupted by an in-range value starts counting again`() {
        val check = FeedbackRateCrossCheck(48000, PACKETS_PER_SECOND)
        check.feed(44100.0, FeedbackRateCrossCheck.CONSECUTIVE_TO_REPORT - 1)
        assertNull(check.observe(q16(48000.0)))
        val results = check.feed(44100.0, FeedbackRateCrossCheck.CONSECUTIVE_TO_REPORT)
        assertEquals(44100, results.last())
        assertEquals(1, results.count { it != null })
    }

    @Test
    fun `unreadable values are ignored`() {
        val check = FeedbackRateCrossCheck(44100, PACKETS_PER_SECOND)
        repeat(200) { assertNull(check.observe(0L)) }
    }

    @Test
    fun `the decoder exposes the shifted value even when its tolerance check rejects it`() {
        // 44.1 kHz nominal at high speed; 60 kHz is outside the decoder's 10% tolerance but
        // inside its detection octave, so it is shifted, then rejected.
        val decoder = FeedbackDecoder(44100, PACKETS_PER_SECOND)
        val value = q16(60000.0)
        val payload = ByteArray(4) { i -> ((value shr (8 * i)) and 0xFF).toByte() }
        assertEquals(FeedbackDecoder.REJECTED, decoder.decode(payload, 4))
        assertEquals(value, decoder.lastShiftedQ16)
    }
}
