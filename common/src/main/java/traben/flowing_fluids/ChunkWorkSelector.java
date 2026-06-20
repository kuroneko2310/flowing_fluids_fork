package traben.flowing_fluids;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Selects a bounded, deterministic sample of chunk work without starving distant player rings.
 */
public final class ChunkWorkSelector {
    private ChunkWorkSelector() {
    }

    public static long[] selectDistributed(long[] chunkPositions,
                                           int[] nearestPlayerRings,
                                           int maxChunks,
                                           long timeSlice,
                                           long seedSalt) {
        if (chunkPositions.length != nearestPlayerRings.length) {
            throw new IllegalArgumentException("Chunk position and ring arrays must be the same length.");
        }
        if (maxChunks <= 0 || chunkPositions.length <= maxChunks) {
            return Arrays.copyOf(chunkPositions, chunkPositions.length);
        }

        int maxRing = 0;
        for (int ring : nearestPlayerRings) {
            maxRing = Math.max(maxRing, Math.max(0, ring));
        }

        @SuppressWarnings("unchecked")
        List<ChunkSelectionData>[] buckets = new List[maxRing + 1];
        for (int i = 0; i < chunkPositions.length; i++) {
            int ring = Math.max(0, nearestPlayerRings[i]);
            if (buckets[ring] == null) {
                buckets[ring] = new ArrayList<>();
            }

            long packed = chunkPositions[i];
            buckets[ring].add(new ChunkSelectionData(
                    packed,
                    computeSelectionOrder(timeSlice, ChunkPos.getX(packed), ChunkPos.getZ(packed), ring, seedSalt)
            ));
        }

        for (List<ChunkSelectionData> bucket : buckets) {
            if (bucket != null && bucket.size() > 1) {
                bucket.sort((left, right) -> Long.compare(right.selectionOrder(), left.selectionOrder()));
            }
        }

        int ringCount = buckets.length;
        int[] bucketIndices = new int[ringCount];
        long[] selected = new long[maxChunks];
        int selectedCount = 0;
        int startRing = chooseStartRing(timeSlice, ringCount, seedSalt);

        while (selectedCount < maxChunks) {
            boolean addedAny = false;
            for (int offset = 0; offset < ringCount && selectedCount < maxChunks; offset++) {
                int ring = Math.floorMod(startRing + offset, ringCount);
                List<ChunkSelectionData> bucket = buckets[ring];
                int bucketIndex = bucketIndices[ring];
                if (bucket == null || bucketIndex >= bucket.size()) {
                    continue;
                }

                selected[selectedCount++] = bucket.get(bucketIndex).packedPos();
                bucketIndices[ring] = bucketIndex + 1;
                addedAny = true;
            }
            if (!addedAny) {
                break;
            }
            startRing = Math.floorMod(startRing - 1, ringCount);
        }

        return selectedCount == selected.length ? selected : Arrays.copyOf(selected, selectedCount);
    }

    static long computeSelectionOrder(long timeSlice, int chunkX, int chunkZ, int playerRing, long seedSalt) {
        long mixed = seedSalt;
        mixed ^= 0xD6E8FEB86659FD93L * (timeSlice + 1L);
        mixed ^= 0x94D049BB133111EBL * (chunkX + 91L);
        mixed ^= 0xBF58476D1CE4E5B9L * (chunkZ - 53L);
        mixed ^= 0x9E3779B97F4A7C15L * (playerRing + 7L);
        return mix64(mixed);
    }

    static int chooseStartRing(long timeSlice, int ringCount, long seedSalt) {
        if (ringCount <= 1) {
            return 0;
        }
        long mixed = mix64(seedSalt ^ (0xC2B2AE3D27D4EB4FL * (timeSlice + 1L)));
        return Math.floorMod((int) (mixed ^ (mixed >>> 32)), ringCount);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private record ChunkSelectionData(long packedPos, long selectionOrder) {
    }
}