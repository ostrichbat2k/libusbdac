/*
 * Copyright (c) 2026 libusbdac Project
 * UsbVolumeController.kt is part of libusbdac.
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


import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.claim.UsbClaimController
import org.ostrichbat2k.usbdac.protocol.UsbAudioController
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession
/**
 * Everything the rest of the library decides about USB DAC hardware volume/mute -- as opposed to
 * `UsbAudioController`'s raw `SET_CUR`/`GET_CUR`/`GET_RANGE` wire primitives, which an
 * implementation calls into but never replaces. See the implementation's own doc comment for the
 * unity-gain-during-fallback invariant every method here depends on.
 *
 * Internal rather than part of the library's public (host-app-facing) API: no host app needs to
 * depend on this directly, only [UsbAudioManager] and [UsbClaimController] do.
 */
internal interface UsbVolumeController {
    /**
     * Forces every channel to unity gain and brackets the jump with a mute/unmute, right before
     * handing the raw interface back to Android's own kernel-driver-backed USB-audio HAL -- so a
     * HAL-routed (fallback) track is never audibly quieter than it should be just because
     * bit-perfect mode's own volume control left the hardware gain attenuated.
     */
    fun prepareForHalHandoff(session: UsbAudioSession, channelCount: Int)

    /**
     * Re-mutes a session that was just reclaimed back from the kernel after HAL failed to confirm
     * it took the device over in time, for a release that didn't actually need HAL to succeed.
     */
    fun remuteForUnconfirmedHandoff(session: UsbAudioSession)

    /**
     * Keeps Android's own `STREAM_MUSIC` index matched to the DAC's hardware dB level, so
     * switching between a bit-perfect track (loudness controlled by the Feature Unit) and a
     * fallback track (loudness controlled by `STREAM_MUSIC`) doesn't sound like a jump in level.
     */
    fun mirrorDbToStreamMusic(valueDb: Int)

    /**
     * The inverse of [mirrorDbToStreamMusic]: called when a real (not self-caused) `STREAM_MUSIC`
     * change is observed while a fallback track is the current route.
     */
    fun onStreamMusicChanged(newIndex: Int)

    /**
     * The dB value to write to push the DAC's live hardware volume to whatever matches
     * `STREAM_MUSIC`'s *current* index, right before an ABX trial that's about to go bit-perfect.
     * Null if there's nothing to sync.
     */
    fun abxSyncTargetDb(): Int?
}
