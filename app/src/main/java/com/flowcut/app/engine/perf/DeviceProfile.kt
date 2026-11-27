package com.flowcut.app.engine.perf

/**
 * Pure Kotlin hardware & system profile representations.
 * Zero android.* dependencies to ensure 100% JVM unit testability.
 */
data class DeviceProfile(
    val sdk: Int,
    val totalRamMb: Long,
    val isLowRam: Boolean,
    val mediaPerfClass: Int,        // API 31+; 0 when lower or unknown
    val hwAvcEnc1080p60: Boolean,   // ExportCapabilities 1080p60 hardware encode capability
    val hwAvcDecInstances: Int      // CodecCapabilities.maxSupportedInstances upper-bound hint
)

enum class Tier { LOW, MID, HIGH }

data class QualityBudget(
    val tier: Tier,
    val pipDecodeThreads: Int,
    val overlayTexBudgetMb: Int,
    val pipUpdateFps: Int,
    val autoProxy: Boolean,
    val thumbnailMemEntries: Int = 256,
    val recommendedExportResolution: Int = 1080,
    val recommendedExportFps: Int = 30
)
