package traben.flowing_fluids;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
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

class EnhancedFluidBFSApplyTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void disconnectedPoolsKeepTheirOwnWaterAtBothSidesOfOldThreshold() {
        for (int count : new int[]{2, 95, 96, 128}) {
            try (Fixture fixture = new Fixture()) {
                for (int i = 0; i < count; i++) {
                    fixture.add(new BlockPos(i * 100, 64, 0), Fluids.WATER, i % 2 == 0 ? 7 : 1);
                }
                Map<BlockPos, Integer> before = new HashMap<>(fixture.amounts);
                fixture.apply();
                assertEquals(before, fixture.amounts);
                assertEquals(0, fixture.writes);
                fixture.verifyNoSupportScans();
            }
        }
    }

    @Test
    void connectedWaterStillEqualizesAndPreservesVisibleTotal() {
        try (Fixture fixture = new Fixture()) {
            BlockPos source = new BlockPos(0, 64, 0);
            fixture.add(source, Fluids.WATER, 7);
            fixture.add(source.east(), Fluids.FLOWING_WATER, 1);
            fixture.apply();
            assertEquals(4, fixture.amounts.get(source));
            assertEquals(4, fixture.amounts.get(source.east()));
            assertEquals(2, fixture.writes);
        }
    }

    @Test
    void staleLavaTargetCannotTakeWaterOrBridgeWaterPools() {
        try (Fixture fixture = new Fixture()) {
            BlockPos source = new BlockPos(0, 64, 0);
            fixture.add(source, Fluids.WATER, 7);
            fixture.add(source.east(), Fluids.LAVA, 1);
            fixture.add(source.east(2), Fluids.WATER, 1);
            Map<BlockPos, Integer> before = new HashMap<>(fixture.amounts);
            fixture.apply();
            assertEquals(before, fixture.amounts);
            assertEquals(0, fixture.writes);
        }
    }

    @Test
    void dryCellsCannotBridgeSeparateWaterPools() {
        try (Fixture fixture = new Fixture()) {
            BlockPos source = new BlockPos(0, 64, 0);
            fixture.add(source, Fluids.WATER, 7);
            fixture.add(source.east(), null, 0);
            fixture.add(source.east(2), Fluids.WATER, 1);
            fixture.apply();
            assertEquals(7, fixture.amounts.get(source));
            assertEquals(1, fixture.amounts.get(source.east(2)));
        }
    }

    @Test
    void blockedFacesSplitAdjacentWaterCells() {
        try (Fixture fixture = new Fixture()) {
            fixture.passable = false;
            BlockPos source = new BlockPos(0, 64, 0);
            fixture.add(source, Fluids.WATER, 7);
            fixture.add(source.east(), Fluids.WATER, 1);
            fixture.apply();
            assertEquals(7, fixture.amounts.get(source));
            assertEquals(1, fixture.amounts.get(source.east()));
            assertEquals(0, fixture.writes);
        }
    }

    @Test
    void uniformPoolSkipsSupportScansAndAllWrites() {
        try (Fixture fixture = new Fixture()) {
            for (int i = 0; i < 128; i++) {
                fixture.add(new BlockPos(i, 64, 0), Fluids.WATER, 4);
            }
            fixture.apply();
            fixture.verifyNoSupportScans();
            assertEquals(0, fixture.writes);
            fixture.buffer.verifyNoInteractions();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final FFConfig previous = FlowingFluids.config;
        final Level level = mock(Level.class);
        final FluidSectionDataCache cache = mock(FluidSectionDataCache.class);
        final Map<BlockPos, Integer> amounts = new HashMap<>();
        final Map<BlockPos, Fluid> fluids = new HashMap<>();
        final LongOpenHashSet keys = new LongOpenHashSet();
        final MockedStatic<FFFluidUtils> utils = mockStatic(FFFluidUtils.class);
        final MockedStatic<FluidTickBuffer> buffer = mockStatic(FluidTickBuffer.class);
        final BlockState block = mock(BlockState.class);
        boolean passable = true;
        int writes;

        Fixture() {
            FlowingFluids.config = new FFConfig();
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.getBlockState(any(BlockPos.class))).thenReturn(block);
            when(cache.fluidType(any(BlockPos.class))).thenAnswer(call -> fluids.get(call.getArgument(0)));
            when(cache.internalAmount(any(BlockPos.class))).thenAnswer(call ->
                    FluidAmountConverter.toInternal(amounts.getOrDefault(call.getArgument(0), 0)));
            when(cache.canAcceptFluid(any(BlockPos.class))).thenReturn(true);
            utils.when(() -> FFFluidUtils.runWithBulkFluidChanges(eq(level), any(Runnable.class)))
                    .thenAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; });
            utils.when(() -> FFFluidUtils.canTraverseFluidAdjacency(eq(level), any(), any(), isNull(),
                    any(Direction.class), any(), any(), isNull(), eq(Fluids.WATER)))
                    .thenAnswer(call -> passable);
            utils.when(() -> FFFluidUtils.getEffectiveFluidState(eq(level), any(BlockPos.class), eq(block)))
                    .thenAnswer(call -> {
                        BlockPos pos = call.getArgument(1);
                        FluidState state = mock(FluidState.class);
                        when(state.isEmpty()).thenReturn(amounts.get(pos) == 0);
                        when(state.getType()).thenReturn(fluids.getOrDefault(pos, Fluids.EMPTY));
                        when(state.getAmount()).thenReturn(amounts.get(pos));
                        return state;
                    });
            utils.when(() -> FFFluidUtils.setFluidStateAtPosToNewAmount(eq(level), any(), any(), anyInt()))
                    .thenAnswer(call -> { amounts.put(call.getArgument(1), call.getArgument(3)); writes++; return true; });
        }

        void add(BlockPos pos, Fluid fluid, int amount) {
            keys.add(pos.asLong());
            amounts.put(pos, amount);
            if (fluid != null) fluids.put(pos, fluid);
        }

        void apply() {
            EnhancedFluidBFS.equalizePositionKeys(level, keys, Fluids.WATER, cache);
        }

        void verifyNoSupportScans() {
            verify(cache, never()).supportScore(any(), any(), any());
        }

        @Override
        public void close() {
            buffer.close();
            utils.close();
            FlowingFluids.config = previous;
        }
    }
}
