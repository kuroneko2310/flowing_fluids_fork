package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import traben.flowing_fluids.optimization.WaterFlowProfile;

public final class FFSectionSampleContext {
    private Level level;
    private FluidSectionDataCache cache;
    private final Long2ObjectOpenHashMap<CachedWaterProfile> waterProfiles = new Long2ObjectOpenHashMap<>();
    private int sampleReads;
    private long gameTime = Long.MIN_VALUE;
    private long lastProfilePos = Long.MIN_VALUE;
    private Fluid lastProfileFluid;
    private int lastProfileAmount = Integer.MIN_VALUE;
    private WaterFlowProfile lastProfileValue;

    public void begin(Level level) {
        this.level = level;
        this.cache = null;
        this.sampleReads = 0;
        this.waterProfiles.clear();
        this.gameTime = level == null ? Long.MIN_VALUE : level.getGameTime();
        clearLastProfile();
    }

    public void end() {
        reset();
    }

    private void ensureFresh(Level level) {
        if (this.level != level) {
            begin(level);
            return;
        }
        long currentGameTime = level == null ? Long.MIN_VALUE : level.getGameTime();
        if (gameTime != currentGameTime) {
            this.cache = null;
            this.sampleReads = 0;
            this.waterProfiles.clear();
            this.gameTime = currentGameTime;
            clearLastProfile();
        }
    }

    public @Nullable FluidSectionDataCache sampleCache(Level level, int sampleThreshold) {
        ensureFresh(level);
        if (cache != null) {
            return cache;
        }
        sampleReads++;
        if (!shouldBuildSectionCache(sampleReads, sampleThreshold)) {
            return null;
        }
        cache = new FluidSectionDataCache(level, 8);
        return cache;
    }

    public WaterFlowProfile waterProfile(Level level, BlockPos pos, FluidState fluidState, int amount) {
        return waterProfile(level, pos, fluidState, amount, 0);
    }

    public WaterFlowProfile waterProfile(Level level, BlockPos pos, FluidState fluidState, int amount, int sampleThreshold) {
        if (level == null || pos == null || fluidState == null) {
            return WaterFlowProfile.analyze(level, pos, fluidState, amount);
        }
        ensureFresh(level);
        long key = pos.asLong();
        Fluid fluidType = fluidState.getType();
        if (lastProfileValue != null
                && lastProfilePos == key
                && lastProfileAmount == amount
                && lastProfileFluid != null
                && lastProfileFluid.isSame(fluidType)) {
            return lastProfileValue;
        }
        CachedWaterProfile cached = waterProfiles.get(key);
        if (cached != null && cached.amount == amount && cached.fluid.isSame(fluidType)) {
            rememberLastProfile(key, fluidType, amount, cached.profile);
            return cached.profile;
        }
        FluidSectionDataCache sectionCache = sampleThreshold > 0 ? sampleCache(level, sampleThreshold) : null;
        WaterFlowProfile profile = WaterFlowProfile.analyze(level, pos, fluidState, amount, sectionCache);
        waterProfiles.put(key, new CachedWaterProfile(fluidType, amount, profile));
        rememberLastProfile(key, fluidType, amount, profile);
        return profile;
    }

    public void invalidate(BlockPos... positions) {
        if (positions == null || positions.length == 0) {
            return;
        }
        // Local writes only invalidate the touched sections; keeping the rest of the
        // sample cache alive avoids rebuilding every later profile in the same tick.
        if (cache != null) {
            for (BlockPos pos : positions) {
                if (pos != null) {
                    cache.invalidate(pos);
                }
            }
        }
        waterProfiles.clear();
        clearLastProfile();
    }

    static boolean shouldBuildSectionCache(int sampleReads, int sampleThreshold) {
        if (sampleThreshold <= 0) {
            return sampleReads > 0;
        }
        return sampleReads >= sampleThreshold;
    }

    static boolean shouldBuildSectionCache(int sampleReads, int sampleThreshold, boolean dirtyTick) {
        return shouldBuildSectionCache(sampleReads, sampleThreshold);
    }

    private record CachedWaterProfile(Fluid fluid, int amount, WaterFlowProfile profile) {
    }

    private void reset() {
        this.level = null;
        this.cache = null;
        this.sampleReads = 0;
        this.waterProfiles.clear();
        this.gameTime = Long.MIN_VALUE;
        clearLastProfile();
    }

    private void rememberLastProfile(long posKey, Fluid fluidType, int amount, WaterFlowProfile profile) {
        this.lastProfilePos = posKey;
        this.lastProfileFluid = fluidType;
        this.lastProfileAmount = amount;
        this.lastProfileValue = profile;
    }

    private void clearLastProfile() {
        this.lastProfilePos = Long.MIN_VALUE;
        this.lastProfileFluid = null;
        this.lastProfileAmount = Integer.MIN_VALUE;
        this.lastProfileValue = null;
    }
}
