package traben.flowing_fluids.config;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
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
import traben.flowing_fluids.AdaptiveTickScheduler;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FluidComponentGraph;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.FlowingFluidsPlatform;
import traben.flowing_fluids.PlugWaterFeature;
import traben.flowing_fluids.drying.DryingEventSystem;
import traben.flowing_fluids.flood.FloodEventSystem;
import traben.flowing_fluids.performance.FluidAutoTickDelay;
import traben.flowing_fluids.rain.RainWaterSystem;
import traben.flowing_fluids.water.WaterPressureSystem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class FFCommands {
    private static final List<String> DEFAULT_INFINITE_BIOME_ENTRIES = List.of(
            "#minecraft:is_ocean",
            "#minecraft:is_river",
            "#minecraft:is_beach",
            "minecraft:swamp",
            "minecraft:mangrove_swamp"
    );

    private static int messageAndSaveConfig(CommandContext<CommandSourceStack> context, String text) {
        FlowingFluids.saveConfig();
        FlowingFluids.applyConfigRuntime();
        FlowingFluids.refreshFluidRuntime(context.getSource().getServer());
        context.getSource().getServer().getPlayerList().getPlayers().forEach(FlowingFluidsPlatform::sendConfigToClient);
        return message(context, text);
    }

    private static int message(CommandContext<CommandSourceStack> context, String text) {
        //always executed server side
        String inputCommand = context.getInput();
        context.getSource().sendSystemMessage(Component.literal("\n§7§o/" + inputCommand + "§r\n" + text + "\n§7_____________________________"));
        return 1;
    }

    private static int componentGraphStatus(CommandContext<CommandSourceStack> context) {
        return message(context, FluidComponentGraph.describeStatus(context.getSource().getLevel()));
    }

    private static int clearComponentGraphRuntime(CommandContext<CommandSourceStack> context) {
        FluidComponentGraph.clearDimension(context.getSource().getLevel());
        return message(context, "Fluid component graph runtime cache cleared for this dimension.");
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

    private static int dryingStatus(CommandContext<CommandSourceStack> context) {
        return message(context, DryingEventSystem.describeStatus(context.getSource().getLevel()));
    }

    private static int floodStatus(CommandContext<CommandSourceStack> context) {
        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
        return message(context, "Flood event settings"
                + "\nEnabled: " + FlowingFluids.config.enableFloodEvents
                + "\nNatural storm chance per day: " + FlowingFluids.config.floodStartChancePerDay
                + "\nRequires rain: " + FlowingFluids.config.floodRequiresRain
                + "\nThunderstorm multiplier: " + FlowingFluids.config.floodThunderstormChanceMultiplier
                + "\nAnnounce events: " + FlowingFluids.config.announceFloodEvents
                + "\n" + FloodEventSystem.describeFlood(context.getSource().getLevel(), pos));
    }

    private static int waterPressureStatus(CommandContext<CommandSourceStack> context) {
        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
        return message(context, WaterPressureSystem.describeStatus(context.getSource().getLevel(), pos));
    }

    private static int autoTickDelayStatus(CommandContext<CommandSourceStack> context) {
        return message(context, FluidAutoTickDelay.describeStatus());
    }

    private static String describeSeaLevelOverrideStatus(Level level) {
        int dimensionOverride = FlowingFluids.config.dimensionSeaLevelOverrides
                .getOrDefault(level.dimensionType().hashCode(), Integer.MIN_VALUE);
        return "Sea level override controls the sea level used by Flowing Fluids for infinite-biome refill, surface drain, broad-water checks, and related water behaviour."
                + "\nCurrent dimension: " + level.dimension().location()
                + "\nVanilla sea level: " + level.getSeaLevel()
                + "\nEffective Flowing Fluids sea level: " + FFFluidUtils.seaLevel(level)
                + "\nCurrent dimension-type hash: " + level.dimensionType().hashCode()
                + "\nDimension override: " + (dimensionOverride == Integer.MIN_VALUE ? "none" : dimensionOverride)
                + "\nDefault override: " + (FlowingFluids.config.defaultSeaLevelOverride == Integer.MIN_VALUE ? "none" : FlowingFluids.config.defaultSeaLevelOverride)
                + "\nStored dimension overrides: " + FlowingFluids.config.dimensionSeaLevelOverrides.size()
                + "\nCommands:"
                + "\n- here status / here set <y> / here clear / here use_vanilla"
                + "\n- default status / default set <y> / default clear"
                + "\n- list / clear_all";
    }

    private static String describeSeaLevelOverrideList() {
        if (FlowingFluids.config.dimensionSeaLevelOverrides.isEmpty()) {
            return "No dimension sea level overrides are currently stored.\nDefault override: "
                    + (FlowingFluids.config.defaultSeaLevelOverride == Integer.MIN_VALUE ? "none" : FlowingFluids.config.defaultSeaLevelOverride);
        }

        StringBuilder builder = new StringBuilder("Stored dimension sea level overrides:");
        for (Int2IntMap.Entry entry : FlowingFluids.config.dimensionSeaLevelOverrides.int2IntEntrySet()) {
            builder.append("\n- dimensionTypeHash=").append(entry.getIntKey())
                    .append(" -> seaLevel=").append(entry.getIntValue());
        }
        builder.append("\nDefault override: ")
                .append(FlowingFluids.config.defaultSeaLevelOverride == Integer.MIN_VALUE ? "none" : FlowingFluids.config.defaultSeaLevelOverride);
        return builder.toString();
    }

    private static String describeSeaLevelHereStatus(Level level) {
        int dimensionOverride = FlowingFluids.config.dimensionSeaLevelOverrides
                .getOrDefault(level.dimensionType().hashCode(), Integer.MIN_VALUE);
        return "Current dimension sea level override status"
                + "\nDimension: " + level.dimension().location()
                + "\nDimension-type hash: " + level.dimensionType().hashCode()
                + "\nVanilla sea level: " + level.getSeaLevel()
                + "\nDimension override: " + (dimensionOverride == Integer.MIN_VALUE ? "none" : dimensionOverride)
                + "\nEffective Flowing Fluids sea level: " + FFFluidUtils.seaLevel(level);
    }

    private static String describeSeaLevelDefaultStatus() {
        return "Default sea level override status"
                + "\nDefault override: " + (FlowingFluids.config.defaultSeaLevelOverride == Integer.MIN_VALUE ? "none" : FlowingFluids.config.defaultSeaLevelOverride)
                + "\nStored dimension overrides: " + FlowingFluids.config.dimensionSeaLevelOverrides.size();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> seaLevelOverrides() {
        return Commands.literal("sea_level_override")
                .executes(cont -> message(cont, describeSeaLevelOverrideStatus(cont.getSource().getLevel())))
                .then(Commands.literal("status")
                        .executes(cont -> message(cont, describeSeaLevelOverrideStatus(cont.getSource().getLevel()))))
                .then(Commands.literal("here")
                        .executes(cont -> message(cont, describeSeaLevelHereStatus(cont.getSource().getLevel())))
                        .then(Commands.literal("status")
                                .executes(cont -> message(cont, describeSeaLevelHereStatus(cont.getSource().getLevel()))))
                        .then(Commands.literal("vanilla")
                                .executes(cont -> message(cont, "Vanilla sea level for this dimension: " + cont.getSource().getLevel().getSeaLevel())))
                        .then(Commands.literal("effective")
                                .executes(cont -> message(cont, "Effective Flowing Fluids sea level for this dimension: " + FFFluidUtils.seaLevel(cont.getSource().getLevel()))))
                        .then(Commands.literal("override")
                                .executes(cont -> {
                                    Level level = cont.getSource().getLevel();
                                    int dimensionOverride = FlowingFluids.config.dimensionSeaLevelOverrides
                                            .getOrDefault(level.dimensionType().hashCode(), Integer.MIN_VALUE);
                                    return message(cont, "Current dimension override"
                                            + "\nDimension: " + level.dimension().location()
                                            + "\nDimension-type hash: " + level.dimensionType().hashCode()
                                            + "\nDimension override: " + (dimensionOverride == Integer.MIN_VALUE ? "none" : dimensionOverride)
                                            + "\nDefault override: " + (FlowingFluids.config.defaultSeaLevelOverride == Integer.MIN_VALUE ? "none" : FlowingFluids.config.defaultSeaLevelOverride)
                                            + "\nEffective Flowing Fluids sea level: " + FFFluidUtils.seaLevel(level));
                                }))
                        .then(Commands.literal("set")
                                .then(Commands.argument("y", IntegerArgumentType.integer(-999999, 999999))
                                        .executes(cont -> {
                                            int override = IntegerArgumentType.getInteger(cont, "y");
                                            FlowingFluids.config.dimensionSeaLevelOverrides.put(cont.getSource().getLevel().dimensionType().hashCode(), override);
                                            return messageAndSaveConfig(cont, "Sea level override for this dimension is now set to " + override + ".\nEffective Flowing Fluids sea level here is now " + FFFluidUtils.seaLevel(cont.getSource().getLevel()) + ".");
                                        })))
                        .then(Commands.literal("clear")
                                .executes(cont -> {
                                    FlowingFluids.config.dimensionSeaLevelOverrides.remove(cont.getSource().getLevel().dimensionType().hashCode());
                                    return messageAndSaveConfig(cont, "Sea level override for this dimension has been removed.\nEffective Flowing Fluids sea level here is now " + FFFluidUtils.seaLevel(cont.getSource().getLevel()) + ".");
                                }))
                        .then(Commands.literal("use_vanilla")
                                .executes(cont -> {
                                    int vanillaSeaLevel = cont.getSource().getLevel().getSeaLevel();
                                    FlowingFluids.config.dimensionSeaLevelOverrides.put(cont.getSource().getLevel().dimensionType().hashCode(), vanillaSeaLevel);
                                    return messageAndSaveConfig(cont, "Sea level override for this dimension is now pinned to the current vanilla sea level: " + vanillaSeaLevel + ".");
                                })))
                .then(Commands.literal("default")
                        .executes(cont -> message(cont, describeSeaLevelDefaultStatus()))
                        .then(Commands.literal("status")
                                .executes(cont -> message(cont, describeSeaLevelDefaultStatus())))
                        .then(Commands.literal("set")
                                .then(Commands.argument("y", IntegerArgumentType.integer(-999999, 999999))
                                        .executes(cont -> {
                                            int override = IntegerArgumentType.getInteger(cont, "y");
                                            FlowingFluids.config.defaultSeaLevelOverride = override;
                                            return messageAndSaveConfig(cont, "Default sea level override is now set to " + override + ".");
                                        })))
                        .then(Commands.literal("clear")
                                .executes(cont -> {
                                    FlowingFluids.config.defaultSeaLevelOverride = Integer.MIN_VALUE;
                                    return messageAndSaveConfig(cont, "Default sea level override has been removed.");
                                })))
                .then(Commands.literal("list")
                        .executes(cont -> message(cont, describeSeaLevelOverrideList())))
                .then(Commands.literal("clear_all")
                        .executes(cont -> {
                            int cleared = FlowingFluids.config.dimensionSeaLevelOverrides.size();
                            FlowingFluids.config.dimensionSeaLevelOverrides.clear();
                            return messageAndSaveConfig(cont, "Cleared all stored dimension sea level overrides (" + cleared + ").");
                        }));
    }

    private static int springStatus(CommandContext<CommandSourceStack> context) {
        return message(context, "Spring settings overview"
                + "\nBiome-aware generation: on"
                + "\nWet cave biomes now grow more water springs, dry and hot biomes grow fewer."
                + "\nDeep underground lava springs now favor hotter and drier overworld biomes."
                + "\nRandom breakage: " + FlowingFluids.config.enableSpringRandomBreakage
                + "\nBreak chance: " + FlowingFluids.config.springRandomBreakChance
                + "\nOverworld spawn multiplier: " + FlowingFluids.config.overworldSpringSpawnMultiplier
                + "\nNether spawn multiplier: " + FlowingFluids.config.netherSpringSpawnMultiplier
                + "\nDeep lava spawn multiplier: " + FlowingFluids.config.deepLavaSpringSpawnMultiplier
                + "\nDeep lava extra reject chance: " + FlowingFluids.config.deepLavaSpringExtraRejectChance
                + "\nDeep lava max placements per feature: " + FlowingFluids.config.deepLavaSpringMaxPlacementsPerFeature
                + "\nCapped pressure head: " + FlowingFluids.config.enableCappedSpringPressureHead
                + "\nWater spring emission / pulse interval: " + FlowingFluids.config.waterSpringEmissionMultiplier
                + " / " + FlowingFluids.config.waterSpringPulseIntervalMultiplier
                + "\nLava spring emission / pulse interval: " + FlowingFluids.config.lavaSpringEmissionMultiplier
                + " / " + FlowingFluids.config.lavaSpringPulseIntervalMultiplier
                + "\nTips:"
                + "\n- Lush caves, dripstone caves, rivers, swamps: more water springs"
                + "\n- Deserts, badlands, savannas: fewer water springs"
                + "\n- Badlands, deserts, deep dark: more deep lava springs"
                + "\n- Nether lava springs use their own multiplier, so you can tune them separately from the overworld"
                + "\n- Large lava lakes now boost lava spring count and make stronger vents more likely"
                + "\n- Capped pressure head keeps upward springs filled right under a stopper block so they can spill sideways instead of relaxing into a short pulse"
                + "\nUse `/flowing_fluids settings springs random_breakage on|off` or `random_breakage_chance <value>` to tune collapse mode."
                + "\nUse `/flowing_fluids settings springs overworld_spawn_multiplier <value>` and `nether_spawn_multiplier <value>` to split overworld vs nether generation."
                + "\nUse `/flowing_fluids settings springs deep_lava_spawn_multiplier <value>` to make lava springs rarer or more common."
                + "\nUse `/flowing_fluids settings springs water_emission_multiplier <value>` or `lava_emission_multiplier <value>` to change how hard springs push."
                + "\nUse `/flowing_fluids settings springs water_pulse_interval_multiplier <value>` or `lava_pulse_interval_multiplier <value>` to make pulsing faster or slower."
                + "\nUse `/flowing_fluids nether_lava info` for the Nether lava event command guide.");
    }

    private static int startFloodHere(CommandContext<CommandSourceStack> context, int radius, int durationSeconds, int waterlineY) {
        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
        boolean started = FloodEventSystem.startFlood(
                context.getSource().getLevel(),
                pos,
                radius,
                durationSeconds * 20,
                waterlineY
        );
        if (!started) {
            return message(context, "Flood events are disabled. Enable `/flowing_fluids settings flood enable on` first.");
        }
        return message(context, "Started a flood event around " + pos + ".\n"
                + FloodEventSystem.describeFlood(context.getSource().getLevel(), pos));
    }

    private static String describeFlowSpeedStatus() {
        return "流速ステータス"
                + "\nこの機能は、重い連続速度シミュレーションではなく、軽量な『擬似流速』レイヤーです。"
                + "\n既存の水路プロフィールと momentum を再利用して、水を still / slow / normal / fast / torrent のような段階として扱います。"
                + "\n有効: " + FlowingFluids.config.enableFlowSpeedControl
                + "\n強さ: " + FlowingFluids.config.flowSpeedStrength
                + "\n影響するもの:"
                + "\n- 薄い水際の広がりに少し前進バイアスを足します"
                + "\n- 速い流れのプロフィールで移送量を少し強めます"
                + "\n- momentum 記録を補強して、水路や決壊流が少しだけ方向性を持ちやすくなります"
                + "\n切り替え: `/flowing_fluids settings behaviour flow_speed enable on|off`"
                + "\n強さ変更: `/flowing_fluids settings behaviour flow_speed strength <value>`";
    }

    private static int flowSpeedStatus(CommandContext<CommandSourceStack> context) {
        return message(context, describeFlowSpeedStatus());
    }

    private static int mudificationStatus(CommandContext<CommandSourceStack> context) {
        return message(context, "Mudification settings"
                + "\nEnabled: " + FlowingFluids.config.enableMudification
                + "\nStrength: " + FlowingFluids.config.mudificationStrength
                + "\nAffects banks: " + FlowingFluids.config.mudificationAffectsBanks
                + "\nOnly successful flowing-water writes build exposure."
                + "\nPlayer-placed soil stays protected, but farmland can still turn to mud.");
    }

    private static int hydraulicBlocksStatus(CommandContext<CommandSourceStack> context) {
        return message(context, "Hydraulic block settings"
                + "\nEnabled: " + FlowingFluids.config.enableHydraulicBlocks
                + "\nWaterway liner: strongly lowers channel drag on narrow lined beds."
                + "\nPressure nozzle: pumps water from its back face to its front face in any of 6 directions."
                + "\nPressure nozzle also pushes water touching its four side faces toward the nozzle facing."
                + "\nPump transfers conserve water by moving only existing levels and retaining source water."
                + "\nHydraulic siphons can lift through lined/nozzle paths, but still outlet below the source surface.");
    }

    private static int siphonsStatus(CommandContext<CommandSourceStack> context) {
        return message(context, "Siphon settings"
                + "\nEnabled: " + FlowingFluids.config.enableSiphons
                + "\nHydraulic max search nodes: " + FlowingFluids.config.hydraulicSiphonMaxSearchNodes
                + "\nHydraulic max path length: " + FlowingFluids.config.hydraulicSiphonMaxPathLength
                + "\nHydraulic max lift: " + FlowingFluids.config.hydraulicSiphonMaxLift
                + "\nHydraulic source surface scan nodes: " + FlowingFluids.config.hydraulicSiphonSourceSurfaceScanNodes
                + "\nHydraulic max pressure head: " + FlowingFluids.config.hydraulicSiphonMaxPressureHead
                + "\nHydraulic max transfer per tick: " + FlowingFluids.config.hydraulicSiphonMaxTransferPerTick
                + "\nNatural terrain siphons: " + FlowingFluids.config.enableNaturalTerrainSiphons
                + "\nNatural max search nodes: " + FlowingFluids.config.naturalSiphonMaxSearchNodes
                + "\nNatural max path length: " + FlowingFluids.config.naturalSiphonMaxPathLength
                + "\nNatural max lift: " + FlowingFluids.config.naturalSiphonMaxLift
                + "\nNatural min filled amount: " + FlowingFluids.config.naturalSiphonMinFilledAmount
                + "\nNatural max transfer per tick: " + FlowingFluids.config.naturalSiphonMaxTransferPerTick
                + "\nNatural cooldown ticks: " + FlowingFluids.config.naturalSiphonCooldownTicks
                + "\nNatural require enclosed path: " + FlowingFluids.config.naturalSiphonRequireEnclosedPath
                + "\nNatural allow open surface: " + FlowingFluids.config.naturalSiphonAllowOpenSurface
                + "\nSame-level outlets anywhere: " + FlowingFluids.config.siphonSameLevelOutletsAnywhere
                + "\nHydraulic siphons use guide/nozzle pressure. Natural siphons use bounded terrain pressure, overtop cells, and cave confinement.");
    }

    private static int hydraulicSiphonsStatus(CommandContext<CommandSourceStack> context) {
        return message(context, "Hydraulic siphon settings"
                + "\nGlobal siphons enabled: " + FlowingFluids.config.enableSiphons
                + "\nHydraulic blocks enabled: " + FlowingFluids.config.enableHydraulicBlocks
                + "\nMax search nodes: " + FlowingFluids.config.hydraulicSiphonMaxSearchNodes
                + "\nMax path length: " + FlowingFluids.config.hydraulicSiphonMaxPathLength
                + "\nMax lift: " + FlowingFluids.config.hydraulicSiphonMaxLift
                + "\nSource surface scan nodes: " + FlowingFluids.config.hydraulicSiphonSourceSurfaceScanNodes
                + "\nMax pressure head: " + FlowingFluids.config.hydraulicSiphonMaxPressureHead
                + "\nMax transfer per tick: " + FlowingFluids.config.hydraulicSiphonMaxTransferPerTick
                + "\nPressure nozzles and liners still conserve water; they just make the bounded search more willing to climb and overtop.");
    }

    private static int naturalSiphonsStatus(CommandContext<CommandSourceStack> context) {
        return message(context, "Natural terrain siphon settings"
                + "\nGlobal siphons enabled: " + FlowingFluids.config.enableSiphons
                + "\nNatural terrain siphons enabled: " + FlowingFluids.config.enableNaturalTerrainSiphons
                + "\nMax search nodes: " + FlowingFluids.config.naturalSiphonMaxSearchNodes
                + "\nMax path length: " + FlowingFluids.config.naturalSiphonMaxPathLength
                + "\nMax lift: " + FlowingFluids.config.naturalSiphonMaxLift
                + "\nMin path fill amount: " + FlowingFluids.config.naturalSiphonMinFilledAmount
                + "\nMax transfer per tick: " + FlowingFluids.config.naturalSiphonMaxTransferPerTick
                + "\nCooldown ticks: " + FlowingFluids.config.naturalSiphonCooldownTicks
                + "\nRequire enclosed path: " + FlowingFluids.config.naturalSiphonRequireEnclosedPath
                + "\nAllow open surface paths: " + FlowingFluids.config.naturalSiphonAllowOpenSurface
                + "\nThis is the block-free pressure path for overflows, sea-floor cave mouths, and trapped air pockets.");
    }

    private static int pressureSiphonsStatus(CommandContext<CommandSourceStack> context) {
        return message(context, "Pressure siphon integration"
                + "\nSiphons enabled: " + FlowingFluids.config.enableSiphons
                + "\nNatural terrain siphons: " + FlowingFluids.config.enableNaturalTerrainSiphons
                + "\nCavity pressure rise: " + FlowingFluids.config.enableCavityPressureRise
                + "\nCavity pressure strength: " + FlowingFluids.config.cavityPressureStrength
                + "\nConnected head strength: " + FlowingFluids.config.connectedHeadStrength
                + "\nHead sample distance: " + FlowingFluids.config.hydraulicSampleDistance
                + "\nSame-level outlets anywhere: " + FlowingFluids.config.siphonSameLevelOutletsAnywhere
                + "\nUse this group when tuning sea overflows into caves or pressure-fed cave filling.");
    }

    private static int applyCaveFillSiphonPreset(CommandContext<CommandSourceStack> context) {
        FlowingFluids.config.enableSiphons = true;
        FlowingFluids.config.enableNaturalTerrainSiphons = true;
        FlowingFluids.config.enableCavityPressureRise = true;
        FlowingFluids.config.naturalSiphonMaxLift = 4;
        FlowingFluids.config.naturalSiphonMinFilledAmount = 6;
        FlowingFluids.config.naturalSiphonMaxTransferPerTick = 2;
        FlowingFluids.config.naturalSiphonRequireEnclosedPath = true;
        FlowingFluids.config.naturalSiphonAllowOpenSurface = false;
        FlowingFluids.config.cavityPressureStrength = Math.max(FlowingFluids.config.cavityPressureStrength, 1.0f);
        FlowingFluids.config.connectedHeadStrength = Math.max(FlowingFluids.config.connectedHeadStrength, 0.8f);
        return messageAndSaveConfig(context, "Applied cave-fill pressure siphon preset. Natural enclosed overflows and cavity filling are now biased on.");
    }

    private static int applyConservativeSiphonPreset(CommandContext<CommandSourceStack> context) {
        FlowingFluids.config.enableSiphons = true;
        FlowingFluids.config.enableNaturalTerrainSiphons = false;
        FlowingFluids.config.naturalSiphonMaxTransferPerTick = 1;
        FlowingFluids.config.naturalSiphonMinFilledAmount = 8;
        FlowingFluids.config.naturalSiphonMaxLift = 2;
        FlowingFluids.config.naturalSiphonAllowOpenSurface = false;
        FlowingFluids.config.siphonSameLevelOutletsAnywhere = false;
        return messageAndSaveConfig(context, "Applied conservative siphon preset. Hydraulic siphons remain available, natural terrain siphons are off.");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> siphonsCommand() {
        return Commands.literal("siphons")
                .executes(FFCommands::siphonsStatus)
                .then(Commands.literal("status")
                        .executes(FFCommands::siphonsStatus))
                .then(booleanCommand("enable",
                        "Controls whether bounded water siphons can run at all.",
                        a -> FlowingFluids.config.enableSiphons = a,
                        () -> FlowingFluids.config.enableSiphons))
                .then(booleanCommand("natural_enable",
                        "Controls the experimental natural-terrain siphon mode. Hydraulic guide/nozzle siphons are separate.",
                        a -> FlowingFluids.config.enableNaturalTerrainSiphons = a,
                        () -> FlowingFluids.config.enableNaturalTerrainSiphons))
                .then(Commands.literal("preset")
                        .executes(cont -> message(cont, "Siphon presets: cave_fill, conservative"))
                        .then(Commands.literal("cave_fill")
                                .executes(FFCommands::applyCaveFillSiphonPreset))
                        .then(Commands.literal("conservative")
                                .executes(FFCommands::applyConservativeSiphonPreset)))
                .then(hydraulicSiphonsCommand())
                .then(naturalSiphonsCommand())
                .then(pressureSiphonsCommand())
                .then(intCommand("hydraulic_max_search_nodes",
                        "Maximum BFS nodes per hydraulic siphon search.",
                        "nodes", 64, 512,
                        a -> FlowingFluids.config.hydraulicSiphonMaxSearchNodes = a,
                        () -> FlowingFluids.config.hydraulicSiphonMaxSearchNodes))
                .then(intCommand("hydraulic_max_path_length",
                        "Maximum guide/nozzle path length for hydraulic siphons.",
                        "length", 16, 128,
                        a -> FlowingFluids.config.hydraulicSiphonMaxPathLength = a,
                        () -> FlowingFluids.config.hydraulicSiphonMaxPathLength))
                .then(intCommand("hydraulic_max_lift",
                        "Maximum blocks a hydraulic siphon may rise above the inlet surface.",
                        "blocks", 0, 32,
                        a -> FlowingFluids.config.hydraulicSiphonMaxLift = a,
                        () -> FlowingFluids.config.hydraulicSiphonMaxLift))
                .then(intCommand("hydraulic_source_surface_scan_nodes",
                        "Connected source cells checked to find the hydraulic source water surface.",
                        "nodes", 16, 512,
                        a -> FlowingFluids.config.hydraulicSiphonSourceSurfaceScanNodes = a,
                        () -> FlowingFluids.config.hydraulicSiphonSourceSurfaceScanNodes))
                .then(intCommand("hydraulic_max_pressure_head",
                        "Maximum carried pressure head for guide/nozzle siphon paths.",
                        "blocks", 0, 32,
                        a -> FlowingFluids.config.hydraulicSiphonMaxPressureHead = a,
                        () -> FlowingFluids.config.hydraulicSiphonMaxPressureHead))
                .then(intCommand("hydraulic_max_transfer_per_tick",
                        "Maximum hydraulic siphon transfer per source tick.",
                        "amount", 1, 16,
                        a -> FlowingFluids.config.hydraulicSiphonMaxTransferPerTick = a,
                        () -> FlowingFluids.config.hydraulicSiphonMaxTransferPerTick))
                .then(intCommand("natural_max_search_nodes",
                        "Maximum BFS nodes per natural siphon search.",
                        "nodes", 64, 512,
                        a -> FlowingFluids.config.naturalSiphonMaxSearchNodes = a,
                        () -> FlowingFluids.config.naturalSiphonMaxSearchNodes))
                .then(intCommand("natural_max_path_length",
                        "Maximum full-water path length for natural siphons.",
                        "length", 16, 128,
                        a -> FlowingFluids.config.naturalSiphonMaxPathLength = a,
                        () -> FlowingFluids.config.naturalSiphonMaxPathLength))
                .then(intCommand("natural_max_lift",
                        "Maximum blocks a natural siphon may rise above the inlet surface.",
                        "blocks", 0, 8,
                        a -> FlowingFluids.config.naturalSiphonMaxLift = a,
                        () -> FlowingFluids.config.naturalSiphonMaxLift))
                .then(intCommand("natural_min_filled_amount",
                        "Minimum water level accepted as a natural siphon path cell.",
                        "amount", 1, 8,
                        a -> FlowingFluids.config.naturalSiphonMinFilledAmount = a,
                        () -> FlowingFluids.config.naturalSiphonMinFilledAmount))
                .then(intCommand("natural_max_transfer_per_tick",
                        "Maximum natural siphon transfer per source tick.",
                        "amount", 1, 8,
                        a -> FlowingFluids.config.naturalSiphonMaxTransferPerTick = a,
                        () -> FlowingFluids.config.naturalSiphonMaxTransferPerTick))
                .then(intCommand("natural_cooldown_ticks",
                        "Cooldown before retrying the same natural siphon source.",
                        "ticks", 1, 80,
                        a -> FlowingFluids.config.naturalSiphonCooldownTicks = a,
                        () -> FlowingFluids.config.naturalSiphonCooldownTicks))
                .then(booleanCommand("natural_require_enclosed_path",
                        "Controls whether natural siphons require narrow or mostly enclosed water paths.",
                        a -> FlowingFluids.config.naturalSiphonRequireEnclosedPath = a,
                        () -> FlowingFluids.config.naturalSiphonRequireEnclosedPath))
                .then(booleanCommand("natural_allow_open_surface",
                        "Controls whether natural siphons may consider open-surface paths. Keep this off unless testing.",
                        a -> FlowingFluids.config.naturalSiphonAllowOpenSurface = a,
                        () -> FlowingFluids.config.naturalSiphonAllowOpenSurface))
                .then(booleanCommand("same_level_outlets_anywhere",
                        "Allows siphons to discharge into same-height low-water outlets even when the outlet is not backed by hydraulic guides or nozzles.",
                        a -> FlowingFluids.config.siphonSameLevelOutletsAnywhere = a,
                        () -> FlowingFluids.config.siphonSameLevelOutletsAnywhere));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> hydraulicSiphonsCommand() {
        return Commands.literal("hydraulic")
                .executes(FFCommands::hydraulicSiphonsStatus)
                .then(Commands.literal("status")
                        .executes(FFCommands::hydraulicSiphonsStatus))
                .then(intCommand("max_search_nodes",
                        "Maximum BFS nodes per hydraulic siphon search.",
                        "nodes", 64, 512,
                        a -> FlowingFluids.config.hydraulicSiphonMaxSearchNodes = a,
                        () -> FlowingFluids.config.hydraulicSiphonMaxSearchNodes))
                .then(intCommand("max_path_length",
                        "Maximum guide/nozzle path length for hydraulic siphons.",
                        "length", 16, 128,
                        a -> FlowingFluids.config.hydraulicSiphonMaxPathLength = a,
                        () -> FlowingFluids.config.hydraulicSiphonMaxPathLength))
                .then(intCommand("max_lift",
                        "Maximum blocks a hydraulic siphon may rise above the inlet surface.",
                        "blocks", 0, 32,
                        a -> FlowingFluids.config.hydraulicSiphonMaxLift = a,
                        () -> FlowingFluids.config.hydraulicSiphonMaxLift))
                .then(intCommand("source_surface_scan_nodes",
                        "Connected source cells checked to find the hydraulic source water surface.",
                        "nodes", 16, 512,
                        a -> FlowingFluids.config.hydraulicSiphonSourceSurfaceScanNodes = a,
                        () -> FlowingFluids.config.hydraulicSiphonSourceSurfaceScanNodes))
                .then(intCommand("max_pressure_head",
                        "Maximum carried pressure head for guide/nozzle siphon paths.",
                        "blocks", 0, 32,
                        a -> FlowingFluids.config.hydraulicSiphonMaxPressureHead = a,
                        () -> FlowingFluids.config.hydraulicSiphonMaxPressureHead))
                .then(intCommand("max_transfer_per_tick",
                        "Maximum hydraulic siphon transfer per source tick.",
                        "amount", 1, 16,
                        a -> FlowingFluids.config.hydraulicSiphonMaxTransferPerTick = a,
                        () -> FlowingFluids.config.hydraulicSiphonMaxTransferPerTick));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> naturalSiphonsCommand() {
        return Commands.literal("natural")
                .executes(FFCommands::naturalSiphonsStatus)
                .then(Commands.literal("status")
                        .executes(FFCommands::naturalSiphonsStatus))
                .then(booleanCommand("enable",
                        "Controls block-free natural terrain siphons for enclosed overflows and cave filling.",
                        a -> FlowingFluids.config.enableNaturalTerrainSiphons = a,
                        () -> FlowingFluids.config.enableNaturalTerrainSiphons))
                .then(intCommand("max_search_nodes",
                        "Maximum BFS nodes per natural siphon search.",
                        "nodes", 64, 512,
                        a -> FlowingFluids.config.naturalSiphonMaxSearchNodes = a,
                        () -> FlowingFluids.config.naturalSiphonMaxSearchNodes))
                .then(intCommand("max_path_length",
                        "Maximum full-water path length for natural siphons.",
                        "length", 16, 128,
                        a -> FlowingFluids.config.naturalSiphonMaxPathLength = a,
                        () -> FlowingFluids.config.naturalSiphonMaxPathLength))
                .then(intCommand("max_lift",
                        "Maximum blocks a natural siphon may rise above the inlet surface.",
                        "blocks", 0, 8,
                        a -> FlowingFluids.config.naturalSiphonMaxLift = a,
                        () -> FlowingFluids.config.naturalSiphonMaxLift))
                .then(intCommand("min_filled_amount",
                        "Minimum water level accepted as a natural siphon path cell.",
                        "amount", 1, 8,
                        a -> FlowingFluids.config.naturalSiphonMinFilledAmount = a,
                        () -> FlowingFluids.config.naturalSiphonMinFilledAmount))
                .then(intCommand("max_transfer_per_tick",
                        "Maximum natural siphon transfer per source tick.",
                        "amount", 1, 8,
                        a -> FlowingFluids.config.naturalSiphonMaxTransferPerTick = a,
                        () -> FlowingFluids.config.naturalSiphonMaxTransferPerTick))
                .then(intCommand("cooldown_ticks",
                        "Cooldown before retrying the same natural siphon source.",
                        "ticks", 1, 80,
                        a -> FlowingFluids.config.naturalSiphonCooldownTicks = a,
                        () -> FlowingFluids.config.naturalSiphonCooldownTicks))
                .then(booleanCommand("require_enclosed_path",
                        "Controls whether natural siphons require narrow or mostly enclosed water paths.",
                        a -> FlowingFluids.config.naturalSiphonRequireEnclosedPath = a,
                        () -> FlowingFluids.config.naturalSiphonRequireEnclosedPath))
                .then(booleanCommand("allow_open_surface",
                        "Controls whether natural siphons may consider open-surface paths. Keep this off unless testing.",
                        a -> FlowingFluids.config.naturalSiphonAllowOpenSurface = a,
                        () -> FlowingFluids.config.naturalSiphonAllowOpenSurface));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pressureSiphonsCommand() {
        return Commands.literal("pressure")
                .executes(FFCommands::pressureSiphonsStatus)
                .then(Commands.literal("status")
                        .executes(FFCommands::pressureSiphonsStatus))
                .then(booleanCommand("cavity_enable",
                        "Controls whether enclosed caves, pits, and shafts build lightweight water head from sustained inflow.",
                        a -> FlowingFluids.config.enableCavityPressureRise = a,
                        () -> FlowingFluids.config.enableCavityPressureRise))
                .then(floatCommand("cavity_strength",
                        "Adjusts how strongly enclosed spaces bias transfer and connected filling.",
                        "strength", 0.0f, 2.0f,
                        a -> FlowingFluids.config.cavityPressureStrength = a,
                        () -> FlowingFluids.config.cavityPressureStrength))
                .then(floatCommand("connected_head_strength",
                        "Adjusts how much nearby connected higher water surfaces add to local pressure head.",
                        "strength", 0.0f, 2.0f,
                        a -> FlowingFluids.config.connectedHeadStrength = a,
                        () -> FlowingFluids.config.connectedHeadStrength))
                .then(intCommand("head_sample_distance",
                        "Horizontal distance sampled for nearby stored water pressure.",
                        "blocks", 0, 16,
                        a -> FlowingFluids.config.hydraulicSampleDistance = a,
                        () -> FlowingFluids.config.hydraulicSampleDistance))
                .then(booleanCommand("same_level_outlets_anywhere",
                        "Allows pressure siphons to discharge into same-height low-water outlets without hardware.",
                        a -> FlowingFluids.config.siphonSameLevelOutletsAnywhere = a,
                        () -> FlowingFluids.config.siphonSameLevelOutletsAnywhere));
    }

    private static int cavityPressureStatus(CommandContext<CommandSourceStack> context) {
        return message(context, "Cavity pressure settings"
                + "\nEnabled: " + FlowingFluids.config.enableCavityPressureRise
                + "\nPressure strength: " + FlowingFluids.config.cavityPressureStrength
                + "\nConnected head strength: " + FlowingFluids.config.connectedHeadStrength
                + "\nHead sample distance: " + FlowingFluids.config.hydraulicSampleDistance
                + "\nRoof probe height: " + FlowingFluids.config.shadeRoofSearchHeight
                + "\nUse this to tune how strongly enclosed caves and pits hold water and climb upward.");
    }

    private static int snowmeltStatus(CommandContext<CommandSourceStack> context) {
        Level level = context.getSource().getLevel();
        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
        float temperature = level.getBiome(pos).value().getBaseTemperature();
        int skyLight = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos.above());

        return message(context, "雪解け水ステータス"
                + "\nこの機能は、プレイヤー周辺の読み込み済みチャンクだけを間引いて見ながら、露出した雪や氷を少しずつ溶かします。"
                + "\n毎 tick 全域を走査しないので、見た目のわりにかなり軽めです。"
                + "\n有効: " + FlowingFluids.config.enableSnowmeltSystem
                + "\n昼のみ: " + FlowingFluids.config.snowmeltDaytimeOnly
                + "\n溶けたあとに水を置く: " + FlowingFluids.config.snowmeltPlacesWater
                + "\nチャンク半径: " + FlowingFluids.config.snowmeltChunkRadius
                + "\n実行間隔 tick: " + FlowingFluids.config.snowmeltIntervalTicks
                + "\n1チャンクあたり試行回数: " + FlowingFluids.config.snowmeltAttemptsPerChunk
                + "\n1回で処理する最大チャンク数: " + FlowingFluids.config.snowmeltMaxChunksPerTick
                + "\n基礎融解確率: " + FlowingFluids.config.snowmeltBaseChance
                + "\n生成水量: " + FlowingFluids.config.snowmeltWaterAmount
                + "\n必要な最低空光: " + FlowingFluids.config.snowmeltMinSkyLight
                + "\n必要な最低気温: " + FlowingFluids.config.snowmeltMinTemperature
                + "\n現在地の判定目安:"
                + "\n- 座標: " + pos
                + "\n- 昼かどうか: " + level.isDay()
                + "\n- 上空の空光: " + skyLight
                + "\n- バイオーム基礎気温: " + temperature
                + "\n切り替え: `/flowing_fluids settings snowmelt enable on|off`"
                + "\n細かい調整は `/flowing_fluids settings snowmelt` 以下の各サブコマンドでできます。");
    }

    private static String currentBiomeId(Level level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey()
                .map(key -> key.location().toString())
                .orElse("<unregistered>");
    }

    private static List<String> sortedInfiniteBiomeEntries(Iterable<String> entries) {
        List<String> sorted = new ArrayList<>();
        for (String entry : entries) {
            if (entry != null && !entry.isBlank()) {
                sorted.add(entry.trim());
            }
        }
        sorted.sort(String::compareTo);
        return sorted;
    }

    private static String describeEntryList(String title, Iterable<String> entries) {
        List<String> sorted = sortedInfiniteBiomeEntries(entries);
        if (sorted.isEmpty()) {
            return title + "\n- none";
        }

        StringBuilder builder = new StringBuilder(title);
        for (String entry : sorted) {
            builder.append("\n- ").append(entry);
        }
        return builder.toString();
    }

    private static String normalizeAutoInfiniteBiomeHint(String rawEntry) {
        if (rawEntry == null) {
            return null;
        }
        String trimmed = rawEntry.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return FFFluidUtils.normalizeConfiguredBiomeEntry(trimmed, trimmed.startsWith("#"));
    }

    private static String describeInfiniteBiomeEntryOverview(Level level, BlockPos pos) {
        FlowingFluids.config.ensureCollections();
        return "Infinite biome entry settings"
                + "\nCurrent biome: " + currentBiomeId(level, pos)
                + "\nCounts as infinite here: " + FFFluidUtils.matchInfiniteBiomes(level.getBiome(pos))
                + "\nCustom config entries: " + FlowingFluids.config.extraInfiniteBiomeEntries.size()
                + "\nAuto add on startup: " + FlowingFluids.config.enableAutomaticInfiniteBiomeAddition
                + "\nAuto add modded only: " + FlowingFluids.config.automaticInfiniteBiomeAdditionModdedOnly
                + "\nAuto tag hints: " + FlowingFluids.config.automaticInfiniteBiomeTagHints.size()
                + "\nAuto keywords: " + FlowingFluids.config.automaticInfiniteBiomeKeywordHints.size()
                + "\nFormat: biome namespace:path or tag #namespace:path"
                + "\nCommands:"
                + "\n- list"
                + "\n- add here / add biome <id> / add tag <id>"
                + "\n- remove here / remove biome <id> / remove tag <id>"
                + "\n- clear_custom"
                + "\n- auto_add status / preview / run";
    }

    private static String describeInfiniteBiomeEntryList(Level level, BlockPos pos) {
        FlowingFluids.config.ensureCollections();
        StringBuilder builder = new StringBuilder("Infinite biome entries");
        builder.append("\nCurrent biome: ").append(currentBiomeId(level, pos));
        builder.append("\nBuilt-in defaults:");
        for (String entry : DEFAULT_INFINITE_BIOME_ENTRIES) {
            builder.append("\n- ").append(entry);
        }

        List<String> configuredEntries = sortedInfiniteBiomeEntries(FlowingFluids.config.extraInfiniteBiomeEntries);
        builder.append("\nCustom config entries:");
        if (configuredEntries.isEmpty()) {
            builder.append("\n- none");
        } else {
            for (String entry : configuredEntries) {
                builder.append("\n- ").append(entry);
            }
        }
        return builder.toString();
    }

    private static int addInfiniteBiomeEntry(CommandContext<CommandSourceStack> context, String rawEntry, boolean tagEntry) {
        FlowingFluids.config.ensureCollections();
        String normalized = FFFluidUtils.normalizeConfiguredBiomeEntry(rawEntry, tagEntry);
        if (normalized == null) {
            String expected = tagEntry ? "#namespace:path" : "namespace:path";
            return message(context, "Invalid infinite biome entry: " + rawEntry + "\nExpected format: " + expected);
        }
        if (!FlowingFluids.config.extraInfiniteBiomeEntries.add(normalized)) {
            return message(context, "Infinite biome entry is already stored: " + normalized);
        }
        return messageAndSaveConfig(context,
                "Added infinite biome entry: " + normalized
                        + "\nCustom entries: " + FlowingFluids.config.extraInfiniteBiomeEntries.size());
    }

    private static int removeInfiniteBiomeEntry(CommandContext<CommandSourceStack> context, String rawEntry, boolean tagEntry) {
        FlowingFluids.config.ensureCollections();
        String normalized = FFFluidUtils.normalizeConfiguredBiomeEntry(rawEntry, tagEntry);
        if (normalized == null) {
            String expected = tagEntry ? "#namespace:path" : "namespace:path";
            return message(context, "Invalid infinite biome entry: " + rawEntry + "\nExpected format: " + expected);
        }
        if (!FlowingFluids.config.extraInfiniteBiomeEntries.remove(normalized)) {
            return message(context, "That infinite biome entry is not in the config: " + normalized);
        }
        return messageAndSaveConfig(context,
                "Removed infinite biome entry: " + normalized
                        + "\nCustom entries: " + FlowingFluids.config.extraInfiniteBiomeEntries.size());
    }

    private static int addCurrentInfiniteBiomeEntry(CommandContext<CommandSourceStack> context) {
        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
        return addInfiniteBiomeEntry(context, currentBiomeId(context.getSource().getLevel(), pos), false);
    }

    private static int removeCurrentInfiniteBiomeEntry(CommandContext<CommandSourceStack> context) {
        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
        return removeInfiniteBiomeEntry(context, currentBiomeId(context.getSource().getLevel(), pos), false);
    }

    private static String describeAutomaticInfiniteBiomeStatus(Level level) {
        FlowingFluids.config.ensureCollections();
        List<FFFluidUtils.AutoInfiniteBiomeCandidate> candidates = FFFluidUtils.collectAutoInfiniteBiomeCandidates(
                level,
                FlowingFluids.config.automaticInfiniteBiomeAdditionModdedOnly
        );
        long newEntries = candidates.stream()
                .filter(candidate -> !FlowingFluids.config.extraInfiniteBiomeEntries.contains(candidate.biomeId()))
                .count();
        return "Automatic infinite biome detection"
                + "\nEnabled on startup: " + FlowingFluids.config.enableAutomaticInfiniteBiomeAddition
                + "\nModded biomes only: " + FlowingFluids.config.automaticInfiniteBiomeAdditionModdedOnly
                + "\nTag hints: " + FlowingFluids.config.automaticInfiniteBiomeTagHints.size()
                + "\nKeyword hints: " + FlowingFluids.config.automaticInfiniteBiomeKeywordHints.size()
                + "\nDetected candidates right now: " + candidates.size()
                + "\nNew candidates not yet stored: " + newEntries;
    }

    private static String describeAutomaticInfiniteBiomePreview(Level level) {
        FlowingFluids.config.ensureCollections();
        List<FFFluidUtils.AutoInfiniteBiomeCandidate> candidates = FFFluidUtils.collectAutoInfiniteBiomeCandidates(
                level,
                FlowingFluids.config.automaticInfiniteBiomeAdditionModdedOnly
        );
        StringBuilder builder = new StringBuilder(describeAutomaticInfiniteBiomeStatus(level));
        if (candidates.isEmpty()) {
            return builder.append("\nNo auto-detect candidates were found in the loaded biome registry.").toString();
        }

        builder.append("\nCandidates:");
        int shown = 0;
        for (FFFluidUtils.AutoInfiniteBiomeCandidate candidate : candidates) {
            if (shown >= 40) {
                builder.append("\n... and ").append(candidates.size() - shown).append(" more");
                break;
            }
            boolean alreadyStored = FlowingFluids.config.extraInfiniteBiomeEntries.contains(candidate.biomeId());
            builder.append("\n- ").append(candidate.biomeId())
                    .append(alreadyStored ? " [stored]" : " [new]")
                    .append(" <- ").append(candidate.reason());
            shown++;
        }
        return builder.toString();
    }

    private static int runAutomaticInfiniteBiomeAdd(CommandContext<CommandSourceStack> context) {
        FlowingFluids.config.ensureCollections();
        Level level = context.getSource().getLevel();
        List<FFFluidUtils.AutoInfiniteBiomeCandidate> candidates = FFFluidUtils.collectAutoInfiniteBiomeCandidates(
                level,
                FlowingFluids.config.automaticInfiniteBiomeAdditionModdedOnly
        );

        List<FFFluidUtils.AutoInfiniteBiomeCandidate> added = new ArrayList<>();
        for (FFFluidUtils.AutoInfiniteBiomeCandidate candidate : candidates) {
            if (FlowingFluids.config.extraInfiniteBiomeEntries.add(candidate.biomeId())) {
                added.add(candidate);
            }
        }

        if (added.isEmpty()) {
            return message(context, "No new infinite biome entries were added.\n" + describeAutomaticInfiniteBiomeStatus(level));
        }

        StringBuilder builder = new StringBuilder("Auto-added infinite biome entries: ").append(added.size());
        int shown = 0;
        for (FFFluidUtils.AutoInfiniteBiomeCandidate candidate : added) {
            if (shown >= 16) {
                builder.append("\n... and ").append(added.size() - shown).append(" more");
                break;
            }
            builder.append("\n- ").append(candidate.biomeId()).append(" <- ").append(candidate.reason());
            shown++;
        }
        return messageAndSaveConfig(context, builder.toString());
    }

    private static int addAutomaticInfiniteBiomeTagHint(CommandContext<CommandSourceStack> context, String rawEntry) {
        FlowingFluids.config.ensureCollections();
        String normalized = normalizeAutoInfiniteBiomeHint(rawEntry);
        if (normalized == null) {
            return message(context, "Invalid tag or biome hint: " + rawEntry + "\nUse #namespace:path or namespace:path.");
        }
        if (!FlowingFluids.config.automaticInfiniteBiomeTagHints.add(normalized)) {
            return message(context, "Automatic tag hint is already stored: " + normalized);
        }
        return messageAndSaveConfig(context, "Added automatic infinite biome tag hint: " + normalized);
    }

    private static int removeAutomaticInfiniteBiomeTagHint(CommandContext<CommandSourceStack> context, String rawEntry) {
        FlowingFluids.config.ensureCollections();
        String normalized = normalizeAutoInfiniteBiomeHint(rawEntry);
        if (normalized == null) {
            return message(context, "Invalid tag or biome hint: " + rawEntry + "\nUse #namespace:path or namespace:path.");
        }
        if (!FlowingFluids.config.automaticInfiniteBiomeTagHints.remove(normalized)) {
            return message(context, "That automatic tag hint is not stored: " + normalized);
        }
        return messageAndSaveConfig(context, "Removed automatic infinite biome tag hint: " + normalized);
    }

    private static int addAutomaticInfiniteBiomeKeyword(CommandContext<CommandSourceStack> context, String rawKeyword) {
        FlowingFluids.config.ensureCollections();
        String normalized = FFFluidUtils.normalizeConfiguredKeyword(rawKeyword);
        if (normalized == null) {
            return message(context, "Invalid auto-detect keyword: " + rawKeyword);
        }
        if (!FlowingFluids.config.automaticInfiniteBiomeKeywordHints.add(normalized)) {
            return message(context, "Automatic keyword is already stored: " + normalized);
        }
        return messageAndSaveConfig(context, "Added automatic infinite biome keyword: " + normalized);
    }

    private static int removeAutomaticInfiniteBiomeKeyword(CommandContext<CommandSourceStack> context, String rawKeyword) {
        FlowingFluids.config.ensureCollections();
        String normalized = FFFluidUtils.normalizeConfiguredKeyword(rawKeyword);
        if (normalized == null) {
            return message(context, "Invalid auto-detect keyword: " + rawKeyword);
        }
        if (!FlowingFluids.config.automaticInfiniteBiomeKeywordHints.remove(normalized)) {
            return message(context, "That automatic keyword is not stored: " + normalized);
        }
        return messageAndSaveConfig(context, "Removed automatic infinite biome keyword: " + normalized);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> automaticInfiniteBiomeCommand() {
        return Commands.literal("auto_add")
                .executes(cont -> message(cont, describeAutomaticInfiniteBiomeStatus(cont.getSource().getLevel())))
                .then(Commands.literal("status")
                        .executes(cont -> message(cont, describeAutomaticInfiniteBiomeStatus(cont.getSource().getLevel()))))
                .then(Commands.literal("preview")
                        .executes(cont -> message(cont, describeAutomaticInfiniteBiomePreview(cont.getSource().getLevel()))))
                .then(Commands.literal("run")
                        .executes(FFCommands::runAutomaticInfiniteBiomeAdd))
                .then(booleanCommand("enable",
                        "Automatically scans the loaded biome registry on server start and stores new infinite biome entries.",
                        a -> FlowingFluids.config.enableAutomaticInfiniteBiomeAddition = a,
                        () -> FlowingFluids.config.enableAutomaticInfiniteBiomeAddition))
                .then(booleanCommand("modded_only",
                        "When enabled, startup auto-add ignores vanilla biomes and only stores mod-added ones.",
                        a -> FlowingFluids.config.automaticInfiniteBiomeAdditionModdedOnly = a,
                        () -> FlowingFluids.config.automaticInfiniteBiomeAdditionModdedOnly))
                .then(Commands.literal("tag_hints")
                        .executes(cont -> message(cont, describeEntryList(
                                "Automatic infinite biome tag hints",
                                FlowingFluids.config.automaticInfiniteBiomeTagHints)))
                        .then(Commands.literal("list")
                                .executes(cont -> message(cont, describeEntryList(
                                        "Automatic infinite biome tag hints",
                                        FlowingFluids.config.automaticInfiniteBiomeTagHints))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("entry", StringArgumentType.greedyString())
                                        .executes(cont -> addAutomaticInfiniteBiomeTagHint(cont, StringArgumentType.getString(cont, "entry")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("entry", StringArgumentType.greedyString())
                                        .executes(cont -> removeAutomaticInfiniteBiomeTagHint(cont, StringArgumentType.getString(cont, "entry")))))
                        .then(Commands.literal("reset")
                                .executes(cont -> {
                                    FFConfig defaults = new FFConfig();
                                    FlowingFluids.config.automaticInfiniteBiomeTagHints = new ObjectOpenHashSet<>(defaults.automaticInfiniteBiomeTagHints);
                                    return messageAndSaveConfig(cont, "Reset automatic infinite biome tag hints to defaults.");
                                })))
                .then(Commands.literal("keywords")
                        .executes(cont -> message(cont, describeEntryList(
                                "Automatic infinite biome keywords",
                                FlowingFluids.config.automaticInfiniteBiomeKeywordHints)))
                        .then(Commands.literal("list")
                                .executes(cont -> message(cont, describeEntryList(
                                        "Automatic infinite biome keywords",
                                        FlowingFluids.config.automaticInfiniteBiomeKeywordHints))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("keyword", StringArgumentType.greedyString())
                                        .executes(cont -> addAutomaticInfiniteBiomeKeyword(cont, StringArgumentType.getString(cont, "keyword")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("keyword", StringArgumentType.greedyString())
                                        .executes(cont -> removeAutomaticInfiniteBiomeKeyword(cont, StringArgumentType.getString(cont, "keyword")))))
                        .then(Commands.literal("reset")
                                .executes(cont -> {
                                    FFConfig defaults = new FFConfig();
                                    FlowingFluids.config.automaticInfiniteBiomeKeywordHints = new ObjectOpenHashSet<>(defaults.automaticInfiniteBiomeKeywordHints);
                                    return messageAndSaveConfig(cont, "Reset automatic infinite biome keywords to defaults.");
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> infiniteBiomeEntriesCommand() {
        return Commands.literal("infinite_biomes")
                .executes(cont -> {
                    Level level = cont.getSource().getLevel();
                    BlockPos pos = BlockPos.containing(cont.getSource().getPosition());
                    return message(cont, describeInfiniteBiomeEntryOverview(level, pos));
                })
                .then(Commands.literal("status")
                        .executes(cont -> {
                            Level level = cont.getSource().getLevel();
                            BlockPos pos = BlockPos.containing(cont.getSource().getPosition());
                            return message(cont, describeInfiniteBiomeEntryOverview(level, pos));
                        }))
                .then(Commands.literal("list")
                        .executes(cont -> {
                            Level level = cont.getSource().getLevel();
                            BlockPos pos = BlockPos.containing(cont.getSource().getPosition());
                            return message(cont, describeInfiniteBiomeEntryList(level, pos));
                        }))
                .then(Commands.literal("add")
                        .executes(cont -> message(cont, "Add infinite biome entries with here, biome <id>, or tag <id>."))
                        .then(Commands.literal("here")
                                .executes(FFCommands::addCurrentInfiniteBiomeEntry))
                        .then(Commands.literal("biome")
                                .then(Commands.argument("id", StringArgumentType.greedyString())
                                        .executes(cont -> addInfiniteBiomeEntry(cont, StringArgumentType.getString(cont, "id"), false))))
                        .then(Commands.literal("tag")
                                .then(Commands.argument("id", StringArgumentType.greedyString())
                                        .executes(cont -> addInfiniteBiomeEntry(cont, StringArgumentType.getString(cont, "id"), true)))))
                .then(Commands.literal("remove")
                        .executes(cont -> message(cont, "Remove infinite biome entries with here, biome <id>, or tag <id>."))
                        .then(Commands.literal("here")
                                .executes(FFCommands::removeCurrentInfiniteBiomeEntry))
                        .then(Commands.literal("biome")
                                .then(Commands.argument("id", StringArgumentType.greedyString())
                                        .executes(cont -> removeInfiniteBiomeEntry(cont, StringArgumentType.getString(cont, "id"), false))))
                        .then(Commands.literal("tag")
                                .then(Commands.argument("id", StringArgumentType.greedyString())
                                        .executes(cont -> removeInfiniteBiomeEntry(cont, StringArgumentType.getString(cont, "id"), true)))))
                .then(Commands.literal("clear_custom")
                        .executes(cont -> {
                            FlowingFluids.config.ensureCollections();
                            int cleared = FlowingFluids.config.extraInfiniteBiomeEntries.size();
                            if (cleared == 0) {
                                return message(cont, "No custom infinite biome entries are currently stored.");
                            }
                            FlowingFluids.config.extraInfiniteBiomeEntries.clear();
                            return messageAndSaveConfig(cont, "Cleared custom infinite biome entries: " + cleared);
                        }))
                .then(automaticInfiniteBiomeCommand());
    }

    private static int inspectInfiniteBiomeHere(CommandContext<CommandSourceStack> context) {
        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
        return message(context, describeInfiniteBiomeStatus(context.getSource().getLevel(), pos));
    }

    private static String describeInfiniteBiomeStatus(Level level, BlockPos pos) {
        var biome = level.getBiome(pos);
        FluidState fluidState = level.getFluidState(pos);
        boolean inInfiniteBiome = FFFluidUtils.matchInfiniteBiomes(biome);
        boolean withinBand = FFFluidUtils.isWithinInfiniteBiomeRefillBand(level, pos);
        boolean randomRefillEnabled = FFFluidUtils.isInfiniteBiomeRandomRefillEnabled();
        boolean nonConsumeEnabled = FFFluidUtils.isInfiniteBiomeNonConsumeEnabled();
        boolean surfaceDrainEnabled = FFFluidUtils.isInfiniteBiomeSurfaceDrainEnabled();
        boolean flowingRefillEnabled = FFFluidUtils.isInfiniteBiomeFlowingRefillEnabled();
        return "Infinite biome runtime status"
                + "\nPosition: " + pos
                + "\nBiome: " + currentBiomeId(level, pos)
                + "\nFluid amount: " + fluidState.getAmount()
                + "\nInfinite biome: " + inInfiniteBiome
                + "\nWithin refill band: " + withinBand
                + "\nPassive refill chance: " + FlowingFluids.config.oceanRiverSwampRefillChance
                + " (enabled=" + randomRefillEnabled + ")"
                + "\nNon-consume chance: " + FlowingFluids.config.infiniteWaterBiomeNonConsumeChance
                + " (enabled=" + nonConsumeEnabled + ")"
                + "\nFlowing refill chance: " + FlowingFluids.config.infiniteWaterBiomeFlowingRefillChance
                + " (enabled=" + flowingRefillEnabled + ")"
                + "\nFlowing refill interval: " + FlowingFluids.config.infiniteWaterBiomeFlowingRefillInterval + " ticks"
                + "\nFlowing refill max amount: " + FlowingFluids.config.infiniteWaterBiomeFlowingRefillMaxAmount
                + "\nSurface drain chance: " + FlowingFluids.config.infiniteWaterBiomeDrainSurfaceChance
                + " (enabled=" + surfaceDrainEnabled + ")"
                + "\nSea-level only refill: " + FlowingFluids.config.fastBiomeRefillAtSeaLevelOnly
                + "\nCustom config entries: " + FlowingFluids.config.extraInfiniteBiomeEntries.size();
    }

    private static int applyInfiniteBiomePreset(CommandContext<CommandSourceStack> context, String presetName) {
        FFConfig defaults = new FFConfig();
        switch (presetName) {
            case "boosted" -> {
                FlowingFluids.config.oceanRiverSwampRefillChance = 0.12f;
                FlowingFluids.config.infiniteWaterBiomeNonConsumeChance = 0.03f;
                FlowingFluids.config.infiniteWaterBiomeFlowingRefillChance = 0.08f;
                FlowingFluids.config.infiniteWaterBiomeFlowingRefillInterval = 8;
                FlowingFluids.config.infiniteWaterBiomeFlowingRefillMaxAmount = 2;
            }
            case "aggressive" -> {
                FlowingFluids.config.oceanRiverSwampRefillChance = 0.20f;
                FlowingFluids.config.infiniteWaterBiomeNonConsumeChance = 0.08f;
                FlowingFluids.config.infiniteWaterBiomeFlowingRefillChance = 0.18f;
                FlowingFluids.config.infiniteWaterBiomeFlowingRefillInterval = 4;
                FlowingFluids.config.infiniteWaterBiomeFlowingRefillMaxAmount = 3;
            }
            case "reset" -> {
                FlowingFluids.config.oceanRiverSwampRefillChance = defaults.oceanRiverSwampRefillChance;
                FlowingFluids.config.infiniteWaterBiomeNonConsumeChance = defaults.infiniteWaterBiomeNonConsumeChance;
                FlowingFluids.config.infiniteWaterBiomeFlowingRefillChance = defaults.infiniteWaterBiomeFlowingRefillChance;
                FlowingFluids.config.infiniteWaterBiomeFlowingRefillInterval = defaults.infiniteWaterBiomeFlowingRefillInterval;
                FlowingFluids.config.infiniteWaterBiomeFlowingRefillMaxAmount = defaults.infiniteWaterBiomeFlowingRefillMaxAmount;
            }
            default -> {
                return message(context, "Unknown infinite biome preset: " + presetName);
            }
        }
        return messageAndSaveConfig(context,
                "Applied infinite biome preset: " + presetName
                        + "\nPassive refill chance: " + FlowingFluids.config.oceanRiverSwampRefillChance
                        + "\nNon-consume chance: " + FlowingFluids.config.infiniteWaterBiomeNonConsumeChance
                        + "\nFlowing refill chance: " + FlowingFluids.config.infiniteWaterBiomeFlowingRefillChance
                        + "\nFlowing refill interval: " + FlowingFluids.config.infiniteWaterBiomeFlowingRefillInterval + " ticks"
                        + "\nFlowing refill max amount: " + FlowingFluids.config.infiniteWaterBiomeFlowingRefillMaxAmount);
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

    private static LiteralArgumentBuilder<CommandSourceStack> componentGraphCommand() {
        return Commands.literal("component_graph")
                .executes(FFCommands::componentGraphStatus)
                .then(Commands.literal("status")
                        .executes(FFCommands::componentGraphStatus))
                .then(booleanCommand("enable",
                        "Delta-driven local fluid component graph. When enabled, fluid writes mark nearby cells dirty and the server rebuilds only a bounded slice each tick.",
                        "Fluid component graph enabled. Runtime graph will rebuild from new fluid changes.",
                        "Fluid component graph disabled. Runtime graph cache cleared.",
                        a -> {
                            FlowingFluids.config.enableFluidComponentGraph = a;
                            if (!a) {
                                FluidComponentGraph.clearAll();
                            }
                        },
                        () -> FlowingFluids.config.enableFluidComponentGraph))
                .then(booleanCommand("equalizer_assist",
                        "Allow stable component interiors to use focused equalizer snapshots instead of broad captures.",
                        a -> FlowingFluids.config.fluidComponentGraphAssistEqualizer = a,
                        () -> FlowingFluids.config.fluidComponentGraphAssistEqualizer))
                .then(intCommand("max_updates_per_tick",
                        "Maximum dirty graph seeds processed per dimension tick. Higher catches up faster, lower smooths cost.",
                        "updates", 1, 4096,
                        a -> FlowingFluids.config.fluidComponentGraphMaxUpdatesPerTick = a,
                        () -> FlowingFluids.config.fluidComponentGraphMaxUpdatesPerTick))
                .then(intCommand("max_scan_nodes",
                        "Maximum connected nodes rebuilt from one dirty seed. Large water bodies stay partial instead of blocking the tick.",
                        "nodes", 16, 8192,
                        a -> FlowingFluids.config.fluidComponentGraphMaxScanNodes = a,
                        () -> FlowingFluids.config.fluidComponentGraphMaxScanNodes))
                .then(Commands.literal("clear_runtime")
                        .executes(FFCommands::clearComponentGraphRuntime));
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

    private static LiteralArgumentBuilder<CommandSourceStack> dryingCommand() {
        return Commands.literal("drying")
                .executes(FFCommands::dryingStatus)
                .then(Commands.literal("status")
                        .executes(FFCommands::dryingStatus))
                .then(Commands.literal("heatwave")
                        .executes(FFCommands::dryingStatus)
                        .then(booleanCommand("enable",
                                "Master toggle for heatwave events that raise evaporation and weaken rain refill.",
                                a -> FlowingFluids.config.enableHeatwaveEvents = a,
                                () -> FlowingFluids.config.enableHeatwaveEvents))
                        .then(floatChanceCommand("start_chance_per_day",
                                "Chance for a heatwave to begin during each daily climate roll.",
                                a -> FlowingFluids.config.heatwaveStartChancePerDay = a,
                                () -> FlowingFluids.config.heatwaveStartChancePerDay))
                        .then(intCommand("min_duration_ticks",
                                "Minimum heatwave duration in ticks.",
                                "ticks", 20, 240000,
                                a -> FlowingFluids.config.heatwaveMinDurationTicks = a,
                                () -> FlowingFluids.config.heatwaveMinDurationTicks))
                        .then(intCommand("max_duration_ticks",
                                "Maximum heatwave duration in ticks.",
                                "ticks", 20, 240000,
                                a -> FlowingFluids.config.heatwaveMaxDurationTicks = a,
                                () -> FlowingFluids.config.heatwaveMaxDurationTicks))
                        .then(floatCommand("evaporation_multiplier",
                                "Multiplier applied to evaporation while a heatwave is active.",
                                "multiplier", 0.0f, 8.0f,
                                a -> FlowingFluids.config.heatwaveEvaporationMultiplier = a,
                                () -> FlowingFluids.config.heatwaveEvaporationMultiplier))
                        .then(floatCommand("rain_refill_multiplier",
                                "Multiplier applied to rain refill while a heatwave is active.",
                                "multiplier", 0.0f, 4.0f,
                                a -> FlowingFluids.config.heatwaveRainRefillMultiplier = a,
                                () -> FlowingFluids.config.heatwaveRainRefillMultiplier))
                        .then(booleanCommand("daytime_only",
                                "When enabled, heatwave bonuses only matter during the day.",
                                a -> FlowingFluids.config.heatwaveDaytimeOnly = a,
                                () -> FlowingFluids.config.heatwaveDaytimeOnly)))
                .then(Commands.literal("dry_season")
                        .executes(FFCommands::dryingStatus)
                        .then(booleanCommand("enable",
                                "Master toggle for dry season events.",
                                a -> FlowingFluids.config.enableDrySeasonEvents = a,
                                () -> FlowingFluids.config.enableDrySeasonEvents))
                        .then(floatChanceCommand("start_chance_per_day",
                                "Chance for a dry season to begin during each daily climate roll.",
                                a -> FlowingFluids.config.drySeasonStartChancePerDay = a,
                                () -> FlowingFluids.config.drySeasonStartChancePerDay))
                        .then(intCommand("min_duration_ticks",
                                "Minimum dry season duration in ticks.",
                                "ticks", 20, 480000,
                                a -> FlowingFluids.config.drySeasonMinDurationTicks = a,
                                () -> FlowingFluids.config.drySeasonMinDurationTicks))
                        .then(intCommand("max_duration_ticks",
                                "Maximum dry season duration in ticks.",
                                "ticks", 20, 480000,
                                a -> FlowingFluids.config.drySeasonMaxDurationTicks = a,
                                () -> FlowingFluids.config.drySeasonMaxDurationTicks))
                        .then(floatCommand("evaporation_multiplier",
                                "Multiplier applied to evaporation while a dry season is active.",
                                "multiplier", 0.0f, 8.0f,
                                a -> FlowingFluids.config.drySeasonEvaporationMultiplier = a,
                                () -> FlowingFluids.config.drySeasonEvaporationMultiplier))
                        .then(floatCommand("rain_refill_multiplier",
                                "Multiplier applied to rain refill while a dry season is active.",
                                "multiplier", 0.0f, 4.0f,
                                a -> FlowingFluids.config.drySeasonRainRefillMultiplier = a,
                                () -> FlowingFluids.config.drySeasonRainRefillMultiplier)))
                .then(Commands.literal("evaporation")
                        .executes(FFCommands::dryingStatus)
                        .then(floatCommand("chance_multiplier",
                                "Multiplier layered on top of the base puddle evaporation chance.",
                                "multiplier", 0.0f, 8.0f,
                                a -> FlowingFluids.config.evaporationChanceMultiplier = a,
                                () -> FlowingFluids.config.evaporationChanceMultiplier))
                        .then(intCommand("interval_ticks",
                                "How often surface evaporation attempts are allowed per position. Higher values slow drying.",
                                "ticks", 1, 1200,
                                a -> FlowingFluids.config.evaporationIntervalTicks = a,
                                () -> FlowingFluids.config.evaporationIntervalTicks))
                        .then(intCommand("thin_water_max_level",
                                "Highest water level that surface drying may evaporate. Thin levels at or below this also ignore the active-flow gate.",
                                "level", 1, 8,
                                a -> FlowingFluids.config.evaporationThinWaterMaxLevel = a,
                                () -> FlowingFluids.config.evaporationThinWaterMaxLevel))
                        .then(floatCommand("nether_chance_multiplier",
                                "Multiplier layered on top of the base ultra-warm dimension evaporation chance.",
                                "multiplier", 0.0f, 8.0f,
                                a -> FlowingFluids.config.evaporationNetherChanceMultiplier = a,
                                () -> FlowingFluids.config.evaporationNetherChanceMultiplier))
                        .then(intCommand("nether_interval_ticks",
                                "How often ultra-warm dimension evaporation attempts are allowed per position.",
                                "ticks", 1, 1200,
                                a -> FlowingFluids.config.evaporationNetherIntervalTicks = a,
                                () -> FlowingFluids.config.evaporationNetherIntervalTicks)))
                .then(Commands.literal("hot_block_evaporation")
                        .executes(FFCommands::dryingStatus)
                        .then(booleanCommand("enable",
                                "Lets nearby hot blocks dry shallow water faster.",
                                a -> FlowingFluids.config.enableHotBlockEvaporation = a,
                                () -> FlowingFluids.config.enableHotBlockEvaporation))
                        .then(floatChanceCommand("chance",
                                "Chance for nearby heat sources to evaporate water during random ticks.",
                                a -> FlowingFluids.config.hotBlockEvaporationChance = a,
                                () -> FlowingFluids.config.hotBlockEvaporationChance))
                        .then(floatCommand("chance_multiplier",
                                "Multiplier layered on top of the base hot-block evaporation chance.",
                                "multiplier", 0.0f, 8.0f,
                                a -> FlowingFluids.config.hotBlockEvaporationChanceMultiplier = a,
                                () -> FlowingFluids.config.hotBlockEvaporationChanceMultiplier))
                        .then(intCommand("interval_ticks",
                                "How often hot-block evaporation attempts are allowed per position.",
                                "ticks", 1, 1200,
                                a -> FlowingFluids.config.hotBlockEvaporationIntervalTicks = a,
                                () -> FlowingFluids.config.hotBlockEvaporationIntervalTicks))
                        .then(intCommand("radius",
                                "Horizontal scan radius for nearby hot blocks.",
                                "radius", 0, 8,
                                a -> FlowingFluids.config.hotBlockEvaporationRadius = a,
                                () -> FlowingFluids.config.hotBlockEvaporationRadius))
                        .then(intCommand("vertical_range",
                                "Vertical scan range for nearby hot blocks.",
                                "range", 0, 8,
                                a -> FlowingFluids.config.hotBlockEvaporationVerticalRange = a,
                                () -> FlowingFluids.config.hotBlockEvaporationVerticalRange))
                        .then(intCommand("drain_amount",
                                "How many fluid levels a hot-block drying tick removes.",
                                "amount", 1, 8,
                                a -> FlowingFluids.config.hotBlockEvaporationDrainAmount = a,
                                () -> FlowingFluids.config.hotBlockEvaporationDrainAmount)))
                .then(Commands.literal("shade_protection")
                        .executes(FFCommands::dryingStatus)
                        .then(booleanCommand("enable",
                                "Prevents drying when a roof or canopy shields the water.",
                                a -> FlowingFluids.config.enableShadeProtection = a,
                                () -> FlowingFluids.config.enableShadeProtection))
                        .then(intCommand("roof_search_height",
                                "How far upward to search for a protective roof.",
                                "height", 1, 32,
                                a -> FlowingFluids.config.shadeRoofSearchHeight = a,
                                () -> FlowingFluids.config.shadeRoofSearchHeight)))
                .then(Commands.literal("river_drought")
                        .executes(FFCommands::dryingStatus)
                        .then(booleanCommand("enable",
                                "Allows river edges to dry out during dry seasons.",
                                a -> FlowingFluids.config.enableRiverDroughts = a,
                                () -> FlowingFluids.config.enableRiverDroughts))
                        .then(floatChanceCommand("refill_multiplier",
                                "Multiplier applied to river refill during drought conditions.",
                                a -> FlowingFluids.config.riverDroughtRefillMultiplier = a,
                                () -> FlowingFluids.config.riverDroughtRefillMultiplier))
                        .then(floatChanceCommand("drain_chance",
                                "Chance for shallow river water to lose a level during drought conditions.",
                                a -> FlowingFluids.config.riverDroughtDrainChance = a,
                                () -> FlowingFluids.config.riverDroughtDrainChance))
                        .then(intCommand("max_affected_level",
                                "Highest water level that drought draining is allowed to touch.",
                                "level", 1, 8,
                                a -> FlowingFluids.config.riverDroughtMaxAffectedLevel = a,
                                () -> FlowingFluids.config.riverDroughtMaxAffectedLevel))
                        .then(floatCommand("heatwave_drain_bonus",
                                "Extra drain multiplier applied when a heatwave overlaps a drought.",
                                "multiplier", 1.0f, 4.0f,
                                a -> FlowingFluids.config.riverDroughtHeatwaveDrainBonus = a,
                                () -> FlowingFluids.config.riverDroughtHeatwaveDrainBonus)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> floodCommand() {
        return Commands.literal("flood")
                .executes(FFCommands::floodStatus)
                .then(Commands.literal("status")
                        .executes(FFCommands::floodStatus))
                .then(booleanCommand("enable",
                        "Master toggle for manually triggered and rain-boosted flood events.",
                        a -> FlowingFluids.config.enableFloodEvents = a,
                        () -> FlowingFluids.config.enableFloodEvents))
                .then(Commands.literal("start_here")
                        .executes(cont -> startFloodHere(cont,
                                FlowingFluids.config.floodDefaultRadius,
                                Math.max(1, FlowingFluids.config.floodDefaultDurationTicks / 20),
                                Integer.MIN_VALUE))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(8, 256))
                                .executes(cont -> startFloodHere(cont,
                                        cont.getArgument("radius", Integer.class),
                                        Math.max(1, FlowingFluids.config.floodDefaultDurationTicks / 20),
                                        Integer.MIN_VALUE))
                                .then(Commands.argument("duration_seconds", IntegerArgumentType.integer(1, 7200))
                                        .executes(cont -> startFloodHere(cont,
                                                cont.getArgument("radius", Integer.class),
                                                cont.getArgument("duration_seconds", Integer.class),
                                                Integer.MIN_VALUE))
                                        .then(Commands.argument("waterline_y", IntegerArgumentType.integer(-128, 512))
                                                .executes(cont -> startFloodHere(cont,
                                                        cont.getArgument("radius", Integer.class),
                                                        cont.getArgument("duration_seconds", Integer.class),
                                                        cont.getArgument("waterline_y", Integer.class)))))))
                .then(Commands.literal("stop")
                        .executes(cont -> message(cont,
                                FloodEventSystem.stopFlood(cont.getSource().getLevel())
                                        ? "Stopped the active flood event in this dimension."
                                        : "No active flood event was running in this dimension.")))
                .then(intCommand("default_radius",
                        "Default radius used by `start_here` when no custom radius is provided.",
                        "radius", 8, 256,
                        a -> FlowingFluids.config.floodDefaultRadius = a,
                        () -> FlowingFluids.config.floodDefaultRadius))
                .then(intCommand("default_duration_seconds",
                        "Default flood duration used by `start_here` when no custom duration is provided.",
                        "seconds", 1, 7200,
                        a -> FlowingFluids.config.floodDefaultDurationTicks = a * 20,
                        () -> Math.max(1, FlowingFluids.config.floodDefaultDurationTicks / 20)))
                .then(intCommand("pulse_interval_ticks",
                        "How often an active flood attempts new water placements.",
                        "ticks", 1, 200,
                        a -> FlowingFluids.config.floodPulseIntervalTicks = a,
                        () -> FlowingFluids.config.floodPulseIntervalTicks))
                .then(intCommand("placements_per_pulse",
                        "How many flood placements are attempted per pulse.",
                        "placements", 1, 256,
                        a -> FlowingFluids.config.floodPlacementsPerPulse = a,
                        () -> FlowingFluids.config.floodPlacementsPerPulse))
                .then(intCommand("water_amount_per_placement",
                        "Base water amount used for each flood placement.",
                        "amount", 1, 8,
                        a -> FlowingFluids.config.floodWaterAmountPerPlacement = a,
                        () -> FlowingFluids.config.floodWaterAmountPerPlacement))
                .then(intCommand("shore_search_radius",
                        "How far each placement checks for nearby existing water.",
                        "radius", 1, 16,
                        a -> FlowingFluids.config.floodShoreSearchRadius = a,
                        () -> FlowingFluids.config.floodShoreSearchRadius))
                .then(intCommand("max_water_rise",
                        "Maximum height above the waterline a flood can climb when placing water.",
                        "rise", 1, 16,
                        a -> FlowingFluids.config.floodMaxWaterRise = a,
                        () -> FlowingFluids.config.floodMaxWaterRise))
                .then(floatCommand("lowland_bias",
                        "How strongly flood placement prefers bowl-shaped low ground.",
                        "bias", 0.0f, 4.0f,
                        a -> FlowingFluids.config.floodLowlandBias = a,
                        () -> FlowingFluids.config.floodLowlandBias))
                .then(floatCommand("rain_amount_multiplier",
                        "Extra rain refill strength granted inside an active flood zone.",
                        "multiplier", 1.0f, 8.0f,
                        a -> FlowingFluids.config.floodRainAmountMultiplier = a,
                        () -> FlowingFluids.config.floodRainAmountMultiplier))
                .then(floatCommand("start_chance_per_day",
                        "Chance that storm weather starts a natural flood roll in this dimension each day.",
                        "chance", 0.0f, 1.0f,
                        a -> FlowingFluids.config.floodStartChancePerDay = a,
                        () -> FlowingFluids.config.floodStartChancePerDay))
                .then(booleanCommand("requires_rain",
                        "Controls whether natural flood events wait for rain or thunderstorms.",
                        a -> FlowingFluids.config.floodRequiresRain = a,
                        () -> FlowingFluids.config.floodRequiresRain))
                .then(floatCommand("thunderstorm_multiplier",
                        "Multiplier applied to the natural flood start chance during thunderstorms.",
                        "multiplier", 0.1f, 8.0f,
                        a -> FlowingFluids.config.floodThunderstormChanceMultiplier = a,
                        () -> FlowingFluids.config.floodThunderstormChanceMultiplier))
                .then(booleanCommand("announce",
                        "Announces natural flood starts to players.",
                        a -> FlowingFluids.config.announceFloodEvents = a,
                        () -> FlowingFluids.config.announceFloodEvents));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> waterPressureCommand() {
        return Commands.literal("water_pressure")
                .executes(FFCommands::waterPressureStatus)
                .then(Commands.literal("status")
                        .executes(FFCommands::waterPressureStatus))
                .then(booleanCommand("enable",
                        "Allows supported barriers to build up water pressure and eventually fail.",
                        a -> FlowingFluids.config.enableWaterPressure = a,
                        () -> FlowingFluids.config.enableWaterPressure))
                .then(booleanCommand("doors",
                        "Allows water pressure to target doors.",
                        a -> FlowingFluids.config.applyPressureToDoors = a,
                        () -> FlowingFluids.config.applyPressureToDoors))
                .then(booleanCommand("trapdoors",
                        "Allows water pressure to target trapdoors.",
                        a -> FlowingFluids.config.applyPressureToTrapdoors = a,
                        () -> FlowingFluids.config.applyPressureToTrapdoors))
                .then(booleanCommand("fence_gates",
                        "Allows water pressure to target fence gates.",
                        a -> FlowingFluids.config.applyPressureToFenceGates = a,
                        () -> FlowingFluids.config.applyPressureToFenceGates))
                .then(floatCommand("accumulation_rate",
                        "Base pressure added each time a tracked barrier is sampled with water contact.",
                        "rate", 0.0f, 4.0f,
                        a -> FlowingFluids.config.waterPressureAccumulationRate = a,
                        () -> FlowingFluids.config.waterPressureAccumulationRate))
                .then(floatCommand("break_threshold",
                        "Pressure value at which a barrier finally gives way.",
                        "threshold", 0.1f, 128.0f,
                        a -> FlowingFluids.config.waterPressureBreakThreshold = a,
                        () -> FlowingFluids.config.waterPressureBreakThreshold))
                .then(floatCommand("open_state_multiplier",
                        "Extra pressure applied while the target block is already open.",
                        "multiplier", 1.0f, 8.0f,
                        a -> FlowingFluids.config.waterPressureOpenStateMultiplier = a,
                        () -> FlowingFluids.config.waterPressureOpenStateMultiplier))
                .then(floatCommand("metal_resistance",
                        "Additional resistance multiplier for iron doors and trapdoors.",
                        "multiplier", 1.0f, 12.0f,
                        a -> FlowingFluids.config.waterPressureMetalResistance = a,
                        () -> FlowingFluids.config.waterPressureMetalResistance))
                .then(intCommand("decay_ticks",
                        "How long dormant tracked pressure survives without fresh water contact.",
                        "ticks", 1, 24000,
                        a -> FlowingFluids.config.waterPressureDecayTicks = a,
                        () -> FlowingFluids.config.waterPressureDecayTicks))
                .then(intCommand("updates_per_tick",
                        "How many tracked barriers are processed each tick.",
                        "count", 1, 512,
                        a -> FlowingFluids.config.waterPressureUpdatesPerTick = a,
                        () -> FlowingFluids.config.waterPressureUpdatesPerTick))
                .then(intCommand("scan_interval",
                        "Tick interval between random scans for new pressure targets.",
                        "ticks", 1, 1200,
                        a -> FlowingFluids.config.waterPressureScanInterval = a,
                        () -> FlowingFluids.config.waterPressureScanInterval))
                .then(intCommand("scan_attempts",
                        "Random block samples per scanned chunk.",
                        "attempts", 1, 64,
                        a -> FlowingFluids.config.waterPressureScanAttempts = a,
                        () -> FlowingFluids.config.waterPressureScanAttempts))
                .then(intCommand("chunk_radius",
                        "Chunk radius around players used by water pressure scanning.",
                        "radius", 0, 4,
                        a -> FlowingFluids.config.waterPressureChunkRadius = a,
                        () -> FlowingFluids.config.waterPressureChunkRadius))
                .then(intCommand("max_tracked",
                        "Hard cap for simultaneously tracked barriers.",
                        "count", 128, 32768,
                        a -> FlowingFluids.config.waterPressureMaxTracked = a,
                        () -> FlowingFluids.config.waterPressureMaxTracked))
                .then(intCommand("data_ttl",
                        "How long old tracked pressure entries are kept before cleanup drops them.",
                        "ticks", 1, 240000,
                        a -> FlowingFluids.config.waterPressureDataTtl = a,
                        () -> FlowingFluids.config.waterPressureDataTtl));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> springCommand() {
        return Commands.literal("springs")
                .executes(FFCommands::springStatus)
                .then(Commands.literal("status")
                        .executes(FFCommands::springStatus))
                .then(booleanCommand("random_breakage",
                        "When enabled, active spring mouths can occasionally collapse on their own. This is off by default so ordinary worlds stay calm.",
                        "Spring random breakage is now enabled. Active vents may occasionally collapse.",
                        "Spring random breakage is now disabled. Springs will stay stable unless something else breaks them.",
                        a -> FlowingFluids.config.enableSpringRandomBreakage = a,
                        () -> FlowingFluids.config.enableSpringRandomBreakage))
                .then(floatCommand("random_breakage_chance",
                        "Base per-tick collapse chance checked on active spring mouths when random breakage is enabled. Stronger and lava vents strain a bit more.",
                        "chance", 0.0f, 0.25f,
                        a -> FlowingFluids.config.springRandomBreakChance = a,
                        () -> FlowingFluids.config.springRandomBreakChance))
                .then(floatCommand("overworld_spawn_multiplier",
                        "Multiplies spring generation attempts in overworld biomes. Set this to 0 to stop overworld spring worldgen entirely.",
                        "multiplier", 0.0f, 8.0f,
                        a -> FlowingFluids.config.overworldSpringSpawnMultiplier = a,
                        () -> FlowingFluids.config.overworldSpringSpawnMultiplier))
                .then(floatCommand("nether_spawn_multiplier",
                        "Multiplies spring generation attempts in Nether biomes. Set this to 0 to stop Nether spring worldgen entirely.",
                        "multiplier", 0.0f, 8.0f,
                        a -> FlowingFluids.config.netherSpringSpawnMultiplier = a,
                        () -> FlowingFluids.config.netherSpringSpawnMultiplier))
                .then(floatCommand("deep_lava_spawn_multiplier",
                        "Multiplies deep underground lava spring generation attempts after biome bias is applied. Lower values make lava springs rarer.",
                        "multiplier", 0.05f, 3.0f,
                        a -> FlowingFluids.config.deepLavaSpringSpawnMultiplier = a,
                        () -> FlowingFluids.config.deepLavaSpringSpawnMultiplier))
                .then(floatCommand("deep_lava_extra_reject_chance",
                        "Extra rejection added on top of the deep lava biome filter. Higher values make deep lava springs much rarer.",
                        "chance", 0.0f, 0.95f,
                        a -> FlowingFluids.config.deepLavaSpringExtraRejectChance = a,
                        () -> FlowingFluids.config.deepLavaSpringExtraRejectChance))
                .then(intCommand("deep_lava_max_placements",
                        "Maximum number of deep lava springs that a single worldgen feature run may place.",
                        "count", 1, 3,
                        a -> FlowingFluids.config.deepLavaSpringMaxPlacementsPerFeature = a,
                        () -> FlowingFluids.config.deepLavaSpringMaxPlacementsPerFeature))
                .then(booleanCommand("capped_pressure_head",
                        "When enabled, upward springs keep a full pressure column up to the last open space below a stopper block instead of dropping back to a short pulse.",
                        "Capped spring pressure head is now enabled. Upward springs can build pressure right under a cap block.",
                        "Capped spring pressure head is now disabled. Blocked shafts will fall back to the shorter ambient pulse band.",
                        a -> FlowingFluids.config.enableCappedSpringPressureHead = a,
                        () -> FlowingFluids.config.enableCappedSpringPressureHead))
                .then(floatCommand("water_emission_multiplier",
                        "Scales how much water each spring pulse tries to push out after its normal strength and seep bonuses are calculated. Higher values spread harder.",
                        "multiplier", 0.25f, 4.0f,
                        a -> FlowingFluids.config.waterSpringEmissionMultiplier = a,
                        () -> FlowingFluids.config.waterSpringEmissionMultiplier))
                .then(floatCommand("lava_emission_multiplier",
                        "Scales how much lava each spring pulse tries to push out after its normal strength and pressure bonuses are calculated.",
                        "multiplier", 0.25f, 4.0f,
                        a -> FlowingFluids.config.lavaSpringEmissionMultiplier = a,
                        () -> FlowingFluids.config.lavaSpringEmissionMultiplier))
                .then(floatCommand("water_pulse_interval_multiplier",
                        "Scales the time between water spring pressure pulses. Values below 1.0 pulse faster, values above 1.0 pulse slower.",
                        "multiplier", 0.25f, 4.0f,
                        a -> FlowingFluids.config.waterSpringPulseIntervalMultiplier = a,
                        () -> FlowingFluids.config.waterSpringPulseIntervalMultiplier))
                .then(floatCommand("lava_pulse_interval_multiplier",
                        "Scales the time between lava spring pressure pulses. Values below 1.0 pulse faster, values above 1.0 pulse slower.",
                        "multiplier", 0.25f, 4.0f,
                        a -> FlowingFluids.config.lavaSpringPulseIntervalMultiplier = a,
                        () -> FlowingFluids.config.lavaSpringPulseIntervalMultiplier));
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, @SuppressWarnings("unused") Commands.CommandSelection var3) {
        var notFluidException = new SimpleCommandExceptionType(new LiteralMessage("The block you provided is not a fluid block, or is not a fluid block that can flow."));

        var commands = Commands.literal("flowing_fluids")
                .requires(source -> source.hasPermission(4) || (source.getServer().isSingleplayer() && source.getPlayer() != null && source.getServer().isSingleplayerOwner(source.getPlayer().getGameProfile()))
                ).then(Commands.literal("help")
                        .executes(c -> message(c, "Use any of the commands without adding any of it's arguments, E.G '/flowing_fluids settings', to get a description of what the command does and it's current value."))
                ).then(siphonsCommand()
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

                        ).then(Commands.literal("behavior")
                                .executes(commandContext -> message(commandContext, "Behavior settings for Flowing Fluids. Use /flowing_fluids settings behaviour for the full settings tree."))
                                .then(siphonsCommand())
                        ).then(Commands.literal("behaviour")
                                .executes(commandContext -> message(commandContext, "Behaviour settings for Flowing Fluids, use these to change how fluids behave."))
                                .then(intCommand("min_level_for_ice",
                                        "Controls the minimum level of water that will freeze, this is useful for making ice form in partial height water.\nThe default value is 4, and the maximum value is 8.",
                                        "level", 0, 8,
                                        a -> FlowingFluids.config.minWaterLevelForIce = a, () -> FlowingFluids.config.minWaterLevelForIce)
                                ).then(intCommand("fluid_processing_distance",
                                        "Allows you to set a block distance for fluid processing, works kinda like render distance but for fluid flowing.\n0 means infinite distance (works with chunk loaders far from players).\nThe default value is 160, and the maximum value is 256 (though it is limited by the servers processing chunk distance).\nPlease note this affects active flowing, leveling, and refill behaviours like rain; visuals are maintained a little farther out so distant water does not freeze in an ugly half-state.",
                                        "distance_in_blocks", 0, 256,
                                        a -> FlowingFluids.config.playerBlockDistanceForFlowing = a, () -> FlowingFluids.config.playerBlockDistanceForFlowing)
                                ).then(seaLevelOverrides()
                                ).then(intCommand("min_level_for_obsidian",
                                        "Controls the minimum level of lava that will convert to obsidian, this is useful for making obsidian form more consistently.\nThe default value is 6, and the maximum value is 8.",
                                        "level", 0, 8,
                                        a -> FlowingFluids.config.minLavaLevelForObsidian = a, () -> FlowingFluids.config.minLavaLevelForObsidian)
                                ).then(enumCommand("water_processing_mode",
                                        "Switches water-only processing between the current smarter flow model and the lighter legacy flow model from the original mod.",
                                        a -> FlowingFluids.config.waterProcessingMode = a,
                                        () -> FlowingFluids.config.waterProcessingMode,
                                        Pair.of(FFConfig.WaterProcessingMode.MODERN,
                                                "Water processing mode set to MODERN.\nWater will use the current flow analysis, adaptive scheduling, and modern equalization rules."),
                                        Pair.of(FFConfig.WaterProcessingMode.LEGACY,
                                                "Water processing mode set to LEGACY.\nWater will use the simpler original-style flow logic while lava and the rest of the mod stay on the current system."),
                                        Pair.of(FFConfig.WaterProcessingMode.HYBRID,
                                                "Water processing mode set to HYBRID.\nMoving fronts and recent changes stay on legacy flow, while settled reservoirs switch to the modern analysis path.")))
                                ).then(Commands.literal("flow_speed")
                                        .executes(FFCommands::flowSpeedStatus)
                                        .then(Commands.literal("status")
                                                .executes(FFCommands::flowSpeedStatus))
                                        .then(booleanCommand("enable",
                                                "軽量な流速レイヤーを有効/無効にします。\n有効時は、速い水路や決壊流にだけ少し方向性を足して、水の勢いを軽く演出します。\n以前のような重い流速シミュレーションではありません。",
                                                "流速制御を有効にしました。軽量なプロフィール式の流速バイアスが水の動きに反映されます。",
                                                "流速制御を無効にしました。追加の流速バイアスなしで、基本の水路挙動だけを使います。",
                                                a -> FlowingFluids.config.enableFlowSpeedControl = a,
                                                () -> FlowingFluids.config.enableFlowSpeedControl))
                                        .then(floatCommand("strength",
                                                "流速レイヤーがどれくらい強く水の動きへ影響するかを調整します。\n低い値だと控えめ、高い値だと水路や決壊流がはっきりした性格になります。\nおすすめは 0.15 から 0.60 くらいです。",
                                                "strength", 0.0f, 2.0f,
                                                a -> FlowingFluids.config.flowSpeedStrength = a,
                                                () -> FlowingFluids.config.flowSpeedStrength))
                                ).then(Commands.literal("mudification")
                                        .executes(FFCommands::mudificationStatus)
                                        .then(Commands.literal("status")
                                                .executes(FFCommands::mudificationStatus))
                                        .then(booleanCommand("enable",
                                                "Controls whether repeated fast water exposure can turn natural soil into mud.",
                                                a -> FlowingFluids.config.enableMudification = a,
                                                () -> FlowingFluids.config.enableMudification))
                                        .then(floatCommand("strength",
                                                "Adjusts how quickly exposure builds up before dirt-like blocks become mud.",
                                                "strength", 0.0f, 4.0f,
                                                a -> FlowingFluids.config.mudificationStrength = a,
                                                () -> FlowingFluids.config.mudificationStrength))
                                        .then(booleanCommand("banks",
                                                "Controls whether FAST and TORRENT flows can also splash mudification onto nearby banks.",
                                                a -> FlowingFluids.config.mudificationAffectsBanks = a,
                                                () -> FlowingFluids.config.mudificationAffectsBanks))
                                ).then(Commands.literal("hydraulic_blocks")
                                        .executes(FFCommands::hydraulicBlocksStatus)
                                        .then(Commands.literal("status")
                                                .executes(FFCommands::hydraulicBlocksStatus))
                                        .then(booleanCommand("enable",
                                                "Controls whether waterway liners and pressure nozzles affect water flow analysis.",
                                                a -> FlowingFluids.config.enableHydraulicBlocks = a,
                                                () -> FlowingFluids.config.enableHydraulicBlocks))
                                ).then(siphonsCommand()
                                ).then(Commands.literal("cavity_pressure")
                                        .executes(FFCommands::cavityPressureStatus)
                                        .then(Commands.literal("status")
                                                .executes(FFCommands::cavityPressureStatus))
                                        .then(booleanCommand("enable",
                                                "Controls whether enclosed caves, pits, and shafts build lightweight water head from sustained inflow.",
                                                a -> FlowingFluids.config.enableCavityPressureRise = a,
                                                () -> FlowingFluids.config.enableCavityPressureRise))
                                        .then(floatCommand("strength",
                                                "Adjusts how strongly enclosed spaces bias horizontal transfer and connected filling when water is being pushed in.",
                                                "strength", 0.0f, 2.0f,
                                                a -> FlowingFluids.config.cavityPressureStrength = a,
                                                () -> FlowingFluids.config.cavityPressureStrength))
                                        .then(floatCommand("connected_head_strength",
                                        "Adjusts how much nearby connected higher water surfaces add to the local pressure head.",
                                        "strength", 0.0f, 2.0f,
                                        a -> FlowingFluids.config.connectedHeadStrength = a,
                                        () -> FlowingFluids.config.connectedHeadStrength))
                                ).then(Commands.literal("random_tick_level_check_distance")
                                        .executes(cont -> message(cont, "Sets the distance fluids will check for other fluids to level with during random ticks, 0 means disabled, currently set to " + FlowingFluids.config.randomTickLevelingDistance))
                                        .then(Commands.argument("distance", IntegerArgumentType.integer(0, 512))
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
                                        ).then(booleanCommand("broad_surface_suppression",
                                                "海・湖・池の広い安定水面で、全体をならそうとする処理を強く抑えます。",
                                                "広水面抑制を有効にしました。",
                                                "広水面抑制を無効にしました。",
                                                a -> FlowingFluids.config.broadSurfaceSuppressionEnabled = a, () -> FlowingFluids.config.broadSurfaceSuppressionEnabled)
                                        ).then(intCommand("broad_surface_stable_ticks",
                                                "広い水面を静的とみなすまでの安定 tick 数です。",
                                                "ticks", 1, 40,
                                                a -> FlowingFluids.config.broadSurfaceStableTicks = a, () -> FlowingFluids.config.broadSurfaceStableTicks)
                                        ).then(intCommand("broad_surface_slope_clamp",
                                                "広い水面での slope 探索距離の基本 clamp 値です。海はこの値、湖は+1まで使います。",
                                                "distance", 1, 8,
                                                a -> FlowingFluids.config.broadSurfaceSlopeClamp = a, () -> FlowingFluids.config.broadSurfaceSlopeClamp)
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
                                ).then(componentGraphCommand()
                                ).then(Commands.literal("tick_delays__aka__flow_speeds")
                                        .executes(cont -> message(cont, "Modifies the tick delay fluids will have between spreading updates\nThe vanilla value is always 5 for water but lava will vary between 10 and 30 depending on if it is in the Nether."))
                                        .then(Commands.literal("water")
                                                .executes(cont -> message(cont, "Modifies the base tick delay water will have between spreading updates.\nThe vanilla value is always 5 for water.\nWater base tick delay is currently set to " + FlowingFluids.config.waterTickDelay
                                                        + "\n\n" + FluidAutoTickDelay.describeStatus()))
                                                .then(Commands.argument("delay", IntegerArgumentType.integer(1, 255))
                                                        .executes(cont -> {
                                                            FlowingFluids.config.waterTickDelay = cont.getArgument("delay", Integer.class);
                                                            return messageAndSaveConfig(cont, "Water tick delay set to " + FlowingFluids.config.waterTickDelay);
                                                        })
                                                )
                                        ).then(Commands.literal("lava")
                                                .executes(cont -> message(cont, "Modifies the base tick delay lava will have between spreading updates in the overworld.\nThe vanilla value is always 30 for lava in the overworld.\nLava base tick delay is currently set to " + FlowingFluids.config.lavaTickDelay
                                                        + "\n\n" + FluidAutoTickDelay.describeStatus()))
                                                .then(Commands.argument("delay", IntegerArgumentType.integer(1, 255))
                                                        .executes(cont -> {
                                                            FlowingFluids.config.lavaTickDelay = cont.getArgument("delay", Integer.class);
                                                            return messageAndSaveConfig(cont, "Lava tick delay set to " + FlowingFluids.config.lavaTickDelay);
                                                        })
                                                )
                                        ).then(Commands.literal("lava_nether")
                                                .executes(cont -> message(cont, "Modifies the base tick delay lava will have between spreading updates in the nether.\nThe vanilla value is always 10 for lava in the nether.\nLava nether base tick delay is currently set to " + FlowingFluids.config.lavaNetherTickDelay
                                                        + "\n\n" + FluidAutoTickDelay.describeStatus()))
                                                .then(Commands.argument("delay", IntegerArgumentType.integer(1, 255))
                                                        .executes(cont -> {
                                                            FlowingFluids.config.lavaNetherTickDelay = cont.getArgument("delay", Integer.class);
                                                            return messageAndSaveConfig(cont, "Lava_nether tick delay set to " + FlowingFluids.config.lavaNetherTickDelay);
                                                        })
                                                )
                                        ).then(Commands.literal("auto_tick_delay")
                                                .executes(FFCommands::autoTickDelayStatus)
                                                .then(Commands.literal("status")
                                                        .executes(FFCommands::autoTickDelayStatus))
                                                .then(booleanCommand("enable",
                                                        "Automatically relaxes fluid tick delays at runtime when the server MSPT drifts above the configured target.",
                                                        a -> FlowingFluids.config.enableAutoTickDelay = a,
                                                        () -> FlowingFluids.config.enableAutoTickDelay))
                                                .then(intCommand("update_rate_ticks",
                                                        "How often the server samples MSPT and nudges the runtime extra delay.",
                                                        "ticks", 20, 1200,
                                                        a -> FlowingFluids.config.autoTickDelayUpdateRateTicks = a,
                                                        () -> FlowingFluids.config.autoTickDelayUpdateRateTicks))
                                                .then(floatCommand("target_mspt_multiplier",
                                                        "Target fraction of the server tick budget before fluid delays start relaxing. Lower values react earlier.",
                                                        "multiplier", 0.25f, 2.0f,
                                                        a -> FlowingFluids.config.autoTickDelayTargetMsptMultiplier = a,
                                                        () -> FlowingFluids.config.autoTickDelayTargetMsptMultiplier))
                                                .then(intCommand("water_max_extra_delay",
                                                        "Maximum extra runtime delay that auto mode may add to water ticks.",
                                                        "delay", 0, 64,
                                                        a -> FlowingFluids.config.autoTickDelayWaterMaxExtraDelay = a,
                                                        () -> FlowingFluids.config.autoTickDelayWaterMaxExtraDelay))
                                                .then(intCommand("lava_max_extra_delay",
                                                        "Maximum extra runtime delay that auto mode may add to lava ticks.",
                                                        "delay", 0, 64,
                                                        a -> FlowingFluids.config.autoTickDelayLavaMaxExtraDelay = a,
                                                        () -> FlowingFluids.config.autoTickDelayLavaMaxExtraDelay))
                                                .then(booleanCommand("log_adjustments",
                                                        "Logs when the runtime extra delay changes, which helps when tuning MSPT targets.",
                                                        a -> FlowingFluids.config.autoTickDelayLogAdjustments = a,
                                                        () -> FlowingFluids.config.autoTickDelayLogAdjustments))
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
                                ).then(floatChanceCommand("infinite_biome_flowing_refill_chance",
                                        "Chance for flowing water in infinite biomes to regain a little amount during support ticks.",
                                        a -> FlowingFluids.config.infiniteWaterBiomeFlowingRefillChance = a,
                                        () -> FlowingFluids.config.infiniteWaterBiomeFlowingRefillChance)
                                ).then(intCommand("infinite_biome_flowing_refill_interval",
                                        "How often infinite-biome flowing refill support runs.",
                                        "ticks", 1, 200,
                                        a -> FlowingFluids.config.infiniteWaterBiomeFlowingRefillInterval = a,
                                        () -> FlowingFluids.config.infiniteWaterBiomeFlowingRefillInterval)
                                ).then(intCommand("infinite_biome_flowing_refill_max_amount",
                                        "Maximum amount restored by the infinite-biome flowing refill support tick.",
                                        "amount", 1, 8,
                                        a -> FlowingFluids.config.infiniteWaterBiomeFlowingRefillMaxAmount = a,
                                        () -> FlowingFluids.config.infiniteWaterBiomeFlowingRefillMaxAmount)
                                ).then(booleanCommand("only_infinite_biomes_at_sea_level",
                                        "Controls if the infinite biome refilling only happens to water at exactly sea level.",
                                        a -> FlowingFluids.config.fastBiomeRefillAtSeaLevelOnly = a, () -> FlowingFluids.config.fastBiomeRefillAtSeaLevelOnly)
                                ).then(Commands.literal("infinite_biome_preset")
                                        .executes(cont -> message(cont, "Infinite biome refill presets"
                                                + "\nboosted: noticeably faster refill with a small amount increase"
                                                + "\naggressive: strong refill for quick recovery and testing"
                                                + "\nreset: restore the default infinite biome refill values"))
                                        .then(Commands.literal("boosted")
                                                .executes(cont -> applyInfiniteBiomePreset(cont, "boosted")))
                                        .then(Commands.literal("aggressive")
                                                .executes(cont -> applyInfiniteBiomePreset(cont, "aggressive")))
                                        .then(Commands.literal("reset")
                                                .executes(cont -> applyInfiniteBiomePreset(cont, "reset"))))
                                .then(Commands.literal("inspect_infinite_here")
                                        .executes(FFCommands::inspectInfiniteBiomeHere))
                                .then(Commands.literal("infinite_biome_runtime_status")
                                        .executes(FFCommands::inspectInfiniteBiomeHere))
                                .then(infiniteBiomeEntriesCommand())
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
                                /* Legacy rain multithread controls intentionally hidden.
                                   Rain placement now always runs on the tick thread. */
                                /*
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
                                */
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
                ).then(Commands.literal("snowmelt")
                                .executes(FFCommands::snowmeltStatus)
                                .then(Commands.literal("status")
                                        .executes(FFCommands::snowmeltStatus))
                                .then(booleanCommand("enable",
                                        "雪解け水システムを有効/無効にします。\n有効時は、明るくて暖かい条件のもとで、プレイヤー周辺の露出した雪や氷が少しずつ溶けます。",
                                        "雪解け水システムを有効にしました。",
                                        "雪解け水システムを無効にしました。",
                                        a -> FlowingFluids.config.enableSnowmeltSystem = a,
                                        () -> FlowingFluids.config.enableSnowmeltSystem))
                                .then(booleanCommand("daytime_only",
                                        "ON だと雪解け判定を昼間だけに制限します。\nOFF にすると、明るさや気温の条件を満たしていれば夜や特殊環境でも溶けるようになります。",
                                        "雪解け判定を昼間限定にしました。",
                                        "昼以外でも条件を満たせば雪解けするようにしました。",
                                        a -> FlowingFluids.config.snowmeltDaytimeOnly = a,
                                        () -> FlowingFluids.config.snowmeltDaytimeOnly))
                                .then(booleanCommand("places_water",
                                        "ON だと、雪や氷が溶けたあとに実際の水を置こうとします。\nOFF だと、溶けたブロックは消えるだけで水は残しません。",
                                        "雪解け後に水を置くようにしました。",
                                        "雪解け後に水を置かないようにしました。",
                                        a -> FlowingFluids.config.snowmeltPlacesWater = a,
                                        () -> FlowingFluids.config.snowmeltPlacesWater))
                                .then(intCommand("chunk_radius",
                                        "各プレイヤーの周囲どこまでの読み込み済みチャンクを雪解け候補として見るかを決めます。\n大きいほど広範囲に効きますが、そのぶん見る地形も増えます。",
                                        "radius", 0, 8,
                                        a -> FlowingFluids.config.snowmeltChunkRadius = a,
                                        () -> FlowingFluids.config.snowmeltChunkRadius))
                                .then(intCommand("interval_ticks",
                                        "雪解けサンプラーを何 tick ごとに回すかを決めます。\n小さいほど反応は速くなり、大きいほど穏やかで軽くなります。",
                                        "ticks", 1, 1200,
                                        a -> FlowingFluids.config.snowmeltIntervalTicks = a,
                                        () -> FlowingFluids.config.snowmeltIntervalTicks))
                                .then(intCommand("attempts_per_chunk",
                                        "選ばれた1チャンクの中で、1回の実行あたり何回ランダムに表面チェックするかを決めます。\n多いほど雪原で溶けるきっかけが増えます。",
                                        "attempts", 0, 64,
                                        a -> FlowingFluids.config.snowmeltAttemptsPerChunk = a,
                                        () -> FlowingFluids.config.snowmeltAttemptsPerChunk))
                                .then(intCommand("max_chunks_per_tick",
                                        "1回の実行で雪解けが処理してよいチャンク数の上限です。\n複数人プレイ時に、処理範囲を暴れさせたくないときに使います。",
                                        "chunks", 0, 512,
                                        a -> FlowingFluids.config.snowmeltMaxChunksPerTick = a,
                                        () -> FlowingFluids.config.snowmeltMaxChunksPerTick))
                                .then(floatCommand("base_chance",
                                        "条件を満たした雪や氷が、実際に溶ける基礎確率です。\nこの機能全体の『溶けやすさ』を決める主ノブです。",
                                        "chance", 0.0f, 1.0f,
                                        a -> FlowingFluids.config.snowmeltBaseChance = a,
                                        () -> FlowingFluids.config.snowmeltBaseChance))
                                .then(intCommand("water_amount",
                                        "氷が溶けて水を置くときの水量です。\n1 は細いしずく寄り、8 はフルブロック量です。",
                                        "amount", 1, 8,
                                        a -> FlowingFluids.config.snowmeltWaterAmount = a,
                                        () -> FlowingFluids.config.snowmeltWaterAmount))
                                .then(intCommand("min_sky_light",
                                        "雪解けを許可するために必要な上空の最低空光です。\n高くすると晴天寄りの厳しめ判定、低くすると緩めの判定になります。",
                                        "light", 0, 15,
                                        a -> FlowingFluids.config.snowmeltMinSkyLight = a,
                                        () -> FlowingFluids.config.snowmeltMinSkyLight))
                                .then(floatCommand("min_temperature",
                                        "雪解けに必要なバイオーム基礎気温の下限です。\n低いほど寒い場所でも溶けやすくなり、高いほど温暖な土地中心の雪解けになります。",
                                        "temperature", -1.0f, 4.0f,
                                        a -> FlowingFluids.config.snowmeltMinTemperature = a,
                                        () -> FlowingFluids.config.snowmeltMinTemperature))
                        ).then(dryingCommand())
                        .then(floodCommand())
                        .then(waterPressureCommand())
                        .then(springCommand())
                        .then(Commands.literal("~debug").executes(cont -> message(cont, "Debug commands you probably don't need these."))
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
                                                            AdaptiveTickScheduler.scheduleFluidTick(level, blockPos, blockState.getFluidState().getType(), 1 + rand.nextInt(200));
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
                            .executes(cont -> message(cont, "Changes how the Create Mod's water wheels interact with fluids, select an mode to get further information. Default is flow_or_river. Water wheel mode is currently set to " + FlowingFluids.config.create_waterWheelMode))
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
