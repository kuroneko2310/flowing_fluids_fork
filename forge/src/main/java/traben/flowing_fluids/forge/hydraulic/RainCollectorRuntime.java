package traben.flowing_fluids.forge.hydraulic;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import traben.flowing_fluids.FlowingFluids;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RainCollectorRuntime {
    private static final Map<ResourceKey<Level>, DimensionCollectors> COLLECTORS_BY_DIMENSION =
        new ConcurrentHashMap<>();

    private RainCollectorRuntime() {
    }

    public static void register(ServerLevel level, BlockPos pos, int radius) {
        DimensionCollectors collectors = COLLECTORS_BY_DIMENSION.computeIfAbsent(
            level.dimension(),
            ignored -> new DimensionCollectors()
        );
        CollectorEntry nextEntry = new CollectorEntry(pos.immutable(), Math.max(1, radius));
        CollectorEntry previousEntry = collectors.entriesByPos.put(nextEntry.pos(), nextEntry);
        if (previousEntry != null) {
            ff$removeFromChunkCoverage(collectors.byChunk, previousEntry);
        }
        ff$addToChunkCoverage(collectors.byChunk, nextEntry);
    }

    public static void unregister(ServerLevel level, BlockPos pos) {
        DimensionCollectors collectors = COLLECTORS_BY_DIMENSION.get(level.dimension());
        if (collectors == null) {
            return;
        }
        CollectorEntry entry = collectors.entriesByPos.remove(pos);
        if (entry == null) {
            return;
        }
        ff$removeFromChunkCoverage(collectors.byChunk, entry);
        if (collectors.isEmpty()) {
            COLLECTORS_BY_DIMENSION.remove(level.dimension());
        }
    }

    public static boolean tryAbsorbRainWater(ServerLevel level, BlockPos rainPos, int amount) {
        if (amount <= 0) {
            return false;
        }
        DimensionCollectors collectors = COLLECTORS_BY_DIMENSION.get(level.dimension());
        if (collectors == null || collectors.isEmpty()) {
            return false;
        }
        List<CollectorEntry> entries = collectors.byChunk.get(ff$chunkKey(rainPos));
        if (entries == null) {
            return false;
        }

        for (CollectorEntry entry : entries) {
            if (!ff$isInRange(entry, rainPos)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(entry.pos());
            if (!(blockEntity instanceof RainCollectorBlockEntity collector) || !collector.isAbsorbingMode()) {
                continue;
            }
            if (ff$tryAbsorbWithCollector(level, collector, amount)) {
                return true;
            }
        }
        return false;
    }

    public static void clearDimension(ServerLevel level) {
        COLLECTORS_BY_DIMENSION.remove(level.dimension());
    }

    private static boolean ff$tryAbsorbWithCollector(ServerLevel level, RainCollectorBlockEntity collector, int amount) {
        int energyCost = collector.absorbEnergyCost();
        if (collector.energyStored() < energyCost) {
            return false;
        }
        if (!collector.canStoreCollectedWater()) {
            return false;
        }

        int accepted = collector.addCollectedWater(amount);
        if (accepted <= 0) {
            return false;
        }
        collector.consumeEnergy(energyCost);
        return true;
    }

    private static void ff$addToChunkCoverage(Map<Long, CopyOnWriteArrayList<CollectorEntry>> byChunk,
                                              CollectorEntry entry) {
        ff$forEachCoveredChunk(entry, chunkKey -> {
            CopyOnWriteArrayList<CollectorEntry> collectors = byChunk.computeIfAbsent(chunkKey, ignored -> new CopyOnWriteArrayList<>());
            collectors.removeIf(existing -> existing.pos().equals(entry.pos()));
            collectors.add(entry);
        });
    }

    private static void ff$removeFromChunkCoverage(Map<Long, CopyOnWriteArrayList<CollectorEntry>> byChunk,
                                                   CollectorEntry entry) {
        ff$forEachCoveredChunk(entry, chunkKey -> {
            List<CollectorEntry> collectors = byChunk.get(chunkKey);
            if (collectors == null) {
                return;
            }
            collectors.removeIf(existing -> existing.pos().equals(entry.pos()));
            if (collectors.isEmpty()) {
                byChunk.remove(chunkKey);
            }
        });
    }

    private static void ff$forEachCoveredChunk(CollectorEntry entry, java.util.function.LongConsumer action) {
        int radius = entry.radius();
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

    private static boolean ff$isInRange(CollectorEntry entry, BlockPos pos) {
        int radius = entry.radius();
        double dx = (entry.pos().getX() + 0.5D) - (pos.getX() + 0.5D);
        double dy = (entry.pos().getY() + 0.5D) - (pos.getY() + 0.5D);
        double dz = (entry.pos().getZ() + 0.5D) - (pos.getZ() + 0.5D);
        return (dx * dx) + (dy * dy) + (dz * dz) <= (double) radius * radius;
    }

    private static long ff$chunkKey(BlockPos pos) {
        return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static final class DimensionCollectors {
        private final Map<BlockPos, CollectorEntry> entriesByPos = new ConcurrentHashMap<>();
        private final Map<Long, CopyOnWriteArrayList<CollectorEntry>> byChunk = new ConcurrentHashMap<>();

        private boolean isEmpty() {
            return entriesByPos.isEmpty();
        }
    }

    private record CollectorEntry(BlockPos pos, int radius) {
    }
}
