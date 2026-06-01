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
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.forge.nether.NetherLavaEventSystem;

public class WallSpringBlock extends Block implements SimpleWaterloggedBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    private static final VoxelShape NORTH_SHAPE = Block.box(2.0D, 2.0D, 0.0D, 14.0D, 14.0D, 4.0D);
    private static final VoxelShape SOUTH_SHAPE = Block.box(2.0D, 2.0D, 12.0D, 14.0D, 14.0D, 16.0D);
    private static final VoxelShape WEST_SHAPE = Block.box(0.0D, 2.0D, 2.0D, 4.0D, 14.0D, 14.0D);
    private static final VoxelShape EAST_SHAPE = Block.box(12.0D, 2.0D, 2.0D, 16.0D, 14.0D, 14.0D);

    private final SpringStrength strength;
    private final FlowingFluid sourceFluid;
    private final ParticleOptions particle;

    public WallSpringBlock(SpringStrength strength, FlowingFluid sourceFluid, ParticleOptions particle) {
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

    public SpringStrength strength() {
        return strength;
    }

    public int nextTickDelay(RandomSource random) {
        return strength.nextDelay(random);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH_SHAPE;
            case WEST -> WEST_SHAPE;
            case EAST -> EAST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction supportDirection = state.getValue(FACING);
        BlockPos supportPos = pos.relative(supportDirection);
        BlockState supportState = level.getBlockState(supportPos);
        return supportState.isFaceSturdy(level, supportPos, supportDirection.getOpposite());
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clickedFace = context.getClickedFace();
        if (!clickedFace.getAxis().isHorizontal()) {
            return null;
        }

        boolean waterlogged = sourceFluid.isSame(Fluids.WATER)
                && context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER;
        BlockState state = defaultBlockState()
                .setValue(FACING, clickedFace.getOpposite())
                .setValue(WATERLOGGED, waterlogged);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            AdaptiveTickScheduler.scheduleFluidTick(level, pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        if (direction == state.getValue(FACING) && !state.canSurvive(level, pos)) {
            return detachedState(level, pos, state);
        }
        SpringTickScheduler.schedule(level, pos, this, strength.minimumDelay());
        return state;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !oldState.is(state.getBlock())) {
            SpringTickScheduler.schedule(level, pos, this, strength.minimumDelay());
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean moving) {
        super.neighborChanged(state, level, pos, block, fromPos, moving);
        if (!level.isClientSide) {
            SpringTickScheduler.schedule(level, pos, this, strength.minimumDelay());
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.canSurvive(level, pos)) {
            // Natural spring mouths should collapse quietly instead of turning into collectible loot.
            replaceDetachedSpring(level, pos, state);
            return;
        }

        if (!FlowingFluids.config.enableMod
                || FlowingFluids.config.isDimensionExcluded(level)
                || !FlowingFluids.config.isFluidAllowed(sourceFluid)) {
            SpringTickScheduler.schedule(level, pos, this, nextTickDelay(random));
            return;
        }

        if (maybeBreakSpring(level, pos, state, random)) {
            return;
        }

        Direction outputDirection = state.getValue(FACING).getOpposite();
        BlockPos outputPos = pos.relative(outputDirection);
        BlockState outputState = level.getBlockState(outputPos);
        FluidState outputFluid = FFFluidUtils.getEffectiveFluidState(level, outputPos, outputState);
        int baseDelay = nextTickDelay(random);

        if (!SpringFluidEmitter.canEmitInto(level, outputState, outputFluid, sourceFluid)) {
            SpringTickScheduler.schedule(level, pos, this, Math.max(strength.minimumDelay() * 4, baseDelay * 2));
            return;
        }

        int emitted = strength.emissionAmount();
        if (sourceFluid.isSame(Fluids.WATER)) {
            emitted += WaterSpringActivity.additionalEmission(level, pos, outputPos, outputDirection, strength);
            emitted += WaterSpringActivity.burstEmission(level, pos, outputPos, outputDirection, strength, random);
        } else if (sourceFluid.isSame(Fluids.LAVA)) {
            emitted += LavaSpringActivity.additionalEmission(level, pos, outputPos, outputDirection, strength);
            emitted += LavaSpringActivity.burstEmission(level, pos, outputPos, outputDirection, strength, random);
            emitted += NetherLavaEventSystem.getSpringEmissionBonus(level, pos, outputDirection, sourceFluid);
        }
        emitted = SpringColumnPulseController.scaleEmission(sourceFluid, emitted);
        int remainder = SpringFluidEmitter.emitFluid(level, outputPos, emitted, sourceFluid, outputDirection);
        int nextDelay = remainder < emitted
                ? baseDelay
                : Math.max(strength.minimumDelay() * 3, baseDelay + strength.minimumDelay());
        SpringTickScheduler.schedule(level, pos, this, nextDelay);

        if (remainder < emitted) {
            AdaptiveTickScheduler.scheduleFluidTick(level, outputPos, sourceFluid, sourceFluid.getTickDelay(level));
            if (sourceFluid.isSame(Fluids.LAVA)) {
                LavaSpringActivity.applyHazards(level, pos, outputPos, outputDirection, strength, random);
            }
        }
    }

    private BlockState detachedState(LevelAccessor level, BlockPos pos, BlockState state) {
        FluidState fluidState = FFFluidUtils.getEffectiveFluidState(level, pos, state);
        return fluidState.getType().isSame(sourceFluid) && fluidState.getAmount() > 0
                ? fluidState.createLegacyBlock()
                : Blocks.AIR.defaultBlockState();
    }

    private void replaceDetachedSpring(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState detachedState = detachedState(level, pos, state);
        if (detachedState.isAir()) {
            level.destroyBlock(pos, false);
            return;
        }
        level.setBlock(pos, detachedState, 3);
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
        if (random.nextFloat() > 0.35F) {
            return;
        }

        Direction outputDirection = state.getValue(FACING).getOpposite();
        double x = pos.getX() + 0.5D + outputDirection.getStepX() * 0.34D + (random.nextDouble() - 0.5D) * 0.12D;
        double y = pos.getY() + 0.42D + random.nextDouble() * 0.24D;
        double z = pos.getZ() + 0.5D + outputDirection.getStepZ() * 0.34D + (random.nextDouble() - 0.5D) * 0.12D;
        level.addParticle(particle, x, y, z, 0.0D, -0.02D, 0.0D);
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
