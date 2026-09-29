package traben.flowing_fluids.water;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-dimension persistent state for the weather and displacement systems, so a server restart neither resets a
 * drought, nor forgets water lent by players standing in a pool (which would otherwise stay in the world for good),
 * nor snaps a receding flood back to the sea-level cap.
 */
public final class WeatherWaterSavedData extends SavedData {
    private static final String DATA_NAME = "flowing_fluids_weather_water";
    private static final String DROUGHT_INDEX_KEY = "drought_index";
    private static final String LAST_RAIN_TICK_KEY = "last_rain_tick";
    private static final String LEDGERS_KEY = "displacement_ledgers";
    private static final String UUID_KEY = "uuid";
    private static final String LENT_KEY = "lent";
    private static final String ANCHOR_KEY = "anchor";
    private static final String SEDIMENT_KEY = "sediment";
    private static final String CHUNK_KEY = "chunk";
    private static final String COUNTS_KEY = "counts";
    private static final String GROUNDWATER_KEY = "groundwater";
    private static final String REGION_KEY = "region";
    private static final String STORED_KEY = "stored";
    private static final String SURFACE_KEY = "surface";
    private static final String UPDATED_KEY = "updated";

    private double droughtIndex;
    private long lastRainTick = Long.MIN_VALUE;
    private final Map<UUID, DisplacementLedger> ledgers = new HashMap<>();
    /** Sediment carried by the water in each chunk, indexed by {@link ErosionMath} sediment type. */
    private final Long2ObjectOpenHashMap<int[]> sediment = new Long2ObjectOpenHashMap<>();
    /** Groundwater aquifers per 64x64 region. */
    private final Long2ObjectOpenHashMap<AquiferRegion> aquifers = new Long2ObjectOpenHashMap<>();

