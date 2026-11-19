/*
 * Copyright (c) 2026 libusbdac Project
 * UrbQueueMarginTracker.kt is part of libusbdac.
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
 * How close the stream comes to running out of queued URBs (#123).
 *
 * When a URB is reaped, the others still in flight -- [inFlightNs] of audio, (numUrbs - 1) URB
 * durations -- are all the device has until that slot is resubmitted. If reap-to-resubmit takes
 * longer than that, the queue ran dry: under `USBDEVFS_URB_ISO_ASAP` the late URB is simply
 * scheduled later, leaving a gap on the bus that no error counter shows. The wall-clock pacing
 * wait is inside that window, so it is recorded on its own too: if `System.nanoTime()` ran slow
 * against the bus clock, that wait would keep growing until the queue ran dry.
 *
 * Used only from the streaming thread.
 */
internal class UrbQueueMarginTracker(private val inFlightNs: Long) {
    /**
     * Smallest margin seen ([inFlightNs] minus reap-to-resubmit time); negative means it ran dry.
     * Null before any URB.
     */
    var minMarginNs: Long? = null
        private set

    /** URBs whose reap-to-resubmit time exceeded [inFlightNs]. */
    var ranDryCount: Int = 0
        private set

    /** Longest wall-clock pacing wait seen. */
    var maxPacingWaitNs: Long = 0L
        private set

    fun record(reapToResubmitNs: Long, pacingWaitNs: Long) {
        val margin = inFlightNs - reapToResubmitNs
        minMarginNs = minMarginNs?.let { minOf(it, margin) } ?: margin
        if (margin < 0) ranDryCount++
        if (pacingWaitNs > maxPacingWaitNs) maxPacingWaitNs = pacingWaitNs
    }
}
