package traben.flowing_fluids.rain;

import net.minecraft.util.Mth;
import traben.flowing_fluids.ChunkWorkSelector;

final class RainMath {

    private RainMath() {
    }

    static float decayWetness(float storedWetness, long elapsedTicks, int persistTicks) {
        if (persistTicks <= 0) {
            return 0.0f;
        }
        return Mth.clamp(storedWetness - (elapsedTicks / (float) persistTicks), 0.0f, 1.0f);
    }

    static int computeSurfaceWaterAmount(int candidateAmount, float absorptionCoeff, float wetness) {
        if (candidateAmount <= 0) {
            return 0;
        }
        float effectiveAbsorption = Mth.clamp(absorptionCoeff * (1.0f - Mth.clamp(wetness, 0.0f, 1.0f)), 0.0f, 1.0f);
        float surfaceWater = candidateAmount * (1.0f - effectiveAbsorption);
        if (surfaceWater < 1.0f) {
            return 0;
        }
        return Math.max(1, Math.round(surfaceWater));
    }

    static RainIntensityStage chooseRainIntensityStage(boolean thundering, long timeSlice, int chunkX, int chunkZ, long seedSalt) {
        if (thundering) {
            return RainIntensityStage.THUNDERSTORM;
        }

        long mixed = seedSalt;
        mixed ^= 0x9E3779B97F4A7C15L * (chunkX + 31L);
        mixed ^= 0xC2B2AE3D27D4EB4FL * (chunkZ - 17L);
        mixed ^= 0x165667B19E3779F9L * (timeSlice + 1L);
        mixed = mix64(mixed);
        float normalized = ((mixed >>> 40) & 0xFFFFFFL) / (float) 0xFFFFFFL;

        if (normalized < 0.2f) {
            return RainIntensityStage.DRIZZLE;
        }
        if (normalized < 0.75f) {
            return RainIntensityStage.STEADY;
        }
        return RainIntensityStage.HEAVY;
    }

    static long computeChunkSelectionOrder(long timeSlice, int chunkX, int chunkZ, int playerRing, long seedSalt) {
        long mixed = seedSalt;
        mixed ^= 0xD6E8FEB86659FD93L * (timeSlice + 1L);
        mixed ^= 0x94D049BB133111EBL * (chunkX + 91L);
        mixed ^= 0xBF58476D1CE4E5B9L * (chunkZ - 53L);
        mixed ^= 0x9E3779B97F4A7C15L * (playerRing + 7L);
        return mix64(mixed);
    }

    static int chooseChunkSelectionStartRing(long timeSlice, int ringCount, long seedSalt) {
        if (ringCount <= 1) {
            return 0;
        }
        long mixed = mix64(seedSalt ^ (0xC2B2AE3D27D4EB4FL * (timeSlice + 1L)));
        return Math.floorMod((int) (mixed ^ (mixed >>> 32)), ringCount);
    }

    static long[] selectDistributedChunkSample(long[] chunkPositions,
                                               int[] nearestPlayerRings,
                                               int maxChunks,
                                               long timeSlice,
                                               long seedSalt) {
        return ChunkWorkSelector.selectDistributed(
                chunkPositions, nearestPlayerRings, maxChunks, timeSlice, seedSalt);
    }
    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

}
