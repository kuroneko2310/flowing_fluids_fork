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
    private static final int CHUNK_INITIALIZATION_BUDGET_PER_TICK = 1;
    private static final int FRONTIER_REBUILD_BUDGET_PER_TICK = 1;
    private static final long MAINTENANCE_INTERVAL_TICKS = 200L;
    private static final ConcurrentHashMap<DimensionKey, Long> lastMaintenanceTick = new ConcurrentHashMap<>();

    private FlowingFluidsTick() {
    }

    public static void onLevelTick(ServerLevel level) {
        if (!FlowingFluids.config.enableMod) {
            DryingEventSystem.onLevelUnload(level);
            ParallelFluidEqualizer.clearDimension(level);
            ParallelFluidTickManager.clearDimension(level);
            SiphonFlowSystem.clearDimension(level);
            FluidTickBuffer.clearDimension(level);
            FluidComponentGraph.clearDimension(level);
            ExtendedWaterlogStore.clearDimension(level);
            HierarchicalDistanceManager.getInstance().clearDimension(level);
            return;
        }
        DryingEventSystem.onLevelTick(level);
        SnowmeltWaterSystem.onLevelTick(level);
        if (FluidSpatialGrid.hasPendingChunkInitializations(level)) {
            FluidSpatialGrid.processPendingChunkInitializations(level, CHUNK_INITIALIZATION_BUDGET_PER_TICK);
        }
        ParallelFluidTickManager.flushQueuedDistantStableTicks(level);
        if (FluidTickBuffer.hasPendingChanges(level)) {
            FluidTickBuffer.applyAll(level);
        }
        if (ParallelFluidEqualizer.hasQueued(level) && ParallelFluidEqualizer.flush(level) > 0
                && FluidTickBuffer.hasPendingChanges(level)) {
            FluidTickBuffer.applyAll(level);
        }
        FluidComponentGraph.processPending(level);
        if (FluidSpatialGrid.hasPendingFrontierRebuilds(level)) {
            FluidSpatialGrid.processPendingFrontierRebuilds(level, FRONTIER_REBUILD_BUDGET_PER_TICK);
        }

        long now = level.getGameTime();
        DimensionKey key = DimensionKey.of(level);
        long last = lastMaintenanceTick.getOrDefault(key, Long.MIN_VALUE);
        if (last == Long.MIN_VALUE || now - last >= MAINTENANCE_INTERVAL_TICKS) {
            boolean hasSchedulerData = AdaptiveTickScheduler.hasDimensionData(level);
            boolean hasSpatialData = FluidSpatialGrid.hasDimensionStorage(level);
            if (hasSchedulerData) {
                AdaptiveTickScheduler.performMaintenance(level);
            }
            if (hasSpatialData) {
                FluidSpatialGrid.performMaintenance(level);
            }
            if (hasSchedulerData || hasSpatialData) {
                lastMaintenanceTick.put(key, now);
            } else {
                lastMaintenanceTick.remove(key);
            }
        }
    }

    public static void onChunkLoad(ServerLevel level, ChunkPos chunkPos) {
        if (!FlowingFluids.config.enableMod) {
            return;
        }
        if (FlowingFluids.config.enableExtendedWaterlogging) {
            ExtendedWaterlogStore.loadChunk(level, chunkPos);
        }
    }

    public static void onChunkUnload(LevelAccessor level, ChunkPos chunkPos) {
        AdaptiveTickScheduler.clearChunk(level, chunkPos);
        ParallelFluidEqualizer.clearChunk(level, chunkPos);
        if (level instanceof ServerLevel serverLevel) {
            SiphonFlowSystem.clearChunk(serverLevel, chunkPos);
        }
        if (level instanceof ServerLevel serverLevel) {
            AsyncSlopeSearchPlanner.clearChunk(serverLevel, chunkPos);
        }
        FluidSpatialGrid.clearChunk(level, chunkPos);
        ChunkLocalSlopeCache.clearChunk(level, chunkPos);
        FluidActivityTracker.clearChunk(level, chunkPos);
        if (level instanceof ServerLevel serverLevel) {
            HierarchicalDistanceManager.getInstance().clearChunk(serverLevel, chunkPos);
        }
        FluidComponentGraph.clearChunk(level, chunkPos);
        ExtendedWaterlogStore.clearChunk(level, chunkPos);
    }

    public static void onLevelUnload(ServerLevel level) {
        lastMaintenanceTick.remove(DimensionKey.of(level));
        DryingEventSystem.onLevelUnload(level);
        SnowmeltWaterSystem.onLevelUnload(level);
        AsyncSlopeSearchPlanner.clearDimension(level);
        ParallelFluidEqualizer.clearDimension(level);
        ParallelFluidTickManager.clearDimension(level);
        SiphonFlowSystem.clearDimension(level);
        FluidTickBuffer.clearDimension(level);
        FluidComponentGraph.clearDimension(level);
        ExtendedWaterlogStore.clearDimension(level);
        FluidActivityTracker.clearDimension(level);
        HierarchicalDistanceManager.getInstance().clearDimension(level);
    }
}
