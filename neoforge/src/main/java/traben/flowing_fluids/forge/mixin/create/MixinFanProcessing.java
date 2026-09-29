package traben.flowing_fluids.forge.mixin.create;

import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessing;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.forge.compat.CreateFanWashingCompat;

/**
 * Reports finished fan processing steps; only bulk washing inside a tracked air current uses water.
 */
@Pseudo
@Mixin(FanProcessing.class)
public abstract class MixinFanProcessing {
    @Inject(method = "applyProcessing(Lnet/minecraft/world/entity/item/ItemEntity;Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;)Z",
            at = @At("RETURN"), remap = false, require = 0)
    private static void ff$afterEntityWash(final ItemEntity entity, final FanProcessingType type, final CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            CreateFanWashingCompat.onProcessed(type);
        }
    }

    @Inject(method = "applyProcessing(Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;Lnet/minecraft/world/level/Level;Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;)Lcom/simibubi/create/content/kinetics/belt/behaviour/TransportedItemStackHandlerBehaviour$TransportedResult;",
            at = @At("RETURN"), remap = false, require = 0)
    private static void ff$afterBeltWash(final TransportedItemStack transported, final Level world, final FanProcessingType type,
                                         final CallbackInfoReturnable<TransportedResult> cir) {
        TransportedResult result = cir.getReturnValue();
        if (result != null && !result.doesNothing()) {
            CreateFanWashingCompat.onProcessed(type);
        }
    }
}
