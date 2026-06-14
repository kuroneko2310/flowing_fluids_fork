package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.forge.nether.NetherLavaEventSystem;

final class SpringColumnPulseController {
    private static final long PHASE_SALT = 0x9E3779B97F4A7C15L;
    private static final int MAX_COLUMN_HEIGHT = 5;
    private static final int MAX_WATER_PRESSURE_COLUMN_HEIGHT = 32;
    private static final int MAX_LAVA_PRESSURE_COLUMN_HEIGHT = 48;
    private static final int NETHER_LAVA_MAX_COLUMN_HEIGHT = 56;

    private SpringColumnPulseController() {
    }

    static int synchronizeColumn(ServerLevel level, BlockPos springPos, Direction growthDirection,
                                 SpringStrength strength, FlowingFluid fluid) {
        int targetHeight = resolveTargetHeight(level, level.getGameTime(), springPos, growthDirection, strength, fluid);
        int maxHeight = resolveMaxColumnHeight(level, springPos, growthDirection, strength, fluid);
        int realizedHeight = 0;

        for (int offset = 1; offset <= maxHeight; offset++) {
            BlockPos currentPos = springPos.relative(growthDirection, offset);
            BlockState currentState = level.getBlockState(currentPos);
            FluidState currentFluid = FFFluidUtils.getEffectiveFluidState(level, currentPos, currentState);
            boolean canOccupy = SpringFluidEmitter.canEmitInto(level, currentState, currentFluid, fluid);

            if (offset <= targetHeight && canOccupy) {
                FFFluidUtils.setFluidStateAtPosToNewAmount(level, currentPos, fluid, 8);
                AdaptiveTickScheduler.scheduleFluidTick(level, currentPos, fluid, fluid.getTickDelay(level));
                realizedHeight = offset;
                continue;
            }

            if (!fluid.isSame(Fluids.WATER)
                    && currentFluid.getType().isSame(fluid)
                    && shouldRetractColumnCell(level, currentPos, growthDirection, fluid)) {
                FFFluidUtils.setFluidStateAtPosToNewAmount(level, currentPos, fluid, 0);
            }

            if (offset <= targetHeight && !canOccupy) {
                break;
            }
        }

        if (realizedHeight > 0) {
            BlockPos tipPos = springPos.relative(growthDirection, realizedHeight);
            int emitted = strength.emissionAmount();
            if (fluid.isSame(Fluids.LAVA)) {
                emitted += LavaSpringActivity.additionalEmission(level, springPos, tipPos, growthDirection, strength);
                emitted += LavaSpringActivity.burstEmission(level, springPos, tipPos, growthDirection, strength, level.random);
                emitted += NetherLavaEventSystem.getSpringEmissionBonus(level, springPos, growthDirection, fluid);
            }
            emitted = scaleEmission(fluid, emitted);

            int remainder = SpringFluidEmitter.emitFluid(level, tipPos, emitted, fluid, growthDirection);
            if (remainder < emitted) {
                AdaptiveTickScheduler.scheduleFluidTick(level, tipPos, fluid, fluid.getTickDelay(level));
                if (fluid.isSame(Fluids.LAVA)) {
                    LavaSpringActivity.applyHazards(level, springPos, tipPos, growthDirection, strength, level.random);
                }
            }
        }

        return realizedHeight;
    }

    static int nextPulseDelay(ServerLevel level, BlockPos springPos, SpringStrength strength, FlowingFluid fluid) {
        long seed = baseSeed(springPos, strength, fluid);
        return resolvePulseInterval(seed, strength, fluid);
    }

