package traben.flowing_fluids;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiphonHydraulicsTest {
    @Test
    void flowGrowsWithTheSquareRootOfHead() {
        double oneBlock = SiphonHydraulics.expectedFlow(1, 4, 0, 0, 1.0);
        double fourBlocks = SiphonHydraulics.expectedFlow(4, 4, 0, 0, 1.0);
        assertEquals(2.0, fourBlocks / oneBlock, 1.0E-9, "Q ~ sqrt(h): four times the head doubles the flow");
    }

    @Test
    void lossesSlowTheSiphonDown() {
        double straight = SiphonHydraulics.expectedFlow(3, 6, 0, 0, 1.0);
        double bent = SiphonHydraulics.expectedFlow(3, 6, 4, 0, 1.0);
        double leaky = SiphonHydraulics.expectedFlow(3, 6, 0, 2, 1.0);
        assertTrue(bent < straight);
        assertTrue(leaky < bent, "open stretches lose pressure faster than bends");
    }

    @Test
    void noHeadMeansNoFlow() {
        assertEquals(0.0, SiphonHydraulics.expectedFlow(0, 5, 0, 0, 2.0));
        assertEquals(0.0, SiphonHydraulics.expectedFlow(-2, 5, 0, 0, 2.0));
        assertEquals(0, SiphonHydraulics.flowLevels(0, 5, 0, 0, 2.0, 4, 0.0));
    }

    @Test
    void stochasticRoundingIsUnbiased() {
        SplittableRandom random = new SplittableRandom(1234L);
        double value = 1.3;
        long total = 0;
        int samples = 200_000;
        for (int i = 0; i < samples; i++) {
            int rounded = SiphonHydraulics.stochasticRound(value, random.nextDouble());
            assertTrue(rounded == 1 || rounded == 2);
            total += rounded;
        }
        assertEquals(value, (double) total / samples, 0.01);
    }

    @Test
    void stochasticRoundingKeepsWholeNumbersExact() {
        assertEquals(2, SiphonHydraulics.stochasticRound(2.0, 0.0));
        assertEquals(2, SiphonHydraulics.stochasticRound(2.0, 0.999));
        assertEquals(0, SiphonHydraulics.stochasticRound(Double.NaN, 0.0));
    }

    @Test
    void transferIsCappedByConfig() {
        assertEquals(2, SiphonHydraulics.flowLevels(32, 1, 0, 0, 10.0, 2, 0.5));
    }
}
