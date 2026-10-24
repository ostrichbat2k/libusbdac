/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAbxTesterImpl.kt is part of libusbdac.
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

import org.ostrichbat2k.usbdac.SessionState
import org.ostrichbat2k.usbdac.UsbAudioManager.AbxStats
import org.ostrichbat2k.usbdac.UsbAudioManagerImpl
import org.ostrichbat2k.usbdac.UsbDacPreferences
import org.ostrichbat2k.usbdac.control.UsbVolumeController
import org.ostrichbat2k.usbdac.streaming.RoutingAudioSink
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class UsbAbxTesterImpl(
    private val usbSettings: UsbDacPreferences,
    private val isBitPerfectCapable: () -> Boolean,
    private val volumeController: UsbVolumeController,
    private val setVolume: (Int) -> Unit,
    // rollTrial()'s fallback branch used to reach directly into UsbAudioManagerImpl's
    // sessionState to drop a stale cached bit-perfect sink -- this callback preserves that without
    // this class needing to know what a SessionState even is.
    private val clearCachedSink: () -> Unit,
) : UsbAbxTester {
    private val _groundTruth = MutableStateFlow<Boolean?>(null)
    override val groundTruth: StateFlow<Boolean?> = _groundTruth.asStateFlow()

    private val _pendingGuess = MutableStateFlow<Boolean?>(null)
    override val pendingGuess: StateFlow<Boolean?> = _pendingGuess.asStateFlow()

    private val _stats = MutableStateFlow(AbxStats())
    override val stats: StateFlow<AbxStats> = _stats.asStateFlow()

    /**
     * Rerolls this track's hidden ABX coin flip -- called by [RoutingAudioSink] once per real
     * `configure()` call (i.e. once per track), *before* it resolves which delegate to use, so
     * each track gets an independent, fresh decision instead of being stuck on whatever the first
     * track in the session happened to pick ([UsbAudioManagerImpl.resolveActiveSink]'s own
     * `activeSink` caching would otherwise keep reusing one session-wide choice forever -- see its
     * doc comment).
     *
     * Deliberately does not attempt to switch mid-track: this only ever changes what the *next*
     * `configure()` call sees, same mechanism (and same real-hardware-proven code path) as
     * ordinary bit-perfect/fallback switching already uses on every track change.
     */
    override fun rollTrial() {
        // Whatever the *previous* track's trial was (if any, and if it's not already been
        // guessed) is about to be overwritten below -- hand it off to pendingGuess first so
        // the host app can still prompt about it, now that this track change means it's actually
        // over. Unconditional: even a test turned off mid-track should still surface the trial
        // that was genuinely running for the track that just ended.
        _groundTruth.value?.let { _pendingGuess.value = it }

        if (!usbSettings.abxTestMode) {
            _groundTruth.value = null
            return
        }
        if (!usbSettings.enabled || !isBitPerfectCapable()) {
            // Can't offer a real trial if bit-perfect isn't even viable right now -- forcing one
            // would just break playback instead of testing anything.
            _groundTruth.value = null
            return
        }
        val useBitPerfect = Random.nextBoolean()
        _groundTruth.value = useBitPerfect
        if (useBitPerfect) {
            syncAbxLoudnessToBitPerfect()
        } else {
            // resolveActiveSink()'s activeSink?.let { return it } check runs before it ever
            // consults this trial's result -- without clearing it here, a track secretly
            // assigned to fallback would still get handed back a live, cached bit-perfect sink.
            clearCachedSink()
        }
    }

    /**
     * Pushes the DAC's live hardware volume to match STREAM_MUSIC's *current* index, right
     * before an ABX trial that's about to go bit-perfect -- see
     * [UsbVolumeController.abxSyncTargetDb] for why this needs to exist at all.
     *
     * Reuses the exact same write path a physical volume key press already takes
     * ([UsbAudioManager.setVolume]), including its own STREAM_MUSIC mirroring call -- a no-op
     * round trip in this direction, since it's already at the value just read from it here.
     */
    private fun syncAbxLoudnessToBitPerfect() {
        volumeController.abxSyncTargetDb()?.let(setVolume)
    }

    /**
     * Grades [guessedBitPerfect] against [pendingGuess], updates [stats], and consumes the
     * trial (clears [pendingGuess] so the host app's prompt goes away until the next track
     * change surfaces a new one). Returns null if there was nothing pending -- the host app
     * should not have offered a guess in that case to begin with.
     */
    override fun submitGuess(guessedBitPerfect: Boolean): Boolean? {
        val truth = _pendingGuess.value ?: return null
        val correct = truth == guessedBitPerfect
        _stats.value = _stats.value.let { it.copy(correct = it.correct + if (correct) 1 else 0, total = it.total + 1) }
        _pendingGuess.value = null
        return correct
    }
}
