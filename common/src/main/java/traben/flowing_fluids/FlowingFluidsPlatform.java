package traben.flowing_fluids;

#if FF_NEOFORGE_STANDALONE
import traben.flowing_fluids.forge.FlowingFluidsPlatformImpl;
#else
import dev.architectury.injectables.annotations.ExpectPlatform;
#endif
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;

import java.nio.file.Path;

/**
 * Platform bridge. The Architectury (Forge 1.20.1) build swaps these bodies for the platform implementation through
 * {@code @ExpectPlatform}; the standalone NeoForge 1.21.1 build has no Architectury transformer, so it calls the
 * implementation directly.
 */
public class FlowingFluidsPlatform {
    #if FF_NEOFORGE_STANDALONE
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
    #else
    @ExpectPlatform
    public static Path getConfigDirectory() {
        return Path.of("");
    }


    @ExpectPlatform
    public static void sendConfigToClient(ServerPlayer player) {
    }

    @ExpectPlatform
    public static boolean isThisModLoaded(String modId) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void clearPlatformRuntime(ServerLevel level) {
    }

    @ExpectPlatform
    public static void syncVirtualFluidState(ServerLevel level, BlockPos pos) {
    }

    @ExpectPlatform
    public static boolean hasProcessingFlowAnchorInRange(LevelAccessor level, BlockPos pos) {
        return false;
    }

    @ExpectPlatform
    public static boolean hasVisualFlowAnchorInRange(LevelAccessor level, BlockPos pos) {
        return false;
    }

    @ExpectPlatform
    public static boolean tryAbsorbRainWater(ServerLevel level, BlockPos pos, int amount) {
        return false;
    }
    #endif
}
