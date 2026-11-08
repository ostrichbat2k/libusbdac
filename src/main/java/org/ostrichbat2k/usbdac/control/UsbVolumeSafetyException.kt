/*
 * Copyright (c) 2026 libusbdac Project
 * UsbVolumeSafetyException.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.control

/**
 * Thrown by [org.ostrichbat2k.usbdac.streaming.UsbAudioOutput]'s own last-instant safety
 * read-back, immediately before it would otherwise unmute and start streaming, if the Feature
 * Unit's actual live volume is somehow still above
 * [org.ostrichbat2k.usbdac.UsbDacPreferences.maxVolumePercent]'s cap despite every write path
 * already clamping to it. This is defense-in-depth, not the primary enforcement (see
 * [org.ostrichbat2k.usbdac.protocol.UsbAudioController.capToMaxPercent] for that) -- it exists to
 * catch a clamped write that silently failed on the wire, or a future write path that forgets to
 * clamp, rather than ever let audio reach the device above the configured safety ceiling.
 */
internal class UsbVolumeSafetyException(message: String) : Exception(message)
