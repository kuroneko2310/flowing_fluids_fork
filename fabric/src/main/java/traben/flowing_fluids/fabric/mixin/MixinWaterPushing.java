package traben.flowing_fluids.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
#if MC > MC_21
import net.minecraft.world.entity.vehicle.AbstractBoat;
#else
import net.minecraft.world.entity.vehicle.Boat;
#endif
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

@Mixin(Entity.class)
public class MixinWaterPushing {
    @Unique private long ff$shallowWaterMovementTick = Long.MIN_VALUE;
    @Unique private boolean ff$ignoreShallowWaterMovement;
    @Unique private boolean ff$boostFlowingWaterCurrent;

    @ModifyExpressionValue(
            method = "updateFluidHeightAndDoFluidPushing",
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

    @ModifyArg(
            method = "updateInWaterStateAndDoWaterCurrentPushing",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;updateFluidHeightAndDoFluidPushing(Lnet/minecraft/tags/TagKey;D)Z"),
            index = 1
    )
    private double ff$boostFlowingWaterMotionScale(final double original) {
        if (!FlowingFluids.config.enableMod || !ff$shouldBoostFlowingWaterCurrent()) {
            return original;
        }
        return original * FFFluidUtils.getFlowingWaterCurrentPushMultiplier();
    }

    @ModifyVariable(method = "moveRelative", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private float ff$boostShallowWaterMoveRelativeSpeed(final float original) {
        if (original <= 0.0F || !FlowingFluids.config.enableMod) {
            return original;
        }
        // Even when shallow water is treated as walkable, vanilla still feels a little sticky.
        // Give players and mobs a small nudge so puddles feel like wading, not hidden sludge.
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
            // Outside those shallow puddles, strong horizontal current should feel forceful
            // enough that players and mobs cannot simply stroll upstream.
            this.ff$boostFlowingWaterCurrent = FFFluidUtils.shouldBoostFlowingWaterCurrent(entity);
        }
    }
}
