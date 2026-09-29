package traben.flowing_fluids.season;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonClimateTest {
    @Test
    void everyTableCoversTwelveSubSeasons() {
        assertEquals(12, SeasonClimate.DROUGHT.length);
        assertEquals(12, SeasonClimate.EVAPORATION.length);
        assertEquals(12, SeasonClimate.HEAVY_RAIN.length);
        assertEquals(12, SeasonClimate.SNOWMELT.length);
        assertEquals(12, SeasonClimate.FRESHET.length);
    }

    @Test
    void winterFreezesDryingAndSpringMeltsSnow() {
        int midWinter = 10;
        int midSummer = 4;
        int earlySpring = 0;
        assertTrue(SeasonClimate.EVAPORATION[midWinter] < 0.5);
        assertTrue(SeasonClimate.DROUGHT[midWinter] < SeasonClimate.DROUGHT[midSummer]);
        assertTrue(SeasonClimate.SNOWMELT[earlySpring] > 2.0);
        assertTrue(SeasonClimate.SNOWMELT[midWinter] < 1.0);
        assertTrue(SeasonClimate.FRESHET[earlySpring] && !SeasonClimate.FRESHET[midSummer]);
    }

    @Test
    void noSeasonMeansNoChange() {
        assertEquals(1.0, SeasonClimate.scaled(SeasonClimate.DROUGHT, SeasonClimate.NO_SEASON));
    }
}
