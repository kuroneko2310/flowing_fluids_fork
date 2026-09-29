package traben.flowing_fluids.forge.mixin.create;


import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import traben.flowing_fluids.FFFluidUtils;

/**
 * Create places a disassembling contraption's blocks with the piston-move flag. Without this, a gate or door made of
 * non-waterloggable blocks closing into water would delete the water it lands in; with it, the water is displaced
 * sideways and upwards like any other block placement. Waterloggable blocks keep taking a full cell as waterlogging,
 * exactly as Create decides.
 */
@Pseudo
@Mixin(Contraption.class)
public abstract class MixinContraptionPlacement {
    @Inject(method = "addBlocksToWorld", at = @At("HEAD"), remap = false, require = 0)
    private void ff$beginPlacement(final Level world, final StructureTransform transform, final CallbackInfo ci) {
        FFFluidUtils.beginContraptionPlacement();
    }

    @Inject(method = "addBlocksToWorld", at = @At("RETURN"), remap = false, require = 0)
    private void ff$endPlacement(final Level world, final StructureTransform transform, final CallbackInfo ci) {
        FFFluidUtils.endContraptionPlacement();
    }
}
