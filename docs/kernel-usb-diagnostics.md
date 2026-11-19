# Kernel-level USB audio diagnostics

libusbdac's own counters only see what the application layer sees: URB status, reap errors,
per-packet status (#78), ring-buffer underruns and, since #123, how close the URB queue came to
running dry. None of them shows what the host controller actually put on the bus. A stream can
look clean at every one of those levels and still click.

That is how [Ma145/decent-player](https://github.com/Ma145/decent-player) found its periodic
pops. Every URB completed with status 0 and the queue never ran dry, but a kernel xHCI trace
showed *short* URBs (312–344 bytes where 352/360 were due), and each one left empty isochronous
microframes. This page adapts their recipe
([`docs/driver/13-usb-audio-diagnostics-reference.md`](https://github.com/Ma145/decent-player/blob/main/docs/driver/13-usb-audio-diagnostics-reference.md),
MIT, © 2026 Marcelo Silva) to libusbdac's stream.

> **Status: not yet run on a real device.** It needs a rooted phone, and none was available when
> this was written (#125). The script's parsing was checked against the kernel's own trace-event
> format (`drivers/usb/host/xhci-trace.h`, `xhci_log_urb`) with synthetic input only. The first
> real capture should confirm the "expected URB sizes" table below against known-good playback.

## Without root (try this first)

- **libusbdac's logcat line every 500 URBs:**

  ```
  UsbIsoStreamEngine: 500 URBs submitted, 0 reap errors, … ; queue 4x8ms, min margin …us, ran dry 0x, max pacing wait …us
  ```

  `ran dry` above 0 means a resubmit came later than the queued audio lasted: there was a gap on
  the bus that nothing else reports (#123). `max pacing wait` growing over a long session would
  point at the wall-clock pacing drifting against the bus clock.
- **`UsbPipelineStats`**, which the host app gets from `UsbAudioManager.pipelineStats`:
  - `badPacketCount` / `shortBytesCount`: packets the host controller completed short or failed.
  - `underrunCount`: the decoder didn't keep up.
  - `minQueueMarginUs` / `queueRanDryCount` / `maxPacingWaitUs`: the same queue margins as the
    logcat line above.
- **Device and descriptor information:** `adb shell dumpsys usb` shows the attached device and its
  descriptors. Running `lsusb -v` with the DAC plugged into a Linux desktop shows them in full.

If all of these are clean and the clicks persist, it's time for the trace.

## With root: the xHCI trace

[`scripts/xhci-trace.sh`](scripts/xhci-trace.sh) turns on the `xhci_urb_enqueue` and
`xhci_urb_giveback` trace events, records for a few seconds, keeps the lines for one endpoint,
turns tracing off again and prints three summaries.

```bash
# Start playback first, then:
docs/scripts/xhci-trace.sh ep1out 15 trace.txt
```

The endpoint name is `ep<number><direction>`. For the streaming endpoint it is the data
endpoint's address without the direction bit: address `0x01` is `ep1out`. libusbdac logs it when
the stream opens, as `opened iso stream … endpoint=1`.

### 1. URB sizes

Each URB is 8 ms of audio (4 are kept in flight). Its length is that many frames times the frame
size. When a packet's frame count isn't a whole number, the scheduler carries the remainder, so
the size alternates between the two neighbouring frame counts. Expected lengths, in bytes:

| Rate | 16-bit (2-byte) stereo | 24-bit in 3 bytes, stereo | 24/32-bit in 4 bytes, stereo |
|---|---|---|---|
| 44100 Hz | 1408 / 1412 | 2112 / 2118 | 2816 / 2824 |
| 48000 Hz | 1536 | 2304 | 3072 |
| 88200 Hz | 2820 / 2824 | 4230 / 4236 | 5640 / 5648 |
| 96000 Hz | 3072 | 4608 | 6144 |
| 176400 Hz | 5644 / 5648 | 8466 / 8472 | 11288 / 11296 |
| 192000 Hz | 6144 | 9216 | 12288 |
| 352800 Hz | 11288 / 11292 | 16932 / 16938 | 22576 / 22584 |
| 384000 Hz | 12288 | 18432 | 24576 |

An Asynchronous DAC adjusts the rate through its feedback endpoint, so its sizes wander by a
frame or so around these. Two things are worth flagging:
- A giveback whose transferred length is less than its requested length (`1300/1412`): a short
  URB.
- Sizes well away from the table: the wrong rate or frame size, or a different queue shape (an
  experiment build using `UsbDacPreferences.urbQueueOverride`).

### 2. Enqueue gaps

The script lists every gap over 20 ms between two submissions. With 4 × 8 ms in flight, one URB
being reaped leaves 24 ms queued. A gap longer than that means the device ran out of data. That's
the same event #123's `ran dry` counts, seen from the kernel's side.

### 3. Giveback timing

The first intervals between completions should sit at the URB duration, 8 ms, with little
spread. Bursts (several completions close together after a long pause) mean the host controller,
not libusbdac, delayed them.

## Isochronous bandwidth: `/sys/kernel/debug/usb/devices` (root)

```bash
adb shell "su -c 'cat /sys/kernel/debug/usb/devices'"
```

The DAC's `I:` lines show which alt-setting each interface is on (`Alt=`). While streaming, the
streaming interface should be on the one logcat's `setInterface(alt=…)` line names, and on 0
when idle. It's a check that the device really is where libusbdac thinks it is.

The `B:` line's `#Iso=` count is **not** useful on a phone, despite older guides suggesting it.
Only the UHCI, OHCI and dwc2 host drivers maintain it (`bandwidth_isoc_reqs`; checked at kernel
v7.3). xHCI, which every current phone uses, leaves it at 0 whatever is streaming. Use the trace
above instead.

## Credits

- [Ma145/decent-player](https://github.com/Ma145/decent-player) (MIT): the xHCI trace recipe and
  its three analyses, adapted here to libusbdac's URB shape.
- Linux kernel `drivers/usb/host/xhci-trace.h` (GPL-2.0, read at v7.3): the trace event format
  the script parses.
