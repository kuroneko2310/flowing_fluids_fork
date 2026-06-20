package traben.flowing_fluids.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.FlowingFluidsTick;
import traben.flowing_fluids.ParallelFluidTickManager;
import traben.flowing_fluids.config.FFCommands;
import traben.flowing_fluids.drying.DryingEventSystem;
import traben.flowing_fluids.flood.FloodEventSystem;
import traben.flowing_fluids.performance.FluidAutoTickDelay;
import traben.flowing_fluids.performance.FluidPerformanceMonitor;
import traben.flowing_fluids.performance.InfiniteBiomeRefillFallbackController;
import traben.flowing_fluids.performance.InfiniteBiomeRefillSuppression;
import traben.flowing_fluids.forge.debug.ForgeDebugCommands;
import traben.flowing_fluids.forge.debug.ForgeDebugItemRegistry;
import traben.flowing_fluids.forge.hydraulic.ForgeHydraulicBlockRegistry;
import traben.flowing_fluids.forge.nether.ForgeNetherLavaCommands;
import traben.flowing_fluids.forge.nether.NetherLavaEventSystem;
import traben.flowing_fluids.forge.spring.ForgeSpringRegistry;
import traben.flowing_fluids.forge.spring.ForgeSpringCommands;
import traben.flowing_fluids.water.WaterPressureSystem;


@Mod(FlowingFluids.MOD_ID)
public final class FlowingFluidsForge {
    public FlowingFluidsForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        // Run our common setup.
        ForgePacketHandler.init();
        FlowingFluids.init();
        ForgeSpringRegistry.register(modBus);
        ForgeHydraulicBlockRegistry.register(modBus);
        ForgeDebugItemRegistry.register(modBus);
        MinecraftForge.EVENT_BUS.register(FlowingFluidsForge.class);
    }

    @SubscribeEvent
    public static void onRegisterCommandEvent(RegisterCommandsEvent event) {
        FlowingFluids.info("commands registered");
        ff$registerCommandGroup("common", () ->
            FFCommands.registerCommands(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection()));
        ff$registerCommandGroup("spring", () -> ForgeSpringCommands.registerCommands(event.getDispatcher()));
        ff$registerCommandGroup("nether lava", () -> ForgeNetherLavaCommands.registerCommands(event.getDispatcher()));
        ff$registerCommandGroup("debug", () -> ForgeDebugCommands.registerCommands(event.getDispatcher()));
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof net.minecraft.server.level.ServerLevel level) {
            NetherLavaEventSystem.onLevelTick(level);
            FlowingFluidsTick.onLevelTick(level);
            InfiniteBiomeRefillSuppression.onLevelTick(level);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            FluidPerformanceMonitor.getInstance().onServerTick(
                    event.getServer(),
                    FlowingFluids.config.enablePerformanceMonitoring,
                    FlowingFluids.config.performanceLogInterval);
            FluidAutoTickDelay.onServerTick(event.getServer());
            InfiniteBiomeRefillFallbackController.onServerTick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        FlowingFluids.autoAddDetectedInfiniteBiomes(event.getServer());
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            NetherLavaEventSystem.onLevelUnload(level);
            FlowingFluidsTick.onLevelUnload(level);
            InfiniteBiomeRefillSuppression.onLevelUnload(level);
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
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        ForgePacketHandler.sendVirtualFluidChunk(event.getPlayer(), event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public static void onChunkUnwatch(ChunkWatchEvent.UnWatch event) {
        ForgePacketHandler.clearVirtualFluidChunk(event.getPlayer(), event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public static void onMobSpawnPositionCheck(MobSpawnEvent.PositionCheck event) {
        Mob mob = event.getEntity();
        if (!ff$canUseShallowWaterSpawn(mob.getType(), event.getLevel(), mob.blockPosition(), event.getSpawnType())) {
            return;
        }
        if (!mob.checkSpawnRules(event.getLevel(), event.getSpawnType())) {
            return;
        }
        if (!event.getLevel().noCollision(mob)) {
            return;
        }

        event.setResult(Event.Result.ALLOW);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        FlowingFluids.info("Server stopping - cleaning up fluid system caches...");
        ParallelFluidTickManager.shutdown();
        traben.flowing_fluids.ParallelFluidEqualizer.shutdown();
        traben.flowing_fluids.AsyncSlopeSearchPlanner.shutdown();
        traben.flowing_fluids.AdaptiveTickScheduler.clearAll();
        traben.flowing_fluids.FluidSpatialGrid.clearAll();
        traben.flowing_fluids.ChunkLocalSlopeCache.clearAll();
        traben.flowing_fluids.FluidTickBuffer.clearBuffer();
        traben.flowing_fluids.FluidActivityTracker.clearAll();
        FluidAutoTickDelay.resetRuntime();
        InfiniteBiomeRefillFallbackController.resetRuntime();
        InfiniteBiomeRefillSuppression.clearAll();
        FloodEventSystem.clearAll();
        DryingEventSystem.clearAll();
        WaterPressureSystem.clearAll();
        NetherLavaEventSystem.clearAll();
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().is(ForgeHydraulicBlockRegistry.WATERWAY_LINER.get().asItem())) {
            ff$appendTooltip(event, "tooltip.flowing_fluids.waterway_liner.1", "tooltip.flowing_fluids.waterway_liner.2");
            return;
        }
        if (event.getItemStack().is(ForgeHydraulicBlockRegistry.PRESSURE_NOZZLE.get().asItem())) {
            ff$appendTooltip(event, "tooltip.flowing_fluids.pressure_nozzle.1", "tooltip.flowing_fluids.pressure_nozzle.2");
            return;
        }
        if (event.getItemStack().is(ForgeHydraulicBlockRegistry.WATER_LEVEL_SENSOR.get().asItem())) {
            ff$appendTooltip(event, "tooltip.flowing_fluids.water_level_sensor.1", "tooltip.flowing_fluids.water_level_sensor.2");
            return;
        }
        if (event.getItemStack().is(ForgeHydraulicBlockRegistry.RAIN_COLLECTOR.get().asItem())) {
            ff$appendTooltip(event, "tooltip.flowing_fluids.rain_collector.1", "tooltip.flowing_fluids.rain_collector.2");
        }
    }

    private static boolean ff$canUseShallowWaterSpawn(EntityType<?> entityType,
                                                      net.minecraft.world.level.ServerLevelAccessor level,
                                                      BlockPos pos,
                                                      MobSpawnType spawnType) {
        return ff$isSupportedShallowSpawnType(spawnType)
                && FFFluidUtils.canGroundMobSpawnInShallowWater(entityType, level, pos);
    }

    private static boolean ff$isSupportedShallowSpawnType(MobSpawnType spawnType) {
        return spawnType == MobSpawnType.NATURAL
                || spawnType == MobSpawnType.CHUNK_GENERATION
                || spawnType == MobSpawnType.PATROL
                || spawnType == MobSpawnType.REINFORCEMENT
                || spawnType == MobSpawnType.STRUCTURE;
    }

    private static void ff$registerCommandGroup(String label, Runnable registration) {
        try {
            registration.run();
        } catch (LinkageError | RuntimeException exception) {
            FlowingFluids.error("Failed to register Forge " + label
                    + " commands. World loading will continue without this command set.", exception);
        }
    }

    private static void ff$appendTooltip(ItemTooltipEvent event, String firstLineKey, String secondLineKey) {
        event.getToolTip().add(Component.translatable(firstLineKey).withStyle(ChatFormatting.GRAY));
        event.getToolTip().add(Component.translatable(secondLineKey).withStyle(ChatFormatting.DARK_GRAY));
    }
}
