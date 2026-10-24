/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioOutputVolumeRestoreTest.kt is part of libusbdac.
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

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioController
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession
import org.ostrichbat2k.usbdac.testutil.FakeFeatureUnit

/**
 * Answers a real question raised while driving: can [UsbAudioOutput]'s "volume jumps" after a
 * genuine pause/resume mid-track, separate from the already-fixed, already-tested (see
 * [org.ostrichbat2k.usbdac.UsbAudioManagerFunctionalTest]) channel-0-skipped bug in
 * [org.ostrichbat2k.usbdac.control.UsbVolumeControllerImpl.prepareForHalHandoff]?
 *
 * [UsbAudioOutput.restoreVolumeAfterRelock] -- extracted from [UsbAudioOutput.activateAndStream]
 * purely so a test can reach it without ever touching [UsbNative] (referencing that object alone
 * throws `UnsatisfiedLinkError` in a plain JVM test; this is exactly the Scope B blocker on
 * record, and testing through the real [UsbAudioOutput.activateAndStream] end to end still
 * requires giving [UsbNative] a seam, which is deliberately not done here) -- restores every
 * channel to [UsbAudioOutput]'s own `initialVolumeDb`, captured once at *construction*. That
 * constructor argument is [org.ostrichbat2k.usbdac.UsbDacPreferences.volumeDb] read fresh only
 * when a *new* instance is built, i.e. on a track/format change (see
 * [UsbAudioOutputProvider.getAudioOutput]'s own doc comment) -- never while the same instance
 * stays alive across a same-track pause/resume.
 */
class UsbAudioOutputVolumeRestoreTest {

    private val testTopology =
        UacPlaybackTopology(
            configurationValue = 1,
            controlInterfaceNumber = 0,
            streamingInterfaceNumber = 1,
            uacVersion = 2,
            clockSourceId = -1,
            outputFeatureUnitId = 7,
            mixerUnitId = -1,
            mixerInputChannelBase = -1,
            powerDomainId = -1,
            powerDomainD1RecoveryUs = 0,
            powerDomainD2RecoveryUs = 0,
            channelCount = 2,
            formats = emptyList(),
        )

    private val testFormat =
        UacStreamFormat(
            alternateSetting = 1,
            bitResolution = 24,
            subslotSize = 4,
            clusterDescriptorId = -1,
            endpointAddress = 0x02,
            maxPacketSize = 192,
            isAsync = true,
        )

    private fun sessionWith(featureUnit: FakeFeatureUnit): UsbAudioSession {
        val connection = mockk<UsbDeviceConnection>(relaxed = true)
        featureUnit.install(connection)
        return UsbAudioSession(
            device = mockk(relaxed = true),
            connection = connection,
            topology = testTopology,
            controlInterface = mockk<UsbInterface>(relaxed = true) { every { id } returns 0 },
            streamingAltSettings = emptyMap(),
        )
    }

    private fun output(session: UsbAudioSession, initialVolumeDb: Int?) =
        UsbAudioOutput(
            session = session,
            format = testFormat,
            channels = testTopology.channelCount,
            sampleRateHz = 44100,
            initialVolumeDb = initialVolumeDb,
            usbAudioManager = mockk(relaxed = true),
        )

    @Test
    fun `restoreVolumeAfterRelock restores every channel, starting at master (channel 0)`() {
        val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)
        val session = sessionWith(featureUnit)
        // Simulate prepareForHalHandoff's own force-to-unity, which is what this function exists
        // to undo on the way back to a direct track -- every channel starts at unity (0).
        for (channel in 0..2) UsbAudioController.setVolume(session, 0, channel)

        output(session, initialVolumeDb = -2000).restoreVolumeAfterRelock()

        assertEquals(-2000, featureUnit.volumeOf(0))
        assertEquals(-2000, featureUnit.volumeOf(1))
        assertEquals(-2000, featureUnit.volumeOf(2))
    }

    @Test
    fun `a stale initialVolumeDb silently overwrites a live volume-key change made after construction`() {
        val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)
        val session = sessionWith(featureUnit)
        // This UsbAudioOutput instance was built when the session started, at -2000 (the dB
        // persisted in UsbDacPreferences.volumeDb at that moment) -- see
        // UsbAudioOutputProvider.getAudioOutput's own doc comment on when a *new* instance (and so
        // a fresh initialVolumeDb read) actually happens: only on a track/format change, not here.
        val usbAudioOutput = output(session, initialVolumeDb = -2000)

        // The user then genuinely lowers the volume via the physical keys, mid-track, with no
        // format change -- the real live write path (UsbControlWriterImpl.setVolume ->
        // UsbAudioController.setVolume), reaching the same hardware this same UsbAudioOutput
        // instance is still alive for.
        for (channel in 0..2) UsbAudioController.setVolume(session, -6000, channel)
        assertEquals(-6000, featureUnit.volumeOf(0))

        // ...and sometime later, this instance survives a real release+reclaim (a pause that sat
        // past the grace period -- a stoplight, a nav prompt, a phone call -- is exactly this,
        // per UsbAudioOutput.play()'s own "redo the whole clock/mute dance... if !activated"
        // comment) and activateAndStream() re-runs its relock dance, including this call.
        usbAudioOutput.restoreVolumeAfterRelock()

        // If this is -6000 (what the user actually, deliberately set last), the hypothesis is
        // wrong. It isn't: this instance never learned about that live change, so it restores to
        // its own stale construction-time value instead, silently discarding the user's own most
        // recent adjustment -- a second, distinct "volume jumps" mechanism from the already-fixed
        // channel-0 bug, real and reachable through this exact, ordinary driving-session sequence
        // (volume adjustment, then any pause long enough to actually release the claim).
        assertEquals(-2000, featureUnit.volumeOf(0))
    }

    @Test
    fun `a brand-new instance after a real track change does read the live volume correctly`() {
        val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)
        val session = sessionWith(featureUnit)
        for (channel in 0..2) UsbAudioController.setVolume(session, -6000, channel)

        // UsbAudioOutputProvider.getAudioOutput() builds a *new* UsbAudioOutput per track/format
        // change, reading UsbDacPreferences.volumeDb fresh at that point -- simulated here by
        // simply constructing with the now-current value, the same thing a real track change
        // would do. This is the one path that already works correctly; included as a contrast to
        // the test above, which is what the mid-track pause/resume path above lacks.
        output(session, initialVolumeDb = -6000).restoreVolumeAfterRelock()

        assertEquals(-6000, featureUnit.volumeOf(0))
    }
}
