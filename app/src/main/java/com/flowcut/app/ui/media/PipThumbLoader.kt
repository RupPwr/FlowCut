package com.flowcut.app.ui.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PipThumbLoader(
    private val scope: CoroutineScope,
    private val maxEntries: Int = 256
) {
    // Copy-on-write: draw-phase reads lock-free (Rule R10). Published only via synchronized path.
    @Volatile
    private var ready: Map<String, ImageBitmap> = emptyMap()

    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Hot-path safe read (Rule R10): strictly lock-free, zero allocation, zero I/O. */
    fun peek(path: String): ImageBitmap? = ready[path]

    fun ensure(paths: Collection<String>, onChanged: () -> Unit) {
        for (p in paths) {
            if (p.isEmpty() || ready.containsKey(p) || failed.contains(p) || !inFlight.add(p)) {
                continue
            }
            scope.launch(Dispatchers.IO) {
                val bmp = try {
                    decode(p)
                } catch (_: Throwable) {
                    null
                }
                if (bmp != null) {
                    publish(p, bmp.asImageBitmap())
                } else {
                    failed.add(p)
                }
                inFlight.remove(p)
                withContext(Dispatchers.Main) {
                    onChanged()
                }
            }
        }
    }

    fun forget(path: String) {
        failed.remove(path)
    }

    @Synchronized
    private fun publish(path: String, img: ImageBitmap) {
        val next = LinkedHashMap(ready)
        next[path] = img
        while (next.size > maxEntries) {
            val oldestKey = next.keys.firstOrNull() ?: break
            next.remove(oldestKey)
        }
        ready = next
    }

    private fun decode(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val isVideo = path.endsWith(".mp4", ignoreCase = true) ||
                path.endsWith(".mkv", ignoreCase = true) ||
                path.endsWith(".mov", ignoreCase = true) ||
                path.contains("overlay_vid_")

        return if (isVideo) {
            try {
                ThumbnailUtils.createVideoThumbnail(file, Size(64, 64), null)
            } catch (_: Throwable) {
                null
            }
        } else {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) >= 64 && opts.outHeight / (sample * 2) >= 64) {
                    sample *= 2
                }
                opts.inJustDecodeBounds = false
                opts.inSampleSize = sample
                val raw = BitmapFactory.decodeFile(path, opts) ?: return null
                val scaled = Bitmap.createScaledBitmap(raw, 64, 64, true)
                if (scaled != raw) {
                    raw.recycle()
                }
                scaled
            } catch (_: Throwable) {
                null
            }
        }
    }
}
