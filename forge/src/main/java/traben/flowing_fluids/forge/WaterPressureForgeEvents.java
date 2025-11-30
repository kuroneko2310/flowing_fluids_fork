package traben.flowing_fluids.forge;

import net.minecraft.core.BlockPos;
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
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }
        WaterPressureSystem.handleLevelTick(level);
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        WaterPressureSystem.handleNeighborUpdate(level, pos);
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onFluidPlaced(BlockEvent.FluidPlaceBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        WaterPressureSystem.handleNeighborUpdate(level, pos);
    }
}
