# ARCHITECTURE_RULES.md

> **Agent: इस File को किसी भी Code बदलाव से पहले पढ़ो और हर Task में इसका पालन करो।**
> (Rules English में हैं ताकि Agent उन्हें ठीक से follow करे।)

## 0. Why this file exists

This is a video editor. The recurring problem: fixing one bug breaks other features, and the Preview freezes, stutters or flickers its loader after operations.
The cure is **isolation**: every feature lives in its own file(s), talks to the rest of the app only through a small, explicit interface, and Preview has exactly **one owner**.

**Prime directive: a change for Feature X must only touch Feature X's files.**

---

## 1. Golden Rules

1. **One feature = one folder (or one file if tiny).** Trim, Split, Text, Filter, Speed, Audio, Export each get their own.
2. **No feature imports another feature.** Features may only import from `core/` and `shared/`. If A needs B, go through the timeline model or the event/command layer, never a direct import.
3. **One source of truth for each piece of state.** Never keep a copy of the timeline, playhead or loader state in a second place.
4. **Preview has one owner** (`PreviewController`). No other file touches the player/decoder/texture/surface directly.
5. **Features describe edits; Preview renders them.** A feature changes the timeline model. It never calls the player.
6. **No god files.** File over ~300 lines or function over ~40 lines → split it.
7. **Minimal diffs.** Fix only what was asked. No drive-by refactors, renames or "cleanups" in files outside the task.
8. **Never delete or rewrite working code to "simplify" it** unless the task explicitly asks for it.

---

## 2. Folder Structure (adapt names to the actual stack)

```
src/
├── core/                      # Stable foundation. Changes rarely, with great care.
│   ├── timeline/              # Timeline data model (clips, tracks, effects) + pure edit functions
│   ├── preview/               # PreviewController, playback state machine, frame cache
│   ├── commands/              # Command/event bus (undo/redo lives here)
│   └── media/                 # Decoder/asset access, thumbnails
│
├── features/                  # One folder per feature. Fully isolated.
│   ├── trim/
│   │   ├── trim.logic.*       # Pure logic: given a clip + handle position → new clip
│   │   ├── trim.ui.*          # UI only: handles, gestures
│   │   ├── trim.commands.*    # Translates UI intent into timeline commands
│   │   └── index.*            # Public API of the feature (the ONLY file others may import)
│   ├── split/
│   ├── text/
│   ├── filter/
│   └── ...
│
├── shared/                    # Generic helpers (math, time formatting, debounce, constants)
└── app/                       # Wiring: registers features, builds screens
```

**Public API rule:** other code may import only `features/<name>/index.*`, never its inner files.

---

## 3. Preview Rules (the most critical part)

### 3.1 Single owner
- Only `core/preview/PreviewController` creates, seeks, plays, pauses, disposes the player/decoder.
- Features and UI never call `seek()`, `play()`, `load()` etc. directly. They change the timeline model and the controller reacts.

### 3.2 Preview = f(timeline model, playhead)
- Preview state is derived from the timeline model and playhead. It is never patched by hand from a feature.

### 3.3 Never rebuild everything for a small edit
- Do **not** destroy and re-create the player/composition after every operation.
- Apply changes incrementally: update the affected clip/layer only.
- Overlays (text, stickers) render in a separate layer and must not trigger a video re-decode.
- Filters should update parameters, not re-load the media.

### 3.4 Loader / buffering state machine
- Exactly one state machine owns this: `idle → loading → ready → playing → seeking → error`.
- UI shows the loader **only** by reading that state. No feature may set a loader flag.
- Anti-flicker: show the loader only if loading lasts longer than ~150 ms, and once shown keep it for at least ~300 ms.
- State transitions must be centralized in one file and logged in debug builds.

### 3.5 Async safety
- **Latest wins:** a newer seek/load request cancels any older one. Every async job carries a request ID; stale results are discarded.
- During drag (trim handle, scrubbing, sliders): throttle/debounce updates; render a low-cost preview while dragging and a full-quality frame on release.
- Never run decode/render work on the UI thread.
- Every listener, timer, subscription, decoder and texture created must have a matching dispose/cleanup. No leaks.

### 3.6 Frame performance
- Nothing heavy in the per-frame path: no allocation-heavy code, no full UI rebuilds, no logging in hot loops.
- Use a small frame/thumbnail cache with a size limit and eviction policy.
- Playhead updates should repaint only the playhead/preview widget, not the whole screen.

---

## 4. Adding a New Feature (checklist)

Before writing code:
- [ ] Create `features/<name>/` with `logic`, `ui`, `commands`, `index`.
- [ ] Define what it adds to the timeline model (new effect type/property). If the model needs a change, make it minimal and in `core/timeline/` only.
- [ ] Write the feature's header comment (template in section 8).
- [ ] Register it in `app/` (one line). Do not edit other features.

