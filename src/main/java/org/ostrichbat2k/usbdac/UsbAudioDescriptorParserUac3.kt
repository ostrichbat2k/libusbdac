/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioDescriptorParserUac3.kt is part of libusbdac.
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

import android.hardware.usb.UsbConstants
import timber.log.Timber as L
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.AC_INPUT_TERMINAL
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.AC_OUTPUT_TERMINAL
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.AC_SUBCLASS
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.AS_GENERAL
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.AS_SUBCLASS
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.DESC_TYPE_CONFIGURATION
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.DESC_TYPE_CS_INTERFACE
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.DESC_TYPE_ENDPOINT
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.DESC_TYPE_INTERFACE
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.FORMAT_BIT_PCM
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.RAW_DATA_BYTE_MASK
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.SYNC_TYPE_ASYNC
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.TERMINAL_USB_STREAMING
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.UAC2_PROTOCOL
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.UAC3_PROTOCOL
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.recordPendingFormat
import org.ostrichbat2k.usbdac.UsbAudioDescriptorHelper.walk

/**
 * Parses raw USB configuration descriptors to extract the USB Audio Class 3.0
 * control/streaming topology.
 *
 * The public android.hardware.usb Java API only models standard descriptors (Interface,
 * Endpoint). The class-specific AudioControl/AudioStreaming descriptors that describe
 * terminals, clocks, and PCM formats are embedded inside the raw configuration descriptor
 * as a flat sequential list and have to be walked manually, matching exactly what a tool
 * like `lsusb -v` does. Byte offsets below were verified against a real UAC 3.0 device
 * (Apple USB-C EarPods) rather than derived from the spec alone.
 */
internal object UsbAudioDescriptorParserUac3 {

    // AudioControl class-specific interface descriptor subtypes (UAC 3.0). These differ from
    // UAC 1.0/2.0's subtype numbers (e.g. FEATURE_UNIT is 6 in UAC2 but 7 in UAC3) -- see
    // UsbAudioDescriptorHelper's AC2_* constants for those. Input/Output Terminal don't shift
    // slot between versions, so those two live in UsbAudioDescriptorHelper instead.
    private const val AC_MIXER_UNIT = 0x05
    private const val AC_SELECTOR_UNIT = 0x06
    private const val AC_FEATURE_UNIT = 0x07
    private const val AC_EFFECT_UNIT = 0x08
    private const val AC_PROCESSING_UNIT = 0x09
    private const val AC_EXTENSION_UNIT = 0x0A
    private const val AC_CLOCK_SOURCE = 0x0B
    private const val AC_CLOCK_SELECTOR = 0x0C
    private const val AC_CLOCK_MULTIPLIER = 0x0D
    private const val AC_POWER_DOMAIN = 0x10

    /**
     * Diagnostic: logs every entity in the UAC 3.0 AudioControl interface's topology graph --
     * not just the Clock Source and single Feature Unit [parseUac3Playback] extracts for
     * playback. That parser's Output Terminal handling *assumes* bSourceID points directly at
     * the Feature Unit that gates audible output ("the last Feature Unit before the physical
     * jack"); this walk verifies that assumption by printing the real id/type/source chain for
     * every unit (Mixer, Selector, Processing, Extension, Feature) and terminal, so a Selector
     * or Mixer Unit sitting between them -- never configured, never even known about -- would
     * show up here instead of silently gating the signal.
     */
    fun dumpAcTopology(configBytes: ByteArray) = dumpAcTopology(walk(configBytes))

