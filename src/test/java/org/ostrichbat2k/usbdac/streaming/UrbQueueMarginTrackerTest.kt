/*
 * Copyright (c) 2026 libusbdac Project
 * UrbQueueMarginTrackerTest.kt is part of libusbdac.
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

/** #123: the queue-margin metric and the queue-shape override's limits. */
class UrbQueueMarginTrackerTest {
    // 4 URBs of 8 ms: 3 x 8 ms = 24 ms still queued when one is reaped.
    private val tracker = UrbQueueMarginTracker(inFlightNs = 24_000_000L)

    @Test
    fun `nothing is reported before the first URB`() {
        assertNull(tracker.minMarginNs)
        assertEquals(0, tracker.ranDryCount)
        assertEquals(0L, tracker.maxPacingWaitNs)
    }

    @Test
    fun `the margin is what's left of the queued time, and the smallest one is kept`() {
        tracker.record(reapToResubmitNs = 8_000_000L, pacingWaitNs = 7_000_000L)
        tracker.record(reapToResubmitNs = 20_000_000L, pacingWaitNs = 1_000_000L)
        tracker.record(reapToResubmitNs = 2_000_000L, pacingWaitNs = 0L)

        assertEquals(4_000_000L, tracker.minMarginNs)
        assertEquals(0, tracker.ranDryCount)
        assertEquals(7_000_000L, tracker.maxPacingWaitNs)
    }

    @Test
    fun `a resubmit later than the queued time counts as the queue running dry`() {
        tracker.record(reapToResubmitNs = 30_000_000L, pacingWaitNs = 29_000_000L)
        tracker.record(reapToResubmitNs = 24_000_000L, pacingWaitNs = 0L)
        tracker.record(reapToResubmitNs = 25_000_000L, pacingWaitNs = 0L)

        assertEquals(-6_000_000L, tracker.minMarginNs)
        // Exactly the queued time is cutting it fine, not dry.
        assertEquals(2, tracker.ranDryCount)
    }

    @Test
    fun `a queue shape is kept within what the native layer accepts`() {
        assertEquals(UrbQueueShape(2, 1), UrbQueueShape(0, 0).coerced())
        assertEquals(UrbQueueShape(64, 64), UrbQueueShape(500, 500).coerced())
        assertEquals(UrbQueueShape(16, 2), UrbQueueShape(16, 2).coerced())
    }

    @Test
    fun `the default shape is the tuned 4 x 8 ms`() {
        assertEquals(UrbQueueShape(4, 8), UrbQueueShape.DEFAULT)
    }
}
