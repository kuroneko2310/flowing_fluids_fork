package traben.flowing_fluids;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.ChunkLocalSlopeCache;
import traben.flowing_fluids.ExtendedWaterlogStore;
import traben.flowing_fluids.FluidSpatialGrid;
import traben.flowing_fluids.performance.InfiniteBiomeRefillSuppression;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class FFFluidUtils {
    private static final int MIN_DRY_CELL_FILL_LEVEL = 2;
    private static final int SHALLOW_ENTITY_MOVEMENT_MAX_WATER_LEVEL = 3;
    private static final double ENTITY_FLUID_INTERSECTION_EPSILON = 1.0E-5D;
    private static final double SHALLOW_ENTITY_MOVEMENT_MAX_WATER_HEIGHT = 3.0D / 8.0D;
    private static final float SHALLOW_ENTITY_MOVEMENT_SPEED_MULTIPLIER = 1.15F;
    private static final double FLOWING_WATER_CURRENT_HORIZONTAL_EPSILON = 1.0E-4D;
    private static final double FLOWING_WATER_CURRENT_PUSH_MULTIPLIER = 2.5D;
    private static final float FLOWING_WATER_CURRENT_MOVE_INPUT_MULTIPLIER = 0.7F;
    private static final double PARTIAL_FLUID_HEIGHT_EPSILON = 1.0E-4D;
    private static final double SHAPE_FACE_SAMPLE_DEPTH = 1.0D / 32.0D;
    private static final double SHAPE_SAMPLE_EPSILON = 1.0E-5D;
    private static final double[] FACE_OPENING_SAMPLES = {0.125D, 0.375D, 0.625D, 0.875D};

    private static final Direction[] CARDINAL_DIRECTIONS = Direction.Plane.HORIZONTAL.stream().toArray(Direction[]::new);
    private static final Direction[] ALL_DIRECTIONS = Direction.values();
    private static final ThreadLocal<Direction[]> CARDINAL_BUFFER = ThreadLocal.withInitial(() -> new Direction[CARDINAL_DIRECTIONS.length]);
    private static final ThreadLocal<Direction[]> ALL_DIRECTION_BUFFER = ThreadLocal.withInitial(() -> new Direction[ALL_DIRECTIONS.length]);

    // Pre-computed shuffle patterns for cardinal directions (24 permutations for 4 elements)
    // This optimization avoids the Fisher-Yates shuffle overhead for frequently used cardinal directions
    private static final Direction[][] CARDINAL_SHUFFLE_PATTERNS = {
        {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST},
        {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST},
        {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST},
        {Direction.NORTH, Direction.EAST, Direction.WEST, Direction.SOUTH},
        {Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST},
        {Direction.NORTH, Direction.WEST, Direction.EAST, Direction.SOUTH},
        {Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST},
        {Direction.SOUTH, Direction.NORTH, Direction.WEST, Direction.EAST},
        {Direction.SOUTH, Direction.EAST, Direction.NORTH, Direction.WEST},
        {Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.NORTH},
        {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST},
        {Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.NORTH},
        {Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.WEST},
        {Direction.EAST, Direction.NORTH, Direction.WEST, Direction.SOUTH},
        {Direction.EAST, Direction.SOUTH, Direction.NORTH, Direction.WEST},
        {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH},
        {Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH},
        {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH},
        {Direction.WEST, Direction.NORTH, Direction.SOUTH, Direction.EAST},
        {Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH},
        {Direction.WEST, Direction.SOUTH, Direction.NORTH, Direction.EAST},
        {Direction.WEST, Direction.SOUTH, Direction.EAST, Direction.NORTH},
        {Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH},
        {Direction.WEST, Direction.EAST, Direction.SOUTH, Direction.NORTH}
    };

    // ThreadLocal caches for fluid traversal to avoid allocations
    private static final ThreadLocal<LongArrayFIFOQueue> POSITION_QUEUE = ThreadLocal.withInitial(LongArrayFIFOQueue::new);
    private static final ThreadLocal<LongOpenHashSet> VISITED_POSITIONS = ThreadLocal.withInitial(LongOpenHashSet::new);
    private static final ThreadLocal<LongOpenHashSet> TARGET_POSITIONS = ThreadLocal.withInitial(LongOpenHashSet::new);
    private static final ThreadLocal<LongArrayList> POSITION_BUFFER = ThreadLocal.withInitial(LongArrayList::new);
    private static final ThreadLocal<IntArrayList> LEVEL_BUFFER = ThreadLocal.withInitial(IntArrayList::new);
    private static final int DEFAULT_CONNECTED_FLUID_SEARCH_DEPTH = 40;

    private record ConnectedFluidCollectionScan(int foundAmount, long[] positions, int[] levels) {
    }

    private record QueuedBulkFluidChange(BlockPos pos, Fluid fluid, boolean hasFluid, int internalAmount) {
    }

    private static final class BulkFluidChangeContext {
        private LevelAccessor levelAccessor;
        private int depth;
        private final LinkedHashMap<Long, QueuedBulkFluidChange> queuedChanges = new LinkedHashMap<>();
        private final Set<ChunkPos> slopeChunks = new HashSet<>();
        private final LinkedHashMap<Long, BlockPos> componentInvalidations = new LinkedHashMap<>();

        private void reset(LevelAccessor levelAccessor) {
            this.levelAccessor = levelAccessor;
            this.depth = 0;
            this.queuedChanges.clear();
            this.slopeChunks.clear();
            this.componentInvalidations.clear();
        }
    }

    private static final ThreadLocal<BulkFluidChangeContext> BULK_FLUID_CHANGES =
            ThreadLocal.withInitial(BulkFluidChangeContext::new);

    public static int seaLevel(net.minecraft.world.level.LevelReader level) {
        if (level == null || FlowingFluids.config == null) {
            return level == null ? 0 : level.getSeaLevel();
        }

        int override = FlowingFluids.config.dimensionSeaLevelOverrides
                .getOrDefault(level.dimensionType().hashCode(), Integer.MIN_VALUE);
        if (override != Integer.MIN_VALUE) {
            return override;
        }

        int defaultOverride = FlowingFluids.config.defaultSeaLevelOverride;
        if (defaultOverride != Integer.MIN_VALUE) {
            return defaultOverride;
        }

        return level.getSeaLevel();
    }

    private static boolean hasReachedConnectedFluidTraversalBudget(int traversedCells, int depth) {
        return traversedCells >= Math.max(1, depth);
    }

    private static LongArrayFIFOQueue getPositionQueue() {
        LongArrayFIFOQueue queue = POSITION_QUEUE.get();
        queue.clear();
        return queue;
    }

    private static LongOpenHashSet getVisitedPositions() {
        LongOpenHashSet visited = VISITED_POSITIONS.get();
        visited.clear();
        return visited;
    }

    private static LongOpenHashSet getTargetPositions() {
        LongOpenHashSet targets = TARGET_POSITIONS.get();
        targets.clear();
        return targets;
    }

    private static LongArrayList getPositionBuffer() {
        LongArrayList buffer = POSITION_BUFFER.get();
        buffer.clear();
        return buffer;
    }

    private static IntArrayList getLevelBuffer() {
        IntArrayList buffer = LEVEL_BUFFER.get();
        buffer.clear();
        return buffer;
    }

    private static boolean beginBulkFluidChanges(LevelAccessor levelAccessor) {
        if (levelAccessor == null) {
            return false;
        }
        BulkFluidChangeContext context = BULK_FLUID_CHANGES.get();
        if (context.depth == 0) {
            context.reset(levelAccessor);
        } else if (context.levelAccessor != levelAccessor) {
            return false;
        }
        context.depth++;
        return true;
    }

    private static void endBulkFluidChanges(LevelAccessor levelAccessor) {
        if (levelAccessor == null) {
            return;
        }
        BulkFluidChangeContext context = BULK_FLUID_CHANGES.get();
        if (context.depth <= 0 || context.levelAccessor != levelAccessor) {
            return;
        }
        context.depth--;
        if (context.depth > 0) {
            return;
        }
        flushBulkFluidChanges(levelAccessor, context);
        context.reset(null);
    }

    private static void runWithBulkFluidChanges(LevelAccessor levelAccessor, Runnable action) {
        boolean batching = beginBulkFluidChanges(levelAccessor);
        try {
            action.run();
        } finally {
            if (batching) {
                endBulkFluidChanges(levelAccessor);
            }
        }
    }

    private static boolean queueBulkFluidChange(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int newAmount,
                                                boolean invalidateConnectedComponents) {
        BulkFluidChangeContext context = BULK_FLUID_CHANGES.get();
        if (context.depth <= 0 || context.levelAccessor != levelAccessor || pos == null || fluid == null) {
            return false;
        }
        BlockPos immutablePos = pos.immutable();
        int clamped = Math.max(0, Math.min(8, newAmount));
        context.queuedChanges.put(immutablePos.asLong(),
                new QueuedBulkFluidChange(immutablePos, fluid, clamped > 0, FluidAmountConverter.toInternal(clamped)));
        ChunkLocalSlopeCache.collectAffectedChunks(immutablePos, context.slopeChunks);
        if (invalidateConnectedComponents) {
            context.componentInvalidations.put(immutablePos.asLong(), immutablePos);
        }
        return true;
    }

    private static boolean queueBulkConnectedComponentInvalidation(LevelAccessor levelAccessor, BlockPos pos) {
        BulkFluidChangeContext context = BULK_FLUID_CHANGES.get();
        if (context.depth <= 0 || context.levelAccessor != levelAccessor || pos == null) {
            return false;
        }
        BlockPos immutablePos = pos.immutable();
        context.componentInvalidations.put(immutablePos.asLong(), immutablePos);
        return true;
    }

    private static void flushBulkFluidChanges(LevelAccessor levelAccessor, BulkFluidChangeContext context) {
        if (context.queuedChanges.isEmpty()) {
            return;
        }

        List<BlockPos> changedPositions = new ArrayList<>(context.queuedChanges.size());
        Set<ChunkPos> touchedChunks = new HashSet<>();
        Level serverLevel = levelAccessor instanceof Level level && !level.isClientSide() ? level : null;

        for (QueuedBulkFluidChange change : context.queuedChanges.values()) {
            touchedChunks.add(new ChunkPos(change.pos()));
        }
        FluidSpatialGrid.markChunksDirtyForBulkFluidChanges(levelAccessor, touchedChunks);

        for (QueuedBulkFluidChange change : context.queuedChanges.values()) {
            FluidSpatialGrid.setFluidAtFromBuffer(levelAccessor, change.pos(), change.hasFluid(), change.internalAmount());
            changedPositions.add(change.pos());
        }

        FluidSpatialGrid.invalidateLocalComponents(levelAccessor, changedPositions);
        FluidSpatialGrid.refreshAreaTypesForChunks(levelAccessor, touchedChunks);
        AdaptiveTickScheduler.notifyFluidChangesBulk(levelAccessor, changedPositions);
        if (serverLevel != null) {
            FluidActivityTracker.recordChanges(serverLevel, changedPositions);
            for (QueuedBulkFluidChange change : context.queuedChanges.values()) {
                if (shouldWakeBulkPlacedFluid(levelAccessor, change)) {
                    serverLevel.scheduleTick(change.pos(), change.fluid(), 1);
                }
            }
        }

        for (ChunkPos chunkPos : context.slopeChunks) {
            ChunkLocalSlopeCache.clearChunk(levelAccessor, chunkPos);
        }

        for (BlockPos center : context.componentInvalidations.values()) {
            invalidateConnectedFluidComponents(levelAccessor, center);
        }
    }

    private static boolean shouldWakeBulkPlacedFluid(LevelAccessor levelAccessor, QueuedBulkFluidChange change) {
        if (!(change.fluid() instanceof FlowingFluid flowingFluid) || !change.hasFluid()) {
            return false;
        }
        BlockPos pos = change.pos();
        BlockPos belowPos = pos.below();
        if (levelAccessor instanceof Level level && !level.isInWorldBounds(belowPos)) {
            return false;
        }
        BlockState currentState = levelAccessor.getBlockState(pos);
        FluidState currentFluid = getEffectiveFluidState(levelAccessor, pos, currentState);
        if (!currentFluid.getType().isSame(change.fluid()) || currentFluid.getAmount() <= 0) {
            return false;
        }
        BlockState belowState = levelAccessor.getBlockState(belowPos);
        FluidState belowFluid = getEffectiveFluidState(levelAccessor, belowPos, belowState);
        return canFluidFlowFromPosToDirection(flowingFluid, currentFluid.getAmount(), levelAccessor, pos, currentState,
                Direction.DOWN, belowPos, belowState, belowFluid);
    }


    public static @NotNull ResourceLocation res(String fullPath){
        #if MC >= MC_21
        return ResourceLocation.parse(fullPath);
        #else
        return new ResourceLocation(fullPath);
        #endif
    }

    public static @NotNull ResourceLocation res(String namespace, String path){
        #if MC >= MC_21
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
        #else
        return new ResourceLocation(namespace, path);
        #endif
    }

    public static ResourceLocation getId(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block);
    }

    public static boolean canFluidFlowToNeighbourFromPos(LevelAccessor accessor, BlockPos pos, FlowingFluid fluid, int amount) {
        return canFluidFlowToNeighbourFromPos(accessor, pos, accessor.getBlockState(pos), fluid, amount);
    }

    public static boolean canFluidFlowToNeighbourFromPos(LevelAccessor accessor, BlockPos pos, BlockState sourceState,
                                                         FlowingFluid fluid, int amount) {
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            neighborPos.setWithOffset(pos, direction);
            BlockState neighborState = accessor.getBlockState(neighborPos);
            if (FFFluidUtils.canFluidFlowFromPosToDirection(
                    fluid,
                    amount,
                    accessor,
                    pos,
                    sourceState,
                    direction,
                    neighborPos,
                    neighborState,
                    getEffectiveFluidState(accessor, neighborPos, neighborState))) {
                return true;
            }
        }
        return false;
    }

    public static FluidState getStateForFluidByAmount(Fluid fluid, int amount) {
        if (amount < 1) {
            return Fluids.EMPTY.defaultFluidState();
        }
        if (fluid instanceof FlowingFluid flowing) {
            return amount >= 8 ? flowing.getSource(false) : flowing.getFlowing(amount, false);
        }
        return amount >= 8 ? fluid.defaultFluidState() : fluid.defaultFluidState().trySetValue(FlowingFluid.LEVEL, amount);
    }


    public static BlockState getBlockForFluidByAmount(Fluid fluid, int amount) {
        return getStateForFluidByAmount(fluid, amount).createLegacyBlock();
    }

    public record DiscreteFlowBalance(int sourceAmount, int destinationAmount) {
    }

    private record VirtualFluidCavity(double minY, double maxY) {
        double height() {
            return Math.max(0.0D, maxY - minY);
        }
    }

    public static DiscreteFlowBalance resolveDiscreteFlowBalance(int sourceAmount, int destinationAmount,
                                                                 int minSourceAmount, float destinationBiasLevels) {
        int clampedSource = Math.max(0, Math.min(8, sourceAmount));
        int clampedDestination = Math.max(0, Math.min(8, destinationAmount));
        int totalLevels = clampedSource + clampedDestination;
        if (totalLevels <= 0) {
            return new DiscreteFlowBalance(0, 0);
        }

        int minSource = Math.max(0, Math.min(8, minSourceAmount));
        int minCandidateSource = Math.max(minSource, Math.max(0, totalLevels - 8));
        int maxCandidateSource = Math.min(8, totalLevels);
        if (minCandidateSource > maxCandidateSource) {
            minCandidateSource = maxCandidateSource;
        }

        int sourceInternal = FluidAmountConverter.toInternal(clampedSource);
        int destinationInternal = FluidAmountConverter.toInternal(clampedDestination);
        int totalInternal = sourceInternal + destinationInternal;
        int targetDestinationInternal = (totalInternal + 1) / 2;
        int biasInternal = Math.round(destinationBiasLevels * (FluidAmountConverter.getMaxInternal() / 8.0f));
        int minDestination = totalLevels - maxCandidateSource;
        int maxDestination = Math.min(8, totalLevels - minCandidateSource);
        targetDestinationInternal = Math.max(FluidAmountConverter.toInternal(minDestination),
                Math.min(FluidAmountConverter.toInternal(maxDestination), targetDestinationInternal + biasInternal));
        int targetSourceInternal = totalInternal - targetDestinationInternal;

        int bestSource = clampedSource;
        int bestDestination = clampedDestination;
        long bestScore = Long.MAX_VALUE;
        int bestMovement = Integer.MAX_VALUE;
        boolean preferHigherDestination = destinationBiasLevels >= 0.0f;

        for (int candidateSource = minCandidateSource; candidateSource <= maxCandidateSource; candidateSource++) {
            int candidateDestination = totalLevels - candidateSource;
            if (candidateDestination < 0 || candidateDestination > 8) {
                continue;
            }

            long score = Math.abs(FluidAmountConverter.toInternal(candidateSource) - targetSourceInternal)
                    + Math.abs(FluidAmountConverter.toInternal(candidateDestination) - targetDestinationInternal);
            int movement = Math.abs(candidateSource - clampedSource) + Math.abs(candidateDestination - clampedDestination);
            boolean better = score < bestScore;
            if (!better && score == bestScore) {
                if (preferHigherDestination && candidateDestination > bestDestination) {
                    better = true;
                } else if (!preferHigherDestination && candidateDestination < bestDestination) {
                    better = true;
                } else if (movement < bestMovement) {
                    better = true;
                }
            }

            if (better) {
                bestScore = score;
                bestMovement = movement;
                bestSource = candidateSource;
                bestDestination = candidateDestination;
            }
        }

        return new DiscreteFlowBalance(bestSource, bestDestination);
    }

    public static boolean isExtendedWaterloggable(LevelAccessor level, BlockState state) {
        if (!FlowingFluids.config.enableExtendedWaterlogging || !FlowingFluids.config.extendedWaterloggingAllowFences) {
            return false;
        }
        var block = state.getBlock();
        if (block instanceof FenceGateBlock) {
            return false;
        }
        return state.is(net.minecraft.tags.BlockTags.FENCES)
                || state.is(net.minecraft.tags.BlockTags.WALLS)
                || block == net.minecraft.world.level.block.Blocks.IRON_BARS;
    }

    public static boolean isPassThroughFluidBlock(LevelAccessor level, BlockState state, Direction direction) {
        var block = state.getBlock();
        if (block instanceof DoorBlock && FlowingFluids.config.applyPressureToDoors) {
            return state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        }
        if (block instanceof TrapDoorBlock && FlowingFluids.config.applyPressureToTrapdoors) {
            return state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        }
        if (block instanceof FenceGateBlock && FlowingFluids.config.applyPressureToFenceGates) {
            return state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        }
        if (!FlowingFluids.config.extendedWaterloggingAllowFences) {
            return false;
        }
        return state.is(net.minecraft.tags.BlockTags.FENCES)
                || state.is(net.minecraft.tags.BlockTags.WALLS)
                || block == net.minecraft.world.level.block.Blocks.IRON_BARS;
    }

    static boolean usesShapeAwareVirtualFluidState(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.getBlock() instanceof SlabBlock && state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            return state.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE;
        }
        return state.getBlock() instanceof StairBlock;
    }

    @Nullable
    private static VirtualFluidCavity getVirtualFluidCavity(BlockState state) {
        if (state == null) {
            return null;
        }
        if (state.getBlock() instanceof SlabBlock && state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            return getVirtualFluidCavity(state.getValue(BlockStateProperties.SLAB_TYPE));
        }
        return null;
    }

    @Nullable
    private static VirtualFluidCavity getVirtualFluidCavity(@Nullable SlabType slabType) {
        if (slabType == SlabType.BOTTOM) {
            return new VirtualFluidCavity(0.5D, 1.0D);
        }
        if (slabType == SlabType.TOP) {
            return new VirtualFluidCavity(0.0D, 0.5D);
        }
        return null;
    }

    static double getVirtualFluidFloorY(BlockState state) {
        VirtualFluidCavity cavity = getVirtualFluidCavity(state);
        return cavity == null ? 0.0D : cavity.minY();
    }

    static double getVirtualFluidCeilingY(BlockState state) {
        VirtualFluidCavity cavity = getVirtualFluidCavity(state);
        return cavity == null ? 1.0D : cavity.maxY();
    }

    static double getVirtualFluidSurfaceY(BlockGetter blockGetter, BlockPos pos, BlockState state, FluidState fluidState) {
        VirtualFluidCavity cavity = getVirtualFluidCavity(state);
        return getVirtualFluidSurfaceY(cavity, blockGetter, pos, fluidState);
    }

    static double getVirtualFluidSurfaceY(@Nullable SlabType slabType, FluidState fluidState) {
        return getVirtualFluidSurfaceY(getVirtualFluidCavity(slabType), null, null, fluidState);
    }

    static double getVirtualFluidSurfaceY(@Nullable SlabType slabType, float normalizedHeight) {
        return getVirtualFluidSurfaceY(getVirtualFluidCavity(slabType), Mth.clamp(normalizedHeight, 0.0F, 1.0F));
    }

    static boolean hasCompatibleVirtualFluidHeights(BlockGetter blockGetter,
                                                    BlockPos sourcePos,
                                                    BlockState sourceState,
                                                    FluidState sourceFluidState,
                                                    Direction direction,
                                                    BlockPos targetPos,
                                                    BlockState targetState,
                                                    FluidState targetFluidState) {
        VirtualFluidCavity sourceCavity = getVirtualFluidCavity(sourceState);
        VirtualFluidCavity targetCavity = getVirtualFluidCavity(targetState);
        return hasCompatibleVirtualFluidHeights(sourceCavity, blockGetter, sourcePos, sourceState, sourceFluidState,
                direction, targetCavity, targetPos, targetFluidState);
    }

    static boolean hasCompatibleVirtualFluidHeights(@Nullable SlabType sourceSlabType,
                                                    FluidState sourceFluidState,
                                                    Direction direction,
                                                    @Nullable SlabType targetSlabType) {
        return hasCompatibleVirtualFluidHeights(getVirtualFluidCavity(sourceSlabType), null, null, null,
                sourceFluidState, direction, getVirtualFluidCavity(targetSlabType), null, Fluids.EMPTY.defaultFluidState());
    }

    static boolean hasCompatibleVirtualFluidHeights(@Nullable SlabType sourceSlabType,
                                                    float sourceNormalizedHeight,
                                                    Direction direction,
                                                    @Nullable SlabType targetSlabType) {
        return hasCompatibleVirtualFluidHeights(getVirtualFluidCavity(sourceSlabType),
                getVirtualFluidSurfaceY(getVirtualFluidCavity(sourceSlabType), Mth.clamp(sourceNormalizedHeight, 0.0F, 1.0F)),
                direction, getVirtualFluidCavity(targetSlabType));
    }

    private static boolean hasCompatibleVirtualFluidHeights(@Nullable VirtualFluidCavity sourceCavity,
                                                            BlockGetter blockGetter,
                                                            BlockPos sourcePos,
                                                            BlockState sourceState,
                                                            FluidState sourceFluidState,
                                                            Direction direction,
                                                            @Nullable VirtualFluidCavity targetCavity,
                                                            BlockPos targetPos,
                                                            FluidState targetFluidState) {
        if (sourceCavity == null && targetCavity == null) {
            return true;
        }

        double sourceFloor = sourceCavity == null ? 0.0D : sourceCavity.minY();
        double sourceSurface = getVirtualFluidSurfaceY(sourceCavity, blockGetter, sourcePos, sourceFluidState);
        return hasCompatibleVirtualFluidHeights(sourceCavity, sourceSurface, direction, targetCavity);
    }

    private static double getVirtualFluidSurfaceY(@Nullable VirtualFluidCavity cavity,
                                                  BlockGetter blockGetter,
                                                  BlockPos pos,
                                                  FluidState fluidState) {
        double floorY = cavity == null ? 0.0D : cavity.minY();
        if (fluidState == null || fluidState.isEmpty() || fluidState.getAmount() <= 0) {
            return floorY;
        }

        double normalizedHeight = Mth.clamp(blockGetter == null || pos == null
                ? fluidState.getOwnHeight()
                : fluidState.getHeight(blockGetter, pos), 0.0F, 1.0F);
        if (cavity == null) {
            return normalizedHeight;
        }
        return cavity.minY() + normalizedHeight * cavity.height();
    }

    private static double getVirtualFluidSurfaceY(@Nullable VirtualFluidCavity cavity, double normalizedHeight) {
        if (cavity == null) {
            return normalizedHeight;
        }
        return cavity.minY() + normalizedHeight * cavity.height();
    }

    private static boolean hasCompatibleVirtualFluidHeights(@Nullable VirtualFluidCavity sourceCavity,
                                                            double sourceSurface,
                                                            Direction direction,
                                                            @Nullable VirtualFluidCavity targetCavity) {
        if (sourceCavity == null && targetCavity == null) {
            return true;
        }

        double sourceFloor = sourceCavity == null ? 0.0D : sourceCavity.minY();
        double targetFloor = targetCavity == null ? 0.0D : targetCavity.minY();
        double targetCeiling = targetCavity == null ? 1.0D : targetCavity.maxY();

        if (direction == Direction.DOWN) {
            return sourceSurface > targetFloor + PARTIAL_FLUID_HEIGHT_EPSILON;
        }
        if (direction == Direction.UP) {
            return targetCeiling > sourceFloor + PARTIAL_FLUID_HEIGHT_EPSILON;
        }

        if ((sourceCavity == null) != (targetCavity == null)) {
            // When only one side is a partial-shape virtual cell, treat the horizontal opening as the
            // entry band. Requiring a strict overlap against the cavity band makes slabs/stairs accept
            // side water only once the source surface rises unnaturally high.
            return sourceSurface > sourceFloor + PARTIAL_FLUID_HEIGHT_EPSILON;
        }

        double overlapMin = Math.max(sourceFloor, targetFloor);
        double overlapMax = Math.min(sourceSurface, targetCeiling);
        return overlapMax > overlapMin + PARTIAL_FLUID_HEIGHT_EPSILON;
    }

    static boolean hasShapeAwareFluidOpening(BlockState state, @Nullable Direction direction) {
        if (!usesShapeAwareVirtualFluidState(state)) {
            return false;
        }
        return hasShapeFaceOpening(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), direction);
    }

    static boolean hasAnyShapeOpening(BlockState state) {
        if (state == null) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (hasShapeFaceOpening(shape, direction)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasShapeFaceOpening(VoxelShape shape, @Nullable Direction direction) {
        if (direction == null || shape == null || shape.isEmpty()) {
            return true;
        }

        for (double first : FACE_OPENING_SAMPLES) {
            for (double second : FACE_OPENING_SAMPLES) {
                double x;
                double y;
                double z;
                switch (direction) {
                    case DOWN -> {
                        x = first;
                        y = SHAPE_FACE_SAMPLE_DEPTH;
                        z = second;
                    }
                    case UP -> {
                        x = first;
                        y = 1.0D - SHAPE_FACE_SAMPLE_DEPTH;
                        z = second;
                    }
                    case NORTH -> {
                        x = first;
                        y = second;
                        z = SHAPE_FACE_SAMPLE_DEPTH;
                    }
                    case SOUTH -> {
                        x = first;
                        y = second;
                        z = 1.0D - SHAPE_FACE_SAMPLE_DEPTH;
                    }
                    case WEST -> {
                        x = SHAPE_FACE_SAMPLE_DEPTH;
                        y = second;
                        z = first;
                    }
                    case EAST -> {
                        x = 1.0D - SHAPE_FACE_SAMPLE_DEPTH;
                        y = second;
                        z = first;
                    }
                    default -> throw new IllegalStateException("Unexpected direction: " + direction);
                }

                if (!isPointInsideShape(shape, x, y, z)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isPointInsideShape(VoxelShape shape, double x, double y, double z) {
        for (AABB box : shape.toAabbs()) {
            if (x > box.minX + SHAPE_SAMPLE_EPSILON && x < box.maxX - SHAPE_SAMPLE_EPSILON
                    && y > box.minY + SHAPE_SAMPLE_EPSILON && y < box.maxY - SHAPE_SAMPLE_EPSILON
                    && z > box.minZ + SHAPE_SAMPLE_EPSILON && z < box.maxZ - SHAPE_SAMPLE_EPSILON) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDirectionalVirtualFluidPassableBlock(LevelAccessor level, BlockState state, @Nullable Direction direction) {
        return isPassThroughFluidBlock(level, state, direction)
                || hasShapeAwareFluidOpening(state, direction);
    }

    private static boolean isVirtualFluidStorageEnabled() {
        return FlowingFluids.config != null && FlowingFluids.config.enableExtendedWaterlogging;
    }

    private static boolean hasRawVanillaWaterlogSupport(BlockState state) {
        return state.getBlock() instanceof LiquidBlockContainer && state.getBlock() instanceof BucketPickup;
    }

    private static boolean shouldOverrideVanillaWaterlogging(BlockState state) {
        return hasRawVanillaWaterlogSupport(state)
                && state.hasProperty(BlockStateProperties.WATERLOGGED)
                && (usesShapeAwareVirtualFluidState(state) || hasAnyShapeOpening(state));
    }

    public static boolean supportsVirtualFluidState(LevelAccessor level, BlockState state) {
        if (!isVirtualFluidStorageEnabled()) {
            return false;
        }
        return isExtendedWaterloggable(level, state)
                || shouldOverrideVanillaWaterlogging(state)
                || isGenericNonFullVirtualFluidBlock(state)
                || isDirectionalVirtualFluidPassableBlock(level, state, null);
    }

    private static boolean isGenericNonFullVirtualFluidBlock(BlockState state) {
        if (state == null || state.isAir() || state.hasBlockEntity()) {
            return false;
        }
        if (state.getBlock() instanceof LiquidBlockContainer || state.canBeReplaced(Fluids.WATER)) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return !shape.isEmpty() && hasAnyShapeOpening(state);
    }

    public static boolean isVanillaWaterloggable(BlockState state) {
        return hasRawVanillaWaterlogSupport(state) && !shouldOverrideVanillaWaterlogging(state);
    }

    public static boolean canStorePartialFluidAmount(LevelAccessor levelAccessor, BlockPos pos, BlockState blockState, Fluid fluid) {
        if (supportsVirtualFluidState(levelAccessor, blockState)) {
            return true;
        }
        if (isVanillaWaterloggable(blockState)) {
            return false;
        }
        FluidState existingState = getEffectiveFluidState(levelAccessor, pos, blockState);
        if (!existingState.isEmpty() && !existingState.getType().isSame(fluid)) {
            return false;
        }
        return existingState.getType().isSame(fluid) || blockState.isAir() || blockState.canBeReplaced(fluid);
    }

    public static boolean isSmallSupportedThinSurfaceCluster(LevelAccessor levelAccessor, BlockPos origin, Fluid fluid,
                                                             int maxClusterSize, int maxFluidAmount) {
        if (!(levelAccessor instanceof Level level)
                || origin == null
                || fluid == null
                || maxClusterSize <= 0
                || maxFluidAmount <= 0) {
            return false;
        }

        FluidState originState = getEffectiveFluidState(level, origin);
        if (!originState.getType().isSame(fluid) || originState.getAmount() <= 0 || originState.getAmount() > maxFluidAmount) {
            return false;
        }

        long[] queue = new long[Math.max(4, maxClusterSize + 1)];
        long[] visited = new long[Math.max(4, maxClusterSize + 1)];
        int head = 0;
        int tail = 0;
        int visitedCount = 0;
        long originKey = origin.asLong();
        queue[tail++] = originKey;
        visited[visitedCount++] = originKey;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos belowPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos abovePos = new BlockPos.MutableBlockPos();

        while (head < tail) {
            long currentKey = queue[head++];
            cursor.set(BlockPos.getX(currentKey), BlockPos.getY(currentKey), BlockPos.getZ(currentKey));

            BlockState currentBlockState = level.getBlockState(cursor);
            FluidState currentState = getEffectiveFluidState(level, cursor, currentBlockState);
            int currentAmount = currentState.getAmount();
            if (!currentState.getType().isSame(fluid) || currentAmount <= 0 || currentAmount > maxFluidAmount) {
                return false;
            }

            abovePos.set(cursor).move(Direction.UP);
            FluidState aboveFluid = getEffectiveFluidState(level, abovePos, level.getBlockState(abovePos));
            if (aboveFluid.getType().isSame(fluid) && aboveFluid.getAmount() > 0) {
                return false;
            }

            belowPos.set(cursor).move(Direction.DOWN);
            BlockState belowState = level.getBlockState(belowPos);
            FluidState belowFluid = getEffectiveFluidState(level, belowPos, belowState);
            boolean supportedBelow = (belowFluid.getType().isSame(fluid) && belowFluid.getAmount() >= currentAmount)
                    || (!belowState.isAir() && !belowState.canBeReplaced(fluid));
            if (!supportedBelow) {
                return false;
            }

            if (fluid instanceof FlowingFluid flowingFluid
                    && canFluidFlowFromPosToDirection(flowingFluid, Math.max(1, currentAmount), level, cursor, currentBlockState,
                    Direction.DOWN, belowPos, belowState, belowFluid)
                    && (belowFluid.isEmpty() || !belowFluid.getType().isSame(fluid) || belowFluid.getAmount() < currentAmount)) {
                return false;
            }

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                neighborPos.set(cursor).move(direction);
                FluidState neighborFluid = getEffectiveFluidState(level, neighborPos, level.getBlockState(neighborPos));
                if (!neighborFluid.getType().isSame(fluid) || neighborFluid.getAmount() <= 0) {
                    continue;
                }
                if (neighborFluid.getAmount() > maxFluidAmount) {
                    return false;
                }
                long neighborKey = neighborPos.asLong();
                boolean seen = false;
                for (int i = 0; i < visitedCount; i++) {
                    if (visited[i] == neighborKey) {
                        seen = true;
                        break;
                    }
                }
                if (seen) {
                    continue;
                }
                if (visitedCount >= maxClusterSize) {
                    return false;
                }
                visited[visitedCount++] = neighborKey;
                queue[tail++] = neighborKey;
            }
        }

        return visitedCount > 0 && visitedCount <= maxClusterSize;
    }

    private static int normalizeRequestedFluidAmount(LevelAccessor levelAccessor, BlockPos pos, BlockState blockState,
                                                     Fluid fluid, int requestedAmount) {
        int clampedAmount = Math.max(0, Math.min(8, requestedAmount));
        if (clampedAmount <= 0) {
            return 0;
        }
        if (supportsVirtualFluidState(levelAccessor, blockState)) {
            return clampedAmount;
        }
        if (isVanillaWaterloggable(blockState)) {
            return clampedAmount >= 8 ? 8 : 0;
        }
        return clampedAmount;
    }

    private static boolean clearStoredVirtualFluidState(LevelAccessor levelAccessor, BlockPos pos) {
        if (!ExtendedWaterlogStore.has(levelAccessor, pos)) {
            return false;
        }
        ExtendedWaterlogStore.remove(levelAccessor, pos);
        if (levelAccessor instanceof net.minecraft.server.level.ServerLevel level) {
            FlowingFluidsPlatform.syncVirtualFluidState(level, pos);
        }
        recordFluidCacheChange(levelAccessor, pos, Fluids.WATER, 0, false);
        return true;
    }

    public static FluidState getEffectiveFluidState(LevelAccessor level, BlockPos pos, BlockState state) {
        FluidState base = state.getFluidState();
        if (FlowingFluids.config == null || !FlowingFluids.config.enableExtendedWaterlogging
                || level == null || pos == null) {
            return base;
        }
        if (ExtendedWaterlogStore.has(level, pos)) {
            if (supportsVirtualFluidState(level, state)) {
                return ExtendedWaterlogStore.get(level, pos);
            }
            clearStoredVirtualFluidState(level, pos);
        }
        return base;
    }

    public static FluidState getEffectiveFluidState(LevelAccessor level, BlockPos pos) {
        return getEffectiveFluidState(level, pos, level.getBlockState(pos));
    }

    public static boolean shouldIgnoreShallowWaterMovement(Entity entity) {
        if (FlowingFluids.config == null || !FlowingFluids.config.enableMod) {
            return false;
        }
        if (!(entity instanceof LivingEntity living)
                || (!(living instanceof Player) && !(living instanceof Mob))
                || living instanceof WaterAnimal
                || living.canBreatheUnderwater()) {
            return false;
        }

        AABB bounds = entity.getBoundingBox().deflate(ENTITY_FLUID_INTERSECTION_EPSILON);
        if (bounds.getXsize() <= 0.0D || bounds.getYsize() <= 0.0D || bounds.getZsize() <= 0.0D) {
            return false;
        }

        int minX = net.minecraft.util.Mth.floor(bounds.minX);
        int minY = net.minecraft.util.Mth.floor(bounds.minY);
        int minZ = net.minecraft.util.Mth.floor(bounds.minZ);
        int maxX = net.minecraft.util.Mth.floor(bounds.maxX);
        int maxZ = net.minecraft.util.Mth.floor(bounds.maxZ);
        int maxFootY = Math.min(
                net.minecraft.util.Mth.floor(bounds.maxY - ENTITY_FLUID_INTERSECTION_EPSILON),
                net.minecraft.util.Mth.floor(bounds.minY + SHALLOW_ENTITY_MOVEMENT_MAX_WATER_HEIGHT + ENTITY_FLUID_INTERSECTION_EPSILON)
        );
        int maxBodyY = net.minecraft.util.Mth.floor(bounds.maxY - ENTITY_FLUID_INTERSECTION_EPSILON);

        Level level = entity.level();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean touchesFootWater = false;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxFootY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    BlockState blockState = level.getBlockState(cursor);
                    FluidState fluidState = getEffectiveFluidState(level, cursor, blockState);
                    if (!fluidState.is(FluidTags.WATER) || fluidState.getAmount() <= 0) {
                        continue;
                    }

                    double fluidSurface = cursor.getY() + fluidState.getHeight(level, cursor);
                    if (fluidSurface <= bounds.minY + ENTITY_FLUID_INTERSECTION_EPSILON
                            || cursor.getY() >= bounds.maxY - ENTITY_FLUID_INTERSECTION_EPSILON) {
                        continue;
                    }

                    touchesFootWater = true;
                    if (fluidState.getAmount() > SHALLOW_ENTITY_MOVEMENT_MAX_WATER_LEVEL
                            || fluidState.getHeight(level, cursor) > SHALLOW_ENTITY_MOVEMENT_MAX_WATER_HEIGHT + ENTITY_FLUID_INTERSECTION_EPSILON) {
                        return false;
                    }
                }
            }
        }

        if (!touchesFootWater) {
            return false;
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = Math.max(minY, maxFootY + 1); y <= maxBodyY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    FluidState fluidState = getEffectiveFluidState(level, cursor, level.getBlockState(cursor));
                    if (!fluidState.is(FluidTags.WATER) || fluidState.getAmount() <= 0) {
                        continue;
                    }

                    double fluidSurface = cursor.getY() + fluidState.getHeight(level, cursor);
                    if (fluidSurface > bounds.minY + SHALLOW_ENTITY_MOVEMENT_MAX_WATER_HEIGHT + ENTITY_FLUID_INTERSECTION_EPSILON
                            && cursor.getY() < bounds.maxY - ENTITY_FLUID_INTERSECTION_EPSILON) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    public static boolean isShallowWaterFluid(FluidState fluidState) {
        return fluidState != null
                && fluidState.is(FluidTags.WATER)
                && fluidState.getAmount() > 0
                && fluidState.getAmount() <= SHALLOW_ENTITY_MOVEMENT_MAX_WATER_LEVEL;
    }

    public static float getShallowWaterMovementSpeedMultiplier() {
        return SHALLOW_ENTITY_MOVEMENT_SPEED_MULTIPLIER;
    }

    public static boolean canGroundMobPathfindThroughShallowWater(Mob mob, BlockPos pos) {
        if (FlowingFluids.config == null
                || !FlowingFluids.config.enableMod
                || mob == null
                || pos == null
                || mob instanceof WaterAnimal
                || mob.canBreatheUnderwater()) {
            return false;
        }

        Level level = mob.level();
        BlockState stateAtFeet = level.getBlockState(pos);
        FluidState feetFluid = getEffectiveFluidState(level, pos, stateAtFeet);
        if (!isShallowWaterFluid(feetFluid)
                || feetFluid.getHeight(level, pos) > SHALLOW_ENTITY_MOVEMENT_MAX_WATER_HEIGHT + ENTITY_FLUID_INTERSECTION_EPSILON) {
            return false;
        }

        BlockPos abovePos = pos.above();
        BlockState aboveState = level.getBlockState(abovePos);
        FluidState aboveFluid = getEffectiveFluidState(level, abovePos, aboveState);
        if ((aboveState.blocksMotion() && !aboveState.getCollisionShape(level, abovePos).isEmpty())
                || (aboveFluid.is(FluidTags.WATER) && aboveFluid.getAmount() > 0)) {
            return false;
        }

        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        var belowShape = belowState.getCollisionShape(level, belowPos);
        if (!belowShape.isEmpty() && belowShape.max(Direction.Axis.Y) > ENTITY_FLUID_INTERSECTION_EPSILON) {
            return true;
        }

        FluidState belowFluid = getEffectiveFluidState(level, belowPos, belowState);
        return isShallowWaterFluid(belowFluid)
                && belowFluid.getHeight(level, belowPos) > SHALLOW_ENTITY_MOVEMENT_MAX_WATER_HEIGHT - ENTITY_FLUID_INTERSECTION_EPSILON;
    }

    public static double getFlowingWaterCurrentPushMultiplier() {
        return FLOWING_WATER_CURRENT_PUSH_MULTIPLIER;
    }

    public static float getFlowingWaterCurrentMoveInputMultiplier() {
        return FLOWING_WATER_CURRENT_MOVE_INPUT_MULTIPLIER;
    }

    public static boolean shouldBoostFlowingWaterCurrent(Entity entity) {
        if (FlowingFluids.config == null || !FlowingFluids.config.enableMod || shouldIgnoreShallowWaterMovement(entity)) {
            return false;
        }
        if (!(entity instanceof LivingEntity living)
                || (!(living instanceof Player) && !(living instanceof Mob))
                || living instanceof WaterAnimal
                || living.canBreatheUnderwater()) {
            return false;
        }

        AABB bounds = entity.getBoundingBox().deflate(ENTITY_FLUID_INTERSECTION_EPSILON);
        if (bounds.getXsize() <= 0.0D || bounds.getYsize() <= 0.0D || bounds.getZsize() <= 0.0D) {
            return false;
        }

        int minX = net.minecraft.util.Mth.floor(bounds.minX);
        int minY = net.minecraft.util.Mth.floor(bounds.minY);
        int minZ = net.minecraft.util.Mth.floor(bounds.minZ);
        int maxX = net.minecraft.util.Mth.floor(bounds.maxX);
        int maxY = net.minecraft.util.Mth.floor(bounds.maxY - ENTITY_FLUID_INTERSECTION_EPSILON);
        int maxZ = net.minecraft.util.Mth.floor(bounds.maxZ);

        Level level = entity.level();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    FluidState fluidState = getEffectiveFluidState(level, cursor, level.getBlockState(cursor));
                    if (!fluidState.is(FluidTags.WATER) || fluidState.getAmount() <= 0) {
                        continue;
                    }

                    double fluidSurface = cursor.getY() + fluidState.getHeight(level, cursor);
                    if (fluidSurface <= bounds.minY + ENTITY_FLUID_INTERSECTION_EPSILON
                            || cursor.getY() >= bounds.maxY - ENTITY_FLUID_INTERSECTION_EPSILON) {
                        continue;
                    }

                    var flow = fluidState.getFlow(level, cursor);
                    double horizontalFlow = flow.x * flow.x + flow.z * flow.z;
                    if (horizontalFlow > FLOWING_WATER_CURRENT_HORIZONTAL_EPSILON) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static boolean canGroundMobSpawnInShallowWater(EntityType<?> entityType, LevelAccessor level, BlockPos pos) {
        if (FlowingFluids.config == null
                || !FlowingFluids.config.enableMod
                || entityType == null
                || level == null
                || pos == null
                || SpawnPlacements.getPlacementType(entityType) != SpawnPlacements.Type.ON_GROUND) {
            return false;
        }

        BlockState currentState = level.getBlockState(pos);
        FluidState currentFluid = getEffectiveFluidState(level, pos, currentState);
        if (!isShallowWaterFluid(currentFluid)) {
            return false;
        }

        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        if (!belowState.isValidSpawn(level, belowPos, entityType)) {
            return false;
        }

        BlockPos abovePos = pos.above();
        BlockState aboveState = level.getBlockState(abovePos);
        FluidState aboveFluid = getEffectiveFluidState(level, abovePos, aboveState);
        if (!NaturalSpawner.isValidEmptySpawnBlock(level, abovePos, aboveState, aboveFluid, entityType)) {
            return false;
        }

        // Reuse vanilla body-space checks while treating shallow foot water as walkable ground.
        return NaturalSpawner.isValidEmptySpawnBlock(level, pos, currentState, Fluids.EMPTY.defaultFluidState(), entityType);
    }

    private static void recordFluidCacheChange(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int newAmount,
                                               boolean invalidateConnectedComponents) {
        if (queueBulkFluidChange(levelAccessor, pos, fluid, newAmount, invalidateConnectedComponents)) {
            return;
        }
        AdaptiveTickScheduler.notifyFluidChange(levelAccessor, pos);
        ChunkLocalSlopeCache.clearForFluidChange(levelAccessor, pos);
        if (FlowingFluids.config != null
                && FlowingFluids.config.enableWaterPressure
                && fluid.isSame(Fluids.WATER)) {
            traben.flowing_fluids.water.WaterPressureSystem.handleNeighborUpdate(levelAccessor, pos);
        }
        int clamped = Math.max(0, Math.min(8, newAmount));
        int internalAmount = FluidAmountConverter.toInternal(clamped);
        FluidSpatialGrid.setFluidAt(levelAccessor, pos, clamped > 0, internalAmount);
        FluidComponentGraph.recordFluidChange(levelAccessor, pos, fluid, internalAmount);
        if (invalidateConnectedComponents) {
            invalidateConnectedFluidComponents(levelAccessor, pos);
        }
    }

    private static void notifyCaches(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int newAmount,
                                     boolean invalidateConnectedComponents) {
        recordFluidCacheChange(levelAccessor, pos, fluid, newAmount, invalidateConnectedComponents);
    }

    private static void wakeVirtualFluidCell(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid,
                                             FluidState previousState, int newAmount) {
        if (!(levelAccessor instanceof Level level) || level.isClientSide()) {
            return;
        }
        if (newAmount <= 0 || !(fluid instanceof FlowingFluid)) {
            return;
        }
        int previousAmount = previousState.getType().isSame(fluid) ? previousState.getAmount() : 0;
        if (previousAmount == newAmount) {
            return;
        }
        // Virtual pass-through cells do not get block-backed fluid updates, so wake them explicitly.
        level.scheduleTick(pos, fluid, 1);
    }

    private static boolean isAquaticPlantFluidHolder(BlockState state) {
        Block block = state.getBlock();
        return block instanceof SeagrassBlock
                || block instanceof TallSeagrassBlock
                || block instanceof KelpPlantBlock
                || block instanceof KelpBlock;
    }

    private static boolean shouldBreakAquaticPlantFluidCell(LevelAccessor levelAccessor, BlockPos pos, BlockState state) {
        if (!isAquaticPlantFluidHolder(state)) {
            return false;
        }
        if (!state.canSurvive(levelAccessor, pos)) {
            return true;
        }
        return canFluidFlowToNeighbourFromPos(levelAccessor, pos, state, Fluids.WATER, 8);
    }

    private static boolean clearAquaticPlantPart(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid,
                                                 boolean invalidateConnectedComponents) {
        BlockState currentState = levelAccessor.getBlockState(pos);
        if (!isAquaticPlantFluidHolder(currentState)) {
            return false;
        }
        boolean changed = levelAccessor.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        if (changed) {
            recordFluidCacheChange(levelAccessor, pos, fluid, 0, invalidateConnectedComponents);
        }
        return changed;
    }

    private static boolean clearAquaticPlantFluidCell(LevelAccessor levelAccessor, BlockPos pos, BlockState state, Fluid fluid,
                                                      boolean invalidateConnectedComponents) {
        if (!fluid.isSame(Fluids.WATER) || !isAquaticPlantFluidHolder(state)) {
            return false;
        }

        boolean changed = clearAquaticPlantPart(levelAccessor, pos, fluid, invalidateConnectedComponents);
        if (state.getBlock() instanceof TallSeagrassBlock
                && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            BlockPos otherPos = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                    ? pos.below()
                    : pos.above();
            changed |= clearAquaticPlantPart(levelAccessor, otherPos, fluid, invalidateConnectedComponents);
        }
        return changed;
    }

    private static void recheckAquaticPlantSurvival(LevelAccessor levelAccessor, BlockPos pos) {
        BlockState state = levelAccessor.getBlockState(pos);
        if (!shouldBreakAquaticPlantFluidCell(levelAccessor, pos, state)) {
            return;
        }
        clearAquaticPlantFluidCell(levelAccessor, pos, state, Fluids.WATER, true);
    }

    private static void recheckNearbyAquaticPlantSurvival(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid) {
        if (pos == null || fluid == null || !fluid.isSame(Fluids.WATER)) {
            return;
        }

        recheckAquaticPlantSurvival(levelAccessor, pos);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            cursor.setWithOffset(pos, direction);
            recheckAquaticPlantSurvival(levelAccessor, cursor);
        }
    }

    private static boolean prepareBlockForVirtualFluidStorage(LevelAccessor levelAccessor, BlockPos pos, BlockState blockState) {
        if (!shouldOverrideVanillaWaterlogging(blockState)
                || !blockState.getValue(BlockStateProperties.WATERLOGGED)) {
            return true;
        }
        return levelAccessor.setBlock(pos, blockState.setValue(BlockStateProperties.WATERLOGGED, false), 3);
    }

    public static void wakeAdjacentVirtualFluidCells(Level level, BlockPos pos) {
        if (level == null || level.isClientSide() || pos == null) {
            return;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            cursor.setWithOffset(pos, direction);
            BlockState neighborState = level.getBlockState(cursor);
            if (!supportsVirtualFluidState(level, neighborState)) {
                continue;
            }

            FluidState neighborFluid = getEffectiveFluidState(level, cursor, neighborState);
            if (!(neighborFluid.getType() instanceof FlowingFluid flowingFluid) || neighborFluid.getAmount() <= 0) {
                continue;
            }

            level.scheduleTick(cursor, flowingFluid, 1);
        }
    }

    private static void invalidateConnectedFluidComponents(LevelAccessor levelAccessor, BlockPos pos) {
        if (levelAccessor == null || pos == null || FlowingFluids.config == null) {
            return;
        }
        if (queueBulkConnectedComponentInvalidation(levelAccessor, pos)) {
            return;
        }
        int radius = getConnectedFluidComponentInvalidationRadius();
        FluidSpatialGrid.invalidateComponentsInRegion(levelAccessor, pos, radius);
    }

    public static int getConnectedFluidComponentInvalidationRadius() {
        if (FlowingFluids.config == null) {
            return 0;
        }
        int baseDistance = Math.max(1, FlowingFluids.config.waterFlowDistance);
        return Math.max(8, Math.min(32, baseDistance * 2));
    }

    private static boolean shouldInvalidateConnectedFluidComponents(FluidState previousState, Fluid newFluid, int newAmount) {
        boolean hadFluid = !previousState.isEmpty() && previousState.getAmount() > 0;
        boolean hasFluid = newAmount > 0;
        if (hadFluid != hasFluid) {
            return true;
        }
        if (!hadFluid) {
            return false;
        }
        // Connected components track reachability, so pure amount changes do not need a full regional reset.
        return !previousState.getType().isSame(newFluid);
    }


    public static boolean setFluidStateAtPosToNewAmount(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int newAmount) {
        BlockState blockState = levelAccessor.getBlockState(pos);
        int normalizedAmount = normalizeRequestedFluidAmount(levelAccessor, pos, blockState, fluid, newAmount);
        FluidState existingState = getEffectiveFluidState(levelAccessor, pos, blockState);
        boolean invalidateConnectedComponents = shouldInvalidateConnectedFluidComponents(existingState, fluid, normalizedAmount);
        if (normalizedAmount == 0) {
            if (existingState.isEmpty()) {
                return true;
            }
        } else if (existingState.getType().isSame(fluid) && existingState.getAmount() == normalizedAmount) {
            return true;
        }

        if (normalizedAmount < 1) {
            return removeAllFluidAtPos(levelAccessor, pos, fluid);
        }

        if (supportsVirtualFluidState(levelAccessor, blockState)) {
            if (!prepareBlockForVirtualFluidStorage(levelAccessor, pos, blockState)) {
                return false;
            }
            ExtendedWaterlogStore.set(levelAccessor, pos, fluid, normalizedAmount);
            if (levelAccessor instanceof net.minecraft.server.level.ServerLevel level) {
                FlowingFluidsPlatform.syncVirtualFluidState(level, pos);
            }
            notifyCaches(levelAccessor, pos, fluid, normalizedAmount, invalidateConnectedComponents);
            wakeVirtualFluidCell(levelAccessor, pos, fluid, existingState, normalizedAmount);
            recheckNearbyAquaticPlantSurvival(levelAccessor, pos, fluid);
            return true;
        }
        if (isVanillaWaterloggable(blockState)) {
            LiquidBlockContainer liquidBlockContainer = (LiquidBlockContainer) blockState.getBlock();
            if (normalizedAmount == 8) {
                boolean result = liquidBlockContainer.placeLiquid(levelAccessor, pos, blockState, getStateForFluidByAmount(fluid, normalizedAmount));
                if (result) {
                    notifyCaches(levelAccessor, pos, fluid, normalizedAmount, invalidateConnectedComponents);
                    recheckNearbyAquaticPlantSurvival(levelAccessor, pos, fluid);
                }
                return result;
            }
            BucketPickup bucketPickup = (BucketPickup) blockState.getBlock();
            bucketPickup.pickupBlock(#if MC > MC_20_1 null, #endif levelAccessor, pos, blockState);
            recordFluidCacheChange(levelAccessor, pos, fluid, 0, invalidateConnectedComponents);
            recheckNearbyAquaticPlantSurvival(levelAccessor, pos, fluid);
            return true;
        }
        if (blockState.getBlock() instanceof LiquidBlockContainer) {
            if (!blockState.canBeReplaced(fluid)) {
                return false;
            }
        }
        if (!blockState.isAir() && !blockState.canBeReplaced(fluid)) {
            return false;
        }

        if (!blockState.isAir()
                && fluid instanceof FlowingFluid flowingFluid
                && !shouldSuppressDecorativePlantDropsForFluidReplacement(blockState, fluid)) {
            flowingFluid.beforeDestroyingBlock(levelAccessor, pos, blockState);
        }
        boolean result = levelAccessor.setBlock(pos, getStateForFluidByAmount(fluid, normalizedAmount).createLegacyBlock(), 3);
        if (result) {
            notifyCaches(levelAccessor, pos, fluid, normalizedAmount, invalidateConnectedComponents);
            recheckNearbyAquaticPlantSurvival(levelAccessor, pos, fluid);
        }
        return result;
    }

    static boolean shouldSuppressDecorativePlantDropsForFluidReplacement(BlockState blockState, Fluid fluid) {
        if (blockState == null || fluid == null || blockState.isAir() || !fluid.isSame(Fluids.WATER)) {
            return false;
        }

        Block block = blockState.getBlock();
        if (!(block instanceof BushBlock) || blockState.hasBlockEntity()) {
            return false;
        }

        if (block instanceof CropBlock
                || block instanceof SaplingBlock
                || blockState.is(BlockTags.CROPS)
                || blockState.is(BlockTags.SAPLINGS)
                || block instanceof SweetBerryBushBlock) {
            return false;
        }

        return true;
    }

    public static int transferFluidAmount(LevelAccessor levelAccessor, BlockPos fromPos, BlockPos toPos,
                                          Fluid fluid, int requestedTransfer, int minSourceAmount) {
        if (requestedTransfer <= 0) {
            return 0;
        }

        FluidState sourceState = getEffectiveFluidState(levelAccessor, fromPos);
        if (!sourceState.getType().isSame(fluid)) {
            return 0;
        }

        int sourceAmount = sourceState.getAmount();
        int removable = Math.max(0, sourceAmount - Math.max(0, minSourceAmount));
        if (removable <= 0) {
            return 0;
        }

        BlockState targetBlockState = levelAccessor.getBlockState(toPos);
        FluidState targetState = getEffectiveFluidState(levelAccessor, toPos, targetBlockState);
        if (!targetState.isEmpty() && !targetState.getType().isSame(fluid)) {
            return 0;
        }

        int targetAmount = targetState.getType().isSame(fluid) ? targetState.getAmount() : 0;
        int capacity = Math.max(0, 8 - targetAmount);
        if (capacity <= 0) {
            return 0;
        }

        boolean partialTarget = canStorePartialFluidAmount(levelAccessor, toPos, targetBlockState, fluid);
        int transfer = Math.min(requestedTransfer, Math.min(removable, capacity));
        if (!partialTarget && targetAmount + transfer < 8) {
            return 0;
        }

        int newTargetAmount = partialTarget ? targetAmount + transfer : 8;
        int actualTransferred = newTargetAmount - targetAmount;
        if (actualTransferred <= 0) {
            return 0;
        }

        int newSourceAmount = sourceAmount - actualTransferred;
        if (!setFluidStateAtPosToNewAmount(levelAccessor, fromPos, fluid, newSourceAmount)) {
            return 0;
        }
        if (!setFluidStateAtPosToNewAmount(levelAccessor, toPos, fluid, newTargetAmount)) {
            setFluidStateAtPosToNewAmount(levelAccessor, fromPos, fluid, sourceAmount);
            return 0;
        }
        return actualTransferred;
    }

    public static double getWaterOpenSpillHead(LevelAccessor levelAccessor,
                                               BlockPos waterPos,
                                               FluidState waterFluid,
                                               FlowingFluid fluid) {
        if (levelAccessor == null || waterPos == null || waterFluid == null
                || !waterFluid.getType().isSame(fluid) || waterFluid.getAmount() <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        BlockPos abovePos = waterPos.above();
        BlockState aboveState = levelAccessor.getBlockState(abovePos);
        FluidState aboveFluid = getEffectiveFluidState(levelAccessor, abovePos, aboveState);
        if (aboveFluid.getType().isSame(fluid) && aboveFluid.getAmount() > 0) {
            return Double.POSITIVE_INFINITY;
        }
        if (!canStorePartialFluidAmount(levelAccessor, abovePos, aboveState, fluid)) {
            return Double.POSITIVE_INFINITY;
        }
        return waterPos.getY() + getFluidSurfaceHeight(levelAccessor, waterPos, waterFluid.getAmount());
    }

    private static double getFluidSurfaceHeight(LevelAccessor levelAccessor, BlockPos pos, int amount) {
        int clampedAmount = Mth.clamp(amount, 0, 8);
        BlockState state = levelAccessor.getBlockState(pos);
        if (supportsVirtualFluidState(levelAccessor, state)) {
            return Mth.clamp(getVirtualFluidSurfaceY(getVirtualFluidCavity(state), clampedAmount / 8.0D),
                    0.0D, 1.0D);
        }
        return clampedAmount / 8.0D;
    }
    public static boolean changeFluidAmountAtPos(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int delta) {
        FluidState state = getEffectiveFluidState(levelAccessor, pos);
        if (!state.isEmpty() && !state.getType().isSame(fluid)) {
            return false;
        }

        int currentAmount = state.isEmpty() ? 0 : state.getAmount();
        int targetAmount = Math.max(0, Math.min(8, currentAmount + delta));
        return setFluidStateAtPosToNewAmount(levelAccessor, pos, fluid, targetAmount);
    }

    public static boolean applyConnectedFluidAmountDelta(LevelAccessor levelAccessor, BlockPos pos, FlowingFluid fluid,
                                                         int delta, int depth, boolean doUp, boolean doDown) {
        if (delta == 0) {
            return true;
        }
        if (delta > 0) {
            Pair<Integer, Runnable> result = placeConnectedFluidAmountAndPlaceAction(levelAccessor, pos, delta, fluid, depth, doUp, doDown);
            if (result.first() == delta || result.second() == null) {
                return false;
            }
            result.second().run();
            return true;
        }

        int amountToRemove = Math.abs(delta);
        Pair<Integer, Runnable> result = collectConnectedFluidAmountAndRemoveAction(levelAccessor, pos, amountToRemove, amountToRemove, fluid, depth);
        if (result.first() < amountToRemove || result.second() == null) {
            return false;
        }
        result.second().run();
        return true;
    }

    public static boolean applyLocalFluidAmountDelta(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int delta) {
        if (delta == 0) {
            return true;
        }
        FluidState state = getEffectiveFluidState(levelAccessor, pos);
        if (!state.isEmpty() && !state.getType().isSame(fluid)) {
            return false;
        }
        int currentAmount = state.isEmpty() ? 0 : state.getAmount();
        int targetAmount = Math.max(0, Math.min(8, currentAmount + delta));
        if (targetAmount == currentAmount) {
            return false;
        }
        return setFluidStateAtPosToNewAmount(levelAccessor, pos, fluid, targetAmount);
    }


    public static boolean removeAllFluidAtPos(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid) {
        var blockState = levelAccessor.getBlockState(pos);
        FluidState existingState = getEffectiveFluidState(levelAccessor, pos, blockState);
        boolean invalidateConnectedComponents = !existingState.isEmpty() && existingState.getAmount() > 0;
        if (clearAquaticPlantFluidCell(levelAccessor, pos, blockState, fluid, invalidateConnectedComponents)) {
            recheckNearbyAquaticPlantSurvival(levelAccessor, pos, fluid);
            return true;
        }
        if (supportsVirtualFluidState(levelAccessor, blockState) || ExtendedWaterlogStore.has(levelAccessor, pos)) {
            boolean cleared = clearStoredVirtualFluidState(levelAccessor, pos);
            if (!cleared
                    && shouldOverrideVanillaWaterlogging(blockState)
                    && blockState.getValue(BlockStateProperties.WATERLOGGED)) {
                cleared = levelAccessor.setBlock(pos, blockState.setValue(BlockStateProperties.WATERLOGGED, false), 3);
                if (cleared) {
                    recordFluidCacheChange(levelAccessor, pos, fluid, 0, invalidateConnectedComponents);
                }
            }
            if (cleared && invalidateConnectedComponents) {
                invalidateConnectedFluidComponents(levelAccessor, pos);
            }
            if (cleared) {
                recheckNearbyAquaticPlantSurvival(levelAccessor, pos, fluid);
            }
            return cleared;
        }
        if (blockState.getBlock() instanceof LiquidBlockContainer
                && blockState.getBlock() instanceof BucketPickup bucketPickup) {
            bucketPickup.pickupBlock(#if MC > MC_20_1 null, #endif levelAccessor, pos, blockState);
            recordFluidCacheChange(levelAccessor, pos, fluid, 0, invalidateConnectedComponents);
            recheckNearbyAquaticPlantSurvival(levelAccessor, pos, fluid);
            return true;
        }

        if (!blockState.isAir() && fluid instanceof FlowingFluid flowingFluid) {
            flowingFluid.beforeDestroyingBlock(levelAccessor, pos, blockState);
        }

        boolean result = levelAccessor.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        if (result) {
            recordFluidCacheChange(levelAccessor, pos, fluid, 0, invalidateConnectedComponents);
            recheckNearbyAquaticPlantSurvival(levelAccessor, pos, fluid);
        }
        return result;
    }


    public static int removeAmountFromFluidAtPosWithRemainder(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int removeAmount) {
        FluidState state = getEffectiveFluidState(levelAccessor, pos);
        if (state.getType().isSame(fluid)) {
            int currentAmount = state.getAmount();
            if (currentAmount <= removeAmount) {
                removeAllFluidAtPos(levelAccessor, pos, fluid);
                return removeAmount - currentAmount;
            } else {
                setFluidStateAtPosToNewAmount(levelAccessor, pos, fluid, currentAmount - removeAmount);
                return 0;
            }
        }
        return removeAmount;
    }

    public static int addAmountToFluidAtPosWithRemainderAndTrySpreadIfFull(LevelAccessor levelAccessor, BlockPos pos, FlowingFluid fluid, int addAmount) {
        return addAmountToFluidAtPosWithRemainderAndTrySpreadIfFull(levelAccessor, pos, fluid, addAmount, true, true);
    }

    public static int addAmountToFluidAtPosWithRemainderAndTrySpreadIfFull(LevelAccessor levelAccessor, BlockPos pos, FlowingFluid fluid, int addAmount, boolean canSpreadUp, boolean canSpreadDown) {
        if (addAmount <= 0) {
            return 0;
        }
        FluidState state = getEffectiveFluidState(levelAccessor, pos);
        if (state.isEmpty()) {
            BlockState blockState = levelAccessor.getBlockState(pos);
            boolean canStorePartial = canStorePartialFluidAmount(levelAccessor, pos, blockState, fluid);
            boolean canStoreFullVanillaWaterlog = addAmount >= 8 && isVanillaWaterloggable(blockState);
            if (!canStorePartial && !canStoreFullVanillaWaterlog) {
                return addAmount;
            }
            int firstCellAmount = canStorePartial ? Math.min(8, addAmount) : 8;
            if (!setFluidStateAtPosToNewAmount(levelAccessor, pos, fluid, firstCellAmount)) {
                return addAmount;
            }
            addAmount -= firstCellAmount;
            if (addAmount <= 0) {
                return 0;
            }
        } else if (!state.getType().isSame(fluid)) {
            return addAmount;
        }

        var data = placeConnectedFluidAmountAndPlaceAction(levelAccessor, pos, addAmount, fluid, 80, canSpreadUp, canSpreadDown);
        if (data.first() != addAmount) {
            data.second().run();
            return data.first();
        }
        return addAmount;
    }

    public static int addAmountToFluidAtPosWithRemainder(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int addAmount) {
        FluidState state = getEffectiveFluidState(levelAccessor, pos);
        if (state.isEmpty() || state.getType().isSame(fluid)) {
            int currentAmount = state.getAmount();
            if (currentAmount == 8) {
                return addAmount;
            }
            if (currentAmount + addAmount <= 8) {
                if (setFluidStateAtPosToNewAmount(levelAccessor, pos, fluid, currentAmount + addAmount)) {
                    return 0;
                }
            } else {
                if (setFluidStateAtPosToNewAmount(levelAccessor, pos, fluid, 8)) {
                    return currentAmount + addAmount - 8;
                }
            }
        }
        return addAmount;
    }


    public static boolean canFluidFlowFromPosToDirection(FlowingFluid fluid, int amount, LevelAccessor levelAccessor, BlockPos fromPos, Direction direction) {
        var blockPos2 = fromPos.relative(direction);
        var blockState2 = levelAccessor.getBlockState(blockPos2);
        var fluidState2 = getEffectiveFluidState(levelAccessor, blockPos2, blockState2);
        return canFluidFlowFromPosToDirection(fluid, amount, levelAccessor, fromPos, levelAccessor.getBlockState(fromPos), direction, blockPos2, blockState2, fluidState2);
    }
    public static boolean canFluidFlowFromPosToDirection(FlowingFluid sourceFluid, int sourceAmount, BlockGetter blockGetter,
                                                         BlockPos blockPos, BlockState blockState, Direction direction,
                                                         BlockPos blockPos2, BlockState blockState2, FluidState fluidState2) {
        FluidState sourceFluidState = blockGetter instanceof LevelAccessor accessor
                ? getEffectiveFluidState(accessor, blockPos, blockState)
                : (!blockState.getFluidState().isEmpty() && blockState.getFluidState().getType().isSame(sourceFluid)
                    ? blockState.getFluidState()
                    : getStateForFluidByAmount(sourceFluid, sourceAmount));
        // consider virtual waterlogged fluid
        if (blockGetter instanceof LevelAccessor accessor) {
            fluidState2 = getEffectiveFluidState(accessor, blockPos2, blockState2);
        }
        boolean replaceableTarget = fluidState2.isEmpty() && blockState2.canBeReplaced(sourceFluid);
        boolean virtualEnabled = isVirtualFluidStorageEnabled();
        boolean porousSource = virtualEnabled && blockGetter instanceof LevelAccessor accessor
                && isDirectionalVirtualFluidPassableBlock(accessor, blockState, direction);
        boolean porousTarget = virtualEnabled && blockGetter instanceof LevelAccessor accessor
                && isDirectionalVirtualFluidPassableBlock(accessor, blockState2, direction.getOpposite());
        boolean virtualTarget = virtualEnabled && blockGetter instanceof LevelAccessor accessor
                && supportsVirtualFluidState(accessor, blockState2);
        boolean compatibleHeights = hasCompatibleVirtualFluidHeights(
                blockGetter, blockPos, blockState, sourceFluidState, direction, blockPos2, blockState2, fluidState2);
        //add extra fluid check for replacing into self
        return (replaceableTarget
                || fluidState2.canBeReplacedWith(blockGetter, blockPos2, sourceFluid, direction)
                || canFitIntoFluid(sourceFluid, fluidState2, direction, sourceAmount, blockState2))
                && compatibleHeights
                && (porousSource
                    || porousTarget
                    || sourceFluid.canPassThroughWall(direction, blockGetter, blockPos, blockState, blockPos2, blockState2))
                && (replaceableTarget || virtualTarget || sourceFluid.canHoldFluid(blockGetter, blockPos2, blockState2, sourceFluid));
    }

    public static boolean canFluidFlowFromPosToDirectionFitOverride(FlowingFluid sourceFluid, BlockGetter blockGetter,
                                                         BlockPos blockPos, BlockState blockState, Direction direction,
                                                         BlockPos blockPos2, BlockState blockState2) {
        FluidState sourceFluidState = blockGetter instanceof LevelAccessor accessor
                ? getEffectiveFluidState(accessor, blockPos, blockState)
                : blockState.getFluidState();
        FluidState targetFluidState = blockGetter instanceof LevelAccessor accessor
                ? getEffectiveFluidState(accessor, blockPos2, blockState2)
                : blockState2.getFluidState();
        boolean virtualEnabled = isVirtualFluidStorageEnabled();
        boolean porousSource = virtualEnabled && blockGetter instanceof LevelAccessor accessor
                && isDirectionalVirtualFluidPassableBlock(accessor, blockState, direction);
        boolean porousTarget = virtualEnabled && blockGetter instanceof LevelAccessor accessor
                && isDirectionalVirtualFluidPassableBlock(accessor, blockState2, direction.getOpposite());
        boolean virtualTarget = virtualEnabled && blockGetter instanceof LevelAccessor accessor
                && supportsVirtualFluidState(accessor, blockState2);
        boolean compatibleHeights = hasCompatibleVirtualFluidHeights(
                blockGetter, blockPos, blockState, sourceFluidState, direction, blockPos2, blockState2, targetFluidState);
        //add extra fluid check for replacing into self
        return compatibleHeights
                && (porousSource
                || porousTarget
                || sourceFluid.canPassThroughWall(direction, blockGetter, blockPos, blockState, blockPos2, blockState2))
                && (blockState2.canBeReplaced(sourceFluid) || virtualTarget || sourceFluid.canHoldFluid(blockGetter, blockPos2, blockState2, sourceFluid));
    }

    public static boolean canTraverseFluidAdjacency(LevelAccessor levelAccessor,
                                                    BlockPos fromPos,
                                                    BlockState fromState,
                                                    FluidState fromFluidState,
                                                    Direction direction,
                                                    BlockPos toPos,
                                                    BlockState toState,
                                                    FluidState toFluidState,
                                                    FlowingFluid sourceFluid) {
        if (levelAccessor == null || fromPos == null || toPos == null || direction == null || sourceFluid == null) {
            return false;
        }

        FluidState resolvedToFluid = toFluidState != null ? toFluidState : getEffectiveFluidState(levelAccessor, toPos, toState);
        boolean virtualEnabled = isVirtualFluidStorageEnabled();
        boolean porousSource = virtualEnabled && isDirectionalVirtualFluidPassableBlock(levelAccessor, fromState, direction);
        boolean porousTarget = virtualEnabled && isDirectionalVirtualFluidPassableBlock(levelAccessor, toState, direction.getOpposite());
        boolean facePassable = porousSource
                || porousTarget
                || sourceFluid.canPassThroughWall(direction, levelAccessor, fromPos, fromState, toPos, toState);
        FluidState sourceFluidState = getEffectiveFluidState(levelAccessor, fromPos, fromState);
        boolean compatibleHeights = hasCompatibleVirtualFluidHeights(
                levelAccessor, fromPos, fromState, sourceFluidState, direction, toPos, toState, resolvedToFluid);
        boolean targetSameFluid = !resolvedToFluid.isEmpty() && resolvedToFluid.getType().isSame(sourceFluid);
        boolean targetHasOtherFluid = !resolvedToFluid.isEmpty() && !targetSameFluid;
        boolean targetVirtual = supportsVirtualFluidState(levelAccessor, toState);
        boolean targetCanHoldFluid = sourceFluid.canHoldFluid(levelAccessor, toPos, toState, sourceFluid);

        return FluidRegressionLogic.shouldTraverseFluidAdjacency(
                facePassable && compatibleHeights,
                targetSameFluid,
                targetHasOtherFluid,
                toState.isAir(),
                toState.canBeReplaced(sourceFluid),
                targetVirtual,
                targetCanHoldFluid
        );
    }




    private static boolean canFitIntoFluid(Fluid thisFluid, FluidState fluidStateTo, Direction direction, int amount, BlockState blockStateTo) {
        if (fluidStateTo.isEmpty()){
            return true;
        }
        if (fluidStateTo.getType().isSame(thisFluid)) {
            if (direction == Direction.DOWN) {
                return fluidStateTo.getAmount() < 8;
            } else {
                return fluidStateTo.getAmount() < amount;
            }
        }
        return false;
    }

    public static Pair<Integer, Runnable> placeConnectedFluidAmountAndPlaceAction(final LevelAccessor levelAccessor, final BlockPos blockPos, final int amountToPlace, final FlowingFluid fluid) {
        return placeConnectedFluidAmountAndPlaceAction(levelAccessor, blockPos, amountToPlace, fluid, 80, true, true);
    }

    public static Pair<Integer, Runnable> placeConnectedFluidAmountAndPlaceAction(final LevelAccessor levelAccessor, final BlockPos blockPos, final int amountToPlace, final FlowingFluid fluid, int depth, boolean doUp, boolean doDown) {
        var originalState = getEffectiveFluidState(levelAccessor, blockPos);
        int originalAmount = originalState.getAmount();
        if (originalState.getType().isSame(fluid) && originalAmount > 0) {

            //check for quick exit
            if (originalAmount + amountToPlace <= 8) {
                return Pair.of(0,()->FFFluidUtils.setFluidStateAtPosToNewAmount(levelAccessor, blockPos, fluid, originalAmount + amountToPlace));
            }

            LongArrayFIFOQueue queue = getPositionQueue();
            LongOpenHashSet visited = getVisitedPositions();
            LongArrayList positionBuffer = getPositionBuffer();
            IntArrayList levelBuffer = getLevelBuffer();

            RandomSource random = levelAccessor.getRandom();
            Direction[] lateralOrder = getCardinalsShuffle(random);

            long originKey = blockPos.asLong();
            queue.enqueue(originKey);
            visited.add(originKey);

            BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos neighbourPos = new BlockPos.MutableBlockPos();

            int totalCapacity = 0;
            int traversedCandidateCells = 0;
            boolean pressureEnabled = doUp
                    && fluid.isSame(Fluids.WATER)
                    && FlowingFluids.config.enableCavityPressureRise
                    && levelAccessor instanceof Level level
                    && !level.isClientSide();
            int highestCandidateY = blockPos.getY();

            while (!queue.isEmpty()) {
                if (hasReachedConnectedFluidTraversalBudget(traversedCandidateCells, depth)) {
                    break;
                }

                long currentKey = queue.dequeueLong();
                currentPos.set(BlockPos.getX(currentKey), BlockPos.getY(currentKey), BlockPos.getZ(currentKey));

                // Optimize: get BlockState once and derive FluidState from it to avoid double lookup
                BlockState blockState = levelAccessor.getBlockState(currentPos);
                FluidState state = getEffectiveFluidState(levelAccessor, currentPos, blockState);
                boolean isSameFluid = fluid.isSame(state.getType());
                boolean canReceiveNewFluid = state.isEmpty()
                        && (blockState.isAir() || blockState.canBeReplaced(fluid) || supportsVirtualFluidState(levelAccessor, blockState));
                if (isSameFluid || canReceiveNewFluid) {
                    traversedCandidateCells++;
                    int currentAmountAtPos = isSameFluid ? state.getAmount() : 0;
                    int space = 8 - currentAmountAtPos;
                    if (space > 0) {
                        positionBuffer.add(currentKey);
                        levelBuffer.add(currentAmountAtPos);
                        totalCapacity += space;
                        highestCandidateY = Math.max(highestCandidateY, currentPos.getY());
                    }

                    // Once nearby reachable cells already hold enough capacity, stop widening the search.
                    // This keeps rain/refill behavior local and avoids expensive far-field scans.
                    boolean needsVerticalLookahead = pressureEnabled
                            && highestCandidateY <= blockPos.getY()
                            && positionBuffer.size() < 16;
                    if (totalCapacity >= amountToPlace && positionBuffer.size() >= 8 && !needsVerticalLookahead) {
                        break;
                    }

                    // Optimized direction priority: down first (gravity), then sides, then up
                    // This follows natural fluid flow and finds space more efficiently

                    if (doDown) {
                        neighbourPos.set(currentPos);
                        neighbourPos.move(Direction.DOWN);
                        long downKey = neighbourPos.asLong();
                        BlockState neighborState = levelAccessor.getBlockState(neighbourPos);
                        FluidState neighborFluid = getEffectiveFluidState(levelAccessor, neighbourPos, neighborState);
                        if (canTraverseFluidAdjacency(levelAccessor, currentPos, blockState, state, Direction.DOWN,
                                neighbourPos, neighborState, neighborFluid, fluid)
                                && visited.add(downKey)) {
                            queue.enqueue(downKey);
                        }
                    }

                    for (Direction direction : lateralOrder) {
                        neighbourPos.set(currentPos);
                        neighbourPos.move(direction);
                        long neighbourKey = neighbourPos.asLong();
                        BlockState neighborState = levelAccessor.getBlockState(neighbourPos);
                        FluidState neighborFluid = getEffectiveFluidState(levelAccessor, neighbourPos, neighborState);
                        if (canTraverseFluidAdjacency(levelAccessor, currentPos, blockState, state, direction,
                                neighbourPos, neighborState, neighborFluid, fluid)
                                && visited.add(neighbourKey)) {
                            queue.enqueue(neighbourKey);
                        }
                    }

                    if (doUp) {
                        neighbourPos.set(currentPos);
                        neighbourPos.move(Direction.UP);
                        long upKey = neighbourPos.asLong();
                        BlockState neighborState = levelAccessor.getBlockState(neighbourPos);
                        FluidState neighborFluid = getEffectiveFluidState(levelAccessor, neighbourPos, neighborState);
                        if (canTraverseFluidAdjacency(levelAccessor, currentPos, blockState, state, Direction.UP,
                                neighbourPos, neighborState, neighborFluid, fluid)
                                && visited.add(upKey)) {
                            queue.enqueue(upKey);
                        }
                    }
                }

            }

            queue.clear();
            visited.clear();

            if (totalCapacity <= 0 || positionBuffer.isEmpty()) {
                positionBuffer.clear();
                levelBuffer.clear();
                return Pair.of(amountToPlace, null);
            }

            int placeable = Math.min(amountToPlace, totalCapacity);
            int[] currentLevels = levelBuffer.toIntArray();
            long[] positions = positionBuffer.toLongArray();
            int count = currentLevels.length;

            int[] finalLevels = Arrays.copyOf(currentLevels, count);
            List<Integer> wetOrder = new ArrayList<>(count);
            List<Integer> dryOrder = new ArrayList<>(count);
            int[] yLevels = new int[count];
            int[] supportScores = new int[count];
            int[] containmentScores = new int[count];
            int[] distances = new int[count];
            float pressureBias = pressureEnabled
                    ? computeConnectedPlacementPressureBias(
                    (Level) levelAccessor,
                    blockPos,
                    fluid,
                    Mth.clamp(Math.max(originalAmount, amountToPlace), 1, 8))
                    : 0.0f;

            BlockPos.MutableBlockPos analysisPos = new BlockPos.MutableBlockPos();
            for (int i = 0; i < count; i++) {
                long key = positions[i];
                analysisPos.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
                yLevels[i] = analysisPos.getY();
                distances[i] = Math.abs(analysisPos.getX() - blockPos.getX())
                        + Math.abs(analysisPos.getY() - blockPos.getY())
                        + Math.abs(analysisPos.getZ() - blockPos.getZ());
                supportScores[i] = calculatePlacementSupportScore(levelAccessor, analysisPos, fluid);
                containmentScores[i] = pressureEnabled
                        ? calculateContainedRiseScore(levelAccessor, analysisPos, fluid)
                        : 0;
                if (currentLevels[i] > 0) {
                    wetOrder.add(i);
                } else {
                    dryOrder.add(i);
                }
            }

            Comparator<Integer> wetComparator = (a, b) -> {
                int cmp = Integer.compare(finalLevels[a], finalLevels[b]);
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Integer.compare(yLevels[a], yLevels[b]);
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
                return Long.compare(positions[a], positions[b]);
            };
            wetOrder.sort(wetComparator);
            dryOrder.sort((a, b) -> {
                int cmp = Integer.compare(yLevels[a], yLevels[b]);
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Integer.compare(containmentScores[b], containmentScores[a]);
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
                return Long.compare(positions[a], positions[b]);
            });

            int remaining = distributeAcrossCandidates(finalLevels, wetOrder, placeable, 8);
            if (remaining > 0 && !dryOrder.isEmpty()) {
                int selectedDryCount = determineDryActivationCount(remaining, dryOrder.size());
                if (pressureEnabled) {
                    selectedDryCount = FluidRegressionLogic.expandDryActivationCountForPressure(
                            selectedDryCount,
                            remaining,
                            dryOrder.size(),
                            pressureBias
                    );
                }
                List<Integer> drySelection = dryOrder.subList(0, selectedDryCount);
                remaining = distributeAcrossCandidates(finalLevels, drySelection, remaining, 8);
            }

            int placed = placeable - remaining;
            int unplaced = amountToPlace - placed;

            positionBuffer.clear();
            levelBuffer.clear();

            if (placed <= 0) {
                return Pair.of(amountToPlace, null);
            }

            return Pair.of(unplaced, () -> runWithBulkFluidChanges(levelAccessor, () -> {
                BlockPos.MutableBlockPos applyPos = new BlockPos.MutableBlockPos();
                for (int i = 0; i < count; i++) {
                    long key = positions[i];
                    applyPos.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
                    FFFluidUtils.setFluidStateAtPosToNewAmount(levelAccessor, applyPos, fluid, finalLevels[i]);
                }
            }));
        }
        return Pair.of(amountToPlace, null);
    }

    private static int determineDryActivationCount(int remaining, int dryCandidates) {
        return FluidRegressionLogic.computeDryActivationCount(remaining, dryCandidates, MIN_DRY_CELL_FILL_LEVEL);
    }

    public static boolean hasRoofWithin(LevelAccessor levelAccessor, BlockPos pos, int maxHeight) {
        if (maxHeight <= 0) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        cursor.set(pos);
        for (int i = 0; i < maxHeight; i++) {
            cursor.move(Direction.UP);
            BlockState state = levelAccessor.getBlockState(cursor);
            FluidState fluidState = getEffectiveFluidState(levelAccessor, cursor, state);
            if (!fluidState.isEmpty() || (!state.isAir() && !state.canBeReplaced())) {
                return true;
            }
        }
        return false;
    }

    private static float computeConnectedPlacementPressureBias(Level level,
                                                              BlockPos origin,
                                                              FlowingFluid fluid,
                                                              int sourceAmount) {
        if (!fluid.isSame(Fluids.WATER) || !FlowingFluids.config.enableCavityPressureRise) {
            return 0.0f;
        }
        float momentum = FlowingFluids.config.flowInertiaMaxAgeTicks > 0
                ? AdaptiveTickScheduler.getFlowMomentum(level, origin, FlowingFluids.config.flowInertiaMaxAgeTicks)
                : 0.0f;
        boolean roofed = hasRoofWithin(level, origin, FlowingFluids.config.shadeRoofSearchHeight);
        boolean supportedBelow = hasContainedSupportBelow(level, origin, fluid);
        int lateralEscapeRoutes = countContainedEscapeRoutes(level, origin, fluid);
        int lateralWaterNeighbors = countContainedWaterNeighbors(level, origin, fluid);
        return FluidRegressionLogic.computeCavityPressureBias(
                sourceAmount,
                0.0f,
                momentum,
                roofed,
                roofed,
                supportedBelow,
                false,
                lateralEscapeRoutes,
                lateralWaterNeighbors,
                0,
                FlowingFluids.config.cavityPressureStrength,
                0.0f
        );
    }

    private static int calculateContainedRiseScore(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid) {
        boolean roofed = hasRoofWithin(levelAccessor, pos, FlowingFluids.config.shadeRoofSearchHeight);
        boolean supportedBelow = hasContainedSupportBelow(levelAccessor, pos, fluid);
        boolean immediateDownwardOutlet = hasContainedImmediateDownwardOutlet(levelAccessor, pos, fluid);
        int lateralEscapeRoutes = countContainedEscapeRoutes(levelAccessor, pos, fluid);
        int lateralWaterNeighbors = countContainedWaterNeighbors(levelAccessor, pos, fluid);
        return FluidRegressionLogic.computeContainedRiseScore(
                roofed,
                supportedBelow,
                immediateDownwardOutlet,
                lateralEscapeRoutes,
                lateralWaterNeighbors
        );
    }

    private static boolean hasContainedSupportBelow(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid) {
        BlockPos belowPos = pos.below();
        BlockState belowState = levelAccessor.getBlockState(belowPos);
        FluidState belowFluid = getEffectiveFluidState(levelAccessor, belowPos, belowState);
        return (belowFluid.getType().isSame(fluid) && belowFluid.getAmount() > 0)
                || (!belowState.isAir() && !belowState.canBeReplaced(fluid));
    }

    private static boolean hasContainedImmediateDownwardOutlet(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid) {
        BlockPos belowPos = pos.below();
        BlockState belowState = levelAccessor.getBlockState(belowPos);
        FluidState belowFluid = getEffectiveFluidState(levelAccessor, belowPos, belowState);
        if (belowFluid.isEmpty()) {
            return belowState.isAir() || belowState.canBeReplaced(fluid) || supportsVirtualFluidState(levelAccessor, belowState);
        }
        return belowFluid.getType().isSame(fluid) && belowFluid.getAmount() < 8;
    }

    private static int countContainedEscapeRoutes(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid) {
        int routes = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, direction);
            BlockState sideState = levelAccessor.getBlockState(cursor);
            FluidState sideFluid = getEffectiveFluidState(levelAccessor, cursor, sideState);
            if (sideFluid.isEmpty()) {
                if (sideState.isAir() || sideState.canBeReplaced(fluid) || supportsVirtualFluidState(levelAccessor, sideState)) {
                    routes++;
                }
            } else if (sideFluid.getType().isSame(fluid) && sideFluid.getAmount() < 8) {
                routes++;
            }
        }
        return routes;
    }

    private static int countContainedWaterNeighbors(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid) {
        int neighbors = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, direction);
            FluidState sideFluid = getEffectiveFluidState(levelAccessor, cursor, levelAccessor.getBlockState(cursor));
            if (sideFluid.getType().isSame(fluid) && sideFluid.getAmount() > 0) {
                neighbors++;
            }
        }
        return neighbors;
    }

    private static int distributeAcrossCandidates(int[] levels, List<Integer> orderedIndices, int amount, int maxLevel) {
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

    private static int calculatePlacementSupportScore(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid) {
        int score = 0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        mutablePos.setWithOffset(pos, Direction.DOWN);
        BlockState belowState = levelAccessor.getBlockState(mutablePos);
        FluidState belowFluid = getEffectiveFluidState(levelAccessor, mutablePos, belowState);
        if (belowFluid.getType().isSame(fluid) && belowFluid.getAmount() > 0) {
            score += 3;
        } else if (!belowState.isAir() && !belowState.canBeReplaced(fluid)) {
            score += 2;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            mutablePos.setWithOffset(pos, direction);
            FluidState neighborFluid = getEffectiveFluidState(levelAccessor, mutablePos, levelAccessor.getBlockState(mutablePos));
            if (neighborFluid.getType().isSame(fluid) && neighborFluid.getAmount() > 0) {
                score++;
            }
        }

        return score;
    }


    public static int collectConnectedFluidAmountAndRemove(final LevelAccessor levelAccessor, final BlockPos blockPos, final int minAmountRequired, final int maxAmountToFind, final FlowingFluid fluid) {
        var data = collectConnectedFluidAmountAndRemoveAction(levelAccessor,blockPos, minAmountRequired, maxAmountToFind, fluid);
        if (data.first() != 0) {
            data.second().run();
            return data.first();
        }
        return 0;
    }

    public static Pair<Integer, Runnable> collectConnectedFluidAmountAndRemoveAction(final LevelAccessor levelAccessor, final BlockPos blockPos, final int minAmountRequired, final int maxAmountToFind, final FlowingFluid fluid) {
        return collectConnectedFluidAmountAndRemoveAction(levelAccessor, blockPos, minAmountRequired, maxAmountToFind, fluid, DEFAULT_CONNECTED_FLUID_SEARCH_DEPTH);
    }

    public static Pair<Integer, Runnable> collectConnectedFluidAmountAndRemoveAction(final LevelAccessor levelAccessor, final BlockPos blockPos, final int minAmountRequired, final int maxAmountToFind, final FlowingFluid fluid, int depth) {
        return collectConnectedFluidAmountAndRemoveActionFixedDepth(levelAccessor, blockPos, minAmountRequired, maxAmountToFind, fluid, depth);
    }

    private static Pair<Integer, Runnable> collectConnectedFluidAmountAndRemoveActionFixedDepth(final LevelAccessor levelAccessor,
                                                                                                 final BlockPos blockPos,
                                                                                                 final int minAmountRequired,
                                                                                                 final int maxAmountToFind,
                                                                                                 final FlowingFluid fluid,
                                                                                                 final int depth) {
        FluidState originalState = getEffectiveFluidState(levelAccessor, blockPos);
        int originalAmount = originalState.getAmount();
        if (!originalState.getType().isSame(fluid) || originalAmount <= 0) {
            return Pair.of(0, null);
        }

        if (originalAmount >= maxAmountToFind) {
            return Pair.of(maxAmountToFind, () -> FFFluidUtils.setFluidStateAtPosToNewAmount(levelAccessor, blockPos, fluid, originalAmount - maxAmountToFind));
        }

        ConnectedFluidCollectionScan scan = scanConnectedFluidAmount(levelAccessor, blockPos, maxAmountToFind, fluid, Math.max(1, depth));
        if (scan.foundAmount < minAmountRequired) {
            return Pair.of(0, null);
        }

        return Pair.of(scan.foundAmount, createConnectedFluidApplyAction(levelAccessor, fluid, scan.positions, scan.levels, null));
    }

    private static ConnectedFluidCollectionScan scanConnectedFluidAmount(final LevelAccessor levelAccessor,
                                                                         final BlockPos blockPos,
                                                                         final int maxAmountToFind,
                                                                         final FlowingFluid fluid,
                                                                         final int depth) {
        LongArrayFIFOQueue positionsToCheck = getPositionQueue();
        LongOpenHashSet discoveredPositions = getVisitedPositions();
        LongArrayList positionBuffer = getPositionBuffer();
        IntArrayList levelBuffer = getLevelBuffer();
        RandomSource random = levelAccessor.getRandom();
        Direction[] searchOrder = getAllDirectionsShuffled(random);

        long originKey = blockPos.asLong();
        positionsToCheck.enqueue(originKey);
        discoveredPositions.add(originKey);

        BlockPos.MutableBlockPos seedPos = new BlockPos.MutableBlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        BlockState originState = levelAccessor.getBlockState(blockPos);
        FluidState originFluid = getEffectiveFluidState(levelAccessor, blockPos, originState);
        for (Direction direction : searchOrder) {
            seedPos.set(blockPos.getX(), blockPos.getY(), blockPos.getZ());
            seedPos.move(direction);
            BlockState seedState = levelAccessor.getBlockState(seedPos);
            FluidState seedFluid = getEffectiveFluidState(levelAccessor, seedPos, seedState);
            if (!canTraverseFluidAdjacency(levelAccessor, blockPos, originState, originFluid, direction,
                    seedPos, seedState, seedFluid, fluid)) {
                continue;
            }
            long seedKey = seedPos.asLong();
            if (discoveredPositions.add(seedKey)) {
                positionsToCheck.enqueue(seedKey);
            }
        }

        int foundAmount = 0;
        int traversedFluidCells = 0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbourPos = new BlockPos.MutableBlockPos();

        while (!positionsToCheck.isEmpty()) {
            if (hasReachedConnectedFluidTraversalBudget(traversedFluidCells, depth)) {
                break;
            }

            long currentKey = positionsToCheck.dequeueLong();
            mutablePos.set(BlockPos.getX(currentKey), BlockPos.getY(currentKey), BlockPos.getZ(currentKey));

            BlockState currentBlockState = levelAccessor.getBlockState(mutablePos);
            FluidState state = getEffectiveFluidState(levelAccessor, mutablePos, currentBlockState);
            if (!fluid.isSame(state.getType())) {
                continue;
            }

            int amount = state.getAmount();
            if (amount <= 0) {
                continue;
            }

            traversedFluidCells++;
            foundAmount += amount;
            if (foundAmount > maxAmountToFind) {
                int finalLevel = foundAmount - maxAmountToFind;
                positionBuffer.add(currentKey);
                levelBuffer.add(finalLevel);
                foundAmount = maxAmountToFind;
                break;
            }

            positionBuffer.add(currentKey);
            levelBuffer.add(0);
            if (foundAmount == maxAmountToFind) {
                break;
            }

            for (Direction direction : searchOrder) {
                neighbourPos.set(mutablePos.getX(), mutablePos.getY(), mutablePos.getZ());
                neighbourPos.move(direction);
                BlockState neighborState = levelAccessor.getBlockState(neighbourPos);
                FluidState neighborFluid = getEffectiveFluidState(levelAccessor, neighbourPos, neighborState);
                if (!canTraverseFluidAdjacency(levelAccessor, mutablePos, currentBlockState, state, direction,
                        neighbourPos, neighborState, neighborFluid, fluid)) {
                    continue;
                }
                long neighbourKey = neighbourPos.asLong();
                if (discoveredPositions.add(neighbourKey)) {
                    positionsToCheck.enqueue(neighbourKey);
                }
            }
        }

        long[] positions = positionBuffer.toLongArray();
        int[] levels = levelBuffer.toIntArray();
        positionBuffer.clear();
        levelBuffer.clear();
        return new ConnectedFluidCollectionScan(foundAmount, positions, levels);
    }

    private static Runnable createConnectedFluidApplyAction(final LevelAccessor levelAccessor,
                                                            final FlowingFluid fluid,
                                                            final long[] positions,
                                                            final int[] levels,
                                                            final Runnable afterApply) {
        return () -> {
            try {
                runWithBulkFluidChanges(levelAccessor, () -> {
                    BlockPos.MutableBlockPos applyPos = new BlockPos.MutableBlockPos();
                    for (int i = 0; i < positions.length; i++) {
                        long key = positions[i];
                        applyPos.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
                        int newLevel = levels[i];
                        if (newLevel == 0) {
                            FFFluidUtils.removeAllFluidAtPos(levelAccessor, applyPos, fluid);
                        } else {
                            FFFluidUtils.setFluidStateAtPosToNewAmount(levelAccessor, applyPos, fluid, newLevel);
                        }
                    }
                });
            } finally {
                if (afterApply != null) {
                    afterApply.run();
                }
            }
        };
    }

    public static Direction[] getCardinalsShuffle(RandomSource random) {
        // Optimized: use pre-computed shuffle patterns instead of Fisher-Yates algorithm
        // This eliminates array copying and swap operations for cardinal directions
        return CARDINAL_SHUFFLE_PATTERNS[random.nextInt(CARDINAL_SHUFFLE_PATTERNS.length)];
    }

    public static Direction[] getAllDirectionsShuffled(RandomSource random) {
        // For all directions (6 elements), still use dynamic shuffle as pre-computing 720 patterns would be excessive
        return shuffleDirections(random, ALL_DIRECTIONS, ALL_DIRECTION_BUFFER.get());
    }

    private static Direction[] shuffleDirections(RandomSource random, Direction[] source, Direction[] buffer) {
        System.arraycopy(source, 0, buffer, 0, source.length);
        for (int i = buffer.length - 1; i > 0; i--) {
            int swapIndex = random.nextInt(i + 1);
            Direction tmp = buffer[i];
            buffer[i] = buffer[swapIndex];
            buffer[swapIndex] = tmp;
        }
        return buffer;
    }

    private static boolean checkBlockIsNonDisplacer(Fluid fluid, BlockState state) {
        return FlowingFluids.nonDisplacerTags.stream().anyMatch(pair ->
                        (pair.first() == Fluids.EMPTY || pair.first().isSame(fluid)) && state.is(pair.second()))
                || FlowingFluids.nonDisplacers.stream().anyMatch(pair ->
                        (pair.first() == Fluids.EMPTY || pair.first().isSame(fluid)) && state.is(pair.second()));
    }

    public static void displaceFluids(final Level level, final BlockPos pos, final BlockState state, final int flags, final LevelChunk levelChunk, final BlockState originalState) {
        // oof, this is a big one
        // try and order in most likely to least likely to avoid unnecessary checks
        // configs first
        if (!level.isClientSide()
                && FlowingFluids.config.enableMod
                && FlowingFluids.config.enableDisplacement
                && !FlowingFluids.isManeuveringFluids()
                && !originalState.getFluidState().isEmpty()// assert that the original state is a fluid
                && originalState.getFluidState().getType() instanceof FlowingFluid flowSource
                && FlowingFluids.config.isFluidAllowed(flowSource) // check if the fluid is not in the ignored list
                && !state.isAir() // covers most block breaking updates
                && state.getFluidState().isEmpty()// not placing a waterlogged or fluid block
                && !((flags & 64) == 64) //Piston moved flag
                && !(state.getBlock() instanceof LiquidBlockContainer && originalState.getBlock() instanceof BucketPickup)
                && !checkBlockIsNonDisplacer(flowSource, state) // check if the block is a displacer
               ) {
            // fluid block was replaced, lets try and displace the fluid
            FlowingFluids.setManeuveringFluids(true);


            try {
                // try spread to the side as much as possible
                int amountRemaining = originalState.getFluidState().getAmount();
                for (Direction direction : getCardinalsShuffle(level.getRandom())) {
                    BlockPos offset = pos.relative(direction);
                    BlockState offsetState = level.getBlockState(offset);
                    FluidState offsetFluid = getEffectiveFluidState(level, offset, offsetState);

                    if (offsetFluid.getType() instanceof FlowingFluid) {
                        amountRemaining = addAmountToFluidAtPosWithRemainder(level, offset, flowSource, amountRemaining);
                        if (amountRemaining == 0) break;
                    } else if (offsetState.isAir()) {
                        if (setFluidStateAtPosToNewAmount(level, offset, flowSource, amountRemaining)) {
                            amountRemaining = 0;
                        }
                        break;
                    }
                }
                if (amountRemaining > 0) {
                    // if we still have fluid left, try to displace upwards recursively
                    BlockPos.MutableBlockPos posTraversing = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
                    int height = levelChunk
                            #if MC > MC_21
                                .getMaxY();
                            #else
                                .getMaxBuildHeight();
                            #endif

                    while (amountRemaining > 0 && posTraversing.getY() < height) {
                        posTraversing.move(Direction.UP);
                        BlockState offsetState = level.getBlockState(posTraversing);
                        FluidState offsetFluid = getEffectiveFluidState(level, posTraversing, offsetState);
                        if (offsetFluid.getType() instanceof FlowingFluid) {
                            amountRemaining = addAmountToFluidAtPosWithRemainder(level, posTraversing, flowSource, amountRemaining);
                        } else if (offsetState.isAir()) {
                            if (setFluidStateAtPosToNewAmount(level, posTraversing, flowSource, amountRemaining)) {
                                amountRemaining = 0;
                            }
                        } else {
                            break;
                        }
                    }
                }

                AdaptiveTickScheduler.notifyFluidChange(level, pos);
                ChunkLocalSlopeCache.clearForFluidChange(level, pos);
                FluidSpatialGrid.removeFluidAt(level, pos);
            } finally {
                FlowingFluids.setManeuveringFluids(false);
            }
        }
    }

    public static boolean matchInfiniteBiomes(Holder<Biome> biome){
        return FlowingFluids.infiniteBiomeTags.stream().anyMatch(biome::is)
                || FlowingFluids.infiniteBiomes.stream().anyMatch(biome::is)
                || matchesConfiguredBiome(biome, FlowingFluids.config.extraInfiniteBiomeEntries)
                || isOceanBiome(biome)
                || isRiverBiome(biome)
                || isBeachBiome(biome);
    }

    public static boolean isInOrNearInfiniteBiome(Level level, BlockPos pos, int radius) {
        if (level == null || pos == null) {
            return false;
        }
        if (matchInfiniteBiomes(level.getBiome(pos))) {
            return true;
        }
        if (radius <= 0) {
            return false;
        }

        int sampleY = Mth.clamp(seaLevel(level), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        int minBiomeCellX = Math.floorDiv(pos.getX() - radius, 4);
        int maxBiomeCellX = Math.floorDiv(pos.getX() + radius, 4);
        int minBiomeCellZ = Math.floorDiv(pos.getZ() - radius, 4);
        int maxBiomeCellZ = Math.floorDiv(pos.getZ() + radius, 4);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int cellX = minBiomeCellX; cellX <= maxBiomeCellX; cellX++) {
            for (int cellZ = minBiomeCellZ; cellZ <= maxBiomeCellZ; cellZ++) {
                cursor.set(cellX * 4 + 2, sampleY, cellZ * 4 + 2);
                if (matchInfiniteBiomes(level.getBiome(cursor))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String normalizeConfiguredBiomeEntry(String rawEntry, boolean tagEntry) {
        if (rawEntry == null) {
            return null;
        }

        String trimmed = rawEntry.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String idText;
        if (tagEntry) {
            idText = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
        } else {
            if (trimmed.startsWith("#")) {
                return null;
            }
            idText = trimmed;
        }

        ResourceLocation resourceLocation = ResourceLocation.tryParse(idText);
        if (resourceLocation == null) {
            return null;
        }

        return tagEntry ? "#" + resourceLocation : resourceLocation.toString();
    }

    public static String normalizeConfiguredKeyword(String rawKeyword) {
        if (rawKeyword == null) {
            return null;
        }
        String trimmed = rawKeyword.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record AutoInfiniteBiomeCandidate(String biomeId, String reason) {
    }

    public static List<AutoInfiniteBiomeCandidate> collectAutoInfiniteBiomeCandidates(Level level, boolean moddedOnly) {
        if (level == null || FlowingFluids.config == null) {
            return List.of();
        }

        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        List<AutoInfiniteBiomeCandidate> candidates = new ArrayList<>();
        for (ResourceKey<Biome> biomeKey : biomeRegistry.registryKeySet()) {
            if (moddedOnly && "minecraft".equals(biomeKey.location().getNamespace())) {
                continue;
            }

            Holder.Reference<Biome> biomeHolder = biomeRegistry.getHolderOrThrow(biomeKey);
            AutoInfiniteBiomeCandidate candidate = classifyAutoInfiniteBiomeCandidate(biomeKey, biomeHolder);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparing(AutoInfiniteBiomeCandidate::biomeId));
        return candidates;
    }

    private static @Nullable AutoInfiniteBiomeCandidate classifyAutoInfiniteBiomeCandidate(ResourceKey<Biome> biomeKey,
                                                                                           Holder<Biome> biome) {
        if (FlowingFluids.config == null || biomeKey == null) {
            return null;
        }

        List<String> reasons = new ArrayList<>();
        if (biome.is(BiomeTags.IS_OCEAN)) {
            reasons.add("#minecraft:is_ocean");
        }
        if (biome.is(BiomeTags.IS_RIVER)) {
            reasons.add("#minecraft:is_river");
        }
        if (biome.is(BiomeTags.IS_BEACH)) {
            reasons.add("#minecraft:is_beach");
        }

        List<String> tagReasons = findMatchingConfiguredBiomeEntries(biome, FlowingFluids.config.automaticInfiniteBiomeTagHints);
        List<String> keywordReasons = findMatchingConfiguredKeywords(biomeKey, FlowingFluids.config.automaticInfiniteBiomeKeywordHints);

        boolean hasDirectWaterTag = reasons.stream().anyMatch(reason -> reason.contains("ocean") || reason.contains("river") || reason.contains("beach"))
                || tagReasons.stream().anyMatch(FFFluidUtils::isDirectWaterTagHint);
        if (!hasDirectWaterTag && keywordReasons.isEmpty()) {
            return null;
        }

        reasons.addAll(tagReasons);
        reasons.addAll(keywordReasons);
        return new AutoInfiniteBiomeCandidate(biomeKey.location().toString(), String.join(", ", reasons));
    }

    private static boolean isDirectWaterTagHint(String entry) {
        if (entry == null) {
            return false;
        }
        if (!entry.startsWith("#")) {
            return true;
        }
        String lowered = entry.toLowerCase(Locale.ROOT);
        return lowered.contains("water")
                || lowered.contains("swamp")
                || lowered.contains("ocean")
                || lowered.contains("river")
                || lowered.contains("beach");
    }

    public static boolean isInfiniteBiomeRefillEnabled() {
        return isInfiniteBiomeRandomRefillEnabled() || isInfiniteBiomeNonConsumeEnabled();
    }

    public static boolean isInfiniteBiomeRandomRefillEnabled() {
        return FlowingFluids.config != null && FlowingFluids.config.oceanRiverSwampRefillChance > 0.0f;
    }

    public static boolean isInfiniteBiomeNonConsumeEnabled() {
        return FlowingFluids.config != null && FlowingFluids.config.infiniteWaterBiomeNonConsumeChance > 0.0f;
    }

    public static boolean isInfiniteBiomeSurfaceDrainEnabled() {
        return FlowingFluids.config != null && FlowingFluids.config.infiniteWaterBiomeDrainSurfaceChance > 0.0f;
    }

    public static boolean isInfiniteBiomeFlowingRefillEnabled() {
        return FlowingFluids.config != null
                && FlowingFluids.config.infiniteWaterBiomeFlowingRefillChance > 0.0f
                && FlowingFluids.config.infiniteWaterBiomeFlowingRefillMaxAmount > 0;
    }

    public static boolean isInfiniteBiomeRefillSuppressed(LevelAccessor level, BlockPos pos) {
        return InfiniteBiomeRefillSuppression.isSuppressed(level, pos);
    }

    public static float scaleInfiniteBiomePassiveRefillChance(float baseChance,
                                                              boolean riverLikeBiome,
                                                              float riverDroughtMultiplier) {
        float clampedBaseChance = Math.max(0.0f, Math.min(1.0f, baseChance));
        if (!riverLikeBiome) {
            return clampedBaseChance;
        }
        float clampedRiverMultiplier = Math.max(0.0f, Math.min(1.0f, riverDroughtMultiplier));
        return Math.max(0.0f, Math.min(1.0f, clampedBaseChance * clampedRiverMultiplier));
    }

    public static boolean isWithinInfiniteBiomeRefillBand(Level level, BlockPos pos) {
        return isWithinInfiniteBiomeRefillBand(pos.getY(), seaLevel(level), FlowingFluids.config.fastBiomeRefillAtSeaLevelOnly);
    }

    public static boolean isWithinInfiniteBiomeRefillBand(int y, int seaLevel, boolean seaLevelOnly) {
        if (y <= 0) {
            return false;
        }
        if (seaLevelOnly) {
            return y == seaLevel || y == seaLevel - 1;
        }
        return y <= seaLevel;
    }

    public static boolean hasInfiniteBiomeAmbientAccess(LevelAccessor level, BlockPos pos, Fluid fluid, int amount) {
        if (!(level instanceof Level world) || pos == null || fluid == null || amount <= 0) {
            return false;
        }

        FluidState below = getEffectiveFluidState(level, pos.below(), level.getBlockState(pos.below()));
        boolean hasFullBelow = below.getType().isSame(fluid) && below.getAmount() >= 8;

        FluidState above = getEffectiveFluidState(level, pos.above(), level.getBlockState(pos.above()));
        boolean hasFluidAbove = above.getType().isSame(fluid) && above.getAmount() > 0;

        int lateralWaterNeighbors = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, direction);
            FluidState neighbor = getEffectiveFluidState(level, cursor, level.getBlockState(cursor));
            if (neighbor.getType().isSame(fluid) && neighbor.getAmount() > 0) {
                lateralWaterNeighbors++;
            }
        }

        return classifyInfiniteBiomeAmbientAccess(
                world.getBrightness(LightLayer.SKY, pos) > 0,
                isOceanBiome(world.getBiome(pos)) || isBeachBiome(world.getBiome(pos)),
                pos.getY(),
                seaLevel(world),
                hasFullBelow,
                lateralWaterNeighbors,
                hasFluidAbove
        );
    }

    public static boolean classifyInfiniteBiomeAmbientAccess(boolean hasSkyLight,
                                                             boolean broadWaterBiome,
                                                             int y,
                                                             int seaLevel,
                                                             boolean hasFullBelow,
                                                             int lateralWaterNeighbors,
                                                             boolean hasFluidAbove) {
        if (hasSkyLight) {
            return true;
        }
        if (!broadWaterBiome || y > seaLevel) {
            return false;
        }
        if (hasFluidAbove) {
            return true;
        }
        if (hasFullBelow && lateralWaterNeighbors >= 1) {
            return true;
        }
        return y <= seaLevel && lateralWaterNeighbors >= 2;
    }

    public static int getInfiniteBiomeDepthBelowSeaLevel(int y, int seaLevel) {
        return Math.max(0, seaLevel - y);
    }

    public static float getInfiniteBiomeFlowingRefillChanceMultiplier(boolean broadWaterBiome, int depthBelowSeaLevel) {
        if (!broadWaterBiome || depthBelowSeaLevel <= 0) {
            return 1.0f;
        }
        if (depthBelowSeaLevel >= 24) {
            return 16.0f;
        }
        if (depthBelowSeaLevel >= 16) {
            return 10.0f;
        }
        if (depthBelowSeaLevel >= 8) {
            return 5.0f;
        }
        if (depthBelowSeaLevel >= 4) {
            return 2.0f;
        }
        return 1.25f;
    }

    public static int getInfiniteBiomeFlowingRefillMaxAmount(int configuredMaxAmount,
                                                             boolean broadWaterBiome,
                                                             int depthBelowSeaLevel,
                                                             int currentAmount) {
        int room = Math.max(0, 8 - currentAmount);
        if (room <= 0) {
            return 0;
        }

        int maxAmount = Math.max(1, configuredMaxAmount);
        if (broadWaterBiome && depthBelowSeaLevel > 0) {
            if (depthBelowSeaLevel >= 24) {
                maxAmount += 3;
            } else if (depthBelowSeaLevel >= 16) {
                maxAmount += 2;
            } else if (depthBelowSeaLevel >= 8) {
                maxAmount += 1;
            }
        }
        return Math.min(room, maxAmount);
    }

    public static int getInfiniteBiomeRefillAmount(LevelAccessor level, BlockPos pos, Fluid fluid, int amount, boolean aggressive) {
        if (!(level instanceof Level world)) {
            return 0;
        }
        int seaLevel = seaLevel(world);
        if (amount <= 0 || amount >= 8 || pos.getY() == seaLevel) {
            return 0;
        }
        if (!isWithinInfiniteBiomeRefillBand(world, pos)) {
            return 0;
        }

        FluidState below = getEffectiveFluidState(level, pos.below(), level.getBlockState(pos.below()));
        boolean hasFullBelow = below.getType().isSame(fluid) && below.getAmount() >= 8;

        FluidState above = getEffectiveFluidState(level, pos.above(), level.getBlockState(pos.above()));
        boolean hasFluidAbove = above.getType().isSame(fluid) && above.getAmount() > 0;

        int lateralWaterNeighbors = 0;
        int supportedNeighbors = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, direction);
            FluidState neighbor = getEffectiveFluidState(level, cursor, level.getBlockState(cursor));
            if (!neighbor.getType().isSame(fluid) || neighbor.getAmount() <= 0) {
                continue;
            }
            lateralWaterNeighbors++;

            cursor.move(Direction.DOWN);
            FluidState neighborBelow = getEffectiveFluidState(level, cursor, level.getBlockState(cursor));
            if (neighborBelow.getType().isSame(fluid) && neighborBelow.getAmount() >= 8) {
                supportedNeighbors++;
            }
            cursor.move(Direction.UP);
        }

        return classifyInfiniteBiomeRefillAmount(amount, hasFullBelow, lateralWaterNeighbors, supportedNeighbors, hasFluidAbove, aggressive);
    }

    public static int classifyInfiniteBiomeRefillAmount(int amount, boolean hasFullBelow, int lateralWaterNeighbors,
                                                        int supportedNeighbors, boolean hasFluidAbove, boolean aggressive) {
        int room = 8 - amount;
        if (amount <= 0 || room <= 0) {
            return 0;
        }

        boolean anchored = supportedNeighbors >= 2
                || (hasFullBelow && lateralWaterNeighbors >= 1)
                || (hasFluidAbove && lateralWaterNeighbors >= 1);
        if (!anchored) {
            return 0;
        }

        int supportScore = (hasFullBelow ? 2 : 0)
                + lateralWaterNeighbors
                + supportedNeighbors
                + (hasFluidAbove ? 1 : 0);

        if (aggressive) {
            if (supportScore >= 7) {
                return room;
            }
            if (supportScore >= 5) {
                return Math.min(room, 2);
            }
            if (supportScore >= 4 && amount <= 3) {
                return 1;
            }
            return 0;
        }

        if (supportScore >= 6) {
            return Math.min(room, 2);
        }
        if (supportScore >= 4) {
            return 1;
        }
        return 0;
    }

    public static boolean shouldAttemptInfiniteBiomeFlowingRefill(LevelAccessor level, BlockPos pos, Fluid fluid,
                                                                  int originalAmount, int currentAmount) {
        if (!(level instanceof Level world) || FlowingFluids.config == null) {
            return false;
        }
        if (!isInfiniteBiomeFlowingRefillEnabled()) {
            return false;
        }
        if (currentAmount <= 0 || currentAmount >= 8 || currentAmount >= originalAmount) {
            return false;
        }
        if (!isWithinInfiniteBiomeRefillBand(world, pos) || pos.getY() == seaLevel(world)) {
            return false;
        }
        if (!hasInfiniteBiomeAmbientAccess(level, pos, fluid, currentAmount)) {
            return false;
        }
        if (AdaptiveTickScheduler.isFlowActiveNow(level, pos)) {
            return false;
        }
        boolean broadWaterBiome = isOceanBiome(world.getBiome(pos)) || isBeachBiome(world.getBiome(pos));
        boolean riverLikeBiome = isRiverBiome(world.getBiome(pos));
        if (riverLikeBiome) {
            if (currentAmount <= 4) {
                return false;
            }
            if (AdaptiveTickScheduler.getPoolStableTicks(level, pos, 20) < 4) {
                return false;
            }
        }

        int interval = Math.max(1, FlowingFluids.config.infiniteWaterBiomeFlowingRefillInterval);
        int stagger = Math.floorMod(Long.hashCode(pos.asLong()), interval);
        if (Math.floorMod(world.getGameTime(), interval) != stagger) {
            return false;
        }

        int deficit = Math.max(1, originalAmount - currentAmount);
        int room = Math.max(1, 8 - currentAmount);
        float chance = FlowingFluids.config.infiniteWaterBiomeFlowingRefillChance;
        if (riverLikeBiome) {
            chance *= 0.35f;
        }
        chance *= getInfiniteBiomeFlowingRefillChanceMultiplier(
                broadWaterBiome,
                getInfiniteBiomeDepthBelowSeaLevel(pos.getY(), seaLevel(world))
        );
        float deficitBoost = 1.0f + Math.min(1.5f, (deficit - 1) * 0.35f);
        float roomBoost = 1.0f + Math.min(1.0f, (room - 1) * 0.1f);
        float adjustedChance = Math.min(1.0f, chance * deficitBoost * roomBoost);
        return world.getRandom().nextFloat() < adjustedChance;
    }

    public static int getInfiniteBiomeFlowingRefillAmount(LevelAccessor level, BlockPos pos, Fluid fluid, int amount) {
        int refill = getInfiniteBiomeRefillAmount(level, pos, fluid, amount, true);
        if (refill <= 0 || FlowingFluids.config == null) {
            return 0;
        }
        boolean broadWaterBiome = false;
        int depthBelowSeaLevel = 0;
        if (level instanceof Level world) {
            broadWaterBiome = isOceanBiome(world.getBiome(pos)) || isBeachBiome(world.getBiome(pos));
            depthBelowSeaLevel = getInfiniteBiomeDepthBelowSeaLevel(pos.getY(), seaLevel(world));
        }
        return Math.min(refill, getInfiniteBiomeFlowingRefillMaxAmount(
                FlowingFluids.config.infiniteWaterBiomeFlowingRefillMaxAmount,
                broadWaterBiome,
                depthBelowSeaLevel,
                amount
        ));
    }

    public static boolean shouldFallbackToVanillaInfiniteSourceRefill(boolean heavyLoad,
                                                                      int y,
                                                                      int seaLevel,
                                                                      int amount,
                                                                      int fullSourceNeighbors,
                                                                      boolean supportedBelow,
                                                                      boolean hasFluidAbove) {
        if (!heavyLoad || amount < 5 || amount >= 8) {
            return false;
        }
        if (y < seaLevel - 1 || y > seaLevel) {
            return false;
        }
        if (hasFluidAbove || !supportedBelow) {
            return false;
        }
        return fullSourceNeighbors >= 2;
    }

    public static boolean tryApplyVanillaInfiniteSourceRefill(LevelAccessor levelAccessor,
                                                              BlockPos pos,
                                                              FlowingFluid fluid,
                                                              int amount,
                                                              boolean heavyLoad) {
        if (!(levelAccessor instanceof Level world) || pos == null || fluid == null) {
            return false;
        }

        int currentAmount = Mth.clamp(amount, 0, 8);
        if (currentAmount <= 0 || currentAmount >= 8) {
            return false;
        }

        BlockPos abovePos = pos.above();
        FluidState aboveFluid = getEffectiveFluidState(levelAccessor, abovePos, levelAccessor.getBlockState(abovePos));
        boolean hasFluidAbove = aboveFluid.getType().isSame(fluid) && aboveFluid.getAmount() > 0;

        BlockPos belowPos = pos.below();
        BlockState belowState = levelAccessor.getBlockState(belowPos);
        FluidState belowFluid = getEffectiveFluidState(levelAccessor, belowPos, belowState);
        boolean supportedBelow = (belowFluid.getType().isSame(fluid) && belowFluid.getAmount() >= 8)
                || (!belowState.isAir() && !belowState.canBeReplaced(fluid));

        int fullSourceNeighbors = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, direction);
            FluidState neighbor = getEffectiveFluidState(levelAccessor, cursor, levelAccessor.getBlockState(cursor));
            if (neighbor.getType().isSame(fluid) && neighbor.getAmount() >= 8) {
                fullSourceNeighbors++;
            }
        }

        if (!shouldFallbackToVanillaInfiniteSourceRefill(
                heavyLoad,
                pos.getY(),
                seaLevel(world),
                currentAmount,
                fullSourceNeighbors,
                supportedBelow,
                hasFluidAbove)) {
            return false;
        }

        return setFluidStateAtPosToNewAmount(levelAccessor, pos, fluid, 8);
    }

    public static boolean classifyBroadSurfaceWater(boolean oceanLikeBiome, boolean riverLikeBiome, int lateralWaterNeighbors,
                                                    boolean hasFluidAbove, boolean supportedBelow,
                                                    boolean immediateDownwardOutlet, int stableTicks,
                                                    int requiredStableTicks) {
        if (riverLikeBiome) {
            return false;
        }
        if (lateralWaterNeighbors < 3 || hasFluidAbove || !supportedBelow || immediateDownwardOutlet) {
            return false;
        }
        return stableTicks >= Math.max(1, requiredStableTicks) && (oceanLikeBiome || lateralWaterNeighbors >= 3);
    }

    private static int getInfiniteBiomeSurfaceDrainStableTicksRequired(int amount) {
        if (amount <= 1) {
            return 1;
        }
        if (amount <= 2) {
            return 2;
        }
        if (amount <= 3) {
            return 4;
        }
        return 6;
    }

    public static int getInfiniteBiomeSurfaceDrainBurstAmount(int amount, int drainAmount) {
        if (amount <= 0 || drainAmount <= 0) {
            return 0;
        }

        int burstAmount = drainAmount + 1;
        if (amount >= 4 && drainAmount >= 2) {
            burstAmount++;
        }
        return Math.min(8, Math.max(drainAmount, burstAmount));
    }

    public static int classifyInfiniteBiomeSurfaceDrainAmount(int amount, int lateralWaterNeighbors, int supportedNeighbors) {
        if (amount <= 0) {
            return 0;
        }
        if (lateralWaterNeighbors >= 2 && supportedNeighbors >= 2) {
            return 0;
        }

        int drainAmount = amount <= 2 ? amount : 1 + Math.max(0, (amount - 1) / 2);
        if (lateralWaterNeighbors == 0 && supportedNeighbors == 0 && amount >= 4) {
            drainAmount++;
        }
        return Math.min(amount, drainAmount);
    }

    /**
     * Sea-level infinite biomes are often broad, exposed surfaces. Draining those full or well-supported
     * tiles creates visible oscillation as equalization immediately fills them back in. Only allow
     * "surface drain" on calm, thin partial-height edge tiles, and let the drain amount scale gently
     * with how much partial water has pooled there.
     */
    public static int getInfiniteBiomeSurfaceDrainAmount(LevelAccessor level, BlockPos pos, Fluid fluid, int amount) {
        if (!(level instanceof Level world)) {
            return 0;
        }
        int seaLevel = seaLevel(world);
        if (amount <= 0 || amount >= 8 || amount > 4 || pos.getY() != seaLevel) {
            return 0;
        }
        if (isProtectedInfiniteBiomeWater(level, pos, fluid, amount)) {
            return 0;
        }
        if (AdaptiveTickScheduler.isFlowActiveNow(level, pos)) {
            return 0;
        }
        int momentumAge = FlowingFluids.config != null
            ? Math.max(8, FlowingFluids.config.flowInertiaMaxAgeTicks / 2)
            : 20;
        if (AdaptiveTickScheduler.getFlowMomentum(level, pos, momentumAge) > 0.35f) {
            return 0;
        }
        int stableTicks = AdaptiveTickScheduler.getPoolStableTicks(level, pos, Math.max(20, momentumAge));
        if (stableTicks < getInfiniteBiomeSurfaceDrainStableTicksRequired(amount)) {
            return 0;
        }

        FluidState below = getEffectiveFluidState(level, pos.below(), level.getBlockState(pos.below()));
        if (!below.getType().isSame(fluid) || below.getAmount() < 8) {
            return 0;
        }

        int lateralWaterNeighbors = 0;
        int supportedNeighbors = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, direction);
            FluidState neighbor = getEffectiveFluidState(level, cursor, level.getBlockState(cursor));
            if (!neighbor.getType().isSame(fluid) || neighbor.getAmount() <= 0) {
                continue;
            }
            lateralWaterNeighbors++;

            cursor.move(Direction.DOWN);
            FluidState neighborBelow = getEffectiveFluidState(level, cursor, level.getBlockState(cursor));
            if (neighborBelow.getType().isSame(fluid) && neighborBelow.getAmount() >= 8) {
                supportedNeighbors++;
            }
            cursor.move(Direction.UP);
        }

        return classifyInfiniteBiomeSurfaceDrainAmount(amount, lateralWaterNeighbors, supportedNeighbors);
    }

    public static boolean applyInfiniteBiomeSurfaceDrain(LevelAccessor levelAccessor,
                                                         BlockPos pos,
                                                         FlowingFluid fluid,
                                                         int amount,
                                                         int drainAmount) {
        if (levelAccessor == null || pos == null || fluid == null || amount <= 0 || drainAmount <= 0) {
            return false;
        }

        int remainingBudget = getInfiniteBiomeSurfaceDrainBurstAmount(amount, drainAmount);
        if (remainingBudget <= 0) {
            return false;
        }

        boolean[] changed = {false};
        int[] remaining = {remainingBudget};
        BlockPos originPos = pos.immutable();

        runWithBulkFluidChanges(levelAccessor, () -> {
            int originDrain = Math.min(Math.min(amount, drainAmount), remaining[0]);
            if (originDrain > 0 && applyLocalFluidAmountDelta(levelAccessor, originPos, fluid, -originDrain)) {
                remaining[0] -= originDrain;
                changed[0] = true;
            }

            if (remaining[0] <= 0) {
                return;
            }

            Direction[] searchOrder = getCardinalsShuffle(levelAccessor.getRandom());
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            // Keep the extra drain local and surface-facing so broad calm water does not
            // fall back into the earlier per-cell inlet churn pattern.
            for (Direction direction : searchOrder) {
                if (remaining[0] <= 0) {
                    break;
                }

                cursor.setWithOffset(originPos, direction);
                FluidState neighborState = getEffectiveFluidState(levelAccessor, cursor, levelAccessor.getBlockState(cursor));
                int neighborAmount = neighborState.getType().isSame(fluid) ? neighborState.getAmount() : 0;
                if (neighborAmount <= 0) {
                    continue;
                }
                if (!hasInfiniteBiomeAmbientAccess(levelAccessor, cursor, fluid, neighborAmount)) {
                    continue;
                }

                int neighborDrainAmount = getInfiniteBiomeSurfaceDrainAmount(levelAccessor, cursor, fluid, neighborAmount);
                if (neighborDrainAmount <= 0) {
                    continue;
                }

                int appliedDrain = Math.min(remaining[0], neighborDrainAmount);
                if (appliedDrain > 0 && applyLocalFluidAmountDelta(levelAccessor, cursor, fluid, -appliedDrain)) {
                    remaining[0] -= appliedDrain;
                    changed[0] = true;
                }
            }
        });

        return changed[0];
    }

    public static boolean shouldDrainInfiniteBiomeSurface(LevelAccessor level, BlockPos pos, Fluid fluid, int amount) {
        return getInfiniteBiomeSurfaceDrainAmount(level, pos, fluid, amount) > 0;
    }

    public static boolean isProtectedInfiniteBiomeWater(LevelAccessor level, BlockPos pos, Fluid fluid, int amount) {
        if (!(level instanceof Level world) || pos == null || fluid == null || amount <= 0) {
            return false;
        }
        Holder<Biome> biome = world.getBiome(pos);
        if (!matchInfiniteBiomes(biome)) {
            return false;
        }
        if (isRiverBiome(biome)) {
            return true;
        }

        FluidState above = getEffectiveFluidState(level, pos.above(), level.getBlockState(pos.above()));
        if (above.getType().isSame(fluid) && above.getAmount() > 0) {
            return true;
        }

        FluidState below = getEffectiveFluidState(level, pos.below(), level.getBlockState(pos.below()));
        boolean supportedBelow = (below.getType().isSame(fluid) && below.getAmount() >= Math.max(amount, 4))
            || (!world.getBlockState(pos.below()).isAir() && !world.getBlockState(pos.below()).canBeReplaced(fluid));

        int lateralWaterNeighbors = 0;
        int supportedNeighbors = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, direction);
            FluidState neighbor = getEffectiveFluidState(level, cursor, level.getBlockState(cursor));
            if (!neighbor.getType().isSame(fluid) || neighbor.getAmount() <= 0) {
                continue;
            }
            lateralWaterNeighbors++;
            if (neighbor.getAmount() >= Math.max(2, amount - 1)) {
                supportedNeighbors++;
            }
        }

        if (supportedBelow && lateralWaterNeighbors >= 2) {
            return true;
        }
        if (amount >= 4 && lateralWaterNeighbors >= 1) {
            return true;
        }
        return pos.getY() >= seaLevel(world) - 1 && supportedNeighbors >= 2;
    }

    public static boolean isOceanBiome(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_OCEAN)
                || matchesConfiguredBiome(biome, FlowingFluids.config.extraOceanBiomes)
                || isAutoDetectedWaterBiome(biome, "ocean", "sea", "gulf", "bay");
    }

    public static boolean isRiverBiome(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_RIVER)
                || matchesConfiguredBiome(biome, FlowingFluids.config.extraRiverBiomes)
                || isAutoDetectedWaterBiome(biome, "river", "stream", "creek", "delta");
    }

    public static boolean isBeachBiome(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_BEACH)
                || matchesConfiguredBiome(biome, FlowingFluids.config.extraBeachBiomes)
                || isAutoDetectedWaterBiome(biome, "beach", "shore", "coast");
    }

    private static boolean matchesConfiguredBiome(Holder<Biome> biome, Collection<String> configuredBiomes) {
        if (configuredBiomes == null || configuredBiomes.isEmpty()) return false;

        for (String configured : configuredBiomes) {
            if (configured == null || configured.isBlank()) continue;
            String trimmed = configured.trim();
            if (trimmed.startsWith("#")) {
                var res = ResourceLocation.tryParse(trimmed.substring(1));
                if (res != null && biome.is(TagKey.create(Registries.BIOME, res))) {
                    return true;
                }
            } else {
                var res = ResourceLocation.tryParse(trimmed);
                if (res != null && biome.is(ResourceKey.create(Registries.BIOME, res))) {
                    return true;
                }
            }
        }

        return false;
    }

    private static List<String> findMatchingConfiguredBiomeEntries(Holder<Biome> biome, Collection<String> configuredBiomes) {
        List<String> matches = new ArrayList<>();
        if (configuredBiomes == null || configuredBiomes.isEmpty()) {
            return matches;
        }

        for (String configured : configuredBiomes) {
            if (configured == null || configured.isBlank()) {
                continue;
            }
            String trimmed = configured.trim();
            if (trimmed.startsWith("#")) {
                ResourceLocation res = ResourceLocation.tryParse(trimmed.substring(1));
                if (res != null && biome.is(TagKey.create(Registries.BIOME, res))) {
                    matches.add("#" + res);
                }
            } else {
                ResourceLocation res = ResourceLocation.tryParse(trimmed);
                if (res != null && biome.is(ResourceKey.create(Registries.BIOME, res))) {
                    matches.add(res.toString());
                }
            }
        }
        return matches;
    }

    private static List<String> findMatchingConfiguredKeywords(ResourceKey<Biome> biomeKey, Collection<String> configuredKeywords) {
        List<String> matches = new ArrayList<>();
        if (biomeKey == null || configuredKeywords == null || configuredKeywords.isEmpty()) {
            return matches;
        }

        String search = biomeKey.location().toString().toLowerCase(Locale.ROOT);
        for (String configuredKeyword : configuredKeywords) {
            String normalizedKeyword = normalizeConfiguredKeyword(configuredKeyword);
            if (normalizedKeyword != null && search.contains(normalizedKeyword)) {
                matches.add("keyword:" + normalizedKeyword);
            }
        }
        return matches;
    }

    private static boolean isAutoDetectedWaterBiome(Holder<Biome> biome, String... keywords) {
        if (!FlowingFluids.config.autoDetectWaterBiomes) return false;

        return biome.unwrapKey().map(key -> {
            String path = key.location().getPath().toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (path.contains(keyword)) {
                    return true;
                }
            }
            return false;
        }).orElse(false);
    }
}
