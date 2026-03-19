package traben.flowing_fluids.mixin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinWaterFluidRegressionTest {

    @Test
    void evaporationRequiresOpenSpaceAbove() {
        assertFalse(MixinFluidRegressionLogic.isSurfaceEvaporationCandidate(true));
    }

    @Test
    void exposedSurfaceWithoutWaterAboveCanEvaporate() {
        assertTrue(MixinFluidRegressionLogic.isSurfaceEvaporationCandidate(false));
    }
}
