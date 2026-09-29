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
import traben.flowing_fluids.FlowingFluids;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Create places a disassembling contraption's blocks with the piston-move flag. Without this, a gate or door made of
 * non-waterloggable blocks closing into water would delete the water it lands in; with it, the water is displaced
 * sideways and upwards like any other block placement. Waterloggable blocks only become waterlogged when they land in
 * a full cell of water; landing in a few levels no longer turns those levels into a full source.
 */
@Pseudo
@Mixin(Contraption.class)
public abstract class MixinContraptionPlacement {
    @Inject(method = "addBlocksToWorld", at = @At("HEAD"), remap = false, require = 0)
    private void ff$beginPlacement(final Level world, final StructureTransform transform, final CallbackInfo ci) {
        FFFluidUtils.beginContraptionPlacement();
    }

    /**
     * Create waterlogs a placed block whenever the target cell holds any water. A waterlogged block is a full source, so
     * disassembling onto a shallow puddle and assembling again would multiply that water on every cycle. Partial water
     * is reported as empty here; the block is then placed dry and the displacement above pushes the levels aside.
     */
    @ModifyExpressionValue(method = "addBlocksToWorld",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",
                    remap = true),
            remap = false, require = 0)
    private FluidState ff$onlyWaterlogInFullWater(final FluidState original) {
        if (FlowingFluids.config.enableMod
                && FlowingFluids.config.isWaterAllowed()
                && original.getType().isSame(Fluids.WATER)
                && original.getAmount() < 8) {
            return Fluids.EMPTY.defaultFluidState();
        }
        return original;
    }

    @Inject(method = "addBlocksToWorld", at = @At("RETURN"), remap = false, require = 0)
    private void ff$endPlacement(final Level world, final StructureTransform transform, final CallbackInfo ci) {
        FFFluidUtils.endContraptionPlacement();
    }
}
