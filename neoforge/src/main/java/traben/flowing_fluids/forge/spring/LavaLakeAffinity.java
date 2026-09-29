package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.material.Fluids;

final class LavaLakeAffinity {
    private LavaLakeAffinity() {
    }

    static int sampleRichness(WorldGenLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int lavaCells = 0;

        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (level.getFluidState(cursor).getType().isSame(Fluids.LAVA)) {
                        lavaCells++;
                    }
                }
            }
        }

        if (lavaCells >= 42) {
            return 3;
        }
        if (lavaCells >= 18) {
            return 2;
        }
        if (lavaCells >= 6) {
            return 1;
        }
        return 0;
    }

    static float attemptMultiplier(int richness) {
        return switch (Mth.clamp(richness, 0, 3)) {
            case 1 -> 1.20F;
            case 2 -> 1.45F;
            case 3 -> 1.75F;
            default -> 1.0F;
        };
    }

    static float rejectReduction(int richness) {
        return switch (Mth.clamp(richness, 0, 3)) {
            case 1 -> 0.10F;
            case 2 -> 0.22F;
            case 3 -> 0.34F;
            default -> 0.0F;
        };
    }

    static int placementBonus(int richness) {
        return switch (Mth.clamp(richness, 0, 3)) {
            case 2 -> 1;
            case 3 -> 2;
            default -> 0;
        };
    }
}
