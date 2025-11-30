package traben.flowing_fluids.rain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.api.FlowingFluidsAPI;

/**
 * Default bridge to the Flowing Fluids API.
 */
public class RainWaterApiImpl implements RainWaterApi {

    private final FlowingFluidsAPI api = FlowingFluidsAPI.getInstance(FlowingFluids.MOD_ID);

    @Override
    public void addRainWater(ServerLevel level, BlockPos pos, int amount) {
        api.placeFluidAmountFromPos(level, pos, Fluids.WATER, amount, false, true);
    }
}

