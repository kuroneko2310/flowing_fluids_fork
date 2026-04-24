package traben.flowing_fluids.forge.mixin.create;

#if MC!=MC_20_1

import org.spongepowered.asm.mixin.Mixin;
import traben.flowing_fluids.config.FFCommands;

@Mixin(FFCommands.class)
public abstract class MixinHosePulley{
}
#else


import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.simibubi.create.content.fluids.hosePulley.HosePulleyFluidHandler;
import com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour;
import com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.fluid.FluidHelper;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

import java.util.function.Supplier;

@Pseudo
@Mixin(HosePulleyFluidHandler.class)
public abstract class MixinHosePulley {

    @Unique
    private static final int FF_LEVEL_MILLIBUCKETS = 1000 / 8;

    @Unique
    private static final int FF_EXTERNAL_PIPE_BUFFER_CAPACITY = 8000;

    @Unique
    private static final int FF_EXTERNAL_PIPE_PLACE_PRIMARY_DEPTH = 192;

    @Unique
    private static final int FF_EXTERNAL_PIPE_PLACE_SECONDARY_DEPTH = 384;

    @Unique
    private static final int FF_HOSE_PULLEY_WATER_ROOT_LEVEL_CAP = 7;

    @Shadow(remap = false) private FluidDrainingBehaviour drainer;

    @Shadow(remap = false) private FluidFillingBehaviour filler;

    @Shadow(remap = false) private SmartFluidTank internalTank;

    @Shadow(remap = false) private Supplier<BlockPos> rootPosGetter;

    @Shadow(remap = false) private Supplier<Boolean> predicate;

    @Inject(method = "getTankCapacity", at = @At("HEAD"), cancellable = true, remap = false)
    private void ff$expandReportedCapacity(final int tank, final CallbackInfoReturnable<Integer> cir) {
        if (FlowingFluids.config.enableMod) {
            cir.setReturnValue(Math.max(FF_EXTERNAL_PIPE_BUFFER_CAPACITY, internalTank.getTankCapacity(tank)));
        }
    }

    @Inject(method = "fill", at = @At("HEAD"), cancellable = true, remap = false)
    private void ff$bulkPipeFillCompat(final FluidStack resource, final IFluidHandler.FluidAction action,
                                       final CallbackInfoReturnable<Integer> cir) {
        if (!ff$shouldHandleExternalPipeFill(resource)) {
            return;
        }

        FluidStack stored = internalTank.getFluid();
        if (!stored.isEmpty() && !resource.isFluidEqual(stored)) {
            cir.setReturnValue(0);
            return;
        }

        int storedAmount = internalTank.getFluidAmount();
        int resourceAmount = resource.getAmount();
        int combinedAmount = storedAmount + resourceAmount;
        int remainingCombined = combinedAmount;

        if (predicate.get() && resource.getFluid() instanceof FlowingFluid flowing) {
            int placeLevels = combinedAmount / FF_LEVEL_MILLIBUCKETS;
            if (placeLevels > 0) {
                int placedLevels = ff$placeFluidFromHosePulley(flowing, placeLevels, action.execute(), true);
                remainingCombined = Math.max(0, combinedAmount - ff$levelsToMilliBuckets(placedLevels));
            }
        }

        int cappedRemaining = Math.min(FF_EXTERNAL_PIPE_BUFFER_CAPACITY, remainingCombined);
        int overflow = Math.max(0, remainingCombined - cappedRemaining);
        int accepted = Math.max(0, Math.min(resourceAmount, resourceAmount - overflow));

        if (accepted <= 0) {
            cir.setReturnValue(0);
            return;
        }

        if (action.execute()) {
            ff$setInternalTankFluid(resource, cappedRemaining);
        }

        cir.setReturnValue(accepted);
    }

