package traben.flowing_fluids.optimization;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FluidRegressionLogic;
import traben.flowing_fluids.FluidSectionDataCache;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.ParallelFluidTickManager;

public final class LavaFlowProfile {
    private static final LavaFlowProfile NON_LAVA = new LavaFlowProfile(
        false, false, false, false, false, 0, 0
    );

    private final boolean stillReservoir;
    private final boolean immediateSurfaceEdge;
    private final boolean immediateDownwardOutlet;
    private final boolean flowActive;
    private final boolean hasFluidAbove;
    private final int lateralLavaNeighbors;
    private final int stableTicks;

    private LavaFlowProfile(boolean stillReservoir,
                            boolean immediateSurfaceEdge,
                            boolean immediateDownwardOutlet,
                            boolean flowActive,
                            boolean hasFluidAbove,
                            int lateralLavaNeighbors,
                            int stableTicks) {
        this.stillReservoir = stillReservoir;
        this.immediateSurfaceEdge = immediateSurfaceEdge;
        this.immediateDownwardOutlet = immediateDownwardOutlet;
        this.flowActive = flowActive;
        this.hasFluidAbove = hasFluidAbove;
        this.lateralLavaNeighbors = lateralLavaNeighbors;
        this.stableTicks = stableTicks;
    }

    public static LavaFlowProfile analyze(Level level, BlockPos pos, FluidState fluidState, int amount) {
        return analyze(level, pos, fluidState, amount, null);
    }

    public static LavaFlowProfile analyze(Level level, BlockPos pos, FluidState fluidState, int amount,
                                          @Nullable FluidSectionDataCache sectionCache) {
        if (level == null || pos == null || fluidState == null || !fluidState.is(FluidTags.LAVA) || amount <= 0) {
            return NON_LAVA;
        }

        Fluid fluidType = fluidState.getType();
        FlowingFluid flowingFluid = fluidType instanceof FlowingFluid typed ? typed : null;
        NeighborhoodMetrics metrics = sampleNeighborhood(level, pos, fluidType, flowingFluid, amount, sectionCache);
        boolean flowActive = AdaptiveTickScheduler.isFlowActiveNow(level, pos);
        int stableTicks = shouldSampleStableTicks(amount, flowActive, metrics)
            ? AdaptiveTickScheduler.getPoolStableTicks(level, pos, 40)
            : 0;
        boolean stillReservoir = FluidRegressionLogic.isStillLavaReservoir(
            amount,
            flowActive,
            metrics.supportedBelow(),
            metrics.surfaceEdgeCount() > 0,
            metrics.immediateDownwardOutlet(),
            metrics.lateralLavaNeighbors(),
            metrics.hasFluidAbove(),
            stableTicks
        );

        return new LavaFlowProfile(
            stillReservoir,
            metrics.surfaceEdgeCount() > 0,
            metrics.immediateDownwardOutlet(),
            flowActive,
            metrics.hasFluidAbove(),
            metrics.lateralLavaNeighbors(),
            stableTicks
        );
    }

    public boolean isStillReservoir() {
        return stillReservoir;
    }

    public boolean shouldUseMacroScheduling(HierarchicalDistanceManager.RangeTier tier) {
        return stillReservoir
            && (tier == HierarchicalDistanceManager.RangeTier.FAR
            || tier == HierarchicalDistanceManager.RangeTier.DISTANT);
    }

    public ParallelFluidTickManager.DelayBucket getMacroDelayBucket(HierarchicalDistanceManager.RangeTier tier) {
        return tier == HierarchicalDistanceManager.RangeTier.DISTANT
            ? ParallelFluidTickManager.DelayBucket.DISTANT
            : ParallelFluidTickManager.DelayBucket.FAR;
    }

    public int getStableInteriorDelay(int baseDelay) {
        return FluidRegressionLogic.getStillLavaDelay(baseDelay, hasFluidAbove);
    }

    public int adjustScheduledDelay(int baseDelay) {
        return FluidRegressionLogic.adjustLavaAdaptiveDelay(baseDelay, stillReservoir, hasFluidAbove);
    }

    public boolean shouldReplenishUltraWarmReservoir(boolean ultraWarmDimension, int amount) {
        return FluidRegressionLogic.shouldReplenishUltraWarmLavaReservoir(
            ultraWarmDimension,
            amount,
            stillReservoir,
            lateralLavaNeighbors,
            hasFluidAbove
        );
    }

