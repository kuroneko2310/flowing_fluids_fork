package traben.flowing_fluids.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.config.FFCommands;
import traben.flowing_fluids.rain.RainWaterSystem;


@Mod(FlowingFluids.MOD_ID)
public final class FlowingFluidsForge {
    public FlowingFluidsForge() {
        // Run our common setup.
        ForgePacketHandler.init();
        FlowingFluids.init();
        MinecraftForge.EVENT_BUS.register(FlowingFluidsForge.class);
    }

    @SubscribeEvent
    public static void onRegisterCommandEvent(RegisterCommandsEvent event) {
        FlowingFluids.info("commands registered");
        FFCommands.registerCommands(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof net.minecraft.server.level.ServerLevel level) {
            RainWaterSystem.onLevelTick(level);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            RainWaterSystem.onLevelUnload(level);
        }
    }
}
