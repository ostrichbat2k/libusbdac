/*
 * Copyright (c) 2026 Auxio Project
 * UsbControlState.kt is part of Auxio.
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

/**
 * A live, read-back snapshot of the active [UsbAudioSession]'s adjustable controls, built
 * entirely from what the *current* device actually reports (available formats, its real
 * mute/volume/mixer-gain ranges) rather than anything hardcoded for one specific device. Used to
 * drive the USB settings screen's dynamically-built controls -- a device with no Mixer Unit in
 * its topology simply reports an empty [mixerChannels], etc., so the UI only ever shows controls
 * that this particular device actually has.
 */
data class UsbControlState(
    val deviceName: String,
    val formats: List<UacStreamFormat>,
    val activeFormat: UacStreamFormat,
    val sampleRateHz: Int,
    val muted: Boolean?,
    val volume: Int?,
    val volumeRange: Pair<Int, Int>?,
    /** (inputChannel, outputChannel) pairs on the Mixer Unit gating our USB input, if any. */
    val mixerChannels: List<Pair<Int, Int>>,
    val mixerGains: Map<Pair<Int, Int>, Int?>,
    val mixerGainRange: Pair<Int, Int>?,
    /**
     * The Clock Source's actual supported sample rate sub-ranges (min..max pairs in Hz), read via
     * GET_RANGE at activation -- see [UsbAudioSession.clockSupportedRates]. Empty for UAC 1.0 (no
     * Clock Source) or if the device didn't answer the query, same caveat as there: empty here
     * means "couldn't ask", not "supports nothing".
     */
    val clockSupportedRates: List<Pair<Int, Int>>,
)
