package traben.flowing_fluids;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;

import java.nio.file.Path;

public class FlowingFluidsPlatform {
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
}
