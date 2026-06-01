package traben.flowing_fluids.rain;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import org.slf4j.Logger;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.FlowingFluidsPlatform;
import traben.flowing_fluids.api.FlowingFluidsAPI;
import traben.flowing_fluids.flood.FloodEventSystem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private static final ConcurrentHashMap<String, ChunkBiomeCache> chunkCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ResourceKey<Biome>, Float> PRECIP_MUL = new ConcurrentHashMap<>();
    private static final RainWetnessCache WETNESS_CACHE = new RainWetnessCache();
    private static final ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<Long, Long>> activeRainChunks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<UUID, Long>> lastPlayerRainChunks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ResourceKey<Level>, Boolean> lastRainState = new ConcurrentHashMap<>();

    private static final ConcurrentLinkedQueue<RainPlacementTask> placementQueue = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger placementQueueSize = new AtomicInteger(0);

    private static final LongOpenHashSet reusableChunkCollector = new LongOpenHashSet();

    private static final TagKey<Block> RAIN_ABSORPTION_HIGH = TagKey.create(Registries.BLOCK, FFFluidUtils.res(FlowingFluids.MOD_ID, "rain_absorption_high"));
    private static final TagKey<Block> RAIN_ABSORPTION_MEDIUM = TagKey.create(Registries.BLOCK, FFFluidUtils.res(FlowingFluids.MOD_ID, "rain_absorption_medium"));
    private static final TagKey<Block> RAIN_ABSORPTION_LOW = TagKey.create(Registries.BLOCK, FFFluidUtils.res(FlowingFluids.MOD_ID, "rain_absorption_low"));
    private static final TagKey<Block> RAIN_ABSORPTION_IMPERVIOUS = TagKey.create(Registries.BLOCK, FFFluidUtils.res(FlowingFluids.MOD_ID, "rain_absorption_impervious"));

    private static final int[][] SAMPLE_DIRECTIONS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1},
            {1, 1},
            {1, -1},
            {-1, 1},
            {-1, -1}
    };
    private static final int MIN_PLACEMENT_QUEUE_CAPACITY = 1;
    private static final int MAX_RAIN_PLACEMENTS_PROCESSED_PER_TICK = 256;

    private static final long FALLBACK_CACHE_RESYNC_TICKS = 20L * 60L * 5L;
    private static final long MIN_WAKE_TTL_TICKS = 200L;

    private RainWaterSystem() {
    }

    static {
        reloadConfig();
    }

    public static void reloadConfig() {
        updateBiomeMultipliers();
        placementQueue.clear();
        placementQueueSize.set(0);
        WETNESS_CACHE.clearAll();
        activeRainChunks.clear();
        lastPlayerRainChunks.clear();
        lastRainState.clear();

        if (!FlowingFluids.config.rainEnableChunkCaching) {
            chunkCache.clear();
            lastCacheMaintenanceTick.clear();
        }
    }

    public static void onLevelTick(ServerLevel level) {
        if (!FlowingFluids.config.enableRainSystem) return;
        if (FlowingFluids.config.rainBaseGenerateChance <= 0.0f) return;
        if (FlowingFluids.config.rainAttemptsPerChunk <= 0) return;
        if (FlowingFluids.config.rainBaseWaterAmount <= 0) return;
        if (FlowingFluids.config.isDimensionExcluded(level)) return;
        if (!level.dimensionType().hasSkyLight()) return;

        final long now = level.getGameTime();
        final ResourceKey<Level> key = level.dimension();
        performCacheMaintenanceIfNeeded(level, now);
        final List<ChunkPos> playerChunks = new ArrayList<>();
        refreshWakeChunks(level, key, now, playerChunks);
        if (!level.isRaining()) {
            purgeQueuedPlacements(key);
            clearWakeState(key);
            return;
        }
        processPlacementQueue();

        final long last = lastRunTick.getOrDefault(key, Long.MIN_VALUE);
        final int interval = FlowingFluids.config.rainGenerateIntervalTicks;
        if (last != Long.MIN_VALUE && (now - last) < interval) return;
        lastRunTick.put(key, now);

        final RandomSource random = level.getRandom();
        final int maxChunksPerTick = FlowingFluids.config.rainMaxChunksPerTick;
        long[] chunkArray = collectActiveRainChunks(key, now);
        if (chunkArray.length == 0) return;
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
            validChunks.add(new ChunkProcessingData(
                    packed,
                    cache,
                    computeNearestPlayerChunkRing(cx, cz, playerChunks)
            ));
        }

        if (validChunks.isEmpty()) return;

        final List<ChunkProcessingData> chunksToProcess;
        if (maxChunksPerTick > 0 && validChunks.size() > maxChunksPerTick) {
            // Rain already only exists inside the loaded simulation window, so when we must cap work,
            // spread the sample across player rings instead of truncating to a stable near-player subset.
            chunksToProcess = selectChunksForTick(validChunks, maxChunksPerTick, currentTime, level.getSeed());
        } else {
            chunksToProcess = validChunks;
        }

        final BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos mAbove = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos mCursor = new BlockPos.MutableBlockPos();

        for (ChunkProcessingData chunkData : chunksToProcess) {
            final int cx = ChunkPos.getX(chunkData.packedPos);
            final int cz = ChunkPos.getZ(chunkData.packedPos);
            final RainIntensityStage intensityStage = RainMath.chooseRainIntensityStage(
                    level.isThundering(),
                    currentTime / Math.max(1L, FlowingFluids.config.rainGenerateIntervalTicks),
                    cx,
                    cz,
                    level.getSeed()
            );
            final float intensityMultiplier = getRainIntensityMultiplier(intensityStage);
            final int attempts = Math.max(1, Math.round(FlowingFluids.config.rainAttemptsPerChunk * chunkData.cache.precipMul * intensityMultiplier));
            spawnRainWaterInChunk(level, random, cx, cz, attempts, chunkData.cache.precipMul, intensityStage,
                    intensityMultiplier, currentTime, mPos, mAbove, mCursor, minBuildY);
        }
    }

    public static void onLevelUnload(ServerLevel level) {
        final ResourceKey<Level> levelKey = level.dimension();
        lastRunTick.remove(levelKey);
        lastCacheMaintenanceTick.remove(levelKey);
        clearChunkCacheForLevel(levelKey);
        WETNESS_CACHE.clearLevel(levelKey);
        purgeQueuedPlacements(levelKey);
        clearWakeState(levelKey);

        traben.flowing_fluids.AdaptiveTickScheduler.clearDimension(level);
        traben.flowing_fluids.FluidSpatialGrid.clearDimension(level);
        traben.flowing_fluids.ChunkLocalSlopeCache.clearDimension(level);
        traben.flowing_fluids.FluidTickBuffer.clearDimension(level);
        traben.flowing_fluids.water.WaterPressureSystem.onLevelUnload(level);
    }

    public static String describeRuntimeState(ServerLevel level) {
        ResourceKey<Level> levelKey = level.dimension();
        long wetnessEntries = WETNESS_CACHE.countLevel(levelKey);
        long cachedChunks = countChunkCacheEntries(levelKey);
        BlockPos referencePos = level.players().isEmpty() ? level.getSharedSpawnPos() : level.players().get(0).blockPosition();
        RainIntensityStage stage = RainMath.chooseRainIntensityStage(
                level.isThundering(),
                level.getGameTime() / Math.max(1L, FlowingFluids.config.rainGenerateIntervalTicks),
                referencePos.getX() >> 4,
                referencePos.getZ() >> 4,
                level.getSeed()
        );
        return "Rain runtime"
                + "\nWeather: raining=" + level.isRaining() + ", thundering=" + level.isThundering()
                + "\nIntensity stage: " + stage.name().toLowerCase(Locale.ROOT)
                + "\nPlacement queue: " + placementQueueSize.get() + "/" + FlowingFluids.config.rainPlacementQueueSize
                + "\nActive wake chunks: " + countActiveWakeChunks(levelKey, level.getGameTime())
                + "\nWetness samples: " + wetnessEntries
                + "\nChunk cache entries: " + cachedChunks
                + "\nCache enabled=" + FlowingFluids.config.rainEnableChunkCaching
                + " / placement mode=single-threaded"
                + " / wake mode=event-driven";
    }

    public static String inspectRainAt(ServerLevel level, BlockPos probePos) {
        if (!FlowingFluids.config.enableRainSystem) {
            return "Rain system is disabled.";
        }

        int x = probePos.getX();
        int z = probePos.getZ();
        int minBuildY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos landingPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos abovePos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        RainIntensityStage stage = RainMath.chooseRainIntensityStage(
                level.isThundering(),
                level.getGameTime() / Math.max(1L, FlowingFluids.config.rainGenerateIntervalTicks),
                x >> 4,
                z >> 4,
                level.getSeed()
        );
        float intensityMultiplier = getRainIntensityMultiplier(stage);
        int baseAmount = Math.max(1, Math.round(Math.max(1, FlowingFluids.config.rainBaseWaterAmount) * intensityMultiplier));

        if (!findRainLandingMutable(level, x, z, landingPos, abovePos, minBuildY)) {
            return "No valid rain landing point at "
                    + x + ", " + probePos.getY() + ", " + z
                    + "\nStage: " + stage.name().toLowerCase(Locale.ROOT)
                    + " / Raining here: " + level.isRainingAt(probePos);
        }

        int groundY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        boolean raisedFromPuddle = FlowingFluids.config.rainFillsWaterHigherV2
                && tryRaiseWaterOnPuddle(level, landingPos, cursor, groundY);
        RainSurfaceContext context = buildSurfaceContext(level, landingPos, baseAmount, stage,
                level.getGameTime(), minBuildY, raisedFromPuddle, cursor);

        return "Rain probe"
                + "\nProbe: " + x + ", " + probePos.getY() + ", " + z
                + "\nLanding: " + landingPos.getX() + ", " + landingPos.getY() + ", " + landingPos.getZ()
                + "\nBlock: " + BuiltInRegistries.BLOCK.getKey(context.groundBlock())
                + "\nAbsorption: " + context.absorptionTier().name().toLowerCase(Locale.ROOT)
                + "\nWetness: " + String.format(Locale.ROOT, "%.2f", context.wetness())
                + "\nIntensity: " + stage.name().toLowerCase(Locale.ROOT) + " x" + String.format(Locale.ROOT, "%.2f", intensityMultiplier)
                + "\nUpstream bonus: " + String.format(Locale.ROOT, "%.2f", context.upstreamBonus())
                + "\nCatchment boost: " + String.format(Locale.ROOT, "%.2f", context.catchmentBoost())
                + "\nCandidate amount: " + context.candidateAmount()
                + "\nEffective amount: " + context.effectiveAmount()
                + "\nAbsorbed wetness add: " + String.format(Locale.ROOT, "%.2f", context.absorbedWetness())
                + "\nRaised from puddle: " + raisedFromPuddle
                + "\nRaining here: " + level.isRainingAt(landingPos);
    }

    private static void purgeQueuedPlacements(ResourceKey<Level> levelKey) {
        AtomicInteger removed = new AtomicInteger();
        placementQueue.removeIf(task -> {
            boolean matchesLevel = task.level().dimension().equals(levelKey);
            if (matchesLevel) {
                removed.incrementAndGet();
            }
            return matchesLevel;
        });

        if (removed.get() > 0) {
            placementQueueSize.updateAndGet(current -> Math.max(0, current - removed.get()));
        }
    }

    private static void spawnRainWaterInChunk(ServerLevel level, RandomSource random,
                                              int chunkX, int chunkZ,
                                              int attempts, float rainMul,
                                              RainIntensityStage intensityStage,
                                              float intensityMultiplier,
                                              long currentTime,
                                              BlockPos.MutableBlockPos mPos,
                                              BlockPos.MutableBlockPos mAbove,
                                              BlockPos.MutableBlockPos mCursor,
                                              int minBuildY) {

        final float baseChance = FlowingFluids.config.rainBaseGenerateChance * rainMul;
        final int rainBaseAmount = Math.max(1, FlowingFluids.config.rainBaseWaterAmount);
        final int baseWaterAmount = Math.max(1, Math.round(rainBaseAmount * intensityMultiplier));

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
            final boolean raisedFromPuddle = FlowingFluids.config.rainFillsWaterHigherV2
                    && tryRaiseWaterOnPuddle(level, mPos, mCursor, groundY);
            final RainSurfaceContext context = buildSurfaceContext(level, mPos, baseWaterAmount, intensityStage,
                    currentTime, minBuildY, raisedFromPuddle, mCursor);

            if (!raisedFromPuddle && context.absorbedWetness() > 0.0f) {
                WETNESS_CACHE.addWetness(level.dimension(), context.groundPos(), context.absorbedWetness(), currentTime);
            }

            if (context.effectiveAmount() <= 0) {
                continue;
            }

            final int adjustedAmount = FloodEventSystem.adjustRainWaterAmount(level, mPos, context.effectiveAmount());
            if (adjustedAmount <= 0) {
                continue;
            }

            if (FlowingFluidsPlatform.tryAbsorbRainWater(level, mPos, adjustedAmount)) {
                continue;
            }

            if (raisedFromPuddle) {
                submitRainPlacement(level, mPos, adjustedAmount, maxQueueSize);
                continue;
            }

            final BlockState cur = level.getBlockState(mPos);
            if (cur.isAir() || cur.canBeReplaced()) {
                mAbove.set(mPos.getX(), mPos.getY() - 1, mPos.getZ());
                if (mAbove.getY() >= minBuildY && !level.getBlockState(mAbove).isAir()) {
                    submitRainPlacement(level, mPos, adjustedAmount, maxQueueSize);
                }
            }
        }
    }

    private static RainSurfaceContext buildSurfaceContext(ServerLevel level, BlockPos placementPos,
                                                          int baseWaterAmount,
                                                          RainIntensityStage intensityStage,
                                                          long currentTime,
                                                          int minBuildY,
                                                          boolean existingPuddle,
                                                          BlockPos.MutableBlockPos cursor) {
        BlockPos groundPos = resolveGroundReferencePos(level, placementPos, cursor, minBuildY);
        BlockState groundState = groundPos.getY() >= minBuildY ? level.getBlockState(groundPos) : Blocks.AIR.defaultBlockState();
        AbsorptionTier absorptionTier = resolveAbsorptionTier(groundState);
        float wetness = existingPuddle ? 1.0f : WETNESS_CACHE.getWetness(level.dimension(), groundPos, currentTime);
        float upstreamBonus = computeUpstreamBonus(level, placementPos.getX(), placementPos.getZ(), groundPos.getY());
        float catchmentBoost = computeCatchmentBoost(level, placementPos.getX(), placementPos.getZ());
        int candidateAmount = computeCandidateAmount(baseWaterAmount, upstreamBonus, catchmentBoost);
        int effectiveAmount = existingPuddle ? candidateAmount
                : RainMath.computeSurfaceWaterAmount(candidateAmount, absorptionTier.absorptionCoefficient(), wetness);
        float effectiveAbsorption = existingPuddle ? 0.0f
                : Mth.clamp(absorptionTier.absorptionCoefficient() * (1.0f - wetness), 0.0f, 1.0f);
        float absorbedWetness = existingPuddle ? 0.0f
                : Mth.clamp((candidateAmount * effectiveAbsorption) / 6.0f, 0.0f, 1.0f);

        return new RainSurfaceContext(
                groundPos,
                groundState.getBlock(),
                absorptionTier,
                wetness,
                intensityStage,
                upstreamBonus,
                catchmentBoost,
                candidateAmount,
                effectiveAmount,
                absorbedWetness
        );
    }

    private static BlockPos resolveGroundReferencePos(ServerLevel level, BlockPos placementPos,
                                                      BlockPos.MutableBlockPos cursor, int minBuildY) {
        cursor.set(placementPos.getX(), placementPos.getY() - 1, placementPos.getZ());
        int guard = 0;
        int maxDepth = Math.max(6, FlowingFluids.config.rainMaxSurfaceSearchDepth + FlowingFluids.config.rainMaxWaterStackHeight + 4);

        while (cursor.getY() >= minBuildY && guard++ < maxDepth) {
            BlockState state = level.getBlockState(cursor);
            if (level.getFluidState(cursor).isSourceOfType(Fluids.WATER)) {
                cursor.move(0, -1, 0);
                continue;
            }
            if (!state.isAir() && !state.canBeReplaced()) {
                return cursor.immutable();
            }
            cursor.move(0, -1, 0);
        }
        return new BlockPos(placementPos.getX(), Math.max(minBuildY, placementPos.getY() - 1), placementPos.getZ());
    }

    private static AbsorptionTier resolveAbsorptionTier(BlockState state) {
        if (state.is(RAIN_ABSORPTION_HIGH)) {
            return AbsorptionTier.HIGH;
        }
        if (state.is(RAIN_ABSORPTION_IMPERVIOUS)) {
            return AbsorptionTier.IMPERVIOUS;
        }
        if (state.is(RAIN_ABSORPTION_LOW)) {
            return AbsorptionTier.LOW;
        }
        if (state.is(RAIN_ABSORPTION_MEDIUM)) {
            return AbsorptionTier.MEDIUM;
        }
        return AbsorptionTier.MEDIUM;
    }

    private static int computeCandidateAmount(int baseWaterAmount, float upstreamBonus, float catchmentBoost) {
        float combined = baseWaterAmount * upstreamBonus * catchmentBoost;
        return Mth.clamp(Math.max(1, Math.round(combined)), 1, Math.max(1, FlowingFluids.config.rainPlacementMaxCombinedAmount));
    }

    private static float computeUpstreamBonus(ServerLevel level, int x, int z, int referenceSurfaceY) {
        int searchRadius = Math.max(0, FlowingFluids.config.rainUpstreamSearchRadius);
        if (searchRadius <= 0) {
            return 1.0f;
        }

        int uphillHits = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int[] offset : SAMPLE_DIRECTIONS) {
            int sampleX = x + offset[0] * searchRadius;
            int sampleZ = z + offset[1] * searchRadius;
            if (!level.hasChunk(sampleX >> 4, sampleZ >> 4)) {
                continue;
            }

            int sampleSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, sampleX, sampleZ) - 1;
            if (sampleSurfaceY <= referenceSurfaceY) {
                continue;
            }

            cursor.set(sampleX, sampleSurfaceY + 1, sampleZ);
            if (level.canSeeSky(cursor)) {
                uphillHits++;
            }
        }

        if (uphillHits <= 0) {
            return 1.0f;
        }

        float uphillRatio = uphillHits / (float) SAMPLE_DIRECTIONS.length;
        float maxBoost = Math.max(1.0f, FlowingFluids.config.rainUpstreamMaxBoost);
        return Mth.clamp(1.0f + uphillRatio * (maxBoost - 1.0f), 1.0f, maxBoost);
    }

    private static float computeCatchmentBoost(ServerLevel level, int x, int z) {
        int radius = Math.max(0, FlowingFluids.config.rainCatchmentRadius);
        if (radius <= 0) {
            return 1.0f;
        }

        int openSky = 0;
        int sampleCount = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int sampleX = x + dx;
                int sampleZ = z + dz;
                if (!level.hasChunk(sampleX >> 4, sampleZ >> 4)) {
                    continue;
                }

                int sampleSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, sampleX, sampleZ);
                cursor.set(sampleX, sampleSurfaceY, sampleZ);
                if (level.canSeeSky(cursor)) {
                    openSky++;
                }
                sampleCount++;
            }
        }

        if (sampleCount <= 0) {
            return 1.0f;
        }

        float openSkyRatio = openSky / (float) sampleCount;
        float maxBoost = Math.max(1.0f, FlowingFluids.config.rainCatchmentMaxBoost);
        return Mth.clamp(1.0f + openSkyRatio * (maxBoost - 1.0f), 1.0f, maxBoost);
    }

    private static float getRainIntensityMultiplier(RainIntensityStage stage) {
        return switch (stage) {
            case DRIZZLE -> Math.max(0.1f, FlowingFluids.config.rainIntensityDrizzleMultiplier);
            case STEADY -> Math.max(0.1f, FlowingFluids.config.rainIntensitySteadyMultiplier);
            case HEAVY -> Math.max(0.1f, FlowingFluids.config.rainIntensityHeavyMultiplier);
            case THUNDERSTORM -> Math.max(0.1f, FlowingFluids.config.rainIntensityThunderstormMultiplier);
        };
    }

    private static void submitRainPlacement(ServerLevel level, BlockPos pos, int amount, int maxQueueSize) {
        BlockPos immutablePos = pos.immutable();
        if (!tryEnqueuePlacementTask(new RainPlacementTask(level, immutablePos, amount), maxQueueSize)) {
            LOGGER.debug("[{}] Rain placement queue full, skipping {}", FlowingFluids.MOD_ID, pos);
        }
    }

    private static boolean tryEnqueuePlacementTask(RainPlacementTask task, int maxQueueSize) {
        final int effectiveMaxQueueSize = Math.max(MIN_PLACEMENT_QUEUE_CAPACITY, maxQueueSize);

        while (true) {
            int current = placementQueueSize.get();
            if (current >= effectiveMaxQueueSize) {
                return false;
            }
            if (placementQueueSize.compareAndSet(current, current + 1)) {
                placementQueue.offer(task);
                return true;
            }
        }
    }

    private static float calculateQueueCongestionMultiplier(int maxQueueSize) {
        final int effectiveMaxQueueSize = Math.max(MIN_PLACEMENT_QUEUE_CAPACITY, maxQueueSize);

        final int currentSize = placementQueueSize.get();
        final float fillRatio = currentSize / (float) effectiveMaxQueueSize;
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
        if (placementQueue.isEmpty()) {
            return;
        }

        final int configuredLimit = Math.max(MIN_PLACEMENT_QUEUE_CAPACITY, FlowingFluids.config.rainPlacementQueueSize);
        final int maxProcessPerTick = Math.min(configuredLimit, MAX_RAIN_PLACEMENTS_PROCESSED_PER_TICK);
        int processed = 0;
        final Map<ResourceKey<Level>, PlacementAggregation> aggregated = new HashMap<>();

        while (processed < maxProcessPerTick) {
            RainPlacementTask task = placementQueue.poll();
            if (task == null) {
                break;
            }
            placementQueueSize.decrementAndGet();
            if (!shouldExecuteQueuedRainPlacement(task.level(), task.pos())) {
                processed++;
                continue;
            }
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
        if (!shouldExecuteQueuedRainPlacement(level, pos)) {
            return;
        }
        RAIN_API.addRainWater(level, pos, amount);
    }

    static boolean shouldExecuteQueuedRainPlacement(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        if (!FlowingFluids.config.enableRainSystem
                || FlowingFluids.config.isDimensionExcluded(level)
                || !level.dimensionType().hasSkyLight()) {
            return false;
        }
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        if (!isWakeChunkActive(level.dimension(), ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), level.getGameTime())) {
            return false;
        }

        BlockPos rainCheckPos = pos.above();
        return traben.flowing_fluids.FluidRegressionLogic.shouldAllowRainDrivenWaterPlacement(
                level.isRaining(),
                level.isRainingAt(rainCheckPos),
                level.getBiome(rainCheckPos).value().coldEnoughToSnow(rainCheckPos)
        );
    }

    private static int getEffectiveChunkRadius(ServerLevel level, int configuredRadius) {
        final int clampedConfigured = Math.max(0, configuredRadius);
        final int simulationDistance = level.getServer().getPlayerList().getSimulationDistance();
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

    private static List<ChunkProcessingData> selectChunksForTick(List<ChunkProcessingData> validChunks,
                                                                 int maxChunksPerTick,
                                                                 long currentTime,
                                                                 long seedSalt) {
        long[] packedPositions = new long[validChunks.size()];
        int[] playerRings = new int[validChunks.size()];
        Map<Long, ChunkProcessingData> byPacked = new HashMap<>(validChunks.size());

        for (int i = 0; i < validChunks.size(); i++) {
            ChunkProcessingData chunkData = validChunks.get(i);
            packedPositions[i] = chunkData.packedPos();
            playerRings[i] = chunkData.nearestPlayerRing();
            byPacked.put(chunkData.packedPos(), chunkData);
        }

        long[] selected = RainMath.selectDistributedChunkSample(packedPositions, playerRings, maxChunksPerTick, currentTime, seedSalt);
        List<ChunkProcessingData> result = new ArrayList<>(selected.length);
        for (long packed : selected) {
            ChunkProcessingData chunkData = byPacked.get(packed);
            if (chunkData != null) {
                result.add(chunkData);
            }
        }
        return result;
    }

    private static void refreshWakeChunks(ServerLevel level, ResourceKey<Level> levelKey, long now, List<ChunkPos> playerChunks) {
        boolean raining = level.isRaining();
        boolean wasRaining = lastRainState.getOrDefault(levelKey, Boolean.FALSE);
        lastRainState.put(levelKey, raining);

        if (!raining) {
            return;
        }

        ConcurrentHashMap<Long, Long> wakeChunks = activeRainChunks.computeIfAbsent(levelKey, ignored -> new ConcurrentHashMap<>());
        ConcurrentHashMap<UUID, Long> playerState = lastPlayerRainChunks.computeIfAbsent(levelKey, ignored -> new ConcurrentHashMap<>());
        Set<UUID> activePlayers = new HashSet<>();

        int chunkRadius = getEffectiveChunkRadius(level, FlowingFluids.config.rainChunkRadius);
        long fullWakeExpiry = now + computeWakeTtl(level, chunkRadius);
        long ringWakeExpiry = now + computeWakeTtl(level, Math.max(1, chunkRadius));
        long wakeSlice = Math.max(1L, now / Math.max(1L, FlowingFluids.config.rainGenerateIntervalTicks));

        for (ServerPlayer player : level.getPlayers(__ -> true)) {
            ChunkPos playerChunk = player.chunkPosition();
            playerChunks.add(playerChunk);
            activePlayers.add(player.getUUID());

            long packedChunk = playerChunk.toLong();
            Long previousChunk = playerState.put(player.getUUID(), packedChunk);
            if (!wasRaining || previousChunk == null || previousChunk.longValue() != packedChunk) {
                wakeChunkArea(wakeChunks, playerChunk.x, playerChunk.z, chunkRadius, fullWakeExpiry);
                continue;
            }

            int ring = chunkRadius <= 0
                    ? 0
                    : Math.floorMod((int) (wakeSlice + player.getUUID().hashCode()), chunkRadius + 1);
            wakeChunkRing(wakeChunks, playerChunk.x, playerChunk.z, ring, ringWakeExpiry);
        }

        playerState.keySet().removeIf(uuid -> !activePlayers.contains(uuid));
        wakeChunks.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private static long[] collectActiveRainChunks(ResourceKey<Level> levelKey, long now) {
        ConcurrentHashMap<Long, Long> wakeChunks = activeRainChunks.get(levelKey);
        if (wakeChunks == null || wakeChunks.isEmpty()) {
            return new long[0];
        }

        reusableChunkCollector.clear();
        wakeChunks.forEach((packedChunk, expiryTick) -> {
            if (expiryTick >= now) {
                reusableChunkCollector.add(packedChunk.longValue());
            }
        });
        return reusableChunkCollector.toLongArray();
    }

    private static long computeWakeTtl(ServerLevel level, int chunkRadius) {
        long interval = Math.max(1L, FlowingFluids.config.rainGenerateIntervalTicks);
        long cycle = Math.max(4L, (long) Math.max(0, chunkRadius) + 2L);
        return Math.max(MIN_WAKE_TTL_TICKS, interval * cycle);
    }

    private static void wakeChunkArea(ConcurrentHashMap<Long, Long> wakeChunks, int centerChunkX, int centerChunkZ,
                                      int radius, long expiryTick) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                wakeChunks.merge(ChunkPos.asLong(centerChunkX + dx, centerChunkZ + dz), expiryTick, Math::max);
            }
        }
    }

    private static void wakeChunkRing(ConcurrentHashMap<Long, Long> wakeChunks, int centerChunkX, int centerChunkZ,
                                      int ring, long expiryTick) {
        if (ring <= 0) {
            wakeChunks.merge(ChunkPos.asLong(centerChunkX, centerChunkZ), expiryTick, Math::max);
            return;
        }

        for (int offset = -ring; offset <= ring; offset++) {
            wakeChunks.merge(ChunkPos.asLong(centerChunkX + offset, centerChunkZ - ring), expiryTick, Math::max);
            wakeChunks.merge(ChunkPos.asLong(centerChunkX + offset, centerChunkZ + ring), expiryTick, Math::max);
        }
        for (int offset = -ring + 1; offset <= ring - 1; offset++) {
            wakeChunks.merge(ChunkPos.asLong(centerChunkX - ring, centerChunkZ + offset), expiryTick, Math::max);
            wakeChunks.merge(ChunkPos.asLong(centerChunkX + ring, centerChunkZ + offset), expiryTick, Math::max);
        }
    }

    private static boolean isWakeChunkActive(ResourceKey<Level> levelKey, long packedChunk, long now) {
        ConcurrentHashMap<Long, Long> wakeChunks = activeRainChunks.get(levelKey);
        if (wakeChunks == null) {
            return false;
        }
        Long expiryTick = wakeChunks.get(packedChunk);
        if (expiryTick == null) {
            return false;
        }
        if (expiryTick.longValue() < now) {
            wakeChunks.remove(packedChunk, expiryTick);
            return false;
        }
        return true;
    }

    private static long countActiveWakeChunks(ResourceKey<Level> levelKey, long now) {
        ConcurrentHashMap<Long, Long> wakeChunks = activeRainChunks.get(levelKey);
        if (wakeChunks == null || wakeChunks.isEmpty()) {
            return 0L;
        }
        return wakeChunks.values().stream().filter(expiry -> expiry >= now).count();
    }

    private static void clearWakeState(ResourceKey<Level> levelKey) {
        activeRainChunks.remove(levelKey);
        lastPlayerRainChunks.remove(levelKey);
        lastRainState.remove(levelKey);
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

    private static ChunkBiomeCache getOrCreateChunkCache(ServerLevel level, long packedChunkPos, long currentTime) {
        final ResourceKey<Level> levelKey = level.dimension();
        final String cacheKey = buildChunkCacheKey(levelKey, packedChunkPos);

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
        final ResourceKey<Level> levelKey = level.dimension();
        final long lastMaintenance = lastCacheMaintenanceTick.getOrDefault(levelKey, Long.MIN_VALUE);

        if (lastMaintenance == Long.MIN_VALUE) {
            lastCacheMaintenanceTick.put(levelKey, now);
            return;
        }

        long wetnessPersistTicks = Math.max(20L, FlowingFluids.config.rainWetnessPersistTicks);
        final long fallbackTicks = Math.max(Math.max(FALLBACK_CACHE_RESYNC_TICKS, FlowingFluids.config.rainCacheDurationTicks), wetnessPersistTicks);
        if ((now - lastMaintenance) >= fallbackTicks) {
            if (FlowingFluids.config.rainEnableChunkCaching) {
                clearChunkCacheForLevel(levelKey);
            }
            WETNESS_CACHE.purgeExpired(levelKey, now);
            lastCacheMaintenanceTick.put(levelKey, now);
        }
    }

    private static String buildChunkCacheKey(ResourceKey<Level> levelKey, long packedChunkPos) {
        return levelKey.location() + "|" + packedChunkPos;
    }

    private static String getChunkCachePrefix(ResourceKey<Level> levelKey) {
        return levelKey.location() + "|";
    }

    private static void clearChunkCacheForLevel(ResourceKey<Level> levelKey) {
        final String prefix = getChunkCachePrefix(levelKey);
        chunkCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static long countChunkCacheEntries(ResourceKey<Level> levelKey) {
        final String prefix = getChunkCachePrefix(levelKey);
        return chunkCache.keySet().stream().filter(key -> key.startsWith(prefix)).count();
    }

    private static boolean findRainLandingMutable(ServerLevel level, int x, int z,
                                                  BlockPos.MutableBlockPos outPos,
                                                  BlockPos.MutableBlockPos tmpAbove,
                                                  int minBuildY) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (surfaceY < minBuildY) return false;

        outPos.set(x, surfaceY, z);
        BlockState s = level.getBlockState(outPos);
        final int maxSearchDepth = FlowingFluids.config.rainMaxSurfaceSearchDepth;

        if (s.is(BlockTags.LEAVES)) {
            int steps = 0;
            while (outPos.getY() >= minBuildY && s.is(BlockTags.LEAVES) && steps++ < maxSearchDepth + 2) {
                outPos.move(0, -1, 0);
                s = level.getBlockState(outPos);
            }
            if (outPos.getY() < minBuildY || s.is(BlockTags.LEAVES)) {
                return false;
            }
        }

        if (s.canBeReplaced()) {
            return true;
        }
        if (s.isFaceSturdy(level, outPos, Direction.UP)) {
            tmpAbove.set(x, surfaceY + 1, z);
            if (level.getBlockState(tmpAbove).isAir()) {
                outPos.set(tmpAbove);
                return true;
            }
        }

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
        if (maxStackHeight <= 0) {
            return false;
        }

        final BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos(cursor.getX(), cursor.getY() + 1, cursor.getZ());
        int guard = 0;
        while (level.getFluidState(above).isSourceOfType(Fluids.WATER)) {
            cursor.set(above.getX(), above.getY(), above.getZ());
            above.set(cursor.getX(), cursor.getY() + 1, cursor.getZ());
            if (++guard > maxStackHeight) break;
        }

        int stackBaseY = findRainWaterStackBaseY(level, cursor);
        final int targetY = cursor.getY() + 1;
        if (targetY > stackBaseY + maxStackHeight) return false;

        BlockState target = level.getBlockState(above.set(cursor.getX(), targetY, cursor.getZ()));
        if (!(target.isAir() || target.canBeReplaced())) return false;

        outPos.set(cursor.getX(), targetY, cursor.getZ());
        return true;
    }

    private static int findRainWaterStackBaseY(ServerLevel level, BlockPos waterPos) {
        BlockPos.MutableBlockPos base = new BlockPos.MutableBlockPos(waterPos.getX(), waterPos.getY(), waterPos.getZ());
        int maxDepth = Math.max(6, FlowingFluids.config.rainMaxSurfaceSearchDepth + FlowingFluids.config.rainMaxWaterStackHeight + 4);
        int minY = level.getMinBuildHeight();

        for (int depth = 0; depth <= maxDepth && base.getY() >= minY; depth++) {
            if (!level.getFluidState(base).isSourceOfType(Fluids.WATER)) {
                return base.getY();
            }
            base.move(Direction.DOWN);
        }
        return waterPos.getY() - maxDepth;
    }

    private enum AbsorptionTier {
        HIGH(0.75f),
        MEDIUM(0.45f),
        LOW(0.20f),
        IMPERVIOUS(0.05f);

        private final float absorptionCoefficient;

        AbsorptionTier(float absorptionCoefficient) {
            this.absorptionCoefficient = absorptionCoefficient;
        }

        float absorptionCoefficient() {
            return absorptionCoefficient;
        }
    }

    private record RainSurfaceContext(BlockPos groundPos, Block groundBlock, AbsorptionTier absorptionTier,
                                      float wetness, RainIntensityStage rainIntensityStage,
                                      float upstreamBonus, float catchmentBoost,
                                      int candidateAmount, int effectiveAmount, float absorbedWetness) {
    }

    private record ChunkProcessingData(long packedPos, ChunkBiomeCache cache, int nearestPlayerRing) {
    }

    private record ChunkBiomeCache(ResourceKey<Biome> biomeKey, float precipMul,
                                   boolean hasPrecipitation, boolean isInfiniteWaterBiome, long cachedTime) {
    }

    private record WetnessCacheKey(ResourceKey<Level> level, BlockPos pos) {
    }

    private record WetnessEntry(float wetness, long lastUpdateTick) {
    }

    private static final class RainWetnessCache {
        private final ConcurrentHashMap<WetnessCacheKey, WetnessEntry> wetnessByBlock = new ConcurrentHashMap<>();

        private float getWetness(ResourceKey<Level> levelKey, BlockPos pos, long currentTime) {
            WetnessCacheKey key = new WetnessCacheKey(levelKey, pos.immutable());
            WetnessEntry entry = wetnessByBlock.get(key);
            if (entry == null) {
                return 0.0f;
            }

            float decayed = RainMath.decayWetness(entry.wetness(), currentTime - entry.lastUpdateTick(), FlowingFluids.config.rainWetnessPersistTicks);
            if (decayed <= 0.0f) {
                wetnessByBlock.remove(key, entry);
                return 0.0f;
            }
            return decayed;
        }

        private void addWetness(ResourceKey<Level> levelKey, BlockPos pos, float delta, long currentTime) {
            if (delta <= 0.0f || FlowingFluids.config.rainWetnessPersistTicks <= 0) {
                return;
            }

            WetnessCacheKey key = new WetnessCacheKey(levelKey, pos.immutable());
            wetnessByBlock.compute(key, (ignored, entry) -> {
                float currentWetness = entry == null
                        ? 0.0f
                        : RainMath.decayWetness(entry.wetness(), currentTime - entry.lastUpdateTick(), FlowingFluids.config.rainWetnessPersistTicks);
                float updated = Mth.clamp(currentWetness + delta, 0.0f, 1.0f);
                return updated <= 0.0f ? null : new WetnessEntry(updated, currentTime);
            });
        }

        private void purgeExpired(ResourceKey<Level> levelKey, long currentTime) {
            wetnessByBlock.entrySet().removeIf(entry -> {
                if (!entry.getKey().level().equals(levelKey)) {
                    return false;
                }
                return RainMath.decayWetness(entry.getValue().wetness(), currentTime - entry.getValue().lastUpdateTick(),
                        FlowingFluids.config.rainWetnessPersistTicks) <= 0.0f;
            });
        }

        private void clearLevel(ResourceKey<Level> levelKey) {
            wetnessByBlock.keySet().removeIf(key -> key.level().equals(levelKey));
        }

        private void clearAll() {
            wetnessByBlock.clear();
        }

        private long countLevel(ResourceKey<Level> levelKey) {
            return wetnessByBlock.keySet().stream().filter(key -> key.level().equals(levelKey)).count();
        }
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
