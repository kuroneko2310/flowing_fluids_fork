package traben.flowing_fluids.forge.hydraulic;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.FluidRegressionLogic;

public class RainCollectorBlock extends BaseEntityBlock {
    public static final BooleanProperty ABSORBING = BooleanProperty.create("absorbing");
    public static final IntegerProperty WATER_LEVEL = IntegerProperty.create("water_level", 0, 8);

    public RainCollectorBlock() {
        super(BlockBehaviour.Properties.copy(Blocks.CAULDRON)
            .requiresCorrectToolForDrops()
            .strength(2.5F, 6.0F)
            .sound(SoundType.COPPER));
        registerDefaultState(stateDefinition.any()
            .setValue(ABSORBING, false)
            .setValue(WATER_LEVEL, 0));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState();
    }

    @Override
    public InteractionResult use(BlockState state,
                                 Level level,
                                 BlockPos pos,
                                 Player player,
                                 InteractionHand hand,
                                 BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND || !player.getItemInHand(hand).isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            if (player.isShiftKeyDown()
                && level.getBlockEntity(pos) instanceof RainCollectorBlockEntity collector) {
                int radius = collector.cycleAbsorbRadius();
                player.displayClientMessage(Component.translatable(
                    "message.flowing_fluids.rain_collector_radius",
                    radius,
                    collector.absorbEnergyCost()
                ), true);
                return InteractionResult.SUCCESS;
            }

            boolean nextMode = !state.getValue(ABSORBING);
            level.setBlock(pos, state.setValue(ABSORBING, nextMode), 3);
            player.displayClientMessage(Component.translatable(
                nextMode
                    ? "message.flowing_fluids.rain_collector_absorbing.on"
                    : "message.flowing_fluids.rain_collector_absorbing.off"
            ), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
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
        return new RainCollectorBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,
                                                                  BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return type == ForgeHydraulicBlockRegistry.RAIN_COLLECTOR_BLOCK_ENTITY.get()
            ? (tickerLevel, pos, tickerState, blockEntity) ->
            RainCollectorBlockEntity.tick((ServerLevel) tickerLevel, pos, tickerState, (RainCollectorBlockEntity) blockEntity)
            : null;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ABSORBING, WATER_LEVEL);
    }

    static boolean canCollectRain(ServerLevel level, BlockPos pos) {
        if (!FlowingFluids.config.enableRainSystem
            || FlowingFluids.config.isDimensionExcluded(level)
            || !level.dimensionType().hasSkyLight()) {
            return false;
        }

        BlockPos rainCheckPos = pos.above();
        return FluidRegressionLogic.shouldAllowRainDrivenWaterPlacement(
            level.isRaining(),
            level.isRainingAt(rainCheckPos),
            level.getBiome(rainCheckPos).value().coldEnoughToSnow(rainCheckPos)
        );
    }

}
