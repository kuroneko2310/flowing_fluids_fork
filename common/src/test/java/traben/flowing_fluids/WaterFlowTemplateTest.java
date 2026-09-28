package traben.flowing_fluids;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaterFlowTemplateTest {
    @Test
    void normalWaterUsesLocalLevelTransferForMeaningfulDifference() {
        assertEquals(WaterFlowTemplate.HorizontalMode.LOCAL_LEVEL_TRANSFER,
            WaterFlowTemplate.chooseHorizontalMode(true, false, 8, 0));
        assertEquals(WaterFlowTemplate.HorizontalMode.LOCAL_LEVEL_TRANSFER,
            WaterFlowTemplate.chooseHorizontalMode(true, false, 6, 3));
    }

    @Test
    void normalWaterSettlesWhenLevelDifferenceIsOneOrLess() {
        assertEquals(WaterFlowTemplate.HorizontalMode.SETTLED,
            WaterFlowTemplate.chooseHorizontalMode(true, false, 8, 7));
        assertEquals(WaterFlowTemplate.HorizontalMode.SETTLED,
            WaterFlowTemplate.chooseHorizontalMode(true, false, 5, 5));
    }

    @Test
    void thinWaterKeepsDeepSearchForNearbyLedges() {
        assertEquals(WaterFlowTemplate.HorizontalMode.DEEP_EDGE_SEARCH,
            WaterFlowTemplate.chooseHorizontalMode(true, true, 1, 0));
    }

    @Test
    void lavaKeepsItsExistingSlopeSearchBehavior() {
        assertEquals(WaterFlowTemplate.HorizontalMode.DEEP_EDGE_SEARCH,
            WaterFlowTemplate.chooseHorizontalMode(false, false, 8, 0));
    }

    @Test
    void malformedAmountsAreClampedBeforeDecision() {
        assertEquals(WaterFlowTemplate.HorizontalMode.SETTLED,
            WaterFlowTemplate.chooseHorizontalMode(true, false, 99, 8));
        assertEquals(WaterFlowTemplate.HorizontalMode.LOCAL_LEVEL_TRANSFER,
            WaterFlowTemplate.chooseHorizontalMode(true, false, 4, -3));
    }

    @Test
    void normalWaterNeverUsesDeepSearchAcrossAllVisibleLevelPairs() {
        for (int source = 0; source <= 8; source++) {
            for (int target = 0; target <= 8; target++) {
                WaterFlowTemplate.HorizontalMode mode = WaterFlowTemplate.chooseHorizontalMode(
                    true, false, source, target);
                assertEquals(source - target > 1
                        ? WaterFlowTemplate.HorizontalMode.LOCAL_LEVEL_TRANSFER
                        : WaterFlowTemplate.HorizontalMode.SETTLED,
                    mode);
            }
        }
    }
}
