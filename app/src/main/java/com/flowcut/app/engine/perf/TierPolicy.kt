package com.flowcut.app.engine.perf

/**
 * Pure Kotlin tier classification and resource budget policy.
 * Zero android.* dependencies to ensure 100% JVM unit testability.
 */
object TierPolicy {

    /**
     * Maps hardware profile to device tier.
     * Note: "3GB" phones typically report totalMem around 2700-2900 MB, hence the 3300 MB threshold.
     */
    fun tier(p: DeviceProfile): Tier = when {
        p.isLowRam || p.totalRamMb < 3300 || !p.hwAvcEnc1080p60 || p.hwAvcDecInstances <= 2 -> Tier.LOW
        p.totalRamMb < 6000 -> Tier.MID
        else -> Tier.HIGH
    }

    /**
     * Resolves quality and resource budgets based on device profile tier.
     */
    fun budget(p: DeviceProfile): QualityBudget = when (tier(p)) {
        Tier.LOW -> QualityBudget(
            tier = Tier.LOW,
            pipDecodeThreads = 1,
            overlayTexBudgetMb = 64,
            pipUpdateFps = 15,
            autoProxy = true,
            thumbnailMemEntries = 96,
            recommendedExportResolution = 720,
            recommendedExportFps = 30
        )
        Tier.MID -> QualityBudget(
            tier = Tier.MID,
            pipDecodeThreads = 2,
            overlayTexBudgetMb = 128,
            pipUpdateFps = 30,
            autoProxy = true,
            thumbnailMemEntries = 160,
            recommendedExportResolution = 1080,
            recommendedExportFps = 30
        )
        Tier.HIGH -> QualityBudget(
            tier = Tier.HIGH,
            pipDecodeThreads = 2,
            overlayTexBudgetMb = 256,
            pipUpdateFps = 30,
            autoProxy = false,
            thumbnailMemEntries = 256,
            recommendedExportResolution = 1080,
            recommendedExportFps = 30
        )
    }
}
