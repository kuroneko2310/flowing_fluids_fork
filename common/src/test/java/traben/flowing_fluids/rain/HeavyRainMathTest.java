package traben.flowing_fluids.rain;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeavyRainMathTest {
    @Test
    void lifecycleBuildsPeaksAndDecaysToZero() {
        long lifetime = 1000;
        assertEquals(0.0, HeavyRainMath.lifecycle(0, lifetime), 1.0E-9);
        assertEquals(1.0, HeavyRainMath.lifecycle(500, lifetime), 1.0E-9, "mature plateau");
        assertTrue(HeavyRainMath.lifecycle(100, lifetime) > 0.0 && HeavyRainMath.lifecycle(100, lifetime) < 1.0);
        assertTrue(HeavyRainMath.lifecycle(900, lifetime) > 0.0 && HeavyRainMath.lifecycle(900, lifetime) < 1.0);
        assertEquals(0.0, HeavyRainMath.lifecycle(1000, lifetime), 1.0E-9);
        assertEquals(0.0, HeavyRainMath.lifecycle(-5, lifetime), 1.0E-9);
    }

    @Test
    void lifecycleNeverJumpsBetweenTicks() {
        long lifetime = 6000;
        double previous = HeavyRainMath.lifecycle(0, lifetime);
        for (long age = 1; age <= lifetime; age++) {
            double current = HeavyRainMath.lifecycle(age, lifetime);
            assertTrue(Math.abs(current - previous) < 0.002, "step at age " + age);
            previous = current;
        }
    }

    @Test
    void falloffIsOneAtTheCoreAndExactlyZeroAtTheEdge() {
        assertEquals(1.0, HeavyRainMath.falloff(0.0, 50.0), 1.0E-9);
        assertEquals(0.0, HeavyRainMath.falloff(50.0 * 50.0, 50.0), 1.0E-9);
        assertEquals(0.0, HeavyRainMath.falloff(80.0 * 80.0, 50.0), 1.0E-9);
        double near = HeavyRainMath.falloff(10.0 * 10.0, 50.0);
        double far = HeavyRainMath.falloff(40.0 * 40.0, 50.0);
        assertTrue(near > far && far > 0.0);
        assertTrue(HeavyRainMath.falloff(49.9 * 49.9, 50.0) < 0.01, "the edge fades in smoothly");
    }

    @Test
    void multiplierIsOneOutsideTheCellAndPeakInTheMatureCore() {
        assertEquals(1.0, HeavyRainMath.rainMultiplier(3.0, 1.0, 0.0), 1.0E-9);
        assertEquals(3.0, HeavyRainMath.rainMultiplier(3.0, 1.0, 1.0), 1.0E-9);
        assertEquals(2.0, HeavyRainMath.rainMultiplier(3.0, 0.5, 1.0), 1.0E-9);
    }

    @Test
    void overlappingCellsAddButAreCapped() {
        assertEquals(4.0, HeavyRainMath.combine(2.5, 2.5, 8.0), 1.0E-9);
        assertEquals(8.0, HeavyRainMath.combine(7.0, 4.0, 8.0), 1.0E-9);
    }

    @Test
    void windWandersButStaysAroundThePrevailingDirection() {
        double prevailing = 1.0;
        double heading = prevailing;
        SplittableRandom random = new SplittableRandom(99L);
        double sumError = 0.0;
        int steps = 20_000;
        for (int i = 0; i < steps; i++) {
            double gaussian = gaussian(random);
            heading = HeavyRainMath.windStep(heading, prevailing, 0.08, 0.18, gaussian);
            assertTrue(heading > -Math.PI - 1.0E-9 && heading <= Math.PI + 1.0E-9);
            sumError += Math.abs(HeavyRainMath.wrapAngle(heading - prevailing));
        }
        // OU stationary sd = vol / sqrt(2k - k^2) ~ 0.46 rad, so the mean absolute error stays well below 1 rad.
        assertTrue(sumError / steps < 0.6, "mean reversion keeps the wind near its prevailing heading");
    }

    @Test
    void prevailingHeadingIsStablePerSeed() {
        assertEquals(HeavyRainMath.prevailingHeading(42L), HeavyRainMath.prevailingHeading(42L));
        assertTrue(HeavyRainMath.prevailingHeading(42L) != HeavyRainMath.prevailingHeading(43L));
    }

    private static double gaussian(SplittableRandom random) {
        double u1 = Math.max(1.0E-12, random.nextDouble());
        double u2 = random.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }
}
