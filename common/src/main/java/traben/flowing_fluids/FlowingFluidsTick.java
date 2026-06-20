package traben.flowing_fluids;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import traben.flowing_fluids.drying.DryingEventSystem;
import traben.flowing_fluids.flood.FloodEventSystem;
import traben.flowing_fluids.optimization.HierarchicalDistanceManager;
import traben.flowing_fluids.performance.FluidPerformanceMonitor;
import traben.flowing_fluids.performance.FluidTickWorkloadGovernor;
import traben.flowing_fluids.rain.RainWaterSystem;
import traben.flowing_fluids.snow.SnowmeltWaterSystem;
import traben.flowing_fluids.util.DimensionKey;
import traben.flowing_fluids.water.WaterPressureSystem;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FlowingFluidsTick {
    private static final int CHUNK_INITIALIZATION_BUDGET_PER_TICK = 1;
    private static final int FRONTIER_REBUILD_BUDGET_PER_TICK = 1;
    private static final int MAX_CHUNK_INITIALIZATION_BUDGET_PER_TICK = 4;
    private static final int MAX_FRONTIER_REBUILD_BUDGET_PER_TICK = 6;
    private static final long MAINTENANCE_INTERVAL_TICKS = 200L;
    private static final ConcurrentHashMap<DimensionKey, Long> lastMaintenanceTick = new ConcurrentHashMap<>();
    private static final Set<DimensionKey> disabledDimensionsCleared = ConcurrentHashMap.newKeySet();

    private FlowingFluidsTick() {
    }

    public static void onLevelTick(ServerLevel level) {
        DimensionKey key = DimensionKey.of(level);
        if (!FlowingFluids.config.enableMod) {
            if (disabledDimensionsCleared.add(key)) {
                clearRuntimeState(level);
            }
            return;
        }
        disabledDimensionsCleared.remove(key);
        FloodEventSystem.onLevelTick(level);
        RainWaterSystem.onLevelTick(level);
        DryingEventSystem.onLevelTick(level);
        SnowmeltWaterSystem.onLevelTick(level);

        FluidPerformanceMonitor monitor = FluidPerformanceMonitor.getInstance();
        double mspt = monitor.getLoadControlMspt(level.getServer().getAverageTickTime());
        int pendingChunkInitializations = FluidSpatialGrid.getPendingChunkInitializationCount(level);
        if (pendingChunkInitializations > 0) {
            FluidSpatialGrid.processPendingChunkInitializations(level,
                computeBacklogDrainBudget(pendingChunkInitializations, mspt,
                    CHUNK_INITIALIZATION_BUDGET_PER_TICK, MAX_CHUNK_INITIALIZATION_BUDGET_PER_TICK));
        }
        ParallelFluidTickManager.flushQueuedActiveWakeTicks(level);
        ParallelFluidTickManager.flushQueuedDistantStableTicks(level);
        if (FluidTickBuffer.hasPendingChanges(level)) {
            FluidTickBuffer.applyAll(level);
        }
        if (ParallelFluidEqualizer.hasQueued(level) && ParallelFluidEqualizer.flush(level) > 0
                && FluidTickBuffer.hasPendingChanges(level)) {
            FluidTickBuffer.applyAll(level);
        }
        FluidComponentGraph.processPending(level);
        int pendingFrontierRebuilds = FluidSpatialGrid.getPendingFrontierRebuildCount(level);
        if (pendingFrontierRebuilds > 0) {
            FluidSpatialGrid.processPendingFrontierRebuilds(level,
                computeBacklogDrainBudget(pendingFrontierRebuilds, mspt,
                    FRONTIER_REBUILD_BUDGET_PER_TICK, MAX_FRONTIER_REBUILD_BUDGET_PER_TICK));
        }
        monitor.recordTickBacklog(
            FluidSpatialGrid.getPendingChunkInitializationCount(level),
            FluidSpatialGrid.getPendingFrontierRebuildCount(level),
            ParallelFluidTickManager.getQueuedActiveWakeTickCount(level),
            ParallelFluidTickManager.getQueuedDistantStableTickCount(level),
            FluidTickBuffer.getBufferedChangeCount()
        );

        long now = level.getGameTime();
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

    static int computeBacklogDrainBudget(int pending, double mspt, int baseBudget, int maxBudget) {
        if (pending <= 0 || maxBudget <= 0) {
            return 0;
        }
        int budget = Math.max(1, baseBudget);
        if (pending >= 256) {
            budget = Math.max(budget, maxBudget);
        } else if (pending >= 96) {
            budget = Math.max(budget, Math.min(maxBudget, baseBudget + 3));
        } else if (pending >= 32) {
            budget = Math.max(budget, Math.min(maxBudget, baseBudget + 2));
        } else if (pending >= 8) {
            budget = Math.max(budget, Math.min(maxBudget, baseBudget + 1));
        }

        if (mspt >= 80.0) {
            budget = Math.min(budget, Math.max(1, baseBudget + 1));
        } else if (mspt >= 50.0) {
            budget = Math.min(budget, Math.max(1, baseBudget + 2));
        }

        return Math.min(Math.min(maxBudget, pending), budget);
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
        DimensionKey key = DimensionKey.of(level);
        disabledDimensionsCleared.remove(key);
        clearRuntimeState(level);
    }

    private static void clearRuntimeState(ServerLevel level) {
        lastMaintenanceTick.remove(DimensionKey.of(level));
        FloodEventSystem.onLevelUnload(level);
        RainWaterSystem.onLevelUnload(level);
        DryingEventSystem.onLevelUnload(level);
        SnowmeltWaterSystem.onLevelUnload(level);
        WaterPressureSystem.onLevelUnload(level);
        AsyncSlopeSearchPlanner.clearDimension(level);
        ParallelFluidEqualizer.clearDimension(level);
        ParallelFluidTickManager.clearDimension(level);
        SiphonFlowSystem.clearDimension(level);
        FluidTickBuffer.clearDimension(level);
        FluidComponentGraph.clearDimension(level);
        ExtendedWaterlogStore.clearDimension(level);
        FluidActivityTracker.clearDimension(level);
        FluidTickWorkloadGovernor.clearDimension(level);
        HierarchicalDistanceManager.getInstance().clearDimension(level);
    }
}
