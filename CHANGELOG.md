# Changelog

All notable changes to this project are documented in this file.

This project is pre-1.0: a breaking API change may land between tags
without a major-version bump -- see "Versioning" in [README.md](README.md).
Pin to an exact tag or commit rather than a version range.

## [Unreleased]

### Added
- Added a user-configurable max-volume safety cap (#109).
- Check each format's largest packet against the endpoint's
  `wMaxPacketSize` (high-bandwidth multiplier bits decoded) when choosing
  a format, instead of stalling when the kernel rejects the URB (#130).
- Stream pre-roll silence while the DAC settles after a clock change,
  unmute onto silence, and fade the first real frames in (#127).
- Measure the URB queue margin -- minimum time to spare, times the queue
  ran dry, longest pacing wait -- in `UsbPipelineStats` and logcat, with
  `UsbDacPreferences.urbQueueOverride` for queue-depth experiments (#123).
- Cross-check a clock change against an Asynchronous device's feedback,
  reporting a different standard rate in the diagnostic log and
  `UsbControlState.feedbackRateMismatchHz` (detection only) (#124).
- Device quirk table seeded from Linux `snd-usb-audio`'s quirk flags, with
  an app override (`UsbDeviceQuirks.setOverride`) (#132).
- Warn when an Asynchronous endpoint has no feedback endpoint (implicit
  feedback, not supported) (#133).

### Fixed
- Decode high-speed feedback per microframe, so async feedback is
  actually applied, and keep the scheduler's fractional phase across
  feedback updates instead of quantizing the rate (#119, #120); the
  feedback loop no longer allocates per packet (#121).
- Asynchronous devices with a feedback endpoint: one thread now reaps the
  shared fd and routes each completion to its stream, instead of the main
  and feedback threads taking each other's URBs (duplicate submissions,
  lost queue slots, stalled feedback) (#139). `UsbNativeBridge.reapIso` is
  replaced by `reapIsoAny`.
- A claim release requested during an activation attempt -- a pause or
  track skip during a clock relock -- is replayed when the attempt ends
  instead of being dropped, so the DAC no longer stays claimed with
  nothing streaming (#141).
- Claim controller decisions run under one lock: a claim can no longer
  report `ALREADY_CLAIMED` on an interface a concurrent release is handing
  to the kernel, and a release already running can no longer fire after
  the claim that superseded it (#142).
- A streaming thread that `stop()` had to leave stuck is no longer revived
  by the next `start()`, which also no longer overwrites (and so leaks)
  its native handles (#147).
- A flush (seek) resets the playback position reliably and no longer
  credits URBs filled before it (#148).
- A `play()` landing just as an activation declined by a pause was
  finishing is no longer dropped; the activation starts again instead of
  waiting for stall recovery (#149).
- UAC1 devices that don't implement the Sampling Frequency control no
  longer fail activation (#128).
- Honour the UAC2/3 Clock Source's `bmControls`: a read-only frequency is
  not written, an unreadable validity control is not polled (#131).
- Change the UAC2/3 clock with the streaming interface on alt-setting 0,
  then select the streaming alt-setting (#122).
- Resolve the UAC2 playback clock through the streaming terminal's
  `bCSourceID`, Clock Selectors and Clock Multipliers, instead of taking
  the first Clock Source declared (#129).
- Gave clock/endpoint-rate writes and power domain writes typed outcomes
  instead of a bare `Boolean`/ignored result (#111).
- `doReleaseClaim()`'s delayed-release recheck now also accounts for
  `claimAttemptsInFlight`, closing the same race #36 already fixed for
  the synchronous release path (#106).
- Signal a fatal streaming error (e.g. the DAC being unplugged) to the
  caller immediately instead of leaving `isStalled()` to discover it by
  polling, which turned one teardown into a repeating pulse
  (Auxio#51).

### Infrastructure
- One implementation of the set-sample-rate and settle steps, shared by
  activation and playback (#136).
- Software Asynchronous DAC simulator running the real stream engine in
  unit tests (#126).
- Kernel-level USB diagnostics recipe (xHCI trace) in
  `docs/kernel-usb-diagnostics.md` (#125).
- Added test coverage for `UsbIsoStreamEngine`'s feedback-thread
  lifecycle (async-clocked DACs) (#107).
- Fixed detekt violations that accumulated while `CI`/`Release` were
  temporarily disabled, and regenerated the detekt baseline (#115).

## [v0.2.0] - 2026-11-07

### Added
- Properly modeled all isochronous sync types (Synchronous/Adaptive/
  Asynchronous) and added real feedback-clocked async support (#89).
- Added a manual DAC release-control mode, opting out of auto-release-to-
  kernel on pause (#31).
- Kept a silent `AudioTrack` playing during direct USB output, winning
  back hardware media-key routing after another app's session takes it
  (Auxio#41).
- Exposed per-URB packet-error counters to Kotlin diagnostics (#78).

### Fixed
- Cross-thread session/claim state mutations made atomic (#81).
- Parsed endpoint `bInterval`, fixing SuperSpeed/SuperSpeed+ isochronous
  pacing (#82).
- Restricted `UsbAudioController`/`UsbAudioInspector` visibility and
  guarded against a double-claim (#83).
- Removed hardcoded `Toast` calls from `UsbDeviceDiscoveryImpl` (#84).
- Used a 64-bit accumulator for `submitIso`'s packet-length total,
  avoiding overflow on long-running streams (#85).
- Closed a race between starting the stream and setting `activated` (#86).
- `getPositionUs()` now advances on URB completion, not submission (#87).
- Routed real 24-bit-in-32-bit-container content to the device's narrower
  alt-setting instead of its widest (#88).
- No longer frees the native isochronous stream if the streaming thread
  didn't actually stop (#74).
- Hardened the streaming loop against lost URB slots and a busy-spin on
  device loss (#75).
- Restored real hardware volume when a pause's HAL-handoff reclaim was
  silently undone (#29).
- Closed a release/claim-attempt race that could reintroduce a stale max
  volume on resume (#37).
- Resolved the unit test suite's flakiness, root cause and contributing
  leaks (#55).
- Don't discard the padded-24-bit flag on the first `getAudioOutput()`
  call after a seek-triggered flush reuses the same `OutputConfig`
  instance (#101).
- Rebuild the silent direct-playback `AudioTrack` fresh on every
  activation instead of reusing a paused one, fixing media-key routing
  regressing after an app-switch-and-back (#102).
- Protected the native isochronous handle table against a concurrent
  close (#76).
- Fail loudly instead of silently guessing when no device format matches
  (#72).
- Log previously-ignored `DISCARDURB` failures instead of swallowing them
  (#77).
- Resolved `UsbControlWriterImplTest`'s flaky background-executor timing
  race (#80).

### Changed
- Migrated unit tests from JUnit4 to JUnit6 (Jupiter) (#48).
- Gave `UsbAudioController`'s control-transfer functions typed failure
  reasons instead of a bare `Boolean`/`null` (#63).
- Extracted a dedicated `UsbIsoStreamEngine` + `UsbNativeBridge` component
  owning native handle lifetime, the URB loop, and position tracking,
  previously embedded directly in `UsbAudioOutput` (#73).

### Infrastructure
- Added detekt static analysis with a pre-existing-debt baseline (#33).
- Added clang-tidy static analysis (#35).
- Added a PR-triggered CI workflow (#45, #52, #53).
- Filled README onboarding gaps: threading contract, host setup
  requirements, troubleshooting, tested-hardware caveats (#65).
- Removed unused `androidTest` dependencies with no corresponding
  instrumented test source (#66).

## [v0.1.0] - 2026-11-01

Initial tagged release. Extracted from and originally developed/
hardware-tested inside [Auxio](https://github.com/OxygenCobalt/Auxio),
published via JitPack from this tag.

Includes UAC1/2/3 descriptor parsing, per-track dynamic format matching,
high/full-speed-aware isochronous pacing, hardware volume/mute control with
`STREAM_MUSIC` mirroring, per-track HAL fallback routing, the
`UsbDeviceCapabilities` diagnostics snapshot, the built-in blind ABX test
mode, and the `SessionState`-based activation lifecycle.
