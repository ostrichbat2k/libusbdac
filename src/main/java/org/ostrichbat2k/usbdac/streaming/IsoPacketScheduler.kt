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
 *
 * Single-writer-assumed until #25: only the main streaming thread ever touched this class, so
 * [nextPacketSampleCount]/[reset] were safe unsynchronized. An Asynchronous device's feedback
 * thread now calls [updateRate] concurrently with the main thread's own [nextPacketSampleCount]
 * calls, so every mutable field is guarded by [lock] -- real correctness, not a nice-to-have.
 */
class IsoPacketScheduler(sampleRateHz: Int, private val packetsPerSecond: Int = 1000) {
    private val lock = Any()
    private var baseSamplesPerPacket = sampleRateHz / packetsPerSecond
    private var remainderPerPacket = sampleRateHz % packetsPerSecond
    private var accumulator = 0

    /** Returns the number of samples the next packet should carry. */
    fun nextPacketSampleCount(): Int =
        synchronized(lock) {
            accumulator += remainderPerPacket
            if (accumulator >= packetsPerSecond) {
                accumulator -= packetsPerSecond
                baseSamplesPerPacket + 1
            } else {
                baseSamplesPerPacket
            }
        }

    fun reset() {
        synchronized(lock) { accumulator = 0 }
    }

    /**
     * Re-derives the per-packet sample distribution for a feedback-reported real rate (#25) --
     * called from the feedback thread, never the main streaming thread. Resets [accumulator] too:
     * an old accumulator value computed against the previous rate's [remainderPerPacket] has no
     * meaning against the new one.
     */
    fun updateRate(sampleRateHz: Int) {
        synchronized(lock) {
            baseSamplesPerPacket = sampleRateHz / packetsPerSecond
            remainderPerPacket = sampleRateHz % packetsPerSecond
            accumulator = 0
        }
    }
}

/**
 * Pacing parameters derived from a device's negotiated USB speed and its streaming endpoint's own
 * declared `bInterval` -- used by [UsbAudioOutput]'s real playback path so this distinction can't
 * drift out of sync between callers that need it. See [UsbNative.getUsbSpeed]'s doc comment for
 * why 125us microframes vs. 1ms frames matters here, not just cosmetically.
 */
data class IsoPacing(val packetsPerSecond: Int, val packetDurationNs: Long, val packetsPerUrb: Int) {
    companion object {
        // USB_SPEED_* values (linux/usb/ch9.h) that schedule isochronous packets per 125us
        // microframe, same unit full-/low-speed don't use: high-speed and both SuperSpeed
        // variants. SuperSpeed/SuperSpeed+ (5/6) used to fall into the full-speed branch below
        // and get timed as 1ms packets -- wrong by the same 8x factor high-speed would be if
        // mistimed, just never caught because no SuperSpeed audio device has been tested yet.
        private val MICROFRAME_SPEEDS = setOf(3, 5, 6)

        /**
         * [basePacketsPerUrb] is the caller's full-speed (1 packet == 1ms) target; scaled up here.
         * [bInterval] is the streaming endpoint's own declared service interval (USB Standard
         * Endpoint Descriptor offset 6, [UacStreamFormat.bInterval]) -- every device actually
         * tested so far declares the shortest legal interval (1), which this degenerates to
         * exactly the previous hardcoded 1000/8000 packets/sec behavior for, so this generalizes
         * the previously speed-only computation without changing anything on tested hardware.
         *
         * Per the USB 2.0 spec (5.6.4): full-/low-speed isochronous endpoints schedule one packet
         * per 1ms frame regardless of `bInterval`'s value (its exponential rule only applies to
         * high-speed and above); high-speed/SuperSpeed/SuperSpeed+ schedule one packet every
         * `2^(bInterval-1)` 125us microframes. An out-of-spec `bInterval` (<= 0) falls back to the
         * shortest interval (1) rather than producing a nonsensical (or divide-by-zero-adjacent)
         * pacing.
         */
        fun forSpeed(usbSpeed: Int, basePacketsPerUrb: Int, bInterval: Int = 1): IsoPacing {
            val safeBInterval = bInterval.takeIf { it > 0 } ?: 1
            val packetsPerSecond =
                if (usbSpeed in MICROFRAME_SPEEDS) {
                    8000 / (1 shl (safeBInterval - 1))
                } else {
                    1000
                }
            return IsoPacing(
                packetsPerSecond = packetsPerSecond,
                packetDurationNs = 1_000_000_000L / packetsPerSecond,
                packetsPerUrb = basePacketsPerUrb * (packetsPerSecond / 1000).coerceAtLeast(1))
        }
    }
}
