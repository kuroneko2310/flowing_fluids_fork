package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;

final class SpringTickScheduler {
    private SpringTickScheduler() {
    }

    static void schedule(LevelAccessor level, BlockPos pos, Block springBlock, int delay) {
        if (level == null || pos == null || springBlock == null) {
            return;
        }
        BlockPos scheduledPos = pos.immutable();
        int safeDelay = Math.max(1, delay);
        if (level instanceof Level lvl && lvl.isClientSide()) {
            return;
        }
        if (level.getBlockTicks().hasScheduledTick(scheduledPos, springBlock)) {
            return;
        }
        level.scheduleTick(scheduledPos, springBlock, safeDelay);
    }
}
