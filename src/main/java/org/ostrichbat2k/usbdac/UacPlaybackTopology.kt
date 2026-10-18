/*
 * Copyright (c) 2026 libusbdac Project
 * UacPlaybackTopology.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac

/** One selectable PCM format on a UAC streaming interface: one alternate setting. */
data class UacStreamFormat(
    val alternateSetting: Int,
    val bitResolution: Int,
    val subslotSize: Int,
    val clusterDescriptorId: Int,
    val endpointAddress: Int,
    val maxPacketSize: Int,
    val isAsync: Boolean,
    /**
     * UAC 1.0 only: the sample rate(s) this format's FORMAT_TYPE descriptor declares directly
     * (empty for UAC 2.0/3.0, which negotiate rate through [UacPlaybackTopology.clockSourceId]
     * instead -- UAC 1.0 has no Clock Source entity at all).
     */
    val sampleRates: List<Int> = emptyList(),
)

/** The USB Audio Class playback topology discovered on a device: enough to start a stream. */
data class UacPlaybackTopology(
    val configurationValue: Int,
    val controlInterfaceNumber: Int,
    val streamingInterfaceNumber: Int,
    /** 1, 2 or 3, whichever parser matched this device's AudioControl interface. */
    val uacVersion: Int,
    /** -1 for UAC 1.0, which has no Clock Source entity -- see [UacStreamFormat.sampleRates]. */
    val clockSourceId: Int,
    /** The Feature Unit gating the physical output (mute/volume), or -1 if none was found. */
    val outputFeatureUnitId: Int,
    /**
     * The Mixer Unit sitting between our USB input and [outputFeatureUnitId], if any (-1 if
     * none) -- on at least one real device, the Feature Unit's mute/volume is NOT the only gate:
     * the USB stream is first summed into a Mixer Unit alongside another source (e.g. the
     * hardware mic/line-in), and that mixer's own per-input gain defaults to silence until set,
     * independently of the Feature Unit downstream being correctly unmuted at unity.
     */
    val mixerUnitId: Int,
    /**
     * The 1-based input-channel number our USB terminal's first channel occupies on
     * [mixerUnitId] (e.g. 1 for stereo if we're the first input pin), or -1 if [mixerUnitId] is
     * -1 or we couldn't determine the offset (anything but the first pin requires summing
     * preceding pins' channel counts via their Cluster Descriptors, which aren't parsed here).
     */
    val mixerInputChannelBase: Int,
    /**
     * The UAC 3.0 Power Domain gating our USB input/output path, or -1 if none was found. UAC3
     * introduced per-entity power management (D0/D1/D2); this device's real Linux kernel ALSA
     * driver (sound/usb/power.c) explicitly wakes the relevant domain to D0 and waits out its
     * declared recovery time before ever streaming to it -- something this codebase had never
     * done at all until this was discovered via a full topology dump. Clean, 0-error USB
     * transfers happening while the domain is still mid-wake would be silently discarded by the
     * device's own (still-asleep) analog output stage, with nothing visible at the USB protocol
     * level to show it.
     */
    val powerDomainId: Int,
    /** D1->D0 recovery time in microseconds (waRecoveryTime1 * 50us), or 0 if unknown. */
    val powerDomainD1RecoveryUs: Int,
    /** D2->D0 recovery time in microseconds (waRecoveryTime2 * 50us), or 0 if unknown. */
    val powerDomainD2RecoveryUs: Int,
    /**
     * The channel count declared on the USB Streaming Input Terminal (e.g. 2 for stereo), or -1
     * if unknown. UAC 1.0/2.0 carry this directly on the Input Terminal; UAC 3.0 pushes it into a
     * separate Cluster Descriptor fetch this parser doesn't do, so it's always -1 there -- callers
     * should fall back to assuming stereo rather than treat -1 as "mono" or similar.
     */
    val channelCount: Int,
    val formats: List<UacStreamFormat>,
)

/**
 * Everything [UsbAudioDescriptorParser] found on a device's descriptors, bundled for read-only
 * display on the USB settings screen -- published as soon as a topology is parsed, independent of
 * whether the control-transfer setup that follows (clock, mute, volume, mixer gain) ends up
 * succeeding, since it describes what the device *declares*, not what Auxio managed to configure.
 */
data class UsbDeviceCapabilities(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val topology: UacPlaybackTopology,
)
