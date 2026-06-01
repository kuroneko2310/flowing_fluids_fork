package traben.flowing_fluids.mixin;

import net.minecraft.core.BlockPos;
#if MC > MC_20_1
import net.minecraft.core.dispenser.BlockSource;
#else
import net.minecraft.core.BlockSource;
#endif
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.material.FlowingFluid;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.FFBucketItem;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

import java.util.IdentityHashMap;
import java.util.Map;

@Mixin(value = DispenserBlock.class, priority = 2000)
public class MixinDispenserBlock {
    private static final Map<Item, DispenseItemBehavior> ff$WRAPPED_BEHAVIORS = new IdentityHashMap<>();
    private static final Map<Item, DispenseItemBehavior> ff$WRAPPED_ORIGINALS = new IdentityHashMap<>();

    @Inject(method = "getDispenseMethod", at = @At("RETURN"), cancellable = true)
    private void ff$wrapBehaviour(final ItemStack stack, final CallbackInfoReturnable<DispenseItemBehavior> cir) {
        if (!(stack.getItem() instanceof FFBucketItem bucket)) {
            return;
        }

        DispenseItemBehavior behavior = cir.getReturnValue();
        if (behavior == null) {
            return;
        }

        // Wrap at lookup-time so other mods can temporarily swap and restore dispenser behaviours
        // without us immediately overwriting the registry entry again.
        cir.setReturnValue(ff$resolveWrappedBehavior(stack.getItem(), bucket, behavior));
    }

    private static DispenseItemBehavior ff$resolveWrappedBehavior(final Item item, final FFBucketItem bucket,
                                                                  final DispenseItemBehavior behavior) {
        DispenseItemBehavior cachedOriginal = ff$WRAPPED_ORIGINALS.get(item);
        DispenseItemBehavior cachedWrapped = ff$WRAPPED_BEHAVIORS.get(item);
        if (cachedOriginal == behavior && cachedWrapped != null) {
            return cachedWrapped;
        }

        DispenseItemBehavior wrapped = ff$createWrappedBehavior(bucket, behavior);
        ff$WRAPPED_ORIGINALS.put(item, behavior);
        ff$WRAPPED_BEHAVIORS.put(item, wrapped);
        return wrapped;
    }

    private static DispenseItemBehavior ff$createWrappedBehavior(final FFBucketItem bucket,
                                                                 final DispenseItemBehavior originalBehavior) {
        return new FFWrappedDispenseBehavior(bucket, originalBehavior);
    }

    private static final class FFWrappedDispenseBehavior implements DispenseItemBehavior {
        private final FFBucketItem bucket;
        private final DispenseItemBehavior originalBehavior;
        private final FFDispenseEffectsHelper effectsHelper = new FFDispenseEffectsHelper();

        private FFWrappedDispenseBehavior(final FFBucketItem bucket, final DispenseItemBehavior originalBehavior) {
            this.bucket = bucket;
            this.originalBehavior = originalBehavior;
        }

        @Override
        public @NotNull ItemStack dispense(BlockSource blockSource, ItemStack item) {
            ItemStack customResult = bucket == Items.BUCKET
                    ? tryFillPartialBucket(blockSource, item)
                    : tryEmptyPartialBucket(blockSource, item);
            if (customResult != null) {
                return effectsHelper.finishWithEffects(blockSource, customResult);
            }
            return originalBehavior.dispense(blockSource, item);
        }

        private ItemStack tryFillPartialBucket(BlockSource blockSource, ItemStack item) {
            if (!FlowingFluids.config.enableMod || !(item.getItem() instanceof FFBucketItem bucketItem)) {
                return null;
            }

            BlockPos blockPos = blockSource. #if MC == MC_20_1 getPos()  #else pos() #endif .relative(blockSource. #if MC == MC_20_1 getBlockState()  #else state() #endif .getValue(DispenserBlock.FACING));
            Level level = blockSource. #if MC == MC_20_1 getLevel()  #else level() #endif;
            var fluidState = level.getFluidState(blockPos);
            if (!(fluidState.getType() instanceof FlowingFluid flowingFluid)
                    || !FlowingFluids.config.isFluidAllowed(fluidState)
                    || fluidState.getAmount() <= 0
                    || fluidState.getAmount() >= 8) {
                return null;
            }

            int found = FFFluidUtils.collectConnectedFluidAmountAndRemove(level, blockPos, 1, 8, flowingFluid);
            if (found <= 0) {
                return item;
            }
            return effectsHelper.bucketWithRemainder(blockSource, item,
                    bucketItem.ff$bucketOfAmount(flowingFluid.getBucket().getDefaultInstance(), found));
        }

        private ItemStack tryEmptyPartialBucket(BlockSource blockSource, ItemStack item) {
            if (!FlowingFluids.config.enableMod
                    || !(item.getItem() instanceof FFBucketItem bucketItem)
                    || !FlowingFluids.config.isFluidAllowed(bucketItem.ff$getFluid())) {
                return null;
            }

            BlockPos blockPos = blockSource.#if MC == MC_20_1 getPos()  #else pos() #endif .relative(blockSource. #if MC == MC_20_1 getBlockState()  #else state() #endif .getValue(DispenserBlock.FACING));
            Level level = blockSource.#if MC == MC_20_1 getLevel()  #else level() #endif;
            var fluidState = level.getFluidState(blockPos);
            if (fluidState.getAmount() <= 0 && item.getDamageValue() <= 0) {
                return null;
            }

            // Keep partial buckets usable without forcing the original behavior twice.
            int amountInBucket = 8 - item.getDamageValue();
            int remainder = bucketItem.ff$emptyContents_AndGetRemainder(null, level, blockPos, null, amountInBucket, false);
            if (remainder != amountInBucket) {
                ((DispensibleContainerItem) bucketItem).checkExtraContent(null, level, item, blockPos);
                return effectsHelper.bucketWithRemainder(blockSource, item, bucketItem.ff$bucketOfAmount(item, remainder));
            }
            return item;
        }
    }

    private static final class FFDispenseEffectsHelper extends DefaultDispenseItemBehavior {
        private @NotNull ItemStack finishWithEffects(BlockSource blockSource, ItemStack result) {
            this.playSound(blockSource);
            this.playAnimation(blockSource, blockSource. #if MC == MC_20_1 getBlockState()  #else state() #endif .getValue(DispenserBlock.FACING));
            return result;
        }

        private @NotNull ItemStack bucketWithRemainder(BlockSource blockSource, ItemStack original, ItemStack replacement) {
            #if MC == MC_20_1
            return replacement;
            #else
            return this.consumeWithRemainder(blockSource, original, replacement);
            #endif
        }
    }

}
