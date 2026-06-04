package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class SiphonFlowSystem {
    private static final int MAX_SIPHON_PROBES_PER_TICK = 8;
    private static final int MAX_PENDING_SIPHON_PROBES_PER_DIMENSION = 2048;
    private static final int HYDRAULIC_COOLDOWN_TICKS = 6;
    private static final int PUMP_LIFT_AMOUNT = 6;
    private static final int PUMP_PRESSURE_BOOST_AMOUNT = 3;
    private static final int PUMP_MIN_SOURCE_RETAIN = 1;
    private static final int PUMP_COOLDOWN_TICKS = 1;
    private static final int PUMP_CHAIN_REACH = 8;
    private static final int PRESSURE_FIELD_AMOUNT = 4;
    private static final int PRESSURE_FIELD_BOOST_AMOUNT = 2;
    private static final int PRESSURE_FIELD_COOLDOWN_TICKS = 1;
    private static final int NATURAL_PRESSURE_HEAD_CAP = 8;
    private static final int NO_PARENT = -1;

    private static final Long2LongOpenHashMap NEXT_HYDRAULIC_PUMP_TICK = new Long2LongOpenHashMap();
    private static final Long2LongOpenHashMap NEXT_HYDRAULIC_PRESSURE_TICK = new Long2LongOpenHashMap();
    private static final Long2LongOpenHashMap NEXT_HYDRAULIC_SEARCH_TICK = new Long2LongOpenHashMap();
    private static final Long2LongOpenHashMap NEXT_NATURAL_SEARCH_TICK = new Long2LongOpenHashMap();
    private static final ConcurrentHashMap<ChunkPos, LongOpenHashSet> NEXT_HYDRAULIC_PUMP_TICK_BY_CHUNK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChunkPos, LongOpenHashSet> NEXT_HYDRAULIC_PRESSURE_TICK_BY_CHUNK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChunkPos, LongOpenHashSet> NEXT_HYDRAULIC_SEARCH_TICK_BY_CHUNK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChunkPos, LongOpenHashSet> NEXT_NATURAL_SEARCH_TICK_BY_CHUNK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ResourceKey<Level>, SiphonDimensionQueue> SIPHON_QUEUES = new ConcurrentHashMap<>();
    private static final Object SIPHON_LOCK = new Object();

    private SiphonFlowSystem() {
    }

    public static void clearDimension(Level level) {
        if (level == null) {
            return;
        }
        SIPHON_QUEUES.remove(level.dimension());
        synchronized (SIPHON_LOCK) {
            NEXT_HYDRAULIC_PUMP_TICK.clear();
            NEXT_HYDRAULIC_PRESSURE_TICK.clear();
            NEXT_HYDRAULIC_SEARCH_TICK.clear();
            NEXT_NATURAL_SEARCH_TICK.clear();
            NEXT_HYDRAULIC_PUMP_TICK_BY_CHUNK.clear();
            NEXT_HYDRAULIC_PRESSURE_TICK_BY_CHUNK.clear();
            NEXT_HYDRAULIC_SEARCH_TICK_BY_CHUNK.clear();
            NEXT_NATURAL_SEARCH_TICK_BY_CHUNK.clear();
        }
    }

    public static void clearChunk(Level level, ChunkPos chunkPos) {
        if (level == null || chunkPos == null) {
            return;
        }
        SiphonDimensionQueue queue = SIPHON_QUEUES.get(level.dimension());
        if (queue != null) {
            synchronized (queue) {
                LongOpenHashSet queuedKeys = queue.queuedByChunk.remove(chunkPos);
                if (queuedKeys != null) {
                    for (long posKey : queuedKeys) {
                        queue.queuedPositions.remove(posKey);
                    }
                }
                if (queue.queuedPositions.isEmpty()) {
                    SIPHON_QUEUES.remove(level.dimension(), queue);
                }
            }
        }
        synchronized (SIPHON_LOCK) {
            removeCooldownChunk(NEXT_HYDRAULIC_PUMP_TICK, NEXT_HYDRAULIC_PUMP_TICK_BY_CHUNK, chunkPos);
            removeCooldownChunk(NEXT_HYDRAULIC_PRESSURE_TICK, NEXT_HYDRAULIC_PRESSURE_TICK_BY_CHUNK, chunkPos);
            removeCooldownChunk(NEXT_HYDRAULIC_SEARCH_TICK, NEXT_HYDRAULIC_SEARCH_TICK_BY_CHUNK, chunkPos);
            removeCooldownChunk(NEXT_NATURAL_SEARCH_TICK, NEXT_NATURAL_SEARCH_TICK_BY_CHUNK, chunkPos);
        }
    }

    public static boolean tryRun(ServerLevel level, BlockPos sourcePos, FluidState sourceFluidState) {
        if (FlowingFluids.config == null
                || level == null
                || sourcePos == null
                || sourceFluidState == null
                || !FlowingFluids.config.enableMod
                || !FlowingFluids.config.enableSiphons
                || FlowingFluids.config.isDimensionExcluded(level)
                || !FlowingFluids.config.isWaterAllowed()
                || !sourceFluidState.is(FluidTags.WATER)
                || sourceFluidState.getAmount() <= 0
                || !isLoadedAndInBounds(level, sourcePos)) {
            return false;
        }

        if (!isLikelySiphonCandidate(level, sourcePos, sourceFluidState)) {
            return drainQueuedProbes(level);
        }
        enqueueProbe(level, sourcePos);
        return drainQueuedProbes(level);
    }

    private static boolean runProbeNow(ServerLevel level, BlockPos sourcePos, FluidState sourceFluidState) {
        if (tryRunHydraulicPump(level, sourcePos, sourceFluidState)) {
            return true;
        }
        if (tryRunHydraulicPressureField(level, sourcePos, sourceFluidState)) {
            return true;
        }
        if (tryRunHydraulicSiphon(level, sourcePos, sourceFluidState)) {
            return true;
        }
        return FlowingFluids.config.enableNaturalTerrainSiphons
                && tryRunNaturalTerrainSiphon(level, sourcePos, sourceFluidState);
    }

    private static boolean isLikelySiphonCandidate(ServerLevel level, BlockPos sourcePos, FluidState sourceFluidState) {
        if (FlowingFluids.config.enableHydraulicBlocks && hasHydraulicHardwareNear(level, sourcePos)) {
            return true;
        }
        int minFilled = Mth.clamp(FlowingFluids.config.naturalSiphonMinFilledAmount, 1, 8);
        return FlowingFluids.config.enableNaturalTerrainSiphons
                && sourceFluidState.getAmount() >= minFilled
                && isOrdinaryFlowBlocked(level, sourcePos, sourceFluidState)
                && !isBroadOpenWaterCell(level, sourcePos, minFilled);
    }

    private static void enqueueProbe(ServerLevel level, BlockPos sourcePos) {
        SiphonDimensionQueue queue = SIPHON_QUEUES.computeIfAbsent(level.dimension(), ignored -> new SiphonDimensionQueue());
        long posKey = sourcePos.asLong();
        synchronized (queue) {
            if (queue.queuedPositions.contains(posKey)) {
                return;
            }
            if (queue.queuedPositions.size() >= MAX_PENDING_SIPHON_PROBES_PER_DIMENSION) {
                return;
            }
            queue.pending.enqueue(posKey);
            queue.queuedPositions.add(posKey);
            queue.queuedByChunk.computeIfAbsent(chunkPos(posKey), ignored -> new LongOpenHashSet()).add(posKey);
        }
    }

    private static boolean drainQueuedProbes(ServerLevel level) {
        SiphonDimensionQueue queue = SIPHON_QUEUES.get(level.dimension());
        if (queue == null) {
            return false;
        }

        long now = level.getGameTime();
        int processed = 0;
        boolean movedAny = false;
        while (processed < MAX_SIPHON_PROBES_PER_TICK && tryBeginQueuedProbe(queue, now)) {
            long posKey;
            synchronized (queue) {
                if (queue.pending.isEmpty()) {
                    return movedAny;
                }
                posKey = queue.pending.dequeueLong();
                if (!queue.queuedPositions.remove(posKey)) {
                    continue;
                }
                removeQueuedIndex(queue, posKey);
            }

            BlockPos pos = BlockPos.of(posKey);
            if (isLoadedAndInBounds(level, pos)) {
                FluidState current = FFFluidUtils.getEffectiveFluidState(level, pos);
                if (current.is(FluidTags.WATER) && current.getAmount() > 0) {
                    movedAny |= runProbeNow(level, pos, current);
                }
            }
            processed++;
        }
        return movedAny;
    }

    private static boolean tryBeginQueuedProbe(SiphonDimensionQueue queue, long gameTime) {
        synchronized (queue) {
            if (queue.lastProcessTick != gameTime) {
                queue.lastProcessTick = gameTime;
                queue.processedThisTick = 0;
            }
            if (queue.processedThisTick >= MAX_SIPHON_PROBES_PER_TICK) {
                return false;
            }
            queue.processedThisTick++;
            return true;
        }
    }

    private static boolean tryRunHydraulicPump(ServerLevel level, BlockPos sourcePos, FluidState sourceFluidState) {
        if (!FlowingFluids.config.enableHydraulicBlocks
                || sourceFluidState.getAmount() <= PUMP_MIN_SOURCE_RETAIN) {
            return false;
        }

        for (Direction direction : Direction.values()) {
            BlockPos nozzlePos = sourcePos.relative(direction);
            if (!isLoadedAndInBounds(level, nozzlePos)) {
                continue;
            }
            BlockState nozzleState = level.getBlockState(nozzlePos);
            if (!nozzleState.is(FlowingFluids.HYDRAULIC_NOZZLE_BLOCKS)
                    || !nozzleState.hasProperty(BlockStateProperties.FACING)) {
                continue;
            }

            Direction facing = nozzleState.getValue(BlockStateProperties.FACING);
            if (!isPumpIntakeFace(direction, facing)) {
                continue;
            }

            long nozzleKey = nozzlePos.asLong();
            if (!isCooldownReady(NEXT_HYDRAULIC_PUMP_TICK, level, nozzleKey)) {
                continue;
            }

            HydraulicPressureDestination destination = findHydraulicPressureDestination(level, sourcePos, nozzlePos,
                    nozzlePos.relative(facing), facing);
            if (destination == null) {
                markCooldown(NEXT_HYDRAULIC_PUMP_TICK, NEXT_HYDRAULIC_PUMP_TICK_BY_CHUNK, level, nozzleKey, PUMP_COOLDOWN_TICKS);
                continue;
            }

            int moved = transferHydraulicPumpIntake(level, nozzlePos, destination.pos(), facing,
                    computePumpTransferAmount(facing, destination.pressureBoost()));
            markCooldown(NEXT_HYDRAULIC_PUMP_TICK, NEXT_HYDRAULIC_PUMP_TICK_BY_CHUNK, level, nozzleKey, PUMP_COOLDOWN_TICKS);
            if (moved > 0) {
                AdaptiveTickScheduler.scheduleFluidTick(level, destination.pos(), Fluids.WATER, 1);
                return true;
            }
        }
        return false;
    }

    private static boolean isPumpIntakeFace(Direction sourceToNozzle, Direction facing) {
        return sourceToNozzle == facing || sourceToNozzle.getAxis() != facing.getAxis();
    }

    private static int transferHydraulicPumpIntake(ServerLevel level, BlockPos nozzlePos, BlockPos destinationPos,
                                                   Direction facing, int requestedTransfer) {
        int remaining = Math.min(requestedTransfer, computePumpAvailableIntake(level, nozzlePos, facing));
        if (remaining <= 0) {
            return 0;
        }

        int moved = 0;
        List<BlockPos> changed = new ArrayList<>();
        Direction backDirection = facing.getOpposite();
        BlockPos backSourcePos = nozzlePos.relative(backDirection);
        if (isLoadedAndInBounds(level, backSourcePos)) {
            int movedFromBack = FFFluidUtils.transferFluidAmount(level, backSourcePos, destinationPos, Fluids.WATER,
                    remaining, PUMP_MIN_SOURCE_RETAIN);
            if (movedFromBack > 0) {
                moved += movedFromBack;
                remaining -= movedFromBack;
                AdaptiveTickScheduler.scheduleFluidTick(level, backSourcePos, Fluids.WATER, 1);
                changed.add(backSourcePos.immutable());
            }
        }

        for (Direction intakeDirection : Direction.values()) {
            if (remaining <= 0) {
                break;
            }
            if (intakeDirection == facing || intakeDirection == backDirection) {
                continue;
            }

            BlockPos sourcePos = nozzlePos.relative(intakeDirection);
            if (!isLoadedAndInBounds(level, sourcePos)) {
                continue;
            }
            int movedFromSource = FFFluidUtils.transferFluidAmount(level, sourcePos, destinationPos, Fluids.WATER,
                    remaining, PUMP_MIN_SOURCE_RETAIN);
            if (movedFromSource <= 0) {
                continue;
            }

            moved += movedFromSource;
            remaining -= movedFromSource;
            AdaptiveTickScheduler.scheduleFluidTick(level, sourcePos, Fluids.WATER, 1);
            changed.add(sourcePos.immutable());
            if (remaining <= 0) {
                break;
            }
        }

        if (moved > 0) {
            changed.add(destinationPos.immutable());
            FluidActivityTracker.recordChanges(level, changed);
        }
        return moved;
    }

    private static int computePumpAvailableIntake(ServerLevel level, BlockPos nozzlePos, Direction facing) {
        int available = 0;
        for (Direction intakeDirection : Direction.values()) {
            if (intakeDirection == facing) {
                continue;
            }

            BlockPos sourcePos = nozzlePos.relative(intakeDirection);
            if (!isLoadedAndInBounds(level, sourcePos)) {
                continue;
            }

            FluidState sourceFluid = FFFluidUtils.getEffectiveFluidState(level, sourcePos);
            if (sourceFluid.is(FluidTags.WATER)) {
                available += Math.max(0, sourceFluid.getAmount() - PUMP_MIN_SOURCE_RETAIN);
            }
        }
        return available;
    }

    private static boolean tryRunHydraulicPressureField(ServerLevel level, BlockPos sourcePos, FluidState sourceFluidState) {
        if (!FlowingFluids.config.enableHydraulicBlocks
                || sourceFluidState.getAmount() <= PUMP_MIN_SOURCE_RETAIN
                || !isCooldownReady(NEXT_HYDRAULIC_PRESSURE_TICK, level, sourcePos.asLong())) {
            return false;
        }

        for (Direction directionToNozzle : Direction.values()) {
            BlockPos nozzlePos = sourcePos.relative(directionToNozzle);
            if (!isLoadedAndInBounds(level, nozzlePos)) {
                continue;
            }
            BlockState nozzleState = level.getBlockState(nozzlePos);
            if (!nozzleState.is(FlowingFluids.HYDRAULIC_NOZZLE_BLOCKS)
                    || !nozzleState.hasProperty(BlockStateProperties.FACING)) {
                continue;
            }

            Direction facing = nozzleState.getValue(BlockStateProperties.FACING);
            Direction sourceSide = directionToNozzle.getOpposite();
            if (sourceSide.getAxis() == facing.getAxis()) {
                continue;
            }

            HydraulicPressureDestination destination = findHydraulicPressureDestination(level, sourcePos, nozzlePos,
                    sourcePos.relative(facing), facing);
            if (destination == null) {
                continue;
            }

            FluidState currentSource = FFFluidUtils.getEffectiveFluidState(level, sourcePos);
            if (!currentSource.is(FluidTags.WATER) || currentSource.getAmount() <= PUMP_MIN_SOURCE_RETAIN) {
                return false;
            }

            int requested = Math.min(
                    computePressureFieldTransferAmount(facing, destination.pressureBoost()),
                    currentSource.getAmount() - PUMP_MIN_SOURCE_RETAIN);
            int moved = FFFluidUtils.transferFluidAmount(level, sourcePos, destination.pos(), Fluids.WATER,
                    requested, PUMP_MIN_SOURCE_RETAIN);
            markCooldown(NEXT_HYDRAULIC_PRESSURE_TICK, NEXT_HYDRAULIC_PRESSURE_TICK_BY_CHUNK, level, sourcePos.asLong(), PRESSURE_FIELD_COOLDOWN_TICKS);
            if (moved > 0) {
                AdaptiveTickScheduler.scheduleFluidTick(level, sourcePos, Fluids.WATER, 1);
                AdaptiveTickScheduler.scheduleFluidTick(level, destination.pos(), Fluids.WATER, 1);
                FluidActivityTracker.recordChanges(level, List.of(sourcePos.immutable(), destination.pos().immutable()));
                return true;
            }
        }
        return false;
    }

    private static HydraulicPressureDestination findHydraulicPressureDestination(ServerLevel level, BlockPos sourcePos,
                                                                                BlockPos nozzlePos, BlockPos startPos, Direction facing) {
        BlockPos cursor = startPos;
        int pressureBoost = facing == Direction.UP ? 1 : 0;
        for (int step = 0; step < PUMP_CHAIN_REACH; step++) {
            if (cursor.equals(sourcePos) || cursor.equals(nozzlePos) || !isLoadedAndInBounds(level, cursor)) {
                return null;
            }
            if (canAcceptPumpDest(level, cursor)) {
                return new HydraulicPressureDestination(cursor.immutable(), pressureBoost);
            }

            BlockState state = level.getBlockState(cursor);
            FluidState fluid = FFFluidUtils.getEffectiveFluidState(level, cursor, state);
            if (fluid.is(FluidTags.WATER) && fluid.getAmount() >= 8) {
                cursor = cursor.relative(facing);
                continue;
            }
            if (state.is(FlowingFluids.HYDRAULIC_NOZZLE_BLOCKS)
                    && state.hasProperty(BlockStateProperties.FACING)
                    && state.getValue(BlockStateProperties.FACING) == facing) {
                pressureBoost = Math.min(3, pressureBoost + 1);
                cursor = cursor.relative(facing);
                continue;
            }
            return null;
        }
        return null;
    }

    private static boolean canAcceptPumpDest(ServerLevel level, BlockPos destPos) {
        BlockState destState = level.getBlockState(destPos);
        FluidState destFluid = FFFluidUtils.getEffectiveFluidState(level, destPos, destState);
        return (destFluid.isEmpty() || (destFluid.is(FluidTags.WATER) && destFluid.getAmount() < 8))
                && FFFluidUtils.canStorePartialFluidAmount(level, destPos, destState, Fluids.WATER);
    }

    private static int computePumpTransferAmount(Direction facing, int pressureBoost) {
        int amount = PUMP_LIFT_AMOUNT + pressureBoost * PUMP_PRESSURE_BOOST_AMOUNT;
        if (facing == Direction.UP) {
            amount += PUMP_PRESSURE_BOOST_AMOUNT;
        }
        return Mth.clamp(amount, 1, 8);
    }

    private static int computePressureFieldTransferAmount(Direction facing, int pressureBoost) {
        int amount = PRESSURE_FIELD_AMOUNT + pressureBoost * PRESSURE_FIELD_BOOST_AMOUNT;
        if (facing == Direction.UP) {
            amount += PRESSURE_FIELD_BOOST_AMOUNT;
        }
        return Mth.clamp(amount, 1, 8);
    }

    private static boolean tryRunHydraulicSiphon(ServerLevel level, BlockPos sourcePos, FluidState sourceFluidState) {
        if (!FlowingFluids.config.enableHydraulicBlocks
                || sourceFluidState.getAmount() < 2
                || FFFluidUtils.isInOrNearInfiniteBiome(level, sourcePos, 2)
                || !hasHydraulicHardwareNear(level, sourcePos)
                || !isCooldownReady(NEXT_HYDRAULIC_SEARCH_TICK, level, sourcePos.asLong())) {
            return false;
        }

        SiphonSearchResult result = searchSiphonPath(
                level,
                sourcePos,
                sourceFluidState.getAmount(),
                findLocalSourceSurfaceY(level, sourcePos,
                        FlowingFluids.config.hydraulicSiphonSourceSurfaceScanNodes,
                        FlowingFluids.config.hydraulicSiphonMaxLift + 2),
                1,
                FlowingFluids.config.hydraulicSiphonMaxSearchNodes,
                FlowingFluids.config.hydraulicSiphonMaxPathLength,
                FlowingFluids.config.hydraulicSiphonMaxLift,
                false,
                true,
                true);
        markCooldown(NEXT_HYDRAULIC_SEARCH_TICK, NEXT_HYDRAULIC_SEARCH_TICK_BY_CHUNK, level, sourcePos.asLong(), result.success() ? HYDRAULIC_COOLDOWN_TICKS : HYDRAULIC_COOLDOWN_TICKS * 2);
        return result.success() && applySiphonTransfer(level, sourcePos, result, computeHydraulicTransfer(result), 1);
    }

    private static boolean tryRunNaturalTerrainSiphon(ServerLevel level, BlockPos sourcePos, FluidState sourceFluidState) {
        int minFilled = Mth.clamp(FlowingFluids.config.naturalSiphonMinFilledAmount, 1, 8);
        if (sourceFluidState.getAmount() < minFilled
                || !isCooldownReady(NEXT_NATURAL_SEARCH_TICK, level, sourcePos.asLong())
                || !isOrdinaryFlowBlocked(level, sourcePos, sourceFluidState)
                || isBroadOpenWaterCell(level, sourcePos, minFilled)) {
            return false;
        }

        boolean requireEnclosed = FlowingFluids.config.naturalSiphonRequireEnclosedPath;
        boolean allowOpenSurface = FlowingFluids.config.naturalSiphonAllowOpenSurface;
        if (requireEnclosed && !isEnclosedPathCell(level, sourcePos, minFilled, null)) {
            markCooldown(NEXT_NATURAL_SEARCH_TICK, NEXT_NATURAL_SEARCH_TICK_BY_CHUNK, level, sourcePos.asLong(), FlowingFluids.config.naturalSiphonCooldownTicks);
            return false;
        }
        if (!allowOpenSurface && isOpenSurfaceCell(level, sourcePos)) {
            markCooldown(NEXT_NATURAL_SEARCH_TICK, NEXT_NATURAL_SEARCH_TICK_BY_CHUNK, level, sourcePos.asLong(), FlowingFluids.config.naturalSiphonCooldownTicks);
            return false;
        }

        int maxLift = FlowingFluids.config.naturalSiphonMaxLift;
        int naturalPressureHead = computeNaturalPressureHead(level, sourcePos, maxLift);
        int sourceSurfaceY = findLocalSourceSurfaceY(level, sourcePos,
                FlowingFluids.config.naturalSiphonMaxSearchNodes, maxLift + naturalPressureHead);
        SiphonSearchResult result = searchSiphonPath(
                level,
                sourcePos,
                sourceFluidState.getAmount(),
                sourceSurfaceY + naturalPressureHead,
                minFilled,
                FlowingFluids.config.naturalSiphonMaxSearchNodes,
                FlowingFluids.config.naturalSiphonMaxPathLength,
                maxLift,
                requireEnclosed,
                allowOpenSurface,
                false);
        int cooldown = Math.max(1, FlowingFluids.config.naturalSiphonCooldownTicks);
        markCooldown(NEXT_NATURAL_SEARCH_TICK, NEXT_NATURAL_SEARCH_TICK_BY_CHUNK, level, sourcePos.asLong(), result.success() ? cooldown : cooldown * 2);
        if (!result.success()) {
            return false;
        }

        int transfer = computeNaturalTransfer(result);
        return applySiphonTransfer(level, sourcePos, result, transfer, Math.max(0, minFilled - 1));
    }

    private static SiphonSearchResult searchSiphonPath(ServerLevel level, BlockPos sourcePos, int sourceAmount,
                                                       int sourceSurfaceY,
                                                       int minFilled, int maxNodes, int maxPathLength, int maxLift,
                                                       boolean requireEnclosed, boolean allowOpenSurface,
                                                       boolean hydraulicMode) {
        int boundedNodes = Mth.clamp(maxNodes, 1, 512);
        int boundedPath = Mth.clamp(maxPathLength, 1, 128);
        int boundedLift = Mth.clamp(maxLift, 0, 32);
        int boundedPressureHead = hydraulicMode
                ? Mth.clamp(FlowingFluids.config.hydraulicSiphonMaxPressureHead, 0, 32)
                : 0;
        long[] positions = new long[boundedNodes];
        int[] parents = new int[boundedNodes];
        int[] depths = new int[boundedNodes];
        int[] highestYs = new int[boundedNodes];
        int[] bends = new int[boundedNodes];
        int[] openSurfaces = new int[boundedNodes];
        int[] pressureHeads = new int[boundedNodes];
        Direction[] entryDirections = new Direction[boundedNodes];
        int[] queue = new int[boundedNodes];
        LongOpenHashSet visited = new LongOpenHashSet(boundedNodes);

        long sourceKey = sourcePos.asLong();
        positions[0] = sourceKey;
        parents[0] = NO_PARENT;
        depths[0] = 0;
        highestYs[0] = sourcePos.getY();
        bends[0] = 0;
        openSurfaces[0] = isOpenSurfaceCell(level, sourcePos) ? 1 : 0;
        pressureHeads[0] = hydraulicMode ? computeHydraulicPressureHead(level, sourcePos, boundedPressureHead) : 0;
        queue[0] = 0;
        visited.add(sourceKey);

        int nodeCount = 1;
        int queueHead = 0;
        int queueTail = 1;
        SiphonSearchResult best = SiphonSearchResult.fail();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();

        while (queueHead < queueTail) {
            int index = queue[queueHead++];
            cursor.set(positions[index]);
            int effectiveSurfaceY = sourceSurfaceY + pressureHeads[index];

            if (index != 0 && canUsePressureOverflowRest(level, cursor, effectiveSurfaceY, hydraulicMode)) {
                int score = Math.max(0, effectiveSurfaceY - cursor.getY()) * 16 + 12;
                SiphonOutlet restOutlet = new SiphonOutlet(cursor.immutable(), score, true);
                SiphonSearchResult candidate = buildResult(level, sourcePos, effectiveSurfaceY, positions, parents, index,
                        restOutlet, depths[index], bends[index], openSurfaces[index], sourceAmount, minFilled,
                        requireEnclosed, allowOpenSurface);
                if (candidate.success() && candidate.isBetterThan(best)) {
                    best = candidate;
                }
            }

            SiphonOutlet outlet = findBestOutlet(level, cursor, effectiveSurfaceY, visited, hydraulicMode);
            if (outlet != null) {
                SiphonSearchResult candidate = buildResult(level, sourcePos, effectiveSurfaceY, positions, parents, index, outlet,
                        depths[index], bends[index], openSurfaces[index], sourceAmount, minFilled, requireEnclosed, allowOpenSurface);
                if (candidate.success() && candidate.isBetterThan(best)) {
                    best = candidate;
                }
            }

            if (depths[index] >= boundedPath || nodeCount >= boundedNodes) {
                continue;
            }

            for (Direction direction : Direction.values()) {
                next.setWithOffset(cursor, direction);
                long nextKey = next.asLong();
                if (visited.contains(nextKey)
                        || !isLoadedAndInBounds(level, next)) {
                    continue;
                }
                int nextPressureHead = hydraulicMode
                        ? computeHydraulicPathPressure(level, cursor, next, direction, pressureHeads[index], boundedPressureHead)
                        : 0;
                if (next.getY() - sourceSurfaceY - nextPressureHead > boundedLift) {
                    continue;
                }

                if (hydraulicMode) {
                    if (!canUseHydraulicPathStep(level, cursor, next, direction, minFilled,
                            sourceSurfaceY, nextPressureHead)) {
                        continue;
                    }
                } else {
                    if (!canUseNaturalPathStep(level, cursor, next, direction, minFilled,
                            sourceSurfaceY, allowOpenSurface, requireEnclosed, visited)) {
                        continue;
                    }
                }

                visited.add(nextKey);
                int nextIndex = nodeCount++;
                positions[nextIndex] = nextKey;
                parents[nextIndex] = index;
                depths[nextIndex] = depths[index] + 1;
                highestYs[nextIndex] = Math.max(highestYs[index], next.getY());
                pressureHeads[nextIndex] = nextPressureHead;
                Direction previous = entryDirections[index];
                entryDirections[nextIndex] = direction;
                bends[nextIndex] = bends[index] + (previous != null && previous != direction ? 1 : 0);
                openSurfaces[nextIndex] = openSurfaces[index] + (isOpenSurfaceCell(level, next) ? 1 : 0);
                queue[queueTail++] = nextIndex;
                if (queueTail >= boundedNodes) {
                    break;
                }
            }
        }

        return best;
    }

    private static SiphonSearchResult buildResult(ServerLevel level, BlockPos sourcePos, int sourceSurfaceY,
                                                  long[] positions, int[] parents,
                                                  int outletParentIndex, SiphonOutlet outlet, int pathLength, int bends,
                                                  int openSurfaces, int sourceAmount, int minFilled,
                                                  boolean requireEnclosed, boolean allowOpenSurface) {
        LongOpenHashSet pathCells = new LongOpenHashSet(pathLength + 1);
        int index = outletParentIndex;
        int highestY = sourcePos.getY();
        while (index != NO_PARENT) {
            long key = positions[index];
            pathCells.add(key);
            highestY = Math.max(highestY, BlockPos.getY(key));
            index = parents[index];
        }

        if (!allowOpenSurface && openSurfaces > 0) {
            return SiphonSearchResult.fail();
        }
        if (requireEnclosed) {
            BlockPos.MutableBlockPos pathPos = new BlockPos.MutableBlockPos();
            for (long key : pathCells) {
                pathPos.set(key);
                if (!isEnclosedPathCell(level, pathPos, minFilled, pathCells)) {
                    return SiphonSearchResult.fail();
                }
            }
        }
        if (outlet.pos().getY() > sourceSurfaceY
                || (outlet.pos().getY() == sourceSurfaceY && !outlet.sameLevelHydraulic())) {
            return SiphonSearchResult.fail();
        }
        return new SiphonSearchResult(outlet.pos().immutable(), true, pathLength, bends, openSurfaces,
                highestY, sourceSurfaceY, outlet.score(), sourceAmount);
    }

    private static SiphonOutlet findBestOutlet(ServerLevel level, BlockPos pathEnd, int sourceSurfaceY,
                                               LongOpenHashSet pathCells, boolean hydraulicMode) {
        SiphonOutlet best = null;
        for (Direction direction : Direction.values()) {
            BlockPos outletPos = pathEnd.relative(direction);
            if (pathCells.contains(outletPos.asLong())
                    || !isLoadedAndInBounds(level, outletPos)
                    || !canUseOutletElevation(level, pathEnd, outletPos, direction, sourceSurfaceY, hydraulicMode)
                    || !canAcceptOutletWater(level, pathEnd, outletPos, direction)) {
                continue;
            }

            boolean sameLevelHydraulic = outletPos.getY() == sourceSurfaceY;
            int score = Math.max(0, sourceSurfaceY - outletPos.getY()) * 16;
            if (direction == Direction.DOWN) {
                score += 8;
            }
            if (hydraulicMode && isNozzleFacing(level, pathEnd.below(), direction)) {
                score += 24;
            }
            if (sameLevelHydraulic) {
                score += 4;
            }
            SiphonOutlet outlet = new SiphonOutlet(outletPos.immutable(), score, sameLevelHydraulic);
            if (best == null || outlet.score() > best.score()
                    || (outlet.score() == best.score() && outlet.pos().getY() < best.pos().getY())) {
                best = outlet;
            }
        }
        return best;
    }

    private static boolean canUseOutletElevation(ServerLevel level, BlockPos pathEnd, BlockPos outletPos,
                                                 Direction direction, int sourceSurfaceY, boolean hydraulicMode) {
        if (outletPos.getY() < sourceSurfaceY) {
            return true;
        }
        if (outletPos.getY() != sourceSurfaceY) {
            return false;
        }
        return FlowingFluids.config.siphonSameLevelOutletsAnywhere
                || (hydraulicMode && isHydraulicOutlet(level, pathEnd, outletPos, direction));
    }

    private static boolean isHydraulicOutlet(ServerLevel level, BlockPos pathEnd, BlockPos outletPos, Direction direction) {
        return isNozzleFacing(level, pathEnd.below(), direction)
                || isDirectHydraulicConduitCell(level, pathEnd)
                || isDirectHydraulicConduitCell(level, outletPos)
                || isHydraulicOvertopOutlet(level, pathEnd, outletPos, direction);
    }

    private static boolean canAcceptOutletWater(ServerLevel level, BlockPos fromPos, BlockPos outletPos, Direction direction) {
        BlockState outletState = level.getBlockState(outletPos);
        FluidState outletFluid = FFFluidUtils.getEffectiveFluidState(level, outletPos, outletState);
        if (!outletFluid.isEmpty() && !outletFluid.is(FluidTags.WATER)) {
            return false;
        }
        if (outletFluid.is(FluidTags.WATER) && outletFluid.getAmount() >= 8) {
            return false;
        }
        FlowingFluid water = Fluids.WATER;
        BlockState fromState = level.getBlockState(fromPos);
        return FFFluidUtils.canStorePartialFluidAmount(level, outletPos, outletState, Fluids.WATER)
                && FFFluidUtils.canFluidFlowFromPosToDirection(water, 8, level, fromPos, fromState,
                direction, outletPos, outletState, outletFluid);
    }

    private static int computeNaturalTransfer(SiphonSearchResult result) {
        int maxTransfer = Math.max(1, FlowingFluids.config.naturalSiphonMaxTransferPerTick);
        int drop = Math.max(1, result.sourceSurfaceY() - result.outletPos().getY());
        int divisor = Math.max(1, result.pathLength() + result.bends() * 2 + result.openSurfaces() * 6);
        int computed = Math.max(1, drop * 8 / divisor);
        return Mth.clamp(computed, 1, maxTransfer);
    }

    private static int computeHydraulicTransfer(SiphonSearchResult result) {
        int drive = Math.max(1, result.sourceSurfaceY() - result.outletPos().getY() + 1);
        int divisor = Math.max(1, result.pathLength() / 2 + result.bends() + result.openSurfaces() * 2);
        int computed = Math.max(1, drive * 3 / divisor);
        return Mth.clamp(computed, 1, Math.max(1, FlowingFluids.config.hydraulicSiphonMaxTransferPerTick));
    }

    private static boolean applySiphonTransfer(ServerLevel level, BlockPos sourcePos, SiphonSearchResult result,
                                               int transfer, int minSourceAmount) {
        if (transfer <= 0 || !result.success()) {
            return false;
        }
        List<BlockPos> changed = new ArrayList<>();
        int moved = transferConnectedSiphonAmount(level, sourcePos, result.outletPos(), transfer,
                minSourceAmount, result.pathLength() + 8, changed);
        if (moved <= 0) {
            return false;
        }
        AdaptiveTickScheduler.scheduleFluidTick(level, sourcePos, Fluids.WATER, 1);
        AdaptiveTickScheduler.scheduleFluidTick(level, result.outletPos(), Fluids.WATER, 1);
        changed.add(result.outletPos());
        FluidActivityTracker.recordChanges(level, changed);
        return true;
    }

    private static int transferConnectedSiphonAmount(ServerLevel level, BlockPos sourcePos, BlockPos outletPos,
                                                     int requestedTransfer, int minSourceAmount, int maxNodes,
                                                     List<BlockPos> changed) {
        int nodeLimit = Mth.clamp(maxNodes, 1, 512);
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        LongOpenHashSet visited = new LongOpenHashSet(nodeLimit);
        queue.enqueue(sourcePos.asLong());
        visited.add(sourcePos.asLong());

        int moved = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty() && visited.size() <= nodeLimit && moved < requestedTransfer) {
            long key = queue.dequeueLong();
            cursor.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
            if (cursor.equals(outletPos)) {
                continue;
            }
            int movedFromCell = FFFluidUtils.transferFluidAmount(level, cursor, outletPos, Fluids.WATER,
                    requestedTransfer - moved, minSourceAmount);
            if (movedFromCell > 0) {
                moved += movedFromCell;
                AdaptiveTickScheduler.scheduleFluidTick(level, cursor, Fluids.WATER, 1);
                changed.add(cursor.immutable());
                if (moved >= requestedTransfer) {
                    break;
                }
            }

            BlockState cursorState = level.getBlockState(cursor);
            FluidState cursorFluid = FFFluidUtils.getEffectiveFluidState(level, cursor, cursorState);
            if (!cursorFluid.is(FluidTags.WATER) || cursorFluid.getAmount() <= minSourceAmount) {
                continue;
            }

            for (Direction direction : Direction.values()) {
                if (visited.size() >= nodeLimit) {
                    break;
                }
                next.setWithOffset(cursor, direction);
                long nextKey = next.asLong();
                if (visited.contains(nextKey) || !isLoadedAndInBounds(level, next)) {
                    continue;
                }
                if (next.equals(outletPos)) {
                    continue;
                }
                BlockState nextState = level.getBlockState(next);
                FluidState nextFluid = FFFluidUtils.getEffectiveFluidState(level, next, nextState);
                if (!nextFluid.is(FluidTags.WATER) || nextFluid.getAmount() <= minSourceAmount) {
                    continue;
                }
                if (!FFFluidUtils.canFluidFlowFromPosToDirection(Fluids.WATER, cursorFluid.getAmount(), level,
                        cursor, cursorState, direction, next, nextState, nextFluid)) {
                    continue;
                }
                visited.add(nextKey);
                queue.enqueue(nextKey);
            }
        }
        return moved;
    }

    private static boolean isOrdinaryFlowBlocked(ServerLevel level, BlockPos pos, FluidState sourceFluidState) {
        if (!(sourceFluidState.getType() instanceof FlowingFluid water)) {
            return false;
        }
        BlockState sourceState = level.getBlockState(pos);
        for (Direction direction : Direction.values()) {
            BlockPos targetPos = pos.relative(direction);
            if (!isLoadedAndInBounds(level, targetPos)) {
                continue;
            }
            BlockState targetState = level.getBlockState(targetPos);
            FluidState targetFluid = FFFluidUtils.getEffectiveFluidState(level, targetPos, targetState);
            int targetAmount = targetFluid.is(FluidTags.WATER) ? targetFluid.getAmount() : 0;
            if (direction == Direction.UP) {
                continue;
            }
            if (direction == Direction.DOWN || targetAmount + 1 < sourceFluidState.getAmount()) {
                if (FFFluidUtils.canFluidFlowFromPosToDirection(water, sourceFluidState.getAmount(), level, pos,
                        sourceState, direction, targetPos, targetState, targetFluid)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isHydraulicPathCell(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos.below()).is(FlowingFluids.HYDRAULIC_FLOW_GUIDE_BLOCKS)
                || level.getBlockState(pos.below()).is(FlowingFluids.HYDRAULIC_NOZZLE_BLOCKS)
                || hasHydraulicHardwareNear(level, pos);
    }

    private static boolean isHydraulicBridge(ServerLevel level, BlockPos fromPos, BlockPos toPos, Direction direction) {
        return direction.getAxis().isHorizontal()
                && (isNozzleFacing(level, fromPos.below(), direction)
                || isNozzleFacing(level, toPos.below(), direction.getOpposite()));
    }

    private static boolean canUseHydraulicPathStep(ServerLevel level, BlockPos fromPos, BlockPos pathPos,
                                                   Direction direction, int minFilled,
                                                   int sourceSurfaceY, int pressureHead) {
        BlockState fromState = level.getBlockState(fromPos);
        FluidState fromFluid = FFFluidUtils.getEffectiveFluidState(level, fromPos, fromState);
        BlockState pathState = level.getBlockState(pathPos);
        FluidState pathFluid = FFFluidUtils.getEffectiveFluidState(level, pathPos, pathState);
        if (pathFluid.is(FluidTags.WATER) && pathFluid.getAmount() >= minFilled) {
            return canTraverseConnectedWater(level, fromPos, fromState, fromFluid, direction, pathPos, pathState, pathFluid)
                    || isHydraulicBridge(level, fromPos, pathPos, direction);
        }
        if (!pathFluid.isEmpty()) {
            return false;
        }
        if (!FFFluidUtils.canStorePartialFluidAmount(level, pathPos, pathState, Fluids.WATER)) {
            return false;
        }
        if (!isDirectHydraulicConduitCell(level, pathPos)
                && !isHydraulicBridge(level, fromPos, pathPos, direction)
                && !isHydraulicOvertopCell(level, fromPos, pathPos, direction, sourceSurfaceY, pressureHead)) {
            return false;
        }
        return pathState.isAir() || pathState.canBeReplaced(Fluids.WATER) || FFFluidUtils.supportsVirtualFluidState(level, pathState);
    }

    private static boolean canTraverseConnectedWater(ServerLevel level, BlockPos fromPos, BlockState fromState,
                                                     FluidState fromFluid, Direction direction, BlockPos toPos,
                                                     BlockState toState, FluidState toFluid) {
        return fromFluid.is(FluidTags.WATER)
                && fromFluid.getAmount() > 0
                && toFluid.is(FluidTags.WATER)
                && toFluid.getAmount() > 0
                && FFFluidUtils.canTraverseFluidAdjacency(level, fromPos, fromState, fromFluid,
                direction, toPos, toState, toFluid, Fluids.WATER);
    }

    private static boolean isHydraulicOvertopCell(ServerLevel level, BlockPos fromPos, BlockPos pathPos,
                                                  Direction direction, int sourceSurfaceY, int pressureHead) {
        if (sourceSurfaceY + Math.max(0, pressureHead) < pathPos.getY()) {
            return false;
        }
        if (direction == Direction.UP) {
            return hasOvertoppableHorizontalBlock(level, fromPos);
        }
        return direction.getAxis().isHorizontal()
                && isOvertoppableBlock(level, pathPos.below());
    }

    private static boolean canUsePressureOverflowRest(ServerLevel level, BlockPos pathPos, int effectiveSurfaceY,
                                                      boolean hydraulicMode) {
        if (pathPos.getY() > effectiveSurfaceY || !canHoldOvertopWater(level, pathPos)) {
            return false;
        }
        FluidState fluid = FFFluidUtils.getEffectiveFluidState(level, pathPos);
        if (fluid.is(FluidTags.WATER) && fluid.getAmount() >= 8) {
            return false;
        }
        return hydraulicMode ? isOvertoppableBlock(level, pathPos.below()) : isNaturalPressureRest(level, pathPos);
    }

    private static boolean isHydraulicOvertopOutlet(ServerLevel level, BlockPos pathEnd, BlockPos outletPos,
                                                    Direction direction) {
        return direction.getAxis().isHorizontal()
                && canUsePressureOverflowRest(level, pathEnd, outletPos.getY(), true)
                && canHoldOvertopWater(level, outletPos);
    }

    private static boolean canUseNaturalPathStep(ServerLevel level, BlockPos fromPos, BlockPos pathPos,
                                                 Direction direction, int minFilled, int sourceSurfaceY,
                                                 boolean allowOpenSurface, boolean requireEnclosed,
                                                 LongOpenHashSet visited) {
        BlockState fromState = level.getBlockState(fromPos);
        FluidState fromFluid = FFFluidUtils.getEffectiveFluidState(level, fromPos, fromState);
        BlockState pathState = level.getBlockState(pathPos);
        FluidState pathFluid = FFFluidUtils.getEffectiveFluidState(level, pathPos, pathState);
        if (pathFluid.is(FluidTags.WATER) && pathFluid.getAmount() >= minFilled) {
            if (!canTraverseConnectedWater(level, fromPos, fromState, fromFluid, direction, pathPos, pathState, pathFluid)) {
                return false;
            }
            if (!allowOpenSurface && isOpenSurfaceCell(level, pathPos)) {
                return false;
            }
            if (isBroadOpenWaterCell(level, pathPos, minFilled)) {
                return false;
            }
            return !requireEnclosed || isEnclosedPathCell(level, pathPos, minFilled, visited);
        }
        if (!pathFluid.isEmpty() || pathPos.getY() > sourceSurfaceY || !canHoldOvertopWater(level, pathPos)) {
            return false;
        }
        if (!allowOpenSurface && isOpenSurfaceCell(level, pathPos)) {
            return false;
        }
        return isNaturalPressureRest(level, pathPos)
                && (!requireEnclosed || isEnclosedPathCell(level, pathPos, minFilled, visited));
    }

    private static boolean isNaturalPressureRest(ServerLevel level, BlockPos pathPos) {
        if (!canHoldOvertopWater(level, pathPos)) {
            return false;
        }
        if (isOvertoppableBlock(level, pathPos.below())) {
            return true;
        }
        int solidSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (isOvertoppableBlock(level, pathPos.relative(direction))) {
                solidSides++;
            }
        }
        return solidSides >= 2 || FFFluidUtils.hasRoofWithin(level, pathPos, FlowingFluids.config.shadeRoofSearchHeight);
    }

    private static int computeNaturalPressureHead(ServerLevel level, BlockPos sourcePos, int maxLift) {
        FluidState sourceFluid = FFFluidUtils.getEffectiveFluidState(level, sourcePos);
        if (!sourceFluid.is(FluidTags.WATER) || sourceFluid.getAmount() <= 0) {
            return 0;
        }
        int localHead = Math.max(0, sourceFluid.getAmount() - Mth.clamp(FlowingFluids.config.naturalSiphonMinFilledAmount, 1, 8));
        if (FFFluidUtils.hasRoofWithin(level, sourcePos, FlowingFluids.config.shadeRoofSearchHeight)) {
            localHead += 2;
        }
        localHead += Math.max(0, findLocalSourceSurfaceY(level, sourcePos,
                Math.min(FlowingFluids.config.naturalSiphonMaxSearchNodes, 96),
                Math.max(1, maxLift + 4)) - sourcePos.getY());
        return Mth.clamp(localHead, 0, Math.min(NATURAL_PRESSURE_HEAD_CAP, Math.max(0, maxLift + 4)));
    }

    private static boolean hasOvertoppableHorizontalBlock(ServerLevel level, BlockPos waterPos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos blockPos = waterPos.relative(direction);
            if (isOvertoppableBlock(level, blockPos) && canHoldOvertopWater(level, blockPos.above())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOvertoppableBlock(ServerLevel level, BlockPos blockPos) {
        if (!isLoadedAndInBounds(level, blockPos)) {
            return false;
        }
        BlockState state = level.getBlockState(blockPos);
        return !state.isAir()
                && state.getFluidState().isEmpty()
                && !state.getCollisionShape(level, blockPos).isEmpty();
    }

    private static boolean canHoldOvertopWater(ServerLevel level, BlockPos pos) {
        if (!isLoadedAndInBounds(level, pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        FluidState fluid = FFFluidUtils.getEffectiveFluidState(level, pos, state);
        return (fluid.isEmpty() || fluid.is(FluidTags.WATER))
                && FFFluidUtils.canStorePartialFluidAmount(level, pos, state, Fluids.WATER);
    }

    private static boolean isDirectHydraulicConduitCell(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        return isLoadedAndInBounds(level, below)
                && (level.getBlockState(below).is(FlowingFluids.HYDRAULIC_FLOW_GUIDE_BLOCKS)
                || level.getBlockState(below).is(FlowingFluids.HYDRAULIC_NOZZLE_BLOCKS));
    }

    private static int findLocalSourceSurfaceY(ServerLevel level, BlockPos sourcePos, int maxNodes, int maxRise) {
        long[] queue = new long[Math.max(1, maxNodes)];
        LongOpenHashSet visited = new LongOpenHashSet(queue.length);
        queue[0] = sourcePos.asLong();
        visited.add(sourcePos.asLong());
        int head = 0;
        int tail = 1;
        int sourceSurfaceY = sourcePos.getY();
        double openSpillHead = Double.POSITIVE_INFINITY;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();

        while (head < tail) {
            cursor.set(queue[head++]);
            BlockState cursorState = level.getBlockState(cursor);
            FluidState cursorFluid = FFFluidUtils.getEffectiveFluidState(level, cursor, cursorState);
            if (cursorFluid.is(FluidTags.WATER) && cursorFluid.getAmount() > 0) {
                int cellSurfaceY = cursor.getY() + (cursorFluid.getAmount() >= 8 ? 1 : 0);
                sourceSurfaceY = Math.max(sourceSurfaceY, cellSurfaceY);
                openSpillHead = Math.min(openSpillHead, FFFluidUtils.getWaterOpenSpillHead(
                        level, cursor, cursorFluid, Fluids.WATER));
            }
            for (Direction direction : Direction.values()) {
                next.setWithOffset(cursor, direction);
                long key = next.asLong();
                if (visited.contains(key)
                        || tail >= queue.length
                        || !isLoadedAndInBounds(level, next)
                        || Math.abs(next.getY() - sourcePos.getY()) > maxRise) {
                    continue;
                }
                BlockState nextState = level.getBlockState(next);
                FluidState fluid = FFFluidUtils.getEffectiveFluidState(level, next, nextState);
                if (!fluid.is(FluidTags.WATER) || fluid.getAmount() <= 0) {
                    continue;
                }
                if (!canTraverseConnectedWater(level, cursor, cursorState, cursorFluid, direction, next, nextState, fluid)) {
                    continue;
                }
                visited.add(key);
                queue[tail++] = key;
            }
        }
        if (Double.isFinite(openSpillHead)) {
            sourceSurfaceY = Math.min(sourceSurfaceY, Mth.floor(openSpillHead));
        }
        return sourceSurfaceY;
    }

    private static int computeHydraulicPressureHead(ServerLevel level, BlockPos sourcePos, int maxPressureHead) {
        if (maxPressureHead <= 0) {
            return 0;
        }
        BlockPos belowSource = sourcePos.below();
        int pressureHead = isLoadedAndInBounds(level, belowSource)
                && level.getBlockState(belowSource).is(FlowingFluids.HYDRAULIC_FLOW_GUIDE_BLOCKS) ? 1 : 0;
        for (Direction direction : Direction.values()) {
            BlockPos nozzlePos = sourcePos.relative(direction);
            if (!isLoadedAndInBounds(level, nozzlePos)) {
                continue;
            }
            BlockState nozzleState = level.getBlockState(nozzlePos);
            if (!nozzleState.is(FlowingFluids.HYDRAULIC_NOZZLE_BLOCKS)
                    || !nozzleState.hasProperty(BlockStateProperties.FACING)) {
                continue;
            }
            Direction facing = nozzleState.getValue(BlockStateProperties.FACING);
            if (facing == direction) {
                pressureHead += facing == Direction.UP ? 4 : 2;
            } else if (facing.getAxis() != direction.getAxis()) {
                pressureHead += facing == Direction.UP ? 2 : 1;
            }
            if (pressureHead >= maxPressureHead) {
                return maxPressureHead;
            }
        }
        return Mth.clamp(pressureHead, 0, maxPressureHead);
    }

    private static int computeHydraulicPathPressure(ServerLevel level, BlockPos fromPos, BlockPos toPos,
                                                    Direction direction, int currentPressureHead, int maxPressureHead) {
        if (maxPressureHead <= 0) {
            return 0;
        }
        int pressureHead = Math.max(0, currentPressureHead);
        if (direction == Direction.UP) {
            pressureHead = Math.max(0, pressureHead - 1);
        }

        pressureHead += computeHydraulicStepPressureBoost(level, fromPos, toPos, direction);
        return Mth.clamp(pressureHead, 0, maxPressureHead);
    }

    private static int computeHydraulicStepPressureBoost(ServerLevel level, BlockPos fromPos, BlockPos toPos,
                                                         Direction direction) {
        int boost = 0;
        if (isNozzleFacing(level, fromPos.below(), direction)) {
            boost += direction == Direction.UP ? 4 : 3;
        }
        if (isNozzleFacing(level, toPos.below(), direction)) {
            boost += direction == Direction.UP ? 3 : 2;
        }
        if (isDirectHydraulicConduitCell(level, fromPos) || isDirectHydraulicConduitCell(level, toPos)) {
            boost += 1;
        }
        if (direction == Direction.UP) {
            boost += computeHorizontalNozzleLiftBoost(level, fromPos);
            boost += computeHorizontalNozzleLiftBoost(level, toPos);
        }
        return boost;
    }

    private static int computeHorizontalNozzleLiftBoost(ServerLevel level, BlockPos waterPos) {
        int boost = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (isNozzleFacing(level, waterPos.relative(direction), direction.getOpposite())) {
                boost += 2;
            }
        }
        return Math.min(4, boost);
    }

    private static boolean hasHydraulicHardwareNear(ServerLevel level, BlockPos pos) {
        if (!isLoadedAndInBounds(level, pos.below())) {
            return false;
        }
        if (level.getBlockState(pos.below()).is(FlowingFluids.HYDRAULIC_FLOW_GUIDE_BLOCKS)
                || level.getBlockState(pos.below()).is(FlowingFluids.HYDRAULIC_NOZZLE_BLOCKS)) {
            return true;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            cursor.setWithOffset(pos, direction);
            if (!isLoadedAndInBounds(level, cursor)) {
                continue;
            }
            BlockPos below = cursor.below();
            if (level.getBlockState(cursor).is(FlowingFluids.HYDRAULIC_FLOW_GUIDE_BLOCKS)
                    || level.getBlockState(cursor).is(FlowingFluids.HYDRAULIC_NOZZLE_BLOCKS)
                    || (isLoadedAndInBounds(level, below)
                    && (level.getBlockState(below).is(FlowingFluids.HYDRAULIC_FLOW_GUIDE_BLOCKS)
                    || level.getBlockState(below).is(FlowingFluids.HYDRAULIC_NOZZLE_BLOCKS)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNozzleFacing(ServerLevel level, BlockPos pos, Direction direction) {
        if (!isLoadedAndInBounds(level, pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.is(FlowingFluids.HYDRAULIC_NOZZLE_BLOCKS)
                && state.hasProperty(BlockStateProperties.FACING)
                && state.getValue(BlockStateProperties.FACING) == direction;
    }

    private static boolean isBroadOpenWaterCell(ServerLevel level, BlockPos pos, int minFilled) {
        if (!isOpenSurfaceCell(level, pos)) {
            return false;
        }
        int waterNeighbors = 0;
        int openNeighbors = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, direction);
            if (!isLoadedAndInBounds(level, cursor)) {
                continue;
            }
            BlockState state = level.getBlockState(cursor);
            FluidState fluid = FFFluidUtils.getEffectiveFluidState(level, cursor, state);
            if (fluid.is(FluidTags.WATER) && fluid.getAmount() >= minFilled) {
                waterNeighbors++;
                if (isOpenSurfaceCell(level, cursor)) {
                    openNeighbors++;
                }
            }
        }
        return waterNeighbors >= 3 || openNeighbors >= 2;
    }

    private static boolean isEnclosedPathCell(ServerLevel level, BlockPos pos, int minFilled, LongOpenHashSet samePathCells) {
        int blocked = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, direction);
            long key = cursor.asLong();
            if (samePathCells != null && samePathCells.contains(key)) {
                blocked++;
                continue;
            }
            if (!isLoadedAndInBounds(level, cursor)) {
                blocked++;
                continue;
            }
            BlockState neighborState = level.getBlockState(cursor);
            FluidState neighborFluid = FFFluidUtils.getEffectiveFluidState(level, cursor, neighborState);
            if (neighborFluid.is(FluidTags.WATER) && neighborFluid.getAmount() >= minFilled) {
                continue;
            }
            if (isSolidOrNotFlowOpen(level, pos, cursor, direction)) {
                blocked++;
            }
        }
        return blocked >= 3;
    }

    private static boolean isSolidOrNotFlowOpen(ServerLevel level, BlockPos fromPos, BlockPos toPos, Direction direction) {
        BlockState toState = level.getBlockState(toPos);
        if (!toState.getCollisionShape(level, toPos).isEmpty() && !toState.canBeReplaced(Fluids.WATER)) {
            return true;
        }
        FlowingFluid water = Fluids.WATER;
        BlockState fromState = level.getBlockState(fromPos);
        FluidState toFluid = FFFluidUtils.getEffectiveFluidState(level, toPos, toState);
        return !FFFluidUtils.canFluidFlowFromPosToDirection(water, 8, level, fromPos, fromState,
                direction, toPos, toState, toFluid);
    }

    private static boolean isOpenSurfaceCell(ServerLevel level, BlockPos pos) {
        BlockPos abovePos = pos.above();
        if (!isLoadedAndInBounds(level, abovePos)) {
            return true;
        }
        BlockState aboveState = level.getBlockState(abovePos);
        FluidState aboveFluid = FFFluidUtils.getEffectiveFluidState(level, abovePos, aboveState);
        return aboveFluid.isEmpty() && (aboveState.isAir() || aboveState.canBeReplaced(Fluids.WATER));
    }

    private static boolean isLoadedAndInBounds(ServerLevel level, BlockPos pos) {
        return level.isInWorldBounds(pos) && level.hasChunkAt(pos);
    }

    private static boolean isCooldownReady(Long2LongOpenHashMap cooldowns, Level level, long key) {
        long now = level.getGameTime();
        synchronized (SIPHON_LOCK) {
            return cooldowns.getOrDefault(key, Long.MIN_VALUE) <= now;
        }
    }

    private static void markCooldown(Long2LongOpenHashMap cooldowns, ConcurrentHashMap<ChunkPos, LongOpenHashSet> index,
                                     Level level, long key, int ticks) {
        synchronized (SIPHON_LOCK) {
            cleanupCooldowns(cooldowns, index, level.getGameTime());
            cooldowns.put(key, level.getGameTime() + Math.max(1, ticks));
            index.computeIfAbsent(chunkPos(key), ignored -> new LongOpenHashSet()).add(key);
        }
    }

    private static void cleanupCooldowns(Long2LongOpenHashMap cooldowns, ConcurrentHashMap<ChunkPos, LongOpenHashSet> index,
                                         long now) {
        if (cooldowns.size() < 4096) {
            return;
        }
        LongOpenHashSet expired = new LongOpenHashSet();
        cooldowns.long2LongEntrySet().removeIf(entry -> {
            if (entry.getLongValue() <= now) {
                expired.add(entry.getLongKey());
                return true;
            }
            return false;
        });
        for (long posKey : expired) {
            removeCooldownIndex(index, posKey);
        }
    }

    private static void removeCooldownChunk(Long2LongOpenHashMap cooldowns,
                                            ConcurrentHashMap<ChunkPos, LongOpenHashSet> index,
                                            ChunkPos chunkPos) {
        LongOpenHashSet keys = index.remove(chunkPos);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (long posKey : keys) {
            cooldowns.remove(posKey);
        }
    }

    private static void removeCooldownIndex(ConcurrentHashMap<ChunkPos, LongOpenHashSet> index, long posKey) {
        ChunkPos chunkPos = chunkPos(posKey);
        LongOpenHashSet keys = index.get(chunkPos);
        if (keys == null) {
            return;
        }
        keys.remove(posKey);
        if (keys.isEmpty()) {
            index.remove(chunkPos, keys);
        }
    }

    private static void removeQueuedIndex(SiphonDimensionQueue queue, long posKey) {
        ChunkPos chunkPos = chunkPos(posKey);
        LongOpenHashSet keys = queue.queuedByChunk.get(chunkPos);
        if (keys == null) {
            return;
        }
        keys.remove(posKey);
        if (keys.isEmpty()) {
            queue.queuedByChunk.remove(chunkPos);
        }
    }

    private static ChunkPos chunkPos(long posKey) {
        return new ChunkPos(BlockPos.getX(posKey) >> 4, BlockPos.getZ(posKey) >> 4);
    }

    private static final class SiphonDimensionQueue {
        private final LongArrayFIFOQueue pending = new LongArrayFIFOQueue();
        private final LongOpenHashSet queuedPositions = new LongOpenHashSet();
        private final ConcurrentHashMap<ChunkPos, LongOpenHashSet> queuedByChunk = new ConcurrentHashMap<>();
        private long lastProcessTick = Long.MIN_VALUE;
        private int processedThisTick = 0;
    }

    private record SiphonOutlet(BlockPos pos, int score, boolean sameLevelHydraulic) {
    }

    private record HydraulicPressureDestination(BlockPos pos, int pressureBoost) {
    }

    private record SiphonSearchResult(BlockPos outletPos, boolean success, int pathLength, int bends,
                                      int openSurfaces, int highestY, int sourceSurfaceY, int outletScore,
                                      int sourceAmount) {
        private static SiphonSearchResult fail() {
            return new SiphonSearchResult(BlockPos.ZERO, false, 0, 0, 0, 0, 0, Integer.MIN_VALUE, 0);
        }

        private boolean isBetterThan(SiphonSearchResult other) {
            if (!other.success) {
                return true;
            }
            if (outletScore != other.outletScore) {
                return outletScore > other.outletScore;
            }
            if (pathLength != other.pathLength) {
                return pathLength < other.pathLength;
            }
            if (openSurfaces != other.openSurfaces) {
                return openSurfaces < other.openSurfaces;
            }
            return bends < other.bends;
        }
    }
}
