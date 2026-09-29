package traben.flowing_fluids.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import traben.flowing_fluids.client.FloodWaterTint;

/**
 * Both the vanilla liquid renderer and Embeddium/Sodium resolve water tint through {@link Biome#getWaterColor()},
 * so this single hook colours flood water for either renderer.
 */
@Mixin(Biome.class)
public abstract class MixinBiomeWaterColor {
    @ModifyReturnValue(method = "getWaterColor", at = @At("RETURN"))
    private int ff$murkyFloodWater(final int original) {
        return FloodWaterTint.tint((Biome) (Object) this, original);
    }
}
