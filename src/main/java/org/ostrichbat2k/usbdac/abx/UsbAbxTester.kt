/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAbxTester.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.abx

import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.UsbDacPreferences
import kotlinx.coroutines.flow.StateFlow

/**
 * The blind ABX self-test: secretly routes a track bit-perfect or HAL-fallback, then asks the
 * host app to guess which, scoring the result -- see [UsbDacPreferences.abxTestMode]. Pulled out
 * of [UsbAudioManager] on its own since it's a genuinely separate feature from everything else
 * that class does, sharing only [UsbAudioManager.isBitPerfectCapable] and
 * [UsbAudioManager.setVolume] as inputs.
 */
internal interface UsbAbxTester {
    /**
     * Whether the *current* track was secretly routed bit-perfect (`true`) or HAL fallback
     * (`false`) by [rollTrial], or `null` if no trial applies to this track. Only meant for the
     * code deciding routing -- a host app should read [pendingGuess] instead, which is what's
     * actually safe to act on without leaking the current track's route.
     */
    val groundTruth: StateFlow<Boolean?>

    /**
     * The ground truth of the track that *just finished* its trial, still ungraded -- `null` once
     * [submitGuess] consumes it, or if that track had no trial running. See
     * [UsbAudioManager.abxPendingGuess].
     */
    val pendingGuess: StateFlow<Boolean?>

    /** Cumulative score across every [submitGuess] call this process lifetime. */
    val stats: StateFlow<UsbAudioManager.AbxStats>

    /** Rerolls the current track's hidden coin flip -- see [UsbAudioManager.rollAbxTrial]. */
    fun rollTrial()

    /** Grades [guessedBitPerfect] against [pendingGuess] and consumes the trial. */
    fun submitGuess(guessedBitPerfect: Boolean): Boolean?
}
