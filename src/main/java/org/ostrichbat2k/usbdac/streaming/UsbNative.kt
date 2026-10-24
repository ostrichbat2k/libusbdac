/*
 * Copyright (c) 2026 libusbdac Project
 * UsbNative.kt is part of libusbdac.
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

/**
 * Raw USBDEVFS_IOCTL access that android.hardware.usb.UsbDeviceConnection doesn't expose: a
 * driver-disconnect that doesn't also claim the interface. See usb_native.cpp for why this is
 * needed instead of claimInterface(force=true).
 */
object UsbNative {
    init {
        System.loadLibrary("usbnative")
    }

    /** Evicts whatever kernel driver is bound to [interfaceNumber] on [fd], without claiming it. */
    external fun disconnectKernelDriver(fd: Int, interfaceNumber: Int): Boolean

    /**
     * The negotiated USB_SPEED_* value for the device at [fd] (0=unknown 1=low 2=full 3=high
     * 4=wireless 5=super 6=super+, per the kernel's `linux/usb/ch9.h`), or -1 on error. See the
     * native implementation's doc comment for why this matters for isochronous pacing.
     */
    external fun getUsbSpeed(fd: Int): Int

    /**
     * Allocates [numUrbs] isochronous URBs of [packetsPerUrb] packets each (every packet
     * [maxPacketSize] bytes) for [endpointAddress] on [fd]. Returns an opaque native handle, or 0
     * on failure.
     */
    external fun openIsoStream(
        fd: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        packetsPerUrb: Int,
        numUrbs: Int,
    ): Long

    /**
     * Fills URB [slot] with bytes from [data] (zero-padded if shorter than the sum of
     * [packetLengths]) and submits it with those per-packet lengths -- see
     * [IsoPacketScheduler] for why packet lengths vary rather than always being the endpoint's
     * max packet size.
     */
    external fun submitIso(handle: Long, slot: Int, data: ByteArray, packetLengths: IntArray): Boolean

    /** Blocks until a submitted URB completes; returns its slot index, or -1 on error/cancel. */
    external fun reapIso(handle: Long): Int

    /**
     * Cancels all outstanding URBs, unblocking any in-flight [reapIso] call on another thread.
     * Safe to call concurrently with [reapIso]/[submitIso]; always call this and join() the
     * thread using the stream *before* [closeIsoStream], not after.
     */
    external fun cancelIso(handle: Long)

    /**
     * Drains remaining completions and frees the stream. Only safe once no other thread is still
     * calling [reapIso]/[submitIso] on this handle (confirmed via [cancelIso] + Thread.join()).
     */
    external fun closeIsoStream(handle: Long)
}
