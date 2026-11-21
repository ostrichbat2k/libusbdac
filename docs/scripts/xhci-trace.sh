#!/usr/bin/env bash
# Capture and summarise an xHCI trace of a libusbdac playback stream (#125).
#
# Needs a ROOTED device (su), adb on PATH, and a kernel with the xhci-hcd trace events.
# Adapted from Ma145/decent-player's docs/driver/13-usb-audio-diagnostics-reference.md (MIT,
# Copyright (c) 2026 Marcelo Silva). Not yet run against a real device -- see
# docs/kernel-usb-diagnostics.md.
#
# Usage: xhci-trace.sh <endpoint, e.g. ep1out> [seconds, default 15] [output file, default trace.txt]
#   Start playback first; the capture covers whatever is playing during those seconds.
set -euo pipefail

ep="${1:?usage: $0 <endpoint, e.g. ep1out> [seconds] [output file]}"
seconds="${2:-15}"
out="${3:-trace.txt}"
tracing=/sys/kernel/debug/tracing

adb shell "su -c '
echo 0 > $tracing/tracing_on
echo > $tracing/trace
echo 1 > $tracing/events/xhci-hcd/xhci_urb_enqueue/enable
echo 1 > $tracing/events/xhci-hcd/xhci_urb_giveback/enable
echo 32768 > $tracing/buffer_size_kb
echo 1 > $tracing/tracing_on
'"
echo "Tracing for ${seconds}s..."
sleep "$seconds"
adb shell "su -c 'echo 0 > $tracing/tracing_on'"
adb shell "su -c 'cat $tracing/trace'" | grep -F "$ep" > "$out" || true
adb shell "su -c '
echo 0 > $tracing/events/xhci-hcd/xhci_urb_enqueue/enable
echo 0 > $tracing/events/xhci-hcd/xhci_urb_giveback/enable
'"
echo "$(wc -l < "$out") trace lines for $ep in $out"

# The event's timestamp is the field ending in ':' just before the event name ("1234.567890:").
timestamps() { grep -F "$1" "$out" | grep -oE '[0-9]+\.[0-9]+: xhci_urb_' | cut -d: -f1; }

echo
echo "== URB sizes at giveback (bytes actually transferred / requested), most common first"
grep -F "giveback" "$out" | grep -oE 'length [0-9]+/[0-9]+' | cut -d' ' -f2 | sort | uniq -c | sort -rn | head -20

echo
echo "== Enqueue gaps over 20 ms (the streaming thread stalled; a 4 x 8 ms queue has 24 ms to spare)"
timestamps enqueue | awk 'NR > 1 { d = ($1 - prev) * 1000; if (d > 20) printf "gap %.1f ms at %s\n", d, $1 } { prev = $1 }'

echo
echo "== Giveback intervals, first 20 (expect the URB duration, 8 ms, with little spread)"
timestamps giveback | head -21 | awk 'NR > 1 { printf "%.3f ms\n", ($1 - prev) * 1000 } { prev = $1 }'
