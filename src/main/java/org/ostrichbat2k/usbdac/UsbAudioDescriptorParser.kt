/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioDescriptorParser.kt is part of libusbdac.
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
import android.hardware.usb.UsbDeviceConnection
import timber.log.Timber as L

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
object UsbAudioDescriptorParser {

    private const val DESC_TYPE_CONFIGURATION = 0x02
    private const val DESC_TYPE_INTERFACE = 0x04
    private const val DESC_TYPE_ENDPOINT = 0x05
    private const val DESC_TYPE_CS_INTERFACE = 0x24

    private const val AC_SUBCLASS = 1
    private const val AS_SUBCLASS = 2
    private const val UAC3_PROTOCOL = 0x30
    private const val UAC2_PROTOCOL = 0x20
    // UAC 1.0 predates the bInterfaceProtocol field being used to carry a version at all --
    // devices declare 0x00 here (confirmed against the Linux kernel's UAC_VERSION_1 in
    // include/linux/usb/audio.h, and against a real device: a generic "AB13X USB Audio" dongle).
    private const val UAC1_PROTOCOL = 0x00

    // AudioControl class-specific interface descriptor subtypes (UAC 3.0). These differ from
    // UAC 1.0/2.0's subtype numbers (e.g. FEATURE_UNIT is 6 in UAC2 but 7 in UAC3).
    private const val AC_INPUT_TERMINAL = 0x02
    private const val AC_OUTPUT_TERMINAL = 0x03
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

    // AudioControl class-specific interface descriptor subtypes (UAC 2.0 -- confirmed against
    // the Linux kernel's include/linux/usb/audio-v2.h). INPUT_TERMINAL/OUTPUT_TERMINAL are
    // shared with UAC 1.0, everything else here shifts down by one slot relative to UAC 3.0
    // (e.g. FEATURE_UNIT is 6, not 7) since UAC 3.0 inserted EXTENDED_TERMINAL. No Power Domain
    // concept exists in UAC 2.0 -- that's a UAC 3.0-only addition.
    private const val AC2_MIXER_UNIT = 0x04
    private const val AC2_SELECTOR_UNIT = 0x05
    private const val AC2_FEATURE_UNIT = 0x06
    private const val AC2_CLOCK_SOURCE = 0x0A

    // AudioStreaming class-specific interface descriptor subtype (UAC 3.0). Unlike UAC 1.0/2.0,
    // UAC 3.0 folds format info directly into AS_GENERAL rather than a separate FORMAT_TYPE
    // descriptor.
    private const val AS_GENERAL = 0x01
    // UAC 2.0's AS_GENERAL (same subtype number, different/shorter struct) carries the format
    // info as a bitmap rather than inline bit-resolution fields, so the actual PCM parameters
    // come from a separate FORMAT_TYPE descriptor that follows it.
    private const val AS2_FORMAT_TYPE = 0x02

    private const val TERMINAL_USB_STREAMING = 0x0101

    // USB Endpoint Descriptor bmAttributes synchronization-type field (bits 3:2).
    private const val SYNC_TYPE_ASYNC = 0x1

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

    /** [entries]-taking overload of [dumpAcTopology] -- see [walk]'s doc comment for why this exists. */
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
                    // (see parseUac2Playback's docs), so this falls through to the raw hex dump
                    // for those -- still enough to see the entity graph, just not named.
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
     * Splits a raw configuration descriptor into its sequential (type, bytes) TLV entries.
     * Public so a caller walking the same buffer through more than one of this object's
     * `ByteArray`-taking functions (see [UsbAudioManager.activateDac], which tries
     * [parseUac3Playback]/[parseUac2Playback]/[parseUac1Playback] plus [dumpAcTopology] against
     * the same bytes) can walk once and pass the result to each function's `entries`-taking
     * overload instead -- walking is a full linear pass over the buffer, and every one of those
     * functions would otherwise redo it independently.
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

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF

    private fun ByteArray.u16(i: Int) = u8(i) or (u8(i + 1) shl 8)

    /**
     * Finds the UAC 3.0 AudioControl interface in [configBytes] (if any) and parses it, together
     * with its linked USB-Streaming-to-output AudioStreaming interface, into a playback topology.
     * Returns null if this configuration isn't a (full, non-BADD) UAC 3.0 one.
     */
    fun parseUac3Playback(configBytes: ByteArray): UacPlaybackTopology? = parseUac3Playback(walk(configBytes))

    /** [entries]-taking overload of [parseUac3Playback] -- see [walk]'s doc comment for why this exists. */
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
        var pendingAltSetting = -1
        var pendingEndpointAddress = -1
        var pendingEndpointSync = -1
        var pendingMaxPacketSize = -1
        var pendingBitResolution = -1
        var pendingSubslotSize = -1
        var pendingClusterId = -1

