# FlowCut — Kotlin Reference Sketches
## Part 2 (Hinglish) — `FlowCut_Plan_1_Architecture_Steps.md` ke tasks ke saath

> **Ye final code nahi hai.** Ye reference sketches hain. Inhe maine compile ya test **nahi** kiya.
> Agent inhe asli code (naam, threading, existing `ExportState`) ke hisaab se adapt kare. `AGENT_RULES` aur `checkRules` ke niyam (no `Thread.sleep`/`runBlocking`, hot path me allocation/lock nahi) har hal me lagu.
> Jahan "pure Kotlin" likha hai wahan `android.*` import nahi — taaki JVM unit test chale.

---

## T1.2 — `engine/media/ColorInfoCheck.kt` (import me 10-bit/HDR reject)

```kotlin
package com.flowcut.app.engine.media

import android.media.MediaCodecInfo.CodecProfileLevel as P
import android.media.MediaFormat

object ColorInfoCheck {
    enum class Verdict { OK, REJECT_HDR, REJECT_10BIT }

    /** MediaExtractor.getTrackFormat() ka format. Best-effort: key na ho to OK maano. */
    fun check(f: MediaFormat): Verdict {
        val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
        val transfer = f.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
        if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG) {
            return Verdict.REJECT_HDR
        }
        val profile = f.intOrNull(MediaFormat.KEY_PROFILE) ?: return Verdict.OK
        val tenBit = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC ->
                profile == P.HEVCProfileMain10 ||
                profile == P.HEVCProfileMain10HDR10 ||
                profile == P.HEVCProfileMain10HDR10Plus
            MediaFormat.MIMETYPE_VIDEO_AVC -> profile == P.AVCProfileHigh10
            else -> false
        }
        return if (tenBit) Verdict.REJECT_10BIT else Verdict.OK
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null
}
```

---

## T1.3 — `schemaVersion` (`model/Project.kt`, `ProjectStore.kt`)

```kotlin
// model/Project.kt  (existing fields wahi rahenge; sirf yeh naya, default ke saath)
const val CURRENT_PROJECT_SCHEMA = 1

@Serializable
data class Project(
    // ... clips, transitions, lanes ...
    val schemaVersion: Int = 1
)

// ProjectStore.kt
class NewerProjectException(val found: Int) : Exception("Project schema $found > $CURRENT_PROJECT_SCHEMA")

fun decodeProject(text: String): Project {
    val p = json.decodeFromString<Project>(text)
    if (p.schemaVersion > CURRENT_PROJECT_SCHEMA) throw NewerProjectException(p.schemaVersion)
    return p
}
// save par: project.copy(schemaVersion = CURRENT_PROJECT_SCHEMA)
```

---

## T1.4 — `ui/media/PipThumbLoader.kt` (draw-safe thumbnails)

Draw phase me sirf `peek()`. `ensure()` composition/`LaunchedEffect` se, kabhi `onDraw` se nahi.

```kotlin
package com.flowcut.app.ui.media

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

class PipThumbLoader(private val scope: CoroutineScope, private val maxEntries: Int = 256) {

    // Copy-on-write: draw lock-free padhta hai (R10). Likhne wala sirf synchronized path.
    @Volatile private var ready: Map<String, ImageBitmap> = emptyMap()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** DRAW PHASE: koi I/O nahi, koi lock nahi. */
    fun peek(path: String): ImageBitmap? = ready[path]

    fun ensure(paths: Collection<String>, onChanged: () -> Unit) {
        for (p in paths) {
            if (p.isEmpty() || ready.containsKey(p) || failed.contains(p) || !inFlight.add(p)) continue
            scope.launch(Dispatchers.IO) {
                val bmp = try { decode(p) } catch (_: Exception) { null }
                if (bmp != null) publish(p, bmp.asImageBitmap()) else failed.add(p)
                inFlight.remove(p)
                withContext(Dispatchers.Main) { onChanged() } // redraw ka tareeka: frameDriver.wake()
            }
        }
    }

    fun forget(path: String) { failed.remove(path) } // file badli ho to dobara try

    @Synchronized private fun publish(path: String, img: ImageBitmap) {
        val next = LinkedHashMap(ready)
        next[path] = img
        while (next.size > maxEntries) next.remove(next.keys.first())
        ready = next
    }

    private fun decode(path: String): android.graphics.Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val isVideo = path.endsWith(".mp4", true) || path.endsWith(".mkv", true) ||
            path.endsWith(".mov", true) || path.contains("overlay_vid_")
        return if (isVideo) {
            ThumbnailUtils.createVideoThumbnail(file, Size(64, 64), null) // API 29+, minSdk 30 ok
        } else {
            // existing sampling logic yahin shift karo (inJustDecodeBounds + inSampleSize)
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
        }
    }
}
```

