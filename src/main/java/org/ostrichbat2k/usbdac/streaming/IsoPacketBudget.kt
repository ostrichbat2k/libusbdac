/*
 * Copyright (c) 2026 libusbdac Project
 * IsoPacketBudget.kt is part of libusbdac.
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

import org.ostrichbat2k.usbdac.protocol.SyncType
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat

/**
 * Whether a format can carry a sample rate at all, judged by packet size alone (#130).
 *
 * Nothing used to check that the largest packet a stream produces fits the streaming endpoint's
 * `wMaxPacketSize`. `submitIso` only checks the URB total, so an oversized packet was rejected by
 * the kernel at submit time, the resubmit failed twice, and the streaming thread stopped -- seen
 * as a stall, not as "this rate does not fit this alt-setting". Checking here, while a format is
 * being chosen, lets the provider skip that alt-setting (or report the track unsupported, so it
 * falls back to the HAL) with a log line that says why.
 *
 * The packet-size ceiling follows Linux `snd-usb-audio`'s `data_ep_set_params`, and the idea of
 * checking it up front comes from unom-io/usbfs-iso.
 */
object IsoPacketBudget {
    private const val PAYLOAD_MASK = 0x7FF
    private const val MULT_SHIFT = 11
    private const val MULT_MASK = 0x3

    /**
     * Bytes one packet may really carry, from the raw `wMaxPacketSize` field. Bits 10:0 are the
     * payload size; bits 12:11 are the number of *additional* transactions per microframe on a
     * high-bandwidth high-speed endpoint (USB 2.0 9.6.6, table 9-13). Full-speed endpoints always
     * have those bits clear, so this is the plain value for them.
     */
    fun effectiveMaxPacketBytes(wMaxPacketSize: Int): Int =
        (wMaxPacketSize and PAYLOAD_MASK) * (1 + ((wMaxPacketSize shr MULT_SHIFT) and MULT_MASK))

    /**
     * The largest packet, in bytes, a stream at [sampleRateHz] sends [packetsPerSecond] times a
     * second: the nominal frames per packet rounded up, plus one frame of headroom on an
     * Asynchronous endpoint, whose feedback can ask for slightly more than nominal.
     */
    fun largestPacketBytes(sampleRateHz: Int, packetsPerSecond: Int, frameBytes: Int, syncType: SyncType): Int {
        val nominalFrames = (sampleRateHz + packetsPerSecond - 1) / packetsPerSecond
        val headroomFrames = if (syncType == SyncType.ASYNC) 1 else 0
        return (nominalFrames + headroomFrames) * frameBytes
    }

    /**
     * Null if [format] can carry [sampleRateHz] with [channels] channels at [packetsPerSecond];
     * otherwise the reason it can't, for logging.
     */
    fun rejectionReason(format: UacStreamFormat, sampleRateHz: Int, channels: Int, packetsPerSecond: Int): String? {
        val needed = largestPacketBytes(sampleRateHz, packetsPerSecond, channels * format.subslotSize, format.syncType)
        val available = effectiveMaxPacketBytes(format.maxPacketSize)
        return if (needed <= available) {
            null
        } else {
            "alt=${format.alternateSetting} ${format.bitResolution}-bit/${channels}ch/${sampleRateHz}Hz needs " +
                "$needed-byte packets at $packetsPerSecond packets/sec, but wMaxPacketSize allows $available"
        }
    }
}
