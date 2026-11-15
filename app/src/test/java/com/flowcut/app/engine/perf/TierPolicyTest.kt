package com.flowcut.app.engine.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TierPolicyTest {

    @Test
    fun testLowTierLowRamUnisocDevice() {
        // "3GB" phone where OS/hardware reserves reduce reported RAM to ~2.8 GB
        val profile = DeviceProfile(
            sdk = 30,
            totalRamMb = 2800L,
            isLowRam = false,
            mediaPerfClass = 0,
            hwAvcEnc1080p60 = true,
            hwAvcDecInstances = 2
        )
        val tier = TierPolicy.tier(profile)
        val budget = TierPolicy.budget(profile)

        assertEquals(Tier.LOW, tier)
        assertEquals(1, budget.pipDecodeThreads)
        assertEquals(64, budget.overlayTexBudgetMb)
        assertEquals(15, budget.pipUpdateFps)
        assertTrue(budget.autoProxy)
    }

    @Test
    fun testLowTierExplicitLowRamFlag() {
        val profile = DeviceProfile(
            sdk = 29,
            totalRamMb = 4096L,
            isLowRam = true,
            mediaPerfClass = 0,
            hwAvcEnc1080p60 = true,
            hwAvcDecInstances = 4
        )
        val tier = TierPolicy.tier(profile)
        val budget = TierPolicy.budget(profile)

        assertEquals(Tier.LOW, tier)
        assertEquals(1, budget.pipDecodeThreads)
        assertEquals(64, budget.overlayTexBudgetMb)
        assertEquals(15, budget.pipUpdateFps)
        assertTrue(budget.autoProxy)
    }

    @Test
    fun testLowTierMissingHardware1080p60Encoder() {
        // Device with decent RAM but lacking 1080p60 hardware encoder support
        val profile = DeviceProfile(
            sdk = 31,
            totalRamMb = 6000L,
            isLowRam = false,
            mediaPerfClass = 0,
            hwAvcEnc1080p60 = false,
            hwAvcDecInstances = 4
        )
        val tier = TierPolicy.tier(profile)
        val budget = TierPolicy.budget(profile)

        assertEquals(Tier.LOW, tier)
        assertEquals(1, budget.pipDecodeThreads)
        assertEquals(64, budget.overlayTexBudgetMb)
        assertEquals(15, budget.pipUpdateFps)
        assertTrue(budget.autoProxy)
    }

    @Test
    fun testMidTierStandardDevice() {
        // 4GB RAM device (~3800MB visible) with 1080p60 encoder
        val profile4Gb = DeviceProfile(
            sdk = 33,
            totalRamMb = 3800L,
            isLowRam = false,
            mediaPerfClass = 31,
            hwAvcEnc1080p60 = true,
            hwAvcDecInstances = 6
        )
        val tier4Gb = TierPolicy.tier(profile4Gb)
        val budget4Gb = TierPolicy.budget(profile4Gb)

        assertEquals(Tier.MID, tier4Gb)
        assertEquals(2, budget4Gb.pipDecodeThreads)
        assertEquals(128, budget4Gb.overlayTexBudgetMb)
        assertEquals(30, budget4Gb.pipUpdateFps)
        assertTrue(budget4Gb.autoProxy)

        // 6GB RAM device (~5700MB visible)
        val profile6Gb = DeviceProfile(
            sdk = 34,
            totalRamMb = 5700L,
            isLowRam = false,
            mediaPerfClass = 33,
            hwAvcEnc1080p60 = true,
            hwAvcDecInstances = 8
        )
        val tier6Gb = TierPolicy.tier(profile6Gb)
        val budget6Gb = TierPolicy.budget(profile6Gb)

        assertEquals(Tier.MID, tier6Gb)
        assertEquals(2, budget6Gb.pipDecodeThreads)
        assertEquals(128, budget6Gb.overlayTexBudgetMb)
        assertEquals(30, budget6Gb.pipUpdateFps)
        assertTrue(budget6Gb.autoProxy)
    }

    @Test
    fun testHighTierFlagshipDevice() {
        // 8GB+ RAM flagship device
        val profile = DeviceProfile(
            sdk = 34,
            totalRamMb = 7800L,
            isLowRam = false,
            mediaPerfClass = 34,
            hwAvcEnc1080p60 = true,
            hwAvcDecInstances = 16
        )
        val tier = TierPolicy.tier(profile)
        val budget = TierPolicy.budget(profile)

        assertEquals(Tier.HIGH, tier)
        assertEquals(2, budget.pipDecodeThreads)
        assertEquals(256, budget.overlayTexBudgetMb)
        assertEquals(30, budget.pipUpdateFps)
        assertFalse(budget.autoProxy)
    }
}
