/*
 * Copyright (c) 2026 libusbdac Project
 * FeedbackRateCrossCheck.kt is part of libusbdac.
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

import kotlin.math.abs

/**
 * Cross-checks a clock change against what an Asynchronous device's feedback says it is really
 * running at (#124). A device can accept SET_CUR, echo the rate back on GET_CUR and still run its
 * clock at another rate; its feedback then asks for that other rate. When the feedback keeps
 * decoding to a different standard rate than the one requested, [observe] reports that rate,
 * once per stream. Detection only: nothing is retried.
 *
 * What it can't see: [FeedbackDecoder] finds the payload format by shifting the value by a power
 * of two into the octave around the nominal rate, so a clock at 2x, 4x... the requested rate is
 * indistinguishable from a format variant. What it does see is a clock in the other rate family
 * (44.1 kHz asked, 48 kHz or a power-of-two multiple running, or the reverse) and other standard
 * rates within the octave. The reported rate is the one inside that octave.
 *
 * Used only from the feedback thread.
 */
internal class FeedbackRateCrossCheck(private val requestedRateHz: Int, private val packetsPerSecond: Int) {
    companion object {
        val STANDARD_RATES_HZ =
            intArrayOf(8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)

        /** How close a decoded rate has to be to a standard rate to count as that rate. */
        const val MATCH_FRACTION = 0.005

        /** How far from the requested rate a standard rate has to be to be a different one. */
        const val DIFFERENT_FRACTION = 0.01

        /** Consecutive matching feedback values before reporting, so one odd value never does. */
        const val CONSECUTIVE_TO_REPORT = 64

        private const val Q16_ONE = 65536.0
    }

    private var candidateHz = 0
    private var consecutive = 0
    private var reported = false

    /**
     * [shiftedQ16] is [FeedbackDecoder.lastShiftedQ16]: the decoded value before the tolerance
     * check, in Q16.16 samples per data packet. Returns the standard rate the device appears to
     * be running at the first time the mismatch is established, null otherwise.
     */
    fun observe(shiftedQ16: Long): Int? {
        val mismatchHz = if (reported) null else mismatchedStandardRate(shiftedQ16)
        when {
            mismatchHz == null -> consecutive = 0
            mismatchHz != candidateHz -> {
                candidateHz = mismatchHz
                consecutive = 1
            }
            else -> consecutive++
        }
        if (mismatchHz == null || consecutive < CONSECUTIVE_TO_REPORT) return null
        reported = true
        return candidateHz
    }

    /** The standard rate [shiftedQ16] decodes to, if it is one and isn't the requested rate. */
    private fun mismatchedStandardRate(shiftedQ16: Long): Int? {
        if (shiftedQ16 <= 0L || packetsPerSecond <= 0) return null
        val observedHz = shiftedQ16 / Q16_ONE * packetsPerSecond
        val standard = STANDARD_RATES_HZ.firstOrNull { abs(observedHz - it) <= it * MATCH_FRACTION }
        return standard?.takeIf { abs(it - requestedRateHz) > requestedRateHz * DIFFERENT_FRACTION }
    }
}
