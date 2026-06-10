package traben.flowing_fluids.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidPerformanceMonitorTest {

    @Test
    void detectsPauseLikeWallClockGapWhenServerTickBarelyAdvanced() {
        assertTrue(FluidPerformanceMonitor.isPauseLikeWallClockGap(1_500_000_000L, 1));
        assertTrue(FluidPerformanceMonitor.isPauseLikeWallClockGap(1_500_000_000L, 0));
    }

    @Test
    void normalLagSpikeWithAdvancingTicksIsStillMeasured() {
        assertFalse(FluidPerformanceMonitor.isPauseLikeWallClockGap(1_500_000_000L, 20));
    }

    @Test
    void shortTickGapIsNotTreatedAsPauseRecovery() {
        assertFalse(FluidPerformanceMonitor.isPauseLikeWallClockGap(250_000_000L, 1));
    }
}
