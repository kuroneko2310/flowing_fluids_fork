package traben.flowing_fluids.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FFPlaystylePresetsTest {

    @Test
    void relaxedStopsCreateMachinesFromUsingWater() {
        FFConfig config = new FFConfig();
        FFPlaystylePresets.PRESETS.get("relaxed").apply().accept(config);
        assertTrue(config.create_infinitePipes);
        assertEquals(0.0f, config.create_fanWashingWaterUseChance);
        assertEquals(1.0f, config.create_waterWheelMaxSpeedMultiplier);
    }

    @Test
    void presetsLeaveUnrelatedSettingsAlone() {
        FFConfig config = new FFConfig();
        config.waterTickDelay = 7.5f;
        config.enableDisplacement = false;
        for (FFPlaystylePresets.Preset preset : FFPlaystylePresets.PRESETS.values()) {
            preset.apply().accept(config);
        }
        assertEquals(7.5f, config.waterTickDelay);
        assertFalse(config.enableDisplacement);
    }

    @Test
    void lightweightNeverRaisesGroundwaterSampling() {
        FFConfig config = new FFConfig();
        config.groundwaterSamplesPerTick = 2;
        FFPlaystylePresets.PRESETS.get("lightweight_server").apply().accept(config);
        assertEquals(2, config.groundwaterSamplesPerTick);
    }
}
