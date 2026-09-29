package traben.flowing_fluids.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FloodWaterTintTest {
    @Test
    void rainLevelIsQuantisedIntoThreeSteps() {
        assertEquals(0.0f, FloodWaterTint.quantize(0.0f));
        assertEquals(0.0f, FloodWaterTint.quantize(0.2f));
        assertEquals(0.5f, FloodWaterTint.quantize(0.4f));
        assertEquals(1.0f, FloodWaterTint.quantize(0.9f));
        assertEquals(1.0f, FloodWaterTint.quantize(3.0f));
    }

    @Test
    void blendKeepsAlphaAndReachesTheTarget() {
        int water = 0xFF3F76E4;
        assertEquals(water, FloodWaterTint.blend(water, FloodWaterTint.MURKY_COLOR, 0.0f));
        assertEquals(0xFF7A6A45, FloodWaterTint.blend(water, FloodWaterTint.MURKY_COLOR, 1.0f));
    }
}
