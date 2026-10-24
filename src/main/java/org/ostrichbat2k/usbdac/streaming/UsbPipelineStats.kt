/*
 * Copyright (c) 2026 libusbdac Project
 * UsbPipelineStats.kt is part of libusbdac.
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

import org.ostrichbat2k.usbdac.UsbAudioManager
/**
 * A live snapshot of the active [UsbAudioOutput]'s ring-buffer/USB-transfer state, reported
 * periodically (not on every single URB -- see where it's sent from) while a stream is genuinely
 * running, and as `null` the moment it isn't -- see [UsbAudioManager.pipelineStats].
 *
 * Deliberately carries raw cumulative counters ([urbsSubmitted]) plus [atMs] rather than an
 * already-computed rate: a consumer wanting "URBs/sec" derives it from the delta between two
 * consecutive emissions, which only needs this class to report its own counters honestly, not to
 * duplicate rate-computation logic that every consumer would otherwise have to agree with.
 */
data class UsbPipelineStats(
    val atMs: Long,
    val urbsSubmitted: Long,
    val reapErrors: Int,
    val underrunCount: Int,
    val bufferedBytes: Int,
    val bufferCapacityBytes: Int,
)
