/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDiagnosticLogImpl.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ostrichbat2k.usbdac.api.UsbDiagnosticLog
import timber.log.Timber as L

internal class UsbDiagnosticLogImpl : UsbDiagnosticLog {
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    override val entries: StateFlow<List<String>> = _entries.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun log(message: String) {
        val stamp = timeFormatter.format(Date())
        L.i(message)
        _entries.value = (listOf("$stamp  $message") + _entries.value).take(LOG_CAPACITY)
    }

    private companion object {
        const val LOG_CAPACITY = 50
    }
}
