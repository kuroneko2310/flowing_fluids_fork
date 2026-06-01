package traben.flowing_fluids.water;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.util.RandomSource;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.config.FFConfig;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Basic water pressure system used to stress wooden barriers when surrounded by water.
 *
 * <p>This is intentionally conservative to avoid surprising world changes. By default the
 * system is disabled and can be toggled in the Flowing Fluids config.</p>
 */
public final class WaterPressureSystem {
    private static final float WARNING_RATIO = 0.75f;
    private static final int WATER_DEPTH_SAMPLE = 10;
    private static final float LATERAL_PRESSURE_PER_SIDE = 0.12f;
    private static final float NEIGHBOR_LEVEL_PRESSURE_SCALE = 0.05f;
    private static final float WATER_HEAD_PRESSURE_SCALE = 0.32f;
    private static final float DIRECT_HEAD_PRESSURE_SCALE = 0.18f;
    private static final float SEALED_FACE_PRESSURE_SCALE = 0.08f;
    private static final float OPEN_RELIEF_PRESSURE_SCALE = 0.14f;
    // FIXED: Use ConcurrentHashMap for thread safety
    private static final Map<ResourceKey<Level>, LevelState> LEVEL_STATE = new ConcurrentHashMap<>();

    private WaterPressureSystem() {
    }

    private static LevelState getState(ServerLevel level) {
        return LEVEL_STATE.computeIfAbsent(level.dimension(), key -> new LevelState());
    }

    /**
     * Clears state data for a level when it's unloaded to prevent memory leaks.
     */
    public static void onLevelUnload(ServerLevel level) {
        if (level == null) return;
        LevelState removed = LEVEL_STATE.remove(level.dimension());
        if (removed != null) {
            removed.data.clear();
            removed.positions.clear();
            removed.positionIndex.clear();
        }
    }

    public static void clearDimension(ServerLevel level) {
        onLevelUnload(level);
    }

    public static void clearAll() {
        LEVEL_STATE.values().forEach(state -> {
            state.data.clear();
            state.positions.clear();
            state.positionIndex.clear();
        });
        LEVEL_STATE.clear();
    }

    /**
     * Entry point for platform tick hooks.
     */
    public static void handleLevelTick(ServerLevel level) {
        FFConfig config = FlowingFluids.config;
        if (level == null || config == null || !config.enableMod || !config.enableWaterPressure
                || config.isDimensionExcluded(level)) {
            clearDimension(level);
            return;
        }

        LevelState state = getState(level);
        int currentTick = level.getServer().getTickCount();

        if (state.lastCleanupTick == -1 || currentTick - state.lastCleanupTick >= Math.max(20, config.waterPressureScanInterval * 2)) {
            cleanup(level, state, currentTick);
        }

        if (!state.data.isEmpty()) {
            processEntries(level, state, currentTick);
        }
    }

    /**
     * Called when neighbouring blocks or fluids change to prime tracking.
     */
    public static void handleNeighborUpdate(LevelAccessor accessor, BlockPos pos) {
        if (!(accessor instanceof ServerLevel level) || pos == null) {
            return;
        }

        FFConfig config = FlowingFluids.config;
        if (config == null || !config.enableMod || !config.enableWaterPressure || config.isDimensionExcluded(level)) {
            clearDimension(level);
            return;
        }

        int currentTick = level.getServer().getTickCount();
        LevelState state = getState(level);
        flagForTracking(level, state, pos, currentTick, true);
        for (Direction direction : Direction.values()) {
            flagForTracking(level, state, pos.relative(direction), currentTick, true);
        }
    }

    public static String describeStatus(ServerLevel level, BlockPos referencePos) {
        FFConfig config = FlowingFluids.config;
        String enabled = config != null && config.enableMod && config.enableWaterPressure ? "enabled" : "disabled";
        int tracked = level == null ? 0 : LEVEL_STATE.getOrDefault(level.dimension(), LevelState.EMPTY).data.size();
        return "Water pressure status"
                + "\nRuntime: event-driven"
                + "\nState: " + enabled
                + "\nTracked targets in this dimension: " + tracked
                + "\nScanner: disabled; only nearby changed water/blocks can wake pressure targets.";
    }

