package traben.flowing_fluids;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import traben.flowing_fluids.drying.DryingEventSystem;
import traben.flowing_fluids.optimization.HierarchicalDistanceManager;
import traben.flowing_fluids.snow.SnowmeltWaterSystem;
import traben.flowing_fluids.util.DimensionKey;

import java.util.concurrent.ConcurrentHashMap;

public final class FlowingFluidsTick {
    private static final long MAINTENANCE_INTERVAL_TICKS = 200L;
    private static final ConcurrentHashMap<DimensionKey, Long> lastMaintenanceTick = new ConcurrentHashMap<>();

    private FlowingFluidsTick() {
    }

    public static void onLevelTick(ServerLevel level) {
        if (!FlowingFluids.config.enableMod) {
            DryingEventSystem.onLevelUnload(level);
            ParallelFluidEqualizer.clearDimension(level);
            ParallelFluidTickManager.clearDimension(level);
            FluidTickBuffer.clearDimension(level);
            ExtendedWaterlogStore.clearDimension(level);
            HierarchicalDistanceManager.getInstance().clearDimension(level);
            return;
        }
        DryingEventSystem.onLevelTick(level);
        SnowmeltWaterSystem.onLevelTick(level);
        ParallelFluidTickManager.flushQueuedDistantStableTicks(level);
        ParallelFluidEqualizer.flush(level);
        FluidTickBuffer.applyAll(level);

        long now = level.getGameTime();
        DimensionKey key = DimensionKey.of(level);
        long last = lastMaintenanceTick.getOrDefault(key, Long.MIN_VALUE);
        if (last == Long.MIN_VALUE || now - last >= MAINTENANCE_INTERVAL_TICKS) {
            AdaptiveTickScheduler.performMaintenance(level);
            FluidSpatialGrid.performMaintenance(level);
            lastMaintenanceTick.put(key, now);
        }
    }

    public static void onChunkLoad(ServerLevel level, ChunkPos chunkPos) {
        if (!FlowingFluids.config.enableMod) {
            return;
        }
        FluidSpatialGrid.initializeChunk(level, chunkPos);
    }

    public static void onChunkUnload(LevelAccessor level, ChunkPos chunkPos) {
        AdaptiveTickScheduler.clearChunk(level, chunkPos);
        FluidSpatialGrid.clearChunk(level, chunkPos);
        ChunkLocalSlopeCache.clearChunk(level, chunkPos);
        FluidActivityTracker.clearChunk(level, chunkPos);
        if (level instanceof ServerLevel serverLevel) {
            HierarchicalDistanceManager.getInstance().clearChunk(serverLevel, chunkPos);
        }
        ExtendedWaterlogStore.clearChunk(level, chunkPos);
    }

    public static void onLevelUnload(ServerLevel level) {
        lastMaintenanceTick.remove(DimensionKey.of(level));
        DryingEventSystem.onLevelUnload(level);
        SnowmeltWaterSystem.onLevelUnload(level);
        ParallelFluidEqualizer.clearDimension(level);
        ParallelFluidTickManager.clearDimension(level);
        ExtendedWaterlogStore.clearDimension(level);
        FluidActivityTracker.clearDimension(level);
        HierarchicalDistanceManager.getInstance().clearDimension(level);
    }
}
