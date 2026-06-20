package traben.flowing_fluids.mixin;


import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.AsyncSlopeSearchPlanner;
import traben.flowing_fluids.ChunkLocalSlopeCache;
import traben.flowing_fluids.FFDownwardFlowTarget;
import traben.flowing_fluids.FFFlowDownResult;
import traben.flowing_fluids.FFHorizontalFlowTarget;
import traben.flowing_fluids.FFSectionSampleContext;
import traben.flowing_fluids.FluidAmountConverter;
import traben.flowing_fluids.FluidMutationBatch;
import traben.flowing_fluids.FluidRegressionLogic;
import traben.flowing_fluids.FluidSectionDataCache;
import traben.flowing_fluids.FluidTickBuffer;
import traben.flowing_fluids.FluidSpatialGrid;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.FluidActivityTracker;
import traben.flowing_fluids.ParallelFluidEqualizer;
import traben.flowing_fluids.ParallelFluidTickManager;
import traben.flowing_fluids.SiphonFlowSystem;
import traben.flowing_fluids.config.FFConfig;
import traben.flowing_fluids.drying.DryingEventSystem;
import traben.flowing_fluids.optimization.HierarchicalDistanceManager;
import traben.flowing_fluids.optimization.WaterFlowProfile;
import traben.flowing_fluids.performance.FluidAutoTickDelay;
import traben.flowing_fluids.performance.FluidFineTickDelay;
import traben.flowing_fluids.performance.FluidPerformanceMonitor;
import traben.flowing_fluids.performance.FluidTickWorkloadGovernor;
import traben.flowing_fluids.performance.InfiniteBiomeRefillFallbackController;

