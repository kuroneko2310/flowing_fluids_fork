package traben.flowing_fluids;

/**
 * Pure siphon flow model, kept free of world access so it can be unit tested directly.
 *
 * <p>A primed siphon discharges like an orifice fed by the head difference between the source surface and the outlet:
 * Torricelli gives {@code v = sqrt(2 g h)}, and pipe losses (length, bends, open stretches that leak pressure) act as a
 * resistance {@code K}, so {@code Q ~ sqrt(h / (1 + K))}. The old model was linear in the drop, which made tall siphons
 * far too strong and one-block drops too weak compared to each other.</p>
 *
 * <p>Minecraft water moves in whole levels, so the expected flow is converted to an integer with stochastic rounding:
 * {@code floor(q)} plus one more level with probability {@code frac(q)}. Its expectation is exactly {@code q}, so over
 * many ticks the delivered volume matches the model without keeping per-siphon remainder state that could leak when
 * chunks unload.</p>
 */
public final class SiphonHydraulics {
    static final double LENGTH_LOSS = 0.25;
    static final double BEND_LOSS = 0.5;
    static final double OPEN_SURFACE_LOSS = 1.5;
    static final double NATURAL_GAIN = 2.0;
    static final double HYDRAULIC_GAIN = 3.0;

    private SiphonHydraulics() {
    }

    /**
     * Expected levels moved per siphon event.
     *
     * @param head         source surface height above the outlet in blocks (hydraulic outlets at the same height pass 1)
     * @param pathLength   number of cells in the siphon path
     * @param bends        direction changes along the path
     * @param openSurfaces path cells open to the air
     * @param gain         discharge coefficient
     */
    public static double expectedFlow(int head, int pathLength, int bends, int openSurfaces, double gain) {
        if (head <= 0 || gain <= 0.0) {
            return 0.0;
        }
        double resistance = 1.0
                + LENGTH_LOSS * Math.max(0, pathLength)
                + BEND_LOSS * Math.max(0, bends)
                + OPEN_SURFACE_LOSS * Math.max(0, openSurfaces);
        return gain * Math.sqrt(head / resistance);
    }

    /**
     * @param uniform a uniform random sample in [0, 1)
     */
    public static int stochasticRound(double value, double uniform) {
        if (!(value > 0.0)) {
            return 0;
        }
        int whole = (int) Math.floor(value);
        return uniform < value - whole ? whole + 1 : whole;
    }

    public static int flowLevels(int head, int pathLength, int bends, int openSurfaces, double gain,
                                 int maxTransfer, double uniform) {
        int rounded = stochasticRound(expectedFlow(head, pathLength, bends, openSurfaces, gain), uniform);
        return Math.max(0, Math.min(Math.max(1, maxTransfer), rounded));
    }
}