`TimelineCanvas.kt` (**[CORE-EDIT-OK]**) me ek-line badlav:
```kotlin
// pehle:
val thumb = pipThumbnails[item.mediaPath] ?: if (item.mediaPath.isNotEmpty()) PipThumbnailCache.get(item.mediaPath) else null
// baad me:
val thumb = loader.peek(item.mediaPath)
```

---

## T1.5 — `engine/export/FrameGate.kt`

```kotlin
package com.flowcut.app.engine.export

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ExportFrameTimeoutException(val frameIndex: Int) :
    Exception("Decoder frame $frameIndex par timeout")

/** Export frame ka "naya frame aaya" signal. Late signal agle frame me leak na ho, isliye arm() pehle. */
class FrameGate {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var available = false

    /** Decoder ko frame render karne se PEHLE. */
    fun arm() = lock.withLock { available = false }

    /** SurfaceTexture.OnFrameAvailableListener se. */
    fun signal() = lock.withLock { available = true; cond.signalAll() }

    /** true = frame aaya, false = timeout. */
    fun await(timeoutMs: Long): Boolean = lock.withLock {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!available) {
            if (nanos <= 0L) return@withLock false
            nanos = cond.awaitNanos(nanos)
        }
        available = false
        true
    }
}
```

`ExportEngine` me pattern (asli `frameAtExport` flow padhkar adapt karo — mujhe uska andar ka code nahi pata):
```kotlin
gateA.arm()
frameSourceA.frameAtExport(sourceUsA)               // andar releaseOutputBuffer(render = true)
var ok = gateA.await(FRAME_WAIT_MS)                 // shuruat: 1500 ms
if (!ok) { stats.timeouts++; ok = gateA.await(FRAME_WAIT_MS) }
if (!ok) throw ExportFrameTimeoutException(i)       // chup duplicate nahi
stA.updateTexImage()                                // exception nigalna band; wrap karke throw
```

---

## T1.6 — Foreground service

`AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name="com.flowcut.app.engine.export.ExportService"
    android:exported="false"
    android:foregroundServiceType="dataSync|mediaProcessing" />
```
(Combo ka purane OS par behaviour mujhe verify nahi hua. Issue aaye to sirf `dataSync`.)

`engine/export/ExportService.kt` (sketch):
```kotlin
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { ExportCoordinator.requestCancel("user"); return START_NOT_STICKY }
        val req = ExportCoordinator.takePendingRequest() ?: run { stopSelf(startId); return START_NOT_STICKY }

        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 35)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(0), type)
        } catch (e: Exception) {
            ExportCoordinator.fail(e); stopSelf(startId); return START_NOT_STICKY
        }

        scope.launch(Dispatchers.IO) {
            try {
                ExportCoordinator.run(applicationContext, req) { pct -> notify(buildNotification(pct)) }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // API 35+: 6 ghante/24 ghante limit. Docs: stopSelf() kuch seconds me, warna ANR.
    override fun onTimeout(startId: Int, fgsType: Int) {
        ExportCoordinator.requestCancel("fgs_timeout")
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object { const val ACTION_CANCEL = "com.flowcut.app.EXPORT_CANCEL"; const val NOTIF_ID = 4107 }
    // ensureChannel(), buildNotification(pct) (progress + cancel action), notify(): standard NotificationCompat
}
```
`ExportCoordinator` (`object`): pending request slot, `StateFlow<ExportState>` (existing `ExportState` se map karo), `requestCancel()`, `run()` jo `ExportEngine` + ladder + `SessionGuard` chalaye.

