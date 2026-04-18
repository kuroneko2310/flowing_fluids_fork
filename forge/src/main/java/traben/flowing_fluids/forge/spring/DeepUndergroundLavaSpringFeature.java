package traben.flowing_fluids.forge.spring;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.FlowingFluids;

public class DeepUndergroundLavaSpringFeature extends Feature<NoneFeatureConfiguration> {
    private static final int PLACEMENT_ATTEMPTS = 18;
    private static final int LEGACY_NETHER_MAX_GENERATION_Y = 29;
    private static final int LEGACY_OVERWORLD_MAX_GENERATION_Y = -20;

    public DeepUndergroundLavaSpringFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        boolean inNether = SpringDimensionContext.isNether(level.getBiome(origin));
        if (!inNether && !level.getBiome(origin).is(BiomeTags.IS_OVERWORLD)) {
            return false;
        }

        RandomSource random = context.random();
        int minBuild = level.getMinBuildHeight();
        int maxY = inNether
                ? SpringDimensionContext.resolveLegacyAbsoluteY(level, LEGACY_NETHER_MAX_GENERATION_Y, true)
                : Math.min(SpringDimensionContext.resolveLegacyAbsoluteY(level, LEGACY_OVERWORLD_MAX_GENERATION_Y, false), level.getSeaLevel() - 48);
        int placed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        float spawnMultiplier = SpringGenerationTuning.dimensionMultiplier(level, origin);
        int originRichness = LavaLakeAffinity.sampleRichness(level, origin);
        int originDepthTier = DeepLavaDepthProfile.activityTier(origin.getY(), minBuild, inNether);
        int originUpwellingScore = DeepLavaUpwellingBias.score(level, origin, originDepthTier, originRichness);
        int attemptBudget = SpringGenerationTuning.scaledAttempts(Math.max(1, Math.round(
                SpringBiomeProfile.adjustedLavaAttempts(level.getBiome(origin), PLACEMENT_ATTEMPTS)
                        * (inNether ? 1.4F : 1.0F)
                        * FlowingFluids.config.deepLavaSpringSpawnMultiplier
                        * LavaLakeAffinity.attemptMultiplier(originRichness)
                        * DeepLavaDepthProfile.attemptMultiplier(originDepthTier)
                        * DeepLavaUpwellingBias.floorAttemptMultiplier(originUpwellingScore)
        )), spawnMultiplier);
        int placementCap = SpringGenerationTuning.scaledPlacements(Math.min(
                inNether ? 4 : 3,
                Math.min(
                        FlowingFluids.config.deepLavaSpringMaxPlacementsPerFeature
                                + LavaLakeAffinity.placementBonus(originRichness)
                                + DeepLavaDepthProfile.placementBonus(originDepthTier)
                                + DeepLavaUpwellingBias.floorPlacementBonus(originUpwellingScore),
                        SpringBiomeProfile.lavaPlacementCap(random, level.getBiome(origin), 2)
                                + (originRichness >= 2 ? 1 : 0)
                                + DeepLavaDepthProfile.placementBonus(originDepthTier)
                                + DeepLavaUpwellingBias.floorPlacementBonus(originUpwellingScore)
                )
        ), spawnMultiplier, 1, inNether ? 4 : 3);
        if (attemptBudget <= 0 || placementCap <= 0) {
            return false;
        }

        for (int i = 0; i < attemptBudget; i++) {
            int x = origin.getX() + random.nextInt(16) - 8;
            int y = origin.getY() + random.nextInt(14) - 7;
            int z = origin.getZ() + random.nextInt(16) - 8;
            y = Math.max(minBuild + 2, Math.min(y, maxY));
            cursor.set(x, y, z);
            if (tryPlaceSpring(level, cursor, random, minBuild, inNether)) {
                placed++;
                if (placed >= placementCap && random.nextBoolean()) {
                    break;
                }
            }
        }

