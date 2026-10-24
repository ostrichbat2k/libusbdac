/*
 * Copyright (c) 2026 libusbdac Project
 * UsbVolumeControllerImpl.kt is part of libusbdac.
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

import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.StateFlow
import org.ostrichbat2k.usbdac.api.UsbAudioManager
import org.ostrichbat2k.usbdac.api.UsbDacPreferences
import org.ostrichbat2k.usbdac.api.UsbVolumeController

/**
 * Everything this app decides about USB DAC hardware volume/mute -- as opposed to
 * [UsbAudioController]'s raw `SET_CUR`/`GET_CUR`/`GET_RANGE` wire primitives, which this class
 * calls into but never replaces.
 *
 * Centralized here (rather than split across [UsbAudioController] and [UsbAudioManager], where
 * it used to live) specifically because the pieces below share one invariant that's easy to
 * silently break if it's not all visible in one place: **the hardware Feature Unit sits at true
 * unity gain for the entire time a fallback (HAL-routed) track is playing.** Both
 * [syncAbxLoudnessToBitPerfect] and [mirrorDbToStreamMusic]/[onStreamMusicChanged] depend on that
 * being exactly true to compute correct perceived-loudness matching across a bit-perfect <->
 * fallback switch -- confirmed, reproduced: a locally-reasonable-looking fix for a different bug
 * (lowering the pre-handoff volume to a quieter safety margin instead of unity) would have broken
 * both of these silently, since the invariant they rely on was only ever documented as scattered
 * comments pointing at a function in a different file.
 */
