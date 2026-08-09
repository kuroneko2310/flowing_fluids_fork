package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SiphonFlowSystemRegressionTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clearRuntimeState() {
        SiphonFlowSystem.clearAll();
    }

    @Test
    void queuedCandidatesAreIsolatedByDimension() {
        Level overworld = mockLevel(Level.OVERWORLD);
        Level nether = mockLevel(Level.NETHER);

        assertTrue(SiphonFlowSystem.enqueueProbe(overworld, new BlockPos(1, 64, 1)));
        assertTrue(SiphonFlowSystem.enqueueProbe(nether, new BlockPos(1, 64, 1)));
        assertEquals(1, SiphonFlowSystem.getQueuedProbeCount(overworld));
        assertEquals(1, SiphonFlowSystem.getQueuedProbeCount(nether));

        SiphonFlowSystem.clearDimension(overworld);

        assertEquals(0, SiphonFlowSystem.getQueuedProbeCount(overworld));
        assertEquals(1, SiphonFlowSystem.getQueuedProbeCount(nether));
    }

    @Test
    void duplicateAndChunkFloodCandidatesAreBounded() {
        Level level = mockLevel(Level.OVERWORLD);
        BlockPos first = new BlockPos(0, 64, 0);

        assertTrue(SiphonFlowSystem.enqueueProbe(level, first));
        assertFalse(SiphonFlowSystem.enqueueProbe(level, first));
        for (int index = 1; index < 8; index++) {
            assertTrue(SiphonFlowSystem.enqueueProbe(level, new BlockPos(index, 64, 0)));
        }
        assertFalse(SiphonFlowSystem.enqueueProbe(level, new BlockPos(8, 64, 0)));
        assertTrue(SiphonFlowSystem.enqueueProbe(level, new BlockPos(16, 64, 0)));
        assertEquals(9, SiphonFlowSystem.getQueuedProbeCount(level));
    }

    @Test
    void siphonDoesNotRewriteForeignFluidsAsVanillaWater() {
        assertTrue(SiphonFlowSystem.isSupportedSiphonFluid(Fluids.WATER.defaultFluidState()));
        assertTrue(SiphonFlowSystem.isSupportedSiphonFluid(Fluids.FLOWING_WATER.defaultFluidState()));
        assertFalse(SiphonFlowSystem.isSupportedSiphonFluid(Fluids.LAVA.defaultFluidState()));
        assertFalse(SiphonFlowSystem.isSupportedSiphonFluid(Fluids.EMPTY.defaultFluidState()));
    }

    @Test
    void candidateRescanCacheIsConstantTimeAndDimensionLocal() {
        Level overworld = mockLevel(Level.OVERWORLD);
        Level nether = mockLevel(Level.NETHER);
        when(overworld.getGameTime()).thenReturn(100L, 100L, 120L);
        when(nether.getGameTime()).thenReturn(100L);
        long key = new BlockPos(3, 70, 5).asLong();

        assertTrue(SiphonFlowSystem.tryBeginCandidateScan(overworld, key));
        assertFalse(SiphonFlowSystem.tryBeginCandidateScan(overworld, key));
        assertTrue(SiphonFlowSystem.tryBeginCandidateScan(nether, key));
        assertTrue(SiphonFlowSystem.tryBeginCandidateScan(overworld, key));
    }

    private static Level mockLevel(net.minecraft.resources.ResourceKey<Level> dimension) {
        Level level = mock(Level.class);
        when(level.dimension()).thenReturn(dimension);
        return level;
    }
}
