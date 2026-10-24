/*
 * Copyright (c) 2026 libusbdac Project
 * UsbClaimControllerImpl.kt is part of libusbdac.
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

import android.hardware.usb.UsbDevice
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.ostrichbat2k.usbdac.api.UsbAudioManager
import org.ostrichbat2k.usbdac.api.UsbClaimController
import org.ostrichbat2k.usbdac.api.UsbVolumeController
import timber.log.Timber as L

/**
 * Everything this app decides about *who* currently holds the raw USB interface -- this app's
 * own bit-perfect streaming path, or Android's normal kernel-driver-backed USB-audio HAL -- and
 * the hand-off between them. Previously mixed into [UsbAudioManager] alongside unrelated
 * concerns (device attach/detach, format/topology activation); pulled out here for the same
 * reason [UsbVolumeController] was: this is the single most invariant-heavy piece of this
 * codebase (nearly every function below has its own "confirmed, reproduced" history), and
 * having it all in one file makes those invariants visible in one place instead of scattered
 * across a much larger file.
 *
 * Deliberately does *not* hold its own copy of "what session is currently active" -- [requestClaim]
 * takes it as an explicit parameter (safe: always called synchronously, no delay in between), while
 * [releaseClaim]'s delayed/scheduled path instead re-resolves it live, at the moment each step
 * actually runs, via [getActiveSession] -- not a snapshot frozen at the moment [releaseClaim] was
 * first called. That distinction matters: [UsbAudioManager]'s own session state can change in the
 * gap between scheduling a delayed release and it actually firing (a new device attached, the
 * session torn down), and reacting to the live state at fire-time, not a stale snapshot, is exactly
 * what the pre-extraction code already did by reading its own private field fresh every time.
 *
 * [onReclaimed], [onClaimFailed], and [onTakeoverTimeout] are all [UsbAudioManager]'s own side
 * effects (invalidating its `activeFormat` cache, tearing the session down) -- they stay
 * [UsbAudioManager]'s responsibility, just invoked from here via callback rather than inlined.
 */
