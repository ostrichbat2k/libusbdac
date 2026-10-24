/*
 * Copyright (c) 2026 libusbdac Project
 * UsbAudioController.kt is part of libusbdac.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.ostrichbat2k.usbdac.protocol

import org.ostrichbat2k.usbdac.UsbAudioManager
import org.ostrichbat2k.usbdac.streaming.UsbAudioOutput
import org.ostrichbat2k.usbdac.streaming.UsbNative
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import timber.log.Timber as L

/** An activated USB connection to a UAC1/2/3 device: configuration selected, interfaces claimed. */
data class UsbAudioSession(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val topology: UacPlaybackTopology,
    val controlInterface: UsbInterface,
    /** Streaming interface alternate settings, keyed by bAlternateSetting. */
    val streamingAltSettings: Map<Int, UsbInterface>,
    /**
     * The Clock Source's actual supported sample rate sub-ranges in Hz (min..max pairs; a
     * discrete rate is one where min == max), read once via [UsbAudioController.getClockFrequencyRanges]
     * right after activation. Empty for UAC 1.0 (no Clock Source) or if the device didn't answer
     * the query -- callers should fall back to a hardcoded guess in that case, not treat empty as
     * "nothing supported." Declaring a rate here is no guarantee we can actually request it
     * (unrelated failures still possible), but NOT declaring one here and still requesting it
     * anyway was confirmed, on a real device, to silently keep the hardware clock at its previous
     * rate while the SET_CUR/GET_CUR control-register readback still reported the requested value
     * -- a clean, error-free "success" the whole way down the USB stack while every sample was
     * actually converted at the wrong rate (audibly: pitched up and sped up).
     */
    val clockSupportedRates: List<Pair<Int, Int>> = emptyList(),
)

/**
 * Switches a UAC3 device into its playback configuration, claims the relevant interfaces, and
 * issues the class-specific control requests needed to pick a sample rate and PCM format.
 *
 * The control-request byte layout here (request type/codes, wValue/wIndex encoding) follows the
 * USB Audio Class 3.0 spec's Clock Source entity requests, carried forward from the same
 * addressing scheme UAC 1.0/2.0 used. Unlike the descriptor parser, this hasn't been cross-
 * verified against a passive capture (control transfers are live, not something `lsusb` records),
 * so [getClockFrequency] is meant to be tried first as a safe, read-only check of the addressing
 * before any SET_CUR write is attempted.
 */
object UsbAudioController {
    private const val REQ_TYPE_CLASS_INTERFACE_GET = 0xA1 // Device-to-host, Class, Interface
    private const val REQ_TYPE_CLASS_INTERFACE_SET = 0x21 // Host-to-device, Class, Interface
    private const val REQ_TYPE_CLASS_ENDPOINT_GET = 0xA2 // Device-to-host, Class, Endpoint
    private const val REQ_TYPE_CLASS_ENDPOINT_SET = 0x22 // Host-to-device, Class, Endpoint
    // UAC 2.0/3.0 request codes: bRequest is a fixed CUR/RANGE selector, and GET vs SET is
    // carried purely by bmRequestType's direction bit.
    private const val UAC_REQUEST_CUR = 0x01
    private const val UAC_REQUEST_RANGE = 0x02
    // Plenty for any real device -- sized to bound the GET_RANGE clock-frequency read buffer
    // (2 header bytes + this many 12-byte dMIN/dMAX/dRES sub-ranges), not a protocol limit.
    private const val MAX_CLOCK_SUBRANGES = 16
    // UAC 1.0 predates that unification: GET/SET is baked into bRequest itself (confirmed
    // against the Linux kernel's UAC_SET_CUR/UAC_GET_CUR in include/linux/usb/audio.h), so a
    // GET on a UAC 1.0 device needs bRequest=0x81, not the 0x01 that would mean SET_CUR.
    private const val UAC1_REQUEST_GET_CUR = 0x81
    private const val CS_SAM_FREQ_CONTROL = 0x01
    private const val CS_CLOCK_VALID_CONTROL = 0x02
    // UAC3 request for a High Capability Descriptor -- unlike CUR/RANGE, wValue here is the
    // descriptor/cluster ID directly (not a (control<<8) selector), confirmed against the Linux
    // kernel's get_cluster_channels_v3() in sound/usb/mixer.c.
    private const val UAC3_CS_REQ_HIGH_CAPABILITY_DESCRIPTOR = 0x06
    private const val FU_MUTE_CONTROL = 0x01
    private const val FU_VOLUME_CONTROL = 0x02
    private const val AC_POWER_DOMAIN_CONTROL = 0x02

    /** The CUR request's bRequest byte for [topology]'s UAC version and GET/SET direction. */
    private fun curRequest(topology: UacPlaybackTopology, get: Boolean): Int =
        if (topology.uacVersion == 1 && get) UAC1_REQUEST_GET_CUR else UAC_REQUEST_CUR

