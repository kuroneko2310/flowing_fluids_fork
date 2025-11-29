package traben.flowing_fluids.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevel {

    @Inject(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V", at = @At("TAIL"))
    private void flowing_fluids$spawnRainWater(final LevelChunk chunk, final int randomTickSpeed, final CallbackInfo ci) {
        if (!FlowingFluids.config.enableMod || FlowingFluids.config.rainSurfaceSpawnChance <= 0) return;

        ServerLevel level = (ServerLevel) (Object) this;
        if (!level.isRaining()) return;
        ChunkPos chunkPos = chunk.getPos();
        BlockPos origin = level.getBlockRandomPos(chunkPos.getMinBlockX(), 0, chunkPos.getMinBlockZ(), 15);
        int attempts = Mth.clamp(FlowingFluids.config.rainSurfaceSpawnTries, 1, 16);
        int maxSpawnLevel = Mth.clamp(FlowingFluids.config.rainSurfaceSpawnMaxLevel, 1, 8);

        for (int attempt = 0; attempt < attempts; attempt++) {
            if (level.random.nextFloat() >= FlowingFluids.config.rainSurfaceSpawnChance) continue;

            BlockPos attemptOrigin = origin.offset(level.random.nextInt(3) - 1, 0, level.random.nextInt(3) - 1);
            BlockPos surfacePos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, attemptOrigin).below();
            BlockPos skyCheckPos = surfacePos.above();
            if (!level.canSeeSky(skyCheckPos)) continue;

            if (!level.getFluidState(surfacePos).isEmpty()) continue;
            BlockState targetState = level.getBlockState(surfacePos);
            FlowingFluid water = (FlowingFluid) Fluids.WATER;
            if (!targetState.isAir() && !targetState.canBeReplaced(water)) continue;

            BlockState groundState = level.getBlockState(surfacePos.below());
            if (groundState.isAir()) continue;

            int spawnAmount = Mth.clamp(FlowingFluids.config.rainSurfaceSpawnLevel, 1, maxSpawnLevel);
            if (FFFluidUtils.setFluidStateAtPosToNewAmount(level, surfacePos, water, spawnAmount)) {
                AdaptiveTickScheduler.markRainBorn(level, surfacePos);
                return;
            }
        }
    }
}
