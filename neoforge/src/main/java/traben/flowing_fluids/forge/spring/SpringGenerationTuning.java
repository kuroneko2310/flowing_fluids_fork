package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelAccessor;
import traben.flowing_fluids.FlowingFluids;

final class SpringGenerationTuning {
    private SpringGenerationTuning() {
    }

    static float dimensionMultiplier(LevelAccessor level, BlockPos pos) {
        if (SpringDimensionContext.isNether(level.getBiome(pos)) || SpringDimensionContext.isUltraWarm(level)) {
            return FlowingFluids.config.netherSpringSpawnMultiplier;
        }
        return FlowingFluids.config.overworldSpringSpawnMultiplier;
    }

    static int scaledAttempts(int baseAttempts, float multiplier) {
        if (baseAttempts <= 0 || multiplier <= 0.0F) {
            return 0;
        }
        return Math.max(1, Math.round(baseAttempts * multiplier));
    }

    static int scaledPlacements(int basePlacements, float multiplier, int min, int max) {
        if (basePlacements <= 0 || multiplier <= 0.0F) {
            return 0;
        }
        return Mth.clamp(Math.max(1, Math.round(basePlacements * multiplier)), min, max);
    }
}
