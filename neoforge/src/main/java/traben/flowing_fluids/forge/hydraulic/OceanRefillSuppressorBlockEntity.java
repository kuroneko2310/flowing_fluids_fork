package traben.flowing_fluids.forge.hydraulic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import traben.flowing_fluids.performance.InfiniteBiomeRefillSuppression;

public final class OceanRefillSuppressorBlockEntity extends BlockEntity {
    private static final int[] RADIUS_STEPS = {16, 32, 64, 96};
    private static final int DEFAULT_RADIUS = RADIUS_STEPS[1];
    private static final int ENERGY_CAPACITY = 200_000;
    private static final int ENERGY_RECEIVE = 10_000;
    private static final int ENERGY_COST_PER_RADIUS_BLOCK = 5;
    private static final int TICK_INTERVAL = 5;

    private final SuppressorEnergyStorage energy = new SuppressorEnergyStorage();
    private int radius = DEFAULT_RADIUS;

    public OceanRefillSuppressorBlockEntity(BlockPos pos, BlockState state) {
        super(ForgeHydraulicBlockRegistry.OCEAN_REFILL_SUPPRESSOR_BLOCK_ENTITY.get(), pos, state);
    }

    public static void tick(ServerLevel level, BlockPos pos, BlockState state, OceanRefillSuppressorBlockEntity suppressor) {
        boolean powered = level.hasNeighborSignal(pos);
        if (state.getValue(OceanRefillSuppressorBlock.ACTIVE) != powered) {
            level.setBlock(pos, state.setValue(OceanRefillSuppressorBlock.ACTIVE, powered), 3);
            return;
        }
        if (!state.getValue(OceanRefillSuppressorBlock.ACTIVE)) {
            return;
        }
        if (Math.floorMod(level.getGameTime() + pos.asLong(), TICK_INTERVAL) != 0L) {
            return;
        }
        if (!suppressor.energy.consumeEnergy(suppressor.suppressionEnergyCost())) {
            return;
        }
        InfiniteBiomeRefillSuppression.register(level, pos, suppressor.radius);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy.setEnergy(tag.getInt("Energy"));
        radius = sanitizeRadius(tag.contains("Radius") ? tag.getInt("Radius") : DEFAULT_RADIUS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putInt("Radius", radius);
    }

    public IEnergyStorage energyStorage() { return energy; }


    int suppressionEnergyCost() {
        return radius * ENERGY_COST_PER_RADIUS_BLOCK;
    }

    int radius() {
        return radius;
    }

    int cycleRadius() {
        radius = nextRadius(radius);
        setChanged();
        return radius;
    }

    private static int nextRadius(int currentRadius) {
        int sanitized = sanitizeRadius(currentRadius);
        for (int i = 0; i < RADIUS_STEPS.length; i++) {
            if (RADIUS_STEPS[i] == sanitized) {
                return RADIUS_STEPS[(i + 1) % RADIUS_STEPS.length];
            }
        }
        return DEFAULT_RADIUS;
    }

    private static int sanitizeRadius(int radius) {
        int selected = DEFAULT_RADIUS;
        int bestDistance = Integer.MAX_VALUE;
        for (int step : RADIUS_STEPS) {
            int distance = Math.abs(step - radius);
            if (distance < bestDistance) {
                selected = step;
                bestDistance = distance;
            }
        }
        return selected;
    }

    private final class SuppressorEnergyStorage extends EnergyStorage {
        private SuppressorEnergyStorage() {
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
