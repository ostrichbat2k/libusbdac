/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioDescriptorParserTest.kt is part of libusbdac.
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Builds raw USB (standard + UAC class-specific) descriptor bytes for test fixtures, mirroring
 * exactly the byte offsets [UsbAudioDescriptorParser] itself reads (see that file's own doc
 * comments for which real device each offset was confirmed against). Every builder here produces
 * a descriptor exactly as long as the fields it sets require -- not a realistic full-length
 * descriptor -- since the parser never reads past what it uses and [UsbAudioDescriptorParser.walk]
 * only cares about each entry's own declared `bLength`.
 */
// File-scoped (not members of [D]) so a fixture call's trailing `{ setU8(...) }` lambda --
// written at the test's own call site, not inside [D]'s body -- can still resolve them.
private fun ByteArray.setU8(offset: Int, value: Int) {
    this[offset] = value.toByte()
}

private fun ByteArray.setU16(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private object D {
    private const val DT_CONFIGURATION = 0x02
    private const val DT_INTERFACE = 0x04
    private const val DT_ENDPOINT = 0x05
    private const val DT_CS_INTERFACE = 0x24

    private fun bytes(length: Int, descriptorType: Int, fill: ByteArray.() -> Unit = {}): ByteArray {
        val b = ByteArray(length)
        b.setU8(0, length)
        b.setU8(1, descriptorType)
        b.fill()
        return b
    }

    /** Standard Configuration Descriptor -- only bConfigurationValue (offset 5) is read. */
    fun configuration(configurationValue: Int) = bytes(9, DT_CONFIGURATION) { setU8(5, configurationValue) }

    /** Standard Interface Descriptor. */
    fun iface(number: Int, altSetting: Int, interfaceClass: Int, subClass: Int, protocol: Int) =
        bytes(9, DT_INTERFACE) {
            setU8(2, number)
            setU8(3, altSetting)
            setU8(5, interfaceClass)
            setU8(6, subClass)
            setU8(7, protocol)
        }

    /** Standard Endpoint Descriptor. */
    fun endpoint(address: Int, attributes: Int, maxPacketSize: Int) =
        bytes(7, DT_ENDPOINT) {
            setU8(2, address)
            setU8(3, attributes)
            setU16(4, maxPacketSize)
        }

    /** A raw CS_INTERFACE entry of exactly [length] bytes, [subtype] at offset 2. */
    fun cs(length: Int, subtype: Int, fill: ByteArray.() -> Unit = {}) =
        bytes(length, DT_CS_INTERFACE) {
            setU8(2, subtype)
            fill()
        }

    // --- UAC 3.0 (and shared Input/Output Terminal subtypes, also used by UAC 1.0/2.0) ---
    fun inputTerminal(id: Int, terminalType: Int) = cs(6, 0x02) { setU8(3, id); setU16(4, terminalType) }

    fun outputTerminal(terminalType: Int, sourceId: Int) =
        cs(8, 0x03) {
            setU16(4, terminalType)
            setU8(7, sourceId)
        }

    fun uac3ClockSource(id: Int) = cs(4, 0x0B) { setU8(3, id) }

    fun uac3MixerUnit(id: Int, sources: List<Int>) =
        cs(5 + sources.size, 0x05) {
            setU8(3, id)
            setU8(4, sources.size)
            sources.forEachIndexed { i, s -> setU8(5 + i, s) }
        }

    fun uac3PowerDomain(id: Int, d1RawUnits: Int, d2RawUnits: Int, entities: List<Int>) =
        cs(9 + entities.size, 0x10) {
            setU8(3, id)
            setU16(4, d1RawUnits)
            setU16(6, d2RawUnits)
            setU8(8, entities.size)
            entities.forEachIndexed { i, e -> setU8(9 + i, e) }
        }

    fun uac3AsGeneral(terminalLink: Int, clusterId: Int, subslotSize: Int, bitResolution: Int) =
        cs(20, 0x01) {
            setU8(3, terminalLink)
            // bmFormats bit 0 (offset 10) -- Type I PCM. Without this, UsbAudioDescriptorParser's
            // own FORMAT_BIT_PCM check (added for DSD/non-PCM support) treats this alt setting as
            // non-PCM and skips recording bitResolution/subslotSize/clusterId entirely, which is
            // not what any of this file's tests building a "normal" UAC3 alt setting want.
            setU8(10, 0x01)
            setU16(8, clusterId)
            setU8(18, subslotSize)
            setU8(19, bitResolution)
        }

    // --- UAC 2.0 ---
    fun uac2InputTerminal(id: Int, terminalType: Int, channelCount: Int) =
        cs(9, 0x02) {
            setU8(3, id)
            setU16(4, terminalType)
            setU8(8, channelCount)
        }

    fun uac2ClockSource(id: Int) = cs(4, 0x0A) { setU8(3, id) }

    fun uac2MixerUnit(id: Int, sources: List<Int>) =
        cs(5 + sources.size, 0x04) {
            setU8(3, id)
            setU8(4, sources.size)
            sources.forEachIndexed { i, s -> setU8(5 + i, s) }
        }

    // 10 bytes, not 4 -- UsbAudioDescriptorParser's FORMAT_BIT_PCM check reads bmFormats at
    // offset 6, which only exists once this descriptor is long enough to carry it. Bit 0 there
    // declares Type I PCM; without it the parser treats this as a non-PCM alt setting and skips
    // recording bitResolution/subslotSize, which is not what a "normal" UAC2 alt setting is.
    fun uac2AsGeneral(terminalLink: Int) = cs(10, 0x01) { setU8(3, terminalLink); setU8(6, 0x01) }

    fun uac2FormatType(subslotSize: Int, bitResolution: Int) =
        cs(6, 0x02) {
            setU8(4, subslotSize)
            setU8(5, bitResolution)
        }

    // --- UAC 1.0 ---
    fun uac1InputTerminal(id: Int, terminalType: Int, channelCount: Int) =
        cs(8, 0x02) {
            setU8(3, id)
            setU16(4, terminalType)
            setU8(7, channelCount) // one byte earlier than UAC 2.0 -- no bCSourceID field
        }

    fun uac1MixerUnit(id: Int, sources: List<Int>) =
        cs(5 + sources.size, 0x04) {
            setU8(3, id)
            setU8(4, sources.size)
            sources.forEachIndexed { i, s -> setU8(5 + i, s) }
        }

    fun uac1AsGeneral(terminalLink: Int) = cs(4, 0x01) { setU8(3, terminalLink) }

    fun uac1FormatType(subslotSize: Int, bitResolution: Int, sampleRates: List<Int>) =
        cs(8 + sampleRates.size * 3, 0x02) {
            setU8(5, subslotSize)
            setU8(6, bitResolution)
            setU8(7, sampleRates.size)
            sampleRates.forEachIndexed { i, rate ->
                val off = 8 + i * 3
                setU8(off, rate and 0xFF)
                setU8(off + 1, (rate shr 8) and 0xFF)
                setU8(off + 2, (rate shr 16) and 0xFF)
            }
        }
}

class UsbAudioDescriptorParserWalkTest {
    @Test
    fun `splits sequential entries by their declared bLength`() {
        val a = D.configuration(1)
        val b = D.iface(0, 0, 1, 1, 0x30)
        val entries = UsbAudioDescriptorParser.walk(a + b)
        assertEquals(2, entries.size)
        assertEquals(0x02, entries[0].first)
        assertEquals(0x04, entries[1].first)
        assertEquals(a.toList(), entries[0].second.toList())
        assertEquals(b.toList(), entries[1].second.toList())
    }

    @Test
    fun `stops without throwing on a malformed zero-length entry`() {
        val a = D.configuration(1)
        val malformed = byteArrayOf(0, 0x04)
        val entries = UsbAudioDescriptorParser.walk(a + malformed)
        assertEquals(1, entries.size)
    }

    @Test
    fun `stops without throwing on a truncated final entry`() {
        val a = D.configuration(1)
        val truncated = byteArrayOf(20, 0x04, 1, 2, 3) // claims length 20, only 5 bytes remain
        val entries = UsbAudioDescriptorParser.walk(a + truncated)
        assertEquals(1, entries.size)
    }

    @Test
    fun `empty input yields an empty list`() {
        assertTrue(UsbAudioDescriptorParser.walk(ByteArray(0)).isEmpty())
    }
}

class UsbAudioDescriptorParserDumpAcTopologyTest {
    /**
     * Regression test for the exact crash fixed alongside this test (confirmed, reproduced on
     * real hardware: a FiiO Snowsky Tiny A, UAC2). A UAC2 Feature Unit (subtype 0x06) whose
     * bSourceID byte (offset 4) happens to be large is numerically indistinguishable, at the
     * subtype level, from UAC3's AC_SELECTOR_UNIT (also 0x06) -- which reads that same byte as
     * nrInPins and would read 20 "source" bytes from an 18-byte buffer. Must not throw.
     */
    @Test
    fun `does not crash on a UAC2 entity whose subtype collides with UAC3's SELECTOR_UNIT`() {
        val iface = D.iface(number = 0, altSetting = 0, interfaceClass = 1, subClass = 1, protocol = 0x20)
        val featureUnit = D.cs(length = 18, subtype = 0x06) { setU8(4, 20) }
        UsbAudioDescriptorParser.dumpAcTopology(iface + featureUnit)
    }

    @Test
    fun `decodes every named UAC3 entity type without throwing`() {
        val iface = D.iface(0, 0, 1, 1, 0x30)
        val input = D.inputTerminal(id = 1, terminalType = 0x0101)
        val clock = D.uac3ClockSource(id = 2)
        val mixer = D.uac3MixerUnit(id = 3, sources = listOf(1, 9))
        val output = D.outputTerminal(terminalType = 0x0301, sourceId = 4)
        val power = D.uac3PowerDomain(id = 5, d1RawUnits = 10, d2RawUnits = 20, entities = listOf(1, 3))
        UsbAudioDescriptorParser.dumpAcTopology(iface + input + clock + mixer + output + power)
    }
}

class UsbAudioDescriptorParserUac3Test {
    private fun earPodsLikeConfig(mixerSources: List<Int>? = null): ByteArray {
        val config = D.configuration(1)
        val acIface = D.iface(0, 0, 1, 1, 0x30)
        val input = D.inputTerminal(id = 1, terminalType = 0x0101)
        val clock = D.uac3ClockSource(id = 2)
        val mixer = mixerSources?.let { D.uac3MixerUnit(id = 6, sources = it) } ?: byteArrayOf()
        val output = D.outputTerminal(terminalType = 0x0301, sourceId = 4)
        val asIface = D.iface(1, 0, 1, 2, 0x30)
        val asGeneral = D.uac3AsGeneral(terminalLink = 1, clusterId = 32, subslotSize = 4, bitResolution = 24)
        val endpoint = D.endpoint(address = 0x83, attributes = 0x05, maxPacketSize = 196) // sync bits = ASYNC
        return config + acIface + input + clock + mixer + output + asIface + asGeneral + endpoint
    }

    @Test
    fun `extracts full UAC3 playback topology`() {
        val topology = UsbAudioDescriptorParser.parseUac3Playback(earPodsLikeConfig())
        assertNotNull(topology)
        topology!!
        assertEquals(1, topology.configurationValue)
        assertEquals(0, topology.controlInterfaceNumber)
        assertEquals(1, topology.streamingInterfaceNumber)
        assertEquals(3, topology.uacVersion)
        assertEquals(2, topology.clockSourceId)
        assertEquals(4, topology.outputFeatureUnitId)
        assertEquals(-1, topology.mixerUnitId)
        assertEquals(-1, topology.channelCount) // UAC3 needs a live Cluster Descriptor fetch for this
        assertEquals(1, topology.formats.size)
        val format = topology.formats.single()
        assertEquals(0, format.alternateSetting)
        assertEquals(24, format.bitResolution)
        assertEquals(4, format.subslotSize)
        assertEquals(32, format.clusterDescriptorId)
        assertEquals(0x83, format.endpointAddress)
        assertEquals(196, format.maxPacketSize)
        assertTrue(format.isAsync)
    }

    @Test
    fun `returns null when there is no UAC3 control interface`() {
        val config = D.configuration(1)
        val asIface = D.iface(1, 0, 1, 2, 0x30)
        val asGeneral = D.uac3AsGeneral(terminalLink = 1, clusterId = 32, subslotSize = 4, bitResolution = 24)
        assertNull(UsbAudioDescriptorParser.parseUac3Playback(config + asIface + asGeneral))
    }

    @Test
    fun `mixer unit at pin 0 resolves a channel base, any other pin does not`() {
        val pin0 = UsbAudioDescriptorParser.parseUac3Playback(earPodsLikeConfig(mixerSources = listOf(1, 9)))!!
        assertEquals(6, pin0.mixerUnitId)
        assertEquals(1, pin0.mixerInputChannelBase)

        val pin1 = UsbAudioDescriptorParser.parseUac3Playback(earPodsLikeConfig(mixerSources = listOf(9, 1)))!!
        assertEquals(6, pin1.mixerUnitId)
        assertEquals(-1, pin1.mixerInputChannelBase) // needs a Cluster Descriptor fetch, not parsed here
    }

    @Test
    fun `power domain covering the USB terminal is reported with microsecond recovery times`() {
        val config = D.configuration(1)
        val acIface = D.iface(0, 0, 1, 1, 0x30)
        val input = D.inputTerminal(1, 0x0101)
        val clock = D.uac3ClockSource(2)
        val output = D.outputTerminal(0x0301, 4)
        val power = D.uac3PowerDomain(id = 9, d1RawUnits = 10, d2RawUnits = 20, entities = listOf(1, 4))
        val asIface = D.iface(1, 0, 1, 2, 0x30)
        val asGeneral = D.uac3AsGeneral(1, 32, 4, 24)
        val endpoint = D.endpoint(0x83, 0x05, 196)
        val topology =
            UsbAudioDescriptorParser.parseUac3Playback(
                config + acIface + input + clock + output + power + asIface + asGeneral + endpoint)!!
        assertEquals(9, topology.powerDomainId)
        assertEquals(500, topology.powerDomainD1RecoveryUs) // 10 raw units * 50us
        assertEquals(1000, topology.powerDomainD2RecoveryUs) // 20 raw units * 50us
    }
}

class UsbAudioDescriptorParserUac2Test {
    private fun fiioLikeConfig(channelCount: Int = 2, withClock: Boolean = true): ByteArray {
        val config = D.configuration(1)
        val acIface = D.iface(0, 0, 1, 1, 0x20)
        val input = D.uac2InputTerminal(id = 1, terminalType = 0x0101, channelCount = channelCount)
        val clock = if (withClock) D.uac2ClockSource(id = 10) else byteArrayOf()
        val output = D.outputTerminal(terminalType = 0x0301, sourceId = 15)
        val asIface = D.iface(1, 1, 1, 2, 0x20)
        val asGeneral = D.uac2AsGeneral(terminalLink = 1)
        val formatType = D.uac2FormatType(subslotSize = 2, bitResolution = 16)
        val endpoint = D.endpoint(address = 0x07, attributes = 0x05, maxPacketSize = 196)
        return config + acIface + input + clock + output + asIface + asGeneral + formatType + endpoint
    }

    @Test
    fun `extracts full UAC2 playback topology including channel count from the input terminal`() {
        val topology = UsbAudioDescriptorParser.parseUac2Playback(fiioLikeConfig(channelCount = 2))
        assertNotNull(topology)
        topology!!
        assertEquals(2, topology.uacVersion)
        assertEquals(10, topology.clockSourceId)
        assertEquals(15, topology.outputFeatureUnitId)
        assertEquals(2, topology.channelCount)
        assertEquals(1, topology.formats.size)
        val format = topology.formats.single()
        assertEquals(16, format.bitResolution)
        assertEquals(2, format.subslotSize)
        assertEquals(-1, format.clusterDescriptorId) // UAC2 never sets this -- UAC3-only concept
        assertTrue(format.sampleRates.isEmpty()) // UAC2 negotiates rate via the Clock Source
    }

    @Test
    fun `returns null without a clock source`() {
        assertNull(UsbAudioDescriptorParser.parseUac2Playback(fiioLikeConfig(withClock = false)))
    }

    @Test
    fun `mixer channel base sums the channel counts of preceding pins`() {
        val config = D.configuration(1)
        val acIface = D.iface(0, 0, 1, 1, 0x20)
        val analogInput = D.uac2InputTerminal(id = 5, terminalType = 0x0201, channelCount = 2)
        val usbInput = D.uac2InputTerminal(id = 1, terminalType = 0x0101, channelCount = 2)
        val clock = D.uac2ClockSource(10)
        val mixer = D.uac2MixerUnit(id = 20, sources = listOf(5, 1)) // our USB terminal is pin 1, not pin 0
        val output = D.outputTerminal(0x0301, 15)
        val asIface = D.iface(1, 1, 1, 2, 0x20)
        val asGeneral = D.uac2AsGeneral(1)
        val formatType = D.uac2FormatType(2, 16)
        val endpoint = D.endpoint(0x07, 0x05, 196)
        val topology =
            UsbAudioDescriptorParser.parseUac2Playback(
                config + acIface + analogInput + usbInput + clock + mixer + output + asIface + asGeneral +
                    formatType + endpoint)!!
        assertEquals(20, topology.mixerUnitId)
        assertEquals(3, topology.mixerInputChannelBase) // 1 + the analog input's 2 preceding channels
    }
}

class UsbAudioDescriptorParserUac1Test {
    private fun ab13xLikeConfig(sampleRates: List<Int> = listOf(8000, 48000)): ByteArray {
        val config = D.configuration(1)
        val acIface = D.iface(0, 0, 1, 1, 0x00)
        val input = D.uac1InputTerminal(id = 1, terminalType = 0x0101, channelCount = 2)
        val output = D.outputTerminal(terminalType = 0x0301, sourceId = 8)
        val asIface = D.iface(1, 1, 1, 2, 0x00)
        val asGeneral = D.uac1AsGeneral(terminalLink = 1)
        val formatType = D.uac1FormatType(subslotSize = 2, bitResolution = 16, sampleRates = sampleRates)
        val endpoint = D.endpoint(address = 0x01, attributes = 0x05, maxPacketSize = 192)
        return config + acIface + input + output + asIface + asGeneral + formatType + endpoint
    }

    @Test
    fun `extracts UAC1 topology with no clock source and discrete sample rates from the format descriptor`() {
        val topology = UsbAudioDescriptorParser.parseUac1Playback(ab13xLikeConfig())
        assertNotNull(topology)
        topology!!
        assertEquals(1, topology.uacVersion)
        assertEquals(-1, topology.clockSourceId) // UAC1 has no Clock Source entity at all
        assertEquals(8, topology.outputFeatureUnitId)
        assertEquals(2, topology.channelCount)
        val format = topology.formats.single()
        assertEquals(16, format.bitResolution)
        assertEquals(2, format.subslotSize)
        assertEquals(listOf(8000, 48000), format.sampleRates)
    }

    @Test
    fun `returns null when there is no AudioStreaming interface`() {
        val config = D.configuration(1)
        val acIface = D.iface(0, 0, 1, 1, 0x00)
        val input = D.uac1InputTerminal(1, 0x0101, 2)
        val output = D.outputTerminal(0x0301, 8)
        assertNull(UsbAudioDescriptorParser.parseUac1Playback(config + acIface + input + output))
    }

    @Test
    fun `a continuous (non-discrete) sample rate range is left unparsed, not misread`() {
        val topology = UsbAudioDescriptorParser.parseUac1Playback(ab13xLikeConfig(sampleRates = emptyList()))!!
        assertTrue(topology.formats.single().sampleRates.isEmpty())
    }

    @Test
    fun `mixer channel base sums the channel counts of preceding pins`() {
        val config = D.configuration(1)
        val acIface = D.iface(0, 0, 1, 1, 0x00)
        val micInput = D.uac1InputTerminal(id = 5, terminalType = 0x0201, channelCount = 1)
        val usbInput = D.uac1InputTerminal(id = 1, terminalType = 0x0101, channelCount = 2)
        val mixer = D.uac1MixerUnit(id = 7, sources = listOf(5, 1))
        val output = D.outputTerminal(0x0301, 8)
        val asIface = D.iface(1, 1, 1, 2, 0x00)
        val asGeneral = D.uac1AsGeneral(1)
        val formatType = D.uac1FormatType(2, 16, listOf(48000))
        val endpoint = D.endpoint(0x01, 0x05, 192)
        val topology =
            UsbAudioDescriptorParser.parseUac1Playback(
                config + acIface + micInput + usbInput + mixer + output + asIface + asGeneral + formatType +
                    endpoint)!!
        assertEquals(7, topology.mixerUnitId)
        assertEquals(2, topology.mixerInputChannelBase) // 1 + the mic's 1 preceding channel
    }
}
