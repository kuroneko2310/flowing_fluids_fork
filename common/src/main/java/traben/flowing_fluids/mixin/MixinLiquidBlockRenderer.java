package traben.flowing_fluids.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.config.FFConfig;

@Mixin(value = LiquidBlockRenderer.class, priority = 1001)
public abstract class MixinLiquidBlockRenderer {

    private static FluidState ff$resolveRenderFluidState(BlockAndTintGetter getter, BlockPos pos, BlockState state) {
        FluidState fallback = state.getFluidState();
        if (getter == null
                || pos == null
                || FlowingFluids.config == null
                || !FlowingFluids.config.enableMod
                || !FlowingFluids.config.enableExtendedWaterlogging) {
            return fallback;
        }
        return getter.getFluidState(pos);
    }

    @Redirect(
            method = "tesselate",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getFluidState()Lnet/minecraft/world/level/material/FluidState;",
                    ordinal = 0
            )
    )
    private FluidState ff$useDownRenderFluidState(BlockState state,
                                                  BlockAndTintGetter getter,
                                                  BlockPos pos,
                                                  VertexConsumer vertexConsumer,
                                                  BlockState blockState,
                                                  FluidState fluidState) {
        return ff$resolveRenderFluidState(getter, pos.below(), state);
    }

    @Redirect(
            method = "tesselate",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getFluidState()Lnet/minecraft/world/level/material/FluidState;",
                    ordinal = 1
            )
    )
    private FluidState ff$useUpRenderFluidState(BlockState state,
                                                BlockAndTintGetter getter,
                                                BlockPos pos,
                                                VertexConsumer vertexConsumer,
                                                BlockState blockState,
                                                FluidState fluidState) {
        return ff$resolveRenderFluidState(getter, pos.above(), state);
    }

    @Redirect(
            method = "tesselate",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getFluidState()Lnet/minecraft/world/level/material/FluidState;",
                    ordinal = 2
            )
    )
    private FluidState ff$useNorthRenderFluidState(BlockState state,
                                                   BlockAndTintGetter getter,
                                                   BlockPos pos,
                                                   VertexConsumer vertexConsumer,
                                                   BlockState blockState,
                                                   FluidState fluidState) {
        return ff$resolveRenderFluidState(getter, pos.north(), state);
    }

    @Redirect(
            method = "tesselate",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getFluidState()Lnet/minecraft/world/level/material/FluidState;",
                    ordinal = 3
            )
    )
    private FluidState ff$useSouthRenderFluidState(BlockState state,
                                                   BlockAndTintGetter getter,
                                                   BlockPos pos,
                                                   VertexConsumer vertexConsumer,
                                                   BlockState blockState,
                                                   FluidState fluidState) {
        return ff$resolveRenderFluidState(getter, pos.south(), state);
    }

    @Redirect(
            method = "tesselate",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getFluidState()Lnet/minecraft/world/level/material/FluidState;",
                    ordinal = 4
            )
    )
    private FluidState ff$useWestRenderFluidState(BlockState state,
                                                  BlockAndTintGetter getter,
                                                  BlockPos pos,
                                                  VertexConsumer vertexConsumer,
                                                  BlockState blockState,
                                                  FluidState fluidState) {
        return ff$resolveRenderFluidState(getter, pos.west(), state);
    }

    @Redirect(
            method = "tesselate",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getFluidState()Lnet/minecraft/world/level/material/FluidState;",
                    ordinal = 5
            )
    )
    private FluidState ff$useEastRenderFluidState(BlockState state,
                                                  BlockAndTintGetter getter,
                                                  BlockPos pos,
                                                  VertexConsumer vertexConsumer,
                                                  BlockState blockState,
                                                  FluidState fluidState) {
        return ff$resolveRenderFluidState(getter, pos.east(), state);
    }

    @Redirect(
            method = "getHeight(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;)F",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getFluidState()Lnet/minecraft/world/level/material/FluidState;"
            )
    )
    private FluidState ff$useHeightRenderFluidState(BlockState state,
                                                    BlockAndTintGetter getter,
                                                    Fluid fluid,
                                                    BlockPos pos) {
        return ff$resolveRenderFluidState(getter, pos, state);
    }

    @Redirect(
            method = "getHeight(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)F",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getFluidState()Lnet/minecraft/world/level/material/FluidState;"
            )
    )
    private FluidState ff$useHeightAboveRenderFluidState(BlockState state,
                                                         BlockAndTintGetter getter,
                                                         Fluid fluid,
                                                         BlockPos pos,
                                                         BlockState blockState,
                                                         FluidState fluidState) {
        return ff$resolveRenderFluidState(getter, pos.above(), state);
    }

    @ModifyExpressionValue(
            method = "tesselate",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;getFlow(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private Vec3 ff$alterFlowDir(final Vec3 value) {
        if (FlowingFluids.config.enableMod
                && FlowingFluids.config.hideFlowingTexture) {
            return Vec3.ZERO;
        }
        return value;
    }

// stopped working in 1.21.3, absolutely no idea why
//    @ModifyVariable(
//            method = "tesselate",
//            at = @At(value = "STORE"),
//            ordinal = 0
//    )
//    private int ff$alterColor(final int value, @Local(argsOnly = true) FluidState fluidState) {
//        if (FlowingFluids.config.enableMod && FlowingFluids.config.debugWaterLevelColours) {
//            return FFConfig.waterLevelColours[fluidState.getAmount()-1];
//        }
//        return value;
//    }

    @ModifyVariable(method = "tesselate", at = @At(value = "STORE"), ordinal = 0)
    private float ff$f(final float value, @Local(argsOnly = true) FluidState fluidState) {
        if (FlowingFluids.config.enableMod && FlowingFluids.config.debugWaterLevelColours) {
            return (FFConfig.waterLevelColours[fluidState.getAmount()-1] >> 16 & 255) / 255.0F;
        }
        return value;
    }
    @ModifyVariable(method = "tesselate", at = @At(value = "STORE"), ordinal = 1)
    private float ff$g(final float value, @Local(argsOnly = true) FluidState fluidState) {
        if (FlowingFluids.config.enableMod && FlowingFluids.config.debugWaterLevelColours) {
            return (FFConfig.waterLevelColours[fluidState.getAmount()-1] >> 8 & 255) / 255.0F;
        }
        return value;
    }
    @ModifyVariable(method = "tesselate", at = @At(value = "STORE"), ordinal = 2)
    private float ff$h(final float value, @Local(argsOnly = true) FluidState fluidState) {
        if (FlowingFluids.config.enableMod && FlowingFluids.config.debugWaterLevelColours) {
            return (FFConfig.waterLevelColours[fluidState.getAmount()-1] & 255) / 255.0F;
        }
        return value;
    }

}
