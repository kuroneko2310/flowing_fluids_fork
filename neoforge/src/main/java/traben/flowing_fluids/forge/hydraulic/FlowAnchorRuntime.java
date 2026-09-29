package traben.flowing_fluids.forge.hydraulic;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FlowAnchorRuntime {
    private static final Map<ResourceKey<Level>, DimensionAnchors> ANCHORS_BY_DIMENSION =
        new ConcurrentHashMap<>();

    private FlowAnchorRuntime() {
    }

    public static void register(ServerLevel level, BlockPos pos, FlowAnchorTier tier) {
        DimensionAnchors dimensionAnchors = ANCHORS_BY_DIMENSION.computeIfAbsent(
            level.dimension(),
            ignored -> new DimensionAnchors()
        );
        AnchorEntry nextEntry = new AnchorEntry(pos.immutable(), tier.processingRadius(), tier.visualRadius());
        AnchorEntry previousEntry = dimensionAnchors.entriesByPos.put(nextEntry.pos(), nextEntry);
        if (previousEntry != null) {
            ff$removeFromChunkCoverage(dimensionAnchors.processingByChunk, previousEntry, false);
            ff$removeFromChunkCoverage(dimensionAnchors.visualByChunk, previousEntry, true);
        }
        ff$addToChunkCoverage(dimensionAnchors.processingByChunk, nextEntry, false);
        ff$addToChunkCoverage(dimensionAnchors.visualByChunk, nextEntry, true);
    }

    public static void unregister(ServerLevel level, BlockPos pos) {
        DimensionAnchors dimensionAnchors = ANCHORS_BY_DIMENSION.get(level.dimension());
        if (dimensionAnchors == null) {
            return;
        }
        AnchorEntry entry = dimensionAnchors.entriesByPos.remove(pos);
        if (entry == null) {
            return;
        }
        ff$removeFromChunkCoverage(dimensionAnchors.processingByChunk, entry, false);
        ff$removeFromChunkCoverage(dimensionAnchors.visualByChunk, entry, true);
        if (dimensionAnchors.isEmpty()) {
            ANCHORS_BY_DIMENSION.remove(level.dimension());
        }
    }

    public static boolean hasProcessingAnchorNearby(LevelAccessor level, BlockPos pos) {
        return ff$hasAnchorNearby(level, pos, false);
    }

    public static boolean hasVisualAnchorNearby(LevelAccessor level, BlockPos pos) {
        return ff$hasAnchorNearby(level, pos, true);
    }

    public static void clearDimension(ServerLevel level) {
        ANCHORS_BY_DIMENSION.remove(level.dimension());
    }

    private static boolean ff$hasAnchorNearby(LevelAccessor level, BlockPos pos, boolean visualRange) {
        if (!(level instanceof Level actualLevel)) {
            return false;
        }
        DimensionAnchors dimensionAnchors = ANCHORS_BY_DIMENSION.get(actualLevel.dimension());
        if (dimensionAnchors == null || dimensionAnchors.isEmpty()) {
            return false;
        }
        List<AnchorEntry> anchors = visualRange
            ? dimensionAnchors.visualByChunk.get(ff$chunkKey(pos))
            : dimensionAnchors.processingByChunk.get(ff$chunkKey(pos));
        if (anchors == null) {
            return false;
        }
        for (AnchorEntry entry : anchors) {
            if (ff$isInRange(entry, pos, visualRange)) {
                return true;
            }
        }
        return false;
    }

    private static void ff$addToChunkCoverage(Map<Long, CopyOnWriteArrayList<AnchorEntry>> byChunk,
                                              AnchorEntry entry,
                                              boolean visualRange) {
        ff$forEachCoveredChunk(entry, visualRange, chunkKey -> {
            CopyOnWriteArrayList<AnchorEntry> anchors = byChunk.computeIfAbsent(chunkKey, ignored -> new CopyOnWriteArrayList<>());
            anchors.removeIf(existing -> existing.pos().equals(entry.pos()));
            anchors.add(entry);
        });
    }

    private static void ff$removeFromChunkCoverage(Map<Long, CopyOnWriteArrayList<AnchorEntry>> byChunk,
                                                   AnchorEntry entry,
                                                   boolean visualRange) {
        ff$forEachCoveredChunk(entry, visualRange, chunkKey -> {
            List<AnchorEntry> anchors = byChunk.get(chunkKey);
            if (anchors == null) {
                return;
            }
            anchors.removeIf(existing -> existing.pos().equals(entry.pos()));
            if (anchors.isEmpty()) {
                byChunk.remove(chunkKey);
            }
        });
    }

    private static void ff$forEachCoveredChunk(AnchorEntry entry,
                                               boolean visualRange,
                                               java.util.function.LongConsumer action) {
        int radius = visualRange ? entry.visualRadius() : entry.processingRadius();
        int minChunkX = (entry.pos().getX() - radius) >> 4;
        int maxChunkX = (entry.pos().getX() + radius) >> 4;
        int minChunkZ = (entry.pos().getZ() - radius) >> 4;
        int maxChunkZ = (entry.pos().getZ() + radius) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                action.accept(ChunkPos.asLong(chunkX, chunkZ));
            }
        }
    }

    private static boolean ff$isInRange(AnchorEntry entry, BlockPos pos, boolean visualRange) {
        int radius = visualRange ? entry.visualRadius() : entry.processingRadius();
        double dx = (entry.pos().getX() + 0.5D) - (pos.getX() + 0.5D);
        double dy = (entry.pos().getY() + 0.5D) - (pos.getY() + 0.5D);
        double dz = (entry.pos().getZ() + 0.5D) - (pos.getZ() + 0.5D);
        return (dx * dx) + (dy * dy) + (dz * dz) <= (double) radius * radius;
    }

    private static long ff$chunkKey(BlockPos pos) {
        return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static final class DimensionAnchors {
        private final Map<BlockPos, AnchorEntry> entriesByPos = new ConcurrentHashMap<>();
        private final Map<Long, CopyOnWriteArrayList<AnchorEntry>> processingByChunk = new ConcurrentHashMap<>();
        private final Map<Long, CopyOnWriteArrayList<AnchorEntry>> visualByChunk = new ConcurrentHashMap<>();

        private boolean isEmpty() {
            return entriesByPos.isEmpty();
        }
    }

    private record AnchorEntry(BlockPos pos, int processingRadius, int visualRadius) {
    }
}