    /** [entries]-taking overload of [dumpAcTopology] -- see [UsbAudioDescriptorHelper.walk]'s doc comment for why this exists. */
    fun dumpAcTopology(entries: List<Pair<Int, ByteArray>>) {
        var currentInterfaceNumber = -1
        var currentInterfaceSubClass = -1
        var currentInterfaceProtocol = -1
        for ((type, bytes) in entries) {
            when (type) {
                DESC_TYPE_INTERFACE -> {
                    currentInterfaceNumber = bytes.u8(2)
                    currentInterfaceSubClass = bytes.u8(6)
                    currentInterfaceProtocol = bytes.u8(7)
                }
                DESC_TYPE_CS_INTERFACE -> {
                    if (currentInterfaceSubClass != AC_SUBCLASS ||
                        (currentInterfaceProtocol != UAC3_PROTOCOL && currentInterfaceProtocol != UAC2_PROTOCOL)) {
                        continue
                    }
                    // Subtype numbers below are UAC 3.0's; on a UAC 2.0 interface
                    // (currentInterfaceProtocol == UAC2_PROTOCOL) they're shifted by one slot
                    // (see UsbAudioDescriptorParserUac2's docs), so this falls through to the raw
                    // hex dump for those -- still enough to see the entity graph, just not named.
                    //
                    // That fallthrough must be an explicit protocol check, not just the `else`
                    // branch below: a UAC2 entity's shifted subtype byte can numerically collide
                    // with an unrelated UAC3 constant (e.g. a UAC2 unit landing on UAC3's
                    // AC_SELECTOR_UNIT value), which then misreads that entity's nrInPins-style
                    // count from the wrong byte offset -- confirmed, reproduced on real hardware
                    // (FiiO Snowsky Tiny A, UAC2): read a garbage pin count and walked off the
                    // end of the (correctly-sized) 18-byte descriptor, throwing
                    // ArrayIndexOutOfBoundsException from this diagnostic-only dump and crashing
                    // the whole app, since activateDac() runs this on a background Thread with
                    // no uncaught-exception handler.
                    val subtype = bytes.u8(2)
                    val hex = bytes.joinToString(" ") { "%02x".format(it) }
                    val desc =
                        if (currentInterfaceProtocol != UAC3_PROTOCOL) {
                            "subtype=0x${"%02x".format(subtype)}"
                        } else when (subtype) {
                            AC_INPUT_TERMINAL ->
                                "INPUT_TERMINAL id=${bytes.u8(3)} type=0x${"%04x".format(bytes.u16(4))}"
                            AC_OUTPUT_TERMINAL ->
                                "OUTPUT_TERMINAL id=${bytes.u8(3)} type=0x${"%04x".format(bytes.u16(4))} " +
                                    "bSourceID=${bytes.u8(7)}"
                            AC_MIXER_UNIT -> {
                                val nrIn = bytes.u8(4)
                                "MIXER_UNIT id=${bytes.u8(3)} nrInPins=$nrIn " +
                                    "sources=${(0 until nrIn).map { bytes.u8(5 + it) }}"
                            }
                            AC_SELECTOR_UNIT -> {
                                val nrIn = bytes.u8(4)
                                "SELECTOR_UNIT id=${bytes.u8(3)} nrInPins=$nrIn " +
                                    "sources=${(0 until nrIn).map { bytes.u8(5 + it) }}"
                            }
                            AC_FEATURE_UNIT -> "FEATURE_UNIT id=${bytes.u8(3)} bSourceID=${bytes.u8(4)}"
                            AC_EFFECT_UNIT -> "EFFECT_UNIT id=${bytes.u8(3)} bSourceID=${bytes.u8(6)}"
                            AC_PROCESSING_UNIT -> {
                                val nrIn = bytes.u8(6)
                                "PROCESSING_UNIT id=${bytes.u8(3)} nrInPins=$nrIn " +
                                    "sources=${(0 until nrIn).map { bytes.u8(7 + it) }}"
                            }
                            AC_EXTENSION_UNIT -> {
                                val nrIn = bytes.u8(6)
                                "EXTENSION_UNIT id=${bytes.u8(3)} nrInPins=$nrIn " +
                                    "sources=${(0 until nrIn).map { bytes.u8(7 + it) }}"
                            }
                            AC_CLOCK_SOURCE -> "CLOCK_SOURCE id=${bytes.u8(3)}"
                            AC_CLOCK_SELECTOR -> {
                                val nrIn = bytes.u8(4)
                                "CLOCK_SELECTOR id=${bytes.u8(3)} nrInPins=$nrIn " +
                                    "sources=${(0 until nrIn).map { bytes.u8(5 + it) }}"
                            }
                            AC_CLOCK_MULTIPLIER -> "CLOCK_MULTIPLIER id=${bytes.u8(3)} bCSourceID=${bytes.u8(4)}"
                            AC_POWER_DOMAIN -> {
                                val nrEntities = bytes.u8(8)
                                "POWER_DOMAIN id=${bytes.u8(3)} " +
                                    "d1d0RecoveryUs=${bytes.u16(4) * 50} d2d0RecoveryUs=${bytes.u16(6) * 50} " +
                                    "entities=${(0 until nrEntities).map { bytes.u8(9 + it) }}"
                            }
                            else -> "subtype=0x${"%02x".format(subtype)}"
                        }
                    L.i("AC topology [iface $currentInterfaceNumber]: $desc  (raw: $hex)")
                }
            }
        }
    }

