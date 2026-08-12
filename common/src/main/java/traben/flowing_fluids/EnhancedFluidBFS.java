package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Equalizer utilities for the natural hybrid fluid system.
 */
public class EnhancedFluidBFS {
    private static final AtomicBoolean QUANTIZATION_FAILURE_REPORTED = new AtomicBoolean();
    private static final int MIN_INTERNAL_DRY_FILL = Math.max(8, FluidAmountConverter.scaleLegacyInternal(64));
    private static final int CLUSTER_PARTITION_THRESHOLD = 96;

    // Depth configurations for different terrain types
    private static final int DEPTH_GENTLE = 60;      // Gentle slopes (reduced for perf)
    private static final int DEPTH_CANAL = 150;      // Artificial channels (reduced for perf)
    private static final int DEPTH_RIVER = 225;      // Natural rivers (reduced for perf)
    private static final int DEPTH_OCEAN = 48;       // Large water bodies (ultra-light, reduced)


    // Default direction order (null gradient)
    private static final Direction[] DEFAULT_DIRECTIONS = new Direction[]{
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };
    private static final Direction[] HORIZONTAL_DIRECTIONS = new Direction[]{
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    /**
     * Determines dynamic search depth based on terrain type and area size.
     *
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
                    FluidState fluid = FFFluidUtils.getEffectiveFluidState(level, samplePos);

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
        FluidSectionDataCache cache = new FluidSectionDataCache(level, Math.max(16, positions.size() / 2));
        equalizePositions(level, positions, findFirstFluidType(positions, cache), cache);
    }

    public static void equalizePositions(Level level, List<BlockPos> positions, Fluid fallbackFluid) {
        FluidSectionDataCache cache = new FluidSectionDataCache(level, Math.max(16, positions.size() / 2));
        equalizePositions(level, positions, fallbackFluid, cache);
    }

    static void equalizePositionKeys(Level level, LongOpenHashSet positionKeys, Fluid fallbackFluid, FluidSectionDataCache cache) {
        if (positionKeys.isEmpty()) {
            return;
        }
        if (positionKeys.size() < CLUSTER_PARTITION_THRESHOLD) {
            List<BlockPos> positions = new ArrayList<>(positionKeys.size());
            for (long key : positionKeys) {
                positions.add(BlockPos.of(key));
            }
            equalizePositionsInternal(level, positions, fallbackFluid, cache);
            return;
        }

        LongOpenHashSet visited = new LongOpenHashSet(positionKeys.size());
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        LongArrayList cluster = new LongArrayList(Math.min(positionKeys.size(), 256));

        for (long seed : positionKeys) {
            if (!visited.add(seed)) {
                continue;
            }
            cluster.clear();
            queue.enqueue(seed);
            cluster.add(seed);

            while (!queue.isEmpty()) {
                long current = queue.dequeueLong();
                int currentX = BlockPos.getX(current);
                int currentY = BlockPos.getY(current);
                int currentZ = BlockPos.getZ(current);

                for (Direction direction : DEFAULT_DIRECTIONS) {
                    long neighbor = BlockPos.asLong(
                        currentX + direction.getStepX(),
                        currentY + direction.getStepY(),
                        currentZ + direction.getStepZ()
                    );
                    if (!positionKeys.contains(neighbor) || !visited.add(neighbor)) {
                        continue;
                    }
                    queue.enqueue(neighbor);
                    cluster.add(neighbor);
                }
            }

            List<BlockPos> clusterPositions = new ArrayList<>(cluster.size());
            for (int i = 0; i < cluster.size(); i++) {
                clusterPositions.add(BlockPos.of(cluster.getLong(i)));
            }
            equalizePositionsInternal(level, clusterPositions, fallbackFluid, cache);
        }
    }

    private static void equalizePositions(Level level, List<BlockPos> positions, Fluid fallbackFluid, FluidSectionDataCache cache) {
        if (positions.isEmpty()) {
            return;
        }

        if (positions.size() >= CLUSTER_PARTITION_THRESHOLD) {
            LongOpenHashSet positionKeys = new LongOpenHashSet(Math.max(positions.size(), 16));
            for (BlockPos pos : positions) {
                positionKeys.add(pos.asLong());
            }
            equalizePositionKeys(level, positionKeys, fallbackFluid, cache);
            return;
        }
        equalizePositionsInternal(level, positions, fallbackFluid, cache);
    }

    static void equalizePositionKeys(Level level, LongOpenHashSet positionKeys, Fluid fallbackFluid) {
        equalizePositionKeys(level, positionKeys, fallbackFluid,
            new FluidSectionDataCache(level, Math.max(16, positionKeys.size() / 8)));
    }

    private static void equalizePositionsInternal(Level level, List<BlockPos> positions, Fluid fallbackFluid, FluidSectionDataCache cache) {
        if (positions.isEmpty()) {
            return;
        }

        // Calculate total fluid amount and collect valid positions
        int totalAmount = 0;
        List<BlockPos> validPos = new ArrayList<>();
        List<Integer> validAmounts = new ArrayList<>();

        for (BlockPos pos : positions) {
            int amount = cache.internalAmount(pos);
            boolean supportsPartialAmount = !cache.isBinaryFluidStorage(pos);
            if (supportsPartialAmount && (amount > 0 || cache.canAcceptFluid(pos))) {
                totalAmount += amount;
                validPos.add(pos);
                validAmounts.add(amount);
            }
        }

        if (validPos.isEmpty()) {
            return;
        }

        int[] finalAmounts = new int[validPos.size()];
        int[] yLevels = new int[validPos.size()];
        long[] positionKeys = new long[validPos.size()];
        int[] supportScores = new int[validPos.size()];
        int[] distances = new int[validPos.size()];
        List<Integer> wetOrder = new ArrayList<>(validPos.size());
        List<Integer> dryOrder = new ArrayList<>(validPos.size());
        for (int i = 0; i < validPos.size(); i++) {
            BlockPos pos = validPos.get(i);
            int amount = validAmounts.get(i);
            finalAmounts[i] = amount;
            yLevels[i] = pos.getY();
            positionKeys[i] = pos.asLong();
            distances[i] = Math.abs(pos.getX() - positions.get(0).getX())
                    + Math.abs(pos.getY() - positions.get(0).getY())
                    + Math.abs(pos.getZ() - positions.get(0).getZ());
            supportScores[i] = cache.supportScore(pos, fallbackFluid, HORIZONTAL_DIRECTIONS);
            if (amount > 0) {
                wetOrder.add(i);
            } else {
                dryOrder.add(i);
            }
        }

        Fluid orderingFluid = fallbackFluid != null ? fallbackFluid : findFirstFluidType(validPos, cache);
        boolean preferWaterSurfacePotential = orderingFluid == Fluids.WATER || orderingFluid == Fluids.FLOWING_WATER;
        wetOrder.sort((a, b) -> FluidRegressionLogic.compareEqualizerFillOrder(
            finalAmounts[a], yLevels[a], supportScores[a], distances[a], validPos.get(a).asLong(),
            finalAmounts[b], yLevels[b], supportScores[b], distances[b], validPos.get(b).asLong(),
            preferWaterSurfacePotential
        ));
        dryOrder.sort((a, b) -> {
            int cmp = Integer.compare(yLevels[a], yLevels[b]);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(supportScores[b], supportScores[a]);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(distances[a], distances[b]);
            if (cmp != 0) {
                return cmp;
            }
            return Long.compare(validPos.get(a).asLong(), validPos.get(b).asLong());
        });

        int[] resolvedAmounts = finalAmounts;
        int remaining = 0;
        if (shouldUseRouteSolver(preferWaterSurfacePotential)) {
            FluidRouteSolver.Result routeResult = FluidRouteSolver.solve(
                finalAmounts,
                yLevels,
                positionKeys,
                FlowingFluids.config.routeSolverIterations,
                FlowingFluids.config.routeSolverMaxTransferPerEdge,
                FlowingFluids.config.routeSolverDownhillBias
            );
            resolvedAmounts = routeResult.amountsInternal();
        } else {
            remaining = equalizeAmounts(finalAmounts, yLevels, wetOrder, dryOrder, totalAmount, preferWaterSurfacePotential);
        }

        int originalBlockTotal = 0;
        for (int amount : validAmounts) {
            originalBlockTotal += FluidAmountConverter.toBlockState(amount);
        }
        int[] resolvedBlockAmounts;
        int[] resolvedInternalAmounts;
        try {
            resolvedBlockAmounts = quantizeInternalAmountsPreservingBlockTotal(
                resolvedAmounts,
                originalBlockTotal
            );
            resolvedInternalAmounts = fitInternalAmountsToBlockBucketsPreservingTotal(
                resolvedAmounts,
                resolvedBlockAmounts,
                totalAmount
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            // Reject a stale or modded result before either the world or the internal
            // buffer changes. An invalid async snapshot must never stop the server tick.
            if (QUANTIZATION_FAILURE_REPORTED.compareAndSet(false, true)) {
                FlowingFluids.warn("Rejected an equalizer result whose visible and internal fluid totals could not both be preserved.", exception);
            }
            return;
        }
        FluidMutationBatch worldMutations = new FluidMutationBatch(level);
        Fluid[] writeFluids = new Fluid[validPos.size()];

        // Build one validated world mutation before changing the simulation authority.
        for (int index = 0; index < validPos.size(); index++) {
            BlockPos pos = validPos.get(index);
            int originalAmount = validAmounts.get(index);
            Fluid fluidType = cache.fluidType(pos);
            if (fluidType == null) {
                fluidType = fallbackFluid;
            }
            writeFluids[index] = fluidType;
            int originalBlockAmount = FluidAmountConverter.toBlockState(originalAmount);
            int resolvedBlockAmount = resolvedBlockAmounts[index];
            if (fluidType != null && originalBlockAmount != resolvedBlockAmount) {
                worldMutations.set(pos, fluidType, originalBlockAmount, resolvedBlockAmount);
            }
        }

        FluidMutationBatch.ApplyResult worldResult = worldMutations.apply();
        if (!worldResult.applied()) {
            return;
        }

        int changedCells = 0;
        for (int index = 0; index < validPos.size(); index++) {
            int originalAmount = validAmounts.get(index);
            int newAmount = resolvedInternalAmounts[index];
            Fluid fluidType = writeFluids[index];
            if (fluidType == null || newAmount == originalAmount) {
                continue;
            }
            changedCells++;
            BlockPos pos = validPos.get(index);
            FluidTickBuffer.bufferFluidChange(level, pos, newAmount, newAmount > 0, fluidType);
            cache.invalidate(pos);
        }

        if (changedCells > 0
                && preferWaterSurfacePotential
                && remaining == 0
                && FlowingFluids.config != null
                && FlowingFluids.config.enableAnalyticPoolDormancy) {
            for (int index = 0; index < validPos.size(); index++) {
                AdaptiveTickScheduler.markPoolStable(level, validPos.get(index), true,
                    resolvedBlockAmounts[index]);
            }
        }
    }

    static int[] quantizeInternalAmountsPreservingBlockTotal(int[] internalAmounts, int targetBlockTotal) {
        if (internalAmounts == null) {
            throw new IllegalArgumentException("Internal amounts are required");
        }
        int maxTotal = internalAmounts.length * FluidAmountConverter.getMaxBlockState();
        if (targetBlockTotal < 0 || targetBlockTotal > maxTotal) {
            throw new IllegalArgumentException("Target block total is outside the available capacity");
        }

        int[] blockAmounts = new int[internalAmounts.length];
        int currentTotal = 0;
        for (int i = 0; i < internalAmounts.length; i++) {
            blockAmounts[i] = FluidAmountConverter.toBlockState(FluidAmountConverter.clamp(internalAmounts[i]));
            currentTotal += blockAmounts[i];
        }

        while (currentTotal > targetBlockTotal) {
            int best = findLeastCostQuantizationStep(internalAmounts, blockAmounts, -1);
            if (best < 0) {
                if (redistributeQuantizedCapacity(internalAmounts, blockAmounts, -1)) {
                    continue;
                }
                throw new IllegalStateException("Unable to reduce quantized fluid total");
            }
            blockAmounts[best]--;
            currentTotal--;
        }
        while (currentTotal < targetBlockTotal) {
            int best = findLeastCostQuantizationStep(internalAmounts, blockAmounts, 1);
            if (best < 0) {
                if (redistributeQuantizedCapacity(internalAmounts, blockAmounts, 1)) {
                    continue;
                }
                throw new IllegalStateException("Unable to increase quantized fluid total");
            }
            blockAmounts[best]++;
            currentTotal++;
        }
        return blockAmounts;
    }

    /**
     * Moves one visible level between two cells without changing the visible total.
     * This opens a valid path when a direct step would temporarily make the preserved
     * internal total impossible, notably with multiple 57-63 source buckets.
     */
    private static boolean redistributeQuantizedCapacity(int[] internalAmounts, int[] blockAmounts, int direction) {
        int donor = -1;
        int receiver = -1;
        int donorPenalty = Integer.MAX_VALUE;
        int receiverPenalty = Integer.MAX_VALUE;

        for (int i = 0; i < blockAmounts.length; i++) {
            int blockAmount = blockAmounts[i];
            if (direction < 0) {
                if (blockAmount == FluidAmountConverter.getMaxBlockState()) {
                    int penalty = quantizationStepPenalty(internalAmounts[i], blockAmount, -1);
                    if (penalty < donorPenalty) {
                        donorPenalty = penalty;
                        donor = i;
                    }
                } else if (blockAmount <= FluidAmountConverter.getMaxBlockState() - 2) {
                    int penalty = quantizationStepPenalty(internalAmounts[i], blockAmount, 1);
                    if (penalty < receiverPenalty) {
                        receiverPenalty = penalty;
                        receiver = i;
                    }
                }
            } else {
                if (blockAmount >= 2) {
                    int penalty = quantizationStepPenalty(internalAmounts[i], blockAmount, -1);
                    if (penalty < donorPenalty) {
                        donorPenalty = penalty;
                        donor = i;
                    }
                } else if (blockAmount == 0) {
                    int penalty = quantizationStepPenalty(internalAmounts[i], blockAmount, 1);
                    if (penalty < receiverPenalty) {
                        receiverPenalty = penalty;
                        receiver = i;
                    }
                }
            }
        }

        if (donor < 0 || receiver < 0) {
            return false;
        }
        blockAmounts[donor]--;
        blockAmounts[receiver]++;
        return true;
    }

    private static int quantizationStepPenalty(int internalAmount, int blockAmount, int delta) {
        int currentError = Math.abs(FluidAmountConverter.toInternal(blockAmount) - internalAmount);
        int candidateError = Math.abs(FluidAmountConverter.toInternal(blockAmount + delta) - internalAmount);
        return candidateError - currentError;
    }

    static int[] fitInternalAmountsToBlockBucketsPreservingTotal(int[] preferredInternalAmounts,
                                                                  int[] blockAmounts,
                                                                  int targetInternalTotal) {
        if (preferredInternalAmounts == null || blockAmounts == null
                || preferredInternalAmounts.length != blockAmounts.length) {
            throw new IllegalArgumentException("Matching internal and block amount arrays are required");
        }
        int minTotal = 0;
        int maxTotal = 0;
        int[] fitted = new int[blockAmounts.length];
        int currentTotal = 0;
        for (int i = 0; i < blockAmounts.length; i++) {
            int min = minimumInternalForBlockAmount(blockAmounts[i]);
            int max = maximumInternalForBlockAmount(blockAmounts[i]);
            minTotal += min;
            maxTotal += max;
            fitted[i] = Math.max(min, Math.min(max, FluidAmountConverter.clamp(preferredInternalAmounts[i])));
            currentTotal += fitted[i];
        }
        if (targetInternalTotal < minTotal || targetInternalTotal > maxTotal) {
            throw new IllegalArgumentException("Target internal total cannot fit the quantized block amounts");
        }

        while (currentTotal < targetInternalTotal) {
            int best = findLeastCostInternalStep(preferredInternalAmounts, fitted, blockAmounts, 1);
            fitted[best]++;
            currentTotal++;
        }
        while (currentTotal > targetInternalTotal) {
            int best = findLeastCostInternalStep(preferredInternalAmounts, fitted, blockAmounts, -1);
            fitted[best]--;
            currentTotal--;
        }
        return fitted;
    }

    private static int findLeastCostQuantizationStep(int[] internalAmounts, int[] blockAmounts, int delta) {
        int bestIndex = -1;
        int bestPenalty = Integer.MAX_VALUE;
        int targetInternalTotal = 0;
        int currentMinimum = 0;
        int currentMaximum = 0;
        for (int i = 0; i < blockAmounts.length; i++) {
            targetInternalTotal += FluidAmountConverter.clamp(internalAmounts[i]);
            currentMinimum += minimumInternalForBlockAmount(blockAmounts[i]);
            currentMaximum += maximumInternalForBlockAmount(blockAmounts[i]);
        }
        for (int i = 0; i < blockAmounts.length; i++) {
            int candidate = blockAmounts[i] + delta;
            if (candidate < 0 || candidate > FluidAmountConverter.getMaxBlockState()) {
                continue;
            }
            int candidateMinimum = currentMinimum
                - minimumInternalForBlockAmount(blockAmounts[i])
                + minimumInternalForBlockAmount(candidate);
            int candidateMaximum = currentMaximum
                - maximumInternalForBlockAmount(blockAmounts[i])
                + maximumInternalForBlockAmount(candidate);
            if (targetInternalTotal < candidateMinimum || targetInternalTotal > candidateMaximum) {
                continue;
            }
            int currentError = Math.abs(FluidAmountConverter.toInternal(blockAmounts[i]) - internalAmounts[i]);
            int candidateError = Math.abs(FluidAmountConverter.toInternal(candidate) - internalAmounts[i]);
            int penalty = candidateError - currentError;
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static int findLeastCostInternalStep(int[] preferred, int[] current, int[] blockAmounts, int delta) {
        int bestIndex = -1;
        int bestPenalty = Integer.MAX_VALUE;
        for (int i = 0; i < current.length; i++) {
            int candidate = current[i] + delta;
            if (candidate < minimumInternalForBlockAmount(blockAmounts[i])
                    || candidate > maximumInternalForBlockAmount(blockAmounts[i])) {
                continue;
            }
            int penalty = Math.abs(candidate - preferred[i]) - Math.abs(current[i] - preferred[i]);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestIndex = i;
            }
        }
        if (bestIndex < 0) {
            throw new IllegalStateException("Unable to fit internal fluid total");
        }
        return bestIndex;
    }

    private static int minimumInternalForBlockAmount(int blockAmount) {
        if (blockAmount <= 0) {
            return 0;
        }
        if (blockAmount >= FluidAmountConverter.getMaxBlockState()) {
            return 57;
        }
        return ((blockAmount - 1) * 8) + 1;
    }

    private static int maximumInternalForBlockAmount(int blockAmount) {
        if (blockAmount <= 0) {
            return 0;
        }
        if (blockAmount >= FluidAmountConverter.getMaxBlockState()) {
            return FluidAmountConverter.getMaxInternal();
        }
        return blockAmount * 8;
    }

    private static boolean shouldUseRouteSolver(boolean preferWaterSurfacePotential) {
        return preferWaterSurfacePotential
            && FlowingFluids.config != null
            && FlowingFluids.config.enableRouteSolver;
    }

    private static Fluid findFirstFluidType(List<BlockPos> positions, FluidSectionDataCache cache) {
        for (BlockPos pos : positions) {
            Fluid fluid = cache.fluidType(pos);
            if (fluid != null) {
                return fluid;
            }
        }
        return null;
    }

    static int[] equalizeAmounts(int[] amountsInternal, int[] yLevels, boolean preferWaterSurfacePotential) {
        if (amountsInternal == null || yLevels == null || amountsInternal.length != yLevels.length) {
            throw new IllegalArgumentException("Amounts and y-levels must be non-null arrays of the same length");
        }
        int[] result = Arrays.copyOf(amountsInternal, amountsInternal.length);
        int totalAmount = 0;
        List<Integer> wetOrder = new ArrayList<>(result.length);
        List<Integer> dryOrder = new ArrayList<>(result.length);
        for (int i = 0; i < result.length; i++) {
            totalAmount += result[i];
            if (result[i] > 0) {
                wetOrder.add(i);
            } else {
                dryOrder.add(i);
            }
        }
        wetOrder.sort((a, b) -> {
            int cmp = Integer.compare(result[a], result[b]);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(yLevels[a], yLevels[b]);
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(a, b);
        });
        dryOrder.sort((a, b) -> {
            int cmp = Integer.compare(yLevels[a], yLevels[b]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        equalizeAmounts(result, yLevels, wetOrder, dryOrder, totalAmount, preferWaterSurfacePotential);
        return result;
    }

    static int equalizeAmounts(int[] amountsInternal, int[] yLevels, List<Integer> wetOrder,
                               List<Integer> dryOrder, int totalAmount, boolean preferWaterSurfacePotential) {
        int maxInternal = FluidAmountConverter.getMaxInternal();
        int remaining;
        if (preferWaterSurfacePotential) {
            remaining = distributeWaterComponentBySurfacePotential(amountsInternal, yLevels, wetOrder, dryOrder,
                totalAmount, maxInternal);
        } else {
            remaining = distributeInternalAmounts(amountsInternal, wetOrder, totalAmount, maxInternal);
        }
        if (!preferWaterSurfacePotential && remaining > 0 && !dryOrder.isEmpty()) {
            int selectedDryCount = determineInternalDryActivationCount(remaining, dryOrder.size());
            remaining = distributeInternalAmounts(amountsInternal, dryOrder.subList(0, selectedDryCount),
                remaining, maxInternal);
        }
        return remaining;
    }

    private static int determineInternalDryActivationCount(int remaining, int dryCandidates) {
        if (remaining <= 0 || dryCandidates <= 0) {
            return 0;
        }
        int minCellsNeeded = Math.max(1, (remaining + FluidAmountConverter.getMaxInternal() - 1)
            / FluidAmountConverter.getMaxInternal());
        int maxCellsForCoherentFill = remaining >= MIN_INTERNAL_DRY_FILL
                ? Math.max(1, remaining / MIN_INTERNAL_DRY_FILL)
                : 1;
        int selected = Math.min(dryCandidates, minCellsNeeded);
        if (selected > maxCellsForCoherentFill) {
            selected = Math.min(dryCandidates, maxCellsForCoherentFill);
        }
        return Math.max(1, selected);
    }

    private static int distributeInternalAmounts(int[] levels, List<Integer> orderedIndices, int amount, int maxLevel) {
        if (amount <= 0 || orderedIndices.isEmpty()) {
            return amount;
        }

        int remaining = amount;
        for (int tier = 0; tier < orderedIndices.size() && remaining > 0; tier++) {
            int currentLevel = levels[orderedIndices.get(tier)];
            int nextLevel = tier == orderedIndices.size() - 1
                    ? maxLevel
                    : levels[orderedIndices.get(tier + 1)];
            int span = Math.max(0, nextLevel - currentLevel);
            if (span == 0) {
                continue;
            }

            int needed = span * (tier + 1);
            if (remaining >= needed) {
                for (int i = 0; i <= tier; i++) {
                    levels[orderedIndices.get(i)] += span;
                }
                remaining -= needed;
            } else {
                int share = remaining / (tier + 1);
                int extra = remaining % (tier + 1);
                for (int i = 0; i <= tier; i++) {
                    levels[orderedIndices.get(i)] += share + (i < extra ? 1 : 0);
                }
                remaining = 0;
            }
        }
        return remaining;
    }

    static int distributeWaterComponentBySurfacePotential(int[] levels, int[] yLevels, List<Integer> wetOrder,
                                                          List<Integer> dryOrder, int amount, int maxLevel) {
        if (amount <= 0 || wetOrder.isEmpty()) {
            return amount;
        }

        List<Integer> activeTargets = new ArrayList<>(wetOrder.size() + dryOrder.size());
        boolean[] active = new boolean[levels.length];
        int highestWetY = Integer.MIN_VALUE;
        for (int index : wetOrder) {
            active[index] = true;
            activeTargets.add(index);
            highestWetY = Math.max(highestWetY, yLevels[index]);
        }

        int remaining = distributeWaterBySurfacePotential(levels, yLevels, activeTargets, amount, maxLevel);
        while (!dryOrder.isEmpty()) {
            int previousSize = activeTargets.size();
            int waterSurface = getFilledWaterSurface(levels, yLevels, activeTargets, maxLevel);

            for (int index : dryOrder) {
                if (active[index]) {
                    continue;
                }
                int dryBaseSurface = yLevels[index] * maxLevel;
                if (remaining > 0 || yLevels[index] < highestWetY && dryBaseSurface < waterSurface) {
                    active[index] = true;
                    activeTargets.add(index);
                }
            }

            if (activeTargets.size() == previousSize) {
                return remaining;
            }
            remaining = distributeWaterBySurfacePotential(levels, yLevels, activeTargets, amount, maxLevel);
        }
        return remaining;
    }

    static int distributeWaterBySurfacePotential(int[] levels, int[] yLevels, List<Integer> orderedIndices,
                                                 int amount, int maxLevel) {
        if (amount <= 0 || orderedIndices.isEmpty()) {
            return amount;
        }

        int minSurface = Integer.MAX_VALUE;
        int maxSurface = Integer.MIN_VALUE;
        for (int index : orderedIndices) {
            levels[index] = 0;
            int baseSurface = yLevels[index] * maxLevel;
            minSurface = Math.min(minSurface, baseSurface);
            maxSurface = Math.max(maxSurface, baseSurface + maxLevel);
        }

        int low = minSurface;
        int high = maxSurface;
        while (low < high) {
            int mid = low + (high - low + 1) / 2;
            if (getWaterVolumeAtSurface(yLevels, orderedIndices, mid, maxLevel) <= amount) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }

        int used = 0;
        for (int index : orderedIndices) {
            int fill = clampInternalSurfaceFill(low - yLevels[index] * maxLevel, maxLevel);
            levels[index] = fill;
            used += fill;
        }

        int remaining = amount - used;
        for (int index : orderedIndices) {
            if (remaining <= 0) {
                break;
            }
            int baseSurface = yLevels[index] * maxLevel;
            if (baseSurface <= low && levels[index] < maxLevel) {
                levels[index]++;
                remaining--;
            }
        }
        return remaining;
    }

    private static int getFilledWaterSurface(int[] levels, int[] yLevels, List<Integer> orderedIndices, int maxLevel) {
        int surface = Integer.MIN_VALUE;
        for (int index : orderedIndices) {
            if (levels[index] > 0) {
                surface = Math.max(surface, yLevels[index] * maxLevel + Math.min(levels[index], maxLevel));
            }
        }
        return surface == Integer.MIN_VALUE ? Integer.MIN_VALUE : surface;
    }

    private static int getWaterVolumeAtSurface(int[] yLevels, List<Integer> orderedIndices, int surface, int maxLevel) {
        int volume = 0;
        for (int index : orderedIndices) {
            volume += clampInternalSurfaceFill(surface - yLevels[index] * maxLevel, maxLevel);
        }
        return volume;
    }

    private static int clampInternalSurfaceFill(int fill, int maxLevel) {
        if (fill <= 0) {
            return 0;
        }
        return Math.min(fill, maxLevel);
    }

    static void rebalanceClusterAmounts(int[] clusterAmounts, int clusterSize, int total, int average, int allowedTransfer) {
        if (clusterAmounts == null || clusterSize <= 0 || allowedTransfer <= 0) {
            return;
        }

        int totalExcess = computeTotalExcess(clusterAmounts, clusterSize, average);
        if (totalExcess <= 0) {
            return;
        }

        float ratio = allowedTransfer / (float) totalExcess;
        double[] fractionalRemainders = new double[clusterSize];
        Integer[] order = new Integer[clusterSize];
        int newTotal = 0;

        for (int i = 0; i < clusterSize; i++) {
            int amount = clusterAmounts[i];
            int delta = amount - average;
            double target = delta >= 0
                ? amount - (Math.max(0, delta) * ratio)
                : amount + (Math.abs(delta) * ratio);
            int floor = Math.max(0, (int) Math.floor(target));
            clusterAmounts[i] = floor;
            fractionalRemainders[i] = target - floor;
            order[i] = i;
            newTotal += floor;
        }

        int remainder = total - newTotal;
        if (remainder > 0) {
            Arrays.sort(order, (left, right) -> Double.compare(fractionalRemainders[right], fractionalRemainders[left]));
            for (int i = 0; i < order.length && remainder > 0; i++) {
                clusterAmounts[order[i]]++;
                remainder--;
            }
        } else if (remainder < 0) {
            Arrays.sort(order, Comparator
                .comparingDouble((Integer index) -> fractionalRemainders[index])
                .thenComparingInt(index -> clusterAmounts[index]));
            for (int i = 0; i < order.length && remainder < 0; i++) {
                int index = order[i];
                if (clusterAmounts[index] <= 0) {
                    continue;
                }
                clusterAmounts[index]--;
                remainder++;
            }
        }
    }

    private static int computeTotalExcess(int[] clusterAmounts, int clusterSize, int average) {
        int totalExcess = 0;
        for (int i = 0; i < clusterSize; i++) {
            int delta = clusterAmounts[i] - average;
            if (delta > 0) {
                totalExcess += delta;
            }
        }
        return totalExcess;
    }
}
