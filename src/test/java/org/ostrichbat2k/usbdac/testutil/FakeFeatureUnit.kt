/*
 * Copyright (c) 2026 libusbdac Project
 * FakeFeatureUnit.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.testutil

import android.hardware.usb.UsbDeviceConnection
import io.mockk.every

/**
 * A stateful fake Feature Unit behind a mocked [UsbDeviceConnection.controlTransfer] -- real
 * per-channel mute/volume state that a `SET_CUR` genuinely mutates and a later `GET_CUR`
 * genuinely reflects, addressed the same way [org.ostrichbat2k.usbdac.protocol.UsbAudioController]
 * itself addresses them (`wValue`/`wIndex`). The per-class unit tests only ever needed one-shot
 * canned responses; a functional test needs round-tripping state to assert a *sequence* ("forced
 * to unity, then later genuinely restored", or "overwritten by a stale target"), not just one
 * isolated call.
 *
 * Shared between [org.ostrichbat2k.usbdac.UsbAudioManagerFunctionalTest] and
 * [org.ostrichbat2k.usbdac.streaming.UsbAudioOutputVolumeRestoreTest].
 */
class FakeFeatureUnit(private val unitId: Int, private val controlInterfaceId: Int) {
    private val mute = mutableMapOf<Int, Boolean>().withDefault { false }
    private val volume = mutableMapOf<Int, Int>().withDefault { 0 }
    var range: Pair<Int, Int> = -9984 to 0 // -39dB..0dB (unity) in 1/256dB units.

    fun volumeOf(channel: Int): Int = volume.getValue(channel)

    fun install(connection: UsbDeviceConnection) {
        val wIndex = (unitId shl 8) or controlInterfaceId
        every { connection.controlTransfer(any(), any(), any(), any(), any(), any(), any()) } answers
            {
                val requestType = arg<Int>(0)
                val request = arg<Int>(1)
                val wValue = arg<Int>(2)
                val index = arg<Int>(3)
                val buffer = arg<ByteArray?>(4)
                if (index != wIndex || buffer == null) return@answers -1
                val control = wValue shr 8
                val channel = wValue and 0xFF
                when {
                    control == 0x01 && requestType == 0xA1 -> { // GET_CUR mute
                        buffer[0] = if (mute.getValue(channel)) 1 else 0
                        1
                    }
                    control == 0x01 && requestType == 0x21 -> { // SET_CUR mute
                        mute[channel] = buffer[0].toInt() != 0
                        1
                    }
                    control == 0x02 && requestType == 0xA1 && request == 0x02 -> { // GET_RANGE volume
                        buffer[2] = (range.first and 0xFF).toByte()
                        buffer[3] = ((range.first shr 8) and 0xFF).toByte()
                        buffer[4] = (range.second and 0xFF).toByte()
                        buffer[5] = ((range.second shr 8) and 0xFF).toByte()
                        8
                    }
                    control == 0x02 && requestType == 0xA1 -> { // GET_CUR volume
                        val v = volume.getValue(channel)
                        buffer[0] = (v and 0xFF).toByte()
                        buffer[1] = ((v shr 8) and 0xFF).toByte()
                        2
                    }
                    control == 0x02 && requestType == 0x21 -> { // SET_CUR volume
                        val v = (buffer[0].toInt() and 0xFF) or (buffer[1].toInt() shl 8)
                        volume[channel] = v
                        2
                    }
                    else -> -1
                }
            }
    }
}
