/*
 * Copyright (c) 2026 libusbdac Project
 * RoutingAudioSinkTest.kt is part of libusbdac.
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

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.control.UsbControlState
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat

/**
 * Covers [RoutingAudioSink]'s delegate-switching state machine -- every behavior here traces back
 * to a real, reproduced bug described in the class's own doc comments: never downgrading a live,
 * format-compatible USB session on its own initiative; falling back for exactly one track (not
 * forever) when the live session can't take that track's format or has died; [current]'s
 * self-heal (abandoned-delegate detection, re-configuring [fallback] with the cached params, and
 * re-reporting the route) for calls that never go through [RoutingAudioSink.configure] again;
 * [supportsFormat]/[getFormatSupport] never triggering activation; and the play()-nudge a
 * mid-playback delegate switch needs since ExoPlayer never calls play() again for it.
 * [usbAudioManager] and both delegates are mocked wholesale -- this class's value is entirely in
 * how it sequences and branches on their results.
 */
class RoutingAudioSinkTest {

    private val uacFormat =
        UacStreamFormat(
            alternateSetting = 1,
            bitResolution = 24,
            subslotSize = 4,
            clusterDescriptorId = -1,
            endpointAddress = 0x02,
            maxPacketSize = 192,
            isAsync = true,
        )

    private fun controlStateWithBitResolution(bitResolution: Int) =
        UsbControlState(
            deviceName = "Test DAC",
            formats = listOf(uacFormat),
            activeFormat = uacFormat.copy(bitResolution = bitResolution),
            sampleRateHz = 44100,
            muted = false,
            volume = null,
            volumeRange = null,
            mixerChannels = emptyList(),
            mixerGains = emptyMap(),
            mixerGainRange = null,
            clockSupportedRates = emptyList(),
        )

    private fun format(sampleRate: Int = 44100, channels: Int = 2, pcmEncoding: Int = C.ENCODING_PCM_16BIT): Format =
        Format.Builder()
            .setChannelCount(channels)
            .setSampleRate(sampleRate)
            .setPcmEncoding(pcmEncoding)
            .build()

    private fun supporting(sink: AudioSink) {
        every { sink.getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    }

    private fun unsupporting(sink: AudioSink) {
        every { sink.getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    private lateinit var usbAudioManager: UsbAudioManager
    private lateinit var fallback: AudioSink
    private lateinit var usbSink: AudioSink
    private lateinit var sink: RoutingAudioSink

    @Before
    fun setUp() {
        usbAudioManager = mockk(relaxed = true)
        fallback = mockk(relaxed = true)
        usbSink = mockk(relaxed = true)
        every { usbAudioManager.controlState } returns MutableStateFlow(null)
        sink = RoutingAudioSink(usbAudioManager, fallback)
    }

    @Test
    fun `configure routes to fallback when no USB sink is active`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns null
        val fmt = format()

        sink.configure(fmt, 0, null)

        verify { fallback.configure(fmt, 0, null) }
        verify(exactly = 0) { usbSink.configure(any(), any(), any()) }
    }

    @Test
    fun `configure upgrades to the USB sink when it supports the format`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        val fmt = format()

        sink.configure(fmt, 0, null)

        verify { usbSink.configure(fmt, 0, null) }
        verify(exactly = 0) { fallback.configure(any(), any(), any()) }
    }

    @Test
    fun `configure never downgrades a live, still-supported USB delegate on its own initiative`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        val fmt = format()

        sink.configure(fmt, 0, null)
        sink.configure(fmt, 0, null)
        sink.configure(fmt, 0, null)

        // Only the very first call (null -> usbSink) is a real switch.
        verify(exactly = 0) { usbSink.pause() }
        verify(exactly = 1) { usbAudioManager.reportPlaybackRoute(UsbAudioManager.PlaybackRoute.Switching) }
        verify(exactly = 3) { usbSink.configure(fmt, 0, null) }
    }

