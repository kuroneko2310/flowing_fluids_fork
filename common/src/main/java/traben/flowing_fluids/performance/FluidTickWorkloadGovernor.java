package traben.flowing_fluids.performance;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import traben.flowing_fluids.util.DimensionKey;

import java.util.concurrent.ConcurrentHashMap;

public final class FluidTickWorkloadGovernor {
    private static final int HEALTHY_BUDGET = 4096;
    private static final int BUSY_BUDGET = 2048;
    private static final int OVERLOADED_BUDGET = 768;
    private static final int CRITICAL_BUDGET = 192;
    private static final int EXTREME_BUDGET = 64;
    private static final int MIN_DEFER_DELAY = 2;
    private static final int MAX_DEFER_DELAY = 10;
    private static final long DEFER_SALT = 0x464c5549445f544bL;

    private static final ConcurrentHashMap<DimensionKey, TickBudget> BUDGETS = new ConcurrentHashMap<>();

    private FluidTickWorkloadGovernor() {
    }

    public static boolean shouldDefer(Level level, BlockPos pos, Fluid fluid, int flowDistance) {
        if (!(level instanceof ServerLevel) || pos == null || fluid == null) {
            return false;
        }

        double mspt = getMspt(level);
        if (shouldSpatiallyDefer(pos, fluid, level.getGameTime(), mspt, flowDistance)) {
            return true;
        }

        TickBudget budget = BUDGETS.computeIfAbsent(DimensionKey.of(level), ignored -> new TickBudget());
        long gameTime = level.getGameTime();
        if (budget.tick != gameTime) {
            budget.tick = gameTime;
            budget.used = 0;
            budget.limit = computeBudgetForMspt(mspt, flowDistance);
        }

        if (budget.used < budget.limit) {
            budget.used++;
            return false;
        }
        return true;
    }

    public static int getDeferredDelay(Level level, BlockPos pos, Fluid fluid, int flowDistance) {
        long tick = level == null ? 0L : level.getGameTime();
        long posKey = pos == null ? 0L : pos.asLong();
        int baseDelay = getBaseDeferredDelay(level, flowDistance);
        long mixed = mix(posKey, fluid, tick);
        int jitter = (int) Long.remainderUnsigned(mixed, Math.max(1, baseDelay));
        return Mth.clamp(baseDelay + jitter, MIN_DEFER_DELAY, MAX_DEFER_DELAY);
    }

    public static int adjustRequestedDelay(Level level, BlockPos pos, Fluid fluid, int requestedDelay, int trackedFluidTicks) {
        int delay = Math.max(1, requestedDelay);
        if (!(level instanceof ServerLevel) || pos == null || fluid == null) {
            return delay;
        }

        int pressureDelay = getQueuePressureDelay(level, trackedFluidTicks);
        if (pressureDelay <= delay) {
            return delay;
        }

        long mixed = mix(pos.asLong(), fluid, level.getGameTime());
        int jitter = (int) Long.remainderUnsigned(mixed, Math.max(1, pressureDelay));
        return Mth.clamp(Math.max(delay, pressureDelay + jitter), 1, MAX_DEFER_DELAY);
    }

    public static void clearDimension(Level level) {
        if (level != null) {
            BUDGETS.remove(DimensionKey.of(level));
        }
    }

    public static void clearAll() {
        BUDGETS.clear();
    }

    static int computeBudgetForMspt(double mspt, int flowDistance) {
        int distancePenalty = Math.max(0, flowDistance - 2) * 384;
        int budget;
        if (mspt >= 250.0) {
            budget = EXTREME_BUDGET;
        } else if (mspt >= 120.0) {
            budget = CRITICAL_BUDGET;
        } else if (mspt >= 70.0) {
            budget = OVERLOADED_BUDGET;
        } else if (mspt >= 45.0) {
            budget = BUSY_BUDGET;
        } else {
            budget = HEALTHY_BUDGET;
        }
        return Math.max(32, budget - distancePenalty);
    }

    static boolean shouldSpatiallyDefer(BlockPos pos, Fluid fluid, long gameTime, double mspt, int flowDistance) {
        int stride = computeSpatialStrideForMspt(mspt, flowDistance);
        if (stride <= 1) {
            return false;
        }
        long phaseTick = Math.floorDiv(gameTime, Math.max(1, stride));
        long mixed = mix(pos.asLong(), fluid, phaseTick);
        return Long.remainderUnsigned(mixed, stride) != 0L;
    }

    static int computeSpatialStrideForMspt(double mspt, int flowDistance) {
        int distancePressure = Math.max(0, flowDistance - 3);
        if (mspt >= 250.0) {
            return Math.min(8, 4 + distancePressure);
        }
        if (mspt >= 120.0) {
            return Math.min(6, 3 + distancePressure);
        }
        if (mspt >= 70.0) {
            return Math.min(4, 2 + distancePressure);
        }
        return 1;
    }

    static int computeQueuePressureDelay(double mspt, int trackedFluidTicks) {
        if (mspt >= 250.0 || trackedFluidTicks >= 524_288) {
            return 6;
        }
        if (mspt >= 120.0 || trackedFluidTicks >= 262_144) {
            return 4;
        }
        if (mspt >= 70.0 || trackedFluidTicks >= 131_072) {
            return 2;
        }
        return 1;
    }

    private static int getBaseDeferredDelay(Level level, int flowDistance) {
        double mspt = getMspt(level);
        if (mspt >= 120.0) {
            return 6 + Math.max(0, flowDistance - 2);
        }
        if (mspt >= 70.0) {
            return 4 + Math.max(0, flowDistance - 2);
        }
        return 2 + Math.max(0, flowDistance - 3);
    }

    private static int getQueuePressureDelay(Level level, int trackedFluidTicks) {
        return computeQueuePressureDelay(getMspt(level), trackedFluidTicks);
    }

    private static double getMspt(Level level) {
        double mspt = FluidPerformanceMonitor.getInstance().getAverageServerMspt20();
        if (mspt <= 0.0) {
            mspt = FluidPerformanceMonitor.getInstance().getLastServerMspt();
        }
        if (mspt <= 0.0 && level instanceof ServerLevel serverLevel) {
            mspt = serverLevel.getServer().getAverageTickTime();
        }
        return mspt;
    }

    private static long mix(long posKey, Fluid fluid, long tick) {
        long fluidHash = System.identityHashCode(fluid);
        long z = posKey ^ Long.rotateLeft(tick * 0x9E3779B97F4A7C15L, 13) ^ fluidHash * DEFER_SALT;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static final class TickBudget {
        long tick = Long.MIN_VALUE;
        int used;
        int limit = HEALTHY_BUDGET;
    }
}
