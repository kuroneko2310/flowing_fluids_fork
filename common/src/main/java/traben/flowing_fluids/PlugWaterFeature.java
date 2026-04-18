package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class PlugWaterFeature {

    private static final Direction[] DIRS = {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.DOWN
    };
    private static final int OCEAN_SURFACE_SKIP_MARGIN = 1;
    private static final int OCEAN_AIR_POCKET_SKIP_MARGIN = 1;

    private static void collectSourceBlocks(LevelAccessor level, ChunkAccess chunkAccess,
                                            int x1, int y1, int z1, int x2, int y2, int z2,
                                            LongOpenHashSet set, int seaLevel) {
        int minSection = #if MC>MC_21 chunkAccess.getMinSectionY() #else chunkAccess.getMinSection() #endif;
        int maxSection = #if MC>MC_21 chunkAccess.getMaxSectionY() #else chunkAccess.getMaxSection() #endif;

        for (int i = minSection; i < maxSection; i++) {
            LevelChunkSection levelChunkSection = chunkAccess.getSection(chunkAccess.getSectionIndexFromSectionY(i));
            if (!levelChunkSection.maybeHas(PlugWaterFeature::isFluidSource)) {
                continue;
            }

            BlockPos sectionOrigin = SectionPos.of(chunkAccess.getPos(), i).origin();
            BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

            for (int y = y1; y < y2; ++y) {
                for (int z = z1; z < z2; ++z) {
                    for (int x = x1; x < x2; ++x) {
                        BlockState blockState = levelChunkSection.getBlockState(x, y, z);
                        if (!isFluidSource(blockState)) {
                            continue;
                        }
                        mutableBlockPos.setWithOffset(sectionOrigin, x, y, z);
                        if (!shouldIgnoreOpenWaterSource(level, mutableBlockPos, seaLevel)) {
                            set.add(mutableBlockPos.asLong());
                        }
                    }
                }
            }
        }
    }

    public static void processChunk(LevelAccessor level, ChunkPos chunk, ChunkAccess chunkAccess) {
        int seaLevel = FFFluidUtils.seaLevel(level);
        LongOpenHashSet sources = new LongOpenHashSet();

        collectSourceBlocks(level, chunkAccess, 0, 0, 0, 16, 16, 16, sources, seaLevel);
        if (level.hasChunk(chunk.x - 1, chunk.z)) {
            collectSourceBlocks(level, level.getChunk(chunk.x - 1, chunk.z), 15, 0, 0, 16, 16, 16, sources, seaLevel);
        }
        if (level.hasChunk(chunk.x + 1, chunk.z)) {
            collectSourceBlocks(level, level.getChunk(chunk.x + 1, chunk.z), 0, 0, 0, 1, 16, 16, sources, seaLevel);
        }
        if (level.hasChunk(chunk.x, chunk.z - 1)) {
            collectSourceBlocks(level, level.getChunk(chunk.x, chunk.z - 1), 0, 0, 15, 16, 16, 16, sources, seaLevel);
        }
        if (level.hasChunk(chunk.x, chunk.z + 1)) {
            collectSourceBlocks(level, level.getChunk(chunk.x, chunk.z + 1), 0, 0, 0, 16, 16, 1, sources, seaLevel);
        }

        if (sources.isEmpty()) {
            return;
        }

        int minx = chunk.getMinBlockX();
        int minz = chunk.getMinBlockZ();
        int maxx = chunk.getMaxBlockX();
        int maxz = chunk.getMaxBlockZ();
        int seaFloor = seaLevel - 5;

        LongOpenHashSet alreadyPlugged = new LongOpenHashSet();
        long[] pendingFillPositions = new long[DIRS.length];

        for (LongIterator iterator = sources.iterator(); iterator.hasNext(); ) {
            long sourceKey = iterator.nextLong();
            int sourceX = BlockPos.getX(sourceKey);
            int sourceY = BlockPos.getY(sourceKey);
            int sourceZ = BlockPos.getZ(sourceKey);

            boolean neighbourWater = false;
            int pendingCount = 0;

            for (Direction dir : DIRS) {
                long candidate = BlockPos.asLong(
                        sourceX + dir.getStepX(),
                        sourceY + dir.getStepY(),
                        sourceZ + dir.getStepZ()
                );
                int result = evaluateCandidate(level, chunkAccess, sources, alreadyPlugged, candidate,
                        minx, maxx, minz, maxz, seaLevel);
                if (result == 1) {
                    neighbourWater = true;
                } else if (result == 2) {
                    pendingFillPositions[pendingCount++] = candidate;
                }
            }

            if (neighbourWater && pendingCount > 0) {
                for (int i = 0; i < pendingCount; i++) {
                    fillBlock(chunkAccess, BlockPos.of(pendingFillPositions[i]), seaFloor);
                }
            }
        }
    }

    private static boolean isFluidSource(BlockState state) {
        var fluid = state.getFluidState();
        if (fluid.isEmpty() || !fluid.isSource()) return false;

        return FlowingFluids.config.isFluidAllowed(fluid);
    }

    private static int evaluateCandidate(LevelAccessor level, ChunkAccess chunkAccess,
                                         LongOpenHashSet sources, LongOpenHashSet alreadyPlugged,
                                         long candidate, int minx, int maxx, int minz, int maxz, int seaLevel) {
        int x = BlockPos.getX(candidate);
        int z = BlockPos.getZ(candidate);
        if (x < minx || x > maxx || z < minz || z > maxz) {
            return 1;
        }
        if (sources.contains(candidate)) {
            return 1;
        }
        if (alreadyPlugged.contains(candidate)) {
            return 0;
        }

        BlockPos pos = BlockPos.of(candidate);
        BlockState blockState = chunkAccess.getBlockState(pos);
        if (!blockState.isAir() || !blockState.getFluidState().isEmpty()) {
            return 0;
        }
        if (shouldSkipNaturalAirPocket(level, pos, seaLevel)) {
            return 0;
        }

        alreadyPlugged.add(candidate);
        return 2;
    }

    static boolean shouldIgnoreOpenWaterSource(LevelAccessor level, BlockPos pos, int seaLevel) {
        var biome = level.getBiome(pos);
        boolean broadWaterBiome = FFFluidUtils.isOceanBiome(biome) || FFFluidUtils.isBeachBiome(biome);
        return shouldIgnoreOpenWaterSource(broadWaterBiome, pos.getY(), seaLevel);
    }

    static boolean shouldSkipNaturalAirPocket(LevelAccessor level, BlockPos pos, int seaLevel) {
        var biome = level.getBiome(pos);
        boolean broadWaterBiome = FFFluidUtils.isOceanBiome(biome) || FFFluidUtils.isBeachBiome(biome);
        return shouldSkipNaturalAirPocket(broadWaterBiome, pos.getY(), seaLevel);
    }

    static boolean shouldIgnoreOpenWaterSource(boolean broadWaterBiome, int y, int seaLevel) {
        return broadWaterBiome && y >= seaLevel - OCEAN_SURFACE_SKIP_MARGIN;
    }

    static boolean shouldSkipNaturalAirPocket(boolean broadWaterBiome, int y, int seaLevel) {
        return broadWaterBiome && y >= seaLevel - OCEAN_AIR_POCKET_SKIP_MARGIN;
    }

    private static void fillBlock(final ChunkAccess chunk, BlockPos pos, int seaLevel) {
        BlockState blockState = choosePlugBlock(chunk.getNoiseBiome(pos.getX(), pos.getY(), pos.getZ()), pos.getY(), seaLevel);
        if (FlowingFluids.config.announceWorldGenActions) {
            FlowingFluids.info("placed block during world gen: " + blockState + " at /tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        }

        FlowingFluids.waterPluggedThisSession++;
        chunk.setBlockState(pos, blockState,
                #if MC>=MC_21_5 0 // no updates pls
                #else false #endif
        );
    }

    static BlockState choosePlugBlock(Holder<Biome> biome, int y, int seaLevel) {
        if (biome.is(BiomeTags.IS_NETHER)) {
            return Blocks.NETHERRACK.defaultBlockState();
        }
        if (biome.is(BiomeTags.IS_END)) {
            return Blocks.END_STONE.defaultBlockState();
        }
        if (y < 0) {
            return Blocks.DEEPSLATE.defaultBlockState();
        }
        if (y < seaLevel) {
            if (FFFluidUtils.isRiverBiome(biome)) {
                return Blocks.GRAVEL.defaultBlockState();
            }
            return Blocks.STONE.defaultBlockState();
        }
        if (biome.is(BiomeTags.HAS_VILLAGE_DESERT)
                || FFFluidUtils.isBeachBiome(biome)
                || FFFluidUtils.isOceanBiome(biome)) {
            return Blocks.SAND.defaultBlockState();
        }
        return Blocks.MUD.defaultBlockState();
    }
}
