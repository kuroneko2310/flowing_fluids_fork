package traben.flowing_fluids;

/**
 * Converts between Minecraft's 0-8 fluid amount (BlockState) and
 * internal 0-255 high-precision amount.
 *
 * Benefits of 0-255 internal precision:
 * - Eliminates unnatural water surface stepping
 * - Smoother equalization between blocks
 * - More accurate fall-off calculations
 * - Natural-looking rivers and lakes
 *
 * Conversion:
 * - BlockState 0 = Internal 0 (no fluid)
 * - BlockState 1 = Internal 32 (1/8 full)
 * - BlockState 2 = Internal 64 (2/8 full)
 * - BlockState 3 = Internal 96 (3/8 full)
 * - BlockState 4 = Internal 128 (4/8 full)
 * - BlockState 5 = Internal 160 (5/8 full)
 * - BlockState 6 = Internal 192 (6/8 full)
 * - BlockState 7 = Internal 224 (7/8 full)
 * - BlockState 8 = Internal 255 (8/8 full, source)
 */
public class FluidAmountConverter {

    private static final int INTERNAL_MAX = 255;
    private static final int BLOCKSTATE_MAX = 8;

    // Pre-computed conversion tables for accuracy and performance
    private static final int[] TO_INTERNAL_TABLE = {
        0,    // 0 -> 0
        32,   // 1 -> 32
        64,   // 2 -> 64
        96,   // 3 -> 96
        128,  // 4 -> 128
        160,  // 5 -> 160
        192,  // 6 -> 192
        224,  // 7 -> 224
        255   // 8 -> 255
    };

    private static final int[] TO_BLOCKSTATE_TABLE = new int[256];

    // Initialize reverse lookup table
    static {
        for (int i = 0; i < 256; i++) {
            // Map internal amount to nearest BlockState amount
            if (i == 0) {
                TO_BLOCKSTATE_TABLE[i] = 0;
            } else if (i <= 32) {
                TO_BLOCKSTATE_TABLE[i] = 1;
            } else if (i <= 64) {
                TO_BLOCKSTATE_TABLE[i] = 2;
            } else if (i <= 96) {
                TO_BLOCKSTATE_TABLE[i] = 3;
            } else if (i <= 128) {
                TO_BLOCKSTATE_TABLE[i] = 4;
            } else if (i <= 160) {
                TO_BLOCKSTATE_TABLE[i] = 5;
            } else if (i <= 192) {
                TO_BLOCKSTATE_TABLE[i] = 6;
            } else if (i <= 224) {
                TO_BLOCKSTATE_TABLE[i] = 7;
            } else {
                TO_BLOCKSTATE_TABLE[i] = 8;
            }
        }
    }

    /**
     * Converts BlockState amount (0-8) to internal amount (0-255).
     *
     * OPTIMIZED: Uses pre-computed table for perfect accuracy and O(1) performance.
     *
     * @param blockStateAmount Minecraft BlockState fluid amount (0-8)
     * @return Internal high-precision amount (0-255)
     */
    public static int toInternal(int blockStateAmount) {
        if (blockStateAmount < 0) return 0;
        if (blockStateAmount >= BLOCKSTATE_MAX) return INTERNAL_MAX;
        return TO_INTERNAL_TABLE[blockStateAmount];
    }

    /**
     * Converts internal amount (0-255) to BlockState amount (0-8).
     *
     * OPTIMIZED: Uses pre-computed table for perfect accuracy and O(1) performance.
     *
     * @param internalAmount Internal high-precision amount (0-255)
     * @return Minecraft BlockState fluid amount (0-8)
     */
    public static int toBlockState(int internalAmount) {
        if (internalAmount < 0) return 0;
        if (internalAmount >= INTERNAL_MAX) return BLOCKSTATE_MAX;
        return TO_BLOCKSTATE_TABLE[internalAmount];
    }

    /**
     * Calculates the average of two internal amounts.
     * Used for equalization between adjacent fluid blocks.
     *
     * @param amount1 First internal amount (0-255)
     * @param amount2 Second internal amount (0-255)
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
     * @param sourceAmount Source fluid amount (0-255)
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
     * Clamps internal amount to valid range (0-255).
     *
     * @param amount Amount to clamp
     * @return Clamped amount
     */
    public static int clamp(int amount) {
        return Math.max(0, Math.min(INTERNAL_MAX, amount));
    }

    /**
     * Gets the maximum internal amount (255).
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
