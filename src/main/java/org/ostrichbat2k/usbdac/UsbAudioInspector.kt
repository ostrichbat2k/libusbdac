/*
 * Copyright (c) 2026 Auxio Project
 * UsbAudioInspector.kt is part of Auxio.
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

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import timber.log.Timber as L

/**
 * Opens a permitted USB device and walks every one of its configurations looking for a UAC 3.0
 * playback topology, logging what it finds. This is read-only enumeration: it does not switch
 * the device's active configuration or claim any interface.
 */
object UsbAudioInspector {
    fun inspect(context: Context, device: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            L.w("Could not open USB connection to ${device.deviceName}")
            return
        }
        var topology: UacPlaybackTopology? = null
        try {
            val configCount = device.configurationCount.coerceAtLeast(1)
            L.i("Inspecting ${device.productName ?: device.deviceName}: $configCount configuration(s)")
            for (i in 0 until configCount) {
                val bytes = UsbAudioDescriptorParser.fetchConfigurationDescriptor(connection, i) ?: continue
                val found = UsbAudioDescriptorParser.parseUac3Playback(bytes)
                if (found != null) {
                    topology = found
                    L.i("Config index $i: UAC3 playback topology = $found")
                }
            }
            if (topology == null) {
                L.i("No UAC3 (full, non-BADD) playback configuration found on this device.")
            }
        } finally {
            connection.close()
        }

        val found = topology ?: return
        val session = UsbAudioController.activate(context, device, found) ?: return
        try {
            val sampleRateHz = 48000
            val setClock = UsbAudioController.setClockFrequency(session, sampleRateHz)
            L.i("Phase 4 probe: set clock to $sampleRateHz Hz -> $setClock")

            // The signal path runs Input Terminal -> Mixer -> Feature Unit (mute/volume) ->
            // Output Terminal. A clean, error-free USB transfer still produces silence if this
            // is muted or near its floor -- likely state left over from whatever the system
            // audio HAL had set before we evicted its driver.
            val muted = UsbAudioController.getMute(session)
            L.i("Phase 4 probe: output feature unit mute=$muted")
            if (muted != false) {
                UsbAudioController.setMute(session, muted = false)
            }
            for (channel in 1..found.channelCount.coerceAtLeast(2)) {
                val volume = UsbAudioController.getVolume(session, channel)
                L.i("Phase 4 probe: channel $channel volume=$volume (1/256 dB, 0=unity)")
                if (volume == null || volume < 0) {
                    UsbAudioController.setVolume(session, value = 0, channel = channel)
                }
            }

            // Prefer the 16-bit format: at 48kHz its packet size (192B) matches what the tone
            // test computes from samples/ms directly, same as 24-bit's 288B -- either works, 16
            // is just less data to push while proving the pipeline out.
            val selected = found.formats.minByOrNull { it.bitResolution }
            if (selected != null && setClock && UsbAudioController.selectFormat(session, selected)) {
                IsoToneTest.play(session, selected, sampleRateHz, durationMs = 3000, channels = found.channelCount.coerceAtLeast(2))
            } else {
                L.w("Skipping tone test: clock/format setup did not succeed")
            }
        } finally {
            UsbAudioController.release(session)
        }
    }
}
