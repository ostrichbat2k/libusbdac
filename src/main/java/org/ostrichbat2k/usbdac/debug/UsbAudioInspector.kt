/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioInspector.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.debug

import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UsbAudioController
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorHelper
import org.ostrichbat2k.usbdac.protocol.UsbAudioDescriptorParserUac3
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import timber.log.Timber as L

/**
 * Opens a permitted USB device and walks every one of its configurations looking for a UAC 3.0
 * playback topology, logging what it finds. The enumeration half (everything up to and including
 * the topology walk) is read-only: it does not switch the device's active configuration or claim
 * any interface. The probe half that follows (clock/mute/volume) does claim the interface via
 * [UsbAudioController.activate] -- see [isSessionActive]'s doc comment for why that's guarded.
 *
 * `internal`, not public (#56) -- same reasoning as [UsbAudioController]'s own doc comment: this
 * is a raw debugging probe (its own log lines still say "Phase 4 probe"), not a supported library
 * entry point, and has no current caller anywhere in this library or in Auxio.
 *
 * The probe's `48000`-fixed clock/channel-floor values are a smoke-test default, not real
 * negotiated playback configuration -- unlike [org.ostrichbat2k.usbdac.activation.UsbDacActivator],
 * this never queries the device's actual Clock Source supported-rate range, so it should not be
 * taken as evidence of what rate a real activation would pick.
 */
internal object UsbAudioInspector {
    /**
     * [isSessionActive] lets a caller that already holds a real [org.ostrichbat2k.usbdac.UsbAudioManager]
     * session for this device tell this probe not to open a second, independent one (#57) --
     * [UsbAudioController.activate] has no awareness of (or coordination with) whatever claim
     * [org.ostrichbat2k.usbdac.UsbAudioManagerImpl]/[org.ostrichbat2k.usbdac.claim.UsbClaimController]
     * already holds, so running both at once can leave either side's view of the interface's
     * claim state wrong. Defaults to `{ false }` (no guard) since this probe currently has no
     * caller to wire a real check through -- any future caller that does have a live
     * [org.ostrichbat2k.usbdac.UsbAudioManager] in hand should pass a real predicate instead.
     */
    fun inspect(context: Context, device: UsbDevice, isSessionActive: () -> Boolean = { false }) {
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
                val bytes = UsbAudioDescriptorHelper.fetchConfigurationDescriptor(connection, i) ?: continue
                val found = UsbAudioDescriptorParserUac3.parseUac3Playback(bytes)
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
        if (isSessionActive()) {
            L.w("Skipping probe-claim for ${device.deviceName}: a real session already holds this device")
            return
        }
        val session = UsbAudioController.activate(context, device, found) ?: return
        try {
            // Smoke-test default, not a real negotiated rate -- see this object's own doc comment.
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
        } finally {
            UsbAudioController.release(session)
        }
    }
}
