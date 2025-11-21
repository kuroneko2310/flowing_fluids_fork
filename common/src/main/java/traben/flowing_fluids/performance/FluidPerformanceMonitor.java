package traben.flowing_fluids.performance;

import traben.flowing_fluids.FlowingFluids;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Performance monitoring system for analyzing the impact of water flow distance on performance.
 * Tracks various metrics including tick times, BFS operations, and memory usage.
 */
public class FluidPerformanceMonitor {

    private static final FluidPerformanceMonitor INSTANCE = new FluidPerformanceMonitor();

    // Performance metrics
    private final AtomicLong totalFluidTicks = new AtomicLong(0);
    private final AtomicLong totalBFSOperations = new AtomicLong(0);
    private final AtomicLong totalBFSNodes = new AtomicLong(0);
    private final AtomicLong totalTickTimeNanos = new AtomicLong(0);
    private final AtomicLong totalBFSTimeNanos = new AtomicLong(0);
    private final AtomicInteger maxFlowDistanceUsed = new AtomicInteger(0);
    private final AtomicInteger maxBFSDepth = new AtomicInteger(0);

    // Distance-based metrics (distance -> count)
    private final ConcurrentHashMap<Integer, AtomicLong> ticksByDistance = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicLong> timeByDistance = new ConcurrentHashMap<>();

    // Optimization tracking
    private final AtomicLong fastPathHits = new AtomicLong(0);
    private final AtomicLong slowPathHits = new AtomicLong(0);
    private final AtomicLong equilibriumSkips = new AtomicLong(0);
    private final AtomicLong spatialGridHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    // Tick counter for logging
    private int tickCounter = 0;
    private int logInterval = 200; // 10 seconds at 20 TPS

    private FluidPerformanceMonitor() {
    }

    public static FluidPerformanceMonitor getInstance() {
        return INSTANCE;
    }

    /**
     * Record a fluid tick with its execution time and flow distance.
     */
    public void recordFluidTick(long nanos, int flowDistance) {
        totalFluidTicks.incrementAndGet();
        totalTickTimeNanos.addAndGet(nanos);

        // Update max distance
        int currentMax = maxFlowDistanceUsed.get();
        if (flowDistance > currentMax) {
            maxFlowDistanceUsed.compareAndSet(currentMax, flowDistance);
        }

        // Record by distance
        ticksByDistance.computeIfAbsent(flowDistance, k -> new AtomicLong()).incrementAndGet();
        timeByDistance.computeIfAbsent(flowDistance, k -> new AtomicLong()).addAndGet(nanos);
    }

    /**
     * Record a BFS operation with node count, time, and maximum depth reached.
     */
    public void recordBFS(long nanos, int nodesVisited, int maxDepth) {
        totalBFSOperations.incrementAndGet();
        totalBFSNodes.addAndGet(nodesVisited);
        totalBFSTimeNanos.addAndGet(nanos);

        // Update max BFS depth
        int currentMaxDepth = maxBFSDepth.get();
        if (maxDepth > currentMaxDepth) {
            maxBFSDepth.compareAndSet(currentMaxDepth, maxDepth);
        }
    }

    /**
     * Record optimization hits and misses.
     */
    public void recordFastPath() {
        fastPathHits.incrementAndGet();
    }

    public void recordSlowPath() {
        slowPathHits.incrementAndGet();
    }

    public void recordEquilibriumSkip() {
        equilibriumSkips.incrementAndGet();
    }

    public void recordSpatialGridHit() {
        spatialGridHits.incrementAndGet();
    }

    public void recordCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    /**
     * Called each game tick to potentially log performance data.
     */
    public void tick(boolean enabled, int interval) {
        if (!enabled) return;

        this.logInterval = interval;
        tickCounter++;

        if (tickCounter >= logInterval) {
            logPerformanceData();
            tickCounter = 0;
        }
    }

