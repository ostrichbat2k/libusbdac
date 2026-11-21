/*
 * Copyright (c) 2026 libusbdac Project
 * Uac2ClockPathTest.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** #129: following a UAC 2.0 clock path from the terminal's bCSourceID to a Clock Source. */
class Uac2ClockPathTest {
    private fun resolve(
        start: Int,
        selectors: Map<Int, List<Int>> = emptyMap(),
        multipliers: Map<Int, Int> = emptyMap(),
    ) =
        Uac2ClockPath.resolve(start, sources = setOf(10, 11), selectors = selectors, multipliers = multipliers)

    @Test
    fun `a source is its own path`() {
        assertEquals(Uac2ClockPath.Resolved(11), resolve(11))
    }

    @Test
    fun `a selector resolves through its first input that leads to a source, recording that pin`() {
        assertEquals(
            Uac2ClockPath.Resolved(10, selectorId = 20, selectorPin = 1), resolve(20, mapOf(20 to listOf(10, 11))))
        assertEquals(
            Uac2ClockPath.Resolved(11, selectorId = 20, selectorPin = 2), resolve(20, mapOf(20 to listOf(99, 11))))
    }

    @Test
    fun `a selector behind a multiplier is recorded, and the multiplier followed`() {
        assertEquals(
            Uac2ClockPath.Resolved(10, selectorId = 20, selectorPin = 1),
            resolve(30, mapOf(20 to listOf(10)), mapOf(30 to 20)))
    }

    @Test
    fun `only the selector nearest the terminal is recorded`() {
        assertEquals(
            Uac2ClockPath.Resolved(11, selectorId = 20, selectorPin = 1),
            resolve(20, mapOf(20 to listOf(21), 21 to listOf(99, 11))))
    }

    @Test
    fun `an unknown entity, or a selector with no usable input, leads nowhere`() {
        assertNull(resolve(42))
        assertNull(resolve(20, mapOf(20 to listOf(98, 99))))
    }

    @Test
    fun `a cyclic path ends instead of recursing forever`() {
        assertNull(resolve(30, multipliers = mapOf(30 to 31, 31 to 30)))
    }
}
