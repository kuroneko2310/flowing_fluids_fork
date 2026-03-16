package traben.flowing_fluids.config;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.FlowingFluidsPlatform;
import traben.flowing_fluids.PlugWaterFeature;
import traben.flowing_fluids.rain.RainWaterSystem;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class FFCommands {
    private static int messageAndSaveConfig(CommandContext<CommandSourceStack> context, String text) {
        FlowingFluids.saveConfig();
        RainWaterSystem.reloadConfig();
        context.getSource().getServer().getPlayerList().getPlayers().forEach(FlowingFluidsPlatform::sendConfigToClient);
        return message(context, text);
    }

    private static int message(CommandContext<CommandSourceStack> context, String text) {
        //always executed server side
        String inputCommand = context.getInput();
        context.getSource().sendSystemMessage(Component.literal("\n§7§o/" + inputCommand + "§r\n" + text + "\n§7_____________________________"));
        return 1;
    }

    private static int rainStatus(CommandContext<CommandSourceStack> context) {
        return message(context, "Rain settings overview"
                + "\nEnabled: " + FlowingFluids.config.enableRainSystem
                + "\nGenerate interval: " + FlowingFluids.config.rainGenerateIntervalTicks + " ticks"
                + "\nChunk radius: " + FlowingFluids.config.rainChunkRadius
                + "\nAttempts per chunk: " + FlowingFluids.config.rainAttemptsPerChunk
                + "\nBase chance / amount: " + FlowingFluids.config.rainBaseGenerateChance + " / " + FlowingFluids.config.rainBaseWaterAmount
                + "\nWetness persist: " + FlowingFluids.config.rainWetnessPersistTicks + " ticks"
                + "\nCatchment: radius=" + FlowingFluids.config.rainCatchmentRadius + ", max=" + FlowingFluids.config.rainCatchmentMaxBoost
                + "\nUpstream: radius=" + FlowingFluids.config.rainUpstreamSearchRadius + ", max=" + FlowingFluids.config.rainUpstreamMaxBoost
                + "\nIntensity multipliers: drizzle=" + FlowingFluids.config.rainIntensityDrizzleMultiplier
                + ", steady=" + FlowingFluids.config.rainIntensitySteadyMultiplier
                + ", heavy=" + FlowingFluids.config.rainIntensityHeavyMultiplier
                + ", thunderstorm=" + FlowingFluids.config.rainIntensityThunderstormMultiplier
                + "\nExtra puddles: chance=" + FlowingFluids.config.rainSurfaceSpawnChance + ", level=" + FlowingFluids.config.rainSurfaceSpawnLevel
                + "\nUse `/flowing_fluids settings rain runtime_status`, `inspect_here`, or `preset` for more.");
    }

    private static int rainRuntimeStatus(CommandContext<CommandSourceStack> context) {
        return message(context, RainWaterSystem.describeRuntimeState(context.getSource().getLevel()));
    }

    private static int rainInspectHere(CommandContext<CommandSourceStack> context) {
        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
        return message(context, RainWaterSystem.inspectRainAt(context.getSource().getLevel(), pos));
    }

    private static int rainReloadRuntime(CommandContext<CommandSourceStack> context) {
        RainWaterSystem.reloadConfig();
        return message(context, "Rain runtime state was refreshed. Cached wetness and queue data were cleared.");
    }

    private static int applyRainPreset(CommandContext<CommandSourceStack> context, String presetName) {
        FFConfig defaults = new FFConfig();
        switch (presetName) {
            case "gentle" -> {
                FlowingFluids.config.enableRainSystem = true;
                FlowingFluids.config.rainGenerateIntervalTicks = 200;
                FlowingFluids.config.rainAttemptsPerChunk = 4;
                FlowingFluids.config.rainBaseGenerateChance = 0.035f;
                FlowingFluids.config.rainBaseWaterAmount = 1;
                FlowingFluids.config.rainFillsWaterHigherV2 = false;
                FlowingFluids.config.rainSurfaceSpawnChance = 0.015f;
                FlowingFluids.config.rainSurfaceSpawnLevel = 1;
                FlowingFluids.config.rainLevelJumpChance = 0.03f;
                FlowingFluids.config.rainPlacementMaxCombinedAmount = 12;
                FlowingFluids.config.rainWetnessPersistTicks = 900;
                FlowingFluids.config.rainCatchmentRadius = 2;
                FlowingFluids.config.rainCatchmentMaxBoost = 1.3f;
                FlowingFluids.config.rainUpstreamSearchRadius = 4;
                FlowingFluids.config.rainUpstreamMaxBoost = 1.2f;
                FlowingFluids.config.rainIntensityDrizzleMultiplier = 0.45f;
                FlowingFluids.config.rainIntensitySteadyMultiplier = 0.85f;
                FlowingFluids.config.rainIntensityHeavyMultiplier = 1.2f;
                FlowingFluids.config.rainIntensityThunderstormMultiplier = 1.6f;
            }
            case "realistic" -> {
                FlowingFluids.config.enableRainSystem = true;
                FlowingFluids.config.rainGenerateIntervalTicks = 160;
                FlowingFluids.config.rainAttemptsPerChunk = 6;
                FlowingFluids.config.rainBaseGenerateChance = 0.05f;
                FlowingFluids.config.rainBaseWaterAmount = 2;
                FlowingFluids.config.rainFillsWaterHigherV2 = true;
                FlowingFluids.config.rainSurfaceSpawnChance = 0.025f;
                FlowingFluids.config.rainSurfaceSpawnLevel = 1;
                FlowingFluids.config.rainLevelJumpChance = 0.06f;
                FlowingFluids.config.rainPlacementMaxCombinedAmount = 18;
                FlowingFluids.config.rainWetnessPersistTicks = 1800;
                FlowingFluids.config.rainCatchmentRadius = 3;
                FlowingFluids.config.rainCatchmentMaxBoost = 1.8f;
                FlowingFluids.config.rainUpstreamSearchRadius = 6;
                FlowingFluids.config.rainUpstreamMaxBoost = 1.6f;
                FlowingFluids.config.rainIntensityDrizzleMultiplier = 0.45f;
                FlowingFluids.config.rainIntensitySteadyMultiplier = 1.0f;
                FlowingFluids.config.rainIntensityHeavyMultiplier = 1.8f;
                FlowingFluids.config.rainIntensityThunderstormMultiplier = 2.6f;
            }
            case "downpour" -> {
                FlowingFluids.config.enableRainSystem = true;
                FlowingFluids.config.rainGenerateIntervalTicks = 120;
                FlowingFluids.config.rainAttemptsPerChunk = 8;
                FlowingFluids.config.rainBaseGenerateChance = 0.08f;
                FlowingFluids.config.rainBaseWaterAmount = 3;
                FlowingFluids.config.rainFillsWaterHigherV2 = true;
                FlowingFluids.config.rainSurfaceSpawnChance = 0.04f;
                FlowingFluids.config.rainSurfaceSpawnLevel = 2;
                FlowingFluids.config.rainLevelJumpChance = 0.1f;
                FlowingFluids.config.rainPlacementMaxCombinedAmount = 24;
                FlowingFluids.config.rainWetnessPersistTicks = 2000;
                FlowingFluids.config.rainCatchmentRadius = 4;
                FlowingFluids.config.rainCatchmentMaxBoost = 2.0f;
                FlowingFluids.config.rainUpstreamSearchRadius = 7;
                FlowingFluids.config.rainUpstreamMaxBoost = 1.8f;
                FlowingFluids.config.rainIntensityDrizzleMultiplier = 0.6f;
                FlowingFluids.config.rainIntensitySteadyMultiplier = 1.2f;
                FlowingFluids.config.rainIntensityHeavyMultiplier = 2.1f;
                FlowingFluids.config.rainIntensityThunderstormMultiplier = 3.0f;
            }
            case "reset" -> {
                FlowingFluids.config.enableRainSystem = defaults.enableRainSystem;
                FlowingFluids.config.rainGenerateIntervalTicks = defaults.rainGenerateIntervalTicks;
                FlowingFluids.config.rainAttemptsPerChunk = defaults.rainAttemptsPerChunk;
                FlowingFluids.config.rainBaseGenerateChance = defaults.rainBaseGenerateChance;
                FlowingFluids.config.rainBaseWaterAmount = defaults.rainBaseWaterAmount;
                FlowingFluids.config.rainFillsWaterHigherV2 = defaults.rainFillsWaterHigherV2;
                FlowingFluids.config.rainSurfaceSpawnChance = defaults.rainSurfaceSpawnChance;
                FlowingFluids.config.rainSurfaceSpawnLevel = defaults.rainSurfaceSpawnLevel;
                FlowingFluids.config.rainLevelJumpChance = defaults.rainLevelJumpChance;
                FlowingFluids.config.rainPlacementMaxCombinedAmount = defaults.rainPlacementMaxCombinedAmount;
                FlowingFluids.config.rainWetnessPersistTicks = defaults.rainWetnessPersistTicks;
                FlowingFluids.config.rainCatchmentRadius = defaults.rainCatchmentRadius;
                FlowingFluids.config.rainCatchmentMaxBoost = defaults.rainCatchmentMaxBoost;
                FlowingFluids.config.rainUpstreamSearchRadius = defaults.rainUpstreamSearchRadius;
                FlowingFluids.config.rainUpstreamMaxBoost = defaults.rainUpstreamMaxBoost;
                FlowingFluids.config.rainIntensityDrizzleMultiplier = defaults.rainIntensityDrizzleMultiplier;
                FlowingFluids.config.rainIntensitySteadyMultiplier = defaults.rainIntensitySteadyMultiplier;
                FlowingFluids.config.rainIntensityHeavyMultiplier = defaults.rainIntensityHeavyMultiplier;
                FlowingFluids.config.rainIntensityThunderstormMultiplier = defaults.rainIntensityThunderstormMultiplier;
            }
            default -> {
                return message(context, "Unknown rain preset: " + presetName);
            }
        }
        return messageAndSaveConfig(context, "Applied rain preset: " + presetName);
    }

    // 日本語用の数値コマンドヘルパー（設定値と現在値を案内）
    private static LiteralArgumentBuilder<CommandSourceStack> jpIntCommand(String name, String description, String argName, int min, int max,
                                                                           Consumer<Integer> setter, Supplier<Integer> getter,
                                                                           String setMessage) {
        return Commands.literal(name)
                .executes(cont -> message(cont, description + "\n現在値: " + getter.get()))
                .then(Commands.argument(argName, IntegerArgumentType.integer(min, max))
                        .executes(cont -> {
                            setter.accept(cont.getArgument(argName, Integer.class));
                            return messageAndSaveConfig(cont, setMessage + getter.get());
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> jpFloatCommand(String name, String description, String argName, float min, float max,
                                                                             Consumer<Float> setter, Supplier<Float> getter,
                                                                             String setMessage) {
        return Commands.literal(name)
                .executes(cont -> message(cont, description + "\n現在値: " + getter.get()))
                .then(Commands.argument(argName, FloatArgumentType.floatArg(min, max))
                        .executes(cont -> {
                            setter.accept(cont.getArgument(argName, Float.class));
                            return messageAndSaveConfig(cont, setMessage + getter.get());
                        }));
    }

    private static  LiteralArgumentBuilder<CommandSourceStack> floatChanceCommand(String name, String description, Consumer<Float> setter, Supplier<Float> getter) {
        return floatCommand(name, description, "chance", 0, 1, setter, getter);
    }

    private static  LiteralArgumentBuilder<CommandSourceStack> floatCommand(String name, String description, String argName, float min, float max, Consumer<Float> setter, Supplier<Float> getter) {
        return Commands.literal(name)
                .executes(cont -> message(cont, description + "\nCurrent value of " + name + " = " + getter.get()))
                .then(Commands.argument(argName, FloatArgumentType.floatArg(min, max))
                        .executes(cont -> {
                            setter.accept(cont.getArgument(argName, Float.class));
                            return messageAndSaveConfig(cont, name + " set to " + getter.get());
                        })
                );
    }

    private static  LiteralArgumentBuilder<CommandSourceStack> intCommand(String name, String description, String argName, int min, int max, Consumer<Integer> setter, Supplier<Integer> getter) {
        return Commands.literal(name)
                .executes(cont -> message(cont, description + "\nCurrent value of " + name + " = " + getter.get()))
                .then(Commands.argument(argName, IntegerArgumentType.integer(min, max))
                        .executes(cont -> {
                            setter.accept(cont.getArgument(argName, Integer.class));
                            return messageAndSaveConfig(cont, name + " set to " + getter.get());
                        })
                );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> booleanCommand(String name, String description, BooleanConsumer setter, BooleanSupplier getter) {
        return booleanCommand(name, description, name + " setting is now: On.", name + " setting is now: Off.", setter, getter);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> booleanCommand(String name, String description, String messageOn, String messageOff, BooleanConsumer setter, BooleanSupplier getter) {
        return Commands.literal(name)
                .executes(cont -> message(cont, description + "\n" + name + " is currently set to: " + (getter.getAsBoolean() ? "on" : "off")))
                .then(Commands.literal("on")
                        .executes(cont -> {
                            setter.accept(true);
                            return messageAndSaveConfig(cont, messageOn);
                        })
                ).then(Commands.literal("off")
                        .executes(cont -> {
                            setter.accept(false);
                            return messageAndSaveConfig(cont, messageOff);
                        })
                );
    }

    @SafeVarargs
    private static <E extends Enum<E>> LiteralArgumentBuilder<CommandSourceStack> enumCommand(String name, String description, Consumer<E> setter, Supplier<E> getter, Pair<E, String>... options) {
        var command = Commands.literal(name)
                .executes(cont -> message(cont, description + "\n" + name + " is currently set to: " + getter.get().toString().toLowerCase()));

        for (var option : options) {
            String message = option.getSecond();
            E enumVal = option.getFirst();
            command.then(Commands.literal(enumVal.toString().toLowerCase())
                    .executes(cont -> {
                        setter.accept(enumVal);
                        return messageAndSaveConfig(cont, message);
                    })
            );
        }
        return command;
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, @SuppressWarnings("unused") Commands.CommandSelection var3) {
        var notFluidException = new SimpleCommandExceptionType(new LiteralMessage("The block you provided is not a fluid block, or is not a fluid block that can flow."));

        var commands = Commands.literal("flowing_fluids")
                .requires(source -> source.hasPermission(4) || (source.getServer().isSingleplayer() && source.getPlayer() != null && source.getServer().isSingleplayerOwner(source.getPlayer().getGameProfile()))
                ).then(Commands.literal("help")
                        .executes(c -> message(c, "Use any of the commands without adding any of it's arguments, E.G '/flowing_fluids settings', to get a description of what the command does and it's current value."))
                ).then(Commands.literal("settings")
                        .executes(commandContext -> message(commandContext, "Settings for Flowing Fluids, use these to change how fluids behave."))
                        .then(booleanCommand("plug_fluids_during_world_gen",
                                        "Enables or disables plugging all fluids that are generated with air beside or below them.\nThis is an IMMENSE reduction in lag during world generation.",
                                        "World gen fluid plugging is now enabled.",
                                        "World gen fluid plugging is now disabled.",
                                        a -> FlowingFluids.config.encloseAllFluidOnWorldGen = a, () -> FlowingFluids.config.encloseAllFluidOnWorldGen)
                        ).then(Commands.literal("ignored_fluids")
                                .executes(cont -> message(cont, "Control which fluids do or do not get affected by this mod."))
                                .then(Commands.literal("list")
                                        .executes(cont -> message(cont, "The following fluids are currently ignored by Flowing Fluids: " + FlowingFluids.config.fluidBlacklist))
                                )
                                .then(Commands.literal("list_all_fluid_names")
                                        .executes(cont -> message(cont, "This is a list of all registered fluids as Flowing Fluids knows them: " +
                                                BuiltInRegistries.FLUID.stream().map(fluid -> BuiltInRegistries.FLUID.getKey(fluid).toString()).collect(Collectors.toCollection(HashSet::new))))
                                )
                                .then(Commands.literal("add")
                                        .then(Commands.argument("fluid", BlockStateArgument.block(commandBuildContext))
                                                .executes(cont -> {
                                                    var fluidState = BlockStateArgument.getBlock(cont, "fluid").getState().getFluidState();
                                                    if (fluidState.isEmpty() || !(fluidState.getType() instanceof FlowingFluid flows)) {
                                                        throw notFluidException.create();
                                                    }
                                                    String source = BuiltInRegistries.FLUID.getKey(flows.getSource()).toString();
                                                    FlowingFluids.config.fluidBlacklist.add(source);
                                                    String flowing = BuiltInRegistries.FLUID.getKey(flows.getFlowing()).toString();
                                                    FlowingFluids.config.fluidBlacklist.add(flowing);
                                                    return messageAndSaveConfig(cont, "Added the fluids " + source + " and " + flowing + " to the ignored fluids list. The list is now: " + FlowingFluids.config.fluidBlacklist);
                                                })
                                        )
                                )
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("fluid", BlockStateArgument.block(commandBuildContext))
                                                .executes(cont -> {
                                                    var fluidState = BlockStateArgument.getBlock(cont, "fluid").getState().getFluidState();
                                                    if (fluidState.isEmpty() || !(fluidState.getType() instanceof FlowingFluid flows)) {
                                                        throw notFluidException.create();
                                                    }
                                                    String source = BuiltInRegistries.FLUID.getKey(flows.getSource()).toString();
                                                    FlowingFluids.config.fluidBlacklist.remove(source);
                                                    String flowing = BuiltInRegistries.FLUID.getKey(flows.getFlowing()).toString();
                                                    FlowingFluids.config.fluidBlacklist.remove(flowing);
                                                    return messageAndSaveConfig(cont, "Removed the fluids " + source + " and " + flowing + " from the ignored fluids list. The list is now: " + FlowingFluids.config.fluidBlacklist);
                                                })
                                        )
                                )
                        ).then(Commands.literal("reset_all_to_defaults")
                                .executes(cont -> {
                                    FlowingFluids.config = new FFConfig();
                                    return messageAndSaveConfig(cont, "All Flowing Fluids settings have been reset to defaults.");
                                })
                        ).then(Commands.literal("appearance")
                                .executes(commandContext -> message(commandContext, "Appearance settings for Flowing Fluids, use these to change how fluids appear."))
                                .then(Commands.literal("flowing_texture")
                                        .executes(cont -> message(cont, "The flowing fluid texture is currently " + (FlowingFluids.config.hideFlowingTexture ? "hidden." : "shown.") + "\n This will make the fluids surface appear more still and less flickery while settling, this might conflict with mods affecting fluid rendering"))
                                        .then(Commands.literal("hidden")
                                                .executes(cont -> {
                                                    FlowingFluids.config.hideFlowingTexture = true;
                                                    return messageAndSaveConfig(cont, "Flowing fluid texture is now hidden.\nLiquids will no longer show the flowing texture on their surface.");
                                                })
                                        ).then(Commands.literal("shown")
                                                .executes(cont -> {
                                                    FlowingFluids.config.hideFlowingTexture = false;
                                                    return messageAndSaveConfig(cont, "Flowing fluid texture is now visible.\nLiquids will now show the flowing texture on their surface.");
                                                })
                                        )
                                )
                        ).then(booleanCommand("enable_mod",
                                "Enables or disables the mod, if disabled the mod will not affect any fluids.",
                                "FlowingFluids is now enabled, liquids will now have physics.",
                                "FlowingFluids is now disabled, vanilla liquid behaviour will be restored, Buckets will retain their partial fill amount until used.",
                                a -> FlowingFluids.config.enableMod = a,
                                () -> FlowingFluids.config.enableMod)

                        ).then(Commands.literal("behaviour")
                                .executes(commandContext -> message(commandContext, "Behaviour settings for Flowing Fluids, use these to change how fluids behave."))
                                .then(intCommand("min_level_for_ice",
                                        "Controls the minimum level of water that will freeze, this is useful for making ice form in partial height water.\nThe default value is 4, and the maximum value is 8.",
                                        "level", 0, 8,
                                        a -> FlowingFluids.config.minWaterLevelForIce = a, () -> FlowingFluids.config.minWaterLevelForIce)
                                ).then(intCommand("fluid_processing_distance",
                                        "Allows you to set a block distance for fluid processing, works kinda like render distance but for fluid flowing.\n0 means infinite distance (works with chunk loaders far from players).\nThe default value is 0, and the maximum value is 256 (though it is limited by the servers processing chunk distance).\nPlease note this only affects the flowing calculation and refilling behaviours like rain.",
                                        "distance_in_blocks", 0, 256,
                                        a -> FlowingFluids.config.playerBlockDistanceForFlowing = a, () -> FlowingFluids.config.playerBlockDistanceForFlowing)
                                ).then(intCommand("min_level_for_obsidian",
                                        "Controls the minimum level of lava that will convert to obsidian, this is useful for making obsidian form more consistently.\nThe default value is 6, and the maximum value is 8.",
                                        "level", 0, 8,
                                        a -> FlowingFluids.config.minLavaLevelForObsidian = a, () -> FlowingFluids.config.minLavaLevelForObsidian)
                                ).then(Commands.literal("random_tick_level_check_distance")
                                        .executes(cont -> message(cont, "Sets the distance fluids will check for other fluids to level with during random ticks, 0 means disabled, currently set to " + FlowingFluids.config.randomTickLevelingDistance))
                                        .then(Commands.argument("distance", IntegerArgumentType.integer(0, 64))
                                                .executes(cont -> {
                                                    FlowingFluids.config.randomTickLevelingDistance = cont.getArgument("distance", Integer.class);
                                                    return messageAndSaveConfig(cont, "Random tick level check distance set to " + FlowingFluids.config.randomTickLevelingDistance);
                                                })
                                        )
                                ).then(Commands.literal("how_liquids_affect_entities")
                                        .then(booleanCommand("flow_pushes_boats",
                                                "Controls if boats are pushed by the flow angle that water visually has at the surface.\nTHIS MUST BE OFF FOR BOATS TO WORK PROPERLY IN PARTIAL HEIGHT FLUIDS!!!",
                                                "Boats will now be affected by water flow, THIS WILL BREAK BOATS!! they will not function correctly in partial height water",
                                                "Boats will no longer be affected by water flow. This will fix boats not working in partial height water.",
                                                (a) -> FlowingFluids.config.waterFlowAffectsBoats = a,() -> FlowingFluids.config.waterFlowAffectsBoats))
                                        .then(booleanCommand("flow_pushes_players",
                                                "Controls if players are pushed by the flow angle that water visually has at the surface.",
                                                (a) -> FlowingFluids.config.waterFlowAffectsPlayers = a,() -> FlowingFluids.config.waterFlowAffectsPlayers))
                                        .then(booleanCommand("flow_pushes_entities",
                                                "Controls if entities are pushed by the flow angle that water visually has at the surface.\nEXCEPT players, boats, and item entities, they have their own settings.",
                                                (a) -> FlowingFluids.config.waterFlowAffectsEntities = a,() -> FlowingFluids.config.waterFlowAffectsEntities))
                                        .then(booleanCommand("flow_pushes_items",
                                                "Controls if item entities are pushed by the flow angle that water visually has at the surface.",
                                                (a) -> FlowingFluids.config.waterFlowAffectsItems = a,() -> FlowingFluids.config.waterFlowAffectsItems))
                                ).then(enumCommand("fluid_height",
                                        "Changes the heights fluids render/affect entities at, currently set to " + FlowingFluids.config.fullLiquidHeight + ".",
                                        a -> FlowingFluids.config.fullLiquidHeight = a, () -> FlowingFluids.config.fullLiquidHeight,
                                        Pair.of(FFConfig.LiquidHeight.REGULAR, "Fluids now render/affect entities up to regular height."),
                                        Pair.of(FFConfig.LiquidHeight.REGULAR_LOWER_BOUND, "Fluids now render/affect entities up to their regular height but will be almost flat at their lowest amount."),
                                        Pair.of(FFConfig.LiquidHeight.BLOCK_LOWER_BOUND, "Fluids now render/affect entities up to block height but will be almost flat at their lowest amount."),
                                        Pair.of(FFConfig.LiquidHeight.BLOCK, "Fluids now render/affect entities up to block height."),
                                        Pair.of(FFConfig.LiquidHeight.SLAB, "Fluids now render/affect entities up to half a block height."),
                                        Pair.of(FFConfig.LiquidHeight.CARPET, "All Fluids now render/affect entities with 1 pixel height."))
                                )
                                .then(Commands.literal("flow_distances")
                                        .executes(cont -> message(cont, "Modifies the distance fluids will search for slopes to flow down.\nThe vanilla value is always 4 for water but lava will vary between 2 and 4 depending on if it is in the Nether.\n§4WARNING: this setting is the biggest source of lag for all fluid flowing, this value is limited to 8 (as any higher will freeze your world) and I strongly suggest you never raise it above the default 4."))
                                        .then(Commands.literal("water")
                                                .executes(cont -> message(cont, "Modifies the distance water will search for slopes to flow down.\nThe vanilla value is always 4 for water.\nWater flow distance modifier is currently set to " + FlowingFluids.config.waterFlowDistance))
                                                .then(Commands.argument("distance", IntegerArgumentType.integer(0, 8))
                                                        .executes(cont -> {
                                                            FlowingFluids.config.waterFlowDistance = cont.getArgument("distance", Integer.class);
                                                            return messageAndSaveConfig(cont, "Water flow distance set to " + FlowingFluids.config.waterFlowDistance);
                                                        })
                                                )
                                        ).then(Commands.literal("lava")
                                                .executes(cont -> message(cont, "Modifies the distance lava will search for slopes to flow down in the overworld.\nThe vanilla value is always 2 for lava in the overworld.\nLava flow distance modifier is currently set to " + FlowingFluids.config.lavaFlowDistance))
                                                .then(Commands.argument("distance", IntegerArgumentType.integer(0, 8))
                                                        .executes(cont -> {
                                                            FlowingFluids.config.lavaFlowDistance = cont.getArgument("distance", Integer.class);
                                                            return messageAndSaveConfig(cont, "Water flow distance set to " + FlowingFluids.config.lavaFlowDistance);
                                                        })
                                                )
                                        ).then(Commands.literal("lava_nether")
                                                .executes(cont -> message(cont, "Modifies the distance lava will search for slopes to flow down in the nether.\nThe vanilla value is always 4 for lava in the nether.\nLava flow distance modifier is currently set to " + FlowingFluids.config.lavaNetherFlowDistance))
                                                .then(Commands.argument("distance", IntegerArgumentType.integer(0, 8))
                                                        .executes(cont -> {
                                                            FlowingFluids.config.lavaNetherFlowDistance = cont.getArgument("distance", Integer.class);
                                                            return messageAndSaveConfig(cont, "Water flow distance set to " + FlowingFluids.config.lavaNetherFlowDistance);
                                                        })
                                                )
                                        )
                                ).then(Commands.literal("advanced_flow_distances")
                                        .executes(cont -> message(cont, "高度な流動距離設定 - 様々なシナリオで水が流れる距離を制御します。\nこれらの設定により、流動の挙動とパフォーマンスを細かく調整できます。"))
                                        .then(intCommand("max_water_flow_distance",
                                                "水の最大水平流動距離（基本流動距離を超えることが可能）。\n高い値 = よりリアルだがCPU使用率が上昇。\nデフォルト: 8, 範囲: 1-256",
                                                "distance", 1, 256,
                                                a -> FlowingFluids.config.maxWaterFlowDistance = a, () -> FlowingFluids.config.maxWaterFlowDistance)
                                        ).then(intCommand("bfs_max_search_distance",
                                                "水の平衡化のためのBFS（幅優先探索）の最大距離。\n高い値 = より正確な流動だがCPU使用率が上昇。\nデフォルト: 16, 範囲: 4-128",
                                                "distance", 4, 128,
                                                a -> FlowingFluids.config.bfsMaxSearchDistance = a, () -> FlowingFluids.config.bfsMaxSearchDistance)
                                        ).then(floatCommand("slope_find_distance_multiplier",
                                                "傾斜探索距離の倍率（1.0 = デフォルト）。\n高い値 = 水がより遠くの低い場所を見つけやすくなる。\nデフォルト: 1.0, 範囲: 0.5-3.0",
                                                "multiplier", 0.5f, 3.0f,
                                                a -> FlowingFluids.config.slopeFindDistanceMultiplier = a, () -> FlowingFluids.config.slopeFindDistanceMultiplier)
                                        ).then(booleanCommand("enable_adaptive_flow_distance",
                                                "地形タイプ（海、川、運河など）に基づいて流動距離を自動調整します。\nバイオーム別の流動距離を有効にし、よりリアルな水の挙動を実現します。",
                                                "適応型流動距離が有効になりました。水は地形タイプに応じて異なる距離を流れます。",
                                                "適応型流動距離が無効になりました。水はすべての場所で標準距離を使用します。",
                                                a -> FlowingFluids.config.enableAdaptiveFlowDistance = a, () -> FlowingFluids.config.enableAdaptiveFlowDistance)
                                        ).then(intCommand("river_flow_distance",
                                                "川バイオームでの流動距離（適応型流動が有効な場合のみ動作）。\nデフォルト: 64, 範囲: 4-256",
                                                "distance", 4, 256,
                                                a -> FlowingFluids.config.riverFlowDistance = a, () -> FlowingFluids.config.riverFlowDistance)
                                        ).then(intCommand("ocean_flow_distance",
                                                "海洋バイオームでの流動距離（適応型流動が有効な場合のみ動作）。\n最適化により高い値でもパフォーマンスを維持します。\nデフォルト: 128, 範囲: 4-512",
                                                "distance", 4, 512,
                                                a -> FlowingFluids.config.oceanFlowDistance = a, () -> FlowingFluids.config.oceanFlowDistance)
                                        ).then(intCommand("canal_flow_distance",
                                                "人工水路（運河）の流動距離（平地に水がある場合）。\n適応型流動が有効な場合のみ動作します。\nデフォルト: 32, 範囲: 4-128",
                                                "distance", 4, 128,
                                                a -> FlowingFluids.config.canalFlowDistance = a, () -> FlowingFluids.config.canalFlowDistance)
                                        ).then(booleanCommand("enable_distance_based_optimization",
                                                "階層的距離管理: 遠距離の水を低頻度で更新します。\n長距離流動で50-70%のパフォーマンス向上を提供し、視覚的影響は最小限です。",
                                                "距離ベース最適化が有効になりました。遠距離の水は低頻度で更新され、パフォーマンスが向上します。",
                                                "距離ベース最適化が無効になりました。すべての水が同じ頻度で更新されます。",
                                                a -> FlowingFluids.config.enableDistanceBasedOptimization = a, () -> FlowingFluids.config.enableDistanceBasedOptimization)
                                        )
                                ).then(Commands.literal("performance_monitoring")
                                        .executes(cont -> message(cont, "パフォーマンスモニタリングツール - 流体フローのパフォーマンスを分析します。\nこれらを使用して設定を最適化し、パフォーマンス問題をデバッグできます。"))
                                        .then(booleanCommand("enable_performance_monitoring",
                                                "流体システムの詳細なパフォーマンス追跡を有効にします。\ntick時間、BFS操作、キャッシュヒット率などを追跡します。\n注意: 有効時のパフォーマンスオーバーヘッドは最小限です。",
                                                "パフォーマンスモニタリングが有効になりました。詳細なメトリクスが収集され、ログに記録されます。",
                                                "パフォーマンスモニタリングが無効になりました。パフォーマンスデータは収集されません。",
                                                a -> FlowingFluids.config.enablePerformanceMonitoring = a, () -> FlowingFluids.config.enablePerformanceMonitoring)
                                        ).then(intCommand("performance_log_interval",
                                                "パフォーマンスデータをログに記録する間隔（tick単位、20 tick = 1秒）。\nパフォーマンスモニタリングが有効な場合のみ適用されます。\nデフォルト: 200 (10秒), 範囲: 20-1200",
                                                "ticks", 20, 1200,
                                                a -> FlowingFluids.config.performanceLogInterval = a, () -> FlowingFluids.config.performanceLogInterval)
                                        ).then(Commands.literal("show_stats")
                                                .executes(cont -> {
                                                    if (!FlowingFluids.config.enablePerformanceMonitoring) {
                                                        return message(cont, "パフォーマンスモニタリングは現在無効です。\n有効にするには: /flowing_fluids settings behaviour performance_monitoring enable_performance_monitoring on");
                                                    }
                                                    try {
                                                        var monitor = Class.forName("traben.flowing_fluids.performance.FluidPerformanceMonitor")
                                                                .getMethod("getInstance")
                                                                .invoke(null);
                                                        var report = monitor.getClass()
                                                                .getMethod("getPerformanceReport")
                                                                .invoke(monitor);
                                                        return message(cont, report.toString());
                                                    } catch (Exception e) {
                                                        return message(cont, "パフォーマンスデータの取得エラー: " + e.getMessage());
                                                    }
                                                })
                                        ).then(Commands.literal("reset_stats")
                                                .executes(cont -> {
                                                    if (!FlowingFluids.config.enablePerformanceMonitoring) {
                                                        return message(cont, "パフォーマンスモニタリングは現在無効です。");
                                                    }
                                                    try {
                                                        var monitor = Class.forName("traben.flowing_fluids.performance.FluidPerformanceMonitor")
                                                                .getMethod("getInstance")
                                                                .invoke(null);
                                                        monitor.getClass()
                                                                .getMethod("reset")
                                                                .invoke(monitor);
                                                        return message(cont, "パフォーマンスモニタリングデータがリセットされました。");
                                                    } catch (Exception e) {
                                                        return message(cont, "パフォーマンスデータのリセットエラー: " + e.getMessage());
                                                    }
                                                })
                                        )
                                ).then(Commands.literal("tick_delays__aka__flow_speeds")
                                        .executes(cont -> message(cont, "Modifies the tick delay fluids will have between spreading updates\nThe vanilla value is always 5 for water but lava will vary between 10 and 30 depending on if it is in the Nether."))
                                        .then(Commands.literal("water")
                                                .executes(cont -> message(cont, "Modifies the tick delay water will have between spreading updates.\nThe vanilla value is always 5 for water.\nWater tick delay modifier is currently set to " + FlowingFluids.config.waterTickDelay))
                                                .then(Commands.argument("delay", IntegerArgumentType.integer(1, 255))
                                                        .executes(cont -> {
                                                            FlowingFluids.config.waterTickDelay = cont.getArgument("delay", Integer.class);
                                                            return messageAndSaveConfig(cont, "Water tick delay set to " + FlowingFluids.config.waterTickDelay);
                                                        })
                                                )
                                        ).then(Commands.literal("lava")
                                                .executes(cont -> message(cont, "Modifies the tick delay lava will have between spreading updates in the overworld.\nThe vanilla value is always 30 for lava in the overworld.\nLava tick delay modifier is currently set to " + FlowingFluids.config.lavaTickDelay))
                                                .then(Commands.argument("delay", IntegerArgumentType.integer(1, 255))
                                                        .executes(cont -> {
                                                            FlowingFluids.config.lavaTickDelay = cont.getArgument("delay", Integer.class);
                                                            return messageAndSaveConfig(cont, "Lava tick delay set to " + FlowingFluids.config.lavaTickDelay);
                                                        })
                                                )
                                        ).then(Commands.literal("lava_nether")
                                                .executes(cont -> message(cont, "Modifies the tick delay lava will have between spreading updates in the nether.\nThe vanilla value is always 10 for lava in the nether.\nLava tick delay modifier is currently set to " + FlowingFluids.config.lavaNetherTickDelay))
                                                .then(Commands.argument("delay", IntegerArgumentType.integer(1, 255))
                                                        .executes(cont -> {
                                                            FlowingFluids.config.lavaNetherTickDelay = cont.getArgument("delay", Integer.class);
                                                            return messageAndSaveConfig(cont, "Lava_nether tick delay set to " + FlowingFluids.config.lavaNetherTickDelay);
                                                        })
                                                )
                                        )
                                ).then(booleanCommand("pistons_push_fluids",
                                        "Enables or disables piston pushing, if disabled pistons will no longer push fluids.",
                                        "Piston pushing is now enabled.\nLiquids will now be pushed by pistons.",
                                        "Piston pushing is now disabled.\nLiquids will no longer be pushed by pistons.",
                                        a -> FlowingFluids.config.enablePistonPushing = a, () -> FlowingFluids.config.enablePistonPushing)
                                ).then(booleanCommand("easy_piston_pumps",
                                        "Makes fluids above pistons delay their falling to make pumping upwards much easier.",
                                        a -> FlowingFluids.config.easyPistonPump = a, () -> FlowingFluids.config.easyPistonPump)
                                ).then(booleanCommand("placed_blocks_displace_fluids",
                                        "Enables or disables placed blocks displacing fluids, if disabled placed blocks will no longer displace fluids.",
                                        "Placed blocks displacing fluids is now enabled.\nLiquids will now be displaced by blocks placed inside them.",
                                        "Placed blocks displacing fluids is now disabled.\nLiquids will no longer be displaced by blocks placed inside them.",
                                        a -> FlowingFluids.config.enableDisplacement = a, () -> FlowingFluids.config.enableDisplacement)
                                ).then(Commands.literal("waterlogged_blocks_flow_mode")
                                        .executes(cont -> message(cont, "Controls how water flows into or out fo water loggable blocks, due to limitations you cannot have two side by side waterloggable blocks flow into each other as they would flicker endlessly, Sea grass and kelp are excluded from this setting and will always break in waters absence, current setting: " + FlowingFluids.config.waterLogFlowMode))
                                        .then(Commands.literal("only_in")
                                                .executes(cont -> {
                                                    FlowingFluids.config.waterLogFlowMode = FFConfig.WaterLogFlowMode.ONLY_IN;
                                                    return messageAndSaveConfig(cont, "Water will only flow into water loggable blocks, and never out of them.");
                                                })
                                        ).then(Commands.literal("only_out")
                                                .executes(cont -> {
                                                    FlowingFluids.config.waterLogFlowMode = FFConfig.WaterLogFlowMode.ONLY_OUT;
                                                    return messageAndSaveConfig(cont, "Water will only flow out of water loggable blocks, and never into them.");
                                                })
                                        ).then(Commands.literal("in_from_sides_or_top_out_down")
                                                .executes(cont -> {
                                                    FlowingFluids.config.waterLogFlowMode = FFConfig.WaterLogFlowMode.OUT_DOWN_ELSE_IN;
                                                    return messageAndSaveConfig(cont, "Water will flow into water loggable blocks from the sides or top, and out of them from the bottom, if possible.");
                                                })
                                        ).then(Commands.literal("ignore")
                                                .executes(cont -> {
                                                    FlowingFluids.config.waterLogFlowMode = FFConfig.WaterLogFlowMode.IGNORE;
                                                    return messageAndSaveConfig(cont, "Water flowing will ignore water loggable blocks entirely.");
                                                })
                                        )
                                ).then(booleanCommand("flow_over_edges",
                                        "Controls if liquids flow over nearby edges, or will stay at the ledge.",
                                        "Liquids at their minimum height will now flow to and over nearby edges, up to 4 blocks away.",
                                        "Liquids at their minimum height will no longer flow to and over nearby edges.",
                                        a -> FlowingFluids.config.flowToEdges = a, () -> FlowingFluids.config.flowToEdges)
                                )
                        ).then(Commands.literal("draining_and_filling")
                                .executes(commandContext -> message(commandContext, "Set the chances of certain random tick interactions with fluids."))
                                .then(floatChanceCommand("water_puddle_evaporation_chance",
                                        "Sets the chance of small minimum level water tiles evaporating during random ticks",
                                        a -> FlowingFluids.config.evaporationChanceV2 = a,
                                        () -> FlowingFluids.config.evaporationChanceV2)
                                ).then(booleanCommand("water_evaporation_daytime_only",
                                        "When enabled, surface puddles only evaporate during daytime to let night-time pools persist.",
                                        a -> FlowingFluids.config.evaporationDaytimeOnly = a,
                                        () -> FlowingFluids.config.evaporationDaytimeOnly)
                                ).then(booleanCommand("water_evaporation_requires_sky",
                                        "When enabled, puddles without direct sky access will not evaporate.",
                                        a -> FlowingFluids.config.evaporationRequiresSky = a,
                                        () -> FlowingFluids.config.evaporationRequiresSky)
                                ).then(floatChanceCommand("water_nether_evaporation_chance",
                                        "Sets the chance of any water losing a level during random ticks in the nether",
                                        a -> FlowingFluids.config.evaporationNetherChance = a,
                                        () -> FlowingFluids.config.evaporationNetherChance)
                                ).then(booleanCommand("rain_system_enabled",
                                        "Master toggle for all rain-driven water generation, puddles, and refills handled by Flowing Fluids.",
                                        a -> FlowingFluids.config.enableRainSystem = a,
                                        () -> FlowingFluids.config.enableRainSystem)
                                ).then(floatChanceCommand("water_rain_refill_chance",
                                        "Sets the chance of non-full water tiles increasing their level while its rains and they are open to the sky, during random ticks. This provides access to renewable water given enough time.\nNOTE: this will always be forcibly limited to 1/3rd of the current water_puddle_evaporation_chance setting otherwise water will endlessly fill the world during rain, this does effectively cap this value to 0.33",
                                        a -> FlowingFluids.config.rainRefillChance = a,
                                        () -> FlowingFluids.config.rainRefillChance)
                                ).then(floatChanceCommand("rain_surface_spawn_chance",
                                        "Chance for rain to spawn shallow flowing water on nearby ground tiles (puddles).",
                                        a -> FlowingFluids.config.rainSurfaceSpawnChance = a,
                                        () -> FlowingFluids.config.rainSurfaceSpawnChance)
                                ).then(floatChanceCommand("water_infinite_biome_refill_chance",
                                        "Sets the chance of non-full water tiles increasing their level within: Oceans, Rivers, and Swamps, during random ticks. Additionally they must have a sky light level higher than 0, and be between y=0 and sea level. This provides time limited access to infinite water within these biomes, granted they are big enough and not drained too quickly",
                                        a -> FlowingFluids.config.oceanRiverSwampRefillChance = a,
                                        () -> FlowingFluids.config.oceanRiverSwampRefillChance)
                                ).then(floatChanceCommand("water_infinite_biome_non_consume_chance",
                                        "Sets the chance of water not being consumed when flowing in: Oceans, Rivers, and Swamps. Additionally they must have a sky light level higher than 0, and be between y=0 and sea level. This allows access to infinite water within these biomes",
                                        a -> FlowingFluids.config.infiniteWaterBiomeNonConsumeChance = a,
                                        () -> FlowingFluids.config.infiniteWaterBiomeNonConsumeChance)
                                ).then(floatChanceCommand("water_infinite_biome_surface_drain_chance",
                                        "Sets the chance of water being drained into water at sea level when flowing into: Oceans, Rivers, and Swamps. Additionally they must have a sky light level higher than 0. This allows infinte water drainage within these biomes",
                                        a -> FlowingFluids.config.infiniteWaterBiomeDrainSurfaceChance = a,
                                        () -> FlowingFluids.config.infiniteWaterBiomeDrainSurfaceChance)
                                ).then(floatChanceCommand("farm_land_drains_water_chance",
                                        "Sets the chance at which a farmland block will consume 1 level of water each time it hydrates. 0 == OFF, 1 == ALWAYS",
                                        a -> FlowingFluids.config.farmlandDrainWaterChance = a,
                                        () -> FlowingFluids.config.farmlandDrainWaterChance)
                                ).then(floatChanceCommand("animal_breeding_drains_water_chance",
                                        "Sets the chance at which an animal will consume 1 level of nearby water each time it tries to breed, range 8 blocks, water can be at same level or 1 lower. 0 == OFF, 1 == ALWAYS",
                                        a -> FlowingFluids.config.drinkWaterToBreedAnimalChance = a,
                                        () -> FlowingFluids.config.drinkWaterToBreedAnimalChance)
                                ).then(floatChanceCommand("concrete_powder_drains_water_chance",
                                        "Sets the chance at which concrete powder will consume a water level on hardening. 0 == OFF, 1 == ALWAYS",
                                        a -> FlowingFluids.config.concreteDrainsWaterChance = a,
                                        () -> FlowingFluids.config.concreteDrainsWaterChance)
                                ).then(booleanCommand("rain_fills_block_above",
                                        "Controls if rain will place new layers of water higher than the previous block of water was.",
                                        a -> FlowingFluids.config.rainFillsWaterHigherV2 = a, () -> FlowingFluids.config.rainFillsWaterHigherV2)
                                ).then(Commands.literal("rain_surface_spawn_level")
                                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 8))
                                                .executes(cont -> {
                                                    FlowingFluids.config.rainSurfaceSpawnLevel = cont.getArgument("level", Integer.class);
                                                    return messageAndSaveConfig(cont, "Rain surface spawn level set to " + FlowingFluids.config.rainSurfaceSpawnLevel);
                                                })
                                        )
                                ).then(floatChanceCommand("rain_level_jump_chance",
                                        "Chance for rain to give an additional small water level boost after a refill tick.",
                                        a -> FlowingFluids.config.rainLevelJumpChance = a,
                                        () -> FlowingFluids.config.rainLevelJumpChance)
                                ).then(Commands.literal("rain_bfs_cooldown_ticks")
                                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 40))
                                                .executes(cont -> {
                                                    FlowingFluids.config.rainBfsCooldownTicks = cont.getArgument("ticks", Integer.class);
                                                    return messageAndSaveConfig(cont, "Rain-spawned water BFS cooldown set to " + FlowingFluids.config.rainBfsCooldownTicks + " ticks.");
                                                })
                                        )
                                ).then(Commands.literal("infinite_biome_rain_fill_max_level")
                                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 8))
                                                .executes(cont -> {
                                                    FlowingFluids.config.infiniteBiomeRainFillMaxLevel = cont.getArgument("level", Integer.class);
                                                    return messageAndSaveConfig(cont, "Infinite biome rain fill cap set to " + FlowingFluids.config.infiniteBiomeRainFillMaxLevel);
                                                })
                                        )
                                ).then(booleanCommand("only_infinite_biomes_at_sea_level",
                                        "Controls if the infinite biome refilling only happens to water at exactly sea level.",
                                        a -> FlowingFluids.config.fastBiomeRefillAtSeaLevelOnly = a, () -> FlowingFluids.config.fastBiomeRefillAtSeaLevelOnly)
                                )
                        ).then(Commands.literal("rain")
                                .executes(FFCommands::rainStatus)
                                .then(Commands.literal("status")
                                        .executes(FFCommands::rainStatus))
                                .then(Commands.literal("runtime_status")
                                        .executes(FFCommands::rainRuntimeStatus))
                                .then(Commands.literal("inspect_here")
                                        .executes(FFCommands::rainInspectHere))
                                .then(Commands.literal("reload_runtime")
                                        .executes(FFCommands::rainReloadRuntime))
                                .then(Commands.literal("preset")
                                        .executes(cont -> message(cont, "Rain presets"
                                                + "\nrealistic: balanced runoff and pooling with stronger terrain response"
                                                + "\ngentle: lighter, calmer rain behavior"
                                                + "\ndownpour: aggressive pooling and runoff"
                                                + "\nreset: restore the rain realism values to defaults"))
                                        .then(Commands.literal("realistic")
                                                .executes(cont -> applyRainPreset(cont, "realistic")))
                                        .then(Commands.literal("gentle")
                                                .executes(cont -> applyRainPreset(cont, "gentle")))
                                        .then(Commands.literal("downpour")
                                                .executes(cont -> applyRainPreset(cont, "downpour")))
                                        .then(Commands.literal("reset")
                                                .executes(cont -> applyRainPreset(cont, "reset"))))
                                .then(booleanCommand("enable",
                                        "雨システム全体のON/OFF。水たまり生成や雨補給をまとめて無効化できます。",
                                        "雨システムを有効にしました。",
                                        "雨システムを無効にしました。",
                                        a -> FlowingFluids.config.enableRainSystem = a, () -> FlowingFluids.config.enableRainSystem))
                                .then(booleanCommand("biome_filter",
                                        "バイオーム毎の降水可否判定を行います。無効化すると全バイオームで雨生成を試行します。",
                                        "バイオームフィルタを有効にしました。",
                                        "バイオームフィルタを無効にしました。",
                                        a -> FlowingFluids.config.rainEnableBiomeFiltering = a, () -> FlowingFluids.config.rainEnableBiomeFiltering))
                                .then(booleanCommand("skip_infinite_biomes",
                                        "無限水判定のバイオーム（海/川/沼など）を雨生成の対象から除外します。",
                                        "無限水バイオームをスキップします。",
                                        "無限水バイオームも対象にします。",
                                        a -> FlowingFluids.config.rainSkipInfiniteWaterBiomes = a, () -> FlowingFluids.config.rainSkipInfiniteWaterBiomes))
                                .then(booleanCommand("chunk_cache",
                                        "雨生成用のチャンクキャッシュを使ってバイオーム判定を高速化します。",
                                        "チャンクキャッシュを有効にしました。",
                                        "チャンクキャッシュを無効にしました。",
                                        a -> FlowingFluids.config.rainEnableChunkCaching = a, () -> FlowingFluids.config.rainEnableChunkCaching))
                                .then(booleanCommand("multithread",
                                        "雨生成を並列スレッドで処理します。チャンク数が多いときに高速化します。",
                                        "雨生成の並列処理を有効にしました。",
                                        "雨生成の並列処理を無効にしました。",
                                        a -> FlowingFluids.config.rainEnableMultithreading = a, () -> FlowingFluids.config.rainEnableMultithreading))
                                .then(jpIntCommand("max_threads",
                                        "雨処理に使う最大スレッド数。0は自動判定。",
                                        "threads", 0, 32,
                                        a -> FlowingFluids.config.rainMaxThreads = a,
                                        () -> FlowingFluids.config.rainMaxThreads,
                                        "最大スレッド数を設定しました: "))
                                .then(jpIntCommand("generate_interval",
                                        "雨生成を試行するtick間隔。数値が小さいほど頻繁に生成を試みます。",
                                        "ticks", 1, 200,
                                        a -> FlowingFluids.config.rainGenerateIntervalTicks = a,
                                        () -> FlowingFluids.config.rainGenerateIntervalTicks,
                                        "雨生成間隔を設定しました: "))
                                .then(jpIntCommand("chunk_radius",
                                        "プレイヤー周囲で雨生成を行うチャンク半径。",
                                        "radius", 0, 8,
                                        a -> FlowingFluids.config.rainChunkRadius = a,
                                        () -> FlowingFluids.config.rainChunkRadius,
                                        "雨生成チャンク半径を設定しました: "))
                                .then(jpIntCommand("attempts_per_chunk",
                                        "1チャンクあたりの雨水スポーン試行回数。",
                                        "attempts", 0, 200,
                                        a -> FlowingFluids.config.rainAttemptsPerChunk = a,
                                        () -> FlowingFluids.config.rainAttemptsPerChunk,
                                        "雨生成試行回数を設定しました: "))
                                .then(jpFloatCommand("base_chance",
                                        "雨生成の基本確率（0.0〜1.0）。",
                                        "chance", 0f, 1f,
                                        a -> FlowingFluids.config.rainBaseGenerateChance = a,
                                        () -> FlowingFluids.config.rainBaseGenerateChance,
                                        "基本確率を設定しました: "))
                                .then(jpIntCommand("base_amount",
                                        "1回の雨生成で置く水量（内部単位）。",
                                        "amount", 1, 32,
                                        a -> FlowingFluids.config.rainBaseWaterAmount = a,
                                        () -> FlowingFluids.config.rainBaseWaterAmount,
                                        "生成水量を設定しました: "))
                                .then(jpIntCommand("max_chunks_per_tick",
                                        "1tickで処理する最大チャンク数の上限。",
                                        "chunks", 1, 512,
                                        a -> FlowingFluids.config.rainMaxChunksPerTick = a,
                                        () -> FlowingFluids.config.rainMaxChunksPerTick,
                                        "1tickあたりチャンク上限を設定しました: "))
                                .then(jpIntCommand("max_surface_search_depth",
                                        "地表を探す最大深さ。0に近いほど軽くなります。",
                                        "depth", 1, 16,
                                        a -> FlowingFluids.config.rainMaxSurfaceSearchDepth = a,
                                        () -> FlowingFluids.config.rainMaxSurfaceSearchDepth,
                                        "表面探索の深さを設定しました: "))
                                .then(jpIntCommand("max_water_stack_height",
                                        "雨で積み上げる水の最大高さ。",
                                        "height", 1, 8,
                                        a -> FlowingFluids.config.rainMaxWaterStackHeight = a,
                                        () -> FlowingFluids.config.rainMaxWaterStackHeight,
                                        "最大スタック高さを設定しました: "))
                                .then(booleanCommand("fills_higher",
                                        "雨が既存の水より1段高い位置にも水を置くか。",
                                        "雨で水を高く積む動作を有効にしました。",
                                        "雨で水を高く積む動作を無効にしました。",
                                        a -> FlowingFluids.config.rainFillsWaterHigherV2 = a, () -> FlowingFluids.config.rainFillsWaterHigherV2))
                                .then(jpFloatCommand("level_jump_chance",
                                        "雨リフィル後に追加で水位を+1する確率。",
                                        "chance", 0f, 1f,
                                        a -> FlowingFluids.config.rainLevelJumpChance = a,
                                        () -> FlowingFluids.config.rainLevelJumpChance,
                                        "水位ジャンプ確率を設定しました: "))
                                .then(jpFloatCommand("surface_spawn_chance",
                                        "雨で周囲の地面に浅い水たまりを生成する確率。",
                                        "chance", 0f, 1f,
                                        a -> FlowingFluids.config.rainSurfaceSpawnChance = a,
                                        () -> FlowingFluids.config.rainSurfaceSpawnChance,
                                        "水たまり生成確率を設定しました: "))
                                .then(jpIntCommand("surface_spawn_level",
                                        "生成する水たまりの高さ(1-8)。",
                                        "level", 1, 8,
                                        a -> FlowingFluids.config.rainSurfaceSpawnLevel = a,
                                        () -> FlowingFluids.config.rainSurfaceSpawnLevel,
                                        "水たまり高さを設定しました: "))
                                .then(jpFloatCommand("queue_soft_cap_ratio",
                                        "雨配置キューのソフト上限割合。1.0で上限なし、低いほど抑制。",
                                        "ratio", 0f, 1f,
                                        a -> FlowingFluids.config.rainQueueSoftCapRatio = a,
                                        () -> FlowingFluids.config.rainQueueSoftCapRatio,
                                        "キュー上限割合を設定しました: "))
                                .then(jpFloatCommand("queue_min_multiplier",
                                        "キューが埋まった際の最低生成倍率。",
                                        "ratio", 0f, 1f,
                                        a -> FlowingFluids.config.rainQueueMinChanceMultiplier = a,
                                        () -> FlowingFluids.config.rainQueueMinChanceMultiplier,
                                        "最低生成倍率を設定しました: "))
                                .then(jpIntCommand("placement_queue_size",
                                        "雨の水配置キューサイズ。大きいほど同時処理量が増えますがメモリ消費も増えます。",
                                        "size", 64, 4096,
                                        a -> FlowingFluids.config.rainPlacementQueueSize = a,
                                        () -> FlowingFluids.config.rainPlacementQueueSize,
                                        "配置キューサイズを設定しました: "))
                                .then(jpIntCommand("placement_merge_distance",
                                        "近傍の雨配置をまとめる距離。0で無効。",
                                        "dist", 0, 4,
                                        a -> FlowingFluids.config.rainPlacementAggregationDistance = a,
                                        () -> FlowingFluids.config.rainPlacementAggregationDistance,
                                        "配置統合距離を設定しました: "))
                                .then(jpIntCommand("placement_max_combined_amount",
                                        "まとめて配置する最大水量。",
                                        "amount", 1, 64,
                                        a -> FlowingFluids.config.rainPlacementMaxCombinedAmount = a,
                                        () -> FlowingFluids.config.rainPlacementMaxCombinedAmount,
                                        "配置合計上限を設定しました: "))
                                .then(jpIntCommand("wetness_persist_ticks",
                                        "How long absorbed ground wetness lingers before it fully dries out.",
                                        "ticks", 20, 24000,
                                        a -> FlowingFluids.config.rainWetnessPersistTicks = a,
                                        () -> FlowingFluids.config.rainWetnessPersistTicks,
                                        "Set wetness persist ticks: "))
                                .then(jpIntCommand("catchment_radius",
                                        "Radius used to sample nearby open sky for the catchment boost.",
                                        "radius", 1, 6,
                                        a -> FlowingFluids.config.rainCatchmentRadius = a,
                                        () -> FlowingFluids.config.rainCatchmentRadius,
                                        "Set catchment radius: "))
                                .then(jpFloatCommand("catchment_max_boost",
                                        "Maximum multiplier granted by local catchment sampling.",
                                        "boost", 1f, 4f,
                                        a -> FlowingFluids.config.rainCatchmentMaxBoost = a,
                                        () -> FlowingFluids.config.rainCatchmentMaxBoost,
                                        "Set catchment max boost: "))
                                .then(jpIntCommand("upstream_search_radius",
                                        "Radius used to look for higher nearby terrain that can feed runoff.",
                                        "radius", 1, 12,
                                        a -> FlowingFluids.config.rainUpstreamSearchRadius = a,
                                        () -> FlowingFluids.config.rainUpstreamSearchRadius,
                                        "Set upstream search radius: "))
                                .then(jpFloatCommand("upstream_max_boost",
                                        "Maximum runoff boost gained from higher nearby terrain samples.",
                                        "boost", 1f, 4f,
                                        a -> FlowingFluids.config.rainUpstreamMaxBoost = a,
                                        () -> FlowingFluids.config.rainUpstreamMaxBoost,
                                        "Set upstream max boost: "))
                                .then(jpFloatCommand("drizzle_multiplier",
                                        "Intensity multiplier used while the system chooses drizzle rainfall.",
                                        "multiplier", 0.1f, 4f,
                                        a -> FlowingFluids.config.rainIntensityDrizzleMultiplier = a,
                                        () -> FlowingFluids.config.rainIntensityDrizzleMultiplier,
                                        "Set drizzle multiplier: "))
                                .then(jpFloatCommand("steady_multiplier",
                                        "Intensity multiplier used while the system chooses steady rainfall.",
                                        "multiplier", 0.1f, 4f,
                                        a -> FlowingFluids.config.rainIntensitySteadyMultiplier = a,
                                        () -> FlowingFluids.config.rainIntensitySteadyMultiplier,
                                        "Set steady multiplier: "))
                                .then(jpFloatCommand("heavy_multiplier",
                                        "Intensity multiplier used while the system chooses heavy rainfall.",
                                        "multiplier", 0.1f, 4f,
                                        a -> FlowingFluids.config.rainIntensityHeavyMultiplier = a,
                                        () -> FlowingFluids.config.rainIntensityHeavyMultiplier,
                                        "Set heavy multiplier: "))
                                .then(jpFloatCommand("thunderstorm_multiplier",
                                        "Intensity multiplier used when thunderstorm rain is active.",
                                        "multiplier", 0.1f, 6f,
                                        a -> FlowingFluids.config.rainIntensityThunderstormMultiplier = a,
                                        () -> FlowingFluids.config.rainIntensityThunderstormMultiplier,
                                        "Set thunderstorm multiplier: "))
                                .then(jpIntCommand("bfs_cooldown_ticks",
                                        "雨で生成された水がBFS等を走るまでのクールダウンtick。",
                                        "ticks", 1, 60,
                                        a -> FlowingFluids.config.rainBfsCooldownTicks = a,
                                        () -> FlowingFluids.config.rainBfsCooldownTicks,
                                        "BFSクールダウンを設定しました: "))
                                .then(jpIntCommand("max_chunks_cache_time_sec",
                                        "雨チャンクキャッシュの保持時間(秒)。",
                                        "seconds", 10, 3600,
                                        a -> FlowingFluids.config.rainCacheDurationTicks = a * 20L,
                                        () -> (int)(FlowingFluids.config.rainCacheDurationTicks / 20L),
                                        "キャッシュ保持時間を設定しました(秒): "))
                        )
                ).then(Commands.literal("~debug").executes(cont -> message(cont, "Debug commands you probably don't need these."))
                        .then(booleanCommand("random_ticks_printing",
                                "Enables or disables printing of random tick events, this will spam your log with every random tick event that happens.",
                                "Random ticks printing is now enabled.",
                                "Random ticks printing is now disabled.",
                                a -> FlowingFluids.config.printRandomTicks = a, () -> FlowingFluids.config.printRandomTicks)

                        ).then(booleanCommand("water_level_tinting",
                                "Enables or disables water level tinting, this will make water change colour based on its level.",
                                "water_level_tinting is now enabled.",
                                "water_level_tinting is now disabled.",
                                a -> FlowingFluids.config.debugWaterLevelColours = a, () -> FlowingFluids.config.debugWaterLevelColours)
                        ).then(Commands.literal("kill_all_current_fluid_updates")
                                .executes(cont -> {
                                    FlowingFluids.debug_killFluidUpdatesUntilTime = System.currentTimeMillis() + 3000;
                                    return message(cont, "All fluid flowing ticks will be ignored and allowed to freeze in place over the next 3 seconds.\nAll fluids that are loaded and ticking during this time will completely stop updating and freeze in place until the next time they get updated.\n You may use the debug command \"plug_fluids_in_nearby_chunks\" to surround all these frozen fluids with appropriate blocks to prevent further flow.");
                                })
                        ).then(Commands.literal("how_many_fluids_plugged_in_world_gen_this_session")
                                .executes(cont ->
                                        message(cont, FlowingFluids.waterPluggedThisSession + " fluids have been plugged during world gen this session."))
                        ).then(Commands.literal("super_sponge_at_me")
                                .executes(cont -> {
                                    int drained = superSponge(cont.getSource().getLevel(), BlockPos.containing(cont.getSource().getPosition()), Fluids.WATER);
                                    return message(cont, drained + " blocks of water have been drained.");
                                })
                                .then(Commands.argument("fluid", BlockStateArgument.block(commandBuildContext))
                                    .executes(cont -> {
                                                var fluidState = BlockStateArgument.getBlock(cont, "fluid").getState().getFluidState();
                                                if (fluidState.isEmpty() || !(fluidState.getType() instanceof FlowingFluid flows)) {
                                                    throw notFluidException.create();
                                                }
                                        int drained = superSponge(cont.getSource().getLevel(), BlockPos.containing(cont.getSource().getPosition()), flows);
                                                return message(cont, drained + " blocks of " + flows.getSource().defaultFluidState().createLegacyBlock().getBlock().getName().getString() +" have been drained.");
                                            }
                                    )
                                )
                        ).then(booleanCommand("announce_world_gen_actions",
                                "Enables or disables world gen action announcements, this will spam your log with every world gen action that happens because of this mod, including the location of this action (E.G. the plug fluids during world gen feature).",
                                "World gen action announcements are now enabled.",
                                "World gen action announcements are now disabled.",
                                a -> FlowingFluids.config.announceWorldGenActions = a, () -> FlowingFluids.config.announceWorldGenActions)
                        ).then(Commands.literal("surround_all_fluids_in_nearby_chunks_with_blocks")
                                .executes(cont ->{
                                    var level = cont.getSource().getLevel();
                                    var pos = cont.getSource().getPosition();
                                    var posChunk = new ChunkPos(new BlockPos((int) pos.x, (int) pos.y, (int) pos.z));

                                    var dist = level.getServer().getPlayerList().getSimulationDistance();

                                    int count = FlowingFluids.waterPluggedThisSession;
                                    for (int x = posChunk.x-dist; x <= posChunk.x+dist; x++) {
                                        for (int z = posChunk.z-dist; z <= posChunk.z+dist; z++) {
                                            if (level.hasChunk(x, z)) {
                                                PlugWaterFeature.processChunk(level, new ChunkPos(x, z), level.getChunk(x, z));
                                            }
                                        }
                                    }
                                    return message(cont, "All fluids, within "+dist+" chunks of you, have had any fluids that are exposed to air plugged up with appropriate blocks.\n" +
                                            "This will not affect any fluids that are not exposed to air, or are already plugged.\n" +
                                            "This has plugged " + (FlowingFluids.waterPluggedThisSession - count) + " fluids in total.");
                                })
                        ).then(Commands.literal("force_tick_all_fluids_in_nearby_chunks")
                                        .executes(cont ->{
                                            var level = cont.getSource().getLevel();
                                            var pos = cont.getSource().getPosition();
                                            var posChunk = new ChunkPos(new BlockPos((int) pos.x, (int) pos.y, (int) pos.z));

                                            var dist = level.getServer().getPlayerList().getSimulationDistance();
                                            var rand = level.getRandom();
                                            final AtomicInteger count = new AtomicInteger();
                                            for (int x = posChunk.x-dist; x <= posChunk.x+dist; x++) {
                                                for (int z = posChunk.z-dist; z <= posChunk.z+dist; z++) {
                                                    if (level.hasChunk(x, z)) {
                                                        level.getChunk(x, z).findBlocks(BlockBehaviour.BlockStateBase::liquid,
                                                                (blockPos, blockState) -> {
                                                            level.scheduleTick(blockPos, blockState.getFluidState().getType(), 1 + rand.nextInt(200));
                                                            count.incrementAndGet();
                                                        });
                                                    }
                                                }
                                            }
                                            return message(cont, "All fluids, within "+dist+" chunks of you, have been forcibly added to the tick queue with random intervals over the next 0-10 seconds, EXPECT SOME LAG! Amount force ticked = " + count.get());
                                        })
                        ).then(Commands.literal("is_infinite_water_biome")
                                        .executes(cont ->{
                                            var level = cont.getSource().getLevel();
                                            var pos = cont.getSource().getPosition();
                                            return message(cont, "You are "+
                                                    (FFFluidUtils.matchInfiniteBiomes(level.getBiome(new BlockPos((int) pos.x, (int) pos.y, (int) pos.z)))
                                                            ? "IN" : "NOT IN") + " an Infinite biome. By default these are: Oceans, Rivers, and Swamps.\n" +
                                                                    "Mods can add their own via the api but most modded oceans and rivers should be accounted for automatically by this mod.");
                                        })
                        )
                );

        if (FlowingFluidsPlatform.isThisModLoaded("create")) {
            commands.then(Commands.literal("create_mod_compat")
                    .executes(commandContext -> message(commandContext, "Settings for Create Mod compatibility, use these to change how fluids interact with Create water wheels and pipes."))
                    .then(Commands.literal("info")
                            .executes(c -> message(c, "The Create mod uses water wheels as it's most primitive power source. Flowing Fluids has settings to change how these water wheels get powered due to the additional challenges of the flowing fluids mod interactions with fluids."))
                    )
                    .then(Commands.literal("water_wheel_requirements")
                            .executes(cont -> message(cont, "Changes how the Create Mod's water wheels interact with fluids, select an mode to get further information. Default is flow. Water wheel mode is currently set to " + FlowingFluids.config.create_waterWheelMode))
                            .then(Commands.literal("flow")
                                    .executes(cont -> {
                                        FlowingFluids.config.create_waterWheelMode = FFConfig.CreateWaterWheelMode.REQUIRE_FLOW;
                                        return messageAndSaveConfig(cont, "Water wheel mode is now set to require flow.\nWater wheels will only spin if the water has a level gradient, which almost always requires the water to be actively flowing.");
                                    })
                            ).then(Commands.literal("flow_or_river")
                                    .executes(cont -> {
                                        FlowingFluids.config.create_waterWheelMode = FFConfig.CreateWaterWheelMode.REQUIRE_FLOW_OR_RIVER;
                                        return messageAndSaveConfig(cont, "Water wheel mode is now set to require flow or river.\nWater wheels will only spin if the water has a level gradient, which almost always requires the water to be actively flowing, or if the water is in a river biome touching any water, and within 5 blocks of sea level. Will always spin in the same direction when using a river as a source.");
                                    })
                            ).then(Commands.literal("flow_or_river_opposite_spin")
                                    .executes(cont -> {
                                        FlowingFluids.config.create_waterWheelMode = FFConfig.CreateWaterWheelMode.REQUIRE_FLOW_OR_RIVER_OPPOSITE;
                                        return messageAndSaveConfig(cont, "Water wheel mode is now set to require flow or river with opposite spin.\nWater wheels will only spin if the water has a level gradient, which almost always requires the water to be actively flowing, or if the water is in a river biome touching any water, and within 5 blocks of sea level. Will spin in the opposite direction to the other river mode.");
                                    })
                            ).then(Commands.literal("fluid")
                                    .executes(cont -> {
                                        FlowingFluids.config.create_waterWheelMode = FFConfig.CreateWaterWheelMode.REQUIRE_FLUID;
                                        return messageAndSaveConfig(cont, "Water wheel mode is now set to only require fluid to be present in the checked spaces. Will always spin in the same direction.");
                                    })
                            ).then(Commands.literal("fluid_opposite_spin")
                                    .executes(cont -> {
                                        FlowingFluids.config.create_waterWheelMode = FFConfig.CreateWaterWheelMode.REQUIRE_FLUID_OPPOSITE;
                                        return messageAndSaveConfig(cont, "Water wheel mode is now set to only require fluid to be present in the checked spaces. Will spin in the opposite direction to the other fluid mode.");
                                    })
                            ).then(Commands.literal("full_fluid")
                                    .executes(cont -> {
                                        FlowingFluids.config.create_waterWheelMode = FFConfig.CreateWaterWheelMode.REQUIRE_FULL_FLUID;
                                        return messageAndSaveConfig(cont, "Water wheel mode is now set to only require a full 8 levels of fluid to be present in the checked spaces. Will always spin in the same direction.");
                                    })
                            ).then(Commands.literal("full_fluid_opposite_spin")
                                    .executes(cont -> {
                                        FlowingFluids.config.create_waterWheelMode = FFConfig.CreateWaterWheelMode.REQUIRE_FULL_FLUID_OPPOSITE;
                                        return messageAndSaveConfig(cont, "Water wheel mode is now set to only require a full 8 levels of fluid to be present in the checked spaces. Will spin in the opposite direction to the other full fluid mode.");
                                    })
                            ).then(Commands.literal("always")
                                    .executes(cont -> {
                                        FlowingFluids.config.create_waterWheelMode = FFConfig.CreateWaterWheelMode.ALWAYS;
                                        return messageAndSaveConfig(cont, "water wheel mode is now set to always spin.\nWater wheels will always spin with max strength regardless of present fluids.");
                                    })
                            ).then(Commands.literal("always_opposite_spin")
                                    .executes(cont -> {
                                        FlowingFluids.config.create_waterWheelMode = FFConfig.CreateWaterWheelMode.ALWAYS_OPPOSITE;
                                        return messageAndSaveConfig(cont, "water wheel mode is now set to always spin with opposite spin.\nWater wheels will always spin with max strength regardless of present fluids, and will spin in the opposite direction to the other always mode.");
                                    })
                            )
                    ).then(Commands.literal("pipes")
                            .then(booleanCommand("infinite_pipe_fluid_source",
                                    "Enables or disables infinite pipe fluid source, if disabled pipes will consume the source fluid block.",
                                    "Pipes will now not consume the source fluid block.",
                                    "Pipes will now consume the source fluid block.",
                                    a -> FlowingFluids.config.create_infinitePipes = a, () -> FlowingFluids.config.create_infinitePipes)
                            ).then(Commands.literal("info")
                                    .executes(c -> message(c, "Create mod pipes will draw fluids only when the entire input block is full (8 levels of fluid). This is required for fluid levels to remain consistent between bucket and other usages, and for Flowing Fluids to be as unobtrusive as possible to the Create mod's inner workings. That being said if you want an easy time of using pipes without worrying about water usage, then enable the infinite pipes setting. You can also disable Create pipes from outputting water blocks in it's own config settings"))
                            )
                    )

            );
        }

        dispatcher.register(commands);
    }

    private static int superSponge(Level level, BlockPos pos, Fluid fluid) {

        final var yes = #if MC>=MC_21_4 BlockPos.TraversalNodeStatus.ACCEPT #else true #endif ;
        final var no = #if MC>=MC_21_4 BlockPos.TraversalNodeStatus.SKIP #else false #endif ;

        return BlockPos.breadthFirstTraversal(pos, 32, 10000, (blockPos, consumer) -> {
            for (Direction direction : Direction.values()) {
                consumer.accept(blockPos.relative(direction));
            }
        }, (blockPos2) -> {
            if (blockPos2.equals(pos)) {
                return yes;
            } else {
                BlockState blockState = level.getBlockState(blockPos2);
                FluidState fluidState = level.getFluidState(blockPos2);
                if (!fluidState.getType().isSame(fluid)) {
                    return no;
                } else {
                    Block block = blockState.getBlock();
                    if (block instanceof final BucketPickup bucketPickup) {
                        if (!bucketPickup.pickupBlock(#if MC >= MC_21 null, #endif level, blockPos2, blockState).isEmpty()) {
                            return yes;
                        }
                    }

                    if (blockState.getBlock() instanceof LiquidBlock) {
                        level.setBlock(blockPos2, Blocks.AIR.defaultBlockState(), 3);
                    } else {
                        if (!blockState.is(Blocks.KELP) && !blockState.is(Blocks.KELP_PLANT) && !blockState.is(Blocks.SEAGRASS) && !blockState.is(Blocks.TALL_SEAGRASS)) {
                            return no;
                        }

                        BlockEntity blockEntity = blockState.hasBlockEntity() ? level.getBlockEntity(blockPos2) : null;
                        Block.dropResources(blockState, level, blockPos2, blockEntity);
                        level.setBlock(blockPos2, Blocks.AIR.defaultBlockState(), 3);
                    }

                    return yes;
                }
            }
        });
    }
}
