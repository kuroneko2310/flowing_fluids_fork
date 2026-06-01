package traben.flowing_fluids.forge.hydraulic;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import traben.flowing_fluids.FFFluidUtils;

public class WaterLevelSensorBlock extends DirectionalBlock {
    public static final DirectionProperty FACING = DirectionalBlock.FACING;
    public static final IntegerProperty POWER = BlockStateProperties.POWER;
    private static final int REFRESH_DELAY = 4;

    public WaterLevelSensorBlock() {
        super(BlockBehaviour.Properties.copy(Blocks.OBSERVER));
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(POWER, 0));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getNearestLookingDirection().getOpposite();
        BlockPos observedPos = context.getClickedPos().relative(facing);
        BlockState observedState = context.getLevel().getBlockState(observedPos);
        return defaultBlockState()
            .setValue(FACING, facing)
            .setValue(POWER, ff$getWaterSignal(context.getLevel(), observedPos, observedState));
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!oldState.is(state.getBlock())) {
            ff$scheduleRefresh(level, pos, 1);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && state.getValue(POWER) > 0) {
            ff$updateOutputNeighbors(level, pos, state);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void neighborChanged(BlockState state,
                                Level level,
                                BlockPos pos,
                                Block block,
                                BlockPos fromPos,
                                boolean movedByPiston) {
        if (fromPos.equals(pos.relative(state.getValue(FACING)))) {
            ff$scheduleRefresh(level, pos, 1);
        }
        super.neighborChanged(state, level, pos, block, fromPos, movedByPiston);
    }

    @Override
    public BlockState updateShape(BlockState state,
                                  Direction direction,
                                  BlockState neighborState,
                                  LevelAccessor level,
                                  BlockPos currentPos,
                                  BlockPos neighborPos) {
        if (direction == state.getValue(FACING)) {
            ff$scheduleRefresh(level, currentPos, 1);
        }
        return super.updateShape(state, direction, neighborState, level, currentPos, neighborPos);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BlockPos observedPos = pos.relative(state.getValue(FACING));
        BlockState observedState = level.getBlockState(observedPos);
        int signal = ff$getWaterSignal(level, observedPos, observedState);
        if (signal != state.getValue(POWER)) {
            BlockState updatedState = state.setValue(POWER, signal);
            level.setBlock(pos, updatedState, 2);
            ff$updateOutputNeighbors(level, pos, updatedState);
            level.updateNeighbourForOutputSignal(pos, this);
            state = updatedState;
        }

        // Virtual waterlog cells do not always emit a normal neighbor update when only their stored amount changes.
        if (ff$shouldKeepRefreshing(level, observedState, signal)) {
            ff$scheduleRefresh(level, pos, REFRESH_DELAY);
        }
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        // Redstone asks from the neighbor's side, so the observed-face direction maps to the output behind us.
        return direction == state.getValue(FACING) ? state.getValue(POWER) : 0;
    }

    @Override
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return state.getValue(POWER);
    }

    @Override
    public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, @Nullable Direction direction) {
        return direction != null && direction == state.getValue(FACING).getOpposite();
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWER);
    }

    private int ff$getWaterSignal(LevelAccessor level, BlockPos observedPos, BlockState observedState) {
        FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, observedPos, observedState);
        int amount = fluidState.is(FluidTags.WATER) ? fluidState.getAmount() : 0;
        return amount <= 0 ? 0 : Math.min(15, (amount * 2) - 1);
    }

    private boolean ff$shouldKeepRefreshing(LevelAccessor level, BlockState observedState, int signal) {
        return signal > 0 || FFFluidUtils.supportsVirtualFluidState(level, observedState);
    }

    private void ff$updateOutputNeighbors(Level level, BlockPos pos, BlockState state) {
        level.updateNeighborsAt(pos, this);
        Direction outputDirection = state.getValue(FACING).getOpposite();
        BlockPos outputPos = pos.relative(outputDirection);
        level.neighborChanged(outputPos, this, pos);
        level.updateNeighborsAtExceptFromFacing(outputPos, this, outputDirection);
    }

    private void ff$scheduleRefresh(LevelAccessor level, BlockPos pos, int delay) {
        if (level instanceof Level actualLevel) {
            ff$scheduleRefresh(actualLevel, pos, delay);
        }
    }

    private void ff$scheduleRefresh(Level level, BlockPos pos, int delay) {
        if (!level.isClientSide()) {
            level.scheduleTick(pos, this, delay);
        }
    }
}
