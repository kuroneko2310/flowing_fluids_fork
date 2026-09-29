package traben.flowing_fluids.water;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.season.SeasonClimate;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rain-driven high water ("増水") for rivers and seas.
 *
 * <p>Normally river and ocean biomes are held near sea level: thin sea-level surface water is drained and water
 * standing above sea level is evaporated as overflow. While it rains that cap is lifted completely, so runoff flowing in
 * from the surrounding land can raise rivers and coasts as high as the inflow allows. Swollen river water also runs
 * faster and pushes harder, so a flooded river reads as a torrent.</p>
 *
 * <p>When the rain stops the cap does not snap back (which would delete a whole swollen river in one tick): the
 * recession factor ramps from 0 to 1 with a smoothstep over {@code riverFloodRecessionTicks}, scaling the drain and
 * overflow-evaporation chances, so high water recedes over a few minutes.</p>
 */
public final class RiverFloodStage {
    private RiverFloodStage() {
    }

    private static final int WARNING_INTERVAL_TICKS = 100;
    private static final int WARNING_REPEAT_TICKS = 600;
    private static final int WARNING_SAMPLE_RADIUS = 16;
    private static final double WARNING_MIN_EXCESS = 1.0;
    private static final ConcurrentHashMap<UUID, Warning> LAST_WARNINGS = new ConcurrentHashMap<>();

    public static void onLevelTick(ServerLevel level) {
        if (level.isRaining() || SeasonClimate.isSpringFreshet(level)) {
            // Persisted, so a restart during the recession keeps the flood receding instead of snapping back.
            WeatherWaterSavedData.get(level).setLastRainTick(level.getGameTime());
        }
        if (FlowingFluids.config.enableFloodWarnings && isHighWater(level)) {
            long now = level.getGameTime();
            for (ServerPlayer player : level.players()) {
                if (Math.floorMod(now + player.getId(), WARNING_INTERVAL_TICKS) == 0) {
                    warnIfRising(level, player, now);
                }
            }
        }
    }

    /**
     * Samples the player's column and a ring around it for river/sea water standing above sea level. Only loaded
     * chunks are read. A warning repeats at most every 30 seconds unless the water has risen another block since.
     */
    private static void warnIfRising(ServerLevel level, ServerPlayer player, long now) {
        int seaLevel = FFFluidUtils.seaLevel(level);
        double highest = Double.NEGATIVE_INFINITY;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = -1; i < 8; i++) {
            double angle = i * (Math.PI / 4.0);
            int x = player.getBlockX() + (i < 0 ? 0 : (int) Math.round(Math.cos(angle) * WARNING_SAMPLE_RADIUS));
            int z = player.getBlockZ() + (i < 0 ? 0 : (int) Math.round(Math.sin(angle) * WARNING_SAMPLE_RADIUS));
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            cursor.set(x, y, z);
            FluidState fluid = FFFluidUtils.getEffectiveFluidState(level, cursor);
            if (!fluid.is(FluidTags.WATER) || fluid.getAmount() <= 0) {
                continue;
            }
            Holder<Biome> biome = coarseBiome(level, cursor);
            if (!FFFluidUtils.isRiverBiome(biome) && !FFFluidUtils.isOceanBiome(biome)) {
                continue;
            }
            highest = Math.max(highest, y + fluid.getAmount() / 8.0 - seaLevel);
        }
        if (highest < WARNING_MIN_EXCESS) {
            return;
        }
        Warning last = LAST_WARNINGS.get(player.getUUID());
        if (last != null && now - last.tick < WARNING_REPEAT_TICKS && highest < last.excess + 1.0) {
            return;
        }
        LAST_WARNINGS.put(player.getUUID(), new Warning(now, highest));
        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                "近くの川や海が増水しています（海面より約%.1fブロック上）。低い場所に注意してください。", highest)), true);
    }

    private record Warning(long tick, double excess) {
    }

    private static boolean isEnabled(Level level) {
        return level != null
                && FlowingFluids.config != null
                && FlowingFluids.config.enableMod
                && FlowingFluids.config.enableRiverFloodStage
                && level.dimensionType().hasSkyLight();
    }

    /**
     * True while it rains, or during the spring snowmelt freshet: the river/sea height cap is lifted entirely.
     */
    public static boolean isHighWater(Level level) {
        return isEnabled(level) && (level.isRaining() || SeasonClimate.isSpringFreshet(level));
    }

    /**
     * 0 while it rains (no cap), rising smoothly to 1 (normal cap) after the rain stops.
     */
    public static float getCapFactor(Level level) {
        if (!isEnabled(level)) {
            return 1.0f;
        }
        if (level.isRaining() || SeasonClimate.isSpringFreshet(level)) {
            return 0.0f;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return 1.0f;
        }
        long lastRain = WeatherWaterSavedData.get(serverLevel).lastRainTick();
        if (lastRain == Long.MIN_VALUE) {
            return 1.0f;
        }
        return recessionFactor(level.getGameTime() - lastRain, FlowingFluids.config.riverFloodRecessionTicks);
    }

    static float recessionFactor(long ticksSinceRain, int recessionTicks) {
        if (recessionTicks <= 0 || ticksSinceRain >= recessionTicks) {
            return 1.0f;
        }
        if (ticksSinceRain <= 0) {
            return 0.0f;
        }
        float t = ticksSinceRain / (float) recessionTicks;
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Water that is part of a swollen river or sea: river/ocean biome water at or above sea level during high water.
     * Only rivers and oceans count, so rain-filled ponds inland keep their normal behaviour.
     */
    public static boolean isSwollenWater(Level level, BlockPos pos) {
        if (!isHighWater(level) || pos.getY() < FFFluidUtils.seaLevel(level) - 1) {
            return false;
        }
        Holder<Biome> biome = coarseBiome(level, pos);
        return FFFluidUtils.isRiverBiome(biome) || FFFluidUtils.isOceanBiome(biome);
    }

    /**
     * This runs for every water tick while it rains, so it reads the stored 4x4x4 noise biome directly instead of
     * {@code Level.getBiome}, which applies the fuzzy biome zoom (a hash and up to eight cell lookups per call).
     */
    private static Holder<Biome> coarseBiome(Level level, BlockPos pos) {
        return level.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()));
    }

    /**
     * Tick delay multiplier (< 1 = faster) for swollen water.
     */
    public static float getFlowDelayMultiplier(Level level, BlockPos pos) {
        return isSwollenWater(level, pos)
                ? Math.max(0.1f, Math.min(1.0f, FlowingFluids.config.riverFloodFlowDelayMultiplier))
                : 1.0f;
    }

    /**
     * Current push multiplier (> 1 = stronger) for entities in swollen river water.
     */
    public static double getCurrentPushMultiplier(Level level, BlockPos pos) {
        if (!isHighWater(level) || pos.getY() < FFFluidUtils.seaLevel(level) - 1
                || !FFFluidUtils.isRiverBiome(coarseBiome(level, pos))) {
            return 1.0;
        }
        return Math.max(1.0, FlowingFluids.config.riverFloodCurrentPushMultiplier);
    }
}