---

## T2.1 — `engine/export/OutputSpec.kt` (pure Kotlin)

```kotlin
package com.flowcut.app.engine.export

enum class OutCodec { AVC, HEVC }

enum class Quality(val bitsPerPixel: Double) {  // shuruati andaze — tune karo
    LOW(0.06), MEDIUM(0.10), HIGH(0.16), ULTRA(0.22), EXTREME(0.30)
}

data class Aspect(val w: Int, val h: Int) {
    init { require(w > 0 && h > 0) }
    companion object {
        val R9_16 = Aspect(9, 16); val R16_9 = Aspect(16, 9)
        val R4_3 = Aspect(4, 3);   val R3_4 = Aspect(3, 4); val R1_1 = Aspect(1, 1)
    }
}

object Resolutions {
    val SHORT_SIDES = intArrayOf(144, 240, 360, 480, 540, 720, 1080, 1440, 2160)
    val FPS = intArrayOf(24, 25, 30, 50, 60)   // 48000 / fps integer: 2000, 1920, 1600, 960, 800
    fun label(shortSide: Int) = when (shortSide) { 1440 -> "2K"; 2160 -> "4K"; else -> "${shortSide}p" }
}

data class OutSize(val w: Int, val h: Int)

data class OutputSpec(
    val aspect: Aspect,
    val shortSide: Int,
    val fps: Int,
    val quality: Quality,
    val codec: OutCodec
) {
    /** alignW/alignH: encoder ka widthAlignment/heightAlignment (power of 2). */
    fun size(alignW: Int = 2, alignH: Int = 2): OutSize =
        if (aspect.w <= aspect.h) {
            OutSize(aligned(shortSide, 1, 1, alignW), aligned(shortSide, aspect.h, aspect.w, alignH))
        } else {
            OutSize(aligned(shortSide, aspect.w, aspect.h, alignW), aligned(shortSide, 1, 1, alignH))
        }

    fun key(): String = "${aspect.w}x${aspect.h}_${shortSide}_${fps}_${quality}_${codec}"

    private fun aligned(base: Int, num: Int, den: Int, align: Int): Int {
        require(align > 0 && (align and (align - 1)) == 0)
        // base*num/den ke sabse paas ka align ka multiple (round half up)
        val q = (base.toLong() * num * 2 + den.toLong() * align) / (2L * den * align)
        return maxOf(align.toLong(), q * align).toInt()
    }
}
```
Unit test (examples):
```kotlin
@Test fun sizes() {
    fun s(a: Aspect, ss: Int) = OutputSpec(a, ss, 30, Quality.HIGH, OutCodec.AVC).size()
    assertEquals(OutSize(1080, 1920), s(Aspect.R9_16, 1080))
    assertEquals(OutSize(3840, 2160), s(Aspect.R16_9, 2160))
    assertEquals(OutSize(1440, 1080), s(Aspect.R4_3, 1080))
    assertEquals(OutSize(1440, 1440), s(Aspect.R1_1, 1440))
    assertEquals(OutSize(240, 426),   s(Aspect.R9_16, 240))
    assertEquals(OutSize(480, 854),   s(Aspect.R9_16, 480))
}
```
Encoder ka alignment bada ho (jaise 16) to aspect me <2% farak aayega — GL viewport bhi aligned size par rakhna.

---

## T2.1 — `BitrateTable.kt` + `ExportEstimator.kt` (pure Kotlin)

