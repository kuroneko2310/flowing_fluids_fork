package traben.flowing_fluids.mixin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MixinFlowingFluidRegressionTest {

    @Test
    void totalAmountBelowWaterlogThresholdFallsBackToNormalFlow() {
        assertEquals(0, MixinFluidRegressionLogic.computeVanillaWaterlogTransferAmount(true, true, 3, 4));
    }

    @Test
    void sourceOnlyWaterlogRespectsDestinationCapacity() {
        assertEquals(5, MixinFluidRegressionLogic.computeVanillaWaterlogTransferAmount(true, false, 8, 3));
        assertEquals(0, MixinFluidRegressionLogic.computeVanillaWaterlogTransferAmount(true, false, 8, 8));
    }

    @Test
    void destinationWaterlogOnlyRequestsExactFillAmount() {
        assertEquals(2, MixinFluidRegressionLogic.computeVanillaWaterlogTransferAmount(false, true, 8, 6));
    }
}
