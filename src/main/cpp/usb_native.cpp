// Copyright (c) 2026 Auxio Project
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
#include <sys/ioctl.h>

#define LOG_TAG "UsbNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_org_oxycblt_auxio_usb_UsbNative_disconnectKernelDriver(
        JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jint interfaceNumber) {
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
Java_org_oxycblt_auxio_usb_UsbNative_getUsbSpeed(JNIEnv* /*env*/, jobject /*thiz*/, jint fd) {
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

extern "C" JNIEXPORT jlong JNICALL
Java_org_oxycblt_auxio_usb_UsbNative_openIsoStream(
        JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jint endpointAddress, jint maxPacketSize,
        jint packetsPerUrb, jint numUrbs) {
    auto* stream = new IsoStream();
    stream->fd = fd;
    stream->endpoint = (unsigned char) endpointAddress;
    stream->packetsPerUrb = packetsPerUrb;
    stream->numUrbs = numUrbs;
    stream->bufferLength = (unsigned int) (packetsPerUrb * maxPacketSize);
    stream->urbs = new struct usbdevfs_urb*[numUrbs];

    size_t urbSize = sizeof(struct usbdevfs_urb) +
            (size_t) packetsPerUrb * sizeof(struct usbdevfs_iso_packet_desc);

    for (int i = 0; i < numUrbs; i++) {
        auto* urb = (struct usbdevfs_urb*) calloc(1, urbSize);
        void* buffer = urb ? malloc(stream->bufferLength) : nullptr;
        if (!urb || !buffer) {
            LOGE("Failed to allocate urb/buffer %d", i);
            free(urb);
            free(buffer);
            for (int j = 0; j < i; j++) {
                free(stream->urbs[j]->buffer);
                free(stream->urbs[j]);
            }
            delete[] stream->urbs;
            delete stream;
            return 0;
        }
        memset(buffer, 0, stream->bufferLength);
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = stream->endpoint;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = buffer;
        urb->usercontext = (void*) (intptr_t) i;
        // iso_frame_desc[].length, buffer_length, and number_of_packets are set per submission
        // (see submitIso) since sample rates that don't divide evenly into the 1ms packet
        // schedule (e.g. 44100 Hz) need a varying number of samples -- and therefore bytes --
        // per packet, not the fixed maxPacketSize every time.
        stream->urbs[i] = urb;
    }

    return (jlong) (intptr_t) stream;
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
Java_org_oxycblt_auxio_usb_UsbNative_submitIso(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint slot, jbyteArray data,
        jintArray packetLengths) {
    auto* stream = (IsoStream*) (intptr_t) handle;
    if (!stream || slot < 0 || slot >= stream->numUrbs) return JNI_FALSE;
    struct usbdevfs_urb* urb = stream->urbs[slot];

    jsize numPackets = env->GetArrayLength(packetLengths);
    if (numPackets <= 0 || numPackets > stream->packetsPerUrb) {
        LOGE("Invalid packet count for slot %d: %d (max %d)", slot, numPackets, stream->packetsPerUrb);
        return JNI_FALSE;
    }

    jint* lengths = env->GetIntArrayElements(packetLengths, nullptr);
    unsigned int total = 0;
    for (jsize p = 0; p < numPackets; p++) {
        urb->iso_frame_desc[p].length = (unsigned int) lengths[p];
        total += (unsigned int) lengths[p];
    }
    env->ReleaseIntArrayElements(packetLengths, lengths, JNI_ABORT);

    if (total > stream->bufferLength) {
        LOGE("Packet total %u exceeds buffer capacity %u for slot %d", total, stream->bufferLength, slot);
        return JNI_FALSE;
    }

    jsize dataLen = env->GetArrayLength(data);
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
Java_org_oxycblt_auxio_usb_UsbNative_reapIso(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* stream = (IsoStream*) (intptr_t) handle;
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
    int badPackets = 0;
    unsigned int totalActual = 0;
    unsigned int totalExpected = 0;
    for (int p = 0; p < reaped->number_of_packets; p++) {
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

    return (jint) (intptr_t) reaped->usercontext;
}

// Cancels all outstanding URBs, unblocking any reapIso() call in flight on another thread (the
// streaming loop's reapIso() returns -1 for a cancelled urb). Does NOT touch memory -- it's safe
// to call concurrently with reapIso()/submitIso() on another thread, unlike closeIsoStream.
extern "C" JNIEXPORT void JNICALL
Java_org_oxycblt_auxio_usb_UsbNative_cancelIso(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* stream = (IsoStream*) (intptr_t) handle;
    if (!stream) return;
    for (int i = 0; i < stream->numUrbs; i++) {
        ioctl(stream->fd, USBDEVFS_DISCARDURB, stream->urbs[i]);
    }
}

// Drains any remaining completions and frees everything. Only safe once the caller has confirmed
// (e.g. via Thread.join() after cancelIso()) that no other thread is still calling reapIso() or
// submitIso() on this handle -- this frees the same memory those functions read and write.
extern "C" JNIEXPORT void JNICALL
Java_org_oxycblt_auxio_usb_UsbNative_closeIsoStream(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* stream = (IsoStream*) (intptr_t) handle;
    if (!stream) return;

    for (int i = 0; i < stream->numUrbs; i++) {
        ioctl(stream->fd, USBDEVFS_DISCARDURB, stream->urbs[i]);
    }
    struct usbdevfs_urb* reaped = nullptr;
    while (ioctl(stream->fd, USBDEVFS_REAPURBNDELAY, &reaped) == 0) {
        // Drain whatever the kernel still has queued so it can free its own bookkeeping.
    }
    for (int i = 0; i < stream->numUrbs; i++) {
        free(stream->urbs[i]->buffer);
        free(stream->urbs[i]);
    }
    delete[] stream->urbs;
    delete stream;
}
