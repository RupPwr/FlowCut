package com.flowcut.app.engine.perf

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.flowcut.app.engine.export.Aspect
import com.flowcut.app.engine.export.ExportCapabilities
import com.flowcut.app.engine.export.OutCodec
import com.flowcut.app.engine.export.OutputSpec
import com.flowcut.app.engine.export.Quality

/**
 * System and hardware capabilities probe with SharedPreferences caching.
 * Keyed by Build.FINGERPRINT so cold app startup reads cached profile in < 1 ms.
 */
class DeviceProfileProbe(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "flowcut_device_profile"
        private const val PROBE_SCHEMA_VERSION = 2
        private const val KEY_PROBE_VERSION = "probe_version"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_SDK = "sdk"
        private const val KEY_TOTAL_RAM_MB = "total_ram_mb"
        private const val KEY_IS_LOW_RAM = "is_low_ram"
        private const val KEY_MEDIA_PERF_CLASS = "media_perf_class"
        private const val KEY_HW_AVC_ENC_1080P60 = "hw_avc_enc_1080p60"
        private const val KEY_HW_AVC_DEC_INSTANCES = "hw_avc_dec_instances"
    }

    /**
     * Returns cached profile if Build.FINGERPRINT and probe schema version match, otherwise
     * performs full hardware probe and persists the result.
     */
    fun probe(): DeviceProfile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedFingerprint = prefs.getString(KEY_FINGERPRINT, null)
        val cachedVersion = prefs.getInt(KEY_PROBE_VERSION, 0)
        val currentFingerprint = Build.FINGERPRINT

        if (cachedVersion == PROBE_SCHEMA_VERSION &&
            cachedFingerprint == currentFingerprint &&
            prefs.contains(KEY_TOTAL_RAM_MB)
        ) {
            return DeviceProfile(
                sdk = prefs.getInt(KEY_SDK, Build.VERSION.SDK_INT),
                totalRamMb = prefs.getLong(KEY_TOTAL_RAM_MB, 0L),
                isLowRam = prefs.getBoolean(KEY_IS_LOW_RAM, false),
                mediaPerfClass = prefs.getInt(KEY_MEDIA_PERF_CLASS, 0),
                hwAvcEnc1080p60 = prefs.getBoolean(KEY_HW_AVC_ENC_1080P60, false),
                hwAvcDecInstances = prefs.getInt(KEY_HW_AVC_DEC_INSTANCES, 1)
            )
        }

        val probed = runProbe()

        prefs.edit()
            .putInt(KEY_PROBE_VERSION, PROBE_SCHEMA_VERSION)
            .putString(KEY_FINGERPRINT, currentFingerprint)
            .putInt(KEY_SDK, probed.sdk)
            .putLong(KEY_TOTAL_RAM_MB, probed.totalRamMb)
            .putBoolean(KEY_IS_LOW_RAM, probed.isLowRam)
            .putInt(KEY_MEDIA_PERF_CLASS, probed.mediaPerfClass)
            .putBoolean(KEY_HW_AVC_ENC_1080P60, probed.hwAvcEnc1080p60)
            .putInt(KEY_HW_AVC_DEC_INSTANCES, probed.hwAvcDecInstances)
            .apply()

        return probed
    }

    private fun runProbe(): DeviceProfile {
        val sdk = Build.VERSION.SDK_INT

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        var totalRamMb = 0L
        var isLowRam = false
        if (am != null) {
            try {
                am.getMemoryInfo(memInfo)
                totalRamMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(0L)
                isLowRam = am.isLowRamDevice
            } catch (_: Throwable) {}
        }

        val mediaPerfClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Build.VERSION.MEDIA_PERFORMANCE_CLASS
            } catch (_: Throwable) {
                0
            }
        } else {
            0
        }

        val hwAvcEnc1080p60 = try {
            val choice = ExportCapabilities().find(
                OutputSpec(Aspect.R16_9, shortSide = 1080, fps = 60, quality = Quality.HIGH, codec = OutCodec.AVC),
                allowSoftware = false
            )
            choice != null && choice.hardware
        } catch (_: Throwable) {
            false
        }

        val hwAvcDecInstances = queryHwAvcDecInstances()

        return DeviceProfile(
            sdk = sdk,
            totalRamMb = totalRamMb,
            isLowRam = isLowRam,
            mediaPerfClass = mediaPerfClass,
            hwAvcEnc1080p60 = hwAvcEnc1080p60,
            hwAvcDecInstances = hwAvcDecInstances
        )
    }

    private fun queryHwAvcDecInstances(): Int {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            var bestInstances = 1
            for (info in list) {
                if (info.isEncoder) continue
                val mime = MediaFormat.MIMETYPE_VIDEO_AVC
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val hw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.startsWith("omx.google.", ignoreCase = true) &&
                            !info.name.startsWith("c2.android.", ignoreCase = true)
                }
                if (!hw) continue
                val caps = try {
                    info.getCapabilitiesForType(mime)
                } catch (_: Throwable) {
                    null
                } ?: continue

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val instances = caps.maxSupportedInstances
                    if (instances > bestInstances) {
                        bestInstances = instances
                    }
                }
            }
            bestInstances
        } catch (_: Throwable) {
            1
        }
    }
}
