package traben.flowing_fluids.mixin;


import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2BooleanMap;
import it.unimi.dsi.fastutil.shorts.Short2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.ChunkLocalSlopeCache;
import traben.flowing_fluids.FFDownwardFlowTarget;
import traben.flowing_fluids.FFFlowDownResult;
import traben.flowing_fluids.FFSectionSampleContext;
import traben.flowing_fluids.FluidAmountConverter;
import traben.flowing_fluids.FluidSectionDataCache;
import traben.flowing_fluids.FluidTickBuffer;
import traben.flowing_fluids.FluidSpatialGrid;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.FluidActivityTracker;
import traben.flowing_fluids.ParallelFluidEqualizer;
import traben.flowing_fluids.config.FFConfig;
import traben.flowing_fluids.optimization.HierarchicalDistanceManager;


import static traben.flowing_fluids.FFFluidUtils.getStateForFluidByAmount;


@Mixin(FlowingFluid.class)
public abstract class MixinFlowingFluid extends Fluid {




    @Unique
    private static short ffCacheKey(final BlockPos sourcePos, final BlockPos spreadPos) {
        int i = spreadPos.getX() - sourcePos.getX();
        int j = spreadPos.getZ() - sourcePos.getZ();
        return (short)((i + 128 & 255) << 8 | j + 128 & 255);
    }


    @Unique
    private static final ThreadLocal<Short2ObjectOpenHashMap<Pair<BlockState, FluidState>>> ff$STATE_CACHE =
            ThreadLocal.withInitial(Short2ObjectOpenHashMap::new);

    @Unique
    private static final ThreadLocal<Short2BooleanOpenHashMap> ff$FLOW_DOWN_CACHE =
            ThreadLocal.withInitial(Short2BooleanOpenHashMap::new);

    // Shared per-top-level slope search budget to prevent runaway recursion on vast flat surfaces.
    // Budget is set at the start of flowing_fluids$getValidDirectionFromDeepSpreadSearch and
    // consumed in flowing_fluids$getSlopeDistance.
    @Unique
    private static final ThreadLocal<int[]> ff$SLOPE_SEARCH_BUDGET =
            ThreadLocal.withInitial(() -> new int[1]);

    @Unique
    private static final ThreadLocal<Long2FloatOpenHashMap> ff$PRESSURE_HEAD_CACHE =
            ThreadLocal.withInitial(() -> {
                Long2FloatOpenHashMap cache = new Long2FloatOpenHashMap();
                cache.defaultReturnValue(Float.NaN);
                return cache;
            });

    @Unique
    private static final ThreadLocal<Long2IntOpenHashMap> ff$WATER_AMOUNT_CACHE =
            ThreadLocal.withInitial(() -> {
                Long2IntOpenHashMap cache = new Long2IntOpenHashMap();
                cache.defaultReturnValue(Integer.MIN_VALUE);
                return cache;
            });

    @Unique
    private static final int ff$SECTION_SAMPLE_THRESHOLD = 12;

    @Unique
    private static final ThreadLocal<FFSectionSampleContext> ff$SECTION_SAMPLE_CONTEXT =
            ThreadLocal.withInitial(FFSectionSampleContext::new);

    @Unique
    private static Short2ObjectOpenHashMap<Pair<BlockState, FluidState>> ff$getStateCache() {
        Short2ObjectOpenHashMap<Pair<BlockState, FluidState>> cache = ff$STATE_CACHE.get();
        cache.clear();
        return cache;
    }

    @Unique
    private static Short2BooleanOpenHashMap ff$getFlowDownCache() {
        Short2BooleanOpenHashMap cache = ff$FLOW_DOWN_CACHE.get();
        cache.clear();
        return cache;
    }

    @Unique
    private static Long2FloatOpenHashMap ff$getPressureHeadCache() {
        return ff$PRESSURE_HEAD_CACHE.get();
    }

    @Unique
    private static Long2IntOpenHashMap ff$getWaterAmountCache() {
        return ff$WATER_AMOUNT_CACHE.get();
    }

    @Unique
    private static FFSectionSampleContext ff$getSectionSampleContext() {
        return ff$SECTION_SAMPLE_CONTEXT.get();
    }

    @Unique
    private static boolean ff$handleWaterLoggedFlowAndReturnIfHandled(final Level level, final BlockPos posFrom, final FluidState fluidState, final int amount,
                                                                      final BlockState thisState, final BlockPos posTo, final int destFluidAmount,
                                                                      boolean flowingDown
    ) {
        //check if either too or from is water loggable and if so exit early if we cannot perform this flow due to settings
        boolean fromIsWaterloggableVanilla = thisState.getBlock() instanceof LiquidBlockContainer && thisState.getBlock() instanceof BucketPickup;
        boolean fromUsesVirtualFluidState = FFFluidUtils.supportsVirtualFluidState(level, thisState);
        boolean fromNeedsSpecialHandling = fromIsWaterloggableVanilla || fromUsesVirtualFluidState;
        if (fromIsWaterloggableVanilla
                && (flowingDown ? //cannot flow out
                FlowingFluids.config.waterLogFlowMode.blocksFlowOutDown()
                : FlowingFluids.config.waterLogFlowMode.blocksFlowOutSides())) {
            return true;
        }

        var blockToState = level.getBlockState(posTo);
        var blockTo = blockToState.getBlock();
        boolean toIsWaterloggableVanilla = blockTo instanceof LiquidBlockContainer && blockTo instanceof BucketPickup;
        boolean toUsesVirtualFluidState = FFFluidUtils.supportsVirtualFluidState(level, blockToState);
        boolean toNeedsSpecialHandling = toIsWaterloggableVanilla || toUsesVirtualFluidState;
        if (toIsWaterloggableVanilla && FlowingFluids.config.waterLogFlowMode.blocksFlowIn(flowingDown)) {//cannot flow in
            return true;
        }

        //from here the flow is allowed to proceed, but there is special handling for water loggables that might need to happen

        if (fromNeedsSpecialHandling || toNeedsSpecialHandling) {
            //here we are handling flow to or from a waterloggable block
            int totalAmount = destFluidAmount + amount;
            if (fromUsesVirtualFluidState || toUsesVirtualFluidState) {
                int cappedDestination = Math.min(8, totalAmount);
                int remainingSource = Math.max(0, totalAmount - cappedDestination);
                FFFluidUtils.setFluidStateAtPosToNewAmount(level, posFrom, fluidState.getType(), remainingSource);
                FFFluidUtils.setFluidStateAtPosToNewAmount(level, posTo, fluidState.getType(), cappedDestination);
                return true;
            }
            if (totalAmount < 8) { //crucial this only runs after we confirm they are waterloggables, as otherwise return should be false
                return true; //do nothing
            } else {
                //both should only be possible when flowing down
                if (toIsWaterloggableVanilla && fromIsWaterloggableVanilla) {
                    FFFluidUtils.setFluidStateAtPosToNewAmount(level, posFrom, fluidState.getType(), 0);
                    FFFluidUtils.setFluidStateAtPosToNewAmount(level, posTo, fluidState.getType(), 8);
                } else if (toIsWaterloggableVanilla) {
                    FFFluidUtils.setFluidStateAtPosToNewAmount(level, posFrom, fluidState.getType(), totalAmount - 8);
                    FFFluidUtils.setFluidStateAtPosToNewAmount(level, posTo, fluidState.getType(), 8);
                } else {//from
                    //don't flow out if destination cannot take all 8 levels of fluid
                    if (destFluidAmount > 0) return true;
                    FFFluidUtils.setFluidStateAtPosToNewAmount(level, posFrom, fluidState.getType(), 0);
                    FFFluidUtils.setFluidStateAtPosToNewAmount(level, posTo, fluidState.getType(), 8);
                }
            }
            return true;
        }
        //no water loggables
        return false;
    }

    @Override
    protected boolean isRandomlyTicking() {
        if (FlowingFluids.config.enableMod
                && FlowingFluids.config.isFluidAllowed(this))
            return true;
        return super.isRandomlyTicking();
    }

