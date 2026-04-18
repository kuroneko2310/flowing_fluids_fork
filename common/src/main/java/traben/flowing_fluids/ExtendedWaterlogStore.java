package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.util.DimensionKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight per-dimension store for "virtual" waterlogging on blocks that do not have a WATERLOGGED property.
 * This lets us keep the block intact while remembering an overlapping fluid state.
 * Enabled via config: enableExtendedWaterlogging.
 */
public final class ExtendedWaterlogStore {
    private static final ConcurrentHashMap<DimensionKey, DimensionStore> STORE = new ConcurrentHashMap<>();

    private ExtendedWaterlogStore() {}

    public static FluidState get(LevelAccessor level, BlockPos pos) {
        DimensionStore store = STORE.get(DimensionKey.of(level));
        if (store == null) return Fluids.EMPTY.defaultFluidState();
        StoredFluid stored = store.byPosition.get(pos.asLong());
        return stored == null ? Fluids.EMPTY.defaultFluidState() : stored.state();
    }

    public static int getAmount(LevelAccessor level, BlockPos pos) {
        DimensionStore store = STORE.get(DimensionKey.of(level));
        if (store == null) return 0;
        StoredFluid stored = store.byPosition.get(pos.asLong());
        return stored == null ? 0 : stored.amount();
    }

    public static boolean has(LevelAccessor level, BlockPos pos) {
        DimensionStore store = STORE.get(DimensionKey.of(level));
        return store != null && store.byPosition.containsKey(pos.asLong());
    }

    public static void set(LevelAccessor level, BlockPos pos, Fluid fluid, int amount) {
        DimensionKey key = DimensionKey.of(level);
        DimensionStore store = STORE.computeIfAbsent(key, k -> new DimensionStore());
        long posKey = pos.asLong();
        StoredFluid previous = store.byPosition.put(posKey, new StoredFluid(fluid, amount));
        if (previous == null) {
            long chunkKey = chunkKeyFromPos(posKey);
            store.chunkIndex.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(posKey);
        }
    }

    public static void remove(LevelAccessor level, BlockPos pos) {
        DimensionKey key = DimensionKey.of(level);
        DimensionStore store = STORE.get(key);
        if (store == null) {
            return;
        }

        long posKey = pos.asLong();
        StoredFluid removed = store.byPosition.remove(posKey);
        if (removed != null) {
            removeFromChunkIndex(store, posKey);
        }
        if (store.byPosition.isEmpty()) {
            STORE.remove(key, store);
        }
    }

    public static void clearChunk(LevelAccessor level, ChunkPos chunkPos) {
        DimensionKey key = DimensionKey.of(level);
        DimensionStore store = STORE.get(key);
        if (store == null) {
            return;
        }

        long chunkKey = chunkKey(chunkPos.x, chunkPos.z);
        Set<Long> positions = store.chunkIndex.remove(chunkKey);
        if (positions == null || positions.isEmpty()) {
            return;
        }

        for (Long posKey : positions) {
            store.byPosition.remove(posKey);
        }

        if (store.byPosition.isEmpty()) {
            STORE.remove(key, store);
        }
    }

    public static void clearDimension(LevelAccessor level) {
        STORE.remove(DimensionKey.of(level));
    }

    public static void clearAll() {
        STORE.clear();
    }

    public static List<StoredFluidEntry> getChunkEntries(LevelAccessor level, ChunkPos chunkPos) {
        DimensionStore store = STORE.get(DimensionKey.of(level));
        if (store == null) {
            return List.of();
        }

        Set<Long> positions = store.chunkIndex.get(chunkKey(chunkPos.x, chunkPos.z));
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }

        ArrayList<StoredFluidEntry> entries = new ArrayList<>(positions.size());
        for (Long posKey : positions) {
            StoredFluid stored = store.byPosition.get(posKey);
            if (stored == null) {
                continue;
            }
            entries.add(new StoredFluidEntry(BlockPos.of(posKey), stored.fluid(), stored.amount()));
        }
        return entries;
    }

    private static void removeFromChunkIndex(DimensionStore store, long posKey) {
        long chunkKey = chunkKeyFromPos(posKey);
        Set<Long> set = store.chunkIndex.get(chunkKey);
        if (set == null) {
            return;
        }
        set.remove(posKey);
        if (set.isEmpty()) {
            store.chunkIndex.remove(chunkKey, set);
        }
    }

    private static long chunkKeyFromPos(long posKey) {
        int chunkX = BlockPos.getX(posKey) >> 4;
        int chunkZ = BlockPos.getZ(posKey) >> 4;
        return chunkKey(chunkX, chunkZ);
    }

    private static long chunkKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    private static final class DimensionStore {
        private final ConcurrentHashMap<Long, StoredFluid> byPosition = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Set<Long>> chunkIndex = new ConcurrentHashMap<>();
    }

    public record StoredFluid(Fluid fluid, int amount) {
        public FluidState state() {
            if (amount <= 0) return Fluids.EMPTY.defaultFluidState();
            if (fluid instanceof net.minecraft.world.level.material.FlowingFluid flowing) {
                return amount >= 8 ? flowing.getSource(false) : flowing.getFlowing(amount, false);
            }
            return fluid.defaultFluidState();
        }
    }

    public record StoredFluidEntry(BlockPos pos, Fluid fluid, int amount) {
    }
}
