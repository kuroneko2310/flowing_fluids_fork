package traben.flowing_fluids.water;

/**
 * River stage ("河川水位") model, free of world access so it can be unit tested.
 *
 * <p>The stage {@code s} in [0, 1] is the share of {@code riverFloodMaxStage} blocks that rivers stand above sea level.
 * It relaxes exponentially towards a weather-dependent target: {@code ds/dt = r (target - s)}. Rain holds the target
 * part way up, a thunderstorm pushes it to the top (flooding), and dry weather lets it fall back to 0. The equation is
 * integrated in closed form, so the result does not depend on how often it is updated.</p>
 *
 * <p>The stage is mapped onto the five Japanese river warning levels (警戒レベル1-5).</p>
 */
public final class RiverFloodMath {
    static final double TICKS_PER_DAY = 24000.0;

    /** Stage share (of the maximum stage) at which each warning level starts; index = level - 1. */
    static final double[] LEVEL_THRESHOLDS = {0.08, 0.25, 0.42, 0.58, 0.75};

    public static final int MAX_LEVEL = LEVEL_THRESHOLDS.length;

    private RiverFloodMath() {
    }

    /**
     * @param stage        current stage share
     * @param elapsedTicks ticks since the last update
     * @param target       stage share the weather is driving towards
     * @param ratePerDay   relaxation rate r, per in-game day
     */
    public static double step(double stage, long elapsedTicks, double target, double ratePerDay) {
        double current = clamp01(stage);
        if (elapsedTicks <= 0) {
            return current;
        }
        double goal = clamp01(target);
        double decay = Math.exp(-Math.max(0.0, ratePerDay) * (elapsedTicks / TICKS_PER_DAY));
        return clamp01(goal + (current - goal) * decay);
    }

    /**
     * Weather target of the stage share.
     *
     * @param rainTarget    target while it rains
     * @param raining       it rains
     * @param thundering    it thunders (floods: the target is the maximum stage)
     * @param freshet       spring snowmelt freshet
     * @param soakFactor    share of rain reaching the rivers (dry soil soaks some up), in [0, 1]
     * @param downpourBoost season / heavy rain multiplier (>= 0)
     */
    public static double target(double rainTarget, boolean raining, boolean thundering, boolean freshet,
                                double soakFactor, double downpourBoost) {
        double target = 0.0;
        if (raining) {
            target = thundering ? 1.0 : clamp01(rainTarget);
            target *= clamp01(soakFactor) * Math.max(0.0, downpourBoost);
        }
        if (freshet) {
            // Snowmelt alone swells rivers to the advisory level.
            target = Math.max(target, LEVEL_THRESHOLDS[1] + 0.05);
        }
        return clamp01(target);
    }

    /**
     * Warning level 0 (normal) to {@link #MAX_LEVEL} (flooding) for a stage share.
     */
    public static int warningLevel(double stage) {
        int level = 0;
        for (int i = 0; i < LEVEL_THRESHOLDS.length; i++) {
            if (stage + 1.0E-9 >= LEVEL_THRESHOLDS[i]) {
                level = i + 1;
            }
        }
        return level;
    }

    /**
     * Stage share at which a warning level starts (0 for level 0).
     */
    public static double levelThreshold(int level) {
        if (level <= 0) {
            return 0.0;
        }
        return LEVEL_THRESHOLDS[Math.min(level, MAX_LEVEL) - 1];
    }

    /**
     * Relaxation rate that brings a receding stage down to about 2% within {@code recessionTicks}.
     */
    public static double recessionRatePerDay(int recessionTicks) {
        if (recessionTicks <= 0) {
            return 1.0E6;
        }
        return 4.0 * TICKS_PER_DAY / recessionTicks;
    }

    /**
     * Water levels (1/8 blocks) to add to bring a river surface at {@code surfaceExcess} blocks above sea level up to
     * {@code stageBlocks}, capped at {@code maxPerPlacement}. 0 when the river is already high enough.
     */
    public static int inflowAmount(double stageBlocks, double surfaceExcess, int maxPerPlacement) {
        double deficit = stageBlocks - surfaceExcess;
        if (deficit < 0.125) {
            return 0;
        }
        int levels = (int) Math.ceil(deficit * 8.0 - 1.0E-9);
        return Math.max(1, Math.min(Math.max(1, Math.min(8, maxPerPlacement)), levels));
    }

    static double clamp01(double value) {
        if (!(value > 0.0)) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }
}
