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

public class CaveFloorSpringFeature extends Feature<NoneFeatureConfiguration> {
    private static final int PLACEMENT_ATTEMPTS = 16;

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
            int y = origin.getY() + random.nextInt(16) - 8;
            int z = origin.getZ() + random.nextInt(16) - 8;
            y = Math.max(level.getMinBuildHeight() + 6, Math.min(y, Math.min(level.getMaxBuildHeight() - 10, seaLevel + 2)));
            cursor.set(x, y, z);
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
        if (level.canSeeSky(pos) || pos.getY() > seaLevel + 2) {
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

        boolean damp = hasNearbyWater(level, pos);
        if (!damp) {
            float rejectChance = pos.getY() > seaLevel - 6 ? 0.78F : pos.getY() > seaLevel - 18 ? 0.48F : 0.24F;
            rejectChance = SpringBiomeProfile.adjustedWaterRejectChance(level.getBiome(pos), rejectChance);
            if (random.nextFloat() < rejectChance) {
                return false;
            }
        }

        if (!hasCaveBreathingRoom(level, pos)) {
            return false;
        }

        FloorSpringBlock springBlock = ForgeSpringRegistry.pickGeneratedFloorBlock(random, pos.getY(), seaLevel, damp);
        Direction facing = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        boolean waterlogged = level.getFluidState(pos).getType().isSame(Fluids.WATER);
        level.setBlock(pos, springBlock.defaultBlockState()
                .setValue(FloorSpringBlock.FACING, facing)
                .setValue(FloorSpringBlock.WATERLOGGED, waterlogged), 2);
        WorldgenSpringFluidSeeder.seedLinearSpring(level, pos, Direction.UP, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER,
                springBlock.strength().pulseMinHeight());
        level.scheduleTick(pos, springBlock, springBlock.nextTickDelay(random));
        return true;
    }

    private boolean canReplaceSpringCell(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        return state.getFluidState().getType().isSame(Fluids.WATER);
    }

    private boolean canOpenUpward(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().getType().isSame(Fluids.WATER);
        }
        return state.canBeReplaced(Fluids.WATER);
    }

    private boolean hasOpenWaterColumn(WorldGenLevel level, BlockPos pos) {
        BlockState aboveState = level.getBlockState(pos.above());
        if (canOpenUpward(aboveState)) {
            return true;
        }

        BlockState secondAboveState = level.getBlockState(pos.above(2));
        return aboveState.getFluidState().getType().isSame(Fluids.WATER) && canOpenUpward(secondAboveState);
    }

    private boolean hasCaveBreathingRoom(WorldGenLevel level, BlockPos pos) {
        int openSides = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.set(pos).move(direction);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir() || state.canBeReplaced(Fluids.WATER) || state.getFluidState().getType().isSame(Fluids.WATER)) {
                openSides++;
            }
        }
        return openSides >= 2;
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
