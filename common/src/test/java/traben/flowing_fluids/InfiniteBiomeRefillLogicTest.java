package traben.flowing_fluids;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import traben.flowing_fluids.config.FFConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfiniteBiomeRefillLogicTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        FlowingFluids.rebuildInfiniteBiomeDefaults();
    }

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
    void ambientAccessAllowsDeepOceanWaterMassesWithoutSkylight() {
        assertTrue(FFFluidUtils.classifyInfiniteBiomeAmbientAccess(false, true, 40, 63, true, 1, false));
        assertTrue(FFFluidUtils.classifyInfiniteBiomeAmbientAccess(false, true, 40, 63, false, 2, false));
        assertTrue(FFFluidUtils.classifyInfiniteBiomeAmbientAccess(false, true, 63, 63, false, 1, true));
        assertFalse(FFFluidUtils.classifyInfiniteBiomeAmbientAccess(false, false, 40, 63, true, 2, false));
        assertFalse(FFFluidUtils.classifyInfiniteBiomeAmbientAccess(false, true, 70, 63, true, 3, false));
    }

    @Test
    void ambientAccessAlsoCoversShoreAndBeachBiomes() {
        assertTrue(FFFluidUtils.classifyInfiniteBiomeAmbientAccess(false, true, 62, 63, true, 1, false));
        assertTrue(FFFluidUtils.classifyInfiniteBiomeAmbientAccess(false, true, 63, 63, false, 2, false));
    }

    @Test
    void ambientAccessAboveSeaLevelNeedsSkyExposure() {
        assertTrue(FFFluidUtils.classifyInfiniteBiomeAmbientAccess(true, true, 64, 63, false, 0, false));
        assertFalse(FFFluidUtils.classifyInfiniteBiomeAmbientAccess(false, true, 64, 63, true, 3, true));
    }

    @Test
    void seaLevelOverflowDefaultsToInstantBroadCleanup() {
        FFConfig config = new FFConfig();

        assertTrue(config.seaLevelOverflowEvaporationInstant);
        assertEquals(32, config.seaLevelOverflowEvaporationMaxExcess);
        assertEquals(10, config.seaLevelOverflowInfiniteBiomeBufferRadius);
    }

    @Test
    void seaLevelOverflowBufferRadiusIsClamped() {
        FFConfig config = new FFConfig();
        config.seaLevelOverflowInfiniteBiomeBufferRadius = 200;
        config.sanitizeRanges();
        assertEquals(64, config.seaLevelOverflowInfiniteBiomeBufferRadius);

        config.seaLevelOverflowInfiniteBiomeBufferRadius = -1;
        config.sanitizeRanges();
        assertEquals(0, config.seaLevelOverflowInfiniteBiomeBufferRadius);
    }

    @SuppressWarnings("unchecked")
    @Test
    void infiniteBiomeMatchingIncludesRiversAndSwampsForOverflowCleanup() {
        Holder<Biome> river = org.mockito.Mockito.mock(Holder.class);
        org.mockito.Mockito.when(river.is(BiomeTags.IS_RIVER)).thenReturn(true);
        Holder<Biome> swamp = org.mockito.Mockito.mock(Holder.class);
        org.mockito.Mockito.when(swamp.is(Biomes.SWAMP)).thenReturn(true);

        assertTrue(FFFluidUtils.matchInfiniteBiomes(river));
        assertTrue(FFFluidUtils.matchInfiniteBiomes(swamp));
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
    void deepOceanFlowingRefillGetsMuchFasterAndStronger() {
        assertEquals(1.0f, FFFluidUtils.getInfiniteBiomeFlowingRefillChanceMultiplier(false, 24), 0.0001f);
        assertEquals(1.0f, FFFluidUtils.getInfiniteBiomeFlowingRefillChanceMultiplier(true, 0), 0.0001f);
        assertEquals(5.0f, FFFluidUtils.getInfiniteBiomeFlowingRefillChanceMultiplier(true, 8), 0.0001f);
        assertEquals(10.0f, FFFluidUtils.getInfiniteBiomeFlowingRefillChanceMultiplier(true, 16), 0.0001f);
        assertEquals(16.0f, FFFluidUtils.getInfiniteBiomeFlowingRefillChanceMultiplier(true, 24), 0.0001f);

        assertEquals(1, FFFluidUtils.getInfiniteBiomeFlowingRefillMaxAmount(1, false, 24, 2));
        assertEquals(2, FFFluidUtils.getInfiniteBiomeFlowingRefillMaxAmount(1, true, 8, 4));
        assertEquals(3, FFFluidUtils.getInfiniteBiomeFlowingRefillMaxAmount(1, true, 16, 4));
        assertEquals(4, FFFluidUtils.getInfiniteBiomeFlowingRefillMaxAmount(1, true, 24, 2));
    }

    @Test
    void heavyLoadFallbackNeedsVanillaLikeSeaLevelSourceShape() {
        assertTrue(FFFluidUtils.shouldFallbackToVanillaInfiniteSourceRefill(
                true, 63, 63, 6, 2, true, false
        ));
        assertTrue(FFFluidUtils.shouldFallbackToVanillaInfiniteSourceRefill(
                true, 62, 63, 5, 3, true, false
        ));

        assertFalse(FFFluidUtils.shouldFallbackToVanillaInfiniteSourceRefill(
                false, 63, 63, 6, 2, true, false
        ));
        assertFalse(FFFluidUtils.shouldFallbackToVanillaInfiniteSourceRefill(
                true, 60, 63, 6, 2, true, false
        ));
        assertFalse(FFFluidUtils.shouldFallbackToVanillaInfiniteSourceRefill(
                true, 63, 63, 4, 3, true, false
        ));
        assertFalse(FFFluidUtils.shouldFallbackToVanillaInfiniteSourceRefill(
                true, 63, 63, 6, 1, true, false
        ));
        assertFalse(FFFluidUtils.shouldFallbackToVanillaInfiniteSourceRefill(
                true, 63, 63, 6, 2, false, false
        ));
        assertFalse(FFFluidUtils.shouldFallbackToVanillaInfiniteSourceRefill(
                true, 63, 63, 6, 2, true, true
        ));
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

    @Test
    void nonConsumeRecoveryStaysOffForActiveFronts() {
        assertEquals(0, FluidRegressionLogic.getInfiniteBiomeNonConsumeRecoveryAmount(
            8, 6, true, true, 1
        ));
        assertEquals(0, FluidRegressionLogic.getInfiniteBiomeNonConsumeRecoveryAmount(
            8, 6, false, false, 1
        ));
    }

    @Test
    void nonConsumeRecoveryOnlyTopsUpCalmInteriorByConfiguredCap() {
        assertEquals(1, FluidRegressionLogic.getInfiniteBiomeNonConsumeRecoveryAmount(
            8, 6, false, true, 1
        ));
        assertEquals(2, FluidRegressionLogic.getInfiniteBiomeNonConsumeRecoveryAmount(
            8, 6, false, true, 3
        ));
    }

    @Test
    void passiveRefillChanceScalesDownDuringRiverDroughtsOnlyForRivers() {
        assertEquals(0.05f, FFFluidUtils.scaleInfiniteBiomePassiveRefillChance(0.05f, false, 0.2f), 0.0001f);
        assertEquals(0.01f, FFFluidUtils.scaleInfiniteBiomePassiveRefillChance(0.05f, true, 0.2f), 0.0001f);
        assertEquals(0.0f, FFFluidUtils.scaleInfiniteBiomePassiveRefillChance(0.05f, true, -1.0f), 0.0001f);
    }

    @Test
    void surfaceDrainBurstOnlyAddsASmallLocalExtraPull() {
        assertEquals(0, FFFluidUtils.getInfiniteBiomeSurfaceDrainBurstAmount(0, 1));
        assertEquals(2, FFFluidUtils.getInfiniteBiomeSurfaceDrainBurstAmount(1, 1));
        assertEquals(3, FFFluidUtils.getInfiniteBiomeSurfaceDrainBurstAmount(3, 2));
        assertEquals(5, FFFluidUtils.getInfiniteBiomeSurfaceDrainBurstAmount(4, 3));
    }

    @Test
    void thinSurfaceDrainFullyClearsVerySmallWaterLevels() {
        assertEquals(1, FFFluidUtils.classifyInfiniteBiomeSurfaceDrainAmount(1, 0, 0));
        assertEquals(2, FFFluidUtils.classifyInfiniteBiomeSurfaceDrainAmount(2, 1, 0));
        assertEquals(0, FFFluidUtils.classifyInfiniteBiomeSurfaceDrainAmount(3, 2, 2));
    }

    @Test
    void biomeEntryNormalizationAcceptsBiomeIdsAndTags() {
        assertEquals("modded:tidal_marsh",
                FFFluidUtils.normalizeConfiguredBiomeEntry(" modded:tidal_marsh ", false));
        assertEquals("#c:is_delta",
                FFFluidUtils.normalizeConfiguredBiomeEntry("c:is_delta", true));
        assertEquals("#c:is_delta",
                FFFluidUtils.normalizeConfiguredBiomeEntry("#c:is_delta", true));
    }

    @Test
    void biomeEntryNormalizationRejectsWrongShapes() {
        assertNull(FFFluidUtils.normalizeConfiguredBiomeEntry("#c:is_delta", false));
        assertNull(FFFluidUtils.normalizeConfiguredBiomeEntry("not a biome id", false));
        assertNull(FFFluidUtils.normalizeConfiguredBiomeEntry("###", true));
    }

    @Test
    void autoDetectKeywordNormalizationTrimsAndLowercases() {
        assertEquals("wetland", FFFluidUtils.normalizeConfiguredKeyword("  WetLand "));
        assertNull(FFFluidUtils.normalizeConfiguredKeyword("   "));
    }
}
