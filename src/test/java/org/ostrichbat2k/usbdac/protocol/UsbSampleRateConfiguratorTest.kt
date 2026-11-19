/*
 * Copyright (c) 2026 libusbdac Project
 * UsbSampleRateConfiguratorTest.kt is part of libusbdac.
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

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ostrichbat2k.usbdac.UsbAudioTuning
import org.ostrichbat2k.usbdac.protocol.UsbAudioController.ClockWriteOutcome
import org.ostrichbat2k.usbdac.protocol.UsbAudioController.ControlWriteOutcome

/**
 * [UsbSampleRateConfigurator] is what plug-in activation and playback start now share (#136).
 * These pin down the two behaviours its callers rely on staying exactly as they were before the
 * extraction: with the default retry count of zero it is one request and one settle delay (what
 * activation did), and with retries it is the loop playback start had.
 */
class UsbSampleRateConfiguratorTest {
    private companion object {
        const val ENDPOINT = 0x01
        const val RATE = 96000
    }

    private val sleeps = mutableListOf<Long>()
    private val sleep: (Long) -> Unit = { sleeps += it }

    private fun session(uacVersion: Int, clockWritable: Boolean = true, validityReadable: Boolean = true) =
        UsbAudioSession(
            device = mockk<UsbDevice>(relaxed = true),
            connection = mockk<UsbDeviceConnection>(relaxed = true),
            topology =
                UacPlaybackTopology(
                    configurationValue = 1,
                    controlInterfaceNumber = 0,
                    streamingInterfaceNumber = 1,
                    uacVersion = uacVersion,
                    clockSourceId = if (uacVersion == 1) -1 else 9,
                    outputFeatureUnitId = -1,
                    mixerUnitId = -1,
                    mixerInputChannelBase = -1,
                    powerDomainId = -1,
                    powerDomainD1RecoveryUs = 0,
                    powerDomainD2RecoveryUs = 0,
                    channelCount = 2,
                    formats = emptyList(),
                    clockFrequencyWritable = clockWritable,
                    clockValidityReadable = validityReadable,
                ),
            controlInterface = mockk<UsbInterface>(relaxed = true),
            streamingAltSettings = emptyMap(),
        )

    private fun format(rates: List<Int> = listOf(44100, 48000, RATE), hasRateControl: Boolean = true) =
        UacStreamFormat(
            alternateSetting = 1,
            bitResolution = 16,
            subslotSize = 2,
            clusterDescriptorId = -1,
            endpointAddress = ENDPOINT,
            maxPacketSize = 192,
            sampleRates = rates,
            hasSamplingFrequencyControl = hasRateControl,
        )

    private fun write(session: UsbAudioSession, format: UacStreamFormat, maxRetries: Int = 0) =
        UsbSampleRateConfigurator.writeSampleRate(session, format, RATE, maxRetries, sleep)

    @BeforeEach
    fun setUp() {
        mockkObject(UsbAudioController)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(UsbAudioController)
    }

