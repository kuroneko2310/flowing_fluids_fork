package traben.flowing_fluids.mixin;

import org.junit.jupiter.api.Test;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FluidRegressionLogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinFlowingFluidRegressionTest {

    @Test
    void shallowDryEdgeKeepsOneLevelTailBehavior() {
        assertTrue(FluidRegressionLogic.shouldPreserveThinShallowFlow(2, 0, 2));
        assertTrue(FluidRegressionLogic.shouldPreserveThinShallowFlow(3, 0, 3));
        assertFalse(FluidRegressionLogic.shouldPreserveThinShallowFlow(4, 0, 4));
        assertFalse(FluidRegressionLogic.shouldPreserveThinShallowFlow(2, 1, 1));
    }

    @Test
    void tinyConnectedPlacementCanFanOutIntoLevelOneTails() {
        assertEquals(1, FluidRegressionLogic.computeDryActivationCount(1, 4, 2));
        assertEquals(2, FluidRegressionLogic.computeDryActivationCount(2, 4, 2));
        assertEquals(3, FluidRegressionLogic.computeDryActivationCount(3, 4, 2));
        assertEquals(1, FluidRegressionLogic.computeDryActivationCount(4, 4, 2));
    }

    @Test
    void shallowDryEdgeBalancePrefersOneLevelLeadingTail() {
        FFFluidUtils.DiscreteFlowBalance balance = FFFluidUtils.resolveDiscreteFlowBalance(
                3,
                0,
                0,
                FluidRegressionLogic.getThinDryEdgeDestinationBiasLevels(3, 0));

        assertEquals(2, balance.sourceAmount());
        assertEquals(1, balance.destinationAmount());
    }

    @Test
    void thinDryEdgeBalanceGuardSkipsRetainedDropoffFlows() {
        assertTrue(FluidRegressionLogic.shouldPreferThinDryEdgeBalance(3, 0, 3, 0));
        assertFalse(FluidRegressionLogic.shouldPreferThinDryEdgeBalance(3, 0, 3, 1));
    }

    @Test
    void totalAmountBelowWaterlogThresholdFallsBackToNormalFlow() {
        assertEquals(0, FluidRegressionLogic.computeVanillaWaterlogTransferAmount(true, true, 3, 4));
    }

    @Test
    void sourceOnlyWaterlogRespectsDestinationCapacity() {
        assertEquals(5, FluidRegressionLogic.computeVanillaWaterlogTransferAmount(true, false, 8, 3));
        assertEquals(0, FluidRegressionLogic.computeVanillaWaterlogTransferAmount(true, false, 8, 8));
    }

    @Test
    void destinationWaterlogOnlyRequestsExactFillAmount() {
        assertEquals(2, FluidRegressionLogic.computeVanillaWaterlogTransferAmount(false, true, 8, 6));
    }
}
