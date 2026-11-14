# FlowCut — Performance, Export aur Safety Plan
## Part 1: Architecture + Steps (Hinglish)

Banaya: 12 Nov 2026 · Version: Draft v1
Saath ki file: `FlowCut_Plan_2_Kotlin_Snippets.md` (code ke reference sketches — compile/test NAHI hue hain)

> **Zaroori:** Yeh plan `FlowCut_Foundation_Spec.txt` aur `AGENT_RULES.md` ko override nahi karta. Jahan dono me takraav ho, wahan AGENT_RULES jeete aur agent ruk kar poochhe.
> Is file me jo code-facts hain wo agent ke reports se liye gaye hain. Maine codebase khud nahi dekha.

---

## 0. Is file ko kaise use karein

1. Agent ko **ek baar me sirf ek Task** (T-number) do. Task ke `Files` se bahar kuch nahi chhuna.
2. Jis task par **[CORE-EDIT-OK]** likha hai, sirf usi ke prompt me ye token likho. Baaki me nahi.
3. Har task ke baad agent ye chalaye aur poora output paste kare:
   `./gradlew checkRules testDebugUnitTest assembleRelease`
4. Naye files ka ghar: `engine/export/`, `engine/perf/`, `engine/proxy/`, `engine/media/`, `ui/media/`. `core/` me naya kuch nahi (R12). `ui/timeline/**` ke bahar naya UI-helper.
5. Naye Choreographer callback nahi (R4). Hot path (vsync, GL draw, `onDraw`, audio block) me naya allocation, lock, file I/O, Log nahi (R10). Governor/tier ki values volatile fields se padhi jaayengi.

**Agent ko task dene ka template**
```
Pehle sirf jaancho, code mat badlo. Task <T-number> (FlowCut_Plan_1_Architecture_Steps.md).
Batao: kaun si files chhuoge, kaun si lines abhi kya karti hain (file:line), aur plan ke kaun se
assumptions asli code se match nahi karte.
(Meri haan ke baad) Ab sirf <files> me <kaam> karo. [CORE-EDIT-OK sirf jahan likha ho]
```

---

## 1. Tay ho chuke decisions

| Vishay | Decision |
|---|---|
| Timeline length | Max **15 min** (900_000 ms) |
| Telemetry / Firebase / remote blocklist | **Nahi.** Sab offline, app ke andar |
| Overlay caps | Text 40, Mosaic 40, Sticker 40, **PIP (photo+video) 40 (4 non-overlapping rows)**, Music/Audio 40 (4 non-overlapping rows), Filter 3, Effect 1 |
| Ek time par PIP | Max **4** (4 rows × ek item) |
| Import | Sirf 8-bit SDR. **10-bit / HDR → reject + message.** VFR → accept (30fps maan kar) |
| Export resolution | 144, 240, 360, 480, 540, 720, 1080, 2K (1440), 4K (2160) — **short side** ke naam se |
| Export FPS | 24, 25, 30, 50, 60 |
| Export quality | low, medium, high, ultra, extreme |
| Aspect ratio | Kai (9:16, 16:9, 4:3, 1:1 ...) — **ratio system abhi bana nahi**, isliye export ratio-agnostic banega (Phase R) |
| 4K / 50-60fps | Sirf jab device capable ho |
| HDR export | **Deferred** (Phase H) — wajah Section 3 me |
| Export se pehle | Estimated file size aur ETA user ko dikhana |

**Abhi pending (tumhara decision):** ek single clip ki max length (`MAX_CLIP_MS`) — default is plan me 900_000 (=total) maani gayi hai.

---

## 2. Abhi code kaisa hai (agent ke reports se)

