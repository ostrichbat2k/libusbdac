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
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioOutputProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
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

    // #130: format selection skips alt-settings whose wMaxPacketSize can't hold the rate's packets.

    private fun uac1Format(alt: Int, maxPacketSize: Int) =
        testFormat.copy(alternateSetting = alt, maxPacketSize = maxPacketSize, sampleRates = listOf(48000, 96000))

    private fun providerFor(formats: List<UacStreamFormat>, usbSpeed: Int?): UsbAudioOutputProvider {
        val session = testSession.copy(topology = testTopology.copy(uacVersion = 1, formats = formats))
        return UsbAudioOutputProvider(mockk(relaxed = true), session, mockk(relaxed = true), usbSpeed = usbSpeed)
    }

    private fun supportLevel(provider: UsbAudioOutputProvider, sampleRate: Int): Int =
        provider
            .getFormatSupport(
                AudioOutputProvider.FormatConfig.Builder(
                        Format.Builder()
                            .setChannelCount(2)
                            .setSampleRate(sampleRate)
                            .setPcmEncoding(C.ENCODING_PCM_16BIT)
                            .build())
                    .build())
            .supportLevel

    @Test
    fun `a rate whose packets overflow wMaxPacketSize at full speed is unsupported`() {
        // 96 frames x 4 bytes = 384 bytes per 1 ms packet, against a 192-byte endpoint.
        val provider = providerFor(listOf(uac1Format(1, 192)), usbSpeed = 2)

        assertEquals(AudioOutputProvider.FORMAT_UNSUPPORTED, supportLevel(provider, 96000))
        assertEquals(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY, supportLevel(provider, 48000))
    }

    @Test
    fun `the same endpoint carries the rate at high speed`() {
        val provider = providerFor(listOf(uac1Format(1, 192)), usbSpeed = 3)

        assertEquals(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY, supportLevel(provider, 96000))
    }

    @Test
    fun `a too-small alt-setting is skipped in favour of one that fits`() {
        val provider = providerFor(listOf(uac1Format(1, 192), uac1Format(2, 400)), usbSpeed = 2)

        assertEquals(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY, supportLevel(provider, 96000))
    }

    @Test
    fun `an unknown USB speed skips the check rather than rejecting formats`() {
        val provider = providerFor(listOf(uac1Format(1, 192)), usbSpeed = null)

        assertEquals(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY, supportLevel(provider, 96000))
    }
}
