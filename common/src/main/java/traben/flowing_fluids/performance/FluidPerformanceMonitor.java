package traben.flowing_fluids.performance;

import net.minecraft.server.MinecraftServer;
import traben.flowing_fluids.FlowingFluids;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight runtime counters for fluid hot-path tuning.
 */
public final class FluidPerformanceMonitor implements FluidPerformanceMonitorMBean {

    private static final FluidPerformanceMonitor INSTANCE = new FluidPerformanceMonitor();
    private static final int MSPT_WINDOW = 20;
    private static final long ALLOCATION_UNAVAILABLE = Long.MIN_VALUE;

    private final AtomicLong totalFluidTicks = new AtomicLong();
    private final AtomicLong totalBFSOperations = new AtomicLong();
    private final AtomicLong totalBFSNodes = new AtomicLong();
    private final AtomicLong totalTickTimeNanos = new AtomicLong();
    private final AtomicLong totalBFSTimeNanos = new AtomicLong();
    private final AtomicLong totalAllocatedBytes = new AtomicLong();
    private final AtomicInteger maxFlowDistanceUsed = new AtomicInteger();
    private final AtomicInteger maxBFSDepth = new AtomicInteger();

    private final ConcurrentHashMap<Integer, AtomicLong> ticksByDistance = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicLong> timeByDistance = new ConcurrentHashMap<>();

    private final AtomicLong fastPathHits = new AtomicLong();
    private final AtomicLong slowPathHits = new AtomicLong();
    private final AtomicLong equilibriumSkips = new AtomicLong();
    private final AtomicLong spatialGridHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong fluidTickSchedulesAccepted = new AtomicLong();
    private final AtomicLong fluidTickSchedulesCoalesced = new AtomicLong();

    private final double[] msptSamples = new double[MSPT_WINDOW];
    private final Object msptLock = new Object();

    private volatile int tickCounter;
    private volatile int msptSampleIndex;
    private volatile int msptSampleCount;
    private volatile double msptSampleTotal;
    private volatile double lastServerMspt;

    private final com.sun.management.ThreadMXBean allocationBean;
    private final boolean allocationTrackingEnabled;

    private FluidPerformanceMonitor() {
        com.sun.management.ThreadMXBean bean = null;
        boolean enabled = false;
        java.lang.management.ThreadMXBean rawBean = ManagementFactory.getThreadMXBean();
        if (rawBean instanceof com.sun.management.ThreadMXBean threadBean
                && threadBean.isThreadAllocatedMemorySupported()) {
            bean = threadBean;
            try {
                if (!threadBean.isThreadAllocatedMemoryEnabled()) {
                    threadBean.setThreadAllocatedMemoryEnabled(true);
                }
                enabled = threadBean.isThreadAllocatedMemoryEnabled();
            } catch (UnsupportedOperationException | SecurityException ignored) {
                enabled = false;
            }
        }
        this.allocationBean = bean;
        this.allocationTrackingEnabled = enabled;
        registerMBean();
    }

    public static FluidPerformanceMonitor getInstance() {
        return INSTANCE;
    }

