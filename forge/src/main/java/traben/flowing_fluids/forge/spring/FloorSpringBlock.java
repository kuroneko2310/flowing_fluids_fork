package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

public class FloorSpringBlock extends Block implements SimpleWaterloggedBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    private static final VoxelShape SHAPE = Block.box(3.0D, 0.0D, 3.0D, 13.0D, 8.0D, 13.0D);

    private final SpringStrength strength;
    private final FlowingFluid sourceFluid;
    private final ParticleOptions particle;

    public FloorSpringBlock(SpringStrength strength, FlowingFluid sourceFluid, ParticleOptions particle) {
        super(BlockBehaviour.Properties.of()
                .requiresCorrectToolForDrops()
                .strength(1.5F, 6.0F)
                .sound(SoundType.DRIPSTONE_BLOCK)
                .noOcclusion());
        this.strength = strength;
        this.sourceFluid = sourceFluid;
        this.particle = particle;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(WATERLOGGED, false));
    }

    public int nextTickDelay(RandomSource random) {
        return strength.nextDelay(random);
    }

    public SpringStrength strength() {
        return strength;
    }

    public FlowingFluid sourceFluid() {
        return sourceFluid;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos supportPos = pos.below();
        BlockState supportState = level.getBlockState(supportPos);
        return supportState.isFaceSturdy(level, supportPos, Direction.UP);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        if (context.getClickedFace() != Direction.UP) {
            return null;
        }

        boolean waterlogged = sourceFluid.isSame(Fluids.WATER)
                && context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER;
        BlockState state = defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(WATERLOGGED, waterlogged);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        if (direction == Direction.DOWN && !state.canSurvive(level, pos)) {
            return state.getValue(WATERLOGGED)
                    ? Fluids.WATER.defaultFluidState().createLegacyBlock()
                    : Blocks.AIR.defaultBlockState();
        }
        level.scheduleTick(pos, this, strength.minimumDelay());
        return state;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !oldState.is(state.getBlock())) {
            level.scheduleTick(pos, this, strength.minimumDelay());
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean moving) {
        super.neighborChanged(state, level, pos, block, fromPos, moving);
        if (!level.isClientSide) {
            level.scheduleTick(pos, this, strength.minimumDelay());
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.canSurvive(level, pos)) {
            level.destroyBlock(pos, false);
            return;
        }

        if (!FlowingFluids.config.enableMod
                || FlowingFluids.config.isDimensionExcluded(level)
                || !FlowingFluids.config.isFluidAllowed(sourceFluid)) {
            level.scheduleTick(pos, this, nextTickDelay(random));
            return;
        }

        if (maybeBreakSpring(level, pos, state, random)) {
            return;
        }

        if (sourceFluid.isSame(Fluids.WATER)) {
            var surfaceVent = SurfaceVentLocator.inspectSurfaceVent(level, pos, sourceFluid);
            if (surfaceVent.isPresent()) {
                SurfaceVentLocator.sustainSurfaceVent(level, surfaceVent.get(), sourceFluid, true);
                level.scheduleTick(pos, this, Math.max(2, strength.minimumDelay() / 2));
                return;
            }
        }

        int realizedHeight = SpringColumnPulseController.synchronizeColumn(level, pos, Direction.UP, strength, sourceFluid);
        int nextDelay = realizedHeight > 0
                ? SpringColumnPulseController.nextPulseDelay(level, pos, strength, sourceFluid)
                : Math.max(strength.minimumDelay() * 4, nextTickDelay(random) * 2);
        level.scheduleTick(pos, this, nextDelay);
    }

    private boolean maybeBreakSpring(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (!FlowingFluids.config.enableSpringRandomBreakage) {
            return false;
        }

        float baseChance = FlowingFluids.config.springRandomBreakChance;
        if (baseChance <= 0.0F) {
            return false;
        }

        // Keep the break-roll local to the spring block so an out-of-date helper class cannot take down world ticks.
        float chance = baseChance * (1.0F + strength.ordinal() * 0.18F);
        if (sourceFluid.isSame(Fluids.LAVA)) {
            chance *= 1.35F;
        }

        if (random.nextFloat() >= Math.min(0.95F, chance)) {
            return false;
        }

        level.levelEvent(2001, pos, Block.getId(state));
        level.destroyBlock(pos, false);
        return true;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextFloat() > 0.4F) {
            return;
        }

        double x = pos.getX() + 0.5D + (random.nextDouble() - 0.5D) * 0.16D;
        double y = pos.getY() + 0.55D + random.nextDouble() * 0.18D;
        double z = pos.getZ() + 0.5D + (random.nextDouble() - 0.5D) * 0.16D;
        level.addParticle(particle, x, y, z, 0.0D, 0.02D, 0.0D);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
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
        builder.add(FACING, WATERLOGGED);
    }
}
