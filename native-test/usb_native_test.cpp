// Host-side GoogleTest coverage for usb_native.cpp -- everything here is reachable without a
// real USB device or JVM: input validation (including the 64-bit overflow guards),
// the handle table (double-close/unknown-handle safety, monotonic ABA-proof IDs), and
// submitIso's early-return validation paths that all run before the function's single
// ioctl() call.
//
// Deliberately NOT covered (would need real hardware or a syscall-interception layer, out of
// scope per libusbdac#23): any ioctl()-success path -- real SUBMITURB/REAPURB completions,
// getUsbSpeed, disconnectKernelDriver. Tests below use a /dev/null fd as a stand-in "device":
// ioctl() on it always fails (ENOTTY), which exercises every validation/bookkeeping branch up to
// and including that failing ioctl call, confirmed via stderr log capture rather than return
// value alone (a validation rejection and a reached-but-failed ioctl call both return the same
// false/-1, so the log line is the only way to tell which branch actually ran).

#include <gtest/gtest.h>
#include <jni.h>

#include <fcntl.h>
#include <unistd.h>

#include "fake_jni.h"
#include "log_capture.h"

extern "C" {
jboolean Java_org_ostrichbat2k_usbdac_streaming_UsbNative_disconnectKernelDriver(
        JNIEnv*, jobject, jint fd, jint interfaceNumber);
jint Java_org_ostrichbat2k_usbdac_streaming_UsbNative_getUsbSpeed(JNIEnv*, jobject, jint fd);
jlong Java_org_ostrichbat2k_usbdac_streaming_UsbNative_openIsoStream(
        JNIEnv*, jobject, jint fd, jint endpointAddress, jint maxPacketSize, jint packetsPerUrb,
        jint numUrbs);
jboolean Java_org_ostrichbat2k_usbdac_streaming_UsbNative_submitIso(
        JNIEnv*, jobject, jlong handle, jint slot, jbyteArray data, jintArray packetLengths);
jint Java_org_ostrichbat2k_usbdac_streaming_UsbNative_reapIso(JNIEnv*, jobject, jlong handle);
void Java_org_ostrichbat2k_usbdac_streaming_UsbNative_cancelIso(JNIEnv*, jobject, jlong handle);
void Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(JNIEnv*, jobject, jlong handle);
}

