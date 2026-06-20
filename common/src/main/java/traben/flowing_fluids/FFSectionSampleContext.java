package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import traben.flowing_fluids.optimization.WaterFlowProfile;

public final class FFSectionSampleContext {
    private Level level;
    private final Long2ObjectOpenHashMap<CachedWaterProfile> waterProfiles = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<CellSnapshot> cells = new Long2ObjectOpenHashMap<>();
    private long gameTime = Long.MIN_VALUE;
    private long lastProfilePos = Long.MIN_VALUE;
    private Fluid lastProfileFluid;
    private int lastProfileAmount = Integer.MIN_VALUE;
    private WaterFlowProfile lastProfileValue;

    public void begin(Level level) {
        this.level = level;
        this.waterProfiles.clear();
        this.cells.clear();
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
            this.waterProfiles.clear();
            this.cells.clear();
            this.gameTime = currentGameTime;
            clearLastProfile();
        }
    }

    public CellSnapshot cell(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return CellSnapshot.EMPTY;
        }
        ensureFresh(level);
        long key = pos.asLong();
        CellSnapshot cached = cachedCell(key);
        if (cached != null) {
            return cached;
        }

        BlockState blockState = level.getBlockState(pos);
        FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, pos, blockState);
        CellSnapshot snapshot = new CellSnapshot(blockState, fluidState);
        rememberCell(key, snapshot);
        return snapshot;
    }

    CellSnapshot cachedCell(long posKey) {
        return cells.get(posKey);
    }

    void rememberCell(long posKey, CellSnapshot snapshot) {
        cells.put(posKey, snapshot);
    }

    int cachedCellCount() {
        return cells.size();
    }

    public boolean hasCell(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        ensureFresh(level);
        return cells.containsKey(pos.asLong());
    }

    public int fluidAmountIfSame(Level level, BlockPos pos, Fluid fluid) {
        if (level == null || pos == null || fluid == null) {
            return 0;
        }
        CellSnapshot snapshot = cell(level, pos);
        return snapshot.fluidState().getType().isSame(fluid)
                ? snapshot.fluidState().getAmount()
                : 0;
    }

    public WaterFlowProfile waterProfile(Level level, BlockPos pos, FluidState fluidState, int amount) {
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
        WaterFlowProfile profile = WaterFlowProfile.analyze(level, pos, fluidState, amount, this);
        waterProfiles.put(key, new CachedWaterProfile(fluidType, amount, profile));
        rememberLastProfile(key, fluidType, amount, profile);
        return profile;
    }

    public void invalidate(BlockPos... positions) {
        if (positions == null || positions.length == 0) {
            return;
        }
        for (BlockPos pos : positions) {
            if (pos != null) {
                cells.remove(pos.asLong());
            }
        }
        waterProfiles.clear();
        clearLastProfile();
    }

    public record CellSnapshot(BlockState blockState, FluidState fluidState) {
        private static final CellSnapshot EMPTY = new CellSnapshot(null, null);
    }

    private record CachedWaterProfile(Fluid fluid, int amount, WaterFlowProfile profile) {
    }

    private void reset() {
        this.level = null;
        this.waterProfiles.clear();
        this.cells.clear();
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
