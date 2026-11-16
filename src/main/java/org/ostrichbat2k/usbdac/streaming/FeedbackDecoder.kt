/*
 * Copyright (c) 2026 libusbdac Project
 * FeedbackDecoder.kt is part of libusbdac.
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

/**
 * Turns an Asynchronous device's feedback-endpoint payload into the rate the device is asking
 * for, as Q16.16 samples per data packet -- the unit [IsoPacketScheduler.updateSamplesPerPacketQ16]
 * takes, so the value is never converted to Hz and back.
 *
 * What the specifications say is on the wire (USB 2.0 5.12.4.2, UAC 2.0 2.3.1.1):
 * - full speed: 3 bytes, 10.14 fixed point, samples per 1 ms frame;
 * - high speed: 4 bytes, 16.16 fixed point, samples per 125 us *microframe*.
 *
 * Real devices do not all follow that. Some high-speed devices keep sending the full-speed value;
 * Linux's own UAC2 gadget scales the value by the data endpoint's bInterval (samples per data
 * packet) because the Windows and macOS drivers expect that. So the format is detected, not
 * assumed, the way Linux's `snd_usb_handle_sync_urb` (sound/usb/endpoint.c) does it: every one of
 * those variants is the true value times a power of two, and the true value has to be close to
 * the nominal rate, so the first payload is shifted left or right until it is, and that shift is
 * remembered for the rest of the stream. One rule covers all the variants, including any
 * bInterval on either endpoint.
 *
 * Before this (#119) a 4-byte value was read as samples per 1 ms frame. That is wrong by a factor
 * of 8 for every spec-conforming high-speed device, every such value fell outside the tolerance
 * band, and feedback was silently never applied.
 *
 * One instance per stream; used only from the feedback thread. [decode] allocates nothing.
 */
internal class FeedbackDecoder(
    nominalRateHz: Int,
    packetsPerSecond: Int,
    maxSamplesPerPacket: Int = Int.MAX_VALUE,
    toleranceFraction: Double = UsbAudioTuning.FEEDBACK_RATE_TOLERANCE_FRACTION,
) {
    companion object {
        /** [decode]'s "no usable value" result. Any real result is positive. */
        const val REJECTED = -1L

        /** [detectedShift] before the first value has been accepted, and after a rejection. */
        const val SHIFT_UNKNOWN = Int.MIN_VALUE

        private const val Q16_SHIFT = 16
        private const val FULL_SPEED_PAYLOAD_BYTES = 3
        private const val HIGH_SPEED_PAYLOAD_BYTES = 4
        private const val BITS_PER_BYTE = 8

        // Same masks as Linux: the top nibble of a 4-byte value is not part of the number.
        private const val MASK_3_BYTES = 0x00FFFFFFL
        private const val MASK_4_BYTES = 0x0FFFFFFFL

        // The detection window is one octave wide (-25% .. +50% of nominal, the same window Linux
        // uses), so exactly one power-of-two shift of any value lands in it. Bounded so a corrupt
        // value cannot spin.
        private const val DETECT_BELOW_DIVISOR = 4
        private const val DETECT_ABOVE_DIVISOR = 2
        private const val MAX_SHIFT = 16
    }

    private val nominalQ16: Long =
        if (nominalRateHz > 0 && packetsPerSecond > 0) {
            ((nominalRateHz.toLong() shl Q16_SHIFT) + packetsPerSecond / 2) / packetsPerSecond
        } else {
            0L
        }
    private val lowestAcceptedQ16 = nominalQ16 - (nominalQ16 * toleranceFraction).toLong()
    private val highestAcceptedQ16 =
        minOf(nominalQ16 + (nominalQ16 * toleranceFraction).toLong(), maxSamplesPerPacket.toLong() shl Q16_SHIFT)

    /**
     * How many bits the device's values are shifted left (positive) or right (negative) to become
     * Q16.16 samples per data packet, or [SHIFT_UNKNOWN]. For diagnostics: 0 is a spec-conforming
     * high-speed device with bInterval 1, 2 is the full-speed 10.14 format.
     */
    var detectedShift: Int = SHIFT_UNKNOWN
        private set

    /**
     * Decodes the first [length] bytes of [raw]. Returns Q16.16 samples per data packet, or
     * [REJECTED] for a payload that is too short, zero, or not within the tolerance band of the
     * nominal rate under any shift. A caller treats [REJECTED] like a failed read: keep the rate
     * already in use.
     */
    fun decode(raw: ByteArray, length: Int): Long {
        val wire = readWireValue(raw, length)
        var shift = detectedShift
        val value =
            when {
                wire == 0L -> 0L
                shift == SHIFT_UNKNOWN -> {
                    shift = shiftTowardsNominal(wire)
                    shifted(wire, shift)
                }
                else -> shifted(wire, shift)
            }
        val accepted = value in lowestAcceptedQ16..highestAcceptedQ16 && value > 0L
        // A rejected value is either garbage or means the remembered shift is wrong; either way
        // detect again on the next one. A payload that could not be read at all (wire == 0)
        // says nothing about the format, so it leaves the shift alone.
        if (wire != 0L) detectedShift = if (accepted) shift else SHIFT_UNKNOWN
        return if (accepted) value else REJECTED
    }

    /** The payload as an unsigned little-endian number, or 0 if there is nothing usable in it. */
    private fun readWireValue(raw: ByteArray, length: Int): Long {
        if (nominalQ16 <= 0L || length < FULL_SPEED_PAYLOAD_BYTES || length > raw.size) return 0L
        val threeBytes = length == FULL_SPEED_PAYLOAD_BYTES
        val bytes = if (threeBytes) FULL_SPEED_PAYLOAD_BYTES else HIGH_SPEED_PAYLOAD_BYTES
        var value = 0L
        for (i in 0 until bytes) value = value or (raw.u8(i).toLong() shl (BITS_PER_BYTE * i))
        return value and (if (threeBytes) MASK_3_BYTES else MASK_4_BYTES)
    }

    /** The power-of-two shift that brings [wire] into the one-octave window around nominal. */
    private fun shiftTowardsNominal(wire: Long): Int {
        var value = wire
        var shift = 0
        while (value < nominalQ16 - nominalQ16 / DETECT_BELOW_DIVISOR && shift < MAX_SHIFT) {
            value = value shl 1
            shift++
        }
        while (value > nominalQ16 + nominalQ16 / DETECT_ABOVE_DIVISOR && shift > -MAX_SHIFT) {
            value = value shr 1
            shift--
        }
        return shift
    }

    private fun shifted(value: Long, shift: Int): Long = if (shift >= 0) value shl shift else value shr -shift
}
