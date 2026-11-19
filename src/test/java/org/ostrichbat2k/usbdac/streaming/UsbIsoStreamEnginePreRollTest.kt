/*
 * Copyright (c) 2026 libusbdac Project
 * UsbIsoStreamEnginePreRollTest.kt is part of libusbdac.
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

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession

/**
 * #127: with `start(holdSilence = true)` the stream runs on silence -- not taken from the ring
 * buffer and not credited to position -- until [UsbIsoStreamEngine.openSilenceGate], and the
 * first real audio after that fades in.
 */
class UsbIsoStreamEnginePreRollTest {
    private companion object {
        const val SAMPLE: Short = 1000
        const val TIMEOUT_MS = 3000L
    }

    private val format =
        UacStreamFormat(
            alternateSetting = 1,
            bitResolution = 16,
            subslotSize = 2,
            clusterDescriptorId = -1,
            endpointAddress = 0x02,
            maxPacketSize = 192,
        )

    private val session =
        UsbAudioSession(
            device = mockk<UsbDevice>(relaxed = true),
            connection = mockk<UsbDeviceConnection>(relaxed = true) { every { fileDescriptor } returns 42 },
            topology =
                UacPlaybackTopology(
                    configurationValue = 1,
                    controlInterfaceNumber = 0,
                    streamingInterfaceNumber = 1,
                    uacVersion = 2,
                    clockSourceId = -1,
                    outputFeatureUnitId = 7,
                    mixerUnitId = -1,
                    mixerInputChannelBase = -1,
                    powerDomainId = -1,
                    powerDomainD1RecoveryUs = 0,
                    powerDomainD2RecoveryUs = 0,
                    channelCount = 2,
                    formats = listOf(format),
                ),
            controlInterface = mockk<UsbInterface>(relaxed = true),
            streamingAltSettings = emptyMap(),
        )

    /** Completes URBs in slot order as fast as the engine's own pacing allows; keeps a copy of every one. */
    private class RecordingBridge : UsbNativeBridge {
        val submitted = CopyOnWriteArrayList<ShortArray>()
        private var nextSlot = 0

        override fun disconnectKernelDriver(fd: Int, interfaceNumber: Int) = true

        override fun getUsbSpeed(fd: Int) = 2

        override fun openIsoStream(
            fd: Int,
            endpointAddress: Int,
            maxPacketSize: Int,
            packetsPerUrb: Int,
            numUrbs: Int,
        ) = 1L

        override fun submitIso(handle: Long, slot: Int, data: ByteArray, packetLengths: IntArray): Boolean {
            val bytes = packetLengths.sum()
            val samples = ShortArray(bytes / 2)
            ByteBuffer.wrap(data, 0, bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            submitted += samples
            return true
        }

        override fun reapIso(handle: Long): Int {
            val slot = nextSlot
            nextSlot = (nextSlot + 1) % 4
            return slot
        }

        override fun cancelIso(handle: Long) = Unit

        override fun closeIsoStream(handle: Long) = Unit

        override fun submitIsoRead(handle: Long, slot: Int, packetLengths: IntArray) = false

        override fun readIsoSlotData(handle: Long, slot: Int, dst: ByteArray) = -1

        override fun getPacketErrorStats(handle: Long) = longArrayOf(0L, 0L)
    }

    private val bridge = RecordingBridge()
    private val ringBuffer = PcmRingBuffer(64 * 1024)

    private val engine =
        UsbIsoStreamEngine(
            session = session,
            format = format,
            channels = 2,
            sampleRateHz = 44100,
            ringBuffer = ringBuffer,
            paused = { false },
            reportPipelineStats = {},
            nativeBridge = bridge,
        )

    @AfterEach
    fun tearDown() {
        engine.stop()
    }

    private fun fillRingBuffer() {
        val bytes = ByteBuffer.allocate(32 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        while (bytes.hasRemaining()) bytes.putShort(SAMPLE)
        bytes.flip()
        ringBuffer.write(bytes)
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < deadline, "timed out waiting for $what")
            Thread.sleep(2)
        }
    }

    @Test
    fun `pre-roll sends silence, leaves the ring buffer alone and credits no position`() {
        fillRingBuffer()
        val buffered = ringBuffer.availableToRead()

        engine.start(holdSilence = true)
        waitUntil("8 URBs of pre-roll") { engine.urbsReaped >= 8 }

        assertTrue(bridge.submitted.all { urb -> urb.all { it.toInt() == 0 } }, "pre-roll carried non-zero samples")
        assertEquals(buffered, ringBuffer.availableToRead())
        assertEquals(0L, engine.framesOutput)
    }

    @Test
    fun `audio after the gate opens fades in from silence and is credited`() {
        fillRingBuffer()
        engine.start(holdSilence = true)
        waitUntil("pre-roll to start") { engine.urbsReaped >= 4 }

        engine.openSilenceGate()
        waitUntil("real audio") { bridge.submitted.any { urb -> urb.any { it.toInt() != 0 } } }
        val first = bridge.submitted.first { urb -> urb.any { it.toInt() != 0 } }

        // Stereo, interleaved: frame i is samples 2i and 2i+1. Frame 0 is at 1/32 of full level,
        // frame 31 is back at full level, and the ramp rises in between.
        assertEquals(SAMPLE / 32, first[0].toInt())
        assertEquals(SAMPLE / 32, first[1].toInt())
        assertEquals(SAMPLE.toInt(), first[2 * 31].toInt())
        assertEquals(SAMPLE.toInt(), first[2 * 40].toInt())
        assertTrue(first[2 * 10] > first[2 * 5] && first[2 * 5] > first[0])

        waitUntil("position to advance") { engine.framesOutput > 0 }
    }

    @Test
    fun `without pre-roll the first URB is real audio at full level`() {
        fillRingBuffer()

        engine.start()
        waitUntil("the first URB") { bridge.submitted.isNotEmpty() }

        assertEquals(SAMPLE.toInt(), bridge.submitted.first()[0].toInt())
    }

    @Test
    fun `opening the gate without pre-roll changes nothing`() {
        fillRingBuffer()

        engine.start()
        engine.openSilenceGate()
        waitUntil("two URBs") { bridge.submitted.size >= 2 }

        assertEquals(SAMPLE.toInt(), bridge.submitted.first()[0].toInt())
    }
}
