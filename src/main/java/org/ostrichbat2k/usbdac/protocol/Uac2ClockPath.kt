/*
 * Copyright (c) 2026 libusbdac Project
 * Uac2ClockPath.kt is part of libusbdac.
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

import timber.log.Timber as L

/**
 * Finds the Clock Source that actually drives the playback path of a UAC 2.0 device (#129), the
 * way Linux's `__uac_clock_find_source` (sound/usb/clock.c) does: start from the USB streaming
 * Input Terminal's `bCSourceID` and follow it through Clock Selectors and Clock Multipliers to a
 * Clock Source. Taking the first Clock Source declared, as before, is right for a single-clock
 * DAC but can pick the wrong one on a device with several (internal plus S/PDIF or word clock),
 * where a rate write to it succeeds while the playback path stays at its old rate.
 *
 * A Selector on the path is resolved through its first input that leads to a Source, and that
 * Selector and pin are recorded so activation can select it. Only the Selector nearest the
 * terminal is recorded; one further along stays on whatever input it is set to.
 */
internal object Uac2ClockPath {
    data class Resolved(val sourceId: Int, val selectorId: Int = -1, val selectorPin: Int = 0)

    // A real path is two or three entities long; this only stops a malformed, cyclic one.
    private const val MAX_DEPTH = 8

    /**
     * [selectors] maps a Clock Selector id to its input ids, in pin order (pin 1 first);
     * [multipliers] a Clock Multiplier id to its source id. Null if [startId] does not lead to
     * any of [sources].
     */
    fun resolve(
        startId: Int,
        sources: Set<Int>,
        selectors: Map<Int, List<Int>>,
        multipliers: Map<Int, Int>,
    ): Resolved? = resolve(startId, sources, selectors, multipliers, depth = 0)

    private fun resolve(
        id: Int,
        sources: Set<Int>,
        selectors: Map<Int, List<Int>>,
        multipliers: Map<Int, Int>,
        depth: Int,
    ): Resolved? {
        if (depth > MAX_DEPTH) {
            L.w("Uac2ClockPath: clock path from entity $id is too deep or cyclic")
            return null
        }
        val selectorInputs = selectors[id]
        val multiplierSource = multipliers[id]
        return when {
            id in sources -> Resolved(id)
            selectorInputs != null ->
                selectorInputs.withIndex().firstNotNullOfOrNull { (index, input) ->
                    resolve(input, sources, selectors, multipliers, depth + 1)
                        ?.copy(selectorId = id, selectorPin = index + 1)
                }
            multiplierSource != null -> resolve(multiplierSource, sources, selectors, multipliers, depth + 1)
            else -> null
        }
    }
}
