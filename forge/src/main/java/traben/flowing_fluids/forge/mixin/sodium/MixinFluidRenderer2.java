package traben.flowing_fluids.forge.mixin.sodium;

#if MC == MC_20_1

import me.jellysquid.mods.sodium.client.model.color.ColorProvider;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.config.FFConfig;

import java.util.Arrays;

@Pseudo
@Mixin(FluidRenderer.class)
public abstract class MixinFluidRenderer2 {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;getFlow(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;",
                    remap = true),
            remap = false,
            require = 0
    )
    private Vec3 ff$alterFlowDir(final FluidState fluidState, final BlockGetter level, final BlockPos pos) {
        if (FlowingFluids.config.enableMod
                && FlowingFluids.config.hideFlowingTexture) {
            return Vec3.ZERO;
        }
        return fluidState.getFlow(level, pos);
    }

    @Unique
    private static final ColorProvider<FluidState> ff$waterLevelColours = (worldSlice, blockPos, fluidState, modelQuadView, ints)
            -> Arrays.fill(ints, FFConfig.waterLevelColours[Math.max(0, Math.min(FFConfig.waterLevelColours.length - 1, fluidState.getAmount() - 1))]);


    @ModifyVariable(method = "render", at = @At("HEAD"),
            ordinal = 2,
            remap = false,
            require = 0)
    private ColorProvider<FluidState> ff$alterColor(final ColorProvider<FluidState> value) {
        if (FlowingFluids.config.enableMod && FlowingFluids.config.debugWaterLevelColours) {
            return ff$waterLevelColours;
        }
        return value;
    }
}
#else

import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.config.FFConfig;

import java.util.Arrays;

@Pseudo
@Mixin(DefaultFluidRenderer.class)
public abstract class MixinFluidRenderer2 {

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;getFlow(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;", remap = true),
            remap = false,
            require = 0)
    private Vec3 ff$alterFlowDir(final FluidState fluidState, final BlockGetter level, final BlockPos pos) {
        if (FlowingFluids.config.enableMod
                && FlowingFluids.config.hideFlowingTexture) {
            return Vec3.ZERO;
        }
        return fluidState.getFlow(level, pos);
    }

    @Unique
    private static final ColorProvider<FluidState> ff$waterLevelColours = (worldSlice, blockPos, mut, fluidState, modelQuadView, ints)
            -> Arrays.fill(ints, FFConfig.waterLevelColours[Math.max(0, Math.min(FFConfig.waterLevelColours.length - 1, fluidState.getAmount() - 1))]);


    @ModifyVariable(method = "render", at = @At("HEAD"), ordinal = 0, argsOnly = true,
            remap = false,
            require = 0)
    private ColorProvider<FluidState> ff$alterColor(final ColorProvider<FluidState> value) {
        if (FlowingFluids.config.enableMod && FlowingFluids.config.debugWaterLevelColours) {
            return ff$waterLevelColours;
        }
        return value;
    }
}

#endif
