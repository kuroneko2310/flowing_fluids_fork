package traben.flowing_fluids.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

@Mixin(LiquidBlock.class)
public abstract class MixinFlowingFluidBlock extends Block implements BucketPickup {

    @Shadow
    @Final
    private FlowingFluid fluid;

    public MixinFlowingFluidBlock() {
        //noinspection DataFlowIssue
        super(null);
    }

    /**
     * Lava が水と反応して丸石 / 黒曜石 を生成するときに、
     * 対象位置のフルイドレベルを 1 消費する処理。
     *
     * LiquidBlock#shouldSpreadLiquid(Level, BlockPos, BlockState) の中の
     * FluidState#is(TagKey) 呼び出しに Wrap している。
     */
    @WrapOperation(
            method = "shouldSpreadLiquid",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z"
            )
    )
    private boolean ff$consumeLevelObsidianOrCobbleCreation(
            final FluidState fluidState,
            final TagKey<Fluid> tag,
            final Operation<Boolean> original,
            @Local(argsOnly = true) Level level,
            @Local(ordinal = 1) BlockPos blockPos
    ) {
        boolean result = original.call(fluidState, tag);
        if (result
                && FlowingFluids.config.enableMod
                && FlowingFluids.config.isFluidAllowed(this.fluid)) {

            FluidState stateAtPos = level.getFluidState(blockPos);
            FFFluidUtils.setFluidStateAtPosToNewAmount(
                    level,
                    blockPos,
                    stateAtPos.getType(),
                    stateAtPos.getAmount() - 1
            );
        }
        return result;
    }

    /**
     * 「ソースじゃないと黒曜石にならない」条件を拡張する処理。
     * minLavaLevelForObsidian 以上の amount があれば、黒曜石生成を許可する。
     *
     * LiquidBlock#shouldSpreadLiquid 内の FluidState#isSource() に Wrap。
     */
    @WrapOperation(
            method = "shouldSpreadLiquid",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;isSource()Z"
            )
    )
    private boolean ff$modifyObsidianCondition(
            final FluidState instance,
            final Operation<Boolean> original
    ) {
        boolean source = original.call(instance); // 他の Mixin も動かすために一度呼ぶ

        if (!source
                && FlowingFluids.config.enableMod
                && FlowingFluids.config.isFluidAllowed(this.fluid)
                && instance.getAmount() >= FlowingFluids.config.minLavaLevelForObsidian) {
            // ソースでなくても、一定レベル以上なら黒曜石判定にしちゃう
            return true;
        }

        return source;
    }
}
