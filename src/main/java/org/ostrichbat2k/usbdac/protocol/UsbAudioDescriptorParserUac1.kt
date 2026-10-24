/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioDescriptorParserUac1.kt is part of libusbdac.
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

import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.AC2_MIXER_UNIT
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.AC_INPUT_TERMINAL
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.AC_OUTPUT_TERMINAL
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.AC_SUBCLASS
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.AS2_FORMAT_TYPE
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.AS_GENERAL
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.AS_SUBCLASS
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.DESC_TYPE_CONFIGURATION
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.DESC_TYPE_CS_INTERFACE
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.DESC_TYPE_ENDPOINT
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.DESC_TYPE_INTERFACE
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.SYNC_TYPE_ASYNC
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.TERMINAL_USB_STREAMING
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.UAC1_PROTOCOL
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.walk
import android.hardware.usb.UsbConstants
import timber.log.Timber as L

/**
 * Same idea as [UsbAudioDescriptorParserUac2.parseUac2Playback]/[UsbAudioDescriptorParserUac3.parseUac3Playback],
 * for UAC 1.0 devices -- the protocol most generic/cheap USB audio dongles still use (confirmed
 * against a real one: a no-name "AB13X USB Audio" adapter, bInterfaceProtocol 0x00, which
 * neither of the other two parsers found anything on). Byte offsets confirmed against the Linux
 * kernel's include/linux/usb/audio.h (uac_input_terminal_descriptor, uac_feature_unit_descriptor,
 * uac_mixer_unit_descriptor, uac1_as_header_descriptor, uac_format_type_i_discrete_descriptor),
 * same approach as the other two parsers.
 *
 * The structural differences that actually matter here: UAC 1.0's unit AC subtype numbers are
 * identical to UAC 2.0's (reusing [UsbAudioDescriptorHelper]'s AC2_MIXER_UNIT/AC2_FEATURE_UNIT is
 * intentional, not a copy-paste shortcut), but there is no Clock Source entity at all -- no
 * [UacPlaybackTopology.clockSourceId] concept exists in UAC 1.0. Instead, each format's
 * FORMAT_TYPE descriptor declares its own supported sample rate(s) directly (collected into
 * [UacStreamFormat.sampleRates]), and the *endpoint* itself -- not an AudioControl entity --
 * is what a Sampling Frequency Control SET_CUR targets once that format's alternate setting is
 * selected. (Only the common discrete-rate-list form of FORMAT_TYPE is handled; the rarer
 * continuous-range form, bSamFreqType=0, is skipped.)
 */
internal object UsbAudioDescriptorParserUac1 {

    fun parseUac1Playback(configBytes: ByteArray): UacPlaybackTopology? = parseUac1Playback(walk(configBytes))

