package traben.flowing_fluids.rain;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RainWaterSystemRegressionTest {

    @Test
    void cappedChunkSamplingStillTouchesMultiplePlayerRings() {
        long[] chunks = new long[12];
        int[] rings = new int[12];
        int cursor = 0;
        for (int ring = 0; ring <= 3; ring++) {
            for (int i = 0; i < 3; i++) {
                chunks[cursor] = ChunkPos.asLong(ring * 10 + i, ring * 10 - i);
                rings[cursor] = ring;
                cursor++;
            }
        }

        long[] selected = RainMath.selectDistributedChunkSample(chunks, rings, 6, 200L, 12345L);
        Set<Integer> selectedRings = new HashSet<>();
        for (long selectedChunk : selected) {
            selectedRings.add(findRing(selectedChunk, chunks, rings));
        }

        assertEquals(6, selected.length);
        assertTrue(selectedRings.contains(0));
        assertTrue(selectedRings.contains(1));
        assertTrue(selectedRings.contains(2));
        assertTrue(selectedRings.contains(3));
    }

    @Test
    void cappedChunkSamplingRotatesAcrossStormSlices() {
        long[] chunks = new long[15];
        int[] rings = new int[15];
        int cursor = 0;
        for (int ring = 0; ring <= 4; ring++) {
            for (int i = 0; i < 3; i++) {
                chunks[cursor] = ChunkPos.asLong(ring * 7 + i, ring * 5 - i);
                rings[cursor] = ring;
                cursor++;
            }
        }

        long[] first = RainMath.selectDistributedChunkSample(chunks, rings, 7, 320L, 9876L);
        long[] second = RainMath.selectDistributedChunkSample(chunks, rings, 7, 321L, 9876L);

        assertFalse(Arrays.equals(first, second));
    }

    private static int findRing(long packedChunk, long[] chunks, int[] rings) {
        for (int i = 0; i < chunks.length; i++) {
            if (chunks[i] == packedChunk) {
                return rings[i];
            }
        }
        throw new AssertionError("Unknown chunk " + packedChunk);
    }
}
