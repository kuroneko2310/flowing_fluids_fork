package traben.flowing_fluids;

/**
 * Shared regression guards for fluid behavior that must stay usable from both
 * mixins and plain support code. Keeping this outside {@code *.mixin} avoids
 * Mixin's package ownership checks from turning helper calls into class-load
 * crashes at runtime.
 */
public final class FluidRegressionLogic {
    private static final int VISUAL_MAINTENANCE_CHUNK_GRACE = 16;
    private static final int MIN_VISUAL_MAINTENANCE_DISTANCE = 24;
    private static final int MAX_VISUAL_MAINTENANCE_DISTANCE = 256;
    private static final int DISTANT_VISUAL_SLOPE_CLAMP = 3;

    private FluidRegressionLogic() {
    }

    public static int getPlayerVisualMaintenanceDistance(int playerBlockDistanceForFlowing) {
        if (playerBlockDistanceForFlowing <= 0) {
            return 0;
        }
        // Keep a one-chunk grace band beyond the hard simulation radius so loaded
        // water near the player does not freeze into a still image right at the edge.
        int paddedDistance = Math.max(MIN_VISUAL_MAINTENANCE_DISTANCE,
            playerBlockDistanceForFlowing + VISUAL_MAINTENANCE_CHUNK_GRACE);
        return Math.min(MAX_VISUAL_MAINTENANCE_DISTANCE, paddedDistance);
    }

    public static int clampDistantVisualSlopeSearchDistance(int slopeFindDistance) {
        if (slopeFindDistance <= 0) {
            return 0;
        }
        // Far-visual upkeep should still notice nearby ledges, but it should not
        // pay for the long corridor searches reserved for full simulation range.
        return Math.min(slopeFindDistance, DISTANT_VISUAL_SLOPE_CLAMP);
    }

    public static boolean isSurfaceEvaporationCandidate(boolean hasSameFluidAbove) {
        return !hasSameFluidAbove;
    }

    public static boolean shouldIgnoreHorizontalEscapeForThinSurfaceEvaporation(int amount,
                                                                                int dropOff,
                                                                                boolean hasSameFluidAbove) {
        // Random-tick evaporation probes horizontal movement more loosely than the
        // main flow tick. One-level surface water on flat ground can look
        // "movable" here even though the actual tick will keep it parked unless it
        // finds a real edge drop. Let those shallow exposed remnants evaporate.
        return amount > 0
                && amount <= Math.max(1, dropOff)
                && !hasSameFluidAbove;
    }

    public static boolean shouldEvaporateSupportedThinSurfacePuddle(int amount,
                                                                    int dropOff,
                                                                    boolean hasSameFluidAbove,
                                                                    boolean smallSupportedCluster) {
        // Tiny rain puddles sit on solid ground, so "below is empty" never fires for
        // them even though they are already stable and have nowhere meaningful to go.
        return smallSupportedCluster
                && shouldIgnoreHorizontalEscapeForThinSurfaceEvaporation(amount, dropOff, hasSameFluidAbove);
    }

    public static boolean shouldHeatSourceEvaporateSurfaceWater(boolean hasSameFluidAbove,
                                                                boolean openToSky,
                                                                boolean rainingAtSurface,
                                                                boolean shadeProtected) {
        // Nearby lava or magma is meant to help exposed surface puddles dry out,
        // not to delete groundwater sealed inside caves.
        return !hasSameFluidAbove
                && openToSky
                && !rainingAtSurface
                && !shadeProtected;
    }

    public static boolean shouldRestoreMudBelowAfterEvaporation(boolean cellDry, boolean mudBelow) {
        return cellDry && mudBelow;
    }

    public static boolean shouldAllowRainDrivenWaterPlacement(boolean dimensionRaining,
                                                              boolean rainingAtTarget,
                                                              boolean coldEnoughToSnow) {
        // Rain-driven water generation should follow the actual local weather column.
        // Global rain alone is too broad: sheltered tiles, dry biomes, or snow columns
        // can otherwise keep spawning liquid water after the player no longer sees rain.
        return dimensionRaining && rainingAtTarget && !coldEnoughToSnow;
    }

