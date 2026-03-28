package traben.flowing_fluids.optimization;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FluidSectionDataCache;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.ParallelFluidTickManager;

public final class WaterFlowProfile {
    public enum FlowSpeed {
        STILL,
        SLOW,
        NORMAL,
        FAST,
        TORRENT
    }

    public enum Regime {
        TRICKLE,
        LOCAL,
        CHANNEL,
        SUBTERRANEAN_POOL,
        LARGE_BODY,
        IMPOUNDED,
        BREACH
    }

    private static final WaterFlowProfile NON_WATER = new WaterFlowProfile(
        Regime.LOCAL, false, false, false, false, false,
        false, false, false, false, 0, 0, 0, 0.0f
    );

    private final Regime regime;
    private final boolean broadSurface;
    private final boolean calmInterior;
    private final boolean immediateSurfaceEdge;
    private final boolean immediateDownwardOutlet;
    private final boolean flowActive;
    private final boolean oceanLikeBiome;
    private final boolean riverLikeBiome;
    private final boolean subterranean;
    private final boolean enclosedReservoir;
    private final int lateralWaterNeighbors;
    private final int lateralEscapeRoutes;
    private final int stackedColumnHeight;
    private final float flowMomentum;

    private WaterFlowProfile(Regime regime, boolean broadSurface, boolean calmInterior,
                             boolean immediateSurfaceEdge, boolean immediateDownwardOutlet,
                             boolean flowActive, boolean oceanLikeBiome, boolean riverLikeBiome,
                             boolean subterranean, boolean enclosedReservoir,
                             int lateralWaterNeighbors, int lateralEscapeRoutes,
                             int stackedColumnHeight, float flowMomentum) {
        this.regime = regime;
        this.broadSurface = broadSurface;
        this.calmInterior = calmInterior;
        this.immediateSurfaceEdge = immediateSurfaceEdge;
        this.immediateDownwardOutlet = immediateDownwardOutlet;
        this.flowActive = flowActive;
        this.oceanLikeBiome = oceanLikeBiome;
        this.riverLikeBiome = riverLikeBiome;
        this.subterranean = subterranean;
        this.enclosedReservoir = enclosedReservoir;
        this.lateralWaterNeighbors = lateralWaterNeighbors;
        this.lateralEscapeRoutes = lateralEscapeRoutes;
        this.stackedColumnHeight = stackedColumnHeight;
        this.flowMomentum = flowMomentum;
    }

    public static WaterFlowProfile analyze(Level level, BlockPos pos, FluidState fluidState, int amount) {
        return analyze(level, pos, fluidState, amount, null);
    }

