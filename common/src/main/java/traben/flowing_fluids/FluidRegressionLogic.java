package traben.flowing_fluids;

/**
 * Shared regression guards for fluid behavior that must stay usable from both
 * mixins and plain support code. Keeping this outside {@code *.mixin} avoids
 * Mixin's package ownership checks from turning helper calls into class-load
 * crashes at runtime.
 */
public final class FluidRegressionLogic {

    private FluidRegressionLogic() {
    }

    public static boolean isSurfaceEvaporationCandidate(boolean hasSameFluidAbove) {
        return !hasSameFluidAbove;
    }

    public static boolean shouldPreserveThinShallowFlow(int sourceAmount, int targetAmount, int difference) {
        if (difference <= 0) {
            return false;
        }
        // Allow shallow water to create a 1-level tail on dry ground instead of
        // freezing as a 2/3-level blob. This keeps thin surface flows visible.
        return targetAmount == 0 && sourceAmount > 0 && sourceAmount <= 3;
    }

    public static boolean shouldPreferThinDryEdgeBalance(int sourceAmount, int targetAmount,
                                                         int difference, int minimumRetainedAmount) {
        return minimumRetainedAmount <= 0
                && shouldPreserveThinShallowFlow(sourceAmount, targetAmount, difference);
    }

    public static float getThinDryEdgeDestinationBiasLevels(int sourceAmount, int targetAmount) {
        if (sourceAmount <= 0 || targetAmount != 0) {
            return 0f;
        }
        // A tiny negative destination bias breaks the 3 -> 0 tie toward a
        // visible 1-level leading edge without blocking the 2 -> 0 => 1/1 split.
        return -0.25f;
    }

    public static int computeDryActivationCount(int remaining, int dryCandidates, int minDryCellFillLevel) {
        if (remaining <= 0 || dryCandidates <= 0) {
            return 0;
        }
        // Very small leftovers should be allowed to fan out into 1-level tails.
        if (remaining <= 3) {
            return Math.min(dryCandidates, remaining);
        }

        int safeMinDryFill = Math.max(1, minDryCellFillLevel);
        int minCellsNeeded = Math.max(1, (remaining + 7) / 8);
        int maxCellsForCoherentFill = remaining >= safeMinDryFill
                ? Math.max(1, remaining / safeMinDryFill)
                : 1;
        int selected = Math.min(dryCandidates, minCellsNeeded);
        if (selected > maxCellsForCoherentFill) {
            selected = Math.min(dryCandidates, maxCellsForCoherentFill);
        }
        return Math.max(1, selected);
    }

    public static int computeVanillaWaterlogTransferAmount(boolean fromIsWaterloggableVanilla,
                                                           boolean toIsWaterloggableVanilla,
                                                           int amount,
                                                           int destFluidAmount) {
        if (amount <= 0) {
            return 0;
        }
        if (destFluidAmount + amount < 8) {
            return 0;
        }
        int requiredToFill = Math.max(0, 8 - destFluidAmount);
        if (toIsWaterloggableVanilla) {
            return requiredToFill;
        }
        if (fromIsWaterloggableVanilla) {
            return Math.min(amount, requiredToFill);
        }
        return 0;
    }
}
