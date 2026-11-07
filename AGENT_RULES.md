# FLOWCUT ANDROID VIDEO EDITOR — AI AGENT MASTER RULES

You are working on **FlowCut**, a Native Android Video Editor (`com.flowcut.app`).
The architecture is strictly locked by `FlowCut_Foundation_Spec.txt` (Version F1). You MUST obey every rule below without exception.

---

## 1. TECH STACK & PACKAGE BOUNDARIES
- **Platform:** Native Android (Min SDK 30, Target SDK 36), Kotlin, Gradle Kotlin DSL (`build.gradle.kts`).
- **UI:** Jetpack Compose (Material3 + Single Custom `Canvas` for Timeline).
- **Engine:** Native `MediaCodec`, `MediaExtractor`, `MediaMuxer`, OpenGL ES 2.0 + `SurfaceTexture`, `Choreographer`. (NO FFmpeg or third-party libraries).
- **Strict Dependency Direction (`com.flowcut.app`):**
  - `core/` (`time`, `model`, `timeline`, `playback`, `edit`, `feature`) -> Pure Kotlin ONLY (`kotlinx.serialization`, `kotlinx.coroutines`, and ONLY `@Immutable`/`@Stable` from Compose). NEVER import `android.*`, other `androidx.*`, `engine`, `ui`, `features`, or `audio` inside `core/`.
  - `engine/`, `audio/`, `features/`, `ui/`, `storage/`, `debug/` -> May depend on `core/`.
  - `features/<name>/` -> Isolated per feature. Features MUST NEVER import or depend on other features.
  - `ui/` -> Never touches internal classes of any feature directly; communicates ONLY via `FeatureRegistry` and `EditorController.dispatch(Command)`.

---

## 2. THE 12 IRON RULES (NON-NEGOTIABLE)
1. **R1 (Single Playhead Writer):** ONLY `PlaybackController` writes to `.timeUs`. Everyone else reads via `PlayheadReader`.
2. **R2 (Zero Scroll State):** Timeline has NO independent scroll state. Scroll offset is derived purely from `playhead.timeUs`. NEVER use `LazyRow`, `LazyColumn`, `ScrollState`, `rememberScrollState`, `horizontalScroll`, or `scrollable(` in `ui/timeline/`.
3. **R3 (Draw-Phase Read Only):** Fast-changing state (`playhead.timeUs`, zoom, `frameTick`) MUST be read ONLY inside the Canvas draw phase—NEVER in Compose composition.
4. **R4 (Single Vsync):** `FrameDriver` is the ONLY `Choreographer.FrameCallback` in the entire app.
5. **R5 (Long Time Units):** Time is ALWAYS `Long` (`ms` in model, `us` in playhead/engine, `frames` in audio via `core/time/Time.kt`). NEVER use `Float` or `Int` for time/duration, and NEVER write manual `* 1000` or `/ 1000` conversions outside `Time.kt`.
6. **R6 (Immutable Snapshots):** `Project` and `TimelineSnapshot` are strictly immutable. Edits build a new snapshot on `Dispatchers.Default` and swap `SnapshotHolder.current` atomically.
7. **R7 (O(log n) Resolution):** No index building during frames. Use binary search (`MainIndex`, `LaneIndex`) writing into a pre-allocated `ActiveSet`.
8. **R8 (Pull Model):** Timeline never pushes to features. Contributors pull active state for `tUs`.
9. **R9 (Single Edit Gate):** Every edit MUST go through `EditorController.dispatch(Command)`. Live slider drags update pre-allocated `LiveParams` and dispatch a single `Command` only on drag end.
10. **R10 (Zero-Allocation Hot Path):** Inside vsync (`doFrame`), GL draw, audio block, and timeline Canvas `onDraw`: NO object allocations (`new`, `listOf`, lambdas, string formatting), NO locks/synchronized blocks, NO file/JSON I/O, NO `Log.*`, and NO `Thread.sleep` or `runBlocking`.
11. **R11 (TimeMap Everywhere):** All media consumers calculate clip source time strictly via `clip.map().sourceUs(localTimelineUs)`.
12. **R12 (Plug-in Features Only):** New features live in `features/<name>/` and register in `Features.kt`. Never modify `core/` to add a feature.

---

## 3. STRICT TASK EXECUTION & FILE DISCIPLINE (NO "BHASAD")
1. **Do ONLY What Is Asked:** Execute ONLY the single task requested in the current prompt. Do NOT add bonus features, refactor unrelated code, rename variables, or change code style.
2. **Announce Files First:** Before modifying or creating any code, list the exact file paths you will touch. Do NOT touch any file outside that list.
3. **Max 5 Files Per Task:** Keep diffs small and surgical. If a task requires touching more than 5 files, STOP and ask for confirmation first.
4. **PROTECTED FILES LOCK:** Once created, the following paths are PROTECTED. You MUST NOT edit them unless the user's prompt explicitly contains the exact token `CORE-EDIT-OK`:
   - `core/**`
   - `ui/timeline/**`
   - `engine/FrameDriver.kt`
   - `engine/PreviewRenderer.kt`
   - `core/edit/EditorController.kt`
5. **One Responsibility = One File:** Never dump unrelated classes, shaders, tools, or commands into a single file. Place every file strictly in its designated package as defined in `FlowCut_Foundation_Spec.txt`.
6. **Smart Precision Within Scope:** Within the assigned file/function, handle edge cases, math boundaries, and resource cleanup with 100% accuracy—no `// TODO` or incomplete placeholders.

---

## 4. VERIFICATION & HONEST REPORTING
- After completing code changes, run and paste the full output of:
  `./gradlew checkRules testDebugUnitTest assembleRelease`
- Never claim a test or build passed if you did not actually run it. If something is untested or uncertain, state it clearly.
- Zero fluff in responses: state the files touched, provide the exact code/diff, and show verification results.