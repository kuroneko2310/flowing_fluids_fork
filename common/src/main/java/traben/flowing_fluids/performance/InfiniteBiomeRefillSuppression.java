package traben.flowing_fluids.performance;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import traben.flowing_fluids.util.DimensionKey;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class InfiniteBiomeRefillSuppression {
    private static final int EXPIRY_TICKS = 20;
    private static final int CLEANUP_INTERVAL_TICKS = 20;

    private static final Map<DimensionKey, Long2IntOpenHashMap> RADII = new HashMap<>();
    private static final Map<DimensionKey, Long2LongOpenHashMap> EXPIRES_AT = new HashMap<>();
    private static final Map<DimensionKey, Long> NEXT_CLEANUP_TICK = new HashMap<>();

    private InfiniteBiomeRefillSuppression() {
    }

    public static void register(ServerLevel level, BlockPos pos, int radius) {
        if (level == null || pos == null || radius <= 0) {
            return;
        }
        DimensionKey key = DimensionKey.of(level);
        long packedPos = pos.asLong();
        RADII.computeIfAbsent(key, ignored -> new Long2IntOpenHashMap()).put(packedPos, radius);
        EXPIRES_AT.computeIfAbsent(key, ignored -> new Long2LongOpenHashMap()).put(packedPos, level.getGameTime() + EXPIRY_TICKS);
    }

    public static boolean isSuppressed(LevelAccessor levelAccessor, BlockPos pos) {
        if (!(levelAccessor instanceof ServerLevel level) || pos == null) {
            return false;
        }

        DimensionKey key = DimensionKey.of(level);
        Long2IntOpenHashMap radii = RADII.get(key);
        Long2LongOpenHashMap expiresAt = EXPIRES_AT.get(key);
        if (radii == null || radii.isEmpty() || expiresAt == null) {
            return false;
        }

        long now = level.getGameTime();
        for (long suppressorPos : radii.keySet()) {
            if (expiresAt.get(suppressorPos) < now) {
                continue;
            }
            int radius = radii.get(suppressorPos);
            if (distSqr(suppressorPos, pos) <= (double) radius * radius) {
                return true;
            }
        }
        return false;
    }

    public static void onLevelTick(ServerLevel level) {
        if (level == null) {
            return;
        }
        DimensionKey key = DimensionKey.of(level);
        Long2IntOpenHashMap radii = RADII.get(key);
        Long2LongOpenHashMap expiresAt = EXPIRES_AT.get(key);
        if (radii == null || expiresAt == null) {
            return;
        }

        long now = level.getGameTime();
        long nextCleanup = NEXT_CLEANUP_TICK.getOrDefault(key, Long.MIN_VALUE);
        if (nextCleanup != Long.MIN_VALUE && now < nextCleanup) {
            return;
        }
        NEXT_CLEANUP_TICK.put(key, now + CLEANUP_INTERVAL_TICKS);
        Iterator<Long2LongMap.Entry> iterator = expiresAt.long2LongEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Long2LongMap.Entry entry = iterator.next();
            if (entry.getLongValue() < now) {
                radii.remove(entry.getLongKey());
                iterator.remove();
            }
        }
        if (radii.isEmpty()) {
            RADII.remove(key);
            EXPIRES_AT.remove(key);
            NEXT_CLEANUP_TICK.remove(key);
        }
    }

    public static void onLevelUnload(ServerLevel level) {
        if (level == null) {
            return;
        }
        DimensionKey key = DimensionKey.of(level);
        RADII.remove(key);
        EXPIRES_AT.remove(key);
        NEXT_CLEANUP_TICK.remove(key);
    }

    public static void clearAll() {
        RADII.clear();
        EXPIRES_AT.clear();
        NEXT_CLEANUP_TICK.clear();
    }

    private static double distSqr(long packedPos, BlockPos pos) {
        double dx = BlockPos.getX(packedPos) - pos.getX();
        double dy = BlockPos.getY(packedPos) - pos.getY();
        double dz = BlockPos.getZ(packedPos) - pos.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
