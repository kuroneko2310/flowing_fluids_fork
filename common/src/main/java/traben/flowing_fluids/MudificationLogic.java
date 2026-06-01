package traben.flowing_fluids;

import traben.flowing_fluids.optimization.WaterFlowProfile;

public final class MudificationLogic {
    private MudificationLogic() {
    }

    public static float getExposureGain(WaterFlowProfile.FlowSpeed flowSpeed, float pressureDrive,
                                        boolean immediateDownwardOutlet, float mudificationStrength) {
        int flowBonus = switch (flowSpeed) {
            case STILL, SLOW, NORMAL -> 0;
            case FAST -> 1;
            case TORRENT -> 2;
        };
        int pressureBonus = pressureDrive > 0.6f || immediateDownwardOutlet ? 1 : 0;
        float baseGain = 1.0f + flowBonus + pressureBonus;
        return Math.max(0.0f, baseGain * Math.max(0.0f, mudificationStrength));
    }

    public static float resolveExposureAfterTouch(float existingExposure, long lastTouchedTick,
                                                  long currentTick, float delta, long ttlTicks) {
        if (delta <= 0.0f) {
            return Math.max(0.0f, existingExposure);
        }
        if (lastTouchedTick < 0L || currentTick - lastTouchedTick > ttlTicks) {
            return delta;
        }
        return existingExposure + delta;
    }

    public static int getMudThreshold(boolean softSurface, boolean bankSide) {
        if (bankSide) {
            return 14;
        }
        return softSurface ? 6 : 10;
    }

    public static boolean shouldIgnorePlayerPlaced(boolean playerPlaced, boolean farmland) {
        return playerPlaced && !farmland;
    }

    public static boolean shouldApplyMudificationHook(boolean changed, boolean isWater, int movedAmount) {
        return changed && isWater && movedAmount > 0;
    }
}
