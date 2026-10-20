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

package org.ostrichbat2k.usbdac

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/** A device's resolved topology and Clock Source rate ranges, as [UsbDacDescriptorCache] hands it back. */
internal data class CachedUsbDacDescriptor(
    val topology: UacPlaybackTopology,
    val clockSupportedRates: List<Pair<Int, Int>>,
)

/**
 * Persists each device's resolved [UacPlaybackTopology] and Clock Source supported-rate ranges
 * across activations, keyed by USB vendor+product ID -- so a device [UsbAudioManager.activateDac]
 * has already activated once doesn't need its raw descriptors re-fetched and re-walked, nor its
 * Cluster Descriptor (UAC 3.0 channel count) or Clock Source range queries re-run, on every later
 * activation.
 *
 * The latter two specifically require the raw USB interface claimed -- the same claim that evicts
 * Android's own kernel driver and disrupts every other app using the device while held (see
 * [UsbAudioManager.ensureActivated]'s doc comment). Skipping them on a cache hit shortens how long
 * that claim needs to stay held on every activation after the first, not just how much CPU work
 * activation does.
 *
 * Keyed on vendor+product ID alone, not a full device identity (no serial number): two physically
 * different devices sharing the same IDs (common on generic/white-label UAC chips) would collide
 * and share a cache entry. Accepted tradeoff -- [UsbAudioManager.retryActivation] always re-derives
 * fresh topology from the device itself and overwrites whatever was cached, so a bad/stale entry
 * from a device swap is a one-tap fix, not a silent permanent wrong state.
 */
internal class UsbDacDescriptorCache(context: Context) {
    private val db =
        Room.databaseBuilder(
                context.applicationContext,
                DescriptorCacheDatabase::class.java,
                "usb_dac_descriptor_cache.db",
            )
            .fallbackToDestructiveMigration(true)
            .build()

    /** Blocking; callers already run this off the main thread (see [UsbAudioManager.activateDac]). */
    fun get(vendorId: Int, productId: Int): CachedUsbDacDescriptor? {
        val key = keyFor(vendorId, productId)
        val dao = db.dao()
        val cachedTopology = dao.getTopology(key) ?: return null
        val formats = dao.getFormats(key)
        if (formats.isEmpty()) return null
        return CachedUsbDacDescriptor(
            topology = cachedTopology.toDomain(formats.map { it.toDomain() }),
            clockSupportedRates = cachedTopology.clockSupportedRatesEncoded.decodeRanges())
    }

    /** Blocking; callers already run this off the main thread (see [UsbAudioManager.activateDac]). */
    fun put(
        vendorId: Int,
        productId: Int,
        topology: UacPlaybackTopology,
        clockSupportedRates: List<Pair<Int, Int>>,
    ) {
        val key = keyFor(vendorId, productId)
        db.dao()
            .replace(
                CachedUacTopology(
                    deviceKey = key,
                    vendorId = vendorId,
                    productId = productId,
                    configurationValue = topology.configurationValue,
                    controlInterfaceNumber = topology.controlInterfaceNumber,
                    streamingInterfaceNumber = topology.streamingInterfaceNumber,
                    uacVersion = topology.uacVersion,
                    clockSourceId = topology.clockSourceId,
                    outputFeatureUnitId = topology.outputFeatureUnitId,
                    mixerUnitId = topology.mixerUnitId,
                    mixerInputChannelBase = topology.mixerInputChannelBase,
                    powerDomainId = topology.powerDomainId,
                    powerDomainD1RecoveryUs = topology.powerDomainD1RecoveryUs,
                    powerDomainD2RecoveryUs = topology.powerDomainD2RecoveryUs,
                    channelCount = topology.channelCount,
                    clockSupportedRatesEncoded = clockSupportedRates.encodeRanges(),
                ),
                topology.formats.map { it.toEntity(key) },
            )
    }

    private fun keyFor(vendorId: Int, productId: Int) = "$vendorId:$productId"
}

// "min-max,min-max" -- discrete rates are just "n-n", same min==max convention
// UsbAudioSession.clockSupportedRates' doc comment already uses for those.
private fun List<Pair<Int, Int>>.encodeRanges(): String = joinToString(",") { "${it.first}-${it.second}" }

