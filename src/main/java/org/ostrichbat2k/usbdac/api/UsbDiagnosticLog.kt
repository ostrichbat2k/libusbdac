/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDiagnosticLog.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.api

import kotlinx.coroutines.flow.StateFlow

/**
 * The timestamped, most-recent-first activation event log backing [UsbAudioManager.diagnosticLog]
 * -- pulled out on its own since nearly every other component ([UsbDacActivator],
 * [UsbControlWriter], and [UsbAudioManager] itself) needs to append to it, but none of them need
 * to own it.
 */
internal interface UsbDiagnosticLog {
    /** Newest first, capped at a fixed capacity -- see the implementation's own doc comment. */
    val entries: StateFlow<List<String>>

    /** Appends a timestamped line, evicting the oldest once past capacity. */
    fun log(message: String)
}