| Area | Sthiti |
|---|---|
| Preview | 540×960 fixed, GLES30, FBO zero (seedha window surface). Main track me sirf **2** `FrameSource` (A/B) = `MediaCodec`→`SurfaceTexture`→OES. `USE_NEW_CORE = true` |
| PIP video | `MediaMetadataRetriever` pool (max **4**), `OPTION_CLOSEST`, **1 thread** (`FlowCut-PipDecode`), frame max **640px** (side), 30fps bucket. Export me synchronous, har frame par |
| Overlay textures | `HashMap` (itemId), image >1024px → 1024px. **Byte-budget eviction nahi** |
| Audio | `AudioTrack` float 48kHz, audio-clock master. Clip decoders 1–2, overlay audio decoders cached (`max(8, expected)`) |
| Export | AVC Main, VBR, **1080×1920 @30, 10 Mbps fixed**. Run: ViewModel (`Dispatchers.IO`). Temp file (`cacheDir`) → copy → MediaStore. **FGS nahi.** Frame wait 25 ms × 10, phir **chup-chaap duplicate frame**. **Preview decoders release nahi hote** |
| Editor export ke dauraan | Blocking modal sheet (user edit nahi kar sakta). App background me jaa sakti hai |
| Import | AVC/HEVC accept. 10-bit/HDR/VFR detect nahi. Short side ≤1080, long ≤2560 |
| Save | `model.Project` JSON (`ignoreUnknownKeys=true`, `encodeDefaults=true`), **schema version nahi**. Memory model alag: `core.model.Project` |
| Limits | `Limits.kt`: 5 min (stale). `EditorViewModel`: 15 min (asli enforce) |
| PIP thumbnails | 64×64, `LruCache(32)`; **draw-phase me fallback decode** (`file.exists()` + thumbnail extract, main thread) |
| Main-track thumbnails / waveform / proxy / tiering / governor | **Nahi hain** |

**Mismatch jo theek karne hain**
1. `Limits.kt` (5 min) ≠ `EditorViewModel` (15 min) → T1.1.
2. `AGENT_RULES.md` "OpenGL ES 2.0" likhta hai, code GLES30 hai.
3. Foundation "decoder pool" likhta hai, asli code me sirf A/B slots hain.
4. `PROTECTED.txt` me 3 timeline files hain, `AGENT_RULES` me `ui/timeline/**` hai.
5. PIP 40 hain par thumbnail `LruCache(32)` → scroll par re-decode (draw phase me).
6. Overlay textures agent ne "chhote" kahe. Worst case: 120 × (1024×1024×4 B = 4 MiB) = **~480 MiB**. Yeh sirf upper bound hai (sab max size par maan kar).

---

## 3. Sachchai (is plan ki seemayein)

1. "Har device par perfect" ka koi guarantee nahi. Plan sirf risk ghatata hai.
2. Offline-only: shipped app me failure ka feedback loop nahi. Isliye har bachaav app ke andar hai (probe, fallback ladder, crash-loop guard).
3. Test phones: Redmi Note 11T 5G (Dimensity 810), Moto E40 (Unisoc T700 — mujhe memory se pata hai, confirm karo), Infinix X6525 (Unisoc T606). T606/T700 spec sites ke hisaab se 1080p-class hain (official nahi; `MediaCodecList` se verify karo). **Koi high-end phone nahi** → 4K, 50/60fps, HEVC encode, `getAchievableFrameRatesFor` jaisi cheezein is plan me **unverified** rahengi.
4. Capability API ≠ speed. Docs ke hisaab se achievable frame rate manufacturer ka estimate hai (ek hi codec maan kar), null bhi ho sakta hai. Isliye benchmark + fallback ladder.
5. Is file ke saare numbers (bpp, tier thresholds, factors) **shuruati andaze** hain. Phones par measure karke tune karna.
6. **4K export = 1080p source ka upscale.** Upar se PIP video 640px par cap aur image overlays 1024px par cap → 4K me softness dikhegi. Sharpness tab badhegi jab Text/Sticker export-scale par bake honge (Phase 5, T5.4).
7. **HDR contradiction:** import me 10-bit/HDR reject hai, to sab sources SDR 8-bit hain. SDR content ko HDR container me daalne se asli HDR nahi milta. Ye bekaar (aur galat dikhne ka risk) hai. HDR tab sahi hoga jab (a) HDR import allow ho, (b) saare GLSL shaders 16-bit float/BT.2020 ke liye aaye, (c) ek capable phone ho. Isliye Phase H deferred.

---

## 4. Architecture

