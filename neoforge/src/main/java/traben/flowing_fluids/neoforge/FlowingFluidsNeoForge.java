package traben.flowing_fluids.neoforge;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.FlowingFluidsTick;
import traben.flowing_fluids.ParallelFluidTickManager;
import traben.flowing_fluids.config.FFCommands;
import traben.flowing_fluids.config.FFConfigData;
import traben.flowing_fluids.rain.RainWaterSystem;

@Mod(FlowingFluids.MOD_ID)
public final class FlowingFluidsNeoForge {
    public FlowingFluidsNeoForge() {
        // Run our common setup.
        FlowingFluids.init();
        NeoForge.EVENT_BUS.register(FlowingFluidsNeoForge.class);

    }

    @SubscribeEvent
    public static void onRegisterCommandEvent(RegisterCommandsEvent event) {
        FlowingFluids.info("commands registered");
        FFCommands.registerCommands(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent event) {
        if (event.phase == LevelTickEvent.Phase.END && event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            RainWaterSystem.onLevelTick(level);
            FlowingFluidsTick.onLevelTick(level);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            RainWaterSystem.onLevelUnload(level);
            FlowingFluidsTick.onLevelUnload(level);
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            FlowingFluidsTick.onChunkLoad(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            FlowingFluidsTick.onChunkUnload(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        FlowingFluids.info("Server stopping - cleaning up fluid system caches...");
        ParallelFluidTickManager.shutdown();
        traben.flowing_fluids.ParallelFluidEqualizer.shutdown();
        traben.flowing_fluids.EnhancedFluidBFS.shutdown();
        traben.flowing_fluids.AdaptiveTickScheduler.clearAll();
        traben.flowing_fluids.FluidSpatialGrid.clearAll();
        traben.flowing_fluids.ChunkLocalSlopeCache.clearAll();
        traben.flowing_fluids.FluidTickBuffer.clearBuffer();
        traben.flowing_fluids.FluidActivityTracker.clearAll();
    }
}

@EventBusSubscriber(modid = "flowing_fluids", bus = EventBusSubscriber.Bus.MOD)
class ModRegister {
    @SubscribeEvent
    public static void onPayloadRegister(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("flowing_fluids");
        registrar.playToClient(FFConfigData.type, FFConfigDataNeoForge.CODEC, (data, b) -> {
            try {
                if (data.isValid()) {
                    FlowingFluids.config = data.delegate;
                    FlowingFluids.applyConfigRuntime();

                    FlowingFluids.info("- Server Config data received and synced");
                } else {
                    FlowingFluids.error("- Server Config data received and failed to sync, invalid data");
                    throw new RuntimeException("[Flowing Fluids] - Server Config data received and failed to sync, invalid data");
                }
            } catch (Exception e) {
                FlowingFluids.error("- Server Config data received and failed to sync, exception");
                throw new RuntimeException("[Flowing Fluids] - Server Config data received and failed to sync, exception", e);
            }
        });
    }
}
