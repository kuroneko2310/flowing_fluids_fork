package traben.flowing_fluids.mixin;


import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.WaterFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FluidRegressionLogic;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.drying.DryingEventSystem;
import traben.flowing_fluids.performance.FluidAutoTickDelay;
import traben.flowing_fluids.performance.InfiniteBiomeRefillFallbackController;


@Mixin(WaterFluid.class)
public abstract class MixinWaterFluid extends FlowingFluid {
    private static final int ff$EVAPORATION_ASSIST_RADIUS = 5;
    private static final Direction[] ff$RAIN_WAKE_DIRECTIONS = new Direction[]{
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN
    };

    @Shadow
    public abstract int getDropOff(final LevelReader levelReader);

    @Shadow
    public abstract boolean isSame(final Fluid fluid);

    @Override
    protected void randomTick(final #if MC > MC_21 ServerLevel #else Level #endif level,
                              final BlockPos blockPos, final FluidState fluidState, final RandomSource randomSource) {
        super.randomTick(level, blockPos, fluidState, randomSource);

        if (level.isClientSide()
                || fluidState.isEmpty()
                || !FlowingFluids.config.enableMod
                || !FlowingFluids.config.isFluidAllowed(fluidState)) return;

        boolean outsideFullSimulationRange = FlowingFluids.config.dontTickAtLocation(blockPos, level);
        if (FlowingFluids.config.dontMaintainFluidVisualsAtLocation(blockPos, level)) return;


        boolean isWithinInfBiomeHeights = FFFluidUtils.isWithinInfiniteBiomeRefillBand(level, blockPos);
        boolean isInfBiome = FFFluidUtils.matchInfiniteBiomes(level.getBiome(blockPos));

        int amount = fluidState.getAmount();
        boolean hasInfiniteBiomeAmbientAccess = isInfBiome
                && FFFluidUtils.hasInfiniteBiomeAmbientAccess(level, blockPos, this, amount);
        // Twilight Forest portals expose a level-1 water state for rendering/logic.
        // Treating that as real water causes bogus upkeep and evaporation ticks.
        if (amount == 1
                && "twilightforest:twilight_portal".equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(blockPos).getBlock()).toString())) {
            return;
        }
        if (outsideFullSimulationRange) {
            // Outside the full simulation radius we still allow rain-facing upkeep so
            // nearby loaded chunks do not look frozen, but we skip slower ambient rules.
            ff$trySpawnSurfaceWater(level, blockPos, randomSource);
            ff$tryRainFill(level, blockPos, level.random.nextFloat(), isWithinInfBiomeHeights, isInfBiome);
            return;
        }

        ff$trySpawnSurfaceWater(level, blockPos, randomSource);
        if (ff$tryEvaporateNether(level, blockPos, amount, randomSource.nextFloat())) {
            if (FlowingFluids.config.printRandomTicks)
                FlowingFluids.info("--- Water was flash-evaporated via ultra warm dimension at " + blockPos + ". Chance: " + FlowingFluids.config.evaporationNetherChance);
            return;
        }
        if (ff$trySeaLevelOverflowEvaporate(level, blockPos, amount, randomSource.nextFloat())) {
            if (FlowingFluids.config.printRandomTicks)
                FlowingFluids.info("--- Sea-level overflow water evaporated at " + blockPos + ". Chance: "
                        + DryingEventSystem.getSeaLevelOverflowEvaporationChance(level, blockPos));
            return;
        }
        if (amount < 8) {
            if (ff$tryBiomeFillOrDrain(level, blockPos, amount, level.random.nextFloat(),
                    isWithinInfBiomeHeights, isInfBiome, hasInfiniteBiomeAmbientAccess)) {
                if (FlowingFluids.config.printRandomTicks)
                    FlowingFluids.info("--- Water was filled by biome at "+blockPos+". Chance: "+ FlowingFluids.config.oceanRiverSwampRefillChance);
                return;
            }
            if (ff$tryRainFill(level, blockPos, level.random.nextFloat(), isWithinInfBiomeHeights, isInfBiome)) {
                if (FlowingFluids.config.printRandomTicks)
                    FlowingFluids.info("--- Water was filled by rain at "+blockPos+". Chance: "+ FlowingFluids.config.rainRefillChance);
                return;
            }
            if (ff$tryHeatSourceEvaporate(level, blockPos, amount, level.random.nextFloat())) {
                if (FlowingFluids.config.printRandomTicks)
                    FlowingFluids.info("--- Water was evaporated by nearby heat at " + blockPos + ". Chance: " + FlowingFluids.config.hotBlockEvaporationChance);
                return;
            }
            if (ff$tryEvaporate(level, blockPos, amount, level.random.nextFloat())) {
                if (FlowingFluids.config.printRandomTicks)
                    FlowingFluids.info("--- Water was evaporated - non Nether at "+blockPos+". Chance: "+ FlowingFluids.config.evaporationChanceV2);
            }
        } else {
            if (ff$tryRainFill(level, blockPos, level.random.nextFloat(), isWithinInfBiomeHeights, isInfBiome)) {
                if (FlowingFluids.config.printRandomTicks)
                    FlowingFluids.info("--- Water was filled by rain at "+blockPos+". Chance: "+ FlowingFluids.config.rainRefillChance);
            }
        }
    }




    @Unique
    private boolean ff$tryRainFill(final Level level, final BlockPos blockPos, float chance,
                                   boolean isWithinInfBiomeHeights, boolean isInfBiome) {
    if (!FlowingFluids.config.enableRainSystem) return false;
    //this evaporation limit is critical!!!! otherwise the water fills endlessly
    int currentAmount = FFFluidUtils.getEffectiveFluidState(level, blockPos).getAmount();
    BlockPos rainCheckPos = blockPos.above();
    boolean blockedByInfiniteBiome = isInfBiome && isWithinInfBiomeHeights
            && currentAmount >= FlowingFluids.config.infiniteBiomeRainFillMaxLevel;
    float rainChance = Mth.clamp(FlowingFluids.config.rainRefillChance * DryingEventSystem.getRainRefillMultiplier(level), 0.0f, 1.0f);
    if (chance < rainChance
            && FluidRegressionLogic.shouldAllowRainDrivenWaterPlacement(
                    level.isRaining(),
                    level.isRainingAt(rainCheckPos),
                    level.getBiome(rainCheckPos).value().coldEnoughToSnow(rainCheckPos))
            && !blockedByInfiniteBiome
            && !level.getBiome(blockPos).is(BiomeTags.HAS_VILLAGE_DESERT)
    ) {
        int amount = level.isThundering() ? 2 : 1;
        var result = FFFluidUtils.placeConnectedFluidAmountAndPlaceAction(
                level, blockPos, amount, this, 40, FlowingFluids.config.rainFillsWaterHigherV2, false);
        if (result.first() != amount) {
            result.second().run();
            AdaptiveTickScheduler.markRainBorn(level, blockPos);
            ff$wakeRainFluid(level, blockPos);
            ff$maybeApplyRainJump(level, blockPos);
            return true;
        }
    }
    return false;
    }

    @Unique
    private boolean ff$tryBiomeFillOrDrain(final Level level, final BlockPos blockPos, int amount, float chance,
                                           boolean isWithinInfBiomeHeights, boolean isInfBiome,
                                           boolean hasInfiniteBiomeAmbientAccess) {
        boolean heavyLoadSourceFallback = InfiniteBiomeRefillFallbackController.shouldUseSourceRefillFallback();
        boolean riverBiome = FFFluidUtils.isRiverBiome(level.getBiome(blockPos));
        if (riverBiome && AdaptiveTickScheduler.isFlowActiveNow(level, blockPos)) {
            return false;
        }
        if (riverBiome
                && DryingEventSystem.shouldRiverDroughtDrain(level, blockPos, amount)
                && chance < DryingEventSystem.getRiverDroughtDrainChance(level)) {
            return ff$applyLocalDrainAndMaybeRestoreMud(level, blockPos, 1, false);
        }

        if (FFFluidUtils.seaLevel(level) == blockPos.getY()) {
            if (chance < FlowingFluids.config.infiniteWaterBiomeDrainSurfaceChance) {
                // Sea-level drain is only meant for settled thin surface tiles, not fresh inlet fronts.
                if (isInfBiome && hasInfiniteBiomeAmbientAccess) {
                    int drainAmount = FFFluidUtils.getInfiniteBiomeSurfaceDrainAmount(level, blockPos, this, amount);
                    if (drainAmount > 0) {
                        boolean changed = FFFluidUtils.applyInfiniteBiomeSurfaceDrain(level, blockPos, this, amount, drainAmount);
                        if (changed) {
                            ff$tryRestoreMudBelowAfterDrying(level, blockPos, FFFluidUtils.getEffectiveFluidState(level, blockPos));
                        }
                        return changed;
                    }
                }
            }
        } else if (isWithinInfBiomeHeights) {
            if (isInfBiome && hasInfiniteBiomeAmbientAccess) {
                // Keep the old passive biome refill alive for calm supported water; the faster
                // flowing-refill path below is only for recently disturbed interiors.
                float passiveRefillChance = FFFluidUtils.scaleInfiniteBiomePassiveRefillChance(
                        FlowingFluids.config.oceanRiverSwampRefillChance,
                        riverBiome,
                        DryingEventSystem.getRiverDroughtRefillMultiplier(level)
                );
                if (chance < passiveRefillChance) {
                    int refillAmount = FFFluidUtils.getInfiniteBiomeRefillAmount(level, blockPos, this, amount, false);
                    if (refillAmount > 0) {
                        if (FFFluidUtils.tryApplyVanillaInfiniteSourceRefill(level, blockPos, this, amount, heavyLoadSourceFallback)) {
                            return true;
                        }
                        return FFFluidUtils.applyConnectedFluidAmountDelta(level, blockPos, this, refillAmount, 12, false, true);
                    }
                }
            }
            if (!FFFluidUtils.shouldAttemptInfiniteBiomeFlowingRefill(level, blockPos, this, 8, amount)) {
                return false;
            }
            if (isInfBiome && hasInfiniteBiomeAmbientAccess) {
                int refillAmount = FFFluidUtils.getInfiniteBiomeFlowingRefillAmount(level, blockPos, this, amount);
                if (refillAmount > 0) {
                    if (FFFluidUtils.tryApplyVanillaInfiniteSourceRefill(level, blockPos, this, amount, heavyLoadSourceFallback)) {
                        return true;
                    }
                    return FFFluidUtils.applyConnectedFluidAmountDelta(level, blockPos, this, refillAmount, 12, false, true);
                }
            }
        }

        return false;
    }

    @Unique
    private boolean ff$tryEvaporate(final Level level, final BlockPos blockPos, int amount, float chance) {
        if (!DryingEventSystem.shouldRunEvaporationTick(level, blockPos, FlowingFluids.config.evaporationIntervalTicks)) {
            return false;
        }
        int evaporationMaxLevel = DryingEventSystem.getSurfaceEvaporationMaxLevel();
        float evaporationChance = DryingEventSystem.getSurfaceEvaporationChance(level);
        if (chance < evaporationChance) {
            if (FFFluidUtils.isProtectedInfiniteBiomeWater(level, blockPos, this, amount)) return false;
            if (AdaptiveTickScheduler.isFlowActiveNow(level, blockPos) && amount > evaporationMaxLevel) return false;
            if (FlowingFluids.config.evaporationDaytimeOnly && !level.isDay()) return false;
            if (DryingEventSystem.isShadeProtected(level, blockPos)) return false;
            if (FlowingFluids.config.evaporationRequiresSky && !DryingEventSystem.hasEvaporationSkyAccess(level, blockPos)) return false;
            if (level.isRainingAt(blockPos.above())) return false;
            FluidState aboveFluid = FFFluidUtils.getEffectiveFluidState(level, blockPos.above());
            boolean hasSameFluidAbove = aboveFluid != null && aboveFluid.getType().isSame(this);
            if (!FluidRegressionLogic.isSurfaceEvaporationCandidate(hasSameFluidAbove)) return false;
            BlockState sourceState = level.getBlockState(blockPos);
            boolean stalledThinSurface = FluidRegressionLogic.shouldIgnoreHorizontalEscapeForThinSurfaceEvaporation(
                    amount,
                    evaporationMaxLevel,
                    hasSameFluidAbove
            );
            boolean supportedThinPuddle = FluidRegressionLogic.shouldEvaporateSupportedThinSurfacePuddle(
                    amount,
                    evaporationMaxLevel,
                    hasSameFluidAbove,
                    FFFluidUtils.isSmallSupportedThinSurfaceCluster(level, blockPos, this, 3, evaporationMaxLevel)
            );
            if (!stalledThinSurface
                    && FFFluidUtils.canFluidFlowToNeighbourFromPos(level, blockPos, sourceState, this, amount)) {
                return false;
            }
            // Let exposed 1-level puddles on solid ground dry too; they are already
            // stable thin clusters and otherwise linger because "below is empty"
            // only catches hanging water.
            if (amount <= evaporationMaxLevel
                    && (FFFluidUtils.getEffectiveFluidState(level, blockPos.below()).isEmpty() || supportedThinPuddle)) {
                return ff$applyLocalDrainAndMaybeRestoreMud(level, blockPos, amount, true);
            }
        }
        return false;
    }

    @Unique
    private boolean ff$tryHeatSourceEvaporate(final Level level, final BlockPos blockPos, int amount, float chance) {
        if (!DryingEventSystem.hasNearbyHeatSource(level, blockPos)) return false;
        if (!DryingEventSystem.shouldRunEvaporationTick(level, blockPos, FlowingFluids.config.hotBlockEvaporationIntervalTicks)) return false;
        int evaporationMaxLevel = DryingEventSystem.getSurfaceEvaporationMaxLevel();
        if (FFFluidUtils.isProtectedInfiniteBiomeWater(level, blockPos, this, amount)) return false;
        if (AdaptiveTickScheduler.isFlowActiveNow(level, blockPos) && amount > evaporationMaxLevel) return false;
        BlockPos abovePos = blockPos.above();
        FluidState aboveFluid = FFFluidUtils.getEffectiveFluidState(level, abovePos);
        boolean hasSameFluidAbove = aboveFluid != null && aboveFluid.getType().isSame(this);
        if (!FluidRegressionLogic.shouldHeatSourceEvaporateSurfaceWater(
                hasSameFluidAbove,
                DryingEventSystem.hasEvaporationSkyAccess(level, blockPos),
                level.isRainingAt(abovePos),
                DryingEventSystem.isShadeProtected(level, blockPos))) {
            return false;
        }
        BlockState sourceState = level.getBlockState(blockPos);
        boolean stalledThinSurface = FluidRegressionLogic.shouldIgnoreHorizontalEscapeForThinSurfaceEvaporation(
                amount,
                evaporationMaxLevel,
                hasSameFluidAbove
        );
        if (!stalledThinSurface
                && FFFluidUtils.canFluidFlowToNeighbourFromPos(level, blockPos, sourceState, this, amount)) {
            return false;
        }
        float heatChance = DryingEventSystem.getHotBlockEvaporationChance(level);
        if (chance >= heatChance) return false;
        int drainAmount = Mth.clamp(FlowingFluids.config.hotBlockEvaporationDrainAmount, 1, amount);
        return ff$applyLocalDrainAndMaybeRestoreMud(level, blockPos, drainAmount, true);
    }

    @Unique
    private boolean ff$trySeaLevelOverflowEvaporate(final Level level, final BlockPos blockPos, int amount, float chance) {
        if (!DryingEventSystem.shouldEvaporateSeaLevelOverflow(level, blockPos, this, amount)) {
            return false;
        }
        if (FlowingFluids.config.seaLevelOverflowEvaporationInstant) {
            return ff$applyLocalDrainAndMaybeRestoreMud(level, blockPos, amount, false);
        }
        float overflowChance = DryingEventSystem.getSeaLevelOverflowEvaporationChance(level, blockPos);
        if (chance >= overflowChance) {
            return false;
        }
        BlockState sourceState = level.getBlockState(blockPos);
        if (FFFluidUtils.canFluidFlowToNeighbourFromPos(level, blockPos, sourceState, this, amount)) {
            return false;
        }
        return ff$applyLocalDrainAndMaybeRestoreMud(level, blockPos, amount, false);
    }

