package traben.flowing_fluids;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FFFluidUtilsShapeOpeningTest {

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
}
