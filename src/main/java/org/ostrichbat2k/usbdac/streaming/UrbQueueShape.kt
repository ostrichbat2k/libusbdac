/*
 * Copyright (c) 2026 libusbdac Project
 * UrbQueueShape.kt is part of libusbdac.
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

/**
 * How many URBs the stream keeps in flight and how long each one is: [numUrbs] URBs of
 * [basePacketsPerUrb] full-speed packets (milliseconds), scaled the same way at high speed (see
 * [IsoPacing.forSpeed]). [DEFAULT] is 4 x 8 ms. Other shapes exist for the #123 experiment --
 * comparing queue depths with [UsbPipelineStats.queueRanDryCount] visible -- through
 * [org.ostrichbat2k.usbdac.UsbDacPreferences.urbQueueOverride].
 */
data class UrbQueueShape(val numUrbs: Int, val basePacketsPerUrb: Int) {
    /**
     * This shape kept within what the native layer accepts (`kMaxNumUrbs`), with at least two
     * URBs so one can be refilled while another plays.
     */
    fun coerced(): UrbQueueShape =
        UrbQueueShape(numUrbs.coerceIn(MIN_URBS, MAX_URBS), basePacketsPerUrb.coerceIn(1, MAX_BASE_PACKETS_PER_URB))

    companion object {
        val DEFAULT = UrbQueueShape(UsbAudioTuning.NUM_URBS, UsbAudioTuning.BASE_PACKETS_PER_URB)

        private const val MIN_URBS = 2

        // usb_native.cpp's kMaxNumUrbs.
        private const val MAX_URBS = 64

        // 64 ms per URB; at high speed that is 512 packets, well inside kMaxPacketsPerUrb (4096).
        private const val MAX_BASE_PACKETS_PER_URB = 64
    }
}
