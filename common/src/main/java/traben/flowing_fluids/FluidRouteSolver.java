package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Arrays;

/**
 * Deterministic route-based fluid redistribution for connected equalizer candidates.
 *
 * This solver treats neighbouring cells as edges and moves bounded amounts from higher
 * hydraulic potential to lower potential. It preserves mass and only operates on the
 * candidate cells handed to the equalizer, so the old equalizer can remain the fallback.
 */
public final class FluidRouteSolver {
    private static final Direction[] ROUTE_DIRECTIONS = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    private FluidRouteSolver() {
    }

    public static Result solve(int[] amountsInternal, int[] yLevels, long[] positionKeys,
                               int iterations, int maxTransferPerEdge, float downhillBias) {
        if (amountsInternal == null || yLevels == null || positionKeys == null
                || amountsInternal.length != yLevels.length
                || amountsInternal.length != positionKeys.length) {
            throw new IllegalArgumentException("Route solver inputs must be non-null arrays of the same length");
        }
        int[] result = Arrays.copyOf(amountsInternal, amountsInternal.length);
        if (result.length <= 1) {
            return new Result(result, 0);
        }

        Long2IntOpenHashMap indexByPos = new Long2IntOpenHashMap(result.length);
        indexByPos.defaultReturnValue(-1);
        for (int i = 0; i < positionKeys.length; i++) {
            indexByPos.put(positionKeys[i], i);
        }

        int maxInternal = FluidAmountConverter.getMaxInternal();
        int passes = Math.max(1, Math.min(16, iterations));
        int edgeLimit = Math.max(1, Math.min(maxInternal, maxTransferPerEdge));
        float dropBias = Math.max(1.0f, Math.min(8.0f, downhillBias));
        int movedTotal = 0;

        for (int pass = 0; pass < passes; pass++) {
            int movedThisPass = 0;
            int[] deltas = new int[result.length];

            for (int source = 0; source < result.length; source++) {
                int sourceAvailable = result[source] + deltas[source];
                if (sourceAvailable <= 0) {
                    continue;
                }

                int sourceX = BlockPos.getX(positionKeys[source]);
                int sourceY = BlockPos.getY(positionKeys[source]);
                int sourceZ = BlockPos.getZ(positionKeys[source]);
                int sourcePotential = potential(yLevels[source], sourceAvailable, maxInternal);

                for (Direction direction : ROUTE_DIRECTIONS) {
                    if (sourceAvailable <= 0) {
                        break;
                    }
                    int target = indexByPos.get(BlockPos.asLong(
                        sourceX + direction.getStepX(),
                        sourceY + direction.getStepY(),
                        sourceZ + direction.getStepZ()
                    ));
                    if (target < 0 || target == source) {
                        continue;
                    }

                    int targetAmount = result[target] + deltas[target];
                    if (targetAmount >= maxInternal) {
                        continue;
                    }
                    int targetPotential = potential(yLevels[target], targetAmount, maxInternal);
                    int potentialDiff = sourcePotential - targetPotential;
                    if (potentialDiff <= 1) {
                        continue;
                    }

                    int transfer = Math.max(1, potentialDiff / (maxInternal * 2));
                    if (direction == Direction.DOWN) {
                        transfer = Math.max(transfer, Math.round(transfer * dropBias));
                    } else if (direction == Direction.UP) {
                        transfer = Math.max(0, transfer / 2);
                    }
                    transfer = Math.min(transfer, edgeLimit);
                    transfer = Math.min(transfer, sourceAvailable);
                    transfer = Math.min(transfer, maxInternal - targetAmount);
                    if (transfer <= 0) {
                        continue;
                    }

                    deltas[source] -= transfer;
                    deltas[target] += transfer;
                    sourceAvailable -= transfer;
                    sourcePotential = potential(yLevels[source], sourceAvailable, maxInternal);
                    movedThisPass += transfer;
                }
            }

            if (movedThisPass <= 0) {
                break;
            }
            for (int i = 0; i < result.length; i++) {
                result[i] = Math.max(0, Math.min(maxInternal, result[i] + deltas[i]));
            }
            movedTotal += movedThisPass;
        }

        return new Result(result, movedTotal);
    }

    private static int potential(int yLevel, int amount, int maxInternal) {
        return yLevel * maxInternal + Math.max(0, Math.min(maxInternal, amount));
    }

    public record Result(int[] amountsInternal, int movedInternalAmount) {
    }
}
