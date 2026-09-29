package traben.flowing_fluids.forge.compat;

import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

/**
 * Encased fan bulk washing: every finished wash drinks, with a configurable chance, one level from the first water the
 * air current passes through, so a washing line needs a real water supply instead of one eternal source block.
 */
public final class CreateFanWashingCompat {

    private static final ThreadLocal<AirCurrent> CURRENT = new ThreadLocal<>();

    private CreateFanWashingCompat() {
    }

    public static void enter(final AirCurrent current) {
        CURRENT.set(current);
    }

    public static void exit() {
        CURRENT.remove();
    }

    public static void onProcessed(final FanProcessingType type) {
        AirCurrent current = CURRENT.get();
        if (current == null
                || !(type instanceof AllFanProcessingTypes.SplashingType)
                || !FlowingFluids.config.enableMod
                || !FlowingFluids.config.isWaterAllowed()
                || current.direction == null) {
            return;
        }
        float chance = FlowingFluids.config.create_fanWashingWaterUseChance;
        Level level = current.source.getAirCurrentWorld();
        if (chance <= 0 || level == null || level.isClientSide() || level.getRandom().nextFloat() >= chance) {
            return;
        }
        BlockPos start = current.source.getAirCurrentPos();
        int reach = Math.max(1, (int) Math.ceil(current.maxDistance));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= reach; i++) {
            cursor.setWithOffset(start, current.direction.getStepX() * i, current.direction.getStepY() * i, current.direction.getStepZ() * i);
            BlockState state = level.getBlockState(cursor);
            FluidState fluid = state.getFluidState();
            if (state.liquid() && fluid.getType().isSame(Fluids.WATER) && fluid.getAmount() > 0) {
                FFFluidUtils.setFluidStateAtPosToNewAmount(level, cursor.immutable(), Fluids.WATER, fluid.getAmount() - 1);
                return;
            }
        }
    }
}
