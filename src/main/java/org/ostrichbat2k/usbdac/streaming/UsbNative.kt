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

    /**
     * Blocks until a submitted URB completes; returns its slot index, or one of
     * [REAP_ERROR_TRANSIENT]/[REAP_ERROR_FATAL] on failure.
     */
    external fun reapIso(handle: Long): Int

    /**
     * The wait was interrupted (EINTR) -- nothing is actually wrong, call [reapIso] again. Mirrors
     * usb_native.cpp's `kReapErrorTransient` exactly (keep in sync if that ever changes).
     */
    const val REAP_ERROR_TRANSIENT = -1

    /**
     * The stream cannot produce anything more -- device gone, handle closed, or any other
     * REAPURB failure. The caller must stop streaming rather than retry (see #60: retrying
     * forever here is exactly the busy-spin this exists to prevent). Mirrors usb_native.cpp's
     * `kReapErrorFatal` exactly (keep in sync if that ever changes).
     */
    const val REAP_ERROR_FATAL = -2

    /**
     * Cumulative since [openIsoStream], as `[badPacketCount, shortBytesCount]` --
     * processReapedUrb's own per-URB packet scan (usb_native.cpp), surfaced beyond just a logcat
     * line (#78). `[0, 0]` for an unknown/already-closed handle. Safe to call from any thread --
     * backed by native atomics, since the streaming thread writes these on every [reapIso] call.
     */
    external fun getPacketErrorStats(handle: Long): LongArray

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

    /**
     * Submits URB [slot] as an IN (read) transfer of the given [packetLengths] -- the feedback-
     * endpoint counterpart of [submitIso] (#25): no data copied in (the buffer is zeroed instead,
     * since this is a read, not a write), everything else identical (lookup, slot bounds,
     * overflow-checked length sum). [reapIso]/[cancelIso]/[closeIsoStream] work unchanged on a
     * handle opened for reading -- only submission direction differs.
     */
    external fun submitIsoRead(handle: Long, slot: Int, packetLengths: IntArray): Boolean

    /**
     * Copies up to [dst]'s size of bytes from URB [slot]'s completed transfer into [dst]. Returns
     * the number of bytes actually copied (the URB's own `actual_length`, capped to [dst].size),
     * or -1 on an invalid handle/slot. Only meaningful to call right after [reapIso] returns this
     * [slot] on a handle opened via [openIsoStream] for a feedback (IN) endpoint -- the main audio
     * data path never reads URB buffer contents back, only [reapIso]'s own success/failure.
     */
    external fun readIsoSlotData(handle: Long, slot: Int, dst: ByteArray): Int
}
