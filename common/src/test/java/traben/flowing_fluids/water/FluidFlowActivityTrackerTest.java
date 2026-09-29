package traben.flowing_fluids.water;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidFlowActivityTrackerTest {

    @Test
    void untouchedCellReportsNoMotion() {
        var cell = new FluidFlowActivityTracker.CellActivity();
        assertEquals(Long.MAX_VALUE, cell.ticksSinceChange(100));
        assertEquals(0.0, cell.levelsPerSecond(100));
    }

    @Test
    void ticksSinceChangeFollowsTheLastWrite() {
        var cell = new FluidFlowActivityTracker.CellActivity();
        cell.record(50, 2);
        assertEquals(0, cell.ticksSinceChange(50));
        assertEquals(30, cell.ticksSinceChange(80));
    }

    @Test
    void steadyFlowSettlesAtItsRealRate() {
        var cell = new FluidFlowActivityTracker.CellActivity();
        // one level through the cell every tick = 20 levels per second
        for (long tick = 0; tick < 600; tick++) {
            cell.record(tick, 1);
        }
        double rate = cell.levelsPerSecond(599);
        assertTrue(Math.abs(rate - 20.0) < 0.6, "rate was " + rate);
    }

    @Test
    void rateDecaysOnceTheWaterStops() {
        var cell = new FluidFlowActivityTracker.CellActivity();
        cell.record(0, 8);
        double fresh = cell.levelsPerSecond(0);
        double later = cell.levelsPerSecond((long) FluidFlowActivityTracker.RATE_TIME_CONSTANT_TICKS);
        assertEquals(8.0 / FluidFlowActivityTracker.RATE_TIME_CONSTANT_TICKS * 20.0, fresh, 1e-9);
        assertEquals(fresh / Math.E, later, 1e-9);
    }
}
