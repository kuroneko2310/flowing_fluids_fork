package traben.flowing_fluids.water;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiverFloodMathTest {
    @Test
    void thunderstormDrivesTheStageToFloodingWithinMinutes() {
        double stage = 0.0;
        double target = RiverFloodMath.target(0.45, true, true, false, 1.0, 1.0);
        assertEquals(1.0, target, 1.0E-9);
        for (int second = 0; second < 300; second++) {
            stage = RiverFloodMath.step(stage, 20, target, 8.0);
        }
        assertEquals(RiverFloodMath.MAX_LEVEL, RiverFloodMath.warningLevel(stage));
    }

    @Test
    void ordinaryRainStopsAtTheEvacuationLevel() {
        double target = RiverFloodMath.target(0.45, true, false, false, 1.0, 1.0);
        double stage = RiverFloodMath.step(0.0, 24000L * 10, target, 8.0);
        assertEquals(0.45, stage, 1.0E-6);
        assertEquals(3, RiverFloodMath.warningLevel(stage));
    }

    @Test
    void stepDoesNotDependOnUpdateFrequency() {
        double coarse = RiverFloodMath.step(0.2, 2000, 0.9, 8.0);
        double fine = 0.2;
        for (int i = 0; i < 100; i++) {
            fine = RiverFloodMath.step(fine, 20, 0.9, 8.0);
        }
        assertEquals(coarse, fine, 1.0E-9);
    }

    @Test
    void recessionFallsBackToNormalWithinTheRecessionTime() {
        double rate = RiverFloodMath.recessionRatePerDay(3600);
        double stage = RiverFloodMath.step(1.0, 3600, 0.0, rate);
        assertTrue(stage < 0.03);
        assertEquals(0, RiverFloodMath.warningLevel(stage));
    }

    @Test
    void drySoilAndNoRainKeepRiversLow() {
        assertEquals(0.0, RiverFloodMath.target(0.45, false, false, false, 1.0, 1.0), 1.0E-9);
        assertTrue(RiverFloodMath.target(0.45, true, false, false, 0.4, 1.0) < 0.2);
        assertEquals(2, RiverFloodMath.warningLevel(RiverFloodMath.target(0.45, false, false, true, 1.0, 1.0)));
    }

    @Test
    void warningLevelsAreMonotonic() {
        int previous = 0;
        for (int i = 0; i <= 100; i++) {
            int level = RiverFloodMath.warningLevel(i / 100.0);
            assertTrue(level >= previous);
            previous = level;
        }
        assertEquals(0, RiverFloodMath.warningLevel(0.0));
        assertEquals(RiverFloodMath.MAX_LEVEL, RiverFloodMath.warningLevel(1.0));
        assertEquals(4, RiverFloodMath.warningLevel(RiverFloodMath.levelThreshold(4)));
    }

    @Test
    void inflowFillsOnlyTheDeficit() {
        assertEquals(0, RiverFloodMath.inflowAmount(2.0, 2.0, 8));
        assertEquals(0, RiverFloodMath.inflowAmount(2.0, 1.95, 8));
        assertEquals(4, RiverFloodMath.inflowAmount(2.0, 1.5, 8));
        assertEquals(8, RiverFloodMath.inflowAmount(4.0, 0.0, 8));
        assertEquals(3, RiverFloodMath.inflowAmount(4.0, 0.0, 3));
    }
}
