package traben.flowing_fluids;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final int DEPTH_GENTLE = 60;      // Gentle slopes (reduced for perf)
    private static final int DEPTH_CANAL = 150;      // Artificial channels (reduced for perf)
    private static final int DEPTH_RIVER = 225;      // Natural rivers (reduced for perf)
    private static final int DEPTH_OCEAN = 48;       // Large water bodies (ultra-light, reduced)

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
    private static final Direction[] HORIZONTAL_DIRECTIONS = new Direction[]{
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    // Downhill acceleration bonus (simulates momentum gained from drops)
    private static final int MAX_MOMENTUM_BONUS = 256;

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
        LongOpenHashSet equalizedKeys = new LongOpenHashSet();

        // Get starting fluid state
        FluidState startFluid = level.getFluidState(startPos);
        if (startFluid.isEmpty()) {
            return equalizedPositions; // No fluid to equalize
        }

        int startAmount = FluidSpatialGrid.getFluidAmount(level, startPos);
        boolean forcedRecheck = AdaptiveTickScheduler.consumeForcedRecheck(level, startPos);
        boolean shouldRunBfs = AdaptiveTickScheduler.shouldRunBFS(level, startPos, startAmount);
        if (!shouldRunBfs && !forcedRecheck) {
            return equalizedPositions; // Too stable, skip BFS
        }

        int effectiveMaxDepth = maxDepth;
        int effectiveMaxNodes = maxNodes;
        if (forcedRecheck && !shouldRunBfs) {
            float budgetFactor = Math.max(0.1f, FlowingFluids.config.forcedEqualizationBudgetFactor);
            effectiveMaxDepth = Math.max(8, Math.round(maxDepth * budgetFactor));
            effectiveMaxNodes = Math.max(128, Math.round(maxNodes * budgetFactor));
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
            LongOpenHashSet visited = new LongOpenHashSet();
            LongArrayList visitedOrder = new LongArrayList();

            BlockPos.MutableBlockPos reusablePos = new BlockPos.MutableBlockPos();

            long startLong = startPos.asLong();
            queue.enqueue(startLong);
            visited.add(startLong);
            equalizedPositions.add(startPos.immutable());

            int nodesExplored = 0;
            int momentumBudget = 0;
            int momentumCap = getDistanceScaledMomentumCap();
            boolean dropEncountered = false;
            ChunkPos chunkPos = new ChunkPos(startPos);

            // Get or estimate gradient vector for weighted search
            Vec3i gradientVector = ChunkLocalSlopeCache.getGradientVector(level, chunkPos, startPos);

            while (!queue.isEmpty() && nodesExplored < effectiveMaxNodes + momentumBudget && visited.size() < effectiveMaxDepth) {
                long currentLong = queue.dequeueLong();
                int currentX = BlockPos.getX(currentLong);
                int currentY = BlockPos.getY(currentLong);
                int currentZ = BlockPos.getZ(currentLong);
                reusablePos.set(currentX, currentY, currentZ);
                nodesExplored++;

                // Get current fluid amount (cached per node)
                int currentAmount = FluidSpatialGrid.getFluidAmount(level, reusablePos);

                // Explore neighbors with weighted priority
                Direction[] directions = getWeightedDirections(gradientVector);

                for (Direction dir : directions) {
                    int neighborX = currentX + dir.getStepX();
                    int neighborY = currentY + dir.getStepY();
                    int neighborZ = currentZ + dir.getStepZ();

                    long neighborLong = BlockPos.asLong(neighborX, neighborY, neighborZ);

                    if (visited.contains(neighborLong)) {
                        continue;
                    }

                    reusablePos.set(neighborX, neighborY, neighborZ);
                    BlockState neighborState = level.getBlockState(reusablePos);
                    FluidState neighborFluidState = neighborState.getFluidState();

                    // CRITICAL: Include air blocks and replaceable blocks!
                    if (canIncludeInBFS(neighborState, neighborFluidState, startFluid)) {
                        visited.add(neighborLong);
                        visitedOrder.add(neighborLong);
                        queue.enqueue(neighborLong);

                        // Add to equalization list if it needs balancing
                        int neighborAmount = FluidSpatialGrid.getFluidAmount(level, reusablePos);
                        boolean isDrop = currentY > neighborY;
                        if (shouldEqualize(currentAmount, neighborAmount) || isDrop) {
                            dropEncountered = dropEncountered || isDrop;
                            addEqualizationTarget(equalizedPositions, equalizedKeys, reusablePos);
                            reusablePos.set(currentX, currentY, currentZ);
                            addEqualizationTarget(equalizedPositions, equalizedKeys, reusablePos);
                            reusablePos.set(neighborX, neighborY, neighborZ);
                        }

                        // Grant additional budget when flowing downhill to mimic acceleration
                        int drop = currentY - neighborY;
                        if (drop > 0) {
                            momentumBudget = Math.min(momentumCap, momentumBudget + drop);
                        }
                    }
                }
            }

            int horizontalBudget = Math.min(
                FlowingFluids.config.horizontalSupplementExtraNodes,
                Math.max(0, effectiveMaxNodes + FlowingFluids.config.horizontalSupplementExtraNodes - nodesExplored)
            );
            if (horizontalBudget > 0) {
                nodesExplored += runHorizontalSupplement(level, startFluid, visited, visitedOrder, equalizedPositions,
                    equalizedKeys, reusablePos, horizontalBudget, FlowingFluids.config.horizontalSupplementDepth);
            }

            // 追加の掃き出し: 雨など一時的な落下で流入が途絶えた場合でも、
            // 一度でも段差を踏んだ探索では訪問済みセル全体を均衡候補に加える。
            // これにより段差の手前・奥に残った水をもう一段深く平均化し、取り残しを防ぐ。
            if (dropEncountered) {
                for (long visitedKey : visitedOrder) {
                    reusablePos.set(BlockPos.getX(visitedKey), BlockPos.getY(visitedKey), BlockPos.getZ(visitedKey));
                    addEqualizationTarget(equalizedPositions, equalizedKeys, reusablePos);
                }
            }

            int diffusionBudget = Math.max(0, (int) Math.round(
                (effectiveMaxNodes - nodesExplored) * FlowingFluids.config.clusterDiffusionBudgetPortion));
            if (diffusionBudget > 0) {
                applyClusterDiffusion(level, startFluid, equalizedPositions, diffusionBudget,
                    FlowingFluids.config.clusterDiffusionHeightThreshold,
                    FlowingFluids.config.clusterDiffusionMaxCluster);
            }

            return equalizedPositions;
        } finally {
            // Always remove from processing set when done
            processingPositions.remove(posKey);
        }
    }

    private static void addEqualizationTarget(List<BlockPos> equalizedPositions, LongOpenHashSet equalizedKeys, BlockPos pos) {
        long key = pos.asLong();
        if (equalizedKeys.add(key)) {
            equalizedPositions.add(pos.immutable());
        }
    }

    /**
     * CRITICAL: Determines if a block should be included in BFS.
     * This includes AIR blocks, which fixes the "water doesn't flow to distant channels" bug!
     */
    private static boolean canIncludeInBFS(BlockState state, FluidState fluidState, FluidState sourceFluid) {
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
        if (fluidState.isEmpty() && !state.isSolid()) {
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
     * 水流距離が長い場合に探索が暴走しないよう、モーメントムの上限を距離に応じて縮小する。
     * 例: 距離6では 4/6 ≒0.67 倍に抑制し、長距離設定での追加探索コストを抑える。
     */
    private static int getDistanceScaledMomentumCap() {
        int distance = Math.max(FlowingFluids.config.waterFlowDistance, 1);
        if (distance <= 4) {
            return MAX_MOMENTUM_BONUS;
        }
        int scaled = Math.round(MAX_MOMENTUM_BONUS * (4.0f / distance));
        return Math.max(32, scaled);
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
        if (budget <= 900) {
            return AdaptiveTickScheduler.AreaType.OCEAN;
        } else if (budget >= 6000) {
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

    private static int runHorizontalSupplement(Level level, FluidState startFluid, LongOpenHashSet visited,
                                               LongArrayList visitedOrder, List<BlockPos> equalizedPositions,
                                               LongOpenHashSet equalizedKeys, BlockPos.MutableBlockPos reusablePos,
                                               int nodeBudget, int depthLimit) {
        if (nodeBudget <= 0 || depthLimit <= 0) {
            return 0;
        }

        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        IntArrayFIFOQueue depthQueue = new IntArrayFIFOQueue();

        for (BlockPos seed : equalizedPositions) {
            queue.enqueue(seed.asLong());
            depthQueue.enqueue(0);
        }

        int addedNodes = 0;
        while (!queue.isEmpty() && addedNodes < nodeBudget) {
            long current = queue.dequeueLong();
            int depth = depthQueue.dequeueInt();
            int currentX = BlockPos.getX(current);
            int currentY = BlockPos.getY(current);
            int currentZ = BlockPos.getZ(current);
            reusablePos.set(currentX, currentY, currentZ);
            int currentAmount = FluidSpatialGrid.getFluidAmount(level, reusablePos);

            for (Direction dir : HORIZONTAL_DIRECTIONS) {
                if (depth >= depthLimit) {
                    break;
                }

                int neighborX = currentX + dir.getStepX();
                int neighborZ = currentZ + dir.getStepZ();
                long neighborLong = BlockPos.asLong(neighborX, currentY, neighborZ);

                if (visited.contains(neighborLong)) {
                    continue;
                }

                reusablePos.set(neighborX, currentY, neighborZ);
                BlockState neighborState = level.getBlockState(reusablePos);
                FluidState neighborFluidState = neighborState.getFluidState();

                if (canIncludeInBFS(neighborState, neighborFluidState, startFluid)) {
                    visited.add(neighborLong);
                    visitedOrder.add(neighborLong);
                    queue.enqueue(neighborLong);
                    depthQueue.enqueue(depth + 1);
                    addedNodes++;

                    int neighborAmount = FluidSpatialGrid.getFluidAmount(level, reusablePos);
                    if (shouldEqualize(currentAmount, neighborAmount)) {
                        addEqualizationTarget(equalizedPositions, equalizedKeys, reusablePos);
                        reusablePos.set(currentX, currentY, currentZ);
                        addEqualizationTarget(equalizedPositions, equalizedKeys, reusablePos);
                        reusablePos.set(neighborX, currentY, neighborZ);
                    }

                    if (addedNodes >= nodeBudget) {
                        break;
                    }
                }
            }
        }

        return addedNodes;
    }

    private static void applyClusterDiffusion(Level level, FluidState sourceFluid, List<BlockPos> equalizedPositions,
                                              int budget, int heightThreshold, int maxClusterSize) {
        if (budget <= 0 || heightThreshold <= 0 || maxClusterSize <= 1) {
            return;
        }

        LongOpenHashSet seen = new LongOpenHashSet();

        for (BlockPos pos : equalizedPositions) {
            long startKey = pos.asLong();
            if (seen.contains(startKey)) {
                continue;
            }

            FluidState originState = level.getFluidState(pos);
            if (originState.isEmpty() || originState.getType() != sourceFluid.getType()) {
                continue;
            }

            List<BlockPos> cluster = new ArrayList<>();
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            queue.enqueue(startKey);

            while (!queue.isEmpty() && cluster.size() < maxClusterSize) {
                long current = queue.dequeueLong();
                BlockPos currentPos = new BlockPos(BlockPos.getX(current), BlockPos.getY(current), BlockPos.getZ(current));
                if (!seen.add(current)) {
                    continue;
                }
                FluidState fluidState = level.getFluidState(currentPos);
                if (fluidState.isEmpty() || fluidState.getType() != sourceFluid.getType()) {
                    continue;
                }

                cluster.add(currentPos);

                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = currentPos.relative(dir);
                    long neighborKey = neighbor.asLong();
                    if (seen.contains(neighborKey)) {
                        continue;
                    }
                    FluidState neighborState = level.getFluidState(neighbor);
                    if (!neighborState.isEmpty() && neighborState.getType() == sourceFluid.getType()) {
                        queue.enqueue(neighborKey);
                    }
                }
            }

            if (cluster.size() < 2) {
                continue;
            }

            int minAmount = Integer.MAX_VALUE;
            int maxAmount = Integer.MIN_VALUE;
            int total = 0;
            for (BlockPos clusterPos : cluster) {
                int amount = FluidSpatialGrid.getFluidAmount(level, clusterPos);
                minAmount = Math.min(minAmount, amount);
                maxAmount = Math.max(maxAmount, amount);
                total += amount;
            }

            if (maxAmount - minAmount < heightThreshold) {
                continue;
            }

            int average = total / cluster.size();
            int totalExcess = 0;
            for (BlockPos clusterPos : cluster) {
                int amount = FluidSpatialGrid.getFluidAmount(level, clusterPos);
                int delta = amount - average;
                if (delta > 0) {
                    totalExcess += delta;
                }
            }

            if (totalExcess == 0) {
                continue;
            }

            int allowedTransfer = Math.min(budget, totalExcess);
            float ratio = allowedTransfer / (float) totalExcess;

            int[] newAmounts = new int[cluster.size()];
            int newTotal = 0;

            for (int i = 0; i < cluster.size(); i++) {
                BlockPos clusterPos = cluster.get(i);
                int amount = FluidSpatialGrid.getFluidAmount(level, clusterPos);
                int delta = amount - average;
                int adjustment = Math.round(Math.abs(delta) * ratio);
                int newAmount = delta >= 0 ? amount - adjustment : amount + adjustment;
                newAmounts[i] = Math.max(0, newAmount);
                newTotal += newAmounts[i];
            }

            int correction = total - newTotal;
            if (correction != 0 && !cluster.isEmpty()) {
                newAmounts[0] = Math.max(0, newAmounts[0] + correction);
            }

            for (int i = 0; i < cluster.size(); i++) {
                BlockPos clusterPos = cluster.get(i);
                FluidTickBuffer.bufferFluidChange(level, clusterPos, newAmounts[i], newAmounts[i] > 0, sourceFluid.getType());
            }

            budget -= allowedTransfer;
            if (budget <= 0) {
                break;
            }
        }
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
