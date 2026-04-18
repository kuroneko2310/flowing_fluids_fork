package traben.flowing_fluids.forge.mixin.mekanism;

import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.forge.compat.MekanismFluidTankBucketCompat;

@Pseudo
@Mixin(targets = "mekanism.common.item.block.machine.ItemBlockFluidTank$FluidTankItemDispenseBehavior", remap = false)
public abstract class MixinFluidTankItemDispenseBehavior {

    @Inject(
            // The dispense behavior overrides a Minecraft method, so Mekanism ships it reobfuscated in production.
            method = "m_7498_(Lnet/minecraft/core/BlockSource;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void ff$allowPartialFluidBucketMode(BlockSource source, ItemStack stack,
                                                CallbackInfoReturnable<ItemStack> cir) {
        Level level = source.getLevel();
        BlockPos pos = source.getPos().relative(source.getBlockState().getValue(DispenserBlock.FACING));
        ItemStack resultStack = MekanismFluidTankBucketCompat.tryDispenserBucketModePickupOrPlace(level, pos, stack);
        if (resultStack != null) {
            cir.setReturnValue(resultStack);
        }
    }
}
