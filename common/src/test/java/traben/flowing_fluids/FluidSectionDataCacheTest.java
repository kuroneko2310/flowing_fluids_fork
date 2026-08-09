package traben.flowing_fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FluidSectionDataCacheTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void passThroughCellsNeverCountAsSolidFluidSupport() {
        byte ordinarySolid = (byte) (FluidSectionDataCache.LOADED | FluidSectionDataCache.SOLID);
        byte porousSolid = (byte) (ordinarySolid | FluidSectionDataCache.PASS_THROUGH);

        assertTrue(FluidSectionDataCache.isSolidSupportFlags(ordinarySolid));
        assertFalse(FluidSectionDataCache.isSolidSupportFlags(porousSolid));
    }

    @Test
    void binaryStorageFlagUsesTheRemainingHighBitWithoutChangingOtherFlags() {
        byte flags = (byte) (FluidSectionDataCache.LOADED | FluidSectionDataCache.HAS_FLUID
            | FluidSectionDataCache.BINARY_FLUID_STORAGE);

        assertTrue((flags & FluidSectionDataCache.LOADED) != 0);
        assertTrue((flags & FluidSectionDataCache.HAS_FLUID) != 0);
        assertTrue((flags & FluidSectionDataCache.BINARY_FLUID_STORAGE) != 0);
        assertFalse((flags & FluidSectionDataCache.PASS_THROUGH) != 0);
    }

    @Test
    void sparseReadsLoadOnlyTheRequestedCell() {
        Level level = mock(Level.class);
        BlockState air = mock(BlockState.class);
        FluidState empty = mock(FluidState.class);
        when(level.getMinBuildHeight()).thenReturn(0);
        when(level.getMaxBuildHeight()).thenReturn(256);
        when(level.isLoaded(any(BlockPos.class))).thenReturn(true);
        when(level.getBlockState(any(BlockPos.class))).thenReturn(air);
        when(air.getFluidState()).thenReturn(empty);
        when(air.isAir()).thenReturn(true);
        when(empty.isEmpty()).thenReturn(true);

        FluidSectionDataCache cache = new FluidSectionDataCache(level, 1);
        assertTrue(cache.isAir(0, 64, 0));
        assertTrue(cache.isAir(0, 64, 0));

        verify(level, times(1)).getBlockState(any(BlockPos.class));
    }
}
