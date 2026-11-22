package com.flowcut.app.engine

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log

/**
 * Isolated GL Pass Scope for FlowCut preview rendering (GL state isolation).
 *
 * Ensures each draw pass (base video, transitions, mosaics, overlays) runs with
 * explicitly configured GL state and restores a known baseline state in finally,
 * isolating errors and preventing GL state contamination across passes and frames.
 */
object GlPassScope {

    const val TAG: String = "GlPassScope"

    /**
     * Restores the canonical baseline GL state expected by the FlowCut preview engine:
     * - Viewport set to full render surface
     * - Blending disabled with standard alpha blend func
     * - Scissor test disabled
     * - Active texture unit reset to GL_TEXTURE0
     * - Textures unbound on units 0 & 1
     * - Program and VAO unbound
     */
    fun restoreBaseline(viewportWidth: Int, viewportHeight: Int) {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)

        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)

        // Clear texture unit 1 (used by transitions)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        // Clear texture unit 0 (primary unit)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        if (viewportWidth > 0 && viewportHeight > 0) {
            GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        }
    }

    /**
     * Executes a single draw pass within an isolated GL state boundary.
     *
     * @param passName Identifying name for debugging and error logging.
     * @param viewportWidth Surface viewport width in pixels.
     * @param viewportHeight Surface viewport height in pixels.
     * @param enableBlend Whether GL_BLEND should be enabled for this pass.
     * @param blendSrc Blend source factor (default GL_SRC_ALPHA).
     * @param blendDst Blend destination factor (default GL_ONE_MINUS_SRC_ALPHA).
     * @param enableScissor Whether GL_SCISSOR_TEST should be enabled for this pass.
     * @param scissorRect Scissor box [x, y, width, height] when enableScissor is true.
     * @param pass Lambda executing the draw operations.
     */
    inline fun runPass(
        passName: String,
        viewportWidth: Int,
        viewportHeight: Int,
        enableBlend: Boolean = false,
        blendSrc: Int = GLES30.GL_SRC_ALPHA,
        blendDst: Int = GLES30.GL_ONE_MINUS_SRC_ALPHA,
        enableScissor: Boolean = false,
        scissorRect: IntArray? = null,
        pass: () -> Unit
    ) {
        try {
            if (viewportWidth > 0 && viewportHeight > 0) {
                GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
            }

            if (enableBlend) {
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(blendSrc, blendDst)
            } else {
                GLES30.glDisable(GLES30.GL_BLEND)
            }

            if (enableScissor && scissorRect != null && scissorRect.size >= 4) {
                GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                GLES30.glScissor(scissorRect[0], scissorRect[1], scissorRect[2], scissorRect[3])
            } else {
                GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            }

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

            pass()
        } catch (e: Throwable) {
            com.flowcut.app.engine.perf.PreviewEventLog.logSwallowedException("GlPassScope.runPass($passName)", e)
            Log.e(TAG, "GL draw pass '$passName' failed: ${e.message}", e)
        } finally {
            restoreBaseline(viewportWidth, viewportHeight)
        }
    }
}