    private static void handleLevelTickLegacy(ServerLevel level) {
        FFConfig config = FlowingFluids.config;
        if (!config.enableMod || !config.enableWaterPressure) {
            return;
        }
        if (config.isDimensionExcluded(level)) {
            return;
        }

        int currentTick = level.getServer().getTickCount();
        LevelState state = getState(level);

        if (state.lastScanTick == -1 || currentTick - state.lastScanTick >= Math.max(1, config.waterPressureScanInterval)) {
            scanForTargets(level, state, currentTick);
        }

        if (state.lastCleanupTick == -1 || currentTick - state.lastCleanupTick >= Math.max(20, config.waterPressureScanInterval * 2)) {
            cleanup(level, state, currentTick);
        }

        if (state.data.isEmpty()) {
            return;
        }

        processEntries(level, state, currentTick);
    }

    /**
     * Legacy retained for reference while the runtime path stays retired.
     */
    private static void handleNeighborUpdateLegacy(LevelAccessor accessor, BlockPos pos) {
        if (!(accessor instanceof ServerLevel level)) {
            return;
        }

        FFConfig config = FlowingFluids.config;
        if (!config.enableMod || !config.enableWaterPressure) {
            return;
        }
        if (config.isDimensionExcluded(level)) {
            return;
        }

        int currentTick = level.getServer().getTickCount();
        LevelState state = getState(level);
        flagForTracking(level, state, pos, currentTick, false);
        for (Direction direction : Direction.values()) {
            flagForTracking(level, state, pos.relative(direction), currentTick, false);
        }
    }

    private static void processEntries(ServerLevel level, LevelState state, int currentTick) {
        FFConfig config = FlowingFluids.config;
        int maxPerTick = Math.max(4, config.waterPressureUpdatesPerTick);
        int processed = 0;
        int size = state.positions.size();

        while (processed < maxPerTick && size > 0) {
            if (state.cursor >= size) {
                state.cursor = 0;
            }

            long posLong = state.positions.getLong(state.cursor);
            state.cursor++;
            processed++;

            WaterPressureData data = state.data.get(posLong);
            if (data == null) {
                size = state.positions.size();
                continue;
            }

            BlockPos pos = BlockPos.of(posLong);
            BlockState blockState = level.getBlockState(pos);
            Block block = blockState.getBlock();

            if (!isPressureTargetBlock(block, config)) {
                removeEntry(state, posLong);
                size = state.positions.size();
                continue;
            }

            PressureSample pressureSample = samplePressure(level, pos);

            if (pressureSample.hasWaterContact()) {
                float increase = calculatePressureIncrease(blockState, block, pressureSample);
                data.pressure += increase;
                data.lastTick = currentTick;

                float threshold = getBreakThreshold(blockState, block, config);
                if (data.pressure >= threshold) {
                    breakBlock(level, pos, blockState);
                    removeEntry(state, posLong);
                    size = state.positions.size();
                    continue;
                }

                if (!data.warned && data.pressure >= threshold * WARNING_RATIO) {
                    showWarning(level, pos);
                    data.warned = true;
                }
            } else if (currentTick - data.lastTick >= config.waterPressureDecayTicks) {
                removeEntry(state, posLong);
                size = state.positions.size();
            }
        }
    }

    private static void scanForTargets(ServerLevel level, LevelState state, int currentTick) {
        FFConfig config = FlowingFluids.config;
        RandomSource random = level.random;
        int radius = Math.max(0, Math.min(4, config.waterPressureChunkRadius));
        int attempts = Math.max(1, config.waterPressureScanAttempts);

        level.players().forEach(player -> {
            int playerChunkX = player.chunkPosition().x;
            int playerChunkZ = player.chunkPosition().z;

            for (int cx = playerChunkX - radius; cx <= playerChunkX + radius; cx++) {
                for (int cz = playerChunkZ - radius; cz <= playerChunkZ + radius; cz++) {
                    if (!level.hasChunk(cx, cz)) {
                        continue;
                    }

                    int minY = level.getMinBuildHeight();
                    int maxY = level.getMaxBuildHeight();

                    for (int attempt = 0; attempt < attempts; attempt++) {
                        int x = (cx << 4) + random.nextInt(16);
                        int z = (cz << 4) + random.nextInt(16);
                        int y = minY + random.nextInt(Math.max(1, maxY - minY));
                        BlockPos pos = new BlockPos(x, y, z);
                        flagForTracking(level, state, pos, currentTick, true);
                    }
                }
            }
        });

        state.lastScanTick = currentTick;
    }