    public long currentThreadAllocatedBytes() {
        if (!allocationTrackingEnabled) {
            return ALLOCATION_UNAVAILABLE;
        }
        return allocationBean.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    public void recordFluidTick(long nanos, int flowDistance) {
        recordFluidTick(nanos, flowDistance, 0L);
    }

    public void recordFluidTick(long nanos, int flowDistance, long allocatedBytes) {
        totalFluidTicks.incrementAndGet();
        totalTickTimeNanos.addAndGet(Math.max(0L, nanos));
        if (allocatedBytes > 0L) {
            totalAllocatedBytes.addAndGet(allocatedBytes);
        }

        updateMax(maxFlowDistanceUsed, flowDistance);
        ticksByDistance.computeIfAbsent(flowDistance, k -> new AtomicLong()).incrementAndGet();
        timeByDistance.computeIfAbsent(flowDistance, k -> new AtomicLong()).addAndGet(Math.max(0L, nanos));
    }

    public void recordBFS(long nanos, int nodesVisited, int maxDepth) {
        totalBFSOperations.incrementAndGet();
        totalBFSNodes.addAndGet(Math.max(0, nodesVisited));
        totalBFSTimeNanos.addAndGet(Math.max(0L, nanos));
        updateMax(maxBFSDepth, maxDepth);
    }

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

    public void recordFluidTickScheduleAccepted() {
        fluidTickSchedulesAccepted.incrementAndGet();
    }

    public void recordFluidTickScheduleCoalesced() {
        fluidTickSchedulesCoalesced.incrementAndGet();
    }

    public void onServerTick(MinecraftServer server, boolean enabled, int interval) {
        if (server == null || !enabled) {
            return;
        }
        recordServerMspt(getCurrentMspt(server));
        tick(true, interval);
    }

    public void tick(boolean enabled, int interval) {
        if (!enabled) {
            return;
        }
        tickCounter++;
        if (tickCounter >= Math.max(20, interval)) {
            logPerformanceData();
            tickCounter = 0;
        }
    }

    public void tick(int serverTick, boolean enabled, int interval) {
        tick(enabled, interval);
    }

    public void logPerformanceData() {
        String report = getPerformanceReport();
        for (String line : report.split("\\R")) {
            FlowingFluids.info(line);
        }
    }

    @Override
    public void reset() {
        totalFluidTicks.set(0L);
        totalBFSOperations.set(0L);
        totalBFSNodes.set(0L);
        totalTickTimeNanos.set(0L);
        totalBFSTimeNanos.set(0L);
        totalAllocatedBytes.set(0L);
        maxFlowDistanceUsed.set(0);
        maxBFSDepth.set(0);
        ticksByDistance.clear();
        timeByDistance.clear();
        fastPathHits.set(0L);
        slowPathHits.set(0L);
        equilibriumSkips.set(0L);
        spatialGridHits.set(0L);
        cacheMisses.set(0L);
        fluidTickSchedulesAccepted.set(0L);
        fluidTickSchedulesCoalesced.set(0L);
        tickCounter = 0;
        synchronized (msptLock) {
            msptSampleIndex = 0;
            msptSampleCount = 0;
            msptSampleTotal = 0.0;
            lastServerMspt = 0.0;
            for (int i = 0; i < msptSamples.length; i++) {
                msptSamples[i] = 0.0;
            }
        }
        FlowingFluids.info("Performance monitor counters reset.");
    }

    @Override
    public String getPerformanceReport() {
        long ticks = totalFluidTicks.get();
        StringBuilder report = new StringBuilder();
        report.append("=== Flowing Fluids performance monitor ===\n");
        report.append(String.format("Server MSPT: last %.2f, 20 tick avg %.2f%n",
                getLastServerMspt(), getAverageServerMspt20()));
        report.append(String.format("Fluid ticks: %,d%n", ticks));
        report.append(String.format("Fluid tick schedules: accepted %,d, coalesced %,d%n",
                getFluidTickSchedulesAccepted(), getFluidTickSchedulesCoalesced()));
        if (ticks == 0L) {
            report.append("No fluid tick samples recorded yet.");
            return report.toString();
        }

        report.append(String.format("Fluid tick time: %.2f ms total, %.3f us avg%n",
                totalTickTimeNanos.get() / 1_000_000.0, getAverageTickMicros()));
        long allocated = totalAllocatedBytes.get();
        if (allocationTrackingEnabled && allocated > 0L) {
            report.append(String.format("Thread allocation: %.2f MiB total, %.1f bytes/fluid tick avg%n",
                    allocated / 1048576.0, allocated / (double) ticks));
        } else {
            report.append("Thread allocation: unavailable or no samples yet\n");
        }

        long bfsOps = totalBFSOperations.get();
        if (bfsOps > 0L) {
            report.append(String.format("BFS: %,d ops, %,d nodes, %.3f us/op avg, max depth %d%n",
                    bfsOps, totalBFSNodes.get(), getAverageBFSMicros(), getMaxBFSDepth()));
        }

        report.append(String.format("Flow distance max: %d%n", getMaxFlowDistanceUsed()));
        report.append(String.format("Path counters: fast %,d, slow %,d, equilibrium skips %,d%n",
                getFastPathHits(), getSlowPathHits(), getEquilibriumSkips()));
        report.append(String.format("Lookup counters: section/cache hits %,d, misses %,d%n",
                getSpatialGridHits(), getCacheMisses()));
        appendDistanceBreakdown(report, ticks);
        report.append("==========================================");
        return report.toString();
    }

    @Override
    public long getTotalFluidTicks() {
        return totalFluidTicks.get();
    }

    @Override
    public long getTotalBFSOperations() {
        return totalBFSOperations.get();
    }

    @Override
    public long getTotalBFSNodes() {
        return totalBFSNodes.get();
    }

    @Override
    public long getTotalAllocatedBytes() {
        return totalAllocatedBytes.get();
    }

    @Override
    public long getAverageTickTimeNanos() {
        long ticks = totalFluidTicks.get();
        return ticks > 0L ? totalTickTimeNanos.get() / ticks : 0L;
    }

    @Override
    public long getAverageBFSNanos() {
        long ops = totalBFSOperations.get();
        return ops > 0L ? totalBFSTimeNanos.get() / ops : 0L;
    }

    @Override
    public double getAverageTickMicros() {
        return getAverageTickTimeNanos() / 1000.0;
    }

    @Override
    public double getAverageBFSMicros() {
        return getAverageBFSNanos() / 1000.0;
    }

    @Override
    public double getLastServerMspt() {
        return lastServerMspt;
    }

    @Override
    public double getAverageServerMspt20() {
        synchronized (msptLock) {
            return msptSampleCount > 0 ? msptSampleTotal / msptSampleCount : 0.0;
        }
    }

    @Override
    public int getMaxFlowDistanceUsed() {
        return maxFlowDistanceUsed.get();
    }

    @Override
    public int getMaxBFSDepth() {
        return maxBFSDepth.get();
    }

    @Override
    public long getFastPathHits() {
        return fastPathHits.get();
    }

    @Override
    public long getSlowPathHits() {
        return slowPathHits.get();
    }

    @Override
    public long getEquilibriumSkips() {
        return equilibriumSkips.get();
    }

    @Override
    public long getSpatialGridHits() {
        return spatialGridHits.get();
    }

    @Override
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    @Override
    public long getFluidTickSchedulesAccepted() {
        return fluidTickSchedulesAccepted.get();
    }

    @Override
    public long getFluidTickSchedulesCoalesced() {
        return fluidTickSchedulesCoalesced.get();
    }

    private void appendDistanceBreakdown(StringBuilder report, long ticks) {
        if (ticksByDistance.isEmpty()) {
            return;
        }
        report.append("By flow distance:\n");
        ticksByDistance.keySet().stream()
                .sorted()
                .forEach(distance -> {
                    long distTicks = ticksByDistance.get(distance).get();
                    long distTime = timeByDistance.get(distance).get();
                    report.append(String.format("  %d: %,d ticks (%.2f%%), %.3f us avg%n",
                            distance,
                            distTicks,
                            distTicks * 100.0 / ticks,
                            distTicks > 0L ? distTime / (double) distTicks / 1000.0 : 0.0));
                });
    }

    private void recordServerMspt(double mspt) {
        synchronized (msptLock) {
            lastServerMspt = mspt;
            if (msptSampleCount < msptSamples.length) {
                msptSamples[msptSampleIndex] = mspt;
                msptSampleTotal += mspt;
                msptSampleCount++;
            } else {
                msptSampleTotal -= msptSamples[msptSampleIndex];
                msptSamples[msptSampleIndex] = mspt;
                msptSampleTotal += mspt;
            }
            msptSampleIndex = (msptSampleIndex + 1) % msptSamples.length;
        }
    }

    private double getCurrentMspt(MinecraftServer server) {
#if MC > MC_21
        return server.getCurrentSmoothedTickTime();
#else
        return server.getAverageTickTime();
#endif
    }

    private void registerMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("traben.flowing_fluids:type=FluidPerformanceMonitor");
            if (!server.isRegistered(name)) {
                server.registerMBean(this, name);
            }
        } catch (InstanceAlreadyExistsException | MBeanRegistrationException | MalformedObjectNameException
                 | NotCompliantMBeanException | SecurityException e) {
            FlowingFluids.warn("Could not register FluidPerformanceMonitor MBean.", e);
        }
    }

    private static void updateMax(AtomicInteger value, int candidate) {
        int current;
        do {
            current = value.get();
            if (candidate <= current) {
                return;
            }
        } while (!value.compareAndSet(current, candidate));
    }
}
