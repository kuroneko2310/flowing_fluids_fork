package traben.flowing_fluids.performance;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import traben.flowing_fluids.FlowingFluids;

public final class FluidAutoTickDelay {
    private static int waterExtraDelay = 0;
    private static int lavaExtraDelay = 0;
    private static int lastAdjustmentTick = Integer.MIN_VALUE;
    private static float lastMeasuredMspt = 0.0f;
    private static float lastTargetMspt = 0.0f;

    private FluidAutoTickDelay() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        if (!FlowingFluids.config.enableMod || !FlowingFluids.config.enableAutoTickDelay) {
            resetRuntime();
            return;
        }

        int currentTick = server.getTickCount();
        if (lastAdjustmentTick != Integer.MIN_VALUE
                && currentTick - lastAdjustmentTick < FlowingFluids.config.autoTickDelayUpdateRateTicks) {
            return;
        }

        lastAdjustmentTick = currentTick;
        lastMeasuredMspt = ff$getCurrentMspt(server);
        lastTargetMspt = Math.max(1.0f, ff$getTargetMspt(server));

        int previousWaterDelay = waterExtraDelay;
        int previousLavaDelay = lavaExtraDelay;
        float loadRatio = lastMeasuredMspt / lastTargetMspt;

        int desiredWaterDelay = ff$getDesiredExtraDelay(loadRatio, FlowingFluids.config.autoTickDelayWaterMaxExtraDelay, 6.0f);
        int desiredLavaDelay = ff$getDesiredExtraDelay(loadRatio, FlowingFluids.config.autoTickDelayLavaMaxExtraDelay, 4.0f);

        waterExtraDelay = ff$approachDelay(previousWaterDelay, desiredWaterDelay);
        lavaExtraDelay = ff$approachDelay(previousLavaDelay, desiredLavaDelay);

        if (waterExtraDelay != previousWaterDelay || lavaExtraDelay != previousLavaDelay) {
            if (FlowingFluids.config.autoTickDelayLogAdjustments) {
                FlowingFluids.info(String.format(
                        "Auto tick delay adjusted: water +%d, lava +%d (MSPT %.2f / %.2f)",
                        waterExtraDelay,
                        lavaExtraDelay,
                        lastMeasuredMspt,
                        lastTargetMspt));
            }
        }
    }

    public static int getAdjustedWaterTickDelay(int baseDelay) {
        return Mth.clamp(baseDelay + waterExtraDelay, 1, 255);
    }

    public static int getAdjustedLavaTickDelay(int baseDelay) {
        return Mth.clamp(baseDelay + lavaExtraDelay, 1, 255);
    }

    public static void reloadConfig() {
        if (!FlowingFluids.config.enableMod || !FlowingFluids.config.enableAutoTickDelay) {
            resetRuntime();
            return;
        }

        waterExtraDelay = Math.min(waterExtraDelay, Math.max(0, FlowingFluids.config.autoTickDelayWaterMaxExtraDelay));
        lavaExtraDelay = Math.min(lavaExtraDelay, Math.max(0, FlowingFluids.config.autoTickDelayLavaMaxExtraDelay));
        lastAdjustmentTick = Integer.MIN_VALUE;
    }

    public static void resetRuntime() {
        waterExtraDelay = 0;
        lavaExtraDelay = 0;
        lastAdjustmentTick = Integer.MIN_VALUE;
        lastMeasuredMspt = 0.0f;
        lastTargetMspt = 0.0f;
    }

    public static String describeStatus() {
        return "Auto tick delay status"
                + "\nEnabled: " + FlowingFluids.config.enableAutoTickDelay
                + "\nWater extra delay: +" + waterExtraDelay + " (base " + FlowingFluids.config.waterTickDelay + ")"
                + "\nLava extra delay: +" + lavaExtraDelay + " (base " + FlowingFluids.config.lavaTickDelay
                + ", nether base " + FlowingFluids.config.lavaNetherTickDelay + ")"
                + "\nUpdate rate: " + FlowingFluids.config.autoTickDelayUpdateRateTicks + " ticks"
                + "\nTarget MSPT multiplier: " + FlowingFluids.config.autoTickDelayTargetMsptMultiplier
                + "\nLast MSPT: " + String.format("%.2f", lastMeasuredMspt)
                + " / " + String.format("%.2f", lastTargetMspt);
    }

    private static int ff$getDesiredExtraDelay(float loadRatio, int maxExtraDelay, float pressureScale) {
        if (maxExtraDelay <= 0) {
            return 0;
        }
        if (loadRatio <= 0.92f) {
            return 0;
        }
        float pressure = Math.max(0.0f, loadRatio - 0.92f) * pressureScale;
        return Mth.clamp(Math.round(pressure), 0, maxExtraDelay);
    }

    private static int ff$approachDelay(int current, int desired) {
        if (desired > current) {
            return current + 1;
        }
        if (desired < current) {
            return current - 1;
        }
        return current;
    }

    private static float ff$getTargetMspt(MinecraftServer server) {
#if MC > MC_21
        return server.tickRateManager().millisecondsPerTick() * FlowingFluids.config.autoTickDelayTargetMsptMultiplier;
#else
        return 50.0f * FlowingFluids.config.autoTickDelayTargetMsptMultiplier;
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