    @Test
    fun `UAC2 and UAC3 write the Clock Source and never the endpoint`() {
        for (version in listOf(2, 3)) {
            val session = session(version)
            every { UsbAudioController.setClockFrequency(session, RATE) } returns ClockWriteOutcome.Confirmed(RATE)
            val write = write(session, format())
            assertTrue(write.accepted)
            assertEquals(0, write.retries)
            verify(exactly = 1) { UsbAudioController.setClockFrequency(session, RATE) }
        }
        verify(exactly = 0) { UsbAudioController.setUac1EndpointSampleRate(any(), any(), any()) }
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `UAC1 writes the streaming endpoint and never the Clock Source`() {
        val session = session(1)
        every { UsbAudioController.setUac1EndpointSampleRate(session, ENDPOINT, RATE) } returns
            ClockWriteOutcome.Confirmed(RATE)
        val write = write(session, format())
        assertTrue(write.accepted)
        verify(exactly = 1) { UsbAudioController.setUac1EndpointSampleRate(session, ENDPOINT, RATE) }
        verify(exactly = 0) { UsbAudioController.setClockFrequency(any(), any()) }
    }

    @Test
    fun `without retries a failed write is reported after a single attempt and no delay`() {
        val session = session(2)
        every { UsbAudioController.setClockFrequency(session, RATE) } returns ClockWriteOutcome.TransferFailed
        val write = write(session, format())
        assertFalse(write.accepted)
        assertEquals(ClockWriteOutcome.TransferFailed, write.outcome)
        assertEquals(0, write.retries)
        verify(exactly = 1) { UsbAudioController.setClockFrequency(session, RATE) }
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `a write that succeeds on a retry waits one settle delay per retry`() {
        val session = session(2)
        every { UsbAudioController.setClockFrequency(session, RATE) } returnsMany
            listOf(
                ClockWriteOutcome.TransferFailed,
                ClockWriteOutcome.Mismatch(RATE, 48000),
                ClockWriteOutcome.Confirmed(RATE))
        val write = write(session, format(), maxRetries = 3)
        assertTrue(write.accepted)
        assertEquals(2, write.retries)
        assertEquals(List(2) { UsbAudioTuning.DAC_SETTLE_DELAY_MS }, sleeps)
    }

    @Test
    fun `retries stop at the limit and the last outcome is reported`() {
        val session = session(2)
        every { UsbAudioController.setClockFrequency(session, RATE) } returns ClockWriteOutcome.Mismatch(RATE, 48000)
        val write = write(session, format(), maxRetries = 3)
        assertFalse(write.accepted)
        assertEquals(3, write.retries)
        verify(exactly = 4) { UsbAudioController.setClockFrequency(session, RATE) }
        assertEquals(3, sleeps.size)
    }

    // ---- #128: what UAC 1.0 is allowed to leave out ----

    @Test
    fun `UAC1 endpoint without the sampling frequency control is sent nothing and accepted`() {
        val session = session(1)
        val write = write(session, format(hasRateControl = false))
        assertTrue(write.accepted)
        assertTrue(write.acceptedBecause!!.contains("no sampling frequency control"))
        verify(exactly = 0) { UsbAudioController.setUac1EndpointSampleRate(any(), any(), any()) }
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `the control flag is not consulted for UAC2`() {
        val session = session(2)
        every { UsbAudioController.setClockFrequency(session, RATE) } returns ClockWriteOutcome.Confirmed(RATE)
        val write = write(session, format(hasRateControl = false))
        assertTrue(write.accepted)
        assertNull(write.acceptedBecause)
        verify(exactly = 1) { UsbAudioController.setClockFrequency(session, RATE) }
    }

    @Test
    fun `UAC1 write that cannot be read back is accepted without retrying`() {
        for (readBack in listOf<Int?>(null, 0)) {
            sleeps.clear()
            val session = session(1)
            every { UsbAudioController.setUac1EndpointSampleRate(session, ENDPOINT, RATE) } returns
                ClockWriteOutcome.Mismatch(RATE, readBack)
            val write = write(session, format(), maxRetries = 3)
            assertTrue(write.accepted, "readBack=$readBack")
            assertEquals(0, write.retries)
            assertTrue(sleeps.isEmpty())
        }
    }

    @Test
    fun `UAC1 read-back of a different rate is a failure even on a single-rate format`() {
        val session = session(1)
        every { UsbAudioController.setUac1EndpointSampleRate(session, ENDPOINT, RATE) } returns
            ClockWriteOutcome.Mismatch(RATE, 48000)
        val write = write(session, format(rates = listOf(RATE)))
        assertFalse(write.accepted)
        assertNull(write.acceptedBecause)
    }

    @Test
    fun `UAC1 stalled request is accepted when it asks for the format's only rate`() {
        val session = session(1)
        every { UsbAudioController.setUac1EndpointSampleRate(session, ENDPOINT, RATE) } returns
            ClockWriteOutcome.TransferFailed
        val write = write(session, format(rates = listOf(RATE)))
        assertTrue(write.accepted)
        assertEquals(ClockWriteOutcome.TransferFailed, write.outcome)
        assertTrue(write.acceptedBecause!!.contains("only rate"))
    }

    @Test
    fun `UAC1 stalled request is a failure when the format has other rates`() {
        val session = session(1)
        every { UsbAudioController.setUac1EndpointSampleRate(session, ENDPOINT, RATE) } returns
            ClockWriteOutcome.TransferFailed
        val write = write(session, format(), maxRetries = 2)
        assertFalse(write.accepted)
        assertEquals(2, write.retries)
    }

    @Test
    fun `UAC2 gets none of the UAC1 leniency`() {
        val session = session(2)
        every { UsbAudioController.setClockFrequency(session, RATE) } returnsMany
            listOf(ClockWriteOutcome.Mismatch(RATE, null), ClockWriteOutcome.TransferFailed)
        val single = format(rates = listOf(RATE))
        assertFalse(write(session, single).accepted)
        assertFalse(write(session, single).accepted)
    }

    // ---- #131: what the Clock Source's bmControls says it supports ----

    @Test
    fun `a read-only clock already at the rate is accepted without any write`() {
        val session = session(2, clockWritable = false)
        every { UsbAudioController.getClockFrequency(session) } returns RATE
        val write = write(session, format(), maxRetries = 3)
        assertTrue(write.accepted)
        assertEquals(ClockWriteOutcome.Confirmed(RATE), write.outcome)
        assertNull(write.acceptedBecause)
        verify(exactly = 0) { UsbAudioController.setClockFrequency(any(), any()) }
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `a read-only clock at another rate is a failure and is still never written`() {
        val session = session(3, clockWritable = false)
        every { UsbAudioController.getClockFrequency(session) } returns 48000
        val write = write(session, format())
        assertFalse(write.accepted)
        assertEquals(ClockWriteOutcome.Mismatch(RATE, 48000), write.outcome)
        verify(exactly = 0) { UsbAudioController.setClockFrequency(any(), any()) }
    }

    @Test
    fun `a clock whose validity control is not readable is not asked`() {
        val settle = UsbSampleRateConfigurator.awaitClockSettled(
            session(2, validityReadable = false), maxValidRetries = 3, sleep = sleep)
        assertNull(settle.valid)
        assertEquals(listOf(UsbAudioTuning.DAC_SETTLE_DELAY_MS), sleeps)
        verify(exactly = 0) { UsbAudioController.getClockValid(any()) }
    }

    @Test
    fun `settling a UAC1 device is one delay and no validity request`() {
        val settle = UsbSampleRateConfigurator.awaitClockSettled(session(1), maxValidRetries = 3, sleep = sleep)
        assertNull(settle.valid)
        assertEquals(0, settle.extraWaits)
        assertEquals(listOf(UsbAudioTuning.DAC_SETTLE_DELAY_MS), sleeps)
        verify(exactly = 0) { UsbAudioController.getClockValid(any()) }
    }

    @Test
    fun `a clock that is valid straight away costs one delay and one request`() {
        val session = session(2)
        every { UsbAudioController.getClockValid(session) } returns true
        val settle = UsbSampleRateConfigurator.awaitClockSettled(session, maxValidRetries = 3, sleep = sleep)
        assertEquals(true, settle.valid)
        assertEquals(0, settle.extraWaits)
        assertEquals(1, sleeps.size)
        verify(exactly = 1) { UsbAudioController.getClockValid(session) }
    }

    @Test
    fun `an invalid clock is polled until it turns valid`() {
        val session = session(2)
        every { UsbAudioController.getClockValid(session) } returnsMany listOf(false, false, true)
        val settle = UsbSampleRateConfigurator.awaitClockSettled(session, maxValidRetries = 3, sleep = sleep)
        assertEquals(true, settle.valid)
        assertEquals(2, settle.extraWaits)
        assertEquals(3, sleeps.size)
    }

    @Test
    fun `polling stops at the limit and reports the clock as still invalid`() {
        val session = session(2)
        every { UsbAudioController.getClockValid(session) } returns false
        val settle = UsbSampleRateConfigurator.awaitClockSettled(session, maxValidRetries = 3, sleep = sleep)
        assertEquals(false, settle.valid)
        assertEquals(3, settle.extraWaits)
        verify(exactly = 4) { UsbAudioController.getClockValid(session) }
    }

    @Test
    fun `an unreadable validity control is not polled`() {
        val session = session(3)
        every { UsbAudioController.getClockValid(session) } returns null
        val settle = UsbSampleRateConfigurator.awaitClockSettled(session, maxValidRetries = 3, sleep = sleep)
        assertNull(settle.valid)
        assertEquals(0, settle.extraWaits)
        verify(exactly = 1) { UsbAudioController.getClockValid(session) }
    }

    @Test
    fun `without retries an invalid clock is asked once, as activation always did`() {
        val session = session(2)
        every { UsbAudioController.getClockValid(session) } returns false
        val settle = UsbSampleRateConfigurator.awaitClockSettled(session, sleep = sleep)
        assertEquals(false, settle.valid)
        assertEquals(0, settle.extraWaits)
        assertEquals(1, sleeps.size)
    }

    // ---- #122: the clock is changed with the streaming interface on alt 0 ----

    private fun idleWrite(session: UsbAudioSession, format: UacStreamFormat) =
        UsbSampleRateConfigurator.writeSampleRateWithStreamIdle(session, format, RATE, sleep = sleep)

    @Test
    fun `UAC2 and UAC3 go to alt 0, write the clock, then reselect the format's alt-setting`() {
        for (version in listOf(2, 3)) {
            val session = session(version)
            val format = format()
            every { UsbAudioController.selectZeroBandwidth(session) } returns ControlWriteOutcome.Success
            every { UsbAudioController.setClockFrequency(session, RATE) } returns ClockWriteOutcome.Confirmed(RATE)
            every { UsbAudioController.selectFormat(session, format) } returns ControlWriteOutcome.Success

            val result = idleWrite(session, format)

            assertTrue(result.write.accepted)
            assertTrue(result.altRestored)
            verifyOrder {
                UsbAudioController.selectZeroBandwidth(session)
                UsbAudioController.setClockFrequency(session, RATE)
                UsbAudioController.selectFormat(session, format)
            }
        }
    }

    @Test
    fun `UAC1 keeps its alt-setting, since its rate lives on the streaming endpoint`() {
        val session = session(1)
        every { UsbAudioController.setUac1EndpointSampleRate(session, ENDPOINT, RATE) } returns
            ClockWriteOutcome.Confirmed(RATE)

        val result = idleWrite(session, format())

        assertTrue(result.write.accepted)
        assertTrue(result.altRestored)
        verify(exactly = 0) { UsbAudioController.selectZeroBandwidth(any()) }
        verify(exactly = 0) { UsbAudioController.selectFormat(any(), any()) }
    }

    @Test
    fun `a read-only clock is not written, so the alt-setting is left alone`() {
        val session = session(2, clockWritable = false)
        every { UsbAudioController.getClockFrequency(session) } returns RATE

        val result = idleWrite(session, format())

        assertTrue(result.write.accepted)
        verify(exactly = 0) { UsbAudioController.selectZeroBandwidth(any()) }
        verify(exactly = 0) { UsbAudioController.selectFormat(any(), any()) }
    }

    @Test
    fun `if alt 0 can't be selected the clock is written anyway and nothing is reselected`() {
        val session = session(2)
        every { UsbAudioController.selectZeroBandwidth(session) } returns ControlWriteOutcome.TransferFailed
        every { UsbAudioController.setClockFrequency(session, RATE) } returns ClockWriteOutcome.Confirmed(RATE)

        val result = idleWrite(session, format())

        assertTrue(result.write.accepted)
        assertTrue(result.altRestored)
        verify(exactly = 1) { UsbAudioController.setClockFrequency(session, RATE) }
        verify(exactly = 0) { UsbAudioController.selectFormat(any(), any()) }
    }

    @Test
    fun `the format's alt-setting is reselected even when the clock write fails`() {
        val session = session(2)
        val format = format()
        every { UsbAudioController.selectZeroBandwidth(session) } returns ControlWriteOutcome.Success
        every { UsbAudioController.setClockFrequency(session, RATE) } returns ClockWriteOutcome.Mismatch(RATE, 44100)
        every { UsbAudioController.selectFormat(session, format) } returns ControlWriteOutcome.Success

        val result = idleWrite(session, format)

        assertFalse(result.write.accepted)
        assertTrue(result.altRestored)
        verify(exactly = 1) { UsbAudioController.selectFormat(session, format) }
    }

    @Test
    fun `a failed reselect is reported even though the clock write succeeded`() {
        val session = session(2)
        val format = format()
        every { UsbAudioController.selectZeroBandwidth(session) } returns ControlWriteOutcome.Success
        every { UsbAudioController.setClockFrequency(session, RATE) } returns ClockWriteOutcome.Confirmed(RATE)
        every { UsbAudioController.selectFormat(session, format) } returns ControlWriteOutcome.TransferFailed

        val result = idleWrite(session, format)

        assertTrue(result.write.accepted)
        assertFalse(result.altRestored)
    }

    // ---- #129 ----

    @Test
    fun `the clock selector is set before the clock it leads to is written`() {
        val base = session(2)
        val session = base.copy(topology = base.topology.copy(clockSelectorId = 5, clockSelectorPin = 1))
        every { UsbAudioController.selectClockInput(session) } returns ControlWriteOutcome.Success
        every { UsbAudioController.setClockFrequency(session, RATE) } returns ClockWriteOutcome.Confirmed(RATE)

        assertTrue(write(session, format()).accepted)
        verifyOrder {
            UsbAudioController.selectClockInput(session)
            UsbAudioController.setClockFrequency(session, RATE)
        }
    }

    @Test
    fun `no selector request without a selector on the path`() {
        val session = session(2)
        every { UsbAudioController.setClockFrequency(session, RATE) } returns ClockWriteOutcome.Confirmed(RATE)

        write(session, format())
        verify(exactly = 0) { UsbAudioController.selectClockInput(any()) }
    }
}
