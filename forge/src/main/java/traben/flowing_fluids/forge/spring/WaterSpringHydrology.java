package traben.flowing_fluids.forge.spring;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.FFFluidUtils;

final class WaterSpringHydrology {
    private static final int HORIZONTAL_RADIUS = 2;
    private static final int MIN_Y_OFFSET = -1;
    private static final int MAX_Y_OFFSET = 2;
    private static final int MAX_REACHABLE_WATER = 24;
    private static final int MAX_FRONTIER_SLOTS = 8;
    private static final Direction[] DIRECTIONS = Direction.values();

    private WaterSpringHydrology() {
    }

    static Sample sample(ServerLevel level, BlockPos springPos, BlockPos tipPos, Direction growthDirection) {
        FlowingFluid water = (FlowingFluid) Fluids.WATER;
        BlockPos backingPos = tipPos.relative(growthDirection.getOpposite());
        BlockPos forwardPos = tipPos.relative(growthDirection);

        boolean tipWet = isWater(level, tipPos);
        boolean forwardWet = isWater(level, forwardPos);
        boolean springBacked = isWater(level, springPos) || isWater(level, backingPos);

        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        LongOpenHashSet visited = new LongOpenHashSet();
        seedIfWater(level, tipPos, tipPos, queue, visited);
        seedIfWater(level, tipPos, backingPos, queue, visited);
        seedIfWater(level, tipPos, springPos, queue, visited);

        if (queue.isEmpty()) {
            return new Sample(0, 0, tipWet, forwardWet, springBacked);
        }

        LongOpenHashSet frontier = new LongOpenHashSet();
        BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        int connectedWater = 0;
        boolean reachableForward = false;

        while (!queue.isEmpty() && connectedWater < MAX_REACHABLE_WATER) {
            long currentKey = queue.dequeueLong();
            currentPos.set(BlockPos.getX(currentKey), BlockPos.getY(currentKey), BlockPos.getZ(currentKey));

            BlockState currentState = level.getBlockState(currentPos);
            FluidState currentFluid = FFFluidUtils.getEffectiveFluidState(level, currentPos, currentState);
            if (!currentFluid.getType().isSame(Fluids.WATER) || currentFluid.getAmount() <= 0) {
                continue;
            }

            connectedWater++;
            if (currentPos.getX() == forwardPos.getX()
                    && currentPos.getY() == forwardPos.getY()
                    && currentPos.getZ() == forwardPos.getZ()) {
                reachableForward = true;
            }

            for (Direction direction : DIRECTIONS) {
                neighborPos.setWithOffset(currentPos, direction);
                if (!isWithinSampleWindow(tipPos, neighborPos)) {
                    continue;
                }

                BlockState neighborState = level.getBlockState(neighborPos);
                FluidState neighborFluid = FFFluidUtils.getEffectiveFluidState(level, neighborPos, neighborState);
                if (!FFFluidUtils.canTraverseFluidAdjacency(level, currentPos, currentState, currentFluid, direction,
                        neighborPos, neighborState, neighborFluid, water)) {
                    continue;
                }

                if (neighborFluid.getType().isSame(Fluids.WATER) && neighborFluid.getAmount() > 0) {
                    long neighborKey = neighborPos.asLong();
                    if (visited.add(neighborKey)) {
                        queue.enqueue(neighborKey);
                    }
                    continue;
                }

                if (neighborFluid.isEmpty()) {
                    frontier.add(neighborPos.asLong());
                }
            }
        }

        return new Sample(
                connectedWater,
                Math.min(frontier.size(), MAX_FRONTIER_SLOTS),
                tipWet,
                forwardWet || reachableForward,
                springBacked
        );
    }

    private static void seedIfWater(ServerLevel level,
                                    BlockPos tipPos,
                                    BlockPos candidatePos,
                                    LongArrayFIFOQueue queue,
                                    LongOpenHashSet visited) {
        if (!isWithinSampleWindow(tipPos, candidatePos) || !isWater(level, candidatePos)) {
            return;
        }
        long key = candidatePos.asLong();
        if (visited.add(key)) {
            queue.enqueue(key);
        }
    }

    private static boolean isWithinSampleWindow(BlockPos tipPos, BlockPos candidatePos) {
        int dx = Math.abs(candidatePos.getX() - tipPos.getX());
        int dz = Math.abs(candidatePos.getZ() - tipPos.getZ());
        int dy = candidatePos.getY() - tipPos.getY();
        return dx <= HORIZONTAL_RADIUS && dz <= HORIZONTAL_RADIUS && dy >= MIN_Y_OFFSET && dy <= MAX_Y_OFFSET;
    }

    private static boolean isWater(ServerLevel level, BlockPos pos) {
        FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, pos);
        return fluidState.getType().isSame(Fluids.WATER) && fluidState.getAmount() > 0;
    }

    record Sample(int connectedWater, int frontierCount, boolean tipWet, boolean forwardWet, boolean springBacked) {
        boolean isCalmPool() {
            return connectedWater >= 8 && frontierCount <= 1;
        }
    }
}
