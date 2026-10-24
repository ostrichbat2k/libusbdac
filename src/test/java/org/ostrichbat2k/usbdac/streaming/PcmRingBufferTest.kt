/*
 * Copyright (c) 2026 libusbdac Project
 * PcmRingBufferTest.kt is part of libusbdac.
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

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmRingBufferTest {

    private fun bytes(vararg values: Int) = ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() })

    @Test
    fun `write makes bytes available to read and updates availableToWrite`() {
        val buf = PcmRingBuffer(capacityBytes = 10)
        val written = buf.write(bytes(1, 2, 3))
        assertEquals(3, written)
        assertEquals(3, buf.availableToRead())
        assertEquals(7, buf.availableToWrite())
    }

    @Test
    fun `write beyond capacity is truncated to available room`() {
        val buf = PcmRingBuffer(capacityBytes = 4)
        val written = buf.write(bytes(1, 2, 3, 4, 5, 6))
        assertEquals(4, written)
        assertEquals(4, buf.availableToRead())
        assertEquals(0, buf.availableToWrite())
    }

    @Test
    fun `readOrZeroPad returns exactly what was written, in order, with no padding`() {
        val buf = PcmRingBuffer(capacityBytes = 10)
        buf.write(bytes(10, 20, 30))
        val dst = ByteArray(3)
        val read = buf.readOrZeroPad(dst)
        assertEquals(3, read)
        assertArrayEquals(byteArrayOf(10, 20, 30), dst)
        assertEquals(0, buf.availableToRead())
    }

    @Test
    fun `readOrZeroPad zero-pads the tail on underrun and reports the real read count`() {
        val buf = PcmRingBuffer(capacityBytes = 10)
        buf.write(bytes(10, 20))
        val dst = ByteArray(5) { 99 } // pre-filled so untouched bytes would be obviously wrong
        val read = buf.readOrZeroPad(dst)
        assertEquals(2, read)
        assertArrayEquals(byteArrayOf(10, 20, 0, 0, 0), dst)
    }

    @Test
    fun `readOrZeroPad on a fully empty buffer returns zero and fills dst with silence`() {
        val buf = PcmRingBuffer(capacityBytes = 10)
        val dst = ByteArray(4) { 99 }
        val read = buf.readOrZeroPad(dst)
        assertEquals(0, read)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), dst)
    }

    @Test
    fun `explicit length reads only that many bytes and leaves the rest of dst untouched`() {
        val buf = PcmRingBuffer(capacityBytes = 10)
        buf.write(bytes(1, 2))
        val dst = ByteArray(6) { 99 }
        val read = buf.readOrZeroPad(dst, length = 4)
        assertEquals(2, read)
        // first 4 bytes are this call's responsibility (2 real + 2 zero-padded); the rest is
        // untouched, as the doc comment promises for a caller reusing a larger scratch buffer.
        assertArrayEquals(byteArrayOf(1, 2, 0, 0, 99, 99), dst)
    }

    @Test
    fun `write and read wrap around the backing array correctly`() {
        val buf = PcmRingBuffer(capacityBytes = 4)
        buf.write(bytes(1, 2, 3)) // writePos now at 3
        buf.readOrZeroPad(ByteArray(2)) // drains 1,2 -- readPos now at 2, size=1 (just "3" left)
        // This write's 3 bytes must wrap: one lands at offset 3, the other two wrap to 0,1.
        buf.write(bytes(4, 5, 6))
        val dst = ByteArray(4)
        val read = buf.readOrZeroPad(dst)
        assertEquals(4, read)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), dst)
    }

    @Test
    fun `clear resets the buffer to empty regardless of prior position`() {
        val buf = PcmRingBuffer(capacityBytes = 4)
        buf.write(bytes(1, 2, 3))
        buf.readOrZeroPad(ByteArray(1))
        buf.clear()
        assertEquals(0, buf.availableToRead())
        assertEquals(4, buf.availableToWrite())
        // and the buffer behaves like fresh afterward, not still offset internally.
        buf.write(bytes(9, 9, 9, 9))
        val dst = ByteArray(4)
        assertEquals(4, buf.readOrZeroPad(dst))
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), dst)
    }

    @Test
    fun `write consumes only from the buffer's remaining bytes, not its full capacity`() {
        val full = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        full.position(2) // remaining() == 3: bytes {3, 4, 5}
        val buf = PcmRingBuffer(capacityBytes = 10)
        val written = buf.write(full)
        assertEquals(3, written)
        val dst = ByteArray(3)
        buf.readOrZeroPad(dst)
        assertArrayEquals(byteArrayOf(3, 4, 5), dst)
    }
}
