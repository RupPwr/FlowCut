package com.flowcut.app.engine.export

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

sealed class PreflightResult {
    data class Ok(val estimatedBytes: Long, val freeBytes: Long) : PreflightResult()
    data class InsufficientStorage(
        val requiredBytes: Long,
        val availableBytes: Long,
        val message: String
    ) : PreflightResult()
}

object ExportPreflight {

    /** 200 MB safety margin as required by architecture plan §5 Task T1.8 */
    const val MARGIN_BYTES = 200L * 1024L * 1024L

    /**
     * Estimates output size in bytes using:
     * ((videoBitrateBps + 192_000L audio) * durationMs) / 8_000L
     */
    fun estimateBytes(durationMs: Long, videoBitrateBps: Int): Long {
        val durMs = durationMs.coerceAtLeast(0L)
        val totalBitrateBps = videoBitrateBps.toLong() + 192_000L
        return (totalBitrateBps * durMs) / 8_000L
    }

    /**
     * Pure-Kotlin testable space evaluation function.
     * When sameVolume == true (temp+copy on same mount), requires (estimated * 2) + MARGIN_BYTES.
     * When sameVolume == false, requires estimated + MARGIN_BYTES on each volume independently.
     */
    fun checkSpace(
        estimatedBytes: Long,
        cacheFreeBytes: Long,
        moviesFreeBytes: Long,
        sameVolume: Boolean
    ): PreflightResult {
        val est = estimatedBytes.coerceAtLeast(0L)

        if (sameVolume) {
            val required = (est * 2L) + MARGIN_BYTES
            val available = minOf(cacheFreeBytes, moviesFreeBytes).coerceAtLeast(0L)
            if (available < required) {
                val reqMb = required / (1024L * 1024L)
                val availMb = available / (1024L * 1024L)
                return PreflightResult.InsufficientStorage(
                    requiredBytes = required,
                    availableBytes = available,
                    message = "Not enough storage space to export. Required: ~${reqMb} MB (including 200 MB buffer), but only ${availMb} MB is free."
                )
            }
            return PreflightResult.Ok(estimatedBytes = est, freeBytes = available)
        } else {
            val requiredPerVolume = est + MARGIN_BYTES
            val cFree = cacheFreeBytes.coerceAtLeast(0L)
            val mFree = moviesFreeBytes.coerceAtLeast(0L)

            if (cFree < requiredPerVolume || mFree < requiredPerVolume) {
                val limitingAvailable = minOf(cFree, mFree)
                val reqMb = requiredPerVolume / (1024L * 1024L)
                val availMb = limitingAvailable / (1024L * 1024L)
                val volumeName = if (cFree < requiredPerVolume) "app cache volume" else "Movies volume"
                return PreflightResult.InsufficientStorage(
                    requiredBytes = requiredPerVolume,
                    availableBytes = limitingAvailable,
                    message = "Not enough storage space on $volumeName. Required: ~${reqMb} MB (including 200 MB buffer), but only ${availMb} MB is free."
                )
            }
            return PreflightResult.Ok(estimatedBytes = est, freeBytes = minOf(cFree, mFree))
        }
    }

    /**
     * Android runtime check evaluating cacheDir and Movies directory free space.
     * Under Android 11+ Scoped Storage, creating /sdcard/Movies directly via File.mkdirs() can
     * fail with EACCES if the folder does not yet exist, causing StatFs to throw and usableSpace
     * to return 0L. Resolve an existing directory on the target external volume before probing.
     */
    fun check(context: Context, durationMs: Long, videoBitrateBps: Int): PreflightResult {
        val estimated = estimateBytes(durationMs, videoBitrateBps)
        val cacheDir = context.cacheDir
        val moviesProbeDir = resolveExistingMoviesVolumeDir(context)

        val cacheFree = queryFreeBytes(cacheDir)
        val moviesFree = queryFreeBytes(moviesProbeDir)

        val sameVolume = isSameVolume(context, cacheDir, moviesProbeDir)
        return checkSpace(
            estimatedBytes = estimated,
            cacheFreeBytes = cacheFree,
            moviesFreeBytes = moviesFree,
            sameVolume = sameVolume
        )
    }

    private fun resolveExistingMoviesVolumeDir(context: Context): File {
        val publicMovies = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        } catch (_: Throwable) {
            null
        }
        if (publicMovies != null && publicMovies.exists()) {
            return publicMovies
        }
        val appExternalMovies = try {
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        } catch (_: Throwable) {
            null
        }
        if (appExternalMovies != null && (appExternalMovies.exists() || appExternalMovies.mkdirs())) {
            return appExternalMovies
        }
        val externalRoot = try {
            Environment.getExternalStorageDirectory()
        } catch (_: Throwable) {
            null
        }
        if (externalRoot != null && externalRoot.exists()) {
            return externalRoot
        }
        return context.filesDir
    }

    private fun queryFreeBytes(dir: File): Long {
        return try {
            StatFs(dir.absolutePath).availableBytes
        } catch (_: Throwable) {
            dir.usableSpace
        }
    }

    private fun isSameVolume(context: Context, fileA: File, fileB: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                if (sm != null) {
                    val uuidA = sm.getUuidForPath(fileA)
                    val uuidB = sm.getUuidForPath(fileB)
                    return uuidA == uuidB
                }
            } catch (_: Throwable) {}
        }
        return try {
            val rootA = fileA.canonicalPath.trimStart('/').substringBefore('/', "")
            val rootB = fileB.canonicalPath.trimStart('/').substringBefore('/', "")
            rootA.equals(rootB, ignoreCase = true)
        } catch (_: Throwable) {
            true // Default to sameVolume for safety
        }
    }
}
