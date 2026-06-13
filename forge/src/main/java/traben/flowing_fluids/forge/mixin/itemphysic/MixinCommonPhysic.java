package traben.flowing_fluids.forge.mixin.itemphysic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import team.creative.itemphysic.common.CommonPhysic;
import traben.flowing_fluids.FFFluidUtils;

@Pseudo
@Mixin(CommonPhysic.class)
public abstract class MixinCommonPhysic {
    @Redirect(
            method = "getFluid(Lnet/minecraft/world/entity/item/ItemEntity;Z)Lnet/minecraft/world/level/material/Fluid;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;m_6425_(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"),
            remap = false,
            require = 0
    )
    private static FluidState ff$getEffectiveItemFluid(final Level level, final BlockPos pos) {
        return FFFluidUtils.getEffectiveFluidState(level, pos, level.getBlockState(pos));
    }
}
