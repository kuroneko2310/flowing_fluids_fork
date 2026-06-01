package traben.flowing_fluids.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.FFFluidUtils;

@Mixin(NaturalSpawner.class)
public class MixinNaturalSpawner {

    @Inject(method = "isSpawnPositionOk", at = @At("HEAD"), cancellable = true)
    private static void ff$allowGroundMobSpawnInShallowWater(final SpawnPlacements.Type placementType,
                                                             final LevelReader level,
                                                             final BlockPos pos,
                                                             final EntityType<?> entityType,
                                                             final CallbackInfoReturnable<Boolean> cir) {
        if (placementType != SpawnPlacements.Type.ON_GROUND
                || entityType == null
                || !(level instanceof LevelAccessor levelAccessor)) {
            return;
        }

        // This pre-check is the shared "body can stand here" gate that rejects any non-empty fluid
        // before mob-specific spawn rules run, so shallow puddles need to be carved out here.
        if (FFFluidUtils.canGroundMobSpawnInShallowWater(entityType, levelAccessor, pos)) {
            cir.setReturnValue(true);
        }
    }
}