    /**
     * Finds the UAC 3.0 AudioControl interface in [configBytes] (if any) and parses it, together
     * with its linked USB-Streaming-to-output AudioStreaming interface, into a playback topology.
     * Returns null if this configuration isn't a (full, non-BADD) UAC 3.0 one.
     */
    fun parseUac3Playback(configBytes: ByteArray): UacPlaybackTopology? = parseUac3Playback(walk(configBytes))

    /** [entries]-taking overload of [parseUac3Playback] -- see [UsbAudioDescriptorHelper.walk]'s doc comment for why this exists. */
    fun parseUac3Playback(entries: List<Pair<Int, ByteArray>>): UacPlaybackTopology? {
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
        // Power Domains can appear anywhere relative to the entities they cover (in this
        // device's descriptors, they're last), so collect all of them and resolve against
        // usbStreamingTerminalId after the full walk instead of relying on ordering.
        data class PowerDomainInfo(val id: Int, val d1RecoveryUs: Int, val d2RecoveryUs: Int, val entityIds: List<Int>)
        val powerDomains = mutableListOf<PowerDomainInfo>()

        val formatsByInterface = mutableMapOf<Int, MutableList<UacStreamFormat>>()
        val nonPcmFormatsByInterface = mutableMapOf<Int, MutableList<UacNonPcmFormat>>()
        var pendingAltSetting = -1
        var pendingEndpointAddress = -1
        var pendingEndpointSync = -1
        var pendingMaxPacketSize = -1
        var pendingBitResolution = -1
        var pendingSubslotSize = -1
        var pendingClusterId = -1
        // Unlike the UAC2 parser, streamingInterfaceNumber itself only ever gets set inside the
        // terminalLink match below, so this flag is redundant with "streamingInterfaceNumber ==
        // currentInterfaceNumber" for PCM purposes -- but flushPendingFormat runs once more after
        // the whole walk for whatever currentInterfaceNumber was last, so it still needs its own
        // per-alt-setting flag rather than reading the (possibly stale-by-then) outer variable.
        var isPendingTerminalLinked = false
        // See UsbAudioDescriptorHelper.RAW_DATA_BYTE_MASK's doc comment -- only meaningful when
        // the alt setting is not PCM.
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
                clusterDescriptorId = pendingClusterId,
                isTerminalLinked = isPendingTerminalLinked,
                isDeclaredPcm = false,
                isRawData = pendingIsRawData,
                formatsByInterface = formatsByInterface,
                nonPcmFormatsByInterface = nonPcmFormatsByInterface)
            pendingAltSetting = -1
            pendingEndpointAddress = -1
            pendingEndpointSync = -1
            pendingMaxPacketSize = -1
            pendingBitResolution = -1
            pendingSubslotSize = -1
            pendingClusterId = -1
            isPendingTerminalLinked = false
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
                    if (currentInterfaceProtocol == UAC3_PROTOCOL && currentInterfaceSubClass == AS_SUBCLASS) {
                        pendingAltSetting = currentAltSetting
                    }
                }
                DESC_TYPE_CS_INTERFACE -> {
                    if (currentInterfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
                    val subtype = bytes.u8(2)
                    if (currentInterfaceSubClass == AC_SUBCLASS && currentInterfaceProtocol == UAC3_PROTOCOL) {
                        controlInterfaceNumber = currentInterfaceNumber
                        when (subtype) {
                            AC_INPUT_TERMINAL -> {
                                val terminalId = bytes.u8(3)
                                val terminalType = bytes.u16(4)
                                if (terminalType == TERMINAL_USB_STREAMING) {
                                    usbStreamingTerminalId = terminalId
                                }
                            }
                            AC_CLOCK_SOURCE -> {
                                if (clockSourceId < 0) clockSourceId = bytes.u8(3)
                            }
                            AC_OUTPUT_TERMINAL -> {
                                val terminalType = bytes.u16(4)
                                // The real-world (non-USB-Streaming) output terminal's source is
                                // the last Feature Unit before the physical jack -- the one whose
                                // mute/volume state actually gates what's audible.
                                if (terminalType != TERMINAL_USB_STREAMING && outputFeatureUnitId < 0) {
                                    outputFeatureUnitId = bytes.u8(7) // bSourceID
                                }
                            }
                            AC_MIXER_UNIT -> {
                                val nrIn = bytes.u8(4)
                                val sources = (0 until nrIn).map { bytes.u8(5 + it) }
                                val pin = sources.indexOf(usbStreamingTerminalId)
                                if (pin >= 0) {
                                    mixerUnitId = bytes.u8(3)
                                    // Only pin 0 is handled: any other pin's base channel number
                                    // requires summing the channel counts of every preceding
                                    // pin's source, which needs their Cluster Descriptors (a
                                    // separate UAC3 High-Capability descriptor fetch) that this
                                    // parser doesn't do.
                                    mixerInputChannelBase = if (pin == 0) 1 else -1
                                }
                            }
                            AC_POWER_DOMAIN -> {
                                val nrEntities = bytes.u8(8)
                                powerDomains.add(
                                    PowerDomainInfo(
                                        id = bytes.u8(3),
                                        d1RecoveryUs = bytes.u16(4) * 50,
                                        d2RecoveryUs = bytes.u16(6) * 50,
                                        entityIds = (0 until nrEntities).map { bytes.u8(9 + it) }))
                            }
                        }
                    } else if (currentInterfaceSubClass == AS_SUBCLASS &&
                        currentInterfaceProtocol == UAC3_PROTOCOL &&
                        subtype == AS_GENERAL) {
                        val terminalLink = bytes.u8(3)
                        if (terminalLink == usbStreamingTerminalId) {
                            streamingInterfaceNumber = currentInterfaceNumber
                            isPendingTerminalLinked = true
                            // Bit 31 is the bitmap's 4th byte (offset 10+3=13) -- see
                            // UsbAudioDescriptorHelper.RAW_DATA_BYTE_MASK's doc comment.
                            // Unverified against real UAC3 hardware (no UAC3 DSD device has been
                            // seen), unlike the UAC2 path.
                            pendingIsRawData = (bytes.u8(13) and RAW_DATA_BYTE_MASK) != 0
                            // bmFormats at offset 10 (bLength/type/subtype/terminalLink each 1
                            // byte, bmControls 4 bytes, wClusterDescrID 2 bytes, then this 8-byte
                            // bitmap in UAC3) -- see UsbAudioDescriptorHelper.FORMAT_BIT_PCM's doc
                            // comment. Recording subslot/bit-resolution only for a PCM alt setting
                            // means a non-PCM one (e.g. DSD) is excluded the same way the UAC2
                            // parser excludes it: pendingBitResolution stays at its reset default
                            // and flushPendingFormat's existing check skips adding it, instead
                            // recording it as a UacNonPcmFormat.
                            if ((bytes.u8(10) and FORMAT_BIT_PCM) != 0) {
                                pendingClusterId = bytes.u16(8) // wClusterDescrID
                                pendingSubslotSize = bytes.u8(18) // bSubslotSize
                                pendingBitResolution = bytes.u8(19) // bBitResolution
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
                "No UAC3 playback topology in this configuration " +
                    "(control=$controlInterfaceNumber streaming=$streamingInterfaceNumber clock=$clockSourceId)")
            return null
        }

        val formats = formatsByInterface[streamingInterfaceNumber].orEmpty()
        if (formats.isEmpty()) {
            L.w("No streaming formats found for interface $streamingInterfaceNumber")
            return null
        }

        val powerDomain = powerDomains.firstOrNull { usbStreamingTerminalId in it.entityIds }

        return UacPlaybackTopology(
            configurationValue = configurationValue,
            controlInterfaceNumber = controlInterfaceNumber,
            streamingInterfaceNumber = streamingInterfaceNumber,
            uacVersion = 3,
            clockSourceId = clockSourceId,
            outputFeatureUnitId = outputFeatureUnitId,
            mixerUnitId = mixerUnitId,
            mixerInputChannelBase = mixerInputChannelBase,
            powerDomainId = powerDomain?.id ?: -1,
            powerDomainD1RecoveryUs = powerDomain?.d1RecoveryUs ?: 0,
            powerDomainD2RecoveryUs = powerDomain?.d2RecoveryUs ?: 0,
            channelCount = -1, // not parsed for UAC3 -- see UacPlaybackTopology.channelCount
            formats = formats,
            nonPcmFormats = nonPcmFormatsByInterface[streamingInterfaceNumber].orEmpty())
    }
}
