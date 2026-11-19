/*
 * Copyright (c) 2026 libusbdac Project
 * UsbDeviceQuirkTable.kt is part of libusbdac.
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

// A table of USB vendor/product IDs and kernel line numbers, generated from the kernel source:
// every number is data, and one entry per line keeps it diffable against the kernel's table.
@file:Suppress("MagicNumber", "MaxLineLength")

package org.ostrichbat2k.usbdac.quirks

import org.ostrichbat2k.usbdac.quirks.UsbDeviceQuirk as Q

/**
 * The built-in quirk table (#132): every entry of Linux `snd-usb-audio`'s `quirk_flags_table`
 * (sound/usb/quirks.c, read at v7.3) that carries a flag this library acts on, with only those
 * flags kept. Each entry names the kernel source line it was taken from and the device name the
 * kernel gives it. Entries the kernel matches on USB strings rather than IDs are left out. As in
 * the kernel, the first matching entry wins, and every product-specific entry comes before the
 * vendor-wide ones.
 *
 * Generated from the kernel source, not written by hand -- regenerate rather than edit, and
 * add nothing that isn't in the kernel's table: entries here can't be checked without the device.
 */
internal object UsbDeviceQuirkTable {
    /** [productId] null matches every product of [vendorId]. */
    data class Entry(
        val vendorId: Int,
        val productId: Int?,
        val kernelLine: Int,
        val name: String,
        val quirks: Set<Q>,
    )

    private fun device(vendorId: Int, productId: Int, kernelLine: Int, name: String, vararg quirks: Q) =
        Entry(vendorId, productId, kernelLine, name, quirks.toSet())

    private fun vendor(vendorId: Int, kernelLine: Int, name: String, vararg quirks: Q) =
        Entry(vendorId, null, kernelLine, name, quirks.toSet())

