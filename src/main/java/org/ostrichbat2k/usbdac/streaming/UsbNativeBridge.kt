/*
 * Copyright (c) 2026 libusbdac Project
 * UsbNativeBridge.kt is part of libusbdac.
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
 * Every native entry point [UsbIsoStreamEngine] needs, as an interface instead of a direct
 * reference to the real [UsbNative] object -- lets a test inject a fake implementation instead
 * of ever touching [UsbNative], whose `init {}` block loads the native library the instant
 * anything merely references it, throwing `UnsatisfiedLinkError` outside a real device process
 * (confirmed empirically; see #73). [UsbNativeBridgeImpl] is the only thing that still talks to
 * [UsbNative] directly; every real caller gets it via [UsbIsoStreamEngine]'s own default
 * constructor argument.
 *
 * Covers every `external fun` [UsbNative] declares, including [disconnectKernelDriver] (used by
 * [org.ostrichbat2k.usbdac.protocol.UsbAudioController] directly, not [UsbIsoStreamEngine] --
 * included here anyway so this interface stays a complete, one-to-one mirror of [UsbNative]
 * rather than drifting to cover only whichever functions happened to need a seam first).
 */
interface UsbNativeBridge {
    /** See [UsbNative.disconnectKernelDriver]. */
    fun disconnectKernelDriver(fd: Int, interfaceNumber: Int): Boolean

    /** See [UsbNative.getUsbSpeed]. */
    fun getUsbSpeed(fd: Int): Int

    /** See [UsbNative.openIsoStream]. */
    fun openIsoStream(
        fd: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        packetsPerUrb: Int,
        numUrbs: Int,
    ): Long

    /** See [UsbNative.submitIso]. */
    fun submitIso(handle: Long, slot: Int, data: ByteArray, packetLengths: IntArray): Boolean

    /** See [UsbNative.reapIso]. */
    fun reapIso(handle: Long): Int

    /** See [UsbNative.cancelIso]. */
    fun cancelIso(handle: Long)

    /** See [UsbNative.closeIsoStream]. */
    fun closeIsoStream(handle: Long)

    /** See [UsbNative.submitIsoRead]. */
    fun submitIsoRead(handle: Long, slot: Int, packetLengths: IntArray): Boolean

    /** See [UsbNative.readIsoSlotData]. */
    fun readIsoSlotData(handle: Long, slot: Int, dst: ByteArray): Int

    /** See [UsbNative.getPacketErrorStats]. */
    fun getPacketErrorStats(handle: Long): LongArray

    companion object {
        /**
         * Mirrors [UsbNative.REAP_ERROR_TRANSIENT] exactly -- duplicated rather than referenced,
         * so a caller (including a test) can use this without ever touching the real [UsbNative]
         * object (see this interface's own doc comment). Keep in sync if that one ever changes.
         */
        const val REAP_ERROR_TRANSIENT = -1

        /** Mirrors [UsbNative.REAP_ERROR_FATAL] exactly -- see [REAP_ERROR_TRANSIENT]'s doc comment. */
        const val REAP_ERROR_FATAL = -2
    }
}

/**
 * The only caller of the real [UsbNative] object left once [UsbIsoStreamEngine] goes through
 * [UsbNativeBridge] instead -- see that interface's own doc comment. Pure indirection: every
 * function here does nothing but forward to its [UsbNative] counterpart, same argument order,
 * same call.
 */
object UsbNativeBridgeImpl : UsbNativeBridge {
    override fun disconnectKernelDriver(fd: Int, interfaceNumber: Int) =
        UsbNative.disconnectKernelDriver(fd, interfaceNumber)

    override fun getUsbSpeed(fd: Int) = UsbNative.getUsbSpeed(fd)

    override fun openIsoStream(
        fd: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        packetsPerUrb: Int,
        numUrbs: Int,
    ) = UsbNative.openIsoStream(fd, endpointAddress, maxPacketSize, packetsPerUrb, numUrbs)

    override fun submitIso(handle: Long, slot: Int, data: ByteArray, packetLengths: IntArray) =
        UsbNative.submitIso(handle, slot, data, packetLengths)

    override fun reapIso(handle: Long) = UsbNative.reapIso(handle)

    override fun cancelIso(handle: Long) = UsbNative.cancelIso(handle)

    override fun closeIsoStream(handle: Long) = UsbNative.closeIsoStream(handle)

    override fun submitIsoRead(handle: Long, slot: Int, packetLengths: IntArray) =
        UsbNative.submitIsoRead(handle, slot, packetLengths)

    override fun readIsoSlotData(handle: Long, slot: Int, dst: ByteArray) =
        UsbNative.readIsoSlotData(handle, slot, dst)

    override fun getPacketErrorStats(handle: Long) = UsbNative.getPacketErrorStats(handle)
}