    public static WaterFlowProfile analyze(Level level, BlockPos pos, FluidState fluidState, int amount,
                                           @Nullable FluidSectionDataCache sectionCache) {
        if (level == null || pos == null || fluidState == null || !fluidState.is(FluidTags.WATER) || amount <= 0) {
            return NON_WATER;
        }

        Fluid fluidType = fluidState.getType();
        FlowingFluid flowingFluid = fluidType instanceof FlowingFluid typed ? typed : null;
        boolean flowActive = AdaptiveTickScheduler.isFlowActiveNow(level, pos);
        float flowMomentum = FlowingFluids.config.flowInertiaMaxAgeTicks > 0
            ? AdaptiveTickScheduler.getFlowMomentum(level, pos, FlowingFluids.config.flowInertiaMaxAgeTicks)
            : 0.0f;
        BasicNeighborhoodMetrics basicMetrics = sampleBasicNeighborhood(level, pos, fluidType, amount, sectionCache);

        var biome = level.getBiome(pos);
        boolean oceanLikeBiome = FFFluidUtils.isOceanBiome(biome) || FFFluidUtils.isBeachBiome(biome);
        boolean riverLikeBiome = FFFluidUtils.isRiverBiome(biome);
        WaterFlowProfile fastPathProfile = tryFastCalmInteriorProfile(
            amount,
            flowActive,
            flowMomentum,
            oceanLikeBiome,
            riverLikeBiome,
            basicMetrics
        );
        if (fastPathProfile != null) {
            return fastPathProfile;
        }

        NeighborhoodMetrics metrics = sampleNeighborhood(level, pos, fluidType, flowingFluid, amount, sectionCache, basicMetrics);
        boolean shouldSampleStableTicks = shouldSampleStableTicks(amount, flowActive, oceanLikeBiome, riverLikeBiome, metrics);
        int stableTicks = shouldSampleStableTicks ? AdaptiveTickScheduler.getPoolStableTicks(level, pos, 20) : 0;
        int stackedColumnHeight = shouldMeasureStackedColumnHeight(amount, metrics)
            ? getStackedColumnHeight(level, pos, fluidType, metrics.hasFluidAbove(), sectionCache)
            : 1;

        boolean broadSurface = FFFluidUtils.classifyBroadSurfaceWater(
            oceanLikeBiome,
            riverLikeBiome,
            metrics.lateralWaterNeighbors(),
            metrics.hasFluidAbove(),
            metrics.supportedBelow(),
            metrics.immediateDownwardOutlet(),
            stableTicks,
            FlowingFluids.config.broadSurfaceStableTicks
        );
        boolean shouldCheckSubterranean = broadSurface || isReservoirCandidate(amount, flowActive, oceanLikeBiome, riverLikeBiome, metrics);
        boolean subterranean = shouldCheckSubterranean && isSubterranean(level, pos);
        boolean calmInterior = broadSurface && !flowActive && metrics.surfaceEdgeCount() == 0 && !metrics.immediateDownwardOutlet();
        boolean enclosedReservoir = subterranean
            && !oceanLikeBiome
            && !riverLikeBiome
            && !flowActive
            && stableTicks >= Math.max(3, FlowingFluids.config.broadSurfaceStableTicks - 2)
            && amount >= 4
            && metrics.lateralWaterNeighbors() >= 2
            && metrics.surfaceEdgeCount() == 0
            && metrics.lateralEscapeRoutes() <= 1
            && metrics.supportedBelow()
            && !metrics.immediateDownwardOutlet();

        int confinedSides = Math.max(0, 4 - metrics.lateralEscapeRoutes());
        boolean pressureLoaded = amount >= 6 && stackedColumnHeight >= 2 && confinedSides >= 2;
        boolean impounded = pressureLoaded && (metrics.surfaceEdgeCount() > 0 || metrics.immediateDownwardOutlet() || metrics.lateralEscapeRoutes() <= 1);
        boolean breach = impounded && (metrics.surfaceEdgeCount() > 0 || metrics.immediateDownwardOutlet())
            && (flowActive || flowMomentum > 0.18f || stackedColumnHeight >= 3);

        Regime regime;
        if (amount <= 2 || (!metrics.supportedBelow() && metrics.lateralWaterNeighbors() <= 1)) {
            regime = Regime.TRICKLE;
        } else if (breach) {
            regime = Regime.BREACH;
        } else if (impounded) {
            regime = Regime.IMPOUNDED;
        } else if (enclosedReservoir) {
            regime = Regime.SUBTERRANEAN_POOL;
        } else if (calmInterior) {
            regime = Regime.LARGE_BODY;
        } else if (riverLikeBiome || metrics.immediateDownwardOutlet() || metrics.lateralEscapeRoutes() <= 1 || flowMomentum > 0.45f) {
            regime = Regime.CHANNEL;
        } else {
            regime = Regime.LOCAL;
        }

        return new WaterFlowProfile(
            regime,
            broadSurface,
            calmInterior,
            metrics.surfaceEdgeCount() > 0,
            metrics.immediateDownwardOutlet(),
            flowActive,
            oceanLikeBiome,
            riverLikeBiome,
            subterranean,
            enclosedReservoir,
            metrics.lateralWaterNeighbors(),
            metrics.lateralEscapeRoutes(),
            stackedColumnHeight,
            flowMomentum
        );
    }

