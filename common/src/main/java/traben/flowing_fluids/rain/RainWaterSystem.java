package traben.flowing_fluids.rain;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import org.slf4j.Logger;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.api.FlowingFluidsAPI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Server-side rain driver that decides where rainfall should add water and delegates placement to {@link RainWaterApi}.
 */
public final class RainWaterSystem {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RainWaterApi RAIN_API = new RainWaterApiImpl();
    private static final FlowingFluidsAPI FLUIDS_API = FlowingFluidsAPI.getInstance(FlowingFluids.MOD_ID);

    private static final ConcurrentHashMap<ResourceKey<Level>, Long> lastRunTick = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ResourceKey<Level>, Long> lastCacheMaintenanceTick = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ChunkCacheKey, ChunkBiomeCache> chunkCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ResourceKey<Biome>, Float> PRECIP_MUL = new ConcurrentHashMap<>();

    private static final ConcurrentLinkedQueue<RainPlacementTask> placementQueue = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger placementQueueSize = new AtomicInteger(0);

    private static ForkJoinPool executorService = null;
    private static final long FALLBACK_CACHE_RESYNC_TICKS = 20L * 60L * 5L; // 5 minutes

    private RainWaterSystem() {
    }

    static {
        reloadConfig();
    }

    public static void reloadConfig() {
        updateBiomeMultipliers();
        initializeExecutorService();
        placementQueue.clear();
        placementQueueSize.set(0);
    }

