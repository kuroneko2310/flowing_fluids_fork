package traben.flowing_fluids.drying;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class DryingEventSystem {
    private static final long DAY_TICKS = 24000L;
    private static final ConcurrentHashMap<ResourceKey<Level>, DryingState> ACTIVE_STATES = new ConcurrentHashMap<>();

    private DryingEventSystem() {
    }

    public static void onLevelTick(ServerLevel level) {
        if (!FlowingFluids.config.enableMod || FlowingFluids.config.isDimensionExcluded(level) || !isClimateDimension(level)) {
            ACTIVE_STATES.remove(level.dimension());
            return;
        }

        DryingState state = ACTIVE_STATES.computeIfAbsent(level.dimension(), key -> new DryingState(nextDailyRollTick(level.getGameTime())));
        long now = level.getGameTime();

        if (state.heatwaveEndTick > 0L && now >= state.heatwaveEndTick) {
            state.heatwaveEndTick = 0L;
            FlowingFluids.info("Heatwave ended in " + level.dimension().location());
        }
        if (state.drySeasonEndTick > 0L && now >= state.drySeasonEndTick) {
            state.drySeasonEndTick = 0L;
            FlowingFluids.info("Dry season ended in " + level.dimension().location());
        }

        if (now >= state.nextDailyRollTick) {
            state.nextDailyRollTick = nextDailyRollTick(now + 1L);
            rollDailyClimate(level, state, now);
        }
    }

    public static void onLevelUnload(ServerLevel level) {
        ACTIVE_STATES.remove(level.dimension());
    }

    public static void clearAll() {
        ACTIVE_STATES.clear();
    }

    public static float getAmbientEvaporationMultiplier(Level level) {
        if (level == null || level.isClientSide()) {
            return 1.0f;
        }

        DryingState state = ACTIVE_STATES.get(level.dimension());
        if (state == null) {
            return 1.0f;
        }

        long now = level.getGameTime();
        float multiplier = 1.0f;
        if (FlowingFluids.config.enableDrySeasonEvents && state.isDrySeasonActive(now)) {
            multiplier *= Math.max(0.0f, FlowingFluids.config.drySeasonEvaporationMultiplier);
        }
        if (FlowingFluids.config.enableHeatwaveEvents
                && state.isHeatwaveActive(now)
                && (!FlowingFluids.config.heatwaveDaytimeOnly || level.isDay())) {
            multiplier *= Math.max(0.0f, FlowingFluids.config.heatwaveEvaporationMultiplier);
        }
        return Math.max(0.0f, multiplier);
    }

    public static float getRainRefillMultiplier(Level level) {
        if (level == null || level.isClientSide()) {
            return 1.0f;
        }

        DryingState state = ACTIVE_STATES.get(level.dimension());
        if (state == null) {
            return 1.0f;
        }

        long now = level.getGameTime();
        float multiplier = 1.0f;
        if (FlowingFluids.config.enableDrySeasonEvents && state.isDrySeasonActive(now)) {
            multiplier *= Math.max(0.0f, FlowingFluids.config.drySeasonRainRefillMultiplier);
        }
        if (FlowingFluids.config.enableHeatwaveEvents
                && state.isHeatwaveActive(now)
                && (!FlowingFluids.config.heatwaveDaytimeOnly || level.isDay())) {
            multiplier *= Math.max(0.0f, FlowingFluids.config.heatwaveRainRefillMultiplier);
        }
        return Math.max(0.0f, multiplier);
    }

    public static boolean isShadeProtected(Level level, BlockPos pos) {
        if (level == null || !FlowingFluids.config.enableShadeProtection) {
            return false;
        }

        int maxHeight = Mth.clamp(FlowingFluids.config.shadeRoofSearchHeight, 1, 32);
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int i = 0; i < maxHeight; i++) {
            cursor.move(Direction.UP);
            if (cursor.getY() >= level.getMaxBuildHeight()) {
                break;
            }

            BlockState state = level.getBlockState(cursor);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            if (state.is(BlockTags.LEAVES) || state.canOcclude() || state.isFaceSturdy(level, cursor, Direction.DOWN)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasNearbyHeatSource(Level level, BlockPos pos) {
        if (level == null || !FlowingFluids.config.enableHotBlockEvaporation || FlowingFluids.config.hotBlockEvaporationChance <= 0.0f) {
            return false;
        }

        int horizontal = Math.max(0, FlowingFluids.config.hotBlockEvaporationRadius);
        int vertical = Math.max(0, FlowingFluids.config.hotBlockEvaporationVerticalRange);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -horizontal; dx <= horizontal; dx++) {
            for (int dy = -vertical; dy <= vertical; dy++) {
                for (int dz = -horizontal; dz <= horizontal; dz++) {
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (isHotSource(level, cursor)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isRiverDroughtActive(Level level) {
        if (level == null || level.isClientSide() || !FlowingFluids.config.enableRiverDroughts) {
            return false;
        }

        DryingState state = ACTIVE_STATES.get(level.dimension());
        if (state == null) {
            return false;
        }
        return state.isDrySeasonActive(level.getGameTime());
    }

    public static float getRiverDroughtRefillMultiplier(Level level) {
        return isRiverDroughtActive(level)
                ? Mth.clamp(FlowingFluids.config.riverDroughtRefillMultiplier, 0.0f, 1.0f)
                : 1.0f;
    }

    public static float getRiverDroughtDrainChance(Level level) {
        if (!isRiverDroughtActive(level)) {
            return 0.0f;
        }

        float chance = Math.max(0.0f, FlowingFluids.config.riverDroughtDrainChance);
        DryingState state = ACTIVE_STATES.get(level.dimension());
        if (state != null
                && state.isHeatwaveActive(level.getGameTime())
                && (!FlowingFluids.config.heatwaveDaytimeOnly || level.isDay())) {
            chance *= Math.max(1.0f, FlowingFluids.config.riverDroughtHeatwaveDrainBonus);
        }
        return Mth.clamp(chance, 0.0f, 1.0f);
    }

    public static String describeStatus(Level level) {
        if (level == null) {
            return "Drying events status is unavailable here.";
        }

        long now = level.getGameTime();
        DryingState state = ACTIVE_STATES.get(level.dimension());
        boolean heatwave = state != null && state.isHeatwaveActive(now);
        boolean drySeason = state != null && state.isDrySeasonActive(now);

        return "Drying events status"
                + "\nHeatwaves: " + FlowingFluids.config.enableHeatwaveEvents
                + " / active=" + heatwave
                + (heatwave ? " / remaining=" + formatTicks(state.heatwaveEndTick - now) : "")
                + "\nDry seasons: " + FlowingFluids.config.enableDrySeasonEvents
                + " / active=" + drySeason
                + (drySeason ? " / remaining=" + formatTicks(state.drySeasonEndTick - now) : "")
                + "\nRiver droughts: " + FlowingFluids.config.enableRiverDroughts
                + " / active=" + isRiverDroughtActive(level)
                + " / refill_multiplier=" + String.format(Locale.ROOT, "%.2f", getRiverDroughtRefillMultiplier(level))
                + " / drain_chance=" + String.format(Locale.ROOT, "%.2f", getRiverDroughtDrainChance(level))
                + "\nHot block drying: " + FlowingFluids.config.enableHotBlockEvaporation
                + " / chance=" + FlowingFluids.config.hotBlockEvaporationChance
                + " / radius=" + FlowingFluids.config.hotBlockEvaporationRadius
                + "\nShade roof protection: " + FlowingFluids.config.enableShadeProtection
                + " / search_height=" + FlowingFluids.config.shadeRoofSearchHeight
                + "\nAmbient evaporation multiplier now: " + String.format(Locale.ROOT, "%.2f", getAmbientEvaporationMultiplier(level))
                + "\nRain refill multiplier now: " + String.format(Locale.ROOT, "%.2f", getRainRefillMultiplier(level));
    }

    private static void rollDailyClimate(ServerLevel level, DryingState state, long now) {
        RandomSource random = level.getRandom();

        if (FlowingFluids.config.enableDrySeasonEvents
                && !state.isDrySeasonActive(now)
                && random.nextFloat() < Mth.clamp(FlowingFluids.config.drySeasonStartChancePerDay, 0.0f, 1.0f)) {
            int duration = sampleDuration(random, FlowingFluids.config.drySeasonMinDurationTicks, FlowingFluids.config.drySeasonMaxDurationTicks);
            state.drySeasonEndTick = now + duration;
            FlowingFluids.info("Dry season started in " + level.dimension().location() + " for " + formatTicks(duration));
        }

        float heatwaveChance = Mth.clamp(FlowingFluids.config.heatwaveStartChancePerDay, 0.0f, 1.0f);
        if (state.isDrySeasonActive(now)) {
            heatwaveChance = Mth.clamp(heatwaveChance * 1.35f, 0.0f, 1.0f);
        }

        if (FlowingFluids.config.enableHeatwaveEvents
                && !state.isHeatwaveActive(now)
                && random.nextFloat() < heatwaveChance) {
            int duration = sampleDuration(random, FlowingFluids.config.heatwaveMinDurationTicks, FlowingFluids.config.heatwaveMaxDurationTicks);
            state.heatwaveEndTick = now + duration;
            FlowingFluids.info("Heatwave started in " + level.dimension().location() + " for " + formatTicks(duration));
        }
    }

    private static boolean isClimateDimension(ServerLevel level) {
        return level.dimensionType().hasSkyLight() && !level.dimensionType().ultraWarm();
    }

    private static long nextDailyRollTick(long now) {
        long remainder = Math.floorMod(now, DAY_TICKS);
        long offset = DAY_TICKS - remainder;
        return now + (offset == 0L ? DAY_TICKS : offset);
    }

    private static int sampleDuration(RandomSource random, int minTicks, int maxTicks) {
        int min = Math.max(20, Math.min(minTicks, maxTicks));
        int max = Math.max(min, Math.max(minTicks, maxTicks));
        return min == max ? min : random.nextInt(max - min + 1) + min;
    }

    private static boolean isHotSource(Level level, BlockPos pos) {
        if (level.getFluidState(pos).getType().isSame(Fluids.LAVA)) {
            return true;
        }

        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.LAVA_CAULDRON)) {
            return true;
        }

        Block block = state.getBlock();
        if (block instanceof CampfireBlock || block instanceof AbstractFurnaceBlock) {
            return state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
        }

        return false;
    }

    public static boolean shouldRiverDroughtDrain(Level level, BlockPos pos, int amount) {
        if (!isRiverDroughtActive(level) || amount <= 0 || amount > Math.max(1, FlowingFluids.config.riverDroughtMaxAffectedLevel)) {
            return false;
        }
        var biome = level.getBiome(pos);
        if (!FFFluidUtils.isRiverBiome(biome)) {
            return false;
        }
        if (!level.canSeeSky(pos.above()) || level.isRainingAt(pos.above())) {
            return false;
        }
        if (AdaptiveTickScheduler.isFlowActiveNow(level, pos)) {
            return false;
        }
        if (FFFluidUtils.isProtectedInfiniteBiomeWater(level, pos, Fluids.WATER, amount)) {
            return false;
        }

        FluidState below = FFFluidUtils.getEffectiveFluidState(level, pos.below(), level.getBlockState(pos.below()));
        boolean supportedBelow = below.getType().isSame(Fluids.WATER) && below.getAmount() >= Math.max(1, amount);
        int lateralWaterNeighbors = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, direction);
            FluidState neighbor = FFFluidUtils.getEffectiveFluidState(level, cursor, level.getBlockState(cursor));
            if (neighbor.getType().isSame(Fluids.WATER) && neighbor.getAmount() > 0) {
                lateralWaterNeighbors++;
            }
        }

        boolean nearSurface = pos.getY() >= level.getSeaLevel() - 1;
        boolean thinWater = amount <= 2;
        boolean emptyAbove = level.getFluidState(pos.above()).isEmpty();
        boolean edgeLike = lateralWaterNeighbors <= 1 || !supportedBelow;
        return edgeLike && (nearSurface || thinWater || emptyAbove);
    }

    private static String formatTicks(long ticks) {
        double seconds = Math.max(0L, ticks) / 20.0D;
        if (seconds >= 60.0D) {
            return String.format(Locale.ROOT, "%.1f min", seconds / 60.0D);
        }
        return String.format(Locale.ROOT, "%.1f s", seconds);
    }

    private static final class DryingState {
        private long nextDailyRollTick;
        private long heatwaveEndTick;
        private long drySeasonEndTick;

        private DryingState(long nextDailyRollTick) {
            this.nextDailyRollTick = nextDailyRollTick;
        }

        private boolean isHeatwaveActive(long now) {
            return heatwaveEndTick > now;
        }

        private boolean isDrySeasonActive(long now) {
            return drySeasonEndTick > now;
        }
    }
}
