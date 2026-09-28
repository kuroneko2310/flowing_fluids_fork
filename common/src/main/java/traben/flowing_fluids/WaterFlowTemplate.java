package traben.flowing_fluids;

/**
 * Chooses the inexpensive local water behavior before the slope-search fallback.
 * Downward transfer is handled by the caller first; this class owns only the
 * horizontal decision that remains after the cell below can no longer accept water.
 */
public final class WaterFlowTemplate {
    private WaterFlowTemplate() {
    }

    public enum HorizontalMode {
        LOCAL_LEVEL_TRANSFER,
        DEEP_EDGE_SEARCH,
        SETTLED
    }

    public static HorizontalMode chooseHorizontalMode(boolean water,
                                                      boolean thinEdgeSearch,
                                                      int sourceAmount,
                                                      int lowestTargetAmount) {
        if (!water) {
            return HorizontalMode.DEEP_EDGE_SEARCH;
        }
        if (thinEdgeSearch) {
            return HorizontalMode.DEEP_EDGE_SEARCH;
        }

        int source = clampBlockAmount(sourceAmount);
        int target = clampBlockAmount(lowestTargetAmount);
        return source - target > 1
            ? HorizontalMode.LOCAL_LEVEL_TRANSFER
            : HorizontalMode.SETTLED;
    }

    private static int clampBlockAmount(int amount) {
        return Math.max(0, Math.min(8, amount));
    }
}
