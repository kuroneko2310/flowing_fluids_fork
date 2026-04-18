package traben.flowing_fluids.optimization;

import org.junit.jupiter.api.Test;
import traben.flowing_fluids.FlowingFluids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LavaFlowProfileRegressionTest {

    @Test
    void lavaEdgeOrOutletCanBypassLowEquilibriumGate() {
        assertTrue(LavaFlowProfile.shouldBypassLowEquilibriumEqualizerGate(false, true, false, false));
        assertTrue(LavaFlowProfile.shouldBypassLowEquilibriumEqualizerGate(false, false, true, false));
        assertTrue(LavaFlowProfile.shouldBypassLowEquilibriumEqualizerGate(false, false, false, true));
        assertFalse(LavaFlowProfile.shouldBypassLowEquilibriumEqualizerGate(true, false, false, false));
    }

    @Test
    void stillLavaReservoirKeepsTightSnapshotRadius() {
        int oldNetherDistance = FlowingFluids.config.lavaNetherFlowDistance;
        try {
            FlowingFluids.config.lavaNetherFlowDistance = 4;
            assertEquals(4, LavaFlowProfile.computeDistanceScaledSnapshotRadius(4, 0.5f, true, false, false));
        } finally {
            FlowingFluids.config.lavaNetherFlowDistance = oldNetherDistance;
        }
    }

    @Test
    void lavaOutletSnapshotGetsExtraReachWithoutExplodingRadius() {
        int oldNetherDistance = FlowingFluids.config.lavaNetherFlowDistance;
        try {
            FlowingFluids.config.lavaNetherFlowDistance = 4;
            assertEquals(8, LavaFlowProfile.computeDistanceScaledSnapshotRadius(6, 1.0f, false, false, true));
        } finally {
            FlowingFluids.config.lavaNetherFlowDistance = oldNetherDistance;
        }
    }
}
