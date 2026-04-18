package traben.flowing_fluids;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FFFluidUtilsPlantDropSuppressionTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void decorativeFlowersSuppressDropsWhenWaterReplacesThem() {
        assertTrue(FFFluidUtils.shouldSuppressDecorativePlantDropsForFluidReplacement(
                Blocks.DANDELION.defaultBlockState(),
                Fluids.WATER
        ));
    }

    @Test
    void decorativeGrassLikePlantsAlsoSuppressDrops() {
        assertTrue(FFFluidUtils.shouldSuppressDecorativePlantDropsForFluidReplacement(
                Blocks.FERN.defaultBlockState(),
                Fluids.WATER
        ));
    }

    @Test
    void cropsStillUseNormalDropPath() {
        assertFalse(FFFluidUtils.shouldSuppressDecorativePlantDropsForFluidReplacement(
                Blocks.WHEAT.defaultBlockState(),
                Fluids.WATER
        ));
    }

    @Test
    void saplingsStillUseNormalDropPath() {
        assertFalse(FFFluidUtils.shouldSuppressDecorativePlantDropsForFluidReplacement(
                Blocks.OAK_SAPLING.defaultBlockState(),
                Fluids.WATER
        ));
    }

    @Test
    void berryBushesStillUseNormalDropPath() {
        assertFalse(FFFluidUtils.shouldSuppressDecorativePlantDropsForFluidReplacement(
                Blocks.SWEET_BERRY_BUSH.defaultBlockState(),
                Fluids.WATER
        ));
    }

    @Test
    void nonWaterFluidsDoNotUseTheSuppressionPath() {
        assertFalse(FFFluidUtils.shouldSuppressDecorativePlantDropsForFluidReplacement(
                Blocks.DANDELION.defaultBlockState(),
                Fluids.LAVA
        ));
    }
}
