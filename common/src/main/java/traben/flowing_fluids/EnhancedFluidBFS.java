package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.*;

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

    /**
     * Performs BFS equalization with all enhancements.
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
        int startAmount = FluidSpatialGrid.getFluidAmount(startPos);
        if (!AdaptiveTickScheduler.shouldRunBFS(level, startPos, startAmount)) {
            return equalizedPositions; // Too stable, skip BFS
        }

        // Initialize BFS
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        Set<Long> visited = new HashSet<>();

        queue.enqueue(startPos.asLong());
        visited.add(startPos.asLong());

        int nodesExplored = 0;
        ChunkPos chunkPos = new ChunkPos(startPos);

        // Get or estimate gradient vector for weighted search
        Vec3i gradientVector = ChunkLocalSlopeCache.getGradientVector(chunkPos, startPos);

        while (!queue.isEmpty() && nodesExplored < maxNodes && visited.size() < maxDepth) {
            long currentLong = queue.dequeueLong();
            BlockPos currentPos = BlockPos.of(currentLong);
            nodesExplored++;

            // Get current fluid amount
            FluidState currentFluid = level.getFluidState(currentPos);
            int currentAmount = FluidSpatialGrid.getFluidAmount(currentPos);

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
                    int neighborAmount = FluidSpatialGrid.getFluidAmount(neighborPos);
                    if (shouldEqualize(currentAmount, neighborAmount)) {
                        equalizedPositions.add(neighborPos.immutable());
                    }
                }
            }
        }

        return equalizedPositions;
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
     */
    private static Direction[] getWeightedDirections(Vec3i gradientVector) {
        Direction[] directions = Direction.values();

        // Sort by weight (lower = preferred)
        Arrays.sort(directions, (a, b) -> {
            float weightA = ChunkLocalSlopeCache.calculateDirectionWeight(gradientVector, a);
            float weightB = ChunkLocalSlopeCache.calculateDirectionWeight(gradientVector, b);
            return Float.compare(weightA, weightB);
        });

        return directions;
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
        int budget = AdaptiveTickScheduler.getBFSBudget(new BlockPos(
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
     */
    public static void equalizePositions(Level level, List<BlockPos> positions) {
        if (positions.isEmpty()) {
            return;
        }

        // Calculate total fluid amount
        int totalAmount = 0;
        int validPositions = 0;

        for (BlockPos pos : positions) {
            int amount = FluidSpatialGrid.getFluidAmount(pos);
            if (amount > 0 || canAcceptFluid(level, pos)) {
                totalAmount += amount;
                validPositions++;
            }
        }

        if (validPositions == 0) {
            return;
        }

        // Calculate average amount
        int averageAmount = totalAmount / validPositions;
        int remainder = totalAmount % validPositions;

        // Distribute fluid evenly
        for (int i = 0; i < positions.size() && i < validPositions; i++) {
            BlockPos pos = positions.get(i);

            // Give remainder to first few positions
            int newAmount = averageAmount + (i < remainder ? 1 : 0);

            // Buffer the change
            FluidState fluid = level.getFluidState(pos);
            FluidTickBuffer.bufferFluidChange(pos, newAmount, newAmount > 0,
                fluid.isEmpty() ? null : fluid.getType());
        }
    }

    /**
     * Checks if a position can accept fluid.
     */
    private static boolean canAcceptFluid(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced() || !level.getFluidState(pos).isEmpty();
    }
}