@Unique
private void ff$maybeApplyRainJump(Level level, BlockPos blockPos) {
    if (FlowingFluids.config.rainLevelJumpChance <= 0) return;
    if (level.random.nextFloat() >= FlowingFluids.config.rainLevelJumpChance) return;

    int extra = Math.max(1, FlowingFluids.config.rainSurfaceSpawnLevel);
    var jump = FFFluidUtils.placeConnectedFluidAmountAndPlaceAction(
            level, blockPos, extra, this, 40,
            FlowingFluids.config.rainFillsWaterHigherV2, false
    );

    if (jump.second() != null && jump.first() != extra) {
        jump.second().run();
        AdaptiveTickScheduler.markRainBorn(level, blockPos);
        ff$wakeRainFluid(level, blockPos);
    }
}

@Unique
private void ff$trySpawnSurfaceWater(Level level, BlockPos origin, RandomSource randomSource) {
    if (!FlowingFluids.config.enableRainSystem) return;
    BlockPos originRainCheckPos = origin.above();
    if (FlowingFluids.config.rainSurfaceSpawnChance <= 0) return;
    if (!FluidRegressionLogic.shouldAllowRainDrivenWaterPlacement(
            level.isRaining(),
            level.isRainingAt(originRainCheckPos),
            level.getBiome(originRainCheckPos).value().coldEnoughToSnow(originRainCheckPos))) return;
    if (randomSource.nextFloat() >= FlowingFluids.config.rainSurfaceSpawnChance) return;

    Direction[] shuffled = FFFluidUtils.getCardinalsShuffle(randomSource);
    for (Direction direction : shuffled) {
        BlockPos candidate = origin.relative(direction);
        BlockPos candidateRainCheckPos = candidate.above();
        if (!FluidRegressionLogic.shouldAllowRainDrivenWaterPlacement(
                level.isRaining(),
                level.isRainingAt(candidateRainCheckPos),
                level.getBiome(candidateRainCheckPos).value().coldEnoughToSnow(candidateRainCheckPos))) continue;

        var candidateState = level.getBlockState(candidate);
        if (!FFFluidUtils.getEffectiveFluidState(level, candidate, candidateState).isEmpty()) continue;

        if (!candidateState.isAir()
                && !candidateState.canBeReplaced(this)
                && !FFFluidUtils.supportsVirtualFluidState(level, candidateState)) continue;

        var belowState = level.getBlockState(candidate.below());
        if (belowState.isAir()) continue;

        int spawnAmount = Mth.clamp(FlowingFluids.config.rainSurfaceSpawnLevel, 1, 8);
        if (FFFluidUtils.setFluidStateAtPosToNewAmount(level, candidate, this, spawnAmount)) {
            AdaptiveTickScheduler.markRainBorn(level, candidate);
            ff$wakeRainFluid(level, candidate);
            return;
        }
    }
}


    @Unique
    private boolean ff$tryEvaporateNether(final Level level, final BlockPos blockPos, int amount, float chance) {
        if (!DryingEventSystem.shouldRunEvaporationTick(level, blockPos, FlowingFluids.config.evaporationNetherIntervalTicks)) {
            return false;
        }
        if (chance < DryingEventSystem.getNetherEvaporationChance(level)
                && level.dimensionType().ultraWarm()) {
            boolean evaporated = ff$applyLocalDrainAndMaybeRestoreMud(level, blockPos, amount, true);
            if (evaporated) {
                ff$triggerSteamFlash(level, blockPos, amount, true);
            }
            return evaporated;
        }
        return false;
    }

    @Unique
    private boolean ff$applyLocalDrainAndMaybeRestoreMud(Level level, BlockPos blockPos, int drainAmount, boolean allowNeighborAssist) {
        boolean changed = FFFluidUtils.applyLocalFluidAmountDelta(level, blockPos, this, -drainAmount);
        if (!changed) {
            return false;
        }
        FluidState remaining = FFFluidUtils.getEffectiveFluidState(level, blockPos);
        ff$tryRestoreMudBelowAfterDrying(level, blockPos, remaining);
        if (allowNeighborAssist && remaining.isEmpty()) {
            ff$assistNeighborEvaporation(level, blockPos);
        }
        return true;
    }

    @Unique
    private void ff$assistNeighborEvaporation(Level level, BlockPos blockPos) {
        int evaporationMaxLevel = DryingEventSystem.getSurfaceEvaporationMaxLevel();
        float assistChance = Math.min(1.0f, DryingEventSystem.getSurfaceEvaporationChance(level) * 1.35f);
        if (assistChance <= 0.0f) {
            return;
        }

        int radiusSq = ff$EVAPORATION_ASSIST_RADIUS * ff$EVAPORATION_ASSIST_RADIUS;
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        for (int dx = -ff$EVAPORATION_ASSIST_RADIUS; dx <= ff$EVAPORATION_ASSIST_RADIUS; dx++) {
            for (int dz = -ff$EVAPORATION_ASSIST_RADIUS; dz <= ff$EVAPORATION_ASSIST_RADIUS; dz++) {
                int distSq = dx * dx + dz * dz;
                if (distSq == 0 || distSq > radiusSq) {
                    continue;
                }

                neighborPos.set(blockPos.getX() + dx, blockPos.getY(), blockPos.getZ() + dz);
                if (!level.isLoaded(neighborPos)) {
                    continue;
                }

                FluidState neighborFluid = FFFluidUtils.getEffectiveFluidState(level, neighborPos);
                if (!neighborFluid.getType().isSame(this)) {
                    continue;
                }
                int amount = neighborFluid.getAmount();
                if (amount <= 0 || amount > evaporationMaxLevel) {
                    continue;
                }

                float distanceFalloff = 0.45f + 0.55f * ((radiusSq - distSq + 1) / (float) (radiusSq + 1));
                if (level.random.nextFloat() >= assistChance * distanceFalloff) {
                    continue;
                }
                if (!ff$canAssistThinSurfaceEvaporation(level, neighborPos, amount, evaporationMaxLevel)) {
                    continue;
                }
                ff$applyLocalDrainAndMaybeRestoreMud(level, neighborPos.immutable(), amount, false);
            }
        }
    }

    @Unique
    private boolean ff$canAssistThinSurfaceEvaporation(Level level, BlockPos pos, int amount, int evaporationMaxLevel) {
        if (FFFluidUtils.isProtectedInfiniteBiomeWater(level, pos, this, amount)) return false;
        if (FlowingFluids.config.evaporationDaytimeOnly && !level.isDay()) return false;
        if (DryingEventSystem.isShadeProtected(level, pos)) return false;
        if (FlowingFluids.config.evaporationRequiresSky && !DryingEventSystem.hasEvaporationSkyAccess(level, pos)) return false;
        if (level.isRainingAt(pos.above())) return false;

        FluidState aboveFluid = FFFluidUtils.getEffectiveFluidState(level, pos.above());
        boolean hasSameFluidAbove = aboveFluid != null && aboveFluid.getType().isSame(this);
        if (!FluidRegressionLogic.isSurfaceEvaporationCandidate(hasSameFluidAbove)) return false;

        BlockState sourceState = level.getBlockState(pos);
        boolean stalledThinSurface = FluidRegressionLogic.shouldIgnoreHorizontalEscapeForThinSurfaceEvaporation(
                amount,
                evaporationMaxLevel,
                hasSameFluidAbove
        );
        boolean supportedThinPuddle = FluidRegressionLogic.shouldEvaporateSupportedThinSurfacePuddle(
                amount,
                evaporationMaxLevel,
                hasSameFluidAbove,
                FFFluidUtils.isSmallSupportedThinSurfaceCluster(level, pos, this, 3, evaporationMaxLevel)
        );
        if (!stalledThinSurface
                && FFFluidUtils.canFluidFlowToNeighbourFromPos(level, pos, sourceState, this, amount)) {
            return false;
        }
        return amount <= evaporationMaxLevel
                && (FFFluidUtils.getEffectiveFluidState(level, pos.below()).isEmpty() || supportedThinPuddle);
    }

    @Unique
    private void ff$tryRestoreMudBelowAfterDrying(Level level, BlockPos blockPos, FluidState remainingFluid) {
        if (!FluidRegressionLogic.shouldRestoreMudBelowAfterEvaporation(
                remainingFluid.isEmpty(),
                level.getBlockState(blockPos.below()).is(Blocks.MUD))) {
            return;
        }
        level.setBlock(blockPos.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
    }

    @Unique
    private void ff$triggerSteamFlash(final Level level, final BlockPos blockPos, int amount, boolean fullyEvaporated) {
        float volume = 0.35f + Math.min(0.35f, amount * 0.05f);
        float pitch = 1.6f - Math.min(0.45f, amount * 0.04f);
        level.playSound(null, blockPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, volume, pitch);
        if (level instanceof ServerLevel serverLevel) {
            double x = blockPos.getX() + 0.5D;
            double y = blockPos.getY() + 0.7D;
            double z = blockPos.getZ() + 0.5D;
            int cloudCount = 5 + amount;
            int smokeCount = 3 + Math.max(1, amount / 2);
            serverLevel.sendParticles(ParticleTypes.CLOUD, x, y, z, cloudCount, 0.28D, 0.18D, 0.28D, 0.015D);
            serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 0.08D, z, smokeCount, 0.18D, 0.2D, 0.18D, 0.01D);
            if (fullyEvaporated && amount >= 6) {
                serverLevel.sendParticles(ParticleTypes.WHITE_ASH, x, y + 0.12D, z, 4 + amount / 2, 0.22D, 0.12D, 0.22D, 0.0D);
            }
        }
    }
    @Unique
    private void ff$wakeRainFluid(Level level, BlockPos pos) {
        level.scheduleTick(pos, this, 1);
        AdaptiveTickScheduler.markFlowActive(level, pos, 8);
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        for (Direction direction : ff$RAIN_WAKE_DIRECTIONS) {
            neighborPos.setWithOffset(pos, direction);
            if (!level.isLoaded(neighborPos)) continue;
            FluidState neighborFluid = FFFluidUtils.getEffectiveFluidState(level, neighborPos);
            if (neighborFluid.isEmpty() || !neighborFluid.getType().isSame(this)) continue;

            // Rain often creates a tiny fresh front against already-sleeping support water.
            // Nudge the adjacent ring awake so the new puddle can actually hand off flow.
            level.scheduleTick(neighborPos, this, direction == Direction.DOWN ? 1 : 2);
            AdaptiveTickScheduler.markFlowActive(level, neighborPos, 4);
        }
    }

    @Inject(method = "getSlopeFindDistance", at = @At(value = "RETURN"), cancellable = true)
    private void ff$modifySlopeDistance(final LevelReader level, final CallbackInfoReturnable<Integer> cir) {
        if (FlowingFluids.config.enableMod && FlowingFluids.config.isFluidAllowed(this)) {
            int configured = FlowingFluids.config.waterFlowDistance;
            int maxDistance = Math.max(1, FlowingFluids.config.maxWaterFlowDistance);
            cir.setReturnValue(Mth.clamp(configured, 1, maxDistance));
        }
    }

    @Inject(method = "getTickDelay", at = @At(value = "RETURN"), cancellable = true)
    private void ff$modifyTickDelay(final LevelReader level, final CallbackInfoReturnable<Integer> cir) {
        if (FlowingFluids.config.enableMod && FlowingFluids.config.isFluidAllowed(this)) {
            cir.setReturnValue(FluidAutoTickDelay.getAdjustedWaterTickDelay(
                    Mth.clamp(FlowingFluids.config.waterTickDelay, 1, 255)));
        }
    }
}