    public boolean shouldQueueEqualizer(int delta, boolean beforeEmpty, boolean afterEmpty) {
        return FluidRegressionLogic.shouldQueueLavaEqualizer(
            stillReservoir,
            delta,
            beforeEmpty,
            afterEmpty,
            immediateSurfaceEdge,
            immediateDownwardOutlet,
            flowActive
        );
    }

    public boolean shouldBypassLowEquilibriumEqualizerGate() {
        return shouldBypassLowEquilibriumEqualizerGate(
            stillReservoir,
            immediateSurfaceEdge,
            immediateDownwardOutlet,
            flowActive
        );
    }

    public int clampEqualizerDepth(int maxDepth, int configuredMaxDepth) {
        int desiredCap = stillReservoir
            ? 4
            : (immediateDownwardOutlet ? 8 : (immediateSurfaceEdge ? 7 : 6));
        int configuredCap = Math.max(3, Math.min(configuredMaxDepth, desiredCap));
        return Math.min(maxDepth, configuredCap);
    }

    public int getMinimumEqualizerDepth() {
        if (immediateDownwardOutlet) {
            return 6;
        }
        if (immediateSurfaceEdge || flowActive) {
            return 5;
        }
        return stillReservoir ? 3 : 4;
    }

    public int clampEqualizerNodes(int maxNodes) {
        int nodeCap = stillReservoir
            ? 192
            : (immediateDownwardOutlet ? 512 : (immediateSurfaceEdge || flowActive ? 384 : 256));
        return Math.min(maxNodes, nodeCap);
    }

    public int getMinimumEqualizerNodes() {
        if (immediateDownwardOutlet) {
            return 256;
        }
        if (immediateSurfaceEdge || flowActive) {
            return 192;
        }
        return stillReservoir ? 128 : 160;
    }

    public float adjustEqualizerLoadFactor(float baseFactor) {
        float multiplier = stillReservoir
            ? 0.45f
            : (immediateDownwardOutlet ? 1.0f : (immediateSurfaceEdge || flowActive ? 0.85f : 0.7f));
        return Mth.clamp(baseFactor * multiplier, 0.25f, 1.0f);
    }

    public int computeDistanceScaledSnapshotRadius(int maxDepth, float distanceLoadFactor) {
        return computeDistanceScaledSnapshotRadius(
            maxDepth,
            distanceLoadFactor,
            stillReservoir,
            immediateSurfaceEdge,
            immediateDownwardOutlet
        );
    }

    static int computeDistanceScaledSnapshotRadius(int maxDepth, float distanceLoadFactor,
                                                   boolean stillReservoir,
                                                   boolean immediateSurfaceEdge,
                                                   boolean immediateDownwardOutlet) {
        float clampedLoadFactor = Mth.clamp(distanceLoadFactor, 0.25f, 1.0f);
        int edgePadding = immediateDownwardOutlet ? 2 : (immediateSurfaceEdge ? 1 : 0);
        int snapshotRadius = Math.max(maxDepth, Math.max(4, Math.round(maxDepth * (0.75f + 0.25f * clampedLoadFactor))));
        int lavaFlowCap = Math.max(FlowingFluids.config.lavaFlowDistance, FlowingFluids.config.lavaNetherFlowDistance);
        int cap = stillReservoir
            ? 6
            : (immediateDownwardOutlet ? Math.max(10, lavaFlowCap + 4) : 8);
        return Math.min(snapshotRadius + edgePadding, Math.max(maxDepth, cap));
    }

    public boolean shouldRunInletProbe() {
        return false;
    }

    public int clampMomentumCap(int momentumCap) {
        int cap = stillReservoir
            ? 32
            : (immediateDownwardOutlet ? 96 : (immediateSurfaceEdge ? 64 : 48));
        return Math.min(cap, momentumCap);
    }

    public int getVisitedPromotionVarianceThreshold() {
        if (immediateDownwardOutlet) {
            return 1;
        }
        if (immediateSurfaceEdge || flowActive) {
            return 2;
        }
        return stillReservoir ? 4 : 3;
    }

