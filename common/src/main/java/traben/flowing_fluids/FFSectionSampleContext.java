package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class FFSectionSampleContext {
    private Level level;
    private FluidSectionDataCache cache;
    private int sampleReads;

    public void begin(Level level) {
        this.level = level;
        this.cache = null;
        this.sampleReads = 0;
    }

    public void end() {
        this.level = null;
        this.cache = null;
        this.sampleReads = 0;
    }

    public @Nullable FluidSectionDataCache sampleCache(Level level, int sampleThreshold) {
        if (this.level != level) {
            begin(level);
        }
        if (cache != null) {
            return cache;
        }
        sampleReads++;
        if (sampleReads < sampleThreshold) {
            return null;
        }
        cache = new FluidSectionDataCache(level, 8);
        return cache;
    }

    public void invalidate(BlockPos... positions) {
        if (cache == null) {
            return;
        }
        for (BlockPos pos : positions) {
            cache.invalidate(pos);
        }
    }
}
