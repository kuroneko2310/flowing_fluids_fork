package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLocalSlopeCacheConcurrencyTest {

    @AfterEach
    void tearDown() {
        ChunkLocalSlopeCache.clearAll();
    }

    @Test
    void concurrentReadWriteRemainsConsistent() throws Exception {
        ChunkPos chunkPos = new ChunkPos(0, 0);
        int taskCount = 128;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            final int taskIndex = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    BlockPos pos = new BlockPos(taskIndex % 16, taskIndex / 256, (taskIndex / 16) % 16);
                    Direction direction = Direction.values()[taskIndex % Direction.values().length];
                    int distance = taskIndex * 2;

                    ChunkLocalSlopeCache.putCached(chunkPos, pos, taskIndex, direction, distance);
                    ChunkLocalSlopeCache.putGradientVector(chunkPos, pos, new BlockPos(distance, distance / 2, -distance));

                    assertEquals(distance,
                            ChunkLocalSlopeCache.getCached(chunkPos, pos, taskIndex, direction));
                    assertEquals(new BlockPos(distance, distance / 2, -distance),
                            ChunkLocalSlopeCache.getGradientVector(chunkPos, pos));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Test interrupted", e);
                }
            }));
        }

        startLatch.countDown();

        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Verify a known final write survives after the concurrent phase.
        BlockPos finalPos = new BlockPos((taskCount - 1) % 16, (taskCount - 1) / 256, ((taskCount - 1) / 16) % 16);
        Direction finalDirection = Direction.values()[(taskCount - 1) % Direction.values().length];
        int finalDistance = (taskCount - 1) * 2;
        ChunkLocalSlopeCache.putCached(chunkPos, finalPos, taskCount - 1, finalDirection, finalDistance);
        ChunkLocalSlopeCache.putGradientVector(chunkPos, finalPos, new BlockPos(finalDistance, finalDistance / 2, -finalDistance));

        assertEquals(finalDistance,
                ChunkLocalSlopeCache.getCached(chunkPos, finalPos, taskCount - 1, finalDirection));
        assertEquals(new BlockPos(finalDistance, finalDistance / 2, -finalDistance),
                ChunkLocalSlopeCache.getGradientVector(chunkPos, finalPos));
    }
}
