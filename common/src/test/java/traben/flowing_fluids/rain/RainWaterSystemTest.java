package traben.flowing_fluids.rain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RainWaterSystemTest {

    @Test
    void wetnessDecaysToZeroAfterPersistenceWindow() {
        assertEquals(0.5f, RainMath.decayWetness(1.0f, 600, 1200), 0.0001f);
        assertEquals(0.0f, RainMath.decayWetness(1.0f, 1200, 1200), 0.0001f);
    }

    @Test
    void surfaceWaterRespectsAbsorptionAndWetness() {
        assertEquals(0, RainMath.computeSurfaceWaterAmount(2, 0.75f, 0.0f));
        assertEquals(2, RainMath.computeSurfaceWaterAmount(2, 0.05f, 0.0f));
        assertEquals(2, RainMath.computeSurfaceWaterAmount(2, 0.75f, 1.0f));
    }

    @Test
    void rainIntensityIsDeterministic() {
        RainIntensityStage stageA = RainMath.chooseRainIntensityStage(false, 10L, 4, -8, 12345L);
        RainIntensityStage stageB = RainMath.chooseRainIntensityStage(false, 10L, 4, -8, 12345L);
        RainIntensityStage stageC = RainMath.chooseRainIntensityStage(true, 10L, 4, -8, 12345L);

        assertEquals(stageA, stageB);
        assertEquals(RainIntensityStage.THUNDERSTORM, stageC);
        assertTrue(stageA == RainIntensityStage.DRIZZLE
                || stageA == RainIntensityStage.STEADY
                || stageA == RainIntensityStage.HEAVY);
    }
}