    static boolean qualifiesForFastCalmInterior(int amount, boolean flowActive, float flowMomentum,
                                                boolean oceanLikeBiome, boolean riverLikeBiome,
                                                boolean hasFluidAbove, boolean supportedBelow,
                                                int lateralWaterNeighbors, int surfaceEdgeCount) {
        if (amount < 8
                || flowActive
                || flowMomentum > 0.12f
                || riverLikeBiome
                || hasFluidAbove
                || !supportedBelow
                || lateralWaterNeighbors < 4
                || surfaceEdgeCount > 0) {
            return false;
        }
        return oceanLikeBiome || lateralWaterNeighbors >= 4;
    }

    private static @Nullable WaterFlowProfile tryFastCalmInteriorProfile(int amount, boolean flowActive, float flowMomentum,
                                                                         boolean oceanLikeBiome, boolean riverLikeBiome,
                                                                         BasicNeighborhoodMetrics metrics) {
        if (!qualifiesForFastCalmInterior(
            amount,
            flowActive,
            flowMomentum,
            oceanLikeBiome,
            riverLikeBiome,
            metrics.hasFluidAbove(),
            metrics.supportedBelow(),
            metrics.lateralWaterNeighbors(),
            metrics.surfaceEdgeCount()
        )) {
            return null;
        }

        return new WaterFlowProfile(
            Regime.LARGE_BODY,
            true,
            true,
            false,
            false,
            false,
            oceanLikeBiome,
            riverLikeBiome,
            false,
            false,
            metrics.lateralWaterNeighbors(),
            0,
            1,
            flowMomentum
        );
    }

    public Regime regime() {
        return regime;
    }

    public boolean isBroadSurface() {
        return broadSurface;
    }

    public boolean isCalmInterior() {
        return calmInterior;
    }

    public boolean isStillReservoir() {
        return calmInterior || enclosedReservoir;
    }

    public boolean isPressureDriven() {
        return regime == Regime.IMPOUNDED || regime == Regime.BREACH || regime == Regime.CHANNEL;
    }

    public boolean shouldBypassStableTransferSuppression() {
        return regime == Regime.IMPOUNDED || regime == Regime.BREACH || regime == Regime.CHANNEL;
    }

    public boolean isInletZone() {
        return immediateSurfaceEdge
            && !subterranean
            && !enclosedReservoir
            && !immediateDownwardOutlet
            && lateralWaterNeighbors >= 2
            && lateralEscapeRoutes >= 2
            && (riverLikeBiome || oceanLikeBiome || broadSurface || stackedColumnHeight >= 2);
    }

    public boolean isRiverInletZone() {
        return riverLikeBiome && isInletZone();
    }

    public boolean shouldUseMacroScheduling(HierarchicalDistanceManager.RangeTier tier) {
        if (regime == Regime.SUBTERRANEAN_POOL) {
            return tier == HierarchicalDistanceManager.RangeTier.FAR
                || tier == HierarchicalDistanceManager.RangeTier.DISTANT;
        }
        return regime == Regime.LARGE_BODY
            && (tier == HierarchicalDistanceManager.RangeTier.FAR || tier == HierarchicalDistanceManager.RangeTier.DISTANT);
    }

    public ParallelFluidTickManager.DelayBucket getMacroDelayBucket(HierarchicalDistanceManager.RangeTier tier) {
        if (regime == Regime.SUBTERRANEAN_POOL && tier != HierarchicalDistanceManager.RangeTier.DISTANT) {
            return ParallelFluidTickManager.DelayBucket.DISTANT;
        }
        return tier == HierarchicalDistanceManager.RangeTier.DISTANT
            ? ParallelFluidTickManager.DelayBucket.DISTANT
            : ParallelFluidTickManager.DelayBucket.FAR;
    }

