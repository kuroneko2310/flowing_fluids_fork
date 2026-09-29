package traben.flowing_fluids;

import traben.flowing_fluids.forge.FlowingFluidsPlatformImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;

import java.nio.file.Path;

public class FlowingFluidsPlatform {
    public static Path getConfigDirectory() {
        return FlowingFluidsPlatformImpl.getConfigDirectory();
    }


    public static void sendConfigToClient(ServerPlayer player) {
        FlowingFluidsPlatformImpl.sendConfigToClient(player);
    }

    public static boolean isThisModLoaded(String modId) {
        return FlowingFluidsPlatformImpl.isThisModLoaded(modId);
    }

    public static void clearPlatformRuntime(ServerLevel level) {
        FlowingFluidsPlatformImpl.clearPlatformRuntime(level);
    }

    public static void syncVirtualFluidState(ServerLevel level, BlockPos pos) {
        FlowingFluidsPlatformImpl.syncVirtualFluidState(level, pos);
    }

    public static boolean hasProcessingFlowAnchorInRange(LevelAccessor level, BlockPos pos) {
        return FlowingFluidsPlatformImpl.hasProcessingFlowAnchorInRange(level, pos);
    }

    public static boolean hasVisualFlowAnchorInRange(LevelAccessor level, BlockPos pos) {
        return FlowingFluidsPlatformImpl.hasVisualFlowAnchorInRange(level, pos);
    }

    public static boolean tryAbsorbRainWater(ServerLevel level, BlockPos pos, int amount) {
        return FlowingFluidsPlatformImpl.tryAbsorbRainWater(level, pos, amount);
    }
}