    public static boolean shouldApplyRainBornCooldown(boolean dimensionRaining,
                                                      boolean rainingAtTarget,
                                                      boolean coldEnoughToSnow,
                                                      boolean amountIncreased) {
        // The scheduler should only treat a rise as "rain-born" when the local
        // weather column still matches liquid rain. Otherwise any incidental increase
        // on an exposed surface during rainy weather gets the same cooldown and can
        // look like player-near water is being singled out or delayed oddly.
        return amountIncreased && shouldAllowRainDrivenWaterPlacement(
            dimensionRaining,
            rainingAtTarget,
            coldEnoughToSnow
        );
    }

    public static boolean shouldTraverseFluidAdjacency(boolean facePassable,
                                                       boolean targetSameFluid,
                                                       boolean targetHasOtherFluid,
                                                       boolean targetAir,
                                                       boolean targetReplaceable,
                                                       boolean targetVirtual,
                                                       boolean targetCanHoldFluid) {
        // Reachability for equalizer/connected-fluid scans should respect the shared
        // face first. Once that face is blocked, treating the neighbor as part of the
        // same averaging body lets water "see through" walls and duplicate support
        // across sealed cavities.
        if (!facePassable) {
            return false;
        }
        if (targetSameFluid) {
            return true;
        }
        if (targetHasOtherFluid) {
            return false;
        }
        return targetAir || targetReplaceable || targetVirtual || targetCanHoldFluid;
    }

    public static boolean shouldAllowEqualizerDryActivation(int wetCells,
                                                            int minWetAmount,
                                                            int maxWetAmount,
                                                            boolean hasReachableHorizontalFrontier) {
        // Equalization is now a conservation-only pass. Letting it mint new wet
        // cells from adjacent air makes ordinary placed water expand even when no
        // real flow transfer added volume to the system.
        return false;
    }

    public static boolean shouldQueueTraversalPairForEqualizer(boolean amountDifferenceWarrantsEqualization,
                                                               boolean traversedVerticalDrop) {
        // A step-down connection matters for reachability and downhill search budget,
        // but it should not by itself merge the upper shelf into the lower basin's
        // averaging set. Otherwise one raised water cell can donate its whole budget
        // into the nearby lower surface whenever the player is close enough for the
        // equalizer to run.
        return amountDifferenceWarrantsEqualization;
    }

    public static boolean shouldPromoteVisitedEqualizerTargets(boolean traversedVerticalDrop,
                                                               int visitedAmountVariance,
                                                               int varianceThreshold) {
        int requiredVariance = Math.max(1, varianceThreshold);
        // Reaching a vertical drop is useful for exploring downstream cells, but it is
        // not proof that every visited water cell belongs in one equalization pool.
        // Promote the whole visited set only when the visited amounts actually show a
        // wide enough spread to justify a surface-wide rebalance.
        if (traversedVerticalDrop && visitedAmountVariance < requiredVariance) {
            return false;
        }
        return visitedAmountVariance >= requiredVariance;
    }

    public static boolean shouldPreserveThinShallowFlow(int sourceAmount, int targetAmount, int difference) {
        if (difference <= 0) {
            return false;
        }
        // Allow shallow water to create a 1-level tail on dry ground instead of
        // freezing as a 2/3-level blob. This keeps thin surface flows visible.
        return targetAmount == 0 && sourceAmount > 0 && sourceAmount <= 3;
    }

    public static boolean shouldSkipInfiniteBiomeOutletHorizontalSearch(boolean retainedMinimumSource,
                                                                        boolean infiniteBiomeSource,
                                                                        boolean immediateDownwardOutlet) {
        // Infinite-biome cave mouths intentionally keep feeding downward. Once the tick
        // already committed that vertical transfer, the retained drop-off cap is only a
        // transient placeholder before refill/non-consume restores the source. Running a
        // full horizontal spread probe on that temporary cap every tick scales badly
        // across broad ocean floors with many breaches.
        return retainedMinimumSource && infiniteBiomeSource && immediateDownwardOutlet;
    }

