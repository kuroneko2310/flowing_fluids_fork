package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import traben.flowing_fluids.optimization.WaterFlowProfile;

import java.util.HashMap;
import java.util.Map;

public final class MudificationSystem {
    private static final long EXPOSURE_TTL_TICKS = 600L;
    public static final TagKey<Block> MUDIFIABLE_TO_MUD = TagKey.create(
        Registries.BLOCK,
        FFFluidUtils.res(FlowingFluids.MOD_ID, "mudifiable_to_mud")
    );

    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<ExposureEntry>> EXPOSURE_BY_LEVEL = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> LAST_PRUNE_TICK_BY_LEVEL = new HashMap<>();

    private MudificationSystem() {
    }

    public static void onWaterTransfer(ServerLevel level, BlockPos waterPos, @Nullable Direction flowDirection,
                                       @Nullable MudificationContext context) {
        if (level == null
            || waterPos == null
            || context == null
            || !FlowingFluids.config.enableMudification) {
            return;
        }

        TrackerAccess trackerAccess = new TrackerAccess(level);
        boolean touched = applyExposure(level, trackerAccess, waterPos.below(), context, false, 1.0f);

        if (FlowingFluids.config.mudificationAffectsBanks
            && flowDirection != null
            && flowDirection.getAxis().isHorizontal()
            && isBankSplashedFlow(context.flowSpeed())) {
            touched |= applyExposure(level, trackerAccess, waterPos.relative(flowDirection.getClockWise()).below(), context, true, 0.5f);
            touched |= applyExposure(level, trackerAccess, waterPos.relative(flowDirection.getCounterClockWise()).below(), context, true, 0.5f);
        }

        Long2ObjectOpenHashMap<ExposureEntry> exposures = EXPOSURE_BY_LEVEL.get(level.dimension());
        if (touched || (exposures != null && !exposures.isEmpty())) {
            pruneExpired(level, level.getGameTime());
        }
    }

    public static void onWaterTransfer(ServerLevel level, BlockPos waterPos, @Nullable Direction flowDirection,
                                       @Nullable WaterFlowProfile waterProfile) {
        onWaterTransfer(level, waterPos, flowDirection, fromProfile(waterProfile));
    }

    public static void clearDimension(ServerLevel level) {
        if (level != null) {
            EXPOSURE_BY_LEVEL.remove(level.dimension());
            LAST_PRUNE_TICK_BY_LEVEL.remove(level.dimension());
        }
    }

    public static void clearAll() {
        EXPOSURE_BY_LEVEL.clear();
        LAST_PRUNE_TICK_BY_LEVEL.clear();
    }

    static boolean isBankSplashedFlow(WaterFlowProfile.FlowSpeed flowSpeed) {
        return flowSpeed == WaterFlowProfile.FlowSpeed.FAST || flowSpeed == WaterFlowProfile.FlowSpeed.TORRENT;
    }

    static float getExposureGain(WaterFlowProfile.FlowSpeed flowSpeed, float pressureDrive,
                                 boolean immediateDownwardOutlet, float mudificationStrength) {
        return MudificationLogic.getExposureGain(flowSpeed, pressureDrive, immediateDownwardOutlet, mudificationStrength);
    }

    static float resolveExposureAfterTouch(float existingExposure, long lastTouchedTick, long currentTick, float delta) {
        return MudificationLogic.resolveExposureAfterTouch(existingExposure, lastTouchedTick, currentTick, delta, EXPOSURE_TTL_TICKS);
    }