    public static WeatherWaterSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                #if MC >= MC_21
                new SavedData.Factory<>(WeatherWaterSavedData::new, (tag, registries) -> load(tag), null),
                #else
                WeatherWaterSavedData::load, WeatherWaterSavedData::new,
                #endif
                DATA_NAME);
    }

    public double droughtIndex() {
        return droughtIndex;
    }

    public void setDroughtIndex(double index) {
        if (Math.abs(index - droughtIndex) > 1.0E-6) {
            droughtIndex = index;
            setDirty();
        }
    }

    public long lastRainTick() {
        return lastRainTick;
    }

    public void setLastRainTick(long tick) {
        if (tick != lastRainTick) {
            lastRainTick = tick;
            setDirty();
        }
    }

    /**
     * Live ledger map; callers must {@link #setDirty()} after changing it.
     */
    Map<UUID, DisplacementLedger> ledgers() {
        return ledgers;
    }

    /**
     * Live sediment counts for a chunk, or {@code null} when it carries none; callers must {@link #setDirty()}.
     */
    int[] sediment(long chunkKey) {
        return sediment.get(chunkKey);
    }

    int[] sedimentOrCreate(long chunkKey) {
        return sediment.computeIfAbsent(chunkKey, ignored -> new int[ErosionMath.SEDIMENT_TYPES]);
    }

    void clearSedimentIfEmpty(long chunkKey) {
        int[] counts = sediment.get(chunkKey);
        if (counts != null) {
            for (int count : counts) {
                if (count > 0) {
                    return;
                }
            }
            sediment.remove(chunkKey);
        }
    }

    /**
     * Live aquifer map; callers must {@link #setDirty()} after changing it.
     */
    Long2ObjectOpenHashMap<AquiferRegion> aquifers() {
        return aquifers;
    }

    private static WeatherWaterSavedData load(CompoundTag tag) {
        WeatherWaterSavedData data = new WeatherWaterSavedData();
        double index = tag.getDouble(DROUGHT_INDEX_KEY);
        data.droughtIndex = Double.isFinite(index) ? Math.max(0.0, Math.min(1.0, index)) : 0.0;
        data.lastRainTick = tag.contains(LAST_RAIN_TICK_KEY) ? tag.getLong(LAST_RAIN_TICK_KEY) : Long.MIN_VALUE;
        ListTag entries = tag.getList(LEDGERS_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.hasUUID(UUID_KEY)) {
                continue;
            }
            int lent = entry.getInt(LENT_KEY);
            if (lent <= 0) {
                continue;
            }
            DisplacementLedger ledger = new DisplacementLedger();
            ledger.lent = Math.min(lent, EntityWaterDisplacement.MAX_LENT_LEVELS);
            ledger.anchor = entry.contains(ANCHOR_KEY) ? entry.getLong(ANCHOR_KEY) : Long.MIN_VALUE;
            data.ledgers.put(entry.getUUID(UUID_KEY), ledger);
        }
        ListTag sedimentEntries = tag.getList(SEDIMENT_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < sedimentEntries.size(); i++) {
            CompoundTag entry = sedimentEntries.getCompound(i);
            int[] stored = entry.getIntArray(COUNTS_KEY);
            int[] counts = new int[ErosionMath.SEDIMENT_TYPES];
            boolean any = false;
            for (int type = 0; type < counts.length && type < stored.length; type++) {
                counts[type] = Math.max(0, stored[type]);
                any |= counts[type] > 0;
            }
            if (any) {
                data.sediment.put(entry.getLong(CHUNK_KEY), counts);
            }
        }
        ListTag aquiferEntries = tag.getList(GROUNDWATER_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < aquiferEntries.size(); i++) {
            CompoundTag entry = aquiferEntries.getCompound(i);
            AquiferRegion region = new AquiferRegion();
            region.stored = Math.max(0, entry.getInt(STORED_KEY));
            region.referenceSurfaceY = entry.getInt(SURFACE_KEY);
            region.lastUpdateTick = entry.getLong(UPDATED_KEY);
            data.aquifers.put(entry.getLong(REGION_KEY), region);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag #if MC >= MC_21 , net.minecraft.core.HolderLookup.Provider registries #endif) {
        return write(tag);
    }

    private CompoundTag write(CompoundTag tag) {
        tag.putDouble(DROUGHT_INDEX_KEY, droughtIndex);
        if (lastRainTick != Long.MIN_VALUE) {
            tag.putLong(LAST_RAIN_TICK_KEY, lastRainTick);
        }
        ListTag entries = new ListTag();
        for (Map.Entry<UUID, DisplacementLedger> entry : ledgers.entrySet()) {
            DisplacementLedger ledger = entry.getValue();
            if (ledger.lent <= 0) {
                continue;
            }
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID(UUID_KEY, entry.getKey());
            entryTag.putInt(LENT_KEY, ledger.lent);
            if (ledger.anchor != Long.MIN_VALUE) {
                entryTag.putLong(ANCHOR_KEY, ledger.anchor);
            }
            entries.add(entryTag);
        }
        tag.put(LEDGERS_KEY, entries);
        ListTag sedimentEntries = new ListTag();
        for (Long2ObjectMap.Entry<int[]> entry : sediment.long2ObjectEntrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong(CHUNK_KEY, entry.getLongKey());
            entryTag.putIntArray(COUNTS_KEY, entry.getValue().clone());
            sedimentEntries.add(entryTag);
        }
        tag.put(SEDIMENT_KEY, sedimentEntries);
        ListTag aquiferEntries = new ListTag();
        for (Long2ObjectMap.Entry<AquiferRegion> entry : aquifers.long2ObjectEntrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong(REGION_KEY, entry.getLongKey());
            entryTag.putInt(STORED_KEY, entry.getValue().stored);
            entryTag.putInt(SURFACE_KEY, entry.getValue().referenceSurfaceY);
            entryTag.putLong(UPDATED_KEY, entry.getValue().lastUpdateTick);
            aquiferEntries.add(entryTag);
        }
        tag.put(GROUNDWATER_KEY, aquiferEntries);
        return tag;
    }

    static final class AquiferRegion {
        int stored;
        int referenceSurfaceY;
        long lastUpdateTick;
    }

    static final class DisplacementLedger {
        int lent;
        long anchor = Long.MIN_VALUE;
    }
}
