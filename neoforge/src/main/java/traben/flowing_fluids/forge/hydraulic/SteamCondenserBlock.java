package traben.flowing_fluids.forge.hydraulic;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Sits on top of a Create steam boiler and turns part of the water the boiler boils away back into water, stored in
 * its own tank for pumps to draw from. Without Create it is an inert block.
 */
public class SteamCondenserBlock extends BaseEntityBlock {

    public static final MapCodec<SteamCondenserBlock> CODEC = simpleCodec(SteamCondenserBlock::new);

    @Override
    protected MapCodec<? extends SteamCondenserBlock> codec() { return CODEC; }

    public SteamCondenserBlock() {
        this(BlockBehaviour.Properties.ofFullCopy(Blocks.COPPER_BLOCK)
            .requiresCorrectToolForDrops()
            .strength(3.0F, 6.0F)
            .sound(SoundType.COPPER));
    }

    public SteamCondenserBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SteamCondenserBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return type == ForgeHydraulicBlockRegistry.STEAM_CONDENSER_BLOCK_ENTITY.get()
            ? (tickerLevel, pos, tickerState, blockEntity) ->
            SteamCondenserBlockEntity.tick((ServerLevel) tickerLevel, pos, (SteamCondenserBlockEntity) blockEntity)
            : null;
    }
}
