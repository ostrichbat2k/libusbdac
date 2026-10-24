/*
 * Copyright (c) 2026 libusbdac Project
 * SessionState.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac

import org.ostrichbat2k.usbdac.claim.UsbClaimController
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession
import org.ostrichbat2k.usbdac.streaming.RoutingAudioSink
import android.hardware.usb.UsbDevice
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.CompletableDeferred

/**
 * Everything that decides whether [UsbAudioManagerImpl.ensureFormatActive] needs to reassert the
 * interface, bundled into one comparable value rather than separately-named booleans. A future
 * addition to what "active" means here (a clock source selection, a DSD marker, anything else
 * that affects what's actually streaming) only has to be added to this class's fields to be
 * covered by the single equality check in [UsbAudioManagerImpl.ensureFormatActive] -- the previous
 * `formatChanged || rateChanged` shape required separately remembering to OR in a new named
 * boolean at both of that function's call sites, which is exactly the kind of gap that let a
 * same-alt rate-only change go unnoticed before `rateChanged` itself was added (see
 * [UsbAudioManagerImpl.ensureFormatActive]'s own doc comment for that history).
 */
internal data class ActiveStreamConfig(val format: UacStreamFormat, val sampleRateHz: Int)

/**
 * Everything [UsbAudioManagerImpl] knows about the current DAC-activation attempt, as one value
 * instead of several independently-mutable fields. This file's own history is full of bugs
 * caused by exactly that kind of drift (a format cache cleared without clearing the session it
 * belonged to, an "is activating" flag that could disagree with whether a session actually
 * existed, a cached sink that could outlive the session it was built for) -- modeling the three
 * real states explicitly makes an illegal combination (e.g. "activating" while a session already
 * exists) unrepresentable instead of merely undocumented.
 */
internal sealed class SessionState {
    /** No device activated, and nothing currently trying to activate one. */
    data object Idle : SessionState()

    /** [UsbAudioManagerImpl.activateDac] is running for [device] on its background thread right now. */
    data class Activating(val device: UsbDevice, val settled: CompletableDeferred<Unit>) : SessionState()

    /**
     * A real [UsbAudioSession] exists and is ready (or was) for playback.
     *
     * [format] is null exactly when [UsbClaimController] has just reclaimed the interface back
     * from the kernel (see [UsbAudioManagerImpl.clearActiveFormatOnReclaim]) -- the kernel driver may
     * have reset the alt-setting as a side effect, invalidating any cached "this is already
     * selected" assumption without invalidating the session itself. See
     * [UsbAudioManagerImpl.ensureFormatActive] for where that distinction is actually load-bearing.
     *
     * [sink] is the lazily-built, session-wide-cached [AudioSink] -- see
     * [UsbAudioManagerImpl.resolveActiveSink]'s doc comment for why one instance is deliberately
     * shared across every [RoutingAudioSink]. Living inside this state instead of its own field
     * means a cached sink can no longer outlive the session it was built against.
     */
    data class Active(
        val session: UsbAudioSession,
        val format: UacStreamFormat?,
        val rateHz: Int,
        val sink: AudioSink? = null,
    ) : SessionState()
}
