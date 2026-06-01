package traben.flowing_fluids;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FFSectionSampleContextTest {

    @Test
    void sectionCacheBuildsAfterThresholdWhenTickIsReadOnly() {
        assertFalse(FFSectionSampleContext.shouldBuildSectionCache(23, 24));
        assertTrue(FFSectionSampleContext.shouldBuildSectionCache(24, 24));
    }

    @Test
    void sectionCacheThresholdStillBuildsAfterLocalWriteCompatibilityCall() {
        assertTrue(FFSectionSampleContext.shouldBuildSectionCache(40, 24, true));
    }

    @Test
    void zeroThresholdBuildsOnFirstRead() {
        assertTrue(FFSectionSampleContext.shouldBuildSectionCache(1, 0));
    }

    @Test
    void compatibilityOverloadMatchesPrimaryRule() {
        assertEquals(
            FFSectionSampleContext.shouldBuildSectionCache(1, 0),
            FFSectionSampleContext.shouldBuildSectionCache(1, 0, true)
        );
    }
}
