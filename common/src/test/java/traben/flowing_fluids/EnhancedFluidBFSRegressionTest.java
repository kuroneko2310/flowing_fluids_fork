package traben.flowing_fluids;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EnhancedFluidBFSRegressionTest {

    @Test
    void rebalancePreservesTotalForOddRoundingCase() {
        int[] amounts = {9, 8, 1};

        EnhancedFluidBFS.rebalanceClusterAmounts(amounts, amounts.length, 18, 6, 3);

        assertEquals(18, sum(amounts));
        assertArrayEquals(new int[]{7, 7, 4}, amounts);
    }

    @Test
    void rebalancePreservesTotalUnderLowBudget() {
        int[] amounts = {15, 1, 1, 1};

        EnhancedFluidBFS.rebalanceClusterAmounts(amounts, amounts.length, 18, 4, 2);

        assertEquals(18, sum(amounts));
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }
}
