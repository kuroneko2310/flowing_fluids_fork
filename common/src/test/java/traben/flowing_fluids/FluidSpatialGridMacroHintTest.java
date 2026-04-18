package traben.flowing_fluids;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidSpatialGridMacroHintTest {

    @Test
    void macroHintTreatsDenseLowFrontierSectionsAsCalm() {
        FluidSpatialGrid.MacroFluidHint hint = new FluidSpatialGrid.MacroFluidHint(96, 3, 236.0f);
        assertTrue(hint.isLikelyCalm(32, 0.08f, 224.0f));
    }

    @Test
    void macroHintRejectsSparseOrFrontierHeavySections() {
        FluidSpatialGrid.MacroFluidHint sparse = new FluidSpatialGrid.MacroFluidHint(12, 0, 236.0f);
        FluidSpatialGrid.MacroFluidHint noisy = new FluidSpatialGrid.MacroFluidHint(96, 18, 236.0f);

        assertFalse(sparse.isLikelyCalm(32, 0.08f, 224.0f));
        assertFalse(noisy.isLikelyCalm(32, 0.08f, 224.0f));
    }
}
