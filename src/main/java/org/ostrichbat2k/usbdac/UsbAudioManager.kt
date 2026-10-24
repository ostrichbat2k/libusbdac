/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioManager.kt is part of libusbdac.
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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Build
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import timber.log.Timber as L

/**
 * Everything that decides whether [UsbAudioManager.ensureFormatActive] needs to reassert the
 * interface, bundled into one comparable value rather than separately-named booleans. A future
 * addition to what "active" means here (a clock source selection, a DSD marker, anything else
 * that affects what's actually streaming) only has to be added to this class's fields to be
 * covered by the single equality check in [UsbAudioManager.ensureFormatActive] -- the previous
 * `formatChanged || rateChanged` shape required separately remembering to OR in a new named
 * boolean at both of that function's call sites, which is exactly the kind of gap that let a
 * same-alt rate-only change go unnoticed before `rateChanged` itself was added (see
 * [UsbAudioManager.ensureFormatActive]'s own doc comment for that history).
 */
private data class ActiveStreamConfig(val format: UacStreamFormat, val sampleRateHz: Int)

/**
 * Everything [UsbAudioManager] knows about the current DAC-activation attempt, as one value
 * instead of several independently-mutable fields. This file's own history is full of bugs
 * caused by exactly that kind of drift (a format cache cleared without clearing the session it
 * belonged to, an "is activating" flag that could disagree with whether a session actually
 * existed, a cached sink that could outlive the session it was built for) -- modeling the three
 * real states explicitly makes an illegal combination (e.g. "activating" while a session already
 * exists) unrepresentable instead of merely undocumented.
 */
private sealed class SessionState {
    /** No device activated, and nothing currently trying to activate one. */
    data object Idle : SessionState()

    /** [UsbAudioManager.activateDac] is running for [device] on its background thread right now. */
    data class Activating(val device: UsbDevice, val settled: CompletableDeferred<Unit>) : SessionState()