While coding:
- [ ] Logic is pure (input → output) so it can be tested without UI or player.
- [ ] The feature talks to the rest only through timeline commands.
- [ ] No import from another feature.
- [ ] No direct Preview/Player calls.

Before finishing:
- [ ] Add the feature to the Feature Registry (section 9).
- [ ] Run the regression checklist (section 7).

---

## 5. Bug Fix Protocol

1. **Reproduce first.** State the exact steps (e.g. "Trim handle → release → preview freezes").
2. **Find the owning file** using the Feature Registry. Name the file and why it is the cause. Do not guess.
3. **Fix inside that file only.**
4. If another file *must* change, stop and explain: which file, why, and what could be affected. Make the smallest change possible.
5. **No unrelated edits** (formatting, renames, moved code) in the same change.
6. After the fix, list which features were *not* touched and run the regression checklist.
7. If the root cause is in a shared/core file, say so explicitly and keep the change backward compatible.

---

## 6. Forbidden Patterns

- ❌ A feature importing another feature's internals.
- ❌ A feature calling the player/decoder/surface directly.
- ❌ Setting loader/buffering flags outside the preview state machine.
- ❌ Two copies of timeline/playhead/selection state.
- ❌ Re-creating the player after every edit.
- ❌ Async work without cancellation or request ID.
- ❌ Timers/listeners/subscriptions without cleanup.
- ❌ Global mutable variables used for cross-feature communication.
- ❌ Giant files that mix UI, logic and preview control.
- ❌ Copy-pasting a block of logic into a second feature (move it to `shared/` instead).

---

## 7. Regression Checklist (run after every change)

For each operation, confirm Preview plays smoothly, no freeze, loader doesn't flicker, and the frame matches the playhead:

| Operation | Plays smoothly | No freeze | Loader stable | Undo/Redo OK |
|-----------|:-:|:-:|:-:|:-:|
| Trim handle (drag + release) | ☐ | ☐ | ☐ | ☐ |
| Split at playhead | ☐ | ☐ | ☐ | ☐ |
| Add / edit Text | ☐ | ☐ | ☐ | ☐ |
| Apply / change Filter | ☐ | ☐ | ☐ | ☐ |
| Delete clip | ☐ | ☐ | ☐ | ☐ |
| Speed change | ☐ | ☐ | ☐ | ☐ |
| Seek / scrub timeline | ☐ | ☐ | ☐ | ☐ |
| Multiple operations back-to-back | ☐ | ☐ | ☐ | ☐ |

Also verify: memory does not keep growing after repeated operations.

---

## 8. File Header Template (put at the top of every feature file)

```
/**
 * Feature:     <name>
 * File role:   logic | ui | commands | index
 * Responsible: <what this file handles, in one or two lines>
 * Depends on:  <core/shared modules only>
 * Used by:     <who calls it>
 * Never touches: Preview/Player directly, other features
 */
```

---

## 9. Feature Registry (keep this updated)

> Every time a feature is added or moved, update this table. Agents use it to find which file to fix.

| Feature | Folder | Public API | Timeline fields it owns | Notes |
|---------|--------|-----------|-------------------------|-------|
| Trim    | `features/trim/`   | `Index.kt` | clip.in / clip.out | TrimLogic, TrimCommands, TrimUi, TrimTrackCanvas |
| Split   | `features/split/`  | `Index.kt` | clip boundaries | SplitLogic, SplitCommands, SplitUi |
| Speed   | `features/speed/`  | `Index.kt` | clip.speed / TimeMap | SpeedLogic, SpeedCommands, SpeedUi |
| Audio   | `features/audio/`  | `Index.kt` | clip.volume, tracks | AudioLogic, AudioCommands, AudioUi |
| Text    | `features/text/`   | `Index.kt` | overlay layer | TextLogic, TextCommands, TextUi |
| Filter  | `features/filter/` | `Index.kt` | clip.filters / adjust | FilterLogic, FilterCommands, FilterUi |
| Mosaic  | `features/mosaic/` | `Index.kt` | overlay layer | MosaicLayerRenderer, MosaicFeature |
| PiP     | `features/pip/`    | `Index.kt` | overlay layer | PipLayerRenderer, PipFeature |
| Export  | `features/export/` | `Index.kt` | render and mux output | ExportLogic, ExportCommands, ExportUi |
| Preview | `core/preview/`    | `PreviewController` | (reads only) | Single owner of playback |

---

## 10. Code Quality Rules

- Prefer small, named, single-purpose functions.
- Prefer pure functions and immutable updates for the timeline model.
- Naming: file names say what they do (`trim.logic`, not `utils2`).
- Comments explain *why*, not *what*.
- Add or update a unit test for every pure logic function you change.
- If you are unsure, ask or state your assumption. Never silently invent files, APIs or behavior.
