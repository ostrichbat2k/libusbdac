// Host-test-only stand-in for the NDK's <android/log.h>, which usb_native.cpp depends on but
// which doesn't exist off-device. Just enough to satisfy usb_native.cpp's own usage
// (__android_log_print + ANDROID_LOG_ERROR) -- prints to stderr instead of logcat so test output
// stays visible without adb, and also appends to an in-process capture buffer tests can inspect
// (usb_native_log_capture.h) to assert on *which* LOGE call fired.
//
// Deliberately does NOT redirect the real fd 2 (unlike testing::internal::CaptureStderr, which
// this file used to rely on via a wrapper) -- that would also swallow AddressSanitizer's own
// crash reports whenever the call under test crashes mid-capture, turning a real memory-safety
// regression into a silent, undiagnosable `exit 1` with no report on the terminal (hit this for
// real: removing submitIso's packet-count bound produced a genuine ASan heap-buffer-overflow
// that printed nothing because it landed inside the then-active CaptureStderr buffer instead of
// the terminal). ASan writes directly to the real fd 2 from its own runtime, bypassing this
// function entirely, so it's unaffected by whatever this buffer is doing.
#pragma once

#include <cstdio>
#include <cstdarg>
#include <string>

#include "../../log_capture.h"

#define ANDROID_LOG_ERROR 6

inline int __android_log_print(int /*prio*/, const char* tag, const char* fmt, ...) {
    char message[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(message, sizeof(message), fmt, args);
    va_end(args);

    fprintf(stderr, "[%s] %s\n", tag, message);
    if (usb_native_test::g_logCapture) {
        *usb_native_test::g_logCapture += message;
        *usb_native_test::g_logCapture += '\n';
    }
    return 0;
}