    @Override
    protected void randomTick(final #if MC > MC_21 ServerLevel #else Level #endif level, final BlockPos pos, final FluidState state, final RandomSource random) {
        super.randomTick(level, pos, state, random);
        //random settle behaviour
        if (FlowingFluids.config.enableMod
                && FlowingFluids.config.randomTickLevelingDistance > 0
                && level.getChunkAt(pos).getFluidTicks().count() < 16 //ignore chunks with many updating fluids
                && FlowingFluids.config.isFluidAllowed(this)
                && !level.getFluidState(pos.above()).getType().isSame(this)//don't settle if there is a fluid above
        ) {
            //search in a random direction up to 32 blocks for a lower fluid to level out with

            final int amount = state.getAmount();
            if (amount <= getDropOff(level)) return;

            final int amountLess = amount - 1;

            Direction[] shuffled = FFFluidUtils.getCardinalsShuffle(random);
            final Direction randomDirection = shuffled[0];

            boolean straightOnly = random.nextBoolean();
            Direction offStep = randomDirection;
            if (!straightOnly) {
                offStep = random.nextBoolean() ? randomDirection.getClockWise() : randomDirection.getCounterClockWise();
            }

            final BlockPos.MutableBlockPos movingDir = pos.mutable();
            final BlockPos.MutableBlockPos movingDirAbove = pos.above().mutable();

            for (int i = 0; i < FlowingFluids.config.randomTickLevelingDistance; i++) {
                Direction step = straightOnly ? randomDirection : (random.nextBoolean() ? randomDirection : offStep);
                movingDir.move(step);
                movingDirAbove.move(step);

                var stateDir = level.getBlockState(movingDir);
                if (!(stateDir.getBlock() instanceof LiquidBlock)) return;

                var fluidStateDir = stateDir.getFluidState();
                if (!fluidStateDir.getType().isSame(this)) return;

                if (level.getFluidState(movingDirAbove).getType().isSame(this)) return;

                int amountDir = fluidStateDir.getAmount();
                if (amountDir > amount) return;

                if (amountDir < amountLess) {
                    FFFluidUtils.setFluidStateAtPosToNewAmount(level, movingDir, this, amountDir + 1);
                    FFFluidUtils.setFluidStateAtPosToNewAmount(level, pos, this, amountLess);
                    return;
                }
                //continue;
            }
        }
    }

    @Shadow
    protected abstract int getDropOff(final LevelReader levelReader);

    @Shadow
    protected abstract void spreadTo(final LevelAccessor levelAccessor, final BlockPos blockPos, final BlockState blockState, final Direction direction, final FluidState fluidState);

    @Shadow
    protected abstract int getSlopeFindDistance(final LevelReader levelReader);


//    @Inject(method = "getFlow", at = @At(value = "HEAD"), cancellable = true)
//    private void ff$hideFlowingTexture(final BlockGetter blockReader, final BlockPos pos, final FluidState fluidState, final CallbackInfoReturnable<Vec3> cir) {
//        if (RenderSystem.isOnRenderThread()
//                && FlowingFluids.config.enableMod
//                && FlowingFluids.config.hideFlowingTexture) {
//            cir.setReturnValue(Vec3.ZERO);
//        }
//    }

    @Shadow
    public abstract int getAmount(final FluidState state);


    @Inject(method = "getOwnHeight", at = @At(value = "HEAD"), cancellable = true)
    private void ff$differentRenderHeight(final FluidState state, final CallbackInfoReturnable<Float> cir) {
        if (FlowingFluids.config.enableMod
                && FlowingFluids.config.isFluidAllowed(state)
                && FlowingFluids.config.fullLiquidHeight != FFConfig.LiquidHeight.REGULAR) {
            cir.setReturnValue(
                    switch (FlowingFluids.config.fullLiquidHeight) {
                        case BLOCK -> state.getAmount() / 8F;
                        case SLAB -> state.getAmount() / 16F;
                        case CARPET -> 0.0625f;
                        case REGULAR_LOWER_BOUND -> (state.getAmount() - 0.9F) * (8.0F / 9.0F) / 7.0F;
                        case BLOCK_LOWER_BOUND -> (state.getAmount() - 0.9F) / 7.0F;
                        default -> state.getAmount() / 9.0F;
                    }
            );
        }
    }

