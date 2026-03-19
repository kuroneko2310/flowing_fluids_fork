package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public record FFDownwardFlowTarget(
        BlockPos targetPos,
        BlockState targetState,
        FluidState targetFluidState,
        BlockPos conduitPos,
        BlockState conduitState,
        boolean skippedPassThrough
) {
}
