package traben.flowing_fluids.forge.mixin.itemphysic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import team.creative.itemphysic.server.ItemPhysicServer;
import traben.flowing_fluids.FFFluidUtils;

@Pseudo
@Mixin(ItemPhysicServer.class)
public abstract class MixinItemPhysicServer {
    @Redirect(
            method = "updateFluidHeightAndDoFluidPushing",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;m_6425_(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"),
            remap = false,
            require = 0
    )
    private static FluidState ff$getEffectiveFluidHeightCell(final Level level, final BlockPos pos) {
        return FFFluidUtils.getEffectiveFluidState(level, pos, level.getBlockState(pos));
    }
}
