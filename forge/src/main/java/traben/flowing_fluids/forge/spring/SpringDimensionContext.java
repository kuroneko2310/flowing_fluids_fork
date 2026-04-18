package traben.flowing_fluids.forge.spring;

import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;

final class SpringDimensionContext {
    private static final int VANILLA_OVERWORLD_MIN_BUILD_HEIGHT = -64;
    private static final int VANILLA_NETHER_MIN_BUILD_HEIGHT = 0;

    private SpringDimensionContext() {
    }

    static boolean isNether(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_NETHER);
    }

    static boolean isUltraWarm(LevelAccessor level) {
        return level.dimensionType().ultraWarm();
    }

    static int heightAboveBedrock(int y, int minBuildHeight) {
        return y - minBuildHeight;
    }

    static int resolveLegacyAbsoluteY(LevelAccessor level, int legacyAbsoluteY, boolean netherLike) {
        return resolveLegacyAbsoluteY(level.getMinBuildHeight(), legacyAbsoluteY, netherLike);
    }

    static int resolveLegacyAbsoluteY(int minBuildHeight, int legacyAbsoluteY, boolean netherLike) {
        return minBuildHeight + legacyHeightAboveBedrock(legacyAbsoluteY, netherLike);
    }

    static boolean isAtOrBelowLegacyAbsoluteY(int y, int minBuildHeight, int legacyAbsoluteY, boolean netherLike) {
        return heightAboveBedrock(y, minBuildHeight) <= legacyHeightAboveBedrock(legacyAbsoluteY, netherLike);
    }

    static boolean isBelowLegacyAbsoluteY(int y, int minBuildHeight, int legacyAbsoluteY, boolean netherLike) {
        return heightAboveBedrock(y, minBuildHeight) < legacyHeightAboveBedrock(legacyAbsoluteY, netherLike);
    }

    private static int legacyHeightAboveBedrock(int legacyAbsoluteY, boolean netherLike) {
        int vanillaMinBuildHeight = netherLike ? VANILLA_NETHER_MIN_BUILD_HEIGHT : VANILLA_OVERWORLD_MIN_BUILD_HEIGHT;
        return Math.max(0, legacyAbsoluteY - vanillaMinBuildHeight);
    }
}
