package traben.flowing_fluids.forge.spring;

import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

public final class SpringBiomeProfile {
    private SpringBiomeProfile() {
    }

    public static int adjustedWaterAttempts(Holder<Biome> biome, int baseAttempts) {
        return scaleAttempts(baseAttempts, waterBias(biome), 8, 30);
    }

    public static int adjustedLavaAttempts(Holder<Biome> biome, int baseAttempts) {
        return scaleAttempts(baseAttempts, lavaBias(biome), 5, 22);
    }

    public static int waterPlacementCap(RandomSource random, Holder<Biome> biome, int baseCap) {
        float bias = waterBias(biome);
        int cap = baseCap + (bias > 0.60F ? 1 : bias < -0.45F ? -1 : 0);
        if (bias > 0.75F && random.nextBoolean()) {
            cap++;
        }
        return Mth.clamp(cap, 1, 3);
    }

    public static int lavaPlacementCap(RandomSource random, Holder<Biome> biome, int baseCap) {
        float bias = lavaBias(biome);
        int cap = baseCap + (bias > 0.55F ? 1 : bias < -0.40F ? -1 : 0);
        if (bias > 0.80F && random.nextBoolean()) {
            cap++;
        }
        return Mth.clamp(cap, 1, 3);
    }

    public static float adjustedWaterRejectChance(Holder<Biome> biome, float baseChance) {
        return Mth.clamp(baseChance - waterBias(biome) * 0.28F, 0.03F, 0.96F);
    }

    public static float adjustedLavaRejectChance(Holder<Biome> biome, float baseChance) {
        return Mth.clamp(baseChance - lavaBias(biome) * 0.24F, 0.03F, 0.96F);
    }

    public static float waterBias(Holder<Biome> biome) {
        Biome value = biome.value();
        float bias = (climateDownfall(value) - 0.5F) * 0.85F;
        bias += value.hasPrecipitation() ? 0.08F : -0.18F;
        bias -= Mth.clamp((value.getBaseTemperature() - 1.2F) * 0.12F, -0.10F, 0.14F);

        if (biome.is(Biomes.LUSH_CAVES)) bias += 0.72F;
        if (biome.is(Biomes.DRIPSTONE_CAVES)) bias += 0.34F;
        if (biome.is(Biomes.RIVER) || biome.is(Biomes.FROZEN_RIVER)) bias += 0.32F;
        if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) bias += 0.42F;
        if (biome.is(Biomes.JUNGLE) || biome.is(Biomes.BAMBOO_JUNGLE)) bias += 0.22F;
        if (biome.is(Biomes.TAIGA) || biome.is(Biomes.OLD_GROWTH_PINE_TAIGA) || biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)) bias += 0.12F;

        if (biome.is(Biomes.DESERT)) bias -= 0.70F;
        if (biome.is(Biomes.BADLANDS) || biome.is(Biomes.ERODED_BADLANDS) || biome.is(Biomes.WOODED_BADLANDS)) bias -= 0.54F;
        if (biome.is(Biomes.SAVANNA) || biome.is(Biomes.SAVANNA_PLATEAU) || biome.is(Biomes.WINDSWEPT_SAVANNA)) bias -= 0.26F;
        if (biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.ICE_SPIKES)) bias -= 0.12F;
        if (biome.is(Biomes.DEEP_DARK)) bias -= 0.06F;

        return Mth.clamp(bias, -1.0F, 1.0F);
    }

    public static float lavaBias(Holder<Biome> biome) {
        Biome value = biome.value();
        float bias = (value.getBaseTemperature() - 0.8F) * 0.35F;
        bias += value.hasPrecipitation() ? -0.08F : 0.18F;
        bias -= Mth.clamp((climateDownfall(value) - 0.55F) * 0.45F, -0.08F, 0.26F);

        if (biome.is(Biomes.BADLANDS) || biome.is(Biomes.ERODED_BADLANDS) || biome.is(Biomes.WOODED_BADLANDS)) bias += 0.62F;
        if (biome.is(Biomes.DESERT)) bias += 0.32F;
        if (biome.is(Biomes.SAVANNA) || biome.is(Biomes.SAVANNA_PLATEAU) || biome.is(Biomes.WINDSWEPT_SAVANNA)) bias += 0.24F;
        if (biome.is(Biomes.STONY_PEAKS) || biome.is(Biomes.JAGGED_PEAKS)) bias += 0.16F;
        if (biome.is(Biomes.DEEP_DARK)) bias += 0.18F;

        if (biome.is(Biomes.LUSH_CAVES)) bias -= 0.30F;
        if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) bias -= 0.30F;
        if (biome.is(Biomes.RIVER) || biome.is(Biomes.FROZEN_RIVER)) bias -= 0.24F;
        if (biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.ICE_SPIKES) || biome.is(Biomes.FROZEN_PEAKS)) bias -= 0.22F;
        if (biome.is(Biomes.NETHER_WASTES)) bias += 0.58F;
        if (biome.is(Biomes.BASALT_DELTAS)) bias += 0.86F;
        if (biome.is(Biomes.CRIMSON_FOREST)) bias += 0.48F;
        if (biome.is(Biomes.SOUL_SAND_VALLEY)) bias += 0.38F;
        if (biome.is(Biomes.WARPED_FOREST)) bias += 0.22F;

        return Mth.clamp(bias, -1.0F, 1.0F);
    }

    private static int scaleAttempts(int baseAttempts, float bias, int min, int max) {
        int adjusted = baseAttempts + Math.round(bias * 10.0F);
        return Mth.clamp(adjusted, min, max);
    }

    private static float climateDownfall(Biome biome) {
        return biome.getModifiedClimateSettings().downfall();
    }
}
