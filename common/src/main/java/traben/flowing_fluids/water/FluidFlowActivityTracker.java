package traben.flowing_fluids.water;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import traben.flowing_fluids.util.DimensionKey;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records where fluid actually moves, for machines that care about real flow rather than the vanilla level gradient.
 * <p>
 * Finite water can rest with neighbouring cells one level apart, so {@code FluidState#getFlow} reports a current for a
 * puddle that never moves. Watchers (Create water wheels) register the cells they sample; every block write that changes
 * the fluid in a watched cell is recorded with a decaying volume counter. Unwatched cells cost one volatile read per
 * block write, and nothing at all while no watcher exists.
 */
public final class FluidFlowActivityTracker {

    /** Cells no watcher has asked about for this long are forgotten. */
    private static final long WATCH_EXPIRY_TICKS = 1200L;
    /** Time constant of the moved-volume counter, in ticks. */
    public static final double RATE_TIME_CONSTANT_TICKS = 40.0D;

    private static final ConcurrentHashMap<DimensionKey, LevelActivity> BY_DIMENSION = new ConcurrentHashMap<>();
    private static final AtomicInteger WATCHED_TOTAL = new AtomicInteger();

    private FluidFlowActivityTracker() {
    }

    /**
     * Called from {@code Level#setBlock} after the chunk accepted the new state.
     */
    public static void onBlockChanged(final Level level, final BlockPos pos, final BlockState oldState, final BlockState newState) {
        if (WATCHED_TOTAL.get() == 0 || oldState == null || newState == null || !(level instanceof ServerLevel)) {
            return;
        }
        LevelActivity activity = BY_DIMENSION.get(DimensionKey.of(level));
        if (activity == null) {
            return;
        }
        FluidState oldFluid = oldState.getFluidState();
        FluidState newFluid = newState.getFluidState();
        if (oldFluid == newFluid) {
            return;
        }
        int moved = Math.abs(newFluid.getAmount() - oldFluid.getAmount());
        if (moved == 0 && oldFluid.getType().isSame(newFluid.getType())) {
            // same amount, only the falling flag or similar changed: still a sign of motion, count it lightly
            moved = 1;
        }
        activity.record(pos.asLong(), level.getGameTime(), Math.max(1, moved));
    }

    /**
     * Registers {@code pos} as watched (or refreshes it) and returns its activity record.
     */
    public static CellActivity watch(final Level level, final BlockPos pos) {
        LevelActivity activity = BY_DIMENSION.computeIfAbsent(DimensionKey.of(level), key -> new LevelActivity());
        return activity.watch(pos.asLong(), level.getGameTime());
    }

    public static void onLevelUnload(final Level level) {
        LevelActivity removed = BY_DIMENSION.remove(DimensionKey.of(level));
        if (removed != null) {
            removed.clear();
        }
    }

    public static int watchedCellCount() {
        return WATCHED_TOTAL.get();
    }

    private static final class LevelActivity {
        private final Long2ObjectMap<CellActivity> cells = new Long2ObjectOpenHashMap<>();
        private long lastPurgeTick = Long.MIN_VALUE;

        synchronized CellActivity watch(final long key, final long now) {
            CellActivity cell = cells.get(key);
            if (cell == null) {
                cell = new CellActivity();
                cells.put(key, cell);
                WATCHED_TOTAL.incrementAndGet();
            }
            cell.lastWatchedTick = now;
            if (lastPurgeTick == Long.MIN_VALUE || now - lastPurgeTick >= WATCH_EXPIRY_TICKS) {
                purge(now);
            }
            return cell;
        }

        synchronized void record(final long key, final long now, final int movedLevels) {
            CellActivity cell = cells.get(key);
            if (cell != null) {
                cell.record(now, movedLevels);
            }
        }

        private void purge(final long now) {
            lastPurgeTick = now;
            int before = cells.size();
            cells.values().removeIf(cell -> now - cell.lastWatchedTick > WATCH_EXPIRY_TICKS);
            WATCHED_TOTAL.addAndGet(cells.size() - before);
        }

        synchronized void clear() {
            WATCHED_TOTAL.addAndGet(-cells.size());
            cells.clear();
        }
    }

    public static final class CellActivity {
        private volatile long lastWatchedTick;
        private volatile long lastChangeTick = Long.MIN_VALUE;
        private double decayedVolume;
        private long decayedAtTick;

        synchronized void record(final long now, final int movedLevels) {
            decayedVolume = volumeAt(now) + movedLevels;
            decayedAtTick = now;
            lastChangeTick = now;
        }

        /** Ticks since the fluid in this cell last changed, or {@link Long#MAX_VALUE} if it never did while watched. */
        public long ticksSinceChange(final long now) {
            long last = lastChangeTick;
            return last == Long.MIN_VALUE ? Long.MAX_VALUE : Math.max(0L, now - last);
        }

        /** Approximate fluid levels moved through this cell per second. */
        public synchronized double levelsPerSecond(final long now) {
            return volumeAt(now) / RATE_TIME_CONSTANT_TICKS * 20.0D;
        }

        private double volumeAt(final long now) {
            if (decayedVolume <= 0.0D) {
                return 0.0D;
            }
            long elapsed = Math.max(0L, now - decayedAtTick);
            return decayedVolume * Math.exp(-elapsed / RATE_TIME_CONSTANT_TICKS);
        }
    }
}
