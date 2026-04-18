package traben.flowing_fluids.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.ExtendedWaterlogStore;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

@Mixin(Level.class)
public abstract class MixinLevelFluidState {

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void ff$useEffectiveFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (FlowingFluids.config == null
                || !FlowingFluids.config.enableMod
                || !FlowingFluids.config.enableExtendedWaterlogging) {
            return;
        }

        Level level = (Level) (Object) this;
        if (level.isClientSide) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!ExtendedWaterlogStore.has(level, pos) && !FFFluidUtils.supportsVirtualFluidState(level, state)) {
            return;
        }

        cir.setReturnValue(FFFluidUtils.getEffectiveFluidState(level, pos, state));
    }
}