    public int getStableInteriorDelay(int baseDelay) {
        int multiplier = subterranean ? 8 : (oceanLikeBiome ? 7 : 5);
        int desiredDelay = Math.max(8, baseDelay * multiplier);
        if (FlowingFluids.config.enableHydraulicGradientFlow) {
            desiredDelay = Math.max(desiredDelay, baseDelay * 3);
        }
        return Math.min(subterranean ? 120 : 96, desiredDelay);
    }

    public int adjustScheduledDelay(int baseDelay) {
        if (isInletZone()) {
            return Math.max(1, Math.round(baseDelay * 1.05f));
        }
        float multiplier = switch (regime) {
            case TRICKLE -> 1.0f;
            case LOCAL -> 1.0f;
            case CHANNEL -> 0.85f;
            case SUBTERRANEAN_POOL -> 1.85f;
            case LARGE_BODY -> 1.5f;
            case IMPOUNDED -> 0.82f;
            case BREACH -> 0.65f;
        };
        return Math.max(1, Math.round(baseDelay * multiplier));
    }

    public float getDirectionalTransferBias() {
        float baseBias = switch (regime) {
            case TRICKLE -> 0.2f;
            case LOCAL -> 0.1f;
            case CHANNEL -> isInletZone() ? 0.45f : 0.85f;
            case SUBTERRANEAN_POOL -> 0.0f;
            case LARGE_BODY -> 0.0f;
            case IMPOUNDED -> 0.95f;
            case BREACH -> 1.35f;
        };
        if (immediateSurfaceEdge || immediateDownwardOutlet) {
            baseBias += 0.15f;
        }
        if (flowActive) {
            baseBias += 0.15f;
        }
        if (flowMomentum > 0.18f) {
            baseBias += Math.min(0.25f, flowMomentum * 0.4f);
        }
        return Mth.clamp(baseBias, 0.0f, 1.75f);
    }

    public int getDownwardRetentionRelief() {
        int relief = switch (regime) {
            case BREACH -> 2;
            case CHANNEL, IMPOUNDED -> 1;
            case TRICKLE, LOCAL, SUBTERRANEAN_POOL, LARGE_BODY -> 0;
        };
        if (immediateDownwardOutlet || flowActive) {
            relief++;
        }
        return Math.min(3, relief);
    }

    public float getPressureTransferScale() {
        return switch (regime) {
            case TRICKLE -> 0.85f;
            case LOCAL -> 1.0f;
            case CHANNEL -> isInletZone() ? 0.96f : 1.08f;
            case SUBTERRANEAN_POOL -> 0.25f;
            case LARGE_BODY -> 0.35f;
            case IMPOUNDED -> 1.2f;
            case BREACH -> 1.45f;
        };
    }

    public float getHydraulicTransferScale() {
        return switch (regime) {
            case TRICKLE -> 0.9f;
            case LOCAL -> 1.0f;
            case CHANNEL -> isInletZone() ? 0.92f : 1.12f;
            case SUBTERRANEAN_POOL -> 0.3f;
            case LARGE_BODY -> 0.45f;
            case IMPOUNDED -> 1.25f;
            case BREACH -> 1.55f;
        };
    }

    public int refineSlopeDistance(int baseDistance, int rangeClamp, int broadSurfaceClamp) {
        int corridorClamp = Math.max(2, rangeClamp);
        return switch (regime) {
            case LARGE_BODY -> Math.min(baseDistance, Math.max(2, Math.min(corridorClamp, broadSurfaceClamp)));
            case SUBTERRANEAN_POOL -> Math.min(baseDistance, Math.max(2, Math.min(corridorClamp, 3)));
            case IMPOUNDED -> Math.min(baseDistance, Math.max(3, corridorClamp));
            case BREACH -> Math.min(baseDistance, Math.max(5, corridorClamp + 2));
            case CHANNEL -> Math.min(baseDistance, Math.max(4, corridorClamp + 1));
            case TRICKLE -> Math.min(baseDistance, Math.max(3, corridorClamp));
            case LOCAL -> Math.min(baseDistance, Math.max(4, corridorClamp + 1));
        };
    }

