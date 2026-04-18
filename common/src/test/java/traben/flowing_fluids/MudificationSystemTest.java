package traben.flowing_fluids;

import org.junit.jupiter.api.Test;
import traben.flowing_fluids.optimization.WaterFlowProfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MudificationLogicTest {

    @Test
    void exposureGainScalesWithFlowSpeedAndPressure() {
        assertEquals(1.0f, MudificationLogic.getExposureGain(
            WaterFlowProfile.FlowSpeed.NORMAL, 0.2f, false, 1.0f
        ), 0.0001f);
        assertEquals(4.0f, MudificationLogic.getExposureGain(
            WaterFlowProfile.FlowSpeed.TORRENT, 0.7f, false, 1.0f
        ), 0.0001f);
    }

    @Test
    void staleExposureExpiresInsteadOfAccumulatingForever() {
        assertEquals(2.0f, MudificationLogic.resolveExposureAfterTouch(5.0f, 10L, 611L, 2.0f, 600L), 0.0001f);
        assertEquals(7.0f, MudificationLogic.resolveExposureAfterTouch(5.0f, 10L, 610L, 2.0f, 600L), 0.0001f);
    }

    @Test
    void mudThresholdMatchesSurfaceAndBankRules() {
        assertEquals(6, MudificationLogic.getMudThreshold(true, false));
        assertEquals(10, MudificationLogic.getMudThreshold(false, false));
        assertEquals(14, MudificationLogic.getMudThreshold(false, true));
    }

    @Test
    void farmlandStaysEligibleEvenWhenPlayerPlaced() {
        assertTrue(MudificationLogic.shouldIgnorePlayerPlaced(true, false));
        assertFalse(MudificationLogic.shouldIgnorePlayerPlaced(true, true));
    }
}