    private static void cleanup(ServerLevel level, LevelState state, int currentTick) {
        FFConfig config = FlowingFluids.config;
        int ttl = config.waterPressureDataTtl;
        if (ttl <= 0) {
            state.lastCleanupTick = currentTick;
            return;
        }

        LongArrayList removals = new LongArrayList();
        for (Long2ObjectMap.Entry<WaterPressureData> entry : state.data.long2ObjectEntrySet()) {
            if (currentTick - entry.getValue().lastTick >= ttl) {
                removals.add(entry.getLongKey());
            }
        }

        removals.forEach(posLong -> removeEntry(state, posLong));
        if (state.data.isEmpty()) {
            LEVEL_STATE.remove(level.dimension());
        }
        state.lastCleanupTick = currentTick;
    }

    private static void flagForTracking(ServerLevel level, LevelState state, BlockPos pos, int currentTick, boolean waterCheck) {
        BlockState blockState = level.getBlockState(pos);
        Block block = blockState.getBlock();
        FFConfig config = FlowingFluids.config;

        if (!isPressureTargetBlock(block, config)) {
            return;
        }

        BlockPos normalized = normalizeTargetPos(level, pos, blockState, block);
        if (!normalized.equals(pos)) {
            blockState = level.getBlockState(normalized);
            block = blockState.getBlock();
            pos = normalized;
            if (!isPressureTargetBlock(block, config)) {
                return;
            }
        }

        if (waterCheck && !samplePressure(level, pos).hasWaterContact()) {
            return;
        }

        long posLong = pos.asLong();
        WaterPressureData existing = state.data.get(posLong);
        if (existing != null) {
            existing.lastTick = currentTick;
            return;
        }

        if (state.data.size() >= Math.max(128, config.waterPressureMaxTracked)) {
            return;
        }

        state.data.put(posLong, new WaterPressureData(currentTick));
        state.positionIndex.put(posLong, state.positions.size());
        state.positions.add(posLong);
    }

    private static boolean isPressureTargetBlock(Block block, FFConfig config) {
        if (config.applyPressureToDoors && block instanceof DoorBlock) {
            return true;
        }
        if (config.applyPressureToTrapdoors && block instanceof TrapDoorBlock) {
            return true;
        }
        return config.applyPressureToFenceGates && block instanceof FenceGateBlock;
    }

    private static BlockPos normalizeTargetPos(LevelAccessor level, BlockPos pos, BlockState state, Block block) {
        if (block instanceof DoorBlock && state.hasProperty(DoorBlock.HALF)) {
            DoubleBlockHalf half = state.getValue(DoorBlock.HALF);
            if (half == DoubleBlockHalf.UPPER) {
                BlockPos below = pos.below();
                BlockState belowState = level.getBlockState(below);
                if (belowState.getBlock() instanceof DoorBlock) {
                    return below;
                }
            }
        }
        return pos;
    }

    private static void removeEntry(LevelState state, long posLong) {
        state.data.remove(posLong);
        int index = state.positionIndex.remove(posLong);
        if (index < 0) {
            return;
        }

        int lastIndex = state.positions.size() - 1;
        if (lastIndex < 0) {
            state.cursor = 0;
            return;
        }

        long lastPos = state.positions.getLong(lastIndex);
        if (index != lastIndex) {
            state.positions.set(index, lastPos);
            state.positionIndex.put(lastPos, index);
        }

        state.positions.removeLong(lastIndex);
        if (state.cursor > index) {
            state.cursor--;
        }
        if (state.positions.isEmpty()) {
            state.cursor = 0;
        } else if (state.cursor >= state.positions.size()) {
            state.cursor = 0;
        }
    }

