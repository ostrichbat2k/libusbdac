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

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.widget.Toast
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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
 * [createAudioSink] hands out an [AudioSink] wired to that session for the player to use in place
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

    private val _devices = MutableStateFlow<List<UsbAudioDevice>>(emptyList())
    val devices: StateFlow<List<UsbAudioDevice>> = _devices.asStateFlow()

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

    /** See [playbackRoute]. Called by [RoutingAudioSink], not meant for any other caller. */
    fun reportPlaybackRoute(route: PlaybackRoute) {
        _playbackRoute.value = route
    }

    sealed interface PlaybackRoute {
        /**
         * Playing through Android's normal audio HAL (speaker, or whatever it last routed to --
         * see [RoutingAudioSink] for when this device's USB DAC itself is the actual HAL output).
         *
         * Deliberately *not* the track's own decoded format the way [Direct]'s fields are --
         * HAL's shared mixer resamples every non-direct track to its own fixed output rate
         * regardless of what the file actually is (confirmed on this device via `dumpsys audio`:
         * its "Active communication device" profile declares a single fixed `sampling
         * rates=[48000]`, the same for every device, speaker included). [sampleRateHz] here is
         * [halOutputSampleRate] -- the real value HAL negotiates -- not [Direct]'s per-track
         * value, so showing this alongside a 96kHz file isn't a contradiction, it's just true:
         * HAL really did resample it.
         */
        data class Fallback(val bitDepth: Int?, val sampleRateHz: Int, val channels: Int) : PlaybackRoute

        /** Streaming bit-perfect PCM directly to the USB DAC at these real, per-track values. */
        data class Direct(val bitDepth: Int?, val sampleRateHz: Int, val channels: Int) : PlaybackRoute

        /**
         * A real hand-off between [Fallback] and [Direct] is in progress -- [requestClaim] is
         * doing its (blocking, hundreds-of-ms-to-low-seconds) reclaim-and-relock-the-clock work,
         * or [releaseClaim] is waiting out [awaitAndroidOutputTakeover]. Shown so the Now Playing
         * screen doesn't look like nothing is happening during that gap.
         */
        data object Switching : PlaybackRoute
    }

    private val claimScheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "UsbAudioControl-Claim").apply { isDaemon = true }
        }
    // Whether we currently hold the raw interface claim (vs. having handed it to the kernel's own
    // driver for Android's normal USB-audio HAL to use). Distinct from activeSession existing at
    // all -- a session can be fully discovered/activated and sitting completely unclaimed between
    // tracks, see activateDac's tail and [releaseClaim].
    @Volatile private var interfaceClaimed = false
    @Volatile private var pendingRelease: ScheduledFuture<*>? = null
    // How many UsbAudioOutput instances currently consider themselves actively streaming (see
    // notifyStreamingStarted/Stopped) -- i.e. actually depending on the claim right now, not just
    // having asked for it once. [releaseClaim] must never release out from under one of these:
    // confirmed, reproduced, a real multi-second dropout -- a volume key press during active
    // playback ran setVolume()'s background write, which (like every other Settings-style
    // mutator here) unconditionally called releaseClaim() afterward on the assumption nothing
    // else needed the claim. With a track genuinely streaming, that scheduled release fired
    // ~RELEASE_GRACE_MS later and yanked the interface out from under the still-running native
    // thread, which only recovered once DefaultAudioSink's own stall detection eventually noticed
    // and forced a fresh reconfigure/reclaim.
    @Volatile private var activeStreamCount = 0

    /** Called by [UsbAudioOutput] once it's actually claimed and streaming -- see [releaseClaim]. */
    fun notifyStreamingStarted() {
        activeStreamCount++
    }

    /** The inverse of [notifyStreamingStarted] -- call before, not after, releasing anything. */
    fun notifyStreamingStopped() {
        activeStreamCount = (activeStreamCount - 1).coerceAtLeast(0)
    }

    /**
     * Claims the interface (evicting whatever the kernel driver currently has bound) if we don't
     * already hold it -- idempotent and cheap to call when already claimed, so every caller about
     * to do real bit-perfect work (streaming, a Settings-screen control write) calls this first
     * rather than assuming the claim is still held from some earlier point. Blocking; callers on
     * this codebase's playback thread already tolerate similar synchronous USB I/O elsewhere (the
     * clock-relock settle wait in [UsbAudioOutput]'s init logic).
     */
    fun requestClaim(): Boolean {
        pendingRelease?.cancel(false)
        pendingRelease = null
        if (interfaceClaimed) return true
        val session = activeSession ?: return false
        val ok = UsbAudioController.reclaimFromKernel(session)
        if (ok) {
            interfaceClaimed = true
            // reclaimFromKernel only re-claims the control interface and the streaming
            // interface's alt-0 -- the actual bit-depth alt-setting is whatever
            // ensureFormatActive's selectFormat call puts it at, further down the line. But that
            // call only runs selectFormat when it thinks the format "changed" from activeFormat,
            // and activeFormat was never invalidated by giving the interface away -- so without
            // this, a reclaim after this exact format was already active looked like a no-op
            // change and selectFormat never ran again, leaving the interface sitting at alt-0
            // while UsbAudioOutput opened its iso stream assuming the real target alt-setting's
            // endpoint. Confirmed, reproduced: every post-reclaim stream failed submitIso on
            // every single slot, immediately.
            activeFormat = null
        } else {
            logEvent("Failed to reclaim USB interface from kernel -- deactivating session")
            deactivateDac()
        }
        return ok
    }

    /**
     * Schedules handing the interface back to the kernel's own driver after [delayMs] (see
     * [RELEASE_GRACE_MS]), canceling any release already pending -- so a [requestClaim] call
     * before the delay elapses is a pure no-op cancellation, not a real reclaim. Pass 0 to
     * release immediately (used when something -- a pause, a track that can't use the DAC, the
     * player itself -- is definitely, not just momentarily, done with it).
     */
    fun releaseClaim(delayMs: Long = RELEASE_GRACE_MS, awaitAndroidTakeover: Boolean = false) {
        pendingRelease?.cancel(false)
        if (activeStreamCount > 0) {
            // Something is genuinely streaming right now and still needs the claim -- a caller
            // here (a Settings-screen mutator, most likely) just wanted it briefly for its own
            // one-off write and is in no position to know that. Not even worth scheduling: the
            // active UsbAudioOutput's own pause()/release() is what will actually call this once
            // it's really done.
            pendingRelease = null
            return
        }
        pendingRelease =
            if (delayMs <= 0) {
                doReleaseClaim()
                if (awaitAndroidTakeover) awaitAndroidOutputTakeover()
                null
            } else {
                claimScheduler.schedule({ doReleaseClaim() }, delayMs, TimeUnit.MILLISECONDS)
            }
    }

    private fun doReleaseClaim() {
        if (activeStreamCount > 0) return
        val session = activeSession
        if (session != null && interfaceClaimed) {
            // Bracketed with mute the same way UsbAudioOutput's own clock-relock is (see its
            // init-time comment on why): setUnityVolume can be a large, sudden jump (e.g.
            // -30dB -> 0dB) from wherever bit-perfect mode last left it, and doing that while
            // unmuted is audible as a real pop/click -- confirmed, reported. Unmuting again
            // before releaseToKernel, not after, since by the time Android's HAL actually opens
            // the device for a fallback track, it needs to already be unmuted to be audible.
            UsbAudioController.setMute(session, muted = true)
            UsbAudioController.setUnityVolume(session, session.topology.channelCount)
            UsbAudioController.setMute(session, muted = false)
            // Must happen before releaseToKernel, not after -- once released, these control
            // transfers need the interface claimed the same as any other, and by the time
            // Android's HAL actually opens the device it's too late to fix the gain up front.
            UsbAudioController.releaseToKernel(session)
            interfaceClaimed = false
        }
    }

    /**
     * Blocks (briefly, bounded) until Android's own audio framework reports this device as an
     * available output, or [AWAIT_TAKEOVER_TIMEOUT_MS] elapses. The kernel-side decision (will
     * snd-usb-audio actually create a playback PCM substream) is synchronous -- see
     * [UsbAudioController.releaseToKernel]'s doc comment -- but Android's own UsbAlsaManager
     * noticing that substream, creating its `/dev/snd/` node, and updating AudioPolicyManager's
     * device list all happen afterward in a separate process with real, measured latency
     * (confirmed via logcat: ~100-300ms from release to `UsbAlsaManager: alsaFileAdded(pcmC1D0p)`
     * on the one device tested). Opening a *fallback* `AudioTrack` while that's still in flight
     * doesn't fail -- it just succeeds, silently routed to the speaker, and Android never
     * revisits that choice later. Polling [AudioManager]'s real device list (not just hoping a
     * fixed sleep is long enough) is what [RoutingAudioSink] calls this for, right before letting
     * a fallback track's `configure()` run.
     */
    private fun awaitAndroidOutputTakeover() {
        val device = activeSession?.device ?: return
        val deadline = System.currentTimeMillis() + AWAIT_TAKEOVER_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val found =
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    (it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE) &&
                        it.productName?.toString() == (device.productName ?: device.deviceName)
                }
            if (found) return
            Thread.sleep(AWAIT_TAKEOVER_POLL_MS)
        }
        L.w("UsbAudioManager: Android didn't report the USB device as an output within timeout")
    }

    // In-memory only: which bit resolution to prefer when multiple formats are offered. Not
    // persisted across process death since changing it re-activates immediately anyway; a
    // settings-screen choice only has user-visible meaning while that screen (or the session it
    // started) is still around to show the effect.
    @Volatile private var preferredBitResolution: Int = 16

    @Volatile private var activeSession: UsbAudioSession? = null
    @Volatile private var activeFormat: UacStreamFormat? = null
    // Completed (regardless of success/failure) when the most recently started activateDac()
    // call returns. Lets a caller about to start playback (see awaitActivation) give it a bounded
    // chance to finish first, rather than racing it on its own background thread and permanently
    // committing that track to the fallback sink -- RoutingAudioSink.resolveForConfigure only
    // re-checks createAudioSink() on the *next* configure() call (next track), not this one, so
    // losing this race once means that whole track plays on fallback with no recovery short of
    // the user manually changing tracks. Confirmed, reproduced on real hardware: autoplay-resume
    // on a cold app launch calling configure() before a freshly-granted permission's activateDac()
    // thread had finished.
    @Volatile private var activationSettled: CompletableDeferred<Unit>? = null
    // Only reflects SUPPORTED_SAMPLE_RATE_HZ (UAC 2.0/3.0) or whatever activateDac negotiated for
    // UAC 1.0 until the first track actually plays -- after that, ensureFormatActive keeps this
    // in sync with whatever rate each track genuinely streams at, since different tracks can (and
    // do) use different rates within the same DAC session. Purely for display (UsbControlState,
    // the Settings screen) and the Capabilities probe before any track has played; actual
    // playback never consults this, it uses each track's own real rate directly.
    @Volatile private var activeRateHz: Int = SUPPORTED_SAMPLE_RATE_HZ

    private var started = false
    private var knownDeviceNames = emptySet<String>()
    private var knownPermissions = emptyMap<String, Boolean>()

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        L.i("USB device attached: ${intentDevice(intent)?.deviceName}")
                        refresh()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        L.i("USB device detached: ${intentDevice(intent)?.deviceName}")
                        deactivateDac()
                        refresh()
                    }
                    ACTION_VOLUME_CHANGED -> {
                        if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) ==
                            AudioManager.STREAM_MUSIC) {
                            onStreamMusicChanged(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                        }
                    }
                    actionUsbPermission -> {
                        val device = intentDevice(intent)
                        val granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        L.i("USB permission result for ${device?.deviceName}: granted=$granted")
                        if (device != null) {
                            val label = device.productName ?: device.deviceName
                            Toast.makeText(
                                    context,
                                    if (granted) "USB DAC permission granted: $label"
                                    else "USB DAC permission denied: $label",
                                    Toast.LENGTH_LONG,
                                )
                                .show()
                        }
                        refresh()
                    }
                }
            }
        }

    private fun intentDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    /** Begins listening for USB device attach/detach/permission events. Call once at startup. */
    fun start() {
        if (started) return
        started = true
        usbSettings.registerListener(this)

        val filter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(actionUsbPermission)
                addAction(ACTION_VOLUME_CHANGED)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        }

        refresh()
    }

    /** Requests permission to use [device]. The result arrives asynchronously via broadcast. */
    fun requestPermission(device: UsbDevice) {
        val permissionIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(actionUsbPermission).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun refresh() {
        val found =
            usbManager.deviceList.values.filter { it.isAudioDevice() }.map {
                UsbAudioDevice(it, usbManager.hasPermission(it))
            }

        val newNames = found.map { it.displayName }.toSet()
        for (name in newNames - knownDeviceNames) {
            Toast.makeText(context, "USB Audio Device detected: $name", Toast.LENGTH_LONG).show()
        }
        knownDeviceNames = newNames

        // Trigger descriptor inspection off the actual permission state transition rather than
        // the ACTION_USB_PERMISSION broadcast's extras, which were observed to be unreliable
        // (null device, granted=false) on at least one real device/OS build even when the
        // permission was in fact granted a moment later.
        for (audioDevice in found) {
            val wasPermitted = knownPermissions[audioDevice.device.deviceName] ?: false
            if (usbSettings.enabled && audioDevice.hasPermission && !wasPermitted) {
                L.i("Permission newly granted for ${audioDevice.displayName}, activating DAC session")
                // Session activation blocks on several control transfers; keep it off whatever
                // thread refresh() was called on (the broadcast receiver's, normally main).
                val deferred = CompletableDeferred<Unit>()
                activationSettled = deferred
                Thread {
                        try {
                            activateDac(audioDevice.device)
                        } finally {
                            deferred.complete(Unit)
                        }
                    }
                    .apply {
                        isDaemon = true
                        start()
                    }
            }
        }
        knownPermissions = found.associate { it.device.deviceName to it.hasPermission }

        L.d("USB audio devices: ${found.map { it.displayName to it.hasPermission }}")
        _devices.value = found
    }

    private fun UsbDevice.isAudioDevice(): Boolean =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }

    // A host app building more than one audio renderer (e.g. a preferred decoder plus a
    // fallback), each wrapping its own RoutingAudioSink -- both get queried
    // (supportsFormat/getFormatSupport, and apparently position) by ExoPlayer's renderer
    // machinery, not just whichever one actually ends up streaming. createAudioSink() returning
    // a *fresh* UsbAudioSink per call meant each
    // RoutingAudioSink got its own, separate instance: one got genuinely configured and streamed
    // correctly, but the other sat forever unconfigured (sampleRateHz=0), and its
    // getCurrentPositionUs() dutifully reporting AudioSink.CURRENT_POSITION_NOT_SET
    // (Long.MIN_VALUE) seems to be exactly what corrupted the player's overall position
    // tracking -- observed as it jumping to nonsense values like -999999475 before stalling.
    // Caching a single instance per session, shared across both RoutingAudioSinks, means
    // whichever one actually gets used reports real progress to *both* callers.
    @Volatile private var activeSink: AudioSink? = null

    /**
     * Waits, bounded by [timeoutMs], for the most recently started [activateDac] to finish --
     * see [activationSettled]'s doc comment for why a caller about to start playback would want
     * this. A no-op (returns immediately) if nothing's currently activating, so this costs
     * nothing outside the actual race window (right after a permission grant, a device
     * reattachment, or anything else that re-triggers [activateDac]). Deliberately doesn't
     * report success/failure -- the caller should just proceed with playback either way;
     * [createAudioSink] returning null (e.g. activation genuinely failed, or there's no DAC at
     * all) falls back exactly as if this had never been called.
     *
     * [createAudioSink] already calls this internally, so most callers don't need to -- this
     * stays public for a caller that specifically wants to wait *before* doing anything else
     * (e.g. [ExoPlaybackStateHolder][org.oxycblt.auxio.playback.service.ExoPlaybackStateHolder]'s
     * cold-launch restore path awaits once up front rather than letting the first per-track
     * [createAudioSink] call absorb the wait on its own).
     */
    suspend fun awaitActivation(timeoutMs: Long = 4000L) {
        val deferred = activationSettled ?: return
        withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    /**
     * Returns an [AudioSink] wired to the active DAC session, or null if none is ready -- or if
     * [UsbDacPreferences.bitPerfectMode] is off, or the device isn't [isBitPerfectCapable]. A device
     * can be fully activated (Controls/Capabilities usable) without ever being used for actual
     * playback: [RoutingAudioSink] falls back to normal Android audio output whenever this
     * returns null, same mechanism as a genuinely absent/unplugged device, and it already
     * re-checks this on every track (see its own liveness re-validation), so toggling bit-perfect
     * mode takes effect on the very next [RoutingAudioSink.configure] call.
     *
     * Blocks (bounded, via [awaitActivation]) on an in-flight [activateDac] *before* checking
     * capability/session state, not after -- otherwise this would race any caller, not just the
     * one that happens to remember to await first. Confirmed, reproduced on real hardware: a
     * device reattachment's background [activateDac] has no caller-side await covering it at
     * all (unlike the one call site that was patched for the original cold-launch version of
     * this race), so a track change or play press landing in that window lost the race every
     * time, with zero recovery short of a manual track change -- this closes the gap for every
     * trigger at once instead of patching each one individually. Only blocks the one call that
     * actually hits the race window: [activeSink] (the common case) is still a zero-cost return
     * above the await, and the await itself is already a no-op when nothing's activating.
     *
     * Returns a plain [DefaultAudioSink] built with a [UsbAudioOutputProvider] rather than a
     * hand-rolled `AudioSink` -- see [UsbAudioOutput]'s doc comment for why.
     */
    fun createAudioSink(): AudioSink? {
        activeSink?.let { return it }
        runBlocking { awaitActivation() }
        if (!usbSettings.bitPerfectMode || !isBitPerfectCapable()) return null
        val session = activeSession ?: return null
        return DefaultAudioSink.Builder(context)
            .setAudioOutputProvider(UsbAudioOutputProvider(this, session, usbSettings))
            .build()
            .also { activeSink = it }
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
        if (!requestClaim()) return false
        val formatChanged = format != activeFormat
        var ok = true
        if (formatChanged) {
            // Same reasoning as the identical call in activateDac(): Android's own ALSA driver
            // can race to re-claim the interface, and evicting it again right before
            // selectFormat is safe/cheap insurance against that.
            UsbAudioController.reassertKernelEviction(session)
            ok = UsbAudioController.selectFormat(session, format)
            if (ok) {
                activeFormat = format
                logEvent("Switched to ${format.bitResolution}-bit (alt=${format.alternateSetting}) for upcoming track")
            } else {
                logEvent("Failed to switch to ${format.bitResolution}-bit (alt=${format.alternateSetting}) for upcoming track")
            }
        }
        if (formatChanged || sampleRateHz != activeRateHz) {
            activeRateHz = sampleRateHz
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
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            logEvent("Could not open USB connection to ${device.deviceName}")
            return
        }
        var topology: UacPlaybackTopology? = null
        try {
            for (i in 0 until device.configurationCount.coerceAtLeast(1)) {
                val bytes = UsbAudioDescriptorParser.fetchConfigurationDescriptor(connection, i) ?: continue
                // Walk once, reuse the result across all four calls below -- each independently
                // re-walking the same bytes was a real, pointless 4x linear pass per
                // configuration index (see UsbAudioDescriptorParser.walk's doc comment).
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
                topology = found)
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
            val ranges = UsbAudioController.getClockFrequencyRanges(session) ?: emptyList()
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
                        topology = found)
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

        activeSession = session
        activeFormat = format
        activeRateHz = rateHz ?: SUPPORTED_SAMPLE_RATE_HZ
        refreshControlState(session, format, device.productName ?: device.deviceName)
        logEvent("DAC session ready: $format at ${activeRateHz} Hz")

        // UsbAudioController.activate() (and selectFormat above) claimed the interface to do
        // this one-time discovery/setup, but nothing's actually playing yet -- holding onto that
        // claim here would mean Android's own USB-audio HAL (and every other app) stays locked
        // out of a device the host app has merely *plugged in*, not even pressed play on. Hand it
        // straight back; requestClaim() re-evicts and re-claims it the moment real playback
        // actually starts.
        interfaceClaimed = true
        releaseClaim(delayMs = 0)
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
        // Some devices (confirmed: FiiO Snowsky Tiny A) accept every SET_CUR volume write but
        // always fail GET_CUR on readback (result=-1) -- a firmware limitation, not a transient
        // error. Falling back to null here used to mean every single caller of [controlState]
        // (this screen's volume slider, [MediaSessionHolder]'s physical-volume-key routing) saw
        // "volume unknown" after *every* write on such a device, and both of them treat unknown
        // as "assume max" -- so on a device like this, every write was immediately followed by
        // every reader snapping back to full volume. Preserving the last known value (readback or
        // our own just-applied write, whichever is freshest) instead means a GET_CUR failure
        // looks like "nothing changed" rather than "reset to max", which is the correct read on a
        // device that we just told to be at a specific value two lines above.
        val volume = UsbAudioController.getVolume(session, 0) ?: _controlState.value?.volume
        _controlState.value =
            UsbControlState(
                deviceName = deviceName,
                formats = session.topology.formats,
                activeFormat = format,
                sampleRateHz = activeRateHz,
                muted = UsbAudioController.getMute(session),
                volume = volume,
                volumeRange = UsbAudioController.getVolumeRange(session, 0),
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
        if (!requestClaim()) return
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
        mirrorDbToStreamMusic(valueDb)
        scheduleVolumeWrite()
    }

    // The range this volume value belongs to -- same one setVolume's own callers already read
    // from controlState, kept here just so mirrorDbToStreamMusic doesn't need its own copy
    // threaded through every call site.
    private fun currentVolumeRange(): Pair<Int, Int>? = _controlState.value?.volumeRange

    /**
     * [AUDIO_STREAM_MUSIC, DEVICE_CATEGORY_HEADSET] and [..., DEVICE_CATEGORY_EXT_MEDIA] (USB
     * falls under one or the other either way) both reference this exact curve in AOSP's
     * `frameworks/av/services/audiopolicy/config/audio_policy_volumes.xml` ->
     * `default_volume_tables.xml`'s `DEFAULT_MEDIA_VOLUME_CURVE`: (percent-of-index-range,
     * attenuation in dB), linearly interpolated in dB between points -- confirmed against the
     * actual interpolation code, `VolumeCurve::volIndexToDb` in
     * `frameworks/av/services/audiopolicy/engine/common/src/VolumeCurve.cpp`. This is AOSP's
     * documented default, not a guess, but OEMs commonly retune these tables for their own
     * devices, so it's still only an approximation of *this* device's real curve -- a much
     * closer one than a flat linear guess, though.
     */
    private val mediaVolumeCurve = listOf(1f to -58f, 20f to -40f, 60f to -17f, 100f to 0f)

    /** The curve's attenuation (dB) at a given [percent] (0..100) of the index range. */
    private fun curveDbAtPercent(percent: Float): Float {
        val p = percent.coerceIn(0f, 100f)
        for (i in 1 until mediaVolumeCurve.size) {
            val (idxHi, dbHi) = mediaVolumeCurve[i]
            if (p <= idxHi) {
                val (idxLo, dbLo) = mediaVolumeCurve[i - 1]
                val t = if (idxHi == idxLo) 0f else (p - idxLo) / (idxHi - idxLo)
                return dbLo + t * (dbHi - dbLo)
            }
        }
        return mediaVolumeCurve.last().second
    }

    /** The inverse of [curveDbAtPercent]: the percent (0..100) at which the curve reads [db]. */
    private fun curvePercentAtDb(db: Float): Float {
        val d = db.coerceIn(mediaVolumeCurve.first().second, mediaVolumeCurve.last().second)
        for (i in 1 until mediaVolumeCurve.size) {
            val (idxHi, dbHi) = mediaVolumeCurve[i]
            val (idxLo, dbLo) = mediaVolumeCurve[i - 1]
            if (d <= dbHi) {
                val t = if (dbHi == dbLo) 0f else (d - dbLo) / (dbHi - dbLo)
                return idxLo + t * (idxHi - idxLo)
            }
        }
        return mediaVolumeCurve.last().first
    }

    private fun streamMusicIndexRange(): Pair<Int, Int> {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            } else {
                0
            }
        return min to max
    }

    /**
     * Keeps Android's own [AudioManager.STREAM_MUSIC] index matched (via [mediaVolumeCurve],
     * Android's own real curve, not a flat linear guess) to the DAC's hardware dB level, purely
     * so switching between a bit-perfect track (loudness controlled by the Feature Unit) and a
     * fallback track (loudness controlled by STREAM_MUSIC -- see [UsbAudioController
     * .setUnityVolume]'s doc comment on why the hardware itself sits at unity during fallback)
     * doesn't sound like a jump up or down in level just because the two tracks happened to use
     * different control paths. [ignoreNextStreamMusicBroadcast] guards against this call's own
     * [AudioManager.setStreamVolume] immediately feeding back into [onStreamMusicChanged] as if
     * the user had just adjusted it externally.
     */
    private fun mirrorDbToStreamMusic(valueDb: Int) {
        currentVolumeRange() ?: return
        val percent = curvePercentAtDb(valueDb / 256f)
        val (minIndex, maxIndex) = streamMusicIndexRange()
        val index = (minIndex + percent / 100f * (maxIndex - minIndex)).roundToIntSafe(minIndex, maxIndex)
        if (index == audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) return
        ignoreNextStreamMusicBroadcast = true
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }

    private fun Float.roundToIntSafe(min: Int, max: Int): Int =
        kotlin.math.round(this).toInt().coerceIn(min, max)

    // Set right before mirrorDbToStreamMusic's own setStreamVolume call, and cleared by the very
    // next VOLUME_CHANGED_ACTION broadcast [onStreamMusicChanged] sees -- distinguishes "this
    // broadcast is just an echo of our own write" from "the user (or another app) genuinely
    // changed STREAM_MUSIC", which is the only case that should feed back into [usbSettings
    // .volumeDb]. A plain boolean is enough: Android delivers this broadcast synchronously
    // enough relative to the call that there's never more than one in flight at a time in
    // practice, and worst case a missed real external change just waits for the next one.
    @Volatile private var ignoreNextStreamMusicBroadcast = false

    /**
     * The inverse of [mirrorDbToStreamMusic]: called when [AudioManager.VOLUME_CHANGED_ACTION]
     * reports a real (not self-caused) STREAM_MUSIC change while a fallback track -- the only
     * time STREAM_MUSIC is actually what's audible -- is the current route. Persists
     * the dB equivalent to [UsbDacPreferences.volumeDb] so the *next* bit-perfect track picks up from
     * roughly the same perceived loudness, but deliberately does not touch the hardware live --
     * it's intentionally sitting at unity gain for the whole time a fallback track is playing
     * (see [UsbAudioController.setUnityVolume]), and touching it now would mean reclaiming the
     * interface right out from under HAL's own active use of it, the same dropout bug
     * [UsbAudioManager.releaseClaim]'s [activeStreamCount] check exists to prevent.
     */
    private fun onStreamMusicChanged(newIndex: Int) {
        if (ignoreNextStreamMusicBroadcast) {
            ignoreNextStreamMusicBroadcast = false
            return
        }
        if (!usbSettings.bitPerfectMode || _controlState.value == null) return
        if (_playbackRoute.value !is PlaybackRoute.Fallback) return
        val range = currentVolumeRange() ?: return
        val (minIndex, maxIndex) = streamMusicIndexRange()
        if (maxIndex == minIndex) return
        val percent = 100f * (newIndex - minIndex) / (maxIndex - minIndex)
        val dbValue = (curveDbAtPercent(percent) * 256f).roundToIntSafe(range.first, range.second)
        logEvent("STREAM_MUSIC changed to $newIndex while on fallback -- syncing DAC volume target to ${dbValue}dB")
        usbSettings.volumeDb = dbValue
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
            if (!requestClaim()) {
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
        if (!requestClaim()) return
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
        pendingRelease?.cancel(false)
        pendingRelease = null
        interfaceClaimed = false
        activeSession?.let { UsbAudioController.release(it) }
        activeSession = null
        activeFormat = null
        activeSink = null
        _controlState.value = null
        _capabilities.value = null
    }

    /**
     * Re-runs DAC activation for whatever USB audio device is currently attached. Requests
     * permission if needed, or re-activates directly if already permitted -- Android only
     * re-fires the permission-granted broadcast on an actual grant, not on a request for a
     * permission [knownPermissions] already has recorded as granted, so without this there's no
     * way to re-trigger activation short of unplugging the device or restarting the app. Not
     * debug-only despite the name this used to have -- called from real user-facing paths
     * ([setPreferredBitResolution], [onUsbEnabledChanged]), not just testing.
     */
    fun retryActivation() {
        if (!usbSettings.enabled) {
            L.i("retryActivation: USB DAC output is disabled in settings")
            return
        }
        val device = _devices.value.firstOrNull()
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
        Thread { activateDac(device.device) }
            .apply {
                isDaemon = true
                start()
            }
    }

    /**
     * [UsbDacPreferences.Listener] callback: direct USB output is an opt-in, experimental feature (it
     * claims the USB interface away from Android's own audio framework), off by default. Turning
     * it on tries to activate whatever's already attached; turning it off releases any active
     * session immediately.
     */
    override fun onUsbEnabledChanged() {
        if (usbSettings.enabled) {
            L.i("USB DAC output enabled by user, attempting activation")
            retryActivation()
        } else {
            L.i("USB DAC output disabled by user, releasing any active session")
            deactivateDac()
        }
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
        // How long awaitAndroidOutputTakeover() will poll before giving up -- confirmed via
        // logcat the real kernel-driver-rebind-to-ALSA-node latency is ~100-300ms on the one
        // device tested; this leaves a lot of headroom for a slower device/host combo without
        // blocking the playback thread indefinitely if Android never picks the device up at all.
        const val AWAIT_TAKEOVER_TIMEOUT_MS = 1500L
        const val AWAIT_TAKEOVER_POLL_MS = 50L
    }
}