    private static int resolveTargetHeight(ServerLevel level, long gameTime, BlockPos springPos, Direction growthDirection,
                                           SpringStrength strength, FlowingFluid fluid) {
        int pressureReachHeight = resolvePressureReachHeight(level, springPos, growthDirection, fluid);
        if (pressureReachHeight > 0) {
            return pressureReachHeight;
        }

        long seed = baseSeed(springPos, strength, fluid) ^ ((long) growthDirection.ordinal() * 0xBF58476D1CE4E5B9L);
        int interval = resolvePulseInterval(seed, strength, fluid);
        long shiftedTime = gameTime + Math.floorMod(seed >>> 16, interval);
        long phase = Math.floorDiv(shiftedTime, interval);
        long phaseSeed = mix(seed ^ (phase * PHASE_SALT));
        if (isNetherUpwardLava(level, growthDirection, fluid)) {
            int minHeight = resolveNetherTargetMinHeight(strength);
            int maxHeight = Math.min(NETHER_LAVA_MAX_COLUMN_HEIGHT,
                    resolveNetherTargetMaxHeight(strength) + NetherLavaEventSystem.getSpringHeightBonus(level, springPos, growthDirection, fluid) * 2);
            int range = Math.max(1, maxHeight - minHeight + 1);
            int baseHeight = minHeight + Math.floorMod((int) phaseSeed, range);
            int swing = resolveNetherRandomSwing(strength, phaseSeed, range);
            return Mth.clamp(baseHeight + swing, minHeight, maxHeight);
        }

        int heightBonus = netherUpwardLavaHeightBonus(level, springPos, growthDirection, fluid);
        int minHeight = strength.pulseMinHeight() + Math.min(1, heightBonus);
        int maxHeight = strength.pulseMaxHeight() + heightBonus;
        int range = Math.max(1, maxHeight - minHeight + 1);
        return minHeight + Math.floorMod((int) phaseSeed, range);
    }

    private static int resolveMaxColumnHeight(ServerLevel level, BlockPos springPos, Direction growthDirection, SpringStrength strength, FlowingFluid fluid) {
        int pressureReachHeight = resolvePressureReachHeight(level, springPos, growthDirection, fluid);
        if (pressureReachHeight > 0) {
            return pressureReachHeight;
        }

        if (isNetherUpwardLava(level, growthDirection, fluid)) {
            return Math.min(NETHER_LAVA_MAX_COLUMN_HEIGHT,
                    resolveNetherTargetMaxHeight(strength) + NetherLavaEventSystem.getSpringHeightBonus(level, springPos, growthDirection, fluid) * 2);
        }
        int heightBonus = netherUpwardLavaHeightBonus(level, springPos, growthDirection, fluid);
        return Math.min(MAX_COLUMN_HEIGHT + heightBonus, strength.pulseMaxHeight() + heightBonus);
    }

    private static int resolvePressureReachHeight(ServerLevel level, BlockPos springPos, Direction growthDirection, FlowingFluid fluid) {
        if (growthDirection != Direction.UP) {
            return 0;
        }

        int maxPressureHeight = resolveMaxPressureColumnHeight(level, fluid);

        int surfaceReachHeight = resolveSurfaceReachHeight(level, springPos, fluid, maxPressureHeight);
        if (surfaceReachHeight > 0) {
            return surfaceReachHeight;
        }

        return resolveCappedReachHeight(level, springPos, fluid, maxPressureHeight);
    }

    private static int resolveSurfaceReachHeight(ServerLevel level, BlockPos springPos, FlowingFluid fluid, int maxPressureHeight) {
        if (!fluid.isSame(Fluids.WATER)) {
            return 0;
        }

        int mouthY = level.getHeight(Heightmap.Types.WORLD_SURFACE, springPos.getX(), springPos.getZ());
        if (mouthY <= springPos.getY() || mouthY >= level.getMaxBuildHeight()) {
            return 0;
        }

        int minBuildHeight = level.getMinBuildHeight();
        int targetHeight = SpringDimensionContext.heightAboveBedrock(mouthY, minBuildHeight)
                - SpringDimensionContext.heightAboveBedrock(springPos.getY(), minBuildHeight);
        if (targetHeight <= 0 || targetHeight > maxPressureHeight) {
            return 0;
        }

        BlockPos mouthPos = new BlockPos(springPos.getX(), mouthY, springPos.getZ());
        if (!level.canSeeSky(mouthPos)) {
            return 0;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int offset = 1; offset <= targetHeight; offset++) {
            cursor.set(springPos.getX(), springPos.getY() + offset, springPos.getZ());
            BlockState state = level.getBlockState(cursor);
            FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, cursor, state);
            if (!SpringFluidEmitter.canEmitInto(level, state, fluidState, fluid)
                    || !isEnclosedPressureShaftCell(level, cursor, fluid)) {
                return offset == 1 ? 0 : offset - 1;
            }
        }

