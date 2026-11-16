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
 * Decides how many samples each isochronous packet carries, for rates that are not a whole number
 * of samples per packet -- 44100 Hz is 44.1 samples per 1 ms packet. A running phase accumulator
 * (the same idea as Linux's `synced_next_packet_size`, sound/usb/endpoint.c): every packet adds
 * the current rate to the phase, sends the whole samples that have accumulated, and carries the
 * fraction over. Exact over any window for the nominal rate, and a fixed count per packet when the
 * rate divides evenly (48000 Hz at 1 packet/ms is always 48).
 *
 * [packetsPerSecond] is how many isochronous packets make up one second of bus time -- 1000 for a
 * full-speed device (one packet per 1 ms frame), 8000 for high speed with bInterval 1 (one per
 * 125 us microframe). See [IsoPacing.forSpeed].
 *
 * An Asynchronous device's feedback thread changes the rate while the streaming thread is asking
 * for packet sizes. A rate change replaces the step only. The phase is deliberately kept: feedback
 * arrives about once per millisecond, and clearing the phase that often throws away the fraction
 * every time, which pins the delivered rate to a multiple of the update frequency no matter what
 * the device asked for (#120).
 *
 * [maxSamplesPerPacket] is a hard ceiling on a single packet (what fits the endpoint's
 * wMaxPacketSize and the caller's buffer). Samples that would exceed it are dropped from the
 * phase, not deferred, so a nonsensical rate cannot build up a backlog.
 */
class IsoPacketScheduler(
    sampleRateHz: Int,
    private val packetsPerSecond: Int = 1000,
    private val maxSamplesPerPacket: Int = Int.MAX_VALUE,
) {
    private companion object {
        const val Q16_ONE = 1L shl 16
    }

    private val lock = Any()

    // Everything is kept in units of 1 / (packetsPerSecond * 65536) of a sample. In that unit a
    // rate in Hz is `hz * 65536` per packet and a feedback value (Q16.16 samples per packet) is
    // `value * packetsPerSecond` per packet -- both exact integers, so neither the nominal rate
    // nor a device-reported one picks up a rounding error.
    private val oneSample = packetsPerSecond * Q16_ONE
    private var stepPerPacket = sampleRateHz * Q16_ONE
    private var phase = 0L

    /** Returns the number of samples the next packet should carry. */
    fun nextPacketSampleCount(): Int =
        synchronized(lock) {
            phase += stepPerPacket
            val whole = phase / oneSample
            phase -= whole * oneSample
            if (whole > maxSamplesPerPacket) maxSamplesPerPacket else whole.toInt()
        }

    fun reset() {
        synchronized(lock) { phase = 0L }
    }

    /** Sets the rate in Hz. Keeps the phase -- see this class's own doc comment. */
    fun updateRate(sampleRateHz: Int) {
        synchronized(lock) { stepPerPacket = sampleRateHz * Q16_ONE }
    }

    /**
     * Sets the rate from an Asynchronous device's feedback, as Q16.16 samples per packet -- the
     * unit [FeedbackDecoder] produces. Called from the feedback thread. Keeps the phase.
     */
    fun updateSamplesPerPacketQ16(samplesPerPacketQ16: Long) {
        synchronized(lock) { stepPerPacket = samplesPerPacketQ16 * packetsPerSecond }
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
