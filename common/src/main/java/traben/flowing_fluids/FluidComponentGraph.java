package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.util.DimensionKey;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional component-first view of the fluid field.
 *
 * <p>The vanilla/world state remains authoritative. This graph is rebuilt from
 * local fluid deltas and gives hot paths a cheap answer to "is this cell inside
 * a stable water body, or near a meaningful frontier/outlet?"</p>
 */
public final class FluidComponentGraph {
    private static final Direction[] ALL_DIRECTIONS = Direction.values();
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private static final ConcurrentHashMap<DimensionKey, DimensionGraph> GRAPHS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_COMPONENT_ID = new AtomicInteger(1);

    private FluidComponentGraph() {
    }

    public static void recordFluidChange(LevelAccessor level, BlockPos pos, Fluid fluid, int amount) {
        if (!isEnabled() || level == null || pos == null || fluid == null) {
            return;
        }
        if (!(level instanceof Level)) {
            return;
        }
        DimensionGraph graph = graph(level);
        long key = pos.asLong();
        if (amount <= 0) {
            graph.cells.remove(key);
            Integer componentId = graph.componentByCell.remove(key);
            if (componentId != null) {
                graph.summaries.remove(componentId);
            }
        } else {
            FluidComponentCell existing = graph.cells.get(key);
            int componentId = existing != null && existing.fluid().isSame(fluid)
                ? existing.componentId()
                : 0;
            graph.cells.put(key, new FluidComponentCell(fluid, amount, componentId, false, false, false));
        }
        markDirty(level, pos);
        for (Direction direction : ALL_DIRECTIONS) {
            markDirty(level, pos.relative(direction));
        }
    }

    public static int processPending(ServerLevel level) {
        if (!isEnabled() || level == null) {
            return 0;
        }
        DimensionGraph graph = GRAPHS.get(DimensionKey.of(level));
        if (graph == null) {
            return 0;
        }

        int processed = 0;
        int budget = Math.max(1, FlowingFluids.config.fluidComponentGraphMaxUpdatesPerTick);
        LongOpenHashSet batch;
        synchronized (graph.dirtySeeds) {
            if (graph.dirtySeeds.isEmpty()) {
                return 0;
            }
            batch = new LongOpenHashSet(Math.min(budget, graph.dirtySeeds.size()));
            for (long seed : graph.dirtySeeds) {
                if (processed >= budget) {
                    break;
                }
                batch.add(seed);
                processed++;
            }
            for (long seed : batch) {
                graph.dirtySeeds.remove(seed);
            }
        }

        FluidSectionDataCache cache = new FluidSectionDataCache(level, Math.max(16, batch.size() / 4));
        for (long seed : batch) {
            rebuildLocalComponent(level, graph, seed, cache);
        }
        graph.lastProcessedTick = level.getGameTime();
        return batch.size();
    }

    public static boolean shouldUseFocusedSnapshot(LevelAccessor level, BlockPos pos, Fluid fluid) {
        if (!isEnabled() || level == null || pos == null || fluid == null
                || !FlowingFluids.config.fluidComponentGraphAssistEqualizer) {
            return false;
        }
        DimensionGraph graph = GRAPHS.get(DimensionKey.of(level));
        if (graph == null) {
            return false;
        }
        FluidComponentCell cell = graph.cells.get(pos.asLong());
        if (cell == null || !cell.fluid().isSame(fluid)) {
            return false;
        }
        FluidComponentSummary summary = graph.summaries.get(cell.componentId());
        return isStableInterior(cell, summary);
    }

    static boolean isStableInterior(FluidComponentCell cell, FluidComponentSummary summary) {
        if (cell == null || summary == null || summary.partial()) {
            return false;
        }
        if (cell.frontier() || cell.outlet() || cell.inlet()) {
            return false;
        }
        if (!cell.fluid().isSame(Fluids.WATER) && !cell.fluid().isSame(Fluids.FLOWING_WATER)) {
            return false;
        }
        if (summary.outletCells() > 0) {
            return false;
        }
        if (summary.cellCount() < 8 || summary.totalInternalMass() <= 0) {
            return false;
        }
        return summary.frontierCells() <= Math.max(2, summary.cellCount() / 8);
    }

