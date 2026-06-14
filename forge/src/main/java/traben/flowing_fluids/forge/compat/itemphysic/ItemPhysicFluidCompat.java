package traben.flowing_fluids.forge.compat.itemphysic;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import team.creative.itemphysic.server.ItemEntityExtender;
import team.creative.itemphysic.server.ItemPhysicServer;
import traben.flowing_fluids.FFFluidUtils;

public final class ItemPhysicFluidCompat {
    private static final double WATER_SWIM_REACH = 0.3D;
    private static final double DEFAULT_FLUID_EPSILON = 0.001D;
    private static final double MIN_FLOATING_Y_SPEED = 0.02D;
    private static final double MAX_FLOATING_Y_SPEED = 0.1D;
    private static final double FLOATING_Y_ACCELERATION = 0.04D;

    private ItemPhysicFluidCompat() {
    }

    public static Fluid findEffectiveFluid(final ItemEntity item, final boolean below) {
        if (item == null || item.level() == null) {
            return null;
        }
        if (below) {
            BlockPos pos = item.blockPosition().below();
            FluidState state = FFFluidUtils.getEffectiveFluidState(item.level(), pos, item.level().getBlockState(pos));
            return state.isEmpty() ? null : state.getType();
        }

        AABB bounds = getFluidProbeBounds(item);
        if (bounds.getXsize() <= 0.0D || bounds.getYsize() <= 0.0D || bounds.getZsize() <= 0.0D) {
            return null;
        }

        Level level = item.level();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minX = Mth.floor(bounds.minX);
        int maxX = Mth.ceil(bounds.maxX);
        int minY = Mth.floor(bounds.minY);
        int maxY = Mth.ceil(bounds.maxY);
        int minZ = Mth.floor(bounds.minZ);
        int maxZ = Mth.ceil(bounds.maxZ);

        Fluid bestFluid = null;
        double bestDepth = 0.0D;
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    cursor.set(x, y, z);
                    BlockState blockState = level.getBlockState(cursor);
                    FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, cursor, blockState);
                    if (fluidState.isEmpty()) {
                        continue;
                    }
                    double fluidSurface = y + fluidState.getHeight(level, cursor);
                    if (fluidSurface < bounds.minY || y >= bounds.maxY) {
                        continue;
                    }
                    double depth = Math.min(fluidSurface, bounds.maxY) - bounds.minY;
                    if (depth > bestDepth) {
                        bestDepth = depth;
                        bestFluid = fluidState.getType();
                    }
                }
            }
        }
        return bestFluid;
    }

    public static void applyEffectiveBuoyancyIfMissing(final ItemEntity item) {
        if (ItemPhysicServer.fluid.get() != null) {
            return;
        }
        Fluid fluid = findEffectiveFluid(item, false);
        if (fluid == null) {
            return;
        }
        ItemPhysicServer.fluid.set(fluid);
        if (item instanceof ItemEntityExtender extender && extender.canSwim() && !fluid.is(FluidTags.LAVA)) {
            applyStableWaterFloat(item);
        }
    }

    private static void applyStableWaterFloat(final ItemEntity item) {
        Vec3 movement = item.getDeltaMovement();
        double targetY = movement.y + (item.isNoGravity() ? 0.0D : FLOATING_Y_ACCELERATION);
        if (targetY < MAX_FLOATING_Y_SPEED) {
            targetY += Math.min(FLOATING_Y_ACCELERATION, MAX_FLOATING_Y_SPEED - targetY);
        }
        targetY = Mth.clamp(Math.max(targetY, MIN_FLOATING_Y_SPEED), movement.y, MAX_FLOATING_Y_SPEED);
        if (targetY > movement.y) {
            item.setDeltaMovement(movement.x, targetY, movement.z);
        }
    }

    private static AABB getFluidProbeBounds(final ItemEntity item) {
        boolean canSwim = item instanceof ItemEntityExtender extender && extender.canSwim();
        return canSwim ? item.getBoundingBox().inflate(WATER_SWIM_REACH) : item.getBoundingBox().inflate(-DEFAULT_FLUID_EPSILON);
    }
}