```kotlin
package com.flowcut.app.engine.export

import kotlin.math.roundToLong

object BitrateTable {
    const val AUDIO_BPS = 192_000

    /** Sab factors shuruati andaze. Abhi ka app: 1080x1920@30 = 10 Mbps (~0.16 bpp). */
    fun videoBps(
        w: Int, h: Int, fps: Int, q: Quality, codec: OutCodec,
        minBps: Int = 200_000, maxBps: Int = Int.MAX_VALUE
    ): Int {
        val px = w.toLong() * h
        val resFactor = when { px <= 2_200_000L -> 1.0; px <= 4_000_000L -> 0.8; else -> 0.65 }
        val fpsFactor = if (fps > 30) 0.8 else 1.0
        val codecFactor = if (codec == OutCodec.HEVC) 0.7 else 1.0
        val raw = px * fps * q.bitsPerPixel * resFactor * fpsFactor * codecFactor
        return raw.roundToLong().coerceIn(minBps.toLong(), maxBps.toLong()).toInt()
    }
}

object ExportEstimator {
    fun sizeBytes(videoBps: Int, durationMs: Long): Long =
        (videoBps + BitrateTable.AUDIO_BPS).toLong() * durationMs / 1000L / 8L * 102L / 100L

    fun totalFrames(durationMs: Long, fps: Int): Long = (durationMs * fps + 999L) / 1000L

    /** measuredFps = benchmark se mila render+encode fps. */
    fun etaMs(totalFrames: Long, measuredFps: Double): Long =
        if (measuredFps <= 0.0) -1L else (totalFrames / measuredFps * 1000.0).toLong()
}
```
Test: `videoBps(1080,1920,30,HIGH,AVC) ≈ 9_953_280`; 4K60 high AVC ≈ 41.4 Mbps → 15 min ≈ 4.7 GB.

---

## T2.3 — `engine/export/ExportCapabilities.kt`

```kotlin
package com.flowcut.app.engine.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

class ExportCapabilities(
    private val encoders: List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
) {
    data class Choice(
        val codecName: String, val hardware: Boolean,
        val width: Int, val height: Int, val minBps: Int, val maxBps: Int
    )

    fun find(spec: OutputSpec, allowSoftware: Boolean): Choice? {
        val mime = if (spec.codec == OutCodec.AVC) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
        val base = spec.size()
        val needHardware = base.w.toLong() * base.h > 2_073_600L || spec.fps > 30  // >1080p-area ya >30fps

        for (info in encoders) {                      // MediaCodecList preferred order me deti hai
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val hw = info.isHardwareAccelerated       // API 29+
            if (!hw && (!allowSoftware || needHardware)) continue

            val vc = try { info.getCapabilitiesForType(mime).videoCapabilities }
                     catch (_: IllegalArgumentException) { null } ?: continue
            val size = spec.size(vc.widthAlignment, vc.heightAlignment)
            if (!vc.isSizeSupported(size.w, size.h)) continue
            if (!vc.areSizeAndRateSupported(size.w, size.h, spec.fps.toDouble())) continue

            return Choice(info.name, hw, size.w, size.h, vc.bitrateRange.lower, vc.bitrateRange.upper)
        }
        return null
    }
}
```
**Yaad rakho:** yeh sirf "claim" hai, speed ki guarantee nahi. Benchmark (T2.4) aur ladder (T2.5) ke bina bharosa mat karo.

Encoder format banana:
```kotlin
fun buildVideoFormat(spec: OutputSpec, c: ExportCapabilities.Choice, bps: Int): MediaFormat {
    val mime = if (spec.codec == OutCodec.AVC) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
    return MediaFormat.createVideoFormat(mime, c.width, c.height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, bps.coerceIn(c.minBps, c.maxBps))
        setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        setInteger(
            MediaFormat.KEY_PROFILE,
            if (spec.codec == OutCodec.AVC) MediaCodecInfo.CodecProfileLevel.AVCProfileMain
            else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        )
        // KEY_LEVEL abhi set nahi (abhi ka code bhi nahi karta). Agar 4K par configure fail ho to dekho.
    }
}
// encoder: MediaCodec.createByCodecName(c.codecName)
```

---

## T2.5 — `ExportLadder.kt` (pure) + `SessionGuard.kt`

