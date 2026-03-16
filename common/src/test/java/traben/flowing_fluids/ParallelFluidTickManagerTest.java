package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelFluidTickManagerTest {

    @Test
    void randomizedDelayIsDeterministicAndBounded() {
        BlockPos pos = new BlockPos(12, 64, -8);
        int delayA = ParallelFluidTickManager.computeRandomizedDelay(pos, 1, 200, 12345L);
        int delayB = ParallelFluidTickManager.computeRandomizedDelay(pos, 1, 200, 12345L);
        int delayC = ParallelFluidTickManager.computeRandomizedDelay(pos, 1, 200, 54321L);

        assertEquals(delayA, delayB);
        assertTrue(delayA >= 1 && delayA <= 200);
        assertTrue(delayC >= 1 && delayC <= 200);
    }
}
