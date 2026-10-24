/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDeviceDiscoveryImplTest.kt is part of libusbdac.
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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.widget.Toast
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ostrichbat2k.usbdac.UsbDacPreferences

class UsbDeviceDiscoveryImplTest {

    private fun fakeUsbDevice(
        deviceName: String,
        productName: String? = "Test DAC",
        vendorId: Int = 0x1234,
        productId: Int = 0x5678,
        interfaceClasses: List<Int> = listOf(UsbConstants.USB_CLASS_AUDIO),
    ): UsbDevice {
        val device = mockk<UsbDevice>(relaxed = true)
        every { device.deviceName } returns deviceName
        every { device.productName } returns productName
        every { device.vendorId } returns vendorId
        every { device.productId } returns productId
        every { device.interfaceCount } returns interfaceClasses.size
        interfaceClasses.forEachIndexed { i, cls ->
            val iface = mockk<UsbInterface>(relaxed = true)
            every { iface.interfaceClass } returns cls
            every { device.getInterface(i) } returns iface
        }
        return device
    }

    private lateinit var context: Context
    private lateinit var usbManager: UsbManager
    private lateinit var usbSettings: UsbDacPreferences
    private var detachedCalls = 0
    private val refreshedCalls = mutableListOf<List<UsbAudioDevice>>()
    private lateinit var discovery: UsbDeviceDiscoveryImpl

    private fun setDeviceList(vararg devices: UsbDevice) {
        every { usbManager.deviceList } returns HashMap(devices.associateBy { it.deviceName })
    }

    @Before
    fun setUp() {
        mockkStatic(Toast::class)
        every { Toast.makeText(any(), any<String>(), any()) } returns mockk(relaxed = true)
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk(relaxed = true)
        // Intent/IntentFilter's real bodies throw "not mocked" in a plain JVM unit test; mocking
        // their constructors routes every instance method through a relaxed mock instead.
        mockkConstructor(Intent::class, IntentFilter::class)
        every { anyConstructed<Intent>().setPackage(any()) } returns mockk(relaxed = true)
        every { anyConstructed<IntentFilter>().addAction(any()) } just Runs

        context = mockk(relaxed = true)
        every { context.packageName } returns "org.ostrichbat2k.usbdac.test"
        usbManager = mockk(relaxed = true)
        usbSettings = mockk(relaxed = true)
        setDeviceList()

        discovery =
            UsbDeviceDiscoveryImpl(
                context = context,
                usbManager = usbManager,
                usbSettings = usbSettings,
                actionUsbPermission = "org.ostrichbat2k.usbdac.test.USB_PERMISSION",
                onDeviceDetached = { detachedCalls++ },
                onRefreshed = { refreshedCalls += it },
            )
    }

    @After
    fun tearDown() {
        unmockkStatic(Toast::class)
        unmockkStatic(PendingIntent::class)
        unmockkConstructor(Intent::class, IntentFilter::class)
    }

    @Test
    fun `refresh only surfaces devices with a USB Audio Class interface`() {
        val audio = fakeUsbDevice("audio", interfaceClasses = listOf(UsbConstants.USB_CLASS_AUDIO))
        val nonAudio = fakeUsbDevice("hid", interfaceClasses = listOf(UsbConstants.USB_CLASS_HID))
        setDeviceList(audio, nonAudio)

        discovery.refresh()

        assertEquals(listOf("audio"), discovery.devices.value.map { it.device.deviceName })
    }

    @Test
    fun `refresh treats a composite device with any audio interface as an audio device`() {
        val composite =
            fakeUsbDevice("composite", interfaceClasses = listOf(UsbConstants.USB_CLASS_HID, UsbConstants.USB_CLASS_AUDIO))
        setDeviceList(composite)

        discovery.refresh()

        assertEquals(listOf("composite"), discovery.devices.value.map { it.device.deviceName })
    }

    @Test
    fun `refresh publishes the result to both devices and onRefreshed`() {
        val device = fakeUsbDevice("audio")
        setDeviceList(device)

        discovery.refresh()

        assertEquals(1, discovery.devices.value.size)
        assertEquals(1, refreshedCalls.size)
        assertEquals(discovery.devices.value, refreshedCalls.last())
    }

    @Test
    fun `a newly seen device name triggers exactly one toast, not on every subsequent refresh`() {
        val device = fakeUsbDevice("audio", productName = "Shiny DAC")
        setDeviceList(device)

        discovery.refresh()
        discovery.refresh()
        discovery.refresh()

        verify(exactly = 1) { Toast.makeText(context, "USB Audio Device detected: Shiny DAC", Toast.LENGTH_LONG) }
    }

    @Test
    fun `an unpermitted device is auto-requested exactly once while still attached`() {
        val device = fakeUsbDevice("audio")
        every { usbSettings.enabled } returns true
        every { usbManager.hasPermission(device) } returns false
        setDeviceList(device)

        discovery.refresh()
        discovery.refresh()
        discovery.refresh()

        verify(exactly = 1) { usbManager.requestPermission(device, any()) }
    }

    @Test
    fun `a device that already has permission is never auto-requested`() {
        val device = fakeUsbDevice("audio")
        every { usbSettings.enabled } returns true
        every { usbManager.hasPermission(device) } returns true
        setDeviceList(device)

        discovery.refresh()

        verify(exactly = 0) { usbManager.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
        assertTrue(discovery.devices.value.single().hasPermission)
    }

    @Test
    fun `auto-permission-request is skipped entirely while usbSettings is disabled`() {
        val device = fakeUsbDevice("audio")
        every { usbSettings.enabled } returns false
        every { usbManager.hasPermission(device) } returns false
        setDeviceList(device)

        discovery.refresh()

        verify(exactly = 0) { usbManager.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
    }

    @Test
    fun `a detach-then-replug of a still-unpermitted device is requested again`() {
        val device = fakeUsbDevice("audio")
        every { usbSettings.enabled } returns true
        every { usbManager.hasPermission(device) } returns false

        setDeviceList(device)
        discovery.refresh() // first attempt, now suppressed

        setDeviceList() // detached
        discovery.refresh() // prunes permissionRequestedFor

        setDeviceList(device) // replugged, still unpermitted
        discovery.refresh() // fresh attempt expected

        verify(exactly = 2) { usbManager.requestPermission(device, any()) }
    }

    @Test
    fun `requestPermission builds a broadcast PendingIntent with the configured flags and forwards it`() {
        val device = fakeUsbDevice("audio")

        discovery.requestPermission(device)

        verify {
            PendingIntent.getBroadcast(
                context,
                0,
                any(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        verify { anyConstructed<Intent>().setPackage(any()) }
        verify { usbManager.requestPermission(device, any()) }
    }

    @Test
    fun `start registers a receiver for attach, detach, and the permission action, then refreshes once`() {
        val device = fakeUsbDevice("audio")
        setDeviceList(device)

        discovery.start()

        verify(exactly = 1) { context.registerReceiver(any(), any<IntentFilter>()) }
        verify { anyConstructed<IntentFilter>().addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED) }
        verify { anyConstructed<IntentFilter>().addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        verify { anyConstructed<IntentFilter>().addAction("org.ostrichbat2k.usbdac.test.USB_PERMISSION") }
        assertEquals(1, discovery.devices.value.size)
    }

    @Test
    fun `start is idempotent -- a second call does not re-register`() {
        discovery.start()
        discovery.start()

        verify(exactly = 1) { context.registerReceiver(any(), any<IntentFilter>()) }
    }
}
