/*
 * Copyright (c) 2026 libusbdac Project
 * UsbControlWriter.kt is part of libusbdac.
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

import org.ostrichbat2k.usbdac.UacStreamFormat
import org.ostrichbat2k.usbdac.UsbAudioSession

/**
 * Every live, user-facing read/write against the active session's adjustable hardware controls
 * (mute, volume, per-channel mixer gain) and republishing [UsbAudioManager.controlState] from a
 * fresh device read-back -- pulled out of [UsbAudioManager] on its own since this is the one
 * cohesive "Settings-screen control" concern, distinct from device discovery/activation and from
 * [UsbVolumeController]'s own lower-level hardware-vs-STREAM_MUSIC bookkeeping (which this class
 * calls into, not duplicates).
 */
internal interface UsbControlWriter {
    /** Re-reads every adjustable control from the device and republishes [UsbAudioManager.controlState]. */
    fun refreshControlState(session: UsbAudioSession, format: UacStreamFormat, deviceName: String)

    /** See [UsbAudioManager.setMuted]. */
    fun setMuted(muted: Boolean)

    /** See [UsbAudioManager.setVolume]. */
    fun setVolume(valueDb: Int)

    /** See [UsbAudioManager.setMixerGain]. */
    fun setMixerGain(inputChannel: Int, outputChannel: Int, valueDb: Int)
}