private fun String.decodeRanges(): List<Pair<Int, Int>> =
    if (isEmpty()) {
        emptyList()
    } else {
        split(",").map { pair ->
            val (min, max) = pair.split("-")
            min.toInt() to max.toInt()
        }
    }

private fun String.decodeInts(): List<Int> = if (isEmpty()) emptyList() else split(",").map { it.toInt() }

private fun CachedUacTopology.toDomain(formats: List<UacStreamFormat>) =
    UacPlaybackTopology(
        configurationValue = configurationValue,
        controlInterfaceNumber = controlInterfaceNumber,
        streamingInterfaceNumber = streamingInterfaceNumber,
        uacVersion = uacVersion,
        clockSourceId = clockSourceId,
        outputFeatureUnitId = outputFeatureUnitId,
        mixerUnitId = mixerUnitId,
        mixerInputChannelBase = mixerInputChannelBase,
        powerDomainId = powerDomainId,
        powerDomainD1RecoveryUs = powerDomainD1RecoveryUs,
        powerDomainD2RecoveryUs = powerDomainD2RecoveryUs,
        channelCount = channelCount,
        formats = formats,
    )

private fun CachedUacFormat.toDomain() =
    UacStreamFormat(
        alternateSetting = alternateSetting,
        bitResolution = bitResolution,
        subslotSize = subslotSize,
        clusterDescriptorId = clusterDescriptorId,
        endpointAddress = endpointAddress,
        maxPacketSize = maxPacketSize,
        isAsync = isAsync,
        sampleRates = sampleRatesEncoded.decodeInts())

private fun UacStreamFormat.toEntity(deviceKey: String) =
    CachedUacFormat(
        deviceKey = deviceKey,
        alternateSetting = alternateSetting,
        bitResolution = bitResolution,
        subslotSize = subslotSize,
        clusterDescriptorId = clusterDescriptorId,
        endpointAddress = endpointAddress,
        maxPacketSize = maxPacketSize,
        isAsync = isAsync,
        sampleRatesEncoded = sampleRates.joinToString(","))

@Entity(tableName = "usb_dac_topology")
internal data class CachedUacTopology(
    @PrimaryKey val deviceKey: String,
    val vendorId: Int,
    val productId: Int,
    val configurationValue: Int,
    val controlInterfaceNumber: Int,
    val streamingInterfaceNumber: Int,
    val uacVersion: Int,
    val clockSourceId: Int,
    val outputFeatureUnitId: Int,
    val mixerUnitId: Int,
    val mixerInputChannelBase: Int,
    val powerDomainId: Int,
    val powerDomainD1RecoveryUs: Int,
    val powerDomainD2RecoveryUs: Int,
    val channelCount: Int,
    val clockSupportedRatesEncoded: String,
)

@Entity(tableName = "usb_dac_format", primaryKeys = ["deviceKey", "alternateSetting"])
internal data class CachedUacFormat(
    val deviceKey: String,
    val alternateSetting: Int,
    val bitResolution: Int,
    val subslotSize: Int,
    val clusterDescriptorId: Int,
    val endpointAddress: Int,
    val maxPacketSize: Int,
    val isAsync: Boolean,
    val sampleRatesEncoded: String,
)

@Dao
internal interface DescriptorCacheDao {
    @Query("SELECT * FROM usb_dac_topology WHERE deviceKey = :deviceKey")
    fun getTopology(deviceKey: String): CachedUacTopology?

    @Query("SELECT * FROM usb_dac_format WHERE deviceKey = :deviceKey")
    fun getFormats(deviceKey: String): List<CachedUacFormat>

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertTopology(topology: CachedUacTopology)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertFormats(formats: List<CachedUacFormat>)

    @Query("DELETE FROM usb_dac_format WHERE deviceKey = :deviceKey") fun deleteFormats(deviceKey: String)

    /** Replaces both a device's topology row and its whole set of format rows atomically. */
    @Transaction
    fun replace(topology: CachedUacTopology, formats: List<CachedUacFormat>) {
        deleteFormats(topology.deviceKey)
        insertTopology(topology)
        insertFormats(formats)
    }
}

@Database(entities = [CachedUacTopology::class, CachedUacFormat::class], version = 1, exportSchema = false)
internal abstract class DescriptorCacheDatabase : RoomDatabase() {
    abstract fun dao(): DescriptorCacheDao
}