        return targetHeight;
    }

    private static int resolveCappedReachHeight(ServerLevel level, BlockPos springPos, FlowingFluid fluid, int maxPressureHeight) {
        if (!traben.flowing_fluids.FlowingFluids.config.enableCappedSpringPressureHead) {
            return 0;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int highestReachable = 0;

        for (int offset = 1; offset <= maxPressureHeight && springPos.getY() + offset < level.getMaxBuildHeight(); offset++) {
            cursor.set(springPos.getX(), springPos.getY() + offset, springPos.getZ());
            BlockState state = level.getBlockState(cursor);
            FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, cursor, state);
            if (!SpringFluidEmitter.canEmitInto(level, state, fluidState, fluid)) {
                // Keep a capped shaft pressurized right up to the stopper block so springs
                // can build a head and spill sideways instead of falling back to a tiny pulse.
                return highestReachable;
            }
            if (!isEnclosedPressureShaftCell(level, cursor, fluid)) {
                return highestReachable;
            }
            highestReachable = offset;
        }

        return highestReachable;
    }

    private static int resolveMaxPressureColumnHeight(ServerLevel level, FlowingFluid fluid) {
        if (fluid.isSame(Fluids.LAVA)) {
            return isNetherUpwardLava(level, Direction.UP, fluid)
                    ? NETHER_LAVA_MAX_COLUMN_HEIGHT
                    : MAX_LAVA_PRESSURE_COLUMN_HEIGHT;
        }
        return MAX_WATER_PRESSURE_COLUMN_HEIGHT;
    }

    private static boolean isEnclosedPressureShaftCell(ServerLevel level, BlockPos pos, FlowingFluid fluid) {
        int enclosedSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = pos.relative(direction);
            BlockState neighborState = level.getBlockState(neighborPos);
            FluidState neighborFluid = FFFluidUtils.getEffectiveFluidState(level, neighborPos, neighborState);
            if (neighborFluid.getType().isSame(fluid) && neighborFluid.getAmount() > 0) {
                continue;
            }
            if (!SpringFluidEmitter.canEmitInto(level, neighborState, neighborFluid, fluid)) {
                enclosedSides++;
            }
        }
        return enclosedSides >= 3;
    }

    private static int resolvePulseInterval(long seed, SpringStrength strength, FlowingFluid fluid) {
        if (fluid.isSame(Fluids.WATER)) {
            int interval = 16 + strength.minimumDelay() * 2 + Math.floorMod((int) (seed >>> 8), 18);
            return Mth.clamp(Math.round(interval * traben.flowing_fluids.FlowingFluids.config.waterSpringPulseIntervalMultiplier), 8, 224);
        }
        if (fluid.isSame(Fluids.LAVA)) {
            int interval = 20 + strength.minimumDelay() * 2 + Math.floorMod((int) (seed >>> 8), 24);
            return Mth.clamp(Math.round(interval * traben.flowing_fluids.FlowingFluids.config.lavaSpringPulseIntervalMultiplier), 8, 256);
        }
        return Mth.clamp(24 + strength.minimumDelay() * 3 + Math.floorMod((int) (seed >>> 8), 24), 24, 160);
    }

    static int scaleEmission(FlowingFluid fluid, int emitted) {
        if (emitted <= 0) {
            return 0;
        }

        float multiplier = 1.0F;
        if (fluid.isSame(Fluids.WATER)) {
            multiplier = traben.flowing_fluids.FlowingFluids.config.waterSpringEmissionMultiplier;
        } else if (fluid.isSame(Fluids.LAVA)) {
            multiplier = traben.flowing_fluids.FlowingFluids.config.lavaSpringEmissionMultiplier;
        }
        return Mth.clamp(Math.round(emitted * multiplier), 1, 64);
    }

    private static long baseSeed(BlockPos springPos, SpringStrength strength, FlowingFluid fluid) {
        long seed = springPos.asLong();
        seed ^= ((long) strength.ordinal() + 1L) * 0x94D049BB133111EBL;
        seed ^= (long) System.identityHashCode(fluid) * 0x369DEA0F31A53F85L;
        return mix(seed);
    }

    private static long mix(long value) {
        value ^= (value >>> 33);
        value *= 0xff51afd7ed558ccdl;
        value ^= (value >>> 33);
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= (value >>> 33);
        return value;
    }

    private static boolean shouldRetractColumnCell(ServerLevel level, BlockPos pos, Direction growthDirection, FlowingFluid fluid) {
        int lateralMatches = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = pos.relative(direction);
            FluidState neighborFluid = FFFluidUtils.getEffectiveFluidState(level, neighborPos, level.getBlockState(neighborPos));
            if (neighborFluid.getType().isSame(fluid) && neighborFluid.getAmount() > 0) {
                lateralMatches++;
                if (lateralMatches >= 2) {
                    // Broad pools should keep their own body shape even when a spring pulse shrinks.
                    return false;
                }
            }
        }

        if (lateralMatches == 0) {
            return true;
        }

        BlockPos forwardPos = pos.relative(growthDirection);
        FluidState forwardFluid = FFFluidUtils.getEffectiveFluidState(level, forwardPos, level.getBlockState(forwardPos));
        if (forwardFluid.getType().isSame(fluid) && forwardFluid.getAmount() > 0) {
            return false;
        }

        BlockPos backwardPos = pos.relative(growthDirection.getOpposite());
        FluidState backwardFluid = FFFluidUtils.getEffectiveFluidState(level, backwardPos, level.getBlockState(backwardPos));
        return !backwardFluid.getType().isSame(fluid) || backwardFluid.getAmount() <= 0;
    }

    private static int netherUpwardLavaHeightBonus(ServerLevel level, BlockPos springPos, Direction growthDirection, FlowingFluid fluid) {
        // Keep the extra spectacle narrow so only upward lava vents in ultra-warm dimensions
        // gain more vertical reach, leaving water and downward drips at their existing feel.
        if (isNetherUpwardLava(level, growthDirection, fluid)) {
            return 2 + NetherLavaEventSystem.getSpringHeightBonus(level, springPos, growthDirection, fluid);
        }
        return 0;
    }

    private static boolean isNetherUpwardLava(ServerLevel level, Direction growthDirection, FlowingFluid fluid) {
        return growthDirection == Direction.UP
                && fluid.isSame(Fluids.LAVA)
                && SpringDimensionContext.isUltraWarm(level);
    }

    private static int resolveNetherTargetMinHeight(SpringStrength strength) {
        return switch (strength) {
            case SLIGHT -> 16;
            case NORMAL -> 24;
            case LARGE -> 34;
            case HEAVY -> 44;
        };
    }

    private static int resolveNetherTargetMaxHeight(SpringStrength strength) {
        return switch (strength) {
            case SLIGHT -> 28;
            case NORMAL -> 38;
            case LARGE -> 48;
            case HEAVY -> 56;
        };
    }

    private static int resolveNetherRandomSwing(SpringStrength strength, long phaseSeed, int range) {
        int swingWindow = switch (strength) {
            case SLIGHT -> 3;
            case NORMAL -> 5;
            case LARGE -> 7;
            case HEAVY -> 9;
        };
        swingWindow = Math.max(1, Math.min(swingWindow, Math.max(1, range / 3)));
        int width = swingWindow * 2 + 1;
        return Math.floorMod((int) (phaseSeed >>> 21), width) - swingWindow;
    }
}
