package traben.flowing_fluids;

/**
 * Converts between Minecraft's 0-8 fluid amount (BlockState) and
 * internal 0-63 medium-precision amount.
 *
 * Benefits of 0-63 internal precision:
 * - Keeps sub-block smoothing without tracking ultra-fine noise
 * - Reduces churn in equalization and pressure heuristics
 * - Preserves clean 8-level mapping for vanilla block states
 *
 * Conversion:
 * - BlockState 0 = Internal 0 (no fluid)
 * - BlockState 1 = Internal 8 (1/8 full)
 * - BlockState 2 = Internal 16 (2/8 full)
 * - BlockState 3 = Internal 24 (3/8 full)
 * - BlockState 4 = Internal 32 (4/8 full)
 * - BlockState 5 = Internal 40 (5/8 full)
 * - BlockState 6 = Internal 48 (6/8 full)
 * - BlockState 7 = Internal 56 (7/8 full)
 * - BlockState 8 = Internal 63 (8/8 full, source)
 */
public class FluidAmountConverter {

    private static final int INTERNAL_MAX = 63;
    private static final int BLOCKSTATE_MAX = 8;
    private static final int LEGACY_INTERNAL_MAX = 255;

    // Pre-computed conversion tables for accuracy and performance
    private static final int[] TO_INTERNAL_TABLE = {
        0,    // 0 -> 0
        8,    // 1 -> 8
        16,   // 2 -> 16
        24,   // 3 -> 24
        32,   // 4 -> 32
        40,   // 5 -> 40
        48,   // 6 -> 48
        56,   // 7 -> 56
        63    // 8 -> 63
    };

    private static final int[] TO_BLOCKSTATE_TABLE = new int[INTERNAL_MAX + 1];

    // Initialize reverse lookup table
    static {
        for (int i = 0; i <= INTERNAL_MAX; i++) {
            // Map internal amount to nearest BlockState amount
            if (i == 0) {
                TO_BLOCKSTATE_TABLE[i] = 0;
            } else if (i <= 8) {
                TO_BLOCKSTATE_TABLE[i] = 1;
            } else if (i <= 16) {
                TO_BLOCKSTATE_TABLE[i] = 2;
            } else if (i <= 24) {
                TO_BLOCKSTATE_TABLE[i] = 3;
            } else if (i <= 32) {
                TO_BLOCKSTATE_TABLE[i] = 4;
            } else if (i <= 40) {
                TO_BLOCKSTATE_TABLE[i] = 5;
            } else if (i <= 48) {
                TO_BLOCKSTATE_TABLE[i] = 6;
            } else if (i <= 56) {
                TO_BLOCKSTATE_TABLE[i] = 7;
            } else {
                TO_BLOCKSTATE_TABLE[i] = 8;
            }
        }
    }

    /**
     * Converts BlockState amount (0-8) to internal amount (0-63).
     *
     * OPTIMIZED: Uses pre-computed table for perfect accuracy and O(1) performance.
     *
     * @param blockStateAmount Minecraft BlockState fluid amount (0-8)
     * @return Internal medium-precision amount (0-63)
     */
    public static int toInternal(int blockStateAmount) {
        if (blockStateAmount < 0) return 0;
        if (blockStateAmount >= BLOCKSTATE_MAX) return INTERNAL_MAX;
        return TO_INTERNAL_TABLE[blockStateAmount];
    }

    /**
     * Converts internal amount (0-63) to BlockState amount (0-8).
     *
     * OPTIMIZED: Uses pre-computed table for perfect accuracy and O(1) performance.
     *
     * @param internalAmount Internal medium-precision amount (0-63)
     * @return Minecraft BlockState fluid amount (0-8)
     */
    public static int toBlockState(int internalAmount) {
        if (internalAmount < 0) return 0;
        if (internalAmount >= INTERNAL_MAX) return BLOCKSTATE_MAX;
        return TO_BLOCKSTATE_TABLE[internalAmount];
    }