### 4.1 Export flow
```
ExportSheet (UI)
  ratio = project canvas se; user chunta hai: resolution / fps / quality
   │
   ▼
ExportPlanner:  OutputSpec(aspect, shortSide, fps, quality, codec)
   │  ExportCapabilities.find(spec)  → encoder name + aligned size + bitrate range  (ya "unsupported" + wajah)
   │  BitrateTable → bitrate;  ExportEstimator → size
   ▼
ExportPreflight:  free-space check → (optional) ExportBenchmark (N frames) → ETA
   │  user ko size + ETA dikhao; bahut slow ho to warning (block nahi)
   ▼
ExportCoordinator.start(spec)  ──►  ExportService (Foreground Service)
        │                               API 35+: mediaProcessing, neeche: dataSync; onTimeout() → stop
        ▼
   ExportEngine(spec)
     ├ preview decoders release (T1.7)
     ├ frame loop: FrameGate.arm() → render → await(); timeout = retry, phir error (chup duplicate nahi)
     ├ stats: duplicateFrames, timeouts, avg fps
     └ fail → ExportLadder.next(spec) → restart (resume nahi)
   ▼
Output: temp+copy (abhi) ya direct MediaStore (T1.8, optional)
SessionGuard: export shuru par marker, khatam par hataya; agle launch par marker mila → woh spec "bad" (2 baar fail ke baad exclude)
```

### 4.2 Runtime (tier + governor)
```
App start ─► DeviceProfile (RAM, isLowRam, SDK, hw encoder/decoder caps, performance class jahan ho)
              cache key: Build.FINGERPRINT
          ─► TierPolicy.tier()  → LOW / MID / HIGH
          ─► QualityBudget (volatile): pipDecodeThreads, overlayTexBudgetMb, pipUpdateFps, autoProxy, defaultExportSpec

Runtime signals (sab background thread par):
   ThermalMonitor (status listener + getThermalHeadroom ≤ 1 call/10 s, ek hi thread)
   onTrimMemory
   late-frame counter (volatile Long; PreviewRenderer me sirf increment — T5.2, CORE-EDIT-OK)
      └► QualityGovernor.update() → Level {FULL, REDUCED, MINIMAL}  (volatile)

Consumers (volatile padhte hain, draw phase me koi lock nahi):
   OverlayTextureCache (PIP refresh fps, texture budget), ProxyManager, ExportPlanner (defaults/warning)
```
Tier sirf **default aur warning** deta hai. Gate hamesha capability + benchmark hai.

### 4.3 PIP video path (Phase 3 — measurement ke baad)
```
PIP video add  →  ProxyManager (background): max-side-640, all-intra H.264, video-only
                  (audio original se, ClipAudioDecoder wahi)
OverlayTextureCache.extractScaledVideoFrame  →  proxy ready ho to proxy, warna original (abhi jaisa)
Export bhi proxy se (frame already 640px par cap hai, isliye visual farak minimal; bit-exact nahi)
```

### 4.4 Naye files ka ghar
```
engine/export/  OutputSpec.kt  BitrateTable.kt  ExportEstimator.kt  ExportCapabilities.kt
                ExportLadder.kt  ExportPreflight.kt  ExportBenchmark.kt
                ExportService.kt  ExportCoordinator.kt  FrameGate.kt
engine/perf/    DeviceProfile.kt  TierPolicy.kt  ThermalMonitor.kt  QualityGovernor.kt  SessionGuard.kt
engine/proxy/   PipProxyGenerator.kt  ProxyStore.kt
engine/media/   ColorInfoCheck.kt
ui/media/       PipThumbLoader.kt
```
Pure-Kotlin classes (OutputSpec, BitrateTable, ExportEstimator, ExportLadder, QualityGovernor, TierPolicy) me `android.*` import nahi — JVM unit tests ke liye.

---

## 5. Phases aur Tasks

### Phase 0 — Baseline (behaviour change zero)

**T0.1 — Debug logging.** Files: `FrameSource.kt`, `ExportEngine.kt`. Sirf `BuildConfig.DEBUG`. Har decoder/encoder ka `codec.name` aur `isHardwareAccelerated` (name se `MediaCodecList` lookup), export me frame wait timeouts aur duplicate count (abhi sirf count, behaviour nahi badlega).
Accept: logcat me tag `CODEC` aur `EXPORTSTAT`.

**T0.2 — Measurement (haath se, teeno phones par).** Sheet banao:

| Scenario | Metric |
|---|---|
| Foundation scale test S/M/L | frame p50/p95/p99, janky % |
| 1 min project, PIP video 0 / 1 / 4 | preview fps (PIP ka), export time ÷ realtime |
| 5 min project (aur baad me 15 min) | export time, free-space dip, temp size |
| Export ke dauraan | `adb shell dumpsys meminfo com.flowcut.app`, `adb shell dumpsys thermalservice` |
| Har codec instance | hardware ya software (T0.1 log) |

