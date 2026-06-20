package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import java.util.LinkedHashMap;

/**
 * Plans related fluid writes against one world snapshot and applies their cache effects once.
 */
public final class FluidMutationBatch {
    private final LevelAccessor level;
    private final LinkedHashMap<Long, Mutation> mutations = new LinkedHashMap<>();

    public FluidMutationBatch(LevelAccessor level) {
        if (level == null) {
            throw new IllegalArgumentException("Level is required");
        }
        this.level = level;
    }

    public FluidMutationBatch set(BlockPos pos, Fluid fluid, int expectedAmount, int newAmount) {
        if (pos == null || fluid == null) {
            throw new IllegalArgumentException("Position and fluid are required");
        }
        int expected = requireBlockAmount(expectedAmount);
        int target = requireBlockAmount(newAmount);
        long key = pos.asLong();
        Mutation existing = mutations.get(key);
        if (existing != null) {
            if (!existing.fluid().isSame(fluid) || existing.newAmount() != expected) {
                throw new IllegalArgumentException("Conflicting fluid mutation at " + pos);
            }
            if (existing.expectedAmount() == target) {
                mutations.remove(key);
            } else {
                mutations.put(key, new Mutation(existing.pos(), fluid, existing.expectedAmount(), target));
            }
        } else if (expected != target) {
            mutations.put(key, new Mutation(pos.immutable(), fluid, expected, target));
        }
        return this;
    }

    public FluidMutationBatch transfer(BlockPos sourcePos, int sourceBefore, int sourceAfter,
                                       BlockPos destinationPos, int destinationBefore, int destinationAfter,
                                       Fluid fluid) {
        if (sourcePos == null || destinationPos == null || sourcePos.equals(destinationPos)) {
            throw new IllegalArgumentException("A transfer requires two different positions");
        }
        if (!isMassConserved(sourceBefore, destinationBefore, sourceAfter, destinationAfter)) {
            throw new IllegalArgumentException("Fluid transfer must conserve block fluid amount");
        }
        set(sourcePos, fluid, sourceBefore, sourceAfter);
        set(destinationPos, fluid, destinationBefore, destinationAfter);
        return this;
    }

    public ApplyResult apply() {
        if (mutations.isEmpty()) {
            return new ApplyResult(true, 0, false);
        }
        for (Mutation mutation : mutations.values()) {
            if (!matchesExpectedState(mutation)) {
                return new ApplyResult(false, 0, false);
            }
        }

        int changed = 0;
        for (Mutation mutation : mutations.values()) {
            if (mutation.expectedAmount() != mutation.newAmount()) {
                changed++;
            }
        }
        if (changed == 0) {
            return new ApplyResult(true, 0, false);
        }

        boolean[] applied = {true};
        FFFluidUtils.runWithBulkFluidChanges(level, () -> {
            for (Mutation mutation : mutations.values()) {
                if (mutation.expectedAmount() == mutation.newAmount()) {
                    continue;
                }
                if (!FFFluidUtils.setFluidStateAtPosToNewAmount(
                        level, mutation.pos(), mutation.fluid(), mutation.newAmount())) {
                    applied[0] = false;
                    break;
                }
            }
        });
        return new ApplyResult(applied[0], applied[0] ? changed : 0, true);
    }

    public int size() {
        return mutations.size();
    }

    public int netAmountDelta(Fluid fluid) {
        if (fluid == null) {
            return 0;
        }
        int delta = 0;
        for (Mutation mutation : mutations.values()) {
            if (mutation.fluid().isSame(fluid)) {
                delta += mutation.newAmount() - mutation.expectedAmount();
            }
        }
        return delta;
    }

    static boolean isMassConserved(int sourceBefore, int destinationBefore,
                                   int sourceAfter, int destinationAfter) {
        if (!isBlockAmount(sourceBefore) || !isBlockAmount(destinationBefore)
                || !isBlockAmount(sourceAfter) || !isBlockAmount(destinationAfter)) {
            return false;
        }
        return sourceBefore + destinationBefore == sourceAfter + destinationAfter;
    }

    private boolean matchesExpectedState(Mutation mutation) {
        BlockState blockState = level.getBlockState(mutation.pos());
        FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, mutation.pos(), blockState);
        if (mutation.expectedAmount() <= 0) {
            return fluidState.isEmpty() || fluidState.getAmount() <= 0;
        }
        return fluidState.getType().isSame(mutation.fluid())
                && fluidState.getAmount() == mutation.expectedAmount();
    }

    private static int requireBlockAmount(int amount) {
        if (!isBlockAmount(amount)) {
            throw new IllegalArgumentException("Block fluid amount must be between 0 and 8");
        }
        return amount;
    }

    private static boolean isBlockAmount(int amount) {
        return amount >= 0 && amount <= 8;
    }

    private record Mutation(BlockPos pos, Fluid fluid, int expectedAmount, int newAmount) {
    }

    public record ApplyResult(boolean applied, int changedCells, boolean writesStarted) {
    }
}
