# libusbdac

Native UAC1/2/3 USB audio output for Android.

Android's normal USB audio path goes through the kernel's USB Audio Class
driver and AudioFlinger, which gives an app no direct control over the
device beyond the system volume. `libusbdac` instead claims the USB
interface directly, parses the device's UAC1/2/3 descriptors itself, and
drives raw isochronous USB transfers from native code to deliver
bit-perfect PCM straight to the DAC's isochronous endpoint -- bypassing
`AudioTrack`/the system mixer entirely for tracks the device can take
natively, and falling back to the normal Android audio path for anything
it can't (e.g. a sample rate the device doesn't support).

It plugs into [Media3](https://github.com/androidx/media) as an
`AudioOutputProvider`/`AudioOutput` pair, so a host app's existing
`DefaultAudioSink` buffering, retry, and stall-recovery logic applies to
USB output the same way it already does for normal `AudioTrack` output.

## Status

Extracted from and originally developed/hardware-tested inside
[Auxio](https://github.com/OxygenCobalt/Auxio), an open-source Android
music player (this fork's USB DAC work: [ostrichbat2k/Auxio](https://github.com/ostrichbat2k/Auxio),
branch `dev`). Confirmed working end-to-end on real hardware as a
standalone library, consumed by Auxio as a git submodule.

Devices tested against: a FiiO Snowsky Tiny A (UAC2, High-Speed), a
generic "AB13X USB Audio" dongle (UAC1, Full-Speed, 8000/48000 Hz only),
and wired EarPods presenting a UAC3 interface (Full-Speed). Every device
seen so far negotiates Synchronous or Adaptive isochronous mode -- the
Asynchronous/feedback-clocked path added for #25 has no real-hardware
verification yet; it only activates for a genuinely Asynchronous device
with a feedback endpoint, and every other device takes the unchanged,
already-verified code path. DSD alt-settings are parsed and shown in the
`UsbDeviceCapabilities` snapshot but are never actually played back --
`UsbAudioOutputProvider` only matches PCM formats for playback routing.

## Versioning

This project is **pre-1.0**: it follows no formal semver guarantee yet, and
a breaking API change may land between tags without a major-version bump.
If you depend on this via JitPack, pin to an exact tag or commit
(`com.github.ostrichbat2k:libusbdac:<tag-or-sha>`) rather than a version
range. See [CHANGELOG.md](CHANGELOG.md) for what changed between releases,
including unreleased changes on `master`.

Going-forward policy (informal, until 1.0): a `0.x` bump may break the
public API; reaching `1.0` is this project's commitment to semver from
then on.

## Features

- UAC 1.0, 2.0, and 3.0 descriptor parsing (clock sources/entities,
  feature units, mixer units, power domains, per-format bit depth and
  sample rates).
- Per-track dynamic format matching: a device offering multiple bit
  depths (e.g. 16-bit and 24-bit alt-settings) switches alt-setting per
  track instead of locking the whole session to one fixed format, and
  the clock-frequency `SET_CUR` is re-applied on every genuinely new
  track rather than assumed from a cached rate.
- High/full-speed-aware isochronous pacing (125µs vs. 1ms packet
  scheduling), with explicit wall-clock pacing rather than trusting the
  kernel's URB completion timing alone.
- Hardware volume/mute control via the device's Feature Unit, including
  graceful handling of devices whose `GET_CUR` readback doesn't work --
  and kept mirrored with Android's `STREAM_MUSIC` volume across route
  switches, so loudness doesn't jump just because a track moved between
  the DAC's own hardware gain and the system mixer.
- Falls back to the host app's normal audio path per-track (not per
  session) for formats the device can't take directly, and releases the
  USB claim back to the kernel driver while idle so Android's own USB
  audio HAL can use the same physical device. Release/reclaim ordering
  matches what the kernel's USB Audio Class driver actually requires
  (control vs. streaming interface), so toggling bit-perfect mode or
  unplugging mid-track never permanently wedges the device's fallback
  path -- confirmed against the real kernel source, not just by retrying
  until it seemed to work.
- Never permanently evicts a composite device's non-audio interfaces
  (e.g. an inline remote's HID interface) -- the kernel-driver detach
  needed to claim the audio interfaces hands everything else straight
  back.
- A read-only `UsbDeviceCapabilities` snapshot (vendor/product IDs, the
  playback topology, and every interface on the device's active
  configuration with its class and role) for a host app's own
  Capabilities/about screen, independent of whether bit-perfect mode
  ever actually activates. Non-PCM alt settings a device declares (e.g.
  raw DSD) are parsed and included in this snapshot too, rather than
  being silently dropped the way they are from playback selection.
- Optional built-in blind ABX test mode: secretly alternates bit-perfect
  and fallback routing per track (reusing the same per-track routing
  path, no mid-track switching), tracks a running correct/total score,
  and keeps the two routes' loudness forced in sync for the duration of
  a trial so level alone can't give away the hidden answer.

## Requirements

- `minSdk 24`.
- A USB Audio Class 1.0/2.0/3.0 compliant device and the Android USB
  permission for it (requested through the normal
  `UsbManager`/`ACTION_USB_PERMISSION` flow) -- no root required.
- `androidx.media3:media3-exoplayer` on the host app's classpath.

### Host manifest / setup

The library's own `AndroidManifest.xml` declares nothing -- USB host
support is optional per-device, so whether to declare
`<uses-feature android:name="android.hardware.usb.host" android:required="..."/>`
is the host app's call (`required="false"` if the app should still install
on devices without USB host support, falling back to no DAC output at
all).

Device discovery is runtime-only: `UsbDeviceDiscoveryImpl` registers its
own `BroadcastReceiver` for `ACTION_USB_DEVICE_ATTACHED`/`_DETACHED` when
`UsbAudioManager.start()` is called, and polls `UsbManager.deviceList` for
devices already attached at that point. There is no manifest-declared
`<uses-feature>`-plus-`usb_device_filter.xml`-plus attach `<intent-filter>`
auto-launch support -- the host app has to already be running (or add that
manifest wiring itself) for `start()` to ever get called in response to a
device being plugged in while the app isn't.

## Threading

`UsbAudioManager`'s public `StateFlow` properties (`capabilities`,
`playbackRoute`, `controlState`, `pipelineStats`, `diagnosticLog`,
`devices`, `blockPlayback`) are safe to collect from any thread or
coroutine context -- `StateFlow`'s own value publication is thread-safe,
and several of them are updated from background threads internally (the
activation thread, the streaming thread, a dedicated volume-write
executor), not just from the thread that called into the manager.

