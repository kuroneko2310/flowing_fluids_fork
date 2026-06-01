package traben.flowing_fluids.forge.compat;

#if MC!=MC_20_1

public final class CreateBasinExternalFluidCompat {

    private CreateBasinExternalFluidCompat() {
    }
}
#else

import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.fluid.FluidHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

public final class CreateBasinExternalFluidCompat {

    public static final int FLUID_LEVEL_MILLIBUCKETS = 1000 / 8;

    private CreateBasinExternalFluidCompat() {
    }

    public static ExternalBasinFluid getExternalFluid(final BasinBlockEntity basin) {
        if (basin == null || basin.getLevel() == null || FlowingFluids.config == null || !FlowingFluids.config.enableMod) {
            return ExternalBasinFluid.EMPTY;
        }

        final Level level = basin.getLevel();
        final BlockState state = basin.getBlockState();
        final FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, basin.getBlockPos(), state);
        if (fluidState.isEmpty()
                || fluidState.getAmount() <= 0
                || !FlowingFluids.config.isFluidAllowed(fluidState)) {
            return ExternalBasinFluid.EMPTY;
        }

        return new ExternalBasinFluid(
                FluidHelper.convertToStill(fluidState.getType()),
                fluidState.getAmount(),
                levelsToMilliBuckets(fluidState.getAmount())
        );
    }

    public static void writeExternalFluid(final BasinBlockEntity basin, final Fluid fluid, final int milliBuckets) {
        if (basin == null || basin.getLevel() == null || fluid == null || fluid == Fluids.EMPTY) {
            return;
        }

        final int levels = milliBucketsToLevels(milliBuckets);
        FFFluidUtils.setFluidStateAtPosToNewAmount(
                basin.getLevel(),
                basin.getBlockPos(),
                FluidHelper.convertToStill(fluid),
                levels
        );
    }

    public static int levelsToMilliBuckets(final int levels) {
        if (levels <= 0) {
            return 0;
        }
        if (levels >= 8) {
            return 1000;
        }
        return levels * FLUID_LEVEL_MILLIBUCKETS;
    }

    public static int milliBucketsToLevels(final int milliBuckets) {
        if (milliBuckets <= 0) {
            return 0;
        }
        if (milliBuckets >= 1000) {
            return 8;
        }
        return Math.max(0, Math.min(8, milliBuckets / FLUID_LEVEL_MILLIBUCKETS));
    }

    public record ExternalBasinFluid(Fluid fluid, int levels, int milliBuckets) {
        public static final ExternalBasinFluid EMPTY = new ExternalBasinFluid(Fluids.EMPTY, 0, 0);

        public boolean isPresent() {
            return fluid != null && fluid != Fluids.EMPTY && levels > 0 && milliBuckets > 0;
        }

        public FluidStack asStack() {
            if (!isPresent()) {
                return FluidStack.EMPTY;
            }
            return new FluidStack(fluid, milliBuckets);
        }
    }
}
#endif
