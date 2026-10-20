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
branch `usb-dac-support`). Confirmed working end-to-end on real hardware as
a standalone library, consumed by Auxio as a git submodule.

Devices tested against: a FiiO Snowsky Tiny A (UAC2), a generic "AB13X USB
Audio" dongle (UAC1, 8000/48000 Hz only), and wired EarPods presenting a
UAC3 interface.

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
  ever actually activates.
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

## Architecture

| Class | Role |
| --- | --- |
| `UsbAudioManager` | Entry point. Owns device detection, permission handling, session activation, claim/release lifecycle, the ABX test harness, and the `AudioSink` the host wires into its player. Publishes `capabilities: StateFlow<UsbDeviceCapabilities?>` and `blockPlayback: StateFlow<Boolean>` for a host's UI. |
| `UsbAudioDescriptorParser` / `UacPlaybackTopology` | Parses a device's USB Audio Class configuration descriptor into the entities (clock source, feature unit, mixer unit, streaming formats) the rest of the library needs, and into the `UsbDeviceCapabilities`/`UsbInterfaceInfo` snapshot exposed for display. |
| `UsbAudioController` | Low-level UAC control-transfer calls (clock frequency, volume, mute, power domain) addressed by entity ID. |
| `UsbNative` / `usb_native.cpp` | Native isochronous transfer layer: raw `USBDEVFS_DISCONNECT`/URB submission, since `UsbDeviceConnection` doesn't expose either. |
| `UsbAudioOutput` / `UsbAudioOutputProvider` | Implements Media3's `AudioOutput`/`AudioOutputProvider`, handing a plain `DefaultAudioSink` a native isochronous stream to write to. |
| `RoutingAudioSink` | An `AudioSink` that picks, per track, between the direct USB path and a host-supplied fallback sink. |
| `UsbDacPreferences` | The settings contract the host app implements (enabled/bit-perfect toggle, persisted volume) -- no bundled persistence or DI framework. |

The library has **no DI-framework dependency** -- `UsbAudioManager` takes a
plain constructor, and a host using something like Hilt or Koin bridges it
into its own graph with an ordinary `@Provides`/factory method.

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
val usbAudioManager = UsbAudioManager(context, MyUsbDacPreferences())

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

## Building

```
./gradlew assembleDebug
```

Needs the Android NDK (CMake 3.22.1+) on `PATH` or resolvable by the
Android Gradle Plugin for the native isochronous transfer layer.

## License

GNU General Public License v3.0 or later -- see individual file headers.
