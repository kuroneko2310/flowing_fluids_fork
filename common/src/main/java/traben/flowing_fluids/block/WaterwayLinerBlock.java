package traben.flowing_fluids.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class WaterwayLinerBlock extends Block {
    public WaterwayLinerBlock() {
        super(BlockBehaviour.Properties.of()
            .requiresCorrectToolForDrops()
            .strength(2.2F, 6.5F)
            .sound(SoundType.METAL));
    }
}
