/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioDescriptorParserUac2.kt is part of libusbdac.
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

import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.AC2_CLOCK_SOURCE
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
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.FORMAT_BIT_PCM
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.RAW_DATA_BYTE_MASK
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.TERMINAL_USB_STREAMING
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.UAC2_PROTOCOL
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.recordPendingFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper.walk
import android.hardware.usb.UsbConstants
import timber.log.Timber as L

/**
 * Same idea as [UsbAudioDescriptorParserUac3.parseUac3Playback], for UAC 2.0 devices -- most
 * real-world USB DACs (this was discovered testing against one: a FiiO Snowsky Tiny A,
 * bInterfaceProtocol 0x20, which the UAC-3.0-only parser found nothing at all for). Confirmed
 * against the Linux kernel's audio-v2.h rather than derived from the spec alone, same as the
 * UAC3 parser's approach.
 *
 * The main structural differences from UAC 3.0: every AC unit subtype number shifts down by one
 * slot (UAC 3.0 inserted EXTENDED_TERMINAL at 0x04 -- see [UsbAudioDescriptorHelper]'s AC2_*
 * constants, shared with UAC 1.0), there's no Power Domain concept at all, and -- the one that
 * actually matters for parsing -- UAC 2.0's AS_GENERAL descriptor doesn't carry bit-resolution/
 * subslot-size inline; those come from a separate FORMAT_TYPE descriptor that follows it in the
 * same interface block. It does carry bNrChannels directly on the Input Terminal, though, which
 * UAC 3.0 can't (that's pushed into a separate Cluster Descriptor fetch) -- meaning
 * mixerInputChannelBase can be computed exactly here instead of only for a Mixer Unit's first
 * input pin.
 */
internal object UsbAudioDescriptorParserUac2 {

    fun parseUac2Playback(configBytes: ByteArray): UacPlaybackTopology? = parseUac2Playback(walk(configBytes))

