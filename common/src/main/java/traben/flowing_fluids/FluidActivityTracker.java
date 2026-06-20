package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import traben.flowing_fluids.util.DimensionKey;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks recent fluid activity per chunk and uses it to reduce costly slope searches
 * during heavy, overlapping flows.
 */
public final class FluidActivityTracker {
    private static final int ACTIVITY_REDUCTION_START = 32;
    private static final int ACTIVITY_REDUCTION_MAX = 256;
    private static final float MAX_SLOPE_REDUCTION = 0.6f;
    private static final int MIN_SLOPE_DISTANCE = 2;

    private static final float DECAY_PER_TICK = 0.88f;
    private static final float MAX_ACTIVITY = 512.0f;
    private static final long STALE_TICKS = 20L * 30L;
    private static final int MAX_TRACKED_CHUNKS = 4096;

    private static final ConcurrentHashMap<DimensionKey, ConcurrentHashMap<ChunkPos, ActivityData>> DATA =
        new ConcurrentHashMap<>();

    private FluidActivityTracker() {
    }

    public static void recordChanges(Level level, Collection<BlockPos> positions) {
        if (level == null || positions == null || positions.isEmpty()) {
            return;
        }

        long nowTick = level.getGameTime();
        DimensionKey key = DimensionKey.of(level);
        ConcurrentHashMap<ChunkPos, ActivityData> map = DATA.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        Long2IntOpenHashMap counts = new Long2IntOpenHashMap();

        for (BlockPos pos : positions) {
            counts.addTo(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), 1);
        }

        for (Long2IntMap.Entry entry : counts.long2IntEntrySet()) {
            long chunkKey = entry.getLongKey();
            ChunkPos chunkPos = new ChunkPos(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            int count = entry.getIntValue();
            ActivityData data = map.computeIfAbsent(chunkPos, k -> new ActivityData(nowTick));
            data.record(nowTick, count);
        }

        if (map.size() > MAX_TRACKED_CHUNKS) {
            cleanupStale(map, nowTick);
        }
    }

    public static int getAdaptiveSlopeFindDistance(Level level, BlockPos pos, int baseDistance) {
        if (level == null || baseDistance <= MIN_SLOPE_DISTANCE) {
            return baseDistance;
        }

        float activity = getActivityScore(level, new ChunkPos(pos), level.getGameTime());
        if (activity <= ACTIVITY_REDUCTION_START) {
            return baseDistance;
        }

        float t = (activity - ACTIVITY_REDUCTION_START)
            / (float) (ACTIVITY_REDUCTION_MAX - ACTIVITY_REDUCTION_START);
        if (t < 0.0f) {
            t = 0.0f;
        } else if (t > 1.0f) {
            t = 1.0f;
        }

        float scale = 1.0f - (MAX_SLOPE_REDUCTION * t);
        int reduced = Math.round(baseDistance * scale);
        return Math.max(MIN_SLOPE_DISTANCE, Math.min(baseDistance, reduced));
    }

    public static void clearChunk(LevelAccessor level, ChunkPos chunkPos) {
        if (level == null || chunkPos == null) {
            return;
        }

        DimensionKey key = DimensionKey.of(level);
        ConcurrentHashMap<ChunkPos, ActivityData> map = DATA.get(key);
        if (map == null) {
            return;
        }
        map.remove(chunkPos);
        if (map.isEmpty()) {
            DATA.remove(key, map);
        }
    }

    public static void clearDimension(LevelAccessor level) {
        if (level == null) {
            return;
        }
        DATA.remove(DimensionKey.of(level));
    }

    public static void clearAll() {
        DATA.clear();
    }

    private static float getActivityScore(Level level, ChunkPos chunkPos, long nowTick) {
        ConcurrentHashMap<ChunkPos, ActivityData> map = DATA.get(DimensionKey.of(level));
        if (map == null) {
            return 0.0f;
        }

        ActivityData data = map.get(chunkPos);
        if (data == null) {
            return 0.0f;
        }

        return data.getSmoothed(nowTick);
    }

    private static void cleanupStale(ConcurrentHashMap<ChunkPos, ActivityData> map, long nowTick) {
        map.entrySet().removeIf(entry -> entry.getValue().isStale(nowTick));
    }

    private static final class ActivityData {
        private volatile long lastTick;
        private volatile float smoothed;

        private ActivityData(long nowTick) {
            this.lastTick = nowTick;
            this.smoothed = 0.0f;
        }

        private void record(long nowTick, int count) {
            float current = getSmoothed(nowTick);
            float updated = Math.min(MAX_ACTIVITY, current + count);
            this.smoothed = updated;
            this.lastTick = nowTick;
        }

        private float getSmoothed(long nowTick) {
            long delta = nowTick - lastTick;
            if (delta <= 0) {
                return smoothed;
            }
            float decay = (float) Math.pow(DECAY_PER_TICK, delta);
            return smoothed * decay;
        }

        private boolean isStale(long nowTick) {
            return nowTick - lastTick > STALE_TICKS;
        }
    }
}
