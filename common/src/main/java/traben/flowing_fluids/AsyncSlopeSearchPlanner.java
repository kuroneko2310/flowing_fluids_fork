package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import traben.flowing_fluids.util.DimensionKey;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plans expensive slope-distance searches off-thread from an immutable snapshot.
 *
 * The actual world write still happens on the server thread. This class only
 * precomputes deep lateral slope distances for far/distant water columns so
 * the tick thread can reuse the answer from ChunkLocalSlopeCache later.
 */
public final class AsyncSlopeSearchPlanner {
    private static final int NO_SLOPE_FOUND = 1000;
    private static final BlockState OUTSIDE_STATE = Blocks.BEDROCK.defaultBlockState();
    private static final FluidState OUTSIDE_FLUID = OUTSIDE_STATE.getFluidState();

    private static final ConcurrentHashMap<RequestKey, CompletableFuture<Integer>> PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<RequestKey, Integer> COMPLETED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<DimensionKey, AtomicLong> DIMENSION_EPOCHS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChunkScope, AtomicLong> CHUNK_EPOCHS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChunkScope, Set<RequestKey>> REQUESTS_BY_CHUNK = new ConcurrentHashMap<>();

    private static volatile ForkJoinPool pool = createPool();

    private AsyncSlopeSearchPlanner() {
    }

    public static boolean canUseAsyncPlanning(Level level, BlockPos sourcePos, BlockState sourceState,
                                              Direction[] directions, int directionCount) {
        if (level == null || sourcePos == null || sourceState == null || directionCount <= 0) {
            return false;
        }
        if (!FlowingFluids.config.enableDistanceBasedOptimization) {
            return false;
        }
        if (FFFluidUtils.supportsVirtualFluidState(level, sourceState)) {
            return false;
        }
        for (int i = 0; i < directionCount; i++) {
            Direction direction = directions[i];
            if (FFFluidUtils.isPassThroughFluidBlock(level, sourceState, direction)) {
                return false;
            }
            BlockPos neighborPos = sourcePos.relative(direction);
            BlockState neighborState = level.getBlockState(neighborPos);
            if (FFFluidUtils.supportsVirtualFluidState(level, neighborState)
                    || FFFluidUtils.isPassThroughFluidBlock(level, neighborState, direction)) {
                return false;
            }
            BlockPos belowPos = neighborPos.below();
            if (!level.isLoaded(belowPos)) {
                continue;
            }
            BlockState belowState = level.getBlockState(belowPos);
            if (FFFluidUtils.supportsVirtualFluidState(level, belowState)) {
                return false;
            }
        }
        return true;
    }

