package traben.flowing_fluids;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AdaptiveTickSchedulerRegressionTest {

    @Test
    void neighborSignatureChangesWhenLoadedStateChanges() {
        long loaded = AdaptiveTickScheduler.mixNeighborSignature(0L, Direction.NORTH, 4, true, false, false);
        long unloaded = AdaptiveTickScheduler.mixNeighborSignature(0L, Direction.NORTH, 4, false, false, false);

        assertNotEquals(loaded, unloaded);
    }

    @Test
    void neighborSignatureChangesWhenReplaceabilityChangesEvenWithSameAmount() {
        long replaceable = AdaptiveTickScheduler.mixNeighborSignature(0L, Direction.EAST, 0, true, true, true);
        long blocked = AdaptiveTickScheduler.mixNeighborSignature(0L, Direction.EAST, 0, true, true, false);

        assertNotEquals(replaceable, blocked);
    }

    @Test
    void stableDelayRequiresRepeatedConfirmationBeforeDoubling() {
        int baseDelay = 5;

        assertEquals(baseDelay, AdaptiveTickScheduler.computeStableDelay(baseDelay, baseDelay, 0, 0.0f));
        assertEquals(baseDelay * 2, AdaptiveTickScheduler.computeStableDelay(baseDelay, baseDelay, 4, 0.0f));
        assertEquals(baseDelay, AdaptiveTickScheduler.computeStableDelay(baseDelay, baseDelay * 2, 0, 0.2f));
    }
}