    public static boolean shouldTreatBroadSurfaceCellAsStillReservoir(int amount) {
        // Broad-surface calm scheduling is only safe for full-strength interior cells.
        // Partial shoreline water still needs normal ticks so visible steps can smooth out.
        return amount >= 8;
    }

    public static boolean shouldTreatBroadSurfaceCellAsStillReservoir(int amount, boolean nearbyDisturbance) {
        // A full-strength tile a couple of blocks behind the shoreline is still part of
        // the live front. Sleeping it too early starves the downstream slope and makes
        // wide surfaces stop one or two cells short of where the water should reach.
        return shouldTreatBroadSurfaceCellAsStillReservoir(amount) && !nearbyDisturbance;
    }

    public static boolean shouldBypassBroadSurfaceTransferSuppression(int sourceAmount, int targetAmount) {
        // The broad-surface suppression is meant to stop tiny full-pool churn.
        // If either side is partial-height, suppressing the move freezes the shoreline step.
        return sourceAmount < 8 || targetAmount < 8;
    }

    public static boolean shouldApplyCalmBroadSurfaceTransferSuppression(boolean sourceStillReservoir,
                                                                         boolean targetStillReservoir) {
        // Broad-surface suppression is only safe when both cells are truly calm interior water.
        // Using the wider "broad surface" label here also catches active shoreline supply tiles.
        return sourceStillReservoir && targetStillReservoir;
    }

    public static boolean shouldSuppressBroadSurfaceExploratorySpread(boolean stillReservoirInterior) {
        // Exploratory spread is the mechanism that keeps a live front searching for the next outlet.
        // Calm-interior water can skip it, but broad water near a front still needs that search.
        return stillReservoirInterior;
    }

    public static boolean shouldPreserveBroadSurfaceThinSource(boolean broadSurface,
                                                               boolean inletZone,
                                                               boolean immediateSurfaceEdge,
                                                               boolean nearbyStepDownOutlet) {
        // Broad-surface thin sources are useful around live fronts so shoreline tails do not collapse
        // too aggressively, but preserving them inside a sealed calm pool leaves 1-level dents behind.
        return broadSurface && (inletZone || immediateSurfaceEdge || nearbyStepDownOutlet);
    }

    public static boolean shouldWakeBroadSurfaceEqualizerForThinPartial(boolean broadSurface,
                                                                        boolean pressureDriven,
                                                                        int difference,
                                                                        int beforeAmount,
                                                                        int afterAmount) {
        // Large calm surfaces already wake the equalizer on >=2 level jumps. The remaining visible
        // artifact is a partial-height 1-step dent that can settle without another strong disturbance.
        if (!broadSurface || pressureDriven || difference != 1) {
            return false;
        }
        return beforeAmount > 0 && beforeAmount < 8
            || afterAmount > 0 && afterAmount < 8;
    }

    public static boolean shouldTrackWaterPoolStableTicks(int amount) {
        // Water profiles use poolStableTicks for both shallow-settled behavior and broad-surface /
        // reservoir classification. Restricting tracking to only thin water makes the large-body
        // heuristics read "never stable" even on genuinely settled full cells.
        return amount > 0;
    }

    public static boolean shouldPreferThinDryEdgeBalance(int sourceAmount, int targetAmount,
                                                         int difference, int minimumRetainedAmount) {
        return minimumRetainedAmount <= 0
                && shouldPreserveThinShallowFlow(sourceAmount, targetAmount, difference);
    }

    public static float getThinDryEdgeDestinationBiasLevels(int sourceAmount, int targetAmount) {
        if (sourceAmount <= 0 || targetAmount != 0) {
            return 0f;
        }
        // A tiny negative destination bias breaks the 3 -> 0 tie toward a
        // visible 1-level leading edge without blocking the 2 -> 0 => 1/1 split.
        return -0.25f;
    }

