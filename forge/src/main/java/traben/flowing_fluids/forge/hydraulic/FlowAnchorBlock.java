package traben.flowing_fluids.forge.hydraulic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public final class FlowAnchorBlock extends BaseEntityBlock {
    private final FlowAnchorTier tier;

    public FlowAnchorBlock(FlowAnchorTier tier) {
        super(BlockBehaviour.Properties.copy(Blocks.SEA_LANTERN)
            .strength(3.0F, 6.5F)
            .sound(SoundType.GLASS)
            .lightLevel(state -> tier.lightLevel()));
        this.tier = tier;
    }

    public FlowAnchorTier tier() {
        return tier;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FlowAnchorBlockEntity(pos, state);
    }
}
