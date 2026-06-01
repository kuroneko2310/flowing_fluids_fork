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
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.FFFluidUtils;

public class PondFloorSpringFeature extends Feature<NoneFeatureConfiguration> {
    private static final int PLACEMENT_ATTEMPTS = 10;

    public PondFloorSpringFeature(Codec<NoneFeatureConfiguration> codec) {
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
                Math.max(4, SpringBiomeProfile.adjustedWaterAttempts(level.getBiome(origin), PLACEMENT_ATTEMPTS) - 2),
                spawnMultiplier
        );
        int placementCap = SpringGenerationTuning.scaledPlacements(
                Math.max(1, SpringBiomeProfile.waterPlacementCap(random, level.getBiome(origin), 1)),
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
            int y = origin.getY() + random.nextInt(10) - 5;
            int z = SpringDimensionContext.randomBlockInOriginChunk(origin.getZ(), random, 1);
            cursor.set(x, y, z);
            int maxY = SpringDimensionContext.localSurfaceWaterSpringMaxY(level, cursor, seaLevel, 36, 4);
            y = Math.max(level.getMinBuildHeight() + 50, Math.min(y, maxY));
            cursor.setY(y);
            if (tryPlaceSpring(level, cursor, random, seaLevel)) {
                placed++;
                if (placed >= placementCap) {
                    break;
                }
            }
        }

        return placed > 0;
    }

    private boolean tryPlaceSpring(WorldGenLevel level, BlockPos.MutableBlockPos pos, RandomSource random, int seaLevel) {
        BlockState stateAtPos = level.getBlockState(pos);
        FluidState fluidAtPos = stateAtPos.getFluidState();
        if (!stateAtPos.isAir() && !fluidAtPos.getType().isSame(Fluids.WATER)) {
            return false;
        }
        if (!hasSurfaceWaterAbove(level, pos)) {
            return false;
        }

        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        if (!isPondHost(level, belowPos, belowState)) {
            return false;
        }

        float waterBias = SpringBiomeProfile.waterBias(level.getBiome(pos));
        if (waterBias < -0.35F && random.nextFloat() < 0.65F) {
            return false;
        }
        if (!hasNearbyWaterBody(level, pos) && random.nextFloat() < 0.55F) {
            return false;
        }
        if (!hasShallowPoolShape(level, pos)) {
            return false;
        }

        FloorSpringBlock springBlock = ForgeSpringRegistry.pickGeneratedFloorBlock(random, pos.getY(), seaLevel, true);
        Direction facing = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        level.setBlock(pos, springBlock.defaultBlockState()
                .setValue(FloorSpringBlock.FACING, facing)
                .setValue(FloorSpringBlock.WATERLOGGED, fluidAtPos.getType().isSame(Fluids.WATER)), 2);
        level.scheduleTick(pos, springBlock, springBlock.nextTickDelay(random));
        return true;
    }

    private boolean hasSurfaceWaterAbove(WorldGenLevel level, BlockPos pos) {
        int waterDepth = 0;
        for (int i = 0; i <= 2; i++) {
            BlockPos checkPos = pos.above(i);
            FluidState state = level.getFluidState(checkPos);
            if (!state.getType().isSame(Fluids.WATER)) {
                if (i == 0 && !level.getBlockState(checkPos).isAir()) {
                    return false;
                }
                break;
            }
            waterDepth++;
        }

        if (waterDepth == 0) {
            return false;
        }

        BlockPos topPos = pos.above(waterDepth);
        return level.canSeeSky(topPos);
    }

    private boolean hasNearbyWaterBody(WorldGenLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                cursor.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
                if (level.getFluidState(cursor).getType().isSame(Fluids.WATER)
                        || level.getFluidState(cursor.above()).getType().isSame(Fluids.WATER)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasShallowPoolShape(WorldGenLevel level, BlockPos pos) {
        int waterySides = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.set(pos).move(direction);
            if (level.getFluidState(cursor).getType().isSame(Fluids.WATER)
                    || level.getFluidState(cursor.above()).getType().isSame(Fluids.WATER)) {
                waterySides++;
            }
        }
        return waterySides >= 2;
    }

    private boolean isPondHost(WorldGenLevel level, BlockPos supportPos, BlockState supportState) {
        if (!supportState.isFaceSturdy(level, supportPos, Direction.UP)) {
            return false;
        }
        return supportState.is(BlockTags.BASE_STONE_OVERWORLD)
                || supportState.is(Blocks.DEEPSLATE)
                || supportState.is(Blocks.TUFF)
                || supportState.is(Blocks.GRAVEL)
                || supportState.is(Blocks.CLAY)
                || supportState.is(Blocks.DIRT)
                || supportState.is(Blocks.COARSE_DIRT)
                || supportState.is(Blocks.ROOTED_DIRT)
                || supportState.is(Blocks.MUD)
                || supportState.is(Blocks.PACKED_MUD)
                || supportState.is(Blocks.SAND)
                || supportState.is(Blocks.RED_SAND);
    }
}
