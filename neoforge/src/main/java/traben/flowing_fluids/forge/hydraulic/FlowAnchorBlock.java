package traben.flowing_fluids.forge.hydraulic;

import com.mojang.serialization.MapCodec;
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

    public static final MapCodec<FlowAnchorBlock> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(
        FlowAnchorTier.CODEC.fieldOf("tier").forGetter(FlowAnchorBlock::tier), propertiesCodec()
    ).apply(instance, FlowAnchorBlock::new));

    @Override
    protected MapCodec<? extends FlowAnchorBlock> codec() { return CODEC; }

    public FlowAnchorBlock(FlowAnchorTier tier) {
        this(tier, BlockBehaviour.Properties.ofFullCopy(Blocks.SEA_LANTERN)
            .strength(3.0F, 6.5F)
            .sound(SoundType.GLASS)
            .lightLevel(state -> tier.lightLevel()));
    }

    public FlowAnchorBlock(FlowAnchorTier tier, BlockBehaviour.Properties properties) {
        super(properties);
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
