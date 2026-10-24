/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDiagnosticLogImplTest.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ostrichbat2k.usbdac.UsbAudioTuning

class UsbDiagnosticLogImplTest {

    @Test
    fun `log prepends the newest entry first, with a timestamp prefix`() {
        val log = UsbDiagnosticLogImpl()
        log.log("first")
        log.log("second")
        val entries = log.entries.value
        assertEquals(2, entries.size)
        assertTrue(entries[0].endsWith("  second"))
        assertTrue(entries[1].endsWith("  first"))
        // "HH:mm:ss  " prefix before the message.
        assertTrue(entries[0].matches(Regex("""\d{2}:\d{2}:\d{2} {2}second""")))
    }

    @Test
    fun `entries beyond LOG_CAPACITY are dropped, oldest first`() {
        val log = UsbDiagnosticLogImpl()
        val total = UsbAudioTuning.LOG_CAPACITY + 10
        for (i in 0 until total) {
            log.log("msg$i")
        }
        val entries = log.entries.value
        assertEquals(UsbAudioTuning.LOG_CAPACITY, entries.size)
        // Most recent message is first; the oldest 10 ("msg0".."msg9") must have been evicted.
        assertTrue(entries.first().endsWith("msg${total - 1}"))
        assertTrue(entries.last().endsWith("msg${total - UsbAudioTuning.LOG_CAPACITY}"))
        assertTrue(entries.none { it.endsWith("msg0") })
    }

    @Test
    fun `entries starts empty`() {
        assertEquals(emptyList<String>(), UsbDiagnosticLogImpl().entries.value)
    }
}
