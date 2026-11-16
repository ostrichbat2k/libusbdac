/*
 * Copyright (c) 2026 libusbdac Project
 * IsoPacketSchedulerTest.kt is part of libusbdac.
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
import org.junit.jupiter.api.Test

class IsoPacketSchedulerTest {
    /** Sum of one full period's worth of packets must exactly equal the sample rate -- the
     * whole point of the running-remainder distribution (see the class's own doc comment) is
     * that there's no drift, only ever a +0/+1 jitter per packet. */
    private fun assertExactOverOnePeriod(sampleRateHz: Int, packetsPerSecond: Int) {
        val scheduler = IsoPacketScheduler(sampleRateHz, packetsPerSecond)
        var total = 0
        repeat(packetsPerSecond) { total += scheduler.nextPacketSampleCount() }
        assertEquals(sampleRateHz, total)
    }

    @Test
    fun `one second's worth of packets sums exactly to the sample rate, full-speed`() {
        assertExactOverOnePeriod(sampleRateHz = 44100, packetsPerSecond = 1000)
        assertExactOverOnePeriod(sampleRateHz = 48000, packetsPerSecond = 1000)
    }

    @Test
    fun `one second's worth of packets sums exactly to the sample rate, high-speed`() {
        assertExactOverOnePeriod(sampleRateHz = 44100, packetsPerSecond = 8000)
        assertExactOverOnePeriod(sampleRateHz = 48000, packetsPerSecond = 8000)
        assertExactOverOnePeriod(sampleRateHz = 96000, packetsPerSecond = 8000)
        assertExactOverOnePeriod(sampleRateHz = 192000, packetsPerSecond = 8000)
        assertExactOverOnePeriod(sampleRateHz = 384000, packetsPerSecond = 8000) // FiiO's declared max
    }

    @Test
    fun `an evenly-dividing rate degenerates to a fixed count every call, never +1`() {
        val scheduler = IsoPacketScheduler(sampleRateHz = 48000, packetsPerSecond = 1000)
        repeat(50) { assertEquals(48, scheduler.nextPacketSampleCount()) }
    }

    @Test
    fun `44100Hz at 1000 packets per second follows the exact known Bresenham pattern`() {
        // 44.1 per packet -- the phase crosses a whole sample (the +1 packet) on exactly every
        // 10th call, never early or late.
        val scheduler = IsoPacketScheduler(sampleRateHz = 44100, packetsPerSecond = 1000)
        val expected = List(20) { i -> if ((i + 1) % 10 == 0) 45 else 44 }
        val actual = List(20) { scheduler.nextPacketSampleCount() }
        assertEquals(expected, actual)
    }

    @Test
    fun `reset restarts the exact same sequence from the beginning`() {
        val scheduler = IsoPacketScheduler(sampleRateHz = 44100, packetsPerSecond = 1000)
        repeat(5) { scheduler.nextPacketSampleCount() } // advance into the cycle, accumulator != 0
        scheduler.reset()
        val afterReset = List(15) { scheduler.nextPacketSampleCount() }

        val fresh = IsoPacketScheduler(sampleRateHz = 44100, packetsPerSecond = 1000)
        val fromScratch = List(15) { fresh.nextPacketSampleCount() }

        assertEquals(fromScratch, afterReset)
    }

    @Test
    fun `updateRate changes the step and keeps the phase`() {
        // 44100 Hz at 1000 packets/s is 44.1 per packet. Seven packets leave 0.7 in the phase;
        // switching to 44300 (44.3 per packet) then crosses 1.0 on the very next packet. A
        // scheduler that cleared the phase would need four more packets to get there.
        val scheduler = IsoPacketScheduler(sampleRateHz = 44100, packetsPerSecond = 1000)
        repeat(7) { assertEquals(44, scheduler.nextPacketSampleCount()) }
        scheduler.updateRate(44300)
        assertEquals(45, scheduler.nextPacketSampleCount())
    }

    @Test
    fun `a rate update before every packet does not change what is delivered`() {
        // #120. Feedback arrives about once per millisecond. Clearing the phase on each update
        // threw the fraction away every time: 44100 Hz came out as exactly 44000.
        for ((rateHz, pps) in listOf(44100 to 1000, 44100 to 8000, 48010 to 1000, 88200 to 8000, 176400 to 8000)) {
            val scheduler = IsoPacketScheduler(rateHz, pps)
            var total = 0
            repeat(pps) {
                scheduler.updateRate(rateHz)
                total += scheduler.nextPacketSampleCount()
            }
            assertEquals(rateHz, total, "rateHz=$rateHz pps=$pps")
        }
    }

    @Test
    fun `feedback in Q16_16 samples per packet is delivered at the rate it asks for`() {
        // A device clock 1000 ppm fast at high speed, feedback applied every 8 packets (1 ms).
        val pps = 8000
        val deviceHz = 44144.1
        val q16 = Math.round(deviceHz * 65536 / pps)
        val scheduler = IsoPacketScheduler(44100, pps)
        var total = 0L
        val seconds = 10
        repeat(pps * seconds) { i ->
            if (i % 8 == 0) scheduler.updateSamplesPerPacketQ16(q16)
            total += scheduler.nextPacketSampleCount()
        }
        // Q16.16 per packet resolves 8000/65536 = 0.12 Hz, so allow that much per second.
        assertEquals(deviceHz * seconds, total.toDouble(), 0.13 * seconds + 1)
    }

    @Test
    fun `packet sizes only ever differ by one sample while following feedback`() {
        val scheduler = IsoPacketScheduler(44100, 8000)
        scheduler.updateSamplesPerPacketQ16(Math.round(44144.1 * 65536 / 8000))
        val sizes = List(8000) { scheduler.nextPacketSampleCount() }.toSet()
        assertEquals(setOf(5, 6), sizes)
    }

    @Test
    fun `a packet never exceeds maxSamplesPerPacket and the excess is dropped, not deferred`() {
        val scheduler = IsoPacketScheduler(sampleRateHz = 48000, packetsPerSecond = 1000, maxSamplesPerPacket = 50)
        scheduler.updateRate(96000) // 96 per packet, far over the ceiling
        repeat(100) { assertEquals(50, scheduler.nextPacketSampleCount()) }
        // Back at a rate that fits, nothing was banked from the clamped packets.
        scheduler.updateRate(48000)
        repeat(100) { assertEquals(48, scheduler.nextPacketSampleCount()) }
    }

    @Test
    fun `updateRate's new distribution still sums exactly to the new rate over one period`() {
        val scheduler = IsoPacketScheduler(sampleRateHz = 44100, packetsPerSecond = 1000)
        scheduler.updateRate(96000)

        var total = 0
        repeat(1000) { total += scheduler.nextPacketSampleCount() }
        assertEquals(96000, total)
    }
}

