package traben.flowing_fluids.forge.spring;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;

public class SurfaceVentSpringFeature extends Feature<NoneFeatureConfiguration> {
    private static final int WATER_SURFACE_VENT_SEA_LEVEL_MARGIN = 2;

    private final FlowingFluid fluid;
    private final boolean lava;

    public SurfaceVentSpringFeature(Codec<NoneFeatureConfiguration> codec, FlowingFluid fluid) {
        super(codec);
        this.fluid = fluid;
        this.lava = fluid.isSame(Fluids.LAVA);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        if (!level.getBiome(origin).is(BiomeTags.IS_OVERWORLD)) {
            return false;
        }

        RandomSource random = context.random();
        int minBuild = level.getMinBuildHeight();
        int seaLevel = level.getSeaLevel();
        int placed = 0;
        int baseAttempts = lava ? 4 : 10;
        float spawnMultiplier = SpringGenerationTuning.dimensionMultiplier(level, origin);
        int attemptBudget = lava
                ? Math.max(2, Math.min(10, SpringBiomeProfile.adjustedLavaAttempts(level.getBiome(origin), baseAttempts)))
                : Math.max(8, Math.min(22, SpringBiomeProfile.adjustedWaterAttempts(level.getBiome(origin), baseAttempts + 2)));
        attemptBudget = SpringGenerationTuning.scaledAttempts(attemptBudget, spawnMultiplier);
        if (attemptBudget <= 0) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int i = 0; i < attemptBudget; i++) {
            int x = SpringDimensionContext.randomBlockInOriginChunk(origin.getX(), random, 1);
            int z = SpringDimensionContext.randomBlockInOriginChunk(origin.getZ(), random, 1);
            int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
            if (surfaceY <= minBuild + 8 || surfaceY >= level.getMaxBuildHeight() - 8) {
                continue;
            }

            cursor.set(x, surfaceY, z);
            if (tryPlaceVent(level, cursor, random, seaLevel, minBuild)) {
                placed++;
                break;
            }
        }