### Phase 1 — Correctness (sab devices par)

**T1.1 — Limits ek jagah.** Files: `Limits.kt`, `EditorViewModel.kt` (+ `MAX_TOTAL_MS`/`MAX_CLIP_MS` ke baaki users, ≤5). `MAX_TOTAL_MS = 900_000L`, `MAX_CLIP_MS = <tumhara decision>`. `EditorViewModel` ka alag `MAX_TIMELINE_DURATION_US` hatao, Limits se banao (conversion `Time.kt` se). Agar `core` ke Validator me alag 15-min constant mile to pehle sirf report (uske liye `CORE-EDIT-OK` alag task).
Accept: importer, VM, validator teeno ek hi limit; 15-min project import/edit/save.

**T1.2 — Import: 10-bit/HDR reject.** Files: `ClipImporter.kt`, `engine/media/ColorInfoCheck.kt` (naya), strings (+ PIP-video import ka raasta — agent dhoondhe; ye bhi same check lage). Extractor ka format best-effort hai (profile/transfer na ho sakta hai) → agar unknown ho to accept. Message: "HDR / 10-bit videos abhi support nahi hain."
Accept: HLG/Main10 clip reject; normal SDR accept; VFR accept.

**T1.3 — `schemaVersion`.** Files: `model/Project.kt`, `ProjectStore.kt`. Field default ke saath, save par current version, load par `schemaVersion > CURRENT` ho to khulne se mana (naya project purane app me data na khoye).
Accept: purani `project.json` khulti hai; version 99 wali project reject.

**T1.4 — Thumbnail draw-phase fix [CORE-EDIT-OK].** Files: `TimelineCanvas.kt`, `ui/media/PipThumbLoader.kt` (naya). Draw se `PipThumbnailCache.get(...)` hatao. Draw sirf `loader.peek(path)` (lock-free, copy-on-write map). Load background me, failure par negative cache. Cache size 32 → 256 (64×64×4 B = 16 KiB each).
Accept: scroll karte waqt main thread par thumbnail decode nahi (StrictMode/trace); 40 PIP ke saath scroll smooth; kharab file par repeat decode nahi.

**T1.5 — Export frame gate.** Files: `ExportEngine.kt`, `engine/export/FrameGate.kt` (naya). Pattern: `arm()` render se pehle, `await(timeout)` baad me. Timeout par ek retry, phir `ExportFrameTimeoutException` (chup duplicate nahi). `updateTexImage` ki exception nigalna band. Export result me `duplicateFrames`/`timeouts`.
Accept: jaan-boojh kar slow decoder (e.g. har frame par sleep wala test build) par export fail ho ya retry me sahi frame aaye — kabhi silent duplicate nahi; output frame count = expected.

**T1.6 — Export foreground service.** Files: `AndroidManifest.xml`, `ExportService.kt`, `ExportCoordinator.kt`, `EditorViewModel.kt`, (`ExportSheet.kt`). Type: API 35+ `mediaProcessing`, neeche `dataSync`. `onTimeout()` me turant stop. Notification me progress + cancel. Export ab ViewModel scope me nahi.
Accept: export shuru karke Home dabao, 2 min baad wapas aao → export chal raha hai. Android 11 (Redmi), Android 14/15 (jo mile) par launch test. Android 13+ par notification permission deny karke bhi export crash nahi karna.
Note: `foregroundServiceType="dataSync|mediaProcessing"` ka combo purane OS par kaise behave karta hai, mujhe docs se confirm nahi hua. Agar manifest/launch me issue aaye to sirf `dataSync` par fallback. Play Console me FGS declaration bharna padega (verify karo).

**T1.7 — Export ke dauraan preview decoders release [CORE-EDIT-OK agar `PreviewRenderer` me hook chahiye].** Files: `EditorViewModel.kt`, preview engine/renderer ka release-resume hook. Export shuru → preview pause + A/B decoders release. Export khatam/cancel → playhead par re-prepare.
Accept: export ke dauraan sirf export ke decoders allocated (T0.1 log); wapas aane par preview theek.

