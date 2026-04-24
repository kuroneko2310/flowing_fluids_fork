package traben.flowing_fluids.forge.mixin.create;

#if MC!=MC_20_1

import org.spongepowered.asm.mixin.Mixin;
import traben.flowing_fluids.config.FFCommands;

@Mixin(FFCommands.class)
public abstract class MixinCombinedTankWrapper {
}
#else

import com.simibubi.create.foundation.fluid.CombinedTankWrapper;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(CombinedTankWrapper.class)
public abstract class MixinCombinedTankWrapper {

    @Shadow(remap = false)
    protected IFluidHandler[] itemHandler;

    @Shadow(remap = false)
    protected boolean enforceVariety;

    @Inject(method = "fill", at = @At("HEAD"), cancellable = true, remap = false)
    private void ff$preventSingleFillFromSplittingAcrossVarietyTanks(final FluidStack resource,
                                                                      final IFluidHandler.FluidAction action,
                                                                      final CallbackInfoReturnable<Integer> cir) {
        if (!enforceVariety
                || resource == null
                || resource.isEmpty()
                || itemHandler == null
                || itemHandler.length <= 1) {
            return;
        }

        IFluidHandler existingHandler = null;
        for (IFluidHandler handler : itemHandler) {
            if (handler == null) {
                continue;
            }
            boolean hasMatchingFluid = false;
            for (int tank = 0; tank < handler.getTanks(); tank++) {
                FluidStack stackInTank = handler.getFluidInTank(tank);
                if (!stackInTank.isEmpty() && stackInTank.isFluidEqual(resource)) {
                    hasMatchingFluid = true;
                    break;
                }
            }
            if (hasMatchingFluid) {
                existingHandler = handler;
                break;
            }
        }

        if (existingHandler != null) {
            cir.setReturnValue(existingHandler.fill(resource.copy(), action));
            return;
        }

        for (IFluidHandler handler : itemHandler) {
            if (handler == null) {
                continue;
            }
            int simulatedFill = handler.fill(resource.copy(), IFluidHandler.FluidAction.SIMULATE);
            if (simulatedFill <= 0) {
                continue;
            }

            FluidStack limitedResource = resource.copy();
            limitedResource.setAmount(Math.min(resource.getAmount(), simulatedFill));
            cir.setReturnValue(handler.fill(limitedResource, action));
            return;
        }

        cir.setReturnValue(0);
    }
}
#endif
