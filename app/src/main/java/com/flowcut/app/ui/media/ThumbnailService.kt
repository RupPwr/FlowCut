package com.flowcut.app.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Process
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
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * ThumbnailService provides high-performance, background-decoded filmstrip tile thumbnails
 * for main-track video and image clips on TimelineCanvas.
 *
 * Enforces Rule R10:
 * - Lock-free, zero-allocation peek() hot-path for 60/120fps render loops (exact + nearest fallback).
 * - OPTION_CLOSEST_SYNC seeking (keyframe-accurate) with bounded 2-way concurrency and idle release.
 * - Two-tier caching: bounded in-memory LRU ImageBitmap cache + on-disk JPEG cache (RGB_565).
 * - Cancellable tile decoding without blacklisting cancelled tiles during timeline scrubs.
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
        val bucketUs: Long = bucketTimeUs(timeUs)
        val key: String by lazy(LazyThreadSafetyMode.PUBLICATION) { tileKey(path, bucketUs) }
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
        fun getNearest(bucketUs: Long, maxDeltaUs: Long): ImageBitmap? {
            val idx = findNearestBucketIndex(buckets, bucketUs, maxDeltaUs)
            return if (idx >= 0) bitmaps[idx] else null
        }
    }

    private class RetrieverSlot(
        var path: String,
        val retriever: MediaMetadataRetriever,
        var lastUsedMs: Long,
        var inUse: Boolean = false
    )

    // Copy-on-write memory cache: reads in peek() are strictly lock-free and zero-allocation (Rule R10)
    @Volatile
    private var ready: Map<String, CachedTile> = emptyMap()

    @Volatile
    private var readyByPath: Map<String, LongBitmapTable> = emptyMap()

    private val inFlight = ConcurrentHashMap<String, Job>()
    private val failureCounts = ConcurrentHashMap<String, Int>()

    var isPaused: () -> Boolean = { false }

    private val retrieverPoolLock = Any()
    private val retrieverPool = ArrayList<RetrieverSlot>(MAX_RETRIEVER_SLOTS)

    @Volatile
    private var poolCleared = false
    private val diskWritesSincePrune = AtomicInteger(0)

    private var idleReleaseJob: Job? = null
    private val idleReleaseLock = Any()

    private fun cancelIdleRelease() {
        synchronized(idleReleaseLock) {
            idleReleaseJob?.cancel()
            idleReleaseJob = null
        }
    }

    private fun scheduleIdleRelease() {
        synchronized(idleReleaseLock) {
            idleReleaseJob?.cancel()
            idleReleaseJob = scope.launch(Dispatchers.IO) {
                delay(IDLE_RETRIEVER_RELEASE_MS)
                if (inFlight.isEmpty()) {
                    releaseUnusedRetrievers()
                }
            }
        }
    }

    private fun releaseUnusedRetrievers() {
        val toRelease = ArrayList<MediaMetadataRetriever>()
        synchronized(retrieverPoolLock) {
            val iter = retrieverPool.iterator()
            while (iter.hasNext()) {
                val slot = iter.next()
                if (!slot.inUse) {
                    toRelease.add(slot.retriever)
                    iter.remove()
                }
            }
        }
        for (r in toRelease) {
            try { r.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Checks out an exclusive RetrieverSlot for [path].
     * Slow MediaMetadataRetriever.setDataSource() calls run OUTSIDE retrieverPoolLock
     * so concurrent workers never block each other on file open.
     */
    private fun checkoutRetrieverSlot(path: String): RetrieverSlot? {
        if (poolCleared || path.isEmpty()) return null
        val now = System.currentTimeMillis()
        var recycled: RetrieverSlot? = null
        var shouldCreateNew = false

        synchronized(retrieverPoolLock) {
            if (poolCleared) return null
            // 1. Prefer an idle slot already bound to this exact path
            val exact = retrieverPool.firstOrNull { !it.inUse && it.path == path }
            if (exact != null) {
                exact.inUse = true
                exact.lastUsedMs = now
                return exact
            }
            // 2. Grow pool up to MAX_RETRIEVER_SLOTS
            if (retrieverPool.size < MAX_RETRIEVER_SLOTS) {
                shouldCreateNew = true
            } else {
                // 3. Rebind least-recently-used idle slot
                val lru = retrieverPool.filter { !it.inUse }.minByOrNull { it.lastUsedMs }
                if (lru != null) {
                    lru.inUse = true
                    lru.lastUsedMs = now
                    recycled = lru
                }
            }
        }

        if (shouldCreateNew) {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(path)
                val slot = RetrieverSlot(path, r, now, inUse = true)
                var accepted = false
                synchronized(retrieverPoolLock) {
                    if (!poolCleared) {
                        retrieverPool.add(slot)
                        accepted = true
                    }
                }
                if (accepted) {
                    slot
                } else {
                    try { r.release() } catch (_: Throwable) {}
                    null
                }
            } catch (_: Throwable) {
                try { r.release() } catch (_: Throwable) {}
                null
            }
        }

        val slot = recycled ?: return null
        return try {
            slot.retriever.setDataSource(path)
            slot.path = path
            slot.lastUsedMs = System.currentTimeMillis()
            slot
        } catch (_: Throwable) {
            returnRetrieverSlot(slot, broken = true)
            null
        }
    }

    private fun returnRetrieverSlot(slot: RetrieverSlot, broken: Boolean) {
        var releaseNow = false
        synchronized(retrieverPoolLock) {
            if (broken || poolCleared) {
                retrieverPool.remove(slot)
                releaseNow = true
            } else {
                slot.inUse = false
                slot.lastUsedMs = System.currentTimeMillis()
            }
        }
        if (releaseNow) {
            try { slot.retriever.release() } catch (_: Throwable) {}
        }
    }

    private fun buildPathTable(source: Map<String, CachedTile>, targetPath: String): LongBitmapTable? {
        val list = ArrayList<CachedTile>()
        for (tile in source.values) {
            if (tile.path == targetPath) {
                list.add(tile)
            }
        }
        if (list.isEmpty()) return null
        list.sortBy { it.bucketUs }
        val buckets = LongArray(list.size)
        val bitmaps = Array(list.size) { i ->
            buckets[i] = list[i].bucketUs
            list[i].bitmap
        }
        return LongBitmapTable(buckets, bitmaps)
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
        if (isGen) {
            cancelIdleRelease()
        } else {
            scheduleIdleRelease()
        }
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
            if (now - lastNotifyTimeMs >= NOTIFY_THROTTLE_MS) {
                lastNotifyTimeMs = now
                pendingNotifyJob?.cancel()
                pendingNotifyJob = null
                scope.launch(Dispatchers.Main) { onChanged() }
            } else if (pendingNotifyJob == null || pendingNotifyJob?.isActive != true) {
                val waitMs = (NOTIFY_THROTTLE_MS - (now - lastNotifyTimeMs)).coerceAtLeast(16L)
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
     * Returns exact bucket hit when ready, or nearest cached tile within MAX_FALLBACK_DELTA_US
     * so filmstrips never flash empty during zoom or rapid scroll.
     */
    fun peek(path: String, timeUs: Long): ImageBitmap? {
        if (path.isEmpty()) return null
        val table = readyByPath[path] ?: return null
        return table.getNearest(bucketTimeUs(timeUs), MAX_FALLBACK_DELTA_US)
    }

    /**
     * Enqueues background decoding for a batch of visible timeline tiles.
     * Preserves caller priority order (closest-to-playhead first) and cancels stale tiles.
     */
    fun requestTiles(requests: List<TileRequest>, onChanged: () -> Unit) {
        if (requests.isEmpty()) {
            cancelStaleRequests(emptySet())
            return
        }

        val capped = if (requests.size > MAX_BATCH_REQUESTS) {
            requests.subList(0, MAX_BATCH_REQUESTS)
        } else {
            requests
        }

        val reqKeys = HashSet<String>(capped.size * 2)
        for (req in capped) {
            if (req.path.isNotEmpty()) {
                reqKeys.add(req.key)
            }
        }

        // Cancel in-flight tiles that are no longer visible
        val iter = inFlight.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (!reqKeys.contains(entry.key)) {
                entry.value.cancel()
                iter.remove()
            }
        }

        val currentReady = ready
        val seen = HashSet<String>(capped.size * 2)
        for (req in capped) {
            if (req.path.isEmpty()) continue
            val key = req.key
            if (!seen.add(key)) continue
            if (currentReady.containsKey(key) || inFlight.containsKey(key)) {
                continue
            }
            if ((failureCounts[key] ?: 0) >= MAX_FAILURES_PER_TILE) {
                continue
            }

            val job = scope.launch(Dispatchers.IO) {
                try {
                    coroutineContext.ensureActive()
                    val decoded = loadTile(req.path, req.bucketUs, key)
                    coroutineContext.ensureActive()
                    if (decoded != null) {
                        failureCounts.remove(key)
                        publish(key, req.path, req.bucketUs, decoded.image)
                        notifyChangeThrottled(onChanged)
                        if (decoded.bitmapToPersist != null) {
                            persistTileToDisk(key, decoded.bitmapToPersist)
                        }
                    } else if (!poolCleared) {
                        // Only count genuine decode failure (never cancellation)
                        val count = (failureCounts[key] ?: 0) + 1
                        failureCounts[key] = count
                    }
                } catch (_: CancellationException) {
                    // Cancelled due to scroll/zoom: do NOT mark as failed so tile can decode later
                } catch (_: Throwable) {
                    if (!poolCleared) {
                        val count = (failureCounts[key] ?: 0) + 1
                        failureCounts[key] = count
                    }
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
     * Clears all in-memory and in-flight states and releases open MediaMetadataRetriever instances.
     */
    fun clearMemory() {
        poolCleared = true
        cancelIdleRelease()
        synchronized(notifyLock) {
            pendingNotifyJob?.cancel()
            pendingNotifyJob = null
        }
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        failureCounts.clear()
        val toRelease = ArrayList<MediaMetadataRetriever>()
        synchronized(retrieverPoolLock) {
            for (slot in retrieverPool) {
                toRelease.add(slot.retriever)
            }
            retrieverPool.clear()
        }
        for (r in toRelease) {
            try { r.release() } catch (_: Throwable) {}
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

    private class DecodedTile(
        val image: ImageBitmap,
        val bitmapToPersist: Bitmap?
    )

    private suspend fun loadTile(path: String, bucketUs: Long, key: String): DecodedTile? {
        coroutineContext.ensureActive()

        // 1. Fast disk cache lookup (never acquires MediaMetadataRetriever or decodeSemaphore)
        val diskFile = File(diskDir, "$key.jpg")
        if (diskFile.exists() && diskFile.length() > 0L) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val cachedBmp = BitmapFactory.decodeFile(diskFile.absolutePath, opts)
                if (cachedBmp != null) {
                    diskFile.setLastModified(System.currentTimeMillis())
                    return DecodedTile(cachedBmp.asImageBitmap(), bitmapToPersist = null)
                }
            } catch (_: Throwable) {}
        }

        // 2. Verify source file exists before waiting on decoder semaphore
        val srcFile = File(path)
        if (!srcFile.exists() || !srcFile.isFile) {
            failureCounts[key] = MAX_FAILURES_PER_TILE
            return null
        }

        // 3. Static image clips: fast subsampled BitmapFactory decode (skip MediaMetadataRetriever)
        if (isStaticImagePath(path)) {
            coroutineContext.ensureActive()
            val imgBmp = decodeStaticImageTile(path) ?: return null
            return DecodedTile(imgBmp.asImageBitmap(), bitmapToPersist = imgBmp)
        }

        // 4. Wait until active scrub/fling pauses WITHOUT holding decodeSemaphore
        while (true) {
            coroutineContext.ensureActive()
            while (isPaused()) {
                delay(PAUSE_POLL_MS)
                coroutineContext.ensureActive()
            }

            var retryBecausePaused = false
            val decodedBmp: Bitmap? = decodeSemaphore.withPermit {
                coroutineContext.ensureActive()
                if (isPaused()) {
                    retryBecausePaused = true
                    return@withPermit null
                }
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                } catch (_: Throwable) {}

                val slot = checkoutRetrieverSlot(path) ?: return@withPermit null
                var broken = false
                try {
                    coroutineContext.ensureActive()
                    val targetUs = bucketUs.coerceAtLeast(0L)
                    val rawBmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        slot.retriever.getScaledFrameAtTime(
                            targetUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            TILE_WIDTH_PX,
                            TILE_HEIGHT_PX
                        ) ?: slot.retriever.getScaledFrameAtTime(
                            targetUs,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                            TILE_WIDTH_PX,
                            TILE_HEIGHT_PX
                        )
                    } else {
                        slot.retriever.getFrameAtTime(
                            targetUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }
                    downscaleIfOversized(rawBmp)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    broken = true
                    null
                } finally {
                    returnRetrieverSlot(slot, broken = broken)
                }
            }

            if (retryBecausePaused) {
                continue
            }
            return if (decodedBmp != null) {
                DecodedTile(decodedBmp.asImageBitmap(), bitmapToPersist = decodedBmp)
            } else {
                null
            }
        }
    }

    private fun downscaleIfOversized(bmp: Bitmap?): Bitmap? {
        if (bmp == null) return null
        val w = bmp.width
        val h = bmp.height
        if (w <= TILE_WIDTH_PX * 2 && h <= TILE_HEIGHT_PX * 2) return bmp
        val scale = minOf(TILE_WIDTH_PX.toFloat() / w, TILE_HEIGHT_PX.toFloat() / h)
        val outW = (w * scale).toInt().coerceAtLeast(1)
        val outH = (h * scale).toInt().coerceAtLeast(1)
        return try {
            val scaled = Bitmap.createScaledBitmap(bmp, outW, outH, true)
            if (scaled !== bmp) {
                bmp.recycle()
            }
            scaled
        } catch (_: Throwable) {
            bmp
        }
    }

    private fun decodeStaticImageTile(path: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null
            var sample = 1
            while (srcW / (sample * 2) >= TILE_WIDTH_PX && srcH / (sample * 2) >= TILE_HEIGHT_PX) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val raw = BitmapFactory.decodeFile(path, opts) ?: return null
            downscaleIfOversized(raw)
        } catch (_: Throwable) {
            null
        }
    }

    private fun persistTileToDisk(key: String, bmp: Bitmap) {
        try {
            val diskFile = File(diskDir, "$key.jpg")
            val temp = File(diskDir, "$key.jpg.tmp")
            FileOutputStream(temp).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 78, fos)
            }
            if (diskFile.exists()) diskFile.delete()
            if (temp.renameTo(diskFile)) {
                diskFile.setLastModified(System.currentTimeMillis())
                if (diskWritesSincePrune.incrementAndGet() >= DISK_PRUNE_INTERVAL) {
                    diskWritesSincePrune.set(0)
                    pruneDiskCache()
                }
            } else {
                temp.delete()
            }
        } catch (_: Throwable) {}
    }

    @Synchronized
    private fun publish(key: String, path: String, bucketUs: Long, img: ImageBitmap) {
        val next = LinkedHashMap(ready)
        // Re-insert to update LRU recency
        next.remove(key)
        next[key] = CachedTile(path, bucketUs, img)
        var evictedPaths: HashSet<String>? = null
        while (next.size > maxMemEntries) {
            val oldestEntry = next.entries.firstOrNull() ?: break
            next.remove(oldestEntry.key)
            val evictedPath = oldestEntry.value.path
            if (evictedPath != path) {
                if (evictedPaths == null) evictedPaths = HashSet(2)
                evictedPaths.add(evictedPath)
            }
        }
        ready = next

        // Incremental index update: only rebuild LongBitmapTable for affected path(s)
        val nextByPath = HashMap(readyByPath)
        val updatedTable = buildPathTable(next, path)
        if (updatedTable != null) {
            nextByPath[path] = updatedTable
        } else {
            nextByPath.remove(path)
        }
        if (evictedPaths != null) {
            for (ep in evictedPaths) {
                val epTable = buildPathTable(next, ep)
                if (epTable != null) {
                    nextByPath[ep] = epTable
                } else {
                    nextByPath.remove(ep)
                }
            }
        }
        readyByPath = nextByPath
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
        private val decodeSemaphore = Semaphore(2)
        private const val MAX_RETRIEVER_SLOTS = 3
        private const val MAX_BATCH_REQUESTS = 64
        private const val MAX_FAILURES_PER_TILE = 2
        private const val DISK_PRUNE_INTERVAL = 24
        private const val NOTIFY_THROTTLE_MS = 60L
        private const val PAUSE_POLL_MS = 32L
        private const val IDLE_RETRIEVER_RELEASE_MS = 4_000L
        private const val MAX_FALLBACK_DELTA_US = 2_500_000L

        private val HEX_CHARS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
        )
        const val TILE_WIDTH_PX = 160
        const val TILE_HEIGHT_PX = 120
        const val DEFAULT_MAX_DISK_BYTES = 100L * 1024L * 1024L // 100 MB

        @Volatile
        var defaultMaxMemEntries: Int = 256

        @Volatile
        var activeInstance: ThumbnailService? = null

        fun trimActiveMemory(maxKeepEntries: Int) {
            activeInstance?.trimMemory(maxKeepEntries)
        }

        internal fun isStaticImagePath(path: String): Boolean {
            val dot = path.lastIndexOf('.')
            if (dot < 0 || dot >= path.length - 1) return false
            val ext = path.substring(dot + 1).lowercase()
            return ext == "jpg" || ext == "jpeg" || ext == "png" ||
                ext == "webp" || ext == "bmp" || ext == "heic" || ext == "heif"
        }

        /**
         * Lock-free, zero-allocation O(log n) exact + nearest bucket search.
         * Pure Kotlin for JVM unit testing without Android dependencies.
         */
        internal fun findNearestBucketIndex(
            buckets: LongArray,
            targetUs: Long,
            maxDeltaUs: Long
        ): Int {
            val size = buckets.size
            if (size == 0) return -1
            var lo = 0
            var hi = size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val v = buckets[mid]
                if (v < targetUs) {
                    lo = mid + 1
                } else if (v > targetUs) {
                    hi = mid - 1
                } else {
                    return mid
                }
            }
            var bestIdx = -1
            var bestDist = maxDeltaUs + 1L
            if (hi >= 0) {
                val d = targetUs - buckets[hi]
                if (d in 0L..maxDeltaUs && d < bestDist) {
                    bestDist = d
                    bestIdx = hi
                }
            }
            if (lo < size) {
                val d = buckets[lo] - targetUs
                if (d in 0L..maxDeltaUs && d < bestDist) {
                    bestIdx = lo
                }
            }
            return bestIdx
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