    val entries: List<Entry> =
        listOf(
            device(0x001f, 0x0b21, 2233, "AB13X USB Audio", Q.INTERFACE_DELAY),
            device(0x001f, 0x0b23, 2235, "AB17X USB Audio", Q.INTERFACE_DELAY),
            device(0x0020, 0x0b21, 2237, "GHW-123P", Q.INTERFACE_DELAY),
            device(0x0124, 0x0c21, 2239, "Generic USB Headphone", Q.INTERFACE_DELAY),
            device(0x03f0, 0x654a, 2241, "HP 320 FHD Webcam", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x041e, 0x4080, 2247, "Creative Live Cam VF0610", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x045e, 0x083c, 2249, "MS USB Link headset", Q.CONTROL_DELAY_20MS, Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x046d, 0x0807, 2254, "Logitech Webcam C500", Q.CONTROL_DELAY_1MS),
            device(0x046d, 0x0808, 2256, "Logitech Webcam C600", Q.CONTROL_DELAY_1MS),
            device(0x046d, 0x0809, 2258, "unnamed in the kernel table", Q.CONTROL_DELAY_1MS),
            device(0x046d, 0x0819, 2260, "Logitech Webcam C210", Q.CONTROL_DELAY_1MS),
            device(0x046d, 0x081b, 2262, "HD Webcam c310", Q.CONTROL_DELAY_1MS),
            device(0x046d, 0x081d, 2264, "HD Webcam c510", Q.CONTROL_DELAY_1MS),
            device(0x046d, 0x0825, 2266, "HD Webcam c270", Q.CONTROL_DELAY_1MS),
            device(0x046d, 0x0826, 2268, "HD Webcam c525", Q.CONTROL_DELAY_1MS),
            device(0x046d, 0x084c, 2270, "Logitech ConferenceCam Connect", Q.CONTROL_DELAY_1MS, Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x046d, 0x08ca, 2272, "Logitech Quickcam Fusion", Q.CONTROL_DELAY_1MS),
            device(0x046d, 0x0991, 2274, "Logitech QuickCam Pro", Q.CONTROL_DELAY_1MS),
            device(0x046d, 0x09a2, 2277, "QuickCam Communicate Deluxe/S7500", Q.CONTROL_DELAY_1MS),
            device(0x046d, 0x09a4, 2279, "Logitech QuickCam E 3500", Q.CONTROL_DELAY_1MS),
            device(0x046d, 0x0a8f, 2281, "Logitech H390 headset", Q.CONTROL_DELAY_1MS),
            device(0x0499, 0x3108, 2291, "Yamaha YIT-W12TX", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x04d8, 0xfeea, 2293, "Benchmark DAC1 Pre", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x04e8, 0xa051, 2295, "Samsung USBC Headset (AKG)", Q.CONTROL_DELAY_5MS, Q.SKIP_CLOCK_SELECTOR),
            device(0x054c, 0x0b8c, 2299, "Sony WALKMAN NW-A45 DAC", Q.SET_INTERFACE_FIRST),
            device(0x0556, 0x0014, 2301, "Phoenix Audio TMX320VC", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x05a3, 0x9420, 2307, "ELP HD USB Camera", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x05a7, 0x1020, 2309, "Bose Companion 5", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x05fc, 0x0231, 2315, "JBL Pebbles", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x0624, 0x3d3f, 2318, "AB13X USB Audio", Q.INTERFACE_DELAY),
            device(0x0644, 0x8043, 2320, "TEAC UD-501/UD-501V2/UD-503/NT-503", Q.CONTROL_DELAY_20MS, Q.INTERFACE_DELAY),
            device(0x0644, 0x8044, 2323, "Esoteric D-05X", Q.CONTROL_DELAY_20MS, Q.INTERFACE_DELAY),
            device(0x0644, 0x804a, 2326, "TEAC UD-301", Q.CONTROL_DELAY_20MS, Q.INTERFACE_DELAY),
            device(0x0644, 0x806b, 2331, "TEAC UD-701", Q.CONTROL_DELAY_20MS, Q.INTERFACE_DELAY),
            device(0x0644, 0x807d, 2334, "TEAC UD-507", Q.CONTROL_DELAY_20MS, Q.INTERFACE_DELAY),
            device(0x0644, 0x806c, 2337, "Esoteric XD", Q.CONTROL_DELAY_20MS, Q.INTERFACE_DELAY),
            device(0x0666, 0x0880, 2342, "SPACETOUCH USB Audio", Q.CONTROL_DELAY_5MS, Q.INTERFACE_DELAY),
            device(0x0711, 0x5800, 2349, "MCT Trigger 5 USB-to-HDMI", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x074d, 0x3553, 2351, "Outlaw RR2150 (Micronas UAC3553B)", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x0763, 0x2084, 2361, "M-Audio Venom", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x07fd, 0x000b, 2363, "MOTU M Series 2nd hardware revision", Q.CONTROL_DELAY_1MS),
            device(0x0951, 0x16ad, 2367, "Kingston HyperX", Q.CONTROL_DELAY_1MS),
            device(0x0b0e, 0x0349, 2377, "Jabra 550a", Q.CONTROL_DELAY_1MS),
            device(0x0c45, 0x6340, 2381, "Sonix HD USB Camera", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x0c45, 0x636b, 2383, "Microdia JP001 USB Camera", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x0d8c, 0x0014, 2389, "C-Media", Q.CONTROL_DELAY_1MS),
            device(0x0d8c, 0x0102, 2391, "C-Media CM6206", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x0e0b, 0xfa01, 2393, "Feaulle Rainbow", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x1101, 0x0003, 2405, "Audioengine D1", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x12d1, 0x3a07, 2407, "HUAWEI USB-C HEADSET", Q.INTERFACE_DELAY),
            device(0x1224, 0x2a25, 2410, "Jieli Technology USB PHY 2.0", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x1395, 0x740a, 2412, "Sennheiser DECT", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x154e, 0x1002, 2433, "Denon DCD-1500RE", Q.CONTROL_DELAY_20MS),
            device(0x154e, 0x1003, 2435, "Denon DA-300USB", Q.CONTROL_DELAY_20MS),
            device(0x154e, 0x3005, 2437, "Marantz HD-DAC1", Q.CONTROL_DELAY_20MS),
            device(0x154e, 0x3006, 2439, "Marantz SA-14S1", Q.CONTROL_DELAY_20MS),
            device(0x1686, 0x00dd, 2445, "Zoom R16/24", Q.CONTROL_DELAY_1MS),
            device(0x17ef, 0x3083, 2455, "Lenovo TBT3 dock", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x1852, 0x5062, 2457, "Luxman D-08u", Q.CONTROL_DELAY_20MS),
            device(0x1852, 0x5065, 2459, "Luxman DA-06", Q.CONTROL_DELAY_20MS),
            device(0x1901, 0x0191, 2461, "GE B850V3 CP2114 audio interface", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x19f7, 0x0035, 2465, "RODE NT-USB+", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x1bcf, 0x2281, 2467, "HD Webcam", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x1bcf, 0x2283, 2469, "NexiGo N930AF FHD Webcam", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x1ff7, 0x0f81, 2473, "SC13A Webcam", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x21b4, 0x0081, 2507, "AudioQuest DragonFly", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x2522, 0x0007, 2515, "LH Labs Geek Out HD Audio 1V5", Q.SET_INTERFACE_FIRST),
            device(0x25aa, 0x600b, 2517, "TAE1159", Q.INTERFACE_DELAY),
            device(0x2912, 0x30c8, 2523, "Audioengine D1", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x2d95, 0x8011, 2535, "VIVO USB-C HEADSET", Q.CONTROL_DELAY_1MS),
            device(0x2d95, 0x8021, 2537, "VIVO USB-C-XE710 HEADSET", Q.CONTROL_DELAY_1MS),
            device(0x2fc6, 0xf06b, 2541, "MOONDROP Moonriver2 Ti", Q.CONTROL_DELAY_20MS),
            device(0x2fc6, 0xf0b5, 2543, "iBasso DC-Elite", Q.CONTROL_DELAY_1MS),
            device(0x2fc6, 0xf0b7, 2545, "iBasso DC07 Pro", Q.CONTROL_DELAY_1MS),
            device(0x3255, 0x0000, 2549, "Luxman D-10X", Q.CONTROL_DELAY_20MS),
            device(0x3302, 0x17c2, 2553, "TTGK Technology USB-C Audio", Q.INTERFACE_DELAY),
            device(0x3443, 0x930d, 2557, "NexiGo N930W 60fps Webcam", Q.SKIP_SAMPLE_RATE_READBACK),
            device(0x36f9, 0xc009, 2559, "XIBERIA K03S", Q.INTERFACE_DELAY),
            device(0x3c20, 0x3d21, 2561, "AB13X USB Audio", Q.INTERFACE_DELAY),
            device(0x413c, 0xa506, 2563, "Dell AE515 sound bar", Q.SKIP_SAMPLE_RATE_READBACK),
            vendor(0x045e, 2580, "MS Lifecam", Q.SKIP_SAMPLE_RATE_READBACK),
            vendor(0x046d, 2582, "Logitech", Q.CONTROL_DELAY_1MS),
            vendor(0x047f, 2584, "Plantronics", Q.CONTROL_DELAY_20MS, Q.SKIP_SAMPLE_RATE_READBACK),
            vendor(0x0644, 2586, "TEAC Corp.", Q.CONTROL_DELAY_20MS, Q.INTERFACE_DELAY),
            vendor(0x1235, 2595, "Focusrite Novation", Q.SKIP_CLOCK_SELECTOR),
            vendor(0x1de7, 2604, "Phoenix Audio", Q.SKIP_SAMPLE_RATE_READBACK),
            vendor(0x23ba, 2612, "Playback Design", Q.CONTROL_DELAY_20MS, Q.INTERFACE_DELAY),
        )

    /** The first entry matching [vendorId]/[productId], or null. */
    fun lookup(vendorId: Int, productId: Int): Entry? =
        entries.firstOrNull { it.vendorId == vendorId && (it.productId == null || it.productId == productId) }
}
