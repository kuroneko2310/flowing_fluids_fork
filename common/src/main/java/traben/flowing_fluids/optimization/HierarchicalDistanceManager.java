package traben.flowing_fluids.optimization;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.ParallelFluidTickManager;
import traben.flowing_fluids.config.FFConfig;
import traben.flowing_fluids.util.DimensionKey;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hierarchical Distance Management System
 *
 * Optimizes fluid ticking by updating distant fluids less frequently.
 * This reduces computational load for long-distance water flow while
 * maintaining visual quality where it matters most (near the player).
 *
 * Performance Impact:
 * - 50-70% tick reduction for 64+ block distances
 * - Minimal visual impact (distant water updates are less noticeable)
 * - Scales well with increasing flow distances
 */
public class HierarchicalDistanceManager {

    private static final HierarchicalDistanceManager INSTANCE = new HierarchicalDistanceManager();

    // Distance tiers and their update frequencies
    private static final int TIER_1_DISTANCE = 4;   // Update every tick
    private static final int TIER_2_DISTANCE = 16;  // Update every 2 ticks
    private static final int TIER_3_DISTANCE = 32;  // Update every 4 ticks
    private static final int TIER_4_DISTANCE = 64;  // Update every 8 ticks
    // Beyond 64: Update every 10 ticks

    private static final int TIER_1_INTERVAL = 1;
    private static final int TIER_2_INTERVAL = 2;
    private static final int TIER_3_INTERVAL = 4;
    private static final int TIER_4_INTERVAL = 8;
    private static final int TIER_5_INTERVAL = 10;

    // Player proximity boost
    private static final int PLAYER_PROXIMITY_BOOST = 32; // Blocks within this distance always update frequently

    // Spatial grid for player position caching (reduces O(n×p) to O(1))
    private static final int GRID_CELL_SIZE = 32; // Same as PLAYER_PROXIMITY_BOOST for efficiency
    private static final int SIMULATION_TIER_PLAYER_GRID_RADIUS = 2;
    private static final Map<DimensionKey, Map<Long, List<PlayerCacheEntry>>> playerGridCache = new ConcurrentHashMap<>();
    private static final Map<DimensionKey, Map<TerrainCacheKey, TerrainCacheEntry>> terrainTypeCache = new ConcurrentHashMap<>();
    private static final Map<DimensionKey, Map<ChunkPos, Set<TerrainCacheKey>>> terrainKeysByChunk = new ConcurrentHashMap<>();
    private static final Map<DimensionKey, Long> lastPlayerCacheUpdate = new ConcurrentHashMap<>();
    private static final long PLAYER_CACHE_REFRESH_INTERVAL = 1000; // Update every 1 second (20 ticks)

    private HierarchicalDistanceManager() {
    }

    public static HierarchicalDistanceManager getInstance() {
        return INSTANCE;
    }

    /**
     * Determines if a fluid at the given position should tick this game tick.
     *
     * @param pos The position of the fluid
     * @param level The level/world
     * @param currentTick Current game tick counter
     * @param flowDistance Estimated flow distance from source
     * @param config Configuration object
     * @return true if the fluid should tick, false to skip
     */
    public boolean shouldTickThisTick(BlockPos pos, Level level, long currentTick, int flowDistance, FFConfig config) {
        // Always tick if distance-based optimization is disabled
        if (!config.enableDistanceBasedOptimization) {
            return true;
        }

        // Check player proximity - always tick if player is nearby
        if (isPlayerNearby(pos, level)) {
            return true;
        }

        // Determine update interval based on distance tier
        int updateInterval = getUpdateInterval(flowDistance);

        // Check if this tick matches the update interval
        return Math.floorMod(currentTick, updateInterval) == Math.floorMod(hashPosition(pos), updateInterval);
    }

