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
import java.util.IdentityHashMap
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
    // The host app's own, decoder-specific way to tell us a track's real source bit depth, when
    // it's narrower than the container Format.pcmEncoding reports (#67) -- this library has no
    // dependency on whatever decoder produced the track (FFmpeg or otherwise) and never will, so
    // it can't read that off the Format itself. Auxio's FfmpegAudioDecoder-based pipeline can:
    // it already depends on decoder_ffmpeg directly and reads androidx.media3.decoder.ffmpeg.
    // RawSampleBitsData off format.metadata there, then hands us back just the plain Int this
    // callback returns -- so no reflection and no new cross-repo dependency either way. Returns
    // null (the default) when the real depth is unknown or equals the container width, i.e.
    // nothing to narrow.
    private val realBitsPerSample: (androidx.media3.common.Format) -> Int? = { null },
) : AudioOutputProvider {

    private companion object {
        // Fallback only -- used when a format's own supported rates couldn't be read back at all
        // (see acceptedSampleRates). Not a hardcoded assumption applied unconditionally.
        val FALLBACK_SAMPLE_RATES = setOf(44100, 48000)

        // The widest real source depth a 4-byte-container sample can still losslessly narrow down
        // to a 3-byte device format by dropping its bottom byte -- see
        // isPaddedTwentyFourBitContent's doc comment for why 24, not e.g. 25.
        const val MAX_PADDABLE_BITS = 24

        // See paddedConfigs' own doc comment -- this bounds its memory, not real concurrent
        // track-switch depth (which the "upcoming track can run ahead" case this field already
        // documents only ever needs 2 of at once in practice).
        const val PADDED_CONFIGS_CAPACITY = 8
    }

    // Bridges getOutputConfig()'s knowledge of a specific track's real Format (and therefore
    // whether UsbAudioOutput will need UsbAudioOutput.InputConversion.DROP_PADDING_BYTE) through
    // to getAudioOutput(), which only ever receives the resolved OutputConfig -- by then,
    // config.encoding == C.ENCODING_PCM_32BIT is ambiguous between "genuinely 32-bit" and
    // "24-bit padded into a 32-bit container" (#67), and DefaultAudioSink defers building the
    // AudioOutput until later (see getAudioOutput's own doc comment on usbAudioManager.
    // releaseClaim's call site), so this can't just be "the most recently seen format" -- an
    // upcoming track's getOutputConfig() can run ahead of an earlier one's getAudioOutput().
    // Keyed by identity on the exact OutputConfig instance DefaultAudioSink round-trips
    // unchanged (confirmed by reading its own source: getOutputConfig()'s return value is what's
    // later passed into getAudioOutput() as-is).
    //
    // Non-destructive lookup, bounded by eviction order instead of removing an entry the first
    // time it's consumed -- confirmed, reproduced on real hardware: seeking within a track calls
    // DefaultAudioSink.flush() directly (not reconfigureAndFlush(), which is the only path that
    // calls getOutputConfig() again), so the *same* OutputConfig instance from before the seek
    // gets handed to getAudioOutput() a second time once the sink rebuilds its AudioOutput. A
    // remove-on-consume design (the original approach here) finds nothing on that second call --
    // already removed by the first -- and silently falls back to the genuinely-32-bit alt-setting
    // for what's still actually padded 24-bit content. PADDED_CONFIGS_CAPACITY bounds memory
    // instead: in practice at most a couple of tracks' configs are ever in flight at once (see
    // this comment's own "upcoming track can run ahead" note above), so this is generous headroom
    // not a real behavioral limit.
    private val paddedConfigs = IdentityHashMap<AudioOutputProvider.OutputConfig, Boolean>()
    private val paddedConfigsOrder = ArrayDeque<AudioOutputProvider.OutputConfig>()

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
     * The best integer device format to convert wide (>16-bit) PCM down to -- used for *float*
     * input, and (since #67) for literal-32-bit input that's actually real ≤24-bit content
     * padded into that container (see [isPaddedTwentyFourBitContent]). Before `media#4`/`#6`
     * landed, float was the *only* way wide content could ever reach this provider at all:
     * `DefaultAudioSink.configure()` used to unconditionally funnel every `MimeTypes.AUDIO_RAW`
     * input through exactly one of two fixed internal processors -- `ToInt16PcmAudioProcessor`
     * or `ToFloatPcmAudioProcessor` -- *before* this provider ever saw the format, never asking
     * it first whether it could take the real encoding directly (confirmed by reading
     * `DefaultAudioSink.configure()`/`shouldUseFloatOutput()` directly, at the time). So a 32-bit
     * source that `FfmpegAudioDecoder.OUTPUT_MODE_NATIVE_INTEGER` decoded exactly (see that
     * mode's doc comment: FFmpeg's own FLAC/ALAC/PCM decoders pick 16- or 32-bit based on the
     * real source bps) always arrived here as float -- `shouldUseFloatOutput()` was keyed on the
     * *decoder's* reported encoding being wider than 16-bit, and the USB sink was built with
     * `enableFloatOutput = true` specifically so that rewrite landed on float, not a forced
     * 16-bit truncation. `media#4`/`#6` fixed this: `getFormatSupport()`/`configure()` now ask
     * the provider first with the literal encoding, and skip the float/16-bit rewrite entirely
     * when it reports `FORMAT_SUPPORTED_DIRECTLY` -- which is what makes real, unconverted
     * 32-bit integer PCM (and therefore #67's padded-24-in-32 case) reach this provider at all.
     *
     * This picks the device's own *smallest* integer bit depth that's still wide enough to hold
     * real 24-bit content (24-bit when the device offers it, 32-bit only as a fallback): both are
     * an equally lossless, exact round-trip for it (a 24-bit target reconstructs the original
     * integer directly; a 32-bit one reconstructs it scaled up by a fixed power-of-two factor --
     * float32's mantissa represents either exactly, as does a plain padding-byte drop), so
     * there's no fidelity reason to prefer the wider one, only a real cost: 33% more bytes over
     * USB for identical information.
     */
    private fun wideIntTargetFormat(targetChannels: Int, sampleRate: Int): UacStreamFormat? =
        allFormats
            .filter { it.subslotSize >= 3 && targetChannels == channels && sampleRate in acceptedSampleRates(it) }
            .minByOrNull { it.subslotSize }

    /**
     * Whether [format]'s literal (non-float) 32-bit-container encoding is actually real ≤24-bit
     * content left-shift-padded into that container, per [realBitsPerSample] (#67) -- FFmpeg's
     * `libavcodec` has no native 24-bit `AVSampleFormat`, so any source wider than 16 but no
     * wider than 24 real bits still decodes to `AV_SAMPLE_FMT_S32` (ExoPlayer's
     * `C.ENCODING_PCM_32BIT`), with the real value occupying only the top bits and the bottom
     * byte always exactly zero -- see [UsbAudioOutput.InputConversion.DROP_PADDING_BYTE]'s doc
     * comment for why dropping that byte is lossless for any real depth from 1 to 24 bits, not
     * just exactly 24. A genuinely 32-bit source (`realBitsPerSample` returns null, or 25-31,
     * i.e. too wide for a clean byte drop) is deliberately excluded, so it stays on the existing
     * exact-match path below.
     */
    private fun isPaddedTwentyFourBitContent(format: androidx.media3.common.Format): Boolean =
        format.pcmEncoding == C.ENCODING_PCM_32BIT &&
            realBitsPerSample(format)?.let { it in 1..MAX_PADDABLE_BITS } == true

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        val format = formatConfig.format
        val match =
            if (format.pcmEncoding == C.ENCODING_PCM_FLOAT || isPaddedTwentyFourBitContent(format)) {
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
        // (which converts it to whatever wideIntTargetFormat picked); padded-24-in-32 content
        // (#67) similarly stays reported as its own real, literal C.ENCODING_PCM_32BIT -- that's
        // genuinely what DefaultAudioSink will hand write() (dropping the padding byte is
        // UsbAudioOutput's job, once getAudioOutput below resolves the real narrower target, not
        // something DefaultAudioSink's own pipeline does) -- everything else requests the
        // matched device format's own real integer encoding.
        val isPadded = isPaddedTwentyFourBitContent(format)
        val encoding =
            when {
                format.pcmEncoding == C.ENCODING_PCM_FLOAT -> C.ENCODING_PCM_FLOAT
                isPadded -> format.pcmEncoding
                else -> matchingFormat(format)?.let { encodingFor(it.subslotSize) } ?: C.ENCODING_PCM_16BIT
            }
        val channelMask = channelMaskFor(channels)
        val config =
            AudioOutputProvider.OutputConfig.Builder()
                .setEncoding(encoding)
                .setSampleRate(format.sampleRate)
                .setChannelMask(channelMask)
                .setAudioAttributes(formatConfig.audioAttributes)
                .setAudioSessionId(formatConfig.audioSessionId)
                .setVirtualDeviceId(formatConfig.virtualDeviceId)
                .build()
        if (isPadded) {
            paddedConfigs[config] = true
            paddedConfigsOrder.addLast(config)
            if (paddedConfigsOrder.size > PADDED_CONFIGS_CAPACITY) {
                paddedConfigs.remove(paddedConfigsOrder.removeFirst())
            }
        }
        return config
    }

    /**
     * Resolves which device format and [UsbAudioOutput.InputConversion] [getAudioOutput] should
     * use for [config] -- split out purely to keep that function's own cyclomatic complexity
     * under threshold; every side effect (claiming, logging, reporting the route) stays there.
     *
     * [config.encoding][AudioOutputProvider.OutputConfig.encoding] `== C.ENCODING_PCM_32BIT` is
     * ambiguous between "genuinely 32-bit" and "24-bit padded into a 32-bit container" (#67) --
     * [paddedConfigs] (checked here, not consumed: see its own doc comment for why a seek's
     * repeat [getAudioOutput] call for the very same [config] instance needs this flag to still
     * be there) is what [getOutputConfig] left behind to disambiguate it. A padded [config] whose
     * resolved target still comes back 4 bytes wide means [wideIntTargetFormat]'s own `>= 3`
     * filter found no alt-setting narrower than the literal container already matches -- i.e. the
     * device has no 24-bit alt-setting at all, so this is routed to the same 32-bit alt-setting a
     * genuinely-32-bit track would use anyway (still exactly lossless, just not bandwidth-
     * optimal), and [UsbAudioOutput] has nothing to drop: the real bytes it receives already
     * exactly match that target's own 4-byte container.
     */
    private fun resolveTarget(
        config: AudioOutputProvider.OutputConfig,
    ): Pair<UacStreamFormat, UsbAudioOutput.InputConversion> {
        val isFloatInput = config.encoding == C.ENCODING_PCM_FLOAT
        val isPaddedInput = !isFloatInput && paddedConfigs.containsKey(config)
        // Both branches below should be unreachable in practice: getFormatSupport() already
        // gated on exactly this same match existing before DefaultAudioSink ever calls
        // getAudioOutput() for this config. Failing loudly here instead of silently picking an
        // arbitrary device format (bitResolution/subslotSize mismatched against what the caller
        // actually needs) turns a future desync between getFormatSupport()/matchingFormat()/
        // wideIntTargetFormat() into a visible, diagnosable error instead of distorted audio or
        // invalid packet sizing with only a warning log to notice by.
        val target =
            if (isFloatInput || isPaddedInput) {
                wideIntTargetFormat(channels, config.sampleRate)
                    ?: error(
                        "UsbAudioOutputProvider: no narrow-int conversion target at " +
                            "${config.sampleRate}Hz -- getFormatSupport should have reported " +
                            "UNSUPPORTED for this config before getAudioOutput() was ever called")
            } else {
                allFormats.firstOrNull { encodingFor(it.subslotSize) == config.encoding }
                    ?: error(
                        "UsbAudioOutputProvider: no format matches encoding=${config.encoding} " +
                            "-- getFormatSupport should have reported UNSUPPORTED for this " +
                            "config before getAudioOutput() was ever called")
            }
        val conversion =
            when {
                isFloatInput -> UsbAudioOutput.InputConversion.FLOAT_TO_INT
                isPaddedInput && target.subslotSize < 4 -> UsbAudioOutput.InputConversion.DROP_PADDING_BYTE
                else -> UsbAudioOutput.InputConversion.NONE
            }
        return target to conversion
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        // Only called when DefaultAudioSink has already decided the OutputConfig genuinely
        // changed (AudioOutput.canReuseAudioOutput's default contract) -- so this is exactly the
        // point, and the only point, where switching the device's alt-setting is actually needed.
        val (target, conversion) = resolveTarget(config)
        L.i(
            "UsbAudioOutputProvider: activating ${target.bitResolution}-bit/alt=${target.alternateSetting} " +
                "at ${config.sampleRate}Hz for upcoming track" +
                when (conversion) {
                    UsbAudioOutput.InputConversion.FLOAT_TO_INT -> " (converting from float)"
                    UsbAudioOutput.InputConversion.DROP_PADDING_BYTE -> " (dropping 32-bit container padding)"
                    UsbAudioOutput.InputConversion.NONE -> ""
                })
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
        return UsbAudioOutput(session, target, channels, config.sampleRate, usbSettings, conversion, usbAudioManager)
    }

    override fun addListener(listener: AudioOutputProvider.Listener) {}

    override fun removeListener(listener: AudioOutputProvider.Listener) {}

    override fun release() {}
}
