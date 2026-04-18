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
    private static final int PLACEMENT_ATTEMPTS = 20;

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
                SpringBiomeProfile.waterPlacementCap(random, level.getBiome(origin), 2),
                spawnMultiplier,
                1,
                3
        );
        if (attemptBudget <= 0 || placementCap <= 0) {
            return false;
        }
        int placed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int i = 0; i < attemptBudget; i++) {
            int x = origin.getX() + random.nextInt(16) - 8;
            int y = origin.getY() + random.nextInt(14) - 7;
            int z = origin.getZ() + random.nextInt(16) - 8;
            y = Math.max(level.getMinBuildHeight() + 6, Math.min(y, Math.min(level.getMaxBuildHeight() - 6, seaLevel + 8)));
            cursor.set(x, y, z);
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
        if (!stateAtPos.isAir() || !stateAtPos.getFluidState().isEmpty()) {
            return false;
        }
        if (level.canSeeSky(pos) || pos.getY() > seaLevel + 8) {
            return false;
        }

        boolean damp = hasNearbyWater(level, pos);
        float biomeBias = SpringBiomeProfile.waterBias(level.getBiome(pos));
        if (!damp) {
            float rejectChance = pos.getY() > seaLevel - 4 ? 0.70F : pos.getY() > seaLevel - 18 ? 0.42F : 0.18F;
            rejectChance = SpringBiomeProfile.adjustedWaterRejectChance(level.getBiome(pos), rejectChance);
            if (random.nextFloat() < rejectChance) {
                return false;
            }
        }

        if (!damp && biomeBias < -0.55F && random.nextFloat() < 0.45F) {
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
            BlockState placedState = springBlock.defaultBlockState().setValue(WallSpringBlock.FACING, supportDirection);
            level.setBlock(pos, placedState, 2);
            WorldgenSpringFluidSeeder.seedLinearSpring(level, pos, outputDirection, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER, 1);
            level.scheduleTick(pos, springBlock, springBlock.nextTickDelay(random));
            return true;
        }

        return false;
    }

    private boolean canOpenTowardCave(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().getType().isSame(Fluids.WATER);
        }
        return state.canBeReplaced();
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
