package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class PlacedTerrainTracker extends SavedData {
    private static final String DATA_NAME = FlowingFluids.MOD_ID + "_placed_terrain";
    private static final String PLAYER_PLACED_KEY = "player_placed";

    private final LongOpenHashSet playerPlaced = new LongOpenHashSet();

    public static PlacedTerrainTracker get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(PlacedTerrainTracker::load, PlacedTerrainTracker::new, DATA_NAME);
    }

    private static PlacedTerrainTracker load(CompoundTag tag) {
        PlacedTerrainTracker tracker = new PlacedTerrainTracker();
        for (long value : tag.getLongArray(PLAYER_PLACED_KEY)) {
            tracker.playerPlaced.add(value);
        }
        return tracker;
    }

    public void markPlayerPlaced(BlockPos pos) {
        if (playerPlaced.add(pos.asLong())) {
            setDirty();
        }
    }

    public void clearPlayerPlaced(BlockPos pos) {
        if (playerPlaced.remove(pos.asLong())) {
            setDirty();
        }
    }

    public boolean isPlayerPlaced(BlockPos pos) {
        return playerPlaced.contains(pos.asLong());
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLongArray(PLAYER_PLACED_KEY, playerPlaced.toLongArray());
        return tag;
    }
}
