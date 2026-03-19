package traben.flowing_fluids;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfiniteBiomeRefillLogicTest {

    @Test
    void refillBandAllowsBelowSeaLevelByDefault() {
        assertTrue(FFFluidUtils.isWithinInfiniteBiomeRefillBand(62, 63, false));
        assertTrue(FFFluidUtils.isWithinInfiniteBiomeRefillBand(40, 63, false));
        assertTrue(FFFluidUtils.isWithinInfiniteBiomeRefillBand(63, 63, false));
        assertFalse(FFFluidUtils.isWithinInfiniteBiomeRefillBand(64, 63, false));
    }

    @Test
    void refillBandCanBeLimitedNearSeaLevel() {
        assertTrue(FFFluidUtils.isWithinInfiniteBiomeRefillBand(63, 63, true));
        assertTrue(FFFluidUtils.isWithinInfiniteBiomeRefillBand(62, 63, true));
        assertFalse(FFFluidUtils.isWithinInfiniteBiomeRefillBand(61, 63, true));
    }

    @Test
    void passiveRefillNeedsSupportButCanRecoverQuickly() {
        assertEquals(2, FFFluidUtils.classifyInfiniteBiomeRefillAmount(5, true, 3, 3, false, false));
        assertEquals(1, FFFluidUtils.classifyInfiniteBiomeRefillAmount(6, true, 2, 1, false, false));
        assertEquals(0, FFFluidUtils.classifyInfiniteBiomeRefillAmount(6, false, 1, 0, false, false));
    }

    @Test
    void aggressiveRefillFullyRestoresStronglySupportedPools() {
        assertEquals(5, FFFluidUtils.classifyInfiniteBiomeRefillAmount(3, true, 4, 3, false, true));
        assertEquals(2, FFFluidUtils.classifyInfiniteBiomeRefillAmount(4, true, 2, 1, false, true));
        assertEquals(0, FFFluidUtils.classifyInfiniteBiomeRefillAmount(4, false, 1, 0, false, true));
    }

    @Test
    void refillAndNonConsumeTogglesFollowExistingChances() {
        float oldRefill = FlowingFluids.config.oceanRiverSwampRefillChance;
        float oldNonConsume = FlowingFluids.config.infiniteWaterBiomeNonConsumeChance;
        float oldDrain = FlowingFluids.config.infiniteWaterBiomeDrainSurfaceChance;
        try {
            FlowingFluids.config.oceanRiverSwampRefillChance = 0.0f;
            FlowingFluids.config.infiniteWaterBiomeNonConsumeChance = 0.0f;
            FlowingFluids.config.infiniteWaterBiomeDrainSurfaceChance = 0.5f;

            assertFalse(FFFluidUtils.isInfiniteBiomeRandomRefillEnabled());
            assertFalse(FFFluidUtils.isInfiniteBiomeNonConsumeEnabled());
            assertFalse(FFFluidUtils.isInfiniteBiomeRefillEnabled());
            assertTrue(FFFluidUtils.isInfiniteBiomeSurfaceDrainEnabled());
        } finally {
            FlowingFluids.config.oceanRiverSwampRefillChance = oldRefill;
            FlowingFluids.config.infiniteWaterBiomeNonConsumeChance = oldNonConsume;
            FlowingFluids.config.infiniteWaterBiomeDrainSurfaceChance = oldDrain;
        }
    }

    @Test
    void broadSurfaceClassificationRejectsRiversAndNeedsSupport() {
        assertTrue(FFFluidUtils.classifyBroadSurfaceWater(true, false, 4, false, true, false, 8, 6));
        assertTrue(FFFluidUtils.classifyBroadSurfaceWater(false, false, 3, false, true, false, 6, 6));
        assertFalse(FFFluidUtils.classifyBroadSurfaceWater(false, true, 4, false, true, false, 8, 6));
        assertFalse(FFFluidUtils.classifyBroadSurfaceWater(true, false, 2, false, true, false, 8, 6));
        assertFalse(FFFluidUtils.classifyBroadSurfaceWater(true, false, 4, false, true, true, 8, 6));
    }
}
