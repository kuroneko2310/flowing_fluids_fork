package traben.flowing_fluids.water;

/**
 * Pure groundwater model, free of world access so it can be unit tested.
 *
 * <p>Each 64x64 region holds a groundwater store {@code G} in water levels with capacity {@code C}; its saturation
 * {@code s = G / C} sets how deep the water table lies below the region's reference ground surface: a full aquifer
 * sits {@code minDepth} blocks down, an empty one {@code maxDepth} blocks down. Holes dug below the table fill by
 * seepage (taken from {@code G}), rain that soaks in recharges {@code G}, and evapotranspiration plus deep percolation
 * drain it exponentially, faster in drought and summer. Springs draw on the same aquifer, so their flow follows
 * {@code s}.</p>
 */
public final class GroundwaterMath {
    static final double TICKS_PER_DAY = 24000.0;

    private GroundwaterMath() {
    }

    public static double saturation(int stored, int capacity) {
        if (capacity <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, stored / (double) capacity));
    }

    /**
     * Depth of the water table below the reference surface, in whole blocks.
     */
    public static int tableDepth(double saturation, int minDepth, int maxDepth) {
        int shallow = Math.max(1, Math.min(minDepth, maxDepth));
        int deep = Math.max(shallow, Math.max(minDepth, maxDepth));
        double s = Math.max(0.0, Math.min(1.0, saturation));
        return (int) Math.round(deep + (shallow - deep) * s);
    }

    /**
     * Water table Y. Near the coast the table cannot sink below sea level while the ground is above it.
     */
    public static int tableY(int referenceSurfaceY, int depth, int seaLevel, int minDepth) {
        int table = referenceSurfaceY - depth;
        int coastalFloor = Math.min(seaLevel, referenceSurfaceY - Math.max(1, minDepth));
        return Math.max(table, coastalFloor);
    }

    /**
     * Exponential drain over {@code elapsedTicks}: {@code G * exp(-rate * factor * days)}, rounded down so the store
     * can actually reach zero.
     */
    public static int drain(int stored, long elapsedTicks, double ratePerDay, double factor) {
        if (stored <= 0 || elapsedTicks <= 0 || ratePerDay <= 0.0 || factor <= 0.0) {
            return Math.max(0, stored);
        }
        double days = elapsedTicks / TICKS_PER_DAY;
        return (int) Math.floor(stored * Math.exp(-ratePerDay * factor * days));
    }

    /**
     * Spring flow multiplier: 0.4 when the aquifer is empty, 1.0 at half, 1.6 when full.
     */
    public static double springMultiplier(double saturation) {
        return 0.4 + 1.2 * Math.max(0.0, Math.min(1.0, saturation));
    }
}
