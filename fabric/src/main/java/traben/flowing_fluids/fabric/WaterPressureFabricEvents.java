package traben.flowing_fluids.fabric;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import traben.flowing_fluids.water.WaterPressureSystem;

public final class WaterPressureFabricEvents {
    private WaterPressureFabricEvents() {
    }

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(WaterPressureFabricEvents::handleWorldTick);
    }

    private static void handleWorldTick(ServerLevel level) {
        WaterPressureSystem.handleLevelTick(level);
    }
}