    /** [entries]-taking overload of [parseUac1Playback] -- see [UsbAudioDescriptorHelper.walk]'s doc comment for why this exists. */
    fun parseUac1Playback(entries: List<Pair<Int, ByteArray>>): UacPlaybackTopology? {

        var configurationValue = 0
        var currentInterfaceNumber = -1
        var currentAltSetting = -1
        var currentInterfaceClass = -1
        var currentInterfaceSubClass = -1
        var currentInterfaceProtocol = -1

        var controlInterfaceNumber = -1
        var streamingInterfaceNumber = -1
        var usbStreamingTerminalId = -1
        var outputFeatureUnitId = -1
        var mixerUnitId = -1
        var mixerInputChannelBase = -1

        val terminalChannelCounts = mutableMapOf<Int, Int>()

        val formatsByInterface = mutableMapOf<Int, MutableList<UacStreamFormat>>()
        var pendingAltSetting = -1
        var pendingEndpointAddress = -1
        var pendingEndpointSync = -1
        var pendingMaxPacketSize = -1
        var pendingBitResolution = -1
        var pendingSubslotSize = -1
        var pendingSampleRates = emptyList<Int>()
        var isPendingTerminalLinked = false

        fun flushPendingFormat(interfaceNumber: Int) {
            if (pendingAltSetting >= 0 && pendingEndpointAddress >= 0 && pendingBitResolution > 0) {
                formatsByInterface
                    .getOrPut(interfaceNumber) { mutableListOf() }
                    .add(
                        UacStreamFormat(
                            alternateSetting = pendingAltSetting,
                            bitResolution = pendingBitResolution,
                            subslotSize = pendingSubslotSize,
                            clusterDescriptorId = -1,
                            endpointAddress = pendingEndpointAddress,
                            maxPacketSize = pendingMaxPacketSize,
                            isAsync = pendingEndpointSync == SYNC_TYPE_ASYNC,
                            sampleRates = pendingSampleRates))
            }
            pendingAltSetting = -1
            pendingEndpointAddress = -1
            pendingEndpointSync = -1
            pendingMaxPacketSize = -1
            pendingBitResolution = -1
            pendingSubslotSize = -1
            pendingSampleRates = emptyList()
            isPendingTerminalLinked = false
        }

        for ((type, bytes) in entries) {
            when (type) {
                DESC_TYPE_CONFIGURATION -> {
                    configurationValue = bytes.u8(5)
                }
                DESC_TYPE_INTERFACE -> {
                    if (currentInterfaceNumber == streamingInterfaceNumber) {
                        flushPendingFormat(currentInterfaceNumber)
                    }
                    currentInterfaceNumber = bytes.u8(2)
                    currentAltSetting = bytes.u8(3)
                    currentInterfaceClass = bytes.u8(5)
                    currentInterfaceSubClass = bytes.u8(6)
                    currentInterfaceProtocol = bytes.u8(7)
                    if (currentInterfaceProtocol == UAC1_PROTOCOL && currentInterfaceSubClass == AS_SUBCLASS) {
                        pendingAltSetting = currentAltSetting
                    }
                }
                DESC_TYPE_CS_INTERFACE -> {
                    if (currentInterfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
                    val subtype = bytes.u8(2)
                    if (currentInterfaceSubClass == AC_SUBCLASS && currentInterfaceProtocol == UAC1_PROTOCOL) {
                        controlInterfaceNumber = currentInterfaceNumber
                        when (subtype) {
                            AC_INPUT_TERMINAL -> {
                                val terminalId = bytes.u8(3)
                                val terminalType = bytes.u16(4)
                                // UAC 1.0's Input Terminal carries bNrChannels at offset 7, one
                                // byte earlier than UAC 2.0's offset 8 -- UAC 2.0 inserted a new
                                // bCSourceID field (linking the terminal to its Clock Source) at
                                // offset 7, pushing everything after it down by one byte.
                                terminalChannelCounts[terminalId] = bytes.u8(7) // bNrChannels
                                if (terminalType == TERMINAL_USB_STREAMING) {
                                    usbStreamingTerminalId = terminalId
                                }
                            }
                            AC_OUTPUT_TERMINAL -> {
                                val terminalType = bytes.u16(4)
                                if (terminalType != TERMINAL_USB_STREAMING && outputFeatureUnitId < 0) {
                                    outputFeatureUnitId = bytes.u8(7) // bSourceID
                                }
                            }
                            AC2_MIXER_UNIT -> { // UAC 1.0 reuses UAC 2.0's Mixer Unit subtype (0x04)
                                val nrIn = bytes.u8(4)
                                val sources = (0 until nrIn).map { bytes.u8(5 + it) }
                                val pin = sources.indexOf(usbStreamingTerminalId)
                                if (pin >= 0) {
                                    mixerUnitId = bytes.u8(3)
                                    mixerInputChannelBase =
                                        1 + sources.take(pin).sumOf { terminalChannelCounts[it] ?: 0 }
                                }
                            }
                        }
                    } else if (currentInterfaceSubClass == AS_SUBCLASS &&
                        currentInterfaceProtocol == UAC1_PROTOCOL) {
                        when (subtype) {
                            AS_GENERAL -> {
                                val terminalLink = bytes.u8(3)
                                isPendingTerminalLinked = terminalLink == usbStreamingTerminalId
                                if (isPendingTerminalLinked) {
                                    streamingInterfaceNumber = currentInterfaceNumber
                                }
                            }
                            AS2_FORMAT_TYPE -> { // same subtype number (0x02) as UAC 2.0's FORMAT_TYPE
                                if (isPendingTerminalLinked) {
                                    pendingSubslotSize = bytes.u8(5) // bSubframeSize
                                    pendingBitResolution = bytes.u8(6) // bBitResolution
                                    val sampleRateCount = bytes.u8(7) // bSamFreqType
                                    if (sampleRateCount > 0) {
                                        // Discrete list: bSamFreqType 3-byte little-endian
                                        // entries starting at offset 8. (bSamFreqType == 0 would
                                        // mean a continuous min/max range instead -- not handled.)
                                        pendingSampleRates =
                                            (0 until sampleRateCount).map { i ->
                                                val off = 8 + i * 3
                                                bytes.u8(off) or (bytes.u8(off + 1) shl 8) or
                                                    (bytes.u8(off + 2) shl 16)
                                            }
                                    }
                                }
                            }
                        }
                    }
                }
                DESC_TYPE_ENDPOINT -> {
                    if (currentInterfaceNumber == streamingInterfaceNumber) {
                        pendingEndpointAddress = bytes.u8(2)
                        val attributes = bytes.u8(3)
                        pendingEndpointSync = (attributes shr 2) and 0x3
                        pendingMaxPacketSize = bytes.u16(4)
                    }
                }
            }
        }
        flushPendingFormat(currentInterfaceNumber)

        if (controlInterfaceNumber < 0 || streamingInterfaceNumber < 0) {
            L.d(
                "No UAC1 playback topology in this configuration " +
                    "(control=$controlInterfaceNumber streaming=$streamingInterfaceNumber)")
            return null
        }

        val formats = formatsByInterface[streamingInterfaceNumber].orEmpty()
        if (formats.isEmpty()) {
            L.w("No streaming formats found for interface $streamingInterfaceNumber")
            return null
        }

        return UacPlaybackTopology(
            configurationValue = configurationValue,
            controlInterfaceNumber = controlInterfaceNumber,
            streamingInterfaceNumber = streamingInterfaceNumber,
            uacVersion = 1,
            clockSourceId = -1,
            outputFeatureUnitId = outputFeatureUnitId,
            mixerUnitId = mixerUnitId,
            mixerInputChannelBase = mixerInputChannelBase,
            powerDomainId = -1,
            powerDomainD1RecoveryUs = 0,
            powerDomainD2RecoveryUs = 0,
            channelCount = terminalChannelCounts[usbStreamingTerminalId] ?: -1,
            formats = formats)
    }
}