    @Inject(method = "tick", at = @At(value = "HEAD"), cancellable = true)
    private void ff$tickMixin(final #if MC > MC_21 ServerLevel #else Level #endif level, final BlockPos blockPos,#if MC > MC_21 BlockState thisState, #endif final FluidState fluidState, final CallbackInfo ci) {
        if (FlowingFluids.config.enableMod
                && FlowingFluids.config.isFluidAllowed(fluidState)) {
            // cancel the original tick
            ci.cancel();

            if (FlowingFluids.config.dontTickAtLocation(blockPos, level)) {
                level.scheduleTick(blockPos, this, 200 + level.random.nextInt(200)); // 10 - 20 seconds delay
                return; // do not calculate and delay the tick
            }

            if (System.currentTimeMillis() < FlowingFluids.debug_killFluidUpdatesUntilTime) {
                return; // kill this update
            }

            FlowingFluids.isManeuveringFluids = true;
            ff$getPressureHeadCache().clear();
            ff$getWaterAmountCache().clear();
            ff$getSectionSampleContext().begin(level);

            boolean withinInfBiomeHeights = FlowingFluids.config.fastBiomeRefillAtSeaLevelOnly
                    ? level.getSeaLevel() == blockPos.getY() || level.getSeaLevel() - 1 == blockPos.getY()
                    : level.getSeaLevel() == blockPos.getY() && blockPos.getY() > 0;

            boolean isWaterAndInfiniteBiome = fluidState.is(FluidTags.WATER)
                    && withinInfBiomeHeights
                    && FFFluidUtils.matchInfiniteBiomes(level.getBiome(blockPos))
                    && level.getBrightness(LightLayer.SKY, blockPos) > 0;

            boolean dontConsumeWater = isWaterAndInfiniteBiome
                    && level.getSeaLevel() != blockPos.getY()
                    && level.getRandom().nextFloat() < FlowingFluids.config.infiniteWaterBiomeNonConsumeChance;

            #if MC <= MC_21
            BlockState thisState = level.getBlockState(blockPos);
            #endif

            try {
                if (fluidState.is(FluidTags.WATER)) {
                    int internalAmount = FluidSpatialGrid.getFluidAmount(level, blockPos);
                    if (internalAmount <= 0) {
                        internalAmount = FluidAmountConverter.toInternal(fluidState.getAmount());
                    }

                    if (!AdaptiveTickScheduler.shouldTick(level, blockPos, internalAmount)) {
                        int baseDelay = getTickDelay(level);
                        int adaptiveDelay = AdaptiveTickScheduler.getAdaptiveDelay(level, blockPos, internalAmount, baseDelay);
                        level.scheduleTick(blockPos, this, Math.max(1, adaptiveDelay));
                        return;
                    }
                }

                BlockPos posDown = blockPos.below();
                // check if we can flow down and if so how much fluid remains out of the 8 total possible
                FFFlowDownResult flowDownResult = flowing_fluids$checkAndFlowDown(level, blockPos, fluidState, thisState, posDown,
                        level.getBlockState(posDown), fluidState.getAmount());

                int remainingAmount = flowDownResult.remainingAmount();

                // if there is remaining amount still, the block below is full, or we couldn't flow down so also flow to the sides
                if (remainingAmount <= 0) {
                    return;
                }
                if (flowDownResult.skipHorizontalSpread()) {
                    return;
                }

                // Enhanced pressure-based fast path algorithm
                // This optimization detects common patterns and skips expensive slope calculations
                if (fluidState.getAmount() == 8 && thisState.liquid()) { // not messing with waterloggables here
                    BlockPos abovePos = blockPos.above();
                    var above = level.getBlockState(abovePos);
                    if (above.liquid()) { // not messing with waterloggables here
                        var aboveF = above.getFluidState();
                        int aboveAmount = aboveF.getAmount();
                        if (aboveAmount >= 8){
                            var flow = (FlowingFluid) aboveF.getType();
                            if (FFFluidUtils.canFluidFlowFromPosToDirectionFitOverride(flow, level, abovePos, above, Direction.DOWN, blockPos, thisState)) {
                                var remainder = FFFluidUtils.placeConnectedFluidAmountAndPlaceAction(level, blockPos, aboveAmount,
                                        flow, 40, false, !FlowingFluids.pistonTick);
                                if (remainder.first() < aboveAmount) {
                                    remainder.second().run();
                                    if (!dontConsumeWater) FFFluidUtils.setFluidStateAtPosToNewAmount(level, abovePos, flow, remainder.first());
                                    return;
                                }
                            }
                        }
                    }
                }

                // Extended fast path: detect fluid column patterns (vertical stack optimization)
                // If we're a full block with more full blocks above, we can often skip horizontal flow checks
                if (fluidState.getAmount() == 8 && thisState.liquid() && remainingAmount == 8) {
                    boolean hasFluidAbove = false;
                    BlockPos abovePos = blockPos.above();
                    for (int i = 0; i < 3; i++) { // Check up to 3 blocks above
                        var aboveState = level.getFluidState(abovePos);
                        if (aboveState.getType().isSame(this) && aboveState.getAmount() == 8) {
                            hasFluidAbove = true;
                            break;
                        }
                        abovePos = abovePos.above();
                    }

                    if (hasFluidAbove) {
                        // We're in a vertical column - check if all horizontal neighbors are also full
                        boolean allNeighborsFull = true;
                        for (Direction dir : Direction.Plane.HORIZONTAL) {
                            var neighborPos = blockPos.relative(dir);
                            var neighborFluid = level.getFluidState(neighborPos);
                            if (!neighborFluid.getType().isSame(this) || neighborFluid.getAmount() < 8) {
                                allNeighborsFull = false;
                                break;
                            }
                        }

                        if (allNeighborsFull) {
                            // We're in a stable pool - no need to flow horizontally
                            // Skip the expensive horizontal flow calculations
                            return;
                        }
                    }
                }

                // if there is still water left, flow to the sides only if it is above the drop-off amount
                // the drop-off amount is the vanilla value determining how much each block of flow reduces the amount
                // this ties in nicely with a sort of surface tension effect
                boolean retainedMinimumForDropOff = flowDownResult.retainedMinimum();

            if (remainingAmount > getDropOff(level) || retainedMinimumForDropOff) {//drop off is 1 for water, 2 for lava in the overworld
                    ff$flowToSides(level, blockPos, fluidState, remainingAmount, thisState,
                            retainedMinimumForDropOff ? getDropOff(level) : 0);//, remainingAmount);
            } else if (FlowingFluids.config.flowToEdges) {
                    if (flowing_fluids$shouldSuppressThinCapDrift(level, blockPos, fluidState, remainingAmount)) {
                        flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, remainingAmount, true);
                        return;
                    }
                    // if the remaining amount is less than the drop-off amount, we can still flow to the sides but only if
                    // we find a nearby ledge to flow towards, as we want this water to settle when on flat ground
                    // use 1 as the amount as we don't spread to lower values than the drop-off, so we only want empty destination tiles
                    Direction dir = remainingAmount <= 1
                            ? flowing_fluids$getImmediateThinEdgeDrop(level, blockPos, fluidState, thisState)
                            : flowing_fluids$getLowestSpreadableLookingFor4BlockDrops(level, blockPos, fluidState, 1, true);

                    // dir is null if no spreadable block was found
                    if (dir != null) {
                        // much simpler logic than flowing_fluids$flowToSides() as we are only flowing our total remaining value into an empty space
                var pos = blockPos.relative(dir);
                flowing_fluids$setOrRemoveWaterAmountAt(level, blockPos, 0, thisState, dir);
                flowing_fluids$spreadTo2(level, pos, level.getBlockState(pos), dir, remainingAmount);
                flowing_fluids$invalidateFluidSampleCaches(blockPos, pos);
                if (fluidState.is(FluidTags.WATER) && FlowingFluids.config.flowInertiaStrength > 0f) {
                    float momentum = Mth.clamp(remainingAmount / 8.0f, 0.35f, 1.0f);
                    AdaptiveTickScheduler.recordFlowDirection(level, blockPos, dir, momentum);
                }
            }
                }



            } finally {

                if (isWaterAndInfiniteBiome) {
                    if (level.getSeaLevel() == blockPos.getY()) {
                        if (level.getRandom().nextFloat() < FlowingFluids.config.infiniteWaterBiomeDrainSurfaceChance) {
                            var amount = level.getFluidState(blockPos).getAmount();
                            if (FFFluidUtils.shouldDrainInfiniteBiomeSurface(level, blockPos, this, amount)) {
                                FFFluidUtils.changeFluidAmountAtPos(level, blockPos, this, -1);
                            }
                        }
                    } else if (dontConsumeWater) {
                        // if we are in a truly infinite biome, we need to set this back to the original state
                        // as we don't want to lose water in these biomes
                        FFFluidUtils.setFluidStateAtPosToNewAmount(level, blockPos, fluidState.getType(), fluidState.getAmount());
                    }
                }

                ff$getSectionSampleContext().end();
                FlowingFluids.isManeuveringFluids = false;
                FlowingFluids.pistonTick = false;
            }
        }

    }

    @Unique
    private void ff$flowToSides(final Level level, final BlockPos blockPos, final FluidState fluidState, int amount, final BlockState thisState, int minimumRetainedAmount) {

        // get a valid direction to move into or null if no spreadable block was found
        Direction dir = flowing_fluids$getLowestSpreadableLookingFor4BlockDrops(level, blockPos, fluidState, amount, false);
        if (dir == null) {
            flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, amount, true);
            return;
        }

        var posDir = blockPos.relative(dir);

        // this amount is already confirmed to be less than {amount}
        final int destFluidAmount = FFFluidUtils.getEffectiveFluidState(level, posDir, level.getBlockState(posDir)).getAmount();

        // If we retained a minimum (drop-off) amount for downward flow, allow leveling without draining below it.
        int combinedTotal = amount + destFluidAmount;

        // must force total flow of fluid because of waterloggables
        if (ff$handleWaterLoggedFlowAndReturnIfHandled(level, blockPos, fluidState, amount, thisState, posDir, destFluidAmount, false))
            return;

        int fromAmount;
        int toAmount;


        // calculate the amount that would level both liquids
        final int difference = amount - destFluidAmount;
        final int averageLevel = destFluidAmount + difference / 2;
        final float sourcePressureHead = flowing_fluids$getPressureHead(level, blockPos, fluidState.getType(), amount);
        final float destPressureHead = flowing_fluids$getPressureHead(level, posDir, fluidState.getType(), destFluidAmount);
        final float pressureHeadDelta = Math.max(0.0f, sourcePressureHead - destPressureHead);

        if (fluidState.is(FluidTags.WATER)
                && flowing_fluids$shouldSuppressStablePoolTransfer(level, blockPos, amount, posDir, destFluidAmount, difference, pressureHeadDelta)) {
            flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, amount, true);
            flowing_fluids$updateStablePoolTracking(level, posDir, fluidState, destFluidAmount, true);
            return;
        }

        if (flowing_fluids$shouldSuppressShallowFlatTransfer(level, blockPos, fluidState, amount, posDir,
                destFluidAmount, difference, pressureHeadDelta, minimumRetainedAmount)) {
            flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, amount, true);
            if (destFluidAmount > 0) {
                flowing_fluids$updateStablePoolTracking(level, posDir, fluidState, destFluidAmount, true);
            }
            return;
        }

        // if the difference is odd, we need to add 1 to the 'from' amount
        boolean hasRemainder = (difference % 2 != 0);

        fromAmount = averageLevel;
        if (hasRemainder) {
            toAmount = averageLevel + 1;
        } else {
            toAmount = averageLevel;
        }

        if (minimumRetainedAmount > 0) {
            // keep at least the retained portion on the source to maintain drop-off support while still equalizing
            int adjustedFrom = Math.max(fromAmount, minimumRetainedAmount);
            int adjustedTo = combinedTotal - adjustedFrom;
            if (adjustedTo < 0) {
                adjustedTo = 0;
                adjustedFrom = combinedTotal;
            }
            fromAmount = adjustedFrom;
            toAmount = adjustedTo;
        }

        if (difference > 0 && FlowingFluids.config.pressureFlowBonusStrength > 0f) {
            int available = Math.max(0, fromAmount - minimumRetainedAmount);
            int bonus = Math.round(pressureHeadDelta * FlowingFluids.config.pressureFlowBonusStrength);
            int transferable = Math.min(available, bonus);
            int room = 8 - toAmount;
            int actual = Math.min(transferable, room);
            if (actual > 0) {
                fromAmount -= actual;
                toAmount += actual;
            }
        }

        boolean changed = fromAmount != amount || toAmount != destFluidAmount;
        flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, fromAmount, !changed);
        flowing_fluids$updateStablePoolTracking(level, posDir, fluidState, toAmount, !changed);

        FFFluidUtils.setFluidStateAtPosToNewAmount(level, blockPos, fluidState.getType(), fromAmount);
        FFFluidUtils.setFluidStateAtPosToNewAmount(level, posDir, fluidState.getType(), toAmount);
        flowing_fluids$invalidateFluidSampleCaches(blockPos, posDir);
        if (fluidState.is(FluidTags.WATER) && FlowingFluids.config.flowInertiaStrength > 0f) {
            if (changed) {
                int moved = Math.max(0, amount - fromAmount);
                float momentum = Mth.clamp((moved / 4.0f) + (pressureHeadDelta / 16.0f), 0.2f, 1.0f);
                AdaptiveTickScheduler.recordFlowDirection(level, blockPos, dir, momentum);
            }
        }
    }

    @Unique
    private @Nullable Direction flowing_fluids$getImmediateThinEdgeDrop(Level level, BlockPos origin, FluidState fluidState, BlockState originState) {
        Fluid sourceFluid = fluidState.getType();
        boolean applyBias = fluidState.is(FluidTags.WATER);
        float affinityStrength = applyBias ? Math.max(0f, FlowingFluids.config.waterAffinityStrength) : 0f;
        float inertiaStrength = applyBias ? Math.max(0f, FlowingFluids.config.flowInertiaStrength) : 0f;
        Direction inertiaDir = null;
        float inertiaMomentum = 0f;
        if (applyBias && inertiaStrength > 0f && FlowingFluids.config.flowInertiaMaxAgeTicks > 0) {
            inertiaDir = AdaptiveTickScheduler.getFlowInertiaDirection(level, origin, FlowingFluids.config.flowInertiaMaxAgeTicks);
            inertiaMomentum = AdaptiveTickScheduler.getFlowMomentum(level, origin, FlowingFluids.config.flowInertiaMaxAgeTicks);
        }

        float sourcePressureHead = applyBias
                ? flowing_fluids$getPressureHead(level, origin, sourceFluid, fluidState.getAmount())
                : 0f;
        float effectiveInertia = inertiaStrength * Math.max(0.15f, inertiaMomentum);
        Direction bestDirection = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        BlockPos.MutableBlockPos sidePos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos belowPos = new BlockPos.MutableBlockPos();

        for (Direction dir : FFFluidUtils.getCardinalsShuffle(level.random)) {
            sidePos.setWithOffset(origin, dir);
            BlockState sideState = level.getBlockState(sidePos);
            FluidState sideFluid = FFFluidUtils.getEffectiveFluidState(level, sidePos, sideState);
            if (!sideFluid.isEmpty()) {
                continue;
            }
            if (!flowing_fluids$canSpreadToOptionallySameOrEmpty(sourceFluid, 1, level, origin, originState,
                    dir, sidePos, sideState, sideFluid, true)) {
                continue;
            }

            belowPos.set(sidePos).move(Direction.DOWN);
            BlockState belowState = level.getBlockState(belowPos);
            FluidState belowFluid = FFFluidUtils.getEffectiveFluidState(level, belowPos, belowState);
            if (!flowing_fluids$canSpreadToOptionallySameOrEmpty(sourceFluid, 8, level, sidePos, sideState,
                    Direction.DOWN, belowPos, belowState, belowFluid, true)) {
                continue;
            }

            float score = applyBias
                    ? flowing_fluids$getDirectionBias(level, origin, dir, sourceFluid, 0, sourcePressureHead,
                    inertiaDir, affinityStrength, effectiveInertia)
                    : 0f;
            if (score > bestScore) {
                bestScore = score;
                bestDirection = dir;
            }
        }

        return bestDirection;
    }



    @Unique
    private FFFlowDownResult flowing_fluids$checkAndFlowDown(final Level level, final BlockPos blockPos, final FluidState fluidState, final BlockState thisState, final BlockPos posDown, final BlockState stateDown, int amount) {
        FFDownwardFlowTarget downwardTarget = flowing_fluids$resolveDownwardFlowTarget(level, posDown, stateDown, fluidState.getType());
        BlockPos actualPosDown = downwardTarget.targetPos();
        BlockState actualStateDown = downwardTarget.targetState();
        FluidState downFState = downwardTarget.targetFluidState();

        boolean canEnterDownwardPath = true;
        if (downwardTarget.skippedPassThrough()) {
            FluidState immediateDownFluid = FFFluidUtils.getEffectiveFluidState(level, posDown, stateDown);
            canEnterDownwardPath = flowing_fluids$canSpreadTo(fluidState.getType(), fluidState.getAmount(), level, blockPos, thisState,
                    Direction.DOWN, posDown, stateDown, immediateDownFluid)
                    && flowing_fluids$canSpreadTo(fluidState.getType(), fluidState.getAmount(), level,
                    downwardTarget.conduitPos(), downwardTarget.conduitState(), Direction.DOWN,
                    actualPosDown, actualStateDown, downFState);
        }
        // check and then handle if we can flow down
        if (canEnterDownwardPath && flowing_fluids$canSpreadTo(fluidState.getType(), fluidState.getAmount(), level, blockPos, thisState,
                Direction.DOWN, actualPosDown, actualStateDown, downFState)) {

            // handle other liquid vanilla collisions by causing a flow
            if (!downFState.isEmpty() && !downFState.getType().isSame(fluidState.getType())) {
                // send like vanilla flow to perform fluid collision
                // only use 1 for the amount, as we are only checking the collision behaviour
                // example: lava flowing down onto water creates stone in this case
                flowing_fluids$setOrRemoveWaterAmountAt(level, blockPos, amount - 1, thisState, Direction.DOWN);
                flowing_fluids$spreadTo2(level, actualPosDown, actualStateDown, Direction.DOWN, 1);
                return new FFFlowDownResult(amount - 1, false, false);
            } else {
                if (FlowingFluids.config.easyPistonPump && FlowingFluids.config.enablePistonPushing) {
                    // check if an upwards piston is present one block further below, and is still moving, and delay this tick
                    var block = level.getBlockState(actualPosDown.below());
                    if (block.is(Blocks.MOVING_PISTON) && block.getValue(DirectionalBlock.FACING) == Direction.UP) {
                        // delay this tick
                        level.scheduleTick(blockPos, this, 10);
                        FlowingFluids.pistonTick = true;
                        return new FFFlowDownResult(amount, false, false);
                    }
                }

                // flow into lower space
                int fluidDownAmount = downFState.getAmount();

                if (ff$handleWaterLoggedFlowAndReturnIfHandled(level, blockPos, fluidState, amount, thisState, actualPosDown, fluidDownAmount, true))
                    return new FFFlowDownResult(level.getFluidState(blockPos).getAmount(), false, false);

                int amountDestCanAccept = Math.min(8 - fluidDownAmount, amount);

                boolean retainedMinimum = false;
                boolean skipHorizontalSpread = false;
                // Avoid draining the entire source when falling into an empty air column.
                // Leaving at least the drop-off amount in the source keeps lateral equalization active,
                // preventing the upstream section of a canal from staying permanently overfilled.
                if (fluidDownAmount == 0 && actualStateDown.isAir() && amountDestCanAccept == amount) {
                    if (flowing_fluids$hasRetentionAnchor(level, blockPos, fluidState)) {
                        int retained = getDropOff(level);
                        if (FlowingFluids.config.downwardPressureStrength > 0f
                                && FlowingFluids.config.downwardPressureMaxColumn > 0) {
                            int column = flowing_fluids$getColumnHeight(level, blockPos, fluidState.getType(),
                                    FlowingFluids.config.downwardPressureMaxColumn);
                            int bonus = Math.round(column * FlowingFluids.config.downwardPressureStrength);
                            retained = Math.max(0, retained - bonus);
                        }
                        if (retained > 0 && amount > retained) {
                            amountDestCanAccept = amount - retained;
                            retainedMinimum = true;
                        }
                    }
                }
                // can fit some liquid
                if (amountDestCanAccept > 0) {
                    int destNewAmount = fluidDownAmount + amountDestCanAccept;
                    int sourceNewAmount = amount - amountDestCanAccept;
                    // Keep a tiny column when cascading fast so the stream doesn't visually break.
                    if (sourceNewAmount == 0 && amount > 0) {
                        var aboveState = level.getFluidState(blockPos.above());
                        if (aboveState.getType().isSame(fluidState.getType()) && aboveState.getAmount() > 0) {
                            int reserve = Math.min(getDropOff(level), amount);
                            sourceNewAmount = reserve;
                            destNewAmount = Math.max(0, destNewAmount - reserve);
                        }
                    }
                    if (sourceNewAmount == 0
                            && flowing_fluids$shouldKeepThinFallingSource(level, blockPos, fluidState, amount,
                            actualPosDown, actualStateDown, fluidDownAmount)) {
                        sourceNewAmount = Math.min(Math.max(1, getDropOff(level)), amount);
                        skipHorizontalSpread = true;
                    }
                    // set both amounts
                    flowing_fluids$setOrRemoveWaterAmountAt(level, blockPos, sourceNewAmount, thisState, Direction.DOWN);
                    flowing_fluids$spreadTo2(level, actualPosDown, actualStateDown, Direction.DOWN, destNewAmount);
                    return new FFFlowDownResult(sourceNewAmount, retainedMinimum, skipHorizontalSpread);
                }
            }
        }
        // return the remaining amount of the source liquid
        return new FFFlowDownResult(amount, false, false);
    }

    @Unique
    private FFDownwardFlowTarget flowing_fluids$resolveDownwardFlowTarget(Level level, BlockPos posDown, BlockState stateDown, Fluid sourceFluid) {
        FluidState initialFluid = FFFluidUtils.getEffectiveFluidState(level, posDown, stateDown);
        if (!FFFluidUtils.isPassThroughFluidBlock(level, stateDown, Direction.DOWN) || !initialFluid.isEmpty()) {
            return new FFDownwardFlowTarget(posDown, stateDown, initialFluid, posDown, stateDown, false);
        }

        BlockPos.MutableBlockPos cursor = posDown.mutable();
        BlockState cursorState = stateDown;
        final int maxPassThroughDepth = Math.max(1, FlowingFluids.config.maxWaterFlowDistance);

        for (int depth = 0; depth < maxPassThroughDepth; depth++) {
            BlockPos belowPos = cursor.below();
            BlockState belowState = level.getBlockState(belowPos);
            FluidState belowFluid = FFFluidUtils.getEffectiveFluidState(level, belowPos, belowState);
            if (FFFluidUtils.isPassThroughFluidBlock(level, belowState, Direction.DOWN) && belowFluid.isEmpty()) {
                cursor.set(belowPos);
                cursorState = belowState;
                continue;
            }
            return new FFDownwardFlowTarget(belowPos.immutable(), belowState, belowFluid, cursor.immutable(), cursorState, true);
        }

        return new FFDownwardFlowTarget(posDown, stateDown, initialFluid, posDown, stateDown, false);
    }

    @Unique
    private void flowing_fluids$setOrRemoveWaterAmountAt(final Level level, final BlockPos blockPos, final int amount, final BlockState thisState, Direction direction) {
        if (amount > 0) {
            flowing_fluids$spreadTo2(level, blockPos, thisState, direction, amount);
        } else {
            FFFluidUtils.removeAllFluidAtPos(level, blockPos, this);
        }
    }

    @Unique
    private boolean flowing_fluids$hasRetentionAnchor(Level level, BlockPos origin, FluidState sourceState) {
        Fluid sourceFluid = sourceState.getType();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        mutablePos.set(origin).move(Direction.UP);
        FluidState above = FFFluidUtils.getEffectiveFluidState(level, mutablePos, level.getBlockState(mutablePos));
        if (above.getType().isSame(sourceFluid) && above.getAmount() > 0) {
            return true;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            mutablePos.set(origin).move(dir);
            FluidState neighbor = FFFluidUtils.getEffectiveFluidState(level, mutablePos, level.getBlockState(mutablePos));
            if (neighbor.getType().isSame(sourceFluid) && neighbor.getAmount() > 0) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private boolean flowing_fluids$shouldKeepThinFallingSource(Level level, BlockPos sourcePos, FluidState sourceState,
                                                               int sourceAmount, BlockPos targetPos,
                                                               BlockState targetState, int targetAmount) {
        if (!sourceState.is(FluidTags.WATER)) {
            return false;
        }
        int thinAmountCap = Math.max(1, getDropOff(level));
        if (sourceAmount <= 0 || sourceAmount > thinAmountCap) {
            return false;
        }
        if (targetAmount != 0 || !targetState.isAir()) {
            return false;
        }
        if (!flowing_fluids$hasRetentionAnchor(level, sourcePos, sourceState)) {
            return false;
        }

        BlockPos belowTarget = targetPos.below();
        BlockState belowTargetState = level.getBlockState(belowTarget);
        FluidState belowTargetFluid = FFFluidUtils.getEffectiveFluidState(level, belowTarget, belowTargetState);
        if (belowTargetFluid.getType().isSame(sourceState.getType()) && belowTargetFluid.getAmount() > 0) {
            return true;
        }
        return belowTargetFluid.isEmpty()
                && (belowTargetState.isAir()
                || belowTargetState.canBeReplaced(sourceState.getType())
                || FFFluidUtils.isPassThroughFluidBlock(level, belowTargetState, Direction.DOWN));
    }

    @Unique
    private int flowing_fluids$getColumnHeight(Level level, BlockPos origin, Fluid sourceFluid, int maxScan) {
        if (maxScan <= 0) {
            return 0;
        }
        int height = 0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        mutablePos.set(origin);
        while (height < maxScan) {
            mutablePos.move(Direction.UP);
            FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, mutablePos, level.getBlockState(mutablePos));
            if (!fluidState.getType().isSame(sourceFluid) || fluidState.getAmount() <= 0) {
                break;
            }
            height++;
        }
        return height;
    }

    @Inject(method = "getNewLiquid", at = @At(value = "HEAD"), cancellable = true)
    private void flowing_fluids$validateLiquidMixin(final #if MC > MC_21 ServerLevel #else Level #endif level, final BlockPos blockPos, final BlockState blockState, final CallbackInfoReturnable<FluidState> cir) {
        if (FlowingFluids.config.enableMod
                && FlowingFluids.config.isFluidAllowed(this)) {
            var state = level.getFluidState(blockPos);
            cir.setReturnValue(getStateForFluidByAmount(state.getType(), state.getAmount()));
        }
    }

    @Unique
    private @Nullable Direction flowing_fluids$getLowestSpreadableLookingFor4BlockDrops(
            Level level, BlockPos blockPos, FluidState fluidState, int amount, final boolean requiresSlope) {

        Short2ObjectOpenHashMap<Pair<BlockState, FluidState>> statesAtPos = ff$getStateCache();
        try {
            Direction[] shuffled = FFFluidUtils.getCardinalsShuffle(level.random);
            Direction[] validDirections = new Direction[shuffled.length];
            int[] neighbourAmounts = new int[shuffled.length];
            int validCount = 0;
            boolean anyFlowableNeighbours2LevelsLowerOrMore = requiresSlope;
            BlockState sourceState = level.getBlockState(blockPos);
            BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ());
            Direction immediateLowDir = null;

            int forcedDifference = 2;
            if (fluidState.is(FluidTags.WATER) && FlowingFluids.config.forceFlowLevelDifference > 0) {
                forcedDifference = FlowingFluids.config.forceFlowLevelDifference;
            }

            for (Direction direction : shuffled) {
                mutablePos.set(blockPos.getX(), blockPos.getY(), blockPos.getZ());
                mutablePos.move(direction);
                short key = ffCacheKey(blockPos, mutablePos);
                Pair<BlockState, FluidState> statesDir = flowing_fluids$getSetPosCache(key, level, statesAtPos, mutablePos);
                BlockState stateDir = statesDir.getFirst();
                FluidState fluidStateDir = statesDir.getSecond();
                int amountDir = fluidStateDir.getAmount();
                boolean canFlow = flowing_fluids$canSpreadToOptionallySameOrEmpty(fluidState.getType(), amount, level, blockPos,
                        sourceState, direction, mutablePos, stateDir, fluidStateDir, requiresSlope);
                if (canFlow) {
                    if (!anyFlowableNeighbours2LevelsLowerOrMore) {
                        anyFlowableNeighbours2LevelsLowerOrMore = amountDir < amount - 1;
                    }
                    // Early fast-path: if we already found a neighbour 2+ levels lower, flow there without deep search.
                    if (amountDir <= amount - forcedDifference) {
                        immediateLowDir = direction;
                        break;
                    }
                    validDirections[validCount] = direction;
                    neighbourAmounts[validCount] = amountDir;
                    validCount++;
                }
            }

            if (immediateLowDir != null) {
                return immediateLowDir;
            }

            if (validCount == 0) {
                return null;
            }
            if (validCount == 1) {
                return validDirections[0];
            }

            for (int i = 0; i < validCount - 1; i++) {
                int minIndex = i;
                for (int j = i + 1; j < validCount; j++) {
                    if (neighbourAmounts[j] < neighbourAmounts[minIndex]) {
                        minIndex = j;
                    }
                }
                if (minIndex != i) {
                    int tmpAmount = neighbourAmounts[i];
                    neighbourAmounts[i] = neighbourAmounts[minIndex];
                    neighbourAmounts[minIndex] = tmpAmount;
                    Direction tmpDirection = validDirections[i];
                    validDirections[i] = validDirections[minIndex];
                    validDirections[minIndex] = tmpDirection;
                }
            }

            boolean requiresSlopeWithOverride = requiresSlope || !anyFlowableNeighbours2LevelsLowerOrMore;

            Direction spreadDirection = flowing_fluids$getValidDirectionFromDeepSpreadSearch(level, blockPos, fluidState, amount,
                    requiresSlopeWithOverride, validDirections, neighbourAmounts, validCount, statesAtPos);

            if (spreadDirection == null && !requiresSlopeWithOverride) {
                boolean applyBias = fluidState.is(FluidTags.WATER);
            float affinityStrength = applyBias ? Math.max(0f, FlowingFluids.config.waterAffinityStrength) : 0f;
            float inertiaStrength = applyBias ? Math.max(0f, FlowingFluids.config.flowInertiaStrength) : 0f;
            Direction inertiaDir = null;
            float inertiaMomentum = 0f;
            if (applyBias && inertiaStrength > 0f && FlowingFluids.config.flowInertiaMaxAgeTicks > 0) {
                inertiaDir = AdaptiveTickScheduler.getFlowInertiaDirection(level, blockPos, FlowingFluids.config.flowInertiaMaxAgeTicks);
                inertiaMomentum = AdaptiveTickScheduler.getFlowMomentum(level, blockPos, FlowingFluids.config.flowInertiaMaxAgeTicks);
            }
            if (affinityStrength > 0f || inertiaStrength > 0f || FlowingFluids.config.pressureFlowBonusStrength > 0f) {
                Direction biased = flowing_fluids$pickBiasedDirection(level, blockPos, fluidState.getType(),
                        validDirections, neighbourAmounts, validCount, amount, inertiaDir, affinityStrength,
                        inertiaStrength * Math.max(0.15f, inertiaMomentum));
                if (biased != null) {
                    return biased;
                }
                }
                return validDirections[0];
            }
            return spreadDirection;
        } finally {
            statesAtPos.clear();
        }
    }


    @Unique
    private @Nullable Direction flowing_fluids$getValidDirectionFromDeepSpreadSearch(final Level level, final BlockPos blockPos, final FluidState fluidState, final int amount, final boolean requiresSlope, final Direction[] directionsCanSpreadToSortedByAmount, final int[] directionAmounts, final int directionCount, final Short2ObjectOpenHashMap<Pair<BlockState, FluidState>> statesAtPos) {

        int slopeFindDistance = getSlopeFindDistance(level);
        if (slopeFindDistance < 1) return null;
        if (requiresSlope && flowing_fluids$shouldSuppressExploratorySpread(level, blockPos, fluidState, amount)) {
            return null;
        }

        // Keep full slope search distance even for low fluid amounts so ledges further away
        // are still discovered. Reducing this distance to half (as before) limited searches
        // to 2 blocks for thin streams, making water ignore nearby drops.
        int adaptiveSlopeFindDistance = slopeFindDistance;
        if (fluidState.is(FluidTags.WATER)) {
            int effectiveDistance = slopeFindDistance;
            if (FlowingFluids.config.enableAdaptiveFlowDistance) {
                HierarchicalDistanceManager manager = HierarchicalDistanceManager.getInstance();
                HierarchicalDistanceManager.TerrainType terrainType = manager.estimateTerrainType(blockPos, level);
                effectiveDistance = manager.getEffectiveFlowDistance(effectiveDistance, terrainType, FlowingFluids.config);

                var biome = level.getBiome(blockPos);
                boolean broadWaterBiome = FFFluidUtils.isOceanBiome(biome) || FFFluidUtils.isBeachBiome(biome);
                if (broadWaterBiome && blockPos.getY() >= level.getSeaLevel() - 2) {
                    int oceanClamp = Math.max(2, Math.min(FlowingFluids.config.waterFlowDistance,
                            Math.max(1, FlowingFluids.config.maxWaterFlowDistance)));
                    effectiveDistance = Math.min(effectiveDistance, oceanClamp);
                }
            }

            float multiplier = FlowingFluids.config.slopeFindDistanceMultiplier;
            if (multiplier <= 0.0f) {
                multiplier = 0.1f;
            }

            int scaled = Math.round(effectiveDistance * multiplier);
            int maxDistance = Math.max(1, FlowingFluids.config.maxWaterFlowDistance);
            int maxScaled = Math.max(1, Math.round(maxDistance * multiplier));
            adaptiveSlopeFindDistance = Math.max(1, Math.min(maxScaled, scaled));
        }
        adaptiveSlopeFindDistance = FluidActivityTracker.getAdaptiveSlopeFindDistance(level, blockPos, adaptiveSlopeFindDistance);

        // Set per-search recursion budget: proportional to search distance, with a floor to still find nearby drops.
        ff$SLOPE_SEARCH_BUDGET.get()[0] = Math.max(24, adaptiveSlopeFindDistance * 6);

        Short2BooleanOpenHashMap posCanFlowDown = ff$getFlowDownCache();
        posCanFlowDown.put(ffCacheKey(blockPos, blockPos), false);

        try {
            Fluid sourceFluid = fluidState.getType();
            boolean applyBias = fluidState.is(FluidTags.WATER);
            float affinityStrength = applyBias ? Math.max(0f, FlowingFluids.config.waterAffinityStrength) : 0f;
            float inertiaStrength = applyBias ? Math.max(0f, FlowingFluids.config.flowInertiaStrength) : 0f;
            Direction inertiaDir = null;
            float inertiaMomentum = 0f;
            if (applyBias && inertiaStrength > 0f && FlowingFluids.config.flowInertiaMaxAgeTicks > 0) {
                inertiaDir = AdaptiveTickScheduler.getFlowInertiaDirection(level, blockPos, FlowingFluids.config.flowInertiaMaxAgeTicks);
                inertiaMomentum = AdaptiveTickScheduler.getFlowMomentum(level, blockPos, FlowingFluids.config.flowInertiaMaxAgeTicks);
            }
            Direction bestDirection = null;
            int bestDistance = Integer.MAX_VALUE;
            float bestScore = Float.MAX_VALUE;
            BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ());
            float sourcePressureHead = applyBias
                    ? flowing_fluids$getPressureHead(level, blockPos, sourceFluid, amount)
                    : 0f;

            // Get chunk position for cache
            var chunkPos = new net.minecraft.world.level.ChunkPos(blockPos);

            for (int i = 0; i < directionCount; i++) {
                Direction dir = directionsCanSpreadToSortedByAmount[i];
                mutablePos.set(blockPos.getX(), blockPos.getY(), blockPos.getZ());
                mutablePos.move(dir);
                short key = ffCacheKey(blockPos, mutablePos);

                // Early exit: if we found a much lower neighbor or can flow down, return immediately
                if (directionAmounts[i] < amount - 1 || flowing_fluids$getSetFlowDownCache(key, level, posCanFlowDown, mutablePos, sourceFluid, requiresSlope)) {
                    return dir;
                }

                // Check cache first for slope distance
                int distance = ChunkLocalSlopeCache.getCached(level, chunkPos, blockPos, adaptiveSlopeFindDistance, dir);

                if (distance == -1) {
                    // Cache miss: calculate and store
                    distance = flowing_fluids$getSlopeDistance(
                            level, blockPos, 1, dir.getOpposite(),
                            sourceFluid, amount + 1, mutablePos.immutable(), statesAtPos,
                            posCanFlowDown, requiresSlope, adaptiveSlopeFindDistance);

                    // Store in cache for future use
                    ChunkLocalSlopeCache.putCached(level, chunkPos, blockPos, adaptiveSlopeFindDistance, dir, distance);
                }

                if (!requiresSlope || distance <= adaptiveSlopeFindDistance) {
                    float bias = applyBias
                            ? flowing_fluids$getDirectionBias(level, blockPos, dir, sourceFluid, directionAmounts[i],
                            sourcePressureHead, inertiaDir, affinityStrength, inertiaStrength * Math.max(0.15f, inertiaMomentum))
                            : 0f;
                    float score = distance - bias;
                    if (score < bestScore || (score == bestScore && distance < bestDistance)) {
                        bestScore = score;
                        bestDistance = distance;
                        bestDirection = dir;

                        // Early exit optimization: if we found a very close slope, no need to check other directions
                        if (bestScore <= 2f) {
                            break;
                        }
                    }
                }
            }

            return bestDirection;
        } finally {
            posCanFlowDown.clear();
        }
    }


    @Unique
    protected int flowing_fluids$getSlopeDistance(LevelReader level, BlockPos sourcePosForKey, int distance, Direction fromDir, Fluid sourceFluid, int sourceAmount,
                                                  BlockPos newPos, Short2ObjectMap<Pair<BlockState, FluidState>> statesAtPos, Short2BooleanMap posCanFlowDown,
                                                  boolean forceSlopeDownSameOrEmpty, int slopeFindDistance) {
        // Global budget to avoid deep recursion storms on large flat surfaces.
        int[] budget = ff$SLOPE_SEARCH_BUDGET.get();
        if (--budget[0] < 0) {
            return 1000; // treat as no slope found within budget
        }

        // OPTIMIZED: Early termination conditions to reduce cubic complexity
        // currently in a worse case scenario, water spreading on flat ground, this deep search will perform:
        // 160 side spread, flowing_fluids$canSpreadToOptionallySameOrEmpty() checks
        // 40 downwards spread, flowing_fluids$getSetFlowDownCache() checks,
        // for a total of 200 checks per original source on totally flat ground

        // the 40 checks are perfectly cached and optimized and cannot be improved as there are exactly 40 possible blocks requiring downwards checks

        // the 160 checks can infact be optimized down to 130 by storing the results of checks using the to and from positions as the key as well as
        // the distance accepting any previously cached values that had lower or equal search distances (meaning those cached results searched further)
        // However, in practise the additional overhead of storing and checking the cache for all 160 searches, was not worth the 30 checks saved.
        // With the result cache we did 130 checks averaging 0.8~ms per spread check, without the cache we did 160 checks averaging 0.4~ms per tick
        // further result caching is detrimental!

        // default distance return
        int smallest = 1000;

        int searchDistance = distance + 1;

        // OPTIMIZATION: Early exit if we've already searched too far
        if (searchDistance > slopeFindDistance) {
            return smallest;
        }

        int checkedDirections = 0;
        //check all directions except the one we came from
        for (final Direction searchDir : Direction.Plane.HORIZONTAL) {
            if (searchDir != fromDir) {
                // OPTIMIZATION: Early exit if we found a good enough path (distance <= 3)
                if (smallest <= 3 && checkedDirections > 0) {
                    break;
                }

                // get search context
                var searchPos = newPos.relative(searchDir);
                var searchKey = ffCacheKey(sourcePosForKey, searchPos);
                var searchStates = flowing_fluids$getSetPosCache(searchKey, level, statesAtPos, searchPos);

                // if we can spread to the searched direction
                if (flowing_fluids$canSpreadToOptionallySameOrEmpty(sourceFluid, sourceAmount, level, newPos,
                        level.getBlockState(newPos), searchDir, searchPos,
                        searchStates.getFirst(), searchStates.getSecond(), forceSlopeDownSameOrEmpty)) {

                    checkedDirections++;

                    // if we can flow down, cache the result of this and return this distance as it's the smallest
                    if (searchStates.getSecond().getAmount() < (sourceAmount - 2)
                            || flowing_fluids$getSetFlowDownCache(searchKey, level, posCanFlowDown, searchPos, sourceFluid, forceSlopeDownSameOrEmpty)) {
                        //cache the result to both keys as we may also come back to this position from another direction
                        return searchDistance;
                    }
                    // if we can't flow down here, check the next distance via iteration as long as we are within the slope search distance
                    if (searchDistance < slopeFindDistance) {
                        // OPTIMIZATION: Only recurse if searchDistance is less than current smallest
                        if (searchDistance < smallest) {
                            int next = flowing_fluids$getSlopeDistance(level, sourcePosForKey, searchDistance,
                                    searchDir.getOpposite(), sourceFluid, sourceAmount, searchPos,
                                    statesAtPos, posCanFlowDown, forceSlopeDownSameOrEmpty, slopeFindDistance);
                            // if the next distance is less than the current smallest, update the smallest
                            if (next < smallest) {
                                smallest = next;
                                // OPTIMIZATION: If we found a very close path, exit early
                                if (smallest <= 2) {
                                    return smallest;
                                }
                            }
                        }
                        // continue to check all directions for the smallest distance
                    }
                }
            }
        }

        return smallest;
    }

    @Unique
    private Pair<BlockState, FluidState> flowing_fluids$getSetPosCache(short key, LevelReader level, Short2ObjectMap<Pair<BlockState, FluidState>> statesAtPos, BlockPos pos) {
        return statesAtPos.computeIfAbsent(key, (sx) -> {
            BlockState blockState = level.getBlockState(pos);
            FluidState fluidState = FFFluidUtils.getEffectiveFluidState((LevelAccessor) level, pos, blockState);
            return Pair.of(blockState, fluidState);
        });
    }

    @Unique
    private boolean flowing_fluids$getSetFlowDownCache(short key, LevelReader level, Short2BooleanMap boolAtPos, BlockPos pos, Fluid sourceFluid, boolean forceSlopeDownSameOrEmpty) {
        return boolAtPos.computeIfAbsent(key, (sx) -> {
            var posDown = pos.below();
            BlockState downState = level.getBlockState(posDown);
            FluidState downFluid = FFFluidUtils.getEffectiveFluidState((LevelAccessor) level, posDown, downState);
            return (flowing_fluids$canSpreadToOptionallySameOrEmpty(sourceFluid, 8, level, pos, level.getBlockState(pos),
                    Direction.DOWN, posDown, downState, downFluid,
                    forceSlopeDownSameOrEmpty));
        });
    }

    @Unique
    private @Nullable Direction flowing_fluids$pickBiasedDirection(Level level, BlockPos origin, Fluid sourceFluid,
                                                                  Direction[] directions, int[] neighbourAmounts, int count,
                                                                  int sourceAmount, @Nullable Direction inertiaDir,
                                                                  float affinityStrength, float inertiaStrength) {
        if (count <= 0) {
            return null;
        }
        float sourcePressureHead = flowing_fluids$getPressureHead(level, origin, sourceFluid, sourceAmount);
        float bestScore = Float.MAX_VALUE;
        Direction bestDirection = null;
        for (int i = 0; i < count; i++) {
            Direction dir = directions[i];
            float bias = flowing_fluids$getDirectionBias(level, origin, dir, sourceFluid, neighbourAmounts[i],
                    sourcePressureHead, inertiaDir, affinityStrength, inertiaStrength);
            float score = neighbourAmounts[i] - bias;
            if (score < bestScore) {
                bestScore = score;
                bestDirection = dir;
            }
        }
        return bestDirection;
    }

    @Unique
    private float flowing_fluids$getDirectionBias(Level level, BlockPos origin, Direction dir, Fluid sourceFluid,
                                                  int targetAmount, float sourcePressureHead,
                                                  @Nullable Direction inertiaDir, float affinityStrength, float inertiaStrength) {
        float bias = 0f;
        if (affinityStrength > 0f) {
            bias += flowing_fluids$getWaterAffinityBias(level, origin, dir, sourceFluid, affinityStrength);
        }
        if (FlowingFluids.config.pressureFlowBonusStrength > 0f) {
            bias += flowing_fluids$getLateralPressureBias(level, origin, dir, sourceFluid, targetAmount, sourcePressureHead,
                    FlowingFluids.config.pressureFlowBonusStrength);
        }
        if (inertiaStrength > 0f && inertiaDir != null && inertiaDir == dir) {
            bias += inertiaStrength;
        }
        return bias;
    }

    @Unique
    private float flowing_fluids$getPressureHead(Level level, BlockPos origin, Fluid sourceFluid, int localAmount) {
        FluidSectionDataCache sectionCache = ff$getSectionSampleContext().sampleCache(level, ff$SECTION_SAMPLE_THRESHOLD);
        if (sectionCache != null) {
            float extraPressure = 0.0f;
            int maxColumn = Math.max(0, FlowingFluids.config.downwardPressureMaxColumn);
            if (maxColumn > 0) {
                int column = sectionCache.columnHeight(origin, sourceFluid, maxColumn);
                if (column > 0) {
                    float columnWeight = Math.max(0.25f, FlowingFluids.config.downwardPressureStrength);
                    extraPressure = column * 8.0f * columnWeight;
                }
            }
            return localAmount + extraPressure;
        }

        long key = origin.asLong();
        Long2FloatOpenHashMap cache = ff$getPressureHeadCache();
        float cachedExtra = cache.get(key);
        if (!Float.isNaN(cachedExtra)) {
            return localAmount + cachedExtra;
        }

        float extraPressure = 0.0f;
        int maxColumn = Math.max(0, FlowingFluids.config.downwardPressureMaxColumn);
        if (maxColumn > 0) {
            int column = flowing_fluids$getColumnHeight(level, origin, sourceFluid, maxColumn);
            if (column > 0) {
                float columnWeight = Math.max(0.25f, FlowingFluids.config.downwardPressureStrength);
                extraPressure = column * 8.0f * columnWeight;
            }
        }
        cache.put(key, extraPressure);
        return localAmount + extraPressure;
    }

    @Unique
    private float flowing_fluids$getLateralPressureBias(Level level, BlockPos origin, Direction dir, Fluid sourceFluid,
                                                        int targetAmount, float sourcePressureHead, float strength) {
        if (strength <= 0f) {
            return 0f;
        }
        float targetPressureHead = flowing_fluids$getPressureHead(level, origin.relative(dir), sourceFluid, targetAmount);
        float delta = sourcePressureHead - targetPressureHead;
        return Mth.clamp(delta / 8.0f, -1.0f, 2.0f) * strength;
    }

    @Unique
    private boolean flowing_fluids$shouldSuppressStablePoolTransfer(Level level, BlockPos sourcePos, int sourceAmount,
                                                                    BlockPos targetPos, int targetAmount, int difference,
                                                                    float pressureHeadDelta) {
        if (difference <= 0 || difference > 2) {
            return false;
        }
        if (sourceAmount > 4 || targetAmount > 4) {
            return false;
        }
        if (pressureHeadDelta > 1.25f) {
            return false;
        }
        int sourceStableTicks = AdaptiveTickScheduler.getPoolStableTicks(level, sourcePos, 20);
        int targetStableTicks = AdaptiveTickScheduler.getPoolStableTicks(level, targetPos, 20);
        return Math.max(sourceStableTicks, targetStableTicks) >= 6;
    }

    @Unique
    private boolean flowing_fluids$shouldSuppressShallowFlatTransfer(Level level, BlockPos sourcePos, FluidState sourceState,
                                                                     int sourceAmount, BlockPos targetPos, int targetAmount,
                                                                     int difference, float pressureHeadDelta,
                                                                     int minimumRetainedAmount) {
        if (!sourceState.is(FluidTags.WATER)) {
            return false;
        }
        if (minimumRetainedAmount > 0) {
            return false;
        }
        if (difference <= 0 || difference > 3) {
            return false;
        }
        if (sourceAmount > 3 || targetAmount > 2) {
            return false;
        }
        if (pressureHeadDelta > 0.75f) {
            return false;
        }
        if (flowing_fluids$hasFluidAbove(level, sourcePos, sourceState.getType())
                || flowing_fluids$hasFluidAbove(level, targetPos, sourceState.getType())) {
            return false;
        }
        if (flowing_fluids$hasImmediateDownwardOutlet(level, sourcePos, sourceState.getType(), sourceAmount)
                || flowing_fluids$hasImmediateDownwardOutlet(level, targetPos, sourceState.getType(), Math.max(1, targetAmount))) {
            return false;
        }
        if (FlowingFluids.config.flowInertiaStrength > 0f && FlowingFluids.config.flowInertiaMaxAgeTicks > 0) {
            float momentum = AdaptiveTickScheduler.getFlowMomentum(level, sourcePos, FlowingFluids.config.flowInertiaMaxAgeTicks);
            if (momentum > 0.45f) {
                return false;
            }
        }
        return true;
    }

    @Unique
    private boolean flowing_fluids$hasFluidAbove(Level level, BlockPos pos, Fluid sourceFluid) {
        BlockPos abovePos = pos.above();
        FluidState above = FFFluidUtils.getEffectiveFluidState(level, abovePos, level.getBlockState(abovePos));
        return above.getType().isSame(sourceFluid) && above.getAmount() > 0;
    }

    @Unique
    private boolean flowing_fluids$hasImmediateDownwardOutlet(Level level, BlockPos pos, Fluid sourceFluid, int sourceAmount) {
        BlockState stateAtPos = level.getBlockState(pos);
        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        FluidState belowFluid = FFFluidUtils.getEffectiveFluidState(level, belowPos, belowState);
        if (!flowing_fluids$canSpreadToOptionallySameOrEmpty(sourceFluid, Math.max(1, sourceAmount), level, pos, stateAtPos,
                Direction.DOWN, belowPos, belowState, belowFluid, false)) {
            return false;
        }
        return belowFluid.isEmpty() || !belowFluid.getType().isSame(sourceFluid) || belowFluid.getAmount() < sourceAmount;
    }

    @Unique
    private boolean flowing_fluids$shouldSuppressThinCapDrift(Level level, BlockPos pos, FluidState fluidState, int amount) {
        if (!fluidState.is(FluidTags.WATER)) {
            return false;
        }
        if (amount <= 0 || amount > getDropOff(level)) {
            return false;
        }
        if (flowing_fluids$hasFluidAbove(level, pos, fluidState.getType())) {
            return false;
        }

        BlockPos belowPos = pos.below();
        FluidState belowFluid = FFFluidUtils.getEffectiveFluidState(level, belowPos, level.getBlockState(belowPos));
        if (!belowFluid.getType().isSame(fluidState.getType()) || belowFluid.getAmount() < 8) {
            return false;
        }

        int supportedBaseNeighbors = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(belowPos, dir);
            FluidState baseNeighbor = FFFluidUtils.getEffectiveFluidState(level, cursor, level.getBlockState(cursor));
            if (baseNeighbor.getType().isSame(fluidState.getType()) && baseNeighbor.getAmount() >= 8) {
                supportedBaseNeighbors++;
            }
        }

        return supportedBaseNeighbors >= 3;
    }

    @Unique
    private boolean flowing_fluids$shouldSuppressExploratorySpread(Level level, BlockPos pos, FluidState fluidState, int amount) {
        if (!fluidState.is(FluidTags.WATER)) {
            return false;
        }
        var biome = level.getBiome(pos);
        boolean broadWaterBiome = FFFluidUtils.isOceanBiome(biome) || FFFluidUtils.isBeachBiome(biome);
        int maxExploratoryAmount = broadWaterBiome && pos.getY() >= level.getSeaLevel() - 1 ? 6 : 4;
        if (amount <= 0 || amount > maxExploratoryAmount) {
            return false;
        }
        if (flowing_fluids$hasFluidAbove(level, pos, fluidState.getType())) {
            return false;
        }
        if (flowing_fluids$hasImmediateDownwardOutlet(level, pos, fluidState.getType(), amount)) {
            return false;
        }
        if (AdaptiveTickScheduler.getPoolStableTicks(level, pos, 20) < 4) {
            return false;
        }

        BlockState belowState = level.getBlockState(pos.below());
        FluidState belowFluid = FFFluidUtils.getEffectiveFluidState(level, pos.below(), belowState);
        boolean supportedBelow = (belowFluid.getType().isSame(fluidState.getType()) && belowFluid.getAmount() >= amount)
                || (!belowState.isAir() && !belowState.canBeReplaced(fluidState.getType()));
        if (!supportedBelow) {
            return false;
        }

        int supportedNeighbors = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, dir);
            FluidState neighbor = FFFluidUtils.getEffectiveFluidState(level, cursor, level.getBlockState(cursor));
            if (neighbor.getType().isSame(fluidState.getType()) && neighbor.getAmount() >= Math.max(1, amount - 1)) {
                supportedNeighbors++;
            }
        }
        int requiredNeighbors = broadWaterBiome && pos.getY() >= level.getSeaLevel() - 1 ? 3 : 2;
        return supportedNeighbors >= requiredNeighbors;
    }

    @Unique
    private void flowing_fluids$updateStablePoolTracking(Level level, BlockPos pos, FluidState fluidState, int amount, boolean stable) {
        if (!fluidState.is(FluidTags.WATER)) {
            return;
        }
        if (flowing_fluids$shouldTrackStablePool(amount)) {
            AdaptiveTickScheduler.markPoolStable(level, pos, stable);
        } else {
            AdaptiveTickScheduler.markPoolStable(level, pos, false);
        }
    }

    @Unique
    private boolean flowing_fluids$shouldTrackStablePool(int amount) {
        return amount > 0 && amount <= 4;
    }

    @Unique
    private void flowing_fluids$invalidateFluidSampleCaches(BlockPos... positions) {
        Long2IntOpenHashMap waterCache = ff$getWaterAmountCache();
        Long2FloatOpenHashMap pressureCache = ff$getPressureHeadCache();
        ff$getSectionSampleContext().invalidate(positions);
        for (BlockPos pos : positions) {
            if (pos == null) {
                continue;
            }
            long key = pos.asLong();
            waterCache.remove(key);
            pressureCache.remove(key);
        }
    }

    @Unique
    private float flowing_fluids$getWaterAffinityBias(Level level, BlockPos origin, Direction dir, Fluid sourceFluid, float strength) {
        if (strength <= 0f) {
            return 0f;
        }
        BlockPos target = origin.relative(dir);
        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
        int total = 0;
        int samples = 0;

        scanPos.set(target);
        total += flowing_fluids$getWaterAmountAt(level, scanPos, sourceFluid);
        samples++;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            scanPos.set(target);
            scanPos.move(side);
            total += flowing_fluids$getWaterAmountAt(level, scanPos, sourceFluid);
            samples++;
        }

        if (samples == 0) {
            return 0f;
        }
        float avg = (float) total / (samples * 8.0f);
        return avg * strength;
    }

    @Unique
    private int flowing_fluids$getWaterAmountAt(Level level, BlockPos pos, Fluid sourceFluid) {
        FluidSectionDataCache sectionCache = ff$getSectionSampleContext().sampleCache(level, ff$SECTION_SAMPLE_THRESHOLD);
        if (sectionCache != null) {
            return sectionCache.amountIfFluid(pos, sourceFluid);
        }
        Long2IntOpenHashMap cache = ff$getWaterAmountCache();
        long key = pos.asLong();
        int cached = cache.get(key);
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }
        BlockState state = level.getBlockState(pos);
        FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, pos, state);
        if (fluidState.getType().isSame(sourceFluid)) {
            int amount = fluidState.getAmount();
            cache.put(key, amount);
            return amount;
        }
        cache.put(key, 0);
        return 0;
    }


    @Unique
    protected void flowing_fluids$spreadTo2(LevelAccessor levelAccessor, BlockPos blockPos, BlockState blockState, Direction direction, int amount) {
        this.spreadTo(levelAccessor, blockPos, blockState, direction, getStateForFluidByAmount(this, amount));
        flowing_fluids$bufferFlowState(levelAccessor, blockPos, blockState);
    }

    @Unique
    private void flowing_fluids$bufferFlowState(LevelAccessor levelAccessor, BlockPos blockPos, BlockState beforeState) {
        FluidState before = FFFluidUtils.getEffectiveFluidState(levelAccessor, blockPos, beforeState);
        FluidState updated = FFFluidUtils.getEffectiveFluidState(levelAccessor, blockPos, levelAccessor.getBlockState(blockPos));
        if (before.isEmpty() && updated.isEmpty()) {
            return;
        }
        if (!before.isEmpty()
                && !updated.isEmpty()
                && before.getType().isSame(updated.getType())
                && before.getAmount() == updated.getAmount()) {
            return;
        }
        if (FlowingFluids.config.flowActivationTicks > 0) {
            AdaptiveTickScheduler.markFlowActive(levelAccessor, blockPos, FlowingFluids.config.flowActivationTicks);
        }
        if (updated.isEmpty()) {
            FluidTickBuffer.bufferFluidChange(levelAccessor, blockPos, 0, false, this);
        } else {
            int internalAmount = FluidAmountConverter.toInternal(updated.getAmount());
            FluidTickBuffer.bufferFluidChange(levelAccessor, blockPos, internalAmount, true, updated.getType());
        }
        ParallelFluidEqualizer.enqueue(levelAccessor, blockPos);
        FluidTickBuffer.bufferSlopeCacheInvalidation(levelAccessor, blockPos);
    }


    @Unique
    private boolean flowing_fluids$canSpreadToOptionallySameOrEmpty(Fluid sourceFluid, int sourceAmount, BlockGetter blockGetter,
                                                                    BlockPos blockPos, BlockState blockState, Direction direction,
                                                                    BlockPos blockPos2, BlockState blockState2, FluidState fluidState2,
                                                                    boolean enforceSameFluidOrEmpty) {
        //add extra fluid check for enforcing replacing into own fluid type, or empty, only
        if (enforceSameFluidOrEmpty && !(fluidState2.isEmpty() || fluidState2.getType().isSame(sourceFluid)))
            return false;

        return flowing_fluids$canSpreadTo(sourceFluid, sourceAmount, blockGetter, blockPos, blockState, direction, blockPos2, blockState2, fluidState2);
    }

    @Unique
    private boolean flowing_fluids$canSpreadTo(Fluid sourceFluid, int sourceAmount, BlockGetter blockGetter,
                                               BlockPos blockPos, BlockState blockState, Direction direction,
                                               BlockPos blockPos2, BlockState blockState2, FluidState fluidState2) {
        //add extra fluid check for replacing into self
        return FFFluidUtils.canFluidFlowFromPosToDirection((FlowingFluid) sourceFluid, sourceAmount, blockGetter, blockPos, blockState, direction, blockPos2, blockState2, fluidState2);
    }

}
