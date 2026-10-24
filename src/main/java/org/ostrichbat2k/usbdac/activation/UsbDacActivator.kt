/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDacActivator.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.activation

import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession
import android.hardware.usb.UsbDevice

/**
 * Walks [device]'s raw USB descriptors, resolves a UAC1/2/3 playback topology (from cache when
 * possible), and fully configures a [UsbAudioSession] -- clock, power domain, mute/volume/mixer
 * defaults, alt-setting -- ready for playback with the interface still claimed. Pulled out of
 * [UsbAudioManager] on its own since this is far and away its single largest, most
 * hardware-specific piece, and is otherwise self-contained: it doesn't touch
 * [UsbAudioManager]'s own session-state machine at all, just hands back a ready session (or
 * null) for the caller to adopt.
 */
internal interface UsbDacActivator {
    /** A fully configured, ready-to-adopt session, plus the format/rate it was configured for. */
    data class Result(val session: UsbAudioSession, val format: UacStreamFormat, val rateHz: Int)

    /** Returns null on any failure along the way -- see the implementation's own doc comment. */
    fun activate(device: UsbDevice): Result?
}
