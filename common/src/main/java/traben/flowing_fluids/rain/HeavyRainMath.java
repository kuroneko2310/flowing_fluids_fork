package traben.flowing_fluids.rain;

/**
 * Pure math for moving heavy-rain cells, free of world access so it can be unit tested.
 *
 * <p>A cell's rain multiplier is {@code 1 + (peak - 1) * lifecycle(t) * falloff(d)}:
 * <ul>
 *     <li>{@link #lifecycle} is a smoothstep build-up, a mature plateau and a smoothstep decay, like a convective cell
 *     that grows, rains hardest, then rains itself out. It is C1-continuous, so rain never jumps between ticks.</li>
 *     <li>{@link #falloff} is a Gaussian core that is cut to zero at the cell radius. The cut is blended so the value
 *     reaches exactly 0 at the edge instead of stepping down, which avoids a visible ring of extra water.</li>
 * </ul>
 * Wind is an Ornstein-Uhlenbeck random walk on the heading: it wanders, but keeps being pulled back towards a
 * prevailing direction, so storms drift in believable, slowly changing directions instead of jittering or spinning.</p>
 */
public final class HeavyRainMath {
    static final double BUILD_UP_FRACTION = 0.2;
    static final double DECAY_FRACTION = 0.3;
    /** Gaussian sigma as a fraction of the cell radius. */
    static final double SIGMA_FRACTION = 0.45;

    private HeavyRainMath() {
    }

    static double smoothstep(double edge0, double edge1, double x) {
        if (edge1 <= edge0) {
            return x < edge0 ? 0.0 : 1.0;
        }
        double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * @param age      ticks since the cell formed
     * @param lifetime total lifetime in ticks
     * @return 0..1 intensity envelope
     */
    public static double lifecycle(long age, long lifetime) {
        if (lifetime <= 0 || age < 0 || age >= lifetime) {
            return 0.0;
        }
        double t = (double) age / lifetime;
        double rise = smoothstep(0.0, BUILD_UP_FRACTION, t);
        double fall = 1.0 - smoothstep(1.0 - DECAY_FRACTION, 1.0, t);
        return rise * fall;
    }

    /**
     * @param distanceSq squared horizontal distance from the cell centre
     * @param radius     cell radius in blocks
     * @return 0..1 spatial weight, exactly 0 at and beyond the radius
     */
    public static double falloff(double distanceSq, double radius) {
        if (radius <= 0.0 || distanceSq >= radius * radius) {
            return 0.0;
        }
        double sigma = radius * SIGMA_FRACTION;
        double gaussian = Math.exp(-distanceSq / (2.0 * sigma * sigma));
        double edge = Math.exp(-(radius * radius) / (2.0 * sigma * sigma));
        return Math.max(0.0, (gaussian - edge) / (1.0 - edge));
    }

    public static double rainMultiplier(double peak, double lifecycle, double falloff) {
        return 1.0 + Math.max(0.0, peak - 1.0) * Math.max(0.0, lifecycle) * Math.max(0.0, falloff);
    }

    /**
     * Combines several overlapping cells. Rain rates add, so the excess over 1 is summed, capped at {@code cap}.
     */
    public static double combine(double currentMultiplier, double cellMultiplier, double cap) {
        return Math.min(cap, currentMultiplier + Math.max(0.0, cellMultiplier - 1.0));
    }

    /**
     * One Ornstein-Uhlenbeck step on an angle.
     *
     * @param heading    current heading in radians
     * @param prevailing heading the wind is pulled towards
     * @param reversion  pull strength per step (0..1)
     * @param volatility standard deviation of the random kick per step, in radians
     * @param gaussian   a standard normal sample
     */
    public static double windStep(double heading, double prevailing, double reversion, double volatility, double gaussian) {
        double error = wrapAngle(prevailing - heading);
        return wrapAngle(heading + reversion * error + volatility * gaussian);
    }

    public static double wrapAngle(double angle) {
        double wrapped = angle % (Math.PI * 2.0);
        if (wrapped > Math.PI) {
            wrapped -= Math.PI * 2.0;
        } else if (wrapped <= -Math.PI) {
            wrapped += Math.PI * 2.0;
        }
        return wrapped;
    }

    /**
     * Prevailing heading derived from the world seed, so each world has its own steady wind climate.
     */
    public static double prevailingHeading(long seed) {
        long mixed = seed * 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        double unit = (mixed >>> 11) * 0x1.0p-53;
        return wrapAngle(unit * Math.PI * 2.0);
    }
}
