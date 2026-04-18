package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.FFFluidUtils;

final class SpringFluidEmitter {
    private SpringFluidEmitter() {
    }

    static boolean canEmitInto(ServerLevel level, BlockState outputState, FluidState outputFluid, FlowingFluid sourceFluid) {
        if (outputFluid.getType().isSame(Fluids.WATER)) {
            return sourceFluid.isSame(Fluids.WATER);
        }
        if (outputFluid.getType().isSame(sourceFluid)) {
            return true;
        }
        if (!outputFluid.isEmpty()) {
            return false;
        }
        return outputState.isAir()
                || outputState.canBeReplaced(sourceFluid)
                || (sourceFluid.isSame(Fluids.WATER) && FFFluidUtils.supportsVirtualFluidState(level, outputState));
    }

    static int emitFluid(ServerLevel level, BlockPos outputPos, int emitted, FlowingFluid sourceFluid, Direction growthDirection) {
        BlockState outputState = level.getBlockState(outputPos);
        FluidState outputFluid = FFFluidUtils.getEffectiveFluidState(level, outputPos, outputState);
        // Upward springs should keep pushing through a filled shaft so the water head can
        // rise above the current top cell instead of stalling at the first full block.
        boolean allowUpwardSpread = growthDirection == Direction.UP
                && (sourceFluid.isSame(Fluids.LAVA) || sourceFluid.isSame(Fluids.WATER));
        boolean allowDownwardSpread = growthDirection != Direction.UP;

        // Springs have to be able to seed the first fluid tile into empty cave air.
        // The connected-placement helper is great once fluid already exists, but it
        // returns "nothing placed" for completely dry cells because it starts from an
        // existing node. Seed a local amount first, then fall through to the connected
        // helper once the spring has established a live fluid cell.
        if (outputFluid.isEmpty()) {
            int remainder = FFFluidUtils.addAmountToFluidAtPosWithRemainder(level, outputPos, sourceFluid, emitted);
            if (remainder <= 0) {
                return 0;
            }

            outputState = level.getBlockState(outputPos);
            outputFluid = FFFluidUtils.getEffectiveFluidState(level, outputPos, outputState);
            if (outputFluid.isEmpty() || !outputFluid.getType().isSame(sourceFluid)) {
                return remainder;
            }

            return FFFluidUtils.addAmountToFluidAtPosWithRemainderAndTrySpreadIfFull(
                    level,
                    outputPos,
                    sourceFluid,
                    remainder,
                    allowUpwardSpread,
                    allowDownwardSpread
            );
        }

        if (!outputFluid.getType().isSame(sourceFluid)) {
            return emitted;
        }

        return FFFluidUtils.addAmountToFluidAtPosWithRemainderAndTrySpreadIfFull(
                level,
                outputPos,
                sourceFluid,
                emitted,
                allowUpwardSpread,
                allowDownwardSpread
        );
    }
}
