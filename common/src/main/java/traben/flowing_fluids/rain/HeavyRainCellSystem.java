package traben.flowing_fluids.rain;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.drying.DryingEventSystem;
import traben.flowing_fluids.season.SeasonClimate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moving heavy-rain cells ("guerrilla downpours") layered on top of normal rain.
 *
 * <p>While it rains, a dimension occasionally spawns a cell upwind of a player. The cell drifts with the wind, grows,
 * rains hardest in its core, then rains itself out (see {@link HeavyRainMath}). Normal rain asks
 * {@link #getRainMultiplier} for each chunk and scales its drop attempts, so all of the existing absorption, runoff and
 * load-shedding rules still apply: a downpour can never bypass the rain load governor.</p>
 *
 * <p>Randomness: spawn rolls, spawn offsets and wind kicks use the level's {@link RandomSource}; everything evaluated
 * per chunk is deterministic in position and time, so neighbouring chunks agree about where the cell is.</p>
 */
public final class HeavyRainCellSystem {
    private static final double WIND_REVERSION = 0.08;
    private static final double WIND_VOLATILITY = 0.18;
    private static final double MIN_DRIFT_PER_TICK = 0.04;
    private static final double MAX_DRIFT_PER_TICK = 0.12;
    /** Once rain stops, a cell loses the rest of its life this many times faster. */
    private static final int DRY_WEATHER_DECAY_SPEEDUP = 6;
    private static final double MULTIPLIER_CAP = 8.0;

    private static final ConcurrentHashMap<ResourceKey<Level>, DimensionState> STATES = new ConcurrentHashMap<>();

    private HeavyRainCellSystem() {
    }

    public static void onLevelTick(ServerLevel level) {
        DimensionState state = STATES.get(level.dimension());
        if (!isEnabled(level)) {
            if (state != null) {
                STATES.remove(level.dimension());
            }
            return;
        }
        if (state == null) {
            if (!level.isRaining()) {
                return;
            }
            state = STATES.computeIfAbsent(level.dimension(), ignored -> new DimensionState(level.getSeed()));
        }

        long now = level.getGameTime();
        advanceCells(level, state, now);
        if (level.isRaining() && now >= state.nextRollTick) {
            state.nextRollTick = now + Math.max(20, FlowingFluids.config.heavyRainRollIntervalTicks);
            state.windHeading = HeavyRainMath.windStep(state.windHeading, state.prevailingHeading,
                    WIND_REVERSION, WIND_VOLATILITY, level.random.nextGaussian());
            tryNaturalSpawn(level, state, now);
        }
        if (state.cells.isEmpty() && !level.isRaining()) {
            STATES.remove(level.dimension(), state);
        }
    }

    public static void onLevelUnload(ServerLevel level) {
        STATES.remove(level.dimension());
    }

    /**
     * Rain multiplier (>= 1) at a block column. Cheap: a handful of cells at most.
     */
    public static float getRainMultiplier(ServerLevel level, double x, double z) {
        DimensionState state = STATES.get(level.dimension());
        if (state == null || state.cells.isEmpty()) {
            return 1.0f;
        }
        long now = level.getGameTime();
        double multiplier = 1.0;
        for (Cell cell : state.cells) {
            double dx = x - cell.x;
            double dz = z - cell.z;
            double weight = HeavyRainMath.falloff(dx * dx + dz * dz, cell.radius);
            if (weight <= 0.0) {
                continue;
            }
            double cellMultiplier = HeavyRainMath.rainMultiplier(cell.peak,
                    HeavyRainMath.lifecycle(cell.effectiveAge(now), cell.lifetime), weight);
            multiplier = HeavyRainMath.combine(multiplier, cellMultiplier, MULTIPLIER_CAP);
        }
        return (float) multiplier;
    }

    public static boolean startCell(ServerLevel level, BlockPos center, int radius, int durationTicks) {
        if (!FlowingFluids.config.enableMod || FlowingFluids.config.isDimensionExcluded(level)
                || !level.dimensionType().hasSkyLight()) {
            return false;
        }
        DimensionState state = STATES.computeIfAbsent(level.dimension(), ignored -> new DimensionState(level.getSeed()));
        spawn(level, state, center.getX() + 0.5, center.getZ() + 0.5, radius, durationTicks);
        return true;
    }

    public static int stopAll(ServerLevel level) {
        DimensionState state = STATES.remove(level.dimension());
        return state == null ? 0 : state.cells.size();
    }

    public static String describe(ServerLevel level, BlockPos reference) {
        DimensionState state = STATES.get(level.dimension());
        StringBuilder text = new StringBuilder("豪雨セル: ");
        if (state == null || state.cells.isEmpty()) {
            text.append("なし");
        } else {
            long now = level.getGameTime();
            text.append(state.cells.size()).append("個");
            for (Cell cell : state.cells) {
                double dx = reference.getX() + 0.5 - cell.x;
                double dz = reference.getZ() + 0.5 - cell.z;
                text.append(String.format(Locale.ROOT,
                        "\n- 中心=(%.0f, %.0f) 半径=%d 強度=%.0f%% 残り=%.0f秒 距離=%.0f",
                        cell.x, cell.z, cell.radius,
                        HeavyRainMath.lifecycle(cell.effectiveAge(now), cell.lifetime) * 100.0,
                        Math.max(0, cell.lifetime - cell.effectiveAge(now)) / 20.0,
                        Math.sqrt(dx * dx + dz * dz)));
            }
        }
        if (state != null) {
            text.append(String.format(Locale.ROOT, "\n風向き=%.0f° / 卓越風=%.0f°",
                    Math.toDegrees(state.windHeading), Math.toDegrees(state.prevailingHeading)));
        }
        text.append(String.format(Locale.ROOT, "\nこの地点の雨量倍率=x%.2f",
                getRainMultiplier(level, reference.getX() + 0.5, reference.getZ() + 0.5)));
        return text.toString();
    }

    private static boolean isEnabled(ServerLevel level) {
        return FlowingFluids.config.enableMod
                && FlowingFluids.config.enableRainSystem
                && FlowingFluids.config.enableHeavyRainCells
                && !FlowingFluids.config.isDimensionExcluded(level)
                && level.dimensionType().hasSkyLight();
    }

    private static void advanceCells(ServerLevel level, DimensionState state, long now) {
        if (state.cells.isEmpty()) {
            state.lastTick = now;
            return;
        }
        long elapsed = Math.max(0, Math.min(200, now - state.lastTick));
        state.lastTick = now;
        boolean raining = level.isRaining();
        double windX = -Math.sin(state.windHeading);
        double windZ = Math.cos(state.windHeading);
        state.cells.removeIf(cell -> {
            cell.x += windX * cell.driftPerTick * elapsed;
            cell.z += windZ * cell.driftPerTick * elapsed;
            if (!raining) {
                cell.agePenalty += elapsed * (DRY_WEATHER_DECAY_SPEEDUP - 1L);
            }
            return cell.effectiveAge(now) >= cell.lifetime;
        });
    }

    private static void tryNaturalSpawn(ServerLevel level, DimensionState state, long now) {
        if (state.cells.size() >= Math.max(0, FlowingFluids.config.heavyRainMaxCells)) {
            return;
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        float chance = FlowingFluids.config.heavyRainCellChancePerRoll;
        if (level.isThundering()) {
            chance *= 2.0f;
        }
        // Convective downpours need moist air; a deep drought suppresses them.
        chance *= (float) (1.0 - 0.8 * DryingEventSystem.getDroughtIndex(level));
        chance *= (float) SeasonClimate.heavyRainMultiplier(level);
        RandomSource random = level.random;
        if (random.nextFloat() >= Math.min(1.0f, chance)) {
            return;
        }

        ServerPlayer target = players.get(random.nextInt(players.size()));
        if (!level.isRainingAt(level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, target.blockPosition()))) {
            // Deserts, snow and caves-only players do not get downpours.
            return;
        }
        int radius = jitter(random, FlowingFluids.config.heavyRainRadius, 0.35);
        int lifetime = jitter(random, FlowingFluids.config.heavyRainDurationTicks, 0.35);
        // Spawn upwind so the drift carries the core across the player about a third into its life.
        double drift = (MIN_DRIFT_PER_TICK + MAX_DRIFT_PER_TICK) * 0.5;
        double upwind = drift * lifetime * 0.35 + radius * 0.25;
        double lateral = (random.nextDouble() - 0.5) * radius;
        double windX = -Math.sin(state.windHeading);
        double windZ = Math.cos(state.windHeading);
        double x = target.getX() - windX * upwind - windZ * lateral;
        double z = target.getZ() - windZ * upwind + windX * lateral;
        spawn(level, state, x, z, radius, lifetime);

        if (FlowingFluids.config.announceHeavyRain) {
            Component message = Component.literal("雨雲が発達しています。まもなく強い雨が降りそうです。");
            for (ServerPlayer player : players) {
                double dx = player.getX() - x;
                double dz = player.getZ() - z;
                if (dx * dx + dz * dz <= Math.pow(radius + upwind + 64.0, 2)) {
                    player.displayClientMessage(message, true);
                }
            }
        }
    }

    private static void spawn(ServerLevel level, DimensionState state, double x, double z, int radius, int lifetime) {
        RandomSource random = level.random;
        Cell cell = new Cell();
        cell.x = x;
        cell.z = z;
        cell.radius = Math.max(8, radius);
        cell.lifetime = Math.max(200, lifetime);
        cell.bornTick = level.getGameTime();
        cell.peak = Math.max(1.0, FlowingFluids.config.heavyRainPeakMultiplier * (0.8 + random.nextDouble() * 0.4));
        cell.driftPerTick = MIN_DRIFT_PER_TICK + random.nextDouble() * (MAX_DRIFT_PER_TICK - MIN_DRIFT_PER_TICK);
        state.cells.add(cell);
        state.lastTick = level.getGameTime();
    }

    private static int jitter(RandomSource random, int base, double spread) {
        return (int) Math.round(Math.max(1, base) * (1.0 + (random.nextDouble() * 2.0 - 1.0) * spread));
    }

    private static final class DimensionState {
        private final List<Cell> cells = new ArrayList<>();
        private final double prevailingHeading;
        private double windHeading;
        private long nextRollTick;
        private long lastTick;

        private DimensionState(long seed) {
            this.prevailingHeading = HeavyRainMath.prevailingHeading(seed);
            this.windHeading = prevailingHeading;
        }
    }

    private static final class Cell {
        private double x;
        private double z;
        private int radius;
        private long lifetime;
        private long bornTick;
        private long agePenalty;
        private double peak;
        private double driftPerTick;

        private long effectiveAge(long now) {
            return now - bornTick + agePenalty;
        }
    }
}
