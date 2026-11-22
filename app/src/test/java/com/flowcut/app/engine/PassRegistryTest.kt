package com.flowcut.app.engine

import com.flowcut.app.core.feature.PassCtx
import com.flowcut.app.core.feature.RenderPass
import com.flowcut.app.core.feature.SimpleBaseFrameInfo
import com.flowcut.app.core.feature.SimplePassCtx
import com.flowcut.app.core.timeline.ActiveSet
import com.flowcut.app.core.timeline.TimelineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PassRegistryTest {

    private lateinit var registry: PassRegistry

    @Before
    fun setUp() {
        registry = PassRegistry()
        // Bypass GL calls in JVM unit tests
        registry.passScopeRunner = { _, _, _, action -> action() }
    }

    private class TestPass(
        override val id: String,
        override val order: Int,
        private val onDrawAction: (PassCtx) -> Unit = {}
    ) : RenderPass {
        var glCreatedCount = 0
        var glDestroyedCount = 0
        var drawCount = 0

        override fun onGlCreated() {
            glCreatedCount++
        }

        override fun draw(ctx: PassCtx) {
            drawCount++
            onDrawAction(ctx)
        }

        override fun onGlDestroyed() {
            glDestroyedCount++
        }
    }

    private fun createDummyCtx(): PassCtx {
        return SimplePassCtx(
            tUs = 1_000_000L,
            snapshot = TimelineSnapshot.EMPTY,
            activeSet = ActiveSet(0),
            outputWidth = 720,
            outputHeight = 1280,
            baseFrame = SimpleBaseFrameInfo(
                textureId = 42,
                matrix = FloatArray(16) { it.toFloat() },
                crop = floatArrayOf(0.8f, 0.9f)
            )
        )
    }

    @Test
    fun testPassOrdering() {
        val executedIds = mutableListOf<String>()

        val pass100 = TestPass("pass_100", 100) { executedIds.add("pass_100") }
        val pass10 = TestPass("pass_10", 10) { executedIds.add("pass_10") }
        val pass50 = TestPass("pass_50", 50) { executedIds.add("pass_50") }
        val pass20 = TestPass("pass_20", 20) { executedIds.add("pass_20") }

        registry.register(pass100)
        registry.register(pass10)
        registry.register(pass50)
        registry.register(pass20)

        val registeredIds = registry.getPasses().map { it.id }
        assertEquals(listOf("pass_10", "pass_20", "pass_50", "pass_100"), registeredIds)

        registry.drawAll(createDummyCtx())
        assertEquals(listOf("pass_10", "pass_20", "pass_50", "pass_100"), executedIds)
    }

    @Test
    fun testPassDisabledAfter3ConsecutiveFailures() {
        var throwError = true
        var executionCount = 0

        val failingPass = TestPass("failing_pass", 10) {
            executionCount++
            if (throwError) {
                throw IllegalStateException("Simulated draw crash")
            }
        }
        val healthyPass = TestPass("healthy_pass", 20)

        registry.register(failingPass)
        registry.register(healthyPass)

        val ctx = createDummyCtx()

        // Frame 1: throws
        registry.drawAll(ctx)
        assertEquals(1, executionCount)
        assertEquals(1, healthyPass.drawCount)
        assertFalse(registry.isPassDisabled("failing_pass"))

        // Frame 2: throws
        registry.drawAll(ctx)
        assertEquals(2, executionCount)
        assertEquals(2, healthyPass.drawCount)
        assertFalse(registry.isPassDisabled("failing_pass"))

        // Frame 3: throws (3rd consecutive failure -> disabled)
        registry.drawAll(ctx)
        assertEquals(3, executionCount)
        assertEquals(3, healthyPass.drawCount)
        assertTrue(registry.isPassDisabled("failing_pass"))

        // Frame 4: failing pass is disabled and skipped; healthy pass continues
        throwError = false
        registry.drawAll(ctx)
        assertEquals(3, executionCount) // Skipped, not called!
        assertEquals(4, healthyPass.drawCount)
    }

    @Test
    fun testFailureCounterResetsOnSuccess() {
        var throwError = true
        var executionCount = 0

        val intermittentPass = TestPass("intermittent_pass", 10) {
            executionCount++
            if (throwError) {
                throw IllegalStateException("Draw failed")
            }
        }

        registry.register(intermittentPass)
        val ctx = createDummyCtx()

        // Frame 1: fail (consecutive = 1)
        registry.drawAll(ctx)
        assertFalse(registry.isPassDisabled("intermittent_pass"))

        // Frame 2: fail (consecutive = 2)
        registry.drawAll(ctx)
        assertFalse(registry.isPassDisabled("intermittent_pass"))

        // Frame 3: success (resets consecutive counter to 0)
        throwError = false
        registry.drawAll(ctx)
        assertFalse(registry.isPassDisabled("intermittent_pass"))

        // Frame 4: fail (consecutive = 1 again, NOT 3)
        throwError = true
        registry.drawAll(ctx)
        assertFalse(registry.isPassDisabled("intermittent_pass"))
        assertEquals(4, executionCount)
    }

    @Test
    fun testBaseFrameInfoExposedToPass() {
        var receivedTexId = -1
        var receivedCropX = 0f

        val pass = TestPass("inspect_pass", 1) { ctx ->
            receivedTexId = ctx.baseFrame.textureId
            receivedCropX = ctx.baseFrame.crop[0]
        }

        registry.register(pass)
        registry.drawAll(createDummyCtx())

        assertEquals(42, receivedTexId)
        assertEquals(0.8f, receivedCropX, 0.001f)
    }

    @Test
    fun testGlLifecycleNotifications() {
        val passA = TestPass("pass_a", 1)
        val passB = TestPass("pass_b", 2)

        registry.register(passA)
        assertEquals(0, passA.glCreatedCount)

        registry.onGlCreated()
        assertEquals(1, passA.glCreatedCount)

        // Late registration while GL is already active invokes onGlCreated immediately
        registry.register(passB)
        assertEquals(1, passB.glCreatedCount)

        registry.onGlDestroyed()
        assertEquals(1, passA.glDestroyedCount)
        assertEquals(1, passB.glDestroyedCount)
    }
}