class UsbClaimControllerImpl(
    private val audioManager: AudioManager,
    private val volumeController: UsbVolumeController,
    private val getActiveSession: () -> UsbAudioSession?,
    private val onReclaimed: () -> Unit,
    private val onClaimFailed: () -> Unit,
    private val onTakeoverTimeout: () -> Unit,
) : UsbClaimController {
    private val claimScheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "UsbAudioControl-Claim").apply { isDaemon = true }
        }
    // Whether we currently hold the raw interface claim (vs. having handed it to the kernel's own
    // driver for Android's normal USB-audio HAL to use). Distinct from a session existing at all
    // -- a session can be fully discovered/activated and sitting completely unclaimed between
    // tracks, see UsbAudioManager.activateDac's tail and [releaseClaim].
    @Volatile private var interfaceClaimed = false
    @Volatile private var pendingRelease: ScheduledFuture<*>? = null
    // See UsbAudioManager.deactivateDac()'s and this class's own awaitAndroidOutputTakeover()
    // comments on this.
    @Volatile private var lastDeactivatedDevice: UsbDevice? = null
    // How many UsbAudioOutput instances currently consider themselves actively streaming (see
    // notifyStreamingStarted/Stopped) -- i.e. actually depending on the claim right now, not just
    // having asked for it once. [releaseClaim] must never release out from under one of these:
    // confirmed, reproduced, a real multi-second dropout -- a volume key press during active
    // playback ran a Settings-style mutator's background write, which unconditionally called
    // releaseClaim() afterward on the assumption nothing else needed the claim. With a track
    // genuinely streaming, that scheduled release fired ~RELEASE_GRACE_MS later and yanked the
    // interface out from under the still-running native thread, which only recovered once
    // DefaultAudioSink's own stall detection eventually noticed and forced a fresh
    // reconfigure/reclaim.
    @Volatile private var activeStreamCount = 0

    // Latches a deactivateOnTimeout=true request that arrived while activeStreamCount > 0 (see
    // releaseClaim's early-return branch) so it isn't silently lost. The caller that lost that
    // race never gets to run doReleaseClaim itself -- the active stream's own eventual
    // pause()/release() is what actually triggers the real release later, with its own default
    // (false) -- so without latching here, a caller's genuine "a concrete track needs HAL to see
    // this device right now" intent would silently downgrade back to the cheap reclaim-and-remute
    // path, reintroducing the exact HAL-recognition bug this parameter exists to fix.
    @Volatile private var pendingDeactivateOnTimeout = false

    /** Called by [UsbAudioOutput] once it's actually claimed and streaming -- see [releaseClaim]. */
    override fun notifyStreamingStarted() {
        activeStreamCount++
    }

    /** The inverse of [notifyStreamingStarted] -- call before, not after, releasing anything. */
    override fun notifyStreamingStopped() {
        activeStreamCount = (activeStreamCount - 1).coerceAtLeast(0)
    }

    /** Whether we currently hold the raw interface claim -- see [interfaceClaimed]. */
    override fun isClaimed(): Boolean = interfaceClaimed

    /**
     * For [UsbAudioManager.activateDac]'s tail: its own `UsbAudioController.activate()` call
     * (and the `selectFormat` right after it) already claimed the interface to do that one-time
     * discovery/setup, outside of [requestClaim] -- this just records that fact so later
     * [releaseClaim]/[requestClaim] calls agree with reality.
     */
    override fun markAlreadyClaimed() {
        interfaceClaimed = true
    }

    /**
     * For [UsbAudioManager.deactivateDac], which does its own full session close (not just a
     * hand-back to the kernel) and needs to mark the claim gone without going through the normal
     * [releaseClaim] dance.
     */
    override fun markReleased() {
        interfaceClaimed = false
    }

    /** For [UsbAudioManager.deactivateDac], canceling any release scheduled before a teardown. */
    override fun cancelPendingRelease() {
        pendingRelease?.cancel(false)
        pendingRelease = null
    }

    /**
     * For [UsbAudioManager.deactivateDac] to record which device to wait for -- see
     * [awaitAndroidOutputTakeover]'s own comment on why.
     */
    override fun recordDeactivatedDevice(device: UsbDevice?) {
        lastDeactivatedDevice = device
    }

    /**
     * Claims the interface (evicting whatever the kernel driver currently has bound) if we don't
     * already hold it -- idempotent and cheap to call when already claimed, so every caller about
     * to do real bit-perfect work (streaming, a Settings-screen control write) calls this first
     * rather than assuming the claim is still held from some earlier point. Blocking; callers on
     * this codebase's playback thread already tolerate similar synchronous USB I/O elsewhere (the
     * clock-relock settle wait in [UsbAudioOutput]'s init logic).
     *
     * Returns [UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED] rather than just `true` specifically
     * so [UsbAudioOutput.activateAndStream] can tell "nothing else could have touched the device
     * since we last set it up" apart from a genuine release+reclaim -- see its own doc comment for
     * why that distinction, not just success/failure, decides whether a resume needs to redo its
     * whole clock/mute/volume relock dance.
     */
    override fun requestClaim(session: UsbAudioSession?): UsbAudioManager.ClaimOutcome {
        pendingRelease?.cancel(false)
        pendingRelease = null
        if (interfaceClaimed) return UsbAudioManager.ClaimOutcome.ALREADY_CLAIMED
        session ?: return UsbAudioManager.ClaimOutcome.FAILED
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
            onReclaimed()
        } else {
            onClaimFailed()
        }
        return if (ok) UsbAudioManager.ClaimOutcome.RECLAIMED else UsbAudioManager.ClaimOutcome.FAILED
    }

    /**
     * Schedules handing the interface back to the kernel's own driver after [delayMs] (see
     * `RELEASE_GRACE_MS`), canceling any release already pending -- so a [requestClaim] call
     * before the delay elapses is a pure no-op cancellation, not a real reclaim. Pass 0 to
     * release immediately (used when something -- a pause, a track that can't use the DAC, the
     * player itself -- is definitely, not just momentarily, done with it).
     *
     * [awaitAndroidTakeover] only changes whether *this call* blocks on [awaitAndroidOutputTakeover]
     * before returning -- a caller with its own next step that depends on Android having actually
     * recognized the device (e.g. [RoutingAudioSink.resolveForConfigure] about to let a fallback
     * track's `configure()` proceed) needs that. Every real release gets verified either way (see
     * [doReleaseClaim]), so a caller that doesn't need to block still gets a definitive log
     * confirming -- or, on timeout, warning -- whether the kernel actually took the device back,
     * rather than silently trusting the release call.
     *
     * [deactivateOnTimeout] controls what happens if that verification times out: `true` means a
     * concrete track genuinely needs HAL to reach this device right now (only
     * [RoutingAudioSink.resolveForConfigure]'s fallback branch passes this), so
     * [awaitAndroidOutputTakeover] fully deactivates on timeout rather than reclaiming the
     * interface back to ourselves, which would just perpetuate "HAL can never see it" on
     * hardware where that's the problem -- see its own doc comment. `false` (every other
     * caller -- a pause's grace-period release, a Settings-write's one-off release, or
     * [UsbAudioManager.activateDac]'s own post-activation courtesy release) means nothing
     * concrete is actually waiting on HAL, so a timeout just reclaims the interface back and
     * re-mutes, keeping the session alive and bit-perfect mode usable in the meantime --
     * confirmed, reproduced: escalating unconditionally here made bit-perfect mode on a device
     * that never recognizes within the timeout self-deactivate within ~1.5s of every single
     * activation, including activateDac's own tail release where nothing was actually trying to
     * use the device via HAL at all.
     */
    override fun releaseClaim(delayMs: Long, awaitAndroidTakeover: Boolean, deactivateOnTimeout: Boolean = false) {
        pendingRelease?.cancel(false)
        if (activeStreamCount > 0) {
            // Something is genuinely streaming right now and still needs the claim -- a caller
            // here (a Settings-screen mutator, most likely) just wanted it briefly for its own
            // one-off write and is in no position to know that. Not even worth scheduling: the
            // active UsbAudioOutput's own pause()/release() is what will actually call this once
            // it's really done -- but see pendingDeactivateOnTimeout's own doc comment for why
            // this caller's deactivateOnTimeout still has to be remembered rather than dropped.
            pendingDeactivateOnTimeout = pendingDeactivateOnTimeout || deactivateOnTimeout
            pendingRelease = null
            return
        }
        val effectiveDeactivateOnTimeout = deactivateOnTimeout || pendingDeactivateOnTimeout
        pendingDeactivateOnTimeout = false
        pendingRelease =
            if (delayMs <= 0) {
                doReleaseClaim(awaitAndroidTakeover, effectiveDeactivateOnTimeout)
                null
            } else {
                claimScheduler.schedule(
                    { doReleaseClaim(awaitAndroidTakeover, effectiveDeactivateOnTimeout) }, delayMs, TimeUnit.MILLISECONDS)
            }
    }

    private fun doReleaseClaim(awaitAndroidTakeover: Boolean, deactivateOnTimeout: Boolean) {
        if (activeStreamCount > 0) {
            // The race can still be lost a second time between releaseClaim scheduling this and
            // it actually firing (a new stream started in the meantime) -- re-latch for the same
            // reason releaseClaim's own early-return branch does, rather than silently dropping
            // an already-escalated request here.
            pendingDeactivateOnTimeout = pendingDeactivateOnTimeout || deactivateOnTimeout
            return
        }
        // Resolved live, right now -- not a parameter frozen back when releaseClaim() was first
        // called -- since this can run after a real delay (see releaseClaim's own doc comment on
        // why that distinction matters).
        val session = getActiveSession()
        if (session != null && interfaceClaimed) {
            // See UsbVolumeController.prepareForHalHandoff's doc comment for why this has to
            // happen, and why it has to happen before releaseToKernel below, not after.
            volumeController.prepareForHalHandoff(session, session.topology.channelCount)
            UsbAudioController.releaseToKernel(session)
            interfaceClaimed = false
            if (awaitAndroidTakeover) {
                awaitAndroidOutputTakeover(deactivateOnTimeout)
            } else {
                // The caller doesn't need to block on this, but still deserves a definitive
                // answer in the log instead of just trusting releaseToKernel's own ioctl-level
                // success silently -- run the same check, just off this thread.
                claimScheduler.execute { awaitAndroidOutputTakeover(deactivateOnTimeout) }
            }
        }
    }

    /**
     * Blocks (briefly, bounded) until Android's own audio framework reports this device as an
     * available output, or [AWAIT_TAKEOVER_TIMEOUT_MS] elapses.
     *
     * The kernel-side decision (will snd-usb-audio actually bind and create a playback PCM
     * substream) is already synchronous by the time [UsbAudioController.releaseToKernel]'s
     * `releaseInterface()` call returns -- see its own doc comment: the `USBDEVFS_CONNECT` ioctl
     * that hands the interface back runs `device_attach()` inline, so there is nothing to wait
     * out, let alone "submit" or nudge, at the kernel/USB-subsystem layer itself. What *does* have
     * real, measured latency (confirmed via logcat: ~100-300ms from release to `UsbAlsaManager:
     * alsaFileAdded(pcmC1D0p)` on the one device tested) is Android's own userspace audio stack,
     * running in a separate process (`audioserver`/`UsbAlsaManager`), noticing that already-bound
     * kernel substream, creating its `/dev/snd/` node, and updating AudioPolicyManager's device
     * list -- ordinary app code has no API to directly trigger or accelerate that recognition
     * (no "submit claim now" equivalent is exposed), only to observe it. Opening a *fallback*
     * `AudioTrack` while that's still in flight doesn't fail -- it just succeeds, silently routed
     * to the speaker, and Android never revisits that choice later, which is what
     * [RoutingAudioSink] calls this for, right before letting a fallback track's `configure()` run.
     *
     * Implemented via [AudioDeviceCallback] rather than polling [AudioManager.getDevices] on a
     * fixed interval: this reacts to Android's own recognition the instant it's reported, instead
     * of discovering it up to one poll tick late.
     *
     * The ~100-300ms figure above held on the one device originally tested, but is not universal
     * -- confirmed, reproduced on a second device (Samsung S23 + USB-C EarPods) that
     * `UsbAlsaManager` never recognizes the device at all as long as our own
     * `UsbDeviceConnection` stays open, no matter how long you wait, even with every interface
     * already released. [onTakeoverTimeout] (fired below, on a real timeout, only when
     * [deactivateOnTimeout] says a concrete track actually needs it) exists for exactly that
     * case -- see [releaseClaim]'s own doc comment on why that's conditional, not unconditional.
     */
    private fun awaitAndroidOutputTakeover(deactivateOnTimeout: Boolean) {
        // getActiveSession() is already returning null by the time this runs if the caller got
        // here via deactivateDac() (e.g. the user just disabled USB DAC output) rather than a
        // per-track liveness switch -- lastDeactivatedDevice is deactivateDac()'s own record of
        // exactly which device to wait for in that case. Without this fallback, this function
        // returned immediately, silently skipping the whole wait -- confirmed, reproduced:
        // switching to fallback right after disabling the DAC raced Android's own recognition of
        // the just-released device far more often than the old "only re-resolves at the next
        // track" path ever did, since that path had arbitrary real wall-clock time to let this
        // settle naturally and this one doesn't.
        val device = getActiveSession()?.device ?: lastDeactivatedDevice ?: return
        // Consume-once, right away -- this wait only needs to happen for the one real gap right
        // after *this* release/detach. Without clearing it here, every later fallback track's
        // configure() (see RoutingAudioSink.resolveForConfigure) re-ran this same wait for a
        // device that was either already confirmed as an output on the very first attempt, or
        // (a genuine physical unplug) is never coming back at all -- confirmed, reproduced: after
        // any one deactivation, *every* subsequent track blocked the configure()-calling thread
        // for up to AWAIT_TAKEOVER_TIMEOUT_MS, forever, until the process restarted, even though
        // there was no USB DAC in the picture anymore.
        lastDeactivatedDevice = null
        val targetName = device.productName?.toString() ?: device.deviceName
        // Not an equality check -- confirmed, reproduced: Android registers this device's
        // AudioDeviceInfo.productName as "USB-Audio - <productName>" (its own internal naming
        // convention for USB audio devices, visible via `dumpsys audio`'s DeviceInfo listing),
        // not UsbDevice.productName's bare value. An exact match against the bare name silently
        // never matched anything -- this function always timed out and warned, even though
        // UsbAlsaManager had already recognized the device within single-digit milliseconds of
        // release every time.
        fun matches(info: AudioDeviceInfo) =
            (info.type == AudioDeviceInfo.TYPE_USB_HEADSET || info.type == AudioDeviceInfo.TYPE_USB_DEVICE) &&
                info.productName?.toString()?.contains(targetName) == true

        val startedAt = System.currentTimeMillis()
        if (audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any(::matches)) {
            L.i("UsbAudioManager: kernel had already reclaimed $targetName as an Android output")
            return
        }
        val latch = CountDownLatch(1)
        val callback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    if (addedDevices.any(::matches)) latch.countDown()
                }
            }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        try {
            // Re-check after registering -- covers the race where the device landed between the
            // first check above and this callback actually being registered, which
            // onAudioDevicesAdded alone would never report (it only fires for future additions).
            val took =
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any(::matches) ||
                    latch.await(AWAIT_TAKEOVER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (took) {
                L.i(
                    "UsbAudioManager: kernel reclaimed $targetName as an Android output after " +
                        "${System.currentTimeMillis() - startedAt}ms")
            } else {
                L.w("UsbAudioManager: Android didn't report the USB device as an output within timeout")
                // On at least one real device (Samsung S23 + USB-C EarPods), Android's
                // UsbAlsaManager never binds the kernel ALSA driver at all as long as we keep
                // the UsbDeviceConnection open -- releasing interfaces alone (doReleaseClaim's
                // releaseToKernel call, above) is not enough; confirmed, reproduced: disabling
                // this app's DAC feature entirely (which closes the connection, via
                // UsbAudioController.release) made the exact same EarPods get recognized by
                // UsbAlsaManager within milliseconds, when staying merely-interface-released
                // never did across several minutes of observation. So when deactivateOnTimeout
                // says a concrete track genuinely needs HAL to reach this device right now,
                // reclaiming the interface back to ourselves (the non-escalating branch below)
                // doesn't just fail to help -- it's exactly the state that was blocking HAL from
                // ever seeing the device, on hardware like this. Fully deactivating instead
                // (onTakeoverTimeout) actually closes the connection, which both bounds the
                // exposure window the same way reclaiming would have (nothing stays claimed by
                // us to ever threaten a future exposure) *and* gives HAL a real chance to bind
                // the device this time.
                //
                // But a release with nothing concrete waiting on HAL (a pause's grace-period
                // release, a Settings-write's one-off release, activateDac's own post-activation
                // courtesy release) has nothing to gain from that, and a lot to lose: confirmed,
                // reproduced, escalating unconditionally here made bit-perfect mode on a device
                // that never recognizes within the timeout self-deactivate within ~1.5s of every
                // single activation, long before the user got a chance to do anything with it.
                // For those callers, reclaim the interface back and re-mute instead -- the same
                // fix this function used to always apply -- keeping the session alive and usable.
                //
                // Either branch is guarded by the live active session still being the same
                // device this wait started for, since a concurrent deactivateDac() or device
                // swap in the meantime already handled (or made moot) this on its own.
                if (deactivateOnTimeout) {
                    if (getActiveSession()?.device == device) {
                        onTakeoverTimeout()
                    }
                } else {
                    val current = getActiveSession()
                    if (current?.device == device && requestClaim(current) != UsbAudioManager.ClaimOutcome.FAILED) {
                        getActiveSession()?.let { volumeController.remuteForUnconfirmedHandoff(it) }
                    }
                }
            }
        } finally {
            audioManager.unregisterAudioDeviceCallback(callback)
        }
    }

    private companion object {
        // How long awaitAndroidOutputTakeover() will wait before giving up -- confirmed via
        // logcat that real recognition landed in ~100-300ms on the one device tested, so this
        // is a generous multiple of that, not a tight bound.
        const val AWAIT_TAKEOVER_TIMEOUT_MS = 1500L
    }
}
