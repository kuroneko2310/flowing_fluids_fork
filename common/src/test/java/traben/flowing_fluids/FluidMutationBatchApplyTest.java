package traben.flowing_fluids;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import traben.flowing_fluids.config.FFConfig;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FluidMutationBatchApplyTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void failedDestinationRestoresSourceAndReportsNoRemainingChanges() {
        try (Fixture fixture = new Fixture()) {
            fixture.rejectDestination = true;
            var result = fixture.transfer().apply();
            assertFalse(result.applied());
            assertTrue(result.writesStarted());
            assertEquals(0, result.changedCells());
            fixture.assertOriginalAmounts();
            fixture.grid.verify(() -> FluidSpatialGrid.setFluidAtFromBuffer(
                    fixture.level, fixture.source, true, FluidAmountConverter.toInternal(8)));
        }
    }

    @Test
    void throwingDestinationRestoresSourceBeforePropagatingFailure() {
        try (Fixture fixture = new Fixture()) {
            fixture.throwOnDestination = true;
            assertThrows(IllegalArgumentException.class, () -> fixture.transfer().apply());
            fixture.assertOriginalAmounts();
        }
    }

    @Test
    void rollbackRestoresVirtualFluidAndOriginalHostBlock() {
        try (Fixture fixture = new Fixture()) {
            BlockState host = Blocks.OAK_STAIRS.defaultBlockState();
            fixture.blocks.put(fixture.source, host);
            ExtendedWaterlogStore.set(fixture.level, fixture.source, Fluids.WATER, 8);
            fixture.rejectDestination = true;
            fixture.transfer().apply();
            assertEquals(host, fixture.blocks.get(fixture.source));
            assertEquals(8, ExtendedWaterlogStore.getAmount(fixture.level, fixture.source));
            fixture.assertOriginalAmounts();
        }
    }

    @Test
    void staleExpectedAmountAbortsBeforeWriting() {
        try (Fixture fixture = new Fixture()) {
            fixture.blocks.put(fixture.destination, water(3));
            var result = fixture.transfer().apply();
            assertFalse(result.applied());
            assertFalse(result.writesStarted());
            assertEquals(0, fixture.writes);
        }
    }

    @Test
    void successfulTransferPreservesWaterAndReportsBothCells() {
        try (Fixture fixture = new Fixture()) {
            var result = fixture.transfer().apply();
            assertTrue(result.applied());
            assertEquals(2, result.changedCells());
            assertEquals(5, fixture.fluidAt(fixture.source).getAmount());
            assertEquals(5, fixture.fluidAt(fixture.destination).getAmount());
        }
    }

    @Test
    void neighborCallbackCannotSilentlyOverwriteAChangedDestination() {
        try (Fixture fixture = new Fixture()) {
            fixture.changeDestinationOnSourceWrite = true;
            var result = fixture.transfer().apply();
            assertFalse(result.applied());
            fixture.assertOriginalAmounts();
        }
    }

    private static BlockState water(int amount) {
        return amount == 0 ? Blocks.AIR.defaultBlockState()
                : (amount == 8 ? Fluids.WATER.getSource(false) : Fluids.WATER.getFlowing(amount, false)).createLegacyBlock();
    }

    private static final class Fixture implements AutoCloseable {
        final FFConfig previous = FlowingFluids.config;
        final LevelAccessor level = mock(LevelAccessor.class);
        final BlockPos source = new BlockPos(0, 64, 0);
        final BlockPos destination = source.east();
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final MockedStatic<FluidSpatialGrid> grid = mockStatic(FluidSpatialGrid.class);
        final MockedStatic<AdaptiveTickScheduler> scheduler = mockStatic(AdaptiveTickScheduler.class);
        final MockedStatic<ChunkLocalSlopeCache> slope = mockStatic(ChunkLocalSlopeCache.class);
        final MockedStatic<FluidComponentGraph> graph = mockStatic(FluidComponentGraph.class);
        boolean rejectDestination;
        boolean throwOnDestination;
        boolean changeDestinationOnSourceWrite;
        int writes;

        Fixture() {
            FlowingFluids.config = new FFConfig();
            FlowingFluids.config.enableWaterPressure = false;
            blocks.put(source, water(8));
            blocks.put(destination, water(2));
            when(level.getBlockState(any())).thenAnswer(call ->
                    blocks.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState()));
            when(level.setBlock(any(), any(), anyInt())).thenAnswer(call -> {
                BlockPos pos = call.getArgument(0);
                BlockState state = call.getArgument(1);
                if (pos.equals(destination) && !state.equals(water(2))) {
                    if (throwOnDestination) throw new IllegalArgumentException("Injected write failure");
                    if (rejectDestination) return false;
                }
                writes++;
                blocks.put(pos.immutable(), state);
                if (changeDestinationOnSourceWrite && pos.equals(source) && state.equals(water(5))) {
                    blocks.put(destination, water(3));
                }
                return true;
            });
        }

        FluidState fluidAt(BlockPos pos) {
            FluidState stored = ExtendedWaterlogStore.get(level, pos);
            return stored.isEmpty() ? blocks.get(pos).getFluidState() : stored;
        }

        FluidMutationBatch transfer() {
            return new FluidMutationBatch(level).transfer(source, 8, 5, destination, 2, 5, Fluids.WATER);
        }

        void assertOriginalAmounts() {
            assertEquals(8, fluidAt(source).getAmount());
            assertEquals(2, fluidAt(destination).getAmount());
        }

        @Override
        public void close() {
            ExtendedWaterlogStore.clearDimension(level);
            graph.close();
            slope.close();
            scheduler.close();
            grid.close();
            FlowingFluids.config = previous;
        }
    }
}
