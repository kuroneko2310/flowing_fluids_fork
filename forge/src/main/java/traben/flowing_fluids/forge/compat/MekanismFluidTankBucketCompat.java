package traben.flowing_fluids.forge.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.common.SoundActions;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import org.jetbrains.annotations.Nullable;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

import java.util.Optional;

public final class MekanismFluidTankBucketCompat {
    private static final int MAX_BUCKET_MODE_CONNECTED_BUCKETS = 4;
    private static final int MAX_BUCKET_MODE_CONNECTED_LEVELS = MAX_BUCKET_MODE_CONNECTED_BUCKETS * 8;
    private static final int FLUID_LEVEL_MB = Math.max(1, FluidType.BUCKET_VOLUME / 8);

    private MekanismFluidTankBucketCompat() {
    }

    public static InteractionResultHolder<ItemStack> tryPickupBucketModeFluid(Level level, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!FlowingFluids.config.enableMod || player.isShiftKeyDown()) {
            return null;
        }
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        ItemStack stack = player.getItemInHand(hand);
        BlockPos pos = hitResult.getBlockPos();
        if (!level.mayInteract(player, pos) || !player.mayUseItemAt(pos, hitResult.getDirection(), stack)) {
            return InteractionResultHolder.fail(stack);
        }

        var fluidHandler = FluidUtil.getFluidHandler(stack).resolve();
        if (fluidHandler.isEmpty()) {
            return InteractionResultHolder.fail(stack);
        }

        IFluidHandlerItem handler = fluidHandler.get();
        PartialDrainResult drainResult = createBucketModeDrainResult(level, pos, level.getBlockState(pos), handler);
        if (drainResult == null) {
            return null;
        }

        if (handler.fill(drainResult.fluidStack().copy(), IFluidHandler.FluidAction.SIMULATE) < drainResult.fluidStack().getAmount()) {
            return InteractionResultHolder.fail(stack);
        }

        if (level.isClientSide()) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        int inserted = handler.fill(drainResult.fluidStack().copy(), IFluidHandler.FluidAction.EXECUTE);
        if (inserted < drainResult.fluidStack().getAmount()) {
            return InteractionResultHolder.fail(stack);
        }
        drainResult.apply().run();
        ItemStack resultStack = handler.getContainer();

