package traben.flowing_fluids.forge.mixin.create;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.config.FFConfig;
import traben.flowing_fluids.water.FluidFlowActivityTracker;

import java.util.Set;

@Pseudo
@Mixin(WaterWheelBlockEntity.class)
public abstract class MixinWaterWheel extends GeneratingKineticBlockEntity {

    /** Levels per second through a size-1 wheel's sampled cells that count as "normal" flow (1x speed). */
    @Unique
    private static final double FF$REFERENCE_LEVELS_PER_SECOND = 8.0D;
    @Unique
    private static final float FF$MIN_SPEED_MULTIPLIER = 0.5F;

    @Shadow(remap = false) protected abstract Set<BlockPos> getOffsetsToCheck();

    @Shadow(remap = false) public abstract void setFlowScoreAndUpdate(final int score);

    @Shadow(remap = false) protected abstract int getSize();

    @Shadow(remap = false) public int flowScore;

    @Unique
    private float ff$speedMultiplier = 1.0F;

    public MixinWaterWheel(final BlockEntityType<?> type, final BlockPos pos, final BlockState state) {
        super(type, pos, state);
    }

    @Inject(method = "determineAndApplyFlowScore",
            at = @At(value = "HEAD"),
            cancellable = true, remap = false)
    private void ff$modifyWaterCheck(final CallbackInfo ci) {
        try {
            //leave if no change or level is null
            if (!FlowingFluids.config.enableMod
                    || FlowingFluids.config.create_waterWheelMode == FFConfig.CreateWaterWheelMode.REQUIRE_FLOW
                    || level == null
            ) return;

            //if REQUIRE_FLOW_OR_RIVER, check for river else fallback to regular flow check
            if (FlowingFluids.config.create_waterWheelMode.isRiver()
                    && !(level.getBiome(worldPosition).is(BiomeTags.IS_RIVER)
                    && Math.abs(worldPosition.getY() - level.getSeaLevel()) <= 5)
            ) {
                if (FlowingFluids.config.create_waterWheelMode.isRiverOnly()) {
                    ci.cancel();
                    ff$speedMultiplier = 1.0F;
                    this.setFlowScoreAndUpdate(0);
                }
                return;
            }

            //from here onwards the only possibilities are
            // - REQUIRE_FULL_FLUID
            // - REQUIRE_FLUID
            // - REQUIRE_FLOW_OR_RIVER and are in a river biome near sea level
            // - RIVER_ONLY and are in a river biome near sea level
            //all of these only require simple water count checks and don't need complex flow checks


            //the mixin will now always cancel the default method
            ci.cancel();
            ff$speedMultiplier = 1.0F;

            //settings for alternative checks
            boolean fluidCanBeAnyHeight = !FlowingFluids.config.create_waterWheelMode.needsFullFluid();
            boolean oppositeSpin = FlowingFluids.config.create_waterWheelMode.isCounterSpin();
            boolean alwaysSpin = FlowingFluids.config.create_waterWheelMode.always();

            //search for valid fluids
            boolean lava = false;
            int score = 0;

            for (final BlockPos blockPos : this.getOffsetsToCheck()) {
                BlockPos checkPos = blockPos.offset(this.worldPosition);
                var fState = level.getFluidState(checkPos);
                lava |= fState.getType().isSame(Fluids.LAVA);

                if (alwaysSpin || (!fState.isEmpty() && (fluidCanBeAnyHeight || fState.getAmount() == 8))) {
                    score += oppositeSpin ? -1 : 1;
                }
            }


            //end setters from super method
            if (score != 0 && !this.level.isClientSide()) {
                this.award(lava ? AllAdvancements.LAVA_WHEEL : AllAdvancements.WATER_WHEEL);
            }

            this.setFlowScoreAndUpdate(score);

        }catch (final Exception ignored){}
    }

    /**
     * Finite water can rest one level apart, which vanilla reads as a permanent current, so a wheel in a still puddle
     * would spin forever. In flow modes only cells whose fluid really changed recently report a current.
     */
    @Inject(method = "getFlowVectorAtPosition", at = @At("HEAD"), cancellable = true, remap = false)
    private void ff$onlyRealFlow(final BlockPos pos, final CallbackInfoReturnable<Vec3> cir) {
        if (level == null
                || level.isClientSide()
                || !FlowingFluids.config.enableMod
                || !FlowingFluids.config.create_waterWheelMode.usesFlow()) {
            return;
        }
        BlockState blockState = level.getBlockState(pos);
        if (blockState.is(Blocks.BUBBLE_COLUMN)) {
            return;
        }
        FluidState fluid = blockState.getFluidState();
        if (fluid.isEmpty() || !FlowingFluids.config.isFluidAllowed(fluid)) {
            return;
        }
        FluidFlowActivityTracker.CellActivity activity = FluidFlowActivityTracker.watch(level, pos);
        // fluid does not tick away from players, so keep the last judgement there instead of stalling far-off machines
        boolean frozen = FlowingFluids.config.dontTickAtLocation(worldPosition, level);
        long maxAge = Math.max(1, FlowingFluids.config.create_waterWheelFlowMaxTickInterval);
        if (!frozen && activity.ticksSinceChange(level.getGameTime()) > maxAge) {
            cir.setReturnValue(Vec3.ZERO);
            return;
        }
        Vec3 flow = fluid.getFlow(level, pos);
        if (flow.lengthSqr() == 0 && level.getFluidState(pos.above()).getType().isSame(fluid.getType())) {
            // an open falling column has no sideways gradient but is clearly moving down
            flow = new Vec3(0, -1, 0);
        }
        cir.setReturnValue(flow);
    }

    @Inject(method = "determineAndApplyFlowScore", at = @At("TAIL"), remap = false)
    private void ff$scaleSpeedWithFlow(final CallbackInfo ci) {
        if (level == null || level.isClientSide() || !FlowingFluids.config.enableMod) {
            return;
        }
        float max = FlowingFluids.config.create_waterWheelMaxSpeedMultiplier;
        float target = 1.0F;
        if (max > 1.0F && flowScore != 0 && FlowingFluids.config.create_waterWheelMode.usesFlow()
                && !FlowingFluids.config.dontTickAtLocation(worldPosition, level)) {
            long now = level.getGameTime();
            double levelsPerSecond = 0.0D;
            for (BlockPos offset : getOffsetsToCheck()) {
                BlockPos checkPos = offset.offset(worldPosition);
                if (!level.getFluidState(checkPos).isEmpty()) {
                    levelsPerSecond += FluidFlowActivityTracker.watch(level, checkPos).levelsPerSecond(now);
                }
            }
            double ratio = levelsPerSecond / (FF$REFERENCE_LEVELS_PER_SECOND * Math.max(1, getSize()));
            // sqrt keeps a flood from being wildly stronger than a brisk stream; quarter steps avoid kinetic churn
            float raw = Mth.clamp((float) Math.sqrt(ratio), FF$MIN_SPEED_MULTIPLIER, Math.min(max, 4.0F));
            target = Math.round(raw * 4.0F) / 4.0F;
        }
        if (target != ff$speedMultiplier) {
            ff$speedMultiplier = target;
            updateGeneratedRotation();
        }
    }

    @ModifyReturnValue(method = "getGeneratedSpeed", at = @At("RETURN"), remap = false)
    private float ff$applyFlowSpeed(final float original) {
        return ff$speedMultiplier == 1.0F ? original : original * ff$speedMultiplier;
    }
}
