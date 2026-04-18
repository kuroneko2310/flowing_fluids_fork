package traben.flowing_fluids.forge.mixin.mekanism;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.forge.compat.MekanismFluidTankBucketCompat;

@Pseudo
@Mixin(targets = "mekanism.common.item.block.machine.ItemBlockFluidTank", remap = false)
public abstract class MixinItemBlockFluidTank extends Item {

    protected MixinItemBlockFluidTank(Properties properties) {
        super(properties);
    }

    @Inject(
            // Mekanism reobfuscates Minecraft override names inside its runtime jar,
            // so we target the runtime signature here and only remap the Minecraft call site below.
            method = "m_7203_(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;",
            at = @At(
                    value = "INVOKE",
                    // In the shipped Mekanism jar this inherited Item helper is invoked through the target class.
                    target = "Lmekanism/common/item/block/machine/ItemBlockFluidTank;m_41435_(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/ClipContext$Fluid;)Lnet/minecraft/world/phys/BlockHitResult;",
                    shift = At.Shift.BEFORE,
                    remap = false
            ),
            cancellable = true,
            remap = false
    )
    private void ff$allowPartialFluidPickup(Level world, Player player, InteractionHand hand,
                                            CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        BlockHitResult hitResult = getPlayerPOVHitResult(world, player, player.isShiftKeyDown() ? ClipContext.Fluid.NONE : ClipContext.Fluid.ANY);
        InteractionResultHolder<ItemStack> result = player.isShiftKeyDown()
                ? MekanismFluidTankBucketCompat.tryPlacePartialFluid(world, player, hand, hitResult)
                : MekanismFluidTankBucketCompat.tryPickupBucketModeFluid(world, player, hand, hitResult);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
