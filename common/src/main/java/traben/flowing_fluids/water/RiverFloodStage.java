package traben.flowing_fluids.water;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.api.FlowingFluidsAPI;
import traben.flowing_fluids.drying.DroughtMath;
import traben.flowing_fluids.drying.DryingEventSystem;
import traben.flowing_fluids.flood.FloodEventSystem;
import traben.flowing_fluids.season.SeasonClimate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rain-driven high water ("増水") and river flooding ("氾濫") for rivers and seas.
 *
 * <p>Normally river and ocean biomes are held near sea level: thin sea-level surface water is drained and water
 * standing above sea level is evaporated as overflow. While it rains that cap is lifted completely.</p>
 *
 * <p>On top of that each dimension tracks a persisted river stage (see {@link RiverFloodMath}). Rain drives it part
 * way up, a thunderstorm drives it to {@code riverFloodMaxStage} blocks above sea level. While the stage rises, water
 * is fed into river-biome water near players until the river surface reaches the stage (upstream inflow), so rivers
 * visibly swell, burst their banks and flood the floodplain. The stage maps onto the five river warning levels;
 * players near a river get escalating warnings, and at level 5 (氾濫発生) a flood event is started on a nearby river.</p>
 *
 * <p>When the rain stops the stage falls over {@code riverFloodRecessionTicks}. Overflow above the falling stage line
 * is removed gradually (the recession factor ramps from 0 to 1 with a smoothstep), so high water recedes over a few
 * minutes instead of vanishing in one tick.</p>
 */
public final class RiverFloodStage {
    private RiverFloodStage() {
    }

    private static final int STAGE_UPDATE_INTERVAL_TICKS = 20;
    private static final int INFLOW_INTERVAL_TICKS = 5;
    private static final int INFLOW_RADIUS = 40;
    private static final int MAX_INFLOW_PLACEMENTS_PER_PULSE = 48;
    private static final int WARNING_INTERVAL_TICKS = 100;
    private static final int WARNING_REPEAT_TICKS = 600;
    private static final int WARNING_SAMPLE_RADIUS = 16;
    private static final long FLOOD_EVENT_COOLDOWN_TICKS = 1200L;
    private static final long RIVER_HIT_MEMORY_TICKS = 1200L;

    private static volatile FlowingFluidsAPI fluidsApi;
    private static final ConcurrentHashMap<UUID, Warning> LAST_WARNINGS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ResourceKey<Level>, DimensionState> STATES = new ConcurrentHashMap<>();

    private static final String[] LEVEL_NAMES = {
            "平常",
            "水防団待機水位",
            "氾濫注意水位",
            "避難判断水位",
            "氾濫危険水位",
            "氾濫発生"
    };
    private static final ChatFormatting[] LEVEL_COLORS = {
            ChatFormatting.GRAY,
            ChatFormatting.WHITE,
            ChatFormatting.YELLOW,
            ChatFormatting.RED,
            ChatFormatting.LIGHT_PURPLE,
            ChatFormatting.DARK_RED
    };

    public static void onLevelTick(ServerLevel level) {
        if (level.isRaining() || SeasonClimate.isSpringFreshet(level)) {
            // Persisted, so a restart during the recession keeps the flood receding instead of snapping back.
            WeatherWaterSavedData.get(level).setLastRainTick(level.getGameTime());
        }
        if (!isEnabled(level)) {
            STATES.remove(level.dimension());
            return;
        }
        DimensionState state = STATES.computeIfAbsent(level.dimension(), key -> new DimensionState());
        long now = level.getGameTime();
        if (!state.loaded) {
            state.stage = WeatherWaterSavedData.get(level).riverStage();
            state.announcedLevel = RiverFloodMath.warningLevel(state.stage);
            state.lastUpdateTick = now;
            state.loaded = true;
        }
        if (now - state.lastUpdateTick >= STAGE_UPDATE_INTERVAL_TICKS) {
            updateStage(level, state, now);
        }
        if (isRising(level) && Math.floorMod(now, INFLOW_INTERVAL_TICKS) == 0) {
            feedRivers(level, state, now);
        }
        maybeStartRiverFlood(level, state, now);
        if (FlowingFluids.config.enableFloodWarnings) {
            announceLevelChange(level, state);
            if (state.stage > 0.0) {
                for (ServerPlayer player : level.players()) {
                    if (Math.floorMod(now + player.getId(), WARNING_INTERVAL_TICKS) == 0) {
                        warnPlayer(level, state, player, now);
                    }
                }
            }
        }
    }

    public static void clearDimension(Level level) {
        if (level != null) {
            STATES.remove(level.dimension());
        }
    }

