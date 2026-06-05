package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidRegressionLogicTest {

    @Test
    void keepsLavaColumnsOutOfSameFluidPressureShortcut() {
        assertFalse(FluidRegressionLogic.shouldUseSameFluidVerticalPressureTransfer(true, true));
    }

    @Test
    void stillAllowsWaterColumnsToUsePressureShortcut() {
        assertTrue(FluidRegressionLogic.shouldUseSameFluidVerticalPressureTransfer(true, false));
    }

    @Test
    void differentFluidColumnsAreStillHandledNormally() {
        assertTrue(FluidRegressionLogic.shouldUseSameFluidVerticalPressureTransfer(false, true));
        assertTrue(FluidRegressionLogic.shouldUseSameFluidVerticalPressureTransfer(false, false));
    }

    @Test
    void onlyFullBroadSurfaceCellsCountAsStillReservoirInterior() {
        assertFalse(FluidRegressionLogic.shouldTreatBroadSurfaceCellAsStillReservoir(7));
        assertTrue(FluidRegressionLogic.shouldTreatBroadSurfaceCellAsStillReservoir(8));
    }

    @Test
    void nearbyDisturbanceKeepsBroadSurfaceCellOutOfStillReservoirSleep() {
        assertFalse(FluidRegressionLogic.shouldTreatBroadSurfaceCellAsStillReservoir(8, true));
        assertTrue(FluidRegressionLogic.shouldTreatBroadSurfaceCellAsStillReservoir(8, false));
    }

    @Test
    void playerDistanceGetsOneChunkVisualMaintenanceGrace() {
        assertEquals(0, FluidRegressionLogic.getPlayerVisualMaintenanceDistance(0));
        assertEquals(24, FluidRegressionLogic.getPlayerVisualMaintenanceDistance(8));
        assertEquals(48, FluidRegressionLogic.getPlayerVisualMaintenanceDistance(32));
    }

    @Test
    void distantVisualMaintenanceUsesShortSlopeSearchClamp() {
        assertEquals(0, FluidRegressionLogic.clampDistantVisualSlopeSearchDistance(0));
        assertEquals(2, FluidRegressionLogic.clampDistantVisualSlopeSearchDistance(2));
        assertEquals(3, FluidRegressionLogic.clampDistantVisualSlopeSearchDistance(6));
    }

    @Test
    void partialBroadSurfaceTransfersBypassSuppression() {
        assertTrue(FluidRegressionLogic.shouldBypassBroadSurfaceTransferSuppression(8, 7));
        assertTrue(FluidRegressionLogic.shouldBypassBroadSurfaceTransferSuppression(6, 6));
        assertFalse(FluidRegressionLogic.shouldBypassBroadSurfaceTransferSuppression(8, 8));
    }

    @Test
    void calmBroadSurfaceSuppressionOnlyAppliesWhenBothCellsAreStillReservoirs() {
        assertTrue(FluidRegressionLogic.shouldApplyCalmBroadSurfaceTransferSuppression(true, true));
        assertFalse(FluidRegressionLogic.shouldApplyCalmBroadSurfaceTransferSuppression(true, false));
        assertFalse(FluidRegressionLogic.shouldApplyCalmBroadSurfaceTransferSuppression(false, true));
        assertFalse(FluidRegressionLogic.shouldApplyCalmBroadSurfaceTransferSuppression(false, false));
    }

    @Test
    void broadSurfaceExplorationStaysEnabledOutsideCalmInterior() {
        assertTrue(FluidRegressionLogic.shouldSuppressBroadSurfaceExploratorySpread(true));
        assertFalse(FluidRegressionLogic.shouldSuppressBroadSurfaceExploratorySpread(false));
    }

    @Test
    void nearbyStepDownKeepsSlopeSearchWideEnoughForShortLedges() {
        assertEquals(2, FluidRegressionLogic.keepSlopeSearchResponsiveForConnectedFlow(
            2, 3, 4, 8, false, false, false, false, 0.0f, 8, false
        ));
        assertEquals(3, FluidRegressionLogic.keepSlopeSearchResponsiveForConnectedFlow(
            2, 3, 4, 8, true, false, false, false, 0.0f, 8, false
        ));
        assertEquals(4, FluidRegressionLogic.keepSlopeSearchResponsiveForConnectedFlow(
            4, 3, 4, 8, true, false, false, false, 0.0f, 8, false
        ));
    }

    @Test
    void connectedFlowSignalsKeepFlowDistanceResponsiveWithoutIgnoringMax() {
        assertEquals(6, FluidRegressionLogic.keepSlopeSearchResponsiveForConnectedFlow(
            2, 3, 4, 8, false, true, false, false, 0.0f, 8, false
        ));
        assertEquals(5, FluidRegressionLogic.keepSlopeSearchResponsiveForConnectedFlow(
            2, 3, 4, 8, false, false, false, true, 0.0f, 8, false
        ));
        assertEquals(4, FluidRegressionLogic.keepSlopeSearchResponsiveForConnectedFlow(
            2, 3, 4, 4, false, true, false, false, 0.0f, 8, false
        ));
        assertEquals(5, FluidRegressionLogic.keepSlopeSearchResponsiveForConnectedFlow(
            2, 3, 4, 8, false, false, false, false, 0.0f, 5, true
        ));
    }

    @Test
    void infiniteBiomeOutletSkipsTransientHorizontalSearchOnlyForRetainedSourceCaps() {
        assertTrue(FluidRegressionLogic.shouldSkipInfiniteBiomeOutletHorizontalSearch(true, true, true));
        assertFalse(FluidRegressionLogic.shouldSkipInfiniteBiomeOutletHorizontalSearch(false, true, true));
        assertFalse(FluidRegressionLogic.shouldSkipInfiniteBiomeOutletHorizontalSearch(true, false, true));
        assertFalse(FluidRegressionLogic.shouldSkipInfiniteBiomeOutletHorizontalSearch(true, true, false));
    }

    @Test
    void waterStablePoolTrackingCoversFullSurfaceCellsToo() {
        assertFalse(FluidRegressionLogic.shouldTrackWaterPoolStableTicks(0));
        assertTrue(FluidRegressionLogic.shouldTrackWaterPoolStableTicks(1));
        assertTrue(FluidRegressionLogic.shouldTrackWaterPoolStableTicks(8));
    }

    @Test
    void connectedWaterLevelingCanBeDeferredToEqualizerForCalmSameFluidCells() {
        assertTrue(FluidRegressionLogic.shouldDeferConnectedWaterLevelingToEqualizer(
            true, false, false, false, false, false,
            0.0f, 7, 5, 2, 0, 0.0f
        ));
        assertTrue(FluidRegressionLogic.shouldDeferConnectedWaterLevelingToEqualizer(
            true, false, false, false, false, false,
            0.0f, 8, 5, 3, 0, 0.0f
        ));
    }

    @Test
    void connectedWaterLevelingKeepsSequentialFlowForFrontsAndPressure() {
        assertFalse(FluidRegressionLogic.shouldDeferConnectedWaterLevelingToEqualizer(
            false, false, false, false, false, false,
            0.0f, 7, 0, 7, 0, 0.0f
        ));
        assertFalse(FluidRegressionLogic.shouldDeferConnectedWaterLevelingToEqualizer(
            true, true, false, false, false, false,
            0.0f, 7, 5, 2, 0, 0.0f
        ));
        assertFalse(FluidRegressionLogic.shouldDeferConnectedWaterLevelingToEqualizer(
            true, false, false, false, false, false,
            0.0f, 8, 4, 4, 0, 0.0f
        ));
    }

    @Test
    void waterEqualizerFillOrderPrefersLowerPotentialBeforeAmount() {
        assertTrue(FluidRegressionLogic.compareEqualizerFillOrder(
            7, 63, 0, 0, 1L,
            1, 64, 0, 0, 2L,
            true
        ) < 0);
    }

    @Test
    void equalizerFillOrderKeepsLegacyAmountFirstWhenPotentialIsDisabled() {
        assertTrue(FluidRegressionLogic.compareEqualizerFillOrder(
            7, 63, 0, 0, 1L,
            1, 64, 0, 0, 2L,
            false
        ) > 0);
    }

    @Test
    void waterEqualizerFillOrderStillSmoothsSameHeightByAmount() {
        assertTrue(FluidRegressionLogic.compareEqualizerFillOrder(
            2, 64, 0, 0, 1L,
            6, 64, 0, 0, 2L,
            true
        ) < 0);
    }

    @Test
    void waterSurfacePotentialFillsLowerCellBeforeUpperShelf() {
        int max = FluidAmountConverter.getMaxInternal();
        int[] levels = {0, 0};
        int[] yLevels = {63, 64};

        int remaining = EnhancedFluidBFS.distributeWaterBySurfacePotential(
            levels, yLevels, List.of(0, 1), max + 16, max
        );

        assertEquals(0, remaining);
        assertEquals(max, levels[0]);
        assertEquals(16, levels[1]);
    }

    @Test
    void waterSurfacePotentialEqualizesSameHeightCellsAndKeepsMass() {
        int max = FluidAmountConverter.getMaxInternal();
        int[] levels = {0, 0, 0};
        int[] yLevels = {64, 64, 64};

        int remaining = EnhancedFluidBFS.distributeWaterBySurfacePotential(
            levels, yLevels, List.of(0, 1, 2), 10, max
        );

        assertEquals(0, remaining);
        assertEquals(4, levels[0]);
        assertEquals(3, levels[1]);
        assertEquals(3, levels[2]);
        assertEquals(10, levels[0] + levels[1] + levels[2]);
    }

    @Test
    void waterSurfacePotentialUsesDryOverflowTargetsWithoutLosingMass() {
        int max = FluidAmountConverter.getMaxInternal();
        int[] levels = {0, 0, 0};
        int[] yLevels = {63, 63, 64};

        int remaining = EnhancedFluidBFS.distributeWaterBySurfacePotential(
            levels, yLevels, List.of(0, 1, 2), max * 2 + 9, max
        );

        assertEquals(0, remaining);
        assertEquals(max, levels[0]);
        assertEquals(max, levels[1]);
        assertEquals(9, levels[2]);
        assertEquals(max * 2 + 9, levels[0] + levels[1] + levels[2]);
    }

    @Test
    void waterComponentPotentialPullsIntoLowerDryCavity() {
        int max = FluidAmountConverter.getMaxInternal();
        int[] levels = {0, 0};
        int[] yLevels = {64, 63};

        int remaining = EnhancedFluidBFS.distributeWaterComponentBySurfacePotential(
            levels, yLevels, List.of(0), List.of(1), 16, max
        );

        assertEquals(0, remaining);
        assertEquals(0, levels[0]);
        assertEquals(16, levels[1]);
    }

    @Test
    void waterComponentPotentialDoesNotCreateSameHeightDryFront() {
        int max = FluidAmountConverter.getMaxInternal();
        int[] levels = {0, 0};
        int[] yLevels = {64, 64};

        int remaining = EnhancedFluidBFS.distributeWaterComponentBySurfacePotential(
            levels, yLevels, List.of(0), List.of(1), 16, max
        );

        assertEquals(0, remaining);
        assertEquals(16, levels[0]);
        assertEquals(0, levels[1]);
    }

    @Test
    void waterComponentPotentialKeepsMassAcrossWetAndLowerDryTargets() {
        int max = FluidAmountConverter.getMaxInternal();
        int[] levels = {0, 0, 0};
        int[] yLevels = {64, 64, 63};

        int remaining = EnhancedFluidBFS.distributeWaterComponentBySurfacePotential(
            levels, yLevels, List.of(0, 1), List.of(2), 70, max
        );

        assertEquals(0, remaining);
        assertEquals(max, levels[2]);
        assertEquals(4, levels[0]);
        assertEquals(3, levels[1]);
        assertEquals(70, levels[0] + levels[1] + levels[2]);
    }

    @Test
    void equalizerKeepsFullInternalWaterColumnMass() {
        int max = FluidAmountConverter.getMaxInternal();
        int[] amounts = {max, max};
        int[] yLevels = {63, 64};

        int[] equalized = EnhancedFluidBFS.equalizeAmounts(amounts, yLevels, true);

        assertEquals(max, equalized[0]);
        assertEquals(max, equalized[1]);
        assertEquals(max * 2, equalized[0] + equalized[1]);
    }

    @Test
    void equalizerKeepsVerticalPartialColumnMass() {
        int max = FluidAmountConverter.getMaxInternal();
        int[] amounts = {max, 0};
        int[] yLevels = {63, 64};

        int[] equalized = EnhancedFluidBFS.equalizeAmounts(amounts, yLevels, true);

        assertEquals(max, equalized[0]);
        assertEquals(0, equalized[1]);
        assertEquals(max, equalized[0] + equalized[1]);
    }

    @Test
    void equalizerKeepsHorizontalAverageMass() {
        int[] amounts = {63, 1, 1};
        int[] yLevels = {64, 64, 64};

        int[] equalized = EnhancedFluidBFS.equalizeAmounts(amounts, yLevels, true);

        assertTrue(equalized[0] >= 21 && equalized[0] <= 22);
        assertTrue(equalized[1] >= 21 && equalized[1] <= 22);
        assertTrue(equalized[2] >= 21 && equalized[2] <= 22);
        assertEquals(65, equalized[0] + equalized[1] + equalized[2]);
    }

    @Test
    void fluidAmountConverterKeepsBlockAndInternalScalesDistinct() {
        int max = FluidAmountConverter.getMaxInternal();

        assertEquals(8, FluidAmountConverter.toBlockState(max));
        assertEquals(max, FluidAmountConverter.toInternal(8));
    }

    @Test
    void slopeInvalidationStaysLocalForInteriorFluidChange() {
        Set<ChunkPos> affected = new HashSet<>();
        ChunkLocalSlopeCache.collectAffectedChunks(new BlockPos(8, 64, 8), affected);

        assertEquals(Set.of(new ChunkPos(0, 0)), affected);
    }

    @Test
    void slopeInvalidationExtendsAcrossChunkSeamsNearBoundary() {
        Set<ChunkPos> affected = new HashSet<>();
        ChunkLocalSlopeCache.collectAffectedChunks(new BlockPos(15, 64, 15), affected);

        assertTrue(affected.contains(new ChunkPos(0, 0)));
        assertTrue(affected.contains(new ChunkPos(1, 0)));
        assertTrue(affected.contains(new ChunkPos(0, 1)));
        assertTrue(affected.contains(new ChunkPos(1, 1)));
    }

    @Test
    void rainDrivenWaterNeedsRealLocalRain() {
        assertTrue(FluidRegressionLogic.shouldAllowRainDrivenWaterPlacement(true, true, false));
        assertFalse(FluidRegressionLogic.shouldAllowRainDrivenWaterPlacement(false, true, false));
        assertFalse(FluidRegressionLogic.shouldAllowRainDrivenWaterPlacement(true, false, false));
        assertFalse(FluidRegressionLogic.shouldAllowRainDrivenWaterPlacement(true, true, true));
    }

    @Test
    void rainBornCooldownOnlyAppliesToActualLiquidRainGrowth() {
        assertTrue(FluidRegressionLogic.shouldApplyRainBornCooldown(true, true, false, true));
        assertFalse(FluidRegressionLogic.shouldApplyRainBornCooldown(true, false, false, true));
        assertFalse(FluidRegressionLogic.shouldApplyRainBornCooldown(true, true, true, true));
        assertFalse(FluidRegressionLogic.shouldApplyRainBornCooldown(true, true, false, false));
    }

    @Test
    void blockedFaceKeepsEqualizerAndConnectedSearchFromCrossingWalls() {
        assertFalse(FluidRegressionLogic.shouldTraverseFluidAdjacency(
            false, true, false, false, false, false, false
        ));
        assertFalse(FluidRegressionLogic.shouldTraverseFluidAdjacency(
            false, false, false, true, true, true, true
        ));
    }

    @Test
    void passableFaceCanStillTraverseSameFluidOrReachableAir() {
        assertTrue(FluidRegressionLogic.shouldTraverseFluidAdjacency(
            true, true, false, false, false, false, false
        ));
        assertTrue(FluidRegressionLogic.shouldTraverseFluidAdjacency(
            true, false, false, true, false, false, false
        ));
        assertTrue(FluidRegressionLogic.shouldTraverseFluidAdjacency(
            true, false, false, false, false, true, false
        ));
    }

    @Test
    void differentOccupiedFluidDoesNotCountAsReachableTraversalTarget() {
        assertFalse(FluidRegressionLogic.shouldTraverseFluidAdjacency(
            true, false, true, false, false, true, true
        ));
    }

    @Test
    void equalizerDryActivationStaysOffForCalmBroadSurfacePools() {
        assertFalse(FluidRegressionLogic.shouldAllowEqualizerDryActivation(12, 8, 8, true));
        assertFalse(FluidRegressionLogic.shouldAllowEqualizerDryActivation(12, 7, 8, true));
    }

    @Test
    void equalizerDryActivationNeverSeedsNewCellsDuringAverageOnlyPass() {
        assertFalse(FluidRegressionLogic.shouldAllowEqualizerDryActivation(3, 2, 8, true));
        assertFalse(FluidRegressionLogic.shouldAllowEqualizerDryActivation(3, 2, 8, false));
    }

    @Test
    void cavityPressurePrefersRoofedContainedTargetsAndHigherConnectedHead() {
        float calm = FluidRegressionLogic.computeCavityPressureBias(
            6, 0.0f, 0.0f, false, false, true, false, 3, 1, 0, 0.6f, 0.45f
        );
        float pressurized = FluidRegressionLogic.computeCavityPressureBias(
            6, 0.1f, 0.5f, true, true, true, false, 1, 3, 3, 0.6f, 0.45f
        );

        assertTrue(pressurized > calm);
        assertTrue(pressurized > 0.5f);
    }

    @Test
    void cavityPressureFallsWhenTargetHasEasyDrainage() {
        float contained = FluidRegressionLogic.computeCavityPressureBias(
            6, 0.05f, 0.35f, true, true, true, false, 1, 2, 2, 0.6f, 0.45f
        );
        float draining = FluidRegressionLogic.computeCavityPressureBias(
            6, 0.05f, 0.35f, true, false, false, true, 4, 0, 0, 0.6f, 0.45f
        );

        assertTrue(contained > draining);
    }

    @Test
    void openConnectedHeadStillCreatesMeaningfulRiseBias() {
        float openHead = FluidRegressionLogic.computeCavityPressureBias(
            2, 0.0f, 0.1f, false, false, true, false, 2, 1, 6, 0.6f, 0.45f
        );
        float noHead = FluidRegressionLogic.computeCavityPressureBias(
            2, 0.0f, 0.1f, false, false, true, false, 2, 1, 0, 0.6f, 0.45f
        );

        assertTrue(openHead > noHead);
        assertTrue(openHead > 0.45f);
    }

    @Test
    void pressureCanExpandDryActivationByOnlyOneOrTwoCells() {
        assertEquals(1, FluidRegressionLogic.expandDryActivationCountForPressure(1, 3, 4, 0.2f));
        assertEquals(2, FluidRegressionLogic.expandDryActivationCountForPressure(1, 3, 4, 0.5f));
        assertEquals(3, FluidRegressionLogic.expandDryActivationCountForPressure(1, 6, 4, 1.0f));
    }

    @Test
    void containedRiseScoreRewardsRoofAndLimitedEscapes() {
        int openScore = FluidRegressionLogic.computeContainedRiseScore(false, false, true, 4, 0);
        int enclosedScore = FluidRegressionLogic.computeContainedRiseScore(true, true, false, 1, 3);

        assertTrue(enclosedScore > openScore);
    }

    @Test
    void verticalDropAloneDoesNotMakeEqualizerAverageThatPair() {
        assertFalse(FluidRegressionLogic.shouldQueueTraversalPairForEqualizer(false, true));
    }

    @Test
    void realAmountDifferenceStillQueuesEqualizerPairAcrossTraversal() {
        assertTrue(FluidRegressionLogic.shouldQueueTraversalPairForEqualizer(true, false));
        assertTrue(FluidRegressionLogic.shouldQueueTraversalPairForEqualizer(true, true));
    }

    @Test
    void dropOnlyTraversalDoesNotPromoteWholeVisitedSet() {
        assertFalse(FluidRegressionLogic.shouldPromoteVisitedEqualizerTargets(true, 0, 2));
        assertFalse(FluidRegressionLogic.shouldPromoteVisitedEqualizerTargets(true, 1, 2));
    }

    @Test
    void wideVarianceStillPromotesVisitedSet() {
        assertTrue(FluidRegressionLogic.shouldPromoteVisitedEqualizerTargets(false, 2, 2));
        assertTrue(FluidRegressionLogic.shouldPromoteVisitedEqualizerTargets(true, 3, 2));
    }

    @Test
    void calmLavaInteriorCanUseReservoirScheduling() {
        assertTrue(FluidRegressionLogic.isStillLavaReservoir(
            8, false, true, false, false, 4, true, 3
        ));
    }

    @Test
    void exposedOrUnstableLavaStaysResponsive() {
        assertFalse(FluidRegressionLogic.isStillLavaReservoir(
            8, false, true, true, false, 4, false, 8
        ));
        assertFalse(FluidRegressionLogic.isStillLavaReservoir(
            8, true, true, false, false, 4, true, 8
        ));
        assertFalse(FluidRegressionLogic.isStillLavaReservoir(
            5, false, true, false, false, 4, true, 8
        ));
    }

    @Test
    void lavaDelayBoostIsStrongerWhenSubmerged() {
        assertEquals(30, FluidRegressionLogic.getStillLavaDelay(10, false));
        assertEquals(40, FluidRegressionLogic.getStillLavaDelay(10, true));
    }

    @Test
    void calmLavaInteriorCanSkipTinyEqualizerShuffles() {
        assertFalse(FluidRegressionLogic.shouldQueueLavaEqualizer(
            true, 1, false, false, false, false, false
        ));
        assertTrue(FluidRegressionLogic.shouldQueueLavaEqualizer(
            true, 2, false, false, false, false, false
        ));
        assertTrue(FluidRegressionLogic.shouldQueueLavaEqualizer(
            true, 1, false, false, true, false, false
        ));
    }

    @Test
    void ultraWarmLavaRefillStaysInsideCalmLakeCore() {
        assertTrue(FluidRegressionLogic.shouldReplenishUltraWarmLavaReservoir(
            true, 8, true, 5, true
        ));
        assertTrue(FluidRegressionLogic.shouldReplenishUltraWarmLavaReservoir(
            true, 8, true, 5, false
        ));
        assertFalse(FluidRegressionLogic.shouldReplenishUltraWarmLavaReservoir(
            false, 8, true, 5, true
        ));
        assertFalse(FluidRegressionLogic.shouldReplenishUltraWarmLavaReservoir(
            true, 7, true, 5, true
        ));
        assertFalse(FluidRegressionLogic.shouldReplenishUltraWarmLavaReservoir(
            true, 8, true, 4, false
        ));
        assertFalse(FluidRegressionLogic.shouldReplenishUltraWarmLavaReservoir(
            true, 8, false, 5, true
        ));
    }

}
