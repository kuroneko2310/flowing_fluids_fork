package traben.flowing_fluids.forge.mixin.create;

#if MC!=MC_20_1

import org.spongepowered.asm.mixin.Mixin;
import traben.flowing_fluids.config.FFCommands;

@Mixin(FFCommands.class)
public abstract class MixinBasinRecipe {
}
#else

import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinRecipe;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour.TankSegment;
import com.simibubi.create.foundation.fluid.FluidIngredient;
import com.simibubi.create.foundation.recipe.DummyCraftingContainer;
import net.createmod.catnip.data.Iterate;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.forge.compat.CreateBasinExternalFluidCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

@Pseudo
@Mixin(BasinRecipe.class)
public abstract class MixinBasinRecipe {

    @Inject(
            method = "apply(Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;Lnet/minecraft/world/item/crafting/Recipe;Z)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void ff$allowExternalFluidInputs(final BasinBlockEntity basin, final Recipe<?> recipe, final boolean test,
                                                    final CallbackInfoReturnable<Boolean> cir) {
        var externalFluid = CreateBasinExternalFluidCompat.getExternalFluid(basin);
        if (!externalFluid.isPresent()) {
            return;
        }

        cir.setReturnValue(ff$applyWithExternalFluid(basin, recipe, test, externalFluid));
    }

    @Unique
    private static boolean ff$applyWithExternalFluid(final BasinBlockEntity basin, final Recipe<?> recipe,
                                                     final boolean test,
                                                     final CreateBasinExternalFluidCompat.ExternalBasinFluid externalFluid) {
        boolean isBasinRecipe = recipe instanceof BasinRecipe;
        IItemHandler availableItems = basin.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        IFluidHandler availableFluids = basin.getCapability(ForgeCapabilities.FLUID_HANDLER).orElse(null);

        if (availableItems == null || availableFluids == null || basin.getLevel() == null) {
            return false;
        }

        HeatLevel heat = BasinBlockEntity.getHeatLevelOf(basin.getLevel().getBlockState(basin.getBlockPos().below()));
        if (isBasinRecipe && !((BasinRecipe) recipe).getRequiredHeat().testBlazeBurner(heat)) {
            return false;
        }

        List<ItemStack> recipeOutputItems = new ArrayList<>();
        List<FluidStack> recipeOutputFluids = new ArrayList<>();

        List<Ingredient> ingredients = new LinkedList<>(recipe.getIngredients());
        List<FluidIngredient> fluidIngredients =
                isBasinRecipe ? ((BasinRecipe) recipe).getFluidIngredients() : Collections.emptyList();

        for (boolean simulate : Iterate.trueAndFalse) {
            if (!simulate && test) {
                return true;
            }

            int[] extractedItemsFromSlot = new int[availableItems.getSlots()];
            int[] extractedFluidsFromTank = new int[availableFluids.getTanks()];
            int extractedExternalMilliBuckets = 0;
            FluidStack mutableExternalFluid = externalFluid.asStack();

            Ingredients:
            for (Ingredient ingredient : ingredients) {
                for (int slot = 0; slot < availableItems.getSlots(); slot++) {
                    if (simulate && availableItems.getStackInSlot(slot).getCount() <= extractedItemsFromSlot[slot]) {
                        continue;
                    }
                    ItemStack extracted = availableItems.extractItem(slot, 1, true);
                    if (!ingredient.test(extracted)) {
                        continue;
                    }
                    if (!simulate) {
                        availableItems.extractItem(slot, 1, false);
                    }
                    extractedItemsFromSlot[slot]++;
                    continue Ingredients;
                }

                return false;
            }

            boolean tankFluidsAffected = false;
            boolean externalFluidAffected = false;

            FluidIngredients:
            for (FluidIngredient fluidIngredient : fluidIngredients) {
                int amountRequired = fluidIngredient.getRequiredAmount();

                for (int tank = 0; tank < availableFluids.getTanks(); tank++) {
                    FluidStack fluidStack = availableFluids.getFluidInTank(tank);
                    if (simulate && fluidStack.getAmount() <= extractedFluidsFromTank[tank]) {
                        continue;
                    }
                    if (!fluidIngredient.test(fluidStack)) {
                        continue;
                    }
                    int drainedAmount = Math.min(amountRequired, fluidStack.getAmount());
                    if (!simulate) {
                        fluidStack.shrink(drainedAmount);
                        tankFluidsAffected = true;
                    }
                    amountRequired -= drainedAmount;
                    if (amountRequired != 0) {
                        continue;
                    }
                    extractedFluidsFromTank[tank] += drainedAmount;
                    continue FluidIngredients;
                }

                if (!mutableExternalFluid.isEmpty()
                        && (!simulate || mutableExternalFluid.getAmount() > extractedExternalMilliBuckets)
                        && fluidIngredient.test(mutableExternalFluid)) {
                    int availableExternalAmount = simulate
                            ? mutableExternalFluid.getAmount() - extractedExternalMilliBuckets
                            : mutableExternalFluid.getAmount();
                    int drainedAmount = Math.min(amountRequired, availableExternalAmount);
                    if (!simulate) {
                        mutableExternalFluid.shrink(drainedAmount);
                        externalFluidAffected = true;
                    }
                    amountRequired -= drainedAmount;
                    extractedExternalMilliBuckets += drainedAmount;
                    if (amountRequired == 0) {
                        continue;
                    }
                }

                return false;
            }

            if (tankFluidsAffected) {
                basin.getBehaviour(SmartFluidTankBehaviour.INPUT).forEach(TankSegment::onFluidStackChanged);
                basin.getBehaviour(SmartFluidTankBehaviour.OUTPUT).forEach(TankSegment::onFluidStackChanged);
            }
            if (externalFluidAffected) {
                CreateBasinExternalFluidCompat.writeExternalFluid(basin, externalFluid.fluid(), mutableExternalFluid.getAmount());
            }

            if (simulate) {
                CraftingContainer remainderContainer = new DummyCraftingContainer(availableItems, extractedItemsFromSlot);

                if (recipe instanceof BasinRecipe basinRecipe) {
                    recipeOutputItems.addAll(basinRecipe.rollResults());

                    for (FluidStack fluidStack : basinRecipe.getFluidResults()) {
                        if (!fluidStack.isEmpty()) {
                            recipeOutputFluids.add(fluidStack);
                        }
                    }
                    for (ItemStack stack : basinRecipe.getRemainingItems(remainderContainer)) {
                        if (!stack.isEmpty()) {
                            recipeOutputItems.add(stack);
                        }
                    }
                } else {
                    recipeOutputItems.add(recipe.getResultItem(basin.getLevel().registryAccess()));

                    if (recipe instanceof CraftingRecipe craftingRecipe) {
                        for (ItemStack stack : craftingRecipe.getRemainingItems(remainderContainer)) {
                            if (!stack.isEmpty()) {
                                recipeOutputItems.add(stack);
                            }
                        }
                    }
                }
            }

            if (!basin.acceptOutputs(recipeOutputItems, recipeOutputFluids, simulate)) {
                return false;
            }
        }

        return true;
    }
}
#endif