    public static int computeDryActivationCount(int remaining, int dryCandidates, int minDryCellFillLevel) {
        if (remaining <= 0 || dryCandidates <= 0) {
            return 0;
        }
        // Very small leftovers should be allowed to fan out into 1-level tails.
        if (remaining <= 3) {
            return Math.min(dryCandidates, remaining);
        }

        int safeMinDryFill = Math.max(1, minDryCellFillLevel);
        int minCellsNeeded = Math.max(1, (remaining + 7) / 8);
        int maxCellsForCoherentFill = remaining >= safeMinDryFill
                ? Math.max(1, remaining / safeMinDryFill)
                : 1;
        int selected = Math.min(dryCandidates, minCellsNeeded);
        if (selected > maxCellsForCoherentFill) {
            selected = Math.min(dryCandidates, maxCellsForCoherentFill);
        }
        return Math.max(1, selected);
    }

    public static int expandDryActivationCountForPressure(int baseCount,
                                                          int remaining,
                                                          int dryCandidates,
                                                          float pressureBias) {
        if (baseCount <= 0 || remaining <= 0 || dryCandidates <= baseCount) {
            return baseCount;
        }
        if (pressureBias < 0.35f) {
            return baseCount;
        }

        int extraCells = pressureBias >= 0.9f && remaining >= Math.max(4, baseCount * 2) ? 2 : 1;
        return Math.min(dryCandidates, baseCount + extraCells);
    }

    public static int computeContainedRiseScore(boolean roofed,
                                                boolean supportedBelow,
                                                boolean immediateDownwardOutlet,
                                                int lateralEscapeRoutes,
                                                int lateralWaterNeighbors) {
        int score = 0;
        if (roofed) {
            score += 3;
        }
        if (supportedBelow) {
            score += 2;
        }
        score += Math.max(0, 2 - lateralEscapeRoutes) * 2;
        score += Math.min(2, lateralWaterNeighbors);
        if (immediateDownwardOutlet) {
            score -= 3;
        }
        return score;
    }

    public static float computeCavityPressureBias(int sourceAmount,
                                                  float flowSpeedTransferBonus,
                                                  float flowMomentum,
                                                  boolean sourceEnclosed,
                                                  boolean targetRoofed,
                                                  boolean supportedBelow,
                                                  boolean immediateDownwardOutlet,
                                                  int lateralEscapeRoutes,
                                                  int lateralWaterNeighbors,
                                                  int connectedHeadBlocks,
                                                  float cavityPressureStrength,
                                                  float connectedHeadStrength) {
        if (sourceAmount <= 0 || cavityPressureStrength <= 0.0f) {
            return 0.0f;
        }

        float inflow = Math.max(0.0f, (sourceAmount - 3) / 5.0f);
        inflow += Math.max(0.0f, flowSpeedTransferBonus * 1.8f);
        inflow += Math.max(0.0f, flowMomentum * 0.65f);
        if (sourceEnclosed) {
            inflow += 0.18f;
        }

        float confinement = 0.0f;
        if (targetRoofed) {
            confinement += 0.35f;
        }
        if (supportedBelow) {
            confinement += 0.22f;
        }
        confinement += Math.max(0, 2 - lateralEscapeRoutes) * 0.18f;
        confinement += Math.min(0.18f, lateralWaterNeighbors * 0.06f);

        float drainage = immediateDownwardOutlet ? 0.45f : 0.0f;
        drainage += Math.max(0, lateralEscapeRoutes - 1) * 0.08f;

        float headBoost = 0.0f;
        if (connectedHeadStrength > 0.0f && connectedHeadBlocks > 0) {
            float perBlock = targetRoofed ? 0.18f : 0.22f;
            headBoost = Math.min(1.6f, connectedHeadBlocks * perBlock * connectedHeadStrength);
            if (!immediateDownwardOutlet) {
                headBoost += Math.min(0.18f, 0.05f * connectedHeadBlocks);
            }
        }

        float baseBias = Math.max(0.0f, inflow + confinement - drainage) * cavityPressureStrength;
        if (connectedHeadBlocks > 0 && !immediateDownwardOutlet) {
            baseBias += targetRoofed ? 0.04f : 0.1f;
        }
        return Math.min(1.25f, baseBias + headBoost);
    }

