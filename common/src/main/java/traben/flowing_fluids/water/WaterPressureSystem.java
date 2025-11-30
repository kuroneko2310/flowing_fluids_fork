package traben.flowing_fluids.water;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.config.FFConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Basic water pressure system used to stress wooden barriers when surrounded by water.
 *
 * <p>This is intentionally conservative to avoid surprising world changes. By default the
 * system is disabled and can be toggled in the Flowing Fluids config.</p>
 */
public final class WaterPressureSystem {
    private static final float WARNING_RATIO = 0.75f;
    private static final int WATER_DEPTH_SAMPLE = 10;
    private static final Map<ResourceKey<Level>, LevelState> LEVEL_STATE = new HashMap<>();

    private WaterPressureSystem() {
    }

    private static LevelState getState(ServerLevel level) {
        return LEVEL_STATE.computeIfAbsent(level.dimension(), key -> new LevelState());
    }

    /**
     * Entry point for platform tick hooks.
     */
    public static void handleLevelTick(ServerLevel level) {
        FFConfig config = FlowingFluids.config;
        if (!config.enableMod || !config.enableWaterPressure) {
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
     * Called when neighbouring blocks or fluids change to prime tracking.
     */
    public static void handleNeighborUpdate(LevelAccessor accessor, BlockPos pos) {
        if (!(accessor instanceof ServerLevel level)) {
            return;
        }

        FFConfig config = FlowingFluids.config;
        if (!config.enableMod || !config.enableWaterPressure) {
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

            boolean adjacentWater = hasAdjacentWater(level, pos);
            int depth = calculateWaterDepthAbove(level, pos);

            if (adjacentWater || depth > 0) {
                float increase = calculatePressureIncrease(blockState, block, depth);
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
        Random random = level.random;
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

        if (waterCheck && !hasAdjacentWater(level, pos)) {
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
        int index = state.positions.indexOf(posLong);
        if (index >= 0) {
            state.positions.removeLong(index);
            if (state.cursor > index) {
                state.cursor--;
            }
        }
    }

    private static boolean hasAdjacentWater(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            FluidState fluidState = level.getFluidState(pos.relative(direction));
            if (fluidState.is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static int calculateWaterDepthAbove(Level level, BlockPos pos) {
        int depth = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 1; y <= WATER_DEPTH_SAMPLE; y++) {
            cursor.set(pos.getX(), pos.getY() + y, pos.getZ());
            FluidState fluidState = level.getFluidState(cursor);
            if (fluidState.is(FluidTags.WATER)) {
                depth++;
            } else {
                break;
            }
        }
        return depth;
    }

    private static float calculatePressureIncrease(BlockState state, Block block, int waterDepth) {
        FFConfig config = FlowingFluids.config;
        float baseRate = config.waterPressureAccumulationRate;
        float depthMultiplier = 1.0f + (waterDepth * 0.25f);
        float openMultiplier = 1.0f;

        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) {
            boolean isOpen = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
            if (isOpen) {
                openMultiplier = config.waterPressureOpenStateMultiplier;
            }
        }

        return baseRate * depthMultiplier * openMultiplier;
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
        final Long2ObjectOpenHashMap<WaterPressureData> data = new Long2ObjectOpenHashMap<>();
        final LongArrayList positions = new LongArrayList();
        int cursor = 0;
        int lastScanTick = -1;
        int lastCleanupTick = -1;
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
}
