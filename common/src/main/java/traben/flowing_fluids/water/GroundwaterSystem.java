package traben.flowing_fluids.water;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.drying.DryingEventSystem;
import traben.flowing_fluids.season.SeasonClimate;
import traben.flowing_fluids.water.WeatherWaterSavedData.AquiferRegion;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Regional groundwater: rain that soaks into the ground recharges an aquifer, holes dug below the water table fill
 * by seepage from it, and springs flow harder or weaker with it.
 *
 * <p>Wells are found by sampling columns near players (plus one column right next to each player every tick, so a
 * freshly dug well is noticed quickly). A column is a well when its bottom is below the water table while the ground
 * three blocks away on at least three sides is above it; natural lowland valleys therefore do not turn into lakes.
 * Known wells are topped up once a second towards the table, taking every level from the aquifer, so pumping a well
 * (for example with a Create hose pulley or pump) draws the aquifer down and a dry spell makes wells run dry.</p>
 */
public final class GroundwaterSystem {
    private static final int REGION_SHIFT = 6;
    private static final int WELL_REFILL_INTERVAL_TICKS = 20;
    private static final int MAX_ACTIVE_WELLS = 256;
    private static final int DISCOVERY_RADIUS = 48;
    private static final int NEAR_PLAYER_RADIUS = 6;
    private static final int SURROUNDING_PROBE = 3;
    private static final int SEEP_PER_REFILL = 2;
    private static final long DRAIN_UPDATE_TICKS = 200L;

    private static final ConcurrentHashMap<ResourceKey<Level>, LongLinkedOpenHashSet> ACTIVE_WELLS = new ConcurrentHashMap<>();

    private GroundwaterSystem() {
    }

    public static void clearDimension(Level level) {
        if (level != null) {
            ACTIVE_WELLS.remove(level.dimension());
        }
    }

    public static boolean isEnabled(Level level) {
        return FlowingFluids.config.enableMod
                && FlowingFluids.config.enableGroundwater
                && FlowingFluids.config.isWaterAllowed()
                && !FlowingFluids.config.isDimensionExcluded(level)
                && level.dimensionType().hasSkyLight();
    }

    public static void onLevelTick(ServerLevel level) {
        if (!isEnabled(level)) {
            ACTIVE_WELLS.remove(level.dimension());
            return;
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        WeatherWaterSavedData saved = WeatherWaterSavedData.get(level);
        LongLinkedOpenHashSet wells = ACTIVE_WELLS.computeIfAbsent(level.dimension(), ignored -> new LongLinkedOpenHashSet());
        RandomSource random = level.random;

        for (ServerPlayer player : players) {
            discover(level, saved, wells, player.getBlockX() + random.nextInt(NEAR_PLAYER_RADIUS * 2 + 1) - NEAR_PLAYER_RADIUS,
                    player.getBlockZ() + random.nextInt(NEAR_PLAYER_RADIUS * 2 + 1) - NEAR_PLAYER_RADIUS);
        }
        int samples = Math.max(0, FlowingFluids.config.groundwaterSamplesPerTick);
        for (int i = 0; i < samples; i++) {
            ServerPlayer player = players.get(random.nextInt(players.size()));
            discover(level, saved, wells, player.getBlockX() + random.nextInt(DISCOVERY_RADIUS * 2 + 1) - DISCOVERY_RADIUS,
                    player.getBlockZ() + random.nextInt(DISCOVERY_RADIUS * 2 + 1) - DISCOVERY_RADIUS);
        }

        if (Math.floorMod(level.getGameTime(), WELL_REFILL_INTERVAL_TICKS) == 0 && !wells.isEmpty()) {
            LongIterator iterator = wells.iterator();
            while (iterator.hasNext()) {
                long column = iterator.nextLong();
                int x = BlockPos.getX(column);
                int z = BlockPos.getZ(column);
                if (!level.hasChunk(x >> 4, z >> 4) || seep(level, saved, x, z) == SeepResult.NOT_A_WELL) {
                    iterator.remove();
                }
            }
        }
    }

    private static void discover(ServerLevel level, WeatherWaterSavedData saved, LongLinkedOpenHashSet wells, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return;
        }
        long column = BlockPos.asLong(x, 0, z);
        if (wells.contains(column)) {
            return;
        }
        if (seep(level, saved, x, z) != SeepResult.NOT_A_WELL) {
            if (wells.size() >= MAX_ACTIVE_WELLS) {
                wells.removeFirstLong();
            }
            wells.add(column);
        }
    }

