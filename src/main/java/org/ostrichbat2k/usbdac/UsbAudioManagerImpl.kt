/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioManagerImpl.kt is part of libusbdac.
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

import org.ostrichbat2k.usbdac.UsbAudioManager.AbxStats
import org.ostrichbat2k.usbdac.UsbAudioManager.ClaimOutcome
import org.ostrichbat2k.usbdac.UsbAudioManager.PlaybackRoute
import org.ostrichbat2k.usbdac.abx.UsbAbxTester
import org.ostrichbat2k.usbdac.abx.UsbAbxTesterImpl
import org.ostrichbat2k.usbdac.activation.UsbDacActivator
import org.ostrichbat2k.usbdac.activation.UsbDacActivatorImpl
import org.ostrichbat2k.usbdac.activation.UsbDacDescriptorCache
import org.ostrichbat2k.usbdac.activation.UsbDacDescriptorCacheImpl
import org.ostrichbat2k.usbdac.claim.UsbClaimController
import org.ostrichbat2k.usbdac.claim.UsbClaimControllerImpl
import org.ostrichbat2k.usbdac.control.UsbControlState
import org.ostrichbat2k.usbdac.control.UsbControlWriter
import org.ostrichbat2k.usbdac.control.UsbControlWriterImpl
import org.ostrichbat2k.usbdac.control.UsbVolumeController
import org.ostrichbat2k.usbdac.control.UsbVolumeControllerImpl
import org.ostrichbat2k.usbdac.diagnostics.UsbDiagnosticLog
import org.ostrichbat2k.usbdac.diagnostics.UsbDiagnosticLogImpl
import org.ostrichbat2k.usbdac.discovery.UsbAudioDevice
import org.ostrichbat2k.usbdac.discovery.UsbDeviceDiscovery
import org.ostrichbat2k.usbdac.discovery.UsbDeviceDiscoveryImpl
import org.ostrichbat2k.usbdac.protocol.UacPlaybackTopology
import org.ostrichbat2k.usbdac.protocol.UacStreamFormat
import org.ostrichbat2k.usbdac.protocol.UsbAudioController
import org.ostrichbat2k.usbdac.protocol.UsbAudioSession
import org.ostrichbat2k.usbdac.protocol.UsbDeviceCapabilities
import org.ostrichbat2k.usbdac.streaming.IsoPacketScheduler
import org.ostrichbat2k.usbdac.streaming.RoutingAudioSink
import org.ostrichbat2k.usbdac.streaming.UsbAudioOutput
import org.ostrichbat2k.usbdac.streaming.UsbAudioOutputProvider
import org.ostrichbat2k.usbdac.streaming.UsbPipelineStats
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber as L

/**
 * Detects attached USB Audio Class devices (candidate DACs), manages permission requests, and
 * once permitted, activates a persistent [UsbAudioSession] configured and ready for playback.
 * [resolveActiveSink] hands out an [AudioSink] wired to that session for the player to use in place
 * of the normal AudioTrack-backed sink.
 *
 * Direct USB output reconfigures the DAC's clock to match each track's actual rate (see
 * [UsbAudioOutput], and [IsoPacketScheduler] for how 44100 Hz, which isn't a whole number of
 * samples per USB's 1ms isochronous packet, is handled without a fixed packet size).
 *
 * Orchestration only -- every self-contained concern this class needs lives in its own
 * component, constructed below and just delegated to: [UsbDacActivator] (descriptor discovery +
 * session activation), [UsbControlWriter] (live mute/volume/mixer control writes),
 * [UsbAbxTester] (the blind ABX self-test), [UsbDiagnosticLog] (the activation event log), plus
 * the pre-existing [UsbClaimController]/[UsbVolumeController]/[UsbDeviceDiscovery]/
 * [UsbDacDescriptorCache]. What's left here is: the public API surface itself, the
 * [SessionState] machine every one of those components' results eventually feeds into, and the
 * handful of fields ([controlState], [capabilities]) that more than one component needs to both
 * read and write, so none of them can solely own it.
 */
