package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

final class WaterSpringActivity {
    private WaterSpringActivity() {
    }

    static int additionalEmission(ServerLevel level, BlockPos springPos, BlockPos tipPos, Direction growthDirection, SpringStrength strength) {
        int seepScore = seepScore(level, springPos, tipPos, growthDirection);
        return switch (seepScore) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 3;
            case 4 -> 4 + (strength.emissionAmount() >= 3 ? 1 : 0);
            default -> 5 + (strength.emissionAmount() >= 3 ? 1 : 0);
        };
    }

    static int burstEmission(ServerLevel level, BlockPos springPos, BlockPos tipPos, Direction growthDirection,
                             SpringStrength strength, RandomSource random) {
        int seepScore = seepScore(level, springPos, tipPos, growthDirection);
        float burstChance = switch (seepScore) {
            case 0 -> 0.04F;
            case 1 -> 0.08F;
            case 2 -> 0.14F;
            case 3 -> 0.22F;
            case 4 -> 0.30F;
            default -> 0.38F;
        };
        if (random.nextFloat() >= burstChance) {
            return 0;
        }

        return switch (seepScore) {
            case 0, 1 -> 2;
            case 2 -> 3;
            case 3 -> 4;
            case 4 -> 5 + Math.min(1, strength.emissionAmount() - 1);
            default -> 6 + Math.min(2, strength.emissionAmount() - 1);
        };
    }

    static void applySurfaceSplash(ServerLevel level, BlockPos springPos, BlockPos tipPos, Direction growthDirection,
                                   SpringStrength strength, RandomSource random) {
        if (growthDirection != Direction.UP) {
            return;
        }

        int seepScore = seepScore(level, springPos, tipPos, growthDirection);
        if (seepScore < 4) {
            return;
        }

        float splashChance = seepScore >= 5 ? 0.20F : 0.11F;
        if (random.nextFloat() >= splashChance) {
            return;
        }

        BlockPos splashPos = tipPos.above();
        BlockState splashState = level.getBlockState(splashPos);
        if (splashState.isAir() || splashState.canBeReplaced(Fluids.WATER)) {
            int splashEmission = SpringColumnPulseController.scaleEmission((net.minecraft.world.level.material.FlowingFluid) Fluids.WATER, Math.max(1, strength.emissionAmount()));
            SpringFluidEmitter.emitFluid(level, splashPos, splashEmission, (net.minecraft.world.level.material.FlowingFluid) Fluids.WATER, Direction.UP);
            level.scheduleTick(splashPos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
    }

    private static int seepScore(ServerLevel level, BlockPos springPos, BlockPos tipPos, Direction growthDirection) {
        WaterSpringHydrology.Sample sample = WaterSpringHydrology.sample(level, springPos, tipPos, growthDirection);
        if (!sample.tipWet() && !sample.springBacked()) {
            return 0;
        }
        // A broad stable pool should not feed back into the spring and create even more water.
        if (sample.isCalmPool()) {
            return 0;
        }
        int score = 0;
        if (sample.springBacked()) {
            score++;
        }
        if (sample.tipWet()) {
            score++;
        }
        if (sample.frontierCount() >= 1) {
            score++;
        }
        if (sample.frontierCount() >= 3) {
            score++;
        }
        if (sample.connectedWater() >= 4 && sample.frontierCount() >= 1) {
            score++;
        }
        if (sample.forwardWet() && sample.frontierCount() >= 1) {
            score++;
        }
        return Mth.clamp(score, 0, 5);
    }
}
