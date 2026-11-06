/*
 * Copyright (c) 2026 libusbdac Project
 * FeedbackRateDecoder.kt is part of libusbdac.
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

import org.ostrichbat2k.usbdac.UsbAudioTuning
import org.ostrichbat2k.usbdac.protocol.u8
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Decodes an Asynchronous device's feedback-endpoint payload (UAC 5.12.4.2) into a real sample
 * rate in Hz. The on-wire format is genuinely ambiguous, not just under-documented: UAC 1.0 never
 * pinned it down precisely, and real devices disagree on what a 4-byte payload means (confirmed
 * against the history of Linux's own `sound/usb/endpoint.c`, which runtime-detects rather than
 * trusting declared USB speed alone to pick a format). Rather than hardcode one formula, [decode]
 * tries every candidate interpretation the raw byte count allows and keeps whichever lands
 * closest to -- and within [toleranceFraction] of -- [nominalRateHz], which the caller always has
 * on hand (it's the rate already negotiated for this stream). This stays pure Kotlin deliberately,
 * unlike the native URB-struct code in `usb_native.cpp`: it only ever touches a small [ByteArray]
 * already copied out to the JVM side, so plain JUnit is strictly simpler than the separate
 * `native-test/` CMake+GoogleTest harness that code earned.
 */
internal object FeedbackRateDecoder {

    // 3-byte payload (full-speed): 10.14 fixed point (UAC 1.0 Sec. 9.2.2).
    private const val FRACTIONAL_BITS_10_14 = 14

    // 4-byte payload (high-speed): 16.16 fixed point (UAC 2.0 Sec. 5.2.2.3) -- but some real
    // high-speed devices instead zero-pad a 10.14 value out to 4 bytes rather than switching
    // formats, hence trying both candidates below rather than picking one by payload size alone.
    private const val FRACTIONAL_BITS_16_16 = 16

    // Both formats encode samples-per-1ms-frame, not Hz directly (UAC 1.0 Sec. 9.2.2: "the number
    // of samples per frame"; UAC 2.0's 16.16 variant is the same unit at finer precision, not a
    // per-microframe value, despite high-speed's 125us microframes elsewhere in the spec) -- the
    // integer part alone is already ~rate/1000 (e.g. ~44 for a 44100Hz device), so converting back
    // to Hz means multiplying by the 1ms frame rate, not just dividing by the fixed-point scale.
    private const val FRAMES_PER_SECOND = 1000

    private const val FULL_SPEED_PAYLOAD_BYTES = 3
    private const val HIGH_SPEED_PAYLOAD_BYTES = 4
    private const val BITS_PER_BYTE = 8

    /**
     * Returns the decoded rate in Hz, or null if [raw] is an unexpected length or none of its
     * candidate decodings land within [toleranceFraction] of [nominalRateHz]. Callers must treat
     * null exactly like a transient feedback-read error: skip this sample, keep whatever rate was
     * last set, never let a garbage decode corrupt playback pacing.
     */
    fun decode(
        raw: ByteArray,
        nominalRateHz: Int,
        toleranceFraction: Double = UsbAudioTuning.FEEDBACK_RATE_TOLERANCE_FRACTION,
    ): Int? {
        val candidates =
            when (raw.size) {
                FULL_SPEED_PAYLOAD_BYTES -> listOf(decodeFixedPoint(raw, FRACTIONAL_BITS_10_14))
                HIGH_SPEED_PAYLOAD_BYTES ->
                    listOf(decodeFixedPoint(raw, FRACTIONAL_BITS_16_16), decodeFixedPoint(raw, FRACTIONAL_BITS_10_14))
                else -> return null
            }
        return candidates.firstOrNull { isWithinTolerance(it, nominalRateHz, toleranceFraction) }
    }

    private fun isWithinTolerance(candidateHz: Int, nominalRateHz: Int, toleranceFraction: Double): Boolean {
        if (nominalRateHz <= 0) return false
        return abs(candidateHz - nominalRateHz) <= nominalRateHz * toleranceFraction
    }

    private fun decodeFixedPoint(raw: ByteArray, fractionalBits: Int): Int {
        var rawValue = 0L
        for (i in raw.indices) {
            rawValue = rawValue or (raw.u8(i).toLong() shl (BITS_PER_BYTE * i))
        }
        return (rawValue.toDouble() * FRAMES_PER_SECOND / (1L shl fractionalBits)).roundToInt()
    }
}
