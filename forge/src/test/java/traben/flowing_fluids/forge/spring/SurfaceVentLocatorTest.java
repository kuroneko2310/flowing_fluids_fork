package traben.flowing_fluids.forge.spring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceVentLocatorTest {
    @Test
    void waterVentShapeScalesBySpringStrength() {
        assertEquals(2, SurfaceVentLocator.crestHeightFor(SpringStrength.LARGE));
        assertEquals(3, SurfaceVentLocator.crestHeightFor(SpringStrength.HEAVY));
        assertEquals(1, SurfaceVentLocator.sprayBurstsFor(SpringStrength.LARGE));
        assertEquals(2, SurfaceVentLocator.sprayBurstsFor(SpringStrength.HEAVY));
    }

    @Test
    void fountainSprayUsesPartialWaterAmounts() {
        assertTrue(SurfaceVentLocator.upperSprayAmount() > SurfaceVentLocator.lowerSprayAmount());
        assertTrue(SurfaceVentLocator.upperSprayAmount() < 8);
        assertTrue(SurfaceVentLocator.lowerSprayAmount() < 8);
    }
}
