package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;
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
    private static final String DATA_NAME = FlowingFluids.MOD_ID + "_extended_waterlog";

    private ExtendedWaterlogStore() {}

    public static FluidState get(LevelAccessor level, BlockPos pos) {
        DimensionStore store = STORE.get(DimensionKey.of(level));
        if (store == null) return Fluids.EMPTY.defaultFluidState();
        synchronized (store) {
            StoredFluid stored = store.byPosition.get(pos.asLong());
            return stored == null ? Fluids.EMPTY.defaultFluidState() : stored.state();
        }
    }

    public static int getAmount(LevelAccessor level, BlockPos pos) {
        DimensionStore store = STORE.get(DimensionKey.of(level));
        if (store == null) return 0;
        synchronized (store) {
            StoredFluid stored = store.byPosition.get(pos.asLong());
            return stored == null ? 0 : stored.amount();
        }
    }

    public static boolean has(LevelAccessor level, BlockPos pos) {
        DimensionStore store = STORE.get(DimensionKey.of(level));
        if (store == null) return false;
        synchronized (store) {
            return store.byPosition.containsKey(pos.asLong());
        }
    }

    public static void set(LevelAccessor level, BlockPos pos, Fluid fluid, int amount) {
        if (amount <= 0 || fluid == null || fluid == Fluids.EMPTY) {
            remove(level, pos);
            return;
        }
        int clampedAmount = Math.max(1, Math.min(8, amount));
        DimensionKey key = DimensionKey.of(level);
        DimensionStore store = STORE.computeIfAbsent(key, k -> new DimensionStore());
        long posKey = pos.asLong();
        synchronized (store) {
            StoredFluid previous = store.byPosition.put(posKey, new StoredFluid(fluid, clampedAmount));
            if (previous == null) {
                long chunkKey = chunkKeyFromPos(posKey);
                store.chunkIndex.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(posKey);
            }
        }
        if (level instanceof ServerLevel serverLevel) {
            PersistentData.get(serverLevel).set(pos, fluid, clampedAmount);
        }
    }

    public static void remove(LevelAccessor level, BlockPos pos) {
        DimensionKey key = DimensionKey.of(level);
        DimensionStore store = STORE.get(key);
        if (store != null) {
            long posKey = pos.asLong();
            synchronized (store) {
                StoredFluid removed = store.byPosition.remove(posKey);
                if (removed != null) {
                    removeFromChunkIndex(store, posKey);
                }
                if (store.byPosition.isEmpty()) {
                    STORE.remove(key, store);
                }
            }
        }
        if (level instanceof ServerLevel serverLevel) {
            PersistentData.get(serverLevel).remove(pos);
        }
    }

    public static void clearChunk(LevelAccessor level, ChunkPos chunkPos) {
        DimensionKey key = DimensionKey.of(level);
        DimensionStore store = STORE.get(key);
        if (store == null) {
            return;
        }

        long chunkKey = chunkKey(chunkPos.x, chunkPos.z);
        synchronized (store) {
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

        synchronized (store) {
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
    }

    public static void loadChunk(LevelAccessor level, ChunkPos chunkPos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        List<StoredFluidEntry> entries = PersistentData.get(serverLevel).getChunkEntries(chunkPos);
        DimensionKey key = DimensionKey.of(level);
        DimensionStore store = STORE.computeIfAbsent(key, k -> new DimensionStore());
        long chunkKey = chunkKey(chunkPos.x, chunkPos.z);

        synchronized (store) {
            Set<Long> previous = store.chunkIndex.remove(chunkKey);
            if (previous != null) {
                for (Long posKey : previous) {
                    store.byPosition.remove(posKey);
                }
            }

            for (StoredFluidEntry entry : entries) {
                if (entry.amount() <= 0 || entry.fluid() == Fluids.EMPTY) {
                    continue;
                }
                long posKey = entry.pos().asLong();
                store.byPosition.put(posKey, new StoredFluid(entry.fluid(), Math.max(1, Math.min(8, entry.amount()))));
                store.chunkIndex.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(posKey);
            }

            if (store.byPosition.isEmpty()) {
                STORE.remove(key, store);
            }
        }
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

    private static final class PersistentData extends SavedData {
        private static final String ENTRIES_KEY = "entries";
        private static final String POS_KEY = "pos";
        private static final String FLUID_KEY = "fluid";
        private static final String AMOUNT_KEY = "amount";

        private final ConcurrentHashMap<Long, StoredFluid> byPosition = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Set<Long>> chunkIndex = new ConcurrentHashMap<>();

        private static PersistentData get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(PersistentData::load, PersistentData::new, DATA_NAME);
        }

        private static PersistentData load(CompoundTag tag) {
            PersistentData data = new PersistentData();
            ListTag entries = tag.getList(ENTRIES_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entryTag = entries.getCompound(i);
                Fluid fluid = readFluid(entryTag.getString(FLUID_KEY));
                int amount = entryTag.getInt(AMOUNT_KEY);
                if (fluid == Fluids.EMPTY || amount <= 0) {
                    continue;
                }
                long posKey = entryTag.getLong(POS_KEY);
                data.putRaw(posKey, fluid, Math.max(1, Math.min(8, amount)));
            }
            return data;
        }

        private static Fluid readFluid(String id) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null) {
                return Fluids.EMPTY;
            }
            Fluid fluid = BuiltInRegistries.FLUID.get(location);
            return fluid == null ? Fluids.EMPTY : fluid;
        }

        void set(BlockPos pos, Fluid fluid, int amount) {
            putRaw(pos.asLong(), fluid, amount);
            setDirty();
        }

        void remove(BlockPos pos) {
            long posKey = pos.asLong();
            StoredFluid removed = byPosition.remove(posKey);
            if (removed != null) {
                removeFromChunkIndex(posKey);
                setDirty();
            }
        }

        List<StoredFluidEntry> getChunkEntries(ChunkPos chunkPos) {
            Set<Long> positions = chunkIndex.get(chunkKey(chunkPos.x, chunkPos.z));
            if (positions == null || positions.isEmpty()) {
                return List.of();
            }

            ArrayList<StoredFluidEntry> entries = new ArrayList<>(positions.size());
            for (Long posKey : positions) {
                StoredFluid stored = byPosition.get(posKey);
                if (stored == null) {
                    continue;
                }
                entries.add(new StoredFluidEntry(BlockPos.of(posKey), stored.fluid(), stored.amount()));
            }
            return entries;
        }

        private void putRaw(long posKey, Fluid fluid, int amount) {
            byPosition.put(posKey, new StoredFluid(fluid, Math.max(1, Math.min(8, amount))));
            chunkIndex.computeIfAbsent(chunkKeyFromPos(posKey), k -> ConcurrentHashMap.newKeySet()).add(posKey);
        }

        private void removeFromChunkIndex(long posKey) {
            long chunkKey = chunkKeyFromPos(posKey);
            Set<Long> set = chunkIndex.get(chunkKey);
            if (set == null) {
                return;
            }
            set.remove(posKey);
            if (set.isEmpty()) {
                chunkIndex.remove(chunkKey, set);
            }
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag entries = new ListTag();
            for (var entry : byPosition.entrySet()) {
                StoredFluid stored = entry.getValue();
                ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(stored.fluid());
                if (fluidId == null || stored.amount() <= 0) {
                    continue;
                }

                CompoundTag entryTag = new CompoundTag();
                entryTag.putLong(POS_KEY, entry.getKey());
                entryTag.putString(FLUID_KEY, fluidId.toString());
                entryTag.putInt(AMOUNT_KEY, Math.max(1, Math.min(8, stored.amount())));
                entries.add(entryTag);
            }
            tag.put(ENTRIES_KEY, entries);
            return tag;
        }
    }
}
