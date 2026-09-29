package traben.flowing_fluids.forge.mixin.create;




import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

@Pseudo
@Mixin(OpenEndedPipe.class)
public abstract class MixinPipe{

    @Shadow(remap = false) private Level world;

    @Shadow(remap = false) private BlockPos outputPos;

    /**
     * Create only drains the intake cell itself, so once that cell is pumped dry a pump stalls until water flows back
     * in. For a dry, open intake, gather a full bucket from the connected water around it instead, so pumps keep
     * draining shallow flood water. Exactly 8 levels or nothing: Create's drain handler always reports a full 1000 mB
     * for any non-empty world drain, so returning a partial bucket would create water.
     */
    @Inject(method = "removeFluidFromSpace", at = @At("HEAD"), cancellable = true, remap = false)
    private void ff$pullAroundDryIntake(final boolean simulate, final CallbackInfoReturnable<FluidStack> cir) {
        if (world == null
                || world.isClientSide()
                || !FlowingFluids.config.enableMod
                || FlowingFluids.config.create_infinitePipes
                || !FlowingFluids.config.isWaterAllowed()
                || !world.isLoaded(outputPos)) {
            return;
        }
        BlockState intake = world.getBlockState(outputPos);
        if (!intake.isAir() || !intake.getFluidState().isEmpty()) {
            return;
        }
        Runnable apply = FFFluidUtils.collectAroundDryIntake(world, outputPos, Fluids.WATER, 8, 40);
        if (apply == null) {
            return;
        }
        if (!simulate) {
            apply.run();
        }
        cir.setReturnValue(new FluidStack(Fluids.WATER, 1000));
    }

    @ModifyArg(method = "removeFluidFromSpace",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"
            , ordinal = 1), index = 1)
    private BlockState ff$modifyWaterRemoval(final BlockState blockState) {
        if (FlowingFluids.config.enableMod
                && !FlowingFluids.config.create_infinitePipes
                && FlowingFluids.config.isFluidAllowed(blockState.getFluidState())){
            return Blocks.AIR.defaultBlockState();
        }
        return blockState;
    }

    @ModifyExpressionValue(method = "removeFluidFromSpace",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/foundation/mixin/accessor/FlowingFluidAccessor;create$getNewLiquid(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/material/FluidState;"
            ), remap = false)
    private FluidState ff$preventInfiniteWaterCheck(final FluidState original) {
        if (FlowingFluids.config.enableMod
                && !FlowingFluids.config.create_infinitePipes
                && FlowingFluids.config.isFluidAllowed(original)){
            return Fluids.EMPTY.defaultFluidState();
        }
        return original;
    }

    @Inject(method = "removeFluidFromSpace",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;isSource()Z"
            ), cancellable = true)
    private void ff$alternativeHandling(final CallbackInfoReturnable<FluidStack> cir,
                                        @Local(ordinal = 0, argsOnly = true) boolean simulate,
                                        @Local(ordinal = 1) boolean waterlogged,
                                        @Local(ordinal = 0) FluidState state) {
        if (!state.isSource()
                && !waterlogged
                && FlowingFluids.config.enableMod
                && FlowingFluids.config.isFluidAllowed(state)
                && state.getType() instanceof FlowingFluid flowing) {
            // search for 8 levels of CONNECTED fluid
            var data = FFFluidUtils.collectConnectedFluidAmountAndRemoveAction(
                    this.world,
                    this.outputPos,
                    8,
                    8,
                    flowing,
                    40);

            if (data.first() == 8) {
                if (!simulate && !FlowingFluids.config.create_infinitePipes) {
                    data.second().run();
                }
                cir.setReturnValue(new FluidStack(state.getType(), 1000));
            }
        }
    }
}

