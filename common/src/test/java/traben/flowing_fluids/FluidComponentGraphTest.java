package traben.flowing_fluids;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidComponentGraphTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void stableInteriorRejectsPartialOrFrontierCells() {
        FluidComponentGraph.FluidComponentCell frontier = new FluidComponentGraph.FluidComponentCell(
            Fluids.WATER, FluidAmountConverter.toInternal(8), 1, true, false, false);
        FluidComponentGraph.FluidComponentSummary fullSummary = new FluidComponentGraph.FluidComponentSummary(
            1, Fluids.WATER, 32, 32 * FluidAmountConverter.toInternal(8), 2, 0, 0, 60, 60, false);
        FluidComponentGraph.FluidComponentSummary partialSummary = new FluidComponentGraph.FluidComponentSummary(
            1, Fluids.WATER, 32, 32 * FluidAmountConverter.toInternal(8), 2, 0, 0, 60, 60, true);

        assertFalse(FluidComponentGraph.isStableInterior(frontier, fullSummary));
        assertFalse(FluidComponentGraph.isStableInterior(
            new FluidComponentGraph.FluidComponentCell(Fluids.WATER, FluidAmountConverter.toInternal(8), 1, false, false, false),
            partialSummary));
    }

    @Test
    void stableInteriorAcceptsCalmWaterInterior() {
        FluidComponentGraph.FluidComponentCell cell = new FluidComponentGraph.FluidComponentCell(
            Fluids.WATER, FluidAmountConverter.toInternal(8), 7, false, false, false);
        FluidComponentGraph.FluidComponentSummary summary = new FluidComponentGraph.FluidComponentSummary(
            7, Fluids.WATER, 32, 32 * FluidAmountConverter.toInternal(8), 2, 0, 0, 60, 61, false);

        assertTrue(FluidComponentGraph.isStableInterior(cell, summary));
    }

    @Test
    void stableInteriorRejectsOutletAndNoisySurface() {
        FluidComponentGraph.FluidComponentCell cell = new FluidComponentGraph.FluidComponentCell(
            Fluids.WATER, FluidAmountConverter.toInternal(8), 3, false, false, false);
        FluidComponentGraph.FluidComponentSummary outlet = new FluidComponentGraph.FluidComponentSummary(
            3, Fluids.WATER, 32, 32 * FluidAmountConverter.toInternal(8), 2, 1, 0, 60, 60, false);
        FluidComponentGraph.FluidComponentSummary noisy = new FluidComponentGraph.FluidComponentSummary(
            3, Fluids.WATER, 32, 32 * FluidAmountConverter.toInternal(8), 12, 0, 0, 60, 60, false);

        assertFalse(FluidComponentGraph.isStableInterior(cell, outlet));
        assertFalse(FluidComponentGraph.isStableInterior(cell, noisy));
    }
}
