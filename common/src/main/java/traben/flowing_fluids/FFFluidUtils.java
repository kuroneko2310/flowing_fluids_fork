package traben.flowing_fluids;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.NotNull;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.ChunkLocalSlopeCache;
import traben.flowing_fluids.FluidSpatialGrid;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.function.Predicate;

public class FFFluidUtils {

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
    private static final ThreadLocal<LongArrayList> POSITION_BUFFER = ThreadLocal.withInitial(LongArrayList::new);
    private static final ThreadLocal<IntArrayList> LEVEL_BUFFER = ThreadLocal.withInitial(IntArrayList::new);

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
        for (Direction direction :Direction.Plane.HORIZONTAL) {
            if (FFFluidUtils.canFluidFlowFromPosToDirection(fluid, amount, accessor, pos, direction)) {
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


    public static boolean setFluidStateAtPosToNewAmount(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int newAmount) {
        if (newAmount < 1) {
            boolean result = removeAllFluidAtPos(levelAccessor, pos, fluid);
            if (result) {
                // Notify adaptive scheduler, cache, and spatial grid of fluid removal
                AdaptiveTickScheduler.notifyFluidChange(levelAccessor, pos);
                ChunkLocalSlopeCache.clearChunk(levelAccessor, new net.minecraft.world.level.ChunkPos(pos));
                FluidSpatialGrid.removeFluidAt(levelAccessor, pos);
            }
            return result;
        }

        //check if we are dealing with a waterlogged block
        var blockState = levelAccessor.getBlockState(pos);
        if (blockState.getBlock() instanceof LiquidBlockContainer liquidBlockContainer) {
            if (newAmount == 8) {
                boolean result = liquidBlockContainer.placeLiquid(levelAccessor, pos, blockState, getStateForFluidByAmount(fluid, newAmount));
                if (result) {
                    AdaptiveTickScheduler.notifyFluidChange(levelAccessor, pos);
                    ChunkLocalSlopeCache.clearChunk(levelAccessor, new net.minecraft.world.level.ChunkPos(pos));
                    FluidSpatialGrid.setFluidAt(levelAccessor, pos, true);
                }
                return result;
            }else if (blockState.getBlock() instanceof BucketPickup bucketPickup) {
                //always drain the water loggable block if it's not full
                bucketPickup.pickupBlock(#if MC > MC_20_1 null, #endif levelAccessor, pos, blockState);
                AdaptiveTickScheduler.notifyFluidChange(levelAccessor, pos);
                ChunkLocalSlopeCache.clearChunk(levelAccessor, new net.minecraft.world.level.ChunkPos(pos));
                FluidSpatialGrid.removeFluidAt(levelAccessor, pos);
                return true;
            }
            //if we cant fill or drain it check if we can just replace it with the new fluid level by itself
            if (!blockState.canBeReplaced(fluid)) {
                return false;//todo infinite source block possible???
            }
        }

        if (!blockState.isAir() && fluid instanceof FlowingFluid flowingFluid) {
            flowingFluid.beforeDestroyingBlock(levelAccessor, pos, blockState);
        }
        //else place fluid block
        boolean result = levelAccessor.setBlock(pos, getStateForFluidByAmount(fluid, newAmount).createLegacyBlock(), 3);
        if (result) {
            AdaptiveTickScheduler.notifyFluidChange(levelAccessor, pos);
            ChunkLocalSlopeCache.clearChunk(levelAccessor, new net.minecraft.world.level.ChunkPos(pos));
            FluidSpatialGrid.setFluidAt(levelAccessor, pos, true);
        }
        return result;
    }


    public static boolean removeAllFluidAtPos(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid) {
        var blockState = levelAccessor.getBlockState(pos);
        if (blockState.getBlock() instanceof LiquidBlockContainer
                && blockState.getBlock() instanceof BucketPickup bucketPickup) {
            bucketPickup.pickupBlock(#if MC > MC_20_1 null, #endif levelAccessor, pos, blockState);
            AdaptiveTickScheduler.notifyFluidChange(levelAccessor, pos);
            ChunkLocalSlopeCache.clearChunk(levelAccessor, new net.minecraft.world.level.ChunkPos(pos));
            FluidSpatialGrid.removeFluidAt(levelAccessor, pos);
            return true;
        }

        if (!blockState.isAir() && fluid instanceof FlowingFluid flowingFluid) {//todo needed for remove??
            flowingFluid.beforeDestroyingBlock(levelAccessor, pos, blockState);
        }

        boolean result = levelAccessor.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        if (result) {
            AdaptiveTickScheduler.notifyFluidChange(levelAccessor, pos);
            ChunkLocalSlopeCache.clearChunk(levelAccessor, new net.minecraft.world.level.ChunkPos(pos));
            FluidSpatialGrid.removeFluidAt(levelAccessor, pos);
        }
        return result;
    }


    public static int removeAmountFromFluidAtPosWithRemainder(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int removeAmount) {
        FluidState state = levelAccessor.getFluidState(pos);
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
        var data = placeConnectedFluidAmountAndPlaceAction(levelAccessor, pos, addAmount, fluid);
        if (data.first() != addAmount) {
            data.second().run();
            return data.first();
        }
        return addAmount;
    }

    public static int addAmountToFluidAtPosWithRemainderAndTrySpreadIfFull(LevelAccessor levelAccessor, BlockPos pos, FlowingFluid fluid, int addAmount, boolean canSpreadUp, boolean canSpreadDown) {
        var data = placeConnectedFluidAmountAndPlaceAction(levelAccessor, pos, addAmount, fluid, 80, canSpreadUp, canSpreadDown);
        if (data.first() != addAmount) {
            data.second().run();
            return data.first();
        }
        return addAmount;
    }

    public static int addAmountToFluidAtPosWithRemainder(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int addAmount) {
        FluidState state = levelAccessor.getFluidState(pos);
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
        var fluidState2 = blockState2.getFluidState();
        return canFluidFlowFromPosToDirection(fluid, amount, levelAccessor, fromPos, levelAccessor.getBlockState(fromPos), direction, blockPos2, blockState2, fluidState2);
    }
    public static boolean canFluidFlowFromPosToDirection(FlowingFluid sourceFluid, int sourceAmount, BlockGetter blockGetter,
                                                         BlockPos blockPos, BlockState blockState, Direction direction,
                                                         BlockPos blockPos2, BlockState blockState2, FluidState fluidState2) {
        //add extra fluid check for replacing into self
        return (fluidState2.canBeReplacedWith(blockGetter, blockPos2, sourceFluid, direction) || canFitIntoFluid(sourceFluid, fluidState2, direction, sourceAmount, blockState2))
                && sourceFluid.canPassThroughWall(direction, blockGetter, blockPos, blockState, blockPos2, blockState2)
                && sourceFluid.canHoldFluid(blockGetter, blockPos2, blockState2, sourceFluid);
    }

    public static boolean canFluidFlowFromPosToDirectionFitOverride(FlowingFluid sourceFluid, BlockGetter blockGetter,
                                                         BlockPos blockPos, BlockState blockState, Direction direction,
                                                         BlockPos blockPos2, BlockState blockState2) {
        //add extra fluid check for replacing into self
        return sourceFluid.canPassThroughWall(direction, blockGetter, blockPos, blockState, blockPos2, blockState2) && sourceFluid.canHoldFluid(blockGetter, blockPos2, blockState2, sourceFluid);
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
        var originalState = levelAccessor.getFluidState(blockPos);
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

            long originKey = blockPos.asLong();
            queue.enqueue(originKey);
            visited.add(originKey);

            BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos neighbourPos = new BlockPos.MutableBlockPos();

            int totalCapacity = 0;

            while (!queue.isEmpty()) {
                if (visited.size() > depth) {
                    break;
                }

                long currentKey = queue.dequeueLong();
                currentPos.set(BlockPos.getX(currentKey), BlockPos.getY(currentKey), BlockPos.getZ(currentKey));

                // Optimize: get BlockState once and derive FluidState from it to avoid double lookup
                BlockState blockState = levelAccessor.getBlockState(currentPos);
                FluidState state = blockState.getFluidState();
                boolean isSameFluid = fluid.isSame(state.getType());
                if (isSameFluid || (state.isEmpty() && blockState.isAir())) {
                    int currentAmountAtPos = isSameFluid ? state.getAmount() : 0;
                    int space = 8 - currentAmountAtPos;
                    if (space > 0) {
                        positionBuffer.add(currentKey);
                        levelBuffer.add(currentAmountAtPos);
                        totalCapacity += space;
                    }

                    // Optimized direction priority: down first (gravity), then sides, then up
                    // This follows natural fluid flow and finds space more efficiently

                    if (doDown) {
                        neighbourPos.set(currentPos);
                        neighbourPos.move(Direction.DOWN);
                        long downKey = neighbourPos.asLong();
                        if (visited.add(downKey)) {
                            queue.enqueue(downKey);
                        }
                    }

                    for (Direction direction : getCardinalsShuffle(random)) {
                        neighbourPos.set(currentPos);
                        neighbourPos.move(direction);
                        long neighbourKey = neighbourPos.asLong();
                        if (visited.add(neighbourKey)) {
                            queue.enqueue(neighbourKey);
                        }
                    }

                    if (doUp) {
                        neighbourPos.set(currentPos);
                        neighbourPos.move(Direction.UP);
                        long upKey = neighbourPos.asLong();
                        if (visited.add(upKey)) {
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
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Integer.compare(finalLevels[a], finalLevels[b]));

            int remaining = placeable;
            // Evenly raise the lowest reachable cells first so added fluid does not pile up at the entry.
            for (int tier = 0; tier < count && remaining > 0; tier++) {
                int currentLevel = finalLevels[order[tier]];
                int nextLevel = tier == count - 1 ? 8 : finalLevels[order[tier + 1]];
                int span = Math.max(0, nextLevel - currentLevel);
                if (span == 0) {
                    continue;
                }

                int needed = span * (tier + 1);
                if (remaining >= needed) {
                    for (int i = 0; i <= tier; i++) {
                        finalLevels[order[i]] += span;
                    }
                    remaining -= needed;
                } else {
                    int share = remaining / (tier + 1);
                    int extra = remaining % (tier + 1);
                    for (int i = 0; i <= tier; i++) {
                        finalLevels[order[i]] += share + (i < extra ? 1 : 0);
                    }
                    remaining = 0;
                    break;
                }
            }

            int placed = placeable - remaining;
            int unplaced = amountToPlace - placed;

            positionBuffer.clear();
            levelBuffer.clear();

            if (placed <= 0) {
                return Pair.of(amountToPlace, null);
            }

            return Pair.of(unplaced, () -> {
                BlockPos.MutableBlockPos applyPos = new BlockPos.MutableBlockPos();
                for (int i = 0; i < count; i++) {
                    long key = positions[i];
                    applyPos.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
                    FFFluidUtils.setFluidStateAtPosToNewAmount(levelAccessor, applyPos, fluid, finalLevels[i]);
                }
            });
        }
        return Pair.of(amountToPlace, null);
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
        return collectConnectedFluidAmountAndRemoveAction(levelAccessor, blockPos, minAmountRequired, maxAmountToFind, fluid, 40);
    }

    public static Pair<Integer, Runnable> collectConnectedFluidAmountAndRemoveAction(final LevelAccessor levelAccessor, final BlockPos blockPos, final int minAmountRequired, final int maxAmountToFind, final FlowingFluid fluid, int depth) {
        var originalState = levelAccessor.getFluidState(blockPos);
        int originalAmount = originalState.getAmount();
        if (originalState.getType().isSame(fluid) && originalAmount > 0) {

            //check for quick exit
            if (originalAmount >= maxAmountToFind) {
                return Pair.of(maxAmountToFind,()->{FFFluidUtils.setFluidStateAtPosToNewAmount(levelAccessor, blockPos, fluid, originalAmount - maxAmountToFind);});
            }

            // FIXED: Use ThreadLocal caches for better performance and consistency
            LongArrayFIFOQueue positionsToCheck = getPositionQueue();
            LongOpenHashSet discoveredPositions = getVisitedPositions();
            LongArrayList positionBuffer = getPositionBuffer();
            IntArrayList levelBuffer = getLevelBuffer();
            RandomSource random = levelAccessor.getRandom();

            long originKey = blockPos.asLong();
            positionsToCheck.enqueue(originKey);
            discoveredPositions.add(originKey);

            BlockPos.MutableBlockPos seedPos = new BlockPos.MutableBlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ());
            for (Direction direction : getAllDirectionsShuffled(random)) {
                seedPos.set(blockPos.getX(), blockPos.getY(), blockPos.getZ());
                seedPos.move(direction);
                long seedKey = seedPos.asLong();
                if (discoveredPositions.add(seedKey)) {
                    positionsToCheck.enqueue(seedKey);
                }
            }

            int foundAmount = 0;

            BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos neighbourPos = new BlockPos.MutableBlockPos();

            while (!positionsToCheck.isEmpty()) {
                if (discoveredPositions.size() > depth) {
                    break;
                }

                long currentKey = positionsToCheck.dequeueLong();
                mutablePos.set(BlockPos.getX(currentKey), BlockPos.getY(currentKey), BlockPos.getZ(currentKey));

                // Optimize: avoid redundant getFluidState call by getting state from blockstate
                var state = levelAccessor.getBlockState(mutablePos).getFluidState();
                if (fluid.isSame(state.getType())) {
                    int amount = state.getAmount();
                    if (amount > 0) {
                        foundAmount += amount;
                        if (foundAmount > maxAmountToFind) {
                            final int finalLevel = foundAmount - maxAmountToFind;
                            positionBuffer.add(currentKey);
                            levelBuffer.add(finalLevel);
                            foundAmount = maxAmountToFind;
                            break;
                        } else {
                            positionBuffer.add(currentKey);
                            levelBuffer.add(0); // 0 means remove
                            if (foundAmount == maxAmountToFind) {
                                break;
                            }
                            for (Direction direction : getAllDirectionsShuffled(random)) {
                                neighbourPos.set(mutablePos.getX(), mutablePos.getY(), mutablePos.getZ());
                                neighbourPos.move(direction);
                                long neighbourKey = neighbourPos.asLong();
                                if (discoveredPositions.add(neighbourKey)) {
                                    positionsToCheck.enqueue(neighbourKey);
                                }
                            }
                        }
                    }
                }
            }

            // FIXED: No need to clear ThreadLocal caches - they are managed automatically

            if (foundAmount < minAmountRequired) {
                //failed to find enough fluid so cancel
                positionBuffer.clear();
                levelBuffer.clear();
                return Pair.of(0, null);
            }

            final long[] positions = positionBuffer.toLongArray();
            final int[] levels = levelBuffer.toIntArray();

            positionBuffer.clear();
            levelBuffer.clear();

            return Pair.of(foundAmount, ()->{
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
        }
        return Pair.of(0, null);
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
                && !FlowingFluids.isManeuveringFluids
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
            FlowingFluids.isManeuveringFluids = true;


            try {
                // try spread to the side as much as possible
                int amountRemaining = originalState.getFluidState().getAmount();
                for (Direction direction : getCardinalsShuffle(level.getRandom())) {
                    BlockPos offset = pos.relative(direction);
                    BlockState offsetState = level.getBlockState(offset);

                    if (offsetState.getFluidState().getType() instanceof FlowingFluid) {
                        amountRemaining = addAmountToFluidAtPosWithRemainder(level, offset, flowSource, amountRemaining);
                        if (amountRemaining == 0) break;
                    } else if (offsetState.isAir()) {
                        level.setBlock(offset, originalState.getFluidState().createLegacyBlock(), 3);
                        amountRemaining = 0;
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
                        if (offsetState.getFluidState().getType() instanceof FlowingFluid) {
                            amountRemaining = addAmountToFluidAtPosWithRemainder(level, posTraversing, flowSource, amountRemaining);
                        } else if (offsetState.isAir()) {
                            level.setBlock(posTraversing, originalState.getFluidState().createLegacyBlock(), 3);
                            amountRemaining = 0;
                        } else {
                            break;
                        }
                    }
                }
            } finally {
                FlowingFluids.isManeuveringFluids = false;
            }
        }
    }

    public static boolean matchInfiniteBiomes(Holder<Biome> biome){
        return FlowingFluids.infiniteBiomeTags.stream().anyMatch(biome::is)
                || FlowingFluids.infiniteBiomes.stream().anyMatch(biome::is)
                || isOceanBiome(biome)
                || isRiverBiome(biome)
                || isBeachBiome(biome);
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
