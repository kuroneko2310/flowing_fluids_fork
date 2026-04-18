package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

final class LavaSpringActivity {
    private LavaSpringActivity() {
    }

    static int additionalEmission(ServerLevel level, BlockPos springPos, BlockPos tipPos, Direction growthDirection, SpringStrength strength) {
        int pressureScore = pressureScore(level, springPos, tipPos, growthDirection);
        int strengthBias = Math.max(0, strength.emissionAmount() - 1);
        return switch (pressureScore) {
            case 0 -> 0;
            case 1 -> 1 + (strengthBias >= 2 ? 1 : 0);
            case 2 -> 2 + (strengthBias >= 2 ? 1 : 0);
            case 3 -> 4 + (strengthBias >= 1 ? 1 : 0);
            case 4 -> 6 + (strengthBias >= 2 ? 1 : 0);
            default -> 8 + Math.min(3, strengthBias);
        };
    }

    static int burstEmission(ServerLevel level, BlockPos springPos, BlockPos tipPos, Direction growthDirection,
                             SpringStrength strength, RandomSource random) {
        int pressureScore = pressureScore(level, springPos, tipPos, growthDirection);
        float burstChance = switch (pressureScore) {
            case 0 -> 0.05F;
            case 1 -> 0.10F;
            case 2 -> 0.18F;
            case 3 -> 0.28F;
            case 4 -> 0.40F;
            default -> 0.52F;
        };
        if (random.nextFloat() >= burstChance) {
            return 0;
        }

        int strengthBias = Math.max(0, strength.emissionAmount() - 1);
        return switch (pressureScore) {
            case 0, 1 -> 3 + Math.min(1, strengthBias);
            case 2 -> 5 + Math.min(2, strengthBias);
            case 3 -> 7 + Math.min(2, strengthBias);
            case 4 -> 9 + Math.min(3, strengthBias);
            default -> 12 + Math.min(4, strengthBias);
        };
    }

    static void applyHazards(ServerLevel level, BlockPos springPos, BlockPos tipPos, Direction growthDirection, SpringStrength strength, RandomSource random) {
        int pressureScore = pressureScore(level, springPos, tipPos, growthDirection);
        if (pressureScore < 2) {
            return;
        }

        float ignitionChance = switch (pressureScore) {
            case 2 -> 0.10F;
            case 3 -> 0.18F;
            case 4 -> 0.27F;
            default -> 0.36F;
        };

        if (random.nextFloat() < ignitionChance) {
            tryIgniteNearby(level, tipPos, growthDirection, random);
        }

        if (pressureScore >= 4 && growthDirection == Direction.UP && random.nextFloat() < 0.14F) {
            BlockPos splashPos = tipPos.above();
            BlockState splashState = level.getBlockState(splashPos);
            if (splashState.isAir() || splashState.canBeReplaced(Fluids.LAVA)) {
                int splashEmission = SpringColumnPulseController.scaleEmission((net.minecraft.world.level.material.FlowingFluid) Fluids.LAVA, Math.max(1, strength.emissionAmount()));
                SpringFluidEmitter.emitFluid(level, splashPos, splashEmission, (net.minecraft.world.level.material.FlowingFluid) Fluids.LAVA, Direction.UP);
                level.scheduleTick(splashPos, Fluids.LAVA, Fluids.LAVA.getTickDelay(level));
            }
        }
    }

    private static int pressureScore(ServerLevel level, BlockPos springPos, BlockPos tipPos, Direction growthDirection) {
        int score = 0;
        int nearbyLava = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    cursor.set(tipPos.getX() + dx, tipPos.getY() + dy, tipPos.getZ() + dz);
                    if (level.getFluidState(cursor).getType().isSame(Fluids.LAVA)) {
                        nearbyLava++;
                    }
                }
            }
        }

        if (nearbyLava >= 10) {
            score++;
        }
        if (nearbyLava >= 22) {
            score++;
        }
        if (nearbyLava >= 38) {
            score++;
        }
        if (level.getFluidState(tipPos).getType().isSame(Fluids.LAVA)) {
            score++;
        }
        if (level.getFluidState(tipPos.relative(growthDirection)).getType().isSame(Fluids.LAVA)) {
            score++;
        }
        if (level.getFluidState(springPos).getType().isSame(Fluids.LAVA)
                || level.getFluidState(springPos.relative(growthDirection.getOpposite())).getType().isSame(Fluids.LAVA)) {
            score++;
        }

        return Mth.clamp(score, 0, 5);
    }

    private static void tryIgniteNearby(ServerLevel level, BlockPos tipPos, Direction growthDirection, RandomSource random) {
        BlockPos[] candidates = new BlockPos[] {
                tipPos.relative(growthDirection),
                tipPos.above(),
                tipPos.north(),
                tipPos.south(),
                tipPos.east(),
                tipPos.west()
        };

        int start = random.nextInt(candidates.length);
        for (int i = 0; i < candidates.length; i++) {
            BlockPos firePos = candidates[(start + i) % candidates.length];
            if (!level.getBlockState(firePos).isAir()) {
                continue;
            }

            BlockPos supportPos = firePos.below();
            BlockState supportState = level.getBlockState(supportPos);
            if (!supportState.isFaceSturdy(level, supportPos, Direction.UP)) {
                continue;
            }

            BlockState fireState = BaseFireBlock.getState(level, firePos);
            if (fireState.canSurvive(level, firePos)) {
                level.setBlock(firePos, fireState, 3);
                return;
            }
        }
    }
}
