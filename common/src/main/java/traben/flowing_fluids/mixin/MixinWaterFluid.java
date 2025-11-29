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
import net.minecraft.world.level.block.Blocks;
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


        isWithinInfBiomeHeights = FlowingFluids.config.fastBiomeRefillAtSeaLevelOnly
                ? level.getSeaLevel() == blockPos.getY() || level.getSeaLevel() - 1 == blockPos.getY()
                : level.getSeaLevel() == blockPos.getY() && blockPos.getY() > 0;

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
            if (ff$tryEvaporate(level, blockPos, amount, level.random.nextFloat())) {
                if (FlowingFluids.config.printRandomTicks)
                    FlowingFluids.info("--- Water was evaporated - non Nether at "+blockPos+". Chance: "+ FlowingFluids.config.evaporationChanceV2);
            }
        } else {
            if (ff$tryRainFill(level, blockPos, level.random.nextFloat())) {
                if (FlowingFluids.config.printRandomTicks)
                    FlowingFluids.info("--- Water was filled by rain at "+blockPos+". Chance: "+ FlowingFluids.config.rainRefillChance);
                // return;
            }
        }
    }




    @Unique
    private boolean ff$tryRainFill(final Level level, final BlockPos blockPos, float chance) {
        //this evaporation limit is critical!!!! otherwise the water fills endlessly
        int currentAmount = level.getFluidState(blockPos).getAmount();
        boolean blockedByInfiniteBiome = isInfBiome && isWithinInfBiomeHeights
                && currentAmount >= FlowingFluids.config.infiniteBiomeRainFillMaxLevel;

        if (chance < Math.min(FlowingFluids.config.rainRefillChance, FlowingFluids.config.evaporationChanceV2 / 3)
                && level.isRaining()
                && level.canSeeSky(blockPos.above())
                && !blockedByInfiniteBiome // very important with fill up behaviour
                && !level.getBiome(blockPos).is(BiomeTags.HAS_VILLAGE_DESERT)
        ) {
            int amount = level.isThundering() ? 2 : 1;
            var result = FFFluidUtils.placeConnectedFluidAmountAndPlaceAction(
                        level, blockPos, amount, this, 40, FlowingFluids.config.rainFillsWaterHigherV2, false);
            if (result.first() != amount) {
                result.second().run();
                AdaptiveTickScheduler.markRainBorn(level, blockPos);
                ff$maybeApplyRainJump(level, blockPos);
                return true;
            }
        }
        return false;
    }

    @Unique
    private boolean ff$tryBiomeFillOrDrain(final Level level, final BlockPos blockPos, int amount, float chance) {
        if (level.getSeaLevel() == blockPos.getY()) {
            // use either infinite biome setting to trigger this draining
            if (chance < FlowingFluids.config.infiniteWaterBiomeNonConsumeChance
                    || chance < FlowingFluids.config.oceanRiverSwampRefillChance
                    || (level.isRaining() && chance < FlowingFluids.config.rainRefillChance) // or rain chance
            ) {
                // if in ocean or river and just above sea level
                var below = level.getFluidState(blockPos.below());
                if (below.getAmount() == 8
                        && below.is(FluidTags.WATER)
                        && hasSkyLight
                        && isInfBiome) {

                    level.setBlockAndUpdate(blockPos, FFFluidUtils.getBlockForFluidByAmount(this, amount - 2));
                    return true;
                }
            }
        } else if (isWithinInfBiomeHeights) {
            if (amount < 8 && chance < FlowingFluids.config.oceanRiverSwampRefillChance) {
                // if in ocean or river and below sea level
                if (isInfBiome && hasSkyLight) {
                    // fill
                    level.setBlockAndUpdate(blockPos, FFFluidUtils.getBlockForFluidByAmount(this, amount + 2));
                    return true;
                }
            }
        }

        return false;
    }

    @Unique
    private boolean ff$tryEvaporate(final Level level, final BlockPos blockPos, int amount, float chance) {
        if (chance < FlowingFluids.config.evaporationChanceV2) {
            if (FlowingFluids.config.evaporationDaytimeOnly && !level.isDay()) return false;
            if (FlowingFluids.config.evaporationRequiresSky && !level.canSeeSky(blockPos.above())) return false;
            // evaporate over time if not raining
            if (amount <= getDropOff(level) && level.getFluidState(blockPos.below()).isEmpty()) {
                level.setBlockAndUpdate(blockPos, Blocks.AIR.defaultBlockState());
                return true;
            }
        }
        return false;
    }

    @Unique
    private void ff$maybeApplyRainJump(Level level, BlockPos blockPos) {
        if (FlowingFluids.config.rainLevelJumpChance <= 0) return;
        if (level.random.nextFloat() >= FlowingFluids.config.rainLevelJumpChance) return;
        int extra = Math.max(1, FlowingFluids.config.rainSurfaceSpawnLevel);
        var jump = FFFluidUtils.placeConnectedFluidAmountAndPlaceAction(level, blockPos, extra, this, 40,
                FlowingFluids.config.rainFillsWaterHigherV2, false);
        if (jump.second() != null && jump.first() != extra) {
            jump.second().run();
            AdaptiveTickScheduler.markRainBorn(level, blockPos);
        }
    }

    @Unique
    private void ff$trySpawnSurfaceWater(Level level, BlockPos origin, RandomSource randomSource) {
        if (!level.isRaining() || FlowingFluids.config.rainSurfaceSpawnChance <= 0) return;
        if (!level.canSeeSky(origin.above())) return;
        if (randomSource.nextFloat() >= FlowingFluids.config.rainSurfaceSpawnChance) return;

        Direction[] shuffled = FFFluidUtils.getCardinalsShuffle(randomSource);
        for (Direction direction : shuffled) {
            BlockPos candidate = origin.relative(direction);
            if (!level.canSeeSky(candidate.above())) continue;
            var candidateFluid = level.getFluidState(candidate);
            if (!candidateFluid.isEmpty()) continue;
            var candidateState = level.getBlockState(candidate);
            if (!candidateState.isAir() && !candidateState.canBeReplaced(this)) continue;
            var belowState = level.getBlockState(candidate.below());
            if (belowState.isAir()) continue;

            int spawnAmount = Mth.clamp(FlowingFluids.config.rainSurfaceSpawnLevel, 1, 4);
            if (FFFluidUtils.setFluidStateAtPosToNewAmount(level, candidate, this, spawnAmount)) {
                AdaptiveTickScheduler.markRainBorn(level, candidate);
                return;
            }
        }
    }


    @Unique
    private boolean ff$tryEvaporateNether(final Level level, final BlockPos blockPos, int amount, float chance) {

        if (chance < FlowingFluids.config.evaporationNetherChance) {
            // evaporate always if nether
            if (level.getBiome(blockPos).is(BiomeTags.IS_NETHER)) {
                if (amount == 1) {
                    level.setBlockAndUpdate(blockPos, Blocks.AIR.defaultBlockState());
                } else {
                    level.setBlockAndUpdate(blockPos, FFFluidUtils.getBlockForFluidByAmount(this, amount - 3));
                }
                return true;
            }
        }
        return false;
    }

    @Inject(method = "getSlopeFindDistance", at = @At(value = "RETURN"), cancellable = true)
    private void ff$modifySlopeDistance(final LevelReader level, final CallbackInfoReturnable<Integer> cir) {
        if (FlowingFluids.config.enableMod && FlowingFluids.config.isFluidAllowed(this)) {
            cir.setReturnValue(Mth.clamp(FlowingFluids.config.waterFlowDistance, 1, 8));
        }
    }

    @Inject(method = "getTickDelay", at = @At(value = "RETURN"), cancellable = true)
    private void ff$modifyTickDelay(final LevelReader level, final CallbackInfoReturnable<Integer> cir) {
        if (FlowingFluids.config.enableMod && FlowingFluids.config.isFluidAllowed(this)) {
            cir.setReturnValue(Mth.clamp(FlowingFluids.config.waterTickDelay, 1, 255));
        }
    }
}