**T1.8 — Disk-space preflight (+ optional direct write).** Files: `ExportPreflight.kt`, `ExportEngine.kt`, strings. `estimated = (videoBps + 192k) × duration / 8`. Abhi temp+copy hai, isliye cache volume me `estimated` aur Movies volume me `estimated` (same volume ho to double). 200 MB margin. Kam ho to export shuru hi nahi. Optional: `MediaMuxer(FileDescriptor)` se seedha MediaStore pending fd — device par test karo, fail ho to temp+copy.
Accept: bharte hue storage par export clean error deta hai.

### Phase 2 — OutputSpec aur Export planner (ratio-agnostic)

**T2.1 — Pure logic.** Files: `OutputSpec.kt`, `BitrateTable.kt`, `ExportEstimator.kt` + unit tests. `OutputSpec(aspect, shortSide, fps, quality, codec)`; size alignment ke saath.
Accept: 9:16/1080 → 1080×1920, 16:9/2160 → 3840×2160, 4:3/1080 → 1440×1080; 1080p30 high ≈ 9.95 Mbps.

**T2.2 — ExportEngine OutputSpec par.** Files: `ExportEngine.kt`, `Limits.kt` (EXPORT_* ko deprecate), `EditorViewModel.kt`. Agent ke report ke mutabiq `EXPORT_W/H/FPS/BITRATE` in jagah use hote hain: total frames, encoder format, `glViewport`, FrameSource buffer size, shader texel size, PTS (`i × 1e9 / fps`), MediaStore WIDTH/HEIGHT. Sab `spec` se. **Default spec = 9:16, 1080, 30, high, AVC** taaki abhi ka output wahi rahe.
Accept: default export abhi ke jaisa (1080×1920@30, ~10 Mbps); 720p/25fps export sahi duration aur A/V sync.

**T2.3 — Capability probe.** Files: `ExportCapabilities.kt`, `ExportEngine.kt`. `createEncoderByType` → probe se chuna encoder `createByCodecName`. >1080p-area ya >30fps ke liye sirf hardware encoder.
Accept: teeno phones par ek table dump (kaun si res/fps supported, hardware ya nahi).

**T2.4 — Benchmark.** Files: `ExportBenchmark.kt`, `ExportEngine.kt`. Chune hue spec par ~60 frames render+encode (output discard), fps naapo → ETA.
Accept: ETA asli export time ke ±30% me (5-min project par check). Agar nahi, factor tune karo.

**T2.5 — Fallback ladder + guard.** Files: `ExportLadder.kt`, `SessionGuard.kt`, `ExportCoordinator.kt`. Fail par: codec swap → fps ghatao → resolution ek step neeche (software encoder sirf ≤1080p30). Resume nahi, restart.
Accept: jaan-boojh kar bada spec fail karao; ladder neeche utarke export poora kare aur user ko batay.

**T2.6 — Export UI.** Files: `ExportSheet.kt`, strings, `EditorViewModel.kt`. Options: resolution / fps / quality. Unsupported option disabled + wajah. Size + ETA dikhao.
Accept: low-end phone par 4K/60 disabled; Redmi par jo probe kahe wahi.

### Phase 3 — PIP path (sirf T0.2 ke numbers dekhkar)

**T3.0 — Gate.** Agar 4 PIP video par mid-phone par preview PIP < ~15 fps **ya** export < ~0.5× realtime → T3.1–T3.3 karo. Warna chhod do.
Samajhne ke liye: `OPTION_CLOSEST` ka overhead tab bada hota hai jab target par sync frame na ho, aur executor ek hi thread hai.

**T3.1 — Proxy generator.** Files: `PipProxyGenerator.kt`, `ProxyStore.kt`. Max-side-640, all-intra, video-only. All-intra ke liye `KEY_I_FRAME_INTERVAL = 0` ya per-frame sync-frame request — **device par verify** (extractor se count karo ki saare samples sync hain).
**T3.2 — Use.** Files: `OverlayTextureCache.kt`. Proxy ready ho to use, warna original. `pipDecodeThreads` tier se (LOW 1, MID/HIGH 2). `RetrieverPool(4)` ke saath 4 simultaneous PIP me zero slack hai — 5th item par thrash ka dhyan.
**T3.3 — Storage.** Proxy `noBackupFilesDir` me, size cap (shuruat 1 GB), item/project delete par cleanup.
Accept: PIP preview fps aur export time T0.2 se behtar; PIP visual preview ≈ export.

### Phase 4 — Thumbnails aur waveform

