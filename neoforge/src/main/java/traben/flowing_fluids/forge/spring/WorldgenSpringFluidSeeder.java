package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;

final class WorldgenSpringFluidSeeder {
    private WorldgenSpringFluidSeeder() {
    }

    static void seedLinearSpring(WorldGenLevel level, BlockPos springPos, Direction growthDirection,
                                 FlowingFluid fluid, int maxLength) {
        seedLinearSpring(level, springPos, growthDirection, fluid, maxLength, true);
    }

    static void seedLinearSpringInExistingCavity(WorldGenLevel level, BlockPos springPos, Direction growthDirection,
                                                FlowingFluid fluid, int maxLength) {
        seedLinearSpring(level, springPos, growthDirection, fluid, maxLength, false);
    }

    private static void seedLinearSpring(WorldGenLevel level, BlockPos springPos, Direction growthDirection,
                                        FlowingFluid fluid, int maxLength, boolean carveClosedCavities) {
        if (maxLength <= 0) {
            return;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int offset = 1; offset <= maxLength; offset++) {
            cursor.set(springPos).move(growthDirection, offset);
            BlockState outputState = level.getBlockState(cursor);
            if (!FFFluidUtils.canStorePartialFluidAmount(level, cursor, outputState, fluid)) {
                boolean filled = carveClosedCavities
                        ? SpringCavityCarver.carveFluidCell(level, cursor, fluid)
                        : SpringCavityCarver.fillExistingCavityFluidCell(level, cursor, fluid);
                if (!filled) {
                    break;
                }
                continue;
            }

            FFFluidUtils.setFluidStateAtPosToNewAmount(level, cursor, fluid, 8);
            AdaptiveTickScheduler.scheduleFluidTick(level, cursor, fluid, fluid.getTickDelay(level));
        }
    }
}
