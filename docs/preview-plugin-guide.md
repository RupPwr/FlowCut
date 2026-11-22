# Preview Plugin Guide: Adding a LayerRenderer

This guide explains how to port or add a visual layer kind as an isolated preview plugin.

## 1. Core Architecture & Interfaces
- **`LayerRenderer`** (`core/feature/LayerRenderer.kt`): Contract with `id`, `kinds`, `onGlCreated()`, `onGlDestroyed()`, and `draw(layer, ctx, selected)`.
- **`RenderServices`** (`engine/RenderServices.kt`): Narrow host services interface providing:
  - `QuadDrawer.drawOverlayQuad(...)`: Standard quad rendering with transform, crop, mask, chroma, and adjustments.
  - `MosaicDrawer.drawMosaicRegion(...)`: Mosaic region rendering with base frame video texture, transform, and feather.
  - `TextureProvider.getTexture(item)`: Cached/baked OpenGL texture retrieval.
  - `PipTextureProvider.getVideoTexture(item, timelineUs)`: Dynamic frame-accurate PiP video textures.
- **`RenderServicesAware`**: Implement this to receive `RenderServices` injection via `attachRenderServices(services)`.

## 2. Implementation Steps
1. Create `<Kind>LayerRenderer` in `features/<kind>/` implementing `LayerRenderer` and `RenderServicesAware`.
2. Implement `draw`:
   - Cast `layer as? OverlayItem ?: return` and verify `item.type == LayerType.<KIND>`.
   - Evaluate transform with an isolated `AnimatedTransformEvaluator`.
   - Retrieve texture via `services.textureProvider.getTexture(item)` or sample `ctx.baseFrame` for effects like MOSAIC, or `services.pipTextureProvider.getVideoTexture(item, ctx.tUs)` for PiP video.
   - Draw via `services.quadDrawer.drawOverlayQuad(...)` or `services.mosaicDrawer.drawMosaicRegion(...)`.
3. Implement `onGlDestroyed`: clean up plugin-owned resources.
4. Expose `<Kind>Feature : Feature` in `features/<kind>/Index.kt` and register in `register(r: FeatureRegistry)`:
   - `r.itemType("<KIND>")`
   - `r.layerRenderer(<Kind>LayerRenderer())`
5. Add `<Kind>Feature` to `ALL_FEATURES` in `Features.kt`.

## 3. Rules & Boundaries
- **NEVER** import `PreviewRenderer` or `TransitionRenderer` directly.
- **NEVER** touch shaders, GL context state machines, or media decoders.
- **NEVER** touch another feature's package or another layer kind's texture cache.
- Maintain failure isolation: if your plugin fails 3 times, `OverlayStagePass` disables it and other layers keep rendering.
- Current status: All visual overlay kinds (TEXT, STICKER, IMAGE, MOSAIC, VIDEO) are plugins; legacy fallback retired.
