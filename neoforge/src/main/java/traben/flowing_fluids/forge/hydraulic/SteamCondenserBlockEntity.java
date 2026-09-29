package traben.flowing_fluids.forge.hydraulic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.FlowingFluidsPlatform;
import traben.flowing_fluids.forge.compat.CreateBoilerCompat;

public final class SteamCondenserBlockEntity extends BlockEntity {
    static final int TICK_INTERVAL = 20;
    private static final int TANK_CAPACITY_MB = 4000;

    private final CondenserTank tank = new CondenserTank();
    private float pendingMilliBuckets;

    public SteamCondenserBlockEntity(BlockPos pos, BlockState state) {
        super(ForgeHydraulicBlockRegistry.STEAM_CONDENSER_BLOCK_ENTITY.get(), pos, state);
    }

    public static void tick(ServerLevel level, BlockPos pos, SteamCondenserBlockEntity condenser) {
        if (Math.floorMod(level.getGameTime() + pos.asLong(), TICK_INTERVAL) != 0L
                || !FlowingFluids.config.enableMod
                || !FlowingFluidsPlatform.isThisModLoaded("create")) {
            return;
        }
        float fraction = Math.max(0.0F, Math.min(0.9F, FlowingFluids.config.create_condenserRecoveryFraction));
        if (fraction <= 0.0F || condenser.tank.getSpace() <= 0) {
            return;
        }
        float boiledPerTick = CreateBoilerCompat.claimBoiledWaterPerTick(level, pos.below(), TICK_INTERVAL);
        if (boiledPerTick <= 0.0F) {
            return;
        }
        condenser.pendingMilliBuckets += boiledPerTick * TICK_INTERVAL * fraction;
        int whole = (int) condenser.pendingMilliBuckets;
        if (whole <= 0) {
            return;
        }
        int accepted = condenser.tank.fillInternal(whole);
        condenser.pendingMilliBuckets -= accepted;
        if (accepted < whole) {
            // tank full: drop the overflow instead of banking it forever
            condenser.pendingMilliBuckets = 0.0F;
        }
        condenser.setChanged();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        tank.readFromNBT(registries, tag.getCompound("Tank"));
        pendingMilliBuckets = tag.getFloat("Pending");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Tank", tank.writeToNBT(registries, new CompoundTag()));
        tag.putFloat("Pending", pendingMilliBuckets);
    }

    public IFluidHandler fluidStorage() {
        return tank;
    }

    public int storedMilliBuckets() {
        return tank.getFluidAmount();
    }

    /** Drain-only tank: condensate comes from the boiler, never from pipes. */
    private final class CondenserTank extends FluidTank {
        CondenserTank() {
            super(TANK_CAPACITY_MB, stack -> stack.getFluid().isSame(Fluids.WATER));
        }

        int fillInternal(int milliBuckets) {
            return super.fill(new FluidStack(Fluids.WATER, milliBuckets), FluidAction.EXECUTE);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return 0;
        }

        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    }
}
