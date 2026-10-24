/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioDescriptorHelper.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.protocol

import org.ostrichbat2k.usbdac.activation.UsbDacActivatorImpl
import android.hardware.usb.UsbDeviceConnection
import timber.log.Timber as L

/**
 * Code and constants genuinely shared by two or more of [UsbAudioDescriptorParserUac1],
 * [UsbAudioDescriptorParserUac2], and [UsbAudioDescriptorParserUac3] -- descriptor-TLV walking,
 * byte-reading, and the handful of subtype/bitmap numbers that mean the same thing (or, for the
 * UAC 1.0/2.0 AC subtype numbers, the literal same number) across more than one of those parsers.
 * Each parser's own version-specific subtype numbers (e.g. UAC 3.0's unit/terminal subtypes, which
 * shift by one slot relative to UAC 1.0/2.0) stay declared in that parser's own file instead.
 */
internal object UsbAudioDescriptorHelper {

    const val DESC_TYPE_CONFIGURATION = 0x02
    const val DESC_TYPE_INTERFACE = 0x04
    const val DESC_TYPE_ENDPOINT = 0x05
    const val DESC_TYPE_CS_INTERFACE = 0x24

    const val AC_SUBCLASS = 1
    const val AS_SUBCLASS = 2
    const val UAC3_PROTOCOL = 0x30
    const val UAC2_PROTOCOL = 0x20
    // UAC 1.0 predates the bInterfaceProtocol field being used to carry a version at all --
    // devices declare 0x00 here (confirmed against the Linux kernel's UAC_VERSION_1 in
    // include/linux/usb/audio.h, and against a real device: a generic "AB13X USB Audio" dongle).
    const val UAC1_PROTOCOL = 0x00

    // AudioControl class-specific interface descriptor subtypes shared by all three UAC
    // versions -- unlike the unit subtypes below, Input/Output Terminal never shifts slot.
    const val AC_INPUT_TERMINAL = 0x02
    const val AC_OUTPUT_TERMINAL = 0x03

    // AudioControl class-specific interface descriptor subtypes shared by UAC 1.0 and UAC 2.0
    // (confirmed against the Linux kernel's include/linux/usb/audio-v2.h) -- UAC 1.0 reuses these
    // same numbers rather than defining its own, which is why UsbAudioDescriptorParserUac1 matches
    // against them too instead of declaring its own copies. UAC 3.0 shifts all of these down by
    // one slot (e.g. FEATURE_UNIT is 7, not 6) since it inserted EXTENDED_TERMINAL -- see
    // UsbAudioDescriptorParserUac3's own AC_* constants for those.
    const val AC2_MIXER_UNIT = 0x04
    const val AC2_SELECTOR_UNIT = 0x05
    const val AC2_FEATURE_UNIT = 0x06
    const val AC2_CLOCK_SOURCE = 0x0A

    // AudioStreaming class-specific interface descriptor subtype, same number across all three
    // UAC versions. UAC 3.0 folds format info directly into AS_GENERAL rather than a separate
    // FORMAT_TYPE descriptor; UAC 1.0/2.0's shorter AS_GENERAL carries only a format bitmap, with
    // the actual PCM parameters coming from the FORMAT_TYPE descriptor that follows it.
    const val AS_GENERAL = 0x01
    // UAC 1.0/2.0's FORMAT_TYPE descriptor subtype (same number in both -- UAC 3.0 has no
    // equivalent of its own since it doesn't use a separate FORMAT_TYPE descriptor at all).
    const val AS2_FORMAT_TYPE = 0x02

    // bmFormats bit 0 (UAC2 Table A-19, UAC3 Table A-13): Type I PCM. Only the field's lowest
    // byte needs reading to test it, regardless of whether the field itself is 4 bytes (UAC2) or
    // 8 (UAC3), since bit 0 always lives in the first byte of a little-endian bitmap. Confirmed
    // necessary against a real device (FiiO Snowsky Tiny A): its 4th alt setting is raw bitstream
    // DSD (bmFormats=0x80000000) but otherwise parses byte-for-byte identically to an ordinary
    // 32-bit PCM alt setting -- same bFormatType, same bSubslotSize/bBitResolution in the
    // FORMAT_TYPE descriptor that follows. bmFormats is the only field that actually
    // distinguishes them; without checking it, a PCM track's format matcher could tie-break onto
    // the DSD alt setting and stream ordinary linear PCM into an endpoint that only accepts a raw
    // DSD bitstream.
    const val FORMAT_BIT_PCM = 0x01

    // bmFormats bit 31 (UAC2 Table A-19 "TYPE_I_RAW_DATA"; same bit position assumed to carry
    // over into UAC3's wider bitmap, though that specific assumption is unverified against real
    // UAC3 hardware -- no UAC3 DSD device has been seen, only the UAC2 Snowsky Tiny A below). This
    // is the real-world convention a device uses to say "this alt setting's bytes aren't meant to
    // be interpreted as linear PCM at all" -- confirmed on the Snowsky Tiny A, whose DSD alt
    // setting declares bmFormats=0x80000000 (bit 31 set, nothing else). Bit 31 lives in the 4th
    // byte of the little-endian bitmap, at bit position 7 of that byte.
    const val RAW_DATA_BYTE_MASK = 0x80

    const val TERMINAL_USB_STREAMING = 0x0101

