/*
 * Copyright (c) 2026 Auxio Project
 * UsbAudioOutputProvider.kt is part of Auxio.
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

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import timber.log.Timber as L

/**
 * An [AudioOutputProvider] that reports direct-PCM support for a UAC1/2/3 device's own declared
 * capabilities (bit depth, channel count, and sample rate(s)) and hands out [UsbAudioOutput]
 * instances that stream to it -- plugged into a plain `DefaultAudioSink` (see
 * [UsbAudioManager.createAudioSink]) rather than Auxio rolling its own full `AudioSink`, so all of
 * `DefaultAudioSink`'s buffering/retry/stall-recovery logic applies to USB output the same way it
 * already does for normal AudioTrack output.
 *
 * Matches each track against *every* format this device declares, not a single one fixed for the
 * whole DAC session -- a library mixing standard 16-bit tracks with 24-bit hi-res ones needs a
 * different device alt-setting per track to play either correctly. Confirmed, reproduced: forcing
 * one fixed bit depth for an entire session either corrupted the other kind of track (byte-
 * misaligned between what ExoPlayer actually produced and how the native layer packed it into
 * isochronous packets -- heard as loud hissing/garbage) or silently rejected it outright once that
 * mismatch was caught instead. [UsbAudioManager.ensureFormatActive] does the actual alt-setting
 * switch, called from [getAudioOutput] -- which only runs when `DefaultAudioSink` has already
 * decided the format genuinely changed (see [AudioOutput.canReuseAudioOutput]'s default contract),
 * so same-format-back-to-back tracks don't pay any switching cost at all.
 */