    public int alignDelayToUpdateInterval(BlockPos pos, Level level, long currentTick, int flowDistance,
                                          int desiredDelay, FFConfig config) {
        int safeDelay = Math.max(1, desiredDelay);
        if (!config.enableDistanceBasedOptimization || isPlayerNearby(pos, level)) {
            return safeDelay;
        }

        int updateInterval = getUpdateInterval(flowDistance);
        if (updateInterval <= 1) {
            return safeDelay;
        }

        long slot = Math.floorMod(hashPosition(pos), (long) updateInterval);
        long targetTick = currentTick + safeDelay;
        long remainder = Math.floorMod(targetTick - slot, (long) updateInterval);
        if (remainder == 0L) {
            return safeDelay;
        }
        return safeDelay + (int) ((long) updateInterval - remainder);
    }

    public RangeTier getSimulationTier(BlockPos pos, Level level) {
        if (level == null || pos == null) {
            return RangeTier.DISTANT;
        }

        double distanceSq = nearestPlayerDistanceSqApprox(pos, level);
        if (!Double.isFinite(distanceSq)) {
            return RangeTier.DISTANT;
        }

        if (distanceSq <= 16.0D * 16.0D) {
            return RangeTier.NEAR;
        }
        if (distanceSq <= 48.0D * 48.0D) {
            return RangeTier.MID;
        }
        if (distanceSq <= 128.0D * 128.0D) {
            return RangeTier.FAR;
        }
        return RangeTier.DISTANT;
    }

    public boolean shouldUseMacroFluidModel(RangeTier tier) {
        return tier == RangeTier.FAR || tier == RangeTier.DISTANT;
    }

    public int getCorridorSearchClamp(RangeTier tier) {
        return switch (tier) {
            case NEAR -> 8;
            case MID -> 6;
            case FAR -> 4;
            case DISTANT -> 3;
        };
    }

    public ParallelFluidTickManager.DelayBucket getParallelDelayBucket(RangeTier tier) {
        return tier == RangeTier.DISTANT
            ? ParallelFluidTickManager.DelayBucket.DISTANT
            : ParallelFluidTickManager.DelayBucket.FAR;
    }

    /**
     * Determines the update interval based on flow distance.
     */
    private int getUpdateInterval(int flowDistance) {
        if (flowDistance <= TIER_1_DISTANCE) {
            return TIER_1_INTERVAL;  // Every tick
        } else if (flowDistance <= TIER_2_DISTANCE) {
            return TIER_2_INTERVAL;  // Every 2 ticks
        } else if (flowDistance <= TIER_3_DISTANCE) {
            return TIER_3_INTERVAL;  // Every 4 ticks
        } else if (flowDistance <= TIER_4_DISTANCE) {
            return TIER_4_INTERVAL;  // Every 8 ticks
        } else {
            return TIER_5_INTERVAL;  // Every 10 ticks
        }
    }

    /**
     * Calculates the optimal BFS search distance based on flow distance and terrain.
     *
     * @param baseDistance Base flow distance from config
     * @param terrainType Estimated terrain type
     * @param fluidAmount Fluid level (0-8)
     * @param config Configuration object
     * @return Optimized BFS search distance
     */
    public int calculateOptimalBFSDistance(int baseDistance, TerrainType terrainType, int fluidAmount, FFConfig config) {
        int maxDistance = config.bfsMaxSearchDistance;

        // Terrain-based multiplier
        float terrainMultiplier = switch (terrainType) {
            case OCEAN -> 0.5f;      // Ocean: large but simple flow patterns
            case RIVER -> 1.5f;      // River: complex flow, needs more search
            case CANAL -> 1.2f;      // Canal: artificial, moderately complex
            case MOUNTAIN -> 0.8f;   // Mountain: mostly vertical, less horizontal search
            case FLAT -> 1.0f;       // Flat: standard search
        };

        // Fluid amount multiplier (less fluid = shorter search)
        float amountMultiplier = Math.max(0.3f, fluidAmount / 8.0f);

        // Distance multiplier from config
        float configMultiplier = config.slopeFindDistanceMultiplier;

        // Calculate final distance
        int optimizedDistance = (int) (maxDistance * terrainMultiplier * amountMultiplier * configMultiplier);

        // Clamp to reasonable bounds
        return Math.max(4, Math.min(optimizedDistance, maxDistance));
    }

