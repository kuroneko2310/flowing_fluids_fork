package traben.flowing_fluids.forge.mixin.create;

#if MC!=MC_20_1

import org.spongepowered.asm.mixin.Mixin;
import traben.flowing_fluids.config.FFCommands;

@Mixin(FFCommands.class)
public abstract class MixinGenericItemEmptying_HandlesItemDrain {
}
#else

import com.simibubi.create.content.fluids.transfer.GenericItemEmptying;
import net.createmod.catnip.data.Pair;
import net.minecraft.world.item.BottleItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.FlowingFluids;

@Pseudo
@Mixin(GenericItemEmptying.class)
public abstract class MixinGenericItemEmptying_HandlesItemDrain {

    @Inject(method = "emptyItem", at = @At("RETURN"), remap = false)
    private static void ff$onEmptyItem(Level level, ItemStack stack, boolean simulate,
                                       CallbackInfoReturnable<Pair<FluidStack, ItemStack>> cir) {
        Pair<FluidStack, ItemStack> result = cir.getReturnValue();
        if (level == null
                || result == null
                || result.getFirst() == null
                || !FlowingFluids.config.enableMod
                || !FlowingFluids.config.isFluidAllowed(result.getFirst().getFluid())) {
            return;
        }

        if (stack.getItem() instanceof BucketItem) {
            int damage = stack.getDamageValue();
            if (damage <= 0) {
                return;
            }
            result.getFirst().setAmount(ff$bucketAmountToMilliBuckets(8 - damage));
        } else if (stack.getItem() instanceof BottleItem) {
            int damage = stack.getDamageValue();
            if (damage <= 0) {
                return;
            }
            result.getFirst().setAmount(Math.max(0, 3 - damage));
        }
    }

    @Unique
    private static int ff$bucketAmountToMilliBuckets(int level) {
        if (level <= 0) {
            return 0;
        }
        if (level >= 8) {
            return 1000;
        }
        return 1000 / 8 * level;
    }
}
#endif
