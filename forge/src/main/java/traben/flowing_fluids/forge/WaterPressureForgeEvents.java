package traben.flowing_fluids.forge;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import traben.flowing_fluids.FlowingFluids;

@Mod.EventBusSubscriber(modid = FlowingFluids.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WaterPressureForgeEvents {
    private WaterPressureForgeEvents() {
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        // Retired: avoid permanent per-tick barrier scans on the server thread.
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        // Retired: avoid waking a secondary pressure system on every neighbor update.
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onFluidPlaced(BlockEvent.FluidPlaceBlockEvent event) {
        // Retired: avoid waking a secondary pressure system on every fluid placement.
    }
}
