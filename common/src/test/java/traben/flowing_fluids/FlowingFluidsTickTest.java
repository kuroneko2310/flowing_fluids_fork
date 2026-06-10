package traben.flowing_fluids;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowingFluidsTickTest {

    @Test
    void backlogDrainBudgetScalesWithPendingWorkWhenServerIsHealthy() {
        int small = FlowingFluidsTick.computeBacklogDrainBudget(4, 20.0, 1, 6);
        int medium = FlowingFluidsTick.computeBacklogDrainBudget(48, 20.0, 1, 6);
        int large = FlowingFluidsTick.computeBacklogDrainBudget(512, 20.0, 1, 6);

        assertEquals(1, small);
        assertTrue(medium > small);
        assertEquals(6, large);
    }

    @Test
    void backlogDrainBudgetStaysConservativeUnderHighMspt() {
        int healthy = FlowingFluidsTick.computeBacklogDrainBudget(512, 20.0, 1, 6);
        int busy = FlowingFluidsTick.computeBacklogDrainBudget(512, 55.0, 1, 6);
        int overloaded = FlowingFluidsTick.computeBacklogDrainBudget(512, 120.0, 1, 6);

        assertTrue(healthy > busy);
        assertTrue(busy >= overloaded);
        assertEquals(2, overloaded);
    }

    @Test
    void backlogDrainBudgetNeverExceedsPendingWork() {
        int budget = FlowingFluidsTick.computeBacklogDrainBudget(2, 20.0, 1, 6);

        assertTrue(budget > 0);
        assertTrue(budget <= 2);
        assertEquals(0, FlowingFluidsTick.computeBacklogDrainBudget(0, 20.0, 1, 6));
    }
}