```kotlin
package com.flowcut.app.engine.export

object ExportLadder {
    /** Pehla = jo user ne manga. Baaki = ek ek karke halke options. */
    fun candidates(req: OutputSpec): List<OutputSpec> {
        val out = LinkedHashSet<OutputSpec>()
        val other = if (req.codec == OutCodec.AVC) OutCodec.HEVC else OutCodec.AVC
        val lowFps = if (req.fps >= 50) req.fps / 2 else req.fps   // 60->30, 50->25
        out += req
        out += req.copy(codec = other)
        if (lowFps != req.fps) {
            out += req.copy(fps = lowFps)
            out += req.copy(fps = lowFps, codec = other)
        }
        for (side in Resolutions.SHORT_SIDES.reversed().filter { it < req.shortSide }) {
            out += req.copy(shortSide = side, fps = lowFps)
            out += req.copy(shortSide = side, fps = lowFps, codec = other)
        }
        return out.toList()
    }
}
```
```kotlin
package com.flowcut.app.engine.perf

import android.content.Context

/** Crash-loop guard. Marker mile to pichhla export process-kill/crash me gaya. LMK false-positive ho sakta hai, isliye 2 baar ke baad exclude. */
class SessionGuard(ctx: Context) {
    private val sp = ctx.getSharedPreferences("flowcut_guard", Context.MODE_PRIVATE)

    fun markExportStart(specKey: String) { sp.edit().putString("inflight", specKey).commit() }
    fun markExportEnd() { sp.edit().remove("inflight").commit() }   // success, cancel, ya handled failure par

    /** App launch par. Pichhla spec jo bina end ke mara, uska fail-count badhao. */
    fun recordCrashIfAny() {
        val k = sp.getString("inflight", null) ?: return
        sp.edit().remove("inflight").putInt("fail_$k", sp.getInt("fail_$k", 0) + 1).commit()
    }
    fun isExcluded(specKey: String) = sp.getInt("fail_$specKey", 0) >= 2
}
```

---

## T1.8 — `ExportPreflight.kt` (space check)

```kotlin
object ExportPreflight {
    private const val MARGIN = 200L * 1024 * 1024

    data class Need(val ok: Boolean, val needBytes: Long, val freeBytes: Long)

    /** tempCopy=true: cache me estimated + Movies me estimated. Same volume ho to double. */
    fun check(estimated: Long, cacheDir: java.io.File, moviesDir: java.io.File, tempCopy: Boolean): Need {
        val cacheFree = android.os.StatFs(cacheDir.path).availableBytes
        val moviesFree = android.os.StatFs(moviesDir.path).availableBytes
        val sameVolume = cacheDir.path.take(8) == moviesDir.path.take(8)   // mota andaza; asli me StatFs/Storage volume se tay karo
        val need = if (tempCopy && sameVolume) estimated * 2 + MARGIN else estimated + MARGIN
        val free = if (sameVolume) minOf(cacheFree, moviesFree) else moviesFree
        val ok = if (tempCopy && !sameVolume) cacheFree >= estimated + MARGIN && moviesFree >= estimated + MARGIN
                 else free >= need
        return Need(ok, need, free)
    }
}
```
(`sameVolume` ka tareeka kaccha hai — agent ise `Environment`/`StorageManager` se sahi kare.)

---

## T3.1 — PIP proxy encoder config (sirf config; poora pipeline agent banaye)

```kotlin
fun proxyFormat(w: Int, h: Int): MediaFormat =
    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)       // andaza; 640px all-intra par measure karo
        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)       // all-intra ki request — device par VERIFY
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
    }
// w/h: computePipTargetDimensions jaisa (max side 640, even). Audio track nahi likhna.
```
Verify: output ko `MediaExtractor` se padho; agar saare samples `SAMPLE_FLAG_SYNC` nahi hain to fallback: har frame se pehle
`codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })`.

---

## T5.1 — `engine/perf/DeviceProfile.kt` + `TierPolicy.kt`

