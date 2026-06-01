package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;

final class SpringCavityCarver {
    private SpringCavityCarver() {
    }

    static boolean canCarveForFluid(BlockState state, FlowingFluid fluid) {
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().getType().isSame(fluid);
        }
        return state.canBeReplaced(fluid) || !isProtectedWorldgenBlock(state);
    }

    static boolean canCarveWater(BlockState state) {
        return canCarveForFluid(state, (FlowingFluid) Fluids.WATER);
    }

    static boolean carveFluidCell(WorldGenLevel level, BlockPos pos, FlowingFluid fluid) {
        BlockState state = level.getBlockState(pos);
        if (!canCarveForFluid(state, fluid)) {
            return false;
        }
        level.setBlock(pos, fluid.defaultFluidState().createLegacyBlock(), 2);
        level.scheduleTick(pos, fluid, fluid.getTickDelay(level));
        return true;
    }

    static int carveFluidLine(WorldGenLevel level, BlockPos origin, Direction direction, FlowingFluid fluid, int length) {
        int carved = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int offset = 1; offset <= length; offset++) {
            cursor.set(origin).move(direction, offset);
            if (!carveFluidCell(level, cursor, fluid)) {
                break;
            }
            carved++;
        }
        return carved;
    }

    static int carveWaterBreathingRoom(WorldGenLevel level, BlockPos pos, int targetSides) {
        int open = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.set(pos).move(direction);
            if (carveFluidCell(level, cursor, (FlowingFluid) Fluids.WATER)) {
                open++;
                if (open >= targetSides) {
                    break;
                }
            }
        }
        return open;
    }

    private static boolean isProtectedWorldgenBlock(BlockState state) {
        return state.is(Blocks.BEDROCK)
                || state.is(Blocks.BARRIER)
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.END_PORTAL_FRAME)
                || state.is(Blocks.COMMAND_BLOCK)
                || state.is(Blocks.CHAIN_COMMAND_BLOCK)
                || state.is(Blocks.REPEATING_COMMAND_BLOCK)
                || state.is(Blocks.STRUCTURE_BLOCK)
                || state.is(Blocks.JIGSAW);
    }
}
