package traben.flowing_fluids.water;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityWaterDisplacementTest {
    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_HEIGHT = 1.8;

    @Test
    void fullySubmergedPlayerLendsAboutFiveLevels() {
        double levels = PLAYER_WIDTH * PLAYER_WIDTH * PLAYER_HEIGHT * 8.0;
        assertEquals(5, EntityWaterDisplacement.quantizeWithHysteresis(levels, 0));
    }

    @Test
    void ankleDeepPuddlesDoNotRiseAtAll() {
        double levels = PLAYER_WIDTH * PLAYER_WIDTH * (1.0 / 8.0) * 8.0;
        assertEquals(0, EntityWaterDisplacement.quantizeWithHysteresis(levels, 0));
    }

    @Test
    void hysteresisHoldsTheCurrentValueForSmallBobbing() {
        assertEquals(3, EntityWaterDisplacement.quantizeWithHysteresis(3.6, 3));
        assertEquals(3, EntityWaterDisplacement.quantizeWithHysteresis(2.4, 3));
        assertEquals(4, EntityWaterDisplacement.quantizeWithHysteresis(3.8, 3));
        assertEquals(0, EntityWaterDisplacement.quantizeWithHysteresis(0.0, 3), "leaving the water always returns everything");
    }

    @Test
    void stepsAreRateLimitedInBothDirections() {
        assertEquals(2, EntityWaterDisplacement.stepToward(0, 5, 2));
        assertEquals(-2, EntityWaterDisplacement.stepToward(5, 0, 2));
        assertEquals(1, EntityWaterDisplacement.stepToward(4, 5, 2));
        assertEquals(0, EntityWaterDisplacement.stepToward(5, 5, 2));
    }

    @Test
    void overlapMatchesBoxAndCellIntersection() {
        assertEquals(0.6, EntityWaterDisplacement.overlapLength(10.2, 10.8, 10.0, 11.0), 1.0E-9);
        assertEquals(0.3, EntityWaterDisplacement.overlapLength(9.7, 10.3, 10.0, 11.0), 1.0E-9);
        assertEquals(0.0, EntityWaterDisplacement.overlapLength(12.0, 13.0, 10.0, 11.0));
    }

    @Test
    void narrowHoleFeedbackConvergesToThePhysicalRise() {
        // 1x1 hole, player footprint 0.36: lent water rising around the player adds 0.36 of itself again.
        double share = PLAYER_WIDTH * PLAYER_WIDTH;
        double base = 2.0;
        int lent = 0;
        for (int i = 0; i < 20; i++) {
            double exact = base + share * lent;
            int target = EntityWaterDisplacement.quantizeWithHysteresis(exact, lent);
            lent += EntityWaterDisplacement.stepToward(lent, target, EntityWaterDisplacement.MAX_LEVEL_STEP_PER_UPDATE);
        }
        double physical = base / (1.0 - share);
        assertEquals(physical, lent, 1.0);
    }
}
