package traben.flowing_fluids.season;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import traben.flowing_fluids.FlowingFluids;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional season integration (Serene Seasons, looked up by reflection so there is no hard dependency).
 *
 * <p>Each climate effect has a twelve-step table, one value per sub-season from early spring to late winter:
 * <ul>
 *     <li>winter: frozen ground and ice cover almost stop evaporation and drought build-up; snow, not rain, falls</li>
 *     <li>spring: the snowpack melts, so meltwater multiplies and early/mid spring rivers may rise without rain
 *     (the spring freshet)</li>
 *     <li>summer: heat drives evaporation and drought, and convective downpours are most likely</li>
 *     <li>autumn: long autumn rains bring more heavy-rain cells and drought eases</li>
 * </ul>
 * {@code seasonStrength} scales every deviation from 1, so 0 turns the seasons off without disabling the rest.
 * Without a season mod every multiplier is exactly 1.</p>
 */
public final class SeasonClimate {
    public static final int NO_SEASON = -1;
    private static final long CACHE_TICKS = 100L;

    //                                       spring E  M    L    summer E  M    L    autumn E  M    L    winter E  M    L
    static final double[] DROUGHT     = {0.7, 0.8, 1.0, 1.4, 1.8, 1.6, 1.0, 0.8, 0.6, 0.3, 0.2, 0.3};
    static final double[] EVAPORATION = {0.8, 1.0, 1.2, 1.4, 1.6, 1.5, 1.1, 0.9, 0.6, 0.35, 0.25, 0.35};
    static final double[] HEAVY_RAIN  = {1.0, 1.1, 1.3, 1.6, 1.9, 1.8, 1.5, 1.4, 1.1, 0.5, 0.4, 0.6};
    static final double[] SNOWMELT    = {2.6, 2.2, 1.4, 1.0, 1.0, 1.0, 0.8, 0.6, 0.5, 0.3, 0.3, 0.8};
    static final boolean[] FRESHET    = {true, true, false, false, false, false, false, false, false, false, false, false};

    private static volatile boolean resolved;
    private static Method getSeasonState;
    private static Method getSubSeason;
    private static final ConcurrentHashMap<ResourceKey<Level>, long[]> CACHE = new ConcurrentHashMap<>();

    private SeasonClimate() {
    }

    /**
     * @return 0..11 sub-season index (early spring = 0), or {@link #NO_SEASON}
     */
    public static int subSeason(Level level) {
        if (level == null || FlowingFluids.config == null || !FlowingFluids.config.enableSeasonIntegration) {
            return NO_SEASON;
        }
        long now = level.getGameTime();
        long[] cached = CACHE.get(level.dimension());
        if (cached != null && now - cached[0] >= 0 && now - cached[0] < CACHE_TICKS) {
            return (int) cached[1];
        }
        int index = querySereneSeasons(level);
        CACHE.put(level.dimension(), new long[]{now, index});
        return index;
    }

    public static boolean isActive(Level level) {
        return subSeason(level) != NO_SEASON;
    }

    public static double droughtMultiplier(Level level) {
        return scaled(DROUGHT, subSeason(level));
    }

    public static double evaporationMultiplier(Level level) {
        return scaled(EVAPORATION, subSeason(level));
    }

    public static double heavyRainMultiplier(Level level) {
        return scaled(HEAVY_RAIN, subSeason(level));
    }

    public static double snowmeltMultiplier(Level level) {
        return scaled(SNOWMELT, subSeason(level));
    }

    /**
     * Early and mid spring snowmelt swells rivers even without rain.
     */
    public static boolean isSpringFreshet(Level level) {
        int index = subSeason(level);
        return index != NO_SEASON && FlowingFluids.config.enableSpringFreshet && FRESHET[index];
    }

    static double scaled(double[] table, int index) {
        if (index < 0 || index >= table.length) {
            return 1.0;
        }
        double strength = FlowingFluids.config == null ? 1.0 : Math.max(0.0, FlowingFluids.config.seasonStrength);
        return Math.max(0.0, 1.0 + (table[index] - 1.0) * strength);
    }

    private static int querySereneSeasons(Level level) {
        if (!resolved) {
            resolve();
        }
        if (getSeasonState == null || getSubSeason == null) {
            return NO_SEASON;
        }
        try {
            Object state = getSeasonState.invoke(null, level);
            Object subSeason = state == null ? null : getSubSeason.invoke(state);
            if (subSeason instanceof Enum<?> value && value.ordinal() < 12) {
                return value.ordinal();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A broken or changed season API must never break water ticks.
        }
        return NO_SEASON;
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        try {
            Class<?> helper = Class.forName("sereneseasons.api.season.SeasonHelper");
            Method state = helper.getMethod("getSeasonState", Level.class);
            Method sub = state.getReturnType().getMethod("getSubSeason");
            getSeasonState = state;
            getSubSeason = sub;
            FlowingFluids.info("Serene Seasons detected: seasonal water climate enabled.");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            getSeasonState = null;
            getSubSeason = null;
        }
        resolved = true;
    }
}
