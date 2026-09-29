package traben.flowing_fluids.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.FFFluidUtils;

@Mixin(WalkNodeEvaluator.class)
public abstract class MixinWalkNodeEvaluator extends net.minecraft.world.level.pathfinder.NodeEvaluator {

    @Inject(method = "getCachedPathType", at = @At("RETURN"), cancellable = true)
    private void ff$treatShallowWaterAsWalkableForGroundMobs(final int x,
                                                             final int y,
                                                             final int z,
                                                             final CallbackInfoReturnable<PathType> cir) {
        if (cir.getReturnValue() != PathType.WATER || mob == null) {
            return;
        }

        // Ground mobs should see ankle-deep puddles as land-like footing so their pathfinder
        // keeps crossing them instead of detouring as if they were proper swim tiles.
        if (FFFluidUtils.canGroundMobPathfindThroughShallowWater(mob, new net.minecraft.core.BlockPos(x, y, z))) {
            cir.setReturnValue(PathType.WALKABLE);
        }
    }
}