    public boolean shouldSuppressExploratorySpread() {
        return regime == Regime.LARGE_BODY || regime == Regime.SUBTERRANEAN_POOL;
    }

    public boolean shouldQueueEqualizer(int delta, boolean beforeEmpty, boolean afterEmpty) {
        if (beforeEmpty || afterEmpty || immediateDownwardOutlet) {
            return true;
        }
        if (immediateSurfaceEdge && !isInletZone()) {
            return true;
        }
        return switch (regime) {
            case TRICKLE -> delta >= 1;
            case LOCAL -> delta >= 2;
            case CHANNEL -> isInletZone()
                ? delta >= 3 || flowActive || flowMomentum > 0.35f
                : true;
            case SUBTERRANEAN_POOL -> delta >= 4 || flowActive;
            case LARGE_BODY -> delta >= 2 || flowActive;
            case IMPOUNDED -> delta >= 2 || flowMomentum > 0.12f;
            case BREACH -> true;
        };
    }

    public int clampEqualizerDepth(int maxDepth, int configuredMaxDepth) {
        return switch (regime) {
            case LARGE_BODY -> Math.min(maxDepth, Math.max(4, FlowingFluids.config.broadSurfaceSlopeClamp + 2));
            case SUBTERRANEAN_POOL -> Math.min(maxDepth, 4);
            case IMPOUNDED -> Math.min(maxDepth, Math.max(5, configuredMaxDepth - 2));
            case BREACH -> Math.min(maxDepth, Math.max(6, configuredMaxDepth));
            case CHANNEL -> Math.min(maxDepth, Math.max(5, configuredMaxDepth - 1));
            case TRICKLE, LOCAL -> maxDepth;
        };
    }

    public int getMinimumEqualizerDepth() {
        return switch (regime) {
            case LARGE_BODY -> 2;
            case SUBTERRANEAN_POOL -> 3;
            case IMPOUNDED -> 5;
            case BREACH -> 6;
            case CHANNEL -> 5;
            case TRICKLE, LOCAL -> 4;
        };
    }

    public int clampEqualizerNodes(int maxNodes) {
        return switch (regime) {
            case LARGE_BODY -> Math.min(maxNodes, 320);
            case SUBTERRANEAN_POOL -> Math.min(maxNodes, 224);
            case IMPOUNDED -> Math.min(maxNodes, 768);
            case BREACH -> Math.min(Math.max(maxNodes, 640), 1152);
            case CHANNEL -> Math.min(maxNodes, 896);
            case TRICKLE, LOCAL -> maxNodes;
        };
    }

    public int getMinimumEqualizerNodes() {
        return switch (regime) {
            case BREACH -> 320;
            case IMPOUNDED, CHANNEL -> 256;
            case LARGE_BODY, SUBTERRANEAN_POOL, TRICKLE, LOCAL -> 160;
        };
    }

    public float adjustEqualizerLoadFactor(float baseFactor) {
        float multiplier = switch (regime) {
            case LARGE_BODY -> 0.55f;
            case SUBTERRANEAN_POOL -> 0.45f;
            case IMPOUNDED -> 0.85f;
            case BREACH -> 1.0f;
            case CHANNEL -> isInletZone() ? 0.68f : 0.95f;
            case TRICKLE, LOCAL -> 1.0f;
        };
        return Mth.clamp(baseFactor * multiplier, 0.25f, 1.0f);
    }

    public int computeDistanceScaledSnapshotRadius(int maxDepth, float distanceLoadFactor) {
        return computeDistanceScaledSnapshotRadius(maxDepth, distanceLoadFactor, shouldRunInletProbe(), regime);
    }