Every mutating entry point (`start()`, `requestPermission()`,
`triggerActivation()`, `releaseClaim()`, and `UsbControlWriter`'s
`setVolume`/`setMuted`/`setMixerGain`) can also be called from any thread
-- each one hands off to its own internal background thread/executor
rather than assuming the caller's thread, so there's no required
dispatcher to call them from. The one exception is `awaitActivation()`,
a `suspend fun` meant to be called from a coroutine (see "Using it"
above) -- it suspends for the real hardware activation round-trip rather
than blocking a thread.

## Architecture

The library is organized by feature area rather than by file type: every
stateful component is a plain interface (internal unless a host app needs
it directly) with one `*Impl` providing it, grouped into a package for the
concern it covers. `UsbAudioManager`/`UsbAudioManagerImpl`, `SessionState`,
`UsbAudioTuning`, and `UsbDacPreferences` stay at the package root since
they're used across every feature rather than belonging to one.

| Package | Class | Role |
| --- | --- | --- |
| (root) | `UsbAudioManager` / `UsbAudioManagerImpl` | Entry point: orchestration only. Detects devices, manages permission, activates a session, and hands out the `AudioSink` the host wires into its player -- every self-contained concern below is constructed here and delegated to. Publishes `capabilities: StateFlow<UsbDeviceCapabilities?>` and `blockPlayback: StateFlow<Boolean>` for a host's UI. |
| (root) | `SessionState` | Everything that decides whether the claimed interface/format needs reasserting, bundled into one comparable value instead of separately-named booleans. |
| (root) | `UsbAudioTuning` | Every tuning/config constant used across the library (timing margins, retry counts, buffer sizing) in one place instead of one private constant per class. |
| (root) | `UsbDacPreferences` | The settings contract the host app implements (enabled/bit-perfect toggle, persisted volume) -- no bundled persistence or DI framework. |
| `discovery` | `UsbDeviceDiscovery` / `Impl` | Tracks attached USB Audio Class devices and their permission state; owns the `ACTION_USB_DEVICE_ATTACHED`/`DETACHED`/permission-result `BroadcastReceiver`. |
| `claim` | `UsbClaimController` / `Impl` | Who currently holds the raw USB interface -- this library's own streaming path or Android's kernel-driver-backed USB-audio HAL -- and the hand-off between them, including the fallback that fully releases the connection (rather than just reclaiming it) when HAL doesn't recognize the device within a bounded wait. |
| `activation` | `UsbDacActivator` / `Impl` | Walks a device's raw descriptors, resolves its UAC1/2/3 topology (from cache when possible), and fully configures a session -- clock, power domain, mute/volume/mixer defaults, alt-setting -- ready for playback with the interface still claimed. |
| `activation` | `UsbDacDescriptorCache` / `Impl` | Persists each device's resolved topology and Clock Source supported-rate ranges across activations, keyed by vendor+product ID, so a cache hit can shorten how long the raw claim needs to stay held. |
| `control` | `UsbVolumeController` / `Impl` | All hardware volume/mute decision logic: the Feature Unit read/write path, the unity-gain hand-off to Android's HAL, and mirroring with `STREAM_MUSIC` across route switches. |
| `control` | `UsbControlWriter` / `Impl` | Every live, user-facing read/write against the active session's adjustable controls (mute, volume, per-channel mixer gain) and republishing live control state from a fresh device read-back -- the "Settings-screen control" concern. |
| `control` | `UsbControlState` | The live, read-only control-value snapshot `UsbControlWriter` publishes for a host's Settings UI. |
| `protocol` | `UacPlaybackTopology`, `UsbAudioDescriptorParserUac1/2/3`, `UsbAudioDescriptorHelper` | Parses a device's USB Audio Class configuration descriptor into the entities (clock source, feature unit, mixer unit, streaming formats, non-PCM alt settings) the rest of the library needs, one parser per UAC version plus the TLV-walking/byte-reading code genuinely shared between them. |
| `protocol` | `UsbAudioController` | Low-level UAC control-transfer calls (clock frequency, volume, mute, power domain) addressed by entity ID. |
| `streaming` | `UsbNative` / `usb_native.cpp` | Native isochronous transfer layer: raw `USBDEVFS_DISCONNECT`/URB submission, since `UsbDeviceConnection` doesn't expose either. |
| `streaming` | `UsbAudioOutput` / `UsbAudioOutputProvider` | Implements Media3's `AudioOutput`/`AudioOutputProvider`, handing a plain `DefaultAudioSink` a native isochronous stream to write to. |
| `streaming` | `RoutingAudioSink` | An `AudioSink` that picks, per track, between the direct USB path and a host-supplied fallback sink. |
| `streaming` | `IsoPacketScheduler`, `PcmRingBuffer`, `UsbPipelineStats` | Isochronous packet-size pacing math, the streaming thread's PCM ring buffer, and the live pipeline stats exposed for a host's diagnostics UI. |
| `abx` | `UsbAbxTester` / `Impl` | The blind ABX self-test: secretly routes a track bit-perfect or HAL-fallback, then asks the host app to guess which, scoring the result. |
| `diagnostics` | `UsbDiagnosticLog` / `Impl` | The timestamped, most-recent-first activation event log backing the manager's diagnostic log. |
| `debug` | `UsbAudioInspector` | A standalone manual descriptor-inspection tool, independent of the real activation path. |

