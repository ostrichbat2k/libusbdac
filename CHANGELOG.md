# Changelog

All notable changes to this project are documented in this file.

This project is pre-1.0: a breaking API change may land between tags
without a major-version bump -- see "Versioning" in [README.md](README.md).
Pin to an exact tag or commit rather than a version range.

## [Unreleased]

### Added
- Properly modeled all isochronous sync types (Synchronous/Adaptive/
  Asynchronous) and added real feedback-clocked async support (#89).

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

### Changed
- Migrated unit tests from JUnit4 to JUnit6 (Jupiter) (#48).

### Infrastructure
- Added detekt static analysis with a pre-existing-debt baseline (#33).
- Added clang-tidy static analysis (#35).
- Added a PR-triggered CI workflow (#45, #52, #53).

## [v0.1.0] - 2026-11-01

Initial tagged release. Extracted from and originally developed/
hardware-tested inside [Auxio](https://github.com/OxygenCobalt/Auxio),
published via JitPack from this tag.

Includes UAC1/2/3 descriptor parsing, per-track dynamic format matching,
high/full-speed-aware isochronous pacing, hardware volume/mute control with
`STREAM_MUSIC` mirroring, per-track HAL fallback routing, the
`UsbDeviceCapabilities` diagnostics snapshot, the built-in blind ABX test
mode, and the `SessionState`-based activation lifecycle.
