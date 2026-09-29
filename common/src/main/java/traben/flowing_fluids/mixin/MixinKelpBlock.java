package traben.flowing_fluids.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import traben.flowing_fluids.FlowingFluids;

/**
 * Vanilla kelp grows into any water block, and a kelp block always carries a full source of water. With finite water
 * that turns a single level of water into eight on every growth step, so kelp farms (Create harvesters included) would
 * mint water forever. Only let kelp grow into cells that already hold a full 8 levels.
 */
@Mixin(KelpBlock.class)
public abstract class MixinKelpBlock {

    @ModifyReturnValue(method = "canGrowInto", at = @At("RETURN"))
    private boolean ff$onlyGrowIntoFullWater(final boolean original, final BlockState state) {
        if (original
                && FlowingFluids.config.enableMod
                && FlowingFluids.config.isWaterAllowed()) {
            return state.getFluidState().getAmount() >= 8;
        }
        return original;
    }
}
