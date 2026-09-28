package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import traben.flowing_fluids.config.FFConfig;
import traben.flowing_fluids.optimization.WaterFlowProfile;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ParallelFluidEqualizerTraversalTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void oneWaterCellInAirProducesOnlySixAdjacentDestinations() throws Exception {
        for (boolean focused : new boolean[]{false, true}) {
            LongArrayList targets = scan(focused, false, false);
            assertEquals(7, targets.size(), "The BFS must stop after the six dry neighbors");
            for (long key : targets) {
                BlockPos pos = BlockPos.of(key);
                assertTrue(Math.abs(pos.getX()) + Math.abs(pos.getY() - 64) + Math.abs(pos.getZ()) <= 1);
            }
        }
    }

    @Test
    void flowingWaterIsConnectedToSourceWaterInBothSnapshotModes() throws Exception {
        for (boolean focused : new boolean[]{false, true}) {
            LongArrayList targets = scan(focused, true, false);
            assertTrue(targets.contains(new BlockPos(2, 64, 0).asLong()),
                    "The flowing water at x=1 must expand to its dry neighbor at x=2");
            assertEquals(12, targets.size());
        }
    }

    @Test
    void inletProbeCannotCrossAnAirGapToAnotherPool() throws Exception {
        LongArrayList targets = scan(false, false, true);
        assertFalse(targets.contains(new BlockPos(3, 64, 0).asLong()));
    }

    // Exercise the actual snapshot builder and worker without scheduling a real server.
    private static LongArrayList scan(boolean focused, boolean flowingNeighbor, boolean inlet) throws Exception {
        FFConfig previous = FlowingFluids.config;
        FlowingFluids.config = new FFConfig();
        try {
            Level level = mock(Level.class);
            when(level.getMinBuildHeight()).thenReturn(0);
            when(level.getMaxBuildHeight()).thenReturn(256);
            FluidSectionDataCache cache = mock(FluidSectionDataCache.class);
            when(cache.flags(anyInt(), anyInt(), anyInt())).thenReturn((byte) (FluidSectionDataCache.LOADED | FluidSectionDataCache.AIR));
            when(cache.flags(0, 64, 0)).thenReturn((byte) (FluidSectionDataCache.LOADED | FluidSectionDataCache.HAS_FLUID));
            when(cache.fluidType(0, 64, 0)).thenReturn(Fluids.WATER);
            when(cache.rawAmount(0, 64, 0)).thenReturn((short) 63);
            if (flowingNeighbor) {
                when(cache.flags(1, 64, 0)).thenReturn((byte) (FluidSectionDataCache.LOADED | FluidSectionDataCache.HAS_FLUID));
                when(cache.fluidType(1, 64, 0)).thenReturn(Fluids.FLOWING_WATER);
                when(cache.rawAmount(1, 64, 0)).thenReturn((short) 8);
            }
            if (inlet) {
                when(cache.flags(3, 64, 0)).thenReturn((byte) (FluidSectionDataCache.LOADED | FluidSectionDataCache.HAS_FLUID));
                when(cache.fluidType(3, 64, 0)).thenReturn(Fluids.WATER);
                when(cache.rawAmount(3, 64, 0)).thenReturn((short) 8);
            }
            Class<?> snapshotType = Class.forName(ParallelFluidEqualizer.class.getName() + "$Snapshot");
            Method capture = focused
                    ? snapshotType.getDeclaredMethod("captureFocused", Level.class, BlockPos.class, int.class,
                            Fluid.class, FluidSectionDataCache.class, Direction.class, net.minecraft.core.Vec3i.class)
                    : snapshotType.getDeclaredMethod("capture", Level.class, BlockPos.class, int.class, Fluid.class, FluidSectionDataCache.class);
            capture.setAccessible(true);
            BlockPos source = new BlockPos(0, 64, 0);
            Object snapshot = focused ? capture.invoke(null, level, source, 4, Fluids.WATER, cache, null, null)
                    : capture.invoke(null, level, source, 4, Fluids.WATER, cache);
            WaterFlowProfile profile = mock(WaterFlowProfile.class);
            when(profile.shouldRunInletProbe()).thenReturn(inlet);
            Class<?> requestType = Class.forName(ParallelFluidEqualizer.class.getName() + "$Request");
            Constructor<?> constructor = requestType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object request = constructor.newInstance(null, source, Fluids.WATER, 63, 16, 1000,
                    1f, inlet ? Direction.EAST : null, null, snapshot, profile);
            Method compute = ParallelFluidEqualizer.class.getDeclaredMethod("computeInternal", requestType);
            compute.setAccessible(true);
            Object result = compute.invoke(null, request);
            Method targets = result.getClass().getDeclaredMethod("targets");
            targets.setAccessible(true);
            return (LongArrayList) targets.invoke(result);
        } finally {
            FlowingFluids.config = previous;
        }
    }
}