        fun flushPendingFormat(interfaceNumber: Int) {
            if (pendingAltSetting >= 0 && pendingEndpointAddress >= 0 && pendingBitResolution > 0) {
                formatsByInterface
                    .getOrPut(interfaceNumber) { mutableListOf() }
                    .add(
                        UacStreamFormat(
                            alternateSetting = pendingAltSetting,
                            bitResolution = pendingBitResolution,
                            subslotSize = pendingSubslotSize,
                            clusterDescriptorId = pendingClusterId,
                            endpointAddress = pendingEndpointAddress,
                            maxPacketSize = pendingMaxPacketSize,
                            isAsync = pendingEndpointSync == SYNC_TYPE_ASYNC))
            }
            pendingAltSetting = -1
            pendingEndpointAddress = -1
            pendingEndpointSync = -1
            pendingMaxPacketSize = -1
            pendingBitResolution = -1
            pendingSubslotSize = -1
            pendingClusterId = -1
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
                            pendingClusterId = bytes.u16(8) // wClusterDescrID
                            pendingSubslotSize = bytes.u8(18) // bSubslotSize
                            pendingBitResolution = bytes.u8(19) // bBitResolution
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
            formats = formats)
    }

    /**
     * Same idea as [parseUac3Playback], for UAC 2.0 devices -- most real-world USB DACs (this
     * was discovered testing against one: a FiiO Snowsky Tiny A, bInterfaceProtocol 0x20, which
     * the UAC-3.0-only parser above found nothing at all for). Confirmed against the Linux
     * kernel's audio-v2.h rather than derived from the spec alone, same as the UAC3 parser's
     * approach.
     *
     * The main structural differences from UAC 3.0: every AC unit/terminal subtype number shifts
     * down by one slot (UAC 3.0 inserted EXTENDED_TERMINAL at 0x04), there's no Power Domain
     * concept at all, and -- the one that actually matters for parsing -- UAC 2.0's AS_GENERAL
     * descriptor doesn't carry bit-resolution/subslot-size inline; those come from a separate
     * FORMAT_TYPE descriptor that follows it in the same interface block. It does carry
     * bNrChannels directly on the Input Terminal, though, which UAC 3.0 can't (that's pushed
     * into a separate Cluster Descriptor fetch) -- meaning mixerInputChannelBase can be computed
     * exactly here instead of only for a Mixer Unit's first input pin.
     */
    fun parseUac2Playback(configBytes: ByteArray): UacPlaybackTopology? = parseUac2Playback(walk(configBytes))

    /** [entries]-taking overload of [parseUac2Playback] -- see [walk]'s doc comment for why this exists. */
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
        var pendingAltSetting = -1
        var pendingEndpointAddress = -1
        var pendingEndpointSync = -1
        var pendingMaxPacketSize = -1
        var pendingBitResolution = -1
        var pendingSubslotSize = -1
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
                            isAsync = pendingEndpointSync == SYNC_TYPE_ASYNC))
            }
            pendingAltSetting = -1
            pendingEndpointAddress = -1
            pendingEndpointSync = -1
            pendingMaxPacketSize = -1
            pendingBitResolution = -1
            pendingSubslotSize = -1
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
                            }
                            AS2_FORMAT_TYPE -> {
                                if (isPendingTerminalLinked) {
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
            formats = formats)
    }

    /**
     * Same idea as [parseUac2Playback]/[parseUac3Playback], for UAC 1.0 devices -- the protocol
     * most generic/cheap USB audio dongles still use (confirmed against a real one: a no-name
     * "AB13X USB Audio" adapter, bInterfaceProtocol 0x00, which neither of the other two parsers
     * found anything on). Byte offsets confirmed against the Linux kernel's
     * include/linux/usb/audio.h (uac_input_terminal_descriptor, uac_feature_unit_descriptor,
     * uac_mixer_unit_descriptor, uac1_as_header_descriptor, uac_format_type_i_discrete_descriptor),
     * same approach as the other two parsers.
     *
     * The structural differences that actually matter here: UAC 1.0's unit/terminal AC subtype
     * numbers are identical to UAC 2.0's (reusing [AC2_MIXER_UNIT]/[AC2_FEATURE_UNIT] below is
     * intentional, not a copy-paste shortcut), but there is no Clock Source entity at all -- no
     * [UacPlaybackTopology.clockSourceId] concept exists in UAC 1.0. Instead, each format's
     * FORMAT_TYPE descriptor declares its own supported sample rate(s) directly (collected into
     * [UacStreamFormat.sampleRates]), and the *endpoint* itself -- not an AudioControl entity --
     * is what a Sampling Frequency Control SET_CUR targets once that format's alternate setting is
     * selected. (Only the common discrete-rate-list form of FORMAT_TYPE is handled; the rarer
     * continuous-range form, bSamFreqType=0, is skipped.)
     */
    fun parseUac1Playback(configBytes: ByteArray): UacPlaybackTopology? = parseUac1Playback(walk(configBytes))

    /** [entries]-taking overload of [parseUac1Playback] -- see [walk]'s doc comment for why this exists. */
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
