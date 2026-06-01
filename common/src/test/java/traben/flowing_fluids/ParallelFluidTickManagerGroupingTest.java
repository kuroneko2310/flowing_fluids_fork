package traben.flowing_fluids;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ParallelFluidTickManagerGroupingTest {

    @Test
    void adjacentChunksAlwaysLandInDifferentParityGroups() {
        ChunkPos origin = new ChunkPos(0, 0);

        assertNotEquals(ParallelFluidTickManager.getChunkGroupIndex(origin),
            ParallelFluidTickManager.getChunkGroupIndex(new ChunkPos(1, 0)));
        assertNotEquals(ParallelFluidTickManager.getChunkGroupIndex(origin),
            ParallelFluidTickManager.getChunkGroupIndex(new ChunkPos(0, 1)));
        assertNotEquals(ParallelFluidTickManager.getChunkGroupIndex(origin),
            ParallelFluidTickManager.getChunkGroupIndex(new ChunkPos(1, 1)));
    }

    @Test
    void negativeChunkCoordinatesKeepSameParityBehavior() {
        ChunkPos origin = new ChunkPos(-2, -2);

        assertNotEquals(ParallelFluidTickManager.getChunkGroupIndex(origin),
            ParallelFluidTickManager.getChunkGroupIndex(new ChunkPos(-1, -2)));
        assertNotEquals(ParallelFluidTickManager.getChunkGroupIndex(origin),
            ParallelFluidTickManager.getChunkGroupIndex(new ChunkPos(-2, -1)));
        assertNotEquals(ParallelFluidTickManager.getChunkGroupIndex(origin),
            ParallelFluidTickManager.getChunkGroupIndex(new ChunkPos(-1, -1)));
    }
}