    public static @Nullable Integer tryResolveOrSchedule(Level level, BlockPos sourcePos, Direction direction,
                                                         Fluid sourceFluid, int sourceAmount,
                                                         boolean enforceSameFluidOrEmpty, int slopeFindDistance) {
        if (!(sourceFluid instanceof FlowingFluid flowingFluid)
                || level == null
                || sourcePos == null
                || direction == null
                || slopeFindDistance < 1) {
            return null;
        }

        RequestKey key = new RequestKey(
                DimensionKey.of(level),
                sourcePos.asLong(),
                currentDimensionEpoch(level),
                currentChunkEpoch(level, sourcePos),
                direction,
                sourceFluid,
                sourceAmount,
                enforceSameFluidOrEmpty,
                slopeFindDistance
        );

        Integer completed = COMPLETED.remove(key);
        if (completed != null) {
            unindexRequest(key);
            return completed;
        }

        CompletableFuture<Integer> existing = PENDING.get(key);
        if (existing != null) {
            if (existing.isDone()) {
                return consumeCompleted(key, existing);
            }
            return null;
        }

        SlopeSnapshot snapshot = SlopeSnapshot.capture(level, sourcePos, slopeFindDistance + 1);
        CompletableFuture<Integer> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> computeSlopeDistance(snapshot, sourcePos, direction, flowingFluid, sourceAmount,
                            enforceSameFluidOrEmpty, slopeFindDistance),
                    getPool());
        } catch (RejectedExecutionException exception) {
            FlowingFluids.warn("Async slope planner rejected a task; falling back to deferred retry.");
            return null;
        }

        CompletableFuture<Integer> previous = PENDING.putIfAbsent(key, future);
        if (previous != null) {
            future.cancel(false);
            if (previous.isDone()) {
                return consumeCompleted(key, previous);
            }
            return null;
        }
        indexRequest(key);

        future.whenComplete((distance, throwable) -> {
            if (!PENDING.remove(key, future)) {
                unindexRequest(key);
                return;
            }
            if (!isRequestStillCurrent(key)) {
                unindexRequest(key);
                return;
            }
            if (throwable != null) {
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause()
                        : throwable;
                FlowingFluids.warn("Async slope planner failed for " + BlockPos.of(key.sourcePos()) + " / "
                        + key.direction() + ": " + cause.getMessage());
                COMPLETED.put(key, NO_SLOPE_FOUND);
                return;
            }
            COMPLETED.put(key, distance == null ? NO_SLOPE_FOUND : distance);
        });

        return null;
    }

    public static void clearChunk(Level level, ChunkPos chunkPos) {
        if (level == null || chunkPos == null) {
            return;
        }
        DimensionKey key = DimensionKey.of(level);
        bumpChunkEpoch(key, chunkPos);
        clearIndexedChunk(new ChunkScope(key, chunkPos.x, chunkPos.z));
    }

    public static void clearDimension(Level level) {
        if (level == null) {
            return;
        }
        DimensionKey key = DimensionKey.of(level);
        bumpDimensionEpoch(key);
        for (ChunkScope scope : REQUESTS_BY_CHUNK.keySet()) {
            if (scope.dimension().equals(key)) {
                clearIndexedChunk(scope);
            }
        }
        CHUNK_EPOCHS.keySet().removeIf(scope -> scope.dimension().equals(key));
    }

    public static void clearAll() {
        DIMENSION_EPOCHS.values().forEach(AtomicLong::incrementAndGet);
        for (CompletableFuture<Integer> future : PENDING.values()) {
            future.cancel(false);
        }
        PENDING.clear();
        COMPLETED.clear();
        CHUNK_EPOCHS.clear();
        REQUESTS_BY_CHUNK.clear();
    }

    public static void shutdown() {
        clearAll();
        synchronized (AsyncSlopeSearchPlanner.class) {
            pool = null;
        }
        SharedFluidWorkerPool.shutdown();
    }

    private static @Nullable Integer consumeCompleted(RequestKey key, CompletableFuture<Integer> future) {
        try {
            Integer computed = future.join();
            PENDING.remove(key, future);
            unindexRequest(key);
            return computed == null ? NO_SLOPE_FOUND : computed;
        } catch (CompletionException exception) {
            PENDING.remove(key, future);
            unindexRequest(key);
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            FlowingFluids.warn("Async slope planner completion failed for " + BlockPos.of(key.sourcePos()) + " / "
                    + key.direction() + ": " + cause.getMessage());
            return NO_SLOPE_FOUND;
        }
    }

    private static ForkJoinPool createPool() {
        return SharedFluidWorkerPool.getPool();
    }

    private static ForkJoinPool getPool() {
        ForkJoinPool current = pool;
        if (current != null && !current.isShutdown() && !current.isTerminated() && !current.isTerminating()) {
            return current;
        }
        synchronized (AsyncSlopeSearchPlanner.class) {
            current = pool;
            if (current == null || current.isShutdown() || current.isTerminated() || current.isTerminating()) {
                pool = createPool();
                FlowingFluids.warn("Recreated async slope planner pool after shutdown.");
            }
            return pool;
        }
    }

    private static void indexRequest(RequestKey key) {
        REQUESTS_BY_CHUNK.computeIfAbsent(key.chunkScope(), ignored -> ConcurrentHashMap.newKeySet()).add(key);
    }

    private static void unindexRequest(RequestKey key) {
        ChunkScope scope = key.chunkScope();
        Set<RequestKey> requests = REQUESTS_BY_CHUNK.get(scope);
        if (requests == null) {
            return;
        }
        requests.remove(key);
        if (requests.isEmpty()) {
            REQUESTS_BY_CHUNK.remove(scope, requests);
        }
    }

    private static void clearIndexedChunk(ChunkScope scope) {
        Set<RequestKey> requests = REQUESTS_BY_CHUNK.remove(scope);
        if (requests == null || requests.isEmpty()) {
            return;
        }
        for (RequestKey request : requests) {
            CompletableFuture<Integer> pending = PENDING.remove(request);
            if (pending != null) {
                pending.cancel(false);
            }
            COMPLETED.remove(request);
        }
    }

    private static long currentDimensionEpoch(Level level) {
        return currentDimensionEpoch(DimensionKey.of(level));
    }

    private static long currentDimensionEpoch(DimensionKey dimension) {
        return DIMENSION_EPOCHS.computeIfAbsent(dimension, ignored -> new AtomicLong()).get();
    }

    private static long currentChunkEpoch(Level level, BlockPos pos) {
        return currentChunkEpoch(DimensionKey.of(level), new ChunkPos(pos));
    }

    private static long currentChunkEpoch(DimensionKey dimension, ChunkPos chunkPos) {
        return CHUNK_EPOCHS.computeIfAbsent(new ChunkScope(dimension, chunkPos.x, chunkPos.z),
                ignored -> new AtomicLong()).get();
    }

    private static void bumpDimensionEpoch(DimensionKey dimension) {
        DIMENSION_EPOCHS.computeIfAbsent(dimension, ignored -> new AtomicLong()).incrementAndGet();
    }

    private static void bumpChunkEpoch(DimensionKey dimension, ChunkPos chunkPos) {
        CHUNK_EPOCHS.computeIfAbsent(new ChunkScope(dimension, chunkPos.x, chunkPos.z),
                ignored -> new AtomicLong()).incrementAndGet();
    }

    private static boolean isRequestStillCurrent(RequestKey key) {
        if (key.dimensionEpoch() != currentDimensionEpoch(key.dimension())) {
            return false;
        }
        return key.chunkEpoch() == currentChunkEpoch(key.dimension(),
                new ChunkPos(BlockPos.getX(key.sourcePos()) >> 4, BlockPos.getZ(key.sourcePos()) >> 4));
    }

    private static int computeSlopeDistance(SlopeSnapshot snapshot, BlockPos sourcePos, Direction initialDirection,
                                            FlowingFluid sourceFluid, int sourceAmount,
                                            boolean enforceSameFluidOrEmpty, int slopeFindDistance) {
        BlockPos startPos = sourcePos.relative(initialDirection);
        if (!snapshot.containsLoaded(startPos)) {
            return NO_SLOPE_FOUND;
        }

        BlockState sourceState = snapshot.getBlockState(sourcePos);
        BlockState startState = snapshot.getBlockState(startPos);
        FluidState startFluid = snapshot.getFluidState(startPos);
        if (!canSpreadToOptionallySameOrEmpty(snapshot, sourceFluid, sourceAmount, sourcePos, sourceState,
                initialDirection, startPos, startState, startFluid, enforceSameFluidOrEmpty)) {
            return NO_SLOPE_FOUND;
        }
        if (startFluid.getAmount() < (sourceAmount - 2)
                || canFlowDown(snapshot, startPos, startState, sourceFluid, enforceSameFluidOrEmpty)) {
            return 1;
        }

        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        LongOpenHashSet visited = new LongOpenHashSet();
        queue.addLast(new SearchNode(startPos.immutable(), 1, initialDirection.getOpposite()));
        visited.add(startPos.asLong());

        while (!queue.isEmpty()) {
            SearchNode current = queue.removeFirst();
            if (current.distance() >= slopeFindDistance) {
                continue;
            }

            BlockState currentState = snapshot.getBlockState(current.pos());
            for (Direction searchDir : Direction.Plane.HORIZONTAL) {
                if (searchDir == current.fromDir()) {
                    continue;
                }

                BlockPos nextPos = current.pos().relative(searchDir);
                if (!snapshot.containsLoaded(nextPos) || !visited.add(nextPos.asLong())) {
                    continue;
                }

                BlockState nextState = snapshot.getBlockState(nextPos);
                FluidState nextFluid = snapshot.getFluidState(nextPos);
                if (!canSpreadToOptionallySameOrEmpty(snapshot, sourceFluid, sourceAmount, current.pos(), currentState,
                        searchDir, nextPos, nextState, nextFluid, enforceSameFluidOrEmpty)) {
                    continue;
                }

                int nextDistance = current.distance() + 1;
                if (nextFluid.getAmount() < (sourceAmount - 2)
                        || canFlowDown(snapshot, nextPos, nextState, sourceFluid, enforceSameFluidOrEmpty)) {
                    return nextDistance;
                }
                queue.addLast(new SearchNode(nextPos.immutable(), nextDistance, searchDir.getOpposite()));
            }
        }

        return NO_SLOPE_FOUND;
    }

    private static boolean canFlowDown(SlopeSnapshot snapshot, BlockPos pos, BlockState state,
                                       FlowingFluid sourceFluid, boolean enforceSameFluidOrEmpty) {
        BlockPos belowPos = pos.below();
        if (!snapshot.containsLoaded(belowPos)) {
            return false;
        }
        BlockState belowState = snapshot.getBlockState(belowPos);
        FluidState belowFluid = snapshot.getFluidState(belowPos);
        return canSpreadToOptionallySameOrEmpty(snapshot, sourceFluid, 8, pos, state,
                Direction.DOWN, belowPos, belowState, belowFluid, enforceSameFluidOrEmpty);
    }

    private static boolean canSpreadToOptionallySameOrEmpty(BlockGetter blockGetter, FlowingFluid sourceFluid,
                                                            int sourceAmount, BlockPos fromPos, BlockState fromState,
                                                            Direction direction, BlockPos toPos, BlockState toState,
                                                            FluidState toFluid, boolean enforceSameFluidOrEmpty) {
        if (enforceSameFluidOrEmpty && !(toFluid.isEmpty() || toFluid.getType().isSame(sourceFluid))) {
            return false;
        }
        return FFFluidUtils.canFluidFlowFromPosToDirection(sourceFluid, sourceAmount, blockGetter,
                fromPos, fromState, direction, toPos, toState, toFluid);
    }

    private record RequestKey(DimensionKey dimension, long sourcePos, long dimensionEpoch, long chunkEpoch, Direction direction,
                              Fluid sourceFluid, int sourceAmount,
                              boolean enforceSameFluidOrEmpty, int slopeFindDistance) {
        private ChunkScope chunkScope() {
            return new ChunkScope(dimension, BlockPos.getX(sourcePos) >> 4, BlockPos.getZ(sourcePos) >> 4);
        }
    }

    private record ChunkScope(DimensionKey dimension, int chunkX, int chunkZ) {
    }

    private record SearchNode(BlockPos pos, int distance, Direction fromDir) {
    }

    private static final class SlopeSnapshot implements BlockGetter {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final boolean[] loaded;
        private final BlockState[] states;
        private final FluidState[] fluids;

        private SlopeSnapshot(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
                              boolean[] loaded, BlockState[] states, FluidState[] fluids) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.loaded = loaded;
            this.states = states;
            this.fluids = fluids;
        }

        private static SlopeSnapshot capture(Level level, BlockPos center, int radius) {
            int clampedRadius = Math.max(1, radius);
            int minX = center.getX() - clampedRadius;
            int maxX = center.getX() + clampedRadius;
            int minZ = center.getZ() - clampedRadius;
            int maxZ = center.getZ() + clampedRadius;
            int minY = Math.max(level.getMinBuildHeight(), center.getY() - clampedRadius);
            int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + clampedRadius);

            int sizeX = maxX - minX + 1;
            int sizeY = maxY - minY + 1;
            int sizeZ = maxZ - minZ + 1;
            int total = sizeX * sizeY * sizeZ;

            boolean[] loaded = new boolean[total];
            BlockState[] states = new BlockState[total];
            FluidState[] fluids = new FluidState[total];
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        cursor.set(x, y, z);
                        int index = index(minX, minY, minZ, sizeX, sizeZ, x, y, z);
                        if (!level.isLoaded(cursor)) {
                            continue;
                        }
                        BlockState state = level.getBlockState(cursor);
                        states[index] = state;
                        fluids[index] = FFFluidUtils.getEffectiveFluidState(level, cursor, state);
                        loaded[index] = true;
                    }
                }
            }

            return new SlopeSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ, loaded, states, fluids);
        }

        private boolean containsLoaded(BlockPos pos) {
            if (!contains(pos.getX(), pos.getY(), pos.getZ())) {
                return false;
            }
            return loaded[index(pos.getX(), pos.getY(), pos.getZ())];
        }

        private boolean contains(int x, int y, int z) {
            return x >= minX && x < minX + sizeX
                    && y >= minY && y < minY + sizeY
                    && z >= minZ && z < minZ + sizeZ;
        }

        private int index(int x, int y, int z) {
            return index(minX, minY, minZ, sizeX, sizeZ, x, y, z);
        }

        private static int index(int minX, int minY, int minZ, int sizeX, int sizeZ,
                                 int x, int y, int z) {
            return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (!containsLoaded(pos)) {
                return OUTSIDE_STATE;
            }
            BlockState state = states[index(pos.getX(), pos.getY(), pos.getZ())];
            return state == null ? OUTSIDE_STATE : state;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            if (!containsLoaded(pos)) {
                return OUTSIDE_FLUID;
            }
            FluidState state = fluids[index(pos.getX(), pos.getY(), pos.getZ())];
            return state == null ? OUTSIDE_FLUID : state;
        }

        @Override
        public int getHeight() {
            return sizeY;
        }

        @Override
        public int getMinBuildHeight() {
            return minY;
        }
    }
}
