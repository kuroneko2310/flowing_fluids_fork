package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.Fluid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FluidMutationBatchTest {

    @Test
    void transferPreservesMassAndBuildsTwoCellPlan() {
        Fluid fluid = sameFluid();
        FluidMutationBatch batch = new FluidMutationBatch(mock(LevelAccessor.class))
                .transfer(BlockPos.ZERO, 8, 5, BlockPos.ZERO.east(), 2, 5, fluid);

        assertEquals(2, batch.size());
        assertEquals(0, batch.netAmountDelta(fluid));
    }

    @Test
    void rejectsTransferThatCreatesOrDeletesFluid() {
        Fluid fluid = sameFluid();

        assertThrows(IllegalArgumentException.class, () ->
                new FluidMutationBatch(mock(LevelAccessor.class))
                        .transfer(BlockPos.ZERO, 8, 4, BlockPos.ZERO.east(), 0, 3, fluid));
    }

    @Test
    void consecutiveWritesToSameCellAreComposed() {
        Fluid fluid = sameFluid();
        FluidMutationBatch batch = new FluidMutationBatch(mock(LevelAccessor.class))
                .set(BlockPos.ZERO, fluid, 4, 6)
                .set(BlockPos.ZERO, fluid, 6, 3);

        assertEquals(1, batch.size());
        assertEquals(-1, batch.netAmountDelta(fluid));
    }

    @Test
    void cancellingWritesRemoveTheCellFromThePlan() {
        Fluid fluid = sameFluid();
        FluidMutationBatch batch = new FluidMutationBatch(mock(LevelAccessor.class))
                .set(BlockPos.ZERO, fluid, 4, 6)
                .set(BlockPos.ZERO, fluid, 6, 4);

        assertEquals(0, batch.size());
        assertEquals(0, batch.netAmountDelta(fluid));
    }

    @Test
    void rejectsConflictingOrOutOfRangeMutations() {
        Fluid fluid = sameFluid();
        FluidMutationBatch batch = new FluidMutationBatch(mock(LevelAccessor.class))
                .set(BlockPos.ZERO, fluid, 4, 6);

        assertThrows(IllegalArgumentException.class, () ->
                batch.set(BlockPos.ZERO, fluid, 5, 3));
        assertThrows(IllegalArgumentException.class, () ->
                new FluidMutationBatch(mock(LevelAccessor.class)).set(BlockPos.ZERO, fluid, 0, 9));
    }

    @Test
    void conservationRuleRejectsOutOfRangeAmounts() {
        assertTrue(FluidMutationBatch.isMassConserved(8, 0, 4, 4));
        assertFalse(FluidMutationBatch.isMassConserved(9, 0, 4, 5));
        assertFalse(FluidMutationBatch.isMassConserved(8, -1, 4, 3));
    }

    private static Fluid sameFluid() {
        Fluid fluid = mock(Fluid.class);
        when(fluid.isSame(fluid)).thenReturn(true);
        return fluid;
    }
}
