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

import org.junit.Assert.assertEquals
import org.junit.Test

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
        // base = 44, remainder = 100 -- the accumulator crosses 1000 (the +1 packet) on
        // exactly every 10th call, never early or late.
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
}

class IsoPacingTest {
    @Test
    fun `high speed (3) paces at 8000 packets per second, 125us each, and scales packetsPerUrb`() {
        val pacing = IsoPacing.forSpeed(usbSpeed = 3, basePacketsPerUrb = 8)
        assertEquals(8000, pacing.packetsPerSecond)
        assertEquals(125_000L, pacing.packetDurationNs) // 125us
        assertEquals(64, pacing.packetsPerUrb) // 8 * (8000/1000)
    }

    @Test
    fun `full speed (2) paces at 1000 packets per second, 1ms each, with packetsPerUrb unscaled`() {
        val pacing = IsoPacing.forSpeed(usbSpeed = 2, basePacketsPerUrb = 8)
        assertEquals(1000, pacing.packetsPerSecond)
        assertEquals(1_000_000L, pacing.packetDurationNs) // 1ms
        assertEquals(8, pacing.packetsPerUrb)
    }

    @Test
    fun `every non-high-speed value currently falls back to full-speed pacing`() {
        // Documents current behavior, not necessarily a deliberate design choice for every one
        // of these: low (1), wireless (4), super (5) and super+ (6) all collapse to the same
        // full-speed numbers as an unrecognized value would. A real super-speed UAC DAC (if one
        // ever shows up) would silently get full-speed pacing here, not its own real timing.
        for (usbSpeed in listOf(1, 2, 4, 5, 6, 0, -1, 99)) {
            val pacing = IsoPacing.forSpeed(usbSpeed, basePacketsPerUrb = 8)
            assertEquals("usbSpeed=$usbSpeed", 1000, pacing.packetsPerSecond)
            assertEquals("usbSpeed=$usbSpeed", 1_000_000L, pacing.packetDurationNs)
            assertEquals("usbSpeed=$usbSpeed", 8, pacing.packetsPerUrb)
        }
    }
}
