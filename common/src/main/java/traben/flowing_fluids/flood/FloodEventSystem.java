package traben.flowing_fluids.flood;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.api.FlowingFluidsAPI;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class FloodEventSystem {
    private static final FlowingFluidsAPI FLUIDS_API = FlowingFluidsAPI.getInstance(FlowingFluids.MOD_ID);
    private static final ConcurrentHashMap<ResourceKey<Level>, FloodEvent> ACTIVE_FLOODS = new ConcurrentHashMap<>();

    private FloodEventSystem() {
    }

    public static void onLevelTick(ServerLevel level) {
        FloodEvent event = ACTIVE_FLOODS.get(level.dimension());
        if (event == null) {
            return;
        }

        long now = level.getGameTime();
        if (!FlowingFluids.config.enableMod || !FlowingFluids.config.enableFloodEvents || FlowingFluids.config.isDimensionExcluded(level)) {
            ACTIVE_FLOODS.remove(level.dimension());
            return;
        }

        if (event.endTick <= now) {
            ACTIVE_FLOODS.remove(level.dimension());
            return;
        }

        if (now < event.nextPulseTick) {
            return;
        }

        event.nextPulseTick = now + Math.max(1, event.pulseIntervalTicks);
        processFloodPulse(level, event);
    }

    public static void onLevelUnload(ServerLevel level) {
        ACTIVE_FLOODS.remove(level.dimension());
    }

    public static boolean startFlood(ServerLevel level, BlockPos center, int radius, int durationTicks, int waterlineY) {
        if (!FlowingFluids.config.enableFloodEvents) {
            return false;
        }

        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, center.getX(), center.getZ()) - 1;
        int resolvedWaterline = waterlineY == Integer.MIN_VALUE
                ? Math.max(level.getSeaLevel() - 1, surfaceY)
                : waterlineY;

        FloodEvent event = new FloodEvent(
                center.immutable(),
                Math.max(8, radius),
                Math.max(20, durationTicks),
                resolvedWaterline,
                Math.max(1, FlowingFluids.config.floodPulseIntervalTicks),
                Math.max(1, FlowingFluids.config.floodPlacementsPerPulse),
                Math.max(1, FlowingFluids.config.floodWaterAmountPerPlacement),
                Math.max(1, FlowingFluids.config.floodShoreSearchRadius),
                Math.max(1, FlowingFluids.config.floodMaxWaterRise),
                Math.max(0.0f, FlowingFluids.config.floodLowlandBias),
                Math.max(1.0f, FlowingFluids.config.floodRainAmountMultiplier),
                level.getGameTime(),
                level.getGameTime() + Math.max(20, durationTicks)
        );
        ACTIVE_FLOODS.put(level.dimension(), event);
        return true;
    }

    public static boolean stopFlood(ServerLevel level) {
        return ACTIVE_FLOODS.remove(level.dimension()) != null;
    }

    public static String describeFlood(ServerLevel level, BlockPos referencePos) {
        FloodEvent event = ACTIVE_FLOODS.get(level.dimension());
        if (event == null) {
            return "このディメンションでは洪水イベントは動いていないにゃ。";
        }

        long remainingTicks = Math.max(0L, event.endTick - level.getGameTime());
        double distance = Math.sqrt(referencePos.distSqr(event.center));
        return "洪水イベント稼働中: 中心="
                + event.center.getX() + ", " + event.center.getY() + ", " + event.center.getZ()
                + " / 半径=" + event.radius
                + " / 水位目標Y=" + event.waterlineY
                + " / 残り=" + String.format(Locale.ROOT, "%.1f", remainingTicks / 20.0D) + "秒"
                + " / あなたからの距離=" + String.format(Locale.ROOT, "%.1f", distance);
    }

    public static int adjustRainWaterAmount(ServerLevel level, BlockPos pos, int baseAmount) {
        if (baseAmount <= 0) {
            return 0;
        }

        FloodEvent event = ACTIVE_FLOODS.get(level.dimension());
        if (event == null || event.rainAmountMultiplier <= 1.0f) {
            return baseAmount;
        }

        float multiplier = getRainAmountMultiplier(level, pos);
        return Math.max(baseAmount, Math.round(baseAmount * multiplier));
    }

    public static float getRainAmountMultiplier(ServerLevel level, BlockPos pos) {
        FloodEvent event = ACTIVE_FLOODS.get(level.dimension());
        if (event == null) {
            return 1.0f;
        }

        double distanceSqr = horizontalDistSqr(pos, event.center);
        double radiusSqr = (double) event.radius * event.radius;
        if (distanceSqr > radiusSqr) {
            return 1.0f;
        }

        double distance = Math.sqrt(distanceSqr);
        float radial = Mth.clamp(1.0f - (float) (distance / event.radius), 0.0f, 1.0f);
        return 1.0f + (event.rainAmountMultiplier - 1.0f) * radial;
    }

    private static void processFloodPulse(ServerLevel level, FloodEvent event) {
        final RandomSource random = level.getRandom();
        final int placementsGoal = Math.max(1, event.placementsPerPulse);
        final int maxAttempts = placementsGoal * 6;
        final int radius = event.radius;
        final int minBuildY = level.getMinBuildHeight();

        BlockPos.MutableBlockPos groundPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos placementPos = new BlockPos.MutableBlockPos();

        int placed = 0;
        for (int attempts = 0; attempts < maxAttempts && placed < placementsGoal; attempts++) {
            int dx = random.nextInt(radius * 2 + 1) - radius;
            int dz = random.nextInt(radius * 2 + 1) - radius;
            if ((dx * dx) + (dz * dz) > (radius * radius)) {
                continue;
            }

            int x = event.center.getX() + dx;
            int z = event.center.getZ() + dz;
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }

            int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (surfaceY < minBuildY || surfaceY > event.waterlineY + 1) {
                continue;
            }

            if (!passesLowlandBias(level, x, z, surfaceY, event, random)) {
                continue;
            }

            groundPos.set(x, surfaceY, z);
            if (!hasNearbyWater(level, groundPos, event.shoreSearchRadius)) {
                continue;
            }

            if (!findPlacementPos(level, groundPos, placementPos, event, minBuildY)) {
                continue;
            }

            int amount = computePlacementAmount(event, placementPos);
            placeFloodWater(level, placementPos, amount);
            placed++;
        }
    }

    private static boolean passesLowlandBias(ServerLevel level, int x, int z, int surfaceY, FloodEvent event, RandomSource random) {
        int sampleDistance = Math.max(2, Math.min(6, event.shoreSearchRadius));
        int higherNeighbours = 0;

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int sampleX = x + (direction.getStepX() * sampleDistance);
            int sampleZ = z + (direction.getStepZ() * sampleDistance);
            int sampleY = level.getHeight(Heightmap.Types.WORLD_SURFACE, sampleX, sampleZ) - 1;
            if (sampleY >= surfaceY + 1) {
                higherNeighbours++;
            }
        }

        float basinFactor = higherNeighbours / 4.0f;
        float waterlineFactor = Mth.clamp((event.waterlineY - surfaceY + 2.0f) / (event.maxWaterRise + 4.0f), 0.0f, 1.0f);
        float chance = Mth.clamp(0.2f + (waterlineFactor * 0.45f) + (basinFactor * event.lowlandBias * 0.35f), 0.05f, 0.95f);
        return random.nextFloat() <= chance;
    }

    private static boolean hasNearbyWater(ServerLevel level, BlockPos pos, int searchRadius) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                if ((dx * dx) + (dz * dz) > (searchRadius * searchRadius)) {
                    continue;
                }

                int sampleX = pos.getX() + dx;
                int sampleZ = pos.getZ() + dz;
                int sampleY = level.getHeight(Heightmap.Types.WORLD_SURFACE, sampleX, sampleZ) - 1;
                for (int dy = -1; dy <= 1; dy++) {
                    cursor.set(sampleX, sampleY + dy, sampleZ);
                    if (level.getFluidState(cursor).getType().isSame(Fluids.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean findPlacementPos(ServerLevel level, BlockPos.MutableBlockPos groundPos, BlockPos.MutableBlockPos outPos, FloodEvent event, int minBuildY) {
        outPos.set(groundPos);
        BlockState state = level.getBlockState(outPos);

        int leavesDepth = 0;
        while (state.is(BlockTags.LEAVES) && outPos.getY() > minBuildY && leavesDepth++ < 8) {
            outPos.move(0, -1, 0);
            state = level.getBlockState(outPos);
        }

        if (level.getFluidState(outPos).getType().isSame(Fluids.WATER)) {
            int guard = 0;
            while (guard++ < event.maxWaterRise + 2) {
                BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos(outPos.getX(), outPos.getY() + 1, outPos.getZ());
                if (level.getFluidState(above).getType().isSame(Fluids.WATER)) {
                    outPos.set(above);
                    continue;
                }

                BlockState target = level.getBlockState(above);
                if ((target.isAir() || target.canBeReplaced()) && above.getY() <= event.waterlineY + event.maxWaterRise) {
                    outPos.set(above);
                    return true;
                }
                return false;
            }
            return false;
        }

        if (state.isAir() || state.canBeReplaced()) {
            return outPos.getY() <= event.waterlineY + event.maxWaterRise;
        }

        if (state.isFaceSturdy(level, outPos, Direction.UP)) {
            outPos.move(0, 1, 0);
            BlockState above = level.getBlockState(outPos);
            return (above.isAir() || above.canBeReplaced()) && outPos.getY() <= event.waterlineY + event.maxWaterRise;
        }

        return false;
    }

    private static int computePlacementAmount(FloodEvent event, BlockPos placementPos) {
        float waterlineFactor = Mth.clamp((event.waterlineY - placementPos.getY() + 1.0f) / Math.max(1.0f, event.maxWaterRise + 1.0f), 0.0f, 1.0f);
        float radial = Mth.clamp(1.0f - (float) (Math.sqrt(horizontalDistSqr(placementPos, event.center)) / Math.max(1.0, event.radius)), 0.0f, 1.0f);
        float multiplier = 0.7f + (waterlineFactor * 0.5f) + (radial * 0.35f);
        return Math.max(1, Math.round(event.waterAmountPerPlacement * multiplier));
    }

    private static void placeFloodWater(ServerLevel level, BlockPos pos, int amount) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isSource() && (state.isAir() || state.canBeReplaced())) {
            level.setBlockAndUpdate(pos, Fluids.WATER.defaultFluidState().createLegacyBlock());
        }
        FLUIDS_API.placeFluidAmountFromPos(level, pos, Fluids.WATER, amount, false, true);
    }

    private static double horizontalDistSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return (dx * dx) + (dz * dz);
    }

    private static final class FloodEvent {
        private final BlockPos center;
        private final int radius;
        private final int durationTicks;
        private final int waterlineY;
        private final int pulseIntervalTicks;
        private final int placementsPerPulse;
        private final int waterAmountPerPlacement;
        private final int shoreSearchRadius;
        private final int maxWaterRise;
        private final float lowlandBias;
        private final float rainAmountMultiplier;
        private final long startTick;
        private final long endTick;
        private long nextPulseTick;

        private FloodEvent(BlockPos center, int radius, int durationTicks, int waterlineY,
                           int pulseIntervalTicks, int placementsPerPulse, int waterAmountPerPlacement,
                           int shoreSearchRadius, int maxWaterRise, float lowlandBias,
                           float rainAmountMultiplier, long startTick, long endTick) {
            this.center = center;
            this.radius = radius;
            this.durationTicks = durationTicks;
            this.waterlineY = waterlineY;
            this.pulseIntervalTicks = pulseIntervalTicks;
            this.placementsPerPulse = placementsPerPulse;
            this.waterAmountPerPlacement = waterAmountPerPlacement;
            this.shoreSearchRadius = shoreSearchRadius;
            this.maxWaterRise = maxWaterRise;
            this.lowlandBias = lowlandBias;
            this.rainAmountMultiplier = rainAmountMultiplier;
            this.startTick = startTick;
            this.endTick = endTick;
            this.nextPulseTick = startTick;
        }
    }
}
