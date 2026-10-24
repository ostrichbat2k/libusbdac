/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDacDescriptorCache.kt is part of libusbdac.
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

package org.ostrichbat2k.usbdac.api

import org.ostrichbat2k.usbdac.CachedUsbDacDescriptor
import org.ostrichbat2k.usbdac.UacPlaybackTopology

/**
 * Persists each device's resolved topology and Clock Source supported-rate ranges across
 * activations, keyed by USB vendor+product ID -- see the implementation's own doc comment for
 * why this shortens how long the raw interface claim needs to stay held on a cache hit.
 *
 * Internal rather than part of the library's public (host-app-facing) API: no host app needs to
 * depend on this directly, only [UsbAudioManager] does.
 */
internal interface UsbDacDescriptorCache {
    /** Blocking; callers already run this off the main thread. */
    fun get(vendorId: Int, productId: Int): CachedUsbDacDescriptor?

    /** Blocking; callers already run this off the main thread. */
    fun put(
        vendorId: Int,
        productId: Int,
        topology: UacPlaybackTopology,
        clockSupportedRates: List<Pair<Int, Int>>,
    )
}
