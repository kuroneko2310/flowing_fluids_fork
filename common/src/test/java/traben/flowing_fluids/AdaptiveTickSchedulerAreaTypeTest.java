package traben.flowing_fluids;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdaptiveTickSchedulerAreaTypeTest {

    @Test
    void oceanLikeBiomeUsesLowerThreshold() {
        assertEquals(AdaptiveTickScheduler.AreaType.OCEAN,
                AdaptiveTickScheduler.classifyAreaType(true, false, 96));
        assertEquals(AdaptiveTickScheduler.AreaType.HIGH_ACTIVITY,
                AdaptiveTickScheduler.classifyAreaType(true, false, 48));
    }

    @Test
    void riverLikeBiomePrefersHighActivityInsteadOfOcean() {
        assertEquals(AdaptiveTickScheduler.AreaType.HIGH_ACTIVITY,
                AdaptiveTickScheduler.classifyAreaType(false, true, 64));
        assertEquals(AdaptiveTickScheduler.AreaType.NORMAL,
                AdaptiveTickScheduler.classifyAreaType(false, true, 16));
    }

    @Test
    void defaultThresholdsStillWorkAwayFromWaterBiomes() {
        assertEquals(AdaptiveTickScheduler.AreaType.NORMAL,
                AdaptiveTickScheduler.classifyAreaType(false, false, 80));
        assertEquals(AdaptiveTickScheduler.AreaType.HIGH_ACTIVITY,
                AdaptiveTickScheduler.classifyAreaType(false, false, 140));
        assertEquals(AdaptiveTickScheduler.AreaType.OCEAN,
                AdaptiveTickScheduler.classifyAreaType(false, false, 1200));
    }
}
