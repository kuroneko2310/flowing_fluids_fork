package traben.flowing_fluids.mixin;

import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.server.Bootstrap;
#if MC >= MC_20_6
import net.minecraft.world.ItemInteractionResult;
#else
import net.minecraft.world.InteractionResult;
#endif
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import traben.flowing_fluids.FlowingFluids;

import java.util.Map;

/**
 * Vanilla cauldrons accept any water or lava bucket item and become completely full, and a full cauldron hands back a
 * full bucket. Flowing Fluids buckets can be partially filled (stored as item damage), so a bucket holding one level of
 * water could be turned into eight levels by filling and emptying a cauldron. Partial buckets are refused instead.
 */
@Mixin(Bootstrap.class)
public abstract class MixinCauldronBootstrap {

    @Inject(method = "bootStrap",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/core/cauldron/CauldronInteraction;bootStrap()V",
                    shift = At.Shift.AFTER))
    private static void ff$refusePartialBucketsInCauldrons(final CallbackInfo ci) {
        ff$wrapBuckets(#if MC >= MC_20_6 CauldronInteraction.EMPTY.map() #else CauldronInteraction.EMPTY #endif);
        ff$wrapBuckets(#if MC >= MC_20_6 CauldronInteraction.WATER.map() #else CauldronInteraction.WATER #endif);
        ff$wrapBuckets(#if MC >= MC_20_6 CauldronInteraction.LAVA.map() #else CauldronInteraction.LAVA #endif);
        ff$wrapBuckets(#if MC >= MC_20_6 CauldronInteraction.POWDER_SNOW.map() #else CauldronInteraction.POWDER_SNOW #endif);
    }

    @Unique
    private static void ff$wrapBuckets(final Map<Item, CauldronInteraction> interactions) {
        ff$wrap(interactions, Items.WATER_BUCKET, true);
        ff$wrap(interactions, Items.LAVA_BUCKET, false);
    }

    @Unique
    private static void ff$wrap(final Map<Item, CauldronInteraction> interactions, final Item bucket, final boolean water) {
        interactions.computeIfPresent(bucket, (item, original) -> (state, level, pos, player, hand, stack) -> {
            if (stack.getDamageValue() > 0
                    && FlowingFluids.config.enableMod
                    && FlowingFluids.config.isFluidAllowed(water ? Fluids.WATER : Fluids.LAVA)) {
                return #if MC >= MC_20_6 ItemInteractionResult.FAIL #else InteractionResult.FAIL #endif ;
            }
            return original.interact(state, level, pos, player, hand, stack);
        });
    }
}
