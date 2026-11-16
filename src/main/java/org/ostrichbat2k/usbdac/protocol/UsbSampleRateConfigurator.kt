/*
 * Copyright (c) 2026 libusbdac Project
 * UsbSampleRateConfigurator.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.protocol

import org.ostrichbat2k.usbdac.UsbAudioTuning

/**
 * The steps that put a device on a sample rate and wait for it to get there, in one place (#136).
 * Plug-in activation ([org.ostrichbat2k.usbdac.activation.UsbDacActivatorImpl]) and playback
 * start ([org.ostrichbat2k.usbdac.streaming.UsbAudioOutput]) both need them; each used to carry
 * its own copy, and the copies had drifted.
 *
 * Only the building blocks live here. *When* each is called relative to selecting the streaming
 * alt-setting, muting, and restoring volume is still each caller's own sequence, and the two
 * callers pass different retry counts -- exactly as they behaved before this was extracted.
 */
internal object UsbSampleRateConfigurator {
    /** [outcome] of the last attempt, and how many [retries] beyond the first it took. */
    data class RateWrite(val outcome: UsbAudioController.ClockWriteOutcome, val retries: Int) {
        val confirmed: Boolean
            get() = outcome is UsbAudioController.ClockWriteOutcome.Confirmed
    }

    /**
     * [valid] is the device's last CLOCK_VALID answer: null if it has no Clock Source to ask
     * (UAC 1.0) or the control could not be read. [extraWaits] is how many additional settle
     * delays were spent waiting for it to turn true.
     */
    data class ClockSettle(val valid: Boolean?, val extraWaits: Int)

    /**
     * Writes [hz] to wherever this device keeps its sample rate: the streaming endpoint at
     * [endpointAddress] for UAC 1.0, which has no Clock Source entity, or the Clock Source for
     * UAC 2.0/3.0, where [endpointAddress] is not used. Using the Clock Source request on a
     * UAC 1.0 device was once the cause of a real, reproduced endless playback-error loop.
     *
     * Anything but [UsbAudioController.ClockWriteOutcome.Confirmed] is retried up to [maxRetries]
     * times, with [UsbAudioTuning.DAC_SETTLE_DELAY_MS] before each retry.
     */
    fun writeSampleRate(
        session: UsbAudioSession,
        endpointAddress: Int,
        hz: Int,
        maxRetries: Int = 0,
        sleep: (Long) -> Unit = Thread::sleep,
    ): RateWrite {
        fun attempt(): UsbAudioController.ClockWriteOutcome =
            if (session.topology.uacVersion == 1) {
                UsbAudioController.setUac1EndpointSampleRate(session, endpointAddress, hz)
            } else {
                UsbAudioController.setClockFrequency(session, hz)
            }
        var outcome = attempt()
        var retries = 0
        while (outcome !is UsbAudioController.ClockWriteOutcome.Confirmed && retries < maxRetries) {
            sleep(UsbAudioTuning.DAC_SETTLE_DELAY_MS)
            outcome = attempt()
            retries++
        }
        return RateWrite(outcome, retries)
    }

    /**
     * Waits [UsbAudioTuning.DAC_SETTLE_DELAY_MS] for a rate change to take hold, then, for
     * UAC 2.0/3.0, asks the Clock Source whether it is valid. A successful write and read-back
     * only prove the device stored the number; whether its clock has actually locked to it is a
     * separate question, and this is the one answer the device itself can give.
     *
     * While the answer is an explicit "not valid", waits and asks again up to [maxValidRetries]
     * times. An unreadable control is not retried.
     */
    fun awaitClockSettled(
        session: UsbAudioSession,
        maxValidRetries: Int = 0,
        sleep: (Long) -> Unit = Thread::sleep,
    ): ClockSettle {
        sleep(UsbAudioTuning.DAC_SETTLE_DELAY_MS)
        if (session.topology.uacVersion == 1) return ClockSettle(valid = null, extraWaits = 0)
        var valid = UsbAudioController.getClockValid(session)
        var extraWaits = 0
        while (valid == false && extraWaits < maxValidRetries) {
            sleep(UsbAudioTuning.DAC_SETTLE_DELAY_MS)
            valid = UsbAudioController.getClockValid(session)
            extraWaits++
        }
        return ClockSettle(valid, extraWaits)
    }
}
