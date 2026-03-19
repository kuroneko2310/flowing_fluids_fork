package traben.flowing_fluids.mixin;


import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
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
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.drying.DryingEventSystem;


@Mixin(WaterFluid.class)
public abstract class MixinWaterFluid extends FlowingFluid {

    @Shadow
    public abstract int getDropOff(final LevelReader levelReader);

    @Shadow
    public abstract boolean isSame(final Fluid fluid);

    @Unique
    boolean isWithinInfBiomeHeights = false;
    @Unique
    boolean isInfBiome = false;
    @Unique
    boolean hasSkyLight = false;


    @Override
    protected void randomTick(final #if MC > MC_21 ServerLevel #else Level #endif level,
                              final BlockPos blockPos, final FluidState fluidState, final RandomSource randomSource) {
        super.randomTick(level, blockPos, fluidState, randomSource);

        if (level.isClientSide()
                || fluidState.isEmpty()
                || !FlowingFluids.config.enableMod
                || !FlowingFluids.config.isFluidAllowed(fluidState)) return;

        if (FlowingFluids.config.dontTickAtLocation(blockPos, level)) return; // do not calculate


        isWithinInfBiomeHeights = FFFluidUtils.isWithinInfiniteBiomeRefillBand(level, blockPos);

        hasSkyLight = level.getBrightness(LightLayer.SKY, blockPos) > 0; // is close enough to sky/atmosphere access

        isInfBiome = FFFluidUtils.matchInfiniteBiomes(level.getBiome(blockPos));

        int amount = fluidState.getAmount();
        ff$trySpawnSurfaceWater(level, blockPos, randomSource);
        if (amount < 8) {
            if (ff$tryBiomeFillOrDrain(level, blockPos, amount, level.random.nextFloat())) {
                if (FlowingFluids.config.printRandomTicks)
                    FlowingFluids.info("--- Water was filled by biome at "+blockPos+". Chance: "+ FlowingFluids.config.oceanRiverSwampRefillChance);
                return;
            }
            if (ff$tryRainFill(level, blockPos, level.random.nextFloat())) {
                if (FlowingFluids.config.printRandomTicks)
                    FlowingFluids.info("--- Water was filled by rain at "+blockPos+". Chance: "+ FlowingFluids.config.rainRefillChance);
                return;
            }
            if (ff$tryEvaporateNether(level, blockPos, amount, level.random.nextFloat())) {
                if (FlowingFluids.config.printRandomTicks)
                    FlowingFluids.info("--- Water was evaporated via Nether at "+blockPos+". Chance: "+ FlowingFluids.config.evaporationChanceV2);
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
            if (ff$tryRainFill(level, blockPos, level.random.nextFloat())) {
                if (FlowingFluids.config.printRandomTicks)
                    FlowingFluids.info("--- Water was filled by rain at "+blockPos+". Chance: "+ FlowingFluids.config.rainRefillChance);
            }
        }
    }




@Unique
private boolean ff$tryRainFill(final Level level, final BlockPos blockPos, float chance) {
    if (!FlowingFluids.config.enableRainSystem) return false;
    //this evaporation limit is critical!!!! otherwise the water fills endlessly
    int currentAmount = FFFluidUtils.getEffectiveFluidState(level, blockPos).getAmount();
    boolean blockedByInfiniteBiome = isInfBiome && isWithinInfBiomeHeights
            && currentAmount >= FlowingFluids.config.infiniteBiomeRainFillMaxLevel;
    float rainChance = Mth.clamp(FlowingFluids.config.rainRefillChance * DryingEventSystem.getRainRefillMultiplier(level), 0.0f, 1.0f);
    if (chance < rainChance
            && level.isRaining()
            && level.canSeeSky(blockPos.above())
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
    private boolean ff$tryBiomeFillOrDrain(final Level level, final BlockPos blockPos, int amount, float chance) {
        boolean riverBiome = FFFluidUtils.isRiverBiome(level.getBiome(blockPos));
        if (riverBiome && AdaptiveTickScheduler.isFlowActiveNow(level, blockPos)) {
            return false;
        }
        if (riverBiome
                && DryingEventSystem.shouldRiverDroughtDrain(level, blockPos, amount)
                && chance < DryingEventSystem.getRiverDroughtDrainChance(level)) {
            return FFFluidUtils.applyLocalFluidAmountDelta(level, blockPos, this, -1);
        }

        if (level.getSeaLevel() == blockPos.getY()) {
            if (chance < FlowingFluids.config.infiniteWaterBiomeDrainSurfaceChance) {
                // Sea-level drain is only meant for thin partial surface tiles.
                if (hasSkyLight
                        && isInfBiome
                        && FFFluidUtils.shouldDrainInfiniteBiomeSurface(level, blockPos, this, amount)) {
                    return FFFluidUtils.applyLocalFluidAmountDelta(level, blockPos, this, -1);
                }
            }
        } else if (isWithinInfBiomeHeights) {
            if (!FFFluidUtils.shouldAttemptInfiniteBiomeFlowingRefill(level, blockPos, this, 8, amount)) {
                return false;
            }
            if (isInfBiome && hasSkyLight) {
                int refillAmount = FFFluidUtils.getInfiniteBiomeFlowingRefillAmount(level, blockPos, this, amount);
                if (refillAmount > 0) {
                    return FFFluidUtils.applyConnectedFluidAmountDelta(level, blockPos, this, refillAmount, 12, false, true);
                }
            }
        }

        return false;
    }

    @Unique
    private boolean ff$tryEvaporate(final Level level, final BlockPos blockPos, int amount, float chance) {
        float evaporationChance = Mth.clamp(FlowingFluids.config.evaporationChanceV2 * DryingEventSystem.getAmbientEvaporationMultiplier(level), 0.0f, 1.0f);
        if (chance < evaporationChance) {
            if (FFFluidUtils.isProtectedInfiniteBiomeWater(level, blockPos, this, amount)) return false;
            if (AdaptiveTickScheduler.isFlowActiveNow(level, blockPos)) return false;
            if (FlowingFluids.config.evaporationDaytimeOnly && !level.isDay()) return false;
            if (DryingEventSystem.isShadeProtected(level, blockPos)) return false;
            if (FlowingFluids.config.evaporationRequiresSky && !level.canSeeSky(blockPos.above())) return false;
            if (level.isRainingAt(blockPos.above())) return false;
            if (FFFluidUtils.canFluidFlowToNeighbourFromPos(level, blockPos, this, amount)) return false;
            FluidState aboveFluid = FFFluidUtils.getEffectiveFluidState(level, blockPos.above());
            if (!MixinFluidRegressionLogic.isSurfaceEvaporationCandidate(aboveFluid != null && aboveFluid.getType().isSame(this))) return false;
            // evaporate over time if not raining
            if (amount <= getDropOff(level) && FFFluidUtils.getEffectiveFluidState(level, blockPos.below()).isEmpty()) {
                return FFFluidUtils.applyLocalFluidAmountDelta(level, blockPos, this, -amount);
            }
        }
        return false;
    }

    @Unique
    private boolean ff$tryHeatSourceEvaporate(final Level level, final BlockPos blockPos, int amount, float chance) {
        if (!DryingEventSystem.hasNearbyHeatSource(level, blockPos)) return false;
        if (FFFluidUtils.isProtectedInfiniteBiomeWater(level, blockPos, this, amount)) return false;
        if (AdaptiveTickScheduler.isFlowActiveNow(level, blockPos)) return false;
        if (FFFluidUtils.canFluidFlowToNeighbourFromPos(level, blockPos, this, amount)) return false;
        float heatChance = Mth.clamp(FlowingFluids.config.hotBlockEvaporationChance * DryingEventSystem.getAmbientEvaporationMultiplier(level), 0.0f, 1.0f);
        if (chance >= heatChance) return false;
        int drainAmount = Mth.clamp(FlowingFluids.config.hotBlockEvaporationDrainAmount, 1, amount);
        return FFFluidUtils.applyLocalFluidAmountDelta(level, blockPos, this, -drainAmount);
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
    if (!level.isRaining() || FlowingFluids.config.rainSurfaceSpawnChance <= 0) return;
    if (!level.canSeeSky(origin.above())) return;
    if (randomSource.nextFloat() >= FlowingFluids.config.rainSurfaceSpawnChance) return;

    Direction[] shuffled = FFFluidUtils.getCardinalsShuffle(randomSource);
    for (Direction direction : shuffled) {
        BlockPos candidate = origin.relative(direction);
        if (!level.canSeeSky(candidate.above())) continue;

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

        if (chance < FlowingFluids.config.evaporationNetherChance) {
            // evaporate always if nether
            if (level.getBiome(blockPos).is(BiomeTags.IS_NETHER)) {
                int drainAmount = amount == 1 ? -1 : -Math.min(3, amount);
                return FFFluidUtils.applyLocalFluidAmountDelta(level, blockPos, this, drainAmount);
            }
        }
        return false;
    }
    @Unique
    private void ff$wakeRainFluid(Level level, BlockPos pos) {
     level.scheduleTick(pos, this, 1);
     AdaptiveTickScheduler.markFlowActive(level, pos, 8);
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
            cir.setReturnValue(Mth.clamp(FlowingFluids.config.waterTickDelay, 1, 255));
        }
    }
}
