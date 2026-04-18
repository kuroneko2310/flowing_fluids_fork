package traben.flowing_fluids.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfiniteBiomeRefillFallbackControllerTest {

    @Test
    void overloadScoreUsesDedicatedHysteresis() {
        assertEquals(1, InfiniteBiomeRefillFallbackController.ff$getNextOverloadScore(0, 0.96f));
        assertEquals(2, InfiniteBiomeRefillFallbackController.ff$getNextOverloadScore(1, 0.97f));
        assertEquals(2, InfiniteBiomeRefillFallbackController.ff$getNextOverloadScore(2, 1.01f));
        assertEquals(1, InfiniteBiomeRefillFallbackController.ff$getNextOverloadScore(2, 0.80f));
        assertEquals(0, InfiniteBiomeRefillFallbackController.ff$getNextOverloadScore(1, 0.80f));
    }

    @Test
    void immediateActivationNeedsStrongerSpike() {
        assertTrue(InfiniteBiomeRefillFallbackController.ff$shouldActivateImmediately(1.10f));
        assertFalse(InfiniteBiomeRefillFallbackController.ff$shouldActivateImmediately(1.02f));
    }
}
