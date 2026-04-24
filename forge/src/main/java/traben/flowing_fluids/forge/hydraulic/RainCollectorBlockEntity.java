package traben.flowing_fluids.forge.hydraulic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import traben.flowing_fluids.FlowingFluids;

public final class RainCollectorBlockEntity extends BlockEntity {
    private static final int[] ABSORB_RADIUS_STEPS = {16, 32, 64, 96, 120};
    private static final int DEFAULT_ABSORB_RADIUS = ABSORB_RADIUS_STEPS[0];
    private static final int ABSORB_ENERGY_COST_PER_BLOCK = 25;
    private static final int MAX_STORED_WATER = 512;
    private static final int FLUID_LEVEL_MB = Math.max(1, FluidType.BUCKET_VOLUME / 8);
    private static final int TANK_CAPACITY_MB = MAX_STORED_WATER * FLUID_LEVEL_MB;
    private static final int ENERGY_CAPACITY = 100_000;
    private static final int ENERGY_RECEIVE = 5_000;
    private static final int TICK_INTERVAL = 5;
    private static final int MAX_ABSORBING_RAIN_LEVELS_PER_TICK = 8;

    private final CollectorEnergyStorage energy = new CollectorEnergyStorage();
    private final LazyOptional<IEnergyStorage> energyCapability = LazyOptional.of(() -> energy);
    private final CollectorFluidTank fluidTank = new CollectorFluidTank();
    private final LazyOptional<IFluidHandler> fluidCapability = LazyOptional.of(() -> fluidTank);
    private int absorbRadius = DEFAULT_ABSORB_RADIUS;
    private boolean ff$registered;

    public RainCollectorBlockEntity(BlockPos pos, BlockState state) {
        super(ForgeHydraulicBlockRegistry.RAIN_COLLECTOR_BLOCK_ENTITY.get(), pos, state);
    }

    public static void tick(ServerLevel level, BlockPos pos, BlockState state, RainCollectorBlockEntity collector) {
        if (Math.floorMod(level.getGameTime() + pos.asLong(), TICK_INTERVAL) != 0L) {
            return;
        }
        collector.ff$tryCollectRain(level, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!ff$registered && level instanceof ServerLevel serverLevel) {
            RainCollectorRuntime.register(serverLevel, worldPosition, absorbRadius);
            ff$registered = true;
        }
    }

    @Override
    public void setRemoved() {
        if (ff$registered && level instanceof ServerLevel serverLevel) {
            RainCollectorRuntime.unregister(serverLevel, worldPosition);
            ff$registered = false;
        }
        energyCapability.invalidate();
        fluidCapability.invalidate();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (ff$registered && level instanceof ServerLevel serverLevel) {
            RainCollectorRuntime.unregister(serverLevel, worldPosition);
            ff$registered = false;
        }
        super.onChunkUnloaded();
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        energy.setEnergy(tag.getInt("Energy"));
        absorbRadius = sanitizeAbsorbRadius(tag.contains("AbsorbRadius") ? tag.getInt("AbsorbRadius") : DEFAULT_ABSORB_RADIUS);
        if (tag.contains("Tank")) {
            fluidTank.readFromNBT(tag.getCompound("Tank"));
        } else {
            int legacyStoredWater = sanitizeStoredWater(tag.getInt("StoredWater"));
            fluidTank.setFluid(new FluidStack(Fluids.WATER, legacyStoredWater * FLUID_LEVEL_MB));
        }
        ff$updateBlockWaterLevel();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("AbsorbRadius", absorbRadius);
        tag.put("Tank", fluidTank.writeToNBT(new CompoundTag()));
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable net.minecraft.core.Direction side) {
        if (capability == ForgeCapabilities.ENERGY) {
            return energyCapability.cast();
        }
        if (capability == ForgeCapabilities.FLUID_HANDLER) {
            return fluidCapability.cast();
        }
        return super.getCapability(capability, side);
    }

    boolean isAbsorbingMode() {
        return getBlockState().getBlock() instanceof RainCollectorBlock
            && getBlockState().getValue(RainCollectorBlock.ABSORBING);
    }

    boolean consumeEnergy(int amount) {
        return energy.consumeEnergy(amount);
    }

    int energyStored() {
        return energy.getEnergyStored();
    }

    int absorbRadius() {
        return absorbRadius;
    }

    int absorbEnergyCost() {
        return absorbRadius * ABSORB_ENERGY_COST_PER_BLOCK;
    }

