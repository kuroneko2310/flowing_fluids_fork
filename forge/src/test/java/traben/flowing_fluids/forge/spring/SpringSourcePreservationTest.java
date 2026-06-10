package traben.flowing_fluids.forge.spring;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringSourcePreservationTest {
    @Test
    void springUpdateShapesWakeInsteadOfDeletingSourceBlocks() throws IOException {
        assertUpdateShapePreserves("FloorSpringBlock.java");
        assertUpdateShapePreserves("WallSpringBlock.java");
        assertUpdateShapePreserves("CeilingSpringBlock.java");
    }

    @Test
    void springWakeupsBypassPeriodicDuplicateSuppression() throws IOException {
        String source = Files.readString(springSourcePath("SpringTickScheduler.java"));
        String scheduleWakeup = methodBody(source, "static void scheduleWakeup");
        String schedule = methodBody(source, "private static void schedule");

        assertTrue(scheduleWakeup.contains("true"),
                "Neighbor/support wakeups should use the urgent wakeup path.");
        assertTrue(schedule.contains("!wakeup && level.getBlockTicks().hasScheduledTick"),
                "Only periodic spring ticks should be duplicate-suppressed.");
        assertTrue(schedule.contains("level.scheduleTick"),
                "Urgent wakeups still need to reach the vanilla tick queue.");
    }

    @Test
    void springSourcesDoNotExposeTheirOwnWaterloggedCellAsFluid() throws IOException {
        assertSourceWaterloggingIsSanitized("FloorSpringBlock.java");
        assertSourceWaterloggingIsSanitized("WallSpringBlock.java");
        assertSourceWaterloggingIsSanitized("CeilingSpringBlock.java");
    }

    @Test
    void generatedWaterSpringsKeepSourceBlockDry() throws IOException {
        assertFalse(Files.readString(springSourcePath("CaveFloorSpringFeature.java"))
                        .contains("FloorSpringBlock.WATERLOGGED, true"),
                "Generated floor springs should seed output water, not waterlog the source block.");
        assertFalse(Files.readString(springSourcePath("CaveWallSpringFeature.java"))
                        .contains("WallSpringBlock.WATERLOGGED, true"),
                "Generated wall springs should seed output water, not waterlog the source block.");
        assertFalse(Files.readString(springSourcePath("CaveCeilingSpringFeature.java"))
                        .contains("CeilingSpringBlock.WATERLOGGED, true"),
                "Generated ceiling springs should seed output water, not waterlog the source block.");
        assertFalse(Files.readString(springSourcePath("PondFloorSpringFeature.java"))
                        .contains("FloorSpringBlock.WATERLOGGED, fluidAtPos"),
                "Pond springs should not inherit source-cell waterlogging from the replaced water cell.");
    }

    private static void assertUpdateShapePreserves(String fileName) throws IOException {
        String source = Files.readString(springSourcePath(fileName));
        String updateShape = methodBody(source, "public BlockState updateShape");

        assertTrue(updateShape.contains("SpringTickScheduler.scheduleWakeup"),
                fileName + " should wake the spring after neighbor/support changes.");
        assertTrue(updateShape.contains("return state;"),
                fileName + " should preserve the source block state during updateShape.");
        assertFalse(updateShape.contains("Blocks.AIR"),
                fileName + " must not convert support loss into source deletion.");
        assertFalse(updateShape.contains("destroyBlock"),
                fileName + " must reserve destruction for explicit breakage paths.");
        assertFalse(updateShape.contains("createLegacyBlock"),
                fileName + " must not replace the spring source with raw fluid.");
    }

    private static void assertSourceWaterloggingIsSanitized(String fileName) throws IOException {
        String source = Files.readString(springSourcePath(fileName));
        String placement = methodBody(source, "public @Nullable BlockState getStateForPlacement");
        String updateShape = methodBody(source, "public BlockState updateShape");
        String tick = methodBody(source, "public void tick");
        String getFluidState = methodBody(source, "public FluidState getFluidState");

        assertTrue(placement.contains("setValue(WATERLOGGED, false)"),
                fileName + " should never place a waterlogged source block.");
        assertTrue(updateShape.contains("sanitizeSourceWaterlogging"),
                fileName + " should repair old waterlogged source states during neighbor updates.");
        assertTrue(tick.contains("normalizeSourceWaterlogging"),
                fileName + " should repair old waterlogged source states during spring ticks.");
        assertFalse(getFluidState.contains("WATERLOGGED") && getFluidState.contains("Fluids.WATER"),
                fileName + " should not expose the source block itself as a water fluid cell.");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "Missing method: " + signature);
        int brace = source.indexOf('{', start);
        assertTrue(brace >= 0, "Missing method body: " + signature);

        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(brace, i + 1);
                }
            }
        }
        throw new AssertionError("Unclosed method body: " + signature);
    }

    private static Path springSourcePath(String fileName) {
        Path fromRoot = Path.of("forge/src/main/java/traben/flowing_fluids/forge/spring", fileName);
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of("src/main/java/traben/flowing_fluids/forge/spring", fileName);
    }
}