    public static void clearDimension(LevelAccessor level) {
        if (level != null) {
            GRAPHS.remove(DimensionKey.of(level));
        }
    }

    public static void clearChunk(LevelAccessor level, ChunkPos chunkPos) {
        if (level == null || chunkPos == null) {
            return;
        }
        DimensionGraph graph = GRAPHS.get(DimensionKey.of(level));
        if (graph == null) {
            return;
        }
        graph.cells.keySet().removeIf(posKey -> new ChunkPos(BlockPos.of(posKey)).equals(chunkPos));
        graph.componentByCell.keySet().removeIf(posKey -> new ChunkPos(BlockPos.of(posKey)).equals(chunkPos));
        synchronized (graph.dirtySeeds) {
            graph.dirtySeeds.removeIf(posKey -> new ChunkPos(BlockPos.of(posKey)).equals(chunkPos));
        }
        graph.summaries.clear();
    }

    public static void clearAll() {
        GRAPHS.clear();
    }

    public static String describeStatus(LevelAccessor level) {
        if (level == null) {
            return "Fluid component graph is unavailable here.";
        }
        DimensionGraph graph = GRAPHS.get(DimensionKey.of(level));
        if (graph == null) {
            return "Fluid component graph"
                + "\nEnabled: " + isEnabled()
                + "\nTracked cells: 0"
                + "\nComponents: 0"
                + "\nDirty seeds: 0";
        }
        return "Fluid component graph"
            + "\nEnabled: " + isEnabled()
            + "\nEqualizer assist: " + FlowingFluids.config.fluidComponentGraphAssistEqualizer
            + "\nTracked cells: " + graph.cells.size()
            + "\nComponents: " + graph.summaries.size()
            + "\nDirty seeds: " + graph.dirtySeedCount()
            + "\nLast processed tick: " + graph.lastProcessedTick
            + "\nMax updates/tick: " + FlowingFluids.config.fluidComponentGraphMaxUpdatesPerTick
            + "\nMax scan nodes: " + FlowingFluids.config.fluidComponentGraphMaxScanNodes;
    }

    private static boolean isEnabled() {
        return FlowingFluids.config != null
            && FlowingFluids.config.enableMod
            && FlowingFluids.config.enableFluidComponentGraph;
    }

    private static DimensionGraph graph(LevelAccessor level) {
        return GRAPHS.computeIfAbsent(DimensionKey.of(level), ignored -> new DimensionGraph());
    }

    private static void markDirty(LevelAccessor level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        DimensionGraph graph = graph(level);
        synchronized (graph.dirtySeeds) {
            graph.dirtySeeds.add(pos.asLong());
        }
    }

    private static void rebuildLocalComponent(Level level, DimensionGraph graph, long seedKey, FluidSectionDataCache cache) {
        BlockPos seedPos = BlockPos.of(seedKey);
        Fluid seedFluid = cache.fluidType(seedPos);
        if (seedFluid == null) {
            graph.cells.remove(seedKey);
            graph.componentByCell.remove(seedKey);
            return;
        }
        int componentId = NEXT_COMPONENT_ID.getAndIncrement();
        LongOpenHashSet visited = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        queue.enqueue(seedKey);
        visited.add(seedKey);

        int maxNodes = Math.max(16, FlowingFluids.config.fluidComponentGraphMaxScanNodes);
        int totalMass = 0;
        int frontierCells = 0;
        int outletCells = 0;
        int inletCells = 0;
        int minY = seedPos.getY();
        int maxY = seedPos.getY();
        boolean partial = false;

        while (!queue.isEmpty()) {
            long currentKey = queue.dequeueLong();
            if (visited.size() > maxNodes) {
                partial = true;
                break;
            }
            int x = BlockPos.getX(currentKey);
            int y = BlockPos.getY(currentKey);
            int z = BlockPos.getZ(currentKey);
            int amount = cache.internalAmount(x, y, z);
            totalMass += amount;
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);

            CellShape shape = classifyCell(level, cache, x, y, z, seedFluid);
            if (shape.frontier()) {
                frontierCells++;
            }
            if (shape.outlet()) {
                outletCells++;
            }
            if (shape.inlet()) {
                inletCells++;
            }

            graph.cells.put(currentKey, new FluidComponentCell(seedFluid, amount, componentId,
                shape.frontier(), shape.outlet(), shape.inlet()));
            graph.componentByCell.put(currentKey, componentId);

            for (Direction direction : ALL_DIRECTIONS) {
                int nx = x + direction.getStepX();
                int ny = y + direction.getStepY();
                int nz = z + direction.getStepZ();
                long neighborKey = BlockPos.asLong(nx, ny, nz);
                if (visited.contains(neighborKey)) {
                    continue;
                }
                Fluid neighborFluid = cache.fluidType(nx, ny, nz);
                if (neighborFluid != null && neighborFluid.isSame(seedFluid)) {
                    visited.add(neighborKey);
                    queue.enqueue(neighborKey);
                }
            }
        }

