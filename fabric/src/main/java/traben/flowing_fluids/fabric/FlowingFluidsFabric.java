package traben.flowing_fluids.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
#if MC > MC_20_1
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
#endif
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.PlugWaterFeature;
import traben.flowing_fluids.config.FFCommands;
import traben.flowing_fluids.rain.RainWaterSystem;
import traben.flowing_fluids.ParallelFluidTickManager;

public final class FlowingFluidsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        // Run our common setup.
        CommandRegistrationCallback.EVENT.register(FFCommands::registerCommands);
        #if MC > MC_20_1
        PayloadTypeRegistry.playS2C().register(FFConfigDataFabric.type, FFConfigDataFabric.CODEC);
        #endif
        FlowingFluids.init();
        WaterPressureFabricEvents.register();

        #if MC > MC_20_1
        ServerTickEvents.END_WORLD_TICK.register(RainWaterSystem::onLevelTick);
        ServerWorldEvents.UNLOAD.register((server, world) -> RainWaterSystem.onLevelUnload(world));

        // OPTIMIZATION: Clean up all static caches and thread pools on server stop
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            FlowingFluids.info("Server stopping - cleaning up fluid system caches...");
            ParallelFluidTickManager.shutdown();
            traben.flowing_fluids.EnhancedFluidBFS.shutdown();
            traben.flowing_fluids.AdaptiveTickScheduler.clearAll();
            traben.flowing_fluids.FluidSpatialGrid.clearAll();
            traben.flowing_fluids.ChunkLocalSlopeCache.clearAll();
            traben.flowing_fluids.FluidTickBuffer.clearBuffer();
        });
        #endif

    }
}
