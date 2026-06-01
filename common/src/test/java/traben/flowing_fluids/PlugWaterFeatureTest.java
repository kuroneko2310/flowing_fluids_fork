package traben.flowing_fluids;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlugWaterFeatureTest {

    @Test
    void skipsOpenOceanSurfaceSources() {
        assertTrue(PlugWaterFeature.shouldIgnoreOpenWaterSource(true, 63, 63));
        assertTrue(PlugWaterFeature.shouldIgnoreOpenWaterSource(true, 62, 63));
        assertTrue(PlugWaterFeature.shouldIgnoreOpenWaterSource(true, 59, 63));
        assertFalse(PlugWaterFeature.shouldIgnoreOpenWaterSource(true, 57, 63));
        assertFalse(PlugWaterFeature.shouldIgnoreOpenWaterSource(false, 63, 63));
    }

    @Test
    void keepsBelowSurfaceAirPocketsPluggable() {
        assertTrue(PlugWaterFeature.shouldSkipNaturalAirPocket(true, 63, 63));
        assertTrue(PlugWaterFeature.shouldSkipNaturalAirPocket(true, 62, 63));
        assertTrue(PlugWaterFeature.shouldSkipNaturalAirPocket(true, 59, 63));
        assertFalse(PlugWaterFeature.shouldSkipNaturalAirPocket(true, 57, 63));
        assertFalse(PlugWaterFeature.shouldSkipNaturalAirPocket(false, 63, 63));
    }
}
