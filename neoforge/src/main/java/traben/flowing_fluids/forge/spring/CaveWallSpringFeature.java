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
import traben.flowing_fluids.FFFluidUtils;

public class CaveWallSpringFeature extends Feature<NoneFeatureConfiguration> {
    private static final int PLACEMENT_ATTEMPTS = 28;

    public CaveWallSpringFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();

        if (level.getBiome(origin).is(BiomeTags.IS_NETHER) || level.getBiome(origin).is(BiomeTags.IS_END)) {
            return false;
        }

        RandomSource random = context.random();
        int seaLevel = level.getSeaLevel();
        float spawnMultiplier = SpringGenerationTuning.dimensionMultiplier(level, origin);
        int attemptBudget = SpringGenerationTuning.scaledAttempts(
                SpringBiomeProfile.adjustedWaterAttempts(level.getBiome(origin), PLACEMENT_ATTEMPTS),
                spawnMultiplier
        );
        int placementCap = SpringGenerationTuning.scaledPlacements(
                SpringBiomeProfile.waterPlacementCap(random, level.getBiome(origin), 3),
                spawnMultiplier,
                1,
                1
        );
        if (attemptBudget <= 0 || placementCap <= 0) {
            return false;
        }
        int placed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int i = 0; i < attemptBudget; i++) {
            int x = SpringDimensionContext.randomBlockInOriginChunk(origin.getX(), random, 1);
            int y = origin.getY() + random.nextInt(33) - 16;
            int z = SpringDimensionContext.randomBlockInOriginChunk(origin.getZ(), random, 1);
            cursor.set(x, y, z);
            int maxY = SpringDimensionContext.localUndergroundWaterSpringMaxY(level, cursor, seaLevel, 8, 10, 6);
            y = Math.max(level.getMinBuildHeight() + 6, Math.min(y, maxY));
            cursor.setY(y);
            if (tryPlaceSpring(level, cursor, random, seaLevel)) {
                placed++;
                if (placed >= placementCap && random.nextBoolean()) {
                    break;
                }
            }
        }

        return placed > 0;
    }

    private boolean tryPlaceSpring(WorldGenLevel level, BlockPos.MutableBlockPos pos, RandomSource random, int seaLevel) {
        BlockState stateAtPos = level.getBlockState(pos);
        if (!canReplaceSpringCell(stateAtPos)) {
            return false;
        }
        if (level.canSeeSky(pos)
                || pos.getY() > SpringDimensionContext.localUndergroundWaterSpringMaxY(level, pos, seaLevel, 8, 10, 6)) {
            return false;
        }
        float biomeBias = SpringBiomeProfile.waterBias(level.getBiome(pos));
        boolean damp = hasNearbyWater(level, pos)
                || FFFluidUtils.matchInfiniteBiomes(level.getBiome(pos))
                || biomeBias > 0.35F;
        if (!damp) {
            float rejectChance = pos.getY() > seaLevel - 4 ? 0.46F : pos.getY() > seaLevel - 18 ? 0.26F : 0.10F;
            rejectChance = SpringBiomeProfile.adjustedWaterRejectChance(level.getBiome(pos), rejectChance);
            if (random.nextFloat() < rejectChance) {
                return false;
            }
        }

        if (!damp && biomeBias < -0.55F && random.nextFloat() < 0.32F) {
            return false;
        }

        for (Direction supportDirection : FFFluidUtils.getCardinalsShuffle(random)) {
            BlockPos supportPos = pos.relative(supportDirection);
            BlockState supportState = level.getBlockState(supportPos);
            if (!isWallHost(level, supportPos, supportState, supportDirection)) {
                continue;
            }

            Direction outputDirection = supportDirection.getOpposite();
            BlockPos outputPos = pos.relative(outputDirection);
            BlockState outputState = level.getBlockState(outputPos);
            if (!canOpenTowardCave(outputState)) {
                continue;
            }

            WallSpringBlock springBlock = ForgeSpringRegistry.pickGeneratedBlock(random, pos.getY(), seaLevel, damp);
            BlockState placedState = springBlock.defaultBlockState()
                    .setValue(WallSpringBlock.FACING, supportDirection)
                    .setValue(WallSpringBlock.WATERLOGGED, false);
            if (!level.setBlock(pos, placedState, 2)) {
                continue;
            }
            if (!SpringCavityCarver.fillExistingCavityFluidCell(level, outputPos, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER)) {
                return false;
            }
            WorldgenSpringFluidSeeder.seedLinearSpringInExistingCavity(level, pos, outputDirection, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER, 1);
            level.scheduleTick(pos, springBlock, springBlock.nextTickDelay(random));
            return true;
        }

        return false;
    }

    private boolean canReplaceSpringCell(BlockState state) {
        return SpringCavityCarver.canPlaceSpringBlock(state, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER);
    }

    private boolean canOpenTowardCave(BlockState state) {
        return SpringCavityCarver.canPlaceSpringBlock(state, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER);
    }

    private boolean isWallHost(WorldGenLevel level, BlockPos supportPos, BlockState supportState, Direction supportDirection) {
        if (!supportState.isFaceSturdy(level, supportPos, supportDirection.getOpposite())) {
            return false;
        }
        return supportState.is(BlockTags.BASE_STONE_OVERWORLD)
                || supportState.is(Blocks.DEEPSLATE)
                || supportState.is(Blocks.COBBLED_DEEPSLATE)
                || supportState.is(Blocks.TUFF)
                || supportState.is(Blocks.CALCITE)
                || supportState.is(Blocks.DRIPSTONE_BLOCK);
    }

    private boolean hasNearbyWater(WorldGenLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int step = 1; step <= 5; step++) {
                cursor.set(pos).move(direction, step);
                if (level.getFluidState(cursor).getType().isSame(Fluids.WATER)) {
                    return true;
                }
                cursor.set(pos).move(direction, step).move(Direction.UP);
                if (level.getFluidState(cursor).getType().isSame(Fluids.WATER)) {
                    return true;
                }
                cursor.set(pos).move(direction, step).move(Direction.DOWN);
                if (level.getFluidState(cursor).getType().isSame(Fluids.WATER)) {
                    return true;
                }
            }
        }

        for (int step = 1; step <= 8; step++) {
            cursor.set(pos).move(Direction.UP, step);
            if (level.getFluidState(cursor).getType().isSame(Fluids.WATER)) {
                return true;
            }
        }

        for (int step = 1; step <= 4; step++) {
            cursor.set(pos).move(Direction.DOWN, step);
            if (level.getFluidState(cursor).getType().isSame(Fluids.WATER)) {
                return true;
            }
        }

        return false;
    }
}
