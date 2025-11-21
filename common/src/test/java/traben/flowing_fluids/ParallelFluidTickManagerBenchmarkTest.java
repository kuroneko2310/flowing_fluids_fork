package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParallelFluidTickManagerBenchmarkTest {

    @Mock
    private ServerLevel serverLevel;

    @Mock
    private MinecraftServer minecraftServer;

    @Test
    void benchmarkLargeWorldScheduling() {
        lenient().when(serverLevel.hasChunkAt(any(BlockPos.class))).thenReturn(true);
        lenient().when(serverLevel.getServer()).thenReturn(minecraftServer);
        lenient().when(serverLevel.getMinBuildHeight()).thenReturn(0);

        FluidState flowingState = mockFluidState(false);
        FluidState sourceState = mockFluidState(true);

        lenient().when(serverLevel.getFluidState(any(BlockPos.class))).thenAnswer(invocation -> {
            BlockPos pos = invocation.getArgument(0);
            // Alternate stability by coordinate parity
            return (pos.getX() + pos.getZ()) % 2 == 0 ? flowingState : sourceState;
        });

        AtomicInteger scheduledCount = new AtomicInteger();
        List<Integer> usedDelays = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(minecraftServer).execute(any(Runnable.class));

        doAnswer(invocation -> {
            BlockPos pos = invocation.getArgument(0);
            int delay = invocation.getArgument(2);
            scheduledCount.incrementAndGet();
            usedDelays.add(delay);
            return null;
        }).when(serverLevel).scheduleTick(any(BlockPos.class), eq(Fluids.WATER), any(Integer.class));

        Collection<BlockPos> positions = generateChunkedPositions();
        int expectedUnique = Set.copyOf(positions).size();

        long start = System.nanoTime();
        ParallelFluidTickManager.processFluidTicksInParallel(serverLevel, positions);
        long durationNanos = System.nanoTime() - start;

        double durationMs = durationNanos / 1_000_000d;
        double tpsEstimate = durationNanos == 0 ? 0 : (scheduledCount.get() / (durationNanos / 1_000_000_000d));

        System.out.printf("Parallel fluid tick benchmark -> positions: %d, scheduled: %d, duration: %.2f ms, est. TPS: %.2f%n",
                positions.size(), scheduledCount.get(), durationMs, tpsEstimate);

        assertEquals(expectedUnique, scheduledCount.get(), "unique positions should be scheduled once each");
        assertTrue(usedDelays.stream().allMatch(delay -> delay >= 1), "all delays should be >= 1");
    }

    private FluidState mockFluidState(boolean source) {
        FluidState state = org.mockito.Mockito.mock(FluidState.class);
        when(state.isEmpty()).thenReturn(false);
        when(state.isSource()).thenReturn(source);
        when(state.getType()).thenReturn(Fluids.WATER);
        return state;
    }

    private Collection<BlockPos> generateChunkedPositions() {
        List<BlockPos> positions = new ArrayList<>();
        int[][] chunkOffsets = new int[][]{{0, 0}, {32, 0}, {0, 32}, {32, 32}};

        for (int[] offset : chunkOffsets) {
            int baseX = offset[0];
            int baseZ = offset[1];
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    positions.add(new BlockPos(baseX + x, 64, baseZ + z));
                    // Inject duplicates to validate deduplication
                    if ((x + z) % 5 == 0) {
                        positions.add(new BlockPos(baseX + x, 64, baseZ + z));
                    }
                }
            }
        }

        return positions;
    }
}
