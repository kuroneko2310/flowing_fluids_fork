package traben.flowing_fluids.mixin;


import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BucketPickup;
#if MC >= MC_20_2
import net.minecraft.world.level.block.FlowingFluidBlock;
#else
import net.minecraft.world.level.block.LiquidBlock;
#endif
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;


#if MC >= MC_20_2
@Mixin(FlowingFluidBlock.class)
#else
@Mixin(LiquidBlock.class)
#endif
public abstract class MixinFlowingFluidBlock extends Block implements BucketPickup {

    @Shadow
    @Final
    private FlowingFluid fluid;

    public MixinFlowingFluidBlock() {
        //noinspection DataFlowIssue
        super(null);
    }


    @ModifyExpressionValue(method = "shouldSpreadLiquid", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/Fluid;is(Lnet/minecraft/tags/TagKey;)Z"))
    // this is a real dodgey mixin target but any other way failed to grab the locals
    private boolean ff$consumeLevelObsidianOrCobbleCreation(final boolean original,
                                                            @Local(argsOnly = true) Level level,
                                                            @Local(ordinal = 1) BlockPos blockPos) {
        if (original && FlowingFluids.config.enableMod && FlowingFluids.config.isFluidAllowed(this.fluid)) {
            var state = level.getFluidState(blockPos);
            FFFluidUtils.setFluidStateAtPosToNewAmount(level, blockPos, state.getType(), state.getAmount() - 1);
        }
        return original;
    }

    @WrapOperation(method = "shouldSpreadLiquid", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;isSource()Z"))
    private boolean ff$modifyObsidianCondition(final FluidState instance, final Operation<Boolean> original) {
        boolean source = original.call(instance); // so any other mixin may run
        if (!source
                && FlowingFluids.config.enableMod
                && FlowingFluids.config.isFluidAllowed(this.fluid)
                && instance.getAmount() >= FlowingFluids.config.minLavaLevelForObsidian) {
            return true;
        }
        return source;
    }

}
