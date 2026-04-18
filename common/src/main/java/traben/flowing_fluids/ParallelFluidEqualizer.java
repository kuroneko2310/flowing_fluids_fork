package traben.flowing_fluids;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.util.DimensionKey;
import traben.flowing_fluids.optimization.WaterFlowProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class ParallelFluidEqualizer {
    private static final int PARALLELISM = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static volatile ForkJoinPool pool = createPool();

    private static final ConcurrentHashMap<DimensionKey, Set<Long>> QUEUED = new ConcurrentHashMap<>();
    private static final Set<Long> ACTIVE = ConcurrentHashMap.newKeySet();

    private static final byte LOADED = 1;
    private static final byte AIR = 1 << 1;
    private static final byte REPLACEABLE = 1 << 2;
    private static final byte SOLID = 1 << 3;
    private static final byte HAS_FLUID = 1 << 4;
    private static final byte SAME_FLUID = 1 << 5;

    private static final Direction[] DEFAULT_DIRECTIONS = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    private static final int MAX_MOMENTUM_BONUS = 256;
    private static final int MIN_SELECTION_BUCKET_SIZE = 6;
    private static final int MAX_SELECTION_BUCKET_SIZE = 12;
    private static final int MAX_BUCKET_REPRESENTATIVES = 3;
    private static final int SYNC_REQUEST_THRESHOLD = 2;
    private static final int CALM_SURFACE_MAX_NODES = 384;

    private static final ThreadLocal<Map<Vec3i, Direction[]>> DIRECTION_CACHE = ThreadLocal.withInitial(() ->
        new LinkedHashMap<Vec3i, Direction[]>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Vec3i, Direction[]> eldest) {
                return size() > 1000;
            }
        }
    );

    private ParallelFluidEqualizer() {
    }

    public static void enqueue(LevelAccessor level, BlockPos pos) {
        if (level == null || pos == null || !FlowingFluids.config.enableMod || FlowingFluids.config.bfsMaxSearchDistance <= 0) {
            return;
        }
        QUEUED.computeIfAbsent(DimensionKey.of(level), ignored -> ConcurrentHashMap.newKeySet()).add(pos.asLong());
    }

    public static boolean hasQueued(LevelAccessor level) {
        if (level == null) {
            return false;
        }
        Set<Long> queued = QUEUED.get(DimensionKey.of(level));
        return queued != null && !queued.isEmpty();
    }

    public static int flush(ServerLevel level) {
        Set<Long> queued = QUEUED.remove(DimensionKey.of(level));
        if (queued == null || queued.isEmpty()) {
            return 0;
        }

        SelectionResult selection = selectRepresentativeSources(level, queued);
        requeueDeferred(level, selection.deferred());
        List<ScanCandidate> representativeSources = selection.selected();
        if (representativeSources.isEmpty()) {
            return 0;
        }

        FluidSectionDataCache captureCache = new FluidSectionDataCache(level, Math.max(32, representativeSources.size() * 8));
        List<Request> requests = new ArrayList<>(representativeSources.size());
        for (ScanCandidate candidate : representativeSources) {
            long posKey = candidate.pos().asLong();
            if (!ACTIVE.add(posKey)) {
                continue;
            }
            Request request = prepare(level, candidate, captureCache);
            if (request != null) {
                requests.add(request);
            } else {
                ACTIVE.remove(posKey);
            }
        }
        if (requests.isEmpty()) {
            return 0;
        }

        Map<Fluid, LongOpenHashSet> mergedByFluid = new LinkedHashMap<>();
        if (requests.size() <= SYNC_REQUEST_THRESHOLD) {
            for (Request request : requests) {
                Result result = computeResult(request);
                cacheComponentMembership(level, result.componentPositions());
                if (!result.targets().isEmpty()) {
                    mergedByFluid.computeIfAbsent(result.fluidType(), ignored -> new LongOpenHashSet()).addAll(result.targets());
                }
            }
        } else {
            List<CompletableFuture<Result>> futures = new ArrayList<>(requests.size());
            for (Request request : requests) {
                futures.add(submitAsync(request));
            }

            for (int i = 0; i < futures.size(); i++) {
                Result result;
                try {
                    result = futures.get(i).join();
                } catch (CompletionException exception) {
                    Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
                    FlowingFluids.error("Parallel equalization failed: " + cause.getMessage());
                    result = computeResult(requests.get(i));
                }
                cacheComponentMembership(level, result.componentPositions());
                if (!result.targets().isEmpty()) {
                    mergedByFluid.computeIfAbsent(result.fluidType(), ignored -> new LongOpenHashSet()).addAll(result.targets());
                }
            }
        }

        int total = 0;
        for (Map.Entry<Fluid, LongOpenHashSet> entry : mergedByFluid.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            EnhancedFluidBFS.equalizePositionKeys(level, entry.getValue(), entry.getKey(), captureCache);
            total += entry.getValue().size();
        }
        return total;
    }

    public static void clearDimension(LevelAccessor level) {
        if (level != null) {
            QUEUED.remove(DimensionKey.of(level));
        }
    }

    public static void clearAll() {
        QUEUED.clear();
        ACTIVE.clear();
    }

    public static void shutdown() {
        clearAll();
        DIRECTION_CACHE.remove();
        shutdownPool();
    }

    private static ForkJoinPool createPool() {
        return new ForkJoinPool(
            PARALLELISM,
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            (thread, error) -> FlowingFluids.error("Uncaught exception in fluid equalizer: " + error.getMessage()),
            true
        );
    }

    private static ForkJoinPool getPool() {
        ForkJoinPool current = pool;
        if (current != null && !current.isShutdown() && !current.isTerminated() && !current.isTerminating()) {
            return current;
        }
        synchronized (ParallelFluidEqualizer.class) {
            current = pool;
            if (current == null || current.isShutdown() || current.isTerminated() || current.isTerminating()) {
                pool = createPool();
                FlowingFluids.warn("Recreated parallel fluid equalizer worker pool after shutdown.");
            }
            return pool;
        }
    }

    private static CompletableFuture<Result> submitAsync(Request request) {
        try {
            return CompletableFuture.supplyAsync(() -> computeResult(request), getPool());
        } catch (RejectedExecutionException exception) {
            FlowingFluids.warn("Equalizer worker pool rejected a task; recreating pool and retrying.");
            synchronized (ParallelFluidEqualizer.class) {
                if (pool == null || pool.isShutdown() || pool.isTerminated() || pool.isTerminating()) {
                    pool = createPool();
                }
            }
            try {
                return CompletableFuture.supplyAsync(() -> computeResult(request), getPool());
            } catch (RejectedExecutionException retryFailure) {
                FlowingFluids.warn("Equalizer worker pool still unavailable; falling back to synchronous compute.");
                return CompletableFuture.completedFuture(computeResult(request));
            }
        }
    }

    private static void shutdownPool() {
        ForkJoinPool current;
        synchronized (ParallelFluidEqualizer.class) {
            current = pool;
            pool = null;
        }
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(3, TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            current.shutdownNow();
        }
    }

    private static SelectionResult selectRepresentativeSources(Level level, Set<Long> queued) {
        int horizontalBucketSize = getSelectionBucketSize();
        int verticalBucketSize = Math.max(4, horizontalBucketSize / 2);
        Map<Fluid, Long2ObjectOpenHashMap<List<ScanCandidate>>> bucketsByFluid = new LinkedHashMap<>();
        Map<Fluid, Long2ObjectOpenHashMap<Set<Integer>>> seenComponentsByFluid = new LinkedHashMap<>();
        LongOpenHashSet deferred = new LongOpenHashSet();
        int candidateCount = 0;
        int momentumAge = FlowingFluids.config != null
            ? Math.max(8, FlowingFluids.config.flowInertiaMaxAgeTicks / 2)
            : 20;

        for (long posKey : queued) {
            if (ACTIVE.contains(posKey)) {
                continue;
            }
            BlockPos pos = BlockPos.of(posKey);
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (!AdaptiveTickScheduler.hasForcedRecheck(level, pos)
                && AdaptiveTickScheduler.wasChunkTouchedRecently(level, pos, 0)) {
                int queuedAmount = FluidSpatialGrid.getFluidAmount(level, pos);
                if (queuedAmount > 0
                    && shouldSkipQueuedSurgeCandidate(
                        false,
                        true,
                        AdaptiveTickScheduler.isFlowActiveNow(level, pos),
                        AdaptiveTickScheduler.getFlowMomentum(level, pos, momentumAge),
                        queuedAmount)) {
                    deferred.add(posKey);
                    continue;
                }
            }
            ScanCandidate candidate = scanCandidate(level, posKey);
            if (candidate == null) {
                continue;
            }
            long bucketKey = packSelectionBucket(candidate.pos(), horizontalBucketSize, verticalBucketSize);
            if (candidate.componentId() > 0) {
                Long2ObjectOpenHashMap<Set<Integer>> seenByBucket = seenComponentsByFluid
                    .computeIfAbsent(candidate.fluidType(), ignored -> new Long2ObjectOpenHashMap<>());
                if (shouldSkipComponentCandidate(seenByBucket, bucketKey, candidate.componentId())) {
                    continue;
                }
            }
            Long2ObjectOpenHashMap<List<ScanCandidate>> fluidBuckets =
                bucketsByFluid.computeIfAbsent(candidate.fluidType(), ignored -> new Long2ObjectOpenHashMap<>());
            List<ScanCandidate> bucketCandidates = fluidBuckets.get(bucketKey);
            if (bucketCandidates == null) {
                bucketCandidates = new ArrayList<>();
                fluidBuckets.put(bucketKey, bucketCandidates);
            }
            bucketCandidates.add(candidate);
            candidateCount++;
        }

        if (candidateCount == 0) {
            return new SelectionResult(List.of(), deferred);
        }

        List<ScanCandidate> selected = new ArrayList<>(Math.max(16, candidateCount / 2));
        for (Long2ObjectOpenHashMap<List<ScanCandidate>> fluidBuckets : bucketsByFluid.values()) {
            for (List<ScanCandidate> bucketCandidates : fluidBuckets.values()) {
                selectBucketRepresentatives(bucketCandidates, selected, horizontalBucketSize, verticalBucketSize);
            }
        }
        return new SelectionResult(selected, deferred);
    }

    private static void requeueDeferred(LevelAccessor level, LongOpenHashSet deferred) {
        if (level == null || deferred == null || deferred.isEmpty()) {
            return;
        }
        Set<Long> queue = QUEUED.computeIfAbsent(DimensionKey.of(level), ignored -> ConcurrentHashMap.newKeySet());
        for (long posKey : deferred) {
            queue.add(posKey);
        }
    }

    private static ScanCandidate scanCandidate(Level level, long posKey) {
        BlockPos pos = BlockPos.of(posKey);
        if (!level.isLoaded(pos)) {
            return null;
        }
        FluidState startFluid = FFFluidUtils.getEffectiveFluidState(level, pos);
        if (startFluid.isEmpty()) {
            return null;
        }

        int startAmount = FluidSpatialGrid.getFluidAmount(level, pos);
        if (startAmount <= 0) {
            startAmount = FluidAmountConverter.toInternal(startFluid.getAmount());
        }
        int componentId = FluidSpatialGrid.getComponentId(level, pos);
        return new ScanCandidate(pos.immutable(), startFluid.getType(), startAmount,
            AdaptiveTickScheduler.hasForcedRecheck(level, pos), componentId);
    }

    private static void selectBucketRepresentatives(List<ScanCandidate> bucketCandidates, List<ScanCandidate> selected,
                                                    int horizontalBucketSize, int verticalBucketSize) {
        if (bucketCandidates.isEmpty()) {
            return;
        }
        bucketCandidates.sort((left, right) -> Integer.compare(scoreCandidate(right), scoreCandidate(left)));

        int maxRepresentatives = getBucketRepresentativeLimit(bucketCandidates);
        int horizontalSpacing = Math.max(4, horizontalBucketSize / 2);
        int verticalSpacing = Math.max(2, verticalBucketSize / 2);
        List<ScanCandidate> chosen = new ArrayList<>(maxRepresentatives);

        for (ScanCandidate candidate : bucketCandidates) {
            if (chosen.size() >= maxRepresentatives) {
                break;
            }
            int effectiveHorizontalSpacing = candidate.forcedRecheck()
                ? Math.max(2, horizontalSpacing / 2)
                : horizontalSpacing;
            int effectiveVerticalSpacing = candidate.forcedRecheck()
                ? Math.max(1, verticalSpacing / 2)
                : verticalSpacing;
            if (isSufficientlySeparated(candidate, chosen, effectiveHorizontalSpacing, effectiveVerticalSpacing)) {
                chosen.add(candidate);
            }
        }

        if (chosen.isEmpty()) {
            chosen.add(bucketCandidates.get(0));
        }
        selected.addAll(chosen);
    }

    private static int getBucketRepresentativeLimit(List<ScanCandidate> bucketCandidates) {
        int limit = 1;
        if (bucketCandidates.size() >= 12) {
            limit++;
        }
        if (bucketCandidates.size() >= 40) {
            limit++;
        }
        int forcedCount = 0;
        for (ScanCandidate candidate : bucketCandidates) {
            if (candidate.forcedRecheck()) {
                forcedCount++;
            }
        }
        if (forcedCount >= 2) {
            limit = Math.max(limit, 2);
        }
        return Math.min(MAX_BUCKET_REPRESENTATIVES, limit);
    }

    private static boolean isSufficientlySeparated(ScanCandidate candidate, List<ScanCandidate> chosen,
                                                   int horizontalSpacing, int verticalSpacing) {
        int horizontalSpacingSquared = horizontalSpacing * horizontalSpacing;
        for (ScanCandidate existing : chosen) {
            int dx = candidate.pos().getX() - existing.pos().getX();
            int dz = candidate.pos().getZ() - existing.pos().getZ();
            int dy = Math.abs(candidate.pos().getY() - existing.pos().getY());
            if ((dx * dx + dz * dz) < horizontalSpacingSquared && dy < verticalSpacing) {
                return false;
            }
        }
        return true;
    }

    private static int scoreCandidate(ScanCandidate candidate) {
        return candidate.amount() + (candidate.forcedRecheck() ? 4096 : 0);
    }

    private static long packSelectionBucket(BlockPos pos, int horizontalBucketSize, int verticalBucketSize) {
        int bucketX = Math.floorDiv(pos.getX(), horizontalBucketSize);
        int bucketY = Math.floorDiv(pos.getY(), verticalBucketSize);
        int bucketZ = Math.floorDiv(pos.getZ(), horizontalBucketSize);
        return BlockPos.asLong(bucketX, bucketY, bucketZ);
    }

    private static int getSelectionBucketSize() {
        int configuredRadius = Math.max(8, FlowingFluids.config.bfsMaxSearchDistance);
        return Math.max(MIN_SELECTION_BUCKET_SIZE, Math.min(MAX_SELECTION_BUCKET_SIZE, configuredRadius / 2));
    }

    static boolean shouldSkipComponentCandidate(Long2ObjectOpenHashMap<Set<Integer>> seenByBucket,
                                                long bucketKey,
                                                int componentId) {
        if (seenByBucket == null || componentId <= 0) {
            return false;
        }
        Set<Integer> seenComponents = seenByBucket.get(bucketKey);
        if (seenComponents == null) {
            seenComponents = new HashSet<>();
            seenByBucket.put(bucketKey, seenComponents);
        }
        return !seenComponents.add(componentId);
    }

    private static Request prepare(Level level, ScanCandidate candidate, FluidSectionDataCache captureCache) {
        if (!level.isLoaded(candidate.pos())) {
            return null;
        }
        BlockPos startPos = candidate.pos();

        boolean forcedRecheck = candidate.forcedRecheck() && AdaptiveTickScheduler.consumeForcedRecheck(level, startPos);
        boolean shouldRunBfs = AdaptiveTickScheduler.shouldRunBFS(level, startPos, candidate.amount());
        if (!shouldRunBfs && !forcedRecheck) {
            return null;
        }
        if (!forcedRecheck && shouldDelayFreshSurgeEqualization(level, startPos, candidate.amount())) {
            return null;
        }

        int maxDepth = EnhancedFluidBFS.getDynamicDepth(level, startPos);
        int maxNodes = AdaptiveTickScheduler.getBFSBudget(level, startPos);
        if (forcedRecheck && !shouldRunBfs) {
            float factor = Math.max(0.1f, FlowingFluids.config.forcedEqualizationBudgetFactor);
            maxDepth = Math.max(8, Math.round(maxDepth * factor));
            maxNodes = Math.max(128, Math.round(maxNodes * factor));
        }

        FluidState startState = FFFluidUtils.getEffectiveFluidState(level, startPos);
        int profileAmount = !startState.isEmpty()
            ? startState.getAmount()
            : FluidAmountConverter.toBlockState(candidate.amount());
        WaterFlowProfile flowProfile = WaterFlowProfile.analyze(level, startPos, startState, profileAmount, captureCache);
        float distanceLoadFactor = flowProfile.adjustEqualizerLoadFactor(getDistanceLoadSheddingFactor());
        if (!forcedRecheck && flowProfile.isInletZone()) {
            maxDepth = Math.min(maxDepth, 6);
            maxNodes = Math.min(maxNodes, 256);
        }
        if (!forcedRecheck && flowProfile.isCalmInterior()) {
            maxNodes = Math.min(maxNodes, CALM_SURFACE_MAX_NODES);
        }
        maxDepth = flowProfile.clampEqualizerDepth(maxDepth, FlowingFluids.config.bfsMaxSearchDistance);
        maxNodes = flowProfile.clampEqualizerNodes(maxNodes);
        int minDepth = flowProfile.getMinimumEqualizerDepth();
        maxNodes = Math.max(flowProfile.getMinimumEqualizerNodes(), Math.round(maxNodes * distanceLoadFactor));
        maxDepth = Math.min(Math.max(minDepth, FlowingFluids.config.bfsMaxSearchDistance), maxDepth);
        maxDepth = Math.max(minDepth, Math.round(maxDepth * Math.max(0.6f, distanceLoadFactor)));

        int snapshotRadius = flowProfile.computeDistanceScaledSnapshotRadius(maxDepth, distanceLoadFactor);

        return new Request(
            startPos.immutable(),
            candidate.fluidType(),
            candidate.amount(),
            maxDepth,
            maxNodes,
            distanceLoadFactor,
            FluidSpatialGrid.getGradientDirection(level, startPos),
            ChunkLocalSlopeCache.getGradientVector(level, new ChunkPos(startPos), startPos),
            Snapshot.capture(level, startPos, snapshotRadius, candidate.fluidType(), captureCache),
            flowProfile
        );
    }

    private static boolean shouldDelayFreshSurgeEqualization(Level level, BlockPos pos, int amount) {
        if (level == null || pos == null) {
            return false;
        }
        int momentumAge = FlowingFluids.config != null
            ? Math.max(8, FlowingFluids.config.flowInertiaMaxAgeTicks / 2)
            : 20;
        return shouldSkipQueuedSurgeCandidate(
            false,
            AdaptiveTickScheduler.wasChunkTouchedRecently(level, pos, 0),
            AdaptiveTickScheduler.isFlowActiveNow(level, pos),
            AdaptiveTickScheduler.getFlowMomentum(level, pos, momentumAge),
            amount
        );
    }

    static boolean shouldSkipQueuedSurgeCandidate(boolean forcedRecheck,
                                                  boolean chunkTouchedRecently,
                                                  boolean flowActive,
                                                  float flowMomentum,
                                                  int amount) {
        if (forcedRecheck || !chunkTouchedRecently) {
            return false;
        }
        if (flowActive) {
            return true;
        }
        if (flowMomentum > 0.2f) {
            return true;
        }
        return amount >= FluidAmountConverter.toInternal(7);
    }

    private static Result computeResult(Request request) {
        return new Result(request.fluidType(), compute(request));
    }

    private static ComputationResult compute(Request request) {
        try {
            return computeInternal(request);
        } finally {
            ACTIVE.remove(request.startPos().asLong());
        }
    }

    private static ComputationResult computeInternal(Request request) {
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        IntArrayFIFOQueue depthQueue = new IntArrayFIFOQueue();
        LongOpenHashSet visited = new LongOpenHashSet(Math.max(64, request.maxNodes()));
        LongOpenHashSet targetKeys = new LongOpenHashSet();
        LongArrayList visitedOrder = new LongArrayList(Math.max(64, Math.min(request.maxNodes(), 2048)));
        LongArrayList targets = new LongArrayList(Math.max(64, Math.min(request.maxNodes(), 2048)));
        EqualizationContext context = new EqualizationContext(request.maxNodes());

        long startKey = request.startPos().asLong();
        queue.enqueue(startKey);
        depthQueue.enqueue(0);
        visited.add(startKey);
        addTarget(targets, targetKeys, startKey);

        int nodesExplored = 0;
        int momentumBudget = 0;
        int momentumCap = Math.round(getDistanceScaledMomentumCap() * Math.max(0.7f, request.distanceLoadFactor()));
        momentumCap = request.flowProfile().clampMomentumCap(momentumCap);
        int minVisitedAmount = request.startAmount();
        int maxVisitedAmount = request.startAmount();
        boolean dropEncountered = false;
        Direction[] directions = prioritizeDownward(getWeightedDirections(request.gradientVector()));

        while (!queue.isEmpty() && nodesExplored < request.maxNodes() + momentumBudget) {
            long current = queue.dequeueLong();
            int currentDepth = depthQueue.dequeueInt();
            int currentX = BlockPos.getX(current);
            int currentY = BlockPos.getY(current);
            int currentZ = BlockPos.getZ(current);
            int currentAmount = request.snapshot().amount(currentX, currentY, currentZ);
            nodesExplored++;

            minVisitedAmount = Math.min(minVisitedAmount, currentAmount);
            maxVisitedAmount = Math.max(maxVisitedAmount, currentAmount);

            boolean skipUpward = request.distanceLoadFactor() < 0.75f && nodesExplored > (request.maxNodes() / 2);

            for (Direction direction : directions) {
                if (skipUpward && direction == Direction.UP) {
                    continue;
                }
                int nx = currentX + direction.getStepX();
                int ny = currentY + direction.getStepY();
                int nz = currentZ + direction.getStepZ();
                long neighbor = BlockPos.asLong(nx, ny, nz);
                if (visited.contains(neighbor) || !request.snapshot().canInclude(nx, ny, nz)) {
                    continue;
                }

                int nextDepth = currentDepth + 1;
                if (nextDepth > request.maxDepth()) {
                    continue;
                }

                visited.add(neighbor);
                visitedOrder.add(neighbor);
                queue.enqueue(neighbor);
                depthQueue.enqueue(nextDepth);

                int neighborAmount = request.snapshot().amount(nx, ny, nz);
                minVisitedAmount = Math.min(minVisitedAmount, neighborAmount);
                maxVisitedAmount = Math.max(maxVisitedAmount, neighborAmount);

                boolean isDrop = currentY > ny;
                if (shouldEqualize(currentAmount, neighborAmount, context) || isDrop) {
                    dropEncountered = dropEncountered || isDrop;
                    addTarget(targets, targetKeys, current);
                    addTarget(targets, targetKeys, neighbor);
                }
                if (currentY > ny) {
                    momentumBudget = Math.min(momentumCap, momentumBudget + (currentY - ny));
                }
            }

            if (!dropEncountered && visited.size() > 64 && (nodesExplored % 64 == 0) && maxVisitedAmount - minVisitedAmount <= 1) {
                break;
            }
        }

        int inletSteps = request.flowProfile().shouldRunInletProbe()
            ? Math.max(0, Math.round(FlowingFluids.config.inletProbeMaxSteps * request.distanceLoadFactor()))
            : 0;
        if (inletSteps > 0) {
            runInletProbe(request, visited, visitedOrder, targets, targetKeys, context, inletSteps);
        }
        boolean shouldPromoteVisited = dropEncountered
            || maxVisitedAmount - minVisitedAmount >= request.flowProfile().getVisitedPromotionVarianceThreshold();
        if (shouldPromoteVisited) {
            for (int i = 0; i < visitedOrder.size(); i++) {
                addTarget(targets, targetKeys, visitedOrder.getLong(i));
            }
        }
        LongArrayList componentPositions = new LongArrayList(visitedOrder.size() + 1);
        if (request.snapshot().hasSameFluid(request.startPos().getX(), request.startPos().getY(), request.startPos().getZ())) {
            componentPositions.add(startKey);
        }
        componentPositions.addAll(visitedOrder);
        trimComponentPositionsToSameFluid(request, componentPositions);
        return new ComputationResult(targets, componentPositions);
    }

    private static void trimComponentPositionsToSameFluid(Request request, LongArrayList componentPositions) {
        if (request == null || componentPositions == null || componentPositions.isEmpty()) {
            return;
        }
        int writeIndex = 0;
        for (int i = 0; i < componentPositions.size(); i++) {
            long posKey = componentPositions.getLong(i);
            int x = BlockPos.getX(posKey);
            int y = BlockPos.getY(posKey);
            int z = BlockPos.getZ(posKey);
            if (!request.snapshot().hasSameFluid(x, y, z)) {
                continue;
            }
            componentPositions.set(writeIndex++, posKey);
        }
        componentPositions.size(writeIndex);
    }

    private static void runInletProbe(Request request, LongOpenHashSet visited, LongArrayList visitedOrder,
                                      LongArrayList targets, LongOpenHashSet targetKeys, EqualizationContext context, int maxSteps) {
        Direction gradient = request.inletGradient();
        if (gradient == null || gradient.getAxis() == Direction.Axis.Y) {
            return;
        }
        int x = request.startPos().getX();
        int y = request.startPos().getY();
        int z = request.startPos().getZ();
        int lastAmount = request.startAmount();

        for (int i = 0; i < Math.min(32, maxSteps); i++) {
            x += gradient.getStepX();
            y += gradient.getStepY();
            z += gradient.getStepZ();
            long key = BlockPos.asLong(x, y, z);
            if (visited.contains(key) || !request.snapshot().canInclude(x, y, z)) {
                break;
            }
            visited.add(key);
            visitedOrder.add(key);
            int amount = request.snapshot().amount(x, y, z);
            if (shouldEqualize(lastAmount, amount, context) || i == 0) {
                addTarget(targets, targetKeys, key);
                addTarget(targets, targetKeys, request.startPos().asLong());
            }
            lastAmount = amount;
        }
    }

    private static void addTarget(LongArrayList targets, LongOpenHashSet keys, long posKey) {
        if (keys.add(posKey)) {
            targets.add(posKey);
        }
    }

    private static void cacheComponentMembership(Level level, LongArrayList componentPositions) {
        if (componentPositions == null || componentPositions.isEmpty()) {
            return;
        }
        int componentId = FluidSpatialGrid.allocateComponentId();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < componentPositions.size(); i++) {
            long posKey = componentPositions.getLong(i);
            mutablePos.set(BlockPos.getX(posKey), BlockPos.getY(posKey), BlockPos.getZ(posKey));
            FluidSpatialGrid.setComponentId(level, mutablePos, componentId);
        }
    }

    private static boolean shouldEqualize(int amount1, int amount2, EqualizationContext context) {
        int diff = Math.abs(amount1 - amount2);
        if (diff >= 2) {
            return true;
        }
        return diff == 1 && context.allow() && (amount1 > 0 || amount2 > 0);
    }

    private static Direction[] getWeightedDirections(Vec3i gradientVector) {
        if (gradientVector == null) {
            return DEFAULT_DIRECTIONS;
        }
        Map<Vec3i, Direction[]> cache = DIRECTION_CACHE.get();
        Direction[] cached = cache.get(gradientVector);
        if (cached != null) {
            return cached;
        }
        Direction[] directions = Direction.values().clone();
        Arrays.sort(directions, (a, b) -> Float.compare(
            ChunkLocalSlopeCache.calculateDirectionWeight(gradientVector, a),
            ChunkLocalSlopeCache.calculateDirectionWeight(gradientVector, b)
        ));
        cache.put(gradientVector, directions);
        return directions;
    }

    private static Direction[] prioritizeDownward(Direction[] directions) {
        if (directions.length == 0 || directions[0] == Direction.DOWN) {
            return directions;
        }
        Direction[] reordered = directions.clone();
        for (int i = 1; i < reordered.length; i++) {
            if (reordered[i] == Direction.DOWN) {
                reordered[i] = reordered[0];
                reordered[0] = Direction.DOWN;
                break;
            }
        }
        return reordered;
    }

    private static int getDistanceScaledMomentumCap() {
        int configured = Math.max(FlowingFluids.config.waterFlowDistance, 1);
        int maxDistance = Math.max(1, FlowingFluids.config.maxWaterFlowDistance);
        int distance = Math.min(configured, maxDistance);
        if (distance <= 4) {
            return MAX_MOMENTUM_BONUS;
        }
        float logScale = (float) (Math.log(4.0) / Math.log(distance));
        return Math.max(64, Math.round(MAX_MOMENTUM_BONUS * Math.max(0.35f, logScale)));
    }

    private static float getDistanceLoadSheddingFactor() {
        int configured = Math.max(FlowingFluids.config.waterFlowDistance, 1);
        int maxDistance = Math.max(1, FlowingFluids.config.maxWaterFlowDistance);
        int distance = Math.min(configured, maxDistance);
        if (distance <= 3) {
            return 1.0f;
        }
        return Math.max(0.35f, Math.min(1.0f, 3.0f / distance));
    }

    private record Request(BlockPos startPos, Fluid fluidType, int startAmount, int maxDepth, int maxNodes,
                           float distanceLoadFactor, Direction inletGradient, Vec3i gradientVector, Snapshot snapshot,
                           WaterFlowProfile flowProfile) {
    }

    private record Result(Fluid fluidType, ComputationResult computation) {
        private LongArrayList targets() {
            return computation.targets();
        }

        private LongArrayList componentPositions() {
            return computation.componentPositions();
        }
    }

    private record ScanCandidate(BlockPos pos, Fluid fluidType, int amount, boolean forcedRecheck, int componentId) {
    }

    private record SelectionResult(List<ScanCandidate> selected, LongOpenHashSet deferred) {
    }

    private record ComputationResult(LongArrayList targets, LongArrayList componentPositions) {
    }

    private static final class EqualizationContext {
        private final int allowance;
        private int used;

        private EqualizationContext(int nodeBudget) {
            allowance = Math.min(64, Math.max(8, nodeBudget / 32));
        }

        private boolean allow() {
            if (used >= allowance) {
                return false;
            }
            used++;
            return true;
        }
    }

    private static final class Snapshot {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final byte[] flags;
        private final short[] amounts;

        private Snapshot(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, byte[] flags, short[] amounts) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.flags = flags;
            this.amounts = amounts;
        }

        private static Snapshot capture(Level level, BlockPos center, int radius, Fluid sourceFluid, FluidSectionDataCache captureCache) {
            int minX = center.getX() - radius;
            int maxX = center.getX() + radius;
            int minZ = center.getZ() - radius;
            int maxZ = center.getZ() + radius;
            int minY = Math.max(level.getMinBuildHeight(), center.getY() - radius);
            int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + radius);
            int sizeX = maxX - minX + 1;
            int sizeY = maxY - minY + 1;
            int sizeZ = maxZ - minZ + 1;
            int totalSize = sizeX * sizeY * sizeZ;
            byte[] flags = new byte[totalSize];
            short[] amounts = new short[totalSize];

            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        int index = index(minX, minY, minZ, sizeX, sizeZ, x, y, z);
                        byte cellFlags = captureCache.flags(x, y, z);
                        if ((cellFlags & LOADED) == 0) {
                            continue;
                        }
                        if (captureCache.fluidType(x, y, z) == sourceFluid) {
                            cellFlags |= SAME_FLUID;
                        }
                        flags[index] = cellFlags;
                        amounts[index] = captureCache.rawAmount(x, y, z);
                    }
                }
            }
            return new Snapshot(minX, minY, minZ, sizeX, sizeY, sizeZ, flags, amounts);
        }

        private boolean canInclude(int x, int y, int z) {
            if (!contains(x, y, z)) {
                return false;
            }
            byte cellFlags = flags[index(x, y, z)];
            if ((cellFlags & LOADED) == 0) {
                return false;
            }
            if ((cellFlags & SAME_FLUID) != 0) {
                return true;
            }
            if ((cellFlags & AIR) != 0 || (cellFlags & REPLACEABLE) != 0) {
                return true;
            }
            return (cellFlags & HAS_FLUID) == 0 && (cellFlags & SOLID) == 0;
        }

        private int amount(int x, int y, int z) {
            if (!contains(x, y, z)) {
                return 0;
            }
            return amounts[index(x, y, z)] & 0xFFFF;
        }

        private boolean hasSameFluid(int x, int y, int z) {
            if (!contains(x, y, z)) {
                return false;
            }
            return (flags[index(x, y, z)] & SAME_FLUID) != 0;
        }

        private boolean contains(int x, int y, int z) {
            return x >= minX && x < minX + sizeX
                && y >= minY && y < minY + sizeY
                && z >= minZ && z < minZ + sizeZ;
        }

        private int index(int x, int y, int z) {
            return index(minX, minY, minZ, sizeX, sizeZ, x, y, z);
        }

        private static int index(int minX, int minY, int minZ, int sizeX, int sizeZ, int x, int y, int z) {
            return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
        }
    }

    private static boolean isCalmBroadSurface(Level level, BlockPos pos, Fluid fluidType, int amount) {
        if (level == null
                || fluidType != Fluids.WATER
                || amount <= 0
                || !FlowingFluids.config.broadSurfaceSuppressionEnabled) {
            return false;
        }

        FluidState state = FFFluidUtils.getEffectiveFluidState(level, pos);
        if (!state.is(FluidTags.WATER) || AdaptiveTickScheduler.isFlowActiveNow(level, pos)) {
            return false;
        }

        var biome = level.getBiome(pos);
        boolean oceanLikeBiome = FFFluidUtils.isOceanBiome(biome) || FFFluidUtils.isBeachBiome(biome);
        boolean riverLikeBiome = FFFluidUtils.isRiverBiome(biome);
        boolean hasFluidAbove = hasFluidAbove(level, pos, fluidType);
        boolean downwardOutlet = hasImmediateDownwardOutlet(level, pos, fluidType, amount);
        boolean supportedBelow = isSupportedBelow(level, pos, fluidType, amount);
        int lateralNeighbors = countLateralWaterNeighbors(level, pos, fluidType);
        int stableTicks = AdaptiveTickScheduler.getPoolStableTicks(level, pos, 20);

        if (!FFFluidUtils.classifyBroadSurfaceWater(oceanLikeBiome, riverLikeBiome, lateralNeighbors,
                hasFluidAbove, supportedBelow, downwardOutlet, stableTicks,
                FlowingFluids.config.broadSurfaceStableTicks)) {
            return false;
        }

        return !hasImmediateSurfaceEdge(level, pos, fluidType);
    }

    private static int countLateralWaterNeighbors(Level level, BlockPos pos, Fluid fluidType) {
        int count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction dir : HORIZONTAL_DIRECTIONS) {
            cursor.setWithOffset(pos, dir);
            FluidState neighbor = FFFluidUtils.getEffectiveFluidState(level, cursor, level.getBlockState(cursor));
            if (neighbor.getType().isSame(fluidType) && neighbor.getAmount() > 0) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasFluidAbove(Level level, BlockPos pos, Fluid fluidType) {
        BlockPos abovePos = pos.above();
        FluidState above = FFFluidUtils.getEffectiveFluidState(level, abovePos, level.getBlockState(abovePos));
        return above.getType().isSame(fluidType) && above.getAmount() > 0;
    }

    private static boolean isSupportedBelow(Level level, BlockPos pos, Fluid fluidType, int amount) {
        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        FluidState belowFluid = FFFluidUtils.getEffectiveFluidState(level, belowPos, belowState);
        return (belowFluid.getType().isSame(fluidType) && belowFluid.getAmount() >= amount)
            || (!belowState.isAir() && !belowState.canBeReplaced(fluidType));
    }

    private static boolean hasImmediateSurfaceEdge(Level level, BlockPos pos, Fluid fluidType) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction dir : HORIZONTAL_DIRECTIONS) {
            cursor.setWithOffset(pos, dir);
            BlockState state = level.getBlockState(cursor);
            FluidState neighbor = FFFluidUtils.getEffectiveFluidState(level, cursor, state);
            if (neighbor.isEmpty() && (state.isAir() || state.canBeReplaced(fluidType))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasImmediateDownwardOutlet(Level level, BlockPos pos, Fluid fluidType, int amount) {
        if (!(fluidType instanceof FlowingFluid flowingFluid)) {
            return false;
        }
        BlockState stateAtPos = level.getBlockState(pos);
        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        FluidState belowFluid = FFFluidUtils.getEffectiveFluidState(level, belowPos, belowState);
        if (!FFFluidUtils.canFluidFlowFromPosToDirection(flowingFluid, Math.max(1, amount), level, pos, stateAtPos,
                Direction.DOWN, belowPos, belowState, belowFluid)) {
            return false;
        }
        return belowFluid.isEmpty() || !belowFluid.getType().isSame(fluidType) || belowFluid.getAmount() < amount;
    }

}
