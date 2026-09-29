package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SurfaceVentLocator {
    private static final int MIN_SHAFT_DEPTH = 5;
    private static final int MAX_SHAFT_DEPTH = 24;
    private static final int LARGE_CREST_HEIGHT = 2;
    private static final int HEAVY_CREST_HEIGHT = 3;
    private static final int LARGE_SPRAY_BURSTS = 1;
    private static final int HEAVY_SPRAY_BURSTS = 2;
    private static final int UPPER_SPRAY_AMOUNT = 5;
    private static final int LOWER_SPRAY_AMOUNT = 3;

    private SurfaceVentLocator() {
    }

    public static List<LocatedVent> findNearbySurfaceVents(ServerLevel level, BlockPos center, FlowingFluid fluid, int radius, int limit) {
        int clampedRadius = Mth.clamp(radius, 16, 512);
        int chunkRadius = (clampedRadius + 15) >> 4;
        int radiusSq = clampedRadius * clampedRadius;
        List<LocatedVent> matches = new ArrayList<>();

        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;

        for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }

                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                chunk.findBlocks(state -> state.getBlock() instanceof FloorSpringBlock, (pos, state) -> {
                    int distanceSq = squaredHorizontalDistance(center, pos);
                    if (distanceSq > radiusSq) {
                        return;
                    }
                    classifySurfaceVent(level, pos, state, fluid, distanceSq).ifPresent(matches::add);
                });
            }
        }

        matches.sort(Comparator
                .comparingInt(LocatedVent::distanceSq)
                .thenComparingInt(vent -> vent.springPos().getX())
                .thenComparingInt(vent -> vent.springPos().getY())
                .thenComparingInt(vent -> vent.springPos().getZ()));
        if (matches.size() > limit) {
            return new ArrayList<>(matches.subList(0, limit));
        }
        return matches;
    }

    public static java.util.Optional<LocatedVent> inspectSurfaceVent(LevelAccessor level, BlockPos springPos, FlowingFluid fluid) {
        BlockState state = level.getBlockState(springPos);
        return classifySurfaceVent(level, springPos, state, fluid, 0);
    }

    public static void sustainSurfaceVent(ServerLevel level, LocatedVent vent, FlowingFluid fluid, boolean keepSpoutRaised) {
        BlockPos springPos = vent.springPos();
        BlockPos mouthPos = vent.mouthPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean shaftOpenToMouth = true;

        for (int y = springPos.getY() + 1; y <= mouthPos.getY(); y++) {
            cursor.set(springPos.getX(), y, springPos.getZ());
            BlockState state = level.getBlockState(cursor);
            FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, cursor, state);
            if (!SpringFluidEmitter.canEmitInto(state, fluidState, fluid)) {
                shaftOpenToMouth = false;
                break;
            }
            FFFluidUtils.setFluidStateAtPosToNewAmount(level, cursor, fluid, 8);
            AdaptiveTickScheduler.scheduleFluidTick(level, cursor, fluid, fluid.getTickDelay(level));
        }

        if (keepSpoutRaised && shaftOpenToMouth) {
            int crestHeight = crestHeightFor(vent.strength());
            sustainRaisedCrest(level, mouthPos, fluid, crestHeight);
            sprayFountain(level, mouthPos, fluid, crestHeight, sprayBurstsFor(vent.strength()));
        }
    }

    public static void sustainRaisedCrest(ServerLevel level, BlockPos mouthPos, FlowingFluid fluid, int crestHeight) {
        for (int offset = 1; offset <= crestHeight; offset++) {
            BlockPos crestPos = mouthPos.above(offset);
            BlockState crestState = level.getBlockState(crestPos);
            FluidState crestFluid = FFFluidUtils.getEffectiveFluidState(level, crestPos, crestState);
            if (!SpringFluidEmitter.canEmitInto(crestState, crestFluid, fluid)) {
                break;
            }
            FFFluidUtils.setFluidStateAtPosToNewAmount(level, crestPos, fluid, 8);
            AdaptiveTickScheduler.scheduleFluidTick(level, crestPos, fluid, fluid.getTickDelay(level));
        }
    }

    public static DebugVentResult createDebugSurfaceVent(ServerLevel level, BlockPos surfacePos, FlowingFluid fluid, int shaftDepth) {
        int clampedDepth = Mth.clamp(shaftDepth, MIN_SHAFT_DEPTH, MAX_SHAFT_DEPTH);
        BlockPos springPos = surfacePos.below(clampedDepth);
        BlockPos supportPos = springPos.below();
        BlockPos mouthPos = surfacePos.above();

        if (springPos.getY() <= level.getMinBuildHeight() + 2 || mouthPos.getY() >= level.getMaxBuildHeight() - 2) {
            return new DebugVentResult(false, "Surface vent would leave the buildable world.");
        }
        if (!level.hasChunkAt(surfacePos) || !level.hasChunkAt(springPos) || !level.hasChunkAt(mouthPos)) {
            return new DebugVentResult(false, "Surface vent target chunks are not loaded.");
        }

        BlockState shellState = fluid.isSame(Fluids.LAVA)
                ? Blocks.BASALT.defaultBlockState()
                : Blocks.STONE.defaultBlockState();
        FloorSpringBlock springBlock = fluid.isSame(Fluids.LAVA)
                ? ForgeSpringRegistry.FLOOR_LAVA_SPRING_HEAVY.get()
                : ForgeSpringRegistry.FLOOR_SPRING_HEAVY.get();

        level.setBlock(supportPos, shellState, 3);
        level.setBlock(surfacePos, shellState, 3);
        level.setBlock(springPos, springBlock.defaultBlockState()
                .setValue(FloorSpringBlock.FACING, Direction.NORTH)
                .setValue(FloorSpringBlock.WATERLOGGED, false), 3);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos sidePos = new BlockPos.MutableBlockPos();
        for (int y = springPos.getY() + 1; y <= surfacePos.getY(); y++) {
            cursor.set(springPos.getX(), y, springPos.getZ());
            FFFluidUtils.setFluidStateAtPosToNewAmount(level, cursor, fluid, 8);
            AdaptiveTickScheduler.scheduleFluidTick(level, cursor, fluid, fluid.getTickDelay(level));

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                sidePos.set(cursor).move(direction);
                BlockState sideState = level.getBlockState(sidePos);
                FluidState sideFluid = FFFluidUtils.getEffectiveFluidState(level, sidePos, sideState);
                if (sideState.isAir() || sideState.canBeReplaced(fluid) || sideFluid.getType().isSame(fluid)) {
                    level.setBlock(sidePos, shellState, 3);
                }
            }
        }

        FFFluidUtils.setFluidStateAtPosToNewAmount(level, mouthPos, fluid, 8);
        AdaptiveTickScheduler.scheduleFluidTick(level, mouthPos, fluid, fluid.getTickDelay(level));
        LocatedVent vent = new LocatedVent(springPos.immutable(), mouthPos.immutable(), 0, springBlock.strength());
        sustainSurfaceVent(level, vent, fluid, fluid.isSame(Fluids.WATER));
        level.scheduleTick(springPos, springBlock, Math.max(2, springBlock.strength().minimumDelay()));

        return new DebugVentResult(true, "Created debug surface "
                + (fluid.isSame(Fluids.LAVA) ? "lava" : "water")
                + " vent spring=" + formatPos(springPos)
                + " mouth=" + formatPos(mouthPos)
                + " depth=" + clampedDepth + ".");
    }

    public static BlockPos surfacePosAt(ServerLevel level, BlockPos columnPos) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, columnPos.getX(), columnPos.getZ()) - 1;
        return new BlockPos(columnPos.getX(), surfaceY, columnPos.getZ());
    }

    private static void sprayFountain(ServerLevel level, BlockPos mouthPos, FlowingFluid fluid, int crestHeight, int sprayBursts) {
        BlockPos sprayOrigin = mouthPos.above(Math.max(1, crestHeight));
        Direction[] directions = FFFluidUtils.getCardinalsShuffle(level.random);

        for (int i = 0; i < sprayBursts && i < directions.length; i++) {
            Direction direction = directions[i];
            BlockPos targetPos = sprayOrigin.relative(direction);
            emitSprayCell(level, targetPos, fluid, UPPER_SPRAY_AMOUNT);

            BlockPos lowerArcPos = mouthPos.above(Math.max(1, crestHeight - 1)).relative(direction);
            emitSprayCell(level, lowerArcPos, fluid, LOWER_SPRAY_AMOUNT);
        }
    }

    private static void emitSprayCell(ServerLevel level, BlockPos targetPos, FlowingFluid fluid, int amount) {
        BlockState targetState = level.getBlockState(targetPos);
        FluidState targetFluid = FFFluidUtils.getEffectiveFluidState(level, targetPos, targetState);
        if (!SpringFluidEmitter.canEmitInto(targetState, targetFluid, fluid)) {
            return;
        }
        int newAmount = targetFluid.getType().isSame(fluid)
                ? Math.max(targetFluid.getAmount(), amount)
                : amount;
        FFFluidUtils.setFluidStateAtPosToNewAmount(level, targetPos, fluid, newAmount);
        AdaptiveTickScheduler.scheduleFluidTick(level, targetPos, fluid, fluid.getTickDelay(level));
    }

    private static java.util.Optional<LocatedVent> classifySurfaceVent(LevelAccessor level, BlockPos pos, BlockState state,
                                                                       FlowingFluid fluid, int distanceSq) {
        if (!(state.getBlock() instanceof FloorSpringBlock spring)) {
            return java.util.Optional.empty();
        }
        if (!spring.sourceFluid().isSame(fluid)) {
            return java.util.Optional.empty();
        }
        if (spring.strength() != SpringStrength.LARGE && spring.strength() != SpringStrength.HEAVY) {
            return java.util.Optional.empty();
        }

        for (int shaftDepth = MIN_SHAFT_DEPTH; shaftDepth <= MAX_SHAFT_DEPTH; shaftDepth++) {
            if (!hasPassableShaft(level, pos, shaftDepth, fluid)) {
                continue;
            }
            if (!hasStableVentWalls(level, pos, shaftDepth, fluid)) {
                continue;
            }

            BlockPos mouthPos = pos.above(shaftDepth + 1);
            if (!isOpenMouth(level, mouthPos, fluid)) {
                continue;
            }
            if (!((LevelReader) level).canSeeSky(mouthPos)) {
                continue;
            }

            return java.util.Optional.of(new LocatedVent(pos.immutable(), mouthPos.immutable(), distanceSq, spring.strength()));
        }

        return java.util.Optional.empty();
    }

    private static boolean hasPassableShaft(LevelAccessor level, BlockPos springPos, int shaftDepth, FlowingFluid fluid) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int offset = 1; offset <= shaftDepth; offset++) {
            cursor.set(springPos.getX(), springPos.getY() + offset, springPos.getZ());
            BlockState state = level.getBlockState(cursor);
            FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, cursor, state);
            if (!SpringFluidEmitter.canEmitInto(state, fluidState, fluid)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasStableVentWalls(LevelAccessor level, BlockPos springPos, int shaftDepth, FlowingFluid fluid) {
        BlockPos.MutableBlockPos shaftPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos sidePos = new BlockPos.MutableBlockPos();
        for (int offset = 1; offset <= shaftDepth; offset++) {
            shaftPos.set(springPos.getX(), springPos.getY() + offset, springPos.getZ());
            int sturdySides = 0;
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                sidePos.set(shaftPos).move(direction);
                BlockState sideState = level.getBlockState(sidePos);
                FluidState sideFluid = FFFluidUtils.getEffectiveFluidState(level, sidePos, sideState);
                if (!sideState.isAir() && !sideState.canBeReplaced(fluid) && sideFluid.isEmpty()) {
                    sturdySides++;
                }
            }
            if (sturdySides < 3) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOpenMouth(LevelAccessor level, BlockPos mouthPos, FlowingFluid fluid) {
        BlockState mouthState = level.getBlockState(mouthPos);
        FluidState mouthFluid = FFFluidUtils.getEffectiveFluidState(level, mouthPos, mouthState);
        return SpringFluidEmitter.canEmitInto(mouthState, mouthFluid, fluid);
    }

    private static int squaredHorizontalDistance(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    static int crestHeightFor(SpringStrength strength) {
        return strength == SpringStrength.HEAVY ? HEAVY_CREST_HEIGHT : LARGE_CREST_HEIGHT;
    }

    static int sprayBurstsFor(SpringStrength strength) {
        return strength == SpringStrength.HEAVY ? HEAVY_SPRAY_BURSTS : LARGE_SPRAY_BURSTS;
    }

    static int upperSprayAmount() {
        return UPPER_SPRAY_AMOUNT;
    }

    static int lowerSprayAmount() {
        return LOWER_SPRAY_AMOUNT;
    }

    public record LocatedVent(BlockPos springPos, BlockPos mouthPos, int distanceSq, SpringStrength strength) {
        public int distance() {
            return Mth.floor(Math.sqrt(distanceSq));
        }
    }

    public record DebugVentResult(boolean success, String message) {
    }

    private static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
