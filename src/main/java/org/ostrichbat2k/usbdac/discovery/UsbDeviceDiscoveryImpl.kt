/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDeviceDiscoveryImpl.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.discovery

import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.UsbAudioTuning
import org.ostrichbat2k.usbdac.UsbDacPreferences
import org.ostrichbat2k.usbdac.claim.UsbClaimController
import org.ostrichbat2k.usbdac.control.UsbVolumeController
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber as L

/**
 * Everything this app decides about *which* USB Audio Class devices are physically attached and
 * whether we're permitted to use them -- previously mixed into [UsbAudioManager] alongside
 * unrelated concerns (DAC activation, live control state, ABX testing). Pulled out here for the
 * same reason [UsbVolumeController]/[UsbClaimController] were: this is a fully self-contained
 * concern (it never touches a claimed interface, a session, or anything hardware-sequencing-
 * related) that [UsbAudioManager] merely reacts to, not something it needs to own directly.
 *
 * Registers its own [BroadcastReceiver] for exactly the three USB-specific actions this concern
 * needs (attach, detach, our own permission-result broadcast) -- deliberately not
 * `UsbAudioTuning.ACTION_VOLUME_CHANGED` too, even though the pre-extraction code shared one receiver for all
 * four: that broadcast is a volume/control concern with nothing to do with device presence, and
 * [UsbAudioManager] keeps its own separate, smaller receiver for it instead of this class having
 * to know anything about volume routing.
 *
 * [onDeviceDetached] and [onRefreshed] are the only two points where something outside this
 * class's own concern needs to react to what happens here: a detach has to tear down any active
 * DAC session (not this class's job), and [UsbAudioManager]'s own "already tried activating this
 * attachment" bookkeeping needs pruning whenever the attached-device set changes, same timing as
 * before extraction -- synchronously, right after [devices] itself updates.
 */
internal class UsbDeviceDiscoveryImpl(
    private val context: Context,
    private val usbManager: UsbManager,
    private val usbSettings: UsbDacPreferences,
    private val actionUsbPermission: String,
    private val onDeviceDetached: () -> Unit,
    private val onRefreshed: (List<UsbAudioDevice>) -> Unit = {},
) : UsbDeviceDiscovery {
    private val _devices = MutableStateFlow<List<UsbAudioDevice>>(emptyList())
    override val devices: StateFlow<List<UsbAudioDevice>> = _devices.asStateFlow()

    private var started = false
    private var knownDeviceNames = emptySet<String>()
    // Device names (keyed like knownDeviceNames' display names, but the raw deviceName) we've
    // already called requestPermission() for during their current attachment -- so a user's
    // explicit denial doesn't turn into a repeated dialog-spam loop every time the resulting
    // ACTION_USB_PERMISSION broadcast re-triggers refresh() (see onReceive/actionUsbPermission).
    // Pruned down to currently-attached devices on every refresh(), so a physical detach/replug
    // (confirmed, reproduced: doesn't always carry the permission grant over) gets a fresh
    // automatic attempt, same as a genuinely new device.
    private var permissionRequestedFor = emptySet<String>()

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        L.i("USB device attached: ${intentDevice(intent)?.deviceName}")
                        refresh()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        L.i("USB device detached: ${intentDevice(intent)?.deviceName}")
                        onDeviceDetached()
                        refresh()
                    }
                    actionUsbPermission -> {
                        val device = intentDevice(intent)
                        val granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        L.i("USB permission result for ${device?.deviceName}: granted=$granted")
                        if (device != null) {
                            val label = device.productName ?: device.deviceName
                            Toast.makeText(
                                    context,
                                    if (granted) "USB DAC permission granted: $label"
                                    else "USB DAC permission denied: $label",
                                    Toast.LENGTH_LONG,
                                )
                                .show()
                        }
                        refresh()
                    }
                }
            }
        }

    private fun intentDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    /** Begins listening for USB device attach/detach/permission events. Call once at startup. */
    override fun start() {
        if (started) return
        started = true

        val filter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(actionUsbPermission)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        }

        refresh()
    }

    /** Requests permission to use [device]. The result arrives asynchronously via broadcast. */
    override fun requestPermission(device: UsbDevice) {
        val permissionIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(actionUsbPermission).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        usbManager.requestPermission(device, permissionIntent)
    }

    internal fun refresh() {
        val found =
            usbManager.deviceList.values.filter { it.isAudioDevice() }.map {
                UsbAudioDevice(it, usbManager.hasPermission(it))
            }

        val newNames = found.map { it.displayName }.toSet()
        for (name in newNames - knownDeviceNames) {
            Toast.makeText(context, "USB Audio Device detected: $name", Toast.LENGTH_LONG).show()
        }
        knownDeviceNames = newNames

        // Getting permission is cheap and worth doing eagerly -- it's just an Android dialog,
        // never touches the USB interface itself. Actually activating is not: see
        // UsbAudioManager.ensureActivated's doc comment for why that stays lazy instead.
        for (audioDevice in found) {
            val name = audioDevice.device.deviceName
            if (usbSettings.enabled && !audioDevice.hasPermission && name !in permissionRequestedFor) {
                // Auto-request instead of waiting for the user to manually tap "Retry
                // activation" (UsbAudioManager.retryActivation()) -- the common real-world
                // trigger is a replug that didn't carry the grant over, not a first-ever attach.
                // permissionRequestedFor guards against re-asking every time the (denied) result
                // broadcast loops back into another refresh() call below.
                L.i("Permission not granted for ${audioDevice.displayName}, requesting automatically")
                permissionRequestedFor = permissionRequestedFor + name
                requestPermission(audioDevice.device)
            }
        }
        permissionRequestedFor = permissionRequestedFor intersect found.map { it.device.deviceName }.toSet()

        L.d("USB audio devices: ${found.map { it.displayName to it.hasPermission }}")
        _devices.value = found
        onRefreshed(found)
    }

    private fun UsbDevice.isAudioDevice(): Boolean =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
}
