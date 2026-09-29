package traben.flowing_fluids.forge.spring;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SpringCavityCarverTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void forcedAirOpeningTargetsProtectedBlocksToo() {
        WorldGenLevel level = mock(WorldGenLevel.class);
        BlockPos pos = new BlockPos(4, 70, -2);

        SpringCavityCarver.forceOpenAirCell(level, pos);

        verify(level).setBlock(eq(pos), eq(Blocks.AIR.defaultBlockState()), eq(2));
    }

    @Test
    void forcedFluidColumnTargetsProtectedBlocksToo() {
        WorldGenLevel level = mock(WorldGenLevel.class);
        BlockPos pos = new BlockPos(4, 71, -2);

        SpringCavityCarver.forceCarveFluidCell(level, pos, Fluids.WATER);

        verify(level).setBlock(eq(pos), eq(Fluids.WATER.defaultFluidState().createLegacyBlock()), eq(2));
    }
}
