package traben.flowing_fluids.water;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiverFloodStageTest {
    @Test
    void capReturnsGraduallyAfterRain() {
        assertEquals(0.0f, RiverFloodStage.recessionFactor(0, 3600), 1.0E-6f);
        assertEquals(0.5f, RiverFloodStage.recessionFactor(1800, 3600), 1.0E-6f);
        assertEquals(1.0f, RiverFloodStage.recessionFactor(3600, 3600), 1.0E-6f);
        float previous = 0.0f;
        for (long t = 0; t <= 3600; t += 20) {
            float factor = RiverFloodStage.recessionFactor(t, 3600);
            assertTrue(factor >= previous);
            previous = factor;
        }
    }

    @Test
    void zeroRecessionRestoresTheCapImmediately() {
        assertEquals(1.0f, RiverFloodStage.recessionFactor(0, 0), 1.0E-6f);
    }
}
