/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAbxTesterImplTest.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.abx

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ostrichbat2k.usbdac.UsbDacPreferences
import org.ostrichbat2k.usbdac.control.UsbVolumeController

class UsbAbxTesterImplTest {

    private class Fixture(
        abxTestMode: Boolean = true,
        enabled: Boolean = true,
        private val isBitPerfectCapable: Boolean = true,
        abxSyncTargetDb: Int? = null,
    ) {
        val usbSettings =
            mockk<UsbDacPreferences>(relaxed = true) {
                every { this@mockk.abxTestMode } returns abxTestMode
                every { this@mockk.enabled } returns enabled
            }
        val volumeController =
            mockk<UsbVolumeController>(relaxed = true) {
                every { abxSyncTargetDb() } returns abxSyncTargetDb
            }
        var lastVolumeSet: Int? = null
        var clearCachedSinkCalls = 0

        val tester =
            UsbAbxTesterImpl(
                usbSettings = usbSettings,
                isBitPerfectCapable = { isBitPerfectCapable },
                volumeController = volumeController,
                setVolume = { lastVolumeSet = it },
                clearCachedSink = { clearCachedSinkCalls++ },
            )
    }

    @Test
    fun `rollTrial leaves groundTruth null when abx test mode is off`() {
        val f = Fixture(abxTestMode = false)
        f.tester.rollTrial()
        assertNull(f.tester.groundTruth.value)
    }

    @Test
    fun `rollTrial leaves groundTruth null when the preference is disabled`() {
        val f = Fixture(abxTestMode = true, enabled = false)
        f.tester.rollTrial()
        assertNull(f.tester.groundTruth.value)
    }

    @Test
    fun `rollTrial leaves groundTruth null when bit-perfect isn't currently viable`() {
        val f = Fixture(abxTestMode = true, enabled = true, isBitPerfectCapable = false)
        f.tester.rollTrial()
        assertNull(f.tester.groundTruth.value)
    }

    @Test
    fun `rollTrial eventually picks both bit-perfect and fallback outcomes`() {
        val f = Fixture()
        val seen = mutableSetOf<Boolean>()
        // Random.nextBoolean() backs the real coin flip -- not deterministically injectable, so
        // this samples repeatedly instead. p(missing an outcome after 60 flips) < 1e-17.
        repeat(60) {
            f.tester.rollTrial()
            seen += f.tester.groundTruth.value!!
        }
        assertEquals(setOf(true, false), seen)
    }

    @Test
    fun `a bit-perfect trial syncs hardware volume to the live stream volume when available`() {
        val f = Fixture(abxSyncTargetDb = -12)
        var sawBitPerfect = false
        repeat(60) {
            f.tester.rollTrial()
            if (f.tester.groundTruth.value == true) sawBitPerfect = true
        }
        assertTrue("never rolled a bit-perfect trial in 60 tries", sawBitPerfect)
        assertEquals(-12, f.lastVolumeSet)
    }

    @Test
    fun `a bit-perfect trial does not touch volume when there is no target to sync to`() {
        val f = Fixture(abxSyncTargetDb = null)
        repeat(60) { f.tester.rollTrial() }
        assertNull(f.lastVolumeSet)
    }

    @Test
    fun `a fallback trial clears the cached bit-perfect sink`() {
        val f = Fixture()
        var sawFallback = false
        repeat(60) {
            f.tester.rollTrial()
            if (f.tester.groundTruth.value == false) sawFallback = true
        }
        assertTrue("never rolled a fallback trial in 60 tries", sawFallback)
        assertTrue(f.clearCachedSinkCalls > 0)
    }

    @Test
    fun `rolling a new trial hands the previous unguessed trial off to pendingGuess`() {
        val f = Fixture()
        f.tester.rollTrial()
        val firstOutcome = f.tester.groundTruth.value!!

        // Force the next roll to a deterministic null outcome so only the hand-off is observed.
        every { f.usbSettings.abxTestMode } returns false
        f.tester.rollTrial()

        assertEquals(firstOutcome, f.tester.pendingGuess.value)
        assertNull(f.tester.groundTruth.value)
    }

    @Test
    fun `pendingGuess is untouched when there was no prior trial to hand off`() {
        val f = Fixture(abxTestMode = false)
        f.tester.rollTrial() // groundTruth stays null -- nothing to hand off
        f.tester.rollTrial()
        assertNull(f.tester.pendingGuess.value)
    }

    @Test
    fun `submitGuess returns null and leaves stats untouched when nothing is pending`() {
        val f = Fixture()
        assertNull(f.tester.submitGuess(true))
        assertEquals(0, f.tester.stats.value.total)
    }

    @Test
    fun `submitGuess records a correct guess, increments both counters, and clears pendingGuess`() {
        val f = Fixture()
        f.tester.rollTrial()
        val truth = f.tester.groundTruth.value!!
        every { f.usbSettings.abxTestMode } returns false
        f.tester.rollTrial() // hands the trial off to pendingGuess

        val result = f.tester.submitGuess(truth)

        assertEquals(true, result)
        assertEquals(1, f.tester.stats.value.correct)
        assertEquals(1, f.tester.stats.value.total)
        assertNull(f.tester.pendingGuess.value)
    }

    @Test
    fun `submitGuess records an incorrect guess without crediting correct`() {
        val f = Fixture()
        f.tester.rollTrial()
        val truth = f.tester.groundTruth.value!!
        every { f.usbSettings.abxTestMode } returns false
        f.tester.rollTrial()

        val result = f.tester.submitGuess(!truth)

        assertEquals(false, result)
        assertEquals(0, f.tester.stats.value.correct)
        assertEquals(1, f.tester.stats.value.total)
    }

    @Test
    fun `stats accumulate across multiple submitted guesses`() {
        val f = Fixture()
        fun rollAndSubmit(guessCorrectly: Boolean) {
            f.tester.rollTrial()
            val truth = f.tester.groundTruth.value!!
            every { f.usbSettings.abxTestMode } returns false
            f.tester.rollTrial()
            f.tester.submitGuess(if (guessCorrectly) truth else !truth)
            every { f.usbSettings.abxTestMode } returns true
        }
        rollAndSubmit(guessCorrectly = true)
        rollAndSubmit(guessCorrectly = true)
        rollAndSubmit(guessCorrectly = false)

        assertEquals(2, f.tester.stats.value.correct)
        assertEquals(3, f.tester.stats.value.total)
    }

    @Test
    fun `stats start at zero`() {
        val f = Fixture()
        assertEquals(0, f.tester.stats.value.correct)
        assertEquals(0, f.tester.stats.value.total)
        verify(exactly = 0) { f.volumeController.abxSyncTargetDb() }
    }
}
