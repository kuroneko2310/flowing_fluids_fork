package traben.flowing_fluids.performance;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.Fluid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FluidTickWorkloadGovernorTest {

    @Test
    void budgetShrinksAsServerLoadRises() {
        int healthy = FluidTickWorkloadGovernor.computeBudgetForMspt(20.0, 2);
        int busy = FluidTickWorkloadGovernor.computeBudgetForMspt(55.0, 2);
        int overloaded = FluidTickWorkloadGovernor.computeBudgetForMspt(90.0, 2);
        int critical = FluidTickWorkloadGovernor.computeBudgetForMspt(150.0, 2);
        int extreme = FluidTickWorkloadGovernor.computeBudgetForMspt(300.0, 2);

        assertTrue(healthy > busy);
        assertTrue(busy > overloaded);
        assertTrue(overloaded > critical);
        assertTrue(critical > extreme);
        assertTrue(extreme >= 32);
    }

    @Test
    void longerFlowDistanceGetsSmallerBudget() {
        int shortRange = FluidTickWorkloadGovernor.computeBudgetForMspt(55.0, 2);
        int longRange = FluidTickWorkloadGovernor.computeBudgetForMspt(55.0, 5);

        assertTrue(longRange < shortRange);
    }

    @Test
    void spatialStrideOnlyActivatesUnderLoad() {
        assertTrue(FluidTickWorkloadGovernor.computeSpatialStrideForMspt(20.0, 4) <= 1);
        assertTrue(FluidTickWorkloadGovernor.computeSpatialStrideForMspt(90.0, 4) > 1);
        assertTrue(FluidTickWorkloadGovernor.computeSpatialStrideForMspt(300.0, 5)
            >= FluidTickWorkloadGovernor.computeSpatialStrideForMspt(90.0, 5));
    }

    @Test
    void queuePressureDelayRisesWithBacklogOrMspt() {
        assertTrue(FluidTickWorkloadGovernor.computeQueuePressureDelay(20.0, 0) <= 1);
        assertTrue(FluidTickWorkloadGovernor.computeQueuePressureDelay(90.0, 0) > 1);
        assertTrue(FluidTickWorkloadGovernor.computeQueuePressureDelay(20.0, 300_000) > 1);
        assertTrue(FluidTickWorkloadGovernor.computeQueuePressureDelay(300.0, 600_000)
            >= FluidTickWorkloadGovernor.computeQueuePressureDelay(90.0, 0));
    }

    @Test
    void spatialDeferralLeavesSomePositionsAdmittedAcrossTime() {
        Fluid fluid = mock(Fluid.class);
        BlockPos pos = new BlockPos(17, 64, -9);

        boolean admitted = false;
        boolean deferred = false;
        for (long tick = 0L; tick < 64L; tick++) {
            boolean shouldDefer = FluidTickWorkloadGovernor.shouldSpatiallyDefer(pos, fluid, tick, 300.0, 5);
            admitted |= !shouldDefer;
            deferred |= shouldDefer;
        }

        assertTrue(admitted);
        assertTrue(deferred);
    }

    @Test
    void spatialDeferralIsOffWhenHealthy() {
        Fluid fluid = mock(Fluid.class);

        assertFalse(FluidTickWorkloadGovernor.shouldSpatiallyDefer(new BlockPos(1, 2, 3), fluid, 42L, 20.0, 5));
    }
}
