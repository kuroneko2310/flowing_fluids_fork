package traben.flowing_fluids.rain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.api.FlowingFluidsAPI;

/**
 * Default bridge to the Flowing Fluids API.
 */
public class RainWaterApiImpl implements RainWaterApi {

    private final FlowingFluidsAPI api = FlowingFluidsAPI.getInstance(FlowingFluids.MOD_ID);

    @Override
    public void addRainWater(ServerLevel level, BlockPos pos, int amount) {
        if (amount <= 0) {
            return;
        }
        if (!RainWaterSystem.shouldExecuteQueuedRainPlacement(level, pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        FluidState existingFluid = FFFluidUtils.getEffectiveFluidState(level, pos, state);
        if (!existingFluid.isEmpty()) {
            if (existingFluid.getType().isSame(Fluids.WATER)) {
                api.placeFluidAmountFromPos(level, pos, Fluids.WATER, amount, false, true);
            }
            return;
        }

        if (!state.isAir()
                && !state.canBeReplaced(Fluids.WATER)
                && !FFFluidUtils.supportsVirtualFluidState(level, state)) {
            return;
        }

        int firstCellAmount = Math.min(8, amount);
        if (!FFFluidUtils.setFluidStateAtPosToNewAmount(level, pos, Fluids.WATER, firstCellAmount)) {
            return;
        }
        int remaining = amount - firstCellAmount;
        if (remaining > 0) {
            api.placeFluidAmountFromPos(level, pos, Fluids.WATER, remaining, false, true);
        }
    }
}