    static int computeDistanceScaledSnapshotRadius(int maxDepth, float distanceLoadFactor,
                                                   boolean allowInletProbe, Regime regime) {
        float clampedLoadFactor = Mth.clamp(distanceLoadFactor, 0.25f, 1.0f);
        int inletProbeRadius = allowInletProbe
            ? Math.max(0, Math.round(FlowingFluids.config.inletProbeMaxSteps * clampedLoadFactor))
            : 0;
        int horizontalSweepRadius = Math.max(1, Math.round(
            FlowingFluids.config.horizontalSupplementDepth * (0.6f + 0.4f * clampedLoadFactor)));
        // Keep capture wide enough for the actual BFS depth and the scaled side probes,
        // but avoid forcing every request up to the raw config floor when load shedding
        // has already reduced the work we intend to do.
        int snapshotRadius = Math.max(maxDepth, Math.max(6, Math.max(inletProbeRadius, horizontalSweepRadius)));
        return clampSnapshotRadius(snapshotRadius, regime);
    }

    private static int clampSnapshotRadius(int snapshotRadius, Regime regime) {
        return switch (regime) {
            case LARGE_BODY -> Math.min(snapshotRadius, Math.max(6, FlowingFluids.config.broadSurfaceSlopeClamp + 4));
            case SUBTERRANEAN_POOL -> Math.min(snapshotRadius, 6);
            case IMPOUNDED -> Math.min(snapshotRadius, Math.max(8, FlowingFluids.config.bfsMaxSearchDistance));
            case BREACH -> Math.min(snapshotRadius, Math.max(10, FlowingFluids.config.bfsMaxSearchDistance + 2));
            case CHANNEL -> Math.min(snapshotRadius, Math.max(8, FlowingFluids.config.bfsMaxSearchDistance));
            case TRICKLE, LOCAL -> snapshotRadius;
        };
    }

    public boolean shouldRunInletProbe() {
        return regime != Regime.LARGE_BODY && regime != Regime.SUBTERRANEAN_POOL;
    }

    public int clampMomentumCap(int momentumCap) {
        return switch (regime) {
            case LARGE_BODY -> Math.min(64, momentumCap);
            case SUBTERRANEAN_POOL -> Math.min(48, momentumCap);
            case IMPOUNDED -> Math.min(96, momentumCap);
            case BREACH, CHANNEL, TRICKLE, LOCAL -> momentumCap;
        };
    }

    public int getVisitedPromotionVarianceThreshold() {
        return switch (regime) {
            case LARGE_BODY -> 2;
            case SUBTERRANEAN_POOL -> 5;
            case IMPOUNDED -> 3;
            case BREACH -> 1;
            case CHANNEL -> 2;
            case TRICKLE, LOCAL -> 2;
        };
    }

    public boolean hasImmediateSurfaceEdge() {
        return immediateSurfaceEdge;
    }

    public boolean hasImmediateDownwardOutlet() {
        return immediateDownwardOutlet;
    }

    public int lateralWaterNeighbors() {
        return lateralWaterNeighbors;
    }

    public int lateralEscapeRoutes() {
        return lateralEscapeRoutes;
    }

    public int stackedColumnHeight() {
        return stackedColumnHeight;
    }

    public float flowMomentum() {
        return flowMomentum;
    }

    public boolean isFlowActive() {
        return flowActive;
    }

    public boolean isRiverLikeBiome() {
        return riverLikeBiome;
    }

    public boolean isSubterranean() {
        return subterranean;
    }

    public FlowSpeed getFlowSpeed() {
        int score = switch (regime) {
            case TRICKLE -> 0;
            case LOCAL -> 1;
            case LARGE_BODY, SUBTERRANEAN_POOL -> 0;
            case CHANNEL -> 2;
            case IMPOUNDED -> 2;
            case BREACH -> 3;
        };
        if (flowActive) {
            score += 1;
        }
        if (flowMomentum > 0.65f) {
            score += 1;
        } else if (flowMomentum < 0.12f) {
            score -= 1;
        }
        if (immediateDownwardOutlet || immediateSurfaceEdge) {
            score += 1;
        }
        if (stackedColumnHeight >= 3) {
            score += 1;
        }
        if (broadSurface && !riverLikeBiome) {
            score -= 1;
        }
        score = Mth.clamp(score, 0, 4);
        return switch (score) {
            case 0 -> FlowSpeed.STILL;
            case 1 -> FlowSpeed.SLOW;
            case 2 -> FlowSpeed.NORMAL;
            case 3 -> FlowSpeed.FAST;
            default -> FlowSpeed.TORRENT;
        };
    }