    /**
     * A real [UsbAudioSession] exists and is ready (or was) for playback.
     *
     * [format] is null exactly when [UsbClaimController] has just reclaimed the interface back
     * from the kernel (see [UsbAudioManager.clearActiveFormatOnReclaim]) -- the kernel driver may
     * have reset the alt-setting as a side effect, invalidating any cached "this is already
     * selected" assumption without invalidating the session itself. See
     * [UsbAudioManager.ensureFormatActive] for where that distinction is actually load-bearing.
     *
     * [sink] is the lazily-built, session-wide-cached [AudioSink] -- see
     * [UsbAudioManager.resolveActiveSink]'s doc comment for why one instance is deliberately
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

/**
 * Detects attached USB Audio Class devices (candidate DACs), manages permission requests, and
 * once permitted, activates a persistent [UsbAudioSession] configured and ready for playback.
 * [resolveActiveSink] hands out an [AudioSink] wired to that session for the player to use in place
 * of the normal AudioTrack-backed sink.
 *
 * Direct USB output reconfigures the DAC's clock to match each track's actual rate (see
 * [UsbAudioOutput], and [IsoPacketScheduler] for how 44100 Hz, which isn't a whole number of
 * samples per USB's 1ms isochronous packet, is handled without a fixed packet size).
 */
class UsbAudioManager(private val context: Context, private val usbSettings: UsbDacPreferences) :
    UsbDacPreferences.Listener {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val descriptorCache = UsbDacDescriptorCache(context)
    // Scoped to this app's own package rather than a fixed literal -- the host app's package
    // name is the natural, always-unique namespace for this broadcast action, and matches what
    // the permission-request Intent is already explicitly scoped to via setPackage() below.
    private val actionUsbPermission = context.packageName + ".action.USB_PERMISSION"

    /**
     * The real sample rate Android's shared audio HAL mixer runs at -- what any non-direct
     * track (any fallback track here) actually gets resampled to on its way out, regardless of
     * its own native rate. [AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE] is the public, documented
     * API for exactly this (confirmed matching this device's own declared output profile via
     * `dumpsys audio`'s "Active communication device" entry: a single fixed `sampling
     * rates=[48000]`) -- used by [RoutingAudioSink] for what it reports as
     * [PlaybackRoute.Fallback]'s `sampleRateHz`, since the track's own decoded rate isn't what
     * HAL is actually doing with it.
     */
    fun halOutputSampleRate(): Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0

    private val _diagnosticLog = MutableStateFlow<List<String>>(emptyList())
    /** Most-recent-first log of activation events, for the USB settings screen. */
    val diagnosticLog: StateFlow<List<String>> = _diagnosticLog.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Appends a timestamped line to [diagnosticLog] (newest first, capped at [LOG_CAPACITY]). */
    private fun logEvent(message: String) {
        val stamp = timeFormatter.format(Date())
        L.i(message)
        _diagnosticLog.value = (listOf("$stamp  $message") + _diagnosticLog.value).take(LOG_CAPACITY)
    }

    private val _pipelineStats = MutableStateFlow<UsbPipelineStats?>(null)
    /**
     * Live ring-buffer/USB-transfer stats for the "Pipeline" diagnostic screen -- see
     * [UsbPipelineStats] and [reportPipelineStats]. Null whenever nothing is actually streaming
     * right now, not just a transient pause-silence gap, so that screen can show "idle" instead
     * of numbers frozen from a track that already stopped.
     */
    val pipelineStats: StateFlow<UsbPipelineStats?> = _pipelineStats.asStateFlow()

    private val _controlState = MutableStateFlow<UsbControlState?>(null)
    /** Live, read-back-from-the-device state of the active session's adjustable controls. */
    val controlState: StateFlow<UsbControlState?> = _controlState.asStateFlow()

    private val _capabilities = MutableStateFlow<UsbDeviceCapabilities?>(null)
    /** Everything found on the attached device's descriptors -- see [UsbDeviceCapabilities]. */
    val capabilities: StateFlow<UsbDeviceCapabilities?> = _capabilities.asStateFlow()

    private val _playbackRoute =
        MutableStateFlow<PlaybackRoute>(PlaybackRoute.Fallback(bitDepth = null, sampleRateHz = 0, channels = 0))
    /**
     * Where the *currently configured track* is actually going out, reported by
     * [RoutingAudioSink] on every real `configure()` call -- not just whether a DAC session
     * exists, since a live bit-perfect session doesn't mean every individual track can use it
     * (e.g. a 96kHz track against a device whose clock only declares 44.1/48kHz falls back to
     * [PlaybackRoute.Fallback] for that one track, even mid-session). Purely informational, for
     * the Now Playing screen -- nothing here feeds back into any routing decision.
     */
    val playbackRoute: StateFlow<PlaybackRoute> = _playbackRoute.asStateFlow()

    // All hardware volume/mute decision logic lives here, not scattered across this class and
    // UsbAudioController -- see its own class doc comment for why that scatter was a real risk,
    // not just untidiness.
    private val volumeController =
        UsbVolumeController(audioManager, usbSettings, controlState, playbackRoute, ::logEvent)

    // Everything about who holds the raw USB interface claim right now and the hand-off to/from
    // Android's HAL lives here -- see its own class doc comment for why. getActiveSession reads
    // live, at the moment each callback actually runs (which can be after a real delay), not a
    // snapshot frozen when a call into this controller was first made -- deliberately preserving
    // the pre-extraction code's own behavior of reading its session field fresh every time.
    private val claimController =
        UsbClaimController(
            audioManager,
            volumeController,
            getActiveSession = { activeSession },
            onReclaimed = { clearActiveFormatOnReclaim() },
            onClaimFailed = {
                logEvent("Failed to reclaim USB interface from kernel -- deactivating session")
                deactivateDac()
            },
            // See UsbClaimController.awaitAndroidOutputTakeover()'s doc comment: on at least one
            // real device, Android's HAL never recognizes this device as long as we hold the
            // connection open at all, so a timeout here means fully deactivating -- not
            // reclaiming back to ourselves -- is what actually lets HAL reach the real device.
            onTakeoverTimeout = { deactivateDac() },
        )

    // Device attach/detach/permission tracking lives here, not in this class -- see its own
    // class doc comment for why. onDeviceDetached tears down any active session the same as the
    // pre-extraction receiver did inline; onRefreshed prunes activationAttemptedFor at the exact
    // same point (synchronously, right after devices itself updates) the pre-extraction code did.
    private val deviceDiscovery =
        UsbDeviceDiscovery(
            context,
            usbManager,
            usbSettings,
            actionUsbPermission,
            onDeviceDetached = { deactivateDac() },
            onRefreshed = { found ->
                activationAttemptedFor = activationAttemptedFor.takeIf { it in found.map { d -> d.device.deviceName } }
            },
        )
    val devices: StateFlow<List<UsbAudioDevice>> = deviceDiscovery.devices

    /** See [playbackRoute]. Called by [RoutingAudioSink], not meant for any other caller. */
    fun reportPlaybackRoute(route: PlaybackRoute) {
        _playbackRoute.value = route
    }

    private val _abxGroundTruth = MutableStateFlow<Boolean?>(null)
    /**
     * Whether the *current* track was secretly routed bit-perfect (`true`) or HAL fallback
     * (`false`) by [rollAbxTrial], or `null` if [UsbDacPreferences.abxTestMode] is off or no
     * trial could apply to this track (e.g. the device isn't [isBitPerfectCapable] right now).
     * Only consulted by [resolveActiveSink] -- the host app should use [abxPendingGuess] instead,
     * which is what's actually safe to act on without leaking the current track's route.
     */
    val abxGroundTruth: StateFlow<Boolean?> = _abxGroundTruth.asStateFlow()

    private val _abxPendingGuess = MutableStateFlow<Boolean?>(null)
    /**
     * The ground truth of the track that *just finished* (whichever one [abxGroundTruth] held
     * right before [rollAbxTrial] moved on to a new one), still ungraded -- `null` once
     * [submitAbxGuess] consumes it, or if that track had no trial running to begin with. The host
     * app should prompt for a guess the moment this goes non-null (i.e. on every track change),
     * not at some other point mid-track -- the whole point is asking about a track that's already
     * done playing, never the one that's still live.
     */
    val abxPendingGuess: StateFlow<Boolean?> = _abxPendingGuess.asStateFlow()

    data class AbxStats(val correct: Int = 0, val total: Int = 0)

    private val _abxStats = MutableStateFlow(AbxStats())
    /** Cumulative score across every [submitAbxGuess] call this process lifetime. */
    val abxStats: StateFlow<AbxStats> = _abxStats.asStateFlow()

    /**
     * Rerolls this track's hidden ABX coin flip -- called by [RoutingAudioSink] once per real
     * `configure()` call (i.e. once per track), *before* it resolves which delegate to use, so
     * each track gets an independent, fresh decision instead of being stuck on whatever the first
     * track in the session happened to pick (resolveActiveSink's own [activeSink] caching would
     * otherwise keep reusing one session-wide choice forever -- see its doc comment).
     *
     * Deliberately does not attempt to switch mid-track: this only ever changes what the *next*
     * `configure()` call sees, same mechanism (and same real-hardware-proven code path) as
     * ordinary bit-perfect/fallback switching already uses on every track change.
     */
    fun rollAbxTrial() {
        // Whatever the *previous* track's trial was (if any, and if it's not already been
        // guessed) is about to be overwritten below -- hand it off to abxPendingGuess first so
        // the host app can still prompt about it, now that this track change means it's actually
        // over. Unconditional: even a test turned off mid-track should still surface the trial
        // that was genuinely running for the track that just ended.
        _abxGroundTruth.value?.let { _abxPendingGuess.value = it }

        if (!usbSettings.abxTestMode) {
            _abxGroundTruth.value = null
            return
        }
        if (!usbSettings.enabled || !isBitPerfectCapable()) {
            // Can't offer a real trial if bit-perfect isn't even viable right now -- forcing one
            // would just break playback instead of testing anything.
            _abxGroundTruth.value = null
            return
        }
        val useBitPerfect = Random.nextBoolean()
        _abxGroundTruth.value = useBitPerfect
        if (useBitPerfect) {
            syncAbxLoudnessToBitPerfect()
        } else {
            // resolveActiveSink()'s activeSink?.let { return it } check runs before it ever
            // consults this trial's result -- without clearing it here, a track secretly
            // assigned to fallback would still get handed back a live, cached bit-perfect sink.
            (sessionState as? SessionState.Active)?.let { sessionState = it.copy(sink = null) }
        }
    }

    /**
     * Pushes the DAC's live hardware volume to match STREAM_MUSIC's *current* index, right
     * before an ABX trial that's about to go bit-perfect -- see
     * [UsbVolumeController.abxSyncTargetDb] for why this needs to exist at all.
     *
     * Reuses the exact same write path a physical volume key press already takes ([setVolume]),
     * including its own STREAM_MUSIC mirroring call -- a no-op round trip in this direction,
     * since it's already at the value just read from it here.
     */
    private fun syncAbxLoudnessToBitPerfect() {
        volumeController.abxSyncTargetDb()?.let { setVolume(it) }
    }

    /**
     * Grades [guessedBitPerfect] against [abxPendingGuess], updates [abxStats], and consumes the
     * trial (clears [abxPendingGuess] so the host app's prompt goes away until the next track
     * change surfaces a new one). Returns null if there was nothing pending -- the host app
     * should not have offered a guess in that case to begin with.
     */
    fun submitAbxGuess(guessedBitPerfect: Boolean): Boolean? {
        val truth = _abxPendingGuess.value ?: return null
        val correct = truth == guessedBitPerfect
        _abxStats.value = _abxStats.value.let { it.copy(correct = it.correct + if (correct) 1 else 0, total = it.total + 1) }
        _abxPendingGuess.value = null
        return correct
    }

    sealed interface PlaybackRoute {
        /**
         * Playing through Android's normal audio HAL (speaker, or whatever it last routed to --
         * see [RoutingAudioSink] for when this device's USB DAC itself is the actual HAL output).
         *
         * [bitDepth]/[sampleRateHz]/[channels] here are the *file's* own decoded format, same
         * meaning as [Direct]'s fields -- not what HAL is actually doing with it. HAL's shared
         * mixer really does resample every non-direct track to its own fixed output rate
         * regardless of what the file is (confirmed on this device via `dumpsys audio`: its
         * "Active communication device" profile declares a single fixed `sampling
         * rates=[48000]`, the same for every device, speaker included; see [halOutputSampleRate]
         * for that real value). But the Now Playing screen wants to show the file's format next
         * to an explicit "not honored" label, not leave the field blank, so these fields carry the
         * file's numbers -- the caller is responsible for making clear HAL isn't honoring them.
         */
        data class Fallback(val bitDepth: Int?, val sampleRateHz: Int, val channels: Int) : PlaybackRoute

        /** Streaming bit-perfect PCM directly to the USB DAC at these real, per-track values. */
        data class Direct(val bitDepth: Int?, val sampleRateHz: Int, val channels: Int) : PlaybackRoute

        /**
         * A real hand-off between [Fallback] and [Direct] is in progress -- [requestClaim] is
         * doing its (blocking, hundreds-of-ms-to-low-seconds) reclaim-and-relock-the-clock work,
         * or [releaseClaim] is waiting out [UsbClaimController]'s device-takeover wait. Shown so
         * the Now Playing screen doesn't look like nothing is happening during that gap.
         */
        data object Switching : PlaybackRoute
    }

    /** Called by [UsbAudioOutput] once it's actually claimed and streaming -- see [releaseClaim]. */
    fun notifyStreamingStarted() = claimController.notifyStreamingStarted()

    /** The inverse of [notifyStreamingStarted] -- call before, not after, releasing anything. */
    fun notifyStreamingStopped() = claimController.notifyStreamingStopped()

    /**
     * Called periodically (not per-URB -- see the call site) by the actively-streaming
     * [UsbAudioOutput]'s own streaming thread, and with `null` the moment it genuinely stops --
     * see [pipelineStats].
     */
    fun reportPipelineStats(stats: UsbPipelineStats?) {
        _pipelineStats.value = stats
    }

    /** [requestClaim]'s result -- see its own doc comment for what each caller should do with it. */
    enum class ClaimOutcome {
        /** We already held the interface; nothing was touched, nothing needs re-verifying. */
        ALREADY_CLAIMED,
        /** A genuine hand-off from the kernel happened -- anything it could have changed (clock
         * lock, mute, volume) must be re-verified before trusting it. */
        RECLAIMED,
        FAILED,
    }

    /**
     * Claims the interface (evicting whatever the kernel driver currently has bound) if we don't
     * already hold it -- idempotent and cheap to call when already claimed, so every caller about
     * to do real bit-perfect work (streaming, a Settings-screen control write) calls this first
     * rather than assuming the claim is still held from some earlier point. Blocking; callers on
     * this codebase's playback thread already tolerate similar synchronous USB I/O elsewhere (the
     * clock-relock settle wait in [UsbAudioOutput]'s init logic).
     *
     * Returns [ClaimOutcome.ALREADY_CLAIMED] rather than just `true` specifically so
     * [UsbAudioOutput.activateAndStream] can tell "nothing else could have touched the device
     * since we last set it up" apart from a genuine release+reclaim -- see its own doc comment for
     * why that distinction, not just success/failure, decides whether a resume needs to redo its
     * whole clock/mute/volume relock dance.
     */
    fun requestClaim(): ClaimOutcome = claimController.requestClaim(activeSession)

    /**
     * Schedules handing the interface back to the kernel's own driver after [delayMs] (see
     * [RELEASE_GRACE_MS]), canceling any release already pending -- so a [requestClaim] call
     * before the delay elapses is a pure no-op cancellation, not a real reclaim. Pass 0 to
     * release immediately (used when something -- a pause, a track that can't use the DAC, the
     * player itself -- is definitely, not just momentarily, done with it).
     *
     * [awaitAndroidTakeover] only changes whether *this call* blocks on the device-takeover wait
     * before returning -- a caller with its own next step that depends on Android having actually
     * recognized the device (e.g. [RoutingAudioSink.resolveForConfigure] about to let a fallback
     * track's `configure()` proceed) needs that. Every real release gets verified either way, so
     * a caller that doesn't need to block still gets a definitive log confirming -- or, on
     * timeout, warning -- whether the kernel actually took the device back, rather than silently
     * trusting the release call. See [UsbClaimController] for the actual claim/release mechanics
     * and that wait.
     *
     * [deactivateOnTimeout] should only be `true` when a concrete track genuinely needs this
     * device to reach HAL right now (currently only [RoutingAudioSink.resolveForConfigure]'s
     * fallback branch) -- see [UsbClaimController.releaseClaim]'s own doc comment for why every
     * other caller should leave it `false`.
     */
    fun releaseClaim(delayMs: Long = RELEASE_GRACE_MS, awaitAndroidTakeover: Boolean = false, deactivateOnTimeout: Boolean = false) {
        claimController.releaseClaim(delayMs, awaitAndroidTakeover, deactivateOnTimeout)
    }

    // In-memory only: which bit resolution to prefer when multiple formats are offered. Not
    // persisted across process death since changing it re-activates immediately anyway; a
    // settings-screen choice only has user-visible meaning while that screen (or the session it
    // started) is still around to show the effect.
    @Volatile private var preferredBitResolution: Int = 16

    /**
     * The single source of truth for where this device's activation is at -- see [SessionState]'s
     * own doc comment for the three states. [activeSession]/[activeFormat]/[activeRateHz]/
     * [activeSink]/[isActivating] below are read-only views derived from this one field, not
     * separately-mutable fields of their own. Written by [activateDac] (success path),
     * [settleActivation] (failure path, or a concurrent [deactivateDac] already settled it),
     * [deactivateDac] itself, and a handful of single-field `copy()` updates
     * ([ensureFormatActive], [clearActiveFormatOnReclaim], [resolveActiveSink], [rollAbxTrial])
     * that only make sense while already [SessionState.Active].
     */
    @Volatile private var sessionState: SessionState = SessionState.Idle

    private val activeSession: UsbAudioSession?
        get() = (sessionState as? SessionState.Active)?.session

    private val activeFormat: UacStreamFormat?
        get() = (sessionState as? SessionState.Active)?.format

    // Only ever meaningful once a session is Active -- see SessionState.Active.rateHz's own doc
    // comment for why nothing outside that state ever needs to read this.
    private val activeRateHz: Int
        get() = (sessionState as? SessionState.Active)?.rateHz ?: SUPPORTED_SAMPLE_RATE_HZ

    // A host app building more than one audio renderer (e.g. a preferred decoder plus a
    // fallback), each wrapping its own RoutingAudioSink -- both get queried
    // (supportsFormat/getFormatSupport, and apparently position) by ExoPlayer's renderer
    // machinery, not just whichever one actually ends up streaming. resolveActiveSink() returning
    // a *fresh* UsbAudioSink per call meant each RoutingAudioSink got its own, separate instance:
    // one got genuinely configured and streamed correctly, but the other sat forever unconfigured
    // (sampleRateHz=0), and its getCurrentPositionUs() dutifully reporting
    // AudioSink.CURRENT_POSITION_NOT_SET (Long.MIN_VALUE) seems to be exactly what corrupted the
    // player's overall position tracking -- observed as it jumping to nonsense values like
    // -999999475 before stalling. Caching a single instance per session, shared across both
    // RoutingAudioSinks, means whichever one actually gets used reports real progress to *both*
    // callers.
    private val activeSink: AudioSink?
        get() = (sessionState as? SessionState.Active)?.sink

    private val isActivating: Boolean
        get() = sessionState is SessionState.Activating

    /**
     * Clears a stale cached alt-setting selection after [UsbClaimController] reclaims the
     * interface back from the kernel -- see [SessionState.Active.format]'s doc comment. A no-op
     * if nothing is [SessionState.Active] right now.
     */
    private fun clearActiveFormatOnReclaim() {
        (sessionState as? SessionState.Active)?.let { sessionState = it.copy(format = null) }
    }

    private val _blockPlayback = MutableStateFlow(false)
    /**
     * True whenever a host UI's play control should hold off starting playback rather than let
     * it land on [PlaybackRoute.Fallback] purely because [activateDac] hasn't finished yet --
     * specifically: [UsbDacPreferences.enabled] and [UsbDacPreferences.bitPerfectMode] are both
     * on (the exact condition [resolveActiveSink] itself gates on) *and* an activation triggered
     * by [refresh]/[retryActivation] is currently in flight. False the rest of the time,
     * including whenever there's no DAC in the picture at all, so a host UI binding its play
     * button to this costs nothing outside the one real race window.
     *
     * This exists because [awaitActivation]'s fixed timeout is a guess against real, variable
     * hardware-activation latency -- confirmed, reproduced on real hardware: a measured
     * attach-to-"DAC session ready" activation took 5.24s end to end, close enough to the (then
     * 4000ms, since bumped to 8000ms) bound that a restore landing unluckily close to
     * activation's start could still time out early and legitimately fall back for just the
     * first track. A host UI that disables its own play control on this instead -- actually
     * waiting for activation rather than racing a number -- doesn't have that failure mode at
     * all, at the cost of the user seeing the control briefly disabled instead of silent fallback.
     */
    val blockPlayback: StateFlow<Boolean> = _blockPlayback.asStateFlow()

    /** Recomputes [blockPlayback] from current settings and activation state. */
    private fun updateBlockPlayback() {
        _blockPlayback.value = usbSettings.enabled && usbSettings.bitPerfectMode && isActivating
    }

    /**
     * Runs [activateDac] on a dedicated background daemon thread, moving [sessionState] into
     * [SessionState.Activating] (carrying the awaitable deferred -- see [awaitActivation]) around
     * it and back out via [settleActivation] -- the single place both [ensureActivated] and
     * [retryActivation] dispatch activation from, so neither signal can drift out of sync with
     * whether a background activation is actually running.
     *
     * Also the single place [activationAttemptedFor] gets marked, not just [ensureActivated]'s
     * own lazy path -- confirmed, reproduced: a session last (re)activated via [retryActivation]
     * (which calls this directly, bypassing [ensureActivated]'s guard entirely) left
     * [activationAttemptedFor] unset for this attachment, so the instant [activeSession] went
     * null again (e.g. [UsbClaimController]'s own timeout-escalation calling [deactivateDac]),
     * the very next per-buffer [resolveActiveSink] call's [ensureActivated] check saw no record
     * of a prior attempt and immediately reclaimed the interface -- winning the race against
     * Android's HAL recognizing the just-freed device every single time, since that reclaim is
     * synchronous and HAL's recognition never is. Marking it here instead means a device that's
     * been tried at all during this attachment, by any path, stays left alone until the user's
     * own retry/replug, exactly as [ensureActivated]'s "once per attachment" doc comment already
     * claimed it would.
     */
    private fun runActivation(device: UsbDevice) {
        activationAttemptedFor = device.deviceName
        val deferred = CompletableDeferred<Unit>()
        sessionState = SessionState.Activating(device, deferred)
        updateBlockPlayback()
        Thread {
                try {
                    activateDac(device)
                } finally {
                    settleActivation()
                    deferred.complete(Unit)
                }
            }
            .apply {
                isDaemon = true
                start()
            }
    }

    /**
     * Called when a background [activateDac] call returns, success or failure. If it already
     * transitioned [sessionState] to [SessionState.Active], leave it alone -- this only has to
     * recover from the failure case (still [SessionState.Activating]) or a concurrent
     * [deactivateDac] that already reset to [SessionState.Idle] in the meantime (checking first
     * avoids implying this call is what decided we're idle, when it wasn't).
     */
    private fun settleActivation() {
        if (sessionState is SessionState.Activating) sessionState = SessionState.Idle
        updateBlockPlayback()
    }

    private var started = false
    // The device name [ensureActivated] has already tried activation for during its current
    // attachment, successfully or not -- same pruning/re-eligibility pattern as
    // [permissionRequestedFor] (see its own comment), so a replug gets a fresh automatic attempt
    // but a device that's just sitting there, already tried and failed (or already activated),
    // doesn't retry on every single track's resolveActiveSink() call.
    private var activationAttemptedFor: String? = null

    // Only ACTION_VOLUME_CHANGED -- device attach/detach/permission broadcasts are
    // UsbDeviceDiscovery's own receiver now (see its class doc comment for why this one stayed
    // separate rather than folding into it).
    private val volumeChangeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_VOLUME_CHANGED &&
                    intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) {
                    volumeController.onStreamMusicChanged(
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                }
            }
        }

    /** Begins listening for USB device attach/detach/permission events. Call once at startup. */
    fun start() {
        if (started) return
        started = true
        usbSettings.registerListener(this)
        deviceDiscovery.start()

        val filter = IntentFilter(ACTION_VOLUME_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(volumeChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(volumeChangeReceiver, filter)
        }
    }

    /** Requests permission to use [device]. The result arrives asynchronously via broadcast. */
    fun requestPermission(device: UsbDevice) = deviceDiscovery.requestPermission(device)

    /**
     * Waits, bounded by [timeoutMs], for the most recently started [activateDac] to finish --
     * see [SessionState.Activating]'s doc comment for why a caller about to start playback would
     * want this. A no-op (returns immediately) if nothing's currently activating, so this costs
     * nothing outside the actual race window (right after a permission grant, a device
     * reattachment, or anything else that re-triggers [activateDac]). Deliberately doesn't
     * report success/failure -- the caller should just proceed with playback either way;
     * [resolveActiveSink] returning null (e.g. activation genuinely failed, or there's no DAC at
     * all) falls back exactly as if this had never been called.
     *
     * [resolveActiveSink] already calls this internally when triggering, so most callers don't
     * need to -- this stays public for a caller that specifically wants to wait *before* doing
     * anything else (e.g. [ExoPlaybackStateHolder][org.oxycblt.auxio.playback.service.ExoPlaybackStateHolder]'s
     * cold-launch restore path awaits once up front rather than letting the first per-track
     * triggering [resolveActiveSink] call absorb the wait on its own).
     *
     * [timeoutMs] default was 4000, bumped to 8000: a real measured attach-to-"DAC session
     * ready" activation on real hardware (FiiO Snowsky Tiny A) took 5.24s end to end (permission
     * grant -> topology parse -> clock set -> settle). 4000ms was tighter than that, so a
     * cold-launch restore landing close to activation's start could time out ~1s early and
     * legitimately fall back to HAL for just the first track, self-correcting on the very next
     * one once [activeSink] was populated -- confirmed, reproduced via real-hardware logcat
     * timing. Not a correctness bug (it always recovers), but a user-visible first-track
     * inconsistency; 8000ms gives real margin over the measured worst case.
     */
    suspend fun awaitActivation(timeoutMs: Long = 8000L) {
        val deferred = (sessionState as? SessionState.Activating)?.settled ?: return
        withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    /**
     * Kicks off [runActivation] if nothing has tried activating the current permitted device yet
     * -- called by [resolveActiveSink] (with `trigger = true`) right before [awaitActivation], so
     * the *first* real attempt to use bit-perfect playback is what triggers the actual
     * USB-interface claim, not merely having the app open with permission already granted. See
     * [permissionRequestedFor]'s sibling field [activationAttemptedFor] for why this doesn't also
     * retry forever on a device that's already been tried (successfully or not) -- that's still
     * [retryActivation]'s job, or a replug's.
     *
     * This is deliberately lazy where the old eager-on-permission-grant trigger wasn't: activating
     * forcibly evicts the kernel's own ALSA driver from the interface to read descriptors/volume
     * range, which tears down and recreates that device's ALSA PCM node -- confirmed, reproduced:
     * merely opening this app (never pressing play, never touching bit-perfect mode) was enough
     * to silence another app's audio on the same device for up to a minute while the kernel
     * re-settled, purely as a side effect of having been opened at all. Deferring the claim to an
     * actual playback attempt means that disruption only happens when something's genuinely about
     * to use the DAC, the same real cost already paid on every cold activation today.
     *
     * "Opened at all" turned out to still include a *paused* app-launch state restore -- confirmed,
     * reproduced: [RoutingAudioSink.configure] (and the format queries that precede it) run during
     * `Player.prepare()` whether or not `playWhenReady` is true, so this was still reachable, and
     * thus still evicted another app's in-progress use of the device, from a cold launch that never
     * pressed play at all. [resolveActiveSink]'s `trigger` parameter is what actually closes that
     * gap now -- this function itself didn't change, only who's allowed to reach it.
     */
    private fun ensureActivated() {
        if (sessionState != SessionState.Idle) return
        if (!usbSettings.enabled) return
        val device = deviceDiscovery.devices.value.firstOrNull { it.hasPermission } ?: return
        if (device.device.deviceName == activationAttemptedFor) return
        runActivation(device.device)
    }

    /**
     * Non-blocking counterpart to passing `trigger = true` into [resolveActiveSink] -- kicks off
     * [ensureActivated] without also waiting on [awaitActivation] (whose own default timeout is
     * 8 seconds), for a caller that has genuine play intent but can't afford that bounded block
     * before it can even start producing sound.
     *
     * [RoutingAudioSink.play] is exactly that caller: [RoutingAudioSink.current]'s own liveness
     * re-check only ever calls into [UsbAudioManager] when the existing delegate is *already* a
     * USB sink (see its own doc comment) -- never when sitting on [RoutingAudioSink.fallback], which
     * is exactly the state a paused state-restore's [resolveForConfigure] call deliberately leaves
     * things in now (see [ensureActivated]'s doc comment on why that call no longer triggers
     * either). Without something else triggering activation, a genuine play press landing in that
     * state had no path left to ever upgrade to USB at all -- confirmed, reproduced: play started
     * immediately on HAL and stayed there, since nothing ever re-ran [resolveForConfigure] for an
     * unchanged track on a plain pause/play resume. Calling this here, fire-and-forget, lets
     * [activeSession] -- and so [controlState] -- flip in the background exactly as it would for a
     * DAC plugged in mid-track, which [ExoPlaybackStateHolder][org.oxycblt.auxio.playback.service.ExoPlaybackStateHolder]'s
     * `forceReconfigureOnDacStateChange` already watches for and upgrades on, without this call
     * itself blocking the first audible frame on HAL while that happens.
     */
    fun triggerActivation() = ensureActivated()

    /**
     * Returns an [AudioSink] wired to the active DAC session, or null if none is ready -- or if
     * [UsbDacPreferences.bitPerfectMode] is off, or the device isn't [isBitPerfectCapable]. A device
     * can be fully activated (Controls/Capabilities usable) without ever being used for actual
     * playback: [RoutingAudioSink] falls back to normal Android audio output whenever this
     * returns null, same mechanism as a genuinely absent/unplugged device, and it already
     * re-checks this on every track (see its own liveness re-validation), so toggling bit-perfect
     * mode takes effect on the very next [RoutingAudioSink.configure] call.
     *
     * [trigger] gates whether this also kicks off (and, if already in flight, blocks bounded via
     * [awaitActivation] on) [ensureActivated] when nothing's activated yet, rather than always
     * doing so unconditionally -- [RoutingAudioSink] passes its own `isPlaying` here, true for
     * every call made with genuine, current playback intent (including every buffer handled while
     * actively playing, so a device that becomes available *during* fallback playback still gets
     * picked up the same way it always did) and false for a merely-preparing-but-paused track
     * (e.g. an app-launch state restore) or a pure format-capability query, neither of which should
     * themselves provoke the eviction activation causes -- see [ensureActivated]'s own doc comment
     * for the concrete bug this closes. Blocking (bounded) on an in-flight [activateDac] *before*
     * checking capability/session state, not after, still matters whenever [trigger] is true and
     * something actually is in flight -- otherwise this would race any caller, not just the one
     * that happens to remember to await first. Confirmed, reproduced on real hardware: a device
     * reattachment's background [activateDac] has no caller-side await covering it at all (unlike
     * the one call site that was patched for the original cold-launch version of this race), so a
     * track change or play press landing in that window lost the race every time, with zero
     * recovery short of a manual track change -- this closes the gap for every triggering call at
     * once instead of patching each one individually. Only blocks the one call that actually hits
     * the race window: [activeSink] (the common case) is still a zero-cost return above the await,
     * and the await itself is already a no-op when nothing's activating.
     *
     * Returns a plain [DefaultAudioSink] built with a [UsbAudioOutputProvider] rather than a
     * hand-rolled `AudioSink` -- see [UsbAudioOutput]'s doc comment for why.
     */
    fun resolveActiveSink(trigger: Boolean): AudioSink? {
        activeSink?.let { return it }
        if (trigger) {
            ensureActivated()
            runBlocking { awaitActivation() }
        }
        // While an ABX test is running, rollAbxTrial() -- called by RoutingAudioSink right
        // before this -- already decided this track's route; substitute that for the user's own
        // bitPerfectMode toggle so the test actually controls what plays, not just what's shown.
        val bitPerfectWanted = if (usbSettings.abxTestMode) _abxGroundTruth.value == true else usbSettings.bitPerfectMode
        if (!bitPerfectWanted || !isBitPerfectCapable()) return null
        val session = activeSession ?: return null
        return DefaultAudioSink.Builder(context)
            .setAudioOutputProvider(UsbAudioOutputProvider(this, session, usbSettings))
            // Off by default in DefaultAudioSink -- without this, getFormatSupport() silently
            // rewrites every float-PCM query back down to 16-bit before UsbAudioOutputProvider
            // ever sees it, which is exactly what made every decoder's own float-vs-16-bit
            // negotiation always choose 16-bit regardless of the DAC's real capability --
            // confirmed, reproduced via a temporary diagnostic log: RoutingAudioSink.
            // getFormatSupport() correctly received the decoder's pcmEncoding=4 (FLOAT) query,
            // but this exact sink -- not the RoutingAudioSink.fallback one, which already has
            // this flag -- rewrote it to 16-bit before UsbAudioOutputProvider's own log ever
            // saw anything but "16-bit". See DefaultAudioSink.getFormatSupport()'s own
            // shouldUseFloatOutput() check.
            .setEnableFloatOutput(true)
            .build()
            .also { sink -> (sessionState as? SessionState.Active)?.let { sessionState = it.copy(sink = sink) } }
    }

    /**
     * Switches the DAC's active alt-setting to [format] if it isn't already, and records
     * [sampleRateHz] as the real currently-active rate -- called by
     * [UsbAudioOutputProvider.getAudioOutput] for every track, so a library mixing standard
     * 16-bit tracks with 24-bit hi-res ones (or different sample rates) gets a device format
     * that actually matches *each* track, rather than being stuck with a single bit depth for the
     * whole session (either corrupting the other, byte-misaligned -- confirmed, reproduced as
     * loud hissing -- or rejecting it outright).
     *
     * [activeRateHz]/[controlState]'s sample rate used to only ever get set once, by
     * [activateDac] at startup -- harmless before this session's per-track dynamic format
     * switching existed (every track genuinely did stream at the same rate activateDac picked),
     * but once tracks could switch rate on their own, the Settings screen kept showing that
     * stale startup rate forever after, even while actually playing something else entirely.
     */
    fun ensureFormatActive(format: UacStreamFormat, sampleRateHz: Int): Boolean {
        val session = activeSession ?: return false
        // Switching the alt-setting is itself a claimed-interface operation -- make sure we
        // actually hold it before trying, rather than assuming whatever called us already did.
        // UsbAudioOutput's own construction (right after this returns) needs it held too, so
        // this must succeed before selectFormat, not be left for later.
        if (requestClaim() == ClaimOutcome.FAILED) return false
        val previousConfig = activeFormat?.let { ActiveStreamConfig(it, activeRateHz) }
        val newConfig = ActiveStreamConfig(format, sampleRateHz)
        val configChanged = previousConfig != newConfig
        var ok = true
        if (configChanged) {
            // Re-selecting the alt-setting -- even to the *same* alternateSetting value when
            // only the sample rate changed, not the bit depth -- always issues a real
            // SET_INTERFACE request (UsbDeviceConnection.setInterface never short-circuits a
            // same-value call), which resets the device's endpoint, flushing whatever's still
            // sitting in its internal FIFO from the previous rate. Without this, a same-alt
            // rate-only change (e.g. 24-bit 44.1kHz -> 24-bit 96kHz) never touched the
            // interface at all, since the old condition only re-selected on a genuine bit-depth
            // change -- confirmed, reproduced: exactly that rate-only case (not a bit-depth
            // change, which already re-selected and so was never affected) produced an audible
            // crackle right as streaming resumed, consistent with leftover samples from the old
            // rate getting flushed out of the device's FIFO at the new rate's timing instead of
            // discarded cleanly beforehand.
            //
            // Same reasoning as the identical call in activateDac(): Android's own ALSA driver
            // can race to re-claim the interface, and evicting it again right before
            // selectFormat is safe/cheap insurance against that.
            UsbAudioController.reassertKernelEviction(session)
            ok = UsbAudioController.selectFormat(session, format)
            if (ok) {
                (sessionState as? SessionState.Active)?.let { sessionState = it.copy(format = format) }
                logEvent("Switched to ${format.bitResolution}-bit (alt=${format.alternateSetting}) for upcoming track")
            } else {
                logEvent("Failed to switch to ${format.bitResolution}-bit (alt=${format.alternateSetting}) for upcoming track")
            }
        }
        if (configChanged) {
            (sessionState as? SessionState.Active)?.let { sessionState = it.copy(rateHz = sampleRateHz) }
            refreshControlState(session, activeFormat ?: format, session.device.productName ?: session.device.deviceName)
        }
        return ok
    }

    /**
     * Whether the active device is bit-perfect capable: its output Feature Unit was found *and*
     * its volume range was successfully read back (confirming we actually know where unity
     * gain is, rather than just assuming it) -- see [UsbControlState.volumeRange]. This is what
     * gates whether the "Bit-perfect mode" toggle is interactable at all; false if nothing is
     * active right now.
     */
    fun isBitPerfectCapable(): Boolean = controlState.value?.volumeRange != null

    /**
     * Reads the active session's Power Domain state right now (one of
     * [UsbAudioController.PD_STATE_D0]/`D1`/`D2`), or null if there's no active session or this
     * device's topology has no Power Domain at all (UAC 1.0/2.0, or a UAC 3.0 device that didn't
     * declare one). A blocking USB control transfer, same as the other on-demand reads this class
     * does in response to a UI query (e.g. [setMuted]'s read-back) -- fine for an occasional
     * settings-screen refresh, not meant to be polled.
     */
    fun getPowerDomainState(): Int? {
        val session = activeSession ?: return null
        val powerDomainId = session.topology.powerDomainId
        if (powerDomainId < 0) return null
        return UsbAudioController.getPowerDomainState(session, powerDomainId)
    }

    /**
     * Reads the active session's Clock Source frequency and valid/locked state right now, or
     * null if there's no active session or this device has no Clock Source at all (UAC 1.0 --
     * see [UacPlaybackTopology.clockSourceId]). Same blocking-read caveat as
     * [getPowerDomainState].
     */
    fun getClockInfo(): Pair<Int?, Boolean?>? {
        val session = activeSession ?: return null
        if (session.topology.clockSourceId < 0) return null
        return UsbAudioController.getClockFrequency(session) to UsbAudioController.getClockValid(session)
    }

    private fun activateDac(device: UsbDevice) {
        logEvent("Activating ${device.productName ?: device.deviceName}")

        // A cache hit skips this whole raw-descriptor fetch+walk -- and, further down, the
        // Cluster Descriptor and Clock Source range queries too -- entirely. Those queries
        // specifically need the interface claimed, so skipping them on a device we've already
        // resolved before shortens how long that claim (which evicts the kernel's own ALSA
        // driver, see ensureActivated's doc comment) needs to stay held this time.
        val cached = descriptorCache.get(device.vendorId, device.productId)
        var topology: UacPlaybackTopology? = cached?.topology
        if (cached != null) {
            logEvent("Using cached descriptor topology for ${device.deviceName}")
        } else {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                logEvent("Could not open USB connection to ${device.deviceName}")
                return
            }
            try {
                for (i in 0 until device.configurationCount.coerceAtLeast(1)) {
                    val bytes = UsbAudioDescriptorParser.fetchConfigurationDescriptor(connection, i) ?: continue
                    // Walk once, reuse the result across all four calls below -- each
                    // independently re-walking the same bytes was a real, pointless 4x linear
                    // pass per configuration index (see UsbAudioDescriptorParser.walk's doc
                    // comment).
                    val entries = UsbAudioDescriptorParser.walk(bytes)
                    UsbAudioDescriptorParser.dumpAcTopology(entries)
                    UsbAudioDescriptorParser.parseUac3Playback(entries)?.let { topology = it }
                    if (topology == null) {
                        UsbAudioDescriptorParser.parseUac2Playback(entries)?.let { topology = it }
                    }
                    if (topology == null) {
                        UsbAudioDescriptorParser.parseUac1Playback(entries)?.let { topology = it }
                    }
                }
            } finally {
                connection.close()
            }
        }

        var found = topology
        if (found == null) {
            logEvent("No UAC1/UAC2/UAC3 playback topology found on ${device.deviceName}")
            return
        }
        val deviceName = device.productName ?: device.deviceName
        _capabilities.value =
            UsbDeviceCapabilities(
                deviceName = deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                topology = found,
                interfaces = UsbAudioController.listInterfaces(device, found))
        val format =
            found.formats.firstOrNull { it.bitResolution == preferredBitResolution }
                ?: found.formats.firstOrNull()
        if (format == null) {
            logEvent("No usable format available for ${device.deviceName}")
            return
        }
        logEvent(
            "Topology found: mixer=${found.mixerUnitId} featureUnit=${found.outputFeatureUnitId} " +
                "clock=${found.clockSourceId}")

        var session = UsbAudioController.activate(context, device, found)
        if (session == null) {
            logEvent("Failed to activate UAC${found.uacVersion} session for ${device.deviceName}")
            return
        }

        // Query what sample rates the Clock Source actually supports before ever asking it to
        // change rate -- UAC 1.0 has none, and this was never checked at all before: a real
        // device accepted a SET_CUR to an unsupported rate and echoed it back as a successful
        // readback while the real hardware clock silently stayed where it was (audibly: pitched
        // up and sped up, every sample converted at the wrong rate despite 0 USB errors anywhere
        // in the chain). See UsbAudioSession.clockSupportedRates and where
        // UsbAudioOutputProvider consults it -- an empty result here (couldn't read, not
        // "nothing supported") falls back to its old hardcoded guess rather than refusing every
        // rate.
        if (found.uacVersion != 1) {
            val ranges =
                cached?.clockSupportedRates ?: (UsbAudioController.getClockFrequencyRanges(session) ?: emptyList())
            session = session.copy(clockSupportedRates = ranges)
            logEvent("Clock ${found.clockSourceId} supported rate ranges: $ranges")
        }

        // UAC 3.0 pushes the real channel count into a separate Cluster Descriptor fetch that
        // UsbAudioDescriptorParser can't do during pure descriptor parsing -- there's no live
        // session yet at that point (see UacPlaybackTopology.channelCount's doc comment). Do it
        // now that we have one, before anything below reads found.channelCount (the volume/mixer
        // loops) or publishes it (Capabilities screen, UsbAudioOutputProvider.channels via
        // session.topology). Confirmed on real hardware: this device's cluster declares 2
        // channels, same as its EarPods form factor implies.
        if (found.uacVersion == 3 && found.channelCount < 0 && format.clusterDescriptorId >= 0) {
            // clusterDescriptorId lives on UacStreamFormat (per-format), not UacPlaybackTopology
            // -- use the format already selected above, not found itself.
            //
            // Not a .let{} on the nullable result -- reassigning found/session from inside a
            // lambda would make the compiler drop its smart-cast of both to non-null for the
            // rest of this function (a lambda could, in principle, outlive this call), breaking
            // every later found./session. use below despite this being called synchronously.
            val channels = UsbAudioController.getClusterChannelCount(session, format.clusterDescriptorId)
            if (channels != null) {
                found = found.copy(channelCount = channels)
                session = session.copy(topology = found)
                logEvent("Cluster ${format.clusterDescriptorId} declares $channels channel(s)")
                _capabilities.value =
                    UsbDeviceCapabilities(
                        deviceName = deviceName,
                        vendorId = device.vendorId,
                        productId = device.productId,
                        topology = found,
                        interfaces = UsbAudioController.listInterfaces(device, found))
            }
        }

        // Normalized to a floor of 2 exactly once, here, instead of every downstream reader
        // independently doing `.coerceAtLeast(2)` (previously duplicated across this file's own
        // volume/mixer loops, UsbAudioOutputProvider.channels, and refreshControlState). This
        // covers both "unknown" (-1: the cluster fetch above failed or didn't apply, or a UAC1/2
        // device's Input Terminal channel count itself couldn't be parsed -- see
        // UacPlaybackTopology.channelCount's doc comment) and a genuinely-parsed mono (1)
        // device -- the floor of 2 was a deliberate safety margin applied to both cases
        // previously, not just the unknown one, and this keeps that behavior rather than only
        // fixing up -1 specifically.
        if (found.channelCount < 2) {
            logEvent("Channel count ${found.channelCount} below floor, coercing to 2")
            found = found.copy(channelCount = 2)
            session = session.copy(topology = found)
        }

        // Only on a fresh parse -- a cache hit already has this device's final, resolved
        // topology and rate ranges on file, and re-writing an identical row every activation
        // would just be wasted DB I/O.
        if (cached == null) {
            descriptorCache.put(device.vendorId, device.productId, found, session.clockSupportedRates)
        }

        // Do this before anything else: its recovery wait (up to 300ms for a D2->D0 wake, per
        // this device's own declared waRecoveryTime2) should overlap with -- not follow -- the
        // rest of setup below, and nothing else here is safe to skip past a domain that might
        // still be asleep.
        if (found.powerDomainId >= 0) {
            UsbAudioController.wakePowerDomain(
                session, found.powerDomainId, found.powerDomainD1RecoveryUs, found.powerDomainD2RecoveryUs)
            logEvent(
                "Power domain ${found.powerDomainId} woken (d1=${found.powerDomainD1RecoveryUs}us " +
                    "d2=${found.powerDomainD2RecoveryUs}us)")
        } else {
            logEvent("No power domain found covering our USB input")
        }

        // UAC 2.0/3.0: the Clock Source entity is addressed independently of which streaming
        // alternate setting ends up selected, so this can happen up front. UAC 1.0 has no Clock
        // Source at all -- its sample rate is a property of the streaming *endpoint* itself, set
        // further below, after selectFormat has made that endpoint active (see there for why).
        val clockOk =
            if (found.uacVersion != 1) {
                UsbAudioController.setClockFrequency(session, SUPPORTED_SAMPLE_RATE_HZ).also {
                    logEvent("Clock set to $SUPPORTED_SAMPLE_RATE_HZ Hz: ok=$it")
                }
            } else {
                true
            }
        val muted = UsbAudioController.getMute(session)
        if (muted != false) UsbAudioController.setMute(session, muted = false)
        // Was hardcoded 0..2 (master + stereo) -- the Feature Unit has one volume control per
        // logical channel (1..N) plus the shared master (0), so a device with more than 2
        // channels has per-channel controls beyond what that range ever touched. channelCount
        // is read from the device's own Input Terminal descriptor, not assumed; only the loop
        // bound was ever hardcoded to stereo.
        //
        // Master channel (0) volume: we check master *mute* above, but had never checked
        // master *volume* -- if this Feature Unit has an independent master gain stage
        // separate from the per-channel ones below, it being low/attenuated would silence
        // everything regardless of what the per-channel ones are set to.
        for (channel in 0..found.channelCount) {
            val volume = UsbAudioController.getVolume(session, channel)
            // Deliberately the midpoint of the device's own reported range, not its max (unity
            // gain) -- a freshly (re)activated DAC shouldn't jump straight to its loudest
            // setting. See UsbAudioController.defaultVolumeTarget.
            val range = UsbAudioController.getVolumeRange(session, channel)
            val target = UsbAudioController.defaultVolumeTarget(range)
            if (volume == null || volume != target) {
                UsbAudioController.setVolume(session, value = target, channel = channel)
            }
            logEvent("Volume ch$channel: was=$volume range=$range target=$target")
        }

        // Our USB input doesn't feed the output Feature Unit directly on every device -- on
        // this one it's first summed into a Mixer Unit alongside a second source (the hardware
        // mic path), discovered via UsbAudioDescriptorParser.dumpAcTopology. The Feature Unit
        // being correctly unmuted at unity means nothing if the mixer's own per-channel gain for
        // our input defaults to silence, independent of and upstream from everything above.
        val mixerUnitId = found.mixerUnitId
        val mixerBase = found.mixerInputChannelBase
        // Was hardcoded to a stereo-in/stereo-out cross-point grid; both sides scale with the
        // device's own real channel count instead (not assumed). found.channelCount is already
        // normalized to never be -1/unknown by this point (see the channel-count fallback above).
        val mixerChannelCount = found.channelCount
        if (mixerUnitId >= 0 && mixerBase >= 0) {
            for (inCh in mixerBase until mixerBase + mixerChannelCount) {
                for (outCh in 1..mixerChannelCount) {
                    val gain = UsbAudioController.getMixerGain(session, mixerUnitId, inCh, outCh)
                    val gainRange = UsbAudioController.getMixerGainRange(session, mixerUnitId, inCh, outCh)
                    val gainTarget = gainRange?.second ?: 0
                    if (gain == null || gain != gainTarget) {
                        UsbAudioController.setMixerGain(session, mixerUnitId, inCh, outCh, gainTarget)
                    }
                    logEvent("Mixer gain in=$inCh out=$outCh: was=$gain range=$gainRange target=$gainTarget")
                }
            }
        } else {
            logEvent("No mixer unit gating our USB input (mixerUnitId=$mixerUnitId base=$mixerBase)")
        }

        // Android's own UsbAlsaManager independently binds the kernel's snd-usb-audio driver to
        // any USB Audio Class device on attach; that bind was observed completing (ALSA device
        // node creation logged) *during* this very activation sequence, after the one-time
        // disconnectKernelDriver pass in UsbAudioController.activate() had already run -- so it
        // can still be racing us at this point. Evicting it again here, right before the final
        // alt-setting selection, is safe; evicting it *after* selectFormat was tried first and
        // broke every subsequent iso submission (errno=ENOENT), almost certainly because
        // disconnecting a driver resets the interface to alt-setting 0 as a side effect, undoing
        // whatever alt-setting was just selected. So this must stay before selectFormat, not after.
        UsbAudioController.reassertKernelEviction(session)
        val formatOk = UsbAudioController.selectFormat(session, format)
        logEvent("Alt-setting ${format.alternateSetting} (${format.bitResolution}-bit) selected: ok=$formatOk")

        // UAC 1.0's Sampling Frequency Control lives on the streaming endpoint itself, which
        // doesn't functionally exist until selectFormat (just above) has made its alternate
        // setting active -- so this has to happen after, unlike UAC 2.0/3.0's entity-addressed
        // clock above. Pick whichever of this format's declared rates matches what the rest of
        // the app expects (48kHz, falling back to 44.1kHz), or just its first if neither is
        // offered.
        val rateHz =
            if (found.uacVersion == 1) {
                format.sampleRates.firstOrNull { it == SUPPORTED_SAMPLE_RATE_HZ }
                    ?: format.sampleRates.firstOrNull { it == 44100 }
                    ?: format.sampleRates.firstOrNull()
            } else {
                SUPPORTED_SAMPLE_RATE_HZ
            }
        val rateOk =
            if (found.uacVersion == 1) {
                if (rateHz == null) {
                    logEvent("Format alt=${format.alternateSetting} declares no sample rate")
                    false
                } else {
                    UsbAudioController.setUac1EndpointSampleRate(session, format.endpointAddress, rateHz).also {
                        logEvent("UAC1 endpoint sample rate set to $rateHz Hz: ok=$it")
                    }
                }
            } else {
                clockOk
            }

        if (!rateOk || !formatOk) {
            logEvent("Failed to configure DAC session (rate=$rateOk format=$formatOk)")
            UsbAudioController.release(session)
            return
        }

        // Give the clock/alt-setting/power-domain changes above a moment to actually settle
        // before marking the session ready. Do NOT call reassertKernelEviction after
        // selectFormat -- see the comment above that call for why.
        //
        // Deliberately not blocking on a diagnostic sine-tone playback test here anymore (that
        // tool, IsoToneTest, has since been removed entirely): it was useful once while chasing
        // the intermittent-silence investigation,
        // but running it on every single activation pushed "DAC session ready" out by 3+ seconds
        // -- which, in real playback, is 3+ seconds RoutingAudioSink's configure() could get
        // called first and commit to the fallback (AudioTrack/speaker) sink before this ever
        // finishes, exactly what was observed (tracks playing through the tablet speaker). The
        // settings screen's "Retry activation" + its live controls now cover manual diagnosis
        // instead.
        Thread.sleep(DAC_SETTLE_DELAY_MS)
        // No Clock Source entity exists to query on UAC 1.0.
        if (found.uacVersion != 1) {
            logEvent("Clock valid: ${UsbAudioController.getClockValid(session)}")
        }

        sessionState = SessionState.Active(session, format, rateHz ?: SUPPORTED_SAMPLE_RATE_HZ)
        refreshControlState(session, format, device.productName ?: device.deviceName)
        logEvent("DAC session ready: $format at ${activeRateHz} Hz")

        // UsbAudioController.activate() (and selectFormat above) claimed the interface to do
        // this one-time discovery/setup, but nothing's actually playing yet -- holding onto that
        // claim here would mean Android's own USB-audio HAL (and every other app) stays locked
        // out of a device the host app has merely *plugged in*, not even pressed play on. Hand it
        // straight back; requestClaim() re-evicts and re-claims it the moment real playback
        // actually starts.
        //
        // awaitAndroidTakeover=true here, not just the plain release RoutingAudioSink's other
        // callers use: this runs on activateDac's own background thread (see startActivation),
        // right after a fresh permission grant -- exactly when some *other* app is most likely to
        // try to open this device next, well before anything of ours has started streaming to
        // race it instead. Without this, a track opened by another app inside the ~100-300ms gap
        // before Android's own UsbAlsaManager catches up to the just-released device silently
        // succeeds routed to the speaker instead, and Android never revisits that choice for it
        // -- confirmed, reproduced: opening another app's player right after granting permission,
        // before ever pressing play in this app at all.
        claimController.markAlreadyClaimed()
        releaseClaim(delayMs = 0, awaitAndroidTakeover = true)
    }

    /**
     * Tries [probe] against channels 0..[channelCount] in order and returns the first non-null
     * result.
     *
     * The write side ([scheduleVolumeWrite], [activateDac]) always broadcasts the same target to
     * every channel, so on a device with real per-channel Feature Unit controls, whichever
     * channel actually answers is reporting the same value by construction -- there's no need to
     * know in advance which channel that is. This replaced a hardcoded channel-0 (master) read
     * that was previously misdiagnosed as a device-side firmware limitation: confirmed against
     * the FiiO Snowsky Tiny A's own descriptors (`bmaControls(0)=0x00000000`, i.e. the device
     * declares *no* controls at all on the master channel -- only `bmaControls(1)` and `(2)` (the
     * individual L/R channels) have the Volume Control bit set). A GET_CUR for a channel the
     * device never declared support for is spec'd to STALL (UAC2 §5.2.5.4.2), which is exactly
     * the `result=-1` that looked like a broken device. Probing every channel instead of
     * hardcoding one means this works whether a device's control lives on master, on individual
     * channels only, or (as here) anywhere but master.
     */
    private fun <T> probeChannels(channelCount: Int, probe: (channel: Int) -> T?): T? {
        for (channel in 0..channelCount) {
            probe(channel)?.let { return it }
        }
        return null
    }

    /** Re-reads every adjustable control from the device and republishes [controlState]. */
    private fun refreshControlState(session: UsbAudioSession, format: UacStreamFormat, deviceName: String) {
        val mixerUnitId = session.topology.mixerUnitId
        val mixerBase = session.topology.mixerInputChannelBase
        // Was hardcoded to a stereo-in/stereo-out grid -- see activateDac's identical fix for why
        // this scales with the device's own real channel count instead. Every session here is
        // always one activateDac already normalized (see its channel-count fallback), so no
        // coerce is needed on this read.
        val mixerChannelCount = session.topology.channelCount
        val mixerChannels =
            if (mixerUnitId >= 0 && mixerBase >= 0) {
                (mixerBase until mixerBase + mixerChannelCount).flatMap { inCh ->
                    (1..mixerChannelCount).map { outCh -> inCh to outCh }
                }
            } else {
                emptyList()
            }
        val mixerGains =
            mixerChannels.associateWith { (inCh, outCh) ->
                UsbAudioController.getMixerGain(session, mixerUnitId, inCh, outCh)
            }
        val mixerGainRange =
            mixerChannels.firstOrNull()?.let { (inCh, outCh) ->
                UsbAudioController.getMixerGainRange(session, mixerUnitId, inCh, outCh)
            }
        // See [probeChannels]'s doc comment: a single hardcoded channel (especially master/0)
        // can be one a given device never declared a Volume control on at all, which used to be
        // misread as a dead device rather than the wrong address. Falling back to the last known
        // value if every channel comes back null (transient USB error, or a device with no
        // volume control whatsoever) still matters for the same reason as before: every caller of
        // [controlState] (this screen's volume slider, [MediaSessionHolder]'s physical-volume-key
        // routing) treats "unknown" as "assume max", so losing the last known value entirely
        // would read as "reset to max" instead of "nothing changed".
        val volume =
            probeChannels(session.topology.channelCount) { channel ->
                UsbAudioController.getVolume(session, channel)
            } ?: _controlState.value?.volume
        _controlState.value =
            UsbControlState(
                deviceName = deviceName,
                formats = session.topology.formats,
                activeFormat = format,
                sampleRateHz = activeRateHz,
                muted = UsbAudioController.getMute(session),
                volume = volume,
                volumeRange =
                    probeChannels(session.topology.channelCount) { channel ->
                        UsbAudioController.getVolumeRange(session, channel)
                    },
                mixerChannels = mixerChannels,
                mixerGains = mixerGains,
                mixerGainRange = mixerGainRange,
                clockSupportedRates = session.clockSupportedRates)
    }

    /** Live-adjusts the active session's mute state, if any; reflected in [controlState]. */
    fun setMuted(muted: Boolean) {
        val session = activeSession ?: return
        val format = activeFormat ?: return
        // A Settings-screen tweak needs the interface claimed the same as real streaming does --
        // see requestClaim's doc comment -- and releaseClaim() hands it straight back to Android
        // after the usual grace period rather than leaving it claimed indefinitely just because
        // the user poked a control once.
        if (requestClaim() == ClaimOutcome.FAILED) return
        UsbAudioController.setMute(session, muted)
        logEvent("User set mute=$muted")
        refreshControlState(session, format, session.device.productName ?: session.device.deviceName)
        releaseClaim()
    }

    // Dedicated to serializing [setVolume]'s own control transfers off whichever thread calls it
    // (the physical volume keys dispatch through MediaSessionCompat's callback handler, i.e. the
    // main thread) -- see [setVolume]'s doc comment for why this exists.
    private val volumeExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "UsbAudioControl-Volume").apply { isDaemon = true } }
    @Volatile private var pendingVolumeDb: Int? = null
    @Volatile private var volumeWriteInFlight = false

    /**
     * Live-adjusts the active session's master/channel volume, if any; reflected in
     * [controlState]. Runs the actual control transfers (and the full post-write
     * [refreshControlState] re-read) on a background thread and coalesces rapid successive calls
     * down to just the latest target, rather than running each synchronously on the calling
     * thread in turn.
     *
     * This specifically fixes a real, reported lag: holding the physical volume key fires a
     * burst of calls here (one per key-repeat event) in quick succession on the main thread. Each
     * one used to be a fully synchronous round trip -- 3 `SET_CUR` transfers plus
     * [refreshControlState]'s own handful of `GET_CUR`/`GET_RANGE` reads -- so a burst of them
     * queued up and had to drain serially, and the DAC's volume kept visibly climbing/falling for
     * a noticeable while after the user had already stopped pressing. Since only the *final*
     * target in a burst actually matters, [pendingVolumeDb] tracks just that, and
     * [scheduleVolumeWrite] only starts a new round trip once the previous one has actually
     * finished, skipping straight to whatever the latest requested value is instead of replaying
     * every intermediate step.
     */
    fun setVolume(valueDb: Int) {
        pendingVolumeDb = valueDb
        // Reflect the requested value immediately so callers (the volume key's system UI, the
        // Settings screen slider) don't have to wait on the hardware round-trip just to show
        // where the user asked the slider to go; refreshControlState below corrects this to the
        // real read-back value once the write actually lands.
        _controlState.value = _controlState.value?.copy(volume = valueDb)
        // Persisted so the next activation (new session, app restart, DAC reconnect) starts from
        // here instead of a fixed default -- see [UsbAudioOutput]'s constructor.
        usbSettings.volumeDb = valueDb
        volumeController.mirrorDbToStreamMusic(valueDb)
        scheduleVolumeWrite()
    }

    private fun scheduleVolumeWrite() {
        if (volumeWriteInFlight) return
        val target = pendingVolumeDb ?: return
        val session = activeSession ?: return
        val format = activeFormat ?: return
        volumeWriteInFlight = true
        volumeExecutor.execute {
            // See setMuted's identical reasoning: a volume write needs the interface claimed the
            // same as real streaming does.
            if (requestClaim() == ClaimOutcome.FAILED) {
                volumeWriteInFlight = false
                return@execute
            }
            // Was hardcoded 0..2 (master + stereo) -- see activateDac's identical fix. No coerce
            // needed: activateDac already normalizes channelCount before this session exists.
            for (channel in 0..session.topology.channelCount) {
                UsbAudioController.setVolume(session, target, channel)
            }
            logEvent("User set volume=$target")
            refreshControlState(session, format, session.device.productName ?: session.device.deviceName)
            releaseClaim()
            volumeWriteInFlight = false
            if (pendingVolumeDb != target) {
                // Something more recent arrived while this write was in flight -- go straight to
                // it instead of waiting for the next external setVolume call to notice.
                scheduleVolumeWrite()
            }
        }
    }

    /** Live-adjusts one Mixer Unit (input, output) channel pair's gain, if the device has one. */
    fun setMixerGain(inputChannel: Int, outputChannel: Int, valueDb: Int) {
        val session = activeSession ?: return
        val format = activeFormat ?: return
        val mixerUnitId = session.topology.mixerUnitId
        if (mixerUnitId < 0) return
        if (requestClaim() == ClaimOutcome.FAILED) return
        UsbAudioController.setMixerGain(session, mixerUnitId, inputChannel, outputChannel, valueDb)
        logEvent("User set mixer gain in=$inputChannel out=$outputChannel to $valueDb")
        refreshControlState(session, format, session.device.productName ?: session.device.deviceName)
        releaseClaim()
    }

    /**
     * Changes which bit resolution to prefer and immediately re-activates with it, if a format
     * at that resolution exists on the current device (falls back to whatever's first otherwise
     * -- see where [preferredBitResolution] is consulted in [activateDac]).
     */
    fun setPreferredBitResolution(bitResolution: Int) {
        preferredBitResolution = bitResolution
        logEvent("Preferred format set to ${bitResolution}-bit, re-activating")
        retryActivation()
    }

    /**
     * Also clears [capabilities], not just [controlState] -- a caller disabling USB DAC output
     * via [onUsbEnabledChanged] used to call this alone and leave [capabilities] (and therefore
     * the Capabilities settings screen, and the one-line summary on the main USB screen) showing
     * the *previous* session's device info indefinitely, since nothing else in the app clears it
     * once set. Confirmed: the device-detach path already had to work around exactly this by
     * clearing [capabilities] itself right after calling this function -- that workaround is now
     * redundant and removed, since every caller gets the correct behavior from here instead.
     */
    private fun deactivateDac() {
        claimController.cancelPendingRelease()
        val session = activeSession
        if (session != null && claimController.isClaimed()) {
            // Mirrors UsbClaimController's own UsbVolumeController.prepareForHalHandoff call
            // (see its doc comment on why this has to happen before the interface is released,
            // not after) -- without it, disabling USB DAC output while still claimed (e.g.
            // mid-track, not between tracks where that path already did this) leaves the
            // device's hardware Feature Unit at whatever gain/mute bit-perfect mode last set it
            // to. Android's own STREAM_MUSIC volume has no visibility into or control over that
            // -- confirmed, reproduced: every fallback track afterward showed the correct route,
            // correct Android-side volume, and played silently, until the device was unplugged
            // or USB DAC output was re-enabled.
            volumeController.prepareForHalHandoff(session, session.topology.channelCount)
        }
        claimController.markReleased()
        // Captured before clearing activeSession below -- see UsbClaimController's
        // awaitAndroidOutputTakeover()'s fallback use of this, needed when this function is the
        // one that triggered the switch to fallback rather than a per-track liveness check.
        claimController.recordDeactivatedDevice(session?.device)
        session?.let { UsbAudioController.release(it) }
        sessionState = SessionState.Idle
        _controlState.value = null
        _capabilities.value = null
    }

    /**
     * Re-runs DAC activation for whatever USB audio device is currently attached. Requests
     * permission if needed, or re-activates directly if already permitted -- the manual escape
     * hatch for forcing an activation attempt right now: [ensureActivated]'s own lazy trigger
     * only ever tries a given attachment once (see [activationAttemptedFor]), so a device that
     * failed to activate, or one the user wants to inspect (Capabilities screen) without first
     * pressing play anywhere, has no other way to retry short of unplugging the device or
     * restarting the app. Not debug-only despite the name this used to have -- called from real
     * user-facing paths ([setPreferredBitResolution], [onUsbEnabledChanged]), not just testing.
     */
    fun retryActivation() {
        if (!usbSettings.enabled) {
            L.i("retryActivation: USB DAC output is disabled in settings")
            return
        }
        val device = deviceDiscovery.devices.value.firstOrNull()
        if (device == null) {
            L.w("retryActivation: no USB audio device attached")
            return
        }
        if (!device.hasPermission) {
            L.i("retryActivation: requesting permission for ${device.displayName}")
            requestPermission(device.device)
            return
        }
        L.i("retryActivation: re-activating DAC session for ${device.displayName}")
        deactivateDac()
        runActivation(device.device)
    }

    /**
     * [UsbDacPreferences.Listener] callback: direct USB output is an opt-in, experimental feature (it
     * claims the USB interface away from Android's own audio framework), off by default. Turning
     * it on tries to activate whatever's already attached; turning it off releases any active
     * session immediately.
     */
    override fun onUsbEnabledChanged() {
        updateBlockPlayback()
        if (usbSettings.enabled) {
            L.i("USB DAC output enabled by user, attempting activation")
            retryActivation()
        } else {
            L.i("USB DAC output disabled by user, releasing any active session")
            deactivateDac()
        }
    }

    /** [UsbDacPreferences.Listener] callback: see [blockPlayback]'s doc comment. */
    override fun onBitPerfectModeChanged() {
        updateBlockPlayback()
    }

    private companion object {
        const val SUPPORTED_SAMPLE_RATE_HZ = 48000
        const val LOG_CAPACITY = 50
        // AudioManager.VOLUME_CHANGED_ACTION/EXTRA_VOLUME_STREAM_TYPE: long-standing, widely used
        // broadcast and extra, but not actually part of the public SDK stub (hence not
        // referenceable as AudioManager.* here) -- a protected system broadcast, so registering
        // for it with RECEIVER_NOT_EXPORTED (see start()) still receives it normally.
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        // How long to keep the raw interface claim after releaseClaim() is called before
        // actually handing it back to the kernel -- long enough that a quick pause/resume (or a
        // brief Settings screen tweak) never pays the real hand-off cost, short enough that
        // genuinely leaving playback paused or idle frees the device for every other app promptly.
        const val RELEASE_GRACE_MS = 3000L
    }
}
