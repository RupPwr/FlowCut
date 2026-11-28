package com.flowcut.app.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * ThumbnailService provides high-performance, background-decoded filmstrip tile thumbnails
 * for main-track video clips on TimelineCanvas.
 *
 * Enforces Rule R10:
 * - Lock-free, zero-allocation peek() hot-path for 60/120fps render loops.
 * - OPTION_CLOSEST_SYNC seeking (keyframe-accurate) to ensure sub-millisecond extraction.
 * - Two-tier caching: bounded in-memory LRU ImageBitmap cache + on-disk JPEG cache.
 * - Cancellable tile decoding to prevent decoding thrash during fast timeline scrubs.
 */
class ThumbnailService(
    val diskDir: File,
    private val scope: CoroutineScope,
    @Volatile private var maxMemEntries: Int = defaultMaxMemEntries,
    private val maxDiskBytes: Long = DEFAULT_MAX_DISK_BYTES
) {
    constructor(
        context: Context,
        scope: CoroutineScope,
        maxMemEntries: Int = defaultMaxMemEntries,
        maxDiskBytes: Long = DEFAULT_MAX_DISK_BYTES
    ) : this(
        context.noBackupFilesDir.resolve("main_thumbnails"),
        scope,
        maxMemEntries,
        maxDiskBytes
    )

    data class TileRequest(val path: String, val timeUs: Long) {
        val bucketUs: Long get() = bucketTimeUs(timeUs)
        val key: String get() = tileKey(path, bucketUs)
    }

    private class CachedTile(
        val path: String,
        val bucketUs: Long,
        val bitmap: ImageBitmap
    )

    private class LongBitmapTable(
        val buckets: LongArray,
        val bitmaps: Array<ImageBitmap>
    ) {
        fun get(bucketUs: Long): ImageBitmap? {
            var lo = 0
            var hi = buckets.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = buckets[mid]
                if (v < bucketUs) {
                    lo = mid + 1
                } else if (v > bucketUs) {
                    hi = mid - 1
                } else {
                    return bitmaps[mid]
                }
            }
            return null
        }
    }

    private class RetrieverSlot(
        var path: String,
        val retriever: MediaMetadataRetriever,
        var lastUsedMs: Long,
        val lock: Any = Any()
    )

    // Copy-on-write memory cache: reads in peek() are strictly lock-free and zero-allocation (Rule R10)
    @Volatile
    private var ready: Map<String, CachedTile> = emptyMap()

    @Volatile
    private var readyByPath: Map<String, LongBitmapTable> = emptyMap()

    private val inFlight = ConcurrentHashMap<String, Job>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    var isPaused: () -> Boolean = { false }
    private val retrieverPoolLock = Any()
    private val retrieverPool = ArrayList<RetrieverSlot>(MAX_RETRIEVER_SLOTS)
    @Volatile
    private var poolCleared = false
    private var diskWritesSincePrune = 0

    private fun acquireRetrieverSlot(path: String): RetrieverSlot? {
        synchronized(retrieverPoolLock) {
            if (poolCleared || path.isEmpty()) return null
            val now = System.currentTimeMillis()
            val existing = retrieverPool.firstOrNull { it.path == path }
            if (existing != null) {
                existing.lastUsedMs = now
                return existing
            }
            if (retrieverPool.size < MAX_RETRIEVER_SLOTS) {
                val r = MediaMetadataRetriever()
                return try {
                    r.setDataSource(path)
                    val slot = RetrieverSlot(path, r, now)
                    retrieverPool.add(slot)
                    slot
                } catch (_: Throwable) {
                    try { r.release() } catch (_: Throwable) {}
                    null
                }
            }
            val lru = retrieverPool.minByOrNull { it.lastUsedMs } ?: retrieverPool[0]
            synchronized(lru.lock) {
                return try {
                    lru.retriever.setDataSource(path)
                    lru.path = path
                    lru.lastUsedMs = now
                    lru
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    private fun releaseRetrieverForPath(path: String) {
        synchronized(retrieverPoolLock) {
            val idx = retrieverPool.indexOfFirst { it.path == path }
            if (idx >= 0) {
                val slot = retrieverPool.removeAt(idx)
                synchronized(slot.lock) {
                    try { slot.retriever.release() } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun rebuildPathIndex(source: Map<String, CachedTile>): Map<String, LongBitmapTable> {
        if (source.isEmpty()) return emptyMap()
        val grouped = HashMap<String, ArrayList<CachedTile>>()
        for (tile in source.values) {
            grouped.getOrPut(tile.path) { ArrayList() }.add(tile)
        }
        val result = HashMap<String, LongBitmapTable>(grouped.size)
        for ((p, list) in grouped) {
            list.sortBy { it.bucketUs }
            val buckets = LongArray(list.size)
            val bitmaps = Array(list.size) { i ->
                buckets[i] = list[i].bucketUs
                list[i].bitmap
            }
            result[p] = LongBitmapTable(buckets, bitmaps)
        }
        return result
    }

    var onGeneratingChanged: ((Boolean) -> Unit)? = null
    @Volatile
    private var lastGeneratingReported: Boolean = false

    private fun checkGeneratingState() {
        val isGen = inFlight.isNotEmpty()
        if (isGen != lastGeneratingReported) {
            lastGeneratingReported = isGen
            val cb = onGeneratingChanged
            if (cb != null) {
                scope.launch(Dispatchers.Main) { cb(isGen) }
            }
        }
    }

    private var lastNotifyTimeMs = 0L
    private var pendingNotifyJob: Job? = null
    private val notifyLock = Any()

    private fun notifyChangeThrottled(onChanged: () -> Unit) {
        val now = System.currentTimeMillis()
        synchronized(notifyLock) {
            if (now - lastNotifyTimeMs >= 120L) {
                lastNotifyTimeMs = now
                pendingNotifyJob?.cancel()
                pendingNotifyJob = null
                scope.launch(Dispatchers.Main) { onChanged() }
            } else if (pendingNotifyJob == null || pendingNotifyJob?.isActive != true) {
                val waitMs = (120L - (now - lastNotifyTimeMs)).coerceAtLeast(10L)
                pendingNotifyJob = scope.launch(Dispatchers.Main) {
                    delay(waitMs)
                    synchronized(notifyLock) {
                        lastNotifyTimeMs = System.currentTimeMillis()
                        pendingNotifyJob = null
                    }
                    onChanged()
                }
            }
        }
    }

    init {
        activeInstance = this
        try {
            if (!diskDir.exists()) {
                diskDir.mkdirs()
            }
        } catch (_: Throwable) {}
    }

    /**
     * Hot-path safe lookup for TimelineCanvas onDraw (Rule R10).
     * Strictly lock-free, zero heap allocation, and zero disk/network I/O.
     */
    fun peek(path: String, timeUs: Long): ImageBitmap? {
        if (path.isEmpty()) return null
        val table = readyByPath[path] ?: return null
        return table.get(bucketTimeUs(timeUs))
    }

    /**
     * Enqueues background decoding for a batch of visible timeline tiles.
     * Stale requests can be cancelled via cancelStaleRequests().
     */
    fun requestTiles(requests: List<TileRequest>, onChanged: () -> Unit) {
        val reqKeys = requests.map { it.key }.toSet()
        val iter = inFlight.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (!reqKeys.contains(entry.key)) {
                entry.value.cancel()
                iter.remove()
            }
        }

        val capped = requests.take(16)
        for (req in capped) {
            if (req.path.isEmpty()) continue
            val key = req.key
            if (ready.containsKey(key) || failed.contains(key) || inFlight.containsKey(key)) {
                continue
            }

            val job = scope.launch(Dispatchers.IO) {
                try {
                    coroutineContext.ensureActive()
                    val img = loadTile(req.path, req.bucketUs, key)
                    if (img != null) {
                        publish(key, req.path, req.bucketUs, img)
                        notifyChangeThrottled(onChanged)
                    } else {
                        failed.add(key)
                    }
                } catch (_: CancellationException) {
                    // Cancelled gracefully
                } catch (_: Throwable) {
                    failed.add(key)
                } finally {
                    inFlight.remove(key)
                    checkGeneratingState()
                }
            }
            inFlight[key] = job
        }
        checkGeneratingState()
    }

    /**
     * Cancels pending decode jobs for tiles that scrolled out of view.
     */
    fun cancelStaleRequests(activeKeys: Set<String>) {
        val iter = inFlight.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (!activeKeys.contains(entry.key)) {
                entry.value.cancel()
                iter.remove()
            }
        }
        checkGeneratingState()
    }

    /**
     * Clears all in-memory and in-flight states.
     */
    fun clearMemory() {
        poolCleared = true
        synchronized(notifyLock) {
            pendingNotifyJob?.cancel()
            pendingNotifyJob = null
        }
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        failed.clear()
        synchronized(retrieverPoolLock) {
            for (slot in retrieverPool) {
                synchronized(slot.lock) {
                    try { slot.retriever.release() } catch (_: Throwable) {}
                }
            }
            retrieverPool.clear()
        }
        synchronized(this) {
            ready = emptyMap()
            readyByPath = emptyMap()
        }
        if (activeInstance === this) {
            activeInstance = null
        }
        poolCleared = false
        checkGeneratingState()
    }

    /**
     * Dynamically shrinks in-memory filmstrip bitmap cache under low-RAM or thermal pressure
     * without wiping the most recently used tiles currently visible on screen.
     */
    @Synchronized
    fun trimMemory(maxKeepEntries: Int) {
        val target = maxKeepEntries.coerceAtLeast(0)
        if (ready.size <= target) return
        if (target == 0) {
            ready = emptyMap()
            readyByPath = emptyMap()
            return
        }
        val next = LinkedHashMap(ready)
        while (next.size > target) {
            val oldest = next.keys.firstOrNull() ?: break
            next.remove(oldest)
        }
        ready = next
        readyByPath = rebuildPathIndex(next)
    }

    /**
     * Clears all disk cache files.
     */
    fun clearDiskCache() {
        try {
            diskDir.listFiles()?.forEach { file ->
                try { file.delete() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private suspend fun loadTile(path: String, bucketUs: Long, key: String): ImageBitmap? {
        val diskFile = File(diskDir, "$key.jpg")
        if (diskFile.exists() && diskFile.length() > 0L) {
            try {
                val cachedBmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (cachedBmp != null) {
                    diskFile.setLastModified(System.currentTimeMillis())
                    return cachedBmp.asImageBitmap()
                }
            } catch (_: Throwable) {}
        }

        // Stale scroll check BEFORE acquiring decodeSemaphore
        kotlin.coroutines.coroutineContext.ensureActive()

        while (isPaused()) {
            delay(100L)
            kotlin.coroutines.coroutineContext.ensureActive()
        }

        return decodeSemaphore.withPermit {
            // Stale scroll check AFTER acquiring semaphore
            kotlin.coroutines.coroutineContext.ensureActive()

            while (isPaused()) {
                delay(100L)
                kotlin.coroutines.coroutineContext.ensureActive()
            }

            // Decode using OPTION_CLOSEST_SYNC for ultra-fast keyframe extraction
            try {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                } catch (_: Throwable) {}
                val slot = acquireRetrieverSlot(path) ?: return@withPermit null
                kotlin.coroutines.coroutineContext.ensureActive()
                val bmp = synchronized(slot.lock) {
                    if (poolCleared || slot.path != path) {
                        null
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        slot.retriever.getScaledFrameAtTime(
                            bucketUs.coerceAtLeast(0L),
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            TILE_WIDTH_PX,
                            TILE_HEIGHT_PX
                        ) ?: slot.retriever.getFrameAtTime(
                            bucketUs.coerceAtLeast(0L),
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    } else {
                        slot.retriever.getFrameAtTime(
                            bucketUs.coerceAtLeast(0L),
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }
                }

                if (bmp != null) {
                    // Persist to disk cache
                    try {
                        val temp = File(diskDir, "$key.jpg.tmp")
                        FileOutputStream(temp).use { fos ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 80, fos)
                        }
                        if (diskFile.exists()) diskFile.delete()
                        if (temp.renameTo(diskFile)) {
                            diskFile.setLastModified(System.currentTimeMillis())
                            diskWritesSincePrune++
                            if (diskWritesSincePrune >= DISK_PRUNE_INTERVAL) {
                                diskWritesSincePrune = 0
                                pruneDiskCache()
                            }
                        }
                    } catch (_: Throwable) {}
                    bmp.asImageBitmap()
                } else {
                    null
                }
            } catch (_: CancellationException) {
                null
            } catch (_: Throwable) {
                releaseRetrieverForPath(path)
                null
            }
        }
    }

    @Synchronized
    private fun publish(key: String, path: String, bucketUs: Long, img: ImageBitmap) {
        val next = LinkedHashMap(ready)
        next[key] = CachedTile(path, bucketUs, img)
        while (next.size > maxMemEntries) {
            val oldest = next.keys.firstOrNull() ?: break
            next.remove(oldest)
        }
        ready = next
        readyByPath = rebuildPathIndex(next)
    }

    private fun pruneDiskCache() {
        try {
            val files = diskDir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") } ?: return
            var totalBytes = files.sumOf { it.length() }
            if (totalBytes <= maxDiskBytes) return

            val oldestFirst = files.sortedBy { it.lastModified() }
            for (f in oldestFirst) {
                val len = f.length()
                if (f.delete()) {
                    totalBytes -= len
                    if (totalBytes <= maxDiskBytes) break
                }
            }
        } catch (_: Throwable) {}
    }

    companion object {
        private val decodeSemaphore = Semaphore(1)
        private const val MAX_RETRIEVER_SLOTS = 4
        private const val DISK_PRUNE_INTERVAL = 20
        private val HEX_CHARS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
        )
        const val TILE_WIDTH_PX = 80
        const val TILE_HEIGHT_PX = 80
        const val DEFAULT_MAX_DISK_BYTES = 100L * 1024L * 1024L // 100 MB

        @Volatile
        var defaultMaxMemEntries: Int = 256

        @Volatile
        var activeInstance: ThumbnailService? = null

        fun trimActiveMemory(maxKeepEntries: Int) {
            activeInstance?.trimMemory(maxKeepEntries)
        }

        /**
         * Quantizes timestamp to nearest bucket so timeline scrub hits cached keyframes.
         * Pure Kotlin for JVM unit testing without Android dependencies.
         */
        fun bucketTimeUs(timeUs: Long, bucketStepUs: Long = 500_000L): Long {
            require(bucketStepUs > 0L) { "bucketStepUs must be > 0" }
            return (timeUs.coerceAtLeast(0L) / bucketStepUs) * bucketStepUs
        }

        /**
         * Deterministic SHA-256 cache key for source media tile.
         * Pure Kotlin for JVM unit testing without Android dependencies.
         */
        fun tileKey(path: String, bucketUs: Long): String {
            val input = "$path:$bucketUs"
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            val chars = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val b = bytes[i].toInt() and 0xFF
                chars[i * 2] = HEX_CHARS[b ushr 4]
                chars[i * 2 + 1] = HEX_CHARS[b and 0x0F]
            }
            return String(chars)
        }
    }
}

fun bucketTimeUs(timeUs: Long, bucketStepUs: Long = 500_000L): Long =
    ThumbnailService.bucketTimeUs(timeUs, bucketStepUs)

fun tileKey(path: String, bucketUs: Long): String =
    ThumbnailService.tileKey(path, bucketUs)
