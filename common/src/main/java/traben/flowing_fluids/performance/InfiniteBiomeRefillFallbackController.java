package traben.flowing_fluids.performance;

import net.minecraft.server.MinecraftServer;
import traben.flowing_fluids.FlowingFluids;

/**
 * Dedicated load-shed controller for infinite-biome refill fallback.
 * Keeps a short-lived overload state so broad ocean surfaces can recover
 * using a simpler vanilla-like source rule without depending on auto tick delay.
 */
public final class InfiniteBiomeRefillFallbackController {
    private static final int SAMPLE_INTERVAL_TICKS = 40;
    private static final int HOLD_TICKS = 200;
    private static final float ENTER_LOAD_RATIO = 0.95f;
    private static final float EXIT_LOAD_RATIO = 0.84f;
    private static final float IMMEDIATE_ENTER_LOAD_RATIO = 1.08f;
    private static final int REQUIRED_OVERLOAD_SCORE = 2;

    private static boolean sourceFallbackActive = false;
    private static int overloadScore = 0;
    private static int lastSampleTick = Integer.MIN_VALUE;
    private static int holdUntilTick = Integer.MIN_VALUE;
    private static float lastMeasuredMspt = 0.0f;
    private static float lastTargetMspt = 0.0f;

    private InfiniteBiomeRefillFallbackController() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || !FlowingFluids.config.enableMod) {
            resetRuntime();
            return;
        }

        int currentTick = server.getTickCount();
        if (lastSampleTick != Integer.MIN_VALUE && currentTick - lastSampleTick < SAMPLE_INTERVAL_TICKS) {
            if (sourceFallbackActive && currentTick >= holdUntilTick && overloadScore <= 0) {
                sourceFallbackActive = false;
            }
            return;
        }

        lastSampleTick = currentTick;
        lastMeasuredMspt = ff$getCurrentMspt(server);
        lastTargetMspt = Math.max(1.0f, ff$getTargetMspt(server));
        float loadRatio = lastMeasuredMspt / lastTargetMspt;

        overloadScore = ff$getNextOverloadScore(overloadScore, loadRatio);

        if (ff$shouldActivateImmediately(loadRatio) || overloadScore >= REQUIRED_OVERLOAD_SCORE) {
            sourceFallbackActive = true;
            holdUntilTick = currentTick + HOLD_TICKS;
            return;
        }

        if (sourceFallbackActive) {
            if (loadRatio >= ENTER_LOAD_RATIO) {
                holdUntilTick = currentTick + HOLD_TICKS;
            } else if (currentTick >= holdUntilTick && overloadScore <= 0) {
                sourceFallbackActive = false;
            }
        }
    }

    public static boolean shouldUseSourceRefillFallback() {
        return sourceFallbackActive;
    }

    public static void reloadConfig() {
        resetRuntime();
    }

    public static void resetRuntime() {
        sourceFallbackActive = false;
        overloadScore = 0;
        lastSampleTick = Integer.MIN_VALUE;
        holdUntilTick = Integer.MIN_VALUE;
        lastMeasuredMspt = 0.0f;
        lastTargetMspt = 0.0f;
    }

    static int ff$getNextOverloadScore(int currentScore, float loadRatio) {
        if (loadRatio >= ENTER_LOAD_RATIO) {
            return Math.min(REQUIRED_OVERLOAD_SCORE, currentScore + 1);
        }
        if (loadRatio <= EXIT_LOAD_RATIO) {
            return Math.max(0, currentScore - 1);
        }
        return currentScore;
    }

    static boolean ff$shouldActivateImmediately(float loadRatio) {
        return loadRatio >= IMMEDIATE_ENTER_LOAD_RATIO;
    }

    private static float ff$getTargetMspt(MinecraftServer server) {
#if MC > MC_21
        return server.tickRateManager().millisecondsPerTick();
#else
        return 50.0f;
#endif
    }

    private static float ff$getCurrentMspt(MinecraftServer server) {
#if MC > MC_21
        return server.getCurrentSmoothedTickTime();
#else
        return server.getAverageTickTime();
#endif
    }
}
