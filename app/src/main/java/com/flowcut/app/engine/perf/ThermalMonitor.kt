package com.flowcut.app.engine.perf

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Android thermal status and headroom monitor.
 * Thermal headroom is queried at most once every 10 seconds on a single background executor thread (docs).
 * Unsupported chipsets returning Float.NaN or throwing UnsupportedOperationException are handled gracefully.
 */
class ThermalMonitor(
    ctx: Context,
    private val exec: ScheduledExecutorService
) {
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Volatile
    var status: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.THERMAL_STATUS_NONE
    } else {
        0
    }
        private set

    @Volatile
    var headroom: Float = Float.NaN
        private set

    val isModerate: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            status >= PowerManager.THERMAL_STATUS_MODERATE
        } else {
            false
        }

    val isSevere: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            status >= PowerManager.THERMAL_STATUS_SEVERE
        } else {
            false
        }

    private var scheduledTask: ScheduledFuture<*>? = null

    private val listener: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { s ->
            status = s
        }
    } else {
        null
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && listener is PowerManager.OnThermalStatusChangedListener) {
            try {
                pm?.addThermalStatusListener(exec, listener)
            } catch (_: Throwable) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                scheduledTask = exec.scheduleWithFixedDelay(
                    {
                        try {
                            headroom = pm?.getThermalHeadroom(10) ?: Float.NaN
                        } catch (_: Throwable) {
                            headroom = Float.NaN
                        }
                    },
                    0,
                    10,
                    TimeUnit.SECONDS
                )
            } catch (_: Throwable) {}
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && listener is PowerManager.OnThermalStatusChangedListener) {
            try {
                pm?.removeThermalStatusListener(listener)
            } catch (_: Throwable) {}
        }
        try {
            scheduledTask?.cancel(false)
            scheduledTask = null
        } catch (_: Throwable) {}
    }
}
