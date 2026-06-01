package traben.flowing_fluids.forge.hydraulic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
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
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.api.FlowingFluidsAPI;

public final class WaterAbsorberBlockEntity extends BlockEntity {
    private static final int[] ABSORB_RADIUS_STEPS = {16, 32, 64, 96, 120};
    private static final int DEFAULT_ABSORB_RADIUS = ABSORB_RADIUS_STEPS[0];
    private static final int MAX_STORED_WATER = 512;
    private static final int FLUID_LEVEL_MB = Math.max(1, FluidType.BUCKET_VOLUME / 8);
    private static final int TANK_CAPACITY_MB = MAX_STORED_WATER * FLUID_LEVEL_MB;
    private static final int ENERGY_CAPACITY = 200_000;
    private static final int ENERGY_RECEIVE = 10_000;
    private static final int ABSORB_ENERGY_COST_PER_LEVEL = 250;
    private static final int MAX_LEVELS_PER_OPERATION = 32;
    private static final int MAX_SCAN_POSITIONS_PER_TICK = 2048;
    private static final int TICK_INTERVAL = 5;

    private final FlowingFluidsAPI fluidsApi = FlowingFluidsAPI.getInstance(FlowingFluids.MOD_ID);
    private final AbsorberEnergyStorage energy = new AbsorberEnergyStorage();
    private final LazyOptional<IEnergyStorage> energyCapability = LazyOptional.of(() -> energy);
    private final AbsorberFluidTank fluidTank = new AbsorberFluidTank();
    private final LazyOptional<IFluidHandler> fluidCapability = LazyOptional.of(() -> fluidTank);
    private int absorbRadius = DEFAULT_ABSORB_RADIUS;
    private int scanIndex;

    public WaterAbsorberBlockEntity(BlockPos pos, BlockState state) {
        super(ForgeHydraulicBlockRegistry.WATER_ABSORBER_BLOCK_ENTITY.get(), pos, state);
    }

    public static void tick(ServerLevel level, BlockPos pos, BlockState state, WaterAbsorberBlockEntity absorber) {
        if (!state.getValue(WaterAbsorberBlock.ACTIVE)) {
            return;
        }
        if (Math.floorMod(level.getGameTime() + pos.asLong(), TICK_INTERVAL) != 0L) {
            return;
        }
        absorber.ff$tryAbsorbNearbyWater(level);
    }

    @Override
    public void setRemoved() {
        energyCapability.invalidate();
        fluidCapability.invalidate();
        super.setRemoved();
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        energy.setEnergy(tag.getInt("Energy"));
        absorbRadius = sanitizeAbsorbRadius(tag.contains("AbsorbRadius") ? tag.getInt("AbsorbRadius") : DEFAULT_ABSORB_RADIUS);
        scanIndex = Math.max(0, tag.getInt("ScanIndex"));
        if (tag.contains("Tank")) {
            fluidTank.readFromNBT(tag.getCompound("Tank"));
        }
        ff$updateBlockWaterLevel();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("AbsorbRadius", absorbRadius);
        tag.putInt("ScanIndex", scanIndex);
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

    int absorbEnergyCost() {
        return ABSORB_ENERGY_COST_PER_LEVEL;
    }

    int cycleAbsorbRadius() {
        absorbRadius = nextAbsorbRadius(absorbRadius);
        scanIndex = 0;
        setChanged();
        return absorbRadius;
    }

    private void ff$tryAbsorbNearbyWater(ServerLevel level) {
        int spaceLevels = (fluidTank.getCapacity() - fluidTank.getFluidAmount()) / FLUID_LEVEL_MB;
        if (spaceLevels <= 0 || energy.getEnergyStored() < ABSORB_ENERGY_COST_PER_LEVEL) {
            return;
        }

        BlockPos target = ff$findWaterTarget(level);
        if (target == null) {
            return;
        }

        int maxByTank = Math.min(MAX_LEVELS_PER_OPERATION, spaceLevels);
        int maxByEnergy = energy.getEnergyStored() / ABSORB_ENERGY_COST_PER_LEVEL;
        int maxLevels = Math.min(maxByTank, maxByEnergy);
        if (maxLevels <= 0) {
            return;
        }

        int removed = fluidsApi.removeFluidAmountFromPos(level, target, Fluids.WATER, 1, maxLevels);
        if (removed <= 0) {
            return;
        }

        int acceptedMb = fluidTank.fill(new FluidStack(Fluids.WATER, removed * FLUID_LEVEL_MB), IFluidHandler.FluidAction.EXECUTE);
        int acceptedLevels = acceptedMb / FLUID_LEVEL_MB;
        if (acceptedLevels > 0) {
            energy.consumeEnergy(acceptedLevels * ABSORB_ENERGY_COST_PER_LEVEL);
        }
    }

    @Nullable
    private BlockPos ff$findWaterTarget(ServerLevel level) {
        int radius = absorbRadius;
        int diameter = radius * 2 + 1;
        int totalPositions = diameter * diameter * diameter;
        int radiusSquared = radius * radius;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int checked = 0, scanned = 0; checked < totalPositions && scanned < MAX_SCAN_POSITIONS_PER_TICK; checked++) {
            int index = Math.floorMod(scanIndex, totalPositions);
            scanIndex = index + 1 >= totalPositions ? 0 : index + 1;
            int dx = (index % diameter) - radius;
            int dz = ((index / diameter) % diameter) - radius;
            int dy = (index / (diameter * diameter)) - radius;
            if ((dx * dx) + (dy * dy) + (dz * dz) > radiusSquared) {
                continue;
            }
            scanned++;
            cursor.set(worldPosition.getX() + dx, worldPosition.getY() + dy, worldPosition.getZ() + dz);

            if (!level.hasChunk(cursor.getX() >> 4, cursor.getZ() >> 4)) {
                continue;
            }
            FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, cursor);
            if (fluidState.getType().isSame(Fluids.WATER) && fluidState.getAmount() > 0) {
                return cursor.immutable();
            }
        }
        return null;
    }

    private void ff$updateBlockWaterLevel() {
        if (level == null) {
            return;
        }
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof WaterAbsorberBlock)) {
            return;
        }
        int storedMb = fluidTank.getFluidAmount();
        int displayLevel = storedMb <= 0
            ? 0
            : Math.max(1, Math.min(8, (storedMb * 8 + TANK_CAPACITY_MB - 1) / TANK_CAPACITY_MB));
        if (state.getValue(WaterAbsorberBlock.WATER_LEVEL) != displayLevel) {
            level.setBlock(worldPosition, state.setValue(WaterAbsorberBlock.WATER_LEVEL, displayLevel), 3);
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

    private final class AbsorberFluidTank extends FluidTank {
        private AbsorberFluidTank() {
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

    private final class AbsorberEnergyStorage extends EnergyStorage {
        private AbsorberEnergyStorage() {
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
