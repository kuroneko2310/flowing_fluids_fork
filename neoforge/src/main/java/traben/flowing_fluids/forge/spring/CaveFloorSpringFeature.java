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

public class CaveFloorSpringFeature extends Feature<NoneFeatureConfiguration> {
    private static final int PLACEMENT_ATTEMPTS = 24;

    public CaveFloorSpringFeature(Codec<NoneFeatureConfiguration> codec) {
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
            int maxY = SpringDimensionContext.localUndergroundWaterSpringMaxY(level, cursor, seaLevel, 2, 8, 10);
            y = Math.max(level.getMinBuildHeight() + 6, Math.min(y, maxY));
            cursor.setY(y);
            if (tryPlaceSpring(level, cursor, random, seaLevel)) {
                placed++;
                if (placed >= placementCap && random.nextInt(3) != 0) {
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
                || pos.getY() > SpringDimensionContext.localUndergroundWaterSpringMaxY(level, pos, seaLevel, 2, 8, 10)) {
            return false;
        }
        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        if (!isFloorHost(level, belowPos, belowState)) {
            return false;
        }

        if (!hasOpenWaterColumn(level, pos)) {
            return false;
        }

        float biomeBias = SpringBiomeProfile.waterBias(level.getBiome(pos));
        boolean damp = hasNearbyWater(level, pos)
                || FFFluidUtils.matchInfiniteBiomes(level.getBiome(pos))
                || biomeBias > 0.35F;
        if (!damp) {
            float rejectChance = pos.getY() > seaLevel - 6 ? 0.52F : pos.getY() > seaLevel - 18 ? 0.30F : 0.14F;
            rejectChance = SpringBiomeProfile.adjustedWaterRejectChance(level.getBiome(pos), rejectChance);
            if (random.nextFloat() < rejectChance) {
                return false;
            }
        }

        if (!prepareCaveBreathingRoom(level, pos)) {
            return false;
        }

        FloorSpringBlock springBlock = ForgeSpringRegistry.pickGeneratedFloorBlock(random, pos.getY(), seaLevel, damp);
        Direction facing = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        if (!level.setBlock(pos, springBlock.defaultBlockState()
                .setValue(FloorSpringBlock.FACING, facing)
                .setValue(FloorSpringBlock.WATERLOGGED, false), 2)) {
            return false;
        }
        if (!fillOpenWaterColumn(level, pos)) {
            return false;
        }
        WorldgenSpringFluidSeeder.seedLinearSpringInExistingCavity(level, pos, Direction.UP, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER,
                springBlock.strength().pulseMinHeight());
        level.scheduleTick(pos, springBlock, springBlock.nextTickDelay(random));
        return true;
    }

    private boolean canReplaceSpringCell(BlockState state) {
        return SpringCavityCarver.canPlaceSpringBlock(state, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER);
    }

    private boolean canOpenUpward(BlockState state) {
        return SpringCavityCarver.canPlaceSpringBlock(state, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER);
    }

    private boolean hasOpenWaterColumn(WorldGenLevel level, BlockPos pos) {
        return canOpenUpward(level.getBlockState(pos.above()))
                && canOpenUpward(level.getBlockState(pos.above(2)));
    }

    private boolean fillOpenWaterColumn(WorldGenLevel level, BlockPos pos) {
        return SpringCavityCarver.fillExistingCavityFluidCell(level, pos.above(), (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER)
                && SpringCavityCarver.fillExistingCavityFluidCell(level, pos.above(2), (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER);
    }

    private boolean prepareCaveBreathingRoom(WorldGenLevel level, BlockPos pos) {
        int openSides = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.set(pos).move(direction);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir() || state.canBeReplaced(Fluids.WATER) || state.getFluidState().getType().isSame(Fluids.WATER)) {
                openSides++;
            }
        }
        if (openSides >= 2) {
            return true;
        }
        return false;
    }

    private boolean isFloorHost(WorldGenLevel level, BlockPos supportPos, BlockState supportState) {
        if (!supportState.isFaceSturdy(level, supportPos, Direction.UP)) {
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
            for (int dy = -3; dy <= 4; dy++) {
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
