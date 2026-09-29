package traben.flowing_fluids.forge;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.water.WaterPressureSystem;

@net.neoforged.fml.common.EventBusSubscriber(modid = FlowingFluids.MOD_ID, bus = net.neoforged.fml.common.EventBusSubscriber.Bus.GAME)
public final class WaterPressureForgeEvents {
    private WaterPressureForgeEvents() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            WaterPressureSystem.handleLevelTick(level);
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!isEnabled()) {
            return;
        }
        WaterPressureSystem.handleNeighborUpdate(event.getLevel(), event.getPos());
        for (Direction direction : event.getNotifiedSides()) {
            WaterPressureSystem.handleNeighborUpdate(event.getLevel(), event.getPos().relative(direction));
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onFluidPlaced(BlockEvent.FluidPlaceBlockEvent event) {
        if (!isEnabled()) {
            return;
        }
        WaterPressureSystem.handleNeighborUpdate(event.getLevel(), event.getPos());
        WaterPressureSystem.handleNeighborUpdate(event.getLevel(), event.getLiquidPos());
    }

    private static boolean isEnabled() {
        return FlowingFluids.config != null
            && FlowingFluids.config.enableMod
            && FlowingFluids.config.enableWaterPressure;
    }
}