**T4.1** `ThumbnailService` (main-track): `OPTION_CLOSEST_SYNC`, cancellable, disk cache, byte-budget LRU. **T4.2 [CORE-EDIT-OK]** `TimelineCanvas` real thumbnails sirf pre-filled cache se draw kare (draw me I/O nahi). **T4.3** Waveform compute (peaks, ek baar file me). **T4.4 [CORE-EDIT-OK]** waveform draw.
Accept: scale test gates (Foundation §12) bina bigde pass.

### Phase 5 — Tier, governor, memory

**T5.1** `DeviceProfile` + `TierPolicy` (pure) + probe. **T5.2 [CORE-EDIT-OK]** `ThermalMonitor`, `QualityGovernor`, late-frame counter (sirf volatile increment). **T5.3** Consumers: PIP refresh fps, thread count. **T5.4** `OverlayTextureCache` ka byte budget (LRU, visible items nahi nikalte) + Text/Sticker ko export-scale par bake (GL_MAX_TEXTURE_SIZE tak cap). Bake off-thread, upload GL thread par.
Accept: 40 text + 40 sticker + 40 PIP-photo wale project par memory (meminfo) budget ke andar; Thermal NaN/unsupported par bhi crash nahi (frame-time signal se kaam).

### Phase 6 — 15-minute verification
Scale test 15-min par; export 15-min (3 phones, 1080p30): time, temp/free space, thermal; autosave size; snapshot build time. Fail hone par limit nahi, bug theek karo.

### Phase R — Aspect ratio system (alag task, yahan sirf zaroorat)
- `OutputSpec` pehle se ratio-agnostic. Ratio system ke liye chahiye: project me `canvasAspect` (+ schema bump), preview surface size ratio se (**`PreviewRenderer` protected → `CORE-EDIT-OK`**), overlay coordinates normalized hain ya pixel (**agent se pucho**), canvas background modes.
- Resolution label short side se: 9:16/1080 = 1080×1920; 16:9/1080 = 1920×1080; 4:3/1080 = 1440×1080; 1:1/1080 = 1080×1080.

### Phase H — HDR (deferred, abhi schedule nahi)
Shart: (1) HDR import ka decision badle, (2) saare GLSL shaders 16-bit float / BT.2020 ready, (3) Android 13+ capable phone (HDR editing encoder feature), (4) tone-map fallback. Media3 ke docs ke hisaab se HDR editing me GL effects ko isi format me kaam karna padta hai.

---

## 6. Definition of Done (har phase ke baad)
- `checkRules testDebugUnitTest assembleRelease` pass (asli output).
- T0.2 sheet dobara bhari — pehle se bura nahi.
- Foundation scale test M — gates pass.
- Teeno phones par smoke: import → edit → export (default spec).

## 7. Open items — agent se confirm (P0 se pehle)
1. `Limits.kt` ke `MAX_*` ke saare users (T1.1).
2. PIP-video import ka raasta kaunsa hai (T1.2).
3. Mosaic kaise render hota hai (single-pass shader ya extra pass)? 4K par fill-rate asar.
4. Text/Sticker textures kis size par bake hote hain (1024 cap sirf image par ya sab par)?
5. Overlay coordinates normalized hain ya pixel (Phase R).
6. Preview ke `FrameSource` kaun si class own karti hai (T1.7 me `PreviewRenderer` protected ho sakta hai).
7. Ek time par visible Text/Sticker/Mosaic ki worst-case ginti (rows ke hisaab se).
8. Export `frameAtExport()` ka asli flow (T1.5 se pehle).
9. Clip audio + PIP-video audio dono hain? Simultaneous audio decoders ka worst-case.

## 8. Risks
- Capability list jhooth bol sakti hai → benchmark + ladder.
- FGS type combo purane OS par (T1.6).
- All-intra request har encoder maane, zaroori nahi (T3.1).
- Offline-only: koi field data nahi; T0.2 hi tumhari ground truth hai.

## 9. Sources (docs jo verify me use hue)
- https://developer.android.com/about/versions/14/changes/fgs-types-required
- https://developer.android.com/about/versions/15/changes/foreground-service-types
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/games/optimize/adpf/thermal
- https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities
- https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities
- https://developer.android.com/media/media3/transformer/supported-formats
- https://developer.android.com/topic/performance/performance-class