    @Inject(method = "drainInternal", at = @At("HEAD"), cancellable = true, remap = false)
    private void ff$bulkPipeDrainCompat(final int maxDrain, final FluidStack resource,
                                        final IFluidHandler.FluidAction action,
                                        final CallbackInfoReturnable<FluidStack> cir) {
        int requestedAmount = resource != null ? resource.getAmount() : maxDrain;
        if (requestedAmount <= 1000 || !FlowingFluids.config.enableMod) {
            return;
        }

        FluidStack stored = internalTank.getFluid();
        if (resource != null && !stored.isEmpty() && !resource.isFluidEqual(stored)) {
            cir.setReturnValue(FluidStack.EMPTY);
            return;
        }

        Fluid fluid = !stored.isEmpty() ? stored.getFluid() : ff$resolveDrainableFluid();
        if (fluid == Fluids.EMPTY || !FlowingFluids.config.isFluidAllowed(fluid)) {
            return;
        }
        if (resource != null && !resource.isEmpty() && !resource.getFluid().isSame(fluid)) {
            cir.setReturnValue(FluidStack.EMPTY);
            return;
        }

        int storedAmount = internalTank.getFluidAmount();
        int availableFromWorld = 0;
        Runnable worldDrain = null;

        if (fluid instanceof FlowingFluid flowing && requestedAmount > storedAmount) {
            int deficit = requestedAmount - storedAmount;
            int requestedLevels = Mth.ceil(deficit / (float) FF_LEVEL_MILLIBUCKETS);
            int searchDepth = Math.max(40, Math.min(160, requestedLevels * 4));
            var data = FFFluidUtils.collectConnectedFluidAmountAndRemoveAction(
                    drainer.getWorld(),
                    rootPosGetter.get(),
                    1,
                    requestedLevels,
                    flowing,
                    searchDepth
            );
            availableFromWorld = ff$levelsToMilliBuckets(data.first());
            worldDrain = data.second();
        }

        int totalAvailable = storedAmount + availableFromWorld;
        if (totalAvailable <= 0) {
            cir.setReturnValue(FluidStack.EMPTY);
            return;
        }

        int drainedAmount = Math.min(requestedAmount, totalAvailable);
        FluidStack drained = ff$copyFluidWithAmount(resource != null && !resource.isEmpty() ? resource : stored, fluid, drainedAmount);

        if (action.execute()) {
            if (availableFromWorld > 0 && worldDrain != null) {
                worldDrain.run();
                filler.counterpartActed();
            }
            ff$setInternalTankFluid(drained, totalAvailable - drainedAmount);
        }

        cir.setReturnValue(drained);
    }