    /**
     * Scales a legacy 0-255 tuned threshold into the active internal precision range.
     */
    public static int scaleLegacyInternal(int legacyAmount) {
        if (legacyAmount <= 0) {
            return 0;
        }
        return Math.max(1, Math.round((legacyAmount / (float) LEGACY_INTERNAL_MAX) * INTERNAL_MAX));
    }

    /**
     * Normalizes a difference measured in the active 0-63 internal amount scale.
     */
    public static float normalizeInternalDifference(float internalDifference) {
        return internalDifference / INTERNAL_MAX;
    }

    /**
     * Calculates the average of two internal amounts.
     * Used for equalization between adjacent fluid blocks.
     *
     * @param amount1 First internal amount (0-63)
     * @param amount2 Second internal amount (0-63)
     * @return Average internal amount
     */
    public static int average(int amount1, int amount2) {
        return (amount1 + amount2) / 2;
    }

    /**
     * Calculates the average of multiple internal amounts.
     *
     * @param amounts Array of internal amounts
     * @return Average internal amount
     */
    public static int average(int... amounts) {
        if (amounts == null || amounts.length == 0) return 0;

        int sum = 0;
        for (int amount : amounts) {
            sum += amount;
        }
        return sum / amounts.length;
    }

    /**
     * Equalizes two fluid amounts, returning the new amounts for each.
     * This is used for natural fluid spreading.
     *
     * @param amount1 First fluid amount
     * @param amount2 Second fluid amount
     * @return Array of [newAmount1, newAmount2]
     */
    public static int[] equalize(int amount1, int amount2) {
        int avg = average(amount1, amount2);
        int remainder = (amount1 + amount2) % 2;

        // Distribute remainder to maintain total volume
        return new int[]{avg + remainder, avg};
    }

    /**
     * Checks if the difference between two amounts is negligible (within 1 internal unit).
     * Used to determine if equalization is needed.
     *
     * @param amount1 First amount
     * @param amount2 Second amount
     * @return true if amounts are essentially equal
     */
    public static boolean isEquilibrium(int amount1, int amount2) {
        return Math.abs(amount1 - amount2) <= 1;
    }

    /**
     * Checks if the difference between two amounts is significant enough to warrant flow.
     *
     * @param amount1 Source amount
     * @param amount2 Destination amount
     * @return true if flow should occur
     */
    public static boolean shouldFlow(int amount1, int amount2) {
        // Flow if difference is > 2 internal units (about 1/16 of a block)
        return amount1 - amount2 > 2;
    }

    /**
     * Calculates fall-off amount based on distance.
     * Higher precision allows for smoother fall-off curves.
     *
     * @param sourceAmount Source fluid amount in the active internal scale
     * @param distance Distance from source (1-based)
     * @param maxDistance Maximum flow distance
     * @return Fluid amount at given distance
     */
    public static int calculateFalloff(int sourceAmount, int distance, int maxDistance) {
        if (distance >= maxDistance) return 0;
        if (distance <= 0) return sourceAmount;

        // Linear falloff: amount * (1 - distance/maxDistance)
        float falloffFactor = 1.0f - ((float) distance / maxDistance);
        int falloffAmount = Math.round(sourceAmount * falloffFactor);

        return Math.max(0, falloffAmount);
    }

    /**
     * Clamps internal amount to valid range (0-63).
     *
     * @param amount Amount to clamp
     * @return Clamped amount
     */
    public static int clamp(int amount) {
        return Math.max(0, Math.min(INTERNAL_MAX, amount));
    }

    /**
     * Gets the maximum internal amount (63).
     */
    public static int getMaxInternal() {
        return INTERNAL_MAX;
    }

    /**
     * Gets the maximum BlockState amount (8).
     */
    public static int getMaxBlockState() {
        return BLOCKSTATE_MAX;
    }
}