    public static void onLevelTick(ServerLevel level) {
        if (!FlowingFluids.config.enableRainSystem) return;
        if (FlowingFluids.config.rainBaseGenerateChance <= 0.0f) return;
        if (FlowingFluids.config.rainAttemptsPerChunk <= 0) return;
        if (FlowingFluids.config.rainBaseWaterAmount <= 0) return;
        if (FlowingFluids.config.isDimensionExcluded(level)) return;
        if (!level.dimensionType().hasSkyLight()) return;

        final long now = level.getGameTime();
        performCacheMaintenanceIfNeeded(level, now);
        processPlacementQueue();

        if (!level.isRaining()) return;

        final ResourceKey<Level> key = level.dimension();
        final long last = lastRunTick.getOrDefault(key, Long.MIN_VALUE);
        final int interval = FlowingFluids.config.rainGenerateIntervalTicks;
        if (last != Long.MIN_VALUE && (now - last) < interval) return;
        lastRunTick.put(key, now);

        final RandomSource random = level.getRandom();

        final int chunkRadius = getEffectiveChunkRadius(level, FlowingFluids.config.rainChunkRadius);
        final int maxChunksPerTick = FlowingFluids.config.rainMaxChunksPerTick;

        final LongOpenHashSet uniqueChunks = new LongOpenHashSet();
        for (ServerPlayer p : level.getPlayers(__ -> true)) {
            final int pcx = p.chunkPosition().x;
            final int pcz = p.chunkPosition().z;
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    uniqueChunks.add(ChunkPos.asLong(pcx + dx, pcz + dz));
                }
            }
        }
        if (uniqueChunks.isEmpty()) return;

        long[] chunkArray = uniqueChunks.toLongArray();
        if (maxChunksPerTick > 0 && chunkArray.length > maxChunksPerTick) {
            chunkArray = Arrays.copyOf(chunkArray, maxChunksPerTick);
        }

        final int minBuildY = level.getMinBuildHeight();
        final List<ChunkProcessingData> validChunks = new ArrayList<>();
        final long currentTime = level.getGameTime();

        for (long packed : chunkArray) {
            final int cx = ChunkPos.getX(packed);
            final int cz = ChunkPos.getZ(packed);

            if (!level.hasChunk(cx, cz)) {
                continue;
            }

            ChunkBiomeCache cache = getOrCreateChunkCache(level, packed, currentTime);

            if (FlowingFluids.config.rainEnableBiomeFiltering && !cache.hasPrecipitation) {
                continue;
            }

            if (FlowingFluids.config.rainSkipInfiniteWaterBiomes && cache.isInfiniteWaterBiome) {
                continue;
            }

            validChunks.add(new ChunkProcessingData(packed, cache));
        }

        if (validChunks.isEmpty()) return;

        final boolean useMultithreading = FlowingFluids.config.rainEnableMultithreading && validChunks.size() > FlowingFluids.config.rainMultithreadThreshold;

        if (useMultithreading) {
            if (executorService == null || executorService.isShutdown()) {
                initializeExecutorService();
            }

            final long[] threadSeeds = new long[validChunks.size()];
            final RandomSource seedGenerator = level.getRandom();
            for (int i = 0; i < threadSeeds.length; i++) {
                threadSeeds[i] = seedGenerator.nextLong();
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < validChunks.size(); i++) {
                final ChunkProcessingData chunkData = validChunks.get(i);
                final long seed = threadSeeds[i];

                futures.add(CompletableFuture.runAsync(() -> {
                    final int cx = ChunkPos.getX(chunkData.packedPos);
                    final int cz = ChunkPos.getZ(chunkData.packedPos);

                    final BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
                    final BlockPos.MutableBlockPos mAbove = new BlockPos.MutableBlockPos();
                    final BlockPos.MutableBlockPos mCursor = new BlockPos.MutableBlockPos();
                    final RandomSource threadRandom = RandomSource.create(seed);

                    final int attempts = Math.max(1, Math.round(FlowingFluids.config.rainAttemptsPerChunk * chunkData.cache.precipMul));
                    spawnRainWaterInChunk(level, threadRandom, cx, cz, attempts, chunkData.cache.precipMul, mPos, mAbove, mCursor, minBuildY);
                }, executorService));
            }

            final int timeoutMs = FlowingFluids.config.rainMultithreadTimeoutMs;
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                LOGGER.debug("[{}] Rain processing timed out after {}ms", FlowingFluids.MOD_ID, timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("[{}] Error during rain processing: {}", FlowingFluids.MOD_ID, e.getMessage());
            }
        } else {
            final BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
            final BlockPos.MutableBlockPos mAbove = new BlockPos.MutableBlockPos();
            final BlockPos.MutableBlockPos mCursor = new BlockPos.MutableBlockPos();

            for (ChunkProcessingData chunkData : validChunks) {
                final int cx = ChunkPos.getX(chunkData.packedPos);
                final int cz = ChunkPos.getZ(chunkData.packedPos);

                final int attempts = Math.max(1, Math.round(FlowingFluids.config.rainAttemptsPerChunk * chunkData.cache.precipMul));
                spawnRainWaterInChunk(level, random, cx, cz, attempts, chunkData.cache.precipMul, mPos, mAbove, mCursor, minBuildY);
            }
        }
    }

    public static void onLevelUnload(ServerLevel level) {
        final ResourceKey<Level> levelKey = level.dimension();
        lastRunTick.remove(levelKey);
        lastCacheMaintenanceTick.remove(levelKey);
        chunkCache.keySet().removeIf(key -> key.level.equals(levelKey));
    }

    private static void spawnRainWaterInChunk(ServerLevel level, RandomSource random,
                                              int chunkX, int chunkZ,
                                              int attempts, float rainMul,
                                              BlockPos.MutableBlockPos mPos,
                                              BlockPos.MutableBlockPos mAbove,
                                              BlockPos.MutableBlockPos mCursor,
                                              int minBuildY) {

        final float baseChance = FlowingFluids.config.rainBaseGenerateChance * rainMul;
        final int baseWaterAmount = Math.max(1, Math.min(FlowingFluids.config.rainPlacementMaxCombinedAmount,
                FlowingFluids.config.rainBaseWaterAmount));

        final int maxQueueSize = FlowingFluids.config.rainPlacementQueueSize;
        final float congestionMultiplier = calculateQueueCongestionMultiplier(maxQueueSize);
        if (congestionMultiplier <= 0.0f) {
            return;
        }

        final float effectiveChance = baseChance * congestionMultiplier;
        if (effectiveChance <= 0.0f) {
            return;
        }

        for (int i = 0; i < attempts; i++) {
            if (random.nextFloat() > effectiveChance) continue;

            final int x = (chunkX << 4) + random.nextInt(16);
            final int z = (chunkZ << 4) + random.nextInt(16);

            if (!findRainLandingMutable(level, x, z, mPos, mAbove, minBuildY)) continue;
            if (!level.isRainingAt(mPos)) continue;
            if (level.getBiome(mPos).value().coldEnoughToSnow(mPos)) continue;

            final int groundY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            if (tryRaiseWaterOnPuddle(level, mPos, mCursor, groundY)) {
                submitRainPlacement(level, mPos, baseWaterAmount, maxQueueSize);
                continue;
            }

            final BlockState cur = level.getBlockState(mPos);
            if (cur.isAir() || cur.canBeReplaced()) {
                mAbove.set(mPos.getX(), mPos.getY() - 1, mPos.getZ());
                if (mAbove.getY() >= minBuildY && !level.getBlockState(mAbove).isAir()) {
                    submitRainPlacement(level, mPos, baseWaterAmount, maxQueueSize);
                }
            }
        }
    }

    private static void submitRainPlacement(ServerLevel level, BlockPos pos, int amount, int maxQueueSize) {
        BlockPos immutablePos = pos.immutable();
        if (!tryEnqueuePlacementTask(new RainPlacementTask(level, immutablePos, amount), maxQueueSize)) {
            LOGGER.debug("[{}] Rain placement queue full, skipping {}", FlowingFluids.MOD_ID, pos);
        }
    }

    private static boolean tryEnqueuePlacementTask(RainPlacementTask task, int maxQueueSize) {
        if (maxQueueSize <= 0) {
            placementQueue.offer(task);
            placementQueueSize.incrementAndGet();
            return true;
        }

        while (true) {
            int current = placementQueueSize.get();
            if (current >= maxQueueSize) {
                return false;
            }
            if (placementQueueSize.compareAndSet(current, current + 1)) {
                placementQueue.offer(task);
                return true;
            }
        }
    }

    private static float calculateQueueCongestionMultiplier(int maxQueueSize) {
        if (maxQueueSize <= 0) {
            return 1.0f;
        }

        final int currentSize = placementQueueSize.get();
        final float fillRatio = currentSize / (float) maxQueueSize;
        final float softCap = Math.max(0.0f, Math.min(1.0f, FlowingFluids.config.rainQueueSoftCapRatio));
        final float minimumMultiplier = Math.max(0.0f, Math.min(1.0f, FlowingFluids.config.rainQueueMinChanceMultiplier));

        if (fillRatio <= softCap) {
            return 1.0f;
        }

        final float overfill = Math.min(1.0f, (fillRatio - softCap) / Math.max(0.0001f, 1.0f - softCap));
        final float scaled = 1.0f - overfill * (1.0f - minimumMultiplier);
        return Math.max(minimumMultiplier, scaled);
    }

    private static void processPlacementQueue() {
        final int configuredLimit = FlowingFluids.config.rainPlacementQueueSize;
        final int maxProcessPerTick = configuredLimit <= 0 ? Integer.MAX_VALUE : configuredLimit;
        int processed = 0;

        final Map<ResourceKey<Level>, PlacementAggregation> aggregated = new HashMap<>();

        while (processed < maxProcessPerTick) {
            RainPlacementTask task = placementQueue.poll();
            if (task == null) {
                break;
            }
            placementQueueSize.decrementAndGet();
            mergePlacementTask(aggregated, task);
            processed++;
        }

        for (PlacementAggregation perLevel : aggregated.values()) {
            perLevel.forEach(placement -> executeWaterPlacement(placement.level(), placement.pos(), placement.amount()));
        }
    }

    private static void mergePlacementTask(Map<ResourceKey<Level>, PlacementAggregation> aggregated,
                                          RainPlacementTask task) {
        final int maxCombinedAmount = Math.max(1, FlowingFluids.config.rainPlacementMaxCombinedAmount);
        final int mergeDistance = Math.max(0, FlowingFluids.config.rainPlacementAggregationDistance);

        final ResourceKey<Level> levelKey = task.level().dimension();
        PlacementAggregation perLevel = aggregated.computeIfAbsent(levelKey, key -> new PlacementAggregation());
        perLevel.merge(task, mergeDistance, maxCombinedAmount);
    }

    private static boolean isWithinAggregationDistance(BlockPos a, BlockPos b, int distance) {
        if (distance <= 0) {
            return a.equals(b);
        }

        return Math.abs(a.getX() - b.getX()) <= distance
                && Math.abs(a.getZ() - b.getZ()) <= distance
                && Math.abs(a.getY() - b.getY()) <= 1;
    }

    private static void executeWaterPlacement(ServerLevel level, BlockPos pos, int amount) {
        RAIN_API.addRainWater(level, pos, amount);
    }

    private static int getEffectiveChunkRadius(ServerLevel level, int configuredRadius) {
        final int clampedConfigured = Math.max(0, configuredRadius);
        final int simulationDistance = level.getServer().getPlayerList().getSimulationDistance();
        if (simulationDistance > 0) {
            return Math.min(clampedConfigured, simulationDistance);
        }
        return clampedConfigured;
    }

    private static void updateBiomeMultipliers() {
        PRECIP_MUL.clear();
        PRECIP_MUL.put(Biomes.JUNGLE, FlowingFluids.config.rainPrecipJungle);
        PRECIP_MUL.put(Biomes.SWAMP, FlowingFluids.config.rainPrecipSwamp);
        PRECIP_MUL.put(Biomes.DESERT, FlowingFluids.config.rainPrecipDesert);
        PRECIP_MUL.put(Biomes.SAVANNA, FlowingFluids.config.rainPrecipSavanna);
        PRECIP_MUL.put(Biomes.PLAINS, FlowingFluids.config.rainPrecipPlains);
        PRECIP_MUL.put(Biomes.FOREST, FlowingFluids.config.rainPrecipForest);
        PRECIP_MUL.put(Biomes.TAIGA, FlowingFluids.config.rainPrecipTaiga);
    }

    private static void initializeExecutorService() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }

        int threadCount = FlowingFluids.config.rainMaxThreads;
        if (threadCount <= 0) {
            threadCount = Runtime.getRuntime().availableProcessors();
        }

        executorService = new ForkJoinPool(threadCount);
    }

    private static ChunkBiomeCache getOrCreateChunkCache(ServerLevel level, long packedChunkPos, long currentTime) {
        final ResourceKey<Level> levelKey = level.dimension();
        final ChunkCacheKey cacheKey = new ChunkCacheKey(levelKey, packedChunkPos);

        if (FlowingFluids.config.rainEnableChunkCaching) {
            ChunkBiomeCache cached = chunkCache.get(cacheKey);
            if (cached != null) {
                long cacheDuration = FlowingFluids.config.rainCacheDurationTicks;
                if (currentTime - cached.cachedTime <= cacheDuration) {
                    return cached;
                }
                chunkCache.remove(cacheKey, cached);
            }
        }

        final int cx = ChunkPos.getX(packedChunkPos);
        final int cz = ChunkPos.getZ(packedChunkPos);
        final int sampleX = (cx << 4) + 8;
        final int sampleZ = (cz << 4) + 8;
        final int sampleY = level.getHeight(Heightmap.Types.WORLD_SURFACE, sampleX, sampleZ);

        BlockPos.MutableBlockPos tempPos = new BlockPos.MutableBlockPos(sampleX, sampleY, sampleZ);
        final Holder<Biome> holder = level.getBiome(tempPos);
        final Biome biome = holder.value();
        final ResourceKey<Biome> biomeKey = holder.unwrapKey().orElse(null);

        final String biomeName = biomeKey != null ? biomeKey.location().getPath().toLowerCase() : "";

        float precipMul = 1.0f;
        if (biomeKey != null) {
            precipMul = PRECIP_MUL.getOrDefault(biomeKey, -1.0f);

            if (precipMul < 0) {
                if (biomeName.contains("desert")) {
                    precipMul = FlowingFluids.config.rainPrecipDesert;
                } else if (biomeName.contains("jungle")) {
                    precipMul = FlowingFluids.config.rainPrecipJungle;
                } else if (biomeName.contains("swamp")) {
                    precipMul = FlowingFluids.config.rainPrecipSwamp;
                } else if (biomeName.contains("savanna")) {
                    precipMul = FlowingFluids.config.rainPrecipSavanna;
                } else if (biomeName.contains("taiga")) {
                    precipMul = FlowingFluids.config.rainPrecipTaiga;
                } else if (biomeName.contains("forest")) {
                    precipMul = FlowingFluids.config.rainPrecipForest;
                } else if (biomeName.contains("plains")) {
                    precipMul = FlowingFluids.config.rainPrecipPlains;
                } else {
                    precipMul = 1.0f;
                }
            }
        }

        final boolean hasPrecipitation = biome.hasPrecipitation();
        final boolean isInfiniteWaterBiome = FLUIDS_API.doesBiomeInfiniteWaterRefill(holder);

        ChunkBiomeCache newCache = new ChunkBiomeCache(biomeKey, precipMul, hasPrecipitation, isInfiniteWaterBiome, currentTime);

        if (FlowingFluids.config.rainEnableChunkCaching) {
            chunkCache.put(cacheKey, newCache);
        }

        return newCache;
    }

    private static void performCacheMaintenanceIfNeeded(ServerLevel level, long now) {
        if (!FlowingFluids.config.rainEnableChunkCaching) {
            return;
        }

        final ResourceKey<Level> levelKey = level.dimension();
        final long lastMaintenance = lastCacheMaintenanceTick.getOrDefault(levelKey, Long.MIN_VALUE);

        if (lastMaintenance == Long.MIN_VALUE) {
            lastCacheMaintenanceTick.put(levelKey, now);
            return;
        }

        final long fallbackTicks = Math.max(FALLBACK_CACHE_RESYNC_TICKS, FlowingFluids.config.rainCacheDurationTicks);

        if ((now - lastMaintenance) >= fallbackTicks) {
            chunkCache.keySet().removeIf(key -> key.level.equals(levelKey));
            lastCacheMaintenanceTick.put(levelKey, now);
        }
    }

    private static boolean findRainLandingMutable(ServerLevel level, int x, int z,
                                                  BlockPos.MutableBlockPos outPos,
                                                  BlockPos.MutableBlockPos tmpAbove,
                                                  int minBuildY) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (surfaceY < minBuildY) return false;

        outPos.set(x, surfaceY, z);
        BlockState s = level.getBlockState(outPos);

        if (s.canBeReplaced()) {
            return true;
        }
        if (s.is(BlockTags.LEAVES)) {
            tmpAbove.set(x, surfaceY + 1, z);
            if (level.getBlockState(tmpAbove).isAir()) {
                outPos.set(tmpAbove);
                return true;
            }
        }
        if (s.isFaceSturdy(level, outPos, Direction.UP)) {
            tmpAbove.set(x, surfaceY + 1, z);
            if (level.getBlockState(tmpAbove).isAir()) {
                outPos.set(tmpAbove);
                return true;
            }
        }

        final int maxSearchDepth = FlowingFluids.config.rainMaxSurfaceSearchDepth;
        int steps = 0;
        while (outPos.getY() >= minBuildY && steps++ < maxSearchDepth) {
            final BlockState st = level.getBlockState(outPos);
            if (st.canBeReplaced()) {
                return true;
            }
            if (st.isFaceSturdy(level, outPos, Direction.UP)) {
                tmpAbove.set(outPos.getX(), outPos.getY() + 1, outPos.getZ());
                if (level.getBlockState(tmpAbove).isAir()) {
                    outPos.set(tmpAbove);
                    return true;
                }
                break;
            }
            outPos.move(0, -1, 0);
        }
        return false;
    }

    private static boolean tryRaiseWaterOnPuddle(ServerLevel level,
                                                 BlockPos.MutableBlockPos outPos,
                                                 BlockPos.MutableBlockPos cursor,
                                                 int groundY) {
        cursor.set(outPos.getX(), outPos.getY(), outPos.getZ());
        if (!level.getFluidState(cursor).isSourceOfType(Fluids.WATER)) {
            cursor.set(outPos.getX(), outPos.getY() - 1, outPos.getZ());
            if (!level.getFluidState(cursor).isSourceOfType(Fluids.WATER)) {
                return false;
            }
        }

        final int maxStackHeight = FlowingFluids.config.rainMaxWaterStackHeight;

        final BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos(cursor.getX(), cursor.getY() + 1, cursor.getZ());
        int guard = 0;
        while (level.getFluidState(above).isSourceOfType(Fluids.WATER)) {
            cursor.set(above.getX(), above.getY(), above.getZ());
            above.set(cursor.getX(), cursor.getY() + 1, cursor.getZ());
            if (++guard > maxStackHeight) break;
        }

        final int targetY = cursor.getY() + 1;
        if (targetY > groundY + maxStackHeight) return false;

        BlockState target = level.getBlockState(above.set(cursor.getX(), targetY, cursor.getZ()));
        if (!(target.isAir() || target.canBeReplaced())) return false;

        outPos.set(cursor.getX(), targetY, cursor.getZ());
        return true;
    }

    private record ChunkProcessingData(long packedPos, ChunkBiomeCache cache) {
    }

    private record ChunkCacheKey(ResourceKey<Level> level, long chunkPos) {
    }

    private record ChunkBiomeCache(ResourceKey<Biome> biomeKey, float precipMul,
                                   boolean hasPrecipitation, boolean isInfiniteWaterBiome, long cachedTime) {
    }

    private static final class AggregatedPlacement {
        private final ServerLevel level;
        private final BlockPos pos;
        private int amount;

        private AggregatedPlacement(ServerLevel level, BlockPos pos, int amount) {
            this.level = level;
            this.pos = pos;
            this.amount = amount;
        }

        private void addAmount(int delta, int maxAmount) {
            amount = Math.min(maxAmount, amount + delta);
        }

        private ServerLevel level() {
            return level;
        }

        private BlockPos pos() {
            return pos;
        }

        private int amount() {
            return amount;
        }
    }

    private static final class PlacementAggregation {
        private final Map<Long, List<AggregatedPlacement>> buckets = new HashMap<>();

        private void merge(RainPlacementTask task, int mergeDistance, int maxCombinedAmount) {
            final BlockPos pos = task.pos();
            final int chunkX = pos.getX() >> 4;
            final int chunkZ = pos.getZ() >> 4;
            final int chunkRadius = mergeDistance <= 0 ? 0 : (mergeDistance + 15) >> 4;

            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    final long key = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
                    final List<AggregatedPlacement> bucket = buckets.get(key);
                    if (bucket == null) continue;

                    for (AggregatedPlacement placement : bucket) {
                        if (isWithinAggregationDistance(placement.pos(), pos, mergeDistance)) {
                            placement.addAmount(task.amount(), maxCombinedAmount);
                            return;
                        }
                    }
                }
            }

            final long selfKey = ChunkPos.asLong(chunkX, chunkZ);
            buckets.computeIfAbsent(selfKey, k -> new ArrayList<>())
                    .add(new AggregatedPlacement(task.level(), pos, Math.min(task.amount(), maxCombinedAmount)));
        }

        private void forEach(Consumer<AggregatedPlacement> consumer) {
            for (List<AggregatedPlacement> bucket : buckets.values()) {
                for (AggregatedPlacement placement : bucket) {
                    consumer.accept(placement);
                }
            }
        }
    }

    private record RainPlacementTask(ServerLevel level, BlockPos pos, int amount) {
    }
}

