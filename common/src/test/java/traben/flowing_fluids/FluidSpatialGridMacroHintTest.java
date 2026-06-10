package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

    @Test
    void emptyReadsAndRemovalsDoNotCreateSpatialStorage() {
        LevelAccessor level = mock(LevelAccessor.class);
        BlockPos pos = new BlockPos(8, 64, 8);

        FluidSpatialGrid.clearAll();

        assertEquals(0, FluidSpatialGrid.getFluidAmount(level, pos));
        assertFalse(FluidSpatialGrid.hasFluidAt(level, pos));
        assertFalse(FluidSpatialGrid.hasDimensionStorage(level));

        FluidSpatialGrid.setFluidAt(level, pos, false, 0);
        FluidSpatialGrid.setFluidAtFromBuffer(level, pos, false, 0);

        assertFalse(FluidSpatialGrid.hasDimensionStorage(level));
    }
}
