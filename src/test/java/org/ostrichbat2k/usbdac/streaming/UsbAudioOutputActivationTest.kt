/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioOutputActivationTest.kt is part of libusbdac.
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

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.UsbDacPreferences
import org.ostrichbat2k.usbdac.protocol.SyncType
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioController
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession
import org.ostrichbat2k.usbdac.protocol.UsbSampleRateConfigurator
import org.ostrichbat2k.usbdac.testutil.FakeFeatureUnit

/**
 * Drives [UsbAudioOutput.play]'s real background activation attempt -- claim, format, mute,
 * clock change -- with the clock write stubbed, along the paths that never reach [UsbNative]
 * (an attempt that ends up not starting the stream). Covers how that attempt interacts with
 * [UsbAudioOutput.pause]/[UsbAudioOutput.play] calls landing while it runs.
 */
class UsbAudioOutputActivationTest {
    private val topology =
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
            formats = emptyList(),
        )

    private val format =
        UacStreamFormat(
            alternateSetting = 1,
            bitResolution = 24,
            subslotSize = 4,
            clusterDescriptorId = -1,
            endpointAddress = 0x02,
            maxPacketSize = 192,
            syncType = SyncType.ASYNC,
        )

    private val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)

    private val session: UsbAudioSession =
        mockk<UsbDeviceConnection>(relaxed = true).let { connection ->
            featureUnit.install(connection)
            UsbAudioSession(
                device = mockk(relaxed = true),
                connection = connection,
                topology = topology,
                controlInterface = mockk<UsbInterface>(relaxed = true) { every { id } returns 0 },
                streamingAltSettings = emptyMap(),
            )
        }

    private val settings: UsbDacPreferences =
        mockk(relaxed = true) {
            every { volumeDb } returns -2000
            every { maxVolumePercent } returns 100
        }

    private val manager = mockk<UsbAudioManager>(relaxed = true)

    /** What the stubbed clock write runs just before returning, to land calls mid-attempt. */
    private var duringClockWrite: () -> Unit = {}

    private lateinit var output: UsbAudioOutput

    @BeforeEach
    fun setUp() {
        mockkObject(UsbSampleRateConfigurator)
        every { UsbSampleRateConfigurator.writeSampleRateWithStreamIdle(any(), any(), any(), any(), any()) } answers
            {
                duringClockWrite()
                UsbSampleRateConfigurator.IdleRateWrite(
                    UsbSampleRateConfigurator.RateWrite(
                        UsbAudioController.ClockWriteOutcome.Confirmed(44100), retries = 0),
                    altRestored = true)
            }
        every { manager.ensureFormatActive(any(), any()) } returns true
        every { manager.consumeHardwareReset() } returns false
        output =
            UsbAudioOutput(
                session = session,
                format = format,
                channels = topology.channelCount,
                sampleRateHz = 44100,
                usbSettings = settings,
                usbAudioManager = manager,
            )
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(UsbSampleRateConfigurator)
    }

    /**
     * Regression shape for #149: pause during the relock, so the attempt declines; then a play()
     * arrives after that decision but before the attempt has finished. That play() used to see
     * `activating` still set and do nothing, leaving nothing streaming. Now the attempt notices
     * on its way out and activates again -- here a second attempt whose claim fails, so it ends
     * there instead of reaching the native stream.
     */
    @Test
    fun `a play arriving as a declined activation finishes starts another attempt`() {
        every { manager.requestClaim() } returnsMany
            listOf(UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED, UsbAudioManager.ClaimOutcome.FAILED)
        duringClockWrite = { output.pause() }
        var lateplay = true
        every { manager.endClaimAttempt() } answers
            {
                if (lateplay) {
                    lateplay = false
                    output.play()
                }
            }

        output.play()

        verify(timeout = 2000, exactly = 2) { manager.requestClaim() }
        verify(exactly = 0) { manager.notifyStreamingStarted() }
    }

    /** #149: a pause that stays paused never retriggers -- the re-check isn't a retry loop. */
    @Test
    fun `a declined activation with no later play does not activate again`() {
        every { manager.requestClaim() } returns UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED
        duringClockWrite = { output.pause() }

        output.play()

        verify(timeout = 2000) { manager.endClaimAttempt() }
        Thread.sleep(100)
        verify(exactly = 1) { manager.requestClaim() }
    }
}
