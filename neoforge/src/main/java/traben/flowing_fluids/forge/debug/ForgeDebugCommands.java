package traben.flowing_fluids.forge.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.FFFluidUtils;

public final class ForgeDebugCommands {
    private static final int DEFAULT_WATER_BLOB_RADIUS = 8;
    private static final int MAX_WATER_BLOB_RADIUS = 32;

    private ForgeDebugCommands() {
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("flowing_fluids")
                .then(Commands.literal("debug")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("probe")
                                .executes(context -> probe(
                                        context.getSource(),
                                        BlockPos.containing(context.getSource().getPosition())))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> probe(
                                                context.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(context, "pos")))))
                        .then(Commands.literal("water_blob")
                                .executes(context -> createWaterBlob(
                                        context.getSource(),
                                        BlockPos.containing(context.getSource().getPosition()),
                                        DEFAULT_WATER_BLOB_RADIUS))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_WATER_BLOB_RADIUS))
                                        .executes(context -> createWaterBlob(
                                                context.getSource(),
                                                BlockPos.containing(context.getSource().getPosition()),
                                                IntegerArgumentType.getInteger(context, "radius")))
                                        .then(Commands.argument("center", BlockPosArgument.blockPos())
                                                .executes(context -> createWaterBlob(
                                                        context.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(context, "center"),
                                                        IntegerArgumentType.getInteger(context, "radius"))))))));
    }

    public static int sendProbeReport(ServerLevel level, BlockPos pos, java.util.function.Consumer<Component> messenger) {
        BlockState blockState = level.getBlockState(pos);
        FluidState rawFluid = blockState.getFluidState();
        FluidState effectiveFluid = FFFluidUtils.getEffectiveFluidState(level, pos, blockState);

        messenger.accept(Component.literal("Flowing Fluids debug probe @ " + formatPos(pos)));
        messenger.accept(Component.literal("block=" + BuiltInRegistries.BLOCK.getKey(blockState.getBlock())));
        messenger.accept(Component.literal("raw=" + formatFluid(rawFluid)));
        messenger.accept(Component.literal("effective=" + formatFluid(effectiveFluid)));
        return 1;
    }

    private static int probe(CommandSourceStack source, BlockPos pos) {
        return sendProbeReport(source.getLevel(), pos, source::sendSystemMessage);
    }

    private static int createWaterBlob(CommandSourceStack source, BlockPos center, int radius) {
        ServerLevel level = source.getLevel();
        int radiusSquared = radius * radius;
        int changed = 0;
        int skippedSolid = 0;
        int skippedUnloaded = 0;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z > radiusSquared) {
                        continue;
                    }

                    cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!level.hasChunkAt(cursor)) {
                        skippedUnloaded++;
                        continue;
                    }

                    BlockState blockState = level.getBlockState(cursor);
                    if (!canReplaceWithDebugWater(blockState)) {
                        skippedSolid++;
                        continue;
                    }

                    FluidState existingFluid = FFFluidUtils.getEffectiveFluidState(level, cursor, blockState);
                    boolean alreadyFullWater = existingFluid.getType().isSame(Fluids.WATER)
                            && existingFluid.getAmount() >= 8;
                    if (FFFluidUtils.setFluidStateAtPosToNewAmount(level, cursor.immutable(), Fluids.WATER, 8)
                            && !alreadyFullWater) {
                        changed++;
                    }
                }
            }
        }

        source.sendSystemMessage(Component.literal(
                "Created debug water blob @ " + formatPos(center)
                        + " radius=" + radius
                        + " changed=" + changed
                        + " skipped_solid=" + skippedSolid
                        + " skipped_unloaded=" + skippedUnloaded));
        return changed;
    }

    private static boolean canReplaceWithDebugWater(BlockState state) {
        return !state.hasBlockEntity()
                && (state.isAir()
                || !state.getFluidState().isEmpty()
                || state.canBeReplaced(Fluids.WATER));
    }

    private static String formatFluid(FluidState state) {
        if (state == null || state.isEmpty()) {
            return "empty";
        }
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(state.getType());
        return id + " amount=" + state.getAmount() + "/8 source=" + state.isSource()
                + " height=" + String.format(java.util.Locale.ROOT, "%.3f", state.getOwnHeight());
    }

    private static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
