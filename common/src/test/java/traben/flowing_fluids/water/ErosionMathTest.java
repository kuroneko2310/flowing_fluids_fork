package traben.flowing_fluids.water;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErosionMathTest {
    @Test
    void stillWaterHasNoPower() {
        assertEquals(0.0, ErosionMath.streamPower(0.0, 1.0, 8, true));
        assertTrue(ErosionMath.canSettle(ErosionMath.streamPower(0.0, 0.0, 8, false)));
    }

    @Test
    void deeperFasterFloodedWaterIsStronger() {
        double shallow = ErosionMath.streamPower(1.0, 0.5, 2, false);
        double deep = ErosionMath.streamPower(1.0, 0.5, 8, false);
        double flood = ErosionMath.streamPower(1.0, 0.5, 8, true);
        assertTrue(shallow < deep && deep < flood);
    }

    @Test
    void materialsResistInShieldsOrder() {
        assertTrue(ErosionMath.criticalPower(ErosionMath.SAND) < ErosionMath.criticalPower(ErosionMath.SILT));
        assertTrue(ErosionMath.criticalPower(ErosionMath.SILT) < ErosionMath.criticalPower(ErosionMath.GRAVEL));
        assertTrue(ErosionMath.criticalPower(ErosionMath.GRAVEL) < ErosionMath.criticalPower(ErosionMath.CLAY));
    }

    @Test
    void erosionNeedsPowerAboveTheThreshold() {
        double critical = ErosionMath.criticalPower(ErosionMath.SILT);
        assertEquals(0.0, ErosionMath.erosionChance(critical, critical, 0.25));
        assertEquals(0.25, ErosionMath.erosionChance(critical * 2.0, critical, 0.25), 1.0E-9);
        assertEquals(1.0, ErosionMath.erosionChance(100.0, critical, 0.25), 1.0E-9);
    }
}
