package traben.flowing_fluids.water;

/**
 * Pure erosion model, free of world access so it can be unit tested.
 *
 * <p>Stream power per unit bed area scales with flow speed times depth ({@code omega ~ rho g Q S}); here the speed is the
 * horizontal flow vector length, boosted by recent flow momentum and by flood conditions, and the depth is the water
 * level of the cell. A bed or bank block only erodes when the power exceeds the material's critical threshold, in the
 * spirit of the Shields criterion: loose sand goes first, gravel and clay resist. Above the threshold the chance grows
 * with the excess, so a torrent reworks a channel quickly while a gentle stream barely touches it. Below the settling
 * threshold, water is calm enough for carried sediment to drop out.</p>
 */
public final class ErosionMath {
    public static final int SILT = 0;
    public static final int SAND = 1;
    public static final int GRAVEL = 2;
    public static final int CLAY = 3;
    public static final int SEDIMENT_TYPES = 4;

    static final double SETTLING_POWER = 0.15;
    static final double FLOOD_POWER_MULTIPLIER = 2.0;

    private ErosionMath() {
    }

    /**
     * @param horizontalFlow length of the horizontal flow vector (0..~1)
     * @param momentum       recent flow momentum (0..1)
     * @param amount         water level of the cell (1..8)
     * @param flooding       whether the water is a river in spate
     */
    public static double streamPower(double horizontalFlow, double momentum, int amount, boolean flooding) {
        if (!(horizontalFlow > 0.0) || amount <= 0) {
            return 0.0;
        }
        double speed = Math.min(1.0, horizontalFlow) * (0.5 + Math.max(0.0, Math.min(1.0, momentum)));
        double depth = Math.min(8, amount) / 8.0;
        return speed * depth * (flooding ? FLOOD_POWER_MULTIPLIER : 1.0);
    }

    /**
     * Critical stream power needed to move each sediment class.
     */
    public static double criticalPower(int sedimentType) {
        return switch (sedimentType) {
            case SAND -> 0.35;
            case SILT -> 0.5;
            case GRAVEL -> 0.75;
            case CLAY -> 0.95;
            default -> Double.POSITIVE_INFINITY;
        };
    }

    /**
     * Erosion probability per random tick: zero below the critical power, then proportional to the excess.
     */
    public static double erosionChance(double power, double critical, double baseChance) {
        if (!(power > critical) || baseChance <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, baseChance * (power - critical) / Math.max(1.0E-6, critical));
    }

    public static boolean canSettle(double power) {
        return power < SETTLING_POWER;
    }
}
