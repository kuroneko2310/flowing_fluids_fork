package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

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

    static int localUndergroundWaterSpringMaxY(LevelAccessor level, BlockPos pos, int seaLevel,
                                               int seaLevelOffset, int surfaceDepthMargin, int topMargin) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, pos.getX(), pos.getZ());
        int surfaceAwareMaxY = surfaceY - surfaceDepthMargin;
        int maxY = Math.max(seaLevel + seaLevelOffset, surfaceAwareMaxY);
        return Mth.clamp(maxY, level.getMinBuildHeight(), level.getMaxBuildHeight() - topMargin);
    }

    static int localSurfaceWaterSpringMaxY(LevelAccessor level, BlockPos pos, int seaLevel,
                                           int seaLevelOffset, int topMargin) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, pos.getX(), pos.getZ());
        int maxY = Math.max(seaLevel + seaLevelOffset, surfaceY + 2);
        return Mth.clamp(maxY, level.getMinBuildHeight(), level.getMaxBuildHeight() - topMargin);
    }

    static int randomBlockInOriginChunk(int originCoordinate, RandomSource random, int margin) {
        int clampedMargin = Mth.clamp(margin, 0, 7);
        int min = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(originCoordinate)) + clampedMargin;
        int width = 16 - clampedMargin * 2;
        return min + random.nextInt(width);
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