The library has **no DI-framework dependency** -- `UsbAudioManagerImpl`
takes a plain constructor behind the `UsbAudioManager` interface, and a
host using something like Hilt or Koin bridges it into its own graph with
an ordinary `@Provides`/factory method.

## Installation

Published via [JitPack](https://jitpack.io/#ostrichbat2k/libusbdac) from
release tags.

```groovy
// settings.gradle
dependencyResolutionManagement {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}

// build.gradle
dependencies {
    implementation 'com.github.ostrichbat2k:libusbdac:v0.1.0' // or any tag/commit
}
```

(Auxio itself still consumes this repo as a git submodule rather than this
published artifact, since it tracks unreleased commits between tags.)

## Using it

```kotlin
// 1. Implement the settings contract however your app persists preferences.
class MyUsbDacPreferences : UsbDacPreferences {
    override val enabled: Boolean get() = /* ... */
    override val bitPerfectMode: Boolean get() = /* ... */
    override var volumeDb: Int? get() = /* ... */; set(value) { /* ... */ }
    override fun registerListener(listener: UsbDacPreferences.Listener) { /* ... */ }
    override fun unregisterListener(listener: UsbDacPreferences.Listener) { /* ... */ }
}

// 2. Construct the manager and let it track USB attach/detach + permission.
val usbAudioManager: UsbAudioManager = UsbAudioManagerImpl(context, MyUsbDacPreferences())

// 3. Wire a RoutingAudioSink into each audio renderer your player builds,
//    with your own normal AudioSink as the fallback.
val sink = RoutingAudioSink(usbAudioManager, fallback = DefaultAudioSink.Builder(context).build())

// 4. If playback is about to start right after a cold launch / permission
//    grant, give an in-flight activation a bounded chance to finish first
//    so the very first track doesn't lose the race and commit to fallback:
suspend fun beforeRestoringPlayback() {
    usbAudioManager.awaitActivation()
}
```

`UsbAudioOutput`'s own `pause()` schedules releasing the USB claim after a
short grace period (so a quick pause/resume doesn't pay the full hand-off
cost), but a host that knows playback stopped for a reason that *isn't*
momentary -- most notably losing Android audio focus to another app --
should call `usbAudioManager.releaseClaim(delayMs = 0)` itself right away.
Audio focus loss in particular is worth reacting to immediately: Android
can grant the new app focus and let it start opening the device before
this app's own focus-loss callback even arrives, so every millisecond this
library still holds the claim after that point is a millisecond more that
the other app's first attempt is likely to fail.

## Troubleshooting

`diagnosticLog`/`UsbDiagnosticLog` is a most-recent-first, timestamped log
of activation events (attach, permission, claim, descriptor parse,
session ready, every reason a fallback happened) -- read it first for
"why isn't bit-perfect mode active for this device/track." A clean
"DAC session ready" line followed by `playbackRoute` reporting
`PlaybackRoute.Direct` for the current track means it *is* working as
intended; most "it's not working" reports turn out to be a device/track
combination that was always going to fall back (see "What happens with
an unsupported device" below), not a bug.

`pipelineStats`/`UsbPipelineStats` is a live ring-buffer/USB-transfer
snapshot (`null` whenever nothing is genuinely streaming) -- `urbsSubmitted`
climbing steadily at the expected rate with `reapErrors`/`underrunCount`
at or near zero is healthy; a nonzero/climbing `underrunCount` with
`bufferedBytes` near zero points at the ring buffer starving (upstream
decode/IO falling behind), while `reapErrors` climbing instead points at
the USB transfer layer itself (device removed, cable fault, hub issue).

**What happens with an unsupported device**: a device with no
`USB_CLASS_AUDIO` interface at all never appears in `devices` -- it's
filtered out at discovery, silently, with no log line and no error,
since it's simply not a USB audio device as far as this library is
concerned. A device that *does* declare a USB Audio Class interface but
fails descriptor parsing (malformed/unusual-enough descriptors) logs the
failure to `diagnosticLog` and `UsbDacActivator.activate()` returns
`null` -- the device shows up in `devices`, permission can be granted for
it, but activation never succeeds and every track falls back to the host
app's normal audio path. Neither case throws an exception out of the
public API.

An Asynchronous-sync-type device (see "Status" above) is not an
unsupported-device case -- it's expected to activate and stream normally,
just without real-hardware verification of the feedback-clocked rate
correction yet.

## Known limitations

- The Asynchronous/feedback-clocked isochronous path (#25) has no
  real-hardware verification -- every device tested so far is
  Synchronous or Adaptive.
- DSD alt-settings are parsed and shown in the `UsbDeviceCapabilities`
  snapshot but are never selected for playback; only PCM formats are
  matched for routing.
- Non-default `bInterval` values are parsed and used for pacing, but
  haven't been exercised against a device that actually declares one
  other than the common case.
- The streaming-loop hardening work (lost URB slots, busy-spin on device
  loss -- see the CHANGELOG) is real-hardware-verified for the specific
  failure modes it targeted, not exhaustively fuzzed against every way a
  device can misbehave mid-stream.
- Multichannel (>2-channel) output is implemented (channel masks, Feature
  Unit/Mixer Unit loops all scale with the device's real channel count)
  but not hardware-verified against actual >2-channel hardware.

## Building

```
./gradlew assembleDebug
```

Needs the Android NDK (CMake 3.22.1+) on `PATH` or resolvable by the
Android Gradle Plugin for the native isochronous transfer layer.

## License

GNU General Public License v3.0 or later -- see individual file headers.