    public float getFlowSpeedTransferBonus() {
        if (!FlowingFluids.config.enableFlowSpeedControl) {
            return 0.0f;
        }
        float strength = Math.max(0.0f, FlowingFluids.config.flowSpeedStrength);
        return switch (getFlowSpeed()) {
            case STILL -> 0.0f;
            case SLOW -> 0.04f * strength;
            case NORMAL -> 0.1f * strength;
            case FAST -> 0.2f * strength;
            case TORRENT -> 0.34f * strength;
        };
    }

    public float getFlowSpeedDirectionalBonus() {
        if (!FlowingFluids.config.enableFlowSpeedControl) {
            return 0.0f;
        }
        float strength = Math.max(0.0f, FlowingFluids.config.flowSpeedStrength);
        return switch (getFlowSpeed()) {
            case STILL -> 0.0f;
            case SLOW -> 0.01f * strength;
            case NORMAL -> 0.025f * strength;
            case FAST -> 0.05f * strength;
            case TORRENT -> 0.08f * strength;
        };
    }

    public float getFlowSpeedMomentumBonus() {
        if (!FlowingFluids.config.enableFlowSpeedControl) {
            return 0.0f;
        }
        float strength = Math.max(0.0f, FlowingFluids.config.flowSpeedStrength);
        return switch (getFlowSpeed()) {
            case STILL -> 0.0f;
            case SLOW -> 0.03f * strength;
            case NORMAL -> 0.07f * strength;
            case FAST -> 0.12f * strength;
            case TORRENT -> 0.2f * strength;
        };
    }

    private static BasicNeighborhoodMetrics sampleBasicNeighborhood(Level level, BlockPos pos, Fluid fluidType,
                                                                    int amount, @Nullable FluidSectionDataCache sectionCache) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        SampledCell above = sampleCell(level, cursor.set(pos.getX(), pos.getY() + 1, pos.getZ()), fluidType, sectionCache);
        SampledCell below = sampleCell(level, cursor.set(pos.getX(), pos.getY() - 1, pos.getZ()), fluidType, sectionCache);
        boolean hasFluidAbove = above.matches(fluidType);
        boolean supportedBelow = (below.matches(fluidType) && below.amount() >= amount)
            || (!below.air() && !below.replaceable());

