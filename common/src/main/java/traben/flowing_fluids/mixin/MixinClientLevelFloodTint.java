package traben.flowing_fluids.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import traben.flowing_fluids.client.FloodWaterTint;

import java.util.function.BooleanSupplier;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevelFloodTint {
    @Inject(method = "tick", at = @At("TAIL"))
    private void ff$updateFloodTint(final BooleanSupplier hasTimeLeft, final CallbackInfo ci) {
        FloodWaterTint.onClientLevelTick((ClientLevel) (Object) this);
    }
}
