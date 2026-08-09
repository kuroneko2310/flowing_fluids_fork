package traben.flowing_fluids;

import net.minecraft.core.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import traben.flowing_fluids.config.FFConfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FFFluidUtilsShapeOpeningTest {
    private FFConfig previousConfig;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void restoreConfig() {
        if (previousConfig != null) {
            FlowingFluids.config = previousConfig;
            previousConfig = null;
        }
    }

    private void useConfig(FFConfig config) {
        previousConfig = FlowingFluids.config;
        FlowingFluids.config = config;
    }

    @Test
    void bottomSlabShapeOpensUpAndSidesButNotDown() {
        VoxelShape bottomSlab = Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, 0.5D, 1.0D);

        assertTrue(FFFluidUtils.hasShapeFaceOpening(bottomSlab, Direction.UP));
        assertTrue(FFFluidUtils.hasShapeFaceOpening(bottomSlab, Direction.NORTH));
        assertFalse(FFFluidUtils.hasShapeFaceOpening(bottomSlab, Direction.DOWN));
    }

    @Test
    void topNorthFacingStairShapeKeepsFrontAndBottomOpen() {
        VoxelShape topNorthStair = Shapes.or(
                Shapes.box(0.0D, 0.5D, 0.0D, 1.0D, 1.0D, 1.0D),
                Shapes.box(0.0D, 0.0D, 0.5D, 1.0D, 0.5D, 1.0D)
        );

        assertTrue(FFFluidUtils.hasShapeFaceOpening(topNorthStair, Direction.DOWN));
        assertTrue(FFFluidUtils.hasShapeFaceOpening(topNorthStair, Direction.NORTH));
        assertFalse(FFFluidUtils.hasShapeFaceOpening(topNorthStair, Direction.UP));
        assertFalse(FFFluidUtils.hasShapeFaceOpening(topNorthStair, Direction.SOUTH));
    }

    @Test
    void fullCubeShapeDoesNotExposeAnyFaceOpening() {
        VoxelShape fullCube = Shapes.block();

        for (Direction direction : Direction.values()) {
            assertFalse(FFFluidUtils.hasShapeFaceOpening(fullCube, direction));
        }
    }

    @Test
    void shallowWaterCanReachBottomSlabSideCavityFromFullCell() {
        assertTrue(FFFluidUtils.hasCompatibleVirtualFluidHeights(
                null,
                3.0F / 9.0F,
                Direction.EAST,
                SlabType.BOTTOM
        ));
    }

    @Test
    void fullWaterReachesBottomSlabSideCavity() {
        assertTrue(FFFluidUtils.hasCompatibleVirtualFluidHeights(
                null,
                1.0F,
                Direction.EAST,
                SlabType.BOTTOM
        ));
    }

    @Test
    void topAndBottomSlabsDoNotShareHorizontalWaterBand() {
        assertFalse(FFFluidUtils.hasCompatibleVirtualFluidHeights(
                SlabType.TOP,
                1.0F,
                Direction.EAST,
                SlabType.BOTTOM
        ));
    }

    @Test
    void shallowBottomSlabWaterCanFlowSidewaysIntoFullCell() {
        assertTrue(FFFluidUtils.hasCompatibleVirtualFluidHeights(
                SlabType.BOTTOM,
                1.0F / 9.0F,
                Direction.EAST,
                null
        ));
    }

    @Test
    void openDoorsAndPorousFenceBlocksArePassThroughEvenWithoutPressureActions() {
        FFConfig config = new FFConfig();
        config.enableExtendedWaterlogging = false;
        config.applyPressureToDoors = false;
        config.applyPressureToTrapdoors = false;
        config.applyPressureToFenceGates = false;
        useConfig(config);

        BlockState openDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true);
        BlockState closedDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, false);
        BlockState openTrapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true);
        BlockState closedTrapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, false);
        BlockState openFenceGate = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(BlockStateProperties.OPEN, true);
        BlockState closedFenceGate = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(BlockStateProperties.OPEN, false);
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        BlockState ironBars = Blocks.IRON_BARS.defaultBlockState();
        BlockState cobblestoneWall = Blocks.COBBLESTONE_WALL.defaultBlockState();

        assertTrue(FFFluidUtils.isPassThroughFluidBlock(null, openDoor, Direction.NORTH));
        assertFalse(FFFluidUtils.isPassThroughFluidBlock(null, closedDoor, Direction.NORTH));
        assertTrue(FFFluidUtils.isPassThroughFluidBlock(null, openTrapdoor, Direction.DOWN));
        assertFalse(FFFluidUtils.isPassThroughFluidBlock(null, closedTrapdoor, Direction.DOWN));
        assertTrue(FFFluidUtils.isPassThroughFluidBlock(null, openFenceGate, Direction.EAST));
        assertFalse(FFFluidUtils.isPassThroughFluidBlock(null, closedFenceGate, Direction.EAST));
        assertTrue(FFFluidUtils.isPassThroughFluidBlock(null, fence, Direction.EAST));
        assertTrue(FFFluidUtils.isPassThroughFluidBlock(null, ironBars, Direction.EAST));
        assertFalse(FFFluidUtils.isPassThroughFluidBlock(null, cobblestoneWall, Direction.EAST));
        assertFalse(FFFluidUtils.isFluidSupportBlock(null, openTrapdoor, Direction.DOWN, Fluids.WATER));
        assertTrue(FFFluidUtils.isFluidSupportBlock(null, closedTrapdoor, Direction.DOWN, Fluids.WATER));
        assertFalse(FFFluidUtils.isFluidSupportBlock(null, fence, Direction.EAST, Fluids.WATER));
        assertTrue(FFFluidUtils.isFluidSupportBlock(null, cobblestoneWall, Direction.EAST, Fluids.WATER));
        config.enableExtendedWaterlogging = true;
        assertFalse(FFFluidUtils.canStoreVirtualFluidState(null, openDoor));
        assertFalse(FFFluidUtils.canStoreVirtualFluidState(null, openTrapdoor));
        assertFalse(FFFluidUtils.canStoreVirtualFluidState(null, openFenceGate));
        assertFalse(FFFluidUtils.canStoreVirtualFluidState(null, closedDoor));
        assertTrue(FFFluidUtils.canStoreVirtualFluidState(null, closedTrapdoor));
        assertFalse(FFFluidUtils.canStoreVirtualFluidState(null, closedFenceGate));
        assertFalse(FFFluidUtils.supportsVirtualFluidState(null, openTrapdoor));
    }
}
