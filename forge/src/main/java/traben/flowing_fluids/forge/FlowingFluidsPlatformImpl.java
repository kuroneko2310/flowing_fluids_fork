package traben.flowing_fluids.forge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.forge.hydraulic.FlowAnchorRuntime;
import traben.flowing_fluids.forge.hydraulic.RainCollectorRuntime;
import traben.flowing_fluids.forge.nether.NetherLavaEventSystem;

import java.nio.file.Path;

public class FlowingFluidsPlatformImpl {
    public static Path getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static void sendConfigToClient(ServerPlayer player) {
        #if MC > MC_20_1
        ForgePacketHandler.INSTANCE.send(new ForgePacketHandler.FFConfigPacket(), PacketDistributor.PLAYER.with(player));
        #else
        ForgePacketHandler.INSTANCE.send(PacketDistributor.PLAYER.with(()-> player),new ForgePacketHandler.FFConfigPacket());
        #endif
        FlowingFluids.info("- Sending server config to [" + player.getName().getString() + "]");
    }

    public static boolean isThisModLoaded(final String modId) {
        try {
            ModList list = ModList.get();
            if (list == null) {
                LoadingModList list2 = FMLLoader.getLoadingModList();
                return list2 != null && checkInitialModList(list2, modId);
            }
            return list.isLoaded(modId);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkInitialModList(@NotNull LoadingModList list, String modId) {
        try {
            for (ModInfo mod : list.getMods()) {
                if (mod.getModId().equals(modId)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static void clearPlatformRuntime(ServerLevel level) {
        FlowAnchorRuntime.clearDimension(level);
        RainCollectorRuntime.clearDimension(level);
        NetherLavaEventSystem.clearDimension(level);
    }

    public static void syncVirtualFluidState(ServerLevel level, BlockPos pos) {
        ForgePacketHandler.sendVirtualFluidState(level, pos);
    }

    public static boolean hasProcessingFlowAnchorInRange(LevelAccessor level, BlockPos pos) {
        return FlowAnchorRuntime.hasProcessingAnchorNearby(level, pos);
    }

    public static boolean hasVisualFlowAnchorInRange(LevelAccessor level, BlockPos pos) {
        return FlowAnchorRuntime.hasVisualAnchorNearby(level, pos);
    }

    public static boolean tryAbsorbRainWater(ServerLevel level, BlockPos pos, int amount) {
        return RainCollectorRuntime.tryAbsorbRainWater(level, pos, amount);
    }
}