import java.util.List;

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
    private static final ThreadLocal<boolean[]> ff$ASYNC_SLOPE_PENDING =
            ThreadLocal.withInitial(() -> new boolean[1]);

    @Unique
    private static final ThreadLocal<int[]> ff$FINE_TICK_DEPTH =
            ThreadLocal.withInitial(() -> new int[1]);

    @Unique
    private static final Object ff$THIN_FRAGMENT_RECLAIM_LOCK = new Object();

    @Unique
    private static final Long2LongOpenHashMap ff$THIN_FRAGMENT_RECLAIM_REGION_TICK = new Long2LongOpenHashMap();

    @Unique
    private static long ff$thinFragmentReclaimBudgetTick = Long.MIN_VALUE;

    @Unique
    private static int ff$thinFragmentReclaimsThisTick = 0;

    @Unique
    private static final int ff$THIN_FRAGMENT_MAX_RECLAIMS_PER_TICK = 48;

    @Unique
    private static final int ff$THIN_FRAGMENT_REGION_COOLDOWN_TICKS = 3;

    @Unique
    private static final ThreadLocal<Direction[]> ff$SPREAD_DIRECTION_BUFFER =
            ThreadLocal.withInitial(() -> new Direction[4]);

    @Unique
    private static final ThreadLocal<int[]> ff$SPREAD_AMOUNT_BUFFER =
            ThreadLocal.withInitial(() -> new int[4]);


    @Unique
    private static final ThreadLocal<Long2IntOpenHashMap> ff$CONNECTED_HEAD_CACHE =
            ThreadLocal.withInitial(() -> {
                Long2IntOpenHashMap cache = new Long2IntOpenHashMap();
                cache.defaultReturnValue(Integer.MIN_VALUE);
                return cache;
            });

    @Unique
    private static final ThreadLocal<LongArrayFIFOQueue> ff$CONNECTED_HEAD_QUEUE =
            ThreadLocal.withInitial(LongArrayFIFOQueue::new);

    @Unique
    private static final ThreadLocal<LongOpenHashSet> ff$CONNECTED_HEAD_VISITED =
            ThreadLocal.withInitial(LongOpenHashSet::new);

    @Unique
    private static final ThreadLocal<Long2IntOpenHashMap> ff$CONNECTED_HEAD_DEPTHS =
            ThreadLocal.withInitial(() -> {
                Long2IntOpenHashMap cache = new Long2IntOpenHashMap();
                cache.defaultReturnValue(-1);
                return cache;
            });

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
    private static Long2IntOpenHashMap ff$getConnectedHeadCache() {
        return ff$CONNECTED_HEAD_CACHE.get();
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
        boolean fromIsWaterloggableVanilla = FFFluidUtils.isVanillaWaterloggable(thisState);
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
        boolean toIsWaterloggableVanilla = FFFluidUtils.isVanillaWaterloggable(blockToState);
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
                return FFFluidUtils.transferFluidAmount(level, posFrom, posTo, fluidState.getType(), amount, 0) > 0;
            }
            if (totalAmount < 8) { //crucial this only runs after we confirm they are waterloggables, as otherwise return should be false
                return false; // let normal flow handling decide
            }

            int transferAmount = FluidRegressionLogic.computeVanillaWaterlogTransferAmount(fromIsWaterloggableVanilla, toIsWaterloggableVanilla, amount, destFluidAmount);
            if (transferAmount <= 0) {
                return false;
            }
            return FFFluidUtils.transferFluidAmount(level, posFrom, posTo, fluidState.getType(), transferAmount, 0) > 0;
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
                && AdaptiveTickScheduler.getTrackedScheduledFluidTickCount(level, new ChunkPos(pos)) < 16 //ignore chunks with many updating fluids
                && FlowingFluids.config.isFluidAllowed(this)
                && !FFFluidUtils.getEffectiveFluidState(level, pos.above()).getType().isSame(this)//don't settle if there is a fluid above
        ) {
            //search in a random direction up to 32 blocks for a lower fluid to level out with

            final int amount = state.getAmount();
            if (amount <= getDropOff(level)) return;
            if (state.is(FluidTags.WATER)
                    && FFFluidUtils.isSmallSupportedThinSurfaceCluster(level, pos, this, 3, getDropOff(level))) {
                return;
            }

            final int amountLess = amount - 1;
            final boolean broadSurfaceSource = state.is(FluidTags.WATER)
                    && flowing_fluids$isBroadSurfaceWater(level, pos, state, amount);
            final int maxTransferDistance = broadSurfaceSource
                    ? Math.min(3, FlowingFluids.config.randomTickLevelingDistance)
                    : 1;

            Direction[] shuffled = FFFluidUtils.getCardinalsShuffle(random);
            final Direction randomDirection = shuffled[0];

            boolean straightOnly = random.nextBoolean();
            Direction offStep = randomDirection;
            if (!straightOnly) {
                offStep = random.nextBoolean() ? randomDirection.getClockWise() : randomDirection.getCounterClockWise();
            }

            final BlockPos.MutableBlockPos movingDir = pos.mutable();
            final BlockPos.MutableBlockPos movingDirAbove = pos.above().mutable();
            boolean stablePath = true;

            for (int i = 0; i < FlowingFluids.config.randomTickLevelingDistance; i++) {
                Direction step = straightOnly ? randomDirection : (random.nextBoolean() ? randomDirection : offStep);
                movingDir.move(step);
                movingDirAbove.move(step);

                var stateDir = level.getBlockState(movingDir);
                var fluidStateDir = FFFluidUtils.getEffectiveFluidState(level, movingDir, stateDir);
                if (!fluidStateDir.getType().isSame(this)) return;

                if (FFFluidUtils.getEffectiveFluidState(level, movingDirAbove).getType().isSame(this)) return;

                int amountDir = fluidStateDir.getAmount();
                if (amountDir > amount) return;

                int distance = i + 1;
                if (amountDir < amountLess) {
                    boolean broadSurfaceTarget = fluidStateDir.is(FluidTags.WATER)
                            && flowing_fluids$isBroadSurfaceWater(level, movingDir, fluidStateDir, amountDir);
                    if (distance <= maxTransferDistance
                            && (distance == 1 || (broadSurfaceSource && stablePath && broadSurfaceTarget
                            && !flowing_fluids$hasImmediateDownwardOutlet(level, movingDir, this, amountDir)))
                            && FFFluidUtils.transferFluidAmount(level, pos, movingDir, this, 1, amountLess) > 0) {
                        return;
                    }
                    return;
                }
                stablePath &= amountDir >= amountLess
                        && !flowing_fluids$hasImmediateDownwardOutlet(level, movingDir, this, amountDir);
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
            final boolean monitorEnabled = FlowingFluids.config.enablePerformanceMonitoring;
            final FluidPerformanceMonitor monitor = monitorEnabled ? FluidPerformanceMonitor.getInstance() : null;
            final long monitorStartNanos = monitorEnabled ? System.nanoTime() : 0L;
            final long monitorStartAllocatedBytes = monitorEnabled ? monitor.currentThreadAllocatedBytes() : -1L;
            final int monitorFlowDistance = fluidState.is(FluidTags.WATER)
                    ? Math.max(1, FlowingFluids.config.waterFlowDistance)
                    : Math.max(1, fluidState.is(FluidTags.LAVA)
                    ? (level.dimensionType().ultraWarm()
                    ? FlowingFluids.config.lavaNetherFlowDistance : FlowingFluids.config.lavaFlowDistance)
                    : getSlopeFindDistance(level));
            // cancel the original tick
            ci.cancel();

            if (FluidTickWorkloadGovernor.shouldDefer(level, blockPos, this, monitorFlowDistance)) {
                int deferredDelay = FluidTickWorkloadGovernor.getDeferredDelay(level, blockPos, this, monitorFlowDistance);
                AdaptiveTickScheduler.scheduleFluidTick(level, blockPos, this, deferredDelay);
                ff$recordFluidTickSample(monitorEnabled, monitor, monitorStartNanos, monitorStartAllocatedBytes, monitorFlowDistance);
                return;
            }

            if (FlowingFluids.config.dontTickAtLocation(blockPos, level)) {
                AdaptiveTickScheduler.scheduleFluidTick(level, blockPos, this, 200 + level.random.nextInt(200)); // 10 - 20 seconds delay
                ff$recordFluidTickSample(monitorEnabled, monitor, monitorStartNanos, monitorStartAllocatedBytes, monitorFlowDistance);
                return; // do not calculate and delay the tick
            }

            if (System.currentTimeMillis() < FlowingFluids.debug_killFluidUpdatesUntilTime) {
                ff$recordFluidTickSample(monitorEnabled, monitor, monitorStartNanos, monitorStartAllocatedBytes, monitorFlowDistance);
                return; // kill this update
            }

            FlowingFluids.setManeuveringFluids(true);
            ff$getConnectedHeadCache().clear();
            ff$getSectionSampleContext().begin(level);

            boolean withinInfBiomeHeights = FFFluidUtils.isWithinInfiniteBiomeRefillBand(level, blockPos);

            boolean isWaterAndInfiniteBiome = fluidState.is(FluidTags.WATER)
                    && withinInfBiomeHeights
                    && !FFFluidUtils.isInfiniteBiomeRefillSuppressed(level, blockPos)
                    && FFFluidUtils.matchInfiniteBiomes(level.getBiome(blockPos))
                    && FFFluidUtils.hasInfiniteBiomeAmbientAccess(level, blockPos, fluidState.getType(), fluidState.getAmount());

            boolean infiniteBiomeImmediateOutlet = isWaterAndInfiniteBiome
                    && flowing_fluids$hasImmediateDownwardOutlet(level, blockPos, fluidState.getType(), fluidState.getAmount());

            boolean dontConsumeWater = isWaterAndInfiniteBiome
                    && FFFluidUtils.isInfiniteBiomeNonConsumeEnabled()
                    && FFFluidUtils.seaLevel(level) != blockPos.getY()
                    && level.getRandom().nextFloat() < FlowingFluids.config.infiniteWaterBiomeNonConsumeChance
                    && FFFluidUtils.hasStableInfiniteSourceShape(
                    level, blockPos, fluidState.getType(), fluidState.getAmount());

            #if MC <= MC_21
            BlockState thisState = level.getBlockState(blockPos);
            #endif

            try {
                if (fluidState.is(FluidTags.WATER)) {
                    if (flowing_fluids$trySeaLevelOverflowEvaporationTick(level, blockPos, thisState, fluidState)) {
                        return;
                    }
                    if (FlowingFluids.config.waterProcessingMode == FFConfig.WaterProcessingMode.LEGACY) {
                        ff$legacyTickWater(level, blockPos, fluidState, thisState, dontConsumeWater);
                        return;
                    }
                    if (FlowingFluids.config.waterProcessingMode == FFConfig.WaterProcessingMode.HYBRID
                            && !ff$shouldUseModernWaterHybrid(level, blockPos, fluidState)) {
                        ff$legacyTickWater(level, blockPos, fluidState, thisState, dontConsumeWater);
                        return;
                    }
                }

                WaterFlowProfile waterProfile = null;
                HierarchicalDistanceManager distanceManager = null;
                HierarchicalDistanceManager.RangeTier rangeTier = null;
                int effectiveFlowDistance = Math.max(1, FlowingFluids.config.waterFlowDistance);
                if (fluidState.is(FluidTags.WATER)
                ) {
                    waterProfile = flowing_fluids$getWaterFlowProfile(level, blockPos, fluidState, fluidState.getAmount());
                    distanceManager = HierarchicalDistanceManager.getInstance();
                    rangeTier = distanceManager.getSimulationTier(blockPos, level);
                    boolean forcedRecheck = AdaptiveTickScheduler.hasForcedRecheck(level, blockPos);
                    HierarchicalDistanceManager.TerrainType terrainType = distanceManager.estimateTerrainType(blockPos, level);
                    effectiveFlowDistance = distanceManager.getEffectiveFlowDistance(
                            Math.max(1, FlowingFluids.config.waterFlowDistance), terrainType, FlowingFluids.config);

                    if (!forcedRecheck
                            && distanceManager.shouldUseMacroFluidModel(rangeTier)
                            && waterProfile.shouldUseMacroScheduling(rangeTier)) {
                        flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, fluidState.getAmount(), true);
                        ParallelFluidTickManager.queueDistantStableTick(level, blockPos, waterProfile.getMacroDelayBucket(rangeTier));
                        return;
                    }
                    if (!forcedRecheck && waterProfile.isStillReservoir()) {
                        flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, fluidState.getAmount(), true);
                        int calmDelay = waterProfile.getStableInteriorDelay(getTickDelay(level));
                        calmDelay = distanceManager.alignDelayToUpdateInterval(blockPos, level, level.getGameTime(),
                                effectiveFlowDistance, calmDelay, FlowingFluids.config);
                        if (distanceManager.shouldUseMacroFluidModel(rangeTier) && calmDelay >= 8) {
                            ParallelFluidTickManager.queueDistantStableTick(level, blockPos, waterProfile.getMacroDelayBucket(rangeTier));
                            return;
                        }
                        AdaptiveTickScheduler.scheduleFluidTick(level, blockPos, this, calmDelay);
                        return;
                    }

                    int internalAmount = FluidSpatialGrid.getFluidAmount(level, blockPos);
                    if (internalAmount <= 0) {
                        internalAmount = FluidAmountConverter.toInternal(fluidState.getAmount());
                    }

                    int baseDelay = getTickDelay(level);
                    if (!AdaptiveTickScheduler.shouldTick(level, blockPos, internalAmount)) {
                        int adaptiveDelay = AdaptiveTickScheduler.getAdaptiveDelay(level, blockPos, internalAmount, baseDelay);
                        adaptiveDelay = waterProfile.adjustScheduledDelay(adaptiveDelay);
                        adaptiveDelay = distanceManager.alignDelayToUpdateInterval(blockPos, level, level.getGameTime(),
                                effectiveFlowDistance, adaptiveDelay, FlowingFluids.config);
                        if (!forcedRecheck
                                && distanceManager.shouldUseMacroFluidModel(rangeTier)
                                && waterProfile.shouldUseMacroScheduling(rangeTier)
                                && adaptiveDelay >= 8) {
                            ParallelFluidTickManager.queueDistantStableTick(level, blockPos, waterProfile.getMacroDelayBucket(rangeTier));
                            return;
                        }
                        AdaptiveTickScheduler.scheduleFluidTick(level, blockPos, this, Math.max(1, adaptiveDelay));
                        return;
                    }
                }

                BlockPos posDown = blockPos.below();
                FFSectionSampleContext.CellSnapshot downCell = ff$getSectionSampleContext().cell(level, posDown);
                // check if we can flow down and if so how much fluid remains out of the 8 total possible
                FFFlowDownResult flowDownResult = flowing_fluids$checkAndFlowDown(level, blockPos, fluidState, thisState,
                        posDown, downCell.blockState(), downCell.fluidState(), fluidState.getAmount());

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
                if (fluidState.getAmount() == 8 && thisState.liquid()) {
                    BlockPos abovePos = blockPos.above();
                    FFSectionSampleContext.CellSnapshot aboveCell = ff$getSectionSampleContext().cell(level, abovePos);
                    BlockState above = aboveCell.blockState();
                    FluidState aboveF = aboveCell.fluidState();
                    if (aboveF.getType() instanceof FlowingFluid) {
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
                        FluidState aboveState = ff$getSectionSampleContext().cell(level, abovePos).fluidState();
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
                            BlockPos neighborPos = blockPos.relative(dir);
                            FluidState neighborFluid = ff$getSectionSampleContext().cell(level, neighborPos).fluidState();
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
                if (FluidRegressionLogic.shouldSkipInfiniteBiomeOutletHorizontalSearch(
                        retainedMinimumForDropOff,
                        isWaterAndInfiniteBiome,
                        infiniteBiomeImmediateOutlet)) {
                    return;
                }

            if (remainingAmount > getDropOff(level) || retainedMinimumForDropOff) {//drop off is 1 for water, 2 for lava in the overworld
                    ff$flowToSides(level, blockPos, fluidState, remainingAmount, thisState,
                            retainedMinimumForDropOff ? getDropOff(level) : 0);//, remainingAmount);
            } else if (FlowingFluids.config.flowToEdges) {
                    Direction dir = flowing_fluids$getImmediateThinEdgeDrop(level, blockPos, fluidState, thisState);
                    if (dir == null && !flowing_fluids$shouldSuppressThinCapDrift(level, blockPos, fluidState, remainingAmount)) {
                        // Thin edge detection alone misses short ledges behind one lateral spread step, which makes
                        // shallow water freeze at stair-steps instead of falling. Keep the cheap immediate check
                        // first, then fall back to the broader slope search for non-settled caps.
                        dir = flowing_fluids$getLowestSpreadableLookingFor4BlockDrops(level, blockPos, fluidState, 1, true);
                    }
                    if (dir != null) {
                        BlockPos pos = blockPos.relative(dir);
                        BlockState sideState = level.getBlockState(pos);
                        FFHorizontalFlowTarget sideTarget = flowing_fluids$resolveHorizontalFlowTarget(level, pos, sideState, dir);
                        BlockPos actualPos = pos;
                        BlockState actualState = sideState;
                        if (sideTarget.skippedPassThrough()
                                && flowing_fluids$canUseHorizontalPassThroughTarget(level, blockPos, thisState, dir,
                                fluidState.getType(), remainingAmount, pos, sideState, sideTarget)) {
                            actualPos = sideTarget.targetPos();
                            actualState = sideTarget.targetState();
                        }
                        flowing_fluids$setOrRemoveWaterAmountAt(level, blockPos, 0, thisState, dir);
                        flowing_fluids$spreadTo2(level, actualPos, actualState, dir, remainingAmount);
                        flowing_fluids$invalidateFluidSampleCaches(blockPos, pos, actualPos);
                        if (fluidState.is(FluidTags.WATER) && FlowingFluids.config.flowInertiaStrength > 0f) {
                            float momentum = Mth.clamp(remainingAmount / 8.0f, 0.2f, 1.0f);
                            AdaptiveTickScheduler.recordFlowDirection(level, blockPos, dir, momentum);
                            AdaptiveTickScheduler.recordFlowDirection(level, actualPos, dir, momentum * 0.65f);
                        }
                        return;
                    }
                    if (flowing_fluids$shouldSuppressThinCapDrift(level, blockPos, fluidState, remainingAmount)) {
                        flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, remainingAmount, true);
                        return;
                    }
                    flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, remainingAmount, true);
                    return;
                }



            } finally {

                if (isWaterAndInfiniteBiome) {
                    if (FFFluidUtils.seaLevel(level) != blockPos.getY()) {
                        FluidState currentState = FFFluidUtils.getEffectiveFluidState(level, blockPos);
                        int fastRefill = FFFluidUtils.isInfiniteBiomeFlowingRefillEnabled()
                                && currentState.getType().isSame(fluidState.getType())
                                && FFFluidUtils.shouldAttemptInfiniteBiomeFlowingRefill(level, blockPos,
                                fluidState.getType(), fluidState.getAmount(), currentState.getAmount())
                                ? FFFluidUtils.getInfiniteBiomeFlowingRefillAmount(level, blockPos, fluidState.getType(),
                                currentState.getAmount())
                                : 0;
                        if (fastRefill > 0) {
                            FlowingFluid currentFluid = (FlowingFluid) fluidState.getType();
                            if (!FFFluidUtils.tryApplyVanillaInfiniteSourceRefill(
                                    level,
                                    blockPos,
                                    currentFluid,
                                    currentState.getAmount(),
                                    InfiniteBiomeRefillFallbackController.shouldUseSourceRefillFallback())) {
                                FFFluidUtils.applyConnectedFluidAmountDelta(level, blockPos, currentFluid,
                                        fastRefill, 12, false, true);
                            }
                        } else if (dontConsumeWater) {
                            // if we are in a truly infinite biome, we need to set this back to the original state
                            // as we don't want to lose water in these biomes
                            FFFluidUtils.setFluidStateAtPosToNewAmount(level, blockPos, fluidState.getType(), fluidState.getAmount());
                        }
                    }
                }
                if (fluidState.is(FluidTags.WATER)) {
                    FluidState currentState = FFFluidUtils.getEffectiveFluidState(level, blockPos);
                    if (level instanceof ServerLevel serverLevel
                            && currentState.is(FluidTags.WATER)
                            && currentState.getAmount() > 0) {
                        BlockState currentBlockState = level.getBlockState(blockPos);
                        if (!flowing_fluids$handleThinWaterFragment(serverLevel, blockPos, currentBlockState, currentState)) {
                            SiphonFlowSystem.tryRun(serverLevel, blockPos, currentState);
                        }
                    }
                }

                ff$getSectionSampleContext().end();
                ff$getConnectedHeadCache().clear();
                FlowingFluids.setManeuveringFluids(false);
                FlowingFluids.pistonTick = false;
                ff$recordFluidTickSample(monitorEnabled, monitor, monitorStartNanos, monitorStartAllocatedBytes, monitorFlowDistance);
                ff$runFineTickSubsteps(level, blockPos, fluidState);
            }
        }

    }

    @Unique
    private void ff$runFineTickSubsteps(final Level level, final BlockPos blockPos, final FluidState originalState) {
        int[] depth = ff$FINE_TICK_DEPTH.get();
        if (depth[0] > 0 || level.isClientSide() || originalState == null || !FlowingFluids.config.isFluidAllowed(originalState)) {
            return;
        }

        float adjustedDelay;
        if (originalState.is(FluidTags.WATER)) {
            adjustedDelay = FluidAutoTickDelay.getAdjustedWaterTickDelay(FlowingFluids.config.waterTickDelay);
        } else if (originalState.is(FluidTags.LAVA)) {
            adjustedDelay = FluidAutoTickDelay.getAdjustedLavaTickDelay(level.dimensionType().ultraWarm()
                    ? FlowingFluids.config.lavaNetherTickDelay
                    : FlowingFluids.config.lavaTickDelay);
        } else {
            return;
        }

        int additionalSubsteps = FluidFineTickDelay.getAdditionalSubsteps(level, blockPos, originalState.getType(), adjustedDelay);
        if (additionalSubsteps <= 0) {
            return;
        }

        depth[0]++;
        try {
            for (int i = 0; i < additionalSubsteps; i++) {
                FluidState currentState = FFFluidUtils.getEffectiveFluidState(level, blockPos);
                if (currentState.isEmpty() || !currentState.getType().isSame(originalState.getType())) {
                    return;
                }
#if MC > MC_21
                if (level instanceof ServerLevel serverLevel) {
                    ((FlowingFluid) (Object) this).tick(serverLevel, blockPos, level.getBlockState(blockPos), currentState);
                }
#else
                ((FlowingFluid) (Object) this).tick(level, blockPos, currentState);
#endif
            }
        } finally {
            depth[0]--;
        }
    }

    @Unique
    private static void ff$recordFluidTickSample(boolean enabled, @Nullable FluidPerformanceMonitor monitor,
                                                 long startNanos, long startAllocatedBytes, int flowDistance) {
        if (!enabled || monitor == null) {
            return;
        }
        long allocatedBytes = 0L;
        long endAllocatedBytes = monitor.currentThreadAllocatedBytes();
        if (startAllocatedBytes >= 0L && endAllocatedBytes >= startAllocatedBytes) {
            allocatedBytes = endAllocatedBytes - startAllocatedBytes;
        }
        monitor.recordFluidTick(System.nanoTime() - startNanos, flowDistance, allocatedBytes);
    }

    @Unique
    private boolean flowing_fluids$trySeaLevelOverflowEvaporationTick(final Level level,
                                                                      final BlockPos blockPos,
                                                                      final BlockState thisState,
                                                                      final FluidState fluidState) {
        if (!(fluidState.getType() instanceof FlowingFluid flowingFluid)) {
            return false;
        }
        int amount = fluidState.getAmount();
        if (!DryingEventSystem.shouldEvaporateSeaLevelOverflow(level, blockPos, flowingFluid, amount)) {
            return false;
        }
        if (FlowingFluids.config.seaLevelOverflowEvaporationInstant) {
            if (FFFluidUtils.applyLocalFluidAmountDelta(level, blockPos, flowingFluid, -amount)) {
                FluidState remaining = FFFluidUtils.getEffectiveFluidState(level, blockPos);
                if (remaining.isEmpty() && level.getBlockState(blockPos.below()).is(Blocks.MUD)) {
                    level.setBlock(blockPos.below(), Blocks.DIRT.defaultBlockState(), 3);
                }
            }
            return true;
        }
        if (FFFluidUtils.canFluidFlowToNeighbourFromPos(level, blockPos, thisState, flowingFluid, amount)) {
            return false;
        }

        float evaporationChance = DryingEventSystem.getSeaLevelOverflowEvaporationChance(level, blockPos);
        if (evaporationChance <= 0.0f) {
            return false;
        }
        if (level.random.nextFloat() < evaporationChance) {
            if (FFFluidUtils.applyLocalFluidAmountDelta(level, blockPos, flowingFluid, -amount)) {
                FluidState remaining = FFFluidUtils.getEffectiveFluidState(level, blockPos);
                if (remaining.isEmpty() && level.getBlockState(blockPos.below()).is(Blocks.MUD)) {
                    level.setBlock(blockPos.below(), Blocks.DIRT.defaultBlockState(), 3);
                }
            }
        } else {
            // Keep overflow cleanup on a slow local retry instead of running the full
            // horizontal search repeatedly while random ticks wait for evaporation.
            AdaptiveTickScheduler.scheduleFluidTick(level, blockPos, this, 20 + level.random.nextInt(40));
        }
        return true;
    }

    @Unique
    private boolean ff$shouldUseModernWaterHybrid(Level level, BlockPos blockPos, FluidState fluidState) {
        int amount = fluidState.getAmount();
        if (amount < 4) {
            return false;
        }

        if (AdaptiveTickScheduler.isFlowActiveNow(level, blockPos)) {
            return false;
        }

        if (FlowingFluids.config.flowInertiaMaxAgeTicks > 0
                && AdaptiveTickScheduler.getFlowMomentum(level, blockPos, FlowingFluids.config.flowInertiaMaxAgeTicks) > 0.12f) {
            return false;
        }

        if (flowing_fluids$hasImmediateSurfaceEdge(level, blockPos, fluidState.getType())) {
            return false;
        }

        if (flowing_fluids$hasNearbyStepDownOutlet(level, blockPos, fluidState.getType(), amount)) {
            return false;
        }

        int stableTicks = AdaptiveTickScheduler.getPoolStableTicks(level, blockPos, 20);
        int requiredStableTicks = Math.max(6, FlowingFluids.config.broadSurfaceStableTicks);
        if (stableTicks < requiredStableTicks) {
            return false;
        }

        WaterFlowProfile waterProfile = flowing_fluids$getWaterFlowProfile(level, blockPos, fluidState, amount);
        if (waterProfile.isFlowActive() || waterProfile.flowMomentum() > 0.12f) {
            return false;
        }

        if (waterProfile.isStillReservoir()) {
            return true;
        }

        return switch (waterProfile.regime()) {
            case LARGE_BODY, SUBTERRANEAN_POOL -> true;
            case IMPOUNDED -> amount >= 6
                    && stableTicks >= requiredStableTicks + 2
                    && waterProfile.stackedColumnHeight() >= 2;
            case CHANNEL -> amount >= 7
                    && stableTicks >= requiredStableTicks + 4
                    && waterProfile.isRiverLikeBiome()
                    && waterProfile.lateralWaterNeighbors() >= 3
                    && waterProfile.lateralEscapeRoutes() >= 2
                    && !waterProfile.isInletZone()
                    && waterProfile.getFlowSpeed().ordinal() <= WaterFlowProfile.FlowSpeed.SLOW.ordinal();
            case TRICKLE, LOCAL, BREACH -> amount >= 6
                    && waterProfile.isBroadSurface()
                    && waterProfile.lateralWaterNeighbors() >= 3
                    && stableTicks >= requiredStableTicks + 2;
        };
    }

    @Unique
    private void ff$legacyTickWater(final Level level, final BlockPos blockPos, final FluidState fluidState,
                                    final BlockState thisState, final boolean dontConsumeWater) {
        int remainingAmount = ff$legacyCheckAndFlowDown(level, blockPos, fluidState, thisState, blockPos.below(),
                level.getBlockState(blockPos.below()), fluidState.getAmount());
        if (remainingAmount <= 0) {
            return;
        }

        if (fluidState.getAmount() == 8 && thisState.liquid()) {
            BlockPos abovePos = blockPos.above();
            BlockState aboveState = level.getBlockState(abovePos);
            FluidState aboveFluid = FFFluidUtils.getEffectiveFluidState(level, abovePos, aboveState);
            if (aboveFluid.getType() instanceof FlowingFluid) {
                int aboveAmount = aboveFluid.getAmount();
                if (aboveAmount > 0) {
                    FlowingFluid flow = (FlowingFluid) aboveFluid.getType();
                    if (FFFluidUtils.canFluidFlowFromPosToDirectionFitOverride(flow, level, abovePos, aboveState,
                            Direction.DOWN, blockPos, thisState)) {
                        var remainder = FFFluidUtils.placeConnectedFluidAmountAndPlaceAction(
                                level, blockPos, aboveAmount, flow, 40, false, !FlowingFluids.pistonTick);
                        if (remainder.first() < aboveAmount) {
                            remainder.second().run();
                            if (!dontConsumeWater) {
                                flowing_fluids$setOrRemoveWaterAmountAt(level, abovePos, remainder.first(), aboveState, Direction.DOWN);
                            }
                            return;
                        }
                    }
                }
            }
        }

        if (remainingAmount > getDropOff(level)) {
            ff$legacyFlowToSides(level, blockPos, fluidState, remainingAmount, thisState);
        } else if (FlowingFluids.config.flowToEdges) {
            Direction dir = ff$legacyGetLowestSpreadableLookingFor4BlockDrops(level, blockPos, fluidState, 1, true);
            if (dir != null) {
                BlockPos pos = blockPos.relative(dir);
                BlockState sideState = level.getBlockState(pos);
                FFHorizontalFlowTarget sideTarget = flowing_fluids$resolveHorizontalFlowTarget(level, pos, sideState, dir);
                BlockPos actualPos = pos;
                BlockState actualState = sideState;
                if (sideTarget.skippedPassThrough()
                        && flowing_fluids$canUseHorizontalPassThroughTarget(level, blockPos, thisState, dir,
                        fluidState.getType(), remainingAmount, pos, sideState, sideTarget)) {
                    actualPos = sideTarget.targetPos();
                    actualState = sideTarget.targetState();
                }
                flowing_fluids$setOrRemoveWaterAmountAt(level, blockPos, 0, thisState, dir);
                flowing_fluids$spreadTo2(level, actualPos, actualState, dir, remainingAmount);
                flowing_fluids$invalidateFluidSampleCaches(blockPos, pos, actualPos);
            }
        }
    }

    @Unique
    private void ff$legacyFlowToSides(final Level level, final BlockPos blockPos, final FluidState fluidState,
                                      int amount, final BlockState thisState) {
        Direction dir = ff$legacyGetLowestSpreadableLookingFor4BlockDrops(level, blockPos, fluidState, amount, false);
        if (dir == null) {
            return;
        }

        BlockPos immediatePosDir = blockPos.relative(dir);
        FFSectionSampleContext.CellSnapshot immediateCell = ff$getSectionSampleContext().cell(level, immediatePosDir);
        BlockState immediateStateDir = immediateCell.blockState();
        FFHorizontalFlowTarget lateralTarget = flowing_fluids$resolveHorizontalFlowTarget(
                level, immediatePosDir, immediateStateDir, immediateCell.fluidState(), dir);
        BlockPos posDir = immediatePosDir;
        BlockState stateDir = immediateStateDir;
        FluidState lateralFluid = immediateCell.fluidState();
        if (lateralTarget.skippedPassThrough()
                && flowing_fluids$canUseHorizontalPassThroughTarget(level, blockPos, thisState, dir,
                fluidState.getType(), amount, immediatePosDir, immediateStateDir, lateralTarget)) {
            posDir = lateralTarget.targetPos();
            stateDir = lateralTarget.targetState();
            lateralFluid = lateralTarget.targetFluidState();
        }
        int destFluidAmount = lateralFluid.getAmount();

        if (ff$handleWaterLoggedFlowAndReturnIfHandled(level, blockPos, fluidState, amount, thisState, posDir, destFluidAmount, false)) {
            return;
        }

        int difference = amount - destFluidAmount;
        int averageLevel = destFluidAmount + difference / 2;
        int fromAmount = averageLevel;
        int toAmount = averageLevel + ((difference % 2 != 0) ? 1 : 0);

        flowing_fluids$setOrRemoveWaterAmountAt(level, blockPos, fromAmount, thisState, dir);
        flowing_fluids$spreadTo2(level, posDir, stateDir, dir, toAmount);
        flowing_fluids$invalidateFluidSampleCaches(blockPos, immediatePosDir, posDir);
    }

    @Unique
    private int ff$legacyCheckAndFlowDown(final Level level, final BlockPos blockPos, final FluidState fluidState,
                                          final BlockState thisState, final BlockPos posDown,
                                          final BlockState stateDown, int amount) {
        FFDownwardFlowTarget downwardTarget = flowing_fluids$resolveDownwardFlowTarget(level, posDown, stateDown, fluidState.getType());
        BlockPos actualPosDown = downwardTarget.targetPos();
        BlockState actualStateDown = downwardTarget.targetState();
        FluidState downFluidState = downwardTarget.targetFluidState();
        if (flowing_fluids$canUseDownwardPassThroughTarget(level, blockPos, thisState, fluidState, posDown, stateDown, downwardTarget)) {

            if (!downFluidState.isEmpty() && !downFluidState.getType().isSame(fluidState.getType())) {
                flowing_fluids$setOrRemoveWaterAmountAt(level, blockPos, amount - 1, thisState, Direction.DOWN);
                flowing_fluids$spreadTo2(level, actualPosDown, actualStateDown, Direction.DOWN, 1);
                return amount - 1;
            }

            if (FlowingFluids.config.easyPistonPump && FlowingFluids.config.enablePistonPushing) {
                BlockState below = level.getBlockState(actualPosDown.below());
                if (below.is(Blocks.MOVING_PISTON) && below.getValue(DirectionalBlock.FACING) == Direction.UP) {
                    AdaptiveTickScheduler.scheduleFluidTick(level, blockPos, this, 10);
                    FlowingFluids.pistonTick = true;
                    return amount;
                }
            }

            int fluidDownAmount = downFluidState.getAmount();
            if (ff$handleWaterLoggedFlowAndReturnIfHandled(level, blockPos, fluidState, amount, thisState, actualPosDown, fluidDownAmount, true)) {
                return FFFluidUtils.getEffectiveFluidState(level, blockPos).getAmount();
            }

            int amountDestCanAccept = Math.min(8 - fluidDownAmount, amount);
            if (amountDestCanAccept > 0) {
                int destNewAmount = fluidDownAmount + amountDestCanAccept;
                int sourceNewAmount = amount - amountDestCanAccept;
                flowing_fluids$setOrRemoveWaterAmountAt(level, blockPos, sourceNewAmount, thisState, Direction.DOWN);
                flowing_fluids$spreadTo2(level, actualPosDown, actualStateDown, Direction.DOWN, destNewAmount);
                return sourceNewAmount;
            }
        }
        return amount;
    }

    @Unique
    private @Nullable Direction ff$legacyGetLowestSpreadableLookingFor4BlockDrops(
            Level level, BlockPos blockPos, FluidState fluidState, int amount, final boolean requiresSlope) {
        Short2ObjectMap<Pair<BlockState, FluidState>> statesAtPos = ff$getStateCache();
        try {
            Direction[] directionsCanSpreadToSortedByAmount = ff$SPREAD_DIRECTION_BUFFER.get();
            int[] directionAmounts = ff$SPREAD_AMOUNT_BUFFER.get();
            int directionCount = 0;
            boolean anyFlowableNeighbours2LevelsLowerOrMore = requiresSlope;
            BlockState sourceState = level.getBlockState(blockPos);

            for (Direction dir : FFFluidUtils.getCardinalsShuffle(level.random)) {
                BlockPos posDir = blockPos.relative(dir);
                short key = ffCacheKey(blockPos, posDir);
                Pair<BlockState, FluidState> statesDir = flowing_fluids$getSetPosCache(key, level, statesAtPos, posDir);
                int amountDir = statesDir.getSecond().getAmount();
                boolean canFlow = flowing_fluids$canSpreadToOptionallySameOrEmpty(fluidState.getType(), amount, level, blockPos,
                        sourceState, dir, posDir, statesDir.getFirst(), statesDir.getSecond(), requiresSlope);
                if (canFlow) {
                    if (!anyFlowableNeighbours2LevelsLowerOrMore) {
                        anyFlowableNeighbours2LevelsLowerOrMore = amountDir < amount - 1;
                    }
                    int insertAt = directionCount++;
                    while (insertAt > 0 && amountDir < directionAmounts[insertAt - 1]) {
                        directionsCanSpreadToSortedByAmount[insertAt] = directionsCanSpreadToSortedByAmount[insertAt - 1];
                        directionAmounts[insertAt] = directionAmounts[insertAt - 1];
                        insertAt--;
                    }
                    directionsCanSpreadToSortedByAmount[insertAt] = dir;
                    directionAmounts[insertAt] = amountDir;
                }
            }

            if (directionCount == 0) {
                return null;
            }

            boolean requiresSlopeWithOverride = requiresSlope || !anyFlowableNeighbours2LevelsLowerOrMore;
            Direction spreadDirection = ff$legacyGetValidDirectionFromDeepSpreadSearch(level, blockPos, fluidState, amount,
                    requiresSlopeWithOverride, directionsCanSpreadToSortedByAmount, directionCount, statesAtPos);
            if (spreadDirection == null && !requiresSlopeWithOverride) {
                return directionsCanSpreadToSortedByAmount[0];
            }
            return spreadDirection;
        } finally {
            statesAtPos.clear();
        }
    }

    @Unique
    private @Nullable Direction ff$legacyGetValidDirectionFromDeepSpreadSearch(final Level level, final BlockPos blockPos,
                                                                               final FluidState fluidState, final int amount,
                                                                               final boolean requiresSlope,
                                                                               final Direction[] directionsCanSpreadToSortedByAmount,
                                                                               final int directionCount,
                                                                               final Short2ObjectMap<Pair<BlockState, FluidState>> statesAtPos) {
        int slopeFindDistance = getSlopeFindDistance(level);
        if (slopeFindDistance < 1) {
            return null;
        }

        Short2BooleanMap posCanFlowDown = ff$getFlowDownCache();
        try {
            posCanFlowDown.put(ffCacheKey(blockPos, blockPos), false);

            Direction bestDirection = null;
            int bestDistance = Integer.MAX_VALUE;

            for (int i = 0; i < directionCount; i++) {
                Direction dir = directionsCanSpreadToSortedByAmount[i];
                BlockPos posDir = blockPos.relative(dir);
                short key = ffCacheKey(blockPos, posDir);
                int distance;
                if (FFFluidUtils.getEffectiveFluidState(level, posDir).getAmount() < (amount - 1)
                        || flowing_fluids$getSetFlowDownCache(key, level, posCanFlowDown, posDir, fluidState.getType(), amount, requiresSlope)) {
                    distance = 0;
                } else {
                    distance = ff$legacyGetSlopeDistance(level, blockPos, 1, dir.getOpposite(), fluidState.getType(), amount + 1,
                            posDir, statesAtPos, posCanFlowDown, requiresSlope, slopeFindDistance);
                }

                if ((!requiresSlope || distance <= slopeFindDistance) && distance < bestDistance) {
                    bestDistance = distance;
                    bestDirection = dir;
                }
            }

            return bestDirection;
        } finally {
            posCanFlowDown.clear();
        }
    }

    @Unique
    private int ff$legacyGetSlopeDistance(LevelReader level, BlockPos sourcePosForKey, int distance, Direction fromDir,
                                          Fluid sourceFluid, int sourceAmount, BlockPos newPos,
                                          Short2ObjectMap<Pair<BlockState, FluidState>> statesAtPos,
                                          Short2BooleanMap posCanFlowDown, boolean forceSlopeDownSameOrEmpty,
                                          int slopeFindDistance) {
        int smallest = 1000;
        int searchDistance = distance + 1;

        for (Direction searchDir : Direction.Plane.HORIZONTAL) {
            if (searchDir == fromDir) {
                continue;
            }

            BlockPos searchPos = newPos.relative(searchDir);
            short searchKey = ffCacheKey(sourcePosForKey, searchPos);
            Pair<BlockState, FluidState> searchStates = flowing_fluids$getSetPosCache(searchKey, level, statesAtPos, searchPos);

            if (flowing_fluids$canSpreadToOptionallySameOrEmpty(sourceFluid, sourceAmount, level, newPos,
                    level.getBlockState(newPos), searchDir, searchPos, searchStates.getFirst(), searchStates.getSecond(),
                    forceSlopeDownSameOrEmpty)) {

                if (searchStates.getSecond().getAmount() < (sourceAmount - 2)
                        || flowing_fluids$getSetFlowDownCache(searchKey, level, posCanFlowDown, searchPos,
                        sourceFluid, sourceAmount, forceSlopeDownSameOrEmpty)) {
                    return searchDistance;
                }

                if (searchDistance < slopeFindDistance) {
                    int next = ff$legacyGetSlopeDistance(level, sourcePosForKey, searchDistance, searchDir.getOpposite(),
                            sourceFluid, sourceAmount, searchPos, statesAtPos, posCanFlowDown,
                            forceSlopeDownSameOrEmpty, slopeFindDistance);
                    if (next < smallest) {
                        smallest = next;
                    }
                }
            }
        }

        return smallest;
    }

    @Unique
    private void ff$flowToSides(final Level level, final BlockPos blockPos, final FluidState fluidState, int amount, final BlockState thisState, int minimumRetainedAmount) {

        // get a valid direction to move into or null if no spreadable block was found
        Direction dir = flowing_fluids$getLowestSpreadableLookingFor4BlockDrops(level, blockPos, fluidState, amount, false);
        if (dir == null) {
            flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, amount, true);
            return;
        }

        BlockPos immediatePosDir = blockPos.relative(dir);
        FFSectionSampleContext.CellSnapshot immediateCell = ff$getSectionSampleContext().cell(level, immediatePosDir);
        BlockState immediateStateDir = immediateCell.blockState();
        FFHorizontalFlowTarget lateralTarget = flowing_fluids$resolveHorizontalFlowTarget(
                level, immediatePosDir, immediateStateDir, immediateCell.fluidState(), dir);
        BlockPos posDir = immediatePosDir;
        BlockState stateDir = immediateStateDir;
        FluidState lateralFluid = immediateCell.fluidState();
        if (lateralTarget.skippedPassThrough()
                && flowing_fluids$canUseHorizontalPassThroughTarget(level, blockPos, thisState, dir,
                fluidState.getType(), amount, immediatePosDir, immediateStateDir, lateralTarget)) {
            posDir = lateralTarget.targetPos();
            stateDir = lateralTarget.targetState();
            lateralFluid = lateralTarget.targetFluidState();
        }

        // this amount is already confirmed to be less than {amount}
        final int destFluidAmount = lateralFluid.getAmount();

        // must force total flow of fluid because of waterloggables
        if (ff$handleWaterLoggedFlowAndReturnIfHandled(level, blockPos, fluidState, amount, thisState, posDir, destFluidAmount, false))
            return;

        int fromAmount;
        int toAmount;


        final int difference = amount - destFluidAmount;
        WaterFlowProfile waterProfile = fluidState.is(FluidTags.WATER)
                ? flowing_fluids$getWaterFlowProfile(level, blockPos, fluidState, amount)
                : null;
        final float transferBias = flowing_fluids$getProfileTransferBias(level, blockPos, dir, fluidState, amount,
                destFluidAmount, difference, waterProfile);
        final boolean preferThinDryEdgeBalance = fluidState.is(FluidTags.WATER)
                && FluidRegressionLogic.shouldPreferThinDryEdgeBalance(
                amount, destFluidAmount, difference, minimumRetainedAmount);

        if (fluidState.is(FluidTags.WATER)
                && (waterProfile == null || !waterProfile.shouldBypassStableTransferSuppression())
                && flowing_fluids$shouldSuppressStablePoolTransfer(level, blockPos, fluidState, amount, posDir, destFluidAmount, difference, transferBias)) {
            flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, amount, true);
            flowing_fluids$updateStablePoolTracking(level, posDir, fluidState, destFluidAmount, true);
            return;
        }

        if ((waterProfile == null || !waterProfile.shouldBypassStableTransferSuppression())
                && flowing_fluids$shouldSuppressShallowFlatTransfer(level, blockPos, fluidState, amount, posDir,
                destFluidAmount, difference, transferBias, minimumRetainedAmount)) {
            flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, amount, true);
            if (destFluidAmount > 0) {
                flowing_fluids$updateStablePoolTracking(level, posDir, fluidState, destFluidAmount, true);
            }
            return;
        }

        if (waterProfile != null
                && FluidRegressionLogic.shouldDeferConnectedWaterLevelingToEqualizer(
                lateralFluid.getType().isSame(fluidState.getType()),
                waterProfile.isPressureDriven(),
                waterProfile.isInletZone(),
                waterProfile.hasImmediateSurfaceEdge(),
                waterProfile.hasImmediateDownwardOutlet(),
                waterProfile.isFlowActive(),
                waterProfile.flowMomentum(),
                amount,
                destFluidAmount,
                difference,
                minimumRetainedAmount,
                transferBias)) {
            flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, amount, false);
            flowing_fluids$updateStablePoolTracking(level, posDir, fluidState, destFluidAmount, false);
            ParallelFluidEqualizer.enqueue(level, blockPos);
            ParallelFluidEqualizer.enqueue(level, posDir);
            return;
        }

        FFFluidUtils.DiscreteFlowBalance baseBalance = FFFluidUtils.resolveDiscreteFlowBalance(
                amount,
                destFluidAmount,
                minimumRetainedAmount,
                preferThinDryEdgeBalance
                        ? FluidRegressionLogic.getThinDryEdgeDestinationBiasLevels(amount, destFluidAmount)
                        : 0f);
        fromAmount = baseBalance.sourceAmount();
        toAmount = baseBalance.destinationAmount();

        if (difference > 0 && transferBias > 0f && !preferThinDryEdgeBalance) {
            int available = Math.max(0, fromAmount - minimumRetainedAmount);
            float maxExtraTransfer = waterProfile != null && waterProfile.regime() == WaterFlowProfile.Regime.BREACH
                    ? 3
                    : (destFluidAmount <= 0 ? 1 : 2);
            int room = 8 - toAmount;
            float actualBias = Math.min(Math.min(maxExtraTransfer, room), Math.min(available, transferBias));
            if (actualBias > 0f) {
                FFFluidUtils.DiscreteFlowBalance pressuredBalance = FFFluidUtils.resolveDiscreteFlowBalance(
                        amount,
                        destFluidAmount,
                        minimumRetainedAmount,
                        actualBias);
                fromAmount = pressuredBalance.sourceAmount();
                toAmount = pressuredBalance.destinationAmount();
            }
        }

        if (flowing_fluids$shouldPreserveLateralSource(level, blockPos, fluidState, amount, posDir, destFluidAmount,
                fromAmount, toAmount)) {
            int reserve = Math.min(Math.max(1, getDropOff(level)), amount);
            if (toAmount - reserve >= destFluidAmount) {
                fromAmount = reserve;
                toAmount -= reserve;
            }
        }

        boolean changed = fromAmount != amount || toAmount != destFluidAmount;
        flowing_fluids$updateStablePoolTracking(level, blockPos, fluidState, fromAmount, !changed);
        flowing_fluids$updateStablePoolTracking(level, posDir, fluidState, toAmount, !changed);

        FluidMutationBatch.ApplyResult mutationResult = new FluidMutationBatch(level)
                .transfer(blockPos, amount, fromAmount, posDir, destFluidAmount, toAmount, fluidState.getType())
                .apply();
        flowing_fluids$invalidateFluidSampleCaches(blockPos, immediatePosDir, posDir);
        if (!mutationResult.applied()) {
            AdaptiveTickScheduler.scheduleFluidTick(level, blockPos, this, 1);
            return;
        }
        if (fluidState.is(FluidTags.WATER) && FlowingFluids.config.flowInertiaStrength > 0f) {
            if (changed) {
                int moved = Math.max(0, amount - fromAmount);
                float momentum = Mth.clamp((moved / 4.0f) + (transferBias / 6.0f), 0.2f, 1.0f);
                if (waterProfile != null) {
                    momentum = Mth.clamp(momentum + waterProfile.getFlowSpeedMomentumBonus(), 0.2f, 1.0f);
                }
                AdaptiveTickScheduler.recordFlowDirection(level, blockPos, dir, momentum);
                AdaptiveTickScheduler.recordFlowDirection(level, posDir, dir, momentum * 0.65f);
            }
        }
    }

    @Unique
    private float flowing_fluids$getThinEdgeForwardBias(Level level, BlockPos origin, Direction direction,
                                                        FluidState fluidState, int amount) {
        if (!fluidState.is(FluidTags.WATER) || amount <= 1) {
            return 0.15f;
        }
        WaterFlowProfile waterProfile = flowing_fluids$getWaterFlowProfile(level, origin, fluidState, amount);
        float bias = 0.15f
                + waterProfile.getDirectionalTransferBias() * 0.06f
                + waterProfile.getFlowSpeedDirectionalBonus();
        if (flowing_fluids$isRiverTransferZone(level, origin, origin.relative(direction))) {
            bias += 0.03f;
        }
        return Mth.clamp(bias, 0.15f, 0.3f);
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

        float effectiveInertia = inertiaStrength * Math.max(0.15f, inertiaMomentum);
        Direction bestDirection = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        BlockPos.MutableBlockPos sidePos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos belowPos = new BlockPos.MutableBlockPos();

        for (Direction dir : FFFluidUtils.getCardinalsShuffle(level.random)) {
            sidePos.setWithOffset(origin, dir);
            FFSectionSampleContext.CellSnapshot sideCell = ff$getSectionSampleContext().cell(level, sidePos);
            BlockState sideState = sideCell.blockState();
            FluidState sideFluid = sideCell.fluidState();
            if (!sideFluid.isEmpty()) {
                continue;
            }
            if (!flowing_fluids$canSpreadToOptionallySameOrEmpty(sourceFluid, 1, level, origin, originState,
                    dir, sidePos, sideState, sideFluid, true)) {
                continue;
            }

            belowPos.set(sidePos).move(Direction.DOWN);
            FFSectionSampleContext.CellSnapshot belowCell = ff$getSectionSampleContext().cell(level, belowPos);
            BlockState belowState = belowCell.blockState();
            FluidState belowFluid = belowCell.fluidState();
            if (!flowing_fluids$canSpreadToOptionallySameOrEmpty(sourceFluid, 8, level, sidePos, sideState,
                    Direction.DOWN, belowPos, belowState, belowFluid, true)) {
                continue;
            }

            float score = applyBias
                    ? flowing_fluids$getDirectionBias(level, origin, dir, sourceFluid, fluidState.getAmount(), 0,
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
    private FFFlowDownResult flowing_fluids$checkAndFlowDown(final Level level, final BlockPos blockPos, final FluidState fluidState, final BlockState thisState, final BlockPos posDown, final BlockState stateDown, final FluidState fluidDownState, int amount) {
        FFDownwardFlowTarget downwardTarget = flowing_fluids$resolveDownwardFlowTarget(
                level, posDown, stateDown, fluidDownState, fluidState.getType());
        BlockPos actualPosDown = downwardTarget.targetPos();
        BlockState actualStateDown = downwardTarget.targetState();
        FluidState downFState = downwardTarget.targetFluidState();

        if (flowing_fluids$canUseDownwardPassThroughTarget(level, blockPos, thisState, fluidState, posDown, stateDown, downwardTarget)) {

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
                        AdaptiveTickScheduler.scheduleFluidTick(level, blockPos, this, 10);
                        FlowingFluids.pistonTick = true;
                        return new FFFlowDownResult(amount, false, false);
                    }
                }

                // flow into lower space
                int fluidDownAmount = downFState.getAmount();

                if (ff$handleWaterLoggedFlowAndReturnIfHandled(level, blockPos, fluidState, amount, thisState, actualPosDown, fluidDownAmount, true))
                    return new FFFlowDownResult(FFFluidUtils.getEffectiveFluidState(level, blockPos).getAmount(), false, false);

                int amountDestCanAccept = Math.min(8 - fluidDownAmount, amount);

                boolean retainedMinimum = false;
                boolean skipHorizontalSpread = false;
                // Avoid draining the entire source when falling into an empty air column.
                // Leaving at least the drop-off amount in the source keeps lateral equalization active,
                // preventing the upstream section of a canal from staying permanently overfilled.
                if (fluidDownAmount == 0 && actualStateDown.isAir() && amountDestCanAccept == amount) {
                    if (flowing_fluids$shouldRetainDownwardSource(level, blockPos, fluidState, amount)) {
                        int retained = getDropOff(level);
                        WaterFlowProfile waterProfile = fluidState.is(FluidTags.WATER)
                                ? flowing_fluids$getWaterFlowProfile(level, blockPos, fluidState, amount)
                                : null;
                        if (retained > 0 && amount > retained) {
                            if (waterProfile != null) {
                                retained = Math.max(0, retained - waterProfile.getDownwardRetentionRelief());
                            }
                            amountDestCanAccept = amount - retained;
                            retainedMinimum = true;
                        }
                    }
                }
                // can fit some liquid
                if (amountDestCanAccept > 0) {
                    int destNewAmount = fluidDownAmount + amountDestCanAccept;
                    int sourceNewAmount = amount - amountDestCanAccept;
                    // set both amounts
                    flowing_fluids$setOrRemoveWaterAmountAt(level, blockPos, sourceNewAmount, thisState, Direction.DOWN);
                    flowing_fluids$spreadTo2(level, actualPosDown, actualStateDown, Direction.DOWN, destNewAmount);
                    flowing_fluids$invalidateFluidSampleCaches(blockPos, posDown, actualPosDown);
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
        return flowing_fluids$resolveDownwardFlowTarget(level, posDown, stateDown, initialFluid, sourceFluid);
    }

    @Unique
    private FFDownwardFlowTarget flowing_fluids$resolveDownwardFlowTarget(Level level, BlockPos posDown, BlockState stateDown,
                                                                          FluidState initialFluid, Fluid sourceFluid) {
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
    private boolean flowing_fluids$canUseDownwardPassThroughTarget(Level level,
                                                                   BlockPos sourcePos,
                                                                   BlockState sourceState,
                                                                   FluidState sourceFluidState,
                                                                   BlockPos immediatePosDown,
                                                                   BlockState immediateStateDown,
                                                                   FFDownwardFlowTarget target) {
        if (!target.skippedPassThrough()) {
            return flowing_fluids$canSpreadTo(sourceFluidState.getType(), sourceFluidState.getAmount(), level, sourcePos, sourceState,
                    Direction.DOWN, target.targetPos(), target.targetState(), target.targetFluidState());
        }

        FluidState immediateDownFluid = FFFluidUtils.getEffectiveFluidState(level, immediatePosDown, immediateStateDown);
        return flowing_fluids$canSpreadTo(sourceFluidState.getType(), sourceFluidState.getAmount(), level, sourcePos, sourceState,
                Direction.DOWN, immediatePosDown, immediateStateDown, immediateDownFluid)
                && flowing_fluids$canSpreadTo(sourceFluidState.getType(), sourceFluidState.getAmount(), level,
                target.conduitPos(), target.conduitState(), Direction.DOWN,
                target.targetPos(), target.targetState(), target.targetFluidState());
    }

    @Unique
    private FFHorizontalFlowTarget flowing_fluids$resolveHorizontalFlowTarget(Level level, BlockPos posSide, BlockState stateSide, Direction direction) {
        FluidState initialFluid = FFFluidUtils.getEffectiveFluidState(level, posSide, stateSide);
        return flowing_fluids$resolveHorizontalFlowTarget(level, posSide, stateSide, initialFluid, direction);
    }

    @Unique
    private FFHorizontalFlowTarget flowing_fluids$resolveHorizontalFlowTarget(Level level, BlockPos posSide, BlockState stateSide,
                                                                               FluidState initialFluid, Direction direction) {
        if (!direction.getAxis().isHorizontal()
                || !FFFluidUtils.isPassThroughFluidBlock(level, stateSide, direction)
                || !initialFluid.isEmpty()) {
            return new FFHorizontalFlowTarget(posSide, stateSide, initialFluid, posSide, stateSide, false);
        }

        BlockPos.MutableBlockPos cursor = posSide.mutable();
        BlockState cursorState = stateSide;
        final int maxPassThroughDepth = Math.max(1, FlowingFluids.config.maxWaterFlowDistance);

        for (int depth = 0; depth < maxPassThroughDepth; depth++) {
            BlockPos nextPos = cursor.relative(direction);
            BlockState nextState = level.getBlockState(nextPos);
            FluidState nextFluid = FFFluidUtils.getEffectiveFluidState(level, nextPos, nextState);
            if (FFFluidUtils.isPassThroughFluidBlock(level, nextState, direction) && nextFluid.isEmpty()) {
                cursor.set(nextPos);
                cursorState = nextState;
                continue;
            }
            return new FFHorizontalFlowTarget(nextPos.immutable(), nextState, nextFluid, cursor.immutable(), cursorState, true);
        }

        return new FFHorizontalFlowTarget(posSide, stateSide, initialFluid, posSide, stateSide, false);
    }

    @Unique
    private boolean flowing_fluids$canUseHorizontalPassThroughTarget(Level level,
                                                                     BlockPos sourcePos,
                                                                     BlockState sourceState,
                                                                     Direction direction,
                                                                     Fluid sourceFluid,
                                                                     int sourceAmount,
                                                                     BlockPos immediatePos,
                                                                     BlockState immediateState,
                                                                     FFHorizontalFlowTarget target) {
        if (!target.skippedPassThrough()) {
            return true;
        }
        FluidState immediateFluid = FFFluidUtils.getEffectiveFluidState(level, immediatePos, immediateState);
        return flowing_fluids$canSpreadTo(sourceFluid, sourceAmount, level, sourcePos, sourceState,
                direction, immediatePos, immediateState, immediateFluid)
                && flowing_fluids$canSpreadTo(sourceFluid, sourceAmount, level,
                target.conduitPos(), target.conduitState(), direction,
                target.targetPos(), target.targetState(), target.targetFluidState());
    }

    @Unique
    private void flowing_fluids$setOrRemoveWaterAmountAt(final Level level, final BlockPos blockPos, final int amount, final BlockState thisState, Direction direction) {
        if (amount > 0) {
            if (FFFluidUtils.supportsVirtualFluidState(level, thisState)) {
                FFFluidUtils.setFluidStateAtPosToNewAmount(level, blockPos, this, amount);
                return;
            }
            flowing_fluids$spreadTo2(level, blockPos, thisState, direction, amount);
        } else {
            FFFluidUtils.removeAllFluidAtPos(level, blockPos, this);
        }
    }

    @Unique
    private boolean flowing_fluids$hasRetentionAnchor(Level level, BlockPos origin, FluidState sourceState) {
        Fluid sourceFluid = sourceState.getType();
        BlockPos belowPos = origin.below();
        BlockState belowState = level.getBlockState(belowPos);
        FluidState belowFluid = FFFluidUtils.getEffectiveFluidState(level, belowPos, belowState);
        boolean supportedBelow = (belowFluid.getType().isSame(sourceFluid) && belowFluid.getAmount() >= sourceState.getAmount())
                || (!belowState.isAir() && !belowState.canBeReplaced(sourceFluid));
        if (!supportedBelow) {
            return false;
        }

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        mutablePos.set(origin).move(Direction.UP);
        FluidState above = FFFluidUtils.getEffectiveFluidState(level, mutablePos, level.getBlockState(mutablePos));
        if (above.getType().isSame(sourceFluid) && above.getAmount() > 0) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                mutablePos.set(origin).move(dir);
                FluidState neighbor = FFFluidUtils.getEffectiveFluidState(level, mutablePos, level.getBlockState(mutablePos));
                if (neighbor.getType().isSame(sourceFluid) && neighbor.getAmount() > 0) {
                    return true;
                }
            }
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
    private boolean flowing_fluids$hasRecentFlowMomentum(Level level, BlockPos pos, float threshold) {
        if (FlowingFluids.config.flowInertiaStrength <= 0f || FlowingFluids.config.flowInertiaMaxAgeTicks <= 0) {
            return false;
        }
        return AdaptiveTickScheduler.getFlowMomentum(level, pos, FlowingFluids.config.flowInertiaMaxAgeTicks) > threshold;
    }

    @Unique
    private boolean flowing_fluids$shouldRetainDownwardSource(Level level, BlockPos origin, FluidState sourceState, int amount) {
        if (amount <= getDropOff(level)) {
            return false;
        }
        if (AdaptiveTickScheduler.isFlowActiveNow(level, origin)) {
            return false;
        }
        if (flowing_fluids$hasRecentFlowMomentum(level, origin, 0.35f)) {
            return false;
        }
        return flowing_fluids$hasRetentionAnchor(level, origin, sourceState);
    }

    @Unique
    private boolean flowing_fluids$shouldPreserveLateralSource(Level level, BlockPos sourcePos, FluidState sourceState,
                                                               int sourceAmount, BlockPos targetPos, int targetAmount,
                                                               int proposedSourceAmount, int proposedTargetAmount) {
        if (!sourceState.is(FluidTags.WATER)) {
            return false;
        }
        if (proposedSourceAmount > 0 || proposedTargetAmount <= targetAmount) {
            return false;
        }
        int thinAmountCap = Math.max(2, getDropOff(level) + 1);
        if (sourceAmount <= 0 || sourceAmount > thinAmountCap) {
            return false;
        }
        if (flowing_fluids$hasFluidAbove(level, sourcePos, sourceState.getType())) {
            return false;
        }
        if (flowing_fluids$hasImmediateDownwardOutlet(level, sourcePos, sourceState.getType(), sourceAmount)) {
            return false;
        }
        if (FFFluidUtils.isRiverBiome(level.getBiome(sourcePos)) || FFFluidUtils.isRiverBiome(level.getBiome(targetPos))) {
            return true;
        }
        WaterFlowProfile waterProfile = flowing_fluids$getWaterFlowProfile(level, sourcePos, sourceState, sourceAmount);
        if (FluidRegressionLogic.shouldPreserveBroadSurfaceThinSource(
                waterProfile.isBroadSurface(),
                waterProfile.isInletZone(),
                flowing_fluids$hasImmediateSurfaceEdge(level, sourcePos, sourceState.getType()),
                flowing_fluids$hasNearbyStepDownOutlet(level, sourcePos, sourceState.getType(), sourceAmount))) {
            return true;
        }
        if (flowing_fluids$countLateralFluidNeighbors(level, sourcePos, sourceState.getType()) >= 2) {
            return true;
        }
        return targetAmount == 0 && flowing_fluids$hasRetentionAnchor(level, sourcePos, sourceState);
    }

    @Unique
    private int flowing_fluids$countLateralWalls(Level level, BlockPos pos, Fluid sourceFluid) {
        int walls = 0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            mutablePos.set(pos).move(direction);
            BlockState neighborState = level.getBlockState(mutablePos);
            FluidState neighborFluid = FFFluidUtils.getEffectiveFluidState(level, mutablePos, neighborState);
            if (!neighborFluid.isEmpty()) {
                continue;
            }
            if (neighborState.isSolid() && !FFFluidUtils.isPassThroughFluidBlock(level, neighborState, direction)) {
                walls++;
            }
        }
        return walls;
    }

    @Unique
    private int flowing_fluids$countLateralFluidNeighbors(Level level, BlockPos pos, Fluid sourceFluid) {
        int neighbors = 0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            mutablePos.set(pos).move(direction);
            FluidState neighborFluid = FFFluidUtils.getEffectiveFluidState(level, mutablePos, level.getBlockState(mutablePos));
            if (neighborFluid.getType().isSame(sourceFluid) && neighborFluid.getAmount() > 0) {
                neighbors++;
            }
        }
        return neighbors;
    }

    @Unique
    private int flowing_fluids$countLateralEscapeRoutes(Level level, BlockPos pos, BlockState stateAtPos,
                                                        Fluid sourceFluid, int sourceAmount) {
        int routes = 0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            mutablePos.set(pos).move(direction);
            BlockState neighborState = level.getBlockState(mutablePos);
            FluidState neighborFluid = FFFluidUtils.getEffectiveFluidState(level, mutablePos, neighborState);
            if (flowing_fluids$canSpreadToOptionallySameOrEmpty(sourceFluid, Math.max(1, sourceAmount), level,
                    pos, stateAtPos, direction, mutablePos, neighborState, neighborFluid, true)) {
                routes++;
            }
        }
        return routes;
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
            FluidState baseState = blockState.getFluidState();
            FluidState effectiveState = FFFluidUtils.getEffectiveFluidState(level, blockPos, blockState);
            if (!effectiveState.isEmpty()
                    && (!effectiveState.getType().isSame(baseState.getType())
                    || effectiveState.getAmount() != baseState.getAmount())) {
                cir.setReturnValue(getStateForFluidByAmount(effectiveState.getType(), effectiveState.getAmount()));
            }
        }
    }

    @Unique
    private @Nullable Direction flowing_fluids$getLowestSpreadableLookingFor4BlockDrops(
            Level level, BlockPos blockPos, FluidState fluidState, int amount, final boolean requiresSlope) {

        Short2ObjectOpenHashMap<Pair<BlockState, FluidState>> statesAtPos = ff$getStateCache();
        Short2BooleanMap quickFlowDown = ff$getFlowDownCache();
        quickFlowDown.clear();
        ff$ASYNC_SLOPE_PENDING.get()[0] = false;
        try {
            Direction[] shuffled = FFFluidUtils.getCardinalsShuffle(level.random);
            Direction[] validDirections = ff$SPREAD_DIRECTION_BUFFER.get();
            int[] neighbourAmounts = ff$SPREAD_AMOUNT_BUFFER.get();
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
                    if (flowing_fluids$getSetFlowDownCache(key, level, quickFlowDown, mutablePos.immutable(), fluidState.getType(), amount, requiresSlope)) {
                        immediateLowDir = direction;
                        break;
                    }
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

            if (ff$ASYNC_SLOPE_PENDING.get()[0]) {
                return null;
            }

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
            if (affinityStrength > 0f || inertiaStrength > 0f) {
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
            ff$ASYNC_SLOPE_PENDING.get()[0] = false;
            statesAtPos.clear();
            quickFlowDown.clear();
        }
    }


    @Unique
    private @Nullable Direction flowing_fluids$getValidDirectionFromDeepSpreadSearch(final Level level, final BlockPos blockPos, final FluidState fluidState, final int amount, final boolean requiresSlope, final Direction[] directionsCanSpreadToSortedByAmount, final int[] directionAmounts, final int directionCount, final Short2ObjectOpenHashMap<Pair<BlockState, FluidState>> statesAtPos) {

        int slopeFindDistance = getSlopeFindDistance(level);
        if (slopeFindDistance < 1) return null;
        WaterFlowProfile waterProfile = fluidState.is(FluidTags.WATER)
                ? flowing_fluids$getWaterFlowProfile(level, blockPos, fluidState, amount)
                : null;
        boolean nearbyStepDownOutlet = fluidState.is(FluidTags.WATER)
                && flowing_fluids$hasNearbyStepDownOutlet(level, blockPos, fluidState.getType(), amount);
        if (requiresSlope && flowing_fluids$shouldSuppressExploratorySpread(level, blockPos, fluidState, amount, waterProfile)) {
            return null;
        }
        BlockState sourceState = level.getBlockState(blockPos);

        // Keep full slope search distance even for low fluid amounts so ledges further away
        // are still discovered. Reducing this distance to half (as before) limited searches
        // to 2 blocks for thin streams, making water ignore nearby drops.
        int adaptiveSlopeFindDistance = slopeFindDistance;
        HierarchicalDistanceManager manager = null;
        HierarchicalDistanceManager.RangeTier rangeTier = HierarchicalDistanceManager.RangeTier.NEAR;
        if (fluidState.is(FluidTags.WATER)) {
            manager = HierarchicalDistanceManager.getInstance();
            rangeTier = manager.getSimulationTier(blockPos, level);
            int effectiveDistance = slopeFindDistance;
            if (FlowingFluids.config.enableAdaptiveFlowDistance) {
                HierarchicalDistanceManager.TerrainType terrainType = manager.estimateTerrainType(blockPos, level);
                effectiveDistance = manager.getEffectiveFlowDistance(effectiveDistance, terrainType, FlowingFluids.config);

                var biome = level.getBiome(blockPos);
                boolean broadWaterBiome = FFFluidUtils.isOceanBiome(biome) || FFFluidUtils.isBeachBiome(biome);
                if (broadWaterBiome && blockPos.getY() >= FFFluidUtils.seaLevel(level) - 2) {
                    int oceanClamp = Math.max(2, Math.min(FlowingFluids.config.waterFlowDistance,
                            Math.max(1, FlowingFluids.config.maxWaterFlowDistance)));
                    effectiveDistance = Math.min(effectiveDistance, oceanClamp);
                }
            }

            int broadClamp = FlowingFluids.config.broadSurfaceSlopeClamp;
            if (waterProfile != null) {
                broadClamp = Math.max(1, FlowingFluids.config.broadSurfaceSlopeClamp
                        + (waterProfile.isBroadSurface() && !waterProfile.isRiverLikeBiome() ? 0 : 1));
                effectiveDistance = waterProfile.refineSlopeDistance(effectiveDistance,
                        manager.getCorridorSearchClamp(rangeTier), broadClamp);
            } else if (flowing_fluids$isBroadSurfaceWater(level, blockPos, fluidState, amount)) {
                boolean oceanLikeBiome = FFFluidUtils.isOceanBiome(level.getBiome(blockPos))
                        || FFFluidUtils.isBeachBiome(level.getBiome(blockPos));
                effectiveDistance = Math.min(effectiveDistance,
                        Math.max(1, FlowingFluids.config.broadSurfaceSlopeClamp + (oceanLikeBiome ? 0 : 1)));
            }

            float multiplier = FlowingFluids.config.slopeFindDistanceMultiplier;
            if (multiplier <= 0.0f) {
                multiplier = 0.1f;
            }

            int scaled = Math.round(effectiveDistance * multiplier);
            int maxDistance = Math.max(1, FlowingFluids.config.maxWaterFlowDistance);
            int maxScaled = Math.max(1, Math.round(maxDistance * multiplier));
            adaptiveSlopeFindDistance = Math.max(1, Math.min(maxScaled, scaled));
            adaptiveSlopeFindDistance = FluidRegressionLogic.keepSlopeSearchResponsiveForConnectedFlow(
                    adaptiveSlopeFindDistance,
                    FlowingFluids.config.stepDownSearchDistance,
                    FlowingFluids.config.waterFlowDistance,
                    FlowingFluids.config.maxWaterFlowDistance,
                    nearbyStepDownOutlet,
                    waterProfile != null && waterProfile.isPressureDriven(),
                    waterProfile != null && waterProfile.isInletZone(),
                    waterProfile != null && waterProfile.isFlowActive(),
                    waterProfile != null ? waterProfile.flowMomentum() : 0.0f,
                    amount,
                    waterProfile != null && waterProfile.isBroadSurface());

        }
        adaptiveSlopeFindDistance = FluidActivityTracker.getAdaptiveSlopeFindDistance(level, blockPos, adaptiveSlopeFindDistance);

        // Set per-search recursion budget: proportional to search distance, with a floor to still find nearby drops.
        int[] slopeBudget = ff$SLOPE_SEARCH_BUDGET.get();
        int previousSlopeBudget = slopeBudget[0];
        slopeBudget[0] = Math.max(24, adaptiveSlopeFindDistance * 6);

        Short2BooleanOpenHashMap posCanFlowDown = ff$getFlowDownCache();
        posCanFlowDown.put(ffCacheKey(blockPos, blockPos), false);
        boolean useAsyncSlopePlanning = fluidState.is(FluidTags.WATER)
                && manager != null
                && manager.shouldUseMacroFluidModel(rangeTier)
                && adaptiveSlopeFindDistance > 2
                && AsyncSlopeSearchPlanner.canUseAsyncPlanning(level, blockPos, sourceState,
                directionsCanSpreadToSortedByAmount, directionCount);

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
            // Get chunk position for cache
            var chunkPos = new net.minecraft.world.level.ChunkPos(blockPos);
            boolean pendingAsyncSlope = false;

            for (int i = 0; i < directionCount; i++) {
                Direction dir = directionsCanSpreadToSortedByAmount[i];
                mutablePos.set(blockPos.getX(), blockPos.getY(), blockPos.getZ());
                mutablePos.move(dir);
                short key = ffCacheKey(blockPos, mutablePos);

                // Early exit: if we found a much lower neighbor or can flow down, return immediately
                if (directionAmounts[i] < amount - 1 || flowing_fluids$getSetFlowDownCache(key, level, posCanFlowDown, mutablePos, sourceFluid, amount, requiresSlope)) {
                    return dir;
                }

                // Check cache first for slope distance
                int distance = ChunkLocalSlopeCache.getCached(level, chunkPos, blockPos, adaptiveSlopeFindDistance, dir);

                if (distance == -1) {
                    if (useAsyncSlopePlanning) {
                        Integer plannedDistance = AsyncSlopeSearchPlanner.tryResolveOrSchedule(
                                level,
                                blockPos,
                                dir,
                                sourceFluid,
                                amount + 1,
                                requiresSlope,
                                adaptiveSlopeFindDistance);
                        if (plannedDistance == null) {
                            pendingAsyncSlope = true;
                            continue;
                        }
                        distance = plannedDistance;
                    } else {
                        // Cache miss: calculate and store
                        long bfsStartNanos = FlowingFluids.config.enablePerformanceMonitoring ? System.nanoTime() : 0L;
                        int budgetBeforeSearch = slopeBudget[0];
                        distance = flowing_fluids$getSlopeDistance(
                                level, blockPos, 1, dir.getOpposite(),
                                sourceFluid, amount + 1, mutablePos.immutable(), statesAtPos,
                                posCanFlowDown, requiresSlope, adaptiveSlopeFindDistance);
                        if (FlowingFluids.config.enablePerformanceMonitoring) {
                            int visitedEstimate = Math.max(1, budgetBeforeSearch - slopeBudget[0]);
                            FluidPerformanceMonitor.getInstance().recordBFS(
                                    System.nanoTime() - bfsStartNanos,
                                    visitedEstimate,
                                    Math.min(adaptiveSlopeFindDistance, Math.max(1, distance)));
                        }
                    }

                    // Store in cache for future use
                    ChunkLocalSlopeCache.putCached(level, chunkPos, blockPos, adaptiveSlopeFindDistance, dir, distance);
                }

                if (!requiresSlope || distance <= adaptiveSlopeFindDistance) {
                    float bias = applyBias
                            ? flowing_fluids$getDirectionBias(level, blockPos, dir, sourceFluid, amount, directionAmounts[i],
                            inertiaDir, affinityStrength, inertiaStrength * Math.max(0.15f, inertiaMomentum))
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

            if (pendingAsyncSlope) {
                ff$ASYNC_SLOPE_PENDING.get()[0] = true;
                int desiredDelay = rangeTier == HierarchicalDistanceManager.RangeTier.DISTANT ? 4 : 2;
                int deferredDelay = manager != null
                        ? manager.alignDelayToUpdateInterval(blockPos, level, level.getGameTime(),
                        Math.max(1, FlowingFluids.config.waterFlowDistance), desiredDelay, FlowingFluids.config)
                        : desiredDelay;
                AdaptiveTickScheduler.scheduleFluidTick(level, blockPos, this, Math.max(1, deferredDelay));
                return null;
            }

            return bestDirection;
        } finally {
            slopeBudget[0] = previousSlopeBudget;
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
                            || flowing_fluids$getSetFlowDownCache(searchKey, level, posCanFlowDown, searchPos, sourceFluid, sourceAmount, forceSlopeDownSameOrEmpty)) {
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
            FluidState fluidState = level instanceof LevelAccessor accessor
                    ? FFFluidUtils.getEffectiveFluidState(accessor, pos, blockState)
                    : blockState.getFluidState();
            return Pair.of(blockState, fluidState);
        });
    }

    @Unique
    private boolean flowing_fluids$getSetFlowDownCache(short key, LevelReader level, Short2BooleanMap boolAtPos, BlockPos pos, Fluid sourceFluid, int sourceAmount, boolean forceSlopeDownSameOrEmpty) {
        return boolAtPos.computeIfAbsent(key, (sx) -> {
            if (!(level instanceof LevelAccessor accessor) || !(level instanceof Level concreteLevel)) {
                return false;
            }
            BlockState stateAtPos = level.getBlockState(pos);
            FluidState fluidAtPos = FFFluidUtils.getStateForFluidByAmount(sourceFluid, Math.max(1, sourceAmount));
            BlockPos posDown = pos.below();
            BlockState downState = level.getBlockState(posDown);
            FFDownwardFlowTarget downwardTarget = flowing_fluids$resolveDownwardFlowTarget(concreteLevel, posDown, downState, sourceFluid);
            if (!flowing_fluids$canUseDownwardPassThroughTarget(concreteLevel, pos, stateAtPos, fluidAtPos, posDown, downState, downwardTarget)) {
                return false;
            }
            return flowing_fluids$canSpreadToOptionallySameOrEmpty(sourceFluid, Math.max(1, fluidAtPos.getAmount()), level,
                    pos, stateAtPos, Direction.DOWN, downwardTarget.targetPos(), downwardTarget.targetState(),
                    downwardTarget.targetFluidState(), forceSlopeDownSameOrEmpty);
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
        float bestScore = Float.MAX_VALUE;
        Direction bestDirection = null;
        for (int i = 0; i < count; i++) {
            Direction dir = directions[i];
            float bias = flowing_fluids$getDirectionBias(level, origin, dir, sourceFluid, sourceAmount, neighbourAmounts[i],
                    inertiaDir, affinityStrength, inertiaStrength);
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
                                                  int sourceAmount, int targetAmount,
                                                  @Nullable Direction inertiaDir, float affinityStrength, float inertiaStrength) {
        float bias = 0f;
        if (affinityStrength > 0f) {
            bias += flowing_fluids$getWaterAffinityBias(level, origin, dir, sourceFluid, affinityStrength);
        }
        bias += flowing_fluids$getHydraulicGuideBias(level, origin, dir, sourceFluid, sourceAmount, targetAmount);
        if (inertiaStrength > 0f && inertiaDir != null && inertiaDir == dir) {
            bias += inertiaStrength;
        }
        return bias;
    }

    @Unique
    private float flowing_fluids$getProfileTransferBias(Level level, BlockPos origin, Direction dir, FluidState fluidState,
                                                        int sourceAmount, int targetAmount, int difference,
                                                        @Nullable WaterFlowProfile waterProfile) {
        if (!fluidState.is(FluidTags.WATER) || sourceAmount <= 0 || difference <= 0) {
            return 0f;
        }
        WaterFlowProfile profile = waterProfile != null
                ? waterProfile
                : flowing_fluids$getWaterFlowProfile(level, origin, fluidState, sourceAmount);
        float bias = profile.getDirectionalTransferBias() + profile.getFlowSpeedTransferBonus();
        if (targetAmount <= 0) {
            bias += 0.1f;
        }
        if (difference >= 4) {
            bias += 0.15f;
        }
        if (flowing_fluids$isRiverTransferZone(level, origin, origin.relative(dir))) {
            bias += 0.05f;
        }
        if (profile.isPressureDriven()) {
            bias += flowing_fluids$getInternalGradientTransferBias(level, origin, dir, sourceAmount, targetAmount);
        }
        bias += flowing_fluids$getHydraulicGuideBias(level, origin, dir, fluidState.getType(), sourceAmount, targetAmount);
        bias += flowing_fluids$getCavityPressureBias(level, origin, dir, fluidState, sourceAmount, targetAmount, profile);
        bias += flowing_fluids$getRememberedPoolLevelBias(level, origin, origin.relative(dir), sourceAmount, targetAmount);
        return Mth.clamp(bias, 0.0f, 2.0f);
    }

    @Unique
    private float flowing_fluids$getRememberedPoolLevelBias(Level level, BlockPos sourcePos, BlockPos targetPos,
                                                            int sourceAmount, int targetAmount) {
        float targetRestore = AdaptiveTickScheduler.getPoolLevelRestoringBias(level, targetPos, targetAmount);
        float sourceResistance = AdaptiveTickScheduler.getPoolLevelDrainResistance(level, sourcePos, sourceAmount);
        return Mth.clamp(targetRestore - sourceResistance, -0.36f, 0.54f);
    }

    @Unique
    private float flowing_fluids$getCavityPressureBias(Level level, BlockPos origin, Direction dir, FluidState fluidState,
                                                       int sourceAmount, int targetAmount, WaterFlowProfile profile) {
        if (!FlowingFluids.config.enableCavityPressureRise
                || FlowingFluids.config.cavityPressureStrength <= 0.0f
                || !fluidState.is(FluidTags.WATER)) {
            return 0.0f;
        }

        BlockPos targetPos = origin.relative(dir);
        BlockState targetState = level.getBlockState(targetPos);
        int lateralEscapeRoutes = flowing_fluids$countLateralEscapeRoutes(
                level,
                targetPos,
                targetState,
                fluidState.getType(),
                Math.max(1, sourceAmount)
        );
        boolean supportedBelow = flowing_fluids$hasSupportedBase(level, targetPos, fluidState.getType());
        boolean immediateDownwardOutlet = flowing_fluids$hasImmediateDownwardOutlet(level, targetPos, fluidState.getType(), Math.max(1, sourceAmount));
        boolean targetRoofed = FFFluidUtils.hasRoofWithin(level, targetPos, FlowingFluids.config.shadeRoofSearchHeight);
        int lateralWaterNeighbors = flowing_fluids$countLateralFluidNeighbors(level, targetPos, fluidState.getType());
        int connectedHeadBlocks = flowing_fluids$getConnectedHeadDelta(level, origin, fluidState.getType());
        if (sourceAmount < 4
                && connectedHeadBlocks <= 0
                && profile.getFlowSpeedTransferBonus() <= 0.04f
                && profile.flowMomentum() < 0.18f) {
            return 0.0f;
        }

        return FluidRegressionLogic.computeCavityPressureBias(
                sourceAmount,
                profile.getFlowSpeedTransferBonus(),
                profile.flowMomentum(),
                profile.isSubterranean(),
                targetRoofed,
                supportedBelow,
                immediateDownwardOutlet,
                lateralEscapeRoutes,
                lateralWaterNeighbors,
                connectedHeadBlocks,
                FlowingFluids.config.cavityPressureStrength,
                FlowingFluids.config.connectedHeadStrength
        );
    }

    @Unique
    private int flowing_fluids$getConnectedHeadDelta(Level level, BlockPos origin, Fluid sourceFluid) {
        if (FlowingFluids.config.connectedHeadStrength <= 0.0f) {
            return 0;
        }
        if (!(sourceFluid instanceof FlowingFluid flowingSourceFluid)) {
            return 0;
        }
        int horizontalSampleDistance = Math.max(0, FlowingFluids.config.hydraulicSampleDistance);
        if (horizontalSampleDistance <= 0) {
            return 0;
        }

        Long2IntOpenHashMap cache = ff$getConnectedHeadCache();
        long originKey = origin.asLong();
        int cached = cache.get(originKey);
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }

        LongArrayFIFOQueue queue = ff$CONNECTED_HEAD_QUEUE.get();
        LongOpenHashSet visited = ff$CONNECTED_HEAD_VISITED.get();
        Long2IntOpenHashMap depths = ff$CONNECTED_HEAD_DEPTHS.get();
        queue.clear();
        visited.clear();
        depths.clear();

        queue.enqueue(originKey);
        visited.add(originKey);
        depths.put(originKey, 0);

        FluidState originFluid = FFFluidUtils.getEffectiveFluidState(level, origin, level.getBlockState(origin));
        float originSurface = origin.getY() + originFluid.getHeight(level, origin);
        float highestSurface = originSurface;
        double openSpillHead = Double.POSITIVE_INFINITY;
        int verticalRiseBudget = Math.max(horizontalSampleDistance * 4, FlowingFluids.config.shadeRoofSearchHeight + 6);
        int verticalDropBudget = Math.max(2, horizontalSampleDistance);
        int maxNodes = Math.max(24, horizontalSampleDistance * 24);
        int explored = 0;
        BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();

        while (!queue.isEmpty() && explored < maxNodes) {
            long currentKey = queue.dequeueLong();
            int depth = depths.remove(currentKey);
            currentPos.set(BlockPos.getX(currentKey), BlockPos.getY(currentKey), BlockPos.getZ(currentKey));

            BlockState currentState = level.getBlockState(currentPos);
            FluidState currentFluid = FFFluidUtils.getEffectiveFluidState(level, currentPos, currentState);
            if (!currentFluid.getType().isSame(sourceFluid) || currentFluid.getAmount() <= 0) {
                continue;
            }

            explored++;
            highestSurface = Math.max(highestSurface, currentPos.getY() + currentFluid.getHeight(level, currentPos));
            openSpillHead = Math.min(openSpillHead, FFFluidUtils.getWaterOpenSpillHead(
                    level, currentPos, currentFluid, flowingSourceFluid));
            if (depth >= horizontalSampleDistance + verticalRiseBudget) {
                continue;
            }

            for (Direction direction : Direction.values()) {
                neighborPos.set(currentPos).move(direction);
                int horizontalDistance = Math.abs(neighborPos.getX() - origin.getX())
                        + Math.abs(neighborPos.getZ() - origin.getZ());
                int verticalDelta = neighborPos.getY() - origin.getY();
                if (horizontalDistance > horizontalSampleDistance
                        || verticalDelta > verticalRiseBudget
                        || verticalDelta < -verticalDropBudget) {
                    continue;
                }

                long neighborKey = neighborPos.asLong();
                if (visited.contains(neighborKey)) {
                    continue;
                }

                BlockState neighborState = level.getBlockState(neighborPos);
                FluidState neighborFluid = FFFluidUtils.getEffectiveFluidState(level, neighborPos, neighborState);
                if (neighborFluid.getType().isSame(sourceFluid)
                        && neighborFluid.getAmount() > 0
                        && FFFluidUtils.canTraverseFluidAdjacency(level, currentPos, currentState, currentFluid,
                        direction, neighborPos, neighborState, neighborFluid, flowingSourceFluid)) {
                    visited.add(neighborKey);
                    queue.enqueue(neighborKey);
                    depths.put(neighborKey, depth + 1);
                }
            }
        }

        queue.clear();
        visited.clear();
        depths.clear();

        float cappedHighestSurface = Double.isFinite(openSpillHead)
                ? (float) Math.min(highestSurface, openSpillHead)
                : highestSurface;
        int delta = Math.max(0, Mth.ceil(cappedHighestSurface - originSurface));
        cache.put(originKey, delta);
        return delta;
    }

    @Unique
    private boolean flowing_fluids$hasSupportedBase(Level level, BlockPos pos, Fluid sourceFluid) {
        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        FluidState belowFluid = FFFluidUtils.getEffectiveFluidState(level, belowPos, belowState);
        return (belowFluid.getType().isSame(sourceFluid) && belowFluid.getAmount() > 0)
                || (!belowState.isAir() && !belowState.canBeReplaced(sourceFluid));
    }

    @Unique
    private boolean flowing_fluids$shouldSuppressStablePoolTransfer(Level level, BlockPos sourcePos, FluidState sourceState,
                                                                    int sourceAmount, BlockPos targetPos, int targetAmount, int difference,
                                                                    float pressureHeadDelta) {
        if (difference <= 0 || difference > 2) {
            return false;
        }
        if (flowing_fluids$isRiverTransferZone(level, sourcePos, targetPos)) {
            return false;
        }
        if (FluidRegressionLogic.shouldBypassBroadSurfaceTransferSuppression(sourceAmount, targetAmount)) {
            return false;
        }
        boolean sourceStillReservoir = flowing_fluids$isCalmBroadSurfaceInterior(level, sourcePos, sourceState, sourceAmount);
        boolean targetStillReservoir = targetAmount > 0
                && flowing_fluids$isCalmBroadSurfaceInterior(level, targetPos, sourceState, targetAmount);
        if (FluidRegressionLogic.shouldApplyCalmBroadSurfaceTransferSuppression(sourceStillReservoir, targetStillReservoir)) {
            if (pressureHeadDelta > 1.25f) {
                return false;
            }
            if (AdaptiveTickScheduler.isFlowActiveNow(level, sourcePos)
                    || AdaptiveTickScheduler.isFlowActiveNow(level, targetPos)) {
                return false;
            }
            if (flowing_fluids$hasImmediateSurfaceEdge(level, sourcePos, sourceState.getType())
                    || flowing_fluids$hasImmediateSurfaceEdge(level, targetPos, sourceState.getType())) {
                return false;
            }
            // 谿�E�蟾�E�蜁E��蜿�E�縺後≠繧九�E繧画椛蛻�E�縺励↑縺・
            if (flowing_fluids$hasNearbyStepDownOutlet(level, sourcePos, sourceState.getType(), sourceAmount)
                    || flowing_fluids$hasNearbyStepDownOutlet(level, targetPos, sourceState.getType(), Math.max(1, targetAmount))) {
                return false;
            }
            return true;
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
        if (flowing_fluids$isRiverTransferZone(level, sourcePos, targetPos)) {
            return false;
        }
        if (minimumRetainedAmount > 0) {
            return false;
        }
        if (FluidRegressionLogic.shouldBypassBroadSurfaceTransferSuppression(sourceAmount, targetAmount)) {
            return false;
        }
        boolean sourceStillReservoir = flowing_fluids$isCalmBroadSurfaceInterior(level, sourcePos, sourceState, sourceAmount);
        boolean targetStillReservoir = targetAmount > 0
                && flowing_fluids$isCalmBroadSurfaceInterior(level, targetPos, sourceState, targetAmount);
        if (FluidRegressionLogic.shouldApplyCalmBroadSurfaceTransferSuppression(sourceStillReservoir, targetStillReservoir)) {
            if (difference <= 0 || difference > 2) {
                return false;
            }
            if (pressureHeadDelta > 1.25f) {
                return false;
            }
            if (AdaptiveTickScheduler.isFlowActiveNow(level, sourcePos)
                    || AdaptiveTickScheduler.isFlowActiveNow(level, targetPos)) {
                return false;
            }
            if (flowing_fluids$hasImmediateSurfaceEdge(level, sourcePos, sourceState.getType())
                    || flowing_fluids$hasImmediateSurfaceEdge(level, targetPos, sourceState.getType())) {
                return false;
            }
            if (flowing_fluids$hasNearbyStepDownOutlet(level, sourcePos, sourceState.getType(), sourceAmount)
                    || flowing_fluids$hasNearbyStepDownOutlet(level, targetPos, sourceState.getType(), Math.max(1, targetAmount))) {
                return false;
            }
            return true;
        }
        if (difference <= 0 || difference > 3) {
            return false;
        }
        if (FluidRegressionLogic.shouldPreserveThinShallowFlow(sourceAmount, targetAmount, difference)) {
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
        if (flowing_fluids$hasNearbyStepDownOutlet(level, sourcePos, sourceState.getType(), sourceAmount)
                || flowing_fluids$hasNearbyStepDownOutlet(level, targetPos, sourceState.getType(), Math.max(1, targetAmount))) {
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
    private boolean flowing_fluids$isRiverTransferZone(Level level, BlockPos sourcePos, BlockPos targetPos) {
        return FFFluidUtils.isRiverBiome(level.getBiome(sourcePos))
                || FFFluidUtils.isRiverBiome(level.getBiome(targetPos));
    }

    @Unique
    private boolean flowing_fluids$isBroadSurfaceWater(Level level, BlockPos pos, FluidState fluidState, int amount) {
        return flowing_fluids$getWaterFlowProfile(level, pos, fluidState, amount).isBroadSurface();
    }

    @Unique
    private boolean flowing_fluids$isCalmBroadSurfaceInterior(Level level, BlockPos pos, FluidState fluidState, int amount) {
        return flowing_fluids$getWaterFlowProfile(level, pos, fluidState, amount).isStillReservoir();
    }

    @Unique
    private int flowing_fluids$getBroadSurfaceInteriorDelay(Level level, BlockPos pos, FluidState fluidState, int baseDelay) {
        return flowing_fluids$getWaterFlowProfile(level, pos, fluidState, fluidState.getAmount()).getStableInteriorDelay(baseDelay);
    }

    @Unique
    private boolean flowing_fluids$hasImmediateSurfaceEdge(Level level, BlockPos pos, Fluid sourceFluid) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, dir);
            BlockState state = level.getBlockState(cursor);
            FluidState neighbor = FFFluidUtils.getEffectiveFluidState(level, cursor, state);
            if (neighbor.isEmpty() && (state.isAir() || state.canBeReplaced(sourceFluid))) {
                return true;
            }
        }
        return false;
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

    /**
     * 霁E��驥冗沿: 荳区婿蜷代↓豬√ｌ繧峨�E�繧九°縺�E�邁E��譏薙メ繧�E�繝�EぁE
     * canSpreadToOptionallySameOrEmpty 縺�E�莉｣繧上ｊ縺�E�菴�E�逕ｨ縺励※鬮倬溷喧
     */
    @Unique
    private boolean flowing_fluids$canFlowDownFast(Level level, BlockState belowState, FluidState belowFluid, Fluid sourceFluid, int sourceAmount) {
        // Lightweight path probe used by nearby step-down detection.
        if (belowState.isAir()) return true;

        if (belowFluid.isEmpty()) {
            if (FFFluidUtils.isPassThroughFluidBlock(level, belowState, Direction.DOWN)) {
                return true;
            }
            return belowState.canBeReplaced(sourceFluid);
        }

        if (belowFluid.getType().isSame(sourceFluid)) {
            return belowFluid.getAmount() < sourceAmount;
        }

        return false;
    }

    @Unique
    private boolean flowing_fluids$canFlowSideFast(Level level, BlockState sideState, FluidState sideFluid, Fluid sourceFluid, int sourceAmount, Direction direction) {
        if (sideState.isAir()) return true;

        if (sideFluid.isEmpty()) {
            if (FFFluidUtils.isPassThroughFluidBlock(level, sideState, direction)) {
                return true;
            }
            return sideState.canBeReplaced(sourceFluid);
        }

        if (sideFluid.getType().isSame(sourceFluid)) {
            return sideFluid.getAmount() < sourceAmount;
        }

        return false;
    }

    @Unique
    private boolean flowing_fluids$hasNearbyStepDownOutlet(Level level, BlockPos pos, Fluid sourceFluid, int sourceAmount) {
        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        FluidState belowFluid = FFFluidUtils.getEffectiveFluidState(level, belowPos, belowState);

        if (flowing_fluids$canFlowDownFast(level, belowState, belowFluid, sourceFluid, sourceAmount)) {
            return true;
        }

        int searchDistance = Math.max(1, Math.min(3, FlowingFluids.config.stepDownSearchDistance));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos cursorBelow = new BlockPos.MutableBlockPos();

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            cursor.set(pos);
            int currentAmount = sourceAmount;

            for (int step = 1; step <= searchDistance; step++) {
                cursor.move(dir);
                BlockState sideState = level.getBlockState(cursor);
                FluidState sideFluid = FFFluidUtils.getEffectiveFluidState(level, cursor, sideState);

                if (!flowing_fluids$canFlowSideFast(level, sideState, sideFluid, sourceFluid, currentAmount, dir)) {
                    break;
                }

                cursorBelow.set(cursor).move(Direction.DOWN);
                BlockState sideBelowState = level.getBlockState(cursorBelow);
                FluidState sideBelowFluid = FFFluidUtils.getEffectiveFluidState(level, cursorBelow, sideBelowState);

                int reducedAmount = Math.max(1, currentAmount - 1);
                if (flowing_fluids$canFlowDownFast(level, sideBelowState, sideBelowFluid, sourceFluid, reducedAmount)) {
                    return true;
                }

                currentAmount = reducedAmount;
                if (currentAmount <= 0) {
                    break;
                }
            }
        }

        return false;
    }

    @Unique
    private boolean flowing_fluids$shouldSuppressThinCapDrift(Level level, BlockPos pos, FluidState fluidState, int amount) {
        if (!fluidState.is(FluidTags.WATER)) {
            return false;
        }
        if (amount <= 0 || amount > getDropOff(level)) {
            return false;
        }
        if (AdaptiveTickScheduler.isFlowActiveNow(level, pos)) {
            return false;
        }
        if (flowing_fluids$hasRecentFlowMomentum(level, pos, 0.35f)) {
            return false;
        }

        if (flowing_fluids$hasNearbyStepDownOutlet(level, pos, fluidState.getType(), amount)) {
            return false;
        }

        if (FFFluidUtils.isSmallSupportedThinSurfaceCluster(level, pos, fluidState.getType(), 3, getDropOff(level))) {
            return true;
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
    private boolean flowing_fluids$handleThinWaterFragment(ServerLevel level, BlockPos pos,
                                                           BlockState state, FluidState fluidState) {
        if (!(fluidState.getType() instanceof FlowingFluid flowingFluid)
                || !flowingFluid.isSame(Fluids.WATER)
                || fluidState.getAmount() <= 0
                || fluidState.getAmount() > Math.min(2, Math.max(1, getDropOff(level)))) {
            return false;
        }
        if (FFFluidUtils.isInOrNearInfiniteBiome(level, pos, 2)
                || flowing_fluids$hasFluidAbove(level, pos, flowingFluid)
                || flowing_fluids$hasNearbyStepDownOutlet(level, pos, flowingFluid, fluidState.getAmount())) {
            return false;
        }
        if (!flowing_fluids$tryConsumeThinFragmentReclaimBudget(level, pos)) {
            return true;
        }

        if (flowing_fluids$tryReclaimThinWaterIntoNeighbor(level, pos, state, fluidState, flowingFluid)) {
            return true;
        }

        int sleepyDelay = 16 + Math.floorMod((int) (pos.asLong() ^ level.getGameTime()), 16);
        AdaptiveTickScheduler.scheduleFluidTick(level, pos, this, sleepyDelay);
        flowing_fluids$updateStablePoolTracking(level, pos, fluidState, fluidState.getAmount(), true);
        return true;
    }

    @Unique
    private boolean flowing_fluids$tryReclaimThinWaterIntoNeighbor(ServerLevel level, BlockPos pos,
                                                                   BlockState state, FluidState fluidState,
                                                                   FlowingFluid flowingFluid) {
        Direction bestDirection = null;
        BlockPos bestPos = null;
        BlockState bestState = null;
        FluidState bestFluid = null;
        int bestScore = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.values()) {
            if (direction == Direction.UP) {
                continue;
            }
            cursor.setWithOffset(pos, direction);
            BlockState neighborState = level.getBlockState(cursor);
            FluidState neighborFluid = FFFluidUtils.getEffectiveFluidState(level, cursor, neighborState);
            if (!neighborFluid.getType().isSame(flowingFluid)
                    || neighborFluid.getAmount() < fluidState.getAmount()
                    || neighborFluid.getAmount() >= 8
                    || !FFFluidUtils.canTraverseFluidAdjacency(level, pos, state, fluidState,
                    direction, cursor, neighborState, neighborFluid, flowingFluid)) {
                continue;
            }

            int score = neighborFluid.getAmount() * 8;
            if (direction == Direction.DOWN) {
                score += 12;
            } else if (direction.getAxis().isHorizontal()) {
                score += 4;
            }
            if (score > bestScore) {
                bestScore = score;
                bestDirection = direction;
                bestPos = cursor.immutable();
                bestState = neighborState;
                bestFluid = neighborFluid;
            }
        }

        if (bestDirection == null || bestPos == null || bestState == null || bestFluid == null) {
            return false;
        }

        int moved = FFFluidUtils.transferFluidAmount(level, pos, bestPos, flowingFluid, fluidState.getAmount(), 0);
        if (moved <= 0) {
            return false;
        }
        AdaptiveTickScheduler.scheduleFluidTick(level, bestPos, flowingFluid, 1);
        if (moved < fluidState.getAmount()) {
            AdaptiveTickScheduler.scheduleFluidTick(level, pos, flowingFluid, 4);
        }
        FluidActivityTracker.recordChanges(level, List.of(pos.immutable(), bestPos.immutable()));
        return true;
    }

    @Unique
    private boolean flowing_fluids$tryConsumeThinFragmentReclaimBudget(Level level, BlockPos pos) {
        long gameTime = level.getGameTime();
        long regionKey = BlockPos.asLong(pos.getX() >> 4, pos.getY() >> 3, pos.getZ() >> 4);
        synchronized (ff$THIN_FRAGMENT_RECLAIM_LOCK) {
            if (ff$thinFragmentReclaimBudgetTick != gameTime) {
                ff$thinFragmentReclaimBudgetTick = gameTime;
                ff$thinFragmentReclaimsThisTick = 0;
            }
            if (ff$thinFragmentReclaimsThisTick >= ff$THIN_FRAGMENT_MAX_RECLAIMS_PER_TICK) {
                return false;
            }
            if (ff$THIN_FRAGMENT_RECLAIM_REGION_TICK.getOrDefault(regionKey, Long.MIN_VALUE) > gameTime) {
                return false;
            }
            if (ff$THIN_FRAGMENT_RECLAIM_REGION_TICK.size() >= 4096) {
                ff$THIN_FRAGMENT_RECLAIM_REGION_TICK.long2LongEntrySet().removeIf(entry -> entry.getLongValue() <= gameTime);
            }
            ff$THIN_FRAGMENT_RECLAIM_REGION_TICK.put(regionKey, gameTime + ff$THIN_FRAGMENT_REGION_COOLDOWN_TICKS);
            ff$thinFragmentReclaimsThisTick++;
            return true;
        }
    }

    @Unique
    private boolean flowing_fluids$shouldSuppressExploratorySpread(Level level, BlockPos pos, FluidState fluidState, int amount) {
        return flowing_fluids$shouldSuppressExploratorySpread(level, pos, fluidState, amount, null);
    }

    @Unique
    private boolean flowing_fluids$shouldSuppressExploratorySpread(Level level, BlockPos pos, FluidState fluidState, int amount,
                                                                   @Nullable WaterFlowProfile waterProfile) {
        if (!fluidState.is(FluidTags.WATER)) {
            return false;
        }

        if (amount > 0
                && amount <= getDropOff(level)
                && FFFluidUtils.isSmallSupportedThinSurfaceCluster(level, pos, fluidState.getType(), 3, getDropOff(level))) {
            return true;
        }

        WaterFlowProfile profile = waterProfile != null
                ? waterProfile
                : flowing_fluids$getWaterFlowProfile(level, pos, fluidState, amount);
        if (profile.shouldSuppressExploratorySpread()) {
            if (profile.hasImmediateSurfaceEdge()
                    || profile.hasImmediateDownwardOutlet()
                    || AdaptiveTickScheduler.isFlowActiveNow(level, pos)
                    || flowing_fluids$hasNearbyStepDownOutlet(level, pos, fluidState.getType(), amount)) {
                return false;
            }
            return true;
        }
        if (profile.isPressureDriven()) {
            return false;
        }
        if (FFFluidUtils.isRiverBiome(level.getBiome(pos))) {
            return false;
        }

        if (profile.isBroadSurface()) {
            if (AdaptiveTickScheduler.isFlowActiveNow(level, pos)) {
                return false;
            }
            if (flowing_fluids$hasImmediateSurfaceEdge(level, pos, fluidState.getType())) {
                return false;
            }
            return !flowing_fluids$hasNearbyStepDownOutlet(level, pos, fluidState.getType(), amount);
        }

        var biome = level.getBiome(pos);
        boolean broadWaterBiome = FFFluidUtils.isOceanBiome(biome) || FFFluidUtils.isBeachBiome(biome);
        int maxExploratoryAmount = broadWaterBiome && pos.getY() >= FFFluidUtils.seaLevel(level) - 1 ? 6 : 4;
        if (amount <= 0 || amount > maxExploratoryAmount) {
            return false;
        }
        if (flowing_fluids$hasFluidAbove(level, pos, fluidState.getType())) {
            return false;
        }
        if (flowing_fluids$hasNearbyStepDownOutlet(level, pos, fluidState.getType(), amount)) {
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

        int routeCount = flowing_fluids$countSpreadableHorizontalRoutes(level, pos, fluidState.getType(), amount);
        if (routeCount >= 3) {
            return true;
        }

        return routeCount == 2 && AdaptiveTickScheduler.getPoolStableTicks(level, pos, 20) >= 8;
    }

    @Unique
    private int flowing_fluids$countSpreadableHorizontalRoutes(Level level, BlockPos pos, Fluid sourceFluid, int amount) {
        BlockState stateAtPos = level.getBlockState(pos);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int routes = 0;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, dir);
            BlockState sideState = level.getBlockState(cursor);
            FluidState sideFluid = FFFluidUtils.getEffectiveFluidState(level, cursor, sideState);

            if (flowing_fluids$canSpreadToOptionallySameOrEmpty(
                    sourceFluid,
                    Math.max(1, amount),
                    level,
                    pos,
                    stateAtPos,
                    dir,
                    cursor,
                    sideState,
                    sideFluid,
                    false
            )) {
                routes++;
            }
        }

        return routes;
    }

    @Unique
    private void flowing_fluids$updateStablePoolTracking(Level level, BlockPos pos, FluidState fluidState, int amount, boolean stable) {
        if (!fluidState.is(FluidTags.WATER)) {
            return;
        }
        if (flowing_fluids$shouldTrackStablePool(amount)) {
            AdaptiveTickScheduler.markPoolStable(level, pos, stable, amount);
        } else {
            AdaptiveTickScheduler.markPoolStable(level, pos, false);
        }
    }

    @Unique
    private boolean flowing_fluids$shouldTrackStablePool(int amount) {
        return FluidRegressionLogic.shouldTrackWaterPoolStableTicks(amount);
    }

    @Unique
    private void flowing_fluids$invalidateFluidSampleCaches(BlockPos... positions) {
        ff$getSectionSampleContext().invalidate(positions);
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
    private float flowing_fluids$getHydraulicGuideBias(Level level, BlockPos origin, Direction dir, Fluid sourceFluid,
                                                       int sourceAmount, int targetAmount) {
        if (!FlowingFluids.config.enableHydraulicBlocks || !sourceFluid.isSame(Fluids.WATER)) {
            return 0f;
        }
        BlockPos target = origin.relative(dir);
        float bias = 0f;
        if (flowing_fluids$isWaterwayGuideSurface(level, target.below())) {
            bias += targetAmount <= 0 ? 0.50f : 0.42f;
        }
        if (flowing_fluids$isWaterwayGuideSurface(level, target.relative(dir).below())) {
            bias += 0.26f;
        }
        if (sourceAmount >= 4 && flowing_fluids$isWaterwayGuideSurface(level, origin.below())) {
            bias += 0.16f;
        }
        if (flowing_fluids$isHydraulicNozzleFacing(level, target.below(), dir)) {
            bias += targetAmount <= 0 ? 0.85f : 0.70f;
        }
        if (sourceAmount >= 4 && flowing_fluids$isHydraulicNozzleFacing(level, origin.below(), dir)) {
            bias += 0.40f;
        }
        return Math.min(1.20f, bias);
    }

    @Unique
    private boolean flowing_fluids$isWaterwayGuideSurface(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(FlowingFluids.HYDRAULIC_FLOW_GUIDE_BLOCKS);
    }

    @Unique
    private boolean flowing_fluids$isHydraulicNozzleFacing(Level level, BlockPos pos, Direction direction) {
        if (!direction.getAxis().isHorizontal()) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.is(FlowingFluids.HYDRAULIC_NOZZLE_BLOCKS)
                && state.hasProperty(DirectionalBlock.FACING)
                && state.getValue(DirectionalBlock.FACING) == direction;
    }

    @Unique
    private float flowing_fluids$getInternalGradientTransferBias(Level level, BlockPos origin, Direction dir,
                                                                 int sourceAmount, int targetAmount) {
        int sourceInternal = FluidSpatialGrid.getFluidAmount(level, origin);
        if (sourceInternal <= 0 && sourceAmount > 0) {
            sourceInternal = FluidAmountConverter.toInternal(sourceAmount);
        }
        BlockPos targetPos = origin.relative(dir);
        int targetInternal = FluidSpatialGrid.getFluidAmount(level, targetPos);
        if (targetInternal <= 0 && targetAmount > 0) {
            targetInternal = FluidAmountConverter.toInternal(targetAmount);
        }
        int internalDifference = Math.max(0, sourceInternal - targetInternal);
        if (internalDifference <= FluidAmountConverter.scaleLegacyInternal(8)) {
            return 0f;
        }
        float normalized = internalDifference / (float) FluidAmountConverter.getMaxInternal();
        return Math.min(0.16f, normalized * 0.18f);
    }

    @Unique
    private int flowing_fluids$getWaterAmountAt(Level level, BlockPos pos, Fluid sourceFluid) {
        FFSectionSampleContext sampleContext = ff$getSectionSampleContext();
        boolean cellCacheHit = sampleContext.hasCell(level, pos);
        int amount = sampleContext.fluidAmountIfSame(level, pos, sourceFluid);
        if (FlowingFluids.config.enablePerformanceMonitoring) {
            if (cellCacheHit) {
                FluidPerformanceMonitor.getInstance().recordFastPath();
            } else {
                FluidPerformanceMonitor.getInstance().recordCacheMiss();
            }
        }
        return amount;
    }

    @Unique
    protected void flowing_fluids$spreadTo2(LevelAccessor levelAccessor, BlockPos blockPos, BlockState blockState, Direction direction, int amount) {
        if (FFFluidUtils.supportsVirtualFluidState(levelAccessor, blockState)) {
            FluidState before = FFFluidUtils.getEffectiveFluidState(levelAccessor, blockPos, blockState);
            FFFluidUtils.setFluidStateAtPosToNewAmount(levelAccessor, blockPos, this, amount);
            FluidState updated = FFFluidUtils.getEffectiveFluidState(levelAccessor, blockPos, levelAccessor.getBlockState(blockPos));
            flowing_fluids$bufferFlowState(levelAccessor, blockPos, before, updated);
            return;
        }
        this.spreadTo(levelAccessor, blockPos, blockState, direction, getStateForFluidByAmount(this, amount));
        flowing_fluids$bufferFlowState(levelAccessor, blockPos, blockState);
    }

    @Unique
    private void flowing_fluids$bufferFlowState(LevelAccessor levelAccessor, BlockPos blockPos, BlockState beforeState) {
        FluidState before = FFFluidUtils.getEffectiveFluidState(levelAccessor, blockPos, beforeState);
        FluidState updated = FFFluidUtils.getEffectiveFluidState(levelAccessor, blockPos, levelAccessor.getBlockState(blockPos));
        flowing_fluids$bufferFlowState(levelAccessor, blockPos, before, updated);
    }

    @Unique
    private void flowing_fluids$bufferFlowState(LevelAccessor levelAccessor, BlockPos blockPos, FluidState before, FluidState updated) {
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
        if (flowing_fluids$shouldQueueEqualizer(levelAccessor, blockPos, before, updated)) {
            ParallelFluidEqualizer.enqueue(levelAccessor, blockPos);
        }
        FluidTickBuffer.bufferSlopeCacheInvalidation(levelAccessor, blockPos);
    }

    @Unique
    private boolean flowing_fluids$shouldQueueEqualizer(LevelAccessor levelAccessor, BlockPos blockPos, FluidState before, FluidState updated) {
        if (!(levelAccessor instanceof Level level)) {
            return true;
        }
        FluidState relevant = !updated.isEmpty() ? updated : before;
        if (!relevant.is(FluidTags.WATER)) {
            return true;
        }

        int beforeAmount = before.getType().isSame(relevant.getType()) ? before.getAmount() : 0;
        int afterAmount = updated.getType().isSame(relevant.getType()) ? updated.getAmount() : 0;
        WaterFlowProfile waterProfile = flowing_fluids$getWaterFlowProfile(level, blockPos, relevant, Math.max(beforeAmount, afterAmount));
        int difference = Math.abs(afterAmount - beforeAmount);
        if (waterProfile.shouldQueueEqualizer(difference, before.isEmpty(), updated.isEmpty())) {
            return true;
        }
        return FluidRegressionLogic.shouldWakeBroadSurfaceEqualizerForThinPartial(
                waterProfile.isBroadSurface(),
                waterProfile.isPressureDriven(),
                waterProfile.isInletZone(),
                difference,
                beforeAmount,
                afterAmount);
    }

    @Unique
    private WaterFlowProfile flowing_fluids$getWaterFlowProfile(Level level, BlockPos pos, FluidState fluidState, int amount) {
        return ff$getSectionSampleContext().waterProfile(level, pos, fluidState, amount);
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