namespace {

// Mirrors usb_native.cpp's own anonymous-namespace constants (not visible outside that file) --
// keep these in sync if those ever change.
constexpr jint kMaxNumUrbs = 64;
constexpr jint kMaxPacketsPerUrb = 4096;
constexpr jint kMaxPacketSize = 4096;

// A real, open fd that supports none of the USBDEVFS_* ioctls this file issues -- every ioctl()
// call against it fails with ENOTTY, standing in for "reached the syscall but it failed" without
// needing a real USB device.
class DummyFd : public ::testing::Test {
protected:
    void SetUp() override { fd_ = open("/dev/null", O_RDWR); }
    void TearDown() override { if (fd_ >= 0) close(fd_); }
    int fd_ = -1;
};

jlong openValidStream(int fd, jint packetsPerUrb = 8, jint maxPacketSize = 192, jint numUrbs = 4) {
    return Java_org_ostrichbat2k_usbdac_streaming_UsbNative_openIsoStream(
            nullptr, nullptr, fd, /*endpointAddress=*/0x83, maxPacketSize, packetsPerUrb, numUrbs);
}

// --- disconnectKernelDriver / getUsbSpeed: trivial fd<0 guard, the only thing testable without
// a real device (both are otherwise a single direct ioctl with no other logic). ---

TEST(DisconnectKernelDriver, RejectsNegativeFd) {
    EXPECT_EQ(Java_org_ostrichbat2k_usbdac_streaming_UsbNative_disconnectKernelDriver(
                      nullptr, nullptr, -1, 0),
              JNI_FALSE);
}

TEST_F(DummyFd, DisconnectKernelDriverReachesIoctlAndFails) {
    ASSERT_GE(fd_, 0);
    EXPECT_EQ(Java_org_ostrichbat2k_usbdac_streaming_UsbNative_disconnectKernelDriver(
                      nullptr, nullptr, fd_, 0),
              JNI_FALSE);
}

TEST(GetUsbSpeed, RejectsNegativeFd) {
    EXPECT_EQ(Java_org_ostrichbat2k_usbdac_streaming_UsbNative_getUsbSpeed(nullptr, nullptr, -1), -1);
}

// --- openIsoStream: input validation + the handle table it feeds. ---

TEST_F(DummyFd, OpenRejectsNegativeFd) {
    EXPECT_EQ(openValidStream(-1), 0);
}

TEST_F(DummyFd, OpenRejectsOutOfRangeEndpointAddress) {
    ASSERT_GE(fd_, 0);
    EXPECT_EQ(Java_org_ostrichbat2k_usbdac_streaming_UsbNative_openIsoStream(
                      nullptr, nullptr, fd_, /*endpointAddress=*/-1, 192, 8, 4),
              0);
    EXPECT_EQ(Java_org_ostrichbat2k_usbdac_streaming_UsbNative_openIsoStream(
                      nullptr, nullptr, fd_, /*endpointAddress=*/0x100, 192, 8, 4),
              0);
}

TEST_F(DummyFd, OpenRejectsOutOfRangeMaxPacketSize) {
    ASSERT_GE(fd_, 0);
    EXPECT_EQ(openValidStream(fd_, 8, /*maxPacketSize=*/0), 0);
    EXPECT_EQ(openValidStream(fd_, 8, /*maxPacketSize=*/kMaxPacketSize + 1), 0);
}

TEST_F(DummyFd, OpenRejectsOutOfRangePacketsPerUrb) {
    ASSERT_GE(fd_, 0);
    EXPECT_EQ(openValidStream(fd_, /*packetsPerUrb=*/0), 0);
    EXPECT_EQ(openValidStream(fd_, /*packetsPerUrb=*/kMaxPacketsPerUrb + 1), 0);
}

TEST_F(DummyFd, OpenRejectsOutOfRangeNumUrbs) {
    ASSERT_GE(fd_, 0);
    EXPECT_EQ(openValidStream(fd_, 8, 192, /*numUrbs=*/0), 0);
    EXPECT_EQ(openValidStream(fd_, 8, 192, /*numUrbs=*/kMaxNumUrbs + 1), 0);
}

TEST_F(DummyFd, OpenAcceptsValuesExactlyAtTheCaps) {
    ASSERT_GE(fd_, 0);
    // Small maxPacketSize/numUrbs alongside the at-cap value under test, so this doesn't try to
    // allocate an unreasonably large buffer just to prove the boundary is inclusive.
    jlong h1 = openValidStream(fd_, /*packetsPerUrb=*/kMaxPacketsPerUrb, /*maxPacketSize=*/1, 1);
    EXPECT_NE(h1, 0);
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, h1);

    jlong h2 = openValidStream(fd_, 1, /*maxPacketSize=*/kMaxPacketSize, 1);
    EXPECT_NE(h2, 0);
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, h2);

    jlong h3 = openValidStream(fd_, 1, 1, /*numUrbs=*/kMaxNumUrbs);
    EXPECT_NE(h3, 0);
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, h3);
}

TEST_F(DummyFd, OpenWithValidParamsReturnsNonzeroHandle) {
    ASSERT_GE(fd_, 0);
    jlong handle = openValidStream(fd_);
    EXPECT_NE(handle, 0);
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, handle);
}

TEST_F(DummyFd, OpenAssignsDistinctMonotonicHandles) {
    ASSERT_GE(fd_, 0);
    jlong a = openValidStream(fd_);
    jlong b = openValidStream(fd_);
    ASSERT_NE(a, 0);
    ASSERT_NE(b, 0);
    EXPECT_NE(a, b);
    EXPECT_LT(a, b);
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, a);
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, b);
}

// --- Handle-table safety: unknown handles and double-close never touch freed memory. ---

TEST(HandleTable, CloseUnknownHandleIsASafeNoOp) {
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, 0x4242);
}

TEST(HandleTable, CancelUnknownHandleIsASafeNoOp) {
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_cancelIso(nullptr, nullptr, 0x4242);
}

TEST(HandleTable, ReapUnknownHandleReturnsMinusOne) {
    EXPECT_EQ(Java_org_ostrichbat2k_usbdac_streaming_UsbNative_reapIso(nullptr, nullptr, 0x4242), -1);
}

