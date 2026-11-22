package com.flowcut.app.core.feature

import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.TimelineSnapshot

/**
 * Preview render pass plugin contract (pure Kotlin).
 * Enables preview tools to run as isolated GL passes.
 */
interface RenderPass {
    val id: String
    val order: Int
    fun onGlCreated()
    fun draw(ctx: PassCtx)
    fun onGlDestroyed()
}

/**
 * Read-only information about the base video/transition frame pass
 * so passes never read renderer internals.
 */
interface BaseFrameInfo {
    val textureId: Int
    val matrix: FloatArray
    val crop: FloatArray

    val videoTexId: Int get() = textureId
    val videoMat: FloatArray get() = matrix
}

/**
 * Context provided to each RenderPass during draw phase on the GL thread.
 */
interface PassCtx {
    val tUs: Long
    val snapshot: TimelineSnapshot
    val activeSet: ActiveSet
    val active: ActiveSet get() = activeSet
    val outputWidth: Int
    val outputHeight: Int
    val baseFrame: BaseFrameInfo
    val baseFrameInfo: BaseFrameInfo get() = baseFrame
}

/**
 * Standalone immutable BaseFrameInfo implementation for tests and standalone contexts.
 */
data class SimpleBaseFrameInfo(
    override val textureId: Int,
    override val matrix: FloatArray,
    override val crop: FloatArray
) : BaseFrameInfo {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BaseFrameInfo) return false
        if (textureId != other.textureId) return false
        if (!matrix.contentEquals(other.matrix)) return false
        if (!crop.contentEquals(other.crop)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = textureId
        result = 31 * result + matrix.contentHashCode()
        result = 31 * result + crop.contentHashCode()
        return result
    }
}

/**
 * Standalone PassCtx implementation for tests and standalone contexts.
 */
data class SimplePassCtx(
    override val tUs: Long,
    override val snapshot: TimelineSnapshot,
    override val activeSet: ActiveSet,
    override val outputWidth: Int,
    override val outputHeight: Int,
    override val baseFrame: BaseFrameInfo
) : PassCtx
