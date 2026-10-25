// In-process capture of this test build's __android_log_print stub output (see
// android_log_stub/android/log.h for why this exists instead of redirecting the real fd 2).
#pragma once

#include <string>

namespace usb_native_test {

inline std::string* g_logCapture = nullptr;

// RAII capture scope: construct to start capturing LOGE output into `out`, destruct (or let it
// go out of scope) to stop. `out` must outlive the scope.
class LogCapture {
public:
    explicit LogCapture(std::string* out) { g_logCapture = out; }
    ~LogCapture() { g_logCapture = nullptr; }
    LogCapture(const LogCapture&) = delete;
    LogCapture& operator=(const LogCapture&) = delete;
};

}  // namespace usb_native_test
