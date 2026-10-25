// Host-test-only stand-in for the NDK's <android/log.h>, which usb_native.cpp depends on but
// which doesn't exist off-device. Just enough to satisfy usb_native.cpp's own usage
// (__android_log_print + ANDROID_LOG_ERROR) -- prints to stderr instead of logcat so test output
// stays visible without adb.
#pragma once

#include <cstdio>
#include <cstdarg>

#define ANDROID_LOG_ERROR 6

inline int __android_log_print(int /*prio*/, const char* tag, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    fprintf(stderr, "[%s] ", tag);
    int ret = vfprintf(stderr, fmt, args);
    fprintf(stderr, "\n");
    va_end(args);
    return ret;
}
