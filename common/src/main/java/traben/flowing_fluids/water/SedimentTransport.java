package traben.flowing_fluids.water;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

/**
 * Erosion, transport and deposition of river sediment, driven by water random ticks.
 *
 * <p>Mass is conserved end to end: every eroded block adds one unit of its sediment class to the chunk's budget,
 * fast water carries units one chunk downstream at a time, and calm water lets a unit settle as a block on the bed.
 * The water displaced by a settling block is pushed up into the pool first; if it cannot be placed, nothing settles.
 * Budgets are persisted with the dimension, so sediment is never lost across restarts. A chunk budget has a cap; once
 * full, erosion above it pauses until sediment moves on.</p>
 *
 * <p>Ocean biomes are left alone (tides and currents are not simulated), and grass is first stripped to dirt before a
 * bank starts to wash away, so a flood scours a channel instead of vaporising a lawn in one tick.</p>
 */
public final class SedimentTransport {
    private static final int CHUNK_CAPACITY_PER_TYPE = 256;
    private static final int MOMENTUM_AGE_TICKS = 40;
    private static final double TRANSPORT_CHANCE = 0.5;
    private static final double MIN_FLOW = 0.05;

    private SedimentTransport() {
    }

    /**
     * @return true when this random tick changed terrain (so the caller can stop processing the cell)
     */
    public static boolean onWaterRandomTick(Level level, BlockPos pos, FluidState fluid, RandomSource random) {
        if (!FlowingFluids.config.enableErosion || !(level instanceof ServerLevel serverLevel) || fluid.getAmount() <= 0) {
            return false;
        }
        if (FFFluidUtils.isOceanBiome(level.getBiome(pos))) {
            return false;
        }
        Vec3 flow = fluid.getFlow(level, pos);
        double horizontal = Math.sqrt(flow.x * flow.x + flow.z * flow.z);
        boolean flooding = RiverFloodStage.isSwollenWater(level, pos);
        double power = horizontal < MIN_FLOW
                ? 0.0
                : ErosionMath.streamPower(horizontal, AdaptiveTickScheduler.getFlowMomentum(level, pos, MOMENTUM_AGE_TICKS),
                fluid.getAmount(), flooding);

        WeatherWaterSavedData saved = WeatherWaterSavedData.get(serverLevel);
        long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (ErosionMath.canSettle(power)) {
            return trySettle(serverLevel, saved, chunkKey, pos, fluid, random);
        }
        if (tryErode(serverLevel, saved, chunkKey, pos, flow, horizontal, power, random)) {
            return true;
        }
        carryDownstream(serverLevel, saved, chunkKey, pos, flow, horizontal, random);
        return false;
    }