```kotlin
package com.flowcut.app.engine.perf

data class DeviceProfile(
    val sdk: Int,
    val totalRamMb: Long,
    val isLowRam: Boolean,
    val mediaPerfClass: Int,        // API 31 se; neeche 0. 0 = "pata nahi" (low-end me aksar yahi)
    val hwAvcEnc1080p60: Boolean,   // ExportCapabilities se
    val hwAvcDecInstances: Int      // CodecCapabilities.maxSupportedInstances — sirf upper-bound hint
)

enum class Tier { LOW, MID, HIGH }

data class QualityBudget(
    val tier: Tier,
    val pipDecodeThreads: Int,
    val overlayTexBudgetMb: Int,
    val pipUpdateFps: Int,
    val autoProxy: Boolean
)

object TierPolicy {
    // Thresholds shuruati andaze. "3GB" phone ka totalMem aksar ~2.7-2.9 GB dikhta hai — apne phones par print karke tay karo.
    fun tier(p: DeviceProfile): Tier = when {
        p.isLowRam || p.totalRamMb < 3300 || !p.hwAvcEnc1080p60 -> Tier.LOW
        p.totalRamMb < 6000 -> Tier.MID
        else -> Tier.HIGH
    }

    fun budget(p: DeviceProfile): QualityBudget = when (tier(p)) {
        Tier.LOW  -> QualityBudget(Tier.LOW, 1, 64, 15, true)
        Tier.MID  -> QualityBudget(Tier.MID, 2, 128, 30, true)
        Tier.HIGH -> QualityBudget(Tier.HIGH, 2, 256, 30, false)
    }
}
```
Probe (Android side): `ActivityManager.MemoryInfo.totalMem`, `isLowRamDevice`, `if (SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0`. Cache key `Build.FINGERPRINT`.

---

## T5.2 — `ThermalMonitor.kt` + `QualityGovernor.kt`

```kotlin
package com.flowcut.app.engine.perf

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Headroom ek hi thread se, ~10 s me ek baar (docs). NaN = unsupported. */
class ThermalMonitor(ctx: Context, private val exec: ScheduledExecutorService) {
    private val pm = ctx.getSystemService(PowerManager::class.java)
    @Volatile var status: Int = PowerManager.THERMAL_STATUS_NONE; private set
    @Volatile var headroom: Float = Float.NaN; private set
    private val listener = PowerManager.OnThermalStatusChangedListener { s -> status = s }

    fun start() {
        pm.addThermalStatusListener(exec, listener)                       // API 30
        exec.scheduleWithFixedDelay({ headroom = pm.getThermalHeadroom(10) }, 0, 10, TimeUnit.SECONDS)
    }
    fun stop() { pm.removeThermalStatusListener(listener) }
}
```
```kotlin
// Pure Kotlin
enum class Level { FULL, REDUCED, MINIMAL }

class QualityGovernor(private val upgradeAfterMs: Long = 30_000L) {
    private var level = Level.FULL
    private var calmSince = -1L

    /** moderate/severe: thermal status se. headroom NaN ho to comparisons false — theek hai. */
    fun update(
        nowMs: Long, moderate: Boolean, severe: Boolean,
        headroom: Float, lateFramePct: Float, memLow: Boolean
    ): Level {
        val target = when {
            severe || headroom >= 1.0f || memLow -> Level.MINIMAL
            moderate || headroom >= 0.95f || lateFramePct > 8f -> Level.REDUCED
            else -> Level.FULL
        }
        if (target.ordinal > level.ordinal) {            // girna turant
            level = target; calmSince = -1L
        } else if (target.ordinal < level.ordinal) {     // chadhna dheere, ek step, 30 s calm ke baad
            if (calmSince < 0) calmSince = nowMs
            else if (nowMs - calmSince >= upgradeAfterMs) { level = Level.values()[level.ordinal - 1]; calmSince = -1L }
        } else calmSince = -1L
        return level
    }
}
```
Thermal status kuch devices par update nahi hota (docs me bhi zikr hai) — isliye `lateFramePct` (frame-time signal) saath me zaroori hai. Late-frame counter: `PreviewRenderer` me sirf `@Volatile var lateFrames: Long` ka increment (**[CORE-EDIT-OK]**, allocation nahi).

---

## Unit tests ka minimum set
- `OutputSpec.size()` — upar wala table.
- `BitrateTable` — 1080p30 high ≈ 9.95 Mbps; 4K60 high ≈ 41.4 Mbps; clamp min/max.
- `ExportLadder` — 4K60 request ke liye pehla = request, phir codec swap, fps 30, phir neeche ki resolutions; duplicates nahi.
- `QualityGovernor` — turant girna, 30 s baad ek step chadhna, NaN headroom par crash nahi.
- `ColorInfoCheck` — HLG/Main10 reject, normal AVC/HEVC accept, key na ho to accept.
- `FrameGate` — `arm()` ke baad purana `signal()` nahi chalta; timeout par `false`.