class UsbAudioOutputProvider(
    private val usbAudioManager: UsbAudioManager,
    private val session: UsbAudioSession,
    private val usbSettings: UsbDacPreferences,
) : AudioOutputProvider {

    private companion object {
        // Fallback only -- used when UacPlaybackTopology.channelCount couldn't be determined
        // (always true for UAC 3.0, which pushes channel count into a Cluster Descriptor fetch
        // this codebase doesn't do) or when a format's own supported rates couldn't be read back
        // at all (see acceptedSampleRates). Not a hardcoded assumption applied unconditionally.
        const val FALLBACK_CHANNELS = 2
        val FALLBACK_SAMPLE_RATES = setOf(44100, 48000)
    }

    // Every format this device's streaming interface declares (one per alt-setting, typically one
    // per bit depth) -- matched against per-track, not narrowed down to a single one up front.
    private val allFormats: List<UacStreamFormat> = session.topology.formats

    // Real channel count from the device's own Input Terminal descriptor when known (see
    // UacPlaybackTopology.channelCount); falls back to assuming stereo only for UAC 3.0, where
    // this codebase doesn't resolve it at all. Assumed constant across every format/alt-setting
    // this device offers -- channel count hasn't been observed to vary by bit depth on any device
    // tested against.
    val channels: Int = session.topology.channelCount.let { if (it > 0) it else FALLBACK_CHANNELS }

    // UAC 1.0 declares its actual supported rate(s) per-format directly (e.g. a real device this
    // was confirmed against offers only 8000/48000 Hz, not 44100 at all). UAC 2.0/3.0 negotiate
    // through a shared Clock Source instead -- session.clockSupportedRates is that Clock
    // Source's own declared range, read once via GET_RANGE at activation (see
    // UsbAudioManager.activateDac and UsbAudioSession.clockSupportedRates for why this matters:
    // requesting a rate the clock doesn't actually support was confirmed, on a real device, to
    // report a clean "success" while silently not changing the real hardware clock at all).
    //
    // Each (min, max) sub-range where min == max is the clock's own declaration of one exact
    // discrete rate it supports -- read those directly rather than only ever filtering
    // FALLBACK_SAMPLE_RATES against them. Confirmed, reproduced: a device whose clock reported
    // discrete support up to 384000 Hz (FiiO Snowsky Tiny A: 44100/48000/88200/96000/176400/
    // 192000/352800/384000, each its own (rate, rate) sub-range) still had every rate above
    // 48000 rejected as "not supported", because FALLBACK_SAMPLE_RATES -- meant only as a last-
    // resort guess for when GET_RANGE itself can't be read at all -- was also silently acting as
    // the hard ceiling on every device that *could* answer it. Only falls through to the old
    // filter-against-the-guess behavior if the clock reports exclusively continuous (non-point)
    // ranges, i.e. genuinely no discrete rates to read directly.
    private fun acceptedSampleRates(format: UacStreamFormat): Set<Int> =
        when {
            format.sampleRates.isNotEmpty() -> format.sampleRates.toSet()
            session.clockSupportedRates.isNotEmpty() ->
                session.clockSupportedRates
                    .mapNotNull { (min, max) -> min.takeIf { it == max } }
                    .toSet()
                    .ifEmpty {
                        FALLBACK_SAMPLE_RATES.filter { rate ->
                            session.clockSupportedRates.any { (min, max) -> rate in min..max }
                        }.toSet()
                    }
            else -> FALLBACK_SAMPLE_RATES
        }

    // Must match a format's own subslotSize (its declared bytes-per-sample), never hardcoded --
    // confirmed, reproduced: requesting 16-bit PCM from ExoPlayer unconditionally while the
    // native layer (UsbAudioOutput's bytesPerFrame = channels * format.subslotSize) packed frames
    // assuming whatever subslotSize a *different*, preference-selected format declared meant a
    // straight byte-alignment mismatch between what was produced and how it was packed into
    // isochronous packets -- heard as loud hissing/garbage.
    private fun encodingFor(subslotSize: Int): Int =
        when (subslotSize) {
            1 -> C.ENCODING_PCM_8BIT
            2 -> C.ENCODING_PCM_16BIT
            3 -> C.ENCODING_PCM_24BIT
            4 -> C.ENCODING_PCM_32BIT
            else -> C.ENCODING_PCM_16BIT
        }

    /**
     * Finds whichever of this device's declared formats actually matches what ExoPlayer wants to
     * hand us for this specific track -- its real decoded bit depth (via subslotSize), channel
     * count, and sample rate -- instead of requiring one bit depth fixed for the whole session.
     */
    /**
     * Was hardcoded to mono-or-else-stereo, silently misrepresenting any device with more than 2
     * channels -- this project's channel count was never actually hardcoded (it's always read
     * from the device's own Input Terminal descriptor, see [channels]), only the *mask* built
     * from it was. Covers every channel count this codebase's UAC descriptor parsing can
     * actually produce; a count with no listed mapping falls back to stereo rather than silently
     * misreporting a wrong-but-plausible one.
     */
    private fun channelMaskFor(channelCount: Int): Int =
        when (channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            7 -> AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_BACK_CENTER
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> {
                L.w("UsbAudioOutputProvider: no channel mask mapping for $channelCount channels, falling back to stereo")
                AudioFormat.CHANNEL_OUT_STEREO
            }
        }

    private fun describeEncoding(encoding: Int): String =
        when (encoding) {
            C.ENCODING_PCM_8BIT -> "8-bit"
            C.ENCODING_PCM_16BIT -> "16-bit"
            C.ENCODING_PCM_24BIT -> "24-bit"
            C.ENCODING_PCM_32BIT -> "32-bit"
            else -> "encoding=$encoding"
        }

    private fun matchingFormat(f: androidx.media3.common.Format): UacStreamFormat? =
        allFormats.firstOrNull { candidate ->
            encodingFor(candidate.subslotSize) == f.pcmEncoding &&
                f.channelCount == channels &&
                f.sampleRate in acceptedSampleRates(candidate)
        }

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        val format = formatConfig.format
        val match = matchingFormat(format)
        // Logged on every track (not just failures) -- the file's actual decoded format, printed
        // here, is what made the earlier 16-bit-vs-24-bit byte-alignment mismatch and the 96kHz
        // rejection both obvious from a single logcat capture instead of having to cross-
        // reference URB/clock logs against which track was actually playing.
        L.i(
            "UsbAudioOutputProvider: track format ${describeEncoding(format.pcmEncoding)}/" +
                "${format.channelCount}ch/${format.sampleRate}Hz -> " +
                (match?.let { "device format ${it.bitResolution}-bit/alt=${it.alternateSetting}" }
                    ?: "NO MATCHING DEVICE FORMAT"))
        if (match == null) {
            return AudioOutputProvider.FormatSupport.UNSUPPORTED
        }
        return AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY)
            .build()
    }

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        val format = formatConfig.format
        // getFormatSupport already gated on a match existing for this exact format/encoding --
        // this just has to agree with it, requesting the matched device format's own real
        // integer encoding.
        val encoding = matchingFormat(format)?.let { encodingFor(it.subslotSize) } ?: C.ENCODING_PCM_16BIT
        val channelMask = channelMaskFor(channels)
        return AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(encoding)
            .setSampleRate(format.sampleRate)
            .setChannelMask(channelMask)
            .setAudioAttributes(formatConfig.audioAttributes)
            .setAudioSessionId(formatConfig.audioSessionId)
            .setVirtualDeviceId(formatConfig.virtualDeviceId)
            .build()
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        // Only called when DefaultAudioSink has already decided the OutputConfig genuinely
        // changed (AudioOutput.canReuseAudioOutput's default contract) -- so this is exactly the
        // point, and the only point, where switching the device's alt-setting is actually needed.
        val target =
            allFormats.firstOrNull { encodingFor(it.subslotSize) == config.encoding }
                ?: allFormats.first().also {
                    L.w("UsbAudioOutputProvider: no format matches encoding=${config.encoding}, using ${it.bitResolution}-bit")
                }
        L.i(
            "UsbAudioOutputProvider: activating ${target.bitResolution}-bit/alt=${target.alternateSetting} " +
                "at ${config.sampleRate}Hz for upcoming track")
        usbAudioManager.ensureFormatActive(target, config.sampleRate)
        // Reads usbSettings.volumeDb fresh on every call rather than once at construction time --
        // this provider (and the DefaultAudioSink built from it) is cached and reused for an
        // entire DAC session (see UsbAudioManager.activeSink), while getAudioOutput() itself gets
        // called again for every track whose format actually differs. Capturing the volume once
        // up front meant every later volume-key correction only applied to the current track --
        // the next format/rate change silently reverted to whatever was persisted back when this
        // provider was first created, confirmed reproduced as "volume still wrong on a new song"
        // even after explicitly correcting it on the previous one.
        return UsbAudioOutput(session, target, channels, config.sampleRate, usbSettings.volumeDb, usbAudioManager)
    }

    override fun addListener(listener: AudioOutputProvider.Listener) {}

    override fun removeListener(listener: AudioOutputProvider.Listener) {}

    override fun release() {}
}