class IsoPacingTest {
    @Test
    fun `high speed (3) with bInterval=1 (the default, every device tested so far) paces at 8000 packets per second`() {
        val pacing = IsoPacing.forSpeed(usbSpeed = 3, basePacketsPerUrb = 8, bInterval = 1)
        assertEquals(8000, pacing.packetsPerSecond)
        assertEquals(125_000L, pacing.packetDurationNs) // 125us
        assertEquals(64, pacing.packetsPerUrb) // 8 * (8000/1000)
    }

    @Test
    fun `full speed (2), bInterval=1, paces at 1000 packets per second, 1ms each, with packetsPerUrb unscaled`() {
        val pacing = IsoPacing.forSpeed(usbSpeed = 2, basePacketsPerUrb = 8, bInterval = 1)
        assertEquals(1000, pacing.packetsPerSecond)
        assertEquals(1_000_000L, pacing.packetDurationNs) // 1ms
        assertEquals(8, pacing.packetsPerUrb)
    }

    @Test
    fun `full speed ignores bInterval's exponential rule entirely -- always 1ms packets`() {
        // Full-/low-speed isochronous endpoints schedule one packet per 1ms frame regardless of
        // bInterval (USB 2.0 5.6.4) -- only high-speed and above use bInterval's 2^(n-1) rule.
        for (bInterval in listOf(1, 2, 4, 16)) {
            val pacing = IsoPacing.forSpeed(usbSpeed = 2, basePacketsPerUrb = 8, bInterval = bInterval)
            assertEquals(1000, pacing.packetsPerSecond, "bInterval=$bInterval")
            assertEquals(1_000_000L, pacing.packetDurationNs, "bInterval=$bInterval")
            assertEquals(8, pacing.packetsPerUrb, "bInterval=$bInterval")
        }
    }

    @Test
    fun `super speed (5) and super+ (6) now pace the same as high-speed, not full-speed`() {
        // #61: these used to fall into the full-speed branch and get timed as 1000 packets/sec,
        // wrong by the same 8x factor high-speed would be if mistimed -- just never caught
        // because no SuperSpeed audio device has been tested yet.
        for (usbSpeed in listOf(5, 6)) {
            val pacing = IsoPacing.forSpeed(usbSpeed, basePacketsPerUrb = 8, bInterval = 1)
            assertEquals(8000, pacing.packetsPerSecond, "usbSpeed=$usbSpeed")
            assertEquals(125_000L, pacing.packetDurationNs, "usbSpeed=$usbSpeed")
            assertEquals(64, pacing.packetsPerUrb, "usbSpeed=$usbSpeed")
        }
    }

    @Test
    fun `high speed with bInterval=4 schedules one packet every 8 microframes, not every microframe`() {
        // 2^(4-1) = 8 microframes = 1ms real interval -- same real-time packet rate as
        // full-speed, just arrived at via high-speed's microframe-based rule instead of
        // full-speed's frame-based one.
        val pacing = IsoPacing.forSpeed(usbSpeed = 3, basePacketsPerUrb = 8, bInterval = 4)
        assertEquals(1000, pacing.packetsPerSecond)
        assertEquals(1_000_000L, pacing.packetDurationNs)
        assertEquals(8, pacing.packetsPerUrb)
    }

    @Test
    fun `an out-of-spec bInterval falls back to the shortest interval instead of producing nonsense`() {
        for (bInterval in listOf(0, -1, -99)) {
            val pacing = IsoPacing.forSpeed(usbSpeed = 3, basePacketsPerUrb = 8, bInterval = bInterval)
            assertEquals(8000, pacing.packetsPerSecond, "bInterval=$bInterval")
        }
    }

    @Test
    fun `low (1), wireless (4) and unrecognized speeds still fall back to full-speed pacing`() {
        for (usbSpeed in listOf(1, 4, 0, -1, 99)) {
            val pacing = IsoPacing.forSpeed(usbSpeed, basePacketsPerUrb = 8, bInterval = 1)
            assertEquals(1000, pacing.packetsPerSecond, "usbSpeed=$usbSpeed")
            assertEquals(1_000_000L, pacing.packetDurationNs, "usbSpeed=$usbSpeed")
            assertEquals(8, pacing.packetsPerUrb, "usbSpeed=$usbSpeed")
        }
    }

    @Test
    fun `forSpeed defaults bInterval to 1 when the caller omits it`() {
        val withoutArg = IsoPacing.forSpeed(usbSpeed = 3, basePacketsPerUrb = 8)
        val withExplicitOne = IsoPacing.forSpeed(usbSpeed = 3, basePacketsPerUrb = 8, bInterval = 1)
        assertEquals(withExplicitOne, withoutArg)
    }
}
