package traben.flowing_fluids.forge.spring;

import net.minecraft.util.Mth;

final class DeepLavaDepthProfile {
    private DeepLavaDepthProfile() {
    }

    static int activityTier(int y, int minBuildHeight, boolean inNether) {
        int depthFromBottom = SpringDimensionContext.heightAboveBedrock(y, minBuildHeight);
        int tier = 0;

        // Preserve the old overworld depth bands as offsets from the bedrock floor so
        // deeper modded dimensions keep the same "closer to the crust" feel.
        if (!inNether && SpringDimensionContext.isAtOrBelowLegacyAbsoluteY(y, minBuildHeight, -16, false)) {
            tier++;
        }
        if (!inNether && SpringDimensionContext.isAtOrBelowLegacyAbsoluteY(y, minBuildHeight, -32, false)) {
            tier++;
        }
        if (!inNether && SpringDimensionContext.isAtOrBelowLegacyAbsoluteY(y, minBuildHeight, -48, false)) {
            tier++;
        }
        if (depthFromBottom <= 24) {
            tier++;
        }
        if (depthFromBottom <= 12) {
            tier++;
        }

        return Mth.clamp(tier, 0, 5);
    }

    static float attemptMultiplier(int tier) {
        return switch (Mth.clamp(tier, 0, 5)) {
            case 1 -> 1.08F;
            case 2 -> 1.16F;
            case 3 -> 1.28F;
            case 4 -> 1.42F;
            case 5 -> 1.58F;
            default -> 1.0F;
        };
    }

    static float rejectReduction(int tier) {
        return switch (Mth.clamp(tier, 0, 5)) {
            case 1 -> 0.04F;
            case 2 -> 0.08F;
            case 3 -> 0.13F;
            case 4 -> 0.18F;
            case 5 -> 0.24F;
            default -> 0.0F;
        };
    }

    static int placementBonus(int tier) {
        return tier >= 4 ? 1 : 0;
    }

    static int strengthBonus(int tier) {
        return switch (Mth.clamp(tier, 0, 5)) {
            case 0, 1 -> 0;
            case 2, 3 -> 1;
            default -> 2;
        };
    }
}