    private static void updateStage(ServerLevel level, DimensionState state, long now) {
        long elapsed = Math.min(now - state.lastUpdateTick, 24000L);
        state.lastUpdateTick = now;
        double target = currentTarget(level);
        double rate = target >= state.stage
                ? Math.max(0.0f, FlowingFluids.config.riverFloodRisePerDay)
                : RiverFloodMath.recessionRatePerDay(FlowingFluids.config.riverFloodRecessionTicks);
        state.stage = RiverFloodMath.step(state.stage, elapsed, target, rate);
        WeatherWaterSavedData.get(level).setRiverStage(state.stage);
    }

    private static double currentTarget(ServerLevel level) {
        boolean raining = level.isRaining();
        double soak = DroughtMath.rainRefillMultiplier(DryingEventSystem.getDroughtIndex(level));
        double downpour = Math.min(1.5, SeasonClimate.heavyRainMultiplier(level));
        return RiverFloodMath.target(FlowingFluids.config.riverFloodRainStage, raining, raining && level.isThundering(),
                SeasonClimate.isSpringFreshet(level), soak, downpour);
    }

    /**
     * Upstream inflow: tops river-biome water near players up to the current stage. Samples are cheap (a heightmap
     * and a stored-biome read); only loaded chunks are touched and the placements per pulse are capped.
     */
    private static void feedRivers(ServerLevel level, DimensionState state, long now) {
        double stageBlocks = stageBlocks(state.stage);
        int samples = Math.max(0, FlowingFluids.config.riverFloodInflowSamples);
        if (stageBlocks < 0.125 || samples == 0) {
            return;
        }
        int seaLevel = FFFluidUtils.seaLevel(level);
        int maxPerPlacement = FlowingFluids.config.riverFloodInflowAmount;
        RandomSource random = level.random;
        FlowingFluidsAPI api = fluidsApi();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int placements = 0;
        for (ServerPlayer player : level.players()) {
            for (int i = 0; i < samples && placements < MAX_INFLOW_PLACEMENTS_PER_PULSE; i++) {
                int x = player.getBlockX() + random.nextInt(INFLOW_RADIUS * 2 + 1) - INFLOW_RADIUS;
                int z = player.getBlockZ() + random.nextInt(INFLOW_RADIUS * 2 + 1) - INFLOW_RADIUS;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                if (!findSurfaceWater(level, x, z, cursor) || cursor.getY() < seaLevel - 1
                        || !FFFluidUtils.isRiverBiome(coarseBiome(level, cursor))) {
                    continue;
                }
                state.lastRiverHit = cursor.immutable();
                state.lastRiverHitTick = now;
                int current = FFFluidUtils.getEffectiveFluidState(level, cursor).getAmount();
                double surfaceExcess = cursor.getY() + current / 8.0 - seaLevel;
                int amount = RiverFloodMath.inflowAmount(stageBlocks, surfaceExcess, maxPerPlacement);
                if (amount <= 0) {
                    continue;
                }
                if (current >= 8) {
                    cursor.move(0, 1, 0);
                }
                if (api.placeFluidAmountFromPos(level, cursor.immutable(), Fluids.WATER, amount, false, true) < amount) {
                    placements++;
                }
            }
        }
    }

