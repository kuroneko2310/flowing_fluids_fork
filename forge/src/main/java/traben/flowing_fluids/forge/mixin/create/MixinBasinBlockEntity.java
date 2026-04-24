package traben.flowing_fluids.forge.mixin.create;

#if MC!=MC_20_1

import org.spongepowered.asm.mixin.Mixin;
import traben.flowing_fluids.config.FFCommands;

@Mixin(FFCommands.class)
public abstract class MixinBasinBlockEntity {
}
#else

import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.forge.compat.CreateBasinExternalFluidCompat;

@Pseudo
@Mixin(BasinBlockEntity.class)
public abstract class MixinBasinBlockEntity {

    @Unique
    private Fluid ff$lastKnownExternalFluid = Fluids.EMPTY;

    @Unique
    private int ff$lastKnownExternalLevels = Integer.MIN_VALUE;

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void ff$trackExternalFluidChanges(final CallbackInfo ci) {
        BasinBlockEntity basin = (BasinBlockEntity) (Object) this;
        if (basin.getLevel() == null || basin.getLevel().isClientSide) {
            return;
        }

        var externalFluid = CreateBasinExternalFluidCompat.getExternalFluid(basin);
        Fluid fluid = externalFluid.fluid();
        int levels = externalFluid.levels();
        if (ff$lastKnownExternalLevels == Integer.MIN_VALUE) {
            ff$lastKnownExternalFluid = fluid;
            ff$lastKnownExternalLevels = levels;
            if (levels > 0) {
                basin.notifyChangeOfContents();
            }
            return;
        }

        if (ff$lastKnownExternalLevels == levels && ff$lastKnownExternalFluid.isSame(fluid)) {
            return;
        }

        ff$lastKnownExternalFluid = fluid;
        ff$lastKnownExternalLevels = levels;
        basin.notifyChangeOfContents();
    }

    @Inject(method = "isEmpty", at = @At("RETURN"), cancellable = true, remap = false)
    private void ff$includeExternalFluidInEmptinessCheck(final CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }

        BasinBlockEntity basin = (BasinBlockEntity) (Object) this;
        if (CreateBasinExternalFluidCompat.getExternalFluid(basin).isPresent()) {
            cir.setReturnValue(false);
        }
    }
}
#endif