        int lateralWaterNeighbors = 0;
        int surfaceEdgeCount = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, dir);
            SampledCell neighbor = sampleCell(level, cursor, fluidType, sectionCache);
            if (neighbor.matches(fluidType)) {
                lateralWaterNeighbors++;
            } else if (neighbor.isSurfaceEdge()) {
                surfaceEdgeCount++;
            }
        }
        return new BasicNeighborhoodMetrics(
            hasFluidAbove,
            supportedBelow,
            lateralWaterNeighbors,
            surfaceEdgeCount
        );
    }

    private static NeighborhoodMetrics sampleNeighborhood(Level level, BlockPos pos, Fluid fluidType,
                                                          @Nullable FlowingFluid flowingFluid, int amount,
                                                          @Nullable FluidSectionDataCache sectionCache,
                                                          BasicNeighborhoodMetrics basicMetrics) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockState sourceState = flowingFluid != null ? level.getBlockState(pos) : null;
        boolean immediateDownwardOutlet = false;
        if (flowingFluid != null && !basicMetrics.supportedBelow()) {
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

        int lateralEscapeRoutes = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            cursor.setWithOffset(pos, dir);
            SampledCell neighbor = sampleCell(level, cursor, fluidType, sectionCache);
            if (flowingFluid == null || (neighbor.matches(fluidType) && neighbor.amount() >= amount)) {
                continue;
            }
            BlockState neighborState = level.getBlockState(cursor);
            FluidState neighborFluid = FFFluidUtils.getEffectiveFluidState(level, cursor, neighborState);
            if (FFFluidUtils.canFluidFlowFromPosToDirection(
                flowingFluid,
                Math.max(1, amount),
                level,
                pos,
                sourceState,
                dir,
                cursor,
                neighborState,
                neighborFluid
            )) {
                lateralEscapeRoutes++;
            }
        }
        return new NeighborhoodMetrics(
            basicMetrics.hasFluidAbove(),
            basicMetrics.supportedBelow(),
            immediateDownwardOutlet,
            basicMetrics.lateralWaterNeighbors(),
            basicMetrics.surfaceEdgeCount(),
            lateralEscapeRoutes
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

    private static boolean shouldSampleStableTicks(int amount, boolean flowActive, boolean oceanLikeBiome,
                                                   boolean riverLikeBiome, NeighborhoodMetrics metrics) {
        boolean broadSurfaceCandidate = !riverLikeBiome
            && metrics.lateralWaterNeighbors() >= 3
            && !metrics.hasFluidAbove()
            && metrics.supportedBelow()
            && !metrics.immediateDownwardOutlet();
        boolean reservoirCandidate = isReservoirCandidate(amount, flowActive, oceanLikeBiome, riverLikeBiome, metrics);
        return broadSurfaceCandidate || reservoirCandidate;
    }

    private static boolean isReservoirCandidate(int amount, boolean flowActive, boolean oceanLikeBiome,
                                                boolean riverLikeBiome, NeighborhoodMetrics metrics) {
        return amount >= 4
            && !oceanLikeBiome
            && !riverLikeBiome
            && !flowActive
            && metrics.lateralWaterNeighbors() >= 2
            && metrics.surfaceEdgeCount() == 0
            && metrics.lateralEscapeRoutes() <= 1
            && metrics.supportedBelow()
            && !metrics.immediateDownwardOutlet();
    }

    private static boolean shouldMeasureStackedColumnHeight(int amount, NeighborhoodMetrics metrics) {
        return metrics.hasFluidAbove()
            || amount >= 6
            || (metrics.surfaceEdgeCount() > 0
                && metrics.lateralWaterNeighbors() >= 2
                && metrics.lateralEscapeRoutes() >= 2);
    }

    private static int getStackedColumnHeight(Level level, BlockPos pos, Fluid fluidType, boolean hasFluidAbove,
                                              @Nullable FluidSectionDataCache sectionCache) {
        if (!hasFluidAbove) {
            return 1;
        }

        int maxColumn = Math.max(2, Math.max(FlowingFluids.config.downwardPressureMaxColumn, 4));
        if (sectionCache != null) {
            return 1 + sectionCache.columnHeight(pos, fluidType, maxColumn);
        }

        int column = 1;
        BlockPos.MutableBlockPos cursor = pos.above().mutable();
        for (int i = 0; i < maxColumn; i++) {
            FluidState state = FFFluidUtils.getEffectiveFluidState(level, cursor, level.getBlockState(cursor));
            if (!state.getType().isSame(fluidType) || state.getAmount() <= 0) {
                break;
            }
            column++;
            cursor.move(Direction.UP);
        }
        return column;
    }

    private static boolean isSubterranean(Level level, BlockPos pos) {
        if (pos.getY() > level.getSeaLevel() - 2) {
            return false;
        }
        if (level.getBrightness(LightLayer.SKY, pos) > 0) {
            return false;
        }
        return !level.canSeeSky(pos.above());
    }

    private record NeighborhoodMetrics(boolean hasFluidAbove, boolean supportedBelow, boolean immediateDownwardOutlet,
                                       int lateralWaterNeighbors, int surfaceEdgeCount, int lateralEscapeRoutes) {
    }

    private record BasicNeighborhoodMetrics(boolean hasFluidAbove, boolean supportedBelow,
                                            int lateralWaterNeighbors, int surfaceEdgeCount) {
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