class UsbAudioManagerImpl(private val context: Context, private val usbSettings: UsbDacPreferences) :
    UsbAudioManager {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val descriptorCache: UsbDacDescriptorCache = UsbDacDescriptorCacheImpl(context)
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
    override fun halOutputSampleRate(): Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0

    private val diagnosticLogger: UsbDiagnosticLog = UsbDiagnosticLogImpl()
    /** Most-recent-first log of activation events, for the USB settings screen. */
    override val diagnosticLog: StateFlow<List<String>> = diagnosticLogger.entries

    private val _pipelineStats = MutableStateFlow<UsbPipelineStats?>(null)
    /**
     * Live ring-buffer/USB-transfer stats for the "Pipeline" diagnostic screen -- see
     * [UsbPipelineStats] and [reportPipelineStats]. Null whenever nothing is actually streaming
     * right now, not just a transient pause-silence gap, so that screen can show "idle" instead
     * of numbers frozen from a track that already stopped.
     */
    override val pipelineStats: StateFlow<UsbPipelineStats?> = _pipelineStats.asStateFlow()

    // Shared between UsbControlWriter (which republishes it on every refresh/write) and this
    // class itself (isBitPerfectCapable's read, deactivateDac's clear) -- neither side can
    // solely own it, so it stays here and is handed to UsbControlWriter as a pair of callbacks
    // rather than a StateFlow it owns itself.
    private val _controlState = MutableStateFlow<UsbControlState?>(null)
    /** Live, read-back-from-the-device state of the active session's adjustable controls. */
    override val controlState: StateFlow<UsbControlState?> = _controlState.asStateFlow()

    // Same reasoning as controlState: UsbDacActivator publishes into it (twice, mid-activation),
    // deactivateDac clears it, and it's genuinely this class's own concern to expose -- so it
    // stays here too, handed to UsbDacActivator as a callback.
    private val _capabilities = MutableStateFlow<UsbDeviceCapabilities?>(null)
    /** Everything found on the attached device's descriptors -- see [UsbDeviceCapabilities]. */
    override val capabilities: StateFlow<UsbDeviceCapabilities?> = _capabilities.asStateFlow()

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
    override val playbackRoute: StateFlow<PlaybackRoute> = _playbackRoute.asStateFlow()

    // All hardware volume/mute decision logic lives here, not scattered across this class and
    // UsbAudioController -- see its own class doc comment for why that scatter was a real risk,
    // not just untidiness.
    private val volumeController: UsbVolumeController =
        UsbVolumeControllerImpl(audioManager, usbSettings, controlState, playbackRoute, diagnosticLogger::log)

    // Everything about who holds the raw USB interface claim right now and the hand-off to/from
    // Android's HAL lives here -- see its own class doc comment for why. getActiveSession reads
    // live, at the moment each callback actually runs (which can be after a real delay), not a
    // snapshot frozen when a call into this controller was first made -- deliberately preserving
    // the pre-extraction code's own behavior of reading its session field fresh every time.
    private val claimController: UsbClaimController =
        UsbClaimControllerImpl(
            audioManager,
            volumeController,
            getActiveSession = { activeSession },
            onReclaimed = { clearActiveFormatOnReclaim() },
            onClaimFailed = {
                diagnosticLogger.log("Failed to reclaim USB interface from kernel -- deactivating session")
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
    // internal var, not private val, so a test can substitute a fake for the activation/discovery
    // seam -- see UsbAudioManagerImplTest's own doc comment for why those two specifically.
    internal var deviceDiscovery: UsbDeviceDiscovery =
        UsbDeviceDiscoveryImpl(
            context,
            usbManager,
            usbSettings,
            actionUsbPermission,
            onDeviceDetached = { deactivateDac() },
            onRefreshed = { found ->
                activationAttemptedFor = activationAttemptedFor.takeIf { it in found.map { d -> d.device.deviceName } }
            },
        )
    override val devices: StateFlow<List<UsbAudioDevice>> = deviceDiscovery.devices

    /** See [playbackRoute]. Called by [RoutingAudioSink], not meant for any other caller. */
    override fun reportPlaybackRoute(route: PlaybackRoute) {
        _playbackRoute.value = route
    }

    // Descriptor discovery + session activation -- see its own class doc comment for why this
    // is self-contained enough to not need to know about SessionState at all.
    internal var dacActivator: UsbDacActivator =
        UsbDacActivatorImpl(
            context,
            usbManager,
            descriptorCache,
            getPreferredBitResolution = { preferredBitResolution },
            onCapabilities = { _capabilities.value = it },
            log = diagnosticLogger::log,
        )

    // Every live mute/volume/mixer control write, and the refreshControlState re-read behind
    // each one -- see its own class doc comment. The fixed releaseClaim args here match every
    // one of its three Settings-screen write call sites' old behavior exactly (grace-period
    // release, no HAL-takeover wait, no deactivate-on-timeout escalation).
    private val controlWriter: UsbControlWriter =
        UsbControlWriterImpl(
            usbSettings,
            volumeController,
            getActiveSession = { activeSession },
            getActiveFormat = { activeFormat },
            getActiveRateHz = { activeRateHz },
            requestClaim = { requestClaim() },
            releaseClaim = {
                releaseClaim(delayMs = UsbAudioTuning.RELEASE_GRACE_MS, awaitAndroidTakeover = false, deactivateOnTimeout = false)
            },
            getControlState = { _controlState.value },
            publishControlState = { _controlState.value = it },
            log = diagnosticLogger::log,
        )
    override fun setMuted(muted: Boolean) = controlWriter.setMuted(muted)
    override fun setVolume(valueDb: Int) = controlWriter.setVolume(valueDb)
    override fun setMixerGain(inputChannel: Int, outputChannel: Int, valueDb: Int) =
        controlWriter.setMixerGain(inputChannel, outputChannel, valueDb)

    // The blind ABX self-test -- see its own class doc comment.
    private val abxTester: UsbAbxTester =
        UsbAbxTesterImpl(
            usbSettings,
            isBitPerfectCapable = { isBitPerfectCapable() },
            volumeController,
            setVolume = { setVolume(it) },
            clearCachedSink = { (sessionState as? SessionState.Active)?.let { sessionState = it.copy(sink = null) } },
        )
    /**
     * Whether the *current* track was secretly routed bit-perfect (`true`) or HAL fallback
     * (`false`) during an ABX trial, or `null` if no trial applies to this track. Only consulted
     * by [resolveActiveSink] -- the host app should use [abxPendingGuess] instead, which is
     * what's actually safe to act on without leaking the current track's route.
     */
    override val abxGroundTruth: StateFlow<Boolean?> = abxTester.groundTruth
    /**
     * The ground truth of the track that *just finished*, still ungraded -- `null` once
     * [submitAbxGuess] consumes it, or if that track had no trial running to begin with. The
     * host app should prompt for a guess the moment this goes non-null (i.e. on every track
     * change), not at some other point mid-track -- the whole point is asking about a track
     * that's already done playing, never the one that's still live.
     */
    override val abxPendingGuess: StateFlow<Boolean?> = abxTester.pendingGuess
    /** Cumulative score across every [submitAbxGuess] call this process lifetime. */
    override val abxStats: StateFlow<AbxStats> = abxTester.stats
    override fun rollAbxTrial() = abxTester.rollTrial()
    override fun submitAbxGuess(guessedBitPerfect: Boolean): Boolean? = abxTester.submitGuess(guessedBitPerfect)

    /** Called by [UsbAudioOutput] once it's actually claimed and streaming -- see [releaseClaim]. */
    override fun notifyStreamingStarted() = claimController.notifyStreamingStarted()

    /** The inverse of [notifyStreamingStarted] -- call before, not after, releasing anything. */
    override fun notifyStreamingStopped() = claimController.notifyStreamingStopped()

    /**
     * Called periodically (not per-URB -- see the call site) by the actively-streaming
     * [UsbAudioOutput]'s own streaming thread, and with `null` the moment it genuinely stops --
     * see [pipelineStats].
     */
    override fun reportPipelineStats(stats: UsbPipelineStats?) {
        _pipelineStats.value = stats
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
    override fun requestClaim(): ClaimOutcome = claimController.requestClaim(activeSession)

    override fun consumeHardwareReset(): Boolean = claimController.consumeHardwareReset()

    /**
     * Schedules handing the interface back to the kernel's own driver after [delayMs] (see
     * [UsbAudioTuning.RELEASE_GRACE_MS]), canceling any release already pending -- so a
     * [requestClaim] call before the delay elapses is a pure no-op cancellation, not a real
     * reclaim. Pass 0 to release immediately (used when something -- a pause, a track that can't
     * use the DAC, the player itself -- is definitely, not just momentarily, done with it).
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
    // No default values here -- Kotlin forbids an override from redeclaring the defaults its
    // interface method already has (see UsbAudioManager.releaseClaim); internal call sites below
    // that used to lean on them now spell out all three arguments explicitly.
    override fun releaseClaim(delayMs: Long, awaitAndroidTakeover: Boolean, deactivateOnTimeout: Boolean) {
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
     * ([ensureFormatActive], [clearActiveFormatOnReclaim], [resolveActiveSink], [UsbAbxTester])
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
        get() = (sessionState as? SessionState.Active)?.rateHz ?: UsbAudioTuning.SUPPORTED_SAMPLE_RATE_HZ

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
    override val blockPlayback: StateFlow<Boolean> = _blockPlayback.asStateFlow()

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

    // Only UsbAudioTuning.ACTION_VOLUME_CHANGED -- device attach/detach/permission broadcasts are
    // UsbDeviceDiscovery's own receiver now (see its class doc comment for why this one stayed
    // separate rather than folding into it).
    private val volumeChangeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == UsbAudioTuning.ACTION_VOLUME_CHANGED &&
                    intent.getIntExtra(UsbAudioTuning.EXTRA_VOLUME_STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) {
                    volumeController.onStreamMusicChanged(
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                }
            }
        }

    /** Begins listening for USB device attach/detach/permission events. Call once at startup. */
    override fun start() {
        if (started) return
        started = true
        usbSettings.registerListener(this)
        deviceDiscovery.start()

        val filter = IntentFilter(UsbAudioTuning.ACTION_VOLUME_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(volumeChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(volumeChangeReceiver, filter)
        }
    }

    /** Requests permission to use [device]. The result arrives asynchronously via broadcast. */
    override fun requestPermission(device: UsbDevice) = deviceDiscovery.requestPermission(device)

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
    override suspend fun awaitActivation(timeoutMs: Long) {
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
    override fun triggerActivation() = ensureActivated()

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
    override fun resolveActiveSink(trigger: Boolean): AudioSink? {
        activeSink?.let { return it }
        if (trigger) {
            ensureActivated()
            runBlocking { awaitActivation(8000L) }
        }
        // While an ABX test is running, rollAbxTrial() -- called by RoutingAudioSink right
        // before this -- already decided this track's route; substitute that for the user's own
        // bitPerfectMode toggle so the test actually controls what plays, not just what's shown.
        val bitPerfectWanted = if (usbSettings.abxTestMode) abxTester.groundTruth.value == true else usbSettings.bitPerfectMode
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
    override fun ensureFormatActive(format: UacStreamFormat, sampleRateHz: Int): Boolean {
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
                diagnosticLogger.log("Switched to ${format.bitResolution}-bit (alt=${format.alternateSetting}) for upcoming track")
            } else {
                diagnosticLogger.log("Failed to switch to ${format.bitResolution}-bit (alt=${format.alternateSetting}) for upcoming track")
            }
        }
        if (configChanged) {
            (sessionState as? SessionState.Active)?.let { sessionState = it.copy(rateHz = sampleRateHz) }
            controlWriter.refreshControlState(session, activeFormat ?: format, session.device.productName ?: session.device.deviceName)
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
    override fun isBitPerfectCapable(): Boolean = controlState.value?.volumeRange != null

    /**
     * Reads the active session's Power Domain state right now (one of
     * [UsbAudioController.PD_STATE_D0]/`D1`/`D2`), or null if there's no active session or this
     * device's topology has no Power Domain at all (UAC 1.0/2.0, or a UAC 3.0 device that didn't
     * declare one). A blocking USB control transfer, same as the other on-demand reads this class
     * does in response to a UI query (e.g. [setMuted]'s read-back) -- fine for an occasional
     * settings-screen refresh, not meant to be polled.
     */
    override fun getPowerDomainState(): Int? {
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
    override fun getClockInfo(): Pair<Int?, Boolean?>? {
        val session = activeSession ?: return null
        if (session.topology.clockSourceId < 0) return null
        return UsbAudioController.getClockFrequency(session) to UsbAudioController.getClockValid(session)
    }

    /**
     * Resolves [dacActivator]'s result into [sessionState], republishes [controlState] for the
     * now-active session, and hands the interface straight back to Android's HAL (see
     * [UsbClaimController.markAlreadyClaimed]'s own doc comment for why) -- the only piece of
     * activation that still belongs here rather than in [UsbDacActivator], since it's what
     * actually adopts a freshly-activated session into this class's own state machine.
     */
    private fun activateDac(device: UsbDevice) {
        val result = dacActivator.activate(device) ?: return
        sessionState = SessionState.Active(result.session, result.format, result.rateHz)
        controlWriter.refreshControlState(result.session, result.format, device.productName ?: device.deviceName)
        diagnosticLogger.log("DAC session ready: ${result.format} at ${result.rateHz} Hz")

        // UsbAudioController.activate() (and selectFormat, inside dacActivator) claimed the
        // interface to do this one-time discovery/setup, but nothing's actually playing yet --
        // holding onto that claim here would mean Android's own USB-audio HAL (and every other
        // app) stays locked out of a device the host app has merely *plugged in*, not even
        // pressed play on. Hand it straight back; requestClaim() re-evicts and re-claims it the
        // moment real playback actually starts.
        //
        // awaitAndroidTakeover=true here, not just the plain release RoutingAudioSink's other
        // callers use: this runs on activateDac's own background thread (see runActivation),
        // right after a fresh permission grant -- exactly when some *other* app is most likely to
        // try to open this device next, well before anything of ours has started streaming to
        // race it instead. Without this, a track opened by another app inside the ~100-300ms gap
        // before Android's own UsbAlsaManager catches up to the just-released device silently
        // succeeds routed to the speaker instead, and Android never revisits that choice for it
        // -- confirmed, reproduced: opening another app's player right after granting permission,
        // before ever pressing play in this app at all.
        claimController.markAlreadyClaimed()
        releaseClaim(delayMs = 0, awaitAndroidTakeover = true, deactivateOnTimeout = false)
    }

    /**
     * Changes which bit resolution to prefer and immediately re-activates with it, if a format
     * at that resolution exists on the current device (falls back to whatever's first otherwise
     * -- see where [preferredBitResolution] is consulted in [UsbDacActivator]).
     */
    override fun setPreferredBitResolution(bitResolution: Int) {
        preferredBitResolution = bitResolution
        diagnosticLogger.log("Preferred format set to ${bitResolution}-bit, re-activating")
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
    override fun retryActivation() {
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
}
