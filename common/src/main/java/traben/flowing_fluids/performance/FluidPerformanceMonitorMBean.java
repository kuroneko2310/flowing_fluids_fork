package traben.flowing_fluids.performance;

public interface FluidPerformanceMonitorMBean {
    long getTotalFluidTicks();

    long getTotalBFSOperations();

    long getTotalBFSNodes();

    long getTotalAllocatedBytes();

    long getAverageTickTimeNanos();

    long getAverageBFSNanos();

    double getAverageTickMicros();

    double getAverageBFSMicros();

    double getLastServerMspt();

    double getAverageServerMspt20();

    int getMaxFlowDistanceUsed();

    int getMaxBFSDepth();

    long getFastPathHits();

    long getSlowPathHits();

    long getEquilibriumSkips();

    long getSpatialGridHits();

    long getCacheMisses();

    long getFluidTickSchedulesAccepted();

    long getFluidTickSchedulesCoalesced();

    int getLastPendingChunkInitializations();

    int getLastPendingFrontierRebuilds();

    int getLastQueuedActiveWakeTicks();

    int getLastQueuedDistantStableTicks();

    int getLastBufferedFluidChanges();

    String getPerformanceReport();

    void reset();
}
