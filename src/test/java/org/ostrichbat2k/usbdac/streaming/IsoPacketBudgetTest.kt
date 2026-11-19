/*
 * Copyright (c) 2026 libusbdac Project
 * IsoPacketBudgetTest.kt is part of libusbdac.
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ostrichbat2k.usbdac.protocol.SyncType
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat

/** #130: a format whose packets would overflow `wMaxPacketSize` is rejected up front. */
class IsoPacketBudgetTest {

    private fun format(maxPacketSize: Int, subslotSize: Int = 2, syncType: SyncType = SyncType.SYNCHRONOUS) =
        UacStreamFormat(
            alternateSetting = 1,
            bitResolution = subslotSize * 8,
            subslotSize = subslotSize,
            clusterDescriptorId = -1,
            endpointAddress = 0x01,
            maxPacketSize = maxPacketSize,
            syncType = syncType,
        )

    @Test
    fun `effective size is the plain value when no high-bandwidth bits are set`() {
        assertEquals(192, IsoPacketBudget.effectiveMaxPacketBytes(192))
        assertEquals(1024, IsoPacketBudget.effectiveMaxPacketBytes(1024))
    }

    @Test
    fun `high-bandwidth bits multiply the payload size instead of being read as part of it`() {
        // 0x0A00: two transactions of 512 bytes, not a 2560-byte packet.
        assertEquals(1024, IsoPacketBudget.effectiveMaxPacketBytes(0x0A00))
        // 0x1400: three transactions of 1024 bytes.
        assertEquals(3072, IsoPacketBudget.effectiveMaxPacketBytes(0x1400))
    }

    @Test
    fun `largest packet rounds the nominal frame count up`() {
        // 44.1 frames per 1 ms packet -> 45 frames of 4 bytes.
        assertEquals(180, IsoPacketBudget.largestPacketBytes(44100, 1000, 4, SyncType.SYNCHRONOUS))
        // 48 frames exactly -> no rounding.
        assertEquals(192, IsoPacketBudget.largestPacketBytes(48000, 1000, 4, SyncType.SYNCHRONOUS))
    }

    @Test
    fun `an asynchronous endpoint gets one frame of headroom for feedback`() {
        assertEquals(196, IsoPacketBudget.largestPacketBytes(48000, 1000, 4, SyncType.ASYNC))
    }

    @Test
    fun `a rate that fits the endpoint is accepted`() {
        assertNull(IsoPacketBudget.rejectionReason(format(192), 48000, 2, 1000))
    }

    @Test
    fun `a rate too large for a full-speed packet is rejected with the sizes in the reason`() {
        val reason = IsoPacketBudget.rejectionReason(format(192), 96000, 2, 1000)
        assertNotNull(reason)
        assertEquals(true, reason!!.contains("384-byte") && reason.contains("allows 192"))
    }

    @Test
    fun `the same rate fits once packets go out every 125 us microframe`() {
        assertNull(IsoPacketBudget.rejectionReason(format(192), 96000, 2, 8000))
    }

    @Test
    fun `asynchronous headroom can push an exactly-full packet over the limit`() {
        assertNull(IsoPacketBudget.rejectionReason(format(192), 48000, 2, 1000))
        assertNotNull(IsoPacketBudget.rejectionReason(format(192, syncType = SyncType.ASYNC), 48000, 2, 1000))
    }
}
