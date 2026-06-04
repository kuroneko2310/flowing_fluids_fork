package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import traben.flowing_fluids.config.FFConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidRouteSolverTest {

    @Test
    void routeSolverDefaultsOff() {
        assertFalse(new FFConfig().enableRouteSolver);
    }

    @Test
    void routeSolverPreservesMassAcrossHorizontalRoute() {
        int[] amounts = {
            FluidAmountConverter.getMaxInternal(),
            0,
            0
        };
        int[] yLevels = {64, 64, 64};
        long[] positions = {
            BlockPos.asLong(0, 64, 0),
            BlockPos.asLong(1, 64, 0),
            BlockPos.asLong(2, 64, 0)
        };

        FluidRouteSolver.Result result = FluidRouteSolver.solve(amounts, yLevels, positions, 4, 16, 2.0f);

        assertEquals(sum(amounts), sum(result.amountsInternal()));
        assertTrue(result.amountsInternal()[0] < amounts[0]);
        assertTrue(result.amountsInternal()[1] > 0);
        assertTrue(result.movedInternalAmount() > 0);
    }

    @Test
    void routeSolverPullsWaterDownConnectedDrop() {
        int max = FluidAmountConverter.getMaxInternal();
        int[] amounts = {max, 0};
        int[] yLevels = {65, 64};
        long[] positions = {
            BlockPos.asLong(0, 65, 0),
            BlockPos.asLong(0, 64, 0)
        };

        FluidRouteSolver.Result result = FluidRouteSolver.solve(amounts, yLevels, positions, 2, max, 3.0f);

        assertEquals(max, sum(result.amountsInternal()));
        assertTrue(result.amountsInternal()[1] > 0);
        assertTrue(result.amountsInternal()[0] < max);
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }
}