    int cycleAbsorbRadius() {
        absorbRadius = nextAbsorbRadius(absorbRadius);
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            RainCollectorRuntime.register(serverLevel, worldPosition, absorbRadius);
        }
        return absorbRadius;
    }

    boolean canStoreCollectedWater() {
        return fluidTank.getFluidAmount() < fluidTank.getCapacity();
    }

    int addCollectedWater(int amount) {
        if (amount <= 0 || !canStoreCollectedWater()) {
            return 0;
        }
        int requestedMb = amount * FLUID_LEVEL_MB;
        int acceptedMb = fluidTank.fill(new FluidStack(Fluids.WATER, requestedMb), IFluidHandler.FluidAction.EXECUTE);
        return acceptedMb / FLUID_LEVEL_MB;
    }

    private void ff$tryCollectRain(ServerLevel level, BlockState state) {
        if (!RainCollectorBlock.canCollectRain(level, worldPosition)) {
            return;
        }
        if (!canStoreCollectedWater()) {
            return;
        }

        if (!state.getValue(RainCollectorBlock.ABSORBING)) {
            ff$tryCollectDirectRain(level);
            return;
        }

        ff$tryCollectAbsorbedRain(level);
    }

    private void ff$tryCollectDirectRain(ServerLevel level) {
        int amount = Math.min(8, Math.max(1, FlowingFluids.config.rainBaseWaterAmount));
        if (level.isThundering()) {
            amount = Math.min(8, amount + 1);
        }
        addCollectedWater(amount);
    }

    private void ff$tryCollectAbsorbedRain(ServerLevel level) {
        int energyCost = absorbEnergyCost();
        if (energy.getEnergyStored() < energyCost) {
            return;
        }

        int baseAmount = Math.max(1, FlowingFluids.config.rainBaseWaterAmount);
        int radiusBonus = Math.max(1, Math.min(MAX_ABSORBING_RAIN_LEVELS_PER_TICK, (absorbRadius + 15) / 16));
        int amount = Math.min(MAX_ABSORBING_RAIN_LEVELS_PER_TICK, baseAmount * radiusBonus);
        if (level.isThundering()) {
            amount = Math.min(MAX_ABSORBING_RAIN_LEVELS_PER_TICK, amount + 1);
        }

        int accepted = addCollectedWater(amount);
        if (accepted > 0) {
            consumeEnergy(energyCost);
        }
    }

    private void ff$updateBlockWaterLevel() {
        if (level == null) {
            return;
        }
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof RainCollectorBlock)) {
            return;
        }
        int storedMb = fluidTank.getFluidAmount();
        int displayLevel = storedMb <= 0
            ? 0
            : Math.max(1, Math.min(8, (storedMb * 8 + TANK_CAPACITY_MB - 1) / TANK_CAPACITY_MB));
        if (state.getValue(RainCollectorBlock.WATER_LEVEL) != displayLevel) {
            level.setBlock(worldPosition, state.setValue(RainCollectorBlock.WATER_LEVEL, displayLevel), 3);
        }
    }

    private static int nextAbsorbRadius(int currentRadius) {
        int sanitized = sanitizeAbsorbRadius(currentRadius);
        for (int i = 0; i < ABSORB_RADIUS_STEPS.length; i++) {
            if (ABSORB_RADIUS_STEPS[i] == sanitized) {
                return ABSORB_RADIUS_STEPS[(i + 1) % ABSORB_RADIUS_STEPS.length];
            }
        }
        return DEFAULT_ABSORB_RADIUS;
    }

    private static int sanitizeAbsorbRadius(int radius) {
        int selected = DEFAULT_ABSORB_RADIUS;
        int bestDistance = Integer.MAX_VALUE;
        for (int step : ABSORB_RADIUS_STEPS) {
            int distance = Math.abs(step - radius);
            if (distance < bestDistance) {
                selected = step;
                bestDistance = distance;
            }
        }
        return selected;
    }

    private static int sanitizeStoredWater(int amount) {
        return Math.max(0, Math.min(MAX_STORED_WATER, amount));
    }

    private final class CollectorFluidTank extends FluidTank {
        private CollectorFluidTank() {
            super(TANK_CAPACITY_MB);
        }

        @Override
        public boolean isFluidValid(FluidStack stack) {
            return !stack.isEmpty() && stack.getFluid().isSame(Fluids.WATER);
        }

        @Override
        protected void onContentsChanged() {
            setChanged();
            ff$updateBlockWaterLevel();
        }
    }

    private final class CollectorEnergyStorage extends EnergyStorage {
        private CollectorEnergyStorage() {
            super(ENERGY_CAPACITY, ENERGY_RECEIVE, 0);
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int received = super.receiveEnergy(maxReceive, simulate);
            if (received > 0 && !simulate) {
                setChanged();
            }
            return received;
        }

        private boolean consumeEnergy(int amount) {
            if (amount <= 0) {
                return true;
            }
            if (energy < amount) {
                return false;
            }
            energy -= amount;
            setChanged();
            return true;
        }

        private void setEnergy(int amount) {
            energy = Math.max(0, Math.min(ENERGY_CAPACITY, amount));
            setChanged();
        }
    }
}
