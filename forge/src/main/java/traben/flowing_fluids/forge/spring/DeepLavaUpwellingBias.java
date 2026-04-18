package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.material.Fluids;

final class DeepLavaUpwellingBias {
    private DeepLavaUpwellingBias() {
    }

    static int score(WorldGenLevel level, BlockPos pos, int depthTier, int lavaRichness) {
        int score = 0;
        if (depthTier >= 3) {
            score++;
        }
        if (depthTier >= 4) {
            score++;
        }
        if (lavaRichness >= 1) {
            score++;
        }
        if (lavaRichness >= 2) {
            score++;
        }
        if (lavaRichness >= 3) {
            score++;
        }
        if (isLavaCell(level, pos)) {
            score += 2;
        }
        if (isLavaCell(level, pos.above())) {
            score++;
        }
        return Mth.clamp(score, 0, 7);
    }

    static float floorAttemptMultiplier(int score) {
        return switch (Mth.clamp(score, 0, 7)) {
            case 1 -> 1.08F;
            case 2 -> 1.18F;
            case 3 -> 1.30F;
            case 4 -> 1.42F;
            case 5 -> 1.56F;
            case 6 -> 1.70F;
            case 7 -> 1.86F;
            default -> 1.0F;
        };
    }

    static int floorPlacementBonus(int score) {
        return score >= 5 ? 1 : 0;
    }

    static float floorRejectReduction(int score) {
        return switch (Mth.clamp(score, 0, 7)) {
            case 1 -> 0.03F;
            case 2 -> 0.06F;
            case 3 -> 0.10F;
            case 4 -> 0.15F;
            case 5 -> 0.20F;
            case 6 -> 0.26F;
            case 7 -> 0.32F;
            default -> 0.0F;
        };
    }

    static int floorStrengthBonus(int score) {
        return switch (Mth.clamp(score, 0, 7)) {
            case 0, 1 -> 0;
            case 2, 3, 4 -> 1;
            default -> 2;
        };
    }

    static float ceilingAttemptMultiplier(int score) {
        return switch (Mth.clamp(score, 0, 7)) {
            case 1 -> 0.96F;
            case 2 -> 0.90F;
            case 3 -> 0.82F;
            case 4 -> 0.74F;
            case 5 -> 0.66F;
            case 6 -> 0.58F;
            case 7 -> 0.52F;
            default -> 1.0F;
        };
    }

    static int ceilingPlacementPenalty(int score) {
        return score >= 4 ? 1 : 0;
    }

    static float ceilingRejectPenalty(int score) {
        return switch (Mth.clamp(score, 0, 7)) {
            case 1 -> 0.03F;
            case 2 -> 0.06F;
            case 3 -> 0.10F;
            case 4 -> 0.14F;
            case 5 -> 0.19F;
            case 6 -> 0.24F;
            case 7 -> 0.30F;
            default -> 0.0F;
        };
    }

    static boolean isLavaCell(WorldGenLevel level, BlockPos pos) {
        return level.getFluidState(pos).getType().isSame(Fluids.LAVA);
    }
}
