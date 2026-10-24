/*
 * Copyright (c) 2026 libusbdac Project
 * IsoPacketScheduler.kt is part of libusbdac.
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

/**
 * Distributes whole-number sample counts across isochronous packets for sample rates that don't
 * divide evenly into one packet's worth of time -- 44100 Hz is 44.1 samples per 1ms packet, not a
 * whole number. Uses a running-remainder (Bresenham-style) distribution: exact over any window,
 * and degenerates to a fixed sample count per packet when the rate does divide evenly (e.g.
 * 48000 Hz at 1 packet/ms -> always exactly 48).
 *
 * [packetsPerSecond] is how many isochronous packets make up one second of real bus time -- 1000
 * for a full-speed device (one packet per 1ms frame), 8000 for high-speed (one packet per 125us
 * *microframe* instead -- see [UsbNative.getUsbSpeed]'s doc comment for why this distinction is
 * essential, not cosmetic: assuming 1000 unconditionally on a high-speed device packs 8x too many
 * samples into each packet slot relative to how much real bus time it actually occupies).
 */
class IsoPacketScheduler(sampleRateHz: Int, private val packetsPerSecond: Int = 1000) {
    private val baseSamplesPerPacket = sampleRateHz / packetsPerSecond
    private val remainderPerPacket = sampleRateHz % packetsPerSecond
    private var accumulator = 0

    /** Returns the number of samples the next packet should carry. */
    fun nextPacketSampleCount(): Int {
        accumulator += remainderPerPacket
        return if (accumulator >= packetsPerSecond) {
            accumulator -= packetsPerSecond
            baseSamplesPerPacket + 1
        } else {
            baseSamplesPerPacket
        }
    }

    fun reset() {
        accumulator = 0
    }
}

/**
 * Pacing parameters derived from a device's negotiated USB speed -- used by [UsbAudioOutput]'s
 * real playback path so the high-speed-vs-full-speed distinction can't drift out of sync between
 * callers that need it. See [UsbNative.getUsbSpeed]'s doc comment for why 125us microframes vs.
 * 1ms frames matters here, not just cosmetically.
 */
data class IsoPacing(val packetsPerSecond: Int, val packetDurationNs: Long, val packetsPerUrb: Int) {
    companion object {
        /** [basePacketsPerUrb] is the caller's full-speed (1 packet == 1ms) target; scaled up here. */
        fun forSpeed(usbSpeed: Int, basePacketsPerUrb: Int): IsoPacing {
            val packetsPerSecond = if (usbSpeed == 3) 8000 else 1000
            return IsoPacing(
                packetsPerSecond = packetsPerSecond,
                packetDurationNs = 1_000_000_000L / packetsPerSecond,
                packetsPerUrb = basePacketsPerUrb * (packetsPerSecond / 1000))
        }
    }
}
