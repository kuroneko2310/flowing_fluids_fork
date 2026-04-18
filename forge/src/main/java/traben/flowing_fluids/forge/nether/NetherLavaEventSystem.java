package traben.flowing_fluids.forge.nether;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.api.FlowingFluidsAPI;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class NetherLavaEventSystem {
    private static final FlowingFluidsAPI FLUIDS_API = FlowingFluidsAPI.getInstance(FlowingFluids.MOD_ID);
    private static final long DAY_TICKS = 24000L;
    private static final ConcurrentHashMap<ResourceKey<Level>, NetherLavaState> ACTIVE_STATES = new ConcurrentHashMap<>();

    private NetherLavaEventSystem() {
    }

    public static void onLevelTick(ServerLevel level) {
        if (!shouldRun(level)) {
            ACTIVE_STATES.remove(level.dimension());
            return;
        }

        NetherLavaState state = ACTIVE_STATES.computeIfAbsent(level.dimension(),
                ignored -> new NetherLavaState(nextDailyRollTick(level.getGameTime())));
        long now = level.getGameTime();

        expireEndedEvents(level, state, now);

        if (now >= state.nextDailyRollTick) {
            state.nextDailyRollTick = nextDailyRollTick(now + 1L);
            rollDailyEvents(level, state);
        }

        for (NetherLavaEventType type : NetherLavaEventType.values()) {
            ActiveNetherLavaEvent event = state.activeEvents.get(type);
            if (event == null || now < event.nextPulseTick) {
                continue;
            }
            event.nextPulseTick = now + event.pulseIntervalTicks;
            processPulse(level, event);
        }
    }

    public static void onLevelUnload(ServerLevel level) {
        ACTIVE_STATES.remove(level.dimension());
    }

    public static void clearDimension(ServerLevel level) {
        if (level == null) {
            return;
        }
        ACTIVE_STATES.remove(level.dimension());
    }

    public static void clearAll() {
        ACTIVE_STATES.clear();
    }

    public static boolean startEvent(ServerLevel level, NetherLavaEventType type, BlockPos center, int radius, int durationTicks) {
        if (!shouldRun(level)) {
            return false;
        }

        NetherLavaState state = ACTIVE_STATES.computeIfAbsent(level.dimension(),
                ignored -> new NetherLavaState(nextDailyRollTick(level.getGameTime())));
        ActiveNetherLavaEvent event = createEvent(level, type, center, radius, durationTicks);
        state.activeEvents.put(type, event);
        FlowingFluids.info("Nether lava event started: " + type.id + " in " + level.dimension().location()
                + " around " + formatPos(center) + " radius=" + event.radius + " duration=" + formatTicks(durationTicks));
        return true;
    }

    public static boolean stopAll(ServerLevel level) {
        NetherLavaState state = ACTIVE_STATES.get(level.dimension());
        if (state == null || state.activeEvents.isEmpty()) {
            return false;
        }
        state.activeEvents.clear();
        return true;
    }

    public static String describeStatus(Level level, BlockPos referencePos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return "ネザー溶岩イベントはサーバー側でのみ管理されています。";
        }
        if (!serverLevel.dimensionType().ultraWarm()) {
            return "この次元は灼熱次元ではないので、ネザー溶岩イベントは発生しません。";
        }

        NetherLavaState state = ACTIVE_STATES.get(serverLevel.dimension());
        if (state == null || state.activeEvents.isEmpty()) {
            return "ネザー溶岩イベントは有効ですが、いま動いているものはありません。";
        }

        long now = serverLevel.getGameTime();
        StringBuilder builder = new StringBuilder("発生中のネザー溶岩イベント");
        for (NetherLavaEventType type : NetherLavaEventType.values()) {
            ActiveNetherLavaEvent event = state.activeEvents.get(type);
            if (event == null) {
                continue;
            }
            double distance = Math.sqrt(horizontalDistSqr(referencePos, event.center));
            builder.append("\n- ").append(type.displayName)
                    .append(": 中心=").append(formatPos(event.center))
                    .append(", 半径=").append(event.radius)
                    .append(", 残り=").append(formatTicks(event.endTick - now))
                    .append(", 距離=").append(String.format(Locale.ROOT, "%.1f", distance));
            if (event.pathDirection != null) {
                builder.append(", 流向=").append(directionName(event.pathDirection));
            }
        }
        return builder.toString();
    }

    public static int getSpringEmissionBonus(Level level, BlockPos springPos, Direction growthDirection, Fluid fluid) {
        if (!(level instanceof ServerLevel serverLevel)
                || !fluid.isSame(Fluids.LAVA)
                || !serverLevel.dimensionType().ultraWarm()) {
            return 0;
        }

        NetherLavaState state = ACTIVE_STATES.get(serverLevel.dimension());
        if (state == null || state.activeEvents.isEmpty()) {
            return 0;
        }

        int bonus = 0;
        if (isInside(state.activeEvents.get(NetherLavaEventType.LAVA_TIDE), springPos)) {
            bonus += 2;
        }
        if (isInside(state.activeEvents.get(NetherLavaEventType.LAVA_PATHS), springPos)) {
            bonus += 1;
            if (growthDirection == Direction.UP) {
                bonus += 1;
            }
        }
        if (isInside(state.activeEvents.get(NetherLavaEventType.EMBER_STORM), springPos) && growthDirection == Direction.UP) {
            bonus += 1;
        }
        return bonus;
    }

    public static int getSpringHeightBonus(Level level, BlockPos springPos, Direction growthDirection, Fluid fluid) {
        if (!(level instanceof ServerLevel serverLevel)
                || growthDirection != Direction.UP
                || !fluid.isSame(Fluids.LAVA)
                || !serverLevel.dimensionType().ultraWarm()) {
            return 0;
        }

        NetherLavaState state = ACTIVE_STATES.get(serverLevel.dimension());
        if (state == null || state.activeEvents.isEmpty()) {
            return 0;
        }

        int bonus = 0;
        if (isInside(state.activeEvents.get(NetherLavaEventType.LAVA_TIDE), springPos)) {
            bonus += 1;
        }
        if (isInside(state.activeEvents.get(NetherLavaEventType.LAVA_PATHS), springPos)) {
            bonus += 1;
        }
        return bonus;
    }

    private static boolean shouldRun(ServerLevel level) {
        return FlowingFluids.config.enableMod
                && FlowingFluids.config.enableNetherLavaEvents
                && !FlowingFluids.config.isDimensionExcluded(level)
                && level.dimensionType().ultraWarm();
    }

    private static void expireEndedEvents(ServerLevel level, NetherLavaState state, long now) {
        state.activeEvents.entrySet().removeIf(entry -> {
            boolean expired = now >= entry.getValue().endTick;
            if (expired) {
                FlowingFluids.info("Nether lava event ended: " + entry.getKey().id + " in " + level.dimension().location());
            }
            return expired;
        });
    }

    private static void rollDailyEvents(ServerLevel level, NetherLavaState state) {
        Player anchor = pickAnchorPlayer(level);
        if (anchor == null) {
            return;
        }

        RandomSource random = level.getRandom();
        for (NetherLavaEventType type : NetherLavaEventType.values()) {
            if (state.activeEvents.containsKey(type)) {
                continue;
            }

            float scaledChance = Mth.clamp(type.baseDailyChance * FlowingFluids.config.netherLavaEventStartChancePerDay, 0.0f, 1.0f);
            if (random.nextFloat() >= scaledChance) {
                continue;
            }

            BlockPos center = anchor.blockPosition().offset(random.nextInt(25) - 12, random.nextInt(13) - 6, random.nextInt(25) - 12);
            int duration = sampleDuration(random,
                    FlowingFluids.config.netherLavaEventMinDurationTicks,
                    FlowingFluids.config.netherLavaEventMaxDurationTicks);
            state.activeEvents.put(type, createEvent(level, type, center,
                    FlowingFluids.config.netherLavaEventDefaultRadius, duration));
            FlowingFluids.info("Nether lava event started: " + type.id + " in " + level.dimension().location()
                    + " around " + formatPos(center) + " for " + formatTicks(duration));
        }
    }

    private static ActiveNetherLavaEvent createEvent(ServerLevel level, NetherLavaEventType type, BlockPos center, int radius, int durationTicks) {
        int pulseInterval = Math.max(2, FlowingFluids.config.netherLavaEventPulseIntervalTicks + type.pulseOffset);
        Direction pathDirection = type == NetherLavaEventType.LAVA_PATHS
                ? Direction.Plane.HORIZONTAL.getRandomDirection(level.random)
                : null;
        long now = level.getGameTime();
        return new ActiveNetherLavaEvent(
                type,
                center.immutable(),
                Math.max(12, radius),
                pulseInterval,
                now,
                now + Math.max(40, durationTicks),
                now,
                pathDirection
        );
    }

    private static void processPulse(ServerLevel level, ActiveNetherLavaEvent event) {
        switch (event.type) {
            case LAVA_TIDE -> processLavaTidePulse(level, event);
            case BASALT_WAVE -> processBasaltWavePulse(level, event);
            case EMBER_STORM -> processEmberStormPulse(level, event);
            case LAVA_PATHS -> processLavaPathPulse(level, event);
        }
    }

    private static void processLavaTidePulse(ServerLevel level, ActiveNetherLavaEvent event) {
        RandomSource random = level.getRandom();
        int placements = 14;
        int successes = 0;
        BlockPos.MutableBlockPos lavaPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos targetPos = new BlockPos.MutableBlockPos();

        for (int attempts = 0; attempts < placements * 6 && successes < placements; attempts++) {
            if (!pickNearbyLava(level, event, lavaPos, random)) {
                continue;
            }

            targetPos.set(lavaPos);
            int climb = 0;
            while (climb++ < 5 && level.getFluidState(targetPos.above()).getType().isSame(Fluids.LAVA)) {
                targetPos.move(Direction.UP);
            }
            targetPos.move(Direction.UP);
            BlockState targetState = level.getBlockState(targetPos);
            if (!FFFluidUtils.canStorePartialFluidAmount(level, targetPos, targetState, Fluids.LAVA)) {
                continue;
            }

            int amount = 4 + random.nextInt(5);
            int remainder = FLUIDS_API.placeFluidAmountFromPos(level, targetPos.immutable(), Fluids.LAVA, amount, true, false);
            if (remainder < amount) {
                successes++;
            }
        }
    }

    private static void processBasaltWavePulse(ServerLevel level, ActiveNetherLavaEvent event) {
        RandomSource random = level.getRandom();
        int conversions = 10;
        int successes = 0;
        BlockPos.MutableBlockPos lavaPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos targetPos = new BlockPos.MutableBlockPos();
        Direction[] directions = Direction.values();

        for (int attempts = 0; attempts < conversions * 8 && successes < conversions; attempts++) {
            if (!pickNearbyLava(level, event, lavaPos, random)) {
                continue;
            }

            Direction direction = directions[random.nextInt(directions.length)];
            targetPos.setWithOffset(lavaPos, direction);
            BlockState targetState = level.getBlockState(targetPos);
            BlockState replacement = chooseBasaltWaveReplacement(targetState, random);
            if (replacement == null || replacement == targetState) {
                continue;
            }

            level.setBlock(targetPos, replacement, 3);
            successes++;
        }
    }

    private static void processEmberStormPulse(ServerLevel level, ActiveNetherLavaEvent event) {
        RandomSource random = level.getRandom();
        List<? extends Player> players = level.players();
        for (Player player : players) {
            BlockPos playerPos = player.blockPosition();
            if (horizontalDistSqr(playerPos, event.center) > (double) event.radius * event.radius) {
                continue;
            }

            level.sendParticles(ParticleTypes.LAVA,
                    player.getX(), player.getEyeY() + 0.4D, player.getZ(),
                    8, 1.2D, 0.6D, 1.2D, 0.01D);
            level.sendParticles(ParticleTypes.SMOKE,
                    player.getX(), player.getEyeY() + 0.2D, player.getZ(),
                    10, 1.4D, 0.8D, 1.4D, 0.02D);
            level.sendParticles(ParticleTypes.WHITE_ASH,
                    player.getX(), player.getEyeY() + 1.0D, player.getZ(),
                    6, 1.8D, 1.0D, 1.8D, 0.0D);
        }

        if (random.nextFloat() < 0.28F) {
            level.playSound(null, event.center, SoundEvents.LAVA_POP, SoundSource.AMBIENT, 0.65F, 0.8F + random.nextFloat() * 0.4F);
        }

        int ignitions = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int attempts = 0; attempts < 40 && ignitions < 6; attempts++) {
            randomPosInRadius(event, random, cursor, level, 10);
            if (!hasNearbyLava(level, cursor, 2)) {
                continue;
            }
            if (!level.getBlockState(cursor).isAir()) {
                continue;
            }
            BlockPos below = cursor.below();
            BlockState belowState = level.getBlockState(below);
            if (!belowState.isFaceSturdy(level, below, Direction.UP)) {
                continue;
            }
            BlockState fireState = BaseFireBlock.getState(level, cursor);
            if (!fireState.canSurvive(level, cursor)) {
                continue;
            }
            level.setBlock(cursor, fireState, 3);
            ignitions++;
        }
    }

    private static void processLavaPathPulse(ServerLevel level, ActiveNetherLavaEvent event) {
        if (event.pathDirection == null) {
            return;
        }

        RandomSource random = level.getRandom();
        int placements = 12;
        int successes = 0;
        BlockPos.MutableBlockPos lavaPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos targetPos = new BlockPos.MutableBlockPos();

        for (int attempts = 0; attempts < placements * 6 && successes < placements; attempts++) {
            if (!pickNearbyLava(level, event, lavaPos, random)) {
                continue;
            }

            targetPos.setWithOffset(lavaPos, event.pathDirection);
            BlockState targetState = level.getBlockState(targetPos);
            if (!FFFluidUtils.canStorePartialFluidAmount(level, targetPos, targetState, Fluids.LAVA)) {
                targetPos.move(Direction.DOWN);
                targetState = level.getBlockState(targetPos);
                if (!FFFluidUtils.canStorePartialFluidAmount(level, targetPos, targetState, Fluids.LAVA)) {
                    continue;
                }
            }

            int amount = level.getFluidState(lavaPos).getAmount() >= 8 ? 8 : 5;
            int remainder = FLUIDS_API.placeFluidAmountFromPos(level, targetPos.immutable(), Fluids.LAVA, amount, false, true);
            if (remainder < amount) {
                successes++;
            }
        }
    }

    private static boolean pickNearbyLava(ServerLevel level, ActiveNetherLavaEvent event, BlockPos.MutableBlockPos outPos, RandomSource random) {
        for (int tries = 0; tries < 12; tries++) {
            randomPosInRadius(event, random, outPos, level, 12);
            if (!level.hasChunk(outPos.getX() >> 4, outPos.getZ() >> 4)) {
                continue;
            }
            if (level.getFluidState(outPos).getType().isSame(Fluids.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private static void randomPosInRadius(ActiveNetherLavaEvent event, RandomSource random, BlockPos.MutableBlockPos outPos,
                                          ServerLevel level, int verticalRadius) {
        int dx = random.nextInt(event.radius * 2 + 1) - event.radius;
        int dz = random.nextInt(event.radius * 2 + 1) - event.radius;
        int dy = random.nextInt(verticalRadius * 2 + 1) - verticalRadius;
        int y = Mth.clamp(event.center.getY() + dy, level.getMinBuildHeight() + 2, level.getMaxBuildHeight() - 2);
        outPos.set(event.center.getX() + dx, y, event.center.getZ() + dz);
    }

    private static boolean hasNearbyLava(ServerLevel level, BlockPos pos, int radius) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (level.getFluidState(cursor).getType().isSame(Fluids.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static BlockState chooseBasaltWaveReplacement(BlockState state, RandomSource random) {
        if (state.is(Blocks.NETHERRACK)) {
            float roll = random.nextFloat();
            if (roll < 0.50F) return Blocks.BASALT.defaultBlockState();
            if (roll < 0.82F) return Blocks.BLACKSTONE.defaultBlockState();
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        if (state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL)) {
            return random.nextFloat() < 0.65F ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.BASALT.defaultBlockState();
        }
        if (state.is(Blocks.BASALT) && random.nextFloat() < 0.18F) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        if (state.is(Blocks.BLACKSTONE) && random.nextFloat() < 0.10F) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        return null;
    }

    private static Player pickAnchorPlayer(ServerLevel level) {
        List<? extends Player> players = level.players();
        if (players.isEmpty()) {
            return null;
        }
        return players.get(level.random.nextInt(players.size()));
    }

    private static boolean isInside(ActiveNetherLavaEvent event, BlockPos pos) {
        if (event == null) {
            return false;
        }
        return horizontalDistSqr(pos, event.center) <= (double) event.radius * event.radius;
    }

    private static double horizontalDistSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return (dx * dx) + (dz * dz);
    }

    private static long nextDailyRollTick(long now) {
        long remainder = Math.floorMod(now, DAY_TICKS);
        long offset = DAY_TICKS - remainder;
        return now + (offset == 0L ? DAY_TICKS : offset);
    }

    private static int sampleDuration(RandomSource random, int minTicks, int maxTicks) {
        int min = Math.max(40, Math.min(minTicks, maxTicks));
        int max = Math.max(min, Math.max(minTicks, maxTicks));
        return min == max ? min : random.nextInt(max - min + 1) + min;
    }

    private static String formatTicks(long ticks) {
        double seconds = Math.max(0L, ticks) / 20.0D;
        if (seconds >= 60.0D) {
            return String.format(Locale.ROOT, "%.1f min", seconds / 60.0D);
        }
        return String.format(Locale.ROOT, "%.1f s", seconds);
    }

    private static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    public enum NetherLavaEventType {
        LAVA_TIDE("lava_tide", "溶岩潮汐", 0.22F, -2),
        BASALT_WAVE("basalt_wave", "玄武岩化の波", 0.18F, 2),
        EMBER_STORM("ember_storm", "火の粉嵐", 0.24F, -4),
        LAVA_PATHS("lava_paths", "溶岩獣道", 0.20F, 0);

        public final String id;
        public final String displayName;
        public final float baseDailyChance;
        public final int pulseOffset;

        NetherLavaEventType(String id, String displayName, float baseDailyChance, int pulseOffset) {
            this.id = id;
            this.displayName = displayName;
            this.baseDailyChance = baseDailyChance;
            this.pulseOffset = pulseOffset;
        }
    }

    private static String directionName(Direction direction) {
        return switch (direction) {
            case NORTH -> "北";
            case SOUTH -> "南";
            case EAST -> "東";
            case WEST -> "西";
            case UP -> "上";
            case DOWN -> "下";
        };
    }

    private static final class NetherLavaState {
        private long nextDailyRollTick;
        private final EnumMap<NetherLavaEventType, ActiveNetherLavaEvent> activeEvents = new EnumMap<>(NetherLavaEventType.class);

        private NetherLavaState(long nextDailyRollTick) {
            this.nextDailyRollTick = nextDailyRollTick;
        }
    }

    private static final class ActiveNetherLavaEvent {
        private final NetherLavaEventType type;
        private final BlockPos center;
        private final int radius;
        private final int pulseIntervalTicks;
        private final long startTick;
        private final long endTick;
        private long nextPulseTick;
        private final Direction pathDirection;

        private ActiveNetherLavaEvent(NetherLavaEventType type, BlockPos center, int radius, int pulseIntervalTicks,
                                      long startTick, long endTick, long nextPulseTick, Direction pathDirection) {
            this.type = type;
            this.center = center;
            this.radius = radius;
            this.pulseIntervalTicks = pulseIntervalTicks;
            this.startTick = startTick;
            this.endTick = endTick;
            this.nextPulseTick = nextPulseTick;
            this.pathDirection = pathDirection;
        }
    }
}