        drainResult.pickupSound().ifPresent(sound ->
                level.playSound(player, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F));
        level.gameEvent(player, GameEvent.FLUID_PICKUP, pos);
        return InteractionResultHolder.sidedSuccess(resultStack, false);
    }

    public static InteractionResultHolder<ItemStack> tryPlacePartialFluid(Level level, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!FlowingFluids.config.enableMod || !player.isShiftKeyDown()) {
            return null;
        }
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        ItemStack stack = player.getItemInHand(hand);
        var fluidHandler = FluidUtil.getFluidHandler(stack).resolve();
        if (fluidHandler.isEmpty()) {
            return InteractionResultHolder.fail(stack);
        }

        IFluidHandlerItem handler = fluidHandler.get();
        FluidStack storedFluid = handler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE);
        if (storedFluid.isEmpty()
                || storedFluid.getAmount() >= FluidType.BUCKET_VOLUME
                || !(storedFluid.getFluid() instanceof FlowingFluid flowingFluid)
                || !FlowingFluids.config.isFluidAllowed(storedFluid.getFluid())) {
            return null;
        }

        BlockPos hitPos = hitResult.getBlockPos();
        Direction hitDirection = hitResult.getDirection();
        if (!level.mayInteract(player, hitPos) || !player.mayUseItemAt(hitPos.relative(hitDirection), hitDirection, stack)) {
            return InteractionResultHolder.fail(stack);
        }

        int levelsToPlace = milliBucketsToFluidLevels(storedFluid.getAmount());
        if (levelsToPlace <= 0) {
            return InteractionResultHolder.fail(stack);
        }

        BlockPos targetPos = resolvePlacementTarget(level, hitPos, hitDirection, storedFluid.getFluid());
        PlacementResult placement = simulatePartialPlacement(level, player, targetPos, flowingFluid, levelsToPlace);
        if (!placement.success() || placement.placedLevels() <= 0) {
            return InteractionResultHolder.fail(stack);
        }

        if (level.isClientSide()) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (!player.isCreative()) {
            int drainAmount = fluidLevelsToMilliBuckets(placement.placedLevels());
            FluidStack drained = handler.drain(drainAmount, IFluidHandler.FluidAction.EXECUTE);
            if (drained.getAmount() < drainAmount) {
                return InteractionResultHolder.fail(stack);
            }
        }
        placement.apply().run();
        wakeNearbyFluidFlow(level, targetPos, flowingFluid);
        ItemStack resultStack = handler.getContainer();

        playEmptySound(player, level, targetPos, storedFluid);
        level.gameEvent(player, GameEvent.FLUID_PLACE, targetPos);
        return InteractionResultHolder.sidedSuccess(resultStack, false);
    }

    @Nullable
    public static ItemStack tryDispenserBucketModePickupOrPlace(Level level, BlockPos pos, ItemStack stack) {
        if (!FlowingFluids.config.enableMod) {
            return null;
        }

        var fluidHandler = FluidUtil.getFluidHandler(stack).resolve();
        if (fluidHandler.isEmpty()) {
            return null;
        }

        IFluidHandlerItem handler = fluidHandler.get();
        PartialDrainResult drainResult = createBucketModeDrainResult(level, pos, level.getBlockState(pos), handler);
        if (drainResult != null) {
            if (handler.fill(drainResult.fluidStack().copy(), IFluidHandler.FluidAction.SIMULATE) >= drainResult.fluidStack().getAmount()) {
                int inserted = handler.fill(drainResult.fluidStack().copy(), IFluidHandler.FluidAction.EXECUTE);
                if (inserted >= drainResult.fluidStack().getAmount()) {
                    drainResult.apply().run();
                    drainResult.pickupSound().ifPresent(sound ->
                            level.playSound(null, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F));
                    level.gameEvent(null, GameEvent.FLUID_PICKUP, pos);
                    return handler.getContainer();
                }
            }
            return null;
        }

        FluidStack storedFluid = handler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE);
        if (storedFluid.isEmpty()
                || storedFluid.getAmount() >= FluidType.BUCKET_VOLUME
                || !(storedFluid.getFluid() instanceof FlowingFluid flowingFluid)
                || !FlowingFluids.config.isFluidAllowed(storedFluid.getFluid())) {
            return null;
        }

        int levelsToPlace = milliBucketsToFluidLevels(storedFluid.getAmount());
        if (levelsToPlace <= 0) {
            return null;
        }

        PlacementResult placement = simulatePartialPlacement(level, null, pos, flowingFluid, levelsToPlace);
        if (!placement.success() || placement.placedLevels() <= 0) {
            return null;
        }

        int drainAmount = fluidLevelsToMilliBuckets(placement.placedLevels());
        FluidStack drained = handler.drain(drainAmount, IFluidHandler.FluidAction.EXECUTE);
        if (drained.getAmount() < drainAmount) {
            return null;
        }
        placement.apply().run();
        wakeNearbyFluidFlow(level, pos, flowingFluid);
        playEmptySound(null, level, pos, storedFluid);
        level.gameEvent(null, GameEvent.FLUID_PLACE, pos);
        return handler.getContainer();
    }

    private static int fluidLevelsToMilliBuckets(int amount) {
        if (amount <= 0) {
            return 0;
        }
        return amount * FLUID_LEVEL_MB;
    }

    private static int milliBucketsToFluidLevels(int milliBuckets) {
        return milliBucketsToFluidLevels(milliBuckets, 8);
    }

    private static int milliBucketsToFluidLevels(int milliBuckets, int maxLevels) {
        if (milliBuckets <= 0 || maxLevels <= 0) {
            return 0;
        }
        return Mth.clamp(milliBuckets / FLUID_LEVEL_MB, 0, maxLevels);
    }

    private static BlockPos resolvePlacementTarget(Level level, BlockPos hitPos, Direction hitDirection, Fluid fluid) {
        BlockState hitState = level.getBlockState(hitPos);
        FluidState hitFluidState = FFFluidUtils.getEffectiveFluidState(level, hitPos, hitState);
        if ((fluid == Fluids.WATER
                && (FFFluidUtils.supportsVirtualFluidState(level, hitState)
                || hitState.getBlock() instanceof LiquidBlockContainer))
                || (fluid.isSame(hitFluidState.getType()) && hitFluidState.getAmount() < 8)) {
            return hitPos;
        }
        return hitPos.relative(hitDirection);
    }

    private static PlacementResult simulatePartialPlacement(Level level, @Nullable Player player, BlockPos targetPos,
                                                            FlowingFluid fluid, int levelsToPlace) {
        BlockState state = level.getBlockState(targetPos);
        FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, targetPos, state);
        boolean fluidIsSame = fluid.isSame(fluidState.getType());
        boolean virtualTarget = FFFluidUtils.supportsVirtualFluidState(level, state);
        boolean canPlaceLiquidInPos = state.canBeReplaced(fluid) || state.isAir() || fluidIsSame || virtualTarget;

        if (!virtualTarget && !canPlaceLiquidInPos && state.getBlock() instanceof LiquidBlockContainer container) {
            if (container.canPlaceLiquid(level, targetPos, state, fluid)) {
                if (levelsToPlace != 8) {
                    return PlacementResult.FAIL;
                }
                return new PlacementResult(true, 8,
                        () -> container.placeLiquid(level, targetPos, level.getBlockState(targetPos), fluid.getSource(false)));
            }
        }

        if (!canPlaceLiquidInPos) {
            return PlacementResult.FAIL;
        }

        if (level.dimensionType().ultraWarm() && fluid.isSame(Fluids.WATER)) {
            return new PlacementResult(true, levelsToPlace, () -> {
                level.playSound(player, targetPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F,
                        2.6F + (level.random.nextFloat() - level.random.nextFloat()) * 0.8F);
                for (int i = 0; i < 8; ++i) {
                    level.addParticle(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE,
                            targetPos.getX() + Math.random(),
                            targetPos.getY() + Math.random(),
                            targetPos.getZ() + Math.random(),
                            0.0, 0.0, 0.0);
                }
            });
        }

        Runnable destroyAction = () -> {
            if (level.getBlockState(targetPos).canBeReplaced(fluid) && !level.getBlockState(targetPos).liquid()) {
                level.destroyBlock(targetPos, true);
            }
        };

        if (fluidIsSame) {
            var placement = FFFluidUtils.placeConnectedFluidAmountAndPlaceAction(level, targetPos, levelsToPlace, fluid);
            int placedLevels = levelsToPlace - placement.first();
            if (placedLevels <= 0 || placement.second() == null) {
                return PlacementResult.FAIL;
            }
            return new PlacementResult(true, placedLevels, () -> {
                destroyAction.run();
                placement.second().run();
            });
        }

        if (!FFFluidUtils.canStorePartialFluidAmount(level, targetPos, state, fluid)) {
            return PlacementResult.FAIL;
        }

        return new PlacementResult(true, levelsToPlace, () -> {
            destroyAction.run();
            FFFluidUtils.setFluidStateAtPosToNewAmount(level, targetPos, fluid, levelsToPlace);
        });
    }

    private static void playEmptySound(@Nullable Player player, Level level, BlockPos pos, FluidStack fluidStack) {
        SoundEvent soundEvent = fluidStack.getFluid().getFluidType().getSound(player, level, pos, SoundActions.BUCKET_EMPTY);
        if (soundEvent == null) {
            soundEvent = fluidStack.getFluid().isSame(Fluids.LAVA) ? SoundEvents.BUCKET_EMPTY_LAVA : SoundEvents.BUCKET_EMPTY;
        }
        level.playSound(player, pos, soundEvent, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    @Nullable
    public static PartialDrainResult createSingleBlockPartialDrainResult(Level level, BlockPos pos, BlockState blockState) {
        FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, pos, blockState);
        if (fluidState.isEmpty()
                || fluidState.isSource()
                || fluidState.getAmount() >= 8
                || !(fluidState.getType() instanceof FlowingFluid flowingFluid)
                || !FlowingFluids.config.isFluidAllowed(fluidState)) {
            return null;
        }

        int drainedLevels = fluidState.getAmount();

        return new PartialDrainResult(
                new FluidStack(fluidState.getType(), fluidLevelsToMilliBuckets(drainedLevels)),
                fluidState.getType(),
                resolvePickupSound(blockState, fluidState.getType()),
                drainedLevels,
                () -> {
                    FFFluidUtils.removeAllFluidAtPos(level, pos, flowingFluid);
                    wakeNearbyFluidFlow(level, pos, flowingFluid);
                }
        );
    }

    @Nullable
    public static PartialDrainResult createBucketModeDrainResult(Level level, BlockPos pos, BlockState blockState,
                                                                 IFluidHandlerItem handler) {
        FluidState effectiveFluidState = FFFluidUtils.getEffectiveFluidState(level, pos, blockState);
        if (effectiveFluidState.isEmpty()
                || effectiveFluidState.getAmount() <= 0
                || !(effectiveFluidState.getType() instanceof FlowingFluid flowingFluid)
                || !FlowingFluids.config.isFluidAllowed(effectiveFluidState)) {
            return null;
        }

        int maxDrainLevels = resolveMaxDrainLevels(handler, effectiveFluidState.getType());
        if (maxDrainLevels <= 0) {
            return null;
        }

        var drainData = FFFluidUtils.collectConnectedFluidAmountAndRemoveAction(level, pos, 1, maxDrainLevels, flowingFluid);
        int drainedLevels = drainData.first();
        Runnable applyAction = drainData.second();
        if (drainedLevels <= 0 || applyAction == null) {
            return null;
        }

        return new PartialDrainResult(
                new FluidStack(effectiveFluidState.getType(), fluidLevelsToMilliBuckets(drainedLevels)),
                effectiveFluidState.getType(),
                resolvePickupSound(blockState, effectiveFluidState.getType()),
                drainedLevels,
                () -> {
                    applyAction.run();
                    wakeNearbyFluidFlow(level, pos, flowingFluid);
                }
        );
    }

    private static int resolveMaxDrainLevels(IFluidHandlerItem handler, Fluid fluid) {
        FluidStack requestedFill = new FluidStack(fluid, fluidLevelsToMilliBuckets(MAX_BUCKET_MODE_CONNECTED_LEVELS));
        int acceptedMilliBuckets = handler.fill(requestedFill, IFluidHandler.FluidAction.SIMULATE);
        if (acceptedMilliBuckets <= 0) {
            return 0;
        }
        return milliBucketsToFluidLevels(acceptedMilliBuckets, MAX_BUCKET_MODE_CONNECTED_LEVELS);
    }

    private static Optional<SoundEvent> resolvePickupSound(BlockState blockState, Fluid fluid) {
        if (blockState.getBlock() instanceof net.minecraft.world.level.block.BucketPickup bucketPickup) {
            Optional<SoundEvent> pickupSound = bucketPickup.getPickupSound(blockState);
            if (pickupSound.isPresent()) {
                return pickupSound;
            }
        }
        return fluid.getPickupSound();
    }

    private static void wakeNearbyFluidFlow(Level level, BlockPos pos, FlowingFluid fluid) {
        if (level.isClientSide()) {
            return;
        }

        // Bucket-mode compat removes or places only a single shallow cell, so we wake the local neighborhood
        // to let Flowing Fluids immediately refill and continue spreading naturally.
        AdaptiveTickScheduler.scheduleFluidTick(level, pos, fluid, 1);
        BlockPos.MutableBlockPos neighbourPos = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            neighbourPos.setWithOffset(pos, direction);
            FluidState neighbourFluid = FFFluidUtils.getEffectiveFluidState(level, neighbourPos, level.getBlockState(neighbourPos));
            if (neighbourFluid.getType().isSame(fluid) && neighbourFluid.getAmount() > 0) {
                AdaptiveTickScheduler.scheduleFluidTick(level, neighbourPos, fluid, 1);
            }
        }
    }

    private record PlacementResult(boolean success, int placedLevels, Runnable apply) {
        private static final PlacementResult FAIL = new PlacementResult(false, 0, () -> {
        });
    }

    public record PartialDrainResult(FluidStack fluidStack, Fluid soundFluid, Optional<SoundEvent> pickupSound,
                                     int drainedLevels, Runnable apply) {
    }
}