    @WrapOperation(method = "drainInternal", remap = false,
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/fluids/transfer/FluidDrainingBehaviour;getDrainableFluid(Lnet/minecraft/core/BlockPos;)Lnet/minecraftforge/fluids/FluidStack;"))
    private FluidStack ff$modifyWaterRemoval1(final FluidDrainingBehaviour instance, final BlockPos blockPos, final Operation<FluidStack> original) {
        if (FlowingFluids.config.enableMod) {
            var fluid = ((FluidDrainingBehaviourAccessor) drainer).ff$getFluid();
            if (fluid == null) {
                var newFluid = drainer.getWorld().getFluidState(blockPos).getType();
                if (FlowingFluids.config.isFluidAllowed(newFluid)) {
                    // mimic fluid set behaviour
                    if (((FluidDrainingBehaviourAccessor) drainer).ff$getFluid() == null)
                        ((FluidDrainingBehaviourAccessor) drainer).ff$setFluid(FluidHelper.convertToStill(newFluid));
                    fluid = newFluid;
                }
            }
            if (fluid == Fluids.EMPTY) return FluidStack.EMPTY;
            if (FlowingFluids.config.isFluidAllowed(fluid)) {

                var source = FluidHelper.convertToStill(fluid);
                return new FluidStack(source, 1000);
            }
        }
        return original.call(instance, blockPos);
    }

    @WrapOperation(method = "drainInternal", remap = false,
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/fluids/transfer/FluidDrainingBehaviour;pullNext(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean ff$modifyWaterRemoval2(final FluidDrainingBehaviour instance, final BlockPos blockPos, final boolean simulate, final Operation<Boolean> original,
                                           @Share("foundLevels") LocalIntRef foundLevels, @Local(ordinal = 0, argsOnly = true) int maxAmount) {
        foundLevels.set(8);
        if (FlowingFluids.config.enableMod) {
            var world = drainer.getWorld();
            var state = world.getBlockState(blockPos);
            var fluidState = state.getFluidState();

            if (fluidState.isEmpty()) return false;

            if (FlowingFluids.config.isFluidAllowed(fluidState) && fluidState.getType() instanceof FlowingFluid flowing) {
                if (FlowingFluids.config.create_infinitePipes || drainer.isInfinite()) {
                    if (!simulate) {
                        ((FluidManipulationBehaviourAccessor) drainer).ff$PlayEffect(world, blockPos, flowing, true);
                        drainer.blockEntity.award(AllAdvancements.HOSE_PULLEY);
                        if (drainer.isInfinite() && FluidHelper.isLava(flowing)) {
                            drainer.blockEntity.award(AllAdvancements.HOSE_PULLEY_LAVA);
                        }
                    }
                    return true;
                }

                // override the existing hose pulley logic as water has physics now
                var data = FFFluidUtils.collectConnectedFluidAmountAndRemoveAction(world, blockPos,1,8, flowing);
                var found = data.first();
                if (found == 0) return false; // nothing found

                foundLevels.set(found);
                if (simulate) return true; // report the actual partial amount without changing the world

                // mimic advancement behaviour
                ((FluidManipulationBehaviourAccessor) drainer).ff$PlayEffect(world, blockPos, flowing, true);
                drainer.blockEntity.award(AllAdvancements.HOSE_PULLEY);
                if (drainer.isInfinite() && FluidHelper.isLava(flowing)) {
                    drainer.blockEntity.award(AllAdvancements.HOSE_PULLEY_LAVA);
                }

                data.second().run();
                return true;
            }
        }
        return original.call(instance, blockPos, simulate);
    }

    @ModifyConstant(method = "drainInternal",
            constant = @Constant(intValue = 1000, ordinal = 1), remap = false)
    private int ff$modifyWaterRemoval3(final int original, @Share("foundLevels") LocalIntRef foundLevels) {
        return waterModified(foundLevels.get());
    }

    @WrapOperation(method = INSERT_METHOD_FF, remap = false,
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/fluids/transfer/FluidFillingBehaviour;tryDeposit(Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean ff$modifyWaterPlacing(final FluidFillingBehaviour instance, final Fluid fluid, final BlockPos blockPos, final boolean b, final Operation<Boolean> original,
                                          @Share("placedLevels") LocalIntRef placedLevels) {
        placedLevels.set(8);
        if (FlowingFluids.config.enableMod
                && !FlowingFluids.config.create_infinitePipes) {
            if (FlowingFluids.config.isFluidAllowed(fluid)
                //&& AllConfigs.server().fluids.pipesPlaceFluidSourceBlocks.get()
                    && fluid instanceof FlowingFluid flowing) {
                int placed = ff$placeFluidFromHosePulley(flowing, 8, !b, false);
                if (placed <= 0) return false;

                placedLevels.set(placed);
                return true;
            }
        }
        return original.call(instance, fluid, blockPos, b);
    }

    private static final String INSERT_METHOD_FF = "fill";

    @ModifyConstant(method = INSERT_METHOD_FF, constant = @Constant(intValue = 1000, ordinal = 1), remap = false)
    private int ff$modifyWaterPlacing1(final int original, @Share("placedLevels") LocalIntRef placedLevels) { return waterModified(placedLevels.get()); }

    @ModifyConstant(method = INSERT_METHOD_FF, constant = @Constant(intValue = 1000, ordinal = 2), remap = false)
    private int ff$modifyWaterPlacing2(final int original, @Share("placedLevels") LocalIntRef placedLevels) { return waterModified(placedLevels.get()); }

    @Inject(method = INSERT_METHOD_FF, at = @At(value = "INVOKE", target = "Lnet/minecraftforge/fluids/FluidStack;shrink(I)V"), remap = false)
    private void ff$modifyWaterPlacing3(final FluidStack resource, final IFluidHandler.FluidAction action, final CallbackInfoReturnable<Integer> cir,
                                        @Share("placedLevels") LocalIntRef placedLevels, @Local(name = "diff") LocalIntRef diff) {
        if (placedLevels.get() == 8) return; // nothing to change

        int newDiff = diff.get() + 1000; // offset the upcoming non injected reduction
        diff.set(newDiff - waterModified(placedLevels.get()));
    }

    @Unique
    private int waterModified(int level) {
        if (level == 0) return 0;
        if (level == 8) return 1000;
        return 1000 / 8 * level; // new amount
    }

    @Unique
    private boolean ff$shouldHandleExternalPipeFill(final FluidStack resource) {
        if (!FlowingFluids.config.enableMod
                || resource == null
                || resource.isEmpty()
                || !FluidHelper.hasBlockState(resource.getFluid())
                || !FlowingFluids.config.isFluidAllowed(resource.getFluid())) {
            return false;
        }
        int actualTankCapacity = internalTank.getTankCapacity(0);
        int storedAmount = internalTank.getFluidAmount();
        return resource.getAmount() > 1000
                || storedAmount > actualTankCapacity
                || storedAmount + resource.getAmount() > actualTankCapacity;
    }

    @Unique
    private Fluid ff$resolveDrainableFluid() {
        Fluid fluid = ((FluidDrainingBehaviourAccessor) drainer).ff$getFluid();
        if (fluid == null) {
            Fluid worldFluid = drainer.getWorld().getFluidState(rootPosGetter.get()).getType();
            if (FlowingFluids.config.isFluidAllowed(worldFluid)) {
                ((FluidDrainingBehaviourAccessor) drainer).ff$setFluid(FluidHelper.convertToStill(worldFluid));
                fluid = worldFluid;
            }
        }
        return fluid == null ? Fluids.EMPTY : fluid;
    }

    @Unique
    private void ff$setInternalTankFluid(final FluidStack template, final int amount) {
        if (amount <= 0) {
            internalTank.setFluid(FluidStack.EMPTY);
            return;
        }
        FluidStack updated = template.copy();
        updated.setAmount(amount);
        internalTank.setFluid(updated);
    }

    @Unique
    private FluidStack ff$copyFluidWithAmount(final FluidStack template, final Fluid fluid, final int amount) {
        FluidStack copy = template != null && !template.isEmpty() ? template.copy() : new FluidStack(fluid, amount);
        copy.setAmount(amount);
        return copy;
    }

    @Unique
    private int ff$levelsToMilliBuckets(final int levels) {
        return Math.max(0, levels) * FF_LEVEL_MILLIBUCKETS;
    }

    @Unique
    private int ff$placeFluidFromHosePulley(final FlowingFluid flowing, final int requestedLevels,
                                            final boolean execute, final boolean allowWideSearch) {
        if (requestedLevels <= 0) {
            return 0;
        }

        int remainingLevels = requestedLevels;
        BlockPos rootPos = rootPosGetter.get();
        var world = filler.getWorld();

        if (ff$shouldProtectHosePulleyRoot(flowing)) {
            remainingLevels -= ff$tryPlaceRootProtectedLevels(world, rootPos, flowing, remainingLevels, execute);
            remainingLevels = ff$tryPlaceFluidLevelsAroundRoot(world, rootPos, flowing, remainingLevels, execute,
                    Math.max(96, Math.min(FF_EXTERNAL_PIPE_PLACE_PRIMARY_DEPTH, requestedLevels * 6)),
                    allowWideSearch);
        } else {
            remainingLevels = ff$tryPlaceFluidLevels(world, rootPos, flowing, remainingLevels, execute,
                    Math.max(96, Math.min(FF_EXTERNAL_PIPE_PLACE_PRIMARY_DEPTH, requestedLevels * 6)),
                    false,
                    true);

            if (allowWideSearch && remainingLevels > 0) {
                remainingLevels = ff$tryPlaceFluidLevelsAroundRoot(world, rootPos, flowing, remainingLevels, execute,
                        Math.max(FF_EXTERNAL_PIPE_PLACE_PRIMARY_DEPTH,
                                Math.min(FF_EXTERNAL_PIPE_PLACE_SECONDARY_DEPTH, requestedLevels * 12)),
                        true);
            }
        }

        int placedLevels = requestedLevels - remainingLevels;
        if (placedLevels > 0 && execute) {
            ((FluidManipulationBehaviourAccessor) filler)
                    .ff$PlayEffect(world, rootPos, flowing, false);
            drainer.counterpartActed();
        }
        return placedLevels;
    }

    @Unique
    private int ff$tryPlaceFluidLevels(final net.minecraft.world.level.Level world, final BlockPos rootPos,
                                       final FlowingFluid flowing, final int requestedLevels,
                                       final boolean execute, final int searchDepth,
                                       final boolean canSpreadUp, final boolean canSpreadDown) {
        if (requestedLevels <= 0) {
            return 0;
        }
        var placement = FFFluidUtils.placeConnectedFluidAmountAndPlaceAction(
                world,
                rootPos,
                requestedLevels,
                flowing,
                searchDepth,
                canSpreadUp,
                canSpreadDown
        );
        if (execute && placement.second() != null && placement.first() < requestedLevels) {
            placement.second().run();
        }
        return placement.first();
    }

    @Unique
    private int ff$tryPlaceFluidLevelsAroundRoot(final net.minecraft.world.level.Level world, final BlockPos rootPos,
                                                 final FlowingFluid flowing, final int requestedLevels,
                                                 final boolean execute, final int searchDepth,
                                                 final boolean includeWideCandidates) {
        int remaining = requestedLevels;
        remaining = ff$tryPlaceFluidLevelsAtCandidate(world, rootPos.below(), flowing, remaining, execute, searchDepth);

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            remaining = ff$tryPlaceFluidLevelsAtCandidate(world, rootPos.relative(direction), flowing, remaining, execute, searchDepth);
        }

        if (includeWideCandidates && remaining > 0) {
            BlockPos belowRoot = rootPos.below();
            remaining = ff$tryPlaceFluidLevelsAtCandidate(world, belowRoot.below(), flowing, remaining, execute, searchDepth);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                remaining = ff$tryPlaceFluidLevelsAtCandidate(world, belowRoot.relative(direction), flowing, remaining, execute, searchDepth);
            }
        }

        return remaining;
    }

    @Unique
    private int ff$tryPlaceFluidLevelsAtCandidate(final net.minecraft.world.level.Level world, final BlockPos candidatePos,
                                                  final FlowingFluid flowing, final int requestedLevels,
                                                  final boolean execute, final int searchDepth) {
        if (requestedLevels <= 0 || candidatePos == null || !world.isLoaded(candidatePos)) {
            return requestedLevels;
        }

        var candidateState = world.getBlockState(candidatePos);
        FluidState candidateFluid = FFFluidUtils.getEffectiveFluidState(world, candidatePos, candidateState);
        if (!candidateFluid.isEmpty() && !candidateFluid.getType().isSame(flowing)) {
            return requestedLevels;
        }
        if (candidateFluid.isEmpty() && !FFFluidUtils.canStorePartialFluidAmount(world, candidatePos, candidateState, flowing)) {
            return requestedLevels;
        }

        return ff$tryPlaceFluidLevels(world, candidatePos, flowing, requestedLevels, execute, searchDepth, false, true);
    }

    @Unique
    private int ff$tryPlaceRootProtectedLevels(final net.minecraft.world.level.Level world, final BlockPos rootPos,
                                               final FlowingFluid flowing, final int requestedLevels,
                                               final boolean execute) {
        if (requestedLevels <= 0) {
            return 0;
        }

        var rootState = world.getBlockState(rootPos);
        if (!FFFluidUtils.canStorePartialFluidAmount(world, rootPos, rootState, flowing)) {
            return 0;
        }

        FluidState rootFluid = FFFluidUtils.getEffectiveFluidState(world, rootPos, rootState);
        if (!rootFluid.isEmpty() && !rootFluid.getType().isSame(flowing)) {
            return 0;
        }

        int currentAmount = rootFluid.getType().isSame(flowing) ? rootFluid.getAmount() : 0;
        int cap = ff$getHosePulleyRootLevelCap(flowing);
        if (currentAmount >= cap) {
            return 0;
        }

        int placed = Math.min(requestedLevels, cap - currentAmount);
        if (execute && placed > 0) {
            FFFluidUtils.setFluidStateAtPosToNewAmount(world, rootPos, flowing, currentAmount + placed);
        }
        return placed;
    }

    @Unique
    private boolean ff$shouldProtectHosePulleyRoot(final FlowingFluid flowing) {
        return flowing.isSame(Fluids.WATER);
    }

    @Unique
    private int ff$getHosePulleyRootLevelCap(final FlowingFluid flowing) {
        return ff$shouldProtectHosePulleyRoot(flowing) ? FF_HOSE_PULLEY_WATER_ROOT_LEVEL_CAP : 8;
    }
}
#endif
