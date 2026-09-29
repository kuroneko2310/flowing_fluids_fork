package traben.flowing_fluids.drying;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DroughtMathTest {
    @Test
    void dryWeatherBuildsTowardsOneWithoutOvershooting() {
        double index = 0.0;
        double previous = index;
        for (int day = 0; day < 200; day++) {
            index = DroughtMath.step(index, 24000, 0.1, 6.0, false);
            assertTrue(index >= previous && index <= 1.0);
            previous = index;
        }
        assertTrue(index > 0.99);
    }

    @Test
    void integrationDoesNotDependOnUpdateInterval() {
        double coarse = DroughtMath.step(0.2, 24000, 0.3, 6.0, false);
        double fine = 0.2;
        for (int i = 0; i < 240; i++) {
            fine = DroughtMath.step(fine, 100, 0.3, 6.0, false);
        }
        assertEquals(coarse, fine, 1.0E-9);
    }

    @Test
    void rainBreaksADroughtExponentially() {
        double afterStorm = DroughtMath.step(0.9, 4000, 0.1, 6.0, true);
        assertEquals(0.9 * Math.exp(-1.0), afterStorm, 1.0E-9);
        assertEquals(0.9, DroughtMath.step(0.9, 0, 0.1, 6.0, true), 1.0E-12);
    }

    @Test
    void effectsScaleSmoothlyWithSeverity() {
        assertEquals(1.0, DroughtMath.evaporationMultiplier(0.0, 3.0), 1.0E-9);
        assertEquals(4.0, DroughtMath.evaporationMultiplier(1.0, 3.0), 1.0E-9);
        assertEquals(1, DroughtMath.evaporationMaxLevel(1, 4, 0.3));
        assertEquals(4, DroughtMath.evaporationMaxLevel(1, 4, 1.0));
        assertEquals(0.0, DroughtMath.pondDrawdownChance(0.02, 0.5), 1.0E-12);
        assertEquals(0.02, DroughtMath.pondDrawdownChance(0.02, 1.0), 1.0E-12);
        assertEquals(1.0, DroughtMath.rainRefillMultiplier(0.0), 1.0E-9);
        assertEquals(0.4, DroughtMath.rainRefillMultiplier(1.0), 1.0E-9);
    }

    @Test
    void invalidInputsStayInRange() {
        double fromNaN = DroughtMath.step(Double.NaN, 100, 0.1, 6.0, false);
        assertTrue(fromNaN >= 0.0 && fromNaN <= 1.0);
        assertTrue(DroughtMath.step(-3.0, 100, 0.1, 6.0, false) >= 0.0);
        assertTrue(DroughtMath.step(7.0, 100, 0.1, 6.0, true) <= 1.0);
    }

    @Test
    void evaporationSurplusDriesOneLevelDeeperPerDoubling() {
        assertEquals(0, DroughtMath.surplusEvaporationLevels(0.5));
        assertEquals(0, DroughtMath.surplusEvaporationLevels(1.0));
        assertEquals(0, DroughtMath.surplusEvaporationLevels(1.9));
        assertEquals(1, DroughtMath.surplusEvaporationLevels(2.0));
        assertEquals(2, DroughtMath.surplusEvaporationLevels(4.5));
        assertEquals(7, DroughtMath.surplusEvaporationLevels(1.0E9));
    }
}
