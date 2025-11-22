package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.FluidState;

import java.util.*;

/**
 * Cost-field based pathfinding for natural fluid flow.
 *
 * Instead of simple "horizontal → up" displacement, fluid finds
 * the minimum cost path through the environment.
 *
 * Cost values:
 * - Air: 1.0 (easy to flow into)
 * - Same fluid: 2.0 (harder than air, can push existing fluid)
 * - Downward slope: 0.5 (gravity assist)
 * - Upward slope: 3.0-4.0 (against gravity)
 * - Solid block: ∞ (impassable)
 *
 * Benefits:
 * - Fluid flows along slopes naturally
 * - Natural branching and splitting
 * - Follows terrain contours
 * - Fills small grooves and channels
 */
public class FluidCostField {

    private static final float COST_AIR = 1.0f;
    private static final float COST_SAME_FLUID = 2.0f;
    private static final float COST_DOWNSLOPE = 0.5f;
    private static final float COST_UPSLOPE = 3.5f;
    private static final float COST_IMPASSABLE = Float.POSITIVE_INFINITY;

    // Maximum search distance for pathfinding
    private static final int MAX_SEARCH_DISTANCE = 16;

    // Maximum nodes to explore (prevents infinite loops)
    private static final int MAX_SEARCH_NODES = 1000;

