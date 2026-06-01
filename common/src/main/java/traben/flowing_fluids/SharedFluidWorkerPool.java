package traben.flowing_fluids;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

public final class SharedFluidWorkerPool {
    private static final int PARALLELISM = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    private static volatile ForkJoinPool pool = createPool();

    private SharedFluidWorkerPool() {
    }

    public static ForkJoinPool getPool() {
        ForkJoinPool current = pool;
        if (isUsable(current)) {
            return current;
        }
        synchronized (SharedFluidWorkerPool.class) {
            current = pool;
            if (!isUsable(current)) {
                pool = createPool();
                FlowingFluids.warn("Recreated shared fluid worker pool after shutdown.");
            }
            return pool;
        }
    }

    public static int getParallelism() {
        return getPool().getParallelism();
    }

    public static void shutdown() {
        ForkJoinPool current;
        synchronized (SharedFluidWorkerPool.class) {
            current = pool;
            pool = null;
        }
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            current.shutdownNow();
        }
    }

    private static ForkJoinPool createPool() {
        return new ForkJoinPool(
                PARALLELISM,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                (thread, error) -> FlowingFluids.error("Uncaught exception in shared fluid worker "
                        + thread.getName() + ".", error),
                true
        );
    }

    private static boolean isUsable(ForkJoinPool current) {
        return current != null
                && !current.isShutdown()
                && !current.isTerminated()
                && !current.isTerminating();
    }
}
