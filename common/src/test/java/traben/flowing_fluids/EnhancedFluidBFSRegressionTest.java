package traben.flowing_fluids;

import org.junit.jupiter.api.Test;
import traben.flowing_fluids.config.FFConfig;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void analyticPoolDormancyDefaultsOff() {
        assertFalse(new FFConfig().enableAnalyticPoolDormancy);
    }

    @Test
    void quantizationKeepsTheWorldVisibleFluidTotal() {
        int[] internalAmounts = {7, 7, 4};

        int[] blockAmounts = EnhancedFluidBFS.quantizeInternalAmountsPreservingBlockTotal(internalAmounts, 4);
        int[] fittedInternal = EnhancedFluidBFS.fitInternalAmountsToBlockBucketsPreservingTotal(
            internalAmounts,
            blockAmounts,
            18
        );

        assertEquals(4, sum(blockAmounts));
        assertArrayEquals(new int[]{2, 1, 1}, blockAmounts);
        assertEquals(18, sum(fittedInternal));
        for (int i = 0; i < blockAmounts.length; i++) {
            assertEquals(blockAmounts[i], FluidAmountConverter.toBlockState(fittedInternal[i]));
        }
    }

    @Test
    void quantizationRejectsImpossibleVisibleTotals() {
        assertThrows(IllegalArgumentException.class,
            () -> EnhancedFluidBFS.quantizeInternalAmountsPreservingBlockTotal(new int[]{8}, 9));
    }

    @Test
    void quantizationPreservesBothTotalsAcrossVariedDistributions() {
        Random random = new Random(0xF10F1D5L);
        for (int scenario = 0; scenario < 2_000; scenario++) {
            int size = 1 + random.nextInt(12);
            int[] original = new int[size];
            int internalTotal = 0;
            int blockTotal = 0;
            for (int i = 0; i < size; i++) {
                original[i] = random.nextInt(FluidAmountConverter.getMaxInternal() + 1);
                internalTotal += original[i];
                blockTotal += FluidAmountConverter.toBlockState(original[i]);
            }

            int[] preferred = new int[size];
            int remaining = internalTotal;
            while (remaining > 0) {
                int index = random.nextInt(size);
                int capacity = FluidAmountConverter.getMaxInternal() - preferred[index];
                if (capacity <= 0) {
                    continue;
                }
                int moved = Math.min(capacity, 1 + random.nextInt(Math.min(remaining, capacity)));
                preferred[index] += moved;
                remaining -= moved;
            }

            int[] blocks = EnhancedFluidBFS.quantizeInternalAmountsPreservingBlockTotal(preferred, blockTotal);
            int[] fitted = EnhancedFluidBFS.fitInternalAmountsToBlockBucketsPreservingTotal(
                preferred,
                blocks,
                internalTotal
            );
            assertEquals(blockTotal, sum(blocks));
            assertEquals(internalTotal, sum(fitted));
            for (int i = 0; i < size; i++) {
                assertEquals(blocks[i], FluidAmountConverter.toBlockState(fitted[i]));
            }
        }
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }
}
