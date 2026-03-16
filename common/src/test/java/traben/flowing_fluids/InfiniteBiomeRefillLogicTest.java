package traben.flowing_fluids;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfiniteBiomeRefillLogicTest {

    @Test
    void refillBandAllowsBelowSeaLevelByDefault() {
        assertTrue(FFFluidUtils.isWithinInfiniteBiomeRefillBand(62, 63, false));
        assertTrue(FFFluidUtils.isWithinInfiniteBiomeRefillBand(40, 63, false));
        assertTrue(FFFluidUtils.isWithinInfiniteBiomeRefillBand(63, 63, false));
        assertFalse(FFFluidUtils.isWithinInfiniteBiomeRefillBand(64, 63, false));
    }

    @Test
    void refillBandCanBeLimitedNearSeaLevel() {
        assertTrue(FFFluidUtils.isWithinInfiniteBiomeRefillBand(63, 63, true));
        assertTrue(FFFluidUtils.isWithinInfiniteBiomeRefillBand(62, 63, true));
        assertFalse(FFFluidUtils.isWithinInfiniteBiomeRefillBand(61, 63, true));
    }

    @Test
    void passiveRefillNeedsSupportButCanRecoverQuickly() {
        assertEquals(2, FFFluidUtils.classifyInfiniteBiomeRefillAmount(5, true, 3, 3, false, false));
        assertEquals(1, FFFluidUtils.classifyInfiniteBiomeRefillAmount(6, true, 2, 1, false, false));
        assertEquals(0, FFFluidUtils.classifyInfiniteBiomeRefillAmount(6, false, 1, 0, false, false));
    }

    @Test
    void aggressiveRefillFullyRestoresStronglySupportedPools() {
        assertEquals(5, FFFluidUtils.classifyInfiniteBiomeRefillAmount(3, true, 4, 3, false, true));
        assertEquals(2, FFFluidUtils.classifyInfiniteBiomeRefillAmount(4, true, 2, 1, false, true));
        assertEquals(0, FFFluidUtils.classifyInfiniteBiomeRefillAmount(4, false, 1, 0, false, true));
    }
}
