package com.flowcut.app.engine.perf

import android.content.Context

/**
 * Crash-loop guard preventing repeated hard crashes / LMK kills on problematic specs.
 * Writes a marker on export start and clears on completion/cancel/handled failure.
 * If app launches with an uncleared marker, the spec is blamed (+1 crash count).
 * A spec with >= 2 crashes is excluded from candidates.
 */
class SessionGuard(ctx: Context) {
    private val sp = ctx.getSharedPreferences("flowcut_guard", Context.MODE_PRIVATE)

    fun markExportStart(specKey: String) {
        sp.edit().putString("inflight", specKey).commit()
    }

    fun markExportEnd() {
        sp.edit().remove("inflight").commit()
    }

    /**
     * Called at app startup. If a spec remained marked as in-flight, it crashed or was killed by LMK.
     */
    fun recordCrashIfAny() {
        val k = sp.getString("inflight", null) ?: return
        val currentFails = sp.getInt("fail_$k", 0)
        sp.edit()
            .remove("inflight")
            .putInt("fail_$k", currentFails + 1)
            .commit()
    }

    fun isExcluded(specKey: String): Boolean = sp.getInt("fail_$specKey", 0) >= 2

    fun resetFailures(specKey: String) {
        sp.edit().remove("fail_$specKey").commit()
    }
}
