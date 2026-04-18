package traben.flowing_fluids.forge.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.fluids.FluidType;
#if MC > MC_21
import net.minecraft.world.entity.vehicle.AbstractBoat;
#else
import net.minecraft.world.entity.vehicle.Boat;
#endif
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

@Mixin(Entity.class)
public class MixinWaterPushing {
    @Unique private long ff$shallowWaterMovementTick = Long.MIN_VALUE;
    @Unique private boolean ff$ignoreShallowWaterMovement;
    @Unique private boolean ff$boostFlowingWaterCurrent;

    @ModifyExpressionValue(
            method = "updateFluidHeightAndDoFluidPushing(Ljava/util/function/Predicate;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;isPushedByFluid()Z")

    )
    private boolean ff$isPushed(final boolean original) {
        if (!original) return false;
        if (ff$shouldIgnoreShallowWaterMovement()) return false;

        if (FlowingFluids.config.enableMod) {
            // if it doesn't anyway just take that
            Object entity = this;
            if (entity instanceof Player) return FlowingFluids.config.waterFlowAffectsPlayers;
            if (entity instanceof #if MC > MC_21 AbstractBoat #else Boat #endif ) return FlowingFluids.config.waterFlowAffectsBoats;
            if (entity instanceof ItemEntity) return FlowingFluids.config.waterFlowAffectsItems;

            return FlowingFluids.config.waterFlowAffectsEntities;
        }
        return true;
    }

    @ModifyReturnValue(method = "isInWater", at = @At("RETURN"))
    private boolean ff$ignoreShallowWaterInWaterCheck(final boolean original) {
        if (!original || !FlowingFluids.config.enableMod) {
            return original;
        }
        return ff$shouldIgnoreShallowWaterMovement() ? false : original;
    }

    @ModifyReturnValue(method = "updateFluidHeightAndDoFluidPushing(Lnet/minecraft/tags/TagKey;D)Z", at = @At("RETURN"))
    private boolean ff$ignoreShallowWaterFluidUpdate(final boolean original, final TagKey<Fluid> fluidTag, final double motionScale) {
        if (!original || !FlowingFluids.config.enableMod || !FluidTags.WATER.equals(fluidTag)) {
            return original;
        }
        return ff$shouldIgnoreShallowWaterMovement() ? false : original;
    }

    @ModifyReturnValue(method = "getFluidHeight", at = @At("RETURN"))
    private double ff$ignoreShallowWaterFluidHeight(final double original, final TagKey<Fluid> fluidTag) {
        if (original <= 0.0D || !FluidTags.WATER.equals(fluidTag) || !FlowingFluids.config.enableMod) {
            return original;
        }
        // Zeroing the sampled water height keeps shallow puddles on the walking path,
        // so vanilla travel code does not quietly re-enter swim slowdown.
        return ff$shouldIgnoreShallowWaterMovement() ? 0.0D : original;
    }

    @ModifyReturnValue(method = "getFluidTypeHeight", at = @At("RETURN"), remap = false)
    private double ff$ignoreShallowWaterFluidTypeHeight(final double original, final FluidType fluidType) {
        if (original <= 0.0D || fluidType != ForgeMod.WATER_TYPE.get() || !FlowingFluids.config.enableMod) {
            return original;
        }
        // Forge checks fluid type height before it falls back to the vanilla water-tag path,
        // so shallow puddles need the same zero-height override here to stay walkable.
        return ff$shouldIgnoreShallowWaterMovement() ? 0.0D : original;
    }

    @ModifyExpressionValue(
            method = "lambda$updateFluidHeightAndDoFluidPushing$29",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getFluidMotionScale(Lnet/minecraftforge/fluids/FluidType;)D"),
            remap = false
    )
    private double ff$boostForgeWaterCurrentMotionScale(final double original, final FluidType fluidType) {
        if (original <= 0.0D || fluidType != ForgeMod.WATER_TYPE.get() || !FlowingFluids.config.enableMod) {
            return original;
        }
        // Forge resolves fluid current strength through an inherited default helper, so
        // boost the call site instead of targeting the method directly on Entity.
        return ff$shouldBoostFlowingWaterCurrent() ? original * FFFluidUtils.getFlowingWaterCurrentPushMultiplier() : original;
    }

    @ModifyVariable(method = "moveRelative", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private float ff$boostShallowWaterMoveRelativeSpeed(final float original) {
        if (original <= 0.0F || !FlowingFluids.config.enableMod) {
            return original;
        }
        // Forge still leaves a bit of hidden drag when feet are clipping shallow water,
        // so slightly boost the relative move input to keep puddles feeling light.
        if (ff$shouldIgnoreShallowWaterMovement()) {
            return original * FFFluidUtils.getShallowWaterMovementSpeedMultiplier();
        }
        if (ff$shouldBoostFlowingWaterCurrent()) {
            return original * FFFluidUtils.getFlowingWaterCurrentMoveInputMultiplier();
        }
        return original;
    }

    @Unique
    private boolean ff$shouldIgnoreShallowWaterMovement() {
        ff$refreshWaterMovementState();
        return this.ff$ignoreShallowWaterMovement;
    }

    @Unique
    private boolean ff$shouldBoostFlowingWaterCurrent() {
        ff$refreshWaterMovementState();
        return this.ff$boostFlowingWaterCurrent;
    }

    @Unique
    private void ff$refreshWaterMovementState() {
        Entity entity = (Entity) (Object) this;
        long gameTime = entity.level().getGameTime();
        if (this.ff$shallowWaterMovementTick != gameTime) {
            this.ff$shallowWaterMovementTick = gameTime;
            // Treat level-3 puddles as walkable so wading does not feel like swimming.
            this.ff$ignoreShallowWaterMovement = FFFluidUtils.shouldIgnoreShallowWaterMovement(entity);
            // Deeper flowing water should drag land mobs and players with conviction.
            this.ff$boostFlowingWaterCurrent = FFFluidUtils.shouldBoostFlowingWaterCurrent(entity);
        }
    }
}
