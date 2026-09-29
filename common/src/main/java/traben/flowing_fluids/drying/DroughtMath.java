package traben.flowing_fluids.drying;

/**
 * Drought index model, free of world access so it can be unit tested.
 *
 * <p>The index {@code D} in [0, 1] is a soil-moisture deficit, loosely after the "days since meaningful rain" family
 * of drought indices:
 * <ul>
 *     <li>Dry weather: {@code dD/dt = r (1 - D)}. Deficit builds quickly at first and saturates towards 1, so a long
 *     dry spell keeps getting worse but never overshoots.</li>
 *     <li>Rain: {@code dD/dt = -k D}. Recovery is exponential, so a storm breaks a drought within a few in-game hours
 *     while a shower only takes the edge off.</li>
 * </ul>
 * Both equations are integrated in closed form ({@code exp}), so the result is identical whether the index is updated
 * every tick or once every few seconds, and it can never leave [0, 1].</p>
 */
public final class DroughtMath {
    static final double TICKS_PER_DAY = 24000.0;

    private DroughtMath() {
    }

    /**
     * @param index           current drought index
     * @param elapsedTicks    ticks since the last update
     * @param dryRatePerDay   accumulation rate r while it is dry (already scaled for dry season / heatwave)
     * @param rainRatePerDay  recovery rate k while it rains (already scaled for thunderstorms)
     * @param raining         whether it rained during the interval
     */
    public static double step(double index, long elapsedTicks, double dryRatePerDay, double rainRatePerDay, boolean raining) {
        double current = clamp01(index);
        if (elapsedTicks <= 0) {
            return current;
        }
        double days = elapsedTicks / TICKS_PER_DAY;
        if (raining) {
            return clamp01(current * Math.exp(-Math.max(0.0, rainRatePerDay) * days));
        }
        return clamp01(1.0 - (1.0 - current) * Math.exp(-Math.max(0.0, dryRatePerDay) * days));
    }

    /**
     * Ambient evaporation multiplier from drought: {@code 1 + boost * D^1.5}. The exponent keeps a mild drought mild and
     * lets the effect bite as the deficit becomes severe.
     */
    public static double evaporationMultiplier(double index, double boost) {
        return 1.0 + Math.max(0.0, boost) * Math.pow(clamp01(index), 1.5);
    }

    /**
     * Thin-water evaporation level cap, raised gradually from {@code baseLevel} towards {@code severeLevel} once the
     * drought passes moderate severity.
     */
    public static int evaporationMaxLevel(int baseLevel, int severeLevel, double index) {
        int base = Math.max(1, Math.min(8, baseLevel));
        int severe = Math.max(base, Math.min(8, severeLevel));
        return base + (int) Math.floor((severe - base) * smoothstep(0.4, 1.0, index) + 1.0E-9);
    }

    /**
     * Chance that an exposed pond surface loses one level, only in serious droughts.
     */
    public static double pondDrawdownChance(double baseChance, double index) {
        return Math.max(0.0, baseChance) * smoothstep(0.5, 1.0, index);
    }

    /**
     * Dry ground soaks up rain before it can pool: rain refill is scaled by {@code 1 - 0.6 D}.
     */
    public static double rainRefillMultiplier(double index) {
        return 1.0 - 0.6 * clamp01(index);
    }

    static double smoothstep(double edge0, double edge1, double x) {
        double t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3.0 - 2.0 * t);
    }

    static double clamp01(double value) {
        if (!(value > 0.0)) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }
}
