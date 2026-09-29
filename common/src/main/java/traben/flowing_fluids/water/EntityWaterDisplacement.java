package traben.flowing_fluids.water;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Archimedes-style displacement for players standing or swimming in finite water.
 *
 * <p>The submerged part of a player's bounding box is measured in water levels (one block = 8 levels) and that much
 * water is <em>lent</em> to the local water surface, so a bath or small pond visibly rises a little when someone gets
 * in. Every level lent is recorded in a per-player ledger and taken back from the connected water when the player
 * rises or leaves, so the feature never creates or destroys water in the long run. If the lent water flowed away
 * (for example an overfull pool spilled over its rim) the ledger keeps the outstanding debt and repays it from the
 * same water body later, exactly like a real overflowing bathtub ends up lower after you step out.</p>
 *
 * <p>Design notes:
 * <ul>
 *     <li>Players are sampled every {@value #UPDATE_INTERVAL_TICKS} ticks, staggered deterministically by entity id so
 *     the work spreads evenly across ticks without any random number generator involved.</li>
 *     <li>The integer target uses a hysteresis band so a bobbing swimmer does not make the surface flicker.</li>
 *     <li>At most {@value #MAX_LEVEL_STEP_PER_UPDATE} levels move per update, so the rise and fall look like a wave
 *     instead of a jump.</li>
 *     <li>Lent water only enters genuinely open cells (never waterloggable blocks) and never spreads downwards.</li>
 * </ul>
 * In a narrow hole the lent water also rises around the player, which increases the submerged volume again. That
 * feedback converges (each level adds at most the player's share of a cell) and its fixed point is the physically
 * expected rise {@code V / (A_hole - A_player)}.</p>
 */
public final class EntityWaterDisplacement {
    static final int UPDATE_INTERVAL_TICKS = 4;
    static final int MAX_LEVEL_STEP_PER_UPDATE = 2;
    static final double HYSTERESIS_BAND = 0.75;
    static final int MAX_LENT_LEVELS = 16;
    private static final int SURFACE_SCAN_LIMIT = 24;
    private static final int PLACEMENT_SEARCH_DEPTH = 48;
    private static final int REPAY_SEARCH_DEPTH = 64;

    private static final ConcurrentHashMap<ResourceKey<Level>, Map<UUID, Ledger>> LEDGERS = new ConcurrentHashMap<>();

    private EntityWaterDisplacement() {
    }

    public static void onLevelTick(ServerLevel level) {
        Map<UUID, Ledger> ledgers = LEDGERS.get(level.dimension());
        boolean active = isActive(level);
        if (!active && (ledgers == null || ledgers.isEmpty())) {
            return;
        }

        long now = level.getGameTime();
        for (ServerPlayer player : level.players()) {
            UUID id = player.getUUID();
            if (Math.floorMod(now + player.getId(), UPDATE_INTERVAL_TICKS) != 0) {
                continue;
            }

            Ledger ledger = ledgers == null ? null : ledgers.get(id);
            double exactLevels = active && player.isAlive() && !player.isSpectator()
                    ? submergedLevels(level, player.getBoundingBox()) * FlowingFluids.config.entityWaterDisplacementScale
                    : 0.0;
            int lent = ledger == null ? 0 : ledger.lent;
            if (lent == 0 && exactLevels < HYSTERESIS_BAND) {
                // Dry players cost one short column scan and no allocation.
                continue;
            }

            int target = Math.min(MAX_LENT_LEVELS, quantizeWithHysteresis(exactLevels, lent));
            int step = stepToward(lent, target, MAX_LEVEL_STEP_PER_UPDATE);
            if (step == 0) {
                continue;
            }

            if (ledger == null) {
                ledgers = LEDGERS.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
                ledger = new Ledger();
                ledgers.put(id, ledger);
            }
            BlockPos surface = findWaterSurface(level, player.getBoundingBox());
            if (step > 0) {
                if (surface != null) {
                    ledger.lent += lend(level, surface, step);
                    ledger.anchor = surface.asLong();
                }
            } else {
                ledger.lent -= repay(level, surface, ledger, -step);
            }
            if (ledger.lent <= 0) {
                ledgers.remove(id);
            }
        }

        if (ledgers != null && !ledgers.isEmpty() && Math.floorMod(now, UPDATE_INTERVAL_TICKS) == 0) {
            repayAbsentPlayers(level, ledgers);
        }
    }

    public static void clearDimension(Level level) {
        if (level != null) {
            LEDGERS.remove(level.dimension());
        }
    }

    private static boolean isActive(ServerLevel level) {
        return FlowingFluids.config.enableEntityWaterDisplacement
                && FlowingFluids.config.entityWaterDisplacementScale > 0.0f
                && FlowingFluids.config.isWaterAllowed()
                && !FlowingFluids.config.isDimensionExcluded(level);
    }

    /**
     * Players who logged out, died and respawned elsewhere or changed dimension still owe their lent water here.
     */
    private static void repayAbsentPlayers(ServerLevel level, Map<UUID, Ledger> ledgers) {
        Set<UUID> present = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            present.add(player.getUUID());
        }
        Iterator<Map.Entry<UUID, Ledger>> iterator = ledgers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Ledger> entry = iterator.next();
            if (present.contains(entry.getKey())) {
                continue;
            }
            Ledger ledger = entry.getValue();
            ledger.lent -= repay(level, null, ledger, Math.min(ledger.lent, MAX_LEVEL_STEP_PER_UPDATE * 2));
            if (ledger.lent <= 0) {
                iterator.remove();
            }
        }
    }

    private static int lend(ServerLevel level, BlockPos surface, int amount) {
        var placement = FFFluidUtils.placeConnectedFluidAmountAndPlaceAction(
                level, surface, amount, Fluids.WATER, PLACEMENT_SEARCH_DEPTH, true, false, true);
        int placed = amount - placement.first();
        if (placed > 0 && placement.second() != null) {
            placement.second().run();
            AdaptiveTickScheduler.scheduleFluidTick(level, surface, Fluids.WATER, 1);
            return placed;
        }
        return 0;
    }

    private static int repay(ServerLevel level, BlockPos surface, Ledger ledger, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int repaid = 0;
        if (surface != null) {
            repaid = collect(level, surface, amount);
        }
        if (repaid < amount && ledger.anchor != Long.MIN_VALUE) {
            BlockPos anchor = BlockPos.of(ledger.anchor);
            if (level.isLoaded(anchor)) {
                BlockPos anchorSurface = findFirstWaterBelow(level, anchor.above(), SURFACE_SCAN_LIMIT);
                if (anchorSurface != null) {
                    repaid += collect(level, anchorSurface, amount - repaid);
                }
            }
        }
        return repaid;
    }

    private static int collect(ServerLevel level, BlockPos from, int amount) {
        int collected = FFFluidUtils.collectConnectedFluidAmountAndRemove(level, from, 1, amount, Fluids.WATER);
        if (collected > 0) {
            AdaptiveTickScheduler.scheduleFluidTick(level, from, Fluids.WATER, 1);
        }
        return Math.max(0, collected);
    }

    /**
     * Submerged volume of {@code box} expressed in water levels (8 levels = one full block).
     */
    static double submergedLevels(Level level, AABB box) {
        int minX = Mth.floor(box.minX);
        int maxX = Mth.floor(box.maxX - 1.0E-7);
        int minY = Mth.floor(box.minY);
        int maxY = Mth.floor(box.maxY - 1.0E-7);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.floor(box.maxZ - 1.0E-7);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        double volume = 0.0;
        for (int x = minX; x <= maxX; x++) {
            double overlapX = overlapLength(box.minX, box.maxX, x, x + 1.0);
            for (int z = minZ; z <= maxZ; z++) {
                double overlapXZ = overlapX * overlapLength(box.minZ, box.maxZ, z, z + 1.0);
                if (overlapXZ <= 0.0) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    FluidState fluid = FFFluidUtils.getEffectiveFluidState(level, cursor);
                    if (!fluid.is(FluidTags.WATER) || fluid.getAmount() <= 0) {
                        continue;
                    }
                    cursor.setY(y + 1);
                    boolean coveredAbove = FFFluidUtils.getEffectiveFluidState(level, cursor).is(FluidTags.WATER);
                    double surfaceY = y + (coveredAbove ? 1.0 : fluid.getAmount() / 8.0);
                    volume += overlapXZ * overlapLength(box.minY, box.maxY, y, surfaceY);
                }
            }
        }
        return volume * 8.0;
    }

    private static BlockPos findWaterSurface(Level level, AABB box) {
        int x = Mth.floor((box.minX + box.maxX) * 0.5);
        int z = Mth.floor((box.minZ + box.maxZ) * 0.5);
        return findColumnSurface(level, x, z, Mth.floor(box.minY), Mth.floor(box.maxY) + SURFACE_SCAN_LIMIT);
    }

    /**
     * Highest water cell of the first water run found scanning up from {@code fromY}, or {@code null}.
     */
    private static BlockPos findColumnSurface(Level level, int x, int z, int fromY, int toY) {
        int bottom = Math.max(level.getMinBuildHeight(), fromY);
        int top = Math.min(level.getMaxBuildHeight() - 1, toY);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, bottom, z);
        int surfaceY = Integer.MIN_VALUE;
        for (int y = bottom; y <= top; y++) {
            cursor.setY(y);
            FluidState fluid = FFFluidUtils.getEffectiveFluidState(level, cursor);
            if (fluid.is(FluidTags.WATER) && fluid.getAmount() > 0) {
                surfaceY = y;
            } else if (surfaceY != Integer.MIN_VALUE) {
                break;
            }
        }
        return surfaceY == Integer.MIN_VALUE ? null : new BlockPos(x, surfaceY, z);
    }

    private static BlockPos findFirstWaterBelow(Level level, BlockPos start, int maxDepth) {
        BlockPos.MutableBlockPos cursor = start.mutable();
        int bottom = Math.max(level.getMinBuildHeight(), start.getY() - maxDepth);
        for (int y = start.getY(); y >= bottom; y--) {
            cursor.setY(y);
            FluidState fluid = FFFluidUtils.getEffectiveFluidState(level, cursor);
            if (fluid.is(FluidTags.WATER) && fluid.getAmount() > 0) {
                return cursor.immutable();
            }
        }
        return null;
    }

    static double overlapLength(double minA, double maxA, double minB, double maxB) {
        return Math.max(0.0, Math.min(maxA, maxB) - Math.max(minA, minB));
    }

    /**
     * Rounds {@code exact} to an integer level count, but keeps {@code current} while the exact value stays within the
     * hysteresis band around it. This removes flicker from small up and down motion.
     */
    static int quantizeWithHysteresis(double exact, int current) {
        if (!(exact > 0.0)) {
            return 0;
        }
        if (Math.abs(exact - current) < HYSTERESIS_BAND) {
            return current;
        }
        return (int) Math.round(exact);
    }

    static int stepToward(int current, int target, int maxStep) {
        return Mth.clamp(target - current, -maxStep, maxStep);
    }

    private static final class Ledger {
        private int lent;
        private long anchor = Long.MIN_VALUE;
    }
}
