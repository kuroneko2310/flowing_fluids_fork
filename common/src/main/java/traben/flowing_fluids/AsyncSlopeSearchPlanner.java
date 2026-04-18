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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Plans expensive slope-distance searches off-thread from an immutable snapshot.
 *
 * The actual world write still happens on the server thread. This class only
 * precomputes deep lateral slope distances for far/distant water columns so
 * the tick thread can reuse the answer from ChunkLocalSlopeCache later.
 */
public final class AsyncSlopeSearchPlanner {
    private static final int NO_SLOPE_FOUND = 1000;
    private static final int PARALLELISM = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static final BlockState OUTSIDE_STATE = Blocks.BEDROCK.defaultBlockState();
    private static final FluidState OUTSIDE_FLUID = OUTSIDE_STATE.getFluidState();

    private static final ConcurrentHashMap<RequestKey, CompletableFuture<Integer>> PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<RequestKey, Integer> COMPLETED = new ConcurrentHashMap<>();

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
                direction,
                sourceFluid,
                sourceAmount,
                enforceSameFluidOrEmpty,
                slopeFindDistance
        );

        Integer completed = COMPLETED.remove(key);
        if (completed != null) {
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

        future.whenComplete((distance, throwable) -> {
            PENDING.remove(key);
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
        PENDING.entrySet().removeIf(entry -> cancelIfMatches(entry, entry.getKey().matchesChunk(key, chunkPos)));
        COMPLETED.entrySet().removeIf(entry -> entry.getKey().matchesChunk(key, chunkPos));
    }

    public static void clearDimension(Level level) {
        if (level == null) {
            return;
        }
        DimensionKey key = DimensionKey.of(level);
        PENDING.entrySet().removeIf(entry -> cancelIfMatches(entry, entry.getKey().dimension().equals(key)));
        COMPLETED.entrySet().removeIf(entry -> entry.getKey().dimension().equals(key));
    }

    public static void clearAll() {
        for (CompletableFuture<Integer> future : PENDING.values()) {
            future.cancel(false);
        }
        PENDING.clear();
        COMPLETED.clear();
    }

    public static void shutdown() {
        clearAll();
        ForkJoinPool current;
        synchronized (AsyncSlopeSearchPlanner.class) {
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

    private static @Nullable Integer consumeCompleted(RequestKey key, CompletableFuture<Integer> future) {
        try {
            Integer computed = future.join();
            PENDING.remove(key, future);
            return computed == null ? NO_SLOPE_FOUND : computed;
        } catch (CompletionException exception) {
            PENDING.remove(key, future);
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            FlowingFluids.warn("Async slope planner completion failed for " + BlockPos.of(key.sourcePos()) + " / "
                    + key.direction() + ": " + cause.getMessage());
            return NO_SLOPE_FOUND;
        }
    }

    private static ForkJoinPool createPool() {
        return new ForkJoinPool(
                PARALLELISM,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                (thread, error) -> FlowingFluids.error("Uncaught exception in async slope planner " + thread.getName(), error),
                true
        );
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

    private static boolean cancelIfMatches(Map.Entry<RequestKey, CompletableFuture<Integer>> entry, boolean matches) {
        if (!matches) {
            return false;
        }
        entry.getValue().cancel(false);
        return true;
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

    private record RequestKey(DimensionKey dimension, long sourcePos, Direction direction,
                              Fluid sourceFluid, int sourceAmount,
                              boolean enforceSameFluidOrEmpty, int slopeFindDistance) {
        private boolean matchesChunk(DimensionKey dimensionKey, ChunkPos chunkPos) {
            return dimension.equals(dimensionKey) && new ChunkPos(BlockPos.of(sourcePos)).equals(chunkPos);
        }
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
