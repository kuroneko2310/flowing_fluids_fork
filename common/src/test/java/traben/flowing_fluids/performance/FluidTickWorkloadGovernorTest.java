package traben.flowing_fluids.performance;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.Fluid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void budgetPreservesEnoughAdmissionsForWaterToKeepMoving() {
        assertEquals(65_536, FluidTickWorkloadGovernor.computeBudgetForMspt(20.0, 2));
        assertEquals(16_384, FluidTickWorkloadGovernor.computeBudgetForMspt(55.0, 2));
        assertEquals(8_192, FluidTickWorkloadGovernor.computeBudgetForMspt(90.0, 2));
        assertEquals(2_048, FluidTickWorkloadGovernor.computeBudgetForMspt(150.0, 2));
        assertEquals(512, FluidTickWorkloadGovernor.computeBudgetForMspt(300.0, 2));
    }

    @Test
    void overloadedBudgetReservesMostWorkForTheMovingFront() {
        int total = FluidTickWorkloadGovernor.computeBudgetForMspt(300.0, 4);
        int active = FluidTickWorkloadGovernor.computeActiveFlowBudget(total);
        int background = FluidTickWorkloadGovernor.computeBackgroundBudget(total);

        assertEquals(total, active + background);
        assertTrue(active > background);
        assertEquals(192, active);
        assertEquals(64, background);
    }

    @Test
    void backgroundSaturationCannotConsumeTheActiveFlowReservation() {
        int total = 256;
        int backgroundLimit = FluidTickWorkloadGovernor.computeBackgroundBudget(total);
        int activeLimit = FluidTickWorkloadGovernor.computeActiveFlowBudget(total);

        assertFalse(FluidTickWorkloadGovernor.shouldAdmitWithinLane(
            false, 0, backgroundLimit, activeLimit, backgroundLimit));
        assertTrue(FluidTickWorkloadGovernor.shouldAdmitWithinLane(
            true, 0, backgroundLimit, activeLimit, backgroundLimit));
        assertFalse(FluidTickWorkloadGovernor.shouldAdmitWithinLane(
            true, activeLimit, backgroundLimit, activeLimit, backgroundLimit));
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
    void bulkWakeBudgetShrinksUnderLoad() {
        int healthy = FluidTickWorkloadGovernor.computeBulkWakeFlushBudgetForMspt(20.0);
        int overloaded = FluidTickWorkloadGovernor.computeBulkWakeFlushBudgetForMspt(90.0);
        int critical = FluidTickWorkloadGovernor.computeBulkWakeFlushBudgetForMspt(150.0);
        int extreme = FluidTickWorkloadGovernor.computeBulkWakeFlushBudgetForMspt(300.0);

        assertTrue(healthy > overloaded);
        assertTrue(overloaded > critical);
        assertTrue(critical > extreme);
        assertTrue(extreme > 0);
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