    enum SeepResult {
        NOT_A_WELL,
        FULL,
        DRY,
        SEEPED
    }

    static SeepResult seep(ServerLevel level, WeatherWaterSavedData saved, int x, int z) {
        int floorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        AquiferRegion region = region(level, saved, x, z);
        int table = tableY(level, region);
        if (floorY + 1 >= table) {
            return SeepResult.NOT_A_WELL;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, floorY + 1, z);
        if (FFFluidUtils.matchInfiniteBiomes(level.getBiome(cursor))) {
            return SeepResult.NOT_A_WELL;
        }
        int higherSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int sx = x + direction.getStepX() * SURROUNDING_PROBE;
            int sz = z + direction.getStepZ() * SURROUNDING_PROBE;
            if (level.hasChunk(sx >> 4, sz >> 4)
                    && level.getHeight(Heightmap.Types.OCEAN_FLOOR, sx, sz) - 1 >= table) {
                higherSides++;
            }
        }
        if (higherSides < 3) {
            return SeepResult.NOT_A_WELL;
        }

        for (int y = floorY + 1; y < table; y++) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            FluidState fluid = FFFluidUtils.getEffectiveFluidState(level, cursor, state);
            int amount = fluid.is(FluidTags.WATER) ? fluid.getAmount() : 0;
            if (amount >= 8) {
                continue;
            }
            if (!fluid.isEmpty() && amount == 0) {
                return SeepResult.NOT_A_WELL;
            }
            if (!FFFluidUtils.isOpenFluidCell(state, Fluids.WATER)) {
                // Something solid sits in the shaft below the table (a pump, a pipe run); seep no higher than that.
                return SeepResult.FULL;
            }
            int add = Math.min(Math.min(SEEP_PER_REFILL, 8 - amount), region.stored);
            if (add <= 0) {
                return SeepResult.DRY;
            }
            if (!FFFluidUtils.setFluidStateAtPosToNewAmount(level, cursor, Fluids.WATER, amount + add)) {
                return SeepResult.FULL;
            }
            region.stored -= add;
            saved.setDirty();
            AdaptiveTickScheduler.scheduleFluidTick(level, cursor, Fluids.WATER, 1);
            return SeepResult.SEEPED;
        }
        return SeepResult.FULL;
    }

    /**
     * Rain that soaked into the ground instead of pooling.
     */
    public static void recharge(ServerLevel level, BlockPos pos, int levels) {
        if (levels <= 0 || !isEnabled(level)) {
            return;
        }
        WeatherWaterSavedData saved = WeatherWaterSavedData.get(level);
        AquiferRegion region = region(level, saved, pos.getX(), pos.getZ());
        int capacity = Math.max(1, FlowingFluids.config.groundwaterCapacity);
        int added = Math.min(capacity - region.stored,
                (int) Math.floor(levels * Math.max(0.0f, FlowingFluids.config.groundwaterRechargeEfficiency)
                        + level.random.nextDouble()));
        if (added > 0) {
            region.stored += added;
            saved.setDirty();
        }
    }

    /**
     * Springs tap the same aquifer: weaker when it runs low in a drought, stronger after a wet season.
     */
    public static double springEmissionMultiplier(ServerLevel level, BlockPos pos) {
        if (!isEnabled(level)) {
            return 1.0;
        }
        AquiferRegion region = region(level, WeatherWaterSavedData.get(level), pos.getX(), pos.getZ());
        return GroundwaterMath.springMultiplier(GroundwaterMath.saturation(region.stored,
                Math.max(1, FlowingFluids.config.groundwaterCapacity)));
    }

    public static void setSaturation(ServerLevel level, BlockPos pos, double saturation) {
        WeatherWaterSavedData saved = WeatherWaterSavedData.get(level);
        AquiferRegion region = region(level, saved, pos.getX(), pos.getZ());
        region.stored = (int) Math.round(Math.max(0.0, Math.min(1.0, saturation)) * Math.max(1, FlowingFluids.config.groundwaterCapacity));
        saved.setDirty();
    }

    public static String describe(ServerLevel level, BlockPos pos) {
        if (!isEnabled(level)) {
            return "地下水: 無効";
        }
        WeatherWaterSavedData saved = WeatherWaterSavedData.get(level);
        AquiferRegion region = region(level, saved, pos.getX(), pos.getZ());
        int capacity = Math.max(1, FlowingFluids.config.groundwaterCapacity);
        double saturation = GroundwaterMath.saturation(region.stored, capacity);
        LongLinkedOpenHashSet wells = ACTIVE_WELLS.get(level.dimension());
        return String.format(Locale.ROOT,
                "地下水（この64x64地域）: %d / %d レベル（%.0f%%）%n基準地表Y=%d / 地下水位Y=%d / 湧き水の勢い x%.2f / 監視中の井戸=%d",
                region.stored, capacity, saturation * 100.0, region.referenceSurfaceY, tableY(level, region),
                GroundwaterMath.springMultiplier(saturation), wells == null ? 0 : wells.size());
    }

    private static int tableY(ServerLevel level, AquiferRegion region) {
        double saturation = GroundwaterMath.saturation(region.stored, Math.max(1, FlowingFluids.config.groundwaterCapacity));
        int depth = GroundwaterMath.tableDepth(saturation, FlowingFluids.config.groundwaterMinDepth, FlowingFluids.config.groundwaterMaxDepth);
        return GroundwaterMath.tableY(region.referenceSurfaceY, depth, FFFluidUtils.seaLevel(level), FlowingFluids.config.groundwaterMinDepth);
    }

    /**
     * Region for a block column, created on first use with a reference surface sampled from loaded terrain and the
     * configured initial saturation, and drained lazily for the time since it was last touched.
     */
    private static AquiferRegion region(ServerLevel level, WeatherWaterSavedData saved, int x, int z) {
        long key = ChunkPos.asLong(x >> REGION_SHIFT, z >> REGION_SHIFT);
        AquiferRegion region = saved.aquifers().get(key);
        long now = level.getGameTime();
        if (region == null) {
            region = new AquiferRegion();
            region.referenceSurfaceY = sampleReferenceSurface(level, x, z);
            region.stored = (int) Math.round(Math.max(1, FlowingFluids.config.groundwaterCapacity)
                    * Math.max(0.0f, Math.min(1.0f, FlowingFluids.config.groundwaterInitialSaturation)));
            region.lastUpdateTick = now;
            saved.aquifers().put(key, region);
            saved.setDirty();
            return region;
        }
        long elapsed = now - region.lastUpdateTick;
        if (elapsed >= DRAIN_UPDATE_TICKS) {
            // Evapotranspiration and deep percolation: faster in drought and summer, nearly stopped in frozen winter.
            double factor = (0.5 + DryingEventSystem.getDroughtIndex(level)) * SeasonClimate.evaporationMultiplier(level);
            int drained = GroundwaterMath.drain(region.stored, Math.min(elapsed, 24000L * 4),
                    FlowingFluids.config.groundwaterDrainPerDay, factor);
            region.lastUpdateTick = now;
            if (drained != region.stored) {
                region.stored = drained;
                saved.setDirty();
            }
        }
        return region;
    }

    private static int sampleReferenceSurface(ServerLevel level, int x, int z) {
        int regionMinX = (x >> REGION_SHIFT) << REGION_SHIFT;
        int regionMinZ = (z >> REGION_SHIFT) << REGION_SHIFT;
        int[] offsets = {8, 32, 56};
        int[] heights = new int[offsets.length * offsets.length];
        int count = 0;
        for (int ox : offsets) {
            for (int oz : offsets) {
                int sx = regionMinX + ox;
                int sz = regionMinZ + oz;
                if (level.hasChunk(sx >> 4, sz >> 4)) {
                    heights[count++] = level.getHeight(Heightmap.Types.OCEAN_FLOOR, sx, sz) - 1;
                }
            }
        }
        if (count == 0) {
            return level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        }
        Arrays.sort(heights, 0, count);
        return heights[count / 2];
    }
}
