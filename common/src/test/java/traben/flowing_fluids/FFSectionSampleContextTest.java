package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FFSectionSampleContextTest {

    @Test
    void samplesOnlyRequestedCellsAndReusesThem() {
        FFSectionSampleContext context = new FFSectionSampleContext();
        FFSectionSampleContext.CellSnapshot snapshot = airSnapshot();

        for (int x = 0; x < 64; x++) {
            context.rememberCell(new BlockPos(x, 64, 0).asLong(), snapshot);
        }
        for (int x = 0; x < 64; x++) {
            assertSame(snapshot, context.cachedCell(new BlockPos(x, 64, 0).asLong()));
        }

        assertEquals(64, context.cachedCellCount());
    }

    @Test
    void invalidationReloadsOnlyTheTouchedCell() {
        FFSectionSampleContext context = new FFSectionSampleContext();
        BlockPos first = BlockPos.ZERO;
        BlockPos second = BlockPos.ZERO.east();
        FFSectionSampleContext.CellSnapshot firstRead = airSnapshot();
        FFSectionSampleContext.CellSnapshot secondRead = airSnapshot();
        context.rememberCell(first.asLong(), firstRead);
        context.rememberCell(second.asLong(), secondRead);

        context.invalidate(first);

        assertNull(context.cachedCell(first.asLong()));
        assertSame(secondRead, context.cachedCell(second.asLong()));
        assertEquals(1, context.cachedCellCount());
    }

    private static FFSectionSampleContext.CellSnapshot airSnapshot() {
        return new FFSectionSampleContext.CellSnapshot(null, null);
    }
}
