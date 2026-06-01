package traben.flowing_fluids.snow;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight player-proxied snowmelt driver.
 *
 * <p>Instead of scanning all snowy terrain, this samples a few loaded chunks around players at an interval,
 * melts exposed snow or ice in warm, bright conditions, and optionally places a small amount of water.</p>
 */
public final class SnowmeltWaterSystem {
    private static final ConcurrentHashMap<ResourceKey<Level>, Long> LAST_RUN_TICK = new ConcurrentHashMap<>();
    private static final LongOpenHashSet REUSABLE_CHUNK_COLLECTOR = new LongOpenHashSet();

    private SnowmeltWaterSystem() {
    }

    public static void onLevelTick(ServerLevel level) {
        if (!FlowingFluids.config.enableSnowmeltSystem) return;
        if (!FlowingFluids.config.enableMod || FlowingFluids.config.isDimensionExcluded(level)) return;
        if (!level.dimensionType().hasSkyLight()) return;
        if (FlowingFluids.config.snowmeltAttemptsPerChunk <= 0 || FlowingFluids.config.snowmeltBaseChance <= 0.0f) return;

        long now = level.getGameTime();
        ResourceKey<Level> key = level.dimension();
        long last = LAST_RUN_TICK.getOrDefault(key, Long.MIN_VALUE);
        int interval = Math.max(1, FlowingFluids.config.snowmeltIntervalTicks);
        if (last != Long.MIN_VALUE && now - last < interval) {
            return;
        }
        LAST_RUN_TICK.put(key, now);

        REUSABLE_CHUNK_COLLECTOR.clear();
        List<ChunkPos> playerChunks = new ArrayList<>();
        int chunkRadius = getEffectiveChunkRadius(level, FlowingFluids.config.snowmeltChunkRadius);
        for (ServerPlayer player : level.getPlayers(__ -> true)) {
            ChunkPos playerChunk = player.chunkPosition();
            playerChunks.add(playerChunk);
            int pcx = playerChunk.x;
            int pcz = playerChunk.z;
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    REUSABLE_CHUNK_COLLECTOR.add(ChunkPos.asLong(pcx + dx, pcz + dz));
                }
            }
        }
        if (REUSABLE_CHUNK_COLLECTOR.isEmpty()) return;

        long[] chunkArray = REUSABLE_CHUNK_COLLECTOR.toLongArray();
        int maxChunks = Math.max(0, FlowingFluids.config.snowmeltMaxChunksPerTick);
        if (maxChunks > 0 && chunkArray.length > maxChunks) {
            int[] playerRings = new int[chunkArray.length];
            for (int i = 0; i < chunkArray.length; i++) {
                playerRings[i] = computeNearestPlayerChunkRing(
                        ChunkPos.getX(chunkArray[i]),
                        ChunkPos.getZ(chunkArray[i]),
                        playerChunks
                );
            }
            chunkArray = selectDistributedChunkSample(chunkArray, playerRings, maxChunks, now, level.getSeed());
        }

        RandomSource random = level.getRandom();
        BlockPos.MutableBlockPos surfacePos = new BlockPos.MutableBlockPos();
        for (long packed : chunkArray) {
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            if (!level.hasChunk(cx, cz)) {
                continue;
            }
            int attempts = Math.max(1, FlowingFluids.config.snowmeltAttemptsPerChunk);
            for (int i = 0; i < attempts; i++) {
                int x = (cx << 4) + random.nextInt(16);
                int z = (cz << 4) + random.nextInt(16);
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                if (surfaceY < level.getMinBuildHeight()) {
                    continue;
                }
                surfacePos.set(x, surfaceY, z);
                tryMeltAt(level, surfacePos, random);
            }
        }
    }

    public static void onLevelUnload(ServerLevel level) {
        if (level != null) {
            LAST_RUN_TICK.remove(level.dimension());
        }
    }

    private static int getEffectiveChunkRadius(ServerLevel level, int configuredRadius) {
        int clampedConfigured = Math.max(0, configuredRadius);
        int simulationDistance = level.getServer().getPlayerList().getSimulationDistance();
        if (simulationDistance > 0) {
            return Math.min(clampedConfigured, simulationDistance);
        }
        return clampedConfigured;
    }

    private static int computeNearestPlayerChunkRing(int chunkX, int chunkZ, List<ChunkPos> playerChunks) {
        if (playerChunks.isEmpty()) {
            return 0;
        }

        int nearest = Integer.MAX_VALUE;
        for (ChunkPos playerChunk : playerChunks) {
            int ring = Math.max(Math.abs(chunkX - playerChunk.x), Math.abs(chunkZ - playerChunk.z));
            if (ring < nearest) {
                nearest = ring;
            }
        }
        return nearest == Integer.MAX_VALUE ? 0 : nearest;
    }

    static long[] selectDistributedChunkSample(long[] chunkPositions,
                                               int[] nearestPlayerRings,
                                               int maxChunks,
                                               long timeSlice,
                                               long seedSalt) {
        if (chunkPositions.length != nearestPlayerRings.length) {
            throw new IllegalArgumentException("Chunk position and ring arrays must be the same length.");
        }
        if (maxChunks <= 0 || chunkPositions.length <= maxChunks) {
            return Arrays.copyOf(chunkPositions, chunkPositions.length);
        }

        int maxRing = 0;
        for (int ring : nearestPlayerRings) {
            maxRing = Math.max(maxRing, Math.max(0, ring));
        }

        @SuppressWarnings("unchecked")
        List<ChunkSelectionData>[] buckets = new List[maxRing + 1];
        for (int i = 0; i < chunkPositions.length; i++) {
            int ring = Math.max(0, nearestPlayerRings[i]);
            if (buckets[ring] == null) {
                buckets[ring] = new ArrayList<>();
            }

            long packed = chunkPositions[i];
            int chunkX = ChunkPos.getX(packed);
            int chunkZ = ChunkPos.getZ(packed);
            buckets[ring].add(new ChunkSelectionData(
                    packed,
                    computeChunkSelectionOrder(timeSlice, chunkX, chunkZ, ring, seedSalt)
            ));
        }

        for (List<ChunkSelectionData> bucket : buckets) {
            if (bucket == null || bucket.size() <= 1) {
                continue;
            }
            bucket.sort((left, right) -> Long.compare(right.selectionOrder(), left.selectionOrder()));
        }

        int ringCount = buckets.length;
        int[] bucketIndices = new int[ringCount];
        long[] selected = new long[maxChunks];
        int selectedCount = 0;
        int startRing = chooseChunkSelectionStartRing(timeSlice, ringCount, seedSalt);

        while (selectedCount < maxChunks) {
            boolean addedAny = false;
            for (int offset = 0; offset < ringCount && selectedCount < maxChunks; offset++) {
                int ring = Math.floorMod(startRing + offset, ringCount);
                List<ChunkSelectionData> bucket = buckets[ring];
                int bucketIndex = bucketIndices[ring];
                if (bucket == null || bucketIndex >= bucket.size()) {
                    continue;
                }

                selected[selectedCount++] = bucket.get(bucketIndex).packedPos();
                bucketIndices[ring] = bucketIndex + 1;
                addedAny = true;
            }

            if (!addedAny) {
                break;
            }

            startRing = Math.floorMod(startRing - 1, ringCount);
        }

        return selectedCount == selected.length ? selected : Arrays.copyOf(selected, selectedCount);
    }

    private static long computeChunkSelectionOrder(long timeSlice, int chunkX, int chunkZ, int playerRing, long seedSalt) {
        long mixed = seedSalt;
        mixed ^= 0xD6E8FEB86659FD93L * (timeSlice + 1L);
        mixed ^= 0x94D049BB133111EBL * (chunkX + 91L);
        mixed ^= 0xBF58476D1CE4E5B9L * (chunkZ - 53L);
        mixed ^= 0x9E3779B97F4A7C15L * (playerRing + 7L);
        return mix64(mixed);
    }

    private static int chooseChunkSelectionStartRing(long timeSlice, int ringCount, long seedSalt) {
        if (ringCount <= 1) {
            return 0;
        }
        long mixed = mix64(seedSalt ^ (0xC2B2AE3D27D4EB4FL * (timeSlice + 1L)));
        return Math.floorMod((int) (mixed ^ (mixed >>> 32)), ringCount);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static void tryMeltAt(ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState state = level.getBlockState(pos);
        MeltTarget target = classifyTarget(state);
        if (target == MeltTarget.NONE) {
            return;
        }
        if (!level.canSeeSky(pos.above())) {
            return;
        }
        if (FlowingFluids.config.snowmeltDaytimeOnly && !level.isDay()) {
            return;
        }
        if (level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos.above()) < FlowingFluids.config.snowmeltMinSkyLight) {
            return;
        }
        float temperature = level.getBiome(pos).value().getBaseTemperature();
        if (temperature < FlowingFluids.config.snowmeltMinTemperature) {
            return;
        }
        float chance = FlowingFluids.config.snowmeltBaseChance;
        if (target == MeltTarget.ICE) {
            chance *= 0.8f;
        } else if (state.hasProperty(SnowLayerBlock.LAYERS)) {
            chance *= 0.9f + (state.getValue(SnowLayerBlock.LAYERS) * 0.05f);
        }
        if (random.nextFloat() >= Mth.clamp(chance, 0.0f, 1.0f)) {
            return;
        }

        switch (target) {
            case SNOW_LAYER -> meltSnowLayer(level, pos, state);
            case ICE -> meltIce(level, pos);
            default -> {
            }
        }
    }

    private static void meltSnowLayer(ServerLevel level, BlockPos pos, BlockState state) {
        int layers = state.hasProperty(SnowLayerBlock.LAYERS) ? state.getValue(SnowLayerBlock.LAYERS) : 1;
        if (layers > 1) {
            level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers - 1), 3);
            return;
        }

        level.removeBlock(pos, false);
        if (FlowingFluids.config.snowmeltPlacesWater) {
            placeMeltWater(level, pos, 1);
        }
    }

    private static void meltIce(ServerLevel level, BlockPos pos) {
        if (FlowingFluids.config.snowmeltPlacesWater) {
            if (!placeMeltWater(level, pos, Math.max(1, FlowingFluids.config.snowmeltWaterAmount))) {
                level.removeBlock(pos, false);
            }
        } else {
            level.removeBlock(pos, false);
        }
    }

    private static boolean placeMeltWater(ServerLevel level, BlockPos pos, int amount) {
        int clampedAmount = Mth.clamp(amount, 1, 8);
        if (!FFFluidUtils.setFluidStateAtPosToNewAmount(level, pos, Fluids.WATER, clampedAmount)) {
            BlockPos below = pos.below();
            if (!FFFluidUtils.setFluidStateAtPosToNewAmount(level, below, Fluids.WATER, clampedAmount)) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos side = pos.relative(dir);
                    if (FFFluidUtils.setFluidStateAtPosToNewAmount(level, side, Fluids.WATER, clampedAmount)) {
                        AdaptiveTickScheduler.scheduleFluidTick(level, side, Fluids.WATER, 1);
                        AdaptiveTickScheduler.markFlowActive(level, side, 8);
                        return true;
                    }
                }
                return false;
            }
            AdaptiveTickScheduler.scheduleFluidTick(level, below, Fluids.WATER, 1);
            AdaptiveTickScheduler.markFlowActive(level, below, 8);
            return true;
        }
        AdaptiveTickScheduler.scheduleFluidTick(level, pos, Fluids.WATER, 1);
        AdaptiveTickScheduler.markFlowActive(level, pos, 8);
        return true;
    }

    private static MeltTarget classifyTarget(BlockState state) {
        if (state.is(Blocks.SNOW) && state.hasProperty(SnowLayerBlock.LAYERS)) {
            return MeltTarget.SNOW_LAYER;
        }
        if (state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE)) {
            return MeltTarget.ICE;
        }
        return MeltTarget.NONE;
    }

    private enum MeltTarget {
        NONE,
        SNOW_LAYER,
        ICE
    }

    private record ChunkSelectionData(long packedPos, long selectionOrder) {
    }
}