    static int getMudThreshold(BlockState state, boolean bankSide) {
        boolean softSurface = state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND);
        return MudificationLogic.getMudThreshold(softSurface, bankSide);
    }

    static boolean shouldIgnorePlayerPlaced(BlockState state, boolean playerPlaced) {
        return MudificationLogic.shouldIgnorePlayerPlaced(playerPlaced, state.is(Blocks.FARMLAND));
    }

    public static MudificationContext fromProfile(@Nullable WaterFlowProfile waterProfile) {
        if (waterProfile == null) {
            return null;
        }
        float pressureDrive = waterProfile.isPressureDriven()
            ? waterProfile.getPressureTransferScale()
            : 0.0f;
        return new MudificationContext(
            waterProfile.getFlowSpeed(),
            pressureDrive,
            waterProfile.hasImmediateDownwardOutlet()
        );
    }

    public static MudificationContext estimateSuccessfulTransfer(int movedAmount, boolean downwardTransfer) {
        int amount = Math.max(1, movedAmount);
        WaterFlowProfile.FlowSpeed flowSpeed;
        if (downwardTransfer) {
            flowSpeed = amount >= 6 ? WaterFlowProfile.FlowSpeed.TORRENT : WaterFlowProfile.FlowSpeed.FAST;
        } else if (amount >= 6) {
            flowSpeed = WaterFlowProfile.FlowSpeed.FAST;
        } else if (amount >= 3) {
            flowSpeed = WaterFlowProfile.FlowSpeed.NORMAL;
        } else {
            flowSpeed = WaterFlowProfile.FlowSpeed.SLOW;
        }
        float pressureDrive = downwardTransfer
            ? (amount >= 6 ? 0.75f : 0.55f)
            : (amount >= 6 ? 0.55f : 0.3f);
        return new MudificationContext(flowSpeed, pressureDrive, downwardTransfer);
    }

    private static boolean applyExposure(ServerLevel level, TrackerAccess trackerAccess,
                                         BlockPos targetPos, MudificationContext context,
                                         boolean bankSide, float exposureScale) {
        BlockState state = level.getBlockState(targetPos);
        if (!state.is(MUDIFIABLE_TO_MUD)) {
            clearExposure(level, targetPos);
            return false;
        }

        boolean playerPlaced = trackerAccess.get().isPlayerPlaced(targetPos);
        if (shouldIgnorePlayerPlaced(state, playerPlaced)) {
            clearExposure(level, targetPos);
            return false;
        }

        float gain = getExposureGain(
            context.flowSpeed(),
            context.pressureDrive(),
            context.immediateDownwardOutlet(),
            FlowingFluids.config.mudificationStrength
        ) * Math.max(0.0f, exposureScale);
        if (gain <= 0.0f) {
            return false;
        }

        long now = level.getGameTime();
        Long2ObjectOpenHashMap<ExposureEntry> exposures = getExposureMap(level);
        long key = targetPos.asLong();
        ExposureEntry entry = exposures.get(key);
        float updatedExposure = resolveExposureAfterTouch(
            entry == null ? 0.0f : entry.exposure(),
            entry == null ? -1L : entry.lastTouchedTick(),
            now,
            gain
        );
        int threshold = getMudThreshold(state, bankSide);

        if (updatedExposure >= threshold) {
            level.setBlock(targetPos, Blocks.MUD.defaultBlockState(), Block.UPDATE_ALL);
            exposures.remove(key);
            trackerAccess.get().clearPlayerPlaced(targetPos);
            return true;
        }

        exposures.put(key, new ExposureEntry(Mth.clamp(updatedExposure, 0.0f, threshold), now));
        return true;
    }

    private static Long2ObjectOpenHashMap<ExposureEntry> getExposureMap(ServerLevel level) {
        return EXPOSURE_BY_LEVEL.computeIfAbsent(level.dimension(), ignored -> new Long2ObjectOpenHashMap<>());
    }

    private static void clearExposure(ServerLevel level, BlockPos pos) {
        Long2ObjectOpenHashMap<ExposureEntry> exposures = EXPOSURE_BY_LEVEL.get(level.dimension());
        if (exposures != null) {
            exposures.remove(pos.asLong());
            if (exposures.isEmpty()) {
                EXPOSURE_BY_LEVEL.remove(level.dimension());
                LAST_PRUNE_TICK_BY_LEVEL.remove(level.dimension());
            }
        }
    }

    private static void pruneExpired(ServerLevel level, long currentTick) {
        Long2ObjectOpenHashMap<ExposureEntry> exposures = EXPOSURE_BY_LEVEL.get(level.dimension());
        if (exposures == null || exposures.isEmpty()) {
            return;
        }
        long pruneInterval = exposures.size() >= 256 ? 20L : 100L;
        long lastPruneTick = LAST_PRUNE_TICK_BY_LEVEL.getOrDefault(level.dimension(), Long.MIN_VALUE);
        if (currentTick - lastPruneTick < pruneInterval) {
            return;
        }
        LAST_PRUNE_TICK_BY_LEVEL.put(level.dimension(), currentTick);
        var iterator = Long2ObjectMaps.fastIterator(exposures);
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (currentTick - entry.getValue().lastTouchedTick() > EXPOSURE_TTL_TICKS) {
                iterator.remove();
            }
        }
        if (exposures.isEmpty()) {
            EXPOSURE_BY_LEVEL.remove(level.dimension());
            LAST_PRUNE_TICK_BY_LEVEL.remove(level.dimension());
        }
    }

    private static final class TrackerAccess {
        private final ServerLevel level;
        private PlacedTerrainTracker tracker;

        private TrackerAccess(ServerLevel level) {
            this.level = level;
        }

        private PlacedTerrainTracker get() {
            if (tracker == null) {
                tracker = PlacedTerrainTracker.get(level);
            }
            return tracker;
        }
    }

    private record ExposureEntry(float exposure, long lastTouchedTick) {
    }

    public record MudificationContext(WaterFlowProfile.FlowSpeed flowSpeed, float pressureDrive,
                                      boolean immediateDownwardOutlet) {
    }
}