    static boolean shouldBypassLowEquilibriumEqualizerGate(boolean stillReservoir,
                                                           boolean immediateSurfaceEdge,
                                                           boolean immediateDownwardOutlet,
                                                           boolean flowActive) {
        if (immediateSurfaceEdge || immediateDownwardOutlet) {
            return true;
        }
        return flowActive && !stillReservoir;
    }

    private static NeighborhoodMetrics sampleNeighborhood(Level level, BlockPos pos, Fluid fluidType,
                                                          @Nullable FlowingFluid flowingFluid, int amount,
                                                          @Nullable FluidSectionDataCache sectionCache) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        SampledCell above = sampleCell(level, cursor.set(pos.getX(), pos.getY() + 1, pos.getZ()), fluidType, sectionCache);
        SampledCell below = sampleCell(level, cursor.set(pos.getX(), pos.getY() - 1, pos.getZ()), fluidType, sectionCache);
        boolean hasFluidAbove = above.matches(fluidType);
        boolean supportedBelow = (below.matches(fluidType) && below.amount() >= amount)
            || (!below.air() && !below.replaceable());

        BlockState sourceState = flowingFluid != null ? level.getBlockState(pos) : null;
        boolean immediateDownwardOutlet = false;
        if (flowingFluid != null && (!below.matches(fluidType) || below.amount() < amount)) {
            BlockPos belowPos = pos.below();
            BlockState belowState = level.getBlockState(belowPos);
            FluidState belowFluid = FFFluidUtils.getEffectiveFluidState(level, belowPos, belowState);
            immediateDownwardOutlet = FFFluidUtils.canFluidFlowFromPosToDirection(
                flowingFluid,
                Math.max(1, amount),
                level,
                pos,
                sourceState,
                Direction.DOWN,
                belowPos,
                belowState,
                belowFluid
            ) && (belowFluid.isEmpty() || !belowFluid.getType().isSame(fluidType) || belowFluid.getAmount() < amount);
        }

        int lateralLavaNeighbors = 0;
        int surfaceEdgeCount = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, dir);
            SampledCell neighbor = sampleCell(level, cursor, fluidType, sectionCache);
            if (neighbor.matches(fluidType)) {
                lateralLavaNeighbors++;
            } else if (neighbor.isSurfaceEdge()) {
                surfaceEdgeCount++;
            }
        }

        return new NeighborhoodMetrics(
            hasFluidAbove,
            supportedBelow,
            immediateDownwardOutlet,
            lateralLavaNeighbors,
            surfaceEdgeCount
        );
    }

    private static SampledCell sampleCell(Level level, BlockPos.MutableBlockPos cursor, Fluid fluidType,
                                          @Nullable FluidSectionDataCache sectionCache) {
        if (sectionCache != null) {
            int x = cursor.getX();
            int y = cursor.getY();
            int z = cursor.getZ();
            return new SampledCell(
                sectionCache.fluidType(x, y, z),
                sectionCache.amount(x, y, z),
                sectionCache.isAir(x, y, z),
                sectionCache.isReplaceable(x, y, z)
            );
        }
        BlockState state = level.getBlockState(cursor);
        FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, cursor, state);
        return new SampledCell(
            fluidState.isEmpty() ? null : fluidState.getType(),
            fluidState.getAmount(),
            state.isAir(),
            state.canBeReplaced(fluidType)
        );
    }

    private static boolean shouldSampleStableTicks(int amount, boolean flowActive, NeighborhoodMetrics metrics) {
        return amount >= 6
            && !flowActive
            && metrics.lateralLavaNeighbors() >= 3
            && metrics.supportedBelow()
            && !metrics.immediateDownwardOutlet();
    }

    private record NeighborhoodMetrics(boolean hasFluidAbove,
                                       boolean supportedBelow,
                                       boolean immediateDownwardOutlet,
                                       int lateralLavaNeighbors,
                                       int surfaceEdgeCount) {
    }

    private record SampledCell(@Nullable Fluid fluid, int amount, boolean air, boolean replaceable) {
        private boolean matches(Fluid targetFluid) {
            return fluid != null && fluid.isSame(targetFluid) && amount > 0;
        }

        private boolean isSurfaceEdge() {
            return amount <= 0 && (air || replaceable);
        }
    }
}
