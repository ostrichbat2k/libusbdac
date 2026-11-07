// Copyright (c) 2026 libusbdac Project
// usb_native.cpp is part of libusbdac.
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.
//
// Android's public UsbDeviceConnection.claimInterface(force=true) evicts a kernel driver
// from an interface via USBDEVFS_IOCTL/USBDEVFS_DISCONNECT, but the native implementation
// (android_hardware_UsbDeviceConnection_claim_interface in AOSP) always immediately claims
// the interface for the caller right after disconnecting it. The kernel's own
// USBDEVFS_SETCONFIGURATION handler (proc_setconfig in drivers/usb/core/devio.c) then
// refuses the configuration switch if *any* interface is claimed by *anyone*, including the
// calling process itself -- so going through claimInterface first guarantees the switch
// will fail.
//
// This shim issues the disconnect ioctl directly, without ever claiming the interface,
// leaving it in the "no driver bound, nothing claimed" state that proc_setconfig requires.

#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <mutex>
#include <new>
#include <sys/ioctl.h>
#include <thread>
#include <unordered_map>

#define LOG_TAG "UsbNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_org_ostrichbat2k_usbdac_streaming_UsbNative_disconnectKernelDriver(
        JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jint interfaceNumber) {  // NOLINT(bugprone-easily-swappable-parameters)
    if (fd < 0) {
        LOGE("disconnectKernelDriver called with invalid fd %d", fd);
        return JNI_FALSE;
    }
    struct usbdevfs_ioctl ctl {};
    ctl.ifno = interfaceNumber;
    ctl.ioctl_code = USBDEVFS_DISCONNECT;
    ctl.data = nullptr;
    int ret = ioctl(fd, USBDEVFS_IOCTL, &ctl);
    return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

// Returns the negotiated USB_SPEED_* value (0=unknown 1=low 2=full 3=high 4=wireless 5=super
// 6=super+, per linux/usb/ch9.h) for the device at [fd], or -1 on error. Checking this directly
// matters here: this codebase's isochronous pacing (IsoPacketScheduler.kt, and the real-time wait
// in UsbAudioOutput's streaming thread) assumes one iso_frame_desc[] entry == 1ms -- true for
// full-speed, but a high-speed device schedules isochronous packets per *microframe* (125us, 8
// per ms) instead, at the raw usbdevfs_urb level this code talks to directly. Getting this wrong
// would mean every packet we size for "1ms of audio" is actually handed to the bus 8x too
// infrequently relative to its real consumption schedule -- a real candidate for audible
// crackle that would never show up as a USBDEVFS_SUBMITURB/REAPURB-level error, since the
// *aggregate* transfer still completes successfully either way.
extern "C" JNIEXPORT jint JNICALL
Java_org_ostrichbat2k_usbdac_streaming_UsbNative_getUsbSpeed(JNIEnv* /*env*/, jobject /*thiz*/, jint fd) {
    if (fd < 0) {
        LOGE("getUsbSpeed called with invalid fd %d", fd);
        return -1;
    }
    int ret = ioctl(fd, USBDEVFS_GET_SPEED);
    return (jint) ret;
}

// ---------------------------------------------------------------------------
// Isochronous streaming.
//
// android.hardware.usb.UsbRequest -- the only async USB I/O class the public API
// exposes -- rejects isochronous endpoints outright (see usb_request_new() in AOSP's
// system/core/libusbhost/usbhost.c: anything that isn't USB_ENDPOINT_XFER_BULK or
// _INTERRUPT returns NULL). There is no public API path for isochronous transfers at
// all, so this submits/reaps USBDEVFS_URB_TYPE_ISO urbs directly via ioctl, built from
// the same uapi struct layout the kernel itself uses (linux/usbdevice_fs.h).
//
// Every device actually tested so far streams in *synchronous* isochronous mode (its
// clock is locked to USB's own SOF timing, confirmed via descriptor parsing). Packet
// length still varies packet-to-packet by design -- IsoPacketScheduler.kt distributes the
// fractional samples-per-packet a non-integer sample rate implies (e.g. 44.1kHz needs 44
// or 45 samples per 1ms packet) -- but that's nominal-rate rounding, not clock-drift
// compensation: a Synchronous device's own clock already tracks USB's SOF timing exactly,
// so no feedback-driven adjustment to that distribution is needed. An Asynchronous device
// instead reports its own real
// consumption rate back over a dedicated feedback endpoint (UAC 5.12.4.2); submitIsoRead/
// readIsoSlotData below exist for that second, IN-direction stream (#25) -- opened via
// the same openIsoStream, reaped via the same reapIso, cancelled/closed via the same
// cancelIso/closeIsoStream as the main OUT data stream, since none of those four care
// which direction a given handle's endpoint is. Only submission (this comment's whole
// point) and result-readback differ enough to need their own entry points.

struct IsoStream {
    int fd;
    unsigned char endpoint;
    int packetsPerUrb;
    int numUrbs;
    unsigned int bufferLength;
    struct usbdevfs_urb** urbs;
    // Cumulative across this stream's whole lifetime, written by reapIso() on the streaming
    // thread and read by getPacketErrorStats() -- plausibly from a different thread (a host
    // diagnostics screen's own refresh timer), so these need to be atomic, not plain counters.
    // See #78: exposes processReapedUrb's per-URB packet scan beyond just a logcat line.
    std::atomic<int64_t> cumulativeBadPackets{0};
    std::atomic<int64_t> cumulativeShortBytes{0};
    // How many entry points below are currently using this stream, after having found it via
    // acquireStream() -- closeIsoStream() waits for this to drop to zero before freeing anything
    // (#76). Never read/written without going through acquireStream/StreamOpGuard or
    // closeIsoStream's own wait loop.
    std::atomic<int> activeOps{0};
};

// Sane ceilings on caller-supplied sizes. Real usage (see UsbAudioOutput.kt/IsoPacketScheduler.kt)
// is numUrbs=4, packetsPerUrb in {8, 64}, maxPacketSize well under 1024 -- these caps are
// generously above that, but exist so a bad/corrupted caller value can't (a) overflow the 32-bit
// arithmetic that sizes the urb/buffer allocations below, or (b) trigger a multi-gigabyte
// allocation that looks like a hang rather than a clean, logged failure.
namespace {
constexpr jint kMaxNumUrbs = 64;
constexpr jint kMaxPacketsPerUrb = 4096;
constexpr jint kMaxPacketSize = 4096;

// reapIso()'s two failure codes -- mirrored exactly by UsbNative.kt's REAP_ERROR_TRANSIENT/
// REAP_ERROR_FATAL constants (keep them in sync if these ever change). Distinct from each other
// (and from -1 meaning "something went wrong" generically) specifically so the streaming loop in
// UsbAudioOutput.kt can tell "retry immediately, nothing is actually wrong" (an EINTR, the only
// case a wait can be legitimately interrupted without the device or stream being at fault) apart
// from "stop, this stream cannot produce anything more" (anything else -- device unplugged,
// handle closed, or any other ioctl failure) -- see #60.
constexpr jint kReapErrorTransient = -1;
constexpr jint kReapErrorFatal = -2;
}  // namespace

// Handles are opaque integer IDs into this table, never the IsoStream* itself. Two reasons:
// (1) it lets every entry point validate a handle against a live set before touching any memory,
// turning a stale/double-closed/garbage handle from the Kotlin side into a logged no-op instead
// of a use-after-free or double-free; (2) unlike a registry keyed by the raw pointer, a
// monotonically-increasing ID can never collide with a *different*, later stream that the
// allocator happens to place at the same freed address (the classic ABA problem) -- once an ID is
// erased here, it is never reused, regardless of what the heap does with that memory afterward.
// The lock is only ever held for a map lookup/insert/erase, never across any ioctl, so the
// per-URB submit/reap hot path (called every ~1-8ms) pays only an uncontended mutex + hash lookup.
namespace {
std::mutex g_streamsMutex;
std::unordered_map<int64_t, IsoStream*> g_streams;
int64_t g_nextHandle = 1;

// Finds the stream for [handle] and marks it as having one more operation in flight, so
// closeIsoStream (possibly running concurrently on another thread) knows to wait for this call
// to finish touching it before freeing anything (#76). The increment happens under the same
// mutex closeIsoStream erases the handle under, so a handle this returns non-null for can never
// be the one a concurrent closeIsoStream is about to (or already did) erase -- that race is
// resolved entirely by map-lookup ordering, before activeOps ever comes into it. Every non-null
// return must be matched by exactly one release, via [StreamOpGuard] below.
IsoStream* acquireStream(jlong handle) {
    std::lock_guard<std::mutex> lock(g_streamsMutex);
    auto it = g_streams.find((int64_t) handle);
    if (it == g_streams.end()) return nullptr;
    IsoStream* stream = it->second;
    stream->activeOps.fetch_add(1, std::memory_order_acq_rel);
    return stream;
}

// RAII counterpart of acquireStream() -- declare one of these right after every successful
// acquireStream() call, before touching the stream, so the release happens on every return path
// (including an early one) without needing to spell it out at each one (#76).
class StreamOpGuard {
public:
    explicit StreamOpGuard(IsoStream* stream) : stream_(stream) {}
    ~StreamOpGuard() { stream_->activeOps.fetch_sub(1, std::memory_order_acq_rel); }
    StreamOpGuard(const StreamOpGuard&) = delete;
    StreamOpGuard& operator=(const StreamOpGuard&) = delete;

private:
    IsoStream* stream_;
};
}  // namespace

static void freeStream(IsoStream* stream) {
    if (!stream) return;
    if (stream->urbs) {
        for (int i = 0; i < stream->numUrbs; i++) {
            if (stream->urbs[i]) {
                free(stream->urbs[i]->buffer);
                free(stream->urbs[i]);
            }
        }
        delete[] stream->urbs;
    }
    delete stream;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_ostrichbat2k_usbdac_streaming_UsbNative_openIsoStream(
        JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jint endpointAddress, jint maxPacketSize,  // NOLINT(bugprone-easily-swappable-parameters)
        jint packetsPerUrb, jint numUrbs) {  // NOLINT(bugprone-easily-swappable-parameters)
    if (fd < 0) {
        LOGE("openIsoStream called with invalid fd %d", fd);
        return 0;
    }
    if (endpointAddress < 0 || endpointAddress > 0xFF) {
        LOGE("openIsoStream called with invalid endpointAddress %d", endpointAddress);
        return 0;
    }
    if (maxPacketSize <= 0 || maxPacketSize > kMaxPacketSize) {
        LOGE("openIsoStream called with out-of-range maxPacketSize %d", maxPacketSize);
        return 0;
    }
    if (packetsPerUrb <= 0 || packetsPerUrb > kMaxPacketsPerUrb) {
        LOGE("openIsoStream called with out-of-range packetsPerUrb %d", packetsPerUrb);
        return 0;
    }
    if (numUrbs <= 0 || numUrbs > kMaxNumUrbs) {
        LOGE("openIsoStream called with out-of-range numUrbs %d", numUrbs);
        return 0;
    }

    // Every size below is computed in 64-bit before being narrowed, and checked against a sane
    // ceiling -- packetsPerUrb/maxPacketSize/numUrbs are caller-controlled ints, and multiplying
    // them directly as plain int/unsigned int (as the original version did) can silently
    // overflow and wrap into a too-small allocation that the rest of this file would then write
    // past the end of, even though every individual argument passed its own range check above.
    uint64_t bufferLength64 = (uint64_t) packetsPerUrb * (uint64_t) maxPacketSize;
    uint64_t urbSize64 = sizeof(struct usbdevfs_urb) +
            (uint64_t) packetsPerUrb * sizeof(struct usbdevfs_iso_packet_desc);
    if (bufferLength64 == 0 || bufferLength64 > UINT32_MAX || urbSize64 > UINT32_MAX) {
        LOGE("openIsoStream requested implausible buffer sizes (buffer=%llu urb=%llu)",
             (unsigned long long) bufferLength64, (unsigned long long) urbSize64);
        return 0;
    }

    IsoStream* stream = nullptr;
    try {
        // `new` can throw std::bad_alloc under real memory pressure -- letting a C++ exception
        // propagate across the JNI boundary is undefined behavior (the JVM's unwinder doesn't
        // understand it and this app crashes unpredictably rather than failing cleanly), so every
        // throwing call in this function must be inside this try block.
        stream = new IsoStream();
        stream->fd = fd;
        stream->endpoint = (unsigned char) endpointAddress;
        stream->packetsPerUrb = packetsPerUrb;
        stream->numUrbs = numUrbs;
        stream->bufferLength = (unsigned int) bufferLength64;
        stream->urbs = new struct usbdevfs_urb*[numUrbs];
        // new T*[n] does not zero-initialize -- without this, the cleanup paths below (which
        // check `stream->urbs[j]` for null to know what's actually been allocated) would be
        // reading indeterminate pointers instead of a reliable "not yet allocated" sentinel.
        memset(stream->urbs, 0, sizeof(struct usbdevfs_urb*) * (size_t) numUrbs);  // NOLINT(bugprone-multi-level-implicit-pointer-conversion)

        size_t urbSize = (size_t) urbSize64;
        for (int i = 0; i < numUrbs; i++) {
            auto* urb = (struct usbdevfs_urb*) calloc(1, urbSize);
            void* buffer = urb ? malloc(stream->bufferLength) : nullptr;
            if (!urb || !buffer) {
                LOGE("Failed to allocate urb/buffer %d", i);
                free(urb);
                free(buffer);
                freeStream(stream);
                return 0;
            }
            memset(buffer, 0, stream->bufferLength);
            urb->type = USBDEVFS_URB_TYPE_ISO;
            urb->endpoint = stream->endpoint;
            urb->flags = USBDEVFS_URB_ISO_ASAP;
            urb->buffer = buffer;
            urb->usercontext = (void*) (intptr_t) i;  // NOLINT(performance-no-int-to-ptr)
            // iso_frame_desc[].length, buffer_length, and number_of_packets are set per
            // submission (see submitIso) since sample rates that don't divide evenly into the
            // 1ms packet schedule (e.g. 44100 Hz) need a varying number of samples -- and
            // therefore bytes -- per packet, not the fixed maxPacketSize every time.
            stream->urbs[i] = urb;
        }
    } catch (const std::bad_alloc& e) {
        LOGE("openIsoStream: allocation threw: %s", e.what());
        freeStream(stream);
        return 0;
    }

    int64_t id;
    {
        std::lock_guard<std::mutex> lock(g_streamsMutex);
        id = g_nextHandle++;
        g_streams[id] = stream;
    }
    return (jlong) id;
}

// Fills URB [slot] with bytes from [data] (zero-padded if shorter than the total of
// [packetLengths]) and submits it with those per-packet lengths. Returns false if the
// submission itself fails; a successful submission still needs to be reaped later to find out
// how it went.
//
// Per-packet lengths (rather than one fixed size for every packet) are what let this carry
// sample rates like 44100 Hz, where samples/packet isn't a whole number at USB's 1-packet-per-ms
// schedule: the caller distributes 44 or 45 samples per packet (see IsoPacketScheduler.kt) to
// average out to exactly 44100/sec over time.
extern "C" JNIEXPORT jboolean JNICALL
Java_org_ostrichbat2k_usbdac_streaming_UsbNative_submitIso(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint slot, jbyteArray data,  // NOLINT(bugprone-easily-swappable-parameters)
        jintArray packetLengths) {
    IsoStream* stream = acquireStream(handle);
    if (!stream) return JNI_FALSE;
    StreamOpGuard guard(stream);
    if (slot < 0 || slot >= stream->numUrbs) return JNI_FALSE;
    if (!data || !packetLengths) {
        // GetArrayLength/GetByteArrayRegion/GetIntArrayElements on a null jarray is undefined
        // behavior at the JNI boundary, not a Java-level NullPointerException -- this must be
        // checked before any of those are called, not after.
        LOGE("submitIso called with a null data or packetLengths array for slot %d", slot);
        return JNI_FALSE;
    }
    struct usbdevfs_urb* urb = stream->urbs[slot];

    jsize numPackets = env->GetArrayLength(packetLengths);
    if (numPackets <= 0 || numPackets > stream->packetsPerUrb) {
        LOGE("Invalid packet count for slot %d: %d (max %d)", slot, numPackets, stream->packetsPerUrb);
        return JNI_FALSE;
    }

    jint* lengths = env->GetIntArrayElements(packetLengths, nullptr);
    if (!lengths) {
        LOGE("GetIntArrayElements failed for slot %d", slot);
        return JNI_FALSE;
    }
    // A 64-bit accumulator: numPackets can be as large as kMaxPacketsPerUrb (4096) and each len
    // as large as INT_MAX, so summing in a 32-bit unsigned int can overflow and wrap around to an
    // arbitrary small value well before the bufferLength check below ever runs -- defeating the
    // one check that exists specifically to catch an oversized total (#79).
    uint64_t total = 0;
    bool negativeLength = false;
    for (jsize p = 0; p < numPackets && !negativeLength; p++) {
        jint len = lengths[p];
        if (len < 0) {
            // A negative length cast to unsigned would silently become huge below, defeating the
            // bufferLength bounds check that exists specifically to prevent an overflow.
            negativeLength = true;
            break;
        }
        urb->iso_frame_desc[p].length = (unsigned int) len;
        total += (uint64_t) len;
    }
    env->ReleaseIntArrayElements(packetLengths, lengths, JNI_ABORT);
    if (negativeLength) {
        LOGE("Negative packet length in packetLengths for slot %d", slot);
        return JNI_FALSE;
    }

    if (total > (uint64_t) stream->bufferLength) {
        LOGE("Packet total %llu exceeds buffer capacity %u for slot %d", (unsigned long long) total,
             stream->bufferLength, slot);
        return JNI_FALSE;
    }
    // Safe to narrow now: the check above already bounded total by bufferLength, itself a
    // validated unsigned int (see openIsoStream's own UINT32_MAX check).
    unsigned int totalU = (unsigned int) total;

    jsize dataLen = env->GetArrayLength(data);
    if (dataLen < 0) {
        LOGE("GetArrayLength returned negative length for slot %d", slot);
        return JNI_FALSE;
    }
    unsigned int toCopy = (unsigned int) dataLen < totalU ? (unsigned int) dataLen : totalU;
    if (toCopy > 0) {
        env->GetByteArrayRegion(data, 0, (jsize) toCopy, (jbyte*) urb->buffer);
    }
    if (toCopy < totalU) {
        memset((char*) urb->buffer + toCopy, 0, totalU - toCopy);
    }

    urb->status = -1;
    urb->actual_length = 0;
    urb->buffer_length = (int) totalU;
    urb->number_of_packets = (int) numPackets;

    int ret = ioctl(stream->fd, USBDEVFS_SUBMITURB, urb);
    if (ret != 0) {
        LOGE("SUBMITURB failed for slot %d: errno=%d", slot, errno);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Scans a just-reaped (completed) URB's per-packet results and returns the slot index to
// refill/resubmit, or -1 if its usercontext slot is bogus. Pure data processing over an
// already-completed urb -- no I/O of its own, the real ioctl() that produces one happens in
// reapIso() below -- split out specifically so this logic is directly testable with a hand-built
// usbdevfs_urb, without a real USB device ever completing one for real (see
// native-test/usb_native_test.cpp). External linkage (no `static`/anonymous namespace) so that
// test binary can call it directly.
int processReapedUrb(const struct usbdevfs_urb* reaped, int packetsPerUrb, int numUrbs,  // NOLINT(bugprone-easily-swappable-parameters)
                      int* badPacketsOut = nullptr, int64_t* shortBytesOut = nullptr) {
    if (reaped->status != 0) {
        LOGE("Reaped urb slot %ld with status=%d actual_length=%d",
             (long) (intptr_t) reaped->usercontext, reaped->status, reaped->actual_length);
    }

    // The aggregate urb->status above only reflects whether the *submission as a whole* was
    // accepted/retired by the host controller -- for isochronous transfers it can read 0 even
    // when individual packets inside a "successful" URB failed to actually reach the device
    // (each has its own status/actual_length in iso_frame_desc[]). Checking only the aggregate
    // status is exactly the kind of gap that would make a transfer look completely clean while
    // the device received partial or no usable audio data.
    int numPackets = reaped->number_of_packets;
    if (numPackets < 0 || numPackets > packetsPerUrb) {
        // iso_frame_desc[] was allocated for exactly packetsPerUrb entries (see
        // openIsoStream) -- this should never happen since the kernel only ever reaps a urb we
        // ourselves submitted, but trusting number_of_packets blindly as a loop bound would turn
        // any unexpected kernel/driver value into an out-of-bounds read past the array's end.
        LOGE("Reaped urb slot %ld has implausible number_of_packets=%d (max %d), skipping packet scan",
             (long) (intptr_t) reaped->usercontext, numPackets, packetsPerUrb);
        numPackets = 0;
    }
    int badPackets = 0;
    unsigned int totalActual = 0;
    unsigned int totalExpected = 0;
    for (int p = 0; p < numPackets; p++) {
        const auto& desc = reaped->iso_frame_desc[p];
        totalActual += desc.actual_length;
        totalExpected += desc.length;
        if (desc.status != 0 || desc.actual_length != desc.length) {
            if (badPackets < 3) {
                LOGE("Reaped urb slot %ld packet %d: status=%d actual_length=%u expected=%u",
                     (long) (intptr_t) reaped->usercontext, p, desc.status, desc.actual_length,
                     desc.length);
            }
            badPackets++;
        }
    }
    if (badPackets > 0) {
        LOGE("Reaped urb slot %ld: %d/%d packets short/failed, %u/%u bytes actually delivered",
             (long) (intptr_t) reaped->usercontext, badPackets, reaped->number_of_packets,
             totalActual, totalExpected);
    }
    if (badPacketsOut) *badPacketsOut = badPackets;
    if (shortBytesOut) *shortBytesOut = (int64_t) totalExpected - (int64_t) totalActual;

    intptr_t slot = (intptr_t) reaped->usercontext;
    if (slot < 0 || slot >= numUrbs) {
        // The caller uses this return value directly as an array index to refill/resubmit --
        // an out-of-range value here must never reach it. This can only mean the reaped urb
        // isn't one we actually submitted (corrupted usercontext, or a kernel/driver bug) --
        // never transient, so it gets the fatal code like any other unrecoverable reapIso()
        // failure, not the generic -1 this used to return before #60 gave reapIso() distinct
        // failure codes.
        LOGE("Reaped urb has out-of-range usercontext slot=%ld (numUrbs=%d)",
             (long) slot, numUrbs);
        return kReapErrorFatal;
    }
    return (int) slot;
}

// Maps a failed REAPURB's errno to the right reapIso() return code. Split out purely so this
// decision is directly testable without a real interrupted syscall (see
// native-test/usb_native_test.cpp) -- same reasoning as processReapedUrb's own extraction above.
int reapErrorCodeForErrno(int err) {
    // EINTR means the blocking wait was interrupted by a signal, not that anything is actually
    // wrong with the stream or device -- the caller should just call reapIso() again. Every
    // other errno (ENODEV when the device is unplugged, EINVAL/ENOENT/EBADF, anything else)
    // means this stream cannot produce anything more, so it's treated as fatal rather than
    // trying to enumerate every errno that really means "device gone" -- see #60: retrying
    // forever on an unrecognized-but-still-fatal errno is exactly the busy-spin this exists to
    // prevent.
    return err == EINTR ? kReapErrorTransient : kReapErrorFatal;
}

// Blocks until a submitted URB completes (or is cancelled by closeIsoStream from another
// thread), returning its slot index so the caller can refill and resubmit it, or one of
// kReapErrorTransient/kReapErrorFatal (mirrored as UsbNative.REAP_ERROR_TRANSIENT/
// REAP_ERROR_FATAL on the Kotlin side) on failure.
extern "C" JNIEXPORT jint JNICALL
Java_org_ostrichbat2k_usbdac_streaming_UsbNative_reapIso(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    IsoStream* stream = acquireStream(handle);
    if (!stream) return kReapErrorFatal;
    StreamOpGuard guard(stream);

    struct usbdevfs_urb* reaped = nullptr;
    int ret = ioctl(stream->fd, USBDEVFS_REAPURB, &reaped);
    if (ret != 0) {
        // errno is only meaningful right after the call that actually failed -- reading it in a
        // combined `ret != 0 || !reaped` branch would risk logging a stale value from some
        // unrelated earlier call on the rare path where the ioctl itself succeeds but still
        // somehow returns a null urb (handled separately below).
        int err = errno;
        int code = reapErrorCodeForErrno(err);
        if (code == kReapErrorFatal) {
            LOGE("REAPURB failed: errno=%d (%s)", err, strerror(err));
        }
        return code;
    }
    if (!reaped) {
        LOGE("REAPURB succeeded but returned a null urb");
        return kReapErrorFatal;
    }
    int badPackets = 0;
    int64_t shortBytes = 0;
    int slot = processReapedUrb(reaped, stream->packetsPerUrb, stream->numUrbs, &badPackets, &shortBytes);
    if (badPackets > 0) {
        stream->cumulativeBadPackets.fetch_add(badPackets, std::memory_order_relaxed);
        stream->cumulativeShortBytes.fetch_add(shortBytes, std::memory_order_relaxed);
    }
    return (jint) slot;
}

// See #78: lets a host diagnostics screen tell "occasional dropped USB packets" apart from
// decoder underrun/ring-buffer-starved glitches, which otherwise look identical above a logcat
// line. Returns {0, 0} for an unknown/already-closed handle, same as every other getter here.
extern "C" JNIEXPORT jlongArray JNICALL
Java_org_ostrichbat2k_usbdac_streaming_UsbNative_getPacketErrorStats(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    jlongArray result = env->NewLongArray(2);
    IsoStream* stream = acquireStream(handle);
    if (!stream || !result) return result;
    StreamOpGuard guard(stream);
    jlong stats[2] = {
        (jlong) stream->cumulativeBadPackets.load(std::memory_order_relaxed),
        (jlong) stream->cumulativeShortBytes.load(std::memory_order_relaxed)};
    env->SetLongArrayRegion(result, 0, 2, stats);
    return result;
}

// Shared by cancelIso() and closeIsoStream() -- both need every outstanding URB discarded
// (closeIsoStream's own discard pass is otherwise just cancelIso's loop retyped a dozen lines
// later in this same file).
static void discardAllUrbs(IsoStream* stream) {
    for (int i = 0; i < stream->numUrbs; i++) {
        if (ioctl(stream->fd, USBDEVFS_DISCARDURB, stream->urbs[i]) != 0) {
            // EINVAL/ENOENT are expected here, not failures: a given URB may have already
            // completed, already been discarded by an earlier pass, or never been submitted at
            // all. Suppressing only those two keeps a genuinely unexpected error (e.g. the
            // device actually gone) visible instead of silently swallowed -- see #76/#68's own
            // caution around diagnosing why a stream failed to tear down cleanly.
            if (errno != EINVAL && errno != ENOENT) {
                LOGE("DISCARDURB failed for urb %d: errno=%d", i, errno);
            }
        }
    }
}

// Cancels all outstanding URBs, unblocking any reapIso() call in flight on another thread. A
// discarded urb's REAPURB still completes "successfully" (not an ioctl() failure, so not either
// of reapIso()'s own failure codes) with a per-urb status reflecting the cancellation -- the
// streaming loop doesn't need to tell that apart from a normal completion because it always
// checks its own `streaming` flag (already false by the time this is called, see
// UsbAudioOutput.stopStreamingThread) right after reapIso() returns, regardless of what it
// returns. Does NOT touch memory -- it's safe to call concurrently with reapIso()/submitIso() on
// another thread, unlike closeIsoStream.
extern "C" JNIEXPORT void JNICALL
Java_org_ostrichbat2k_usbdac_streaming_UsbNative_cancelIso(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    IsoStream* stream = acquireStream(handle);
    if (!stream) return;
    StreamOpGuard guard(stream);
    discardAllUrbs(stream);
}

// Drains any remaining completions and frees everything. Only safe once the caller has confirmed
// (e.g. via Thread.join() after cancelIso()) that no other thread is still calling reapIso() or
// submitIso() on this handle -- this frees the same memory those functions read and write.
extern "C" JNIEXPORT void JNICALL
Java_org_ostrichbat2k_usbdac_streaming_UsbNative_closeIsoStream(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    IsoStream* stream;
    {
        std::lock_guard<std::mutex> lock(g_streamsMutex);
        auto it = g_streams.find((int64_t) handle);
        if (it == g_streams.end()) {
            // Erasing the handle from the table before anything below frees memory is what
            // makes a double-close (or a call with a garbage/already-closed handle) a safe,
            // logged no-op instead of a double-free -- the second call simply never finds it.
            LOGE("closeIsoStream called with an unknown or already-closed handle %lld",
                 (long long) handle);
            return;
        }
        stream = it->second;
        // Erasing now, under the same mutex acquireStream() takes for its own lookup, is what
        // stops any *new* call from ever getting a pointer to this stream again -- see
        // acquireStream's own doc comment. Anything that already got one, racing in on another
        // thread just before this, is covered by the activeOps wait below instead (#76).
        g_streams.erase(it);
    }

    // Callers are required to have already called cancelIso() + joined before this (see this
    // function's own doc comment), so activeOps is already expected to be 0 by the time the wait
    // below runs. This discard pass and that wait exist purely as hardening against a future
    // caller or bug that violates that contract (#76) -- not the normal path, but if it ever
    // happens, this still unblocks a reapIso() call stuck in REAPURB on another thread instead of
    // freeing the memory out from under it.
    discardAllUrbs(stream);
    while (stream->activeOps.load(std::memory_order_acquire) != 0) {
        std::this_thread::yield();
    }

    struct usbdevfs_urb* reaped = nullptr;
    while (ioctl(stream->fd, USBDEVFS_REAPURBNDELAY, &reaped) == 0) {
        // Drain whatever the kernel still has queued so it can free its own bookkeeping.
    }
    freeStream(stream);
}

// The feedback-endpoint (#25) counterpart of submitIso: same validation shape (lookup, slot
// bounds, per-packet-length overflow check against bufferLength), but this is a read (IN)
// submission -- there is no [data] to copy in, so the buffer is zeroed instead, exactly like
// openIsoStream zeroes a freshly-allocated buffer, so a short/failed read never exposes
// leftover bytes from whatever this URB last carried.
extern "C" JNIEXPORT jboolean JNICALL
Java_org_ostrichbat2k_usbdac_streaming_UsbNative_submitIsoRead(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint slot, jintArray packetLengths) {
    IsoStream* stream = acquireStream(handle);
    if (!stream) return JNI_FALSE;
    StreamOpGuard guard(stream);
    if (slot < 0 || slot >= stream->numUrbs) return JNI_FALSE;
    if (!packetLengths) {
        LOGE("submitIsoRead called with a null packetLengths array for slot %d", slot);
        return JNI_FALSE;
    }
    struct usbdevfs_urb* urb = stream->urbs[slot];

    jsize numPackets = env->GetArrayLength(packetLengths);
    if (numPackets <= 0 || numPackets > stream->packetsPerUrb) {
        LOGE("Invalid packet count for slot %d: %d (max %d)", slot, numPackets, stream->packetsPerUrb);
        return JNI_FALSE;
    }

    jint* lengths = env->GetIntArrayElements(packetLengths, nullptr);
    if (!lengths) {
        LOGE("GetIntArrayElements failed for slot %d", slot);
        return JNI_FALSE;
    }
    // Same 64-bit-accumulator overflow reasoning as submitIso's identical loop (#79).
    uint64_t total = 0;
    bool negativeLength = false;
    for (jsize p = 0; p < numPackets && !negativeLength; p++) {
        jint len = lengths[p];
        if (len < 0) {
            negativeLength = true;
            break;
        }
        urb->iso_frame_desc[p].length = (unsigned int) len;
        total += (uint64_t) len;
    }
    env->ReleaseIntArrayElements(packetLengths, lengths, JNI_ABORT);
    if (negativeLength) {
        LOGE("Negative packet length in packetLengths for slot %d", slot);
        return JNI_FALSE;
    }

    if (total > (uint64_t) stream->bufferLength) {
        LOGE("Packet total %llu exceeds buffer capacity %u for slot %d", (unsigned long long) total,
             stream->bufferLength, slot);
        return JNI_FALSE;
    }
    unsigned int totalU = (unsigned int) total;
    memset(urb->buffer, 0, totalU);

    urb->status = -1;
    urb->actual_length = 0;
    urb->buffer_length = (int) totalU;
    urb->number_of_packets = (int) numPackets;

    int ret = ioctl(stream->fd, USBDEVFS_SUBMITURB, urb);
    if (ret != 0) {
        LOGE("SUBMITURB (read) failed for slot %d: errno=%d", slot, errno);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Copies a just-reaped feedback URB's own payload back to the Kotlin side (#25) -- the first
// code path in this file that ever reads URB buffer contents back out; processReapedUrb/reapIso
// only ever inspect actual_length/status for the main audio data path, since that path's actual
// bytes went the other direction (host to device) and were never meant to be read back.
extern "C" JNIEXPORT jint JNICALL
Java_org_ostrichbat2k_usbdac_streaming_UsbNative_readIsoSlotData(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint slot, jbyteArray dst) {
    IsoStream* stream = acquireStream(handle);
    if (!stream) return -1;
    StreamOpGuard guard(stream);
    if (slot < 0 || slot >= stream->numUrbs || !dst) return -1;
    struct usbdevfs_urb* urb = stream->urbs[slot];
    if (urb->actual_length < 0) return -1;

    jsize dstLen = env->GetArrayLength(dst);
    if (dstLen < 0) return -1;
    unsigned int available = (unsigned int) urb->actual_length;
    unsigned int toCopy = (unsigned int) dstLen < available ? (unsigned int) dstLen : available;
    if (toCopy > 0) {
        env->SetByteArrayRegion(dst, 0, (jsize) toCopy, (jbyte*) urb->buffer);
    }
    return (jint) toCopy;
}
