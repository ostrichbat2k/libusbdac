/*
 * Copyright (c) 2026 libusbdac Project
 * UsbControlWriterImpl.kt is part of libusbdac.
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

import java.util.concurrent.Executors
import org.ostrichbat2k.usbdac.api.UsbAudioManager.ClaimOutcome
import org.ostrichbat2k.usbdac.api.UsbControlWriter
import org.ostrichbat2k.usbdac.api.UsbDacPreferences
import org.ostrichbat2k.usbdac.api.UsbVolumeController

internal class UsbControlWriterImpl(
    private val usbSettings: UsbDacPreferences,
    private val volumeController: UsbVolumeController,
    private val getActiveSession: () -> UsbAudioSession?,
    private val getActiveFormat: () -> UacStreamFormat?,
    private val getActiveRateHz: () -> Int,
    private val requestClaim: () -> ClaimOutcome,
    // Always the same fixed grace-period/no-wait/no-escalate release -- see UsbAudioManager.
    // releaseClaim's own doc comment for why every Settings-screen write uses exactly this.
    private val releaseClaim: () -> Unit,
    private val getControlState: () -> UsbControlState?,
    private val publishControlState: (UsbControlState?) -> Unit,
    private val log: (String) -> Unit,
) : UsbControlWriter {
    /**
     * Tries [probe] against channels 0..[channelCount] in order and returns the first non-null
     * result.
     *
     * The write side ([scheduleVolumeWrite], [UsbDacActivatorImpl.activate]) always broadcasts
     * the same target to every channel, so on a device with real per-channel Feature Unit
     * controls, whichever channel actually answers is reporting the same value by construction
     * -- there's no need to know in advance which channel that is. This replaced a hardcoded
     * channel-0 (master) read that was previously misdiagnosed as a device-side firmware
     * limitation: confirmed against the FiiO Snowsky Tiny A's own descriptors
     * (`bmaControls(0)=0x00000000`, i.e. the device declares *no* controls at all on the master
     * channel -- only `bmaControls(1)` and `(2)` (the individual L/R channels) have the Volume
     * Control bit set). A GET_CUR for a channel the device never declared support for is spec'd
     * to STALL (UAC2 §5.2.5.4.2), which is exactly the `result=-1` that looked like a broken
     * device. Probing every channel instead of hardcoding one means this works whether a
     * device's control lives on master, on individual channels only, or (as here) anywhere but
     * master.
     */
    private fun <T> probeChannels(channelCount: Int, probe: (channel: Int) -> T?): T? {
        for (channel in 0..channelCount) {
            probe(channel)?.let { return it }
        }
        return null
    }

    override fun refreshControlState(session: UsbAudioSession, format: UacStreamFormat, deviceName: String) {
        val mixerUnitId = session.topology.mixerUnitId
        val mixerBase = session.topology.mixerInputChannelBase
        // Was hardcoded to a stereo-in/stereo-out grid -- see UsbDacActivatorImpl.activate's
        // identical fix for why this scales with the device's own real channel count instead.
        // Every session here is always one that's already normalized (see its channel-count
        // fallback), so no coerce is needed on this read.
        val mixerChannelCount = session.topology.channelCount
        val mixerChannels =
            if (mixerUnitId >= 0 && mixerBase >= 0) {
                (mixerBase until mixerBase + mixerChannelCount).flatMap { inCh ->
                    (1..mixerChannelCount).map { outCh -> inCh to outCh }
                }
            } else {
                emptyList()
            }
        val mixerGains =
            mixerChannels.associateWith { (inCh, outCh) ->
                UsbAudioController.getMixerGain(session, mixerUnitId, inCh, outCh)
            }
        val mixerGainRange =
            mixerChannels.firstOrNull()?.let { (inCh, outCh) ->
                UsbAudioController.getMixerGainRange(session, mixerUnitId, inCh, outCh)
            }
        // See [probeChannels]'s doc comment: a single hardcoded channel (especially master/0)
        // can be one a given device never declared a Volume control on at all, which used to be
        // misread as a dead device rather than the wrong address. Falling back to the last known
        // value if every channel comes back null (transient USB error, or a device with no
        // volume control whatsoever) still matters for the same reason as before: every caller of
        // [UsbAudioManager.controlState] (this screen's volume slider, [MediaSessionHolder]'s
        // physical-volume-key routing) treats "unknown" as "assume max", so losing the last known
        // value entirely would read as "reset to max" instead of "nothing changed".
        val volume =
            probeChannels(session.topology.channelCount) { channel ->
                UsbAudioController.getVolume(session, channel)
            } ?: getControlState()?.volume
        publishControlState(
            UsbControlState(
                deviceName = deviceName,
                formats = session.topology.formats,
                activeFormat = format,
                sampleRateHz = getActiveRateHz(),
                muted = UsbAudioController.getMute(session),
                volume = volume,
                volumeRange =
                    probeChannels(session.topology.channelCount) { channel ->
                        UsbAudioController.getVolumeRange(session, channel)
                    },
                mixerChannels = mixerChannels,
                mixerGains = mixerGains,
                mixerGainRange = mixerGainRange,
                clockSupportedRates = session.clockSupportedRates))
    }

    /** Live-adjusts the active session's mute state, if any; reflected in [UsbAudioManager.controlState]. */
    override fun setMuted(muted: Boolean) {
        val session = getActiveSession() ?: return
        val format = getActiveFormat() ?: return
        // A Settings-screen tweak needs the interface claimed the same as real streaming does --
        // see requestClaim's doc comment -- and releaseClaim() hands it straight back to Android
        // after the usual grace period rather than leaving it claimed indefinitely just because
        // the user poked a control once.
        if (requestClaim() == ClaimOutcome.FAILED) return
        UsbAudioController.setMute(session, muted)
        log("User set mute=$muted")
        refreshControlState(session, format, session.device.productName ?: session.device.deviceName)
        releaseClaim()
    }

    // Dedicated to serializing [setVolume]'s own control transfers off whichever thread calls it
    // (the physical volume keys dispatch through MediaSessionCompat's callback handler, i.e. the
    // main thread) -- see [setVolume]'s doc comment for why this exists.
    private val volumeExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "UsbAudioControl-Volume").apply { isDaemon = true } }
    @Volatile private var pendingVolumeDb: Int? = null
    @Volatile private var volumeWriteInFlight = false

    /**
     * Live-adjusts the active session's master/channel volume, if any; reflected in
     * [UsbAudioManager.controlState]. Runs the actual control transfers (and the full post-write
     * [refreshControlState] re-read) on a background thread and coalesces rapid successive calls
     * down to just the latest target, rather than running each synchronously on the calling
     * thread in turn.
     *
     * This specifically fixes a real, reported lag: holding the physical volume key fires a
     * burst of calls here (one per key-repeat event) in quick succession on the main thread. Each
     * one used to be a fully synchronous round trip -- 3 `SET_CUR` transfers plus
     * [refreshControlState]'s own handful of `GET_CUR`/`GET_RANGE` reads -- so a burst of them
     * queued up and had to drain serially, and the DAC's volume kept visibly climbing/falling for
     * a noticeable while after the user had already stopped pressing. Since only the *final*
     * target in a burst actually matters, [pendingVolumeDb] tracks just that, and
     * [scheduleVolumeWrite] only starts a new round trip once the previous one has actually
     * finished, skipping straight to whatever the latest requested value is instead of replaying
     * every intermediate step.
     */
    override fun setVolume(valueDb: Int) {
        pendingVolumeDb = valueDb
        // Reflect the requested value immediately so callers (the volume key's system UI, the
        // Settings screen slider) don't have to wait on the hardware round-trip just to show
        // where the user asked the slider to go; refreshControlState below corrects this to the
        // real read-back value once the write actually lands.
        publishControlState(getControlState()?.copy(volume = valueDb))
        // Persisted so the next activation (new session, app restart, DAC reconnect) starts from
        // here instead of a fixed default -- see [UsbAudioOutput]'s constructor.
        usbSettings.volumeDb = valueDb
        volumeController.mirrorDbToStreamMusic(valueDb)
        scheduleVolumeWrite()
    }

    private fun scheduleVolumeWrite() {
        if (volumeWriteInFlight) return
        val target = pendingVolumeDb ?: return
        val session = getActiveSession() ?: return
        val format = getActiveFormat() ?: return
        volumeWriteInFlight = true
        volumeExecutor.execute {
            // See setMuted's identical reasoning: a volume write needs the interface claimed the
            // same as real streaming does.
            if (requestClaim() == ClaimOutcome.FAILED) {
                volumeWriteInFlight = false
                return@execute
            }
            // Was hardcoded 0..2 (master + stereo) -- see UsbDacActivatorImpl.activate's
            // identical fix. No coerce needed: that activation already normalizes channelCount
            // before this session exists.
            for (channel in 0..session.topology.channelCount) {
                UsbAudioController.setVolume(session, target, channel)
            }
            log("User set volume=$target")
            refreshControlState(session, format, session.device.productName ?: session.device.deviceName)
            releaseClaim()
            volumeWriteInFlight = false
            if (pendingVolumeDb != target) {
                // Something more recent arrived while this write was in flight -- go straight to
                // it instead of waiting for the next external setVolume call to notice.
                scheduleVolumeWrite()
            }
        }
    }

    /** Live-adjusts one Mixer Unit (input, output) channel pair's gain, if the device has one. */
    override fun setMixerGain(inputChannel: Int, outputChannel: Int, valueDb: Int) {
        val session = getActiveSession() ?: return
        val format = getActiveFormat() ?: return
        val mixerUnitId = session.topology.mixerUnitId
        if (mixerUnitId < 0) return
        if (requestClaim() == ClaimOutcome.FAILED) return
        UsbAudioController.setMixerGain(session, mixerUnitId, inputChannel, outputChannel, valueDb)
        log("User set mixer gain in=$inputChannel out=$outputChannel to $valueDb")
        refreshControlState(session, format, session.device.productName ?: session.device.deviceName)
        releaseClaim()
    }
}