class UsbVolumeControllerImpl(
    private val audioManager: AudioManager,
    private val usbSettings: UsbDacPreferences,
    private val controlState: StateFlow<UsbControlState?>,
    private val playbackRoute: StateFlow<UsbAudioManager.PlaybackRoute>,
    private val logEvent: (String) -> Unit,
) : UsbVolumeController {
    private fun currentVolumeRange(): Pair<Int, Int>? = controlState.value?.volumeRange

    /**
     * Brackets [UsbAudioController.setMute]/volume writes the same way whether called right
     * before releasing the interface to the kernel ([UsbAudioManager.doReleaseClaim]) or right
     * before tearing the session down entirely ([UsbAudioManager.deactivateDac]) -- previously
     * duplicated inline at both call sites.
     *
     * Forces every channel to unity gain (the top of its own reported range -- see
     * [UsbAudioController.getVolumeRange]'s doc comment on why that's the general UAC convention
     * for "loudest/least attenuated", not a hardcoded 0) before handing the interface back:
     * that path only ever applies its own software volume curve on top of whatever the Feature
     * Unit's hardware gain already is, the same way it would for any other ordinary USB audio
     * device -- it has no reason to assume *this* one might be sitting at some attenuated level
     * a different, unrelated code path (bit-perfect mode's own volume control, or just the
     * device's own initial-activation midpoint default) left it at. Confirmed, reproduced:
     * without this, a HAL-routed track topped out at roughly 40-50% of real full volume even at
     * 100% system volume, because the hardware gain was still sitting wherever it was last set
     * for bit-perfect playback (or the activation-time midpoint, if the user never even touched
     * bit-perfect mode) -- Android's software curve was stacking on top of that, not replacing
     * it.
     *
     * Bracketed with mute the same way [UsbAudioOutput]'s own clock-relock is (see its init-time
     * comment on why): the unity jump can be large and sudden (e.g. -30dB -> 0dB) from wherever
     * bit-perfect mode last left it, and doing that while unmuted is audible as a real pop/click
     * -- confirmed, reported. Unmuting again before the caller releases to the kernel, not after,
     * since by the time Android's HAL actually opens the device for a fallback track, it needs
     * to already be unmuted to be audible -- callers must not try to mute again afterward, since
     * once the interface is released these control transfers need it claimed the same as any
     * other.
     */
    override fun prepareForHalHandoff(session: UsbAudioSession, channelCount: Int) {
        UsbAudioController.setMute(session, muted = true)
        for (channel in 0..channelCount.coerceAtLeast(2)) {
            val range = UsbAudioController.getVolumeRange(session, channel) ?: continue
            UsbAudioController.setVolume(session, range.second, channel)
        }
        UsbAudioController.setMute(session, muted = false)
    }

    /**
     * Re-mutes a session [UsbAudioManager.awaitAndroidOutputTakeover] just reclaimed back from
     * the kernel after HAL failed to confirm it took the device over in time, for a release that
     * didn't actually need HAL to succeed (see that function's own doc comment on why only a
     * genuine per-track fallback escalates to a full deactivation instead -- a courtesy release
     * with nothing concrete waiting on HAL has nothing to gain from tearing the session down, and
     * reclaiming it back here keeps bit-perfect mode usable in the meantime). Just mute: the
     * Feature Unit is already sitting at the unity gain [prepareForHalHandoff] left it at moments
     * ago, which is still the right level to leave it at -- whatever happens next (another
     * release attempt, or a bit-perfect track reclaiming it for real) decides the actual level
     * from there, not this call.
     */
    override fun remuteForUnconfirmedHandoff(session: UsbAudioSession) {
        UsbAudioController.setMute(session, muted = true)
    }

    /**
     * [AUDIO_STREAM_MUSIC, DEVICE_CATEGORY_HEADSET] and [..., DEVICE_CATEGORY_EXT_MEDIA] (USB
     * falls under one or the other either way) both reference this exact curve in AOSP's
     * `frameworks/av/services/audiopolicy/config/audio_policy_volumes.xml` ->
     * `default_volume_tables.xml`'s `DEFAULT_MEDIA_VOLUME_CURVE`: (percent-of-index-range,
     * attenuation in dB), linearly interpolated in dB between points -- confirmed against the
     * actual interpolation code, `VolumeCurve::volIndexToDb` in
     * `frameworks/av/services/audiopolicy/engine/common/src/VolumeCurve.cpp`. This is AOSP's
     * documented default, not a guess, but OEMs commonly retune these tables for their own
     * devices, so it's still only an approximation of *this* device's real curve -- a much
     * closer one than a flat linear guess, though.
     */
    private val mediaVolumeCurve = listOf(1f to -58f, 20f to -40f, 60f to -17f, 100f to 0f)

    /** The curve's attenuation (dB) at a given [percent] (0..100) of the index range. */
    private fun curveDbAtPercent(percent: Float): Float {
        val p = percent.coerceIn(0f, 100f)
        for (i in 1 until mediaVolumeCurve.size) {
            val (idxHi, dbHi) = mediaVolumeCurve[i]
            if (p <= idxHi) {
                val (idxLo, dbLo) = mediaVolumeCurve[i - 1]
                val t = if (idxHi == idxLo) 0f else (p - idxLo) / (idxHi - idxLo)
                return dbLo + t * (dbHi - dbLo)
            }
        }
        return mediaVolumeCurve.last().second
    }

    /** The inverse of [curveDbAtPercent]: the percent (0..100) at which the curve reads [db]. */
    private fun curvePercentAtDb(db: Float): Float {
        val d = db.coerceIn(mediaVolumeCurve.first().second, mediaVolumeCurve.last().second)
        for (i in 1 until mediaVolumeCurve.size) {
            val (idxHi, dbHi) = mediaVolumeCurve[i]
            val (idxLo, dbLo) = mediaVolumeCurve[i - 1]
            if (d <= dbHi) {
                val t = if (dbHi == dbLo) 0f else (d - dbLo) / (dbHi - dbLo)
                return idxLo + t * (idxHi - idxLo)
            }
        }
        return mediaVolumeCurve.last().first
    }

    private fun streamMusicIndexRange(): Pair<Int, Int> {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            } else {
                0
            }
        return min to max
    }

    private fun Float.roundToIntSafe(min: Int, max: Int): Int =
        kotlin.math.round(this).toInt().coerceIn(min, max)

    // Set right before mirrorDbToStreamMusic's own setStreamVolume call, and cleared by the very
    // next VOLUME_CHANGED_ACTION broadcast onStreamMusicChanged sees -- distinguishes "this
    // broadcast is just an echo of our own write" from "the user (or another app) genuinely
    // changed STREAM_MUSIC", which is the only case that should feed back into [UsbDacPreferences
    // .volumeDb]. A plain boolean is enough: Android delivers this broadcast synchronously
    // enough relative to the call that there's never more than one in flight at a time in
    // practice, and worst case a missed real external change just waits for the next one.
    @Volatile private var ignoreNextStreamMusicBroadcast = false

    /**
     * Keeps Android's own [AudioManager.STREAM_MUSIC] index matched (via [mediaVolumeCurve],
     * Android's own real curve, not a flat linear guess) to the DAC's hardware dB level, purely
     * so switching between a bit-perfect track (loudness controlled by the Feature Unit) and a
     * fallback track (loudness controlled by STREAM_MUSIC -- see [prepareForHalHandoff]'s doc
     * comment on why the hardware itself sits at unity during fallback) doesn't sound like a
     * jump up or down in level just because the two tracks happened to use different control
     * paths. [ignoreNextStreamMusicBroadcast] guards against this call's own
     * [AudioManager.setStreamVolume] immediately feeding back into [onStreamMusicChanged] as if
     * the user had just adjusted it externally.
     */
    override fun mirrorDbToStreamMusic(valueDb: Int) {
        currentVolumeRange() ?: return
        val percent = curvePercentAtDb(valueDb / 256f)
        val (minIndex, maxIndex) = streamMusicIndexRange()
        val index = (minIndex + percent / 100f * (maxIndex - minIndex)).roundToIntSafe(minIndex, maxIndex)
        if (index == audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) return
        ignoreNextStreamMusicBroadcast = true
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }

    /**
     * The inverse of [mirrorDbToStreamMusic]: called when [AudioManager.VOLUME_CHANGED_ACTION]
     * reports a real (not self-caused) STREAM_MUSIC change while a fallback track -- the only
     * time STREAM_MUSIC is actually what's audible -- is the current route. Persists the dB
     * equivalent to [UsbDacPreferences.volumeDb] so the *next* bit-perfect track picks up from
     * roughly the same perceived loudness, but deliberately does not touch the hardware live --
     * it's intentionally sitting at unity gain for the whole time a fallback track is playing
     * (see [prepareForHalHandoff]), and touching it now would mean reclaiming the interface right
     * out from under HAL's own active use of it, the same dropout bug
     * [UsbAudioManager.releaseClaim]'s `activeStreamCount` check exists to prevent.
     */
    override fun onStreamMusicChanged(newIndex: Int) {
        if (ignoreNextStreamMusicBroadcast) {
            ignoreNextStreamMusicBroadcast = false
            return
        }
        if (!usbSettings.bitPerfectMode || controlState.value == null) return
        if (playbackRoute.value !is UsbAudioManager.PlaybackRoute.Fallback) return
        val range = currentVolumeRange() ?: return
        val (minIndex, maxIndex) = streamMusicIndexRange()
        if (maxIndex == minIndex) return
        val percent = 100f * (newIndex - minIndex) / (maxIndex - minIndex)
        val dbValue = (curveDbAtPercent(percent) * 256f).roundToIntSafe(range.first, range.second)
        logEvent("STREAM_MUSIC changed to $newIndex while on fallback -- syncing DAC volume target to ${dbValue}dB")
        usbSettings.volumeDb = dbValue
    }

    /**
     * The dB value [UsbAudioManager.setVolume] should write to push the DAC's live hardware
     * volume to whatever dB [mediaVolumeCurve] says matches STREAM_MUSIC's *current* index,
     * right before an ABX trial that's about to go bit-perfect. Null if there's nothing to sync
     * (no volume range known yet, or STREAM_MUSIC has no real range on this device).
     *
     * [mirrorDbToStreamMusic]/[onStreamMusicChanged]'s existing mirroring only runs reactively,
     * when the user explicitly moves a volume control -- during a hands-off ABX run (queue just
     * auto-advancing, nobody touching a volume key), two bit-perfect trials separated by a
     * fallback trial in between would otherwise keep whatever hardware dB was last explicitly
     * set, which could be a genuinely different perceived loudness than STREAM_MUSIC -- the
     * *only* thing actually controlling loudness during that intervening fallback trial, since
     * the hardware deliberately sits at forced unity gain the whole time one is live (see
     * [prepareForHalHandoff]). A real loudness mismatch between trials would confound the whole
     * point of a blind test by giving away which path is live, or worse, making the "wrong" one
     * sound better for a reason that has nothing to do with bit-perfection.
     */
    override fun abxSyncTargetDb(): Int? {
        val range = currentVolumeRange() ?: return null
        val (minIndex, maxIndex) = streamMusicIndexRange()
        if (maxIndex == minIndex) return null
        val currentIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = 100f * (currentIndex - minIndex) / (maxIndex - minIndex)
        return (curveDbAtPercent(percent) * 256f).roundToIntSafe(range.first, range.second)
    }
}