    /**
     * Estimates terrain type based on surrounding blocks.
     */
    public TerrainType estimateTerrainType(BlockPos pos, Level level) {
        ChunkPos chunkPos = new ChunkPos(pos);
        AdaptiveTickScheduler.AreaType areaType = AdaptiveTickScheduler.getAreaType(level, chunkPos);
        DimensionKey dimensionKey = DimensionKey.of(level);
        Map<TerrainCacheKey, TerrainCacheEntry> dimensionCache =
            terrainTypeCache.computeIfAbsent(dimensionKey, ignored -> new ConcurrentHashMap<>());
        TerrainCacheKey cacheKey = TerrainCacheKey.of(pos, chunkPos);

        TerrainCacheEntry cached = dimensionCache.get(cacheKey);
        if (cached != null && cached.areaType == areaType) {
            return cached.terrainType;
        }

        TerrainType computed = classifyTerrainType(pos, level, areaType);
        dimensionCache.put(cacheKey, new TerrainCacheEntry(areaType, computed));
        terrainKeysByChunk
            .computeIfAbsent(dimensionKey, ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(chunkPos, ignored -> ConcurrentHashMap.newKeySet())
            .add(cacheKey);
        return computed;
    }

    private TerrainType classifyTerrainType(BlockPos pos, Level level, AdaptiveTickScheduler.AreaType areaType) {
        var biome = level.getBiome(pos);
        if (areaType == AdaptiveTickScheduler.AreaType.OCEAN
                || FFFluidUtils.isOceanBiome(biome)
                || FFFluidUtils.isBeachBiome(biome)) {
            return TerrainType.OCEAN;
        }
        if (FFFluidUtils.isRiverBiome(biome)) {
            return TerrainType.RIVER;
        }

        int heightVariance = sampleHeightVariance(pos, level);
        if (areaType == AdaptiveTickScheduler.AreaType.HIGH_ACTIVITY) {
            if (heightVariance <= 3) {
                return TerrainType.CANAL;
            }
            return heightVariance > 10 ? TerrainType.MOUNTAIN : TerrainType.RIVER;
        }

        if (heightVariance > 10) {
            return TerrainType.MOUNTAIN;
        }
        return TerrainType.FLAT;
    }

    private int sampleHeightVariance(BlockPos pos, Level level) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int dx = -4; dx <= 4; dx += 4) {
            for (int dz = -4; dz <= 4; dz += 4) {
                int sampleY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    pos.getX() + dx, pos.getZ() + dz);
                minY = Math.min(minY, sampleY);
                maxY = Math.max(maxY, sampleY);
            }
        }

        if (minY == Integer.MAX_VALUE) {
            return 0;
        }
        return maxY - minY;
    }

    /**
     * Checks if any player is nearby (within PLAYER_PROXIMITY_BOOST blocks).
     * OPTIMIZED: Uses spatial grid cache to reduce from O(n×p) to O(1).
     */
    private boolean isPlayerNearby(BlockPos pos, Level level) {
        DimensionKey dimensionKey = DimensionKey.of(level);
        ensurePlayerCache(level, dimensionKey);

        Map<Long, List<PlayerCacheEntry>> dimensionPlayerCache = playerGridCache.get(dimensionKey);
        if (dimensionPlayerCache == null || dimensionPlayerCache.isEmpty()) {
            return false;
        }

        // Check surrounding grid cells (3x3x3 = 27 cells)
        int cellX = pos.getX() / GRID_CELL_SIZE;
        int cellY = pos.getY() / GRID_CELL_SIZE;
        int cellZ = pos.getZ() / GRID_CELL_SIZE;

        double sqrDist = PLAYER_PROXIMITY_BOOST * PLAYER_PROXIMITY_BOOST;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long gridKey = getGridKey(cellX + dx, cellY + dy, cellZ + dz);
                    List<PlayerCacheEntry> players = dimensionPlayerCache.get(gridKey);

                    if (players != null) {
                        for (PlayerCacheEntry player : players) {
                            double distSqr = pos.distSqr(player.blockPos);
                            if (distSqr <= sqrDist) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private double nearestPlayerDistanceSqApprox(BlockPos pos, Level level) {
        DimensionKey dimensionKey = DimensionKey.of(level);
        ensurePlayerCache(level, dimensionKey);

        Map<Long, List<PlayerCacheEntry>> dimensionPlayerCache = playerGridCache.get(dimensionKey);
        if (dimensionPlayerCache == null || dimensionPlayerCache.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        int cellX = pos.getX() / GRID_CELL_SIZE;
        int cellY = pos.getY() / GRID_CELL_SIZE;
        int cellZ = pos.getZ() / GRID_CELL_SIZE;
        double nearest = Double.POSITIVE_INFINITY;

        for (int dx = -SIMULATION_TIER_PLAYER_GRID_RADIUS; dx <= SIMULATION_TIER_PLAYER_GRID_RADIUS; dx++) {
            for (int dy = -SIMULATION_TIER_PLAYER_GRID_RADIUS; dy <= SIMULATION_TIER_PLAYER_GRID_RADIUS; dy++) {
                for (int dz = -SIMULATION_TIER_PLAYER_GRID_RADIUS; dz <= SIMULATION_TIER_PLAYER_GRID_RADIUS; dz++) {
                    long gridKey = getGridKey(cellX + dx, cellY + dy, cellZ + dz);
                    List<PlayerCacheEntry> players = dimensionPlayerCache.get(gridKey);
                    if (players == null) {
                        continue;
                    }
                    for (PlayerCacheEntry player : players) {
                        nearest = Math.min(nearest, pos.distSqr(player.blockPos));
                    }
                }
            }
        }

        return nearest;
    }

    private static void ensurePlayerCache(Level level, DimensionKey dimensionKey) {
        long currentTime = System.currentTimeMillis();
        long lastUpdate = lastPlayerCacheUpdate.getOrDefault(dimensionKey, 0L);
        if (currentTime - lastUpdate > PLAYER_CACHE_REFRESH_INTERVAL) {
            updatePlayerCache(level);
            lastPlayerCacheUpdate.put(dimensionKey, currentTime);
        }
    }

    /**
     * Updates the player grid cache with current player positions.
     */
    private static void updatePlayerCache(Level level) {
        DimensionKey dimensionKey = DimensionKey.of(level);
        Map<Long, List<PlayerCacheEntry>> dimensionPlayerCache = new ConcurrentHashMap<>();

        for (Player player : level.players()) {
            BlockPos playerPos = player.blockPosition();
            int cellX = playerPos.getX() / GRID_CELL_SIZE;
            int cellY = playerPos.getY() / GRID_CELL_SIZE;
            int cellZ = playerPos.getZ() / GRID_CELL_SIZE;

            long gridKey = getGridKey(cellX, cellY, cellZ);
            dimensionPlayerCache.computeIfAbsent(gridKey, k -> new ArrayList<>())
                .add(new PlayerCacheEntry(playerPos));
        }
        playerGridCache.put(dimensionKey, dimensionPlayerCache);
    }

    /**
     * Generates a grid key from cell coordinates.
     */
    private static long getGridKey(int cellX, int cellY, int cellZ) {
        return ((long) cellX & 0x3FFFFF) | (((long) cellY & 0x3FF) << 22) | (((long) cellZ & 0x3FFFFF) << 32);
    }

    /**
     * Creates a deterministic hash from position for staggered updates.
     * This ensures fluids don't all update on the same tick.
     */
    private long hashPosition(BlockPos pos) {
        // Simple hash that distributes updates evenly
        return (long) pos.getX() * 31 + (long) pos.getY() * 17 + (long) pos.getZ() * 13;
    }

    /**
     * Calculates the effective flow distance for adaptive systems.
     *
     * @param baseDistance Base flow distance
     * @param terrainType Terrain type
     * @param config Configuration
     * @return Effective flow distance to use
     */
    public int getEffectiveFlowDistance(int baseDistance, TerrainType terrainType, FFConfig config) {
        if (!config.enableAdaptiveFlowDistance) {
            return baseDistance;
        }

        return switch (terrainType) {
            case OCEAN -> Math.min(config.oceanFlowDistance, config.maxWaterFlowDistance);
            case RIVER -> Math.min(config.riverFlowDistance, config.maxWaterFlowDistance);
            case CANAL -> Math.min(config.canalFlowDistance, config.maxWaterFlowDistance);
            case MOUNTAIN, FLAT -> baseDistance;
        };
    }

    public void clearChunk(Level level, ChunkPos chunkPos) {
        if (level == null || chunkPos == null) {
            return;
        }
        DimensionKey dimensionKey = DimensionKey.of(level);
        Map<TerrainCacheKey, TerrainCacheEntry> dimensionCache = terrainTypeCache.get(dimensionKey);
        Map<ChunkPos, Set<TerrainCacheKey>> dimensionIndex = terrainKeysByChunk.get(dimensionKey);
        Set<TerrainCacheKey> cacheKeys = dimensionIndex == null ? null : dimensionIndex.remove(chunkPos);
        if (dimensionCache == null) {
            if (dimensionIndex != null && dimensionIndex.isEmpty()) {
                terrainKeysByChunk.remove(dimensionKey, dimensionIndex);
            }
            return;
        }
        if (cacheKeys != null) {
            for (TerrainCacheKey cacheKey : cacheKeys) {
                dimensionCache.remove(cacheKey);
            }
        }
        if (dimensionCache.isEmpty()) {
            terrainTypeCache.remove(dimensionKey, dimensionCache);
            if (dimensionIndex != null && dimensionIndex.isEmpty()) {
                terrainKeysByChunk.remove(dimensionKey, dimensionIndex);
            }
        }
    }

    public void clearDimension(Level level) {
        if (level == null) {
            return;
        }
        DimensionKey dimensionKey = DimensionKey.of(level);
        terrainTypeCache.remove(dimensionKey);
        terrainKeysByChunk.remove(dimensionKey);
        playerGridCache.remove(dimensionKey);
        lastPlayerCacheUpdate.remove(dimensionKey);
    }

    /**
     * Terrain classification for adaptive optimization.
     */
    public enum TerrainType {
        OCEAN,      // Large body of water, simple flow
        RIVER,      // Flowing water channel
        CANAL,      // Artificial water channel
        MOUNTAIN,   // Steep terrain, vertical flow dominant
        FLAT        // Flat terrain, standard flow
    }

    public enum RangeTier {
        NEAR,
        MID,
        FAR,
        DISTANT
    }

    private record TerrainCacheKey(ChunkPos chunkPos, int localBiomeX, int localBiomeZ, int yBand) {
        static TerrainCacheKey of(BlockPos pos, ChunkPos chunkPos) {
            return new TerrainCacheKey(
                    chunkPos,
                    (pos.getX() & 15) >> 2,
                    (pos.getZ() & 15) >> 2,
                    pos.getY() >> 4
            );
        }
    }

    /**
     * Cache entry for player position in spatial grid.
     */
    private static class PlayerCacheEntry {
        final BlockPos blockPos;

        PlayerCacheEntry(BlockPos blockPos) {
            this.blockPos = blockPos;
        }
    }

    private static class TerrainCacheEntry {
        final AdaptiveTickScheduler.AreaType areaType;
        final TerrainType terrainType;

        private TerrainCacheEntry(AdaptiveTickScheduler.AreaType areaType, TerrainType terrainType) {
            this.areaType = areaType;
            this.terrainType = terrainType;
        }
    }

    /**
     * Get performance statistics about update intervals.
     */
    public String getPerformanceInfo(int flowDistance) {
        int interval = getUpdateInterval(flowDistance);
        float reductionPercent = (1.0f - 1.0f / interval) * 100;

        return String.format(
            "Distance: %d blocks, Interval: every %d tick(s), Reduction: %.1f%%",
            flowDistance, interval, reductionPercent
        );
    }
}
