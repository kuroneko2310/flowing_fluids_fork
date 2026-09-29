package traben.flowing_fluids.water;

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

    private double droughtIndex;
    private long lastRainTick = Long.MIN_VALUE;
    private final Map<UUID, DisplacementLedger> ledgers = new HashMap<>();

    public static WeatherWaterSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(WeatherWaterSavedData::load, WeatherWaterSavedData::new, DATA_NAME);
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
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
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
        return tag;
    }

    static final class DisplacementLedger {
        int lent;
        long anchor = Long.MIN_VALUE;
    }
}
