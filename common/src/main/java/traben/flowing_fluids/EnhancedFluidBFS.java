package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced BFS equalization algorithm with natural fluid behavior.
 *
 * Key improvements over vanilla:
 * 1. Includes air blocks in BFS (fixes "water doesn't reach distant channels" bug)
 * 2. Weighted search based on gradient vectors (flows downhill naturally)
 * 3. Dynamic depth adjustment based on terrain type
 * 4. Budget-controlled node exploration (prevents ocean lag)
 *
 * This is the core of the "Natural Hybrid Fluid" system.
 */
public class EnhancedFluidBFS {

    // Depth configurations for different terrain types
    private static final int DEPTH_GENTLE = 80;      // Gentle slopes
    private static final int DEPTH_CANAL = 200;      // Artificial channels
    private static final int DEPTH_RIVER = 300;      // Natural rivers
    private static final int DEPTH_OCEAN = 64;       // Large water bodies (ultra-light)

    // Direction cache to avoid repeated sorting (optimization)
    private static final int MAX_DIRECTION_CACHE_SIZE = 1000; // Prevent unbounded growth
    // LRU cache implementation using LinkedHashMap
    private static final Map<Vec3i, Direction[]> directionCache = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<Vec3i, Direction[]>(100, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Vec3i, Direction[]> eldest) {
                return size() > MAX_DIRECTION_CACHE_SIZE;
            }
        }
    );

    // Default direction order (null gradient)
    private static final Direction[] DEFAULT_DIRECTIONS = new Direction[]{
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    // Track positions currently being processed to prevent duplicate BFS runs
    private static final Set<Long> processingPositions = ConcurrentHashMap.newKeySet();
    private static long lastCleanupTime = System.currentTimeMillis();

    /**
     * Performs BFS equalization with all enhancements.
     * OPTIMIZED: Prevents duplicate BFS runs on overlapping water sources.
     *
     * @param level World level
     * @param startPos Starting position
     * @param maxDepth Maximum search depth (dynamic)
     * @param maxNodes Maximum nodes to explore (from BFS budget)
     * @return List of positions that need equalization
     */
    public static List<BlockPos> performEqualization(Level level, BlockPos startPos, int maxDepth, int maxNodes) {
        List<BlockPos> equalizedPositions = new ArrayList<>();

        // Get starting fluid state
        FluidState startFluid = level.getFluidState(startPos);
        if (startFluid.isEmpty()) {
            return equalizedPositions; // No fluid to equalize
        }

        // Check equilibrium index - skip if stable
        int startAmount = FluidSpatialGrid.getFluidAmount(level, startPos);
        if (!AdaptiveTickScheduler.shouldRunBFS(level, startPos, startAmount)) {
            return equalizedPositions; // Too stable, skip BFS
        }

        // OPTIMIZATION: Check if this position is already being processed
        long posKey = startPos.asLong();
        if (!processingPositions.add(posKey)) {
            return equalizedPositions; // Already being processed by another thread/source
        }

        // Cleanup old processing entries periodically (every 5 seconds)
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCleanupTime > 5000) {
            cleanupProcessingPositions();
            lastCleanupTime = currentTime;
        }

        try {
            // Initialize BFS
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            Set<Long> visited = new HashSet<>();

            queue.enqueue(startPos.asLong());
            visited.add(startPos.asLong());

            int nodesExplored = 0;
            ChunkPos chunkPos = new ChunkPos(startPos);

            // Get or estimate gradient vector for weighted search
            Vec3i gradientVector = ChunkLocalSlopeCache.getGradientVector(level, chunkPos, startPos);

            while (!queue.isEmpty() && nodesExplored < maxNodes && visited.size() < maxDepth) {
                long currentLong = queue.dequeueLong();
                BlockPos currentPos = BlockPos.of(currentLong);
                nodesExplored++;

                // Get current fluid amount
                FluidState currentFluid = level.getFluidState(currentPos);
                int currentAmount = FluidSpatialGrid.getFluidAmount(level, currentPos);

                // Explore neighbors with weighted priority
                Direction[] directions = getWeightedDirections(gradientVector);

                for (Direction dir : directions) {
                    BlockPos neighborPos = currentPos.relative(dir);
                    long neighborLong = neighborPos.asLong();

                    if (visited.contains(neighborLong)) {
                        continue;
                    }

                    // CRITICAL: Include air blocks and replaceable blocks!
                    if (canIncludeInBFS(level, neighborPos, startFluid)) {
                        visited.add(neighborLong);
                        queue.enqueue(neighborLong);

                        // Add to equalization list if it needs balancing
                        int neighborAmount = FluidSpatialGrid.getFluidAmount(level, neighborPos);
                        if (shouldEqualize(currentAmount, neighborAmount)) {
                            equalizedPositions.add(neighborPos.immutable());
                        }
                    }
                }
            }

            return equalizedPositions;
        } finally {
            // Always remove from processing set when done
            processingPositions.remove(posKey);
        }
    }

    /**
     * CRITICAL: Determines if a block should be included in BFS.
     * This includes AIR blocks, which fixes the "water doesn't flow to distant channels" bug!
     */
    private static boolean canIncludeInBFS(BlockGetter level, BlockPos pos, FluidState sourceFluid) {
        BlockState state = level.getBlockState(pos);
        FluidState fluidState = level.getFluidState(pos);

        // Include if:
        // 1. Has same fluid type
        if (!fluidState.isEmpty() && fluidState.getType() == sourceFluid.getType()) {
            return true;
        }

        // 2. Is air or replaceable (THIS IS KEY!)
        if (state.isAir() || state.canBeReplaced()) {
            return true;
        }

        // 3. Can accept fluid (waterloggable, etc.)
        if (state.getFluidState().isEmpty() && !state.isSolid()) {
            return true;
        }

        return false;
    }

    /**
     * Determines if two positions should equalize their fluid amounts.
     */
    private static boolean shouldEqualize(int amount1, int amount2) {
        // Equalize if difference is significant (> 2 internal units)
        return Math.abs(amount1 - amount2) > 2;
    }

    /**
     * Gets directions sorted by weight (gradient preference).
     * Returns directions in order: downslope, horizontal, upslope.
     *
     * OPTIMIZED: Uses LRU cache to avoid repeated sorting.
     * FIXED: Replaced cache thundering herd with proper LRU eviction.
     */
    private static Direction[] getWeightedDirections(Vec3i gradientVector) {
        if (gradientVector == null) {
            return DEFAULT_DIRECTIONS;
        }

        // Check cache first - LRU automatically handles size limit
        return directionCache.computeIfAbsent(gradientVector, gv -> {
            Direction[] directions = Direction.values().clone();

            // Sort by weight (lower = preferred)
            Arrays.sort(directions, (a, b) -> {
                float weightA = ChunkLocalSlopeCache.calculateDirectionWeight(gv, a);
                float weightB = ChunkLocalSlopeCache.calculateDirectionWeight(gv, b);
                return Float.compare(weightA, weightB);
            });

            return directions;
        });
    }

    /**
     * Determines dynamic depth based on terrain analysis.
     *
     * @param level World level
     * @param pos Position to analyze
     * @return Appropriate search depth
     */
    public static int getDynamicDepth(Level level, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);

        // Use area type from AdaptiveTickScheduler
        AdaptiveTickScheduler.AreaType areaType = getAreaType(level, chunkPos);

        return switch (areaType) {
            case OCEAN -> DEPTH_OCEAN;           // Large water: ultra-light
            case HIGH_ACTIVITY -> DEPTH_CANAL;   // Villages/canals: deep search
            default -> detectTerrainType(level, pos);
        };
    }

    /**
     * Detects terrain type at a position to determine appropriate depth.
     */
    private static int detectTerrainType(Level level, BlockPos pos) {
        // Sample surrounding blocks to determine terrain
        int fluidCount = 0;
        int totalBlocks = 0;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos samplePos = pos.offset(dx, dy, dz);
                    FluidState fluid = level.getFluidState(samplePos);

                    totalBlocks++;
                    if (!fluid.isEmpty()) {
                        fluidCount++;
                    }
                }
            }
        }

        float fluidDensity = (float) fluidCount / totalBlocks;

        // Classify terrain
        if (fluidDensity > 0.7f) {
            return DEPTH_RIVER; // Dense fluid = river
        } else if (fluidDensity > 0.3f) {
            return DEPTH_CANAL; // Medium density = canal
        } else {
            return DEPTH_GENTLE; // Low density = gentle slope
        }
    }

    /**
     * Gets area type for a chunk, with auto-detection if not set.
     */
    private static AdaptiveTickScheduler.AreaType getAreaType(Level level, ChunkPos chunkPos) {
        // Try to get from scheduler
        int budget = AdaptiveTickScheduler.getBFSBudget(level, new BlockPos(
            chunkPos.getMinBlockX(), 64, chunkPos.getMinBlockZ()
        ));

        // Infer area type from budget
        if (budget <= 1000) {
            return AdaptiveTickScheduler.AreaType.OCEAN;
        } else if (budget >= 8000) {
            return AdaptiveTickScheduler.AreaType.HIGH_ACTIVITY;
        } else {
            return AdaptiveTickScheduler.AreaType.NORMAL;
        }
    }

    /**
     * Performs equalization on a list of positions.
     * Uses the TickBuffer to batch changes.
     *
     * FIXED: NPE handling and proper iteration through valid positions only.
     */
    public static void equalizePositions(Level level, List<BlockPos> positions) {
        equalizePositions(level, positions, findFirstFluidType(level, positions));
    }

    public static void equalizePositions(Level level, List<BlockPos> positions, Fluid fallbackFluid) {
        if (positions.isEmpty()) {
            return;
        }

        // Calculate total fluid amount and collect valid positions
        int totalAmount = 0;
        List<BlockPos> validPos = new ArrayList<>();

        for (BlockPos pos : positions) {
            int amount = FluidSpatialGrid.getFluidAmount(level, pos);
            if (amount > 0 || canAcceptFluid(level, pos)) {
                totalAmount += amount;
                validPos.add(pos);
            }
        }

        if (validPos.isEmpty()) {
            return;
        }

        // Calculate average amount
        int averageAmount = totalAmount / validPos.size();
        int remainder = totalAmount % validPos.size();

        // Distribute fluid evenly to valid positions only
        for (int i = 0; i < validPos.size(); i++) {
            BlockPos pos = validPos.get(i);

            // Give remainder to first few positions
            int newAmount = averageAmount + (i < remainder ? 1 : 0);

            // Buffer the change with null safety
            FluidState fluidState = level.getFluidState(pos);
            Fluid fluidType = (fluidState != null && !fluidState.isEmpty()) ? fluidState.getType() : fallbackFluid;

            if (fluidType != null) {
                FluidTickBuffer.bufferFluidChange(level, pos, newAmount, newAmount > 0, fluidType);
            }
        }
    }

    private static Fluid findFirstFluidType(Level level, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            FluidState state = level.getFluidState(pos);
            if (state != null && !state.isEmpty()) {
                return state.getType();
            }
        }
        return null;
    }

    /**
     * Checks if a position can accept fluid.
     */
    private static boolean canAcceptFluid(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced() || !level.getFluidState(pos).isEmpty();
    }

    /**
     * Cleans up the processing positions set to prevent memory leaks.
     * Called periodically from performEqualization().
     */
    private static void cleanupProcessingPositions() {
        // Clear all processing positions as they should have been removed by their respective threads
        // If any positions remain, they're likely from crashed/interrupted BFS runs
        // Note: In normal operation, processingPositions should be empty or nearly empty
        // because each BFS removes its position in the finally block
        if (processingPositions.size() > 100) {
            // If too many positions are stuck, clear them all
            processingPositions.clear();
        }
    }
}