        graph.summaries.put(componentId, new FluidComponentSummary(componentId, seedFluid,
            visited.size(), totalMass, frontierCells, outletCells, inletCells, minY, maxY, partial));
    }

    private static CellShape classifyCell(Level level, FluidSectionDataCache cache, int x, int y, int z, Fluid fluid) {
        boolean frontier = false;
        boolean outlet = false;
        boolean inlet = false;
        int amount = cache.amount(x, y, z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        pos.set(x, y, z);
        BlockState state = level.getBlockState(pos);
        FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, pos, state);

        for (Direction direction : ALL_DIRECTIONS) {
            int nx = x + direction.getStepX();
            int ny = y + direction.getStepY();
            int nz = z + direction.getStepZ();
            Fluid neighborFluid = cache.fluidType(nx, ny, nz);
            int neighborAmount = cache.amount(nx, ny, nz);
            if (neighborFluid != null && neighborFluid.isSame(fluid)) {
                if (neighborAmount < amount) {
                    frontier = true;
                }
                continue;
            }
            byte flags = cache.flags(nx, ny, nz);
            if ((flags & FluidSectionDataCache.LOADED) == 0) {
                frontier = true;
                continue;
            }
            if ((flags & FluidSectionDataCache.AIR) != 0 || (flags & FluidSectionDataCache.REPLACEABLE) != 0) {
                frontier = true;
                if (direction == Direction.DOWN) {
                    outlet = true;
                }
                if (direction == Direction.UP) {
                    inlet = true;
                }
                continue;
            }
            if (direction == Direction.DOWN) {
                neighbor.set(nx, ny, nz);
                BlockState neighborState = level.getBlockState(neighbor);
                FluidState neighborStateFluid = FFFluidUtils.getEffectiveFluidState(level, neighbor, neighborState);
                if (fluid instanceof net.minecraft.world.level.material.FlowingFluid flowingFluid
                        && FFFluidUtils.canFluidFlowFromPosToDirection(flowingFluid, Math.max(1, fluidState.getAmount()),
                        level, pos, state, direction, neighbor, neighborState, neighborStateFluid)) {
                    outlet = true;
                    frontier = true;
                }
            }
        }
        return new CellShape(frontier, outlet, inlet);
    }

    private record CellShape(boolean frontier, boolean outlet, boolean inlet) {
    }

    public record FluidComponentCell(Fluid fluid, int amount, int componentId,
                                     boolean frontier, boolean outlet, boolean inlet) {
    }

    public record FluidComponentSummary(int componentId, Fluid fluid, int cellCount,
                                        int totalInternalMass, int frontierCells, int outletCells,
                                        int inletCells, int minY, int maxY, boolean partial) {
        public String shortDebugString() {
            return String.format(Locale.ROOT,
                "component=%d fluid=%s cells=%d mass=%d frontier=%d outlets=%d inlets=%d y=%d..%d partial=%s",
                componentId, fluid, cellCount, totalInternalMass, frontierCells, outletCells, inletCells,
                minY, maxY, partial);
        }
    }

    private static final class DimensionGraph {
        private final ConcurrentHashMap<Long, FluidComponentCell> cells = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Integer, FluidComponentSummary> summaries = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Integer> componentByCell = new ConcurrentHashMap<>();
        private final LongOpenHashSet dirtySeeds = new LongOpenHashSet();
        private volatile long lastProcessedTick = Long.MIN_VALUE;

        private int dirtySeedCount() {
            synchronized (dirtySeeds) {
                return dirtySeeds.size();
            }
        }
    }
}