    private static PressureSample samplePressure(Level level, BlockPos pos) {
        int adjacentWater = 0;
        int adjacentLevelSum = 0;
        int sealedWaterFaces = 0;
        int openReliefRoutes = 0;
        float strongestWaterHead = 0.0f;
        float strongestDirectHead = 0.0f;
        float ventedWaterHead = 0.0f;
        Direction strongestDirection = null;
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            neighborPos.setWithOffset(pos, direction);
            FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, neighborPos);
            if (fluidState.is(FluidTags.WATER)) {
                adjacentWater++;
                adjacentLevelSum += fluidState.getAmount();
                float waterHead = calculateWaterHeadAgainstTarget(level, pos, neighborPos, fluidState);
                if (waterHead > strongestWaterHead) {
                    strongestWaterHead = waterHead;
                    strongestDirection = direction;
                }
                if (direction.getAxis().isHorizontal() || direction == Direction.UP) {
                    strongestDirectHead = Math.max(strongestDirectHead, waterHead);
                }
                sealedWaterFaces += countSealedFacesAroundWater(level, neighborPos, direction.getOpposite());
                int reliefRoutes = countOpenReliefRoutes(level, neighborPos, direction.getOpposite());
                openReliefRoutes += reliefRoutes;
                if (reliefRoutes > 0) {
                    ventedWaterHead = Math.max(ventedWaterHead, waterHead);
                }
            }
        }
        int depth = calculateWaterDepthAbove(level, pos);
        strongestWaterHead = Math.max(strongestWaterHead, depth);
        return new PressureSample(adjacentWater, adjacentLevelSum, depth, strongestWaterHead, strongestDirectHead,
                ventedWaterHead, sealedWaterFaces, openReliefRoutes, strongestDirection);
    }

    private static int calculateWaterDepthAbove(Level level, BlockPos pos) {
        int depth = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 1; y <= WATER_DEPTH_SAMPLE; y++) {
            cursor.set(pos.getX(), pos.getY() + y, pos.getZ());
            FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, cursor);
            if (fluidState.is(FluidTags.WATER)) {
                depth++;
            } else {
                break;
            }
        }
        return depth;
    }

    private static float calculateWaterHeadAgainstTarget(Level level, BlockPos targetPos, BlockPos waterPos, FluidState waterState) {
        double surfaceHead = waterPos.getY() + Mth.clamp(waterState.getAmount(), 0, 8) / 8.0D;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 1; y <= WATER_DEPTH_SAMPLE; y++) {
            cursor.set(waterPos.getX(), waterPos.getY() + y, waterPos.getZ());
            FluidState aboveFluid = FFFluidUtils.getEffectiveFluidState(level, cursor);
            if (!aboveFluid.is(FluidTags.WATER)) {
                break;
            }
            surfaceHead = cursor.getY() + Mth.clamp(aboveFluid.getAmount(), 0, 8) / 8.0D;
        }

        double spillHead = FFFluidUtils.getWaterOpenSpillHead(level, waterPos, waterState, Fluids.WATER);
        if (Double.isFinite(spillHead)) {
            surfaceHead = Math.min(surfaceHead, spillHead);
        }
        return (float) Math.max(0.0D, surfaceHead - targetPos.getY());
    }

    private static int countSealedFacesAroundWater(Level level, BlockPos waterPos, Direction targetDirection) {
        int sealed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            if (direction == targetDirection) {
                continue;
            }
            cursor.setWithOffset(waterPos, direction);
            BlockState state = level.getBlockState(cursor);
            if (!state.isAir() && state.getFluidState().isEmpty() && state.isFaceSturdy(level, cursor, direction.getOpposite())) {
                sealed++;
            }
        }
        return sealed;
    }

    private static int countOpenReliefRoutes(Level level, BlockPos waterPos, Direction targetDirection) {
        int open = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            if (direction == targetDirection || direction == Direction.DOWN) {
                continue;
            }
            cursor.setWithOffset(waterPos, direction);
            BlockState state = level.getBlockState(cursor);
            FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, cursor, state);
            if (!fluidState.is(FluidTags.WATER) && state.isAir()) {
                open++;
            }
        }
        return open;
    }

    private static float calculatePressureIncrease(BlockState state, Block block, PressureSample sample) {
        FFConfig config = FlowingFluids.config;
        float baseRate = config.waterPressureAccumulationRate;
        float depthMultiplier = 1.0f + Math.min(3.5f, sample.waterHead() * WATER_HEAD_PRESSURE_SCALE);
        float directHeadMultiplier = 1.0f + Math.min(1.25f, sample.directWaterHead() * DIRECT_HEAD_PRESSURE_SCALE);
        float lateralMultiplier = 1.0f + Math.min(1.5f, sample.adjacentWaterCount() * LATERAL_PRESSURE_PER_SIDE);
        float neighborLevelMultiplier = 1.0f + (Math.max(0.0f, sample.averageNeighborLevel() - 1.0f)
                * NEIGHBOR_LEVEL_PRESSURE_SCALE);
        float containmentMultiplier = 1.0f
                + Math.min(1.25f, sample.sealedWaterFaces() * SEALED_FACE_PRESSURE_SCALE)
                - Math.min(0.75f, sample.openReliefRoutes() * OPEN_RELIEF_PRESSURE_SCALE);
        if (sample.ventedWaterHead() > 0.0f) {
            containmentMultiplier -= Math.min(0.35f, sample.ventedWaterHead() * 0.04f);
        }
        containmentMultiplier = Mth.clamp(containmentMultiplier, 0.25f, 2.2f);
        float directionMultiplier = calculateDirectionalPressureMultiplier(state, block, sample.strongestDirection());
        float openMultiplier = 1.0f;

        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) {
            boolean isOpen = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
            if (isOpen) {
                openMultiplier = config.waterPressureOpenStateMultiplier;
            }
        }

        return baseRate * depthMultiplier * directHeadMultiplier * lateralMultiplier * neighborLevelMultiplier
                * containmentMultiplier * directionMultiplier * openMultiplier;
    }

    private static float calculateDirectionalPressureMultiplier(BlockState state, Block block, Direction pressureDirection) {
        if (pressureDirection == null) {
            return 1.0f;
        }
        if (block instanceof TrapDoorBlock) {
            return pressureDirection.getAxis().isVertical() ? 1.2f : 0.85f;
        }
        if ((block instanceof DoorBlock || block instanceof FenceGateBlock)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                && pressureDirection.getAxis().isHorizontal()) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return pressureDirection.getAxis() == facing.getAxis() ? 1.2f : 0.85f;
        }
        return 1.0f;
    }

    private static float getBreakThreshold(BlockState state, Block block, FFConfig config) {
        float base = config.waterPressureBreakThreshold;
        String blockId = FFFluidUtils.getId(block).toString();
        if (block instanceof DoorBlock || block instanceof TrapDoorBlock) {
            if (blockId.contains("iron")) {
                base *= config.waterPressureMetalResistance;
            }
        }
        return base;
    }

    private static void breakBlock(ServerLevel level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        BlockPos normalized = normalizeTargetPos(level, pos, state, block);
        if (!normalized.equals(pos)) {
            pos = normalized;
            state = level.getBlockState(pos);
            block = state.getBlock();
        }

        level.destroyBlock(pos, true);

        if (block instanceof DoorBlock) {
            BlockPos above = pos.above();
            BlockState aboveState = level.getBlockState(above);
            if (aboveState.getBlock() instanceof DoorBlock) {
                level.destroyBlock(above, false);
            }
        }

        level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        level.sendParticles(ParticleTypes.SPLASH, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 20, 0.4, 0.4, 0.4, 0.05);
        level.playSound(null, pos, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
    }

    private static void showWarning(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.BUBBLE, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 8, 0.2, 0.2, 0.2, 0.02);
        level.playSound(null, pos, SoundEvents.WOOD_STEP, SoundSource.BLOCKS, 0.6f, 0.8f);
    }

    private static final class LevelState {
        static final LevelState EMPTY = new LevelState();
        final Long2ObjectOpenHashMap<WaterPressureData> data = new Long2ObjectOpenHashMap<>();
        final LongArrayList positions = new LongArrayList();
        final Long2IntOpenHashMap positionIndex = new Long2IntOpenHashMap();
        int cursor = 0;
        int lastScanTick = -1;
        int lastCleanupTick = -1;

        private LevelState() {
            positionIndex.defaultReturnValue(-1);
        }
    }

    private static final class WaterPressureData {
        float pressure;
        int lastTick;
        boolean warned;

        WaterPressureData(int currentTick) {
            this.pressure = 0.0f;
            this.lastTick = currentTick;
            this.warned = false;
        }
    }

    private record PressureSample(int adjacentWaterCount, int adjacentLevelSum, int waterDepth, float waterHead,
                                  float directWaterHead, float ventedWaterHead, int sealedWaterFaces,
                                  int openReliefRoutes, Direction strongestDirection) {
        private boolean hasWaterContact() {
            return adjacentWaterCount > 0 || waterDepth > 0 || waterHead > 0.05f;
        }

        private float averageNeighborLevel() {
            return adjacentWaterCount <= 0 ? 0.0f : (float) adjacentLevelSum / adjacentWaterCount;
        }
    }
}
