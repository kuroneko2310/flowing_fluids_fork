package traben.flowing_fluids.forge.spring;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;

import java.util.List;

public final class ForgeSpringCommands {
    private static final int DEFAULT_RADIUS = 128;
    private static final int MAX_RADIUS = 2048;
    private static final int RESULT_LIMIT = 10;
    private static final int DEFAULT_DEBUG_VENT_DEPTH = 16;

    private ForgeSpringCommands() {
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("flowing_fluids")
                .then(Commands.literal("springs")
                        .then(Commands.literal("surface_vents")
                                .executes(context -> showSurfaceVentInfo(context.getSource()))
                        .then(Commands.literal("find")
                                .requires(source -> source.hasPermission(2))
                                .then(surfaceVentBranch("water", (FlowingFluid) Fluids.WATER))
                                        .then(surfaceVentBranch("lava", (FlowingFluid) Fluids.LAVA)))
                                .then(Commands.literal("create")
                                        .then(surfaceVentCreateBranch("water", (FlowingFluid) Fluids.WATER))
                                        .then(surfaceVentCreateBranch("lava", (FlowingFluid) Fluids.LAVA))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> surfaceVentCreateBranch(String name, FlowingFluid fluid) {
        return Commands.literal(name)
                .requires(source -> source.hasPermission(2))
                .executes(context -> createSurfaceVent(context.getSource(), fluid, DEFAULT_DEBUG_VENT_DEPTH, null))
                .then(Commands.argument("depth", IntegerArgumentType.integer(5, 24))
                        .executes(context -> createSurfaceVent(
                                context.getSource(),
                                fluid,
                                IntegerArgumentType.getInteger(context, "depth"),
                                null))
                        .then(Commands.argument("surface_pos", BlockPosArgument.blockPos())
                                .executes(context -> createSurfaceVent(
                                        context.getSource(),
                                        fluid,
                                        IntegerArgumentType.getInteger(context, "depth"),
                                        BlockPosArgument.getLoadedBlockPos(context, "surface_pos")))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> surfaceVentBranch(String name, FlowingFluid fluid) {
        return Commands.literal(name)
                .executes(context -> findSurfaceVents(context.getSource(), fluid, DEFAULT_RADIUS))
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, MAX_RADIUS))
                        .executes(context -> findSurfaceVents(
                                context.getSource(),
                                fluid,
                                IntegerArgumentType.getInteger(context, "radius"))));
    }

    private static int showSurfaceVentInfo(CommandSourceStack source) {
        source.sendSystemMessage(Component.literal(
                "Surface vent notes"
                        + "\n- Water vents are overworld-only and still use the rare base roll of about 1 in 48 chunks."
                        + "\n- Overworld spring spawn multiplier: " + traben.flowing_fluids.FlowingFluids.config.overworldSpringSpawnMultiplier
                        + "\n- Nether spring spawn multiplier: " + traben.flowing_fluids.FlowingFluids.config.netherSpringSpawnMultiplier
                        + "\n- Each successful feature then samples 3-12 local spots, leaning wetter biomes upward and deserts/badlands downward."
                        + "\n- The current vent style can start deeper underground, carve a slim shaft up to the surface, and keep that shaft braced with surrounding stone or dirt."
                        + "\n- Water vents prefer nearby water, generate only LARGE or HEAVY floor springs, and keep a strength-based raised crest with partial side spray so they read like lively little fountains."
                        + "\n- Water vents do not drain or consume nearby connected water; the fountain is fed by the spring source itself."
                        + "\n- Use `/flowing_fluids settings springs overworld_spawn_multiplier <value>` or `nether_spawn_multiplier <value>` to retune generation live."
                        + "\n- Use `/flowing_fluids settings springs water_emission_multiplier <value>` or `lava_emission_multiplier <value>` to retune how hard spring columns push."
                        + "\n- Use `/flowing_fluids springs surface_vents find water [radius]` to list nearby loaded vents."
                        + "\n- Use `/flowing_fluids springs surface_vents create water [depth] [surface_pos]` to force a debug vent into the world."
        ));
        return 1;
    }

    private static int findSurfaceVents(CommandSourceStack source, FlowingFluid fluid, int radius) {
        net.minecraft.server.level.ServerLevel level = source.getLevel();
        BlockPos center = BlockPos.containing(source.getPosition());
        List<SurfaceVentLocator.LocatedVent> vents = SurfaceVentLocator.findNearbySurfaceVents(level, center, fluid, radius, RESULT_LIMIT);
        String fluidName = fluid.isSame(Fluids.LAVA) ? "lava" : "water";

        if (vents.isEmpty()) {
            source.sendSystemMessage(Component.literal(
                    "No nearby surface " + fluidName + " vents were found in loaded chunks within " + radius + " blocks."
            ));
            return 0;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Nearby surface ").append(fluidName).append(" vents")
                .append(" (loaded chunks, radius ").append(radius).append(")");
        for (int i = 0; i < vents.size(); i++) {
            SurfaceVentLocator.LocatedVent vent = vents.get(i);
            builder.append("\n").append(i + 1).append(". spring=")
                    .append(formatPos(vent.springPos()))
                    .append(", mouth=")
                    .append(formatPos(vent.mouthPos()))
                    .append(", strength=")
                    .append(vent.strength().name().toLowerCase(java.util.Locale.ROOT))
                    .append(", dist=")
                    .append(vent.distance());
        }
        source.sendSystemMessage(Component.literal(builder.toString()));
        return vents.size();
    }

    private static int createSurfaceVent(CommandSourceStack source, FlowingFluid fluid, int depth, BlockPos explicitSurfacePos) {
        net.minecraft.server.level.ServerLevel level = source.getLevel();
        BlockPos surfacePos = explicitSurfacePos != null
                ? explicitSurfacePos
                : SurfaceVentLocator.surfacePosAt(level, BlockPos.containing(source.getPosition()));
        SurfaceVentLocator.DebugVentResult result = SurfaceVentLocator.createDebugSurfaceVent(level, surfacePos, fluid, depth);
        source.sendSystemMessage(Component.literal(result.message()));
        return result.success() ? 1 : 0;
    }

    private static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