    /** [entries]-taking overload of [parseUac2Playback] -- see [UsbAudioDescriptorHelper.walk]'s doc comment for why this exists. */
    fun parseUac2Playback(entries: List<Pair<Int, ByteArray>>): UacPlaybackTopology? {

        var configurationValue = 0
        var currentInterfaceNumber = -1
        var currentAltSetting = -1
        var currentInterfaceClass = -1
        var currentInterfaceSubClass = -1
        var currentInterfaceProtocol = -1

        var controlInterfaceNumber = -1
        var streamingInterfaceNumber = -1
        var clockSourceId = -1
        var usbStreamingTerminalId = -1
        var outputFeatureUnitId = -1
        var mixerUnitId = -1
        var mixerInputChannelBase = -1

        // Channel count of every Input Terminal seen so far, by terminal id -- used to compute
        // the exact base channel number for whichever pin on the Mixer Unit our USB terminal
        // occupies (summing the channel counts of every preceding pin's source).
        val terminalChannelCounts = mutableMapOf<Int, Int>()

        val formatsByInterface = mutableMapOf<Int, MutableList<UacStreamFormat>>()
        val nonPcmFormatsByInterface = mutableMapOf<Int, MutableList<UacNonPcmFormat>>()
        var pendingAltSetting = -1
        var pendingEndpointAddress = -1
        var pendingEndpointSync = -1
        var pendingMaxPacketSize = -1
        var pendingBitResolution = -1
        var pendingSubslotSize = -1
        var isPendingTerminalLinked = false
        // Set from AS_GENERAL's bmFormats, independently of isPendingTerminalLinked (which only
        // tracks whether this alt setting's AS_GENERAL is linked to our USB streaming terminal --
        // streamingInterfaceNumber still needs to resolve correctly even on a device whose only
        // alt settings happen to be non-PCM). Gates AS2_FORMAT_TYPE below instead, so a non-PCM
        // alt setting's bSubslotSize/bBitResolution never get recorded, and flushPendingFormat's
        // existing pendingBitResolution > 0 check then naturally excludes it from formatsByInterface.
        var pendingFormatIsPcm = false
        // See UsbAudioDescriptorHelper.RAW_DATA_BYTE_MASK's doc comment -- only meaningful when
        // !pendingFormatIsPcm.
        var pendingIsRawData = false

        fun flushPendingFormat(interfaceNumber: Int) {
            recordPendingFormat(
                interfaceNumber = interfaceNumber,
                altSetting = pendingAltSetting,
                endpointAddress = pendingEndpointAddress,
                endpointSync = pendingEndpointSync,
                maxPacketSize = pendingMaxPacketSize,
                bitResolution = pendingBitResolution,
                subslotSize = pendingSubslotSize,
                clusterDescriptorId = -1,
                isTerminalLinked = isPendingTerminalLinked,
                isDeclaredPcm = pendingFormatIsPcm,
                isRawData = pendingIsRawData,
                formatsByInterface = formatsByInterface,
                nonPcmFormatsByInterface = nonPcmFormatsByInterface)
            pendingAltSetting = -1
            pendingEndpointAddress = -1
            pendingEndpointSync = -1
            pendingMaxPacketSize = -1
            pendingBitResolution = -1
            pendingSubslotSize = -1
            isPendingTerminalLinked = false
            pendingFormatIsPcm = false
            pendingIsRawData = false
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
                    if (currentInterfaceProtocol == UAC2_PROTOCOL && currentInterfaceSubClass == AS_SUBCLASS) {
                        pendingAltSetting = currentAltSetting
                    }
                }
                DESC_TYPE_CS_INTERFACE -> {
                    if (currentInterfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
                    val subtype = bytes.u8(2)
                    if (currentInterfaceSubClass == AC_SUBCLASS && currentInterfaceProtocol == UAC2_PROTOCOL) {
                        controlInterfaceNumber = currentInterfaceNumber
                        when (subtype) {
                            AC_INPUT_TERMINAL -> {
                                val terminalId = bytes.u8(3)
                                val terminalType = bytes.u16(4)
                                terminalChannelCounts[terminalId] = bytes.u8(8) // bNrChannels
                                if (terminalType == TERMINAL_USB_STREAMING) {
                                    usbStreamingTerminalId = terminalId
                                }
                            }
                            AC2_CLOCK_SOURCE -> {
                                if (clockSourceId < 0) clockSourceId = bytes.u8(3)
                            }
                            AC_OUTPUT_TERMINAL -> {
                                val terminalType = bytes.u16(4)
                                if (terminalType != TERMINAL_USB_STREAMING && outputFeatureUnitId < 0) {
                                    outputFeatureUnitId = bytes.u8(7) // bSourceID
                                }
                            }
                            AC2_MIXER_UNIT -> {
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
                        currentInterfaceProtocol == UAC2_PROTOCOL) {
                        when (subtype) {
                            AS_GENERAL -> {
                                val terminalLink = bytes.u8(3)
                                isPendingTerminalLinked = terminalLink == usbStreamingTerminalId
                                if (isPendingTerminalLinked) {
                                    streamingInterfaceNumber = currentInterfaceNumber
                                }
                                // bmFormats at offset 6 (bLength/type/subtype/terminalLink/
                                // bmControls/bFormatType each 1 byte, then this 4-byte bitmap) --
                                // see UsbAudioDescriptorHelper.FORMAT_BIT_PCM's doc comment.
                                pendingFormatIsPcm = (bytes.u8(6) and FORMAT_BIT_PCM) != 0
                                // Bit 31 is the bitmap's 4th byte (offset 6+3=9) -- see
                                // UsbAudioDescriptorHelper.RAW_DATA_BYTE_MASK's doc comment.
                                pendingIsRawData = (bytes.u8(9) and RAW_DATA_BYTE_MASK) != 0
                            }
                            AS2_FORMAT_TYPE -> {
                                if (isPendingTerminalLinked && pendingFormatIsPcm) {
                                    pendingSubslotSize = bytes.u8(4) // bSubslotSize
                                    pendingBitResolution = bytes.u8(5) // bBitResolution
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

        if (controlInterfaceNumber < 0 || streamingInterfaceNumber < 0 || clockSourceId < 0) {
            L.d(
                "No UAC2 playback topology in this configuration " +
                    "(control=$controlInterfaceNumber streaming=$streamingInterfaceNumber clock=$clockSourceId)")
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
            uacVersion = 2,
            clockSourceId = clockSourceId,
            outputFeatureUnitId = outputFeatureUnitId,
            mixerUnitId = mixerUnitId,
            mixerInputChannelBase = mixerInputChannelBase,
            powerDomainId = -1,
            powerDomainD1RecoveryUs = 0,
            powerDomainD2RecoveryUs = 0,
            channelCount = terminalChannelCounts[usbStreamingTerminalId] ?: -1,
            formats = formats,
            nonPcmFormats = nonPcmFormatsByInterface[streamingInterfaceNumber].orEmpty())
    }
}
