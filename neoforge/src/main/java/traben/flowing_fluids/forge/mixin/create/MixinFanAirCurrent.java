package traben.flowing_fluids.forge.mixin.create;

import com.simibubi.create.content.kinetics.fan.AirCurrent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import traben.flowing_fluids.forge.compat.CreateFanWashingCompat;

/**
 * Remembers which air current is processing items so a finished bulk wash can drink from that current's water.
 */
@Pseudo
@Mixin(AirCurrent.class)
public abstract class MixinFanAirCurrent {
    @Inject(method = {"tickAffectedEntities", "tickAffectedHandlers"}, at = @At("HEAD"), remap = false, require = 0)
    private void ff$enter(final CallbackInfo ci) {
        CreateFanWashingCompat.enter((AirCurrent) (Object) this);
    }

    @Inject(method = {"tickAffectedEntities", "tickAffectedHandlers"}, at = @At("RETURN"), remap = false, require = 0)
    private void ff$exit(final CallbackInfo ci) {
        CreateFanWashingCompat.exit();
    }
}
