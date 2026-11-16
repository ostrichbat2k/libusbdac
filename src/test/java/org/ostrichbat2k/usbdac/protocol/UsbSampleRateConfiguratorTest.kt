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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ostrichbat2k.usbdac.UsbAudioTuning
import org.ostrichbat2k.usbdac.protocol.UsbAudioController.ClockWriteOutcome

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

    private fun session(uacVersion: Int) =
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
                ),
            controlInterface = mockk<UsbInterface>(relaxed = true),
            streamingAltSettings = emptyMap(),
        )

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
            val write = UsbSampleRateConfigurator.writeSampleRate(session, ENDPOINT, RATE, sleep = sleep)
            assertTrue(write.confirmed)
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
        val write = UsbSampleRateConfigurator.writeSampleRate(session, ENDPOINT, RATE, sleep = sleep)
        assertTrue(write.confirmed)
        verify(exactly = 1) { UsbAudioController.setUac1EndpointSampleRate(session, ENDPOINT, RATE) }
        verify(exactly = 0) { UsbAudioController.setClockFrequency(any(), any()) }
    }

    @Test
    fun `without retries a failed write is reported after a single attempt and no delay`() {
        val session = session(2)
        every { UsbAudioController.setClockFrequency(session, RATE) } returns ClockWriteOutcome.TransferFailed
        val write = UsbSampleRateConfigurator.writeSampleRate(session, ENDPOINT, RATE, sleep = sleep)
        assertFalse(write.confirmed)
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
        val write = UsbSampleRateConfigurator.writeSampleRate(session, ENDPOINT, RATE, maxRetries = 3, sleep = sleep)
        assertTrue(write.confirmed)
        assertEquals(2, write.retries)
        assertEquals(List(2) { UsbAudioTuning.DAC_SETTLE_DELAY_MS }, sleeps)
    }

    @Test
    fun `retries stop at the limit and the last outcome is reported`() {
        val session = session(2)
        every { UsbAudioController.setClockFrequency(session, RATE) } returns ClockWriteOutcome.Mismatch(RATE, 48000)
        val write = UsbSampleRateConfigurator.writeSampleRate(session, ENDPOINT, RATE, maxRetries = 3, sleep = sleep)
        assertFalse(write.confirmed)
        assertEquals(3, write.retries)
        verify(exactly = 4) { UsbAudioController.setClockFrequency(session, RATE) }
        assertEquals(3, sleeps.size)
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
}