        return placed > 0;
    }

    private boolean tryPlaceVent(WorldGenLevel level, BlockPos.MutableBlockPos surfacePos, RandomSource random, int seaLevel, int minBuild) {
        var biome = level.getBiome(surfacePos);
        float bias = lava ? SpringBiomeProfile.lavaBias(biome) : SpringBiomeProfile.waterBias(biome);
        if (lava && bias < -0.15F) {
            return false;
        }
        if (!lava && bias < -0.65F) {
            return false;
        }
        if (shouldSkipWaterVentNearSeaSurface(biome, surfacePos.getY(), seaLevel)) {
            return false;
        }
        BlockPos mouthPos = surfacePos.above();
        int shaftDepth = lava ? Mth.nextInt(random, 8, 18) : Mth.nextInt(random, 10, 24);
        BlockPos springPos = surfacePos.below(shaftDepth);
        if (springPos.getY() <= minBuild + 5) {
            return false;
        }

        BlockState surfaceState = level.getBlockState(surfacePos);
        if (!lava && !surfaceState.getFluidState().isEmpty()) {
            return false;
        }
        BlockState springState = level.getBlockState(springPos);
        if (!canReplaceSpringCell(springState)) {
            return false;
        }

        BlockPos supportPos = springPos.below();
        BlockState supportState = level.getBlockState(supportPos);
        BlockState shellState = chooseVentShellState(surfaceState, supportState);

        if (!prepareSurfaceCap(level, surfacePos, surfaceState, shellState)) {
            return false;
        }
        if (!prepareVentMouth(level, mouthPos)) {
            return false;
        }
        if (!prepareVentSupport(level, supportPos, supportState, shellState)) {
            return false;
        }
        if (!hasCarveableShaft(level, springPos, surfacePos)) {
            return false;
        }
        if (!ensureStableVentWalls(level, springPos, surfacePos, shellState)) {
            return false;
        }

        if (lava) {
            int nearbyRichness = LavaLakeAffinity.sampleRichness(level, springPos);
            float rejectChance = SpringBiomeProfile.adjustedLavaRejectChance(biome, nearbyRichness > 0 ? 0.52F : 0.80F);
            if (nearbyRichness <= 0 && random.nextFloat() < rejectChance) {
                return false;
            }
        } else {
            boolean nearbyWater = hasNearbyFluid(level, springPos, 6, 5, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER);
            boolean wetBiome = FFFluidUtils.matchInfiniteBiomes(biome) || SpringBiomeProfile.waterBias(biome) > 0.35F;
            float rejectChance = SpringBiomeProfile.adjustedWaterRejectChance(biome, nearbyWater || wetBiome ? 0.18F : 0.42F);
            if (!nearbyWater && random.nextFloat() < rejectChance) {
                return false;
            }
        }

        FloorSpringBlock springBlock = pickVentBlock(random, seaLevel, surfacePos.getY(), bias);
        level.setBlock(springPos, springBlock.defaultBlockState()
                .setValue(FloorSpringBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(random))
                .setValue(FloorSpringBlock.WATERLOGGED, false), 2);

        fillVentColumn(level, springPos, mouthPos);
        level.scheduleTick(springPos, springBlock, springBlock.nextTickDelay(random));
        return true;
    }

    private boolean shouldSkipWaterVentNearSeaSurface(net.minecraft.core.Holder<Biome> biome, int y, int seaLevel) {
        return !lava && isBroadWaterSurfaceBiome(biome) && y <= seaLevel + WATER_SURFACE_VENT_SEA_LEVEL_MARGIN;
    }

    private static boolean isBroadWaterSurfaceBiome(net.minecraft.core.Holder<Biome> biome) {
        return FFFluidUtils.isOceanBiome(biome)
                || FFFluidUtils.isBeachBiome(biome)
                || FFFluidUtils.isRiverBiome(biome)
                || FFFluidUtils.matchInfiniteBiomes(biome);
    }

    private FloorSpringBlock pickVentBlock(RandomSource random, int seaLevel, int y, float bias) {
        if (lava) {
            boolean veryHot = bias > 0.55F || y < seaLevel - 6;
            return veryHot || random.nextFloat() < 0.55F
                    ? ForgeSpringRegistry.FLOOR_LAVA_SPRING_HEAVY.get()
                    : ForgeSpringRegistry.FLOOR_LAVA_SPRING_LARGE.get();
        }

        boolean veryWet = bias > 0.45F || y < seaLevel - 4;
        return veryWet && random.nextFloat() < 0.45F
                ? ForgeSpringRegistry.FLOOR_SPRING_HEAVY.get()
                : ForgeSpringRegistry.FLOOR_SPRING_LARGE.get();
    }

    private void fillVentColumn(WorldGenLevel level, BlockPos springPos, BlockPos mouthPos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = springPos.getY() + 1; y <= mouthPos.getY(); y++) {
            cursor.set(springPos.getX(), y, springPos.getZ());
            if (!SpringCavityCarver.carveFluidCell(level, cursor, fluid)) {
                break;
            }
        }

        if (fluid.isSame(Fluids.WATER)) {
            seedInitialRaisedCrest(level, mouthPos, 3);
        }
    }

    private void seedInitialRaisedCrest(WorldGenLevel level, BlockPos mouthPos, int crestHeight) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int offset = 1; offset <= crestHeight; offset++) {
            cursor.set(mouthPos.getX(), mouthPos.getY() + offset, mouthPos.getZ());
            BlockState crestState = level.getBlockState(cursor);

            // Worldgen runs in a WorldGenRegion, so the runtime ServerLevel-only sustain helper
            // cannot be used here. Seed the crest directly, then let the placed spring keep it alive.
            if (!FFFluidUtils.canStorePartialFluidAmount(level, cursor, crestState, fluid)) {
                if (!SpringCavityCarver.canCarveForFluid(crestState, fluid)) {
                    break;
                }
                SpringCavityCarver.carveFluidCell(level, cursor, fluid);
                continue;
            }

            FFFluidUtils.setFluidStateAtPosToNewAmount(level, cursor, fluid, 8);
            AdaptiveTickScheduler.scheduleFluidTick(level, cursor, fluid, fluid.getTickDelay(level));
        }
    }

    private boolean prepareSurfaceCap(WorldGenLevel level, BlockPos surfacePos, BlockState surfaceState, BlockState shellState) {
        if (isSurfaceCap(level, surfacePos, surfaceState)) {
            return true;
        }
        if (!canShapeVentBlock(surfaceState)) {
            return false;
        }
        level.setBlock(surfacePos, shellState, 2);
        return isSurfaceCap(level, surfacePos, level.getBlockState(surfacePos));
    }

    private boolean prepareVentMouth(WorldGenLevel level, BlockPos mouthPos) {
        BlockState mouthState = level.getBlockState(mouthPos);
        if (!canShapeVentBlock(mouthState)) {
            return false;
        }

        // Let worldgen replace the mouth with fluid directly instead of leaving an air step behind.
        return level.canSeeSky(mouthPos)
                || (!mouthState.isAir() && level.canSeeSky(mouthPos.above()));
    }

    private boolean prepareVentSupport(WorldGenLevel level, BlockPos supportPos, BlockState supportState, BlockState shellState) {
        if (isVentSupport(level, supportPos, supportState)) {
            return true;
        }
        if (!canShapeVentBlock(supportState)) {
            return false;
        }
        level.setBlock(supportPos, shellState, 2);
        return isVentSupport(level, supportPos, level.getBlockState(supportPos));
    }

    private BlockState chooseVentShellState(BlockState surfaceState, BlockState supportState) {
        if (isNaturalVentMaterial(surfaceState) && surfaceState.getFluidState().isEmpty()) {
            return surfaceState.getBlock().defaultBlockState();
        }
        if (isNaturalVentMaterial(supportState) && supportState.getFluidState().isEmpty()) {
            return supportState.getBlock().defaultBlockState();
        }
        return lava ? Blocks.BASALT.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    private boolean canReplaceSpringCell(BlockState state) {
        return SpringCavityCarver.canCarveForFluid(state, fluid);
    }

    private boolean hasCarveableShaft(WorldGenLevel level, BlockPos springPos, BlockPos surfacePos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = springPos.getY() + 1; y <= surfacePos.getY(); y++) {
            cursor.set(springPos.getX(), y, springPos.getZ());
            BlockState state = level.getBlockState(cursor);
            if (!SpringCavityCarver.canCarveForFluid(state, fluid)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasStableVentWalls(WorldGenLevel level, BlockPos springPos, BlockPos surfacePos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos sidePos = new BlockPos.MutableBlockPos();
        for (int y = springPos.getY() + 1; y <= surfacePos.getY(); y++) {
            cursor.set(springPos.getX(), y, springPos.getZ());
            int sturdySides = 0;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                sidePos.set(cursor).move(direction);
                BlockState sideState = level.getBlockState(sidePos);
                if (!sideState.isAir() && !sideState.canBeReplaced(fluid) && sideState.getFluidState().isEmpty()) {
                    sturdySides++;
                }
            }
            if (sturdySides < 3) {
                return false;
            }
        }
        return true;
    }

    private boolean ensureStableVentWalls(WorldGenLevel level, BlockPos springPos, BlockPos surfacePos, BlockState shellState) {
        if (hasStableVentWalls(level, springPos, surfacePos)) {
            return true;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos sidePos = new BlockPos.MutableBlockPos();
        for (int y = springPos.getY() + 1; y <= surfacePos.getY(); y++) {
            cursor.set(springPos.getX(), y, springPos.getZ());
            int sturdySides = 0;
            int fillableSides = 0;

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                sidePos.set(cursor).move(direction);
                BlockState sideState = level.getBlockState(sidePos);
                if (isVentWallBlock(sideState)) {
                    sturdySides++;
                } else if (canShapeVentWall(sideState)) {
                    fillableSides++;
                }
            }

            if (sturdySides >= 3) {
                continue;
            }
            if (sturdySides + fillableSides < 3) {
                return false;
            }

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (sturdySides >= 3) {
                    break;
                }
                sidePos.set(cursor).move(direction);
                BlockState sideState = level.getBlockState(sidePos);
                if (!canShapeVentWall(sideState)) {
                    continue;
                }
                level.setBlock(sidePos, shellState, 2);
                sturdySides++;
            }

            if (sturdySides < 3) {
                return false;
            }
        }
        return true;
    }

    private boolean hasNearbyFluid(WorldGenLevel level, BlockPos center, int horizontalRadius, int verticalRadius, FlowingFluid targetFluid) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (level.getFluidState(cursor).getType().isSame(targetFluid)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isSurfaceCap(WorldGenLevel level, BlockPos surfacePos, BlockState surfaceState) {
        if (!surfaceState.isFaceSturdy(level, surfacePos, Direction.UP)) {
            return false;
        }
        if (!surfaceState.getFluidState().isEmpty()) {
            return false;
        }
        return isNaturalVentMaterial(surfaceState);
    }

    private boolean isVentSupport(WorldGenLevel level, BlockPos supportPos, BlockState supportState) {
        if (!supportState.isFaceSturdy(level, supportPos, Direction.UP)) {
            return false;
        }
        if (lava) {
            return supportState.is(BlockTags.BASE_STONE_OVERWORLD)
                    || supportState.is(Blocks.DEEPSLATE)
                    || supportState.is(Blocks.COBBLED_DEEPSLATE)
                    || supportState.is(Blocks.TUFF)
                    || supportState.is(Blocks.BASALT)
                    || supportState.is(Blocks.MAGMA_BLOCK)
                    || supportState.is(Blocks.BLACKSTONE);
        }
        return supportState.is(BlockTags.BASE_STONE_OVERWORLD)
                || supportState.is(Blocks.DEEPSLATE)
                || supportState.is(Blocks.COBBLED_DEEPSLATE)
                || supportState.is(Blocks.TUFF)
                || supportState.is(Blocks.CALCITE)
                || supportState.is(Blocks.DRIPSTONE_BLOCK)
                || supportState.is(Blocks.GRAVEL)
                || supportState.is(Blocks.CLAY)
                || supportState.is(Blocks.DIRT)
                || supportState.is(Blocks.COARSE_DIRT)
                || supportState.is(Blocks.ROOTED_DIRT)
                || supportState.is(Blocks.MUD)
                || supportState.is(Blocks.PACKED_MUD);
    }

    private boolean canShapeVentBlock(BlockState state) {
        return SpringCavityCarver.canCarveForFluid(state, fluid);
    }

    private boolean isVentWallBlock(BlockState state) {
        return !state.isAir() && !state.canBeReplaced(fluid) && state.getFluidState().isEmpty();
    }

    private boolean canShapeVentWall(BlockState state) {
        return SpringCavityCarver.canCarveForFluid(state, fluid);
    }

    private boolean isNaturalVentMaterial(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.DIRT)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.CALCITE)
                || state.is(Blocks.DRIPSTONE_BLOCK)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.TERRACOTTA)
                || state.is(Blocks.WHITE_TERRACOTTA)
                || state.is(Blocks.ORANGE_TERRACOTTA)
                || state.is(Blocks.YELLOW_TERRACOTTA)
                || state.is(Blocks.RED_TERRACOTTA)
                || state.is(Blocks.BROWN_TERRACOTTA)
                || state.is(Blocks.LIGHT_GRAY_TERRACOTTA)
                || state.is(Blocks.MUD)
                || state.is(Blocks.PACKED_MUD)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.BASALT)
                || state.is(Blocks.MAGMA_BLOCK);
    }
}