    /**
     * Finds the top water block of a column (stepping below one non-water block such as a lily pad).
     */
    private static boolean findSurfaceWater(ServerLevel level, int x, int z, BlockPos.MutableBlockPos out) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        for (int step = 0; step < 2; step++) {
            out.set(x, y - step, z);
            FluidState fluid = FFFluidUtils.getEffectiveFluidState(level, out);
            if (fluid.is(FluidTags.WATER) && fluid.getAmount() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * At warning level 5 a flood event is started on a river a player was recently near, so the floodplain around
     * it fills even where the swollen channel alone would not reach.
     */
    private static void maybeStartRiverFlood(ServerLevel level, DimensionState state, long now) {
        if (!FlowingFluids.config.riverFloodTriggersFloodEvents
                || !FlowingFluids.config.enableFloodEvents
                || !isRising(level)
                || RiverFloodMath.warningLevel(state.stage) < RiverFloodMath.MAX_LEVEL
                || now < state.nextFloodEventTick
                || state.lastRiverHit == null
                || now - state.lastRiverHitTick > RIVER_HIT_MEMORY_TICKS
                || FloodEventSystem.hasActiveFlood(level)) {
            return;
        }
        int seaLevel = FFFluidUtils.seaLevel(level);
        int waterline = seaLevel - 1 + (int) Math.ceil(stageBlocks(state.stage));
        int duration = Math.max(20, FlowingFluids.config.floodDefaultDurationTicks);
        BlockPos center = state.lastRiverHit;
        if (FloodEventSystem.startFlood(level, center, FlowingFluids.config.floodDefaultRadius, duration, waterline)) {
            state.nextFloodEventTick = now + duration + FLOOD_EVENT_COOLDOWN_TICKS;
            if (FlowingFluids.config.announceFloodEvents) {
                broadcast(level, Component.literal(String.format(Locale.ROOT,
                                "【氾濫発生情報】%d, %d 付近で川が氾濫しました。川沿いの低地はただちに高い場所へ避難してください。",
                                center.getX(), center.getZ()))
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
            }
        }
    }

    private static void announceLevelChange(ServerLevel level, DimensionState state) {
        int current = RiverFloodMath.warningLevel(state.stage);
        if (current == state.announcedLevel) {
            return;
        }
        int previous = state.announcedLevel;
        state.announcedLevel = current;
        if (current > previous && current >= 3) {
            broadcast(level, levelLabel(current).append(Component.literal(String.format(Locale.ROOT,
                    " 河川の水位が海面より約%.1fブロックに達しました。%s", stageBlocks(state.stage), advice(current)))));
        } else if (current == 0 && previous >= 2) {
            broadcast(level, Component.literal("【増水解除】河川の水位は平常に戻りつつあります。").withStyle(ChatFormatting.GREEN));
        }
    }

    /**
     * Samples the player's column and a ring around it for river/sea water standing above sea level. Only loaded
     * chunks are read. A warning repeats at most every 30 seconds unless the warning level has changed since.
     */
    private static void warnPlayer(ServerLevel level, DimensionState state, ServerPlayer player, long now) {
        int threat = RiverFloodMath.warningLevel(state.stage);
        if (threat <= 0) {
            return;
        }
        int seaLevel = FFFluidUtils.seaLevel(level);
        double highest = Double.NEGATIVE_INFINITY;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = -1; i < 8; i++) {
            double angle = i * (Math.PI / 4.0);
            int x = player.getBlockX() + (i < 0 ? 0 : (int) Math.round(Math.cos(angle) * WARNING_SAMPLE_RADIUS));
            int z = player.getBlockZ() + (i < 0 ? 0 : (int) Math.round(Math.sin(angle) * WARNING_SAMPLE_RADIUS));
            if (!level.hasChunk(x >> 4, z >> 4) || !findSurfaceWater(level, x, z, cursor)) {
                continue;
            }
            Holder<Biome> biome = coarseBiome(level, cursor);
            if (!FFFluidUtils.isRiverBiome(biome) && !FFFluidUtils.isOceanBiome(biome)) {
                continue;
            }
            int amount = FFFluidUtils.getEffectiveFluidState(level, cursor).getAmount();
            highest = Math.max(highest, cursor.getY() + amount / 8.0 - seaLevel);
        }
        if (highest == Double.NEGATIVE_INFINITY) {
            // No river or sea nearby: nothing to warn about here.
            return;
        }
        Warning last = LAST_WARNINGS.get(player.getUUID());
        if (last != null && now - last.tick < WARNING_REPEAT_TICKS && threat == last.level) {
            return;
        }
        LAST_WARNINGS.put(player.getUUID(), new Warning(now, threat));
        String trend = isRising(level) ? "上昇中" : "下降中";
        player.displayClientMessage(levelLabel(threat).append(Component.literal(String.format(Locale.ROOT,
                " 近くの水位 海面+%.1f / 河川水位 +%.1f（%s）", Math.max(0.0, highest), stageBlocks(state.stage), trend))), true);
        if (threat >= 4 && (last == null || threat > last.level)) {
            player.playNotifySound(SoundEvents.BELL_BLOCK, SoundSource.WEATHER, 1.0f, threat >= 5 ? 0.6f : 0.8f);
        }
    }

    private static MutableComponent levelLabel(int level) {
        int clamped = Math.max(0, Math.min(RiverFloodMath.MAX_LEVEL, level));
        return Component.literal("【警戒レベル" + clamped + " " + LEVEL_NAMES[clamped] + "】")
                .withStyle(LEVEL_COLORS[clamped], ChatFormatting.BOLD);
    }

    private static String advice(int level) {
        return switch (level) {
            case 3 -> "川沿いの低地では避難の準備をしてください。";
            case 4 -> "川沿いの低地から高い場所へ避難してください。";
            case 5 -> "氾濫が発生しています。命を守る行動をとってください。";
            default -> "今後の水位に注意してください。";
        };
    }

    private static void broadcast(ServerLevel level, Component message) {
        List<ServerPlayer> players = level.players();
        for (ServerPlayer player : players) {
            player.sendSystemMessage(message);
        }
    }

    private record Warning(long tick, int level) {
    }

    private static final class DimensionState {
        private double stage;
        private boolean loaded;
        private long lastUpdateTick;
        private int announcedLevel;
        private long nextFloodEventTick;
        private BlockPos lastRiverHit;
        private long lastRiverHitTick;
    }

    private static FlowingFluidsAPI fluidsApi() {
        FlowingFluidsAPI api = fluidsApi;
        if (api == null) {
            api = FlowingFluidsAPI.getInstance(FlowingFluids.MOD_ID);
            fluidsApi = api;
        }
        return api;
    }

    private static boolean isEnabled(Level level) {
        return level != null
                && FlowingFluids.config != null
                && FlowingFluids.config.enableMod
                && FlowingFluids.config.enableRiverFloodStage
                && level.dimensionType().hasSkyLight();
    }

    private static boolean isRising(Level level) {
        return level.isRaining() || SeasonClimate.isSpringFreshet(level);
    }

    private static double stageBlocks(double stage) {
        return stage * Math.max(1, FlowingFluids.config.riverFloodMaxStage);
    }

    /**
     * Current river stage share in [0, 1]; 0 on clients and when high water is disabled.
     */
    public static double getStage(Level level) {
        if (!isEnabled(level) || level.isClientSide()) {
            return 0.0;
        }
        DimensionState state = STATES.get(level.dimension());
        return state == null ? 0.0 : state.stage;
    }

    /**
     * Current river stage in blocks above sea level.
     */
    public static double getStageBlocks(Level level) {
        return stageBlocks(getStage(level));
    }

    public static int getWarningLevel(Level level) {
        return RiverFloodMath.warningLevel(getStage(level));
    }

    public static void setStage(ServerLevel level, double stage) {
        DimensionState state = STATES.computeIfAbsent(level.dimension(), key -> new DimensionState());
        state.stage = RiverFloodMath.clamp01(stage);
        state.loaded = true;
        state.lastUpdateTick = level.getGameTime();
        WeatherWaterSavedData.get(level).setRiverStage(state.stage);
    }

    public static String describe(ServerLevel level) {
        double stage = getStage(level);
        int threat = RiverFloodMath.warningLevel(stage);
        return "増水・氾濫"
                + "\n有効: " + (isEnabled(level) ? "ON" : "OFF")
                + " / 状態: " + (isRising(level) ? (level.isThundering() ? "雷雨で増水中" : "増水中") : "減水中")
                + "\n河川水位: 海面+" + String.format(Locale.ROOT, "%.2f", stageBlocks(stage))
                + " / 最大+" + FlowingFluids.config.riverFloodMaxStage
                + " (" + String.format(Locale.ROOT, "%.0f", stage * 100.0) + "%)"
                + "\n警戒レベル: " + threat + " " + LEVEL_NAMES[threat]
                + "\n目標水位: " + String.format(Locale.ROOT, "%.0f", currentTarget(level) * 100.0) + "%"
                + " / 上昇速度: " + FlowingFluids.config.riverFloodRisePerDay + "/日"
                + " / 減水: " + FlowingFluids.config.riverFloodRecessionTicks / 20 + "秒"
                + "\n海面上限係数: " + String.format(Locale.ROOT, "%.2f", getCapFactor(level))
                + " / 流入サンプル: " + FlowingFluids.config.riverFloodInflowSamples
                + " / 氾濫で洪水イベント: " + (FlowingFluids.config.riverFloodTriggersFloodEvents ? "ON" : "OFF");
    }

    /**
     * True while it rains, or during the spring snowmelt freshet: the river/sea height cap is lifted entirely.
     */
    public static boolean isHighWater(Level level) {
        return isEnabled(level) && isRising(level);
    }

    /**
     * 0 while it rains (no cap), rising smoothly to 1 (normal cap) after the rain stops.
     */
    public static float getCapFactor(Level level) {
        if (!isEnabled(level)) {
            return 1.0f;
        }
        if (isRising(level)) {
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
     * Tick delay multiplier (< 1 = faster) for swollen water. The higher the river stage, the faster it runs:
     * {@code base^(0.5 + stage)}, so the configured value applies at half stage and a flood runs faster still.
     */
    public static float getFlowDelayMultiplier(Level level, BlockPos pos) {
        if (!isSwollenWater(level, pos)) {
            return 1.0f;
        }
        float base = Math.max(0.1f, Math.min(1.0f, FlowingFluids.config.riverFloodFlowDelayMultiplier));
        return Math.max(0.1f, (float) Math.pow(base, 0.5 + getStage(level)));
    }

    /**
     * Current push multiplier (> 1 = stronger) for entities in swollen river water. A thunderstorm flood pushes
     * harder still ({@code base^1.5}). Only weather the client also knows is used, so a player's own client-side
     * movement agrees with the server.
     */
    public static double getCurrentPushMultiplier(Level level, BlockPos pos) {
        if (!isHighWater(level) || pos.getY() < FFFluidUtils.seaLevel(level) - 1
                || !FFFluidUtils.isRiverBiome(coarseBiome(level, pos))) {
            return 1.0;
        }
        double base = Math.max(1.0, FlowingFluids.config.riverFloodCurrentPushMultiplier);
        return level.isThundering() ? Math.pow(base, 1.5) : base;
    }
}