        return placed > 0;
    }

    private boolean tryPlaceSpring(WorldGenLevel level, BlockPos.MutableBlockPos pos, RandomSource random, int minBuild, boolean inNether) {
        BlockState stateAtPos = level.getBlockState(pos);
        if (!canReplaceSpringCell(stateAtPos)) {
            return false;
        }
        int maxY = inNether
                ? SpringDimensionContext.resolveLegacyAbsoluteY(minBuild, LEGACY_NETHER_MAX_GENERATION_Y, true)
                : SpringDimensionContext.resolveLegacyAbsoluteY(minBuild, LEGACY_OVERWORLD_MAX_GENERATION_Y, false);
        if (level.canSeeSky(pos) || pos.getY() > maxY) {
            return false;
        }

        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        if (!isFloorHost(level, belowPos, belowState, inNether)) {
            return false;
        }

        if (!hasOpenLavaColumn(level, pos)) {
            return false;
        }

        int lavaRichness = LavaLakeAffinity.sampleRichness(level, pos);
        boolean nearLava = lavaRichness > 0;
        int depthFromBottom = pos.getY() - minBuild;
        int depthTier = DeepLavaDepthProfile.activityTier(pos.getY(), minBuild, inNether);
        int upwellingScore = DeepLavaUpwellingBias.score(level, pos, depthTier, lavaRichness);
        if (inNether) {
            // Nether lava springs should read as pressure coming up through lake floors, not random cave vents.
            if (!nearLava) {
                return false;
            }
            if (lavaRichness < 2 && random.nextFloat() < 0.75F) {
                return false;
            }
        } else if (depthTier <= 1 && depthFromBottom > 18 && random.nextFloat() < 0.8F) {
            return false;
        }
        float rejectChance = inNether ? 0.18F : 0.76F;
        rejectChance = SpringBiomeProfile.adjustedLavaRejectChance(level.getBiome(pos), rejectChance);
        rejectChance = Math.min(0.98F, rejectChance + FlowingFluids.config.deepLavaSpringExtraRejectChance);
        rejectChance = Math.max(
                0.02F,
                rejectChance
                        - LavaLakeAffinity.rejectReduction(lavaRichness)
                        - DeepLavaDepthProfile.rejectReduction(depthTier)
                        - DeepLavaUpwellingBias.floorRejectReduction(upwellingScore)
        );
        if (random.nextFloat() < rejectChance) {
            return false;
        }

        FloorSpringBlock springBlock = ForgeSpringRegistry.pickGeneratedFloorLavaBlock(
                random,
                pos.getY(),
                minBuild,
                inNether,
                nearLava,
                lavaRichness,
                Math.min(
                        3,
                        DeepLavaDepthProfile.strengthBonus(depthTier)
                                + DeepLavaUpwellingBias.floorStrengthBonus(upwellingScore)
                                + (inNether ? 1 : 0)
                )
        );
        level.setBlock(pos, springBlock.defaultBlockState()
                .setValue(FloorSpringBlock.FACING, net.minecraft.core.Direction.Plane.HORIZONTAL.getRandomDirection(random)), 2);
        WorldgenSpringFluidSeeder.seedLinearSpring(level, pos, net.minecraft.core.Direction.UP,
                (net.minecraft.world.level.material.FlowingFluid) Fluids.LAVA, springBlock.strength().pulseMinHeight() + (inNether ? 2 : 0));
        level.scheduleTick(pos, springBlock, springBlock.nextTickDelay(random));
        return true;
    }

    private boolean canReplaceSpringCell(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        return state.getFluidState().getType().isSame(Fluids.LAVA);
    }

    private boolean hasOpenLavaColumn(WorldGenLevel level, BlockPos pos) {
        BlockState aboveState = level.getBlockState(pos.above());
        if (canOpenUpward(aboveState)) {
            return true;
        }

        BlockState secondAboveState = level.getBlockState(pos.above(2));
        return aboveState.getFluidState().getType().isSame(Fluids.LAVA) && canOpenUpward(secondAboveState);
    }

    private boolean canOpenUpward(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().getType().isSame(Fluids.LAVA);
        }
        return state.canBeReplaced(Fluids.LAVA);
    }

    private boolean isFloorHost(WorldGenLevel level, BlockPos supportPos, BlockState supportState, boolean inNether) {
        if (!supportState.isFaceSturdy(level, supportPos, net.minecraft.core.Direction.UP)) {
            return false;
        }
        if (inNether) {
            return supportState.is(Blocks.NETHERRACK)
                    || supportState.is(Blocks.BASALT)
                    || supportState.is(Blocks.BLACKSTONE)
                    || supportState.is(Blocks.MAGMA_BLOCK)
                    || supportState.is(Blocks.SOUL_SOIL)
                    || supportState.is(Blocks.SOUL_SAND);
        }
        return supportState.is(BlockTags.BASE_STONE_OVERWORLD)
                || supportState.is(Blocks.DEEPSLATE)
                || supportState.is(Blocks.COBBLED_DEEPSLATE)
                || supportState.is(Blocks.TUFF)
                || supportState.is(Blocks.BASALT)
                || supportState.is(Blocks.MAGMA_BLOCK);
    }

}