    @Test
    fun `configure falls back and flushes the abandoned delegate when the USB session dies`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        val fmt = format()
        sink.configure(fmt, 0, null)

        every { usbAudioManager.resolveActiveSink(any()) } returns null
        sink.configure(fmt, 0, null)

        // flush(), not pause() -- a dead session has nothing left worth preserving, and flush()
        // (unlike pause()) actually tears the native thread/claim down instead of leaving an
        // already-dead delegate paused forever. See pendingSwitch's and commitDelegateSwitch's
        // own doc comments for why this specific call changed.
        verify { usbSink.flush() }
        verify(exactly = 1) { fallback.configure(fmt, 0, null) }
        verify(exactly = 2) { usbAudioManager.reportPlaybackRoute(UsbAudioManager.PlaybackRoute.Switching) }
        verify { usbAudioManager.releaseClaim(delayMs = 0, awaitAndroidTakeover = true, deactivateOnTimeout = true) }
    }

    @Test
    fun `configure falls back for a single unsupported-format track without abandoning the live session`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        sink.configure(format(sampleRate = 44100), 0, null)

        unsupporting(usbSink)
        sink.configure(format(sampleRate = 96000), 0, null)

        verify { usbSink.flush() }
        verify(exactly = 1) { fallback.configure(any(), any(), any()) }

        // Next track: the device supports it again, and the live session was never torn down --
        // picked right back up instead of staying stuck on fallback.
        supporting(usbSink)
        sink.configure(format(sampleRate = 44100), 0, null)
        verify(exactly = 2) { usbSink.configure(any(), any(), any()) }
    }

    // ---- deferred cross-delegate switch: see pendingSwitch's doc comment ----
    //
    // Real, reproduced bug: abandoning a live USB delegate the moment the *next* track's
    // configure() call revealed it needs fallback (e.g. a 96kHz file against a 44.1/48kHz-only
    // device) cut the *current* track off by however much it still had genuinely unplayed,
    // already-buffered audio -- confirmed via a live logcat-correlated repro, then reproduced on
    // real hardware even after switching the abandon call from pause() to flush() (which only
    // fixed a secondary symptom: the abandoned audio bleeding into a *later* track instead of
    // vanishing silently). The actual fix is deferring the delegate swap itself until the old
    // delegate's hasPendingData() genuinely goes false, mirroring exactly how a plain
    // (non-routing) DefaultAudioSink defers its own internal AudioOutput swap.

    @Test
    fun `configure defers abandoning a live USB delegate until its pending data drains`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        sink.configure(format(sampleRate = 44100), 0, null)
        every { usbSink.hasPendingData() } returns true
        every { usbSink.getCurrentPositionUs(any()) } returns 55555L

        unsupporting(usbSink)
        sink.configure(format(sampleRate = 96000), 0, null)

        // existing still has real, undrained audio from the first track -- nothing abandoned or
        // switched yet, even though configure() for the second (unsupported) track already ran.
        verify(exactly = 0) { usbSink.flush() }
        verify(exactly = 0) { fallback.configure(any(), any(), any()) }
        // current() still routes to the old delegate during the deferred window, so the first
        // track keeps reporting its own real, still-advancing position and pending state.
        assertTrue(sink.hasPendingData())
        assertEquals(55555L, sink.getCurrentPositionUs(false))
        // Reported immediately anyway -- this window can last as long as the old track still has
        // buffered audio, and the Now Playing screen shouldn't look frozen for that whole span.
        verify { usbAudioManager.reportPlaybackRoute(UsbAudioManager.PlaybackRoute.Switching) }
    }

    @Test
    fun `handleBuffer rejects buffers while a deferred switch's old delegate is still draining, then commits once it's done`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        sink.configure(format(sampleRate = 44100), 0, null)
        every { usbSink.hasPendingData() } returns true
        unsupporting(usbSink)
        val fmt96k = format(sampleRate = 96000)
        sink.configure(fmt96k, 0, null)
        val buffer = java.nio.ByteBuffer.allocate(4)

        // Still draining -- this buffer (already decoded against the *new* track's format)
        // can't go anywhere yet: not to the old delegate (wrong format), not to the new one
        // (not configured yet). Rejecting it is ordinary AudioSink backpressure -- the renderer
        // retries later instead of either dropping it or corrupting anything.
        assertFalse(sink.handleBuffer(buffer, 0L, 1))
        verify(exactly = 0) { usbSink.handleBuffer(any(), any(), any()) }
        verify(exactly = 0) { fallback.handleBuffer(any(), any(), any()) }
        verify(exactly = 0) { usbSink.flush() }
        verify(exactly = 0) { fallback.configure(any(), any(), any()) }

        // Now genuinely drained -- the very next handleBuffer() call completes the deferred
        // switch (flush the old delegate, configure the new one) and forwards this buffer to it.
        every { usbSink.hasPendingData() } returns false
        every { fallback.handleBuffer(buffer, 0L, 1) } returns true

        assertTrue(sink.handleBuffer(buffer, 0L, 1))
        verify { usbSink.flush() }
        verify { fallback.configure(fmt96k, 0, null) }
        verify { fallback.handleBuffer(buffer, 0L, 1) }
    }

    @Test
    fun `reset clears a pending deferred switch so a later handleBuffer doesn't commit it`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        sink.configure(format(sampleRate = 44100), 0, null)
        every { usbSink.hasPendingData() } returns true
        unsupporting(usbSink)
        sink.configure(format(sampleRate = 96000), 0, null)

        sink.reset()
        verify { usbSink.reset() }

        every { usbAudioManager.resolveActiveSink(any()) } returns null
        val buffer = java.nio.ByteBuffer.allocate(4)
        sink.handleBuffer(buffer, 0L, 1)

        // The abandoned switch's own flush()/configure() never run after reset() -- only a
        // fresh current() resolution (fallback, since no USB sink is live post-reset) handles
        // this buffer, not the stale plan that was pending when reset() was called.
        verify(exactly = 0) { usbSink.flush() }
        verify(exactly = 0) { fallback.configure(any(), any(), any()) }
        verify { fallback.handleBuffer(buffer, 0L, 1) }
    }

    @Test
    fun `release clears a pending deferred switch`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        sink.configure(format(sampleRate = 44100), 0, null)
        every { usbSink.hasPendingData() } returns true
        unsupporting(usbSink)
        sink.configure(format(sampleRate = 96000), 0, null)

        sink.release()

        verify { usbSink.release() }
        verify(exactly = 0) { usbSink.flush() }
        verify(exactly = 0) { fallback.configure(any(), any(), any()) }
    }

    @Test
    fun `current self-heals to fallback, reconfiguring it with the last real format, when the session dies between configure calls`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        val fmt = format(sampleRate = 48000, channels = 2, pcmEncoding = C.ENCODING_PCM_16BIT)
        sink.configure(fmt, 512, null)

        every { usbAudioManager.resolveActiveSink(any()) } returns null
        sink.handleDiscontinuity()

        verify(exactly = 1) { fallback.configure(fmt, 512, null) }
        verify { fallback.handleDiscontinuity() }
        verify {
            usbAudioManager.reportPlaybackRoute(
                UsbAudioManager.PlaybackRoute.Fallback(bitDepth = 16, sampleRateHz = 48000, channels = 2))
        }
    }

    @Test
    fun `current only re-configures fallback once per dead-session detection`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        val fmt = format()
        sink.configure(fmt, 0, null)

        every { usbAudioManager.resolveActiveSink(any()) } returns null
        sink.handleDiscontinuity()
        sink.handleDiscontinuity()

        verify(exactly = 1) { fallback.configure(any(), any(), any()) }
    }

    @Test
    fun `supportsFormat and getFormatSupport never trigger activation`() {
        every { usbAudioManager.resolveActiveSink(trigger = false) } returns usbSink
        supporting(usbSink)
        every { fallback.supportsFormat(any()) } returns true

        assertTrue(sink.supportsFormat(format()))
        sink.getFormatSupport(format())

        verify(exactly = 2) { usbAudioManager.resolveActiveSink(trigger = false) }
        verify(exactly = 0) { usbAudioManager.resolveActiveSink(trigger = true) }
    }

    @Test
    fun `supportsFormat falls through to fallback when the live USB sink can't take this format`() {
        every { usbAudioManager.resolveActiveSink(trigger = false) } returns usbSink
        unsupporting(usbSink)
        every { fallback.supportsFormat(any()) } returns true

        assertTrue(sink.supportsFormat(format()))
    }

    @Test
    fun `supportsFormat is false when neither delegate can take the format`() {
        every { usbAudioManager.resolveActiveSink(trigger = false) } returns null
        every { fallback.supportsFormat(any()) } returns false

        assertFalse(sink.supportsFormat(format()))
    }

    @Test
    fun `play triggers activation before delegating to the current sink`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns null

        sink.play()

        verifyOrder {
            usbAudioManager.triggerActivation()
            fallback.play()
        }
    }

    @Test
    fun `configure plays a newly-switched delegate immediately when already mid-playback`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns null
        sink.play()

        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        sink.configure(format(), 0, null)

        verify { usbSink.play() }
    }

    @Test
    fun `pause forwards to the current delegate`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        sink.configure(format(), 0, null)

        sink.pause()

        verify { usbSink.pause() }
    }

    @Test
    fun `reset clears the delegate so the next call re-evaluates from fallback`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        sink.configure(format(), 0, null)

        sink.reset()
        verify { usbSink.reset() }

        sink.handleDiscontinuity()
        verify { fallback.handleDiscontinuity() }
    }

    @Test
    fun `release clears the delegate and forwards to whatever was active`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        sink.configure(format(), 0, null)

        sink.release()

        verify { usbSink.release() }
    }

    @Test
    fun `configure reports a Direct route with null bitDepth for a track whose format differs from the previous one`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)

        sink.configure(format(sampleRate = 44100), 0, null)

        verify {
            usbAudioManager.reportPlaybackRoute(
                UsbAudioManager.PlaybackRoute.Direct(bitDepth = null, sampleRateHz = 44100, channels = 2))
        }
    }

    @Test
    fun `configure reuses controlState's activeFormat bitResolution for Direct route when encoding and rate match the previous track`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        every { usbAudioManager.controlState } returns MutableStateFlow(controlStateWithBitResolution(24))

        sink.configure(format(sampleRate = 44100), 0, null)
        sink.configure(format(sampleRate = 44100), 0, null)

        verify {
            usbAudioManager.reportPlaybackRoute(
                UsbAudioManager.PlaybackRoute.Direct(bitDepth = 24, sampleRateHz = 44100, channels = 2))
        }
    }

    @Test
    fun `configure reports Fallback route (the file's own format, not HAL's) when routed to fallback`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns null

        sink.configure(format(sampleRate = 44100, channels = 2, pcmEncoding = C.ENCODING_PCM_24BIT), 0, null)

        verify {
            usbAudioManager.reportPlaybackRoute(
                UsbAudioManager.PlaybackRoute.Fallback(bitDepth = 24, sampleRateHz = 44100, channels = 2))
        }
    }

    @Test
    fun `configure reports a 32-bit Fallback bitDepth for a 32-bit PCM track`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns null

        sink.configure(format(sampleRate = 44100, channels = 2, pcmEncoding = C.ENCODING_PCM_32BIT), 0, null)

        verify {
            usbAudioManager.reportPlaybackRoute(
                UsbAudioManager.PlaybackRoute.Fallback(bitDepth = 32, sampleRateHz = 44100, channels = 2))
        }
    }

    @Test
    fun `configure reports a Fallback route with a null bitDepth for a non-PCM encoding`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns null

        sink.configure(format(sampleRate = 44100, channels = 2, pcmEncoding = C.ENCODING_AC3), 0, null)

        verify {
            usbAudioManager.reportPlaybackRoute(
                UsbAudioManager.PlaybackRoute.Fallback(bitDepth = null, sampleRateHz = 44100, channels = 2))
        }
    }

    @Test
    fun `configure reports a Fallback route and rethrows when the chosen delegate's configure throws`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns null
        val boom = RuntimeException("boom")
        every { fallback.configure(any(), any(), isNull()) } throws boom
        val fmt = format(sampleRate = 44100)

        val thrown = try {
            sink.configure(fmt, 0, null)
            null
        } catch (e: RuntimeException) {
            e
        }

        assertTrue(thrown === boom)
        verify {
            usbAudioManager.reportPlaybackRoute(
                UsbAudioManager.PlaybackRoute.Fallback(bitDepth = 16, sampleRateHz = 44100, channels = 2))
        }
    }

    // ---- plain one-line delegations to current() ----

    @Test
    fun `setListener forwards to the current delegate and remembers it for a later switch`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        sink.configure(format(), 0, null)
        val listener = mockk<AudioSink.Listener>(relaxed = true)

        sink.setListener(listener)

        verify { usbSink.setListener(listener) }
    }

    @Test
    fun `getCurrentPositionUs, handleBuffer, playToEndOfStream, isEnded, and hasPendingData forward to the current delegate`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        sink.configure(format(), 0, null)
        every { usbSink.getCurrentPositionUs(any()) } returns 12345L
        every { usbSink.isEnded() } returns true
        every { usbSink.hasPendingData() } returns true
        val buffer = java.nio.ByteBuffer.allocate(4)

        assertEquals(12345L, sink.getCurrentPositionUs(false))
        sink.handleBuffer(buffer, 0L, 1)
        sink.playToEndOfStream()
        assertTrue(sink.isEnded())
        assertTrue(sink.hasPendingData())

        verify { usbSink.handleBuffer(buffer, 0L, 1) }
        verify { usbSink.playToEndOfStream() }
    }

    @Test
    fun `playback-parameter, skip-silence, audio-attribute, and tunneling getters-setters forward to the current delegate`() {
        every { usbAudioManager.resolveActiveSink(any()) } returns usbSink
        supporting(usbSink)
        sink.configure(format(), 0, null)
        val params = PlaybackParameters(1.5f)
        every { usbSink.getPlaybackParameters() } returns params
        every { usbSink.getSkipSilenceEnabled() } returns true
        val attributes = AudioAttributes.DEFAULT
        every { usbSink.getAudioAttributes() } returns attributes
        every { usbSink.audioTrackBufferSizeUs } returns 9999L
        val auxEffectInfo = AuxEffectInfo(1, 0.5f)

        sink.setPlaybackParameters(params)
        assertEquals(params, sink.getPlaybackParameters())
        sink.setSkipSilenceEnabled(true)
        assertTrue(sink.getSkipSilenceEnabled())
        sink.setAudioAttributes(attributes)
        assertEquals(attributes, sink.getAudioAttributes())
        sink.setAudioSessionId(7)
        sink.setAuxEffectInfo(auxEffectInfo)
        assertEquals(9999L, sink.getAudioTrackBufferSizeUs())
        sink.enableTunnelingV21()
        sink.disableTunneling()
        sink.setVolume(0.5f)
        sink.flush()

        verify { usbSink.setPlaybackParameters(params) }
        verify { usbSink.setSkipSilenceEnabled(true) }
        verify { usbSink.setAudioAttributes(attributes) }
        verify { usbSink.setAudioSessionId(7) }
        verify { usbSink.setAuxEffectInfo(auxEffectInfo) }
        verify { usbSink.enableTunnelingV21() }
        verify { usbSink.disableTunneling() }
        verify { usbSink.setVolume(0.5f) }
        verify { usbSink.flush() }
    }
}
