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

public class CaveCeilingSpringFeature extends Feature<NoneFeatureConfiguration> {
    private static final int PLACEMENT_ATTEMPTS = 22;

    public CaveCeilingSpringFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        if (!level.getBiome(origin).is(BiomeTags.IS_OVERWORLD)) {
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
            int x = SpringDimensionContext.randomBlockInOriginChunk(origin.getX(), random, 1);
            int y = origin.getY() + random.nextInt(33) - 16;
            int z = SpringDimensionContext.randomBlockInOriginChunk(origin.getZ(), random, 1);
            cursor.set(x, y, z);
            int maxY = SpringDimensionContext.localUndergroundWaterSpringMaxY(level, cursor, seaLevel, 10, 6, 6);
            y = Math.max(level.getMinBuildHeight() + 8, Math.min(y, maxY));
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
                || pos.getY() > SpringDimensionContext.localUndergroundWaterSpringMaxY(level, pos, seaLevel, 10, 6, 6)) {
            return false;
        }
        BlockPos abovePos = pos.above();
        BlockState aboveState = level.getBlockState(abovePos);
        if (!isCeilingHost(level, abovePos, aboveState)) {
            return false;
        }
        if (!prepareOpenDrop(level, pos)) {
            return false;
        }

        float biomeBias = SpringBiomeProfile.waterBias(level.getBiome(pos));
        boolean damp = hasNearbyWater(level, pos)
                || FFFluidUtils.matchInfiniteBiomes(level.getBiome(pos))
                || biomeBias > 0.35F;
        if (!damp) {
            float rejectChance = pos.getY() > seaLevel - 2 ? 0.58F : pos.getY() > seaLevel - 16 ? 0.34F : 0.16F;
            rejectChance = SpringBiomeProfile.adjustedWaterRejectChance(level.getBiome(pos), rejectChance);
            if (random.nextFloat() < rejectChance) {
                return false;
            }
        }

        CeilingSpringBlock springBlock = ForgeSpringRegistry.pickGeneratedCeilingBlock(random, pos.getY(), seaLevel, damp);
        level.setBlock(pos, springBlock.defaultBlockState()
                .setValue(CeilingSpringBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(random))
                .setValue(CeilingSpringBlock.WATERLOGGED, true), 2);
        WorldgenSpringFluidSeeder.seedLinearSpring(level, pos, Direction.DOWN, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER,
                springBlock.strength().pulseMinHeight());
        level.scheduleTick(pos, springBlock, springBlock.nextTickDelay(random));
        return true;
    }

    private boolean canReplaceSpringCell(BlockState state) {
        return SpringCavityCarver.canCarveWater(state);
    }

    private boolean prepareOpenDrop(WorldGenLevel level, BlockPos pos) {
        if (!canOpenDownward(level.getBlockState(pos.below()))) {
            return false;
        }
        return SpringCavityCarver.carveFluidCell(level, pos.below(), (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER)
                && SpringCavityCarver.carveFluidCell(level, pos.below(2), (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER);
    }

    private boolean canOpenDownward(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().getType().isSame(Fluids.WATER);
        }
        return state.canBeReplaced(Fluids.WATER) || SpringCavityCarver.canCarveWater(state);
    }

    private boolean isCeilingHost(WorldGenLevel level, BlockPos supportPos, BlockState supportState) {
        if (!supportState.isFaceSturdy(level, supportPos, Direction.DOWN)) {
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
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -4; dy <= 3; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (level.getFluidState(cursor).getType().isSame(Fluids.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
