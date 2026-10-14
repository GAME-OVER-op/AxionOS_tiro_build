/*
 * Copyright (C) 2025 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.axion.deviceinfo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.fingerprint.FingerprintManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemProperties
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import com.android.internal.util.MemInfoReader
import com.android.settingslib.deviceinfo.PrivateStorageInfo
import com.android.settingslib.deviceinfo.StorageManagerVolumeProvider
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class DeviceInfo(
    val deviceName: String,
    val model: String,
    val axionVersion: String,
    val axionBuildType: String,
    val androidVersion: String,
    val securityPatch: String,
    val buildDate: String,
    val buildNumber: String,
    val maintainer: String,
    val processor: String,
    val totalRam: String,
    val storageUsed: String,
    val storageTotal: String,
    val storagePercentage: Float,
    val batteryCapacity: String,
    val screenSize: String,
    val screenResolution: String,
    val frontCamera: String,
    val rearCamera: String,
    val kernelVersion: String
)

object DeviceInfoProvider {
    private const val UNKNOWN = "—"

    // Collect on Dispatchers.IO: CameraService, StorageManager and sysfs can block.
    fun getDeviceInfo(context: Context): DeviceInfo {
        val storageInfo = runCatching { getStorageInfo(context) }
            .getOrDefault(Triple(UNKNOWN, UNKNOWN, 0f))
        return DeviceInfo(
            deviceName = getDeviceName(context),
            model = Build.MODEL,
            axionVersion = getAxionVersion(),
            axionBuildType = getAxionBuildType(),
            androidVersion = Build.VERSION.RELEASE,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            buildDate = getBuildDate(),
            buildNumber = Build.DISPLAY,
            maintainer = getMaintainerName(),
            processor = getProcessor(),
            totalRam = runCatching { getTotalRam() }.getOrDefault(UNKNOWN),
            storageUsed = storageInfo.first,
            storageTotal = storageInfo.second,
            storagePercentage = storageInfo.third,
            batteryCapacity = getBatteryCapacity(context),
            screenSize = getScreenSize(context),
            screenResolution = getScreenResolution(context),
            frontCamera = getCameraMegapixels(context, CameraCharacteristics.LENS_FACING_FRONT),
            rearCamera = getCameraMegapixels(context, CameraCharacteristics.LENS_FACING_BACK),
            kernelVersion = getKernelVersion()
        )
    }

    private fun firstProperty(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            SystemProperties.get(key, "").trim().takeIf { it.isNotEmpty() }
        } ?: ""

    fun getDeviceName(context: Context): String = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }.getOrDefault(Build.MODEL)

    fun setDeviceName(context: Context, name: String) {
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, name)
    }

    @JvmStatic
    fun isFingerprintBiometricSupported(context: Context): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) return false
        return try {
            context.getSystemService(FingerprintManager::class.java)
                ?.sensorPropertiesInternal?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun isUdfpsSupported(context: Context): Boolean {
        return try {
            if (context.resources.run {
                    getIdentifier("config_is_powerbutton_fps", "bool", "android")
                        .takeIf { it != 0 }?.let { getBoolean(it) }
                } == true) return false
            context.resources.run {
                getIdentifier("config_udfps_sensor_props", "array", "android")
                    .takeIf { it != 0 }?.let { getIntArray(it) }?.isNotEmpty()
            } == true || context.getSystemService(FingerprintManager::class.java)
                ?.sensorPropertiesInternal?.any { it.isAnyUdfpsType } == true
        } catch (_: Exception) {
            false
        }
    }

    // Build/version information comes from the running ROM. Never fabricate a release version.
    fun getAxionVersion(): String = firstProperty(
        "ro.axion.build.version", "ro.axion.version", "ro.axion.display.version"
    ).ifEmpty { UNKNOWN }

    // A device-tree override marks locally maintained builds without changing upstream metadata.
    fun getAxionBuildType(): String = firstProperty(
        "ro.axion.local_build_type", "ro.axion.releasetype"
    ).ifEmpty { UNKNOWN }.uppercase(Locale.ROOT)

    fun getBuildDate(): String = firstProperty("ro.build.date").ifEmpty { UNKNOWN }

    fun getMaintainerName(): String = firstProperty(
        "ro.axion.local_maintainer", "persist.sys.axion_maintainer"
    ).ifEmpty { UNKNOWN }

    fun getProcessor(): String {
        val override = firstProperty("persist.sys.axion_cpu_info")
        if (override.isNotEmpty()) return override.replace('_', ' ')

        val soc = firstProperty("ro.soc.model", "ro.vendor.soc.model")
            .ifEmpty { Build.SOC_MODEL?.trim().orEmpty() }
        if (soc.isNotEmpty() && !soc.equals("unknown", true)) {
            // Marketing names are aliases for detected SoC IDs, not ROM version constants.
            val knownSoCs = mapOf(
                "SM8650" to "Snapdragon 8 Gen 3",
                "SM8750" to "Snapdragon 8 Elite",
                "SM8550" to "Snapdragon 8 Gen 2",
                "SM8475" to "Snapdragon 8+ Gen 1"
            )
            knownSoCs[soc.uppercase(Locale.ROOT)]?.let { return it }
            val manufacturer = firstProperty("ro.soc.manufacturer")
                .ifEmpty { Build.SOC_MANUFACTURER?.trim().orEmpty() }
            return if (manufacturer.isNotBlank() && !manufacturer.equals("unknown", true)) {
                "$manufacturer $soc"
            } else soc
        }
        val hardware = Build.HARDWARE?.takeIf { it.isNotBlank() && it != "qcom" }
        return hardware ?: UNKNOWN
    }

    fun getTotalRam(): String {
        val reader = MemInfoReader()
        reader.readMemInfo()
        val totalGB = reader.totalSize / 1_000_000_000.0
        return "${totalGB.roundToInt().coerceAtLeast(1)} GB"
    }

    private fun getStorageInfo(context: Context): Triple<String, String, Float> {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val info = PrivateStorageInfo.getPrivateStorageInfo(StorageManagerVolumeProvider(storageManager))
        val totalBytes = info.totalBytes.coerceAtLeast(0L)
        val usedBytes = (totalBytes - info.freeBytes).coerceIn(0L, totalBytes)
        if (totalBytes == 0L) return Triple(UNKNOWN, UNKNOWN, 0f)

        // Intentionally query the active volume; never use a fixed device capacity (DSU safe).
        val usedGiB = usedBytes / (1024.0 * 1024.0 * 1024.0)
        val totalGiB = totalBytes / (1024.0 * 1024.0 * 1024.0)
        val rounded = roundToNearestKnownStorageSize(totalGiB)
        val used = if (usedGiB >= 1024) {
            String.format(Locale.US, "%.1f TB", usedGiB / 1024.0)
        } else String.format(Locale.US, "%.1f GB", usedGiB)
        val total = if (rounded >= 1024) "${rounded / 1024} TB" else "$rounded GB"
        return Triple(used, total, usedBytes.toFloat() / totalBytes.toFloat())
    }

    private fun roundToNearestKnownStorageSize(storageGiB: Double): Int {
        val knownSizes = arrayOf(16, 32, 64, 128, 256, 512, 1024)
        if (storageGiB <= 8) return ceil(storageGiB).toInt()
        for (size in knownSizes) if (storageGiB <= size) return size
        return ceil(storageGiB).toInt()
    }

    private fun normalizeCapacityMah(raw: Long): Long? {
        // BatteryService and power_supply expose design capacity in µAh, while
        // PowerProfile traditionally reports mAh. Validate both without guessing.
        val mah = if (raw >= 100_000L) raw / 1_000L else raw
        return mah.takeIf { it in 100L..30_000L }
    }

    fun getBatteryCapacity(context: Context): String {
        val broadcast = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val extraCapacity = runCatching {
            broadcast?.getIntExtra(BatteryManager.EXTRA_DESIGN_CAPACITY, -1)?.toLong() ?: -1L
        }.getOrDefault(-1L)
        val fromBroadcast = normalizeCapacityMah(extraCapacity)
        val fromSysfs = runCatching {
            File("/sys/class/power_supply/battery/charge_full_design").readText()
                .trim().toLongOrNull()?.let(::normalizeCapacityMah)
        }.getOrNull()
        // Do not fall back to a potentially unrelated device's PowerProfile (5500 on Tiro).
        val value = fromBroadcast ?: fromSysfs ?: return UNKNOWN
        return "$value mAh"
    }

    fun getScreenSize(): String = firstProperty("persist.sys.axion_screen_size")

    fun getScreenSize(context: Context): String {
        val configured = getScreenSize()
        if (configured.isNotBlank()) return configured
        return runCatching {
            val display = context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching ""
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.xdpi !in 100f..1000f || metrics.ydpi !in 100f..1000f) ""
            else {
                val mode = display.mode
                val diagonal = sqrt(
                    (mode.physicalWidth.toDouble() / metrics.xdpi.toDouble()).let { it * it } +
                    (mode.physicalHeight.toDouble() / metrics.ydpi.toDouble()).let { it * it }
                )
                if (diagonal in 3.0..20.0) String.format(Locale.US, "%.1f\"", diagonal)
                else ""
            }
        }.getOrDefault("")
    }

    fun getScreenResolution(context: Context): String = runCatching {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching UNKNOWN
        val mode = display.mode
        "${mode.physicalWidth} × ${mode.physicalHeight}"
    }.getOrDefault(UNKNOWN)

    fun getFrontCameraMegapixels(context: Context): String =
        getCameraMegapixels(context, CameraCharacteristics.LENS_FACING_FRONT)

    fun getRearCameraMegapixels(context: Context): String =
        getCameraMegapixels(context, CameraCharacteristics.LENS_FACING_BACK)

    private fun getCameraMegapixels(context: Context, facing: Int): String {
        val suffix = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "rear"
        val configured = firstProperty("persist.sys.device_camera_info_$suffix")
        if (configured.isNotEmpty()) {
            return configured.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(" + ") { if (it.endsWith("MP", true)) it else "${it}MP" }
        }
        return runCatching {
            val cameraManager = context.getSystemService(CameraManager::class.java)
                ?: return@runCatching UNKNOWN
            val values = mutableListOf<String>()
            for (cameraId in cameraManager.cameraIdList) {
                // Some camera IDs are not accessible while the camera HAL is initializing.
                val characteristics = runCatching {
                    cameraManager.getCameraCharacteristics(cameraId)
                }.getOrNull() ?: continue
                if (characteristics.get(CameraCharacteristics.LENS_FACING) != facing) continue
                val sensorSize = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION
                ) ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val sensorPixels = sensorSize?.let {
                    it.width.toLong() * it.height.toLong()
                } ?: 0L
                val streamPixels = runCatching {
                    characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                    )?.getOutputSizes(ImageFormat.JPEG)
                        ?.maxOfOrNull { it.width.toLong() * it.height.toLong() } ?: 0L
                }.getOrDefault(0L)
                val megapixels = maxOf(sensorPixels, streamPixels) / 1_000_000.0
                if (megapixels > 0.0) values += formatMegapixels(megapixels)
            }
            values.distinct().takeIf { it.isNotEmpty() }?.joinToString(" + ") ?: UNKNOWN
        }.getOrDefault(UNKNOWN)
    }

    private fun formatMegapixels(megapixels: Double): String {
        val rounded = megapixels.roundToInt()
        return if (abs(megapixels - rounded) <= 0.25) "${rounded}MP"
        else String.format(Locale.US, "%.1fMP", megapixels)
    }

    fun getKernelVersion(): String = System.getProperty("os.version")
        ?.takeIf { it.isNotBlank() } ?: UNKNOWN
}