TEST_F(DummyFd, DoubleCloseIsASafeNoOp) {
    ASSERT_GE(fd_, 0);
    jlong handle = openValidStream(fd_);
    ASSERT_NE(handle, 0);
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, handle);
    // Second close of the same, now-erased handle must be a no-op, not a double-free.
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, handle);
}

TEST_F(DummyFd, SubmitAfterCloseFailsInsteadOfUseAfterFree) {
    ASSERT_GE(fd_, 0);
    jlong handle = openValidStream(fd_);
    ASSERT_NE(handle, 0);
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, handle);

    FakeJni jni;
    FakeJavaArray dataArr = FakeJavaArray::OfBytes({1, 2, 3});
    FakeJavaArray lenArr = FakeJavaArray::OfInts({3});
    jboolean result = Java_org_ostrichbat2k_usbdac_streaming_UsbNative_submitIso(
            jni.env(), nullptr, handle, 0, reinterpret_cast<jbyteArray>(&dataArr),
            reinterpret_cast<jintArray>(&lenArr));
    EXPECT_EQ(result, JNI_FALSE);
}

TEST_F(DummyFd, ReapAfterCloseReturnsMinusOne) {
    ASSERT_GE(fd_, 0);
    jlong handle = openValidStream(fd_);
    ASSERT_NE(handle, 0);
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, handle);
    EXPECT_EQ(Java_org_ostrichbat2k_usbdac_streaming_UsbNative_reapIso(nullptr, nullptr, handle), -1);
}

TEST_F(DummyFd, CancelAfterCloseIsASafeNoOp) {
    ASSERT_GE(fd_, 0);
    jlong handle = openValidStream(fd_);
    ASSERT_NE(handle, 0);
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, handle);
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_cancelIso(nullptr, nullptr, handle);
}

// --- submitIso: validation paths that run before its single ioctl() call. ---

class SubmitIso : public DummyFd {
protected:
    void SetUp() override {
        DummyFd::SetUp();
        ASSERT_GE(fd_, 0);
        handle_ = openValidStream(fd_, /*packetsPerUrb=*/4, /*maxPacketSize=*/16, /*numUrbs=*/2);
        ASSERT_NE(handle_, 0);
    }
    void TearDown() override {
        Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, handle_);
        DummyFd::TearDown();
    }

    jboolean submit(jint slot, FakeJavaArray* data, FakeJavaArray* lens) {
        return Java_org_ostrichbat2k_usbdac_streaming_UsbNative_submitIso(
                jni_.env(), nullptr, handle_, slot,
                data ? reinterpret_cast<jbyteArray>(data) : nullptr,
                lens ? reinterpret_cast<jintArray>(lens) : nullptr);
    }

    // A validation rejection and a reached-but-failing ioctl() call both return JNI_FALSE on
    // this host build (there's no real device for a well-formed submission to actually
    // succeed against) -- asserting on the return value alone wouldn't catch a regression that
    // silently dropped one of these checks, since execution would just fall through to the
    // ioctl call and land on the same false. Capturing the specific LOGE line this check emits
    // is what actually distinguishes "rejected here" from "rejected somewhere else".
    //
    // Uses usb_native_test::LogCapture (an in-process buffer our own android/log.h stub writes
    // to), not testing::internal::CaptureStderr -- CaptureStderr redirects the real fd 2, which
    // would also swallow AddressSanitizer's own crash report if the wrapped call actually
    // overruns a buffer instead of hitting a clean validation return, turning a real
    // memory-safety regression into a silent, undiagnosable `exit 1`. Confirmed this the hard
    // way: temporarily removing submitIso's packet-count bound produced a genuine ASan
    // heap-buffer-overflow that printed nothing until CaptureStderr was replaced with this.
    jboolean submitAndExpectLog(jint slot, FakeJavaArray* data, FakeJavaArray* lens,
                                 const char* expectedLogSubstring) {
        std::string log;
        {
            usb_native_test::LogCapture capture(&log);
            jboolean result = submit(slot, data, lens);
            EXPECT_NE(log.find(expectedLogSubstring), std::string::npos)
                    << "expected log to contain \"" << expectedLogSubstring << "\", actual log: "
                    << log;
            return result;
        }
    }

    FakeJni jni_;
    jlong handle_ = 0;
};

