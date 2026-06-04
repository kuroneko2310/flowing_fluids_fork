package traben.flowing_fluids.performance;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.material.Fluid;

public final class FluidFineTickDelay {
    public static final float MIN_BASE_DELAY = 0.25f;
    public static final float MAX_BASE_DELAY = 255.0f;
    public static final int MAX_SUBSTEPS_PER_SERVER_TICK = 4;

    private FluidFineTickDelay() {
    }

    public static float sanitizeBaseDelay(float delay) {
        if (!Float.isFinite(delay)) {
            return 1.0f;
        }
        return Mth.clamp(delay, MIN_BASE_DELAY, MAX_BASE_DELAY);
    }

    public static float addExtraDelay(float baseDelay, int extraDelay) {
        return sanitizeBaseDelay(baseDelay) + Math.max(0, extraDelay);
    }

    public static int toScheduledTickDelay(LevelReader level, Fluid fluid, float adjustedDelay) {
        float safeDelay = Math.max(MIN_BASE_DELAY, adjustedDelay);
        if (safeDelay <= 1.0f) {
            return 1;
        }

        int floorDelay = Mth.floor(safeDelay);
        float fractional = safeDelay - floorDelay;
        if (fractional <= 0.0001f) {
            return Mth.clamp(floorDelay, 1, 255);
        }
        long tick = level instanceof Level realLevel ? realLevel.getGameTime() : 0L;
        boolean useCeiling = fractionalPhase(tick, 0L, fluid) < fractional;
        return Mth.clamp(floorDelay + (useCeiling ? 1 : 0), 1, 255);
    }

    public static int getAdditionalSubsteps(Level level, BlockPos pos, Fluid fluid, float adjustedDelay) {
        if (level == null || pos == null || fluid == null || adjustedDelay >= 1.0f) {
            return 0;
        }

        float safeDelay = Math.max(MIN_BASE_DELAY, adjustedDelay);
        float targetSubsteps = 1.0f / safeDelay;
        int guaranteedSubsteps = Mth.floor(targetSubsteps);
        float fractional = targetSubsteps - guaranteedSubsteps;
        int totalSubsteps = guaranteedSubsteps;
        if (fractional > 0.0001f && fractionalPhase(level.getGameTime(), pos.asLong(), fluid) < fractional) {
            totalSubsteps++;
        }
        totalSubsteps = Mth.clamp(totalSubsteps, 1, MAX_SUBSTEPS_PER_SERVER_TICK);
        return totalSubsteps - 1;
    }

    public static String describeBaseDelay(float delay) {
        return String.format("%.2f", sanitizeBaseDelay(delay));
    }

    public static String describeEffectiveDelay(float adjustedDelay) {
        float safeDelay = Math.max(MIN_BASE_DELAY, adjustedDelay);
        if (safeDelay >= 1.0f) {
            return String.format("%.2f ticks average", safeDelay);
        }
        return String.format("%.2f ticks average, up to %d substeps/server tick",
                safeDelay,
                Mth.clamp(Mth.ceil(1.0f / safeDelay), 1, MAX_SUBSTEPS_PER_SERVER_TICK));
    }

    private static float fractionalPhase(long gameTime, long posKey, Fluid fluid) {
        ResourceLocation fluidId = fluid == null ? null : BuiltInRegistries.FLUID.getKey(fluid);
        long fluidHash = fluidId == null ? 0L : fluidId.hashCode();
        long mixed = gameTime * 0x9E3779B97F4A7C15L
                ^ Long.rotateLeft(posKey, 17)
                ^ fluidHash * 0xC2B2AE3D27D4EB4FL;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= mixed >>> 33;
        return ((mixed >>> 11) & ((1L << 53) - 1)) * 0x1.0p-53f;
    }
}
