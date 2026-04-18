package traben.flowing_fluids.forge.spring;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

public class DeepUndergroundCeilingLavaSpringFeature extends Feature<NoneFeatureConfiguration> {
    private static final int PLACEMENT_ATTEMPTS = 12;
    private static final int LEGACY_OVERWORLD_MAX_GENERATION_Y = -18;

    public DeepUndergroundCeilingLavaSpringFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        boolean inNether = SpringDimensionContext.isNether(level.getBiome(origin));
        if (inNether) {
            // Nether should feel like magma pressure rising from the lava sea floor, not ceiling drips.
            return false;
        }
        if (!inNether && !level.getBiome(origin).is(BiomeTags.IS_OVERWORLD)) {
            return false;
        }

        RandomSource random = context.random();
        int minBuild = level.getMinBuildHeight();
        int maxY = inNether
                ? level.getMaxBuildHeight() - 24
                : Math.min(SpringDimensionContext.resolveLegacyAbsoluteY(level, LEGACY_OVERWORLD_MAX_GENERATION_Y, false), level.getSeaLevel() - 44);
        int placed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        float spawnMultiplier = SpringGenerationTuning.dimensionMultiplier(level, origin);
        int originRichness = LavaLakeAffinity.sampleRichness(level, origin);
        int originDepthTier = DeepLavaDepthProfile.activityTier(origin.getY(), minBuild, inNether);
        int originUpwellingScore = DeepLavaUpwellingBias.score(level, origin, originDepthTier, originRichness);
        int attemptBudget = SpringGenerationTuning.scaledAttempts(Math.max(1, Math.round(
                SpringBiomeProfile.adjustedLavaAttempts(level.getBiome(origin), PLACEMENT_ATTEMPTS)
                        * (inNether ? 1.35F : 1.0F)
                        * FlowingFluids.config.deepLavaSpringSpawnMultiplier
                        * LavaLakeAffinity.attemptMultiplier(originRichness)
                        * DeepLavaDepthProfile.attemptMultiplier(originDepthTier)
                        * DeepLavaUpwellingBias.ceilingAttemptMultiplier(originUpwellingScore)
        )), spawnMultiplier);
        int placementCap = SpringGenerationTuning.scaledPlacements(Math.min(
                inNether ? 3 : 2,
                Math.max(
                        1,
                        FlowingFluids.config.deepLavaSpringMaxPlacementsPerFeature
                                - 1
                                + LavaLakeAffinity.placementBonus(originRichness)
                                + DeepLavaDepthProfile.placementBonus(originDepthTier)
                                - DeepLavaUpwellingBias.ceilingPlacementPenalty(originUpwellingScore)
                )
        ), spawnMultiplier, 1, inNether ? 3 : 2);
        if (attemptBudget <= 0 || placementCap <= 0) {
            return false;
        }

        for (int i = 0; i < attemptBudget; i++) {
            int x = origin.getX() + random.nextInt(16) - 8;
            int y = origin.getY() + random.nextInt(16) - 8;
            int z = origin.getZ() + random.nextInt(16) - 8;
            y = Math.max(minBuild + 6, Math.min(y, maxY));
            cursor.set(x, y, z);
            if (tryPlaceSpring(level, cursor, random, minBuild, inNether)) {
                placed++;
                if (placed >= placementCap) {
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
                ? level.getMaxBuildHeight() - 24
                : SpringDimensionContext.resolveLegacyAbsoluteY(minBuild, LEGACY_OVERWORLD_MAX_GENERATION_Y, false);
        if (level.canSeeSky(pos) || pos.getY() > maxY) {
            return false;
        }

        BlockPos abovePos = pos.above();
        BlockState aboveState = level.getBlockState(abovePos);
        if (!isCeilingHost(level, abovePos, aboveState, inNether)) {
            return false;
        }
        if (!hasOpenDrop(level, pos)) {
            return false;
        }

        int lavaRichness = LavaLakeAffinity.sampleRichness(level, pos);
        boolean nearLava = lavaRichness > 0;
        int depthFromBottom = pos.getY() - minBuild;
        int depthTier = DeepLavaDepthProfile.activityTier(pos.getY(), minBuild, inNether);
        int upwellingScore = DeepLavaUpwellingBias.score(level, pos, depthTier, lavaRichness);
        if (!inNether && !nearLava && depthTier == 0 && depthFromBottom > 20 && random.nextFloat() < 0.78F) {
            return false;
        }
        float rejectChance = inNether ? 0.42F : 0.82F;
        rejectChance = SpringBiomeProfile.adjustedLavaRejectChance(level.getBiome(pos), rejectChance);
        rejectChance = Math.min(0.98F, rejectChance + FlowingFluids.config.deepLavaSpringExtraRejectChance);
        rejectChance = Math.min(
                0.98F,
                Math.max(
                0.02F,
                rejectChance
                        - LavaLakeAffinity.rejectReduction(lavaRichness)
                        - DeepLavaDepthProfile.rejectReduction(depthTier)
                        + DeepLavaUpwellingBias.ceilingRejectPenalty(upwellingScore)
                )
        );
        if (random.nextFloat() < rejectChance) {
            return false;
        }

        CeilingSpringBlock springBlock = ForgeSpringRegistry.pickGeneratedCeilingLavaBlock(
                random,
                pos.getY(),
                minBuild,
                inNether,
                nearLava,
                lavaRichness,
                Math.min(3, DeepLavaDepthProfile.strengthBonus(depthTier) + (inNether ? 1 : 0))
        );
        level.setBlock(pos, springBlock.defaultBlockState()
                .setValue(CeilingSpringBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(random)), 2);
        WorldgenSpringFluidSeeder.seedLinearSpring(level, pos, Direction.DOWN,
                (net.minecraft.world.level.material.FlowingFluid) Fluids.LAVA, springBlock.strength().pulseMinHeight());
        level.scheduleTick(pos, springBlock, springBlock.nextTickDelay(random));
        return true;
    }

    private boolean canReplaceSpringCell(BlockState state) {
        return state.isAir() || state.getFluidState().getType().isSame(Fluids.LAVA);
    }

    private boolean hasOpenDrop(WorldGenLevel level, BlockPos pos) {
        return canOpenDownward(level.getBlockState(pos.below()))
                || (level.getFluidState(pos.below()).getType().isSame(Fluids.LAVA)
                && canOpenDownward(level.getBlockState(pos.below(2))));
    }

    private boolean canOpenDownward(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().getType().isSame(Fluids.LAVA);
        }
        return state.canBeReplaced(Fluids.LAVA);
    }

    private boolean isCeilingHost(WorldGenLevel level, BlockPos supportPos, BlockState supportState, boolean inNether) {
        if (!supportState.isFaceSturdy(level, supportPos, Direction.DOWN)) {
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