    public static boolean shouldUseSameFluidVerticalPressureTransfer(boolean sameFluidColumn, boolean lavaFluid) {
        // Lava lakes rely on stacked columns to keep their surface height.
        // Reusing the fast "push the top block downward" shortcut here flattens
        // the column back into the pool and makes molten layers refuse to stack.
        if (!sameFluidColumn) {
            return true;
        }
        return !lavaFluid;
    }

    public static boolean isStillLavaReservoir(int amount,
                                               boolean flowActive,
                                               boolean supportedBelow,
                                               boolean immediateSurfaceEdge,
                                               boolean immediateDownwardOutlet,
                                               int lateralLavaNeighbors,
                                               boolean hasFluidAbove,
                                               int stableTicks) {
        if (amount < 6
                || flowActive
                || !supportedBelow
                || immediateSurfaceEdge
                || immediateDownwardOutlet
                || lateralLavaNeighbors < 3) {
            return false;
        }

        int requiredStableTicks = hasFluidAbove ? 3 : 5;
        return stableTicks >= requiredStableTicks;
    }

    public static int getStillLavaDelay(int baseDelay, boolean hasFluidAbove) {
        int safeBaseDelay = Math.max(1, baseDelay);
        int multiplier = hasFluidAbove ? 4 : 3;
        int desiredDelay = Math.max(8, safeBaseDelay * multiplier);
        return Math.min(hasFluidAbove ? 80 : 60, desiredDelay);
    }

    public static int adjustLavaAdaptiveDelay(int baseDelay, boolean stillReservoir, boolean hasFluidAbove) {
        if (!stillReservoir) {
            return Math.max(1, baseDelay);
        }
        float multiplier = hasFluidAbove ? 1.45f : 1.2f;
        return Math.max(1, Math.round(baseDelay * multiplier));
    }

    public static boolean shouldReplenishUltraWarmLavaReservoir(boolean ultraWarmDimension,
                                                                int amount,
                                                                boolean stillReservoir,
                                                                int lateralLavaNeighbors,
                                                                boolean hasFluidAbove) {
        // The refill should feel like pressure from the molten lake core, not a free
        // source at every shoreline. Keep it inside calm, full-strength reservoir cells.
        if (!ultraWarmDimension
                || amount < 8
                || !stillReservoir
                || lateralLavaNeighbors < 4) {
            return false;
        }
        return hasFluidAbove || lateralLavaNeighbors >= 5;
    }

    public static boolean shouldQueueLavaEqualizer(boolean stillReservoir,
                                                   int delta,
                                                   boolean beforeEmpty,
                                                   boolean afterEmpty,
                                                   boolean immediateSurfaceEdge,
                                                   boolean immediateDownwardOutlet,
                                                   boolean flowActive) {
        if (beforeEmpty
                || afterEmpty
                || immediateSurfaceEdge
                || immediateDownwardOutlet
                || flowActive) {
            return true;
        }
        if (!stillReservoir) {
            return delta >= 1;
        }
        return delta >= 2;
    }

    public static int getInfiniteBiomeNonConsumeRecoveryAmount(int originalAmount,
                                                               int currentAmount,
                                                               boolean flowActive,
                                                               boolean stillReservoir,
                                                               int maxRecovery) {
        if (originalAmount <= 0 || currentAmount <= 0 || currentAmount >= originalAmount) {
            return 0;
        }
        if (flowActive || !stillReservoir || maxRecovery <= 0) {
            return 0;
        }
        return Math.min(maxRecovery, originalAmount - currentAmount);
    }

    public static int computeVanillaWaterlogTransferAmount(boolean fromIsWaterloggableVanilla,
                                                           boolean toIsWaterloggableVanilla,
                                                           int amount,
                                                           int destFluidAmount) {
        if (amount <= 0) {
            return 0;
        }
        if (destFluidAmount + amount < 8) {
            return 0;
        }
        int requiredToFill = Math.max(0, 8 - destFluidAmount);
        if (toIsWaterloggableVanilla) {
            return requiredToFill;
        }
        if (fromIsWaterloggableVanilla) {
            return Math.min(amount, requiredToFill);
        }
        return 0;
    }
}