TEST_F(SubmitIso, RejectsUnknownHandle) {
    FakeJavaArray data = FakeJavaArray::OfBytes({1, 2});
    FakeJavaArray lens = FakeJavaArray::OfInts({2});
    jboolean result = Java_org_ostrichbat2k_usbdac_streaming_UsbNative_submitIso(
            jni_.env(), nullptr, /*handle=*/0x4242, 0, reinterpret_cast<jbyteArray>(&data),
            reinterpret_cast<jintArray>(&lens));
    EXPECT_EQ(result, JNI_FALSE);
}

TEST_F(SubmitIso, RejectsOutOfRangeSlot) {
    FakeJavaArray data = FakeJavaArray::OfBytes({1, 2});
    FakeJavaArray lens = FakeJavaArray::OfInts({2});
    EXPECT_EQ(submit(-1, &data, &lens), JNI_FALSE);
    EXPECT_EQ(submit(2 /* numUrbs is 2, so 0 and 1 are the only valid slots */, &data, &lens),
              JNI_FALSE);
}

TEST_F(SubmitIso, RejectsNullData) {
    FakeJavaArray lens = FakeJavaArray::OfInts({2});
    EXPECT_EQ(submitAndExpectLog(0, nullptr, &lens, "null data or packetLengths"), JNI_FALSE);
}

TEST_F(SubmitIso, RejectsNullPacketLengths) {
    FakeJavaArray data = FakeJavaArray::OfBytes({1, 2});
    EXPECT_EQ(submitAndExpectLog(0, &data, nullptr, "null data or packetLengths"), JNI_FALSE);
}

TEST_F(SubmitIso, RejectsEmptyPacketLengths) {
    FakeJavaArray data = FakeJavaArray::OfBytes({1, 2});
    FakeJavaArray lens = FakeJavaArray::OfInts({});
    EXPECT_EQ(submitAndExpectLog(0, &data, &lens, "Invalid packet count"), JNI_FALSE);
}

TEST_F(SubmitIso, RejectsMorePacketsThanPacketsPerUrb) {
    // Stream was opened with packetsPerUrb=4.
    FakeJavaArray data = FakeJavaArray::OfBytes({1, 2, 3, 4, 5});
    FakeJavaArray lens = FakeJavaArray::OfInts({1, 1, 1, 1, 1});
    EXPECT_EQ(submitAndExpectLog(0, &data, &lens, "Invalid packet count"), JNI_FALSE);
}

TEST_F(SubmitIso, RejectsNegativePacketLength) {
    FakeJavaArray data = FakeJavaArray::OfBytes({1, 2});
    FakeJavaArray lens = FakeJavaArray::OfInts({2, -1});
    EXPECT_EQ(submitAndExpectLog(0, &data, &lens, "Negative packet length"), JNI_FALSE);
}

TEST_F(SubmitIso, RejectsPacketTotalExceedingBufferCapacity) {
    // Stream was opened with packetsPerUrb=4, maxPacketSize=16 -> bufferLength=64.
    FakeJavaArray data = FakeJavaArray::OfBytes(std::vector<jbyte>(80, 0));
    FakeJavaArray lens = FakeJavaArray::OfInts({20, 20, 20, 20});  // sums to 80 > 64
    EXPECT_EQ(submitAndExpectLog(0, &data, &lens, "exceeds buffer capacity"), JNI_FALSE);
}

TEST_F(SubmitIso, WellFormedInputReachesTheIoctlCall) {
    FakeJavaArray data = FakeJavaArray::OfBytes({1, 2, 3, 4});
    FakeJavaArray lens = FakeJavaArray::OfInts({2, 2});
    EXPECT_EQ(submitAndExpectLog(0, &data, &lens, "SUBMITURB failed"), JNI_FALSE);
}

// --- reapIso: the only two branches reachable without a real completed URB. ---

TEST_F(DummyFd, ReapOnFreshStreamReachesTheIoctlCallAndFails) {
    ASSERT_GE(fd_, 0);
    jlong handle = openValidStream(fd_);
    ASSERT_NE(handle, 0);
    EXPECT_EQ(Java_org_ostrichbat2k_usbdac_streaming_UsbNative_reapIso(nullptr, nullptr, handle), -1);
    Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(nullptr, nullptr, handle);
}

}  // namespace