    /**
     * Decodes a little-endian unsigned integer from [len] bytes (1-4) starting at [offset] --
     * every multi-byte UAC CUR/RANGE value in this file (clock frequency, UAC1 endpoint sample
     * rate) is one of these, previously hand-rolled with the same bit-shift arithmetic at each
     * call site instead of sharing this.
     */
    private fun ByteArray.leUInt(offset: Int, len: Int): Int {
        var value = 0
        for (i in 0 until len) {
            value = value or ((this[offset + i].toInt() and 0xFF) shl (8 * i))
        }
        return value
    }

    /** Encodes [value] as [len] little-endian bytes (1-4) -- the write-side counterpart of [leUInt]. */
    private fun leBytes(value: Int, len: Int): ByteArray = ByteArray(len) { i -> ((value shr (8 * i)) and 0xFF).toByte() }

    const val PD_STATE_D0 = 0
    const val PD_STATE_D1 = 1
    const val PD_STATE_D2 = 2

    /** Opens, configures, and claims the interfaces for [device]'s playback topology. */
    fun activate(context: Context, device: UsbDevice, topology: UacPlaybackTopology): UsbAudioSession? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            L.w("Could not open USB connection to ${device.deviceName}")
            return null
        }

        // The kernel's USBDEVFS_SETCONFIGURATION handler (proc_setconfig) refuses the switch if
        // *any* interface of the device is claimed by *anyone* -- including us. claimInterface
        // (force=true) evicts a kernel driver but then claims the interface for the caller,
        // which satisfies proc_setconfig's check just as much as the driver it replaced did.
        // UsbNative.disconnectKernelDriver issues the raw USBDEVFS_DISCONNECT ioctl directly,
        // without claiming anything afterward, leaving the interface in the "nothing claimed"
        // state the kernel actually requires.
        val fd = connection.fileDescriptor
        val defaultConfig = device.getConfiguration(0)
        var disconnected = 0
        for (i in 0 until defaultConfig.interfaceCount) {
            if (UsbNative.disconnectKernelDriver(fd, defaultConfig.getInterface(i).id)) disconnected++
        }
        L.i("Disconnected $disconnected/${defaultConfig.interfaceCount} default-configuration interfaces")

        val targetConfig =
            (0 until device.configurationCount)
                .map { device.getConfiguration(it) }
                .firstOrNull { it.id == topology.configurationValue }
        if (targetConfig == null) {
            L.w("Could not find UsbConfiguration matching value ${topology.configurationValue}")
            connection.close()
            return null
        }

        if (!connection.setConfiguration(targetConfig)) {
            L.w("setConfiguration(${targetConfig.id}) failed")
            connection.close()
            return null
        }

        var controlInterface: UsbInterface? = null
        val streamingAlts = mutableMapOf<Int, UsbInterface>()
        for (i in 0 until targetConfig.interfaceCount) {
            val iface = targetConfig.getInterface(i)
            if (iface.id == topology.controlInterfaceNumber && iface.alternateSetting == 0) {
                controlInterface = iface
            } else if (iface.id == topology.streamingInterfaceNumber) {
                streamingAlts[iface.alternateSetting] = iface
            }
        }

        val control = controlInterface
        if (control == null || streamingAlts.isEmpty()) {
            L.w("Could not locate control/streaming interfaces in target configuration")
            connection.close()
            return null
        }

        if (!connection.claimInterface(control, true)) {
            L.w("Failed to claim control interface ${control.id}")
            connection.close()
            return null
        }
        val altZero = streamingAlts[0]
        if (altZero != null && !connection.claimInterface(altZero, true)) {
            L.w("Failed to claim streaming interface ${altZero.id}")
            connection.releaseInterface(control)
            connection.close()
            return null
        }

        L.i(
            "Activated UAC${topology.uacVersion} session: config=${targetConfig.id} " +
                "control=${control.id} streaming=${topology.streamingInterfaceNumber} " +
                "alts=${streamingAlts.keys}")

        // The disconnect-everything sweep above was necessary for setConfiguration() to succeed
        // (confirmed, reproduced: filtering it down to just audio interfaces makes
        // setConfiguration() itself fail outright -- proc_setconfig really does require every
        // interface of the device free, not just the ones we care about), but it leaves every
        // non-audio interface -- e.g. a composite headset's own inline-remote HID interface(s),
        // not part of the Audio Interface Collection -- evicted with nothing to ever reclaim it.
        // Unlike control/streaming (explicitly given back by releaseToKernel), nothing else here
        // ever reconnects them: confirmed, reproduced, this silently and permanently killed the
        // kernel's usbhid binding to a real EarPods remote the first time any track ever
        // streamed (`adb shell getevent -lp` lost the device, button presses stopped doing
        // anything), for the rest of the session. claimInterface(force=true) followed by an
        // immediate releaseInterface() is the only tool this codebase has to turn "evicted,
        // unclaimed" back into a real USBDEVFS_CONNECT/device_attach() (see releaseToKernel's own
        // doc comment) -- Auxio never wants to hold these, so release happens right away.
        for (i in 0 until targetConfig.interfaceCount) {
            val iface = targetConfig.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) continue
            if (connection.claimInterface(iface, true)) {
                connection.releaseInterface(iface)
            } else {
                L.w("Could not give non-audio interface ${iface.id} back to the kernel")
            }
        }

        return UsbAudioSession(device, connection, topology, control, streamingAlts)
    }

    /**
     * Every interface on [device]'s configuration matching [topology.configurationValue],
     * independent of whether it's audio -- display-only, for a host app's Capabilities UI to
     * show a composite device's non-audio interfaces (e.g. an inline-remote HID one, see the
     * eviction-sweep comment in [activate]) instead of leaving them invisible. Doesn't open a
     * connection or claim anything itself.
     */
    fun listInterfaces(device: UsbDevice, topology: UacPlaybackTopology): List<UsbInterfaceInfo> {
        val config =
            (0 until device.configurationCount)
                .map { device.getConfiguration(it) }
                .firstOrNull { it.id == topology.configurationValue }
                ?: return emptyList()
        return (0 until config.interfaceCount)
            .map { config.getInterface(it) }
            .groupBy { it.id }
            .map { (number, altSettings) ->
                val interfaceClass = altSettings.first().interfaceClass
                val role =
                    when {
                        number == topology.controlInterfaceNumber -> UsbInterfaceInfo.Role.CONTROL
                        number == topology.streamingInterfaceNumber -> UsbInterfaceInfo.Role.STREAMING
                        interfaceClass == UsbConstants.USB_CLASS_AUDIO -> UsbInterfaceInfo.Role.OTHER_AUDIO
                        else -> UsbInterfaceInfo.Role.NON_AUDIO
                    }
                UsbInterfaceInfo(number, interfaceClass, altSettings.size, role)
            }
            .sortedBy { it.number }
    }

    /**
     * Re-evicts whatever kernel driver is bound to this session's interfaces, right before
     * streaming starts.
     *
     * Android's own UsbAlsaManager independently binds the kernel's snd-usb-audio driver to any
     * USB Audio Class device as soon as it's attached -- confirmed via logcat: its ALSA device
     * node creation (alsaFileAdded(pcmC1D0p) etc.) was observed landing *during* this class's own
     * [activate] sequence, after the one-time [UsbNative.disconnectKernelDriver] pass in
     * [activate] had already run. That kernel probe appears to still be completing
     * asynchronously at that point, so it can rebind (or finish binding) after we've already
     * evicted it once, racing our own clock/alt-setting setup -- exactly the kind of thing that
     * would make iso transfers look completely clean at our claimed interface while the device's
     * actual output stage got reconfigured out from under us by the competing driver. Calling
     * this again immediately before the first packet goes out closes that window.
     *
     * Only ever targets audio-class interfaces -- confirmed, reproduced: a composite device like
     * a headset with its own inline remote exposes that remote as one or more entirely separate
     * class=HID interfaces (not part of the Audio Interface Collection this function cares
     * about), and the raw USBDEVFS_DISCONNECT ioctl this calls doesn't distinguish "ours" from
     * "someone else's" -- it evicts whatever kernel driver (usbhid included) is currently bound
     * to whatever interface number it's given. Called before every single track, unfiltered this
     * used to permanently kill the kernel's usbhid binding to the remote the first time any track
     * ever streamed, with nothing anywhere in this codebase ever reconnecting it afterward
     * (unlike the control/streaming interfaces, which [releaseToKernel] explicitly hands back) --
     * confirmed via `adb shell getevent -lp`: the remote's input device vanished the moment
     * streaming started and never came back, and physical button presses stopped doing anything.
     */
    fun reassertKernelEviction(session: UsbAudioSession) {
        val fd = session.connection.fileDescriptor
        val defaultConfig = session.device.getConfiguration(0)
        var disconnected = 0
        var audioInterfaces = 0
        for (i in 0 until defaultConfig.interfaceCount) {
            val iface = defaultConfig.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
            audioInterfaces++
            if (UsbNative.disconnectKernelDriver(fd, iface.id)) disconnected++
        }
        L.i("Re-evicted $disconnected/$audioInterfaces audio interfaces before streaming")
    }

    /**
     * Gives both interfaces back to the kernel so Android's own snd-usb-audio can register a
     * normal playback PCM substream for this device -- order matters and is the entire point of
     * this function existing separately from just calling `releaseInterface` twice in whatever
     * order.
     *
     * Read directly from the kernel source (`sound/usb/card.c`): only the *control* interface's
     * own probe ever calls `snd_usb_create_streams()`, which walks its interface-association
     * descriptor's interface list (control + streaming) and calls `snd_usb_create_stream()` for
     * each one. The streaming interface's own probe can never create its own PCM substream --
     * self-referencing `snd_usb_create_streams()` on it fails immediately ("Audio class v2/v3
     * interfaces need an interface association"), since the IAD descriptor only lives on the
     * control interface. `snd_usb_create_stream()` skips an interface outright
     * (`usb_interface_claimed(iface)`, i.e. `iface->dev.driver != NULL` -- see
     * `include/linux/usb.h` -- true for *our own* usbfs claim just as much as a real kernel
     * driver) with no retry later. And `USBDEVFS_CONNECT`'s `device_attach()` call -- what
     * `UsbDeviceConnection.releaseInterface()` issues internally on success, confirmed from AOSP's
     * own JNI/libusbhost source -- runs *synchronously*, inside that one ioctl syscall: there is
     * no asynchronous race to wait out, only ordering. So: releasing control while we still hold
     * streaming (the order this used to be in) permanently skips creating the playback substream
     * for that chip's entire lifetime, confirmed, reproduced -- it never comes back no matter how
     * long streaming sits unclaimed afterward, since control's probe already ran and decided.
     * Releasing streaming *first* means it's already unclaimed by the time control's release
     * triggers control's own synchronous reprobe-and-create-streams call.
     */
    fun releaseToKernel(session: UsbAudioSession) {
        session.streamingAltSettings[0]?.let { session.connection.releaseInterface(it) }
        session.connection.releaseInterface(session.controlInterface)
        L.i("Released streaming=${session.topology.streamingInterfaceNumber} then control=${session.controlInterface.id} to kernel")
    }

    /**
     * The inverse of [releaseToKernel]: re-claims the control and alt-0 streaming interfaces (via
     * the public, force=true API, which internally evicts whatever Android's snd-usb-audio bound
     * in the meantime) so a fresh bit-perfect session can resume on the *same* [session] object --
     * callers still need their own clock/format re-setup after this (see [UsbAudioOutput]'s init
     * logic), exactly as if this were the first activation.
     *
     * Returns false if re-claiming either interface failed (e.g. another process is genuinely
     * holding it, or the device was unplugged in the interim) -- callers must treat [session] as
     * dead in that case, the same way a mid-stream unplug is already handled elsewhere.
     */
    fun reclaimFromKernel(session: UsbAudioSession): Boolean {
        if (!session.connection.claimInterface(session.controlInterface, true)) {
            L.w("reclaimFromKernel: failed to claim control interface")
            return false
        }
        val altZero = session.streamingAltSettings[0]
        if (altZero != null && !session.connection.claimInterface(altZero, true)) {
            L.w("reclaimFromKernel: failed to claim streaming interface")
            session.connection.releaseInterface(session.controlInterface)
            return false
        }
        L.i("Reclaimed control=${session.controlInterface.id} streaming alt-0 from kernel")
        return true
    }

    fun release(session: UsbAudioSession) {
        // Streaming before control, same reasoning (and same kernel source) as releaseToKernel's
        // own doc comment -- releasing control first permanently skips creating this device's
        // playback substream for its entire remaining lifetime, confirmed, reproduced: disabling
        // USB DAC output mid-track left every fallback track, and even a later re-enable, hitting
        // "pcm device cannot support given format. Supported config are:[]" in an endless retry
        // loop until the device was unplugged. This function used to release control first; that
        // was the bug.
        for (iface in session.streamingAltSettings.values) {
            session.connection.releaseInterface(iface)
        }
        session.connection.releaseInterface(session.controlInterface)
        session.connection.close()
    }

    /** Reads the clock source's current sample rate in Hz, or null on failure. Safe/read-only. */
    fun getClockFrequency(session: UsbAudioSession): Int? {
        val buffer = ByteArray(4)
        val wValue = CS_SAM_FREQ_CONTROL shl 8
        val wIndex = (session.topology.clockSourceId shl 8) or session.controlInterface.id
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_GET, UAC_REQUEST_CUR, wValue, wIndex, buffer, buffer.size, 1000)
        if (result != 4) {
            L.w("GET_CUR clock frequency failed: result=$result (wValue=$wValue wIndex=$wIndex)")
            return null
        }
        val hz = buffer.leUInt(0, 4)
        L.i("Clock ${session.topology.clockSourceId} current frequency: $hz Hz")
        return hz
    }

    /**
     * Fetches the real channel count from a UAC3 device's Cluster Descriptor by ID, answering
     * "does this device actually support more than stereo" directly from the device instead of
     * the hardcoded-stereo assumption [UacPlaybackTopology.channelCount] falls back to when this
     * returns null (no live session yet during pure descriptor parsing, or the fetch itself
     * failing) -- called by [UsbAudioManager.activateDac] once a session exists.
     *
     * Request parameters confirmed against the Linux kernel's own `get_cluster_channels_v3()` in
     * `sound/usb/mixer.c`: bRequest=UAC3_CS_REQ_HIGH_CAPABILITY_DESCRIPTOR (0x06), wValue is the
     * cluster ID directly (not a (control<<8) selector the way CUR/RANGE requests work), wIndex
     * is just the control interface number. The response is a `uac3_cluster_header_descriptor`
     * (wLength:2, bDescriptorType:1, bDescriptorSubtype:1, wDescriptorID:2, bNrChannels:1 -- 7
     * bytes total, per `include/linux/usb/audio-v3.h`), with the real channel count as its last
     * byte.
     */
    fun getClusterChannelCount(session: UsbAudioSession, clusterId: Int): Int? {
        val buffer = ByteArray(7)
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_GET,
                UAC3_CS_REQ_HIGH_CAPABILITY_DESCRIPTOR,
                clusterId,
                session.controlInterface.id,
                buffer,
                buffer.size,
                1000)
        if (result != buffer.size) {
            L.w("GET cluster descriptor $clusterId failed: result=$result")
            return null
        }
        val nrChannels = buffer[6].toInt() and 0xFF
        L.i("Cluster $clusterId: bNrChannels=$nrChannels")
        return nrChannels
    }

    /**
     * Reads the clock source's actual supported sample rate sub-ranges via the UAC 2.0/3.0 RANGE
     * request (each a dMIN/dMAX/dRES triple per the spec; only MIN/MAX are kept since membership
     * testing is all this is used for), or null on failure. This was never queried anywhere in
     * this codebase before -- [setClockFrequency] was always just trusted blindly on the strength
     * of its own SET_CUR+readback succeeding, which a real device demonstrated is not actually
     * proof the rate took effect (see [UsbAudioSession.clockSupportedRates]). Safe/read-only, same
     * as [getClockFrequency].
     */
    fun getClockFrequencyRanges(session: UsbAudioSession): List<Pair<Int, Int>>? {
        val buffer = ByteArray(2 + MAX_CLOCK_SUBRANGES * 12)
        val wValue = CS_SAM_FREQ_CONTROL shl 8
        val wIndex = (session.topology.clockSourceId shl 8) or session.controlInterface.id
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_GET, UAC_REQUEST_RANGE, wValue, wIndex, buffer, buffer.size, 1000)
        if (result < 2) {
            L.w("GET_RANGE clock frequency failed: result=$result (wValue=$wValue wIndex=$wIndex)")
            return null
        }
        val numSubRanges = buffer.leUInt(0, 2)
        val ranges = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until numSubRanges) {
            val base = 2 + i * 12
            if (base + 12 > result) break // device declared more sub-ranges than it sent back
            ranges.add(buffer.leUInt(base, 4) to buffer.leUInt(base + 4, 4))
        }
        L.i("Clock ${session.topology.clockSourceId} supported rate ranges: $ranges")
        return ranges
    }

    /**
     * Reads the clock source's CLOCK_VALID bit, or null if this control isn't readable on this
     * device. Isochronous OUT has no device-side handshake at all -- "clean, 0-error transfer"
     * only means the host successfully put bytes on the wire on schedule, never that the device
     * actually had a locked clock to render them with. This is the one piece of ground truth the
     * device itself can report back about whether it's actually ready to stream.
     */
    fun getClockValid(session: UsbAudioSession): Boolean? {
        val buffer = ByteArray(1)
        val wValue = CS_CLOCK_VALID_CONTROL shl 8
        val wIndex = (session.topology.clockSourceId shl 8) or session.controlInterface.id
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_GET, UAC_REQUEST_CUR, wValue, wIndex, buffer, buffer.size, 1000)
        if (result != 1) {
            L.w("GET_CUR clock valid failed: result=$result (wValue=$wValue wIndex=$wIndex)")
            return null
        }
        val valid = (buffer[0].toInt() and 0x01) != 0
        L.i("Clock ${session.topology.clockSourceId} valid=$valid")
        return valid
    }

    /** Attempts to set the clock source sample rate in Hz, then reads it back to confirm. */
    fun setClockFrequency(session: UsbAudioSession, hz: Int): Boolean {
        val wValue = CS_SAM_FREQ_CONTROL shl 8
        val wIndex = (session.topology.clockSourceId shl 8) or session.controlInterface.id
        val buffer = leBytes(hz, 4)
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_SET, UAC_REQUEST_CUR, wValue, wIndex, buffer, buffer.size, 1000)
        if (result < 0) {
            L.w("SET_CUR clock frequency to $hz Hz failed: result=$result")
            return false
        }
        val readBack = getClockFrequency(session)
        val ok = readBack == hz
        L.i("SET_CUR clock frequency to $hz Hz: write result=$result, readback=$readBack, match=$ok")
        return ok
    }

    /**
     * Reads a UAC 1.0 streaming endpoint's current Sampling Frequency Control, in Hz, or null on
     * failure. UAC 1.0 has no Clock Source entity ([UacPlaybackTopology.clockSourceId] is always
     * -1); the rate is instead a 3-byte (not UAC 2.0/3.0's 4-byte) property of the endpoint
     * itself, addressed by endpoint address rather than by entity id + control interface.
     */
    fun getUac1EndpointSampleRate(session: UsbAudioSession, endpointAddress: Int): Int? {
        val buffer = ByteArray(3)
        val wValue = CS_SAM_FREQ_CONTROL shl 8
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_ENDPOINT_GET,
                UAC1_REQUEST_GET_CUR,
                wValue,
                endpointAddress,
                buffer,
                buffer.size,
                1000)
        if (result != 3) {
            L.w("GET_CUR UAC1 endpoint sample rate (ep=0x${"%02x".format(endpointAddress)}) failed: result=$result")
            return null
        }
        val hz = buffer.leUInt(0, 3)
        L.i("UAC1 endpoint 0x${"%02x".format(endpointAddress)} current sample rate: $hz Hz")
        return hz
    }

    /**
     * Sets a UAC 1.0 streaming endpoint's Sampling Frequency Control, then reads it back to
     * confirm. Must be called only after [selectFormat] has switched the streaming interface to
     * the alternate setting owning this endpoint -- unlike UAC 2.0/3.0's entity-addressed Clock
     * Source, this endpoint doesn't functionally exist (and this request isn't meaningful) until
     * that alternate setting is actually active.
     */
    fun setUac1EndpointSampleRate(session: UsbAudioSession, endpointAddress: Int, hz: Int): Boolean {
        val wValue = CS_SAM_FREQ_CONTROL shl 8
        val buffer = leBytes(hz, 3)
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_ENDPOINT_SET,
                UAC_REQUEST_CUR, // SET_CUR is 0x01 in UAC1 too -- only GET_CUR differs (0x81)
                wValue,
                endpointAddress,
                buffer,
                buffer.size,
                1000)
        if (result < 0) {
            L.w("SET_CUR UAC1 endpoint sample rate to $hz Hz failed: result=$result")
            return false
        }
        val readBack = getUac1EndpointSampleRate(session, endpointAddress)
        val ok = readBack == hz
        L.i(
            "SET_CUR UAC1 endpoint 0x${"%02x".format(endpointAddress)} sample rate to $hz Hz: " +
                "write result=$result, readback=$readBack, match=$ok")
        return ok
    }

    /** Reads the Feature Unit's mute state for [channel] (0=master, 1=left, 2=right), or null. */
    fun getMute(session: UsbAudioSession, channel: Int = 0): Boolean? {
        val unitId = session.topology.outputFeatureUnitId
        if (unitId < 0) return null
        val buffer = ByteArray(1)
        val wValue = (FU_MUTE_CONTROL shl 8) or channel
        val wIndex = (unitId shl 8) or session.controlInterface.id
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_GET,
                curRequest(session.topology, get = true),
                wValue,
                wIndex,
                buffer,
                buffer.size,
                1000)
        if (result != 1) {
            L.w("GET_CUR mute (unit=$unitId ch=$channel) failed: result=$result")
            return null
        }
        return buffer[0].toInt() != 0
    }

    /** Sets the Feature Unit's mute state for [channel] (0=master, 1=left, 2=right). */
    fun setMute(session: UsbAudioSession, muted: Boolean, channel: Int = 0): Boolean {
        val unitId = session.topology.outputFeatureUnitId
        if (unitId < 0) return false
        val wValue = (FU_MUTE_CONTROL shl 8) or channel
        val wIndex = (unitId shl 8) or session.controlInterface.id
        val buffer = byteArrayOf(if (muted) 1 else 0)
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_SET, UAC_REQUEST_CUR, wValue, wIndex, buffer, buffer.size, 1000)
        L.i("SET_CUR mute (unit=$unitId ch=$channel) to $muted: result=$result")
        return result >= 0
    }

    /** Reads the Feature Unit's volume for [channel] (0=master, 1=left, 2=right), in 1/256 dB. */
    fun getVolume(session: UsbAudioSession, channel: Int): Int? {
        val unitId = session.topology.outputFeatureUnitId
        if (unitId < 0) return null
        val buffer = ByteArray(2)
        val wValue = (FU_VOLUME_CONTROL shl 8) or channel
        val wIndex = (unitId shl 8) or session.controlInterface.id
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_GET,
                curRequest(session.topology, get = true),
                wValue,
                wIndex,
                buffer,
                buffer.size,
                1000)
        if (result != 2) {
            L.w("GET_CUR volume (unit=$unitId ch=$channel) failed: result=$result")
            return null
        }
        // Signed 16-bit, 1/256 dB units (0x0000 = 0dB).
        return (buffer[0].toInt() and 0xFF) or (buffer[1].toInt() shl 8)
    }

    /**
     * Reads the Feature Unit's volume MIN/MAX for [channel] via the UAC2/3 RANGE request, or
     * null on failure. We've been assuming 0 (1/256 dB units, i.e. 0dB/unity) is this device's
     * loudest setting, per the general UAC convention -- but that's never actually been
     * confirmed against this specific device's own reported range. If its real MAX is some
     * other, non-zero value (firmware quirk, or a device that encodes gain rather than
     * attenuation), writing 0 could be landing well short of audible instead of at unity.
     */
    fun getVolumeRange(session: UsbAudioSession, channel: Int): Pair<Int, Int>? {
        val unitId = session.topology.outputFeatureUnitId
        if (unitId < 0) return null
        val buffer = ByteArray(8) // wNumSubRanges(2) + MIN(2) + MAX(2) + RES(2), one subrange
        val wValue = (FU_VOLUME_CONTROL shl 8) or channel
        val wIndex = (unitId shl 8) or session.controlInterface.id
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_GET, UAC_REQUEST_RANGE, wValue, wIndex, buffer, buffer.size, 1000)
        if (result < 8) {
            L.w("GET_RANGE volume (unit=$unitId ch=$channel) failed: result=$result")
            return null
        }
        val numSubRanges = (buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)
        val min = (buffer[2].toInt() and 0xFF) or (buffer[3].toInt() shl 8)
        val max = (buffer[4].toInt() and 0xFF) or (buffer[5].toInt() shl 8)
        L.i("Volume range (unit=$unitId ch=$channel): numSubRanges=$numSubRanges min=$min max=$max")
        return min to max
    }

    /**
     * The default volume target to request on activation/reconfigure: the midpoint of [range]
     * rather than its max (unity gain) -- a deliberate safety margin so a freshly (re)activated
     * DAC doesn't jump straight to its loudest setting. Falls back to 0 (unity, the prior
     * default) if [range] couldn't be read.
     */
    fun defaultVolumeTarget(range: Pair<Int, Int>?): Int = range?.let { (it.first + it.second) / 2 } ?: 0

    /** Sets the Feature Unit's volume for [channel] (0=master, 1=left, 2=right), in 1/256 dB. */
    fun setVolume(session: UsbAudioSession, value: Int, channel: Int): Boolean {
        val unitId = session.topology.outputFeatureUnitId
        if (unitId < 0) return false
        val wValue = (FU_VOLUME_CONTROL shl 8) or channel
        val wIndex = (unitId shl 8) or session.controlInterface.id
        val buffer = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_SET, UAC_REQUEST_CUR, wValue, wIndex, buffer, buffer.size, 1000)
        L.i("SET_CUR volume (unit=$unitId ch=$channel) to $value: result=$result")
        return result >= 0
    }

    /**
     * Reads a Mixer Unit's programmable gain for one (input channel, output channel) pair, in
     * 1/256 dB, or null on failure. Unlike a Feature Unit's single mute/volume, a Mixer Unit's
     * "control selector" slot in wValue is the 1-based *input* channel number itself (not a
     * fixed selector constant), with the 1-based output channel in the low byte -- confirmed
     * against the Linux kernel's own usbmixer (sound/usb/mixer.c: build_mixer_unit_ctl's
     * `cval->control = in_ch + 1`, then validx = (control << 8) | output_channel).
     */
    fun getMixerGain(session: UsbAudioSession, mixerUnitId: Int, inputChannel: Int, outputChannel: Int): Int? {
        val buffer = ByteArray(2)
        val wValue = (inputChannel shl 8) or outputChannel
        val wIndex = (mixerUnitId shl 8) or session.controlInterface.id
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_GET,
                curRequest(session.topology, get = true),
                wValue,
                wIndex,
                buffer,
                buffer.size,
                1000)
        if (result != 2) {
            L.w(
                "GET_CUR mixer gain (unit=$mixerUnitId in=$inputChannel out=$outputChannel) " +
                    "failed: result=$result")
            return null
        }
        return (buffer[0].toInt() and 0xFF) or (buffer[1].toInt() shl 8)
    }

    /** Reads a Mixer Unit's gain range for one (input channel, output channel) pair, or null. */
    fun getMixerGainRange(
        session: UsbAudioSession,
        mixerUnitId: Int,
        inputChannel: Int,
        outputChannel: Int,
    ): Pair<Int, Int>? {
        val buffer = ByteArray(8)
        val wValue = (inputChannel shl 8) or outputChannel
        val wIndex = (mixerUnitId shl 8) or session.controlInterface.id
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_GET, UAC_REQUEST_RANGE, wValue, wIndex, buffer, buffer.size, 1000)
        if (result < 8) {
            L.w(
                "GET_RANGE mixer gain (unit=$mixerUnitId in=$inputChannel out=$outputChannel) " +
                    "failed: result=$result")
            return null
        }
        val min = (buffer[2].toInt() and 0xFF) or (buffer[3].toInt() shl 8)
        val max = (buffer[4].toInt() and 0xFF) or (buffer[5].toInt() shl 8)
        L.i(
            "Mixer gain range (unit=$mixerUnitId in=$inputChannel out=$outputChannel): " +
                "min=$min max=$max")
        return min to max
    }

    /** Sets a Mixer Unit's programmable gain for one (input channel, output channel) pair. */
    fun setMixerGain(
        session: UsbAudioSession,
        mixerUnitId: Int,
        inputChannel: Int,
        outputChannel: Int,
        value: Int,
    ): Boolean {
        val wValue = (inputChannel shl 8) or outputChannel
        val wIndex = (mixerUnitId shl 8) or session.controlInterface.id
        val buffer = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_SET, UAC_REQUEST_CUR, wValue, wIndex, buffer, buffer.size, 1000)
        L.i(
            "SET_CUR mixer gain (unit=$mixerUnitId in=$inputChannel out=$outputChannel) to " +
                "$value: result=$result")
        return result >= 0
    }

    /** Reads a UAC 3.0 Power Domain's current state (0=D0/full power, 1=D1, 2=D2), or null. */
    fun getPowerDomainState(session: UsbAudioSession, powerDomainId: Int): Int? {
        val buffer = ByteArray(1)
        val wValue = AC_POWER_DOMAIN_CONTROL shl 8
        val wIndex = (powerDomainId shl 8) or session.controlInterface.id
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_GET, UAC_REQUEST_CUR, wValue, wIndex, buffer, buffer.size, 1000)
        if (result != 1) {
            L.w("GET_CUR power domain state (id=$powerDomainId) failed: result=$result")
            return null
        }
        return buffer[0].toInt() and 0xFF
    }

    /** Sets a UAC 3.0 Power Domain's state. Does not wait out any recovery time itself. */
    fun setPowerDomainState(session: UsbAudioSession, powerDomainId: Int, state: Int): Boolean {
        val wValue = AC_POWER_DOMAIN_CONTROL shl 8
        val wIndex = (powerDomainId shl 8) or session.controlInterface.id
        val buffer = byteArrayOf(state.toByte())
        val result =
            session.connection.controlTransfer(
                REQ_TYPE_CLASS_INTERFACE_SET, UAC_REQUEST_CUR, wValue, wIndex, buffer, buffer.size, 1000)
        L.i("SET_CUR power domain state (id=$powerDomainId) to $state: result=$result")
        return result >= 0
    }

    /**
     * Wakes [powerDomainId] to D0 (full power) if it isn't already there, then blocks for
     * exactly as long as the device itself declared it needs to actually be ready -- mirroring
     * the real Linux kernel driver (sound/usb/power.c: snd_usb_power_domain_set), which performs
     * this exact GET-then-SET-then-wait sequence before ever streaming to a UAC 3.0 device.
     * This codebase never did any of this before it was discovered via a full topology dump;
     * every previous "clean transfer, no sound" run could plausibly have been streaming into a
     * domain that was still asleep, or mid-wake, the whole time -- with absolutely nothing
     * visible about that at the USB protocol level, since isochronous OUT has no handshake to
     * reveal it either way.
     */
    fun wakePowerDomain(session: UsbAudioSession, powerDomainId: Int, d1RecoveryUs: Int, d2RecoveryUs: Int) {
        val current = getPowerDomainState(session, powerDomainId)
        if (current == null) {
            L.w("Could not read power domain $powerDomainId state; skipping wake")
            return
        }
        if (current == PD_STATE_D0) {
            L.i("Power domain $powerDomainId already in D0")
            return
        }
        if (!setPowerDomainState(session, powerDomainId, PD_STATE_D0)) {
            L.w("Failed to set power domain $powerDomainId to D0")
            return
        }
        val recoveryUs =
            when (current) {
                PD_STATE_D1 -> d1RecoveryUs
                PD_STATE_D2 -> d2RecoveryUs
                else -> 0
            }
        L.i("Power domain $powerDomainId: D$current -> D0, waiting ${recoveryUs}us")
        if (recoveryUs > 0) {
            Thread.sleep(recoveryUs / 1000L, (recoveryUs % 1000L * 1000).toInt())
        }
    }

    /** Switches the streaming interface to the alternate setting for [format]. */
    fun selectFormat(session: UsbAudioSession, format: UacStreamFormat): Boolean {
        val iface = session.streamingAltSettings[format.alternateSetting]
        if (iface == null) {
            L.w("No interface for alternate setting ${format.alternateSetting}")
            return false
        }
        val ok = session.connection.setInterface(iface)
        L.i("setInterface(alt=${format.alternateSetting}, ${format.bitResolution}-bit): $ok")
        return ok
    }
}
