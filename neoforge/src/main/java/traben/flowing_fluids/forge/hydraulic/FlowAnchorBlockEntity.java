package traben.flowing_fluids.forge.hydraulic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public final class FlowAnchorBlockEntity extends BlockEntity {
    private boolean ff$registered;

    public FlowAnchorBlockEntity(BlockPos pos, BlockState state) {
        super(ForgeHydraulicBlockRegistry.FLOW_ANCHOR_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!ff$registered && level instanceof ServerLevel serverLevel) {
            FlowAnchorTier tier = ff$getTier();
            if (tier != null) {
                FlowAnchorRuntime.register(serverLevel, worldPosition, tier);
                ff$registered = true;
            }
        }
    }

    @Override
    public void setRemoved() {
        if (ff$registered && level instanceof ServerLevel serverLevel) {
            FlowAnchorRuntime.unregister(serverLevel, worldPosition);
            ff$registered = false;
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (ff$registered && level instanceof ServerLevel serverLevel) {
            FlowAnchorRuntime.unregister(serverLevel, worldPosition);
            ff$registered = false;
        }
        super.onChunkUnloaded();
    }

    @Nullable
    private FlowAnchorTier ff$getTier() {
        return getBlockState().getBlock() instanceof FlowAnchorBlock block ? block.tier() : null;
    }
}