    /**
     * Finds the lowest cost path for fluid displacement.
     *
     * FIXED: Proper search range and PriorityQueue duplicate handling.
     *
     * @param level Block getter for world access
     * @param source Source position where fluid is being displaced from
     * @param fluidAmount Amount of fluid to displace (0-255)
     * @return List of positions representing the path, or empty if no path found
     */
    public static List<BlockPos> findLowestCostPath(BlockGetter level, BlockPos source, int fluidAmount) {
        // Priority queue ordered by total path cost
        PriorityQueue<PathNode> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.totalCost));
        Map<BlockPos, PathNode> visited = new HashMap<>();
        Set<BlockPos> inQueue = new HashSet<>(); // Track what's in the queue

        // Start at source
        PathNode startNode = new PathNode(source, null, 0.0f, 0);
        openSet.add(startNode);
        visited.put(source, startNode);
        inQueue.add(source);

        PathNode bestNode = null;
        float bestScore = Float.POSITIVE_INFINITY;
        int nodesExplored = 0;

        while (!openSet.isEmpty() && nodesExplored < MAX_SEARCH_NODES) {
            PathNode current = openSet.poll();
            inQueue.remove(current.pos);
            nodesExplored++;

            // Skip if we've found a better path to this position already
            PathNode visitedNode = visited.get(current.pos);
            if (visitedNode != null && visitedNode.totalCost < current.totalCost) {
                continue;
            }

            // Check if this position can accept the fluid
            if (canAcceptFluid(level, current.pos, source, fluidAmount)) {
                // Calculate score (lower is better)
                // Prefer positions that are:
                // 1. Low cost to reach
                // 2. Below source (negative Y difference)
                float score = current.totalCost - (source.getY() - current.pos.getY()) * 0.5f;

                if (score < bestScore) {
                    bestScore = score;
                    bestNode = current;
                }

                // If we found a good downward path, prefer it
                if (current.pos.getY() < source.getY()) {
                    break;
                }
            }

            // Explore neighbors
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = current.pos.relative(dir);

                // Skip if too far from source
                if (neighborPos.distManhattan(source) > MAX_SEARCH_DISTANCE) {
                    continue;
                }

                // Calculate movement cost
                float movementCost = calculateMovementCost(level, current.pos, neighborPos, dir);

                // Skip impassable blocks
                if (movementCost == COST_IMPASSABLE) {
                    continue;
                }

                float newCost = current.totalCost + movementCost;

                // Check if this is a better path to neighbor
                PathNode existingNode = visited.get(neighborPos);
                if (existingNode == null || newCost < existingNode.totalCost) {
                    // Remove old node from queue if present
                    if (existingNode != null && inQueue.contains(neighborPos)) {
                        openSet.remove(existingNode);
                        inQueue.remove(neighborPos);
                    }

                    PathNode neighborNode = new PathNode(neighborPos, current, newCost, current.depth + 1);
                    visited.put(neighborPos, neighborNode);
                    openSet.add(neighborNode);
                    inQueue.add(neighborPos);
                }
            }
        }

        // Reconstruct path
        if (bestNode != null) {
            return reconstructPath(bestNode);
        }

        return Collections.emptyList();
    }

    /**
     * Calculates the cost to move from one position to another.
     */
    private static float calculateMovementCost(BlockGetter level, BlockPos from, BlockPos to, Direction direction) {
        BlockState toState = level.getBlockState(to);
        FluidState toFluid = level.getFluidState(to);

        // Check if target is solid (impassable)
        if (toState.isSolid() && toFluid.isEmpty()) {
            return COST_IMPASSABLE;
        }

        float baseCost;

        // Determine base cost by block type
        if (toFluid.isEmpty()) {
            // Air or replaceable block
            baseCost = COST_AIR;
        } else {
            // Already contains fluid
            baseCost = COST_SAME_FLUID;
        }

        // Adjust cost based on direction (gravity)
        if (direction == Direction.DOWN) {
            // Downward is easiest (gravity assist)
            baseCost *= COST_DOWNSLOPE;
        } else if (direction == Direction.UP) {
            // Upward is hardest (against gravity)
            baseCost *= COST_UPSLOPE / COST_DOWNSLOPE;
        }

        // Check for downward slope in horizontal directions
        if (direction.getAxis().isHorizontal()) {
            BlockPos below = to.below();
            BlockState belowState = level.getBlockState(below);
            FluidState belowFluid = level.getFluidState(below);

            // If there's space below, this is a downslope (lower cost)
            if (!belowState.isSolid() || !belowFluid.isEmpty()) {
                baseCost *= 0.7f; // Reduce cost for downslope
            }
        }

        return baseCost;
    }

    /**
     * Checks if a position can accept displaced fluid.
     */
    private static boolean canAcceptFluid(BlockGetter level, BlockPos pos, BlockPos source, int fluidAmount) {
        if (pos.equals(source)) {
            return false; // Can't displace to source
        }

        BlockState state = level.getBlockState(pos);
        FluidState fluidState = level.getFluidState(pos);

        // Can accept if air or replaceable
        if (state.isAir() || state.canBeReplaced()) {
            return true;
        }

        // Can accept if already has fluid (merge)
        if (!fluidState.isEmpty()) {
            int maxAmount = FluidAmountConverter.getMaxInternal();
            if (level instanceof LevelAccessor levelAccessor) {
                int existingAmount = FluidSpatialGrid.getFluidAmount(levelAccessor, pos);
                return existingAmount + fluidAmount <= maxAmount;
            }
            return true; // Without level context, assume merge is possible
        }

        return false;
    }

    /**
     * Reconstructs the path from a node back to the source.
     */
    private static List<BlockPos> reconstructPath(PathNode endNode) {
        List<BlockPos> path = new ArrayList<>();
        PathNode current = endNode;

        while (current != null) {
            path.add(current.pos);
            current = current.parent;
        }

        Collections.reverse(path);
        return path;
    }

    /**
     * Represents a node in the pathfinding search.
     */
    private static class PathNode {
        final BlockPos pos;
        final PathNode parent;
        final float totalCost;
        final int depth;

        PathNode(BlockPos pos, PathNode parent, float totalCost, int depth) {
            this.pos = pos;
            this.parent = parent;
            this.totalCost = totalCost;
            this.depth = depth;
        }
    }

    /**
     * Displaces fluid along a minimum cost path.
     *
     * @param level Block getter
     * @param source Source position
     * @param fluidAmount Amount to displace (0-255)
     * @return Position where fluid was placed, or null if failed
     */
    public static BlockPos displaceFluid(BlockGetter level, BlockPos source, int fluidAmount) {
        List<BlockPos> path = findLowestCostPath(level, source, fluidAmount);

        if (path.isEmpty()) {
            return null;
        }

        // Return the destination (last position in path)
        return path.get(path.size() - 1);
    }

    /**
     * Gets the direction toward the lowest cost adjacent position.
     * Used for quick flow direction determination.
     *
     * @return Direction to flow, or null if no valid direction
     */
    public static Direction getLowestCostDirection(BlockGetter level, BlockPos pos) {
        Direction bestDir = null;
        float lowestCost = Float.POSITIVE_INFINITY;

        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            float cost = calculateMovementCost(level, pos, neighborPos, dir);

            if (cost < lowestCost && cost < COST_IMPASSABLE) {
                lowestCost = cost;
                bestDir = dir;
            }
        }

        return bestDir;
    }
}
