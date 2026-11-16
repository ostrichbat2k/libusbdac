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
    /**
     * [outcome] of the last attempt and how many [retries] beyond the first it took.
     *
     * [acceptedBecause] is set when the rate is being treated as in effect although the device
     * did not confirm it by read-back -- the cases UAC 1.0 allows, see [writeSampleRate]. Callers
     * decide on [accepted], and log [acceptedBecause] so such a session can be recognised later.
     */
    data class RateWrite(
        val outcome: UsbAudioController.ClockWriteOutcome,
        val retries: Int,
        val acceptedBecause: String? = null,
    ) {
        val accepted: Boolean
            get() = outcome is UsbAudioController.ClockWriteOutcome.Confirmed || acceptedBecause != null
    }

    /**
     * [valid] is the device's last CLOCK_VALID answer: null if it has no Clock Source to ask
     * (UAC 1.0) or the control could not be read. [extraWaits] is how many additional settle
     * delays were spent waiting for it to turn true.
     */
    data class ClockSettle(val valid: Boolean?, val extraWaits: Int)

    /**
     * Writes [hz] to wherever this device keeps its sample rate: [format]'s streaming endpoint
     * for UAC 1.0, which has no Clock Source entity, or the Clock Source for UAC 2.0/3.0. Using
     * the Clock Source request on a UAC 1.0 device was once the cause of a real, reproduced
     * endless playback-error loop.
     *
     * UAC 2.0/3.0: only a read-back equal to [hz] is accepted. A real device has stored and
     * echoed a rate it then did not run at, so nothing weaker is trusted there.
     *
     * UAC 1.0 (#128): the Sampling Frequency control is optional, and the usual device without it
     * has one fixed rate and STALLs the request. Treating that as failure made every such device
     * unusable. So, the way Linux's `set_sample_rate_v1` (sound/usb/clock.c) does:
     * - the endpoint does not declare the control: nothing is sent;
     * - the write went through but the rate cannot be read back (failed, or zero): accepted,
     *   since reading is optional too;
     * - anything else failed, but [hz] is the only rate the format declares: accepted, because
     *   there is no other rate the device could be running at.
     * A read-back of a *different* rate, or any failure on a device with several rates, is still
     * a failure.
     *
     * A result that is not accepted is retried up to [maxRetries] times, with
     * [UsbAudioTuning.DAC_SETTLE_DELAY_MS] before each retry.
     */
    fun writeSampleRate(
        session: UsbAudioSession,
        format: UacStreamFormat,
        hz: Int,
        maxRetries: Int = 0,
        sleep: (Long) -> Unit = Thread::sleep,
    ): RateWrite {
        val isUac1 = session.topology.uacVersion == 1
        if (isUac1 && !format.hasSamplingFrequencyControl) {
            return RateWrite(
                UsbAudioController.ClockWriteOutcome.Confirmed(hz),
                retries = 0,
                acceptedBecause = "endpoint declares no sampling frequency control; nothing sent")
        }
        fun attempt(retries: Int): RateWrite {
            val outcome =
                if (isUac1) {
                    UsbAudioController.setUac1EndpointSampleRate(session, format.endpointAddress, hz)
                } else {
                    UsbAudioController.setClockFrequency(session, hz)
                }
            return RateWrite(outcome, retries, if (isUac1) uac1Tolerance(outcome, format, hz) else null)
        }
        var write = attempt(0)
        while (!write.accepted && write.retries < maxRetries) {
            sleep(UsbAudioTuning.DAC_SETTLE_DELAY_MS)
            write = attempt(write.retries + 1)
        }
        return write
    }

    /** Why an unconfirmed UAC 1.0 [outcome] is acceptable, or null if it is not. See [writeSampleRate]. */
    private fun uac1Tolerance(
        outcome: UsbAudioController.ClockWriteOutcome,
        format: UacStreamFormat,
        hz: Int,
    ): String? {
        val mismatch = outcome as? UsbAudioController.ClockWriteOutcome.Mismatch
        val unreadable = mismatch != null && (mismatch.actualHz == null || mismatch.actualHz == 0)
        val onlyRate = format.sampleRates.size == 1 && format.sampleRates[0] == hz
        val wrongRateReadBack = mismatch != null && !unreadable
        return when {
            outcome is UsbAudioController.ClockWriteOutcome.Confirmed -> null
            unreadable -> "rate written; device does not report it back"
            onlyRate && !wrongRateReadBack -> "request failed, but $hz Hz is the only rate this format declares"
            else -> null
        }
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