    /**
     * Log comprehensive performance data.
     */
    public void logPerformanceData() {
        long ticks = totalFluidTicks.get();
        if (ticks == 0) {
            FlowingFluids.info("=== Fluid Performance Monitor: No data yet ===");
            return;
        }

        FlowingFluids.info("=== Fluid Performance Monitor Report ===");
        FlowingFluids.info(String.format("Total fluid ticks: %,d", ticks));
        FlowingFluids.info(String.format("Total tick time: %.2f ms (avg: %.3f μs/tick)",
                totalTickTimeNanos.get() / 1_000_000.0,
                totalTickTimeNanos.get() / (double) ticks / 1000.0));

        // BFS Statistics
        long bfsOps = totalBFSOperations.get();
        if (bfsOps > 0) {
            FlowingFluids.info(String.format("BFS operations: %,d (%.2f%% of ticks)",
                    bfsOps, (bfsOps * 100.0) / ticks));
            FlowingFluids.info(String.format("BFS time: %.2f ms (avg: %.3f μs/op)",
                    totalBFSTimeNanos.get() / 1_000_000.0,
                    totalBFSTimeNanos.get() / (double) bfsOps / 1000.0));
            FlowingFluids.info(String.format("BFS nodes visited: %,d (avg: %.1f nodes/op)",
                    totalBFSNodes.get(),
                    totalBFSNodes.get() / (double) bfsOps));
            FlowingFluids.info(String.format("Max BFS depth reached: %d blocks", maxBFSDepth.get()));
        }

        // Distance Statistics
        FlowingFluids.info(String.format("Max flow distance used: %d blocks", maxFlowDistanceUsed.get()));
        FlowingFluids.info("Performance by distance:");
        ticksByDistance.keySet().stream()
                .sorted()
                .forEach(distance -> {
                    long distTicks = ticksByDistance.get(distance).get();
                    long distTime = timeByDistance.get(distance).get();
                    FlowingFluids.info(String.format("  Distance %d: %,d ticks (%.2f%%), avg time: %.3f μs",
                            distance,
                            distTicks,
                            (distTicks * 100.0) / ticks,
                            distTime / (double) distTicks / 1000.0));
                });

        // Optimization Statistics
        long fastPaths = fastPathHits.get();
        long slowPaths = slowPathHits.get();
        long totalPaths = fastPaths + slowPaths;
        if (totalPaths > 0) {
            FlowingFluids.info(String.format("Fast path hits: %,d (%.2f%%)",
                    fastPaths, (fastPaths * 100.0) / totalPaths));
            FlowingFluids.info(String.format("Slow path hits: %,d (%.2f%%)",
                    slowPaths, (slowPaths * 100.0) / totalPaths));
        }

        long eqSkips = equilibriumSkips.get();
        if (eqSkips > 0) {
            FlowingFluids.info(String.format("Equilibrium skips: %,d (saved %.2f%% of ticks)",
                    eqSkips, (eqSkips * 100.0) / (ticks + eqSkips)));
        }

        long gridHits = spatialGridHits.get();
        long misses = cacheMisses.get();
        long totalLookups = gridHits + misses;
        if (totalLookups > 0) {
            FlowingFluids.info(String.format("Spatial grid hit rate: %.2f%% (%,d hits, %,d misses)",
                    (gridHits * 100.0) / totalLookups, gridHits, misses));
        }

        FlowingFluids.info("=====================================");
    }

    /**
     * Reset all performance data.
     */
    public void reset() {
        totalFluidTicks.set(0);
        totalBFSOperations.set(0);
        totalBFSNodes.set(0);
        totalTickTimeNanos.set(0);
        totalBFSTimeNanos.set(0);
        maxFlowDistanceUsed.set(0);
        maxBFSDepth.set(0);

        ticksByDistance.clear();
        timeByDistance.clear();

        fastPathHits.set(0);
        slowPathHits.set(0);
        equilibriumSkips.set(0);
        spatialGridHits.set(0);
        cacheMisses.set(0);

        tickCounter = 0;

        FlowingFluids.info("Performance monitor data reset.");
    }

    /**
     * Get a performance report as formatted string.
     */
    public String getPerformanceReport() {
        StringBuilder report = new StringBuilder();
        long ticks = totalFluidTicks.get();

        if (ticks == 0) {
            return "No performance data available yet.";
        }

        report.append("=== Performance Analysis ===\n");
        report.append(String.format("Total Ticks: %,d\n", ticks));
        report.append(String.format("Avg Tick Time: %.3f μs\n",
                totalTickTimeNanos.get() / (double) ticks / 1000.0));

        long bfsOps = totalBFSOperations.get();
        if (bfsOps > 0) {
            report.append(String.format("BFS Usage: %.2f%%\n", (bfsOps * 100.0) / ticks));
            report.append(String.format("Avg BFS Nodes: %.1f\n",
                    totalBFSNodes.get() / (double) bfsOps));
        }

        long fastPaths = fastPathHits.get();
        long slowPaths = slowPathHits.get();
        long totalPaths = fastPaths + slowPaths;
        if (totalPaths > 0) {
            report.append(String.format("Fast Path Rate: %.2f%%\n",
                    (fastPaths * 100.0) / totalPaths));
        }

        return report.toString();
    }

    // Getters for external analysis
    public long getTotalFluidTicks() { return totalFluidTicks.get(); }
    public long getTotalBFSOperations() { return totalBFSOperations.get(); }
    public long getAverageTickTimeNanos() {
        long ticks = totalFluidTicks.get();
        return ticks > 0 ? totalTickTimeNanos.get() / ticks : 0;
    }
    public int getMaxFlowDistanceUsed() { return maxFlowDistanceUsed.get(); }
    public int getMaxBFSDepth() { return maxBFSDepth.get(); }
}
