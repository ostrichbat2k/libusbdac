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
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.UsbDacPreferences
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
 * channel to [UsbDacPreferences.volumeDb], read fresh on every call (fixed here -- it used to be
 * a value captured once at construction, see the second test below for the regression that
 * caught).
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

    private fun fakeUsbSettings(initialVolumeDb: Int?): UsbDacPreferences {
        var backing = initialVolumeDb
        return mockk(relaxed = true) {
            every { volumeDb } answers { backing }
            every { volumeDb = any() } answers { backing = it.invocation.args[0] as Int? }
        }
    }

    private fun output(
        session: UsbAudioSession,
        usbSettings: UsbDacPreferences,
        usbAudioManager: UsbAudioManager = mockk(relaxed = true),
    ) =
        UsbAudioOutput(
            session = session,
            format = testFormat,
            channels = testTopology.channelCount,
            sampleRateHz = 44100,
            usbSettings = usbSettings,
            usbAudioManager = usbAudioManager,
        )

    @Test
    fun `restoreVolumeAfterRelock restores every channel, starting at master (channel 0)`() {
        val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)
        val session = sessionWith(featureUnit)
        // Simulate prepareForHalHandoff's own force-to-unity, which is what this function exists
        // to undo on the way back to a direct track -- every channel starts at unity (0).
        for (channel in 0..2) UsbAudioController.setVolume(session, 0, channel)

        output(session, fakeUsbSettings(initialVolumeDb = -2000)).restoreVolumeAfterRelock()

        assertEquals(-2000, featureUnit.volumeOf(0))
        assertEquals(-2000, featureUnit.volumeOf(1))
        assertEquals(-2000, featureUnit.volumeOf(2))
    }

    @Test
    fun `restoreVolumeAfterRelock picks up a volume-key change made after construction, not a stale snapshot`() {
        val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)
        val session = sessionWith(featureUnit)
        // This UsbAudioOutput instance was built when the session started, at -2000 (the dB
        // persisted in UsbDacPreferences.volumeDb at that moment) -- see
        // UsbAudioOutputProvider.getAudioOutput's own doc comment on when a *new* instance (and
        // so a fresh read) actually happens: only on a track/format change, not here.
        val usbSettings = fakeUsbSettings(initialVolumeDb = -2000)
        val usbAudioOutput = output(session, usbSettings)

        // The user then genuinely lowers the volume via the physical keys, mid-track, with no
        // format change -- the real live write path (UsbControlWriterImpl.setVolume) updates
        // usbSettings.volumeDb synchronously; simulated directly here since this test is only
        // about what restoreVolumeAfterRelock reads, not that write path itself (already covered
        // by UsbControlWriterImplTest).
        usbSettings.volumeDb = -6000

        // ...and sometime later, this instance survives a real release+reclaim -- which is
        // exactly what forces the Feature Unit to unity first (UsbVolumeControllerImpl.
        // prepareForHalHandoff, see UsbAudioManagerFunctionalTest), leaving the hardware here at
        // 0 right before activateAndStream() re-runs its relock dance (e.g. a pause that sat past
        // the release grace period -- a stoplight, a nav prompt, a phone call -- then resuming;
        // see UsbAudioOutput.play()'s own "redo the whole clock/mute dance... if !activated"
        // comment). Starting from unity here, not from -6000 already written, is what makes this
        // assertion actually depend on which target restoreVolumeAfterRelock computes -- starting
        // from the live value already in place would pass vacuously even with the bug still live.
        for (channel in 0..2) UsbAudioController.setVolume(session, 0, channel)

        usbAudioOutput.restoreVolumeAfterRelock()

        // Confirms the fix: this reads usbSettings.volumeDb fresh, so it restores to -6000 (what
        // the user actually, deliberately set last), not -2000 (this instance's own
        // construction-time snapshot) -- before the fix, this assertion failed with -2000,
        // demonstrating the real, now-fixed "volume jumps" mechanism this test is named for.
        assertEquals(-6000, featureUnit.volumeOf(0))
        assertEquals(-6000, featureUnit.volumeOf(1))
        assertEquals(-6000, featureUnit.volumeOf(2))
    }

    @Test
    fun `falls back to the device's own reported midpoint when no volume has ever been persisted`() {
        val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)
        featureUnit.range = -12800 to 0 // midpoint -6400.
        val session = sessionWith(featureUnit)
        for (channel in 0..2) UsbAudioController.setVolume(session, 0, channel)

        output(session, fakeUsbSettings(initialVolumeDb = null)).restoreVolumeAfterRelock()

        assertEquals(-6400, featureUnit.volumeOf(0))
    }

    /**
     * [UsbAudioOutput.play] runs [UsbAudioOutput.activateAndStream] in the background now (see
     * its own doc comment, part of #30) instead of blocking the caller for however long a real
     * relock takes -- which opens a window where [UsbAudioOutput.pause] can land before that
     * background attempt finishes. Drives [UsbAudioOutput.onActivationAttemptFinished] directly
     * (the seam [play]'s background task calls into) rather than the real async [play], for the
     * same reason [restoreVolumeAfterRelock] is tested directly: the alternative, successful-and-
     * still-playing branch reaches [UsbNative] via `startStreamingThread()`, which throws
     * `UnsatisfiedLinkError` in a plain JVM test.
     */
    @Test
    fun `onActivationAttemptFinished hands the claim back instead of streaming when paused by the time activation finished`() {
        val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)
        val session = sessionWith(featureUnit)
        val usbAudioManager = mockk<UsbAudioManager>(relaxed = true)
        // paused starts true on a fresh instance (see its own doc comment) -- exactly the state
        // play() would be in if pause() landed again before the background relock it kicked off
        // ever got back to onActivationAttemptFinished.
        val output = output(session, fakeUsbSettings(-2000), usbAudioManager)

        output.onActivationAttemptFinished(succeeded = true)

        verify { usbAudioManager.releaseClaim() }
        verify(exactly = 0) { usbAudioManager.notifyStreamingStarted() }
    }

    @Test
    fun `onActivationAttemptFinished does nothing but log when activation itself failed`() {
        val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)
        val session = sessionWith(featureUnit)
        val usbAudioManager = mockk<UsbAudioManager>(relaxed = true)
        val output = output(session, fakeUsbSettings(-2000), usbAudioManager)

        output.onActivationAttemptFinished(succeeded = false)

        verify(exactly = 0) { usbAudioManager.releaseClaim() }
        verify(exactly = 0) { usbAudioManager.notifyStreamingStarted() }
    }

    /**
     * [UsbAudioOutput.isStalled] used to be able to assume `!paused && !activated` was an
     * instantaneous state -- [UsbAudioOutput.play] ran the whole relock inline, so nothing else
     * ever got a chance to observe it mid-flight. Now that #30 moved that relock onto
     * [UsbAudioOutput]'s own background executor, that window can last as long as a real relock
     * does (1.6-2.9s, observed on real hardware) -- [isStalled] has to know not to report that as
     * a stall. Blocks the real [play] call's background task inside a mocked
     * [UsbAudioManager.requestClaim] (not a [UsbAudioController]/[UsbNative] call, so nothing
     * native gets touched) to deterministically catch it mid-flight, without any sleep-based
     * polling.
     */
    @Test
    fun `isStalled does not report a stall while play()'s background relock is still in flight`() {
        val featureUnit = FakeFeatureUnit(unitId = 7, controlInterfaceId = 0)
        val session = sessionWith(featureUnit)
        val usbAudioManager = mockk<UsbAudioManager>(relaxed = true)
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        every { usbAudioManager.requestClaim() } answers {
            started.countDown()
            proceed.await()
            UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED
        }
        val output = output(session, fakeUsbSettings(-2000), usbAudioManager)

        output.play()
        assertTrue("background activation never reached requestClaim()", started.await(2, TimeUnit.SECONDS))
        assertFalse(output.isStalled())

        // Let the background task finish (whatever it ends up doing from here isn't this test's
        // concern) and release its executor rather than leaving a thread blocked on nothing.
        proceed.countDown()
        output.release()
    }
}
