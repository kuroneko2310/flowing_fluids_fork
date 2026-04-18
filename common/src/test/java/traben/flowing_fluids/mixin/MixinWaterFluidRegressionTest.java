package traben.flowing_fluids.mixin;

import org.junit.jupiter.api.Test;
import traben.flowing_fluids.FluidRegressionLogic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinWaterFluidRegressionTest {

    @Test
    void evaporationRequiresOpenSpaceAbove() {
        assertFalse(FluidRegressionLogic.isSurfaceEvaporationCandidate(true));
    }

    @Test
    void exposedSurfaceWithoutWaterAboveCanEvaporate() {
        assertTrue(FluidRegressionLogic.isSurfaceEvaporationCandidate(false));
    }

    @Test
    void shallowExposedSurfaceIgnoresHorizontalEscapeProbe() {
        assertTrue(FluidRegressionLogic.shouldIgnoreHorizontalEscapeForThinSurfaceEvaporation(1, 1, false));
    }

    @Test
    void thickerSurfaceStillRespectsHorizontalEscapeProbe() {
        assertFalse(FluidRegressionLogic.shouldIgnoreHorizontalEscapeForThinSurfaceEvaporation(2, 1, false));
    }

    @Test
    void coveredSurfaceStillBlocksThinEvaporationShortcut() {
        assertFalse(FluidRegressionLogic.shouldIgnoreHorizontalEscapeForThinSurfaceEvaporation(1, 1, true));
    }

    @Test
    void supportedThinPuddleCanEvaporate() {
        assertTrue(FluidRegressionLogic.shouldEvaporateSupportedThinSurfacePuddle(1, 1, false, true));
    }

    @Test
    void unsupportedClusterDoesNotEnablePuddleEvaporation() {
        assertFalse(FluidRegressionLogic.shouldEvaporateSupportedThinSurfacePuddle(1, 1, false, false));
    }

    @Test
    void deeperSupportedWaterStillDoesNotCountAsThinPuddle() {
        assertFalse(FluidRegressionLogic.shouldEvaporateSupportedThinSurfacePuddle(2, 1, false, true));
    }

    @Test
    void heatSourceEvaporationRequiresOpenSky() {
        assertFalse(FluidRegressionLogic.shouldHeatSourceEvaporateSurfaceWater(false, false, false, false));
    }

    @Test
    void heatSourceEvaporationRejectsCoveredSurface() {
        assertFalse(FluidRegressionLogic.shouldHeatSourceEvaporateSurfaceWater(false, true, false, true));
    }

    @Test
    void heatSourceEvaporationRejectsStackedWater() {
        assertFalse(FluidRegressionLogic.shouldHeatSourceEvaporateSurfaceWater(true, true, false, false));
    }

    @Test
    void heatSourceEvaporationAllowsExposedDrySurface() {
        assertTrue(FluidRegressionLogic.shouldHeatSourceEvaporateSurfaceWater(false, true, false, false));
    }

    @Test
    void driedCellRestoresMudBelowToDirt() {
        assertTrue(FluidRegressionLogic.shouldRestoreMudBelowAfterEvaporation(true, true));
    }

    @Test
    void partialWaterOrNonMudBelowDoesNotRestoreTerrain() {
        assertFalse(FluidRegressionLogic.shouldRestoreMudBelowAfterEvaporation(false, true));
        assertFalse(FluidRegressionLogic.shouldRestoreMudBelowAfterEvaporation(true, false));
    }
}
