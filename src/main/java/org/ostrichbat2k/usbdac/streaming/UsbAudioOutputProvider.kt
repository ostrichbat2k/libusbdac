/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioOutputProvider.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.streaming

import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.UsbDacPreferences
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession
import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import timber.log.Timber as L

/**
 * An [AudioOutputProvider] that reports direct-PCM support for a UAC1/2/3 device's own declared
 * capabilities (bit depth, channel count, and sample rate(s)) and hands out [UsbAudioOutput]
 * instances that stream to it -- plugged into a plain `DefaultAudioSink` (see
 * [UsbAudioManager.resolveActiveSink]) rather than rolling our own full `AudioSink`, so all of
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
        // Fallback only -- used when a format's own supported rates couldn't be read back at all
        // (see acceptedSampleRates). Not a hardcoded assumption applied unconditionally.
        val FALLBACK_SAMPLE_RATES = setOf(44100, 48000)
    }

    // Every format this device's streaming interface declares (one per alt-setting, typically one
    // per bit depth) -- matched against per-track, not narrowed down to a single one up front.
    private val allFormats: List<UacStreamFormat> = session.topology.formats

    // Real channel count from the device's own Input Terminal descriptor, or UsbAudioManager.
    // activateDac's UAC3 Cluster Descriptor fetch, or that same function's stereo-assumption
    // fallback if even that fails -- see UacPlaybackTopology.channelCount's doc comment.
    // session.topology is always one activateDac has already resolved by the time a provider is
    // constructed (see UsbAudioManager.resolveActiveSink), so no fallback is needed here too.
    // Assumed constant across every format/alt-setting this device offers -- channel count
    // hasn't been observed to vary by bit depth on any device tested against.
    val channels: Int = session.topology.channelCount

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

    /**
     * Finds whichever of this device's declared formats actually matches what ExoPlayer wants to
     * hand us for this specific track -- its real decoded bit depth (via subslotSize), channel
     * count, and sample rate -- instead of requiring one bit depth fixed for the whole session.
     */
    private fun matchingFormat(f: androidx.media3.common.Format): UacStreamFormat? =
        allFormats.firstOrNull { candidate ->
            encodingFor(candidate.subslotSize) == f.pcmEncoding &&
                f.channelCount == channels &&
                f.sampleRate in acceptedSampleRates(candidate)
        }

    /**
     * The best integer device format to convert *float* PCM down to. ExoPlayer never actually
     * hands a custom `AudioOutputProvider` real 32-bit integer PCM, no matter what the decoder
     * produced or what this provider claims to accept: `DefaultAudioSink.configure()`
     * unconditionally funnels every `MimeTypes.AUDIO_RAW` input through exactly one of two fixed
     * internal processors -- `ToInt16PcmAudioProcessor` or `ToFloatPcmAudioProcessor` -- *before*
     * this provider ever sees the format (confirmed by reading `DefaultAudioSink.configure()`
     * and `shouldUseFloatOutput()` directly; there is no custom-provider escape hatch from this).
     * So a 32-bit source that `FfmpegAudioDecoder.OUTPUT_MODE_NATIVE_INTEGER` decoded exactly
     * (see that mode's doc comment: FFmpeg's own FLAC/ALAC/PCM decoders pick 16- or 32-bit based
     * on the real source bps) always arrives here as float -- `shouldUseFloatOutput()` is keyed
     * on the *decoder's* reported encoding being wider than 16-bit, and the USB sink is built
     * with `enableFloatOutput = true` specifically so that rewrite lands on float, not a forced
     * 16-bit truncation. (An earlier version of this method matched on
     * `C.ENCODING_PCM_32BIT` instead, on the wrong assumption that the real 32-bit decode would
     * reach this provider unmodified -- confirmed, reproduced: it silently forced every >16-bit
     * source, including a genuine 24-bit FLAC, down to 16-bit with no warning, since that
     * encoding can structurally never arrive here to match against.)
     *
     * This picks the device's own *smallest* integer bit depth that's still wide enough to hold
     * real 24-bit content (24-bit when the device offers it, 32-bit only as a fallback): both are
     * an equally lossless, exact round-trip for it (a 24-bit target reconstructs the original
     * integer directly; a 32-bit one reconstructs it scaled up by a fixed power-of-two factor --
     * float32's mantissa represents either exactly), so there's no fidelity reason to prefer the
     * wider one, only a real cost: 33% more bytes over USB for identical information.
     */
    private fun wideIntTargetFormat(targetChannels: Int, sampleRate: Int): UacStreamFormat? =
        allFormats
            .filter { it.subslotSize >= 3 && targetChannels == channels && sampleRate in acceptedSampleRates(it) }
            .minByOrNull { it.subslotSize }

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        val format = formatConfig.format
        val match =
            if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) {
                wideIntTargetFormat(format.channelCount, format.sampleRate)
            } else {
                matchingFormat(format)
            }
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
        // this just has to agree with it. Float input stays float all the way to UsbAudioOutput
        // (which converts it to whatever wideIntTargetFormat picked); everything else requests
        // the matched device format's own real integer encoding.
        val encoding =
            if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) {
                C.ENCODING_PCM_FLOAT
            } else {
                matchingFormat(format)?.let { encodingFor(it.subslotSize) } ?: C.ENCODING_PCM_16BIT
            }
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
        val isFloatInput = config.encoding == C.ENCODING_PCM_FLOAT
        val target =
            if (isFloatInput) {
                wideIntTargetFormat(channels, config.sampleRate)
                    ?: allFormats.first().also {
                        L.w("UsbAudioOutputProvider: no float conversion target at ${config.sampleRate}Hz, using ${it.bitResolution}-bit")
                    }
            } else {
                allFormats.firstOrNull { encodingFor(it.subslotSize) == config.encoding }
                    ?: allFormats.first().also {
                        L.w("UsbAudioOutputProvider: no format matches encoding=${config.encoding}, using ${it.bitResolution}-bit")
                    }
            }
        L.i(
            "UsbAudioOutputProvider: activating ${target.bitResolution}-bit/alt=${target.alternateSetting} " +
                "at ${config.sampleRate}Hz for upcoming track" +
                (if (isFloatInput) " (converting from float)" else ""))
        usbAudioManager.ensureFormatActive(target, config.sampleRate)
        // Authoritative Now Playing route report -- RoutingAudioSink.configure() also reports
        // an immediate, optimistic one right after resolveForConfigure() for UI responsiveness,
        // but that one derives bit depth from the decoder's own inputFormat.pcmEncoding, which
        // is wrong whenever this is the float-conversion path (pcmEncoding is PCM_FLOAT, not a
        // real bit depth at all) -- confirmed, reproduced: the Now Playing screen showed blank,
        // then (after routing that through controlState.activeFormat instead) a stale leftover
        // value, because configure() reports before this function -- the only place that
        // actually knows the real [target] -- has run for this specific track at all
        // (DefaultAudioSink defers building the AudioOutput until the first handleBuffer call).
        // This report, right here with the real target this call just resolved, supersedes it.
        usbAudioManager.reportPlaybackRoute(
            UsbAudioManager.PlaybackRoute.Direct(
                bitDepth = target.bitResolution,
                sampleRateHz = config.sampleRate,
                channels = channels))
        // ensureFormatActive() just claimed the interface (if it wasn't already) purely to select
        // the alt-setting and refresh the control-state UI reads (volume range, bit-perfect
        // capability) -- getAudioOutput() itself is called whenever DefaultAudioSink decides it
        // needs a new AudioOutput, which includes merely preparing a track (e.g. opening the Now
        // Playing screen), well before play() is ever pressed. The UsbAudioOutput constructed
        // below no longer claims or streams anything on its own (see where its init{} used to be)
        // -- so without this, that claim here would be the only thing left locking the DAC's
        // interface away from Android's kernel driver for however long nothing plays. Letting it
        // go after the normal grace period costs nothing if play() does follow shortly after:
        // UsbAudioOutput.activateAndStream()'s own requestClaim() call cancels this pending
        // release and does the real relock at that point either way.
        usbAudioManager.releaseClaim()
        // Passes usbSettings itself, not usbSettings.volumeDb -- UsbAudioOutput.
        // restoreVolumeAfterRelock() reads it fresh on every relock, not just once here. This
        // provider (and the DefaultAudioSink built from it) is cached and reused for an entire DAC
        // session (see UsbAudioManager.activeSink), while getAudioOutput() itself only gets called
        // again for a track whose format actually differs -- so a one-time read here, like this
        // call used to pass, would go stale the moment a volume-key change happened mid-track with
        // no format change in between, see UsbAudioOutput.restoreVolumeAfterRelock's own doc
        // comment for the confirmed bug this caused.
        return UsbAudioOutput(session, target, channels, config.sampleRate, usbSettings, isFloatInput, usbAudioManager)
    }

    override fun addListener(listener: AudioOutputProvider.Listener) {}

    override fun removeListener(listener: AudioOutputProvider.Listener) {}

    override fun release() {}
}
