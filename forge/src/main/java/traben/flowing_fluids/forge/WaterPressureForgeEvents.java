package traben.flowing_fluids.forge;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.water.WaterPressureSystem;

@Mod.EventBusSubscriber(modid = FlowingFluids.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WaterPressureForgeEvents {
    private WaterPressureForgeEvents() {
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) {
            WaterPressureSystem.handleLevelTick(level);
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        WaterPressureSystem.handleNeighborUpdate(event.getLevel(), event.getPos());
        for (Direction direction : event.getNotifiedSides()) {
            WaterPressureSystem.handleNeighborUpdate(event.getLevel(), event.getPos().relative(direction));
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onFluidPlaced(BlockEvent.FluidPlaceBlockEvent event) {
        WaterPressureSystem.handleNeighborUpdate(event.getLevel(), event.getPos());
        WaterPressureSystem.handleNeighborUpdate(event.getLevel(), event.getLiquidPos());
    }
}
