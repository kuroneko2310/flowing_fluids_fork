package traben.flowing_fluids.mixin;

import org.junit.jupiter.api.Test;
import traben.flowing_fluids.FluidRegressionLogic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinWaterFluidRegressionTest {

    @Test
    void evaporationRequiresOpenSpaceAbove() {
        assertFalse(FluidRegressionLogic.isSurfaceEvaporationCandidate(true));
    }

    @Test
    void exposedSurfaceWithoutWaterAboveCanEvaporate() {
        assertTrue(FluidRegressionLogic.isSurfaceEvaporationCandidate(false));
    }
}