    private static boolean tryErode(ServerLevel level, WeatherWaterSavedData saved, long chunkKey, BlockPos pos,
                                    Vec3 flow, double horizontal, double power, RandomSource random) {
        // Pick the bed or the bank the current is pushing against.
        BlockPos target;
        if (random.nextBoolean()) {
            target = pos.below();
        } else {
            Direction downstream = Direction.getNearest(flow.x / horizontal, 0.0, flow.z / horizontal);
            target = pos.relative(downstream);
        }
        if (target.getY() <= level.getMinBuildHeight() + 4) {
            return false;
        }
        BlockState state = level.getBlockState(target);
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM) || state.is(Blocks.PODZOL) || state.is(Blocks.DIRT_PATH)) {
            if (random.nextDouble() < ErosionMath.erosionChance(power, ErosionMath.criticalPower(ErosionMath.SILT),
                    FlowingFluids.config.erosionChance)) {
                level.setBlock(target, Blocks.DIRT.defaultBlockState(), 3);
                return true;
            }
            return false;
        }
        int type = sedimentTypeOf(state);
        if (type < 0 || random.nextDouble() >= ErosionMath.erosionChance(power, ErosionMath.criticalPower(type),
                FlowingFluids.config.erosionChance)) {
            return false;
        }
        int[] counts = saved.sedimentOrCreate(chunkKey);
        if (counts[type] >= CHUNK_CAPACITY_PER_TYPE) {
            return false;
        }
        // The water can flow into the freed space on its own; removing a block never creates water.
        if (!level.setBlock(target, Blocks.AIR.defaultBlockState(), 3)) {
            return false;
        }
        counts[type]++;
        saved.setDirty();
        AdaptiveTickScheduler.scheduleFluidTick(level, pos, Fluids.WATER, 1);
        return true;
    }

    private static void carryDownstream(ServerLevel level, WeatherWaterSavedData saved, long chunkKey, BlockPos pos,
                                        Vec3 flow, double horizontal, RandomSource random) {
        int[] counts = saved.sediment(chunkKey);
        if (counts == null || random.nextDouble() >= TRANSPORT_CHANCE) {
            return;
        }
        int type = pickCarried(counts, random);
        if (type < 0) {
            return;
        }
        int targetX = pos.getX() + (int) Math.round(flow.x / horizontal * 16.0);
        int targetZ = pos.getZ() + (int) Math.round(flow.z / horizontal * 16.0);
        long targetKey = ChunkPos.asLong(targetX >> 4, targetZ >> 4);
        if (targetKey == chunkKey || !level.hasChunk(targetX >> 4, targetZ >> 4)) {
            return;
        }
        int[] targetCounts = saved.sedimentOrCreate(targetKey);
        if (targetCounts[type] >= CHUNK_CAPACITY_PER_TYPE) {
            saved.clearSedimentIfEmpty(targetKey);
            return;
        }
        counts[type]--;
        targetCounts[type]++;
        saved.clearSedimentIfEmpty(chunkKey);
        saved.setDirty();
    }

    private static boolean trySettle(ServerLevel level, WeatherWaterSavedData saved, long chunkKey, BlockPos pos,
                                     FluidState fluid, RandomSource random) {
        int[] counts = saved.sediment(chunkKey);
        if (counts == null || fluid.getAmount() < 8
                || random.nextDouble() >= FlowingFluids.config.depositionChance) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        if (below.isAir() || below.canBeReplaced(Fluids.WATER) || !below.getFluidState().isEmpty()) {
            // Only settle onto a bed, never mid-column.
            return false;
        }
        if (!level.getBlockState(pos).liquid() || FFFluidUtils.isNearFlowingFluidsWaterSpringSource(level, pos)) {
            return false;
        }
        FluidState above = FFFluidUtils.getEffectiveFluidState(level, pos.above());
        if (!above.is(FluidTags.WATER) || above.getAmount() <= 0) {
            // A settling block must have water above it to push the displaced water into.
            return false;
        }
        int type = pickCarried(counts, random);
        if (type < 0) {
            return false;
        }
        var placement = FFFluidUtils.placeConnectedFluidAmountAndPlaceAction(
                level, pos.above(), fluid.getAmount(), Fluids.WATER, 48, true, false, true);
        if (placement.first() != 0 || placement.second() == null) {
            // The pool cannot take the displaced water; settling here would delete water.
            return false;
        }
        // Empty the cell first: if that is refused (spring-fed water), nothing has moved yet.
        if (!FFFluidUtils.setFluidStateAtPosToNewAmount(level, pos, Fluids.WATER, 0)) {
            return false;
        }
        placement.second().run();
        if (!level.setBlock(pos, depositFor(type), 3)) {
            // The water already moved up, so it is conserved; the sediment simply stays in the budget.
            return false;
        }
        counts[type]--;
        saved.clearSedimentIfEmpty(chunkKey);
        saved.setDirty();
        AdaptiveTickScheduler.scheduleFluidTick(level, pos.above(), Fluids.WATER, 1);
        return true;
    }

    static int sedimentTypeOf(BlockState state) {
        if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)) {
            return ErosionMath.SAND;
        }
        if (state.is(Blocks.GRAVEL)) {
            return ErosionMath.GRAVEL;
        }
        if (state.is(Blocks.CLAY)) {
            return ErosionMath.CLAY;
        }
        if (state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.FARMLAND) || state.is(Blocks.MUD)) {
            return ErosionMath.SILT;
        }
        return -1;
    }

    private static BlockState depositFor(int type) {
        return switch (type) {
            case ErosionMath.SAND -> Blocks.SAND.defaultBlockState();
            case ErosionMath.GRAVEL -> Blocks.GRAVEL.defaultBlockState();
            case ErosionMath.CLAY -> Blocks.CLAY.defaultBlockState();
            default -> Blocks.MUD.defaultBlockState();
        };
    }

    /**
     * Picks a carried sediment class weighted by how much of each the chunk holds.
     */
    private static int pickCarried(int[] counts, RandomSource random) {
        int total = 0;
        for (int count : counts) {
            total += Math.max(0, count);
        }
        if (total <= 0) {
            return -1;
        }
        int roll = random.nextInt(total);
        for (int type = 0; type < counts.length; type++) {
            roll -= Math.max(0, counts[type]);
            if (roll < 0) {
                return type;
            }
        }
        return -1;
    }
}
