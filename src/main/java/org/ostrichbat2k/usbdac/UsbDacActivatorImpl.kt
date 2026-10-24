/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDacActivatorImpl.kt is part of libusbdac.
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

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.ostrichbat2k.usbdac.api.UsbDacActivator
import org.ostrichbat2k.usbdac.api.UsbDacDescriptorCache

internal class UsbDacActivatorImpl(
    private val context: Context,
    private val usbManager: UsbManager,
    private val descriptorCache: UsbDacDescriptorCache,
    private val getPreferredBitResolution: () -> Int,
    private val onCapabilities: (UsbDeviceCapabilities?) -> Unit,
    private val log: (String) -> Unit,
) : UsbDacActivator {
    override fun activate(device: UsbDevice): UsbDacActivator.Result? {
        log("Activating ${device.productName ?: device.deviceName}")

        // A cache hit skips this whole raw-descriptor fetch+walk -- and, further down, the
        // Cluster Descriptor and Clock Source range queries too -- entirely. Those queries
        // specifically need the interface claimed, so skipping them on a device we've already
        // resolved before shortens how long that claim (which evicts the kernel's own ALSA
        // driver) needs to stay held this time.
        val cached = descriptorCache.get(device.vendorId, device.productId)
        var topology: UacPlaybackTopology? = cached?.topology
        if (cached != null) {
            log("Using cached descriptor topology for ${device.deviceName}")
        } else {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                log("Could not open USB connection to ${device.deviceName}")
                return null
            }
            try {
                for (i in 0 until device.configurationCount.coerceAtLeast(1)) {
                    val bytes = UsbAudioDescriptorParser.fetchConfigurationDescriptor(connection, i) ?: continue
                    // Walk once, reuse the result across all four calls below -- each
                    // independently re-walking the same bytes was a real, pointless 4x linear
                    // pass per configuration index (see UsbAudioDescriptorParser.walk's doc
                    // comment).
                    val entries = UsbAudioDescriptorParser.walk(bytes)
                    UsbAudioDescriptorParser.dumpAcTopology(entries)
                    UsbAudioDescriptorParser.parseUac3Playback(entries)?.let { topology = it }
                    if (topology == null) {
                        UsbAudioDescriptorParser.parseUac2Playback(entries)?.let { topology = it }
                    }
                    if (topology == null) {
                        UsbAudioDescriptorParser.parseUac1Playback(entries)?.let { topology = it }
                    }
                }
            } finally {
                connection.close()
            }
        }

        var found = topology
        if (found == null) {
            log("No UAC1/UAC2/UAC3 playback topology found on ${device.deviceName}")
            return null
        }
        val deviceName = device.productName ?: device.deviceName
        onCapabilities(
            UsbDeviceCapabilities(
                deviceName = deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                topology = found,
                interfaces = UsbAudioController.listInterfaces(device, found)))
        val format =
            found.formats.firstOrNull { it.bitResolution == getPreferredBitResolution() }
                ?: found.formats.firstOrNull()
        if (format == null) {
            log("No usable format available for ${device.deviceName}")
            return null
        }
        log(
            "Topology found: mixer=${found.mixerUnitId} featureUnit=${found.outputFeatureUnitId} " +
                "clock=${found.clockSourceId}")

        var session = UsbAudioController.activate(context, device, found)
        if (session == null) {
            log("Failed to activate UAC${found.uacVersion} session for ${device.deviceName}")
            return null
        }

        // Query what sample rates the Clock Source actually supports before ever asking it to
        // change rate -- UAC 1.0 has none, and this was never checked at all before: a real
        // device accepted a SET_CUR to an unsupported rate and echoed it back as a successful
        // readback while the real hardware clock silently stayed where it was (audibly: pitched
        // up and sped up, every sample converted at the wrong rate despite 0 USB errors anywhere
        // in the chain). See UsbAudioSession.clockSupportedRates and where
        // UsbAudioOutputProvider consults it -- an empty result here (couldn't read, not
        // "nothing supported") falls back to its old hardcoded guess rather than refusing every
        // rate.
        if (found.uacVersion != 1) {
            val ranges =
                cached?.clockSupportedRates ?: (UsbAudioController.getClockFrequencyRanges(session) ?: emptyList())
            session = session.copy(clockSupportedRates = ranges)
            log("Clock ${found.clockSourceId} supported rate ranges: $ranges")
        }

        // UAC 3.0 pushes the real channel count into a separate Cluster Descriptor fetch that
        // UsbAudioDescriptorParser can't do during pure descriptor parsing -- there's no live
        // session yet at that point (see UacPlaybackTopology.channelCount's doc comment). Do it
        // now that we have one, before anything below reads found.channelCount (the volume/mixer
        // loops) or publishes it (Capabilities screen, UsbAudioOutputProvider.channels via
        // session.topology). Confirmed on real hardware: this device's cluster declares 2
        // channels, same as its EarPods form factor implies.
        if (found.uacVersion == 3 && found.channelCount < 0 && format.clusterDescriptorId >= 0) {
            // clusterDescriptorId lives on UacStreamFormat (per-format), not UacPlaybackTopology
            // -- use the format already selected above, not found itself.
            //
            // Not a .let{} on the nullable result -- reassigning found/session from inside a
            // lambda would make the compiler drop its smart-cast of both to non-null for the
            // rest of this function (a lambda could, in principle, outlive this call), breaking
            // every later found./session. use below despite this being called synchronously.
            val channels = UsbAudioController.getClusterChannelCount(session, format.clusterDescriptorId)
            if (channels != null) {
                found = found.copy(channelCount = channels)
                session = session.copy(topology = found)
                log("Cluster ${format.clusterDescriptorId} declares $channels channel(s)")
                onCapabilities(
                    UsbDeviceCapabilities(
                        deviceName = deviceName,
                        vendorId = device.vendorId,
                        productId = device.productId,
                        topology = found,
                        interfaces = UsbAudioController.listInterfaces(device, found)))
            }
        }

        // Normalized to a floor of 2 exactly once, here, instead of every downstream reader
        // independently doing `.coerceAtLeast(2)` (previously duplicated across this file's own
        // volume/mixer loops, UsbAudioOutputProvider.channels, and refreshControlState). This
        // covers both "unknown" (-1: the cluster fetch above failed or didn't apply, or a UAC1/2
        // device's Input Terminal channel count itself couldn't be parsed -- see
        // UacPlaybackTopology.channelCount's doc comment) and a genuinely-parsed mono (1)
        // device -- the floor of 2 was a deliberate safety margin applied to both cases
        // previously, not just the unknown one, and this keeps that behavior rather than only
        // fixing up -1 specifically.
        if (found.channelCount < 2) {
            log("Channel count ${found.channelCount} below floor, coercing to 2")
            found = found.copy(channelCount = 2)
            session = session.copy(topology = found)
        }

        // Only on a fresh parse -- a cache hit already has this device's final, resolved
        // topology and rate ranges on file, and re-writing an identical row every activation
        // would just be wasted DB I/O.
        if (cached == null) {
            descriptorCache.put(device.vendorId, device.productId, found, session.clockSupportedRates)
        }

        // Do this before anything else: its recovery wait (up to 300ms for a D2->D0 wake, per
        // this device's own declared waRecoveryTime2) should overlap with -- not follow -- the
        // rest of setup below, and nothing else here is safe to skip past a domain that might
        // still be asleep.
        if (found.powerDomainId >= 0) {
            UsbAudioController.wakePowerDomain(
                session, found.powerDomainId, found.powerDomainD1RecoveryUs, found.powerDomainD2RecoveryUs)
            log(
                "Power domain ${found.powerDomainId} woken (d1=${found.powerDomainD1RecoveryUs}us " +
                    "d2=${found.powerDomainD2RecoveryUs}us)")
        } else {
            log("No power domain found covering our USB input")
        }

        // UAC 2.0/3.0: the Clock Source entity is addressed independently of which streaming
        // alternate setting ends up selected, so this can happen up front. UAC 1.0 has no Clock
        // Source at all -- its sample rate is a property of the streaming *endpoint* itself, set
        // further below, after selectFormat has made that endpoint active (see there for why).
        val clockOk =
            if (found.uacVersion != 1) {
                UsbAudioController.setClockFrequency(session, UsbAudioTuning.SUPPORTED_SAMPLE_RATE_HZ).also {
                    log("Clock set to ${UsbAudioTuning.SUPPORTED_SAMPLE_RATE_HZ} Hz: ok=$it")
                }
            } else {
                true
            }
        val muted = UsbAudioController.getMute(session)
        if (muted != false) UsbAudioController.setMute(session, muted = false)
        // Was hardcoded 0..2 (master + stereo) -- the Feature Unit has one volume control per
        // logical channel (1..N) plus the shared master (0), so a device with more than 2
        // channels has per-channel controls beyond what that range ever touched. channelCount
        // is read from the device's own Input Terminal descriptor, not assumed; only the loop
        // bound was ever hardcoded to stereo.
        //
        // Master channel (0) volume: we check master *mute* above, but had never checked
        // master *volume* -- if this Feature Unit has an independent master gain stage
        // separate from the per-channel ones below, it being low/attenuated would silence
        // everything regardless of what the per-channel ones are set to.
        for (channel in 0..found.channelCount) {
            val volume = UsbAudioController.getVolume(session, channel)
            // Deliberately the midpoint of the device's own reported range, not its max (unity
            // gain) -- a freshly (re)activated DAC shouldn't jump straight to its loudest
            // setting. See UsbAudioController.defaultVolumeTarget.
            val range = UsbAudioController.getVolumeRange(session, channel)
            val target = UsbAudioController.defaultVolumeTarget(range)
            if (volume == null || volume != target) {
                UsbAudioController.setVolume(session, value = target, channel = channel)
            }
            log("Volume ch$channel: was=$volume range=$range target=$target")
        }

        // Our USB input doesn't feed the output Feature Unit directly on every device -- on
        // this one it's first summed into a Mixer Unit alongside a second source (the hardware
        // mic path), discovered via UsbAudioDescriptorParser.dumpAcTopology. The Feature Unit
        // being correctly unmuted at unity means nothing if the mixer's own per-channel gain for
        // our input defaults to silence, independent of and upstream from everything above.
        val mixerUnitId = found.mixerUnitId
        val mixerBase = found.mixerInputChannelBase
        // Was hardcoded to a stereo-in/stereo-out cross-point grid; both sides scale with the
        // device's own real channel count instead (not assumed). found.channelCount is already
        // normalized to never be -1/unknown by this point (see the channel-count fallback above).
        val mixerChannelCount = found.channelCount
        if (mixerUnitId >= 0 && mixerBase >= 0) {
            for (inCh in mixerBase until mixerBase + mixerChannelCount) {
                for (outCh in 1..mixerChannelCount) {
                    val gain = UsbAudioController.getMixerGain(session, mixerUnitId, inCh, outCh)
                    val gainRange = UsbAudioController.getMixerGainRange(session, mixerUnitId, inCh, outCh)
                    val gainTarget = gainRange?.second ?: 0
                    if (gain == null || gain != gainTarget) {
                        UsbAudioController.setMixerGain(session, mixerUnitId, inCh, outCh, gainTarget)
                    }
                    log("Mixer gain in=$inCh out=$outCh: was=$gain range=$gainRange target=$gainTarget")
                }
            }
        } else {
            log("No mixer unit gating our USB input (mixerUnitId=$mixerUnitId base=$mixerBase)")
        }

        // Android's own UsbAlsaManager independently binds the kernel's snd-usb-audio driver to
        // any USB Audio Class device on attach; that bind was observed completing (ALSA device
        // node creation logged) *during* this very activation sequence, after the one-time
        // disconnectKernelDriver pass in UsbAudioController.activate() had already run -- so it
        // can still be racing us at this point. Evicting it again here, right before the final
        // alt-setting selection, is safe; evicting it *after* selectFormat was tried first and
        // broke every subsequent iso submission (errno=ENOENT), almost certainly because
        // disconnecting a driver resets the interface to alt-setting 0 as a side effect, undoing
        // whatever alt-setting was just selected. So this must stay before selectFormat, not after.
        UsbAudioController.reassertKernelEviction(session)
        val formatOk = UsbAudioController.selectFormat(session, format)
        log("Alt-setting ${format.alternateSetting} (${format.bitResolution}-bit) selected: ok=$formatOk")

        // UAC 1.0's Sampling Frequency Control lives on the streaming endpoint itself, which
        // doesn't functionally exist until selectFormat (just above) has made its alternate
        // setting active -- so this has to happen after, unlike UAC 2.0/3.0's entity-addressed
        // clock above. Pick whichever of this format's declared rates matches what the rest of
        // the app expects (48kHz, falling back to 44.1kHz), or just its first if neither is
        // offered.
        val rateHz =
            if (found.uacVersion == 1) {
                format.sampleRates.firstOrNull { it == UsbAudioTuning.SUPPORTED_SAMPLE_RATE_HZ }
                    ?: format.sampleRates.firstOrNull { it == 44100 }
                    ?: format.sampleRates.firstOrNull()
            } else {
                UsbAudioTuning.SUPPORTED_SAMPLE_RATE_HZ
            }
        val rateOk =
            if (found.uacVersion == 1) {
                if (rateHz == null) {
                    log("Format alt=${format.alternateSetting} declares no sample rate")
                    false
                } else {
                    UsbAudioController.setUac1EndpointSampleRate(session, format.endpointAddress, rateHz).also {
                        log("UAC1 endpoint sample rate set to $rateHz Hz: ok=$it")
                    }
                }
            } else {
                clockOk
            }

        if (!rateOk || !formatOk) {
            log("Failed to configure DAC session (rate=$rateOk format=$formatOk)")
            UsbAudioController.release(session)
            return null
        }

        // Give the clock/alt-setting/power-domain changes above a moment to actually settle
        // before marking the session ready. Do NOT call reassertKernelEviction after
        // selectFormat -- see the comment above that call for why.
        //
        // Deliberately not blocking on a diagnostic sine-tone playback test here anymore (that
        // tool, IsoToneTest, has since been removed entirely): it was useful once while chasing
        // the intermittent-silence investigation,
        // but running it on every single activation pushed "DAC session ready" out by 3+ seconds
        // -- which, in real playback, is 3+ seconds RoutingAudioSink's configure() could get
        // called first and commit to the fallback (AudioTrack/speaker) sink before this ever
        // finishes, exactly what was observed (tracks playing through the tablet speaker). The
        // settings screen's "Retry activation" + its live controls now cover manual diagnosis
        // instead.
        Thread.sleep(UsbAudioTuning.DAC_SETTLE_DELAY_MS)
        // No Clock Source entity exists to query on UAC 1.0.
        if (found.uacVersion != 1) {
            log("Clock valid: ${UsbAudioController.getClockValid(session)}")
        }

        return UsbDacActivator.Result(session, format, rateHz ?: UsbAudioTuning.SUPPORTED_SAMPLE_RATE_HZ)
    }
}
