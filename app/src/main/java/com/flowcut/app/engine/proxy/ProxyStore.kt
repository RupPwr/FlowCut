package com.flowcut.app.engine.proxy

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * ProxyStore manages persistent storage and lifecycle for PIP video proxies.
 *
 * Proxies are stored inside `context.noBackupFilesDir.resolve("pip_proxies")` with a default
 * size budget of 1 GB (1_024 MB). Oldest proxies are evicted according to LRU timestamp.
 */
class ProxyStore(
    val proxyDir: File,
    val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    constructor(context: Context, maxBytes: Long = DEFAULT_MAX_BYTES) : this(
        context.noBackupFilesDir.resolve("pip_proxies"),
        maxBytes
    )

    private val lock = Any()

    init {
        try {
            if (!proxyDir.exists()) {
                proxyDir.mkdirs()
            }
        } catch (_: Throwable) {}
    }

    /**
     * Thread-safe lookup for a ready proxy.
     * Returns valid absolute file path ONLY when the .mp4 proxy is completely written,
     * updating its lastModified timestamp for LRU eviction.
     */
    fun getReadyProxyPath(sourcePath: String): String? {
        if (sourcePath.isEmpty()) return null
        val key = computeKeyForSource(sourcePath)
        return getReadyProxyPathForKey(key)
    }

    /**
     * Looks up ready proxy by cache key.
     */
    fun getReadyProxyPathForKey(key: String): String? {
        if (key.isEmpty()) return null
        val proxyFile = getProxyFile(key)
        synchronized(lock) {
            if (proxyFile.exists() && proxyFile.isFile && proxyFile.length() > 0L) {
                proxyFile.setLastModified(System.currentTimeMillis())
                return proxyFile.absolutePath
            }
        }
        return null
    }

    /**
     * Computes cache key from source file attributes on disk.
     */
    fun computeKeyForSource(sourcePath: String): String {
        val file = File(sourcePath)
        val (size, mtime) = if (file.exists()) {
            Pair(file.length(), file.lastModified())
        } else {
            Pair(0L, 0L)
        }
        return computeCacheKey(sourcePath, size, mtime)
    }

    /**
     * Final proxy file destination for a given cache key.
     */
    fun getProxyFile(key: String): File = File(proxyDir, "$key.mp4")

    /**
     * Temporary proxy file written during transcode before atomic rename.
     */
    fun getTempProxyFile(key: String): File = File(proxyDir, "$key.mp4.tmp")

    /**
     * Atomically commits a completed temporary proxy file to its final destination.
     * Updates lastModified timestamp and triggers budget pruning.
     */
    fun commitProxy(key: String): File? {
        val temp = getTempProxyFile(key)
        val target = getProxyFile(key)
        synchronized(lock) {
            if (!temp.exists() || temp.length() == 0L) {
                try { temp.delete() } catch (_: Throwable) {}
                return null
            }
            if (target.exists()) {
                target.delete()
            }
            if (temp.renameTo(target)) {
                target.setLastModified(System.currentTimeMillis())
                pruneToBudget()
                return target
            }
            return null
        }
    }

    /**
     * Evicts oldest proxy files (lastModified ascending) until total directory size is <= maxBytes.
     */
    fun pruneToBudget() {
        synchronized(lock) {
            try {
                val files = proxyDir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") } ?: return
                var totalBytes = files.sumOf { it.length() }
                if (totalBytes <= maxBytes) return

                val oldestFirst = files.sortedBy { it.lastModified() }
                for (f in oldestFirst) {
                    val len = f.length()
                    if (f.delete()) {
                        totalBytes -= len
                        if (totalBytes <= maxBytes) break
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Deletes proxy files (both target and temp) associated with the given source path.
     */
    fun deleteProxyFor(sourcePath: String) {
        if (sourcePath.isEmpty()) return
        val key = computeKeyForSource(sourcePath)
        deleteProxyForKey(key)
    }

    /**
     * Deletes proxy files associated with the given cache key.
     */
    fun deleteProxyForKey(key: String) {
        if (key.isEmpty()) return
        synchronized(lock) {
            val target = getProxyFile(key)
            val temp = getTempProxyFile(key)
            try { if (target.exists()) target.delete() } catch (_: Throwable) {}
            try { if (temp.exists()) temp.delete() } catch (_: Throwable) {}
        }
    }

    /**
     * Clears all proxy files in the store.
     */
    fun clearAll() {
        synchronized(lock) {
            try {
                proxyDir.listFiles()?.forEach { file ->
                    try { file.delete() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 1_024L * 1024L * 1024L // 1 GB cap

        /**
         * Computes deterministic SHA-256 cache key for source media.
         * Pure Kotlin for JVM unit testing without Android dependencies.
         */
        fun computeCacheKey(sourcePath: String, fileSize: Long, lastModifiedMs: Long): String {
            val input = "$sourcePath:$fileSize:$lastModifiedMs"
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Computes proxy dimensions scaled down so longest side <= maxSide (default 640),
         * applying 90/270 degree rotation swap, avoiding upscaling for small sources,
         * and aligning both width and height to even numbers.
         */
        fun computeProxyDimensions(
            srcW: Int,
            srcH: Int,
            rotationDeg: Int,
            maxSide: Int = 640
        ): Pair<Int, Int> {
            val safeW = srcW.coerceAtLeast(1)
            val safeH = srcH.coerceAtLeast(1)
            val normalizedRot = ((rotationDeg % 360) + 360) % 360
            val (effW, effH) = if (normalizedRot == 90 || normalizedRot == 270) {
                Pair(safeH, safeW)
            } else {
                Pair(safeW, safeH)
            }
            val longest = maxOf(effW, effH)
            val (scaledW, scaledH) = if (longest > maxSide) {
                val scale = maxSide.toDouble() / longest.toDouble()
                Pair(
                    (effW * scale).roundToInt(),
                    (effH * scale).roundToInt()
                )
            } else {
                Pair(effW, effH)
            }
            val finalW = (scaledW and 1.inv()).coerceAtLeast(2)
            val finalH = (scaledH and 1.inv()).coerceAtLeast(2)
            return Pair(finalW, finalH)
        }
    }
}

fun computeCacheKey(sourcePath: String, fileSize: Long, lastModifiedMs: Long): String =
    ProxyStore.computeCacheKey(sourcePath, fileSize, lastModifiedMs)

fun computeProxyDimensions(srcW: Int, srcH: Int, rotationDeg: Int, maxSide: Int = 640): Pair<Int, Int> =
    ProxyStore.computeProxyDimensions(srcW, srcH, rotationDeg, maxSide)
