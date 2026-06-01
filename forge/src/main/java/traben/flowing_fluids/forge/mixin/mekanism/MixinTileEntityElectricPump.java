package traben.flowing_fluids.forge.mixin.mekanism;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.forge.compat.MekanismFluidTankBucketCompat;

@Pseudo
@Mixin(targets = "mekanism.common.tile.machine.TileEntityElectricPump", remap = false)
public abstract class MixinTileEntityElectricPump {

    @Shadow
    protected abstract boolean validFluid(FluidStack fluidStack);

    @Shadow
    protected abstract FluidStack getOutput(net.minecraft.world.level.material.Fluid sourceFluid, boolean hasFilter);

    @Shadow
    protected abstract void suck(FluidStack fluidStack, BlockPos pos, boolean addRecurring);

    @Inject(method = "suck(Lnet/minecraft/core/BlockPos;ZZ)Z", at = @At("HEAD"), cancellable = true)
    private void ff$allowPartialFluidSuck(BlockPos pos, boolean hasFilter, boolean addRecurring,
                                          CallbackInfoReturnable<Boolean> cir) {
        BlockEntity blockEntity = (BlockEntity) (Object) this;
        Level level = blockEntity.getLevel();
        if (level == null || !FlowingFluids.config.enableMod) {
            return;
        }

        BlockState blockState = level.getBlockState(pos);
        FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, pos, blockState);
        if (fluidState.isEmpty()
                || fluidState.isSource()
                || fluidState.getAmount() >= 8
                || !(fluidState.getType() instanceof FlowingFluid flowingFluid)
                || !FlowingFluids.config.isFluidAllowed(fluidState)) {
            return;
        }

        var drainResult = MekanismFluidTankBucketCompat.createSingleBlockPartialDrainResult(level, pos, blockState);
        if (drainResult == null || drainResult.drainedLevels() <= 0) {
            return;
        }

        FluidStack fullOutput = getOutput(fluidState.getType(), hasFilter);
        int scaledAmount = Math.max(1, (int) Math.floor((fullOutput.getAmount() * (double) drainResult.drainedLevels()) / 8.0D));
        FluidStack partialOutput = fullOutput.copy();
        partialOutput.setAmount(scaledAmount);
        if (!validFluid(partialOutput)) {
            return;
        }

        drainResult.apply().run();
        suck(partialOutput, pos, addRecurring);
        cir.setReturnValue(true);
    }
}
