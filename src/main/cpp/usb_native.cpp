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
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <mutex>
#include <new>
#include <sys/ioctl.h>
#include <unordered_map>

#define LOG_TAG "UsbNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_org_ostrichbat2k_usbdac_UsbNative_disconnectKernelDriver(
        JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jint interfaceNumber) {
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
Java_org_ostrichbat2k_usbdac_UsbNative_getUsbSpeed(JNIEnv* /*env*/, jobject /*thiz*/, jint fd) {
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
// This device streams in *synchronous* isochronous mode (its clock is locked to USB's
// own SOF timing, confirmed via descriptor parsing), so every packet is exactly
// wMaxPacketSize bytes -- no per-packet size variation for clock-drift compensation is
// needed, unlike asynchronous/adaptive mode.

struct IsoStream {
    int fd;
    unsigned char endpoint;
    int packetsPerUrb;
    int numUrbs;
    unsigned int bufferLength;
    struct usbdevfs_urb** urbs;
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

IsoStream* lookupStream(jlong handle) {
    std::lock_guard<std::mutex> lock(g_streamsMutex);
    auto it = g_streams.find((int64_t) handle);
    return it != g_streams.end() ? it->second : nullptr;
}
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
Java_org_ostrichbat2k_usbdac_UsbNative_openIsoStream(
        JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jint endpointAddress, jint maxPacketSize,
        jint packetsPerUrb, jint numUrbs) {
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
        memset(stream->urbs, 0, sizeof(struct usbdevfs_urb*) * (size_t) numUrbs);

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
            urb->usercontext = (void*) (intptr_t) i;
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
Java_org_ostrichbat2k_usbdac_UsbNative_submitIso(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint slot, jbyteArray data,
        jintArray packetLengths) {
    IsoStream* stream = lookupStream(handle);
    if (!stream || slot < 0 || slot >= stream->numUrbs) return JNI_FALSE;
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
    unsigned int total = 0;
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
        total += (unsigned int) len;
    }
    env->ReleaseIntArrayElements(packetLengths, lengths, JNI_ABORT);
    if (negativeLength) {
        LOGE("Negative packet length in packetLengths for slot %d", slot);
        return JNI_FALSE;
    }

    if (total > stream->bufferLength) {
        LOGE("Packet total %u exceeds buffer capacity %u for slot %d", total, stream->bufferLength, slot);
        return JNI_FALSE;
    }

    jsize dataLen = env->GetArrayLength(data);
    if (dataLen < 0) {
        LOGE("GetArrayLength returned negative length for slot %d", slot);
        return JNI_FALSE;
    }
    unsigned int toCopy = (unsigned int) dataLen < total ? (unsigned int) dataLen : total;
    if (toCopy > 0) {
        env->GetByteArrayRegion(data, 0, (jsize) toCopy, (jbyte*) urb->buffer);
    }
    if (toCopy < total) {
        memset((char*) urb->buffer + toCopy, 0, total - toCopy);
    }

    urb->status = -1;
    urb->actual_length = 0;
    urb->buffer_length = (int) total;
    urb->number_of_packets = (int) numPackets;

    int ret = ioctl(stream->fd, USBDEVFS_SUBMITURB, urb);
    if (ret != 0) {
        LOGE("SUBMITURB failed for slot %d: errno=%d", slot, errno);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Blocks until a submitted URB completes (or is cancelled by closeIsoStream from another
// thread), returning its slot index so the caller can refill and resubmit it. Returns -1
// on error/cancellation.
extern "C" JNIEXPORT jint JNICALL
Java_org_ostrichbat2k_usbdac_UsbNative_reapIso(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    IsoStream* stream = lookupStream(handle);
    if (!stream) return -1;

    struct usbdevfs_urb* reaped = nullptr;
    int ret = ioctl(stream->fd, USBDEVFS_REAPURB, &reaped);
    if (ret != 0 || !reaped) {
        return -1;
    }
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
    if (numPackets < 0 || numPackets > stream->packetsPerUrb) {
        // iso_frame_desc[] was allocated for exactly stream->packetsPerUrb entries (see
        // openIsoStream) -- this should never happen since the kernel only ever reaps a urb we
        // ourselves submitted, but trusting number_of_packets blindly as a loop bound would turn
        // any unexpected kernel/driver value into an out-of-bounds read past the array's end.
        LOGE("Reaped urb slot %ld has implausible number_of_packets=%d (max %d), skipping packet scan",
             (long) (intptr_t) reaped->usercontext, numPackets, stream->packetsPerUrb);
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

    intptr_t slot = (intptr_t) reaped->usercontext;
    if (slot < 0 || slot >= stream->numUrbs) {
        // The caller uses this return value directly as an array index to refill/resubmit --
        // an out-of-range value here must never reach it.
        LOGE("Reaped urb has out-of-range usercontext slot=%ld (numUrbs=%d)",
             (long) slot, stream->numUrbs);
        return -1;
    }
    return (jint) slot;
}

// Shared by cancelIso() and closeIsoStream() -- both need every outstanding URB discarded
// (closeIsoStream's own discard pass is otherwise just cancelIso's loop retyped a dozen lines
// later in this same file).
static void discardAllUrbs(IsoStream* stream) {
    for (int i = 0; i < stream->numUrbs; i++) {
        ioctl(stream->fd, USBDEVFS_DISCARDURB, stream->urbs[i]);
    }
}

// Cancels all outstanding URBs, unblocking any reapIso() call in flight on another thread (the
// streaming loop's reapIso() returns -1 for a cancelled urb). Does NOT touch memory -- it's safe
// to call concurrently with reapIso()/submitIso() on another thread, unlike closeIsoStream.
extern "C" JNIEXPORT void JNICALL
Java_org_ostrichbat2k_usbdac_UsbNative_cancelIso(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    IsoStream* stream = lookupStream(handle);
    if (!stream) return;
    discardAllUrbs(stream);
}

// Drains any remaining completions and frees everything. Only safe once the caller has confirmed
// (e.g. via Thread.join() after cancelIso()) that no other thread is still calling reapIso() or
// submitIso() on this handle -- this frees the same memory those functions read and write.
extern "C" JNIEXPORT void JNICALL
Java_org_ostrichbat2k_usbdac_UsbNative_closeIsoStream(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
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
        g_streams.erase(it);
    }

    // Callers are required to have already called cancelIso() + joined before this (see this
    // function's own doc comment) -- discarding again here is a harmless no-op safety net, not
    // load-bearing, same as the original duplicated loop was.
    discardAllUrbs(stream);
    struct usbdevfs_urb* reaped = nullptr;
    while (ioctl(stream->fd, USBDEVFS_REAPURBNDELAY, &reaped) == 0) {
        // Drain whatever the kernel still has queued so it can free its own bookkeeping.
    }
    freeStream(stream);
}
