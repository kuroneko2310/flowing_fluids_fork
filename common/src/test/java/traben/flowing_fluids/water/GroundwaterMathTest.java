package traben.flowing_fluids.water;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundwaterMathTest {
    @Test
    void wetterAquifersRaiseTheWaterTable() {
        assertEquals(20, GroundwaterMath.tableDepth(0.0, 2, 20));
        assertEquals(2, GroundwaterMath.tableDepth(1.0, 2, 20));
        assertEquals(11, GroundwaterMath.tableDepth(0.5, 2, 20));
    }

    @Test
    void coastalTablesDoNotSinkBelowSeaLevel() {
        assertEquals(63, GroundwaterMath.tableY(66, 20, 63, 2));
        assertEquals(80, GroundwaterMath.tableY(100, 20, 63, 2));
        assertEquals(58, GroundwaterMath.tableY(60, 20, 63, 2), "ground below sea level keeps the table minDepth down");
    }

    @Test
    void drainIsExponentialAndCanEmptyTheStore() {
        assertEquals(1000, GroundwaterMath.drain(1000, 0, 0.05, 1.0));
        int afterDay = GroundwaterMath.drain(1000, 24000, 0.05, 1.0);
        assertEquals((int) Math.floor(1000 * Math.exp(-0.05)), afterDay);
        assertEquals(0, GroundwaterMath.drain(1, 24000, 0.05, 1.0));
    }

    @Test
    void springsFollowSaturation() {
        assertEquals(0.4, GroundwaterMath.springMultiplier(0.0), 1.0E-9);
        assertEquals(1.0, GroundwaterMath.springMultiplier(0.5), 1.0E-9);
        assertEquals(1.6, GroundwaterMath.springMultiplier(1.0), 1.0E-9);
        assertTrue(GroundwaterMath.saturation(5, 0) == 0.0);
    }
}
