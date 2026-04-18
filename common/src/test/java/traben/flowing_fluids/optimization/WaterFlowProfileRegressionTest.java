package traben.flowing_fluids.optimization;

import org.junit.jupiter.api.Test;
import traben.flowing_fluids.FlowingFluids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterFlowProfileRegressionTest {

    @Test
    void snapshotRadiusShrinksWithDistanceLoadForChannelRequests() {
        int oldHorizontalDepth = FlowingFluids.config.horizontalSupplementDepth;
        int oldInletSteps = FlowingFluids.config.inletProbeMaxSteps;
        int oldBfsDistance = FlowingFluids.config.bfsMaxSearchDistance;
        try {
            FlowingFluids.config.horizontalSupplementDepth = 12;
            FlowingFluids.config.inletProbeMaxSteps = 8;
            FlowingFluids.config.bfsMaxSearchDistance = 10;

            int fullLoadRadius = WaterFlowProfile.computeDistanceScaledSnapshotRadius(
                10, 1.0f, true, WaterFlowProfile.Regime.CHANNEL);
            int shedLoadRadius = WaterFlowProfile.computeDistanceScaledSnapshotRadius(
                6, 0.35f, true, WaterFlowProfile.Regime.CHANNEL);

            assertEquals(10, fullLoadRadius);
            assertEquals(9, shedLoadRadius);
            assertTrue(shedLoadRadius < fullLoadRadius);
        } finally {
            FlowingFluids.config.horizontalSupplementDepth = oldHorizontalDepth;
            FlowingFluids.config.inletProbeMaxSteps = oldInletSteps;
            FlowingFluids.config.bfsMaxSearchDistance = oldBfsDistance;
        }
    }

    @Test
    void largeBodiesStayClampedToTheirSmallSnapshotCap() {
        int oldHorizontalDepth = FlowingFluids.config.horizontalSupplementDepth;
        int oldInletSteps = FlowingFluids.config.inletProbeMaxSteps;
        int oldBroadSurfaceClamp = FlowingFluids.config.broadSurfaceSlopeClamp;
        try {
            FlowingFluids.config.horizontalSupplementDepth = 12;
            FlowingFluids.config.inletProbeMaxSteps = 8;
            FlowingFluids.config.broadSurfaceSlopeClamp = 2;

            int radius = WaterFlowProfile.computeDistanceScaledSnapshotRadius(
                8, 0.5f, false, WaterFlowProfile.Regime.LARGE_BODY);

            assertEquals(6, radius);
        } finally {
            FlowingFluids.config.horizontalSupplementDepth = oldHorizontalDepth;
            FlowingFluids.config.inletProbeMaxSteps = oldInletSteps;
            FlowingFluids.config.broadSurfaceSlopeClamp = oldBroadSurfaceClamp;
        }
    }

    @Test
    void fastCalmInteriorPathRecognizesFullStableBroadWater() {
        assertTrue(WaterFlowProfile.qualifiesForFastCalmInterior(
            8,
            false,
            0.05f,
            true,
            false,
            false,
            true,
            4,
            0,
            96,
            2,
            240.0f
        ));
    }

    @Test
    void fastCalmInteriorPathRejectsLiveFrontierWater() {
        assertFalse(WaterFlowProfile.qualifiesForFastCalmInterior(
            8,
            false,
            0.05f,
            false,
            false,
            false,
            true,
            3,
            1,
            96,
            2,
            240.0f
        ));
    }

    @Test
    void fastCalmInteriorPathRejectsMacrosWithTooManyFrontiers() {
        assertFalse(WaterFlowProfile.qualifiesForFastCalmInterior(
            8,
            false,
            0.05f,
            true,
            false,
            false,
            true,
            4,
            0,
            96,
            24,
            240.0f
        ));
    }
}
