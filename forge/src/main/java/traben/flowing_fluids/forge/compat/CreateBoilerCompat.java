package traben.flowing_fluids.forge.compat;

#if MC!=MC_20_1

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class CreateBoilerCompat {
    private CreateBoilerCompat() {
    }

    public static float claimBoiledWaterPerTick(final Level level, final BlockPos tankPos, final int claimIntervalTicks) {
        return 0.0F;
    }
}
#else

import com.simibubi.create.content.fluids.tank.BoilerData;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Reads how much water a Create steam boiler is boiling away. Only touched when Create is loaded.
 */
public final class CreateBoilerCompat {
    /** Create's boiler needs this much water per tick for each heat level it runs at. */
    private static final int WATER_PER_HEAT_LEVEL_PER_TICK = 10;
    private static final Map<FluidTankBlockEntity, Long> LAST_CLAIM = new WeakHashMap<>();

    private CreateBoilerCompat() {
    }

    /**
     * Water (mB per tick) the boiler that contains {@code tankPos} currently boils. Several condensers can sit on one
     * boiler; only the first to ask within {@code claimIntervalTicks} gets the boiler's steam, so stacking condensers
     * never recovers more than the boiler used.
     */
    public static synchronized float claimBoiledWaterPerTick(final Level level, final BlockPos tankPos, final int claimIntervalTicks) {
        BlockEntity blockEntity = level.getBlockEntity(tankPos);
        if (!(blockEntity instanceof FluidTankBlockEntity tank)) {
            return 0.0F;
        }
        FluidTankBlockEntity controller = tank.getControllerBE();
        if (controller == null || controller.boiler == null || !controller.boiler.isActive()) {
            return 0.0F;
        }
        long now = level.getGameTime();
        Long last = LAST_CLAIM.get(controller);
        if (last != null && now - last < claimIntervalTicks && now >= last) {
            return 0.0F;
        }
        LAST_CLAIM.put(controller, now);
        return boiledWaterPerTick(controller.boiler, controller.getTotalTankSize());
    }

    public static float boiledWaterPerTick(final BoilerData boiler, final int boilerSize) {
        int heat = Math.min(boiler.activeHeat,
                Math.min(boiler.getMaxHeatLevelForWaterSupply(), boiler.getMaxHeatLevelForBoilerSize(boilerSize)));
        if (heat <= 0 && boiler.isPassive(boilerSize)) {
            heat = 1;
        }
        if (heat <= 0) {
            return 0.0F;
        }
        return Math.min(boiler.waterSupply, heat * WATER_PER_HEAT_LEVEL_PER_TICK);
    }
}
#endif