    // USB Endpoint Descriptor bmAttributes synchronization-type field (bits 3:2).
    const val SYNC_TYPE_ASYNC = 0x1

    /**
     * Shared by [UsbAudioDescriptorParserUac2.parseUac2Playback] and
     * [UsbAudioDescriptorParserUac3.parseUac3Playback]'s own `flushPendingFormat` closures: decides,
     * from one alt setting's accumulated pending descriptor fields, whether it's a real selectable
     * PCM format, a real non-PCM (e.g. DSD) alt setting on our own streaming interface (see
     * [UacNonPcmFormat]), or neither -- and appends the right constructed object into whichever of
     * [formatsByInterface]/[nonPcmFormatsByInterface] applies. The two parsers' pending-state
     * variables themselves stay local to each (different descriptor layouts feed them), only this
     * decide-and-construct step was ever near-identical between the two.
     *
     * [isDeclaredPcm] lets UAC2's caller preserve its own extra guard against a malformed alt
     * setting whose AS_GENERAL declared PCM (bmFormats) but whose AS_FORMAT_TYPE never actually
     * arrived to supply a bit resolution -- without it, that incomplete record would otherwise be
     * wrongly recorded as non-PCM here instead of dropped entirely. UAC3's caller has no such flag
     * to track (it has nothing else from the walk to set it from) and passes `false` always, which
     * leaves its own non-PCM condition exactly as unrestricted as before this extraction.
     */
    fun recordPendingFormat(
        interfaceNumber: Int,
        altSetting: Int,
        endpointAddress: Int,
        endpointSync: Int,
        maxPacketSize: Int,
        bitResolution: Int,
        subslotSize: Int,
        clusterDescriptorId: Int,
        isTerminalLinked: Boolean,
        isDeclaredPcm: Boolean,
        isRawData: Boolean,
        formatsByInterface: MutableMap<Int, MutableList<UacStreamFormat>>,
        nonPcmFormatsByInterface: MutableMap<Int, MutableList<UacNonPcmFormat>>,
    ) {
        if (altSetting < 0 || endpointAddress < 0) return
        if (bitResolution > 0) {
            formatsByInterface
                .getOrPut(interfaceNumber) { mutableListOf() }
                .add(
                    UacStreamFormat(
                        alternateSetting = altSetting,
                        bitResolution = bitResolution,
                        subslotSize = subslotSize,
                        clusterDescriptorId = clusterDescriptorId,
                        endpointAddress = endpointAddress,
                        maxPacketSize = maxPacketSize,
                        isAsync = endpointSync == SYNC_TYPE_ASYNC))
        } else if (isTerminalLinked && !isDeclaredPcm) {
            // A real alt setting on our own streaming interface (not some other, e.g. capture,
            // interface that merely shares this AudioStreaming subclass/protocol) -- just not one
            // this codebase will ever select for playback. See UacNonPcmFormat.
            nonPcmFormatsByInterface
                .getOrPut(interfaceNumber) { mutableListOf() }
                .add(
                    UacNonPcmFormat(
                        alternateSetting = altSetting,
                        endpointAddress = endpointAddress,
                        maxPacketSize = maxPacketSize,
                        isRawData = isRawData))
        }
    }

    /** Fetches a full configuration descriptor (standard + class-specific) by its 0-based index. */
    fun fetchConfigurationDescriptor(connection: UsbDeviceConnection, configIndex: Int): ByteArray? {
        val header = ByteArray(9)
        val headerLen =
            connection.controlTransfer(
                0x80, 0x06, (DESC_TYPE_CONFIGURATION shl 8) or configIndex, 0, header, header.size, 1000)
        if (headerLen < 4) {
            L.w("Failed to read configuration descriptor header (index $configIndex): $headerLen")
            return null
        }
        val totalLength = header.u16(2)
        val full = ByteArray(totalLength)
        val fullLen =
            connection.controlTransfer(
                0x80, 0x06, (DESC_TYPE_CONFIGURATION shl 8) or configIndex, 0, full, full.size, 2000)
        if (fullLen != totalLength) {
            L.w("Short read on configuration descriptor $configIndex: got $fullLen, wanted $totalLength")
        }
        return if (fullLen > 0) full.copyOf(fullLen) else null
    }

    /**
     * Splits a raw configuration descriptor into its sequential (type, bytes) TLV entries.
     * Public (within the module) so a caller walking the same buffer through more than one
     * parser (see [UsbDacActivatorImpl], which tries
     * [UsbAudioDescriptorParserUac3.parseUac3Playback]/[UsbAudioDescriptorParserUac2.parseUac2Playback]/
     * [UsbAudioDescriptorParserUac1.parseUac1Playback] plus
     * [UsbAudioDescriptorParserUac3.dumpAcTopology] against the same bytes) can walk once and
     * pass the result to each function's `entries`-taking overload instead -- walking is a full
     * linear pass over the buffer, and every one of those functions would otherwise redo it
     * independently.
     */
    fun walk(data: ByteArray): List<Pair<Int, ByteArray>> {
        val entries = mutableListOf<Pair<Int, ByteArray>>()
        var offset = 0
        while (offset + 2 <= data.size) {
            val length = data.u8(offset)
            if (length < 2 || offset + length > data.size) break
            val type = data.u8(offset + 1)
            entries.add(type to data.copyOfRange(offset, offset + length))
            offset += length
        }
        return entries
    }
}

internal fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF

internal fun ByteArray.u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
