package traben.flowing_fluids.rain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Internal hook used by the rain system to delegate the actual fluid placement to the Flowing Fluids core.
 *
 * The rain logic is responsible only for deciding where water should be added and by how much. The core API
 * handles how that amount is merged with existing fluid at the position.
 */
public interface RainWaterApi {

    /**
     * Add the given amount of water (1-8) to the position. Implementations are responsible for routing the call
     * to the Flowing Fluids core in a thread-safe manner.
     */
    void addRainWater(ServerLevel level, BlockPos pos, int amount);
}

