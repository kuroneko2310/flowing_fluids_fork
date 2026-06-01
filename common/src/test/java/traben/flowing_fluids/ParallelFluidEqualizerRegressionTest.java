package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelFluidEqualizerRegressionTest {

    @Test
    void sameComponentCanStillRegisterAcrossDifferentBuckets() {
        Long2ObjectOpenHashMap<Set<Integer>> seen = new Long2ObjectOpenHashMap<>();

        assertFalse(ParallelFluidEqualizer.shouldSkipComponentCandidate(seen, 1L, 42));
        assertFalse(ParallelFluidEqualizer.shouldSkipComponentCandidate(seen, 2L, 42));
    }

    @Test
    void sameComponentStillDeduplicatesInsideSingleBucket() {
        Long2ObjectOpenHashMap<Set<Integer>> seen = new Long2ObjectOpenHashMap<>();

        assertFalse(ParallelFluidEqualizer.shouldSkipComponentCandidate(seen, 7L, 42));
        assertTrue(ParallelFluidEqualizer.shouldSkipComponentCandidate(seen, 7L, 42));
    }

    @Test
    void freshSurgeCandidatesCanBeSkippedBeforeRepresentativeScan() {
        assertTrue(ParallelFluidEqualizer.shouldSkipQueuedSurgeCandidate(
            false,
            true,
            true,
            0.0f,
            FluidAmountConverter.toInternal(4)
        ));
        assertTrue(ParallelFluidEqualizer.shouldSkipQueuedSurgeCandidate(
            false,
            true,
            false,
            0.3f,
            FluidAmountConverter.toInternal(4)
        ));
        assertTrue(ParallelFluidEqualizer.shouldSkipQueuedSurgeCandidate(
            false,
            true,
            false,
            0.0f,
            FluidAmountConverter.toInternal(7)
        ));
        assertFalse(ParallelFluidEqualizer.shouldSkipQueuedSurgeCandidate(
            true,
            true,
            true,
            0.3f,
            FluidAmountConverter.toInternal(7)
        ));
        assertFalse(ParallelFluidEqualizer.shouldSkipQueuedSurgeCandidate(
            false,
            false,
            true,
            0.3f,
            FluidAmountConverter.toInternal(7)
        ));
    }

    @Test
    void broadSurfaceOrInletSurgesStillReachEqualizerPreparation() {
        assertFalse(ParallelFluidEqualizer.shouldSkipQueuedSurgeCandidate(
            false,
            true,
            false,
            0.0f,
            FluidAmountConverter.toInternal(7),
            true,
            false
        ));
        assertFalse(ParallelFluidEqualizer.shouldSkipQueuedSurgeCandidate(
            false,
            true,
            false,
            0.0f,
            FluidAmountConverter.toInternal(7),
            false,
            true
        ));
    }

    @Test
    void deferredSurgesWaitOnlyWhileStillMoving() {
        assertFalse(ParallelFluidEqualizer.shouldRequeueDeferredNow(10L, 11L, true, 0.0f));
        assertFalse(ParallelFluidEqualizer.shouldRequeueDeferredNow(10L, 11L, false, 0.3f));
        assertTrue(ParallelFluidEqualizer.shouldRequeueDeferredNow(10L, 11L, false, 0.0f));
        assertTrue(ParallelFluidEqualizer.shouldRequeueDeferredNow(10L, 14L, true, 0.0f));
    }
}
