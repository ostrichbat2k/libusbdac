/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioOutputProviderTest.kt is part of libusbdac.
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
import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioOutputProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession

/**
 * Regression coverage for #51: [UsbAudioOutputProvider.getAudioOutput] used to ignore
 * [UsbAudioManager.ensureFormatActive]'s return value entirely, so a provider whose session had
 * already died (device unplugged mid-relock, or a prior fatal stream error already deactivated
 * it) kept building a new [UsbAudioOutput] against it anyway -- opening a native stream doomed to
 * wedge again, which is what turned one teardown into the repeating pulse #51 reported.
 */
class UsbAudioOutputProviderTest {

    private val testFormat =
        UacStreamFormat(
            alternateSetting = 1,
            bitResolution = 16,
            subslotSize = 2,
            clusterDescriptorId = -1,
            endpointAddress = 0x02,
            maxPacketSize = 192,
        )

    private val testTopology =
        UacPlaybackTopology(
            configurationValue = 1,
            controlInterfaceNumber = 0,
            streamingInterfaceNumber = 1,
            uacVersion = 2,
            clockSourceId = -1,
            outputFeatureUnitId = -1,
            mixerUnitId = -1,
            mixerInputChannelBase = -1,
            powerDomainId = -1,
            powerDomainD1RecoveryUs = 0,
            powerDomainD2RecoveryUs = 0,
            channelCount = 2,
            formats = listOf(testFormat),
        )

    private val testSession =
        UsbAudioSession(
            device = mockk<UsbDevice>(relaxed = true),
            connection = mockk<UsbDeviceConnection>(relaxed = true),
            topology = testTopology,
            controlInterface = mockk<UsbInterface>(relaxed = true),
            streamingAltSettings = emptyMap(),
        )

    private fun testConfig(): AudioOutputProvider.OutputConfig =
        AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

    private fun providerWith(usbAudioManager: UsbAudioManager) =
        UsbAudioOutputProvider(usbAudioManager, testSession, mockk(relaxed = true))

    @Test
    fun `getAudioOutput throws when ensureFormatActive fails`() {
        val usbAudioManager = mockk<UsbAudioManager>(relaxed = true)
        every { usbAudioManager.ensureFormatActive(any(), any()) } returns false

        assertThrows(AudioOutputProvider.InitializationException::class.java) {
            providerWith(usbAudioManager).getAudioOutput(testConfig())
        }
    }

    @Test
    fun `getAudioOutput still returns a working UsbAudioOutput when ensureFormatActive succeeds`() {
        val usbAudioManager = mockk<UsbAudioManager>(relaxed = true)
        every { usbAudioManager.ensureFormatActive(any(), any()) } returns true

        val output = providerWith(usbAudioManager).getAudioOutput(testConfig())

        assertNotNull(output)
    }
}
