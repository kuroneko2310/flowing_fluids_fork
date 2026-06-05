package traben.flowing_fluids.config;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
#if MC > MC_21
import net.minecraft.util.ARGB;
#else
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
#endif
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FluidAmountConverter;
import traben.flowing_fluids.FluidRegressionLogic;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.FlowingFluidsPlatform;

import java.util.List;

public class FFConfig {
    private static ObjectOpenHashSet<String> createStringSet(String... entries) {
        return new ObjectOpenHashSet<>(List.of(entries));
    }

    public boolean flowToEdges = true;
    public boolean enableMod = true;
//    public boolean debugSpread = false;
//    public boolean debugSpreadPrint = false;
    public boolean enableDisplacement = true;
    public boolean enablePistonPushing = true;
    public float rainRefillChance = 0.3f;
    public float oceanRiverSwampRefillChance = 0.05f;
    public float evaporationChanceV2 = 1f;
    public float evaporationChanceMultiplier = 1f;
    public int evaporationIntervalTicks = 1;
    public int evaporationThinWaterMaxLevel = 2;
    public boolean evaporationDaytimeOnly = true;
    public boolean evaporationRequiresSky = true;
    public float evaporationNetherChance = 1f;
    public float evaporationNetherChanceMultiplier = 1f;
    public int evaporationNetherIntervalTicks = 1;
    public boolean enableSeaLevelOverflowEvaporation = true;
    public boolean seaLevelOverflowEvaporationInstant = true;
    public float seaLevelOverflowEvaporationChance = 0.5f;
    public float seaLevelOverflowEvaporationHeightScale = 0.25f;
    public int seaLevelOverflowEvaporationMinExcess = 1;
    public int seaLevelOverflowEvaporationMaxExcess = 32;
    public int seaLevelOverflowInfiniteBiomeBufferRadius = 10;
    public boolean enableHeatwaveEvents = true;
    public float heatwaveStartChancePerDay = 0.08f;
    public int heatwaveMinDurationTicks = 12000;
    public int heatwaveMaxDurationTicks = 36000;
    public float heatwaveEvaporationMultiplier = 2.0f;
    public float heatwaveRainRefillMultiplier = 0.6f;
    public boolean heatwaveDaytimeOnly = true;
    public boolean enableDrySeasonEvents = true;
    public float drySeasonStartChancePerDay = 0.03f;
    public int drySeasonMinDurationTicks = 72000;
    public int drySeasonMaxDurationTicks = 168000;
    public float drySeasonEvaporationMultiplier = 1.5f;
    public float drySeasonRainRefillMultiplier = 0.45f;
    public boolean enableHotBlockEvaporation = true;
    public float hotBlockEvaporationChance = 0.35f;
    public float hotBlockEvaporationChanceMultiplier = 1f;
    public int hotBlockEvaporationIntervalTicks = 1;
    public int hotBlockEvaporationRadius = 2;
    public int hotBlockEvaporationVerticalRange = 1;
    public int hotBlockEvaporationDrainAmount = 1;
    public boolean enableShadeProtection = true;
    public int shadeRoofSearchHeight = 6;
    public boolean enableRiverDroughts = true;
    public float riverDroughtRefillMultiplier = 0.2f;
    public float riverDroughtDrainChance = 0.08f;
    public int riverDroughtMaxAffectedLevel = 4;
    public float riverDroughtHeatwaveDrainBonus = 1.35f;
    public boolean printRandomTicks = false;
    public boolean hideFlowingTexture = true;
    public LiquidHeight fullLiquidHeight = LiquidHeight.REGULAR;
    public float farmlandDrainWaterChance = 0.1f;
    public boolean debugWaterLevelColours = false;
    public WaterLogFlowMode waterLogFlowMode = WaterLogFlowMode.IN_FROM_TOP_ELSE_OUT;
    public int waterFlowDistance = 4;
    public int lavaFlowDistance = 2;
    public int lavaNetherFlowDistance = 4;
    public float waterTickDelay = 4.0f;
    public float lavaTickDelay = 15.0f;
    public float lavaNetherTickDelay = 5.0f;
    public int randomTickLevelingDistance = 32;
    public WaterProcessingMode waterProcessingMode = WaterProcessingMode.MODERN;

    // Advanced water flow distance settings
    public int maxWaterFlowDistance = 8; // Maximum horizontal flow distance (can be higher than base flow distance)
    public int bfsMaxSearchDistance = 16; // Maximum BFS search distance for equalization
    public float slopeFindDistanceMultiplier = 1.2f; // Multiplier for slope finding distance (1.0 = default, higher = farther search)
    public boolean enableAdaptiveFlowDistance = true; // Adjust flow distance based on terrain type
    public int riverFlowDistance = 64; // Flow distance in river biomes
    public int oceanFlowDistance = 128; // Flow distance in ocean biomes
    public int canalFlowDistance = 48; // Flow distance for artificial canals (flat terrain)
    public boolean broadSurfaceSuppressionEnabled = true; // Suppress wide, stable surface leveling until disturbed
    public int broadSurfaceStableTicks = 6; // Stable ticks required before broad surface suppression applies
    public int broadSurfaceSlopeClamp = 2; // Base slope-search clamp for broad surfaces
    public int forcedEqualizationStableTicks = 1200; // Stable ticks before a forced lightweight recheck is scheduled
    public int forcedEqualizationCooldownTicks = 600; // Cooldown between forced rechecks per column
    public float forcedEqualizationBudgetFactor = 0.2f; // Budget multiplier when running forced lightweight checks
    public int horizontalSupplementDepth = 12; // Depth for horizontal-only exploration sweeps
    public int horizontalSupplementExtraNodes = 96; // Additional nodes budget for horizontal sweeps
    public int inletProbeMaxSteps = 8; // Straight-line inlet probe to prevent 3-block stalls on narrow drains
    public int clusterDiffusionHeightThreshold = 6; // Minimum height delta to trigger cluster diffusion
    public int clusterDiffusionMaxCluster = 96; // Maximum positions to include in a cluster diffusion pass
    public float clusterDiffusionBudgetPortion = 0.2f; // Portion of remaining BFS budget usable for diffusion

    // Performance monitoring settings
    public boolean enablePerformanceMonitoring = false; // Enable detailed performance tracking
    public int performanceLogInterval = 200; // Log performance data every N ticks (20 ticks = 1 second)
    public boolean enableDistanceBasedOptimization = true; // Apply optimizations based on flow distance
    public boolean enableAutoTickDelay = true; // Relax fluid tick delay when the server is struggling
    public int autoTickDelayUpdateRateTicks = 200; // How often to sample MSPT and adjust runtime delay
    public float autoTickDelayTargetMsptMultiplier = 0.9f; // Target fraction of server MSPT budget before slowing fluids
    public int autoTickDelayWaterMaxExtraDelay = 12; // Maximum extra delay added to water ticks at runtime
    public int autoTickDelayLavaMaxExtraDelay = 8; // Maximum extra delay added to lava ticks at runtime
    public boolean autoTickDelayLogAdjustments = true; // Log when the runtime delay bias changes

    // Workload governor and parallel wake scheduling
    public boolean enableFluidWorkloadGovernor = true; // Protects server ticks from runaway fluid scheduled-tick storms
    public boolean fluidWorkloadGovernorSpatialDeferral = false; // Extra spatial skipping under high MSPT; off by default for responsive flow
    public boolean fluidWorkloadGovernorQueuePressureDelay = false; // Add delay at schedule time only when explicitly prioritizing stability
    public int activeWakeFlushBudgetPerTick = 16_384; // Active water wake positions flushed per level tick; 0 disables the cap
    public int activeWakeMaxDelayTicks = 2; // Max spread delay for active wake scheduling; lower is faster, higher is smoother

    // Fluid component graph
    public boolean enableFluidComponentGraph = false; // Track local fluid components from deltas instead of broad rescans
    public boolean fluidComponentGraphAssistEqualizer = true; // Let stable graph interiors use focused equalizer snapshots
    public int fluidComponentGraphMaxUpdatesPerTick = 96; // Dirty graph seeds processed per level tick
    public int fluidComponentGraphMaxScanNodes = 512; // Maximum nodes rebuilt for one local component
    public boolean enableRouteSolver = false; // Experimental edge/potential route solver for water equalization
    public int routeSolverIterations = 4; // Relaxation passes for route-based water movement
    public int routeSolverMaxTransferPerEdge = 16; // Max internal water amount moved across one route edge per pass
    public float routeSolverDownhillBias = 2.0f; // Extra pull for downward route edges

    // Flow cohesion and inertia
    public float waterAffinityStrength = 0.2f; // Bias flow toward nearby water (0 = off)
    public float flowInertiaStrength = 0.25f; // Bias flow toward last direction (0 = off)
    public int flowInertiaMaxAgeTicks = 40; // How long inertia is remembered
    public boolean enableFlowSpeedControl = true; // Lightweight flow speed tiers derived from water profile + momentum
    public float flowSpeedStrength = 0.35f; // How strongly flow speed tiers bias transfer and thin-edge movement
    public int flowActivationTicks = 1; // Force ticks briefly after flow updates; profile-based breach handling keeps fronts responsive
    public boolean forceTickWhenAdjacentAir = false; // Always tick when adjacent to air/replaceable blocks
    public int forceFlowLevelDifference = 2; // Force flow when level difference exceeds this
    public int stepDownSearchDistance = 3; // How many blocks horizontally to search for step-down outlets (1-3)
    public float pressureFlowBonusStrength = 0.1f; // Extra lateral transfer based on height difference
    public float downwardPressureStrength = 0.4f; // Reduces retention when falling (pressure effect)
    public int downwardPressureMaxColumn = 4; // Max column height to sample for pressure
    public float connectedFlowDelayMultiplier = 0.6f; // Tick delay multiplier for connected flow lines
    public float channelBoostDelayMultiplier = 0.5f; // Tick delay multiplier for narrow channels
    public float downwardTickDelayMultiplier = 0.5f; // Tick delay multiplier when falling
    public float activeFlowDistanceBudgetBoost = 1.0f; // Distance budget boost while actively flowing
    public boolean enableHydraulicGradientFlow = true; // Pressure-aware flow model for rivers, canals, and deep intakes
    public int hydraulicSampleDistance = 4; // Upstream distance sampled for nearby stored water pressure
    public float hydraulicDepthWeight = 1.0f; // Local depth contribution to hydraulic drive
    public float hydraulicUpstreamWeight = 0.75f; // Connected upstream water contribution
    public float hydraulicIntakeWeight = 1.15f; // River or basin intake boost
    public float hydraulicChannelVelocityWeight = 0.45f; // How much confinement speeds up narrow channels
    public float hydraulicChannelCapacityWeight = 0.7f; // How much width/headroom increases moved volume
    public float hydraulicTickAcceleration = 0.55f; // How strongly hydraulic drive reduces tick delay
    public boolean enableCavityPressureRise = true; // Let enclosed spaces build water head and rise when flow is sustained
    public float cavityPressureStrength = 1.0f; // How strongly enclosed-space pressure biases transfer and filling
    public float connectedHeadStrength = 0.8f; // Extra bias from nearby connected higher water surfaces
    public boolean enableMudification = false; // Retired; kept for config/network compatibility
    public float mudificationStrength = 1.0f; // Retired; kept for config/network compatibility
    public boolean mudificationAffectsBanks = false; // Retired; kept for config/network compatibility
    public boolean enableHydraulicBlocks = true; // Allow liner and nozzle support blocks to bias water flow
    public boolean enableSiphons = true; // Enable bounded water siphons
    public int hydraulicSiphonMaxSearchNodes = 256; // Maximum BFS nodes per hydraulic siphon search
    public int hydraulicSiphonMaxPathLength = 64; // Maximum guide/nozzle path length for hydraulic siphons
    public int hydraulicSiphonMaxLift = 12; // Maximum blocks a hydraulic siphon may rise above the inlet surface
    public int hydraulicSiphonSourceSurfaceScanNodes = 128; // Connected source cells checked for hydraulic source surface
    public int hydraulicSiphonMaxPressureHead = 12; // Maximum carried hydraulic pressure head
    public int hydraulicSiphonMaxTransferPerTick = 8; // Maximum hydraulic siphon transfer per source tick
    public boolean enableNaturalTerrainSiphons = true; // Allow bounded terrain pressure siphons through enclosed water paths
    public int naturalSiphonMaxSearchNodes = 128; // Maximum BFS nodes per natural siphon search
    public int naturalSiphonMaxPathLength = 24; // Maximum full-water path length for natural siphons
    public int naturalSiphonMaxLift = 4; // Maximum blocks a natural siphon may rise above the inlet surface
    public int naturalSiphonMinFilledAmount = 6; // Minimum water level accepted as a natural siphon path cell
    public int naturalSiphonMaxTransferPerTick = 2; // Maximum natural siphon transfer per source tick
    public int naturalSiphonCooldownTicks = 20; // Cooldown before retrying the same natural siphon source
    public boolean naturalSiphonRequireEnclosedPath = true; // Require narrow or mostly enclosed natural paths
    public boolean naturalSiphonAllowOpenSurface = false; // Allow open-surface natural paths with heavy limits
    public boolean siphonSameLevelOutletsAnywhere = false; // Allow same-height low-water outlets without hydraulic support

    public float drinkWaterToBreedAnimalChance = 0.1f;
    public boolean encloseAllFluidOnWorldGen = true;
    public boolean announceWorldGenActions = false;
    public boolean easyPistonPump = true;
    public boolean waterFlowAffectsBoats = false;
    public boolean waterFlowAffectsEntities = true;
    public boolean waterFlowAffectsPlayers = false;
    public boolean waterFlowAffectsItems = true;

    // Water pressure system
    public boolean enableWaterPressure = false;
    public boolean applyPressureToDoors = true;
    public boolean applyPressureToTrapdoors = true;
    public boolean applyPressureToFenceGates = true;
    public float waterPressureAccumulationRate = 0.1f;
    public float waterPressureBreakThreshold = 12.0f;
    public float waterPressureOpenStateMultiplier = 1.5f;
    public float waterPressureMetalResistance = 3.0f;
    public int waterPressureDecayTicks = 120;
    public int waterPressureUpdatesPerTick = 32;
    public int waterPressureScanInterval = 20;
    public int waterPressureScanAttempts = 4;
    public int waterPressureChunkRadius = 2;
    public int waterPressureMaxTracked = 4096;
    public int waterPressureDataTtl = 1200;
    public float infiniteWaterBiomeNonConsumeChance = 0.01f;
    public float infiniteWaterBiomeDrainSurfaceChance = 0.1f;
    public float infiniteWaterBiomeFlowingRefillChance = 0.025f;
    public int infiniteWaterBiomeFlowingRefillInterval = 16;
    public int infiniteWaterBiomeFlowingRefillMaxAmount = 1;
    public int minWaterLevelForIce = 4;
    public boolean rainFillsWaterHigherV2 = false;
    public int rainBfsCooldownTicks = 5;
    public float rainSurfaceSpawnChance = 0.02f;
    public int rainSurfaceSpawnLevel = 1;
    public float rainLevelJumpChance = 0.05f;
    public int infiniteBiomeRainFillMaxLevel = 6;
    public int minLavaLevelForObsidian = 6;
    public boolean fastBiomeRefillAtSeaLevelOnly = false;
    public int playerBlockDistanceForFlowing = 160;
    public float concreteDrainsWaterChance = 0.5f;
    public boolean autoDetectWaterBiomes = true;
    public ObjectOpenHashSet<String> extraOceanBiomes = new ObjectOpenHashSet<>();
    public ObjectOpenHashSet<String> extraRiverBiomes = new ObjectOpenHashSet<>();
    public ObjectOpenHashSet<String> extraBeachBiomes = new ObjectOpenHashSet<>();
    public ObjectOpenHashSet<String> extraInfiniteBiomeEntries = new ObjectOpenHashSet<>();
    public boolean enableAutomaticInfiniteBiomeAddition = true;
    public boolean automaticInfiniteBiomeAdditionModdedOnly = true;
    public ObjectOpenHashSet<String> automaticInfiniteBiomeTagHints = createStringSet(
            "#forge:is_water",
            "#forge:is_swamp",
            "#forge:is_wet",
            "#forge:is_river",
            "#forge:is_beach",
            "#forge:is_ocean",
            "#c:is_water",
            "#c:is_swamp",
            "#c:is_wet",
            "#c:is_river",
            "#c:is_beach",
            "#c:is_ocean"
    );
    public ObjectOpenHashSet<String> automaticInfiniteBiomeKeywordHints = createStringSet(
            "ocean",
            "sea",
            "gulf",
            "bay",
            "coast",
            "shore",
            "beach",
            "river",
            "stream",
            "creek",
            "delta",
            "swamp",
            "marsh",
            "mangrove",
            "bog",
            "wetland",
            "lagoon",
            "estuary",
            "reef"
    );

    // Extended waterlogging for blocks that normally cannot hold fluids (e.g., fences/iron bars).
    public boolean enableExtendedWaterlogging = true;
    public boolean extendedWaterloggingAllowFences = true; // covers fences/iron bars/walls-like if tagged

    // Adaptive scheduler settings
    public long adaptiveSchedulerChunkExpiryMs = 60_000; // 1 minute by default
    public int adaptiveSchedulerMaxEntries = 10_000; // Max cached stability entries

    // Rain system settings
    public boolean enableRainSystem = true;
    public int rainChunkRadius = 3;
    public int rainGenerateIntervalTicks = 200;
    public int rainAttemptsPerChunk = 4;
    public float rainBaseGenerateChance = 0.03f;
    public int rainBaseWaterAmount = 2;
    public int rainMaxChunksPerTick = 24;
    public boolean rainEnableBiomeFiltering = true;
    public boolean rainSkipInfiniteWaterBiomes = true;
    public boolean rainEnableChunkCaching = true;
    public long rainCacheDurationTicks = 20L * 60L * 5L;
    public int rainMaxSurfaceSearchDepth = 4;
    public int rainMaxWaterStackHeight = 3;
    // Rain placement now always runs on the server tick thread.
    // The older multithread toggles were removed once the runtime path was retired.
    public int rainPlacementQueueSize = 1024;
    public float rainQueueSoftCapRatio = 0.65f;
    public float rainQueueMinChanceMultiplier = 0.35f;
    public int rainPlacementAggregationDistance = 1;
    public int rainPlacementMaxCombinedAmount = 16;
    public int rainWetnessPersistTicks = 1200;
    public int rainCatchmentRadius = 3;
    public float rainCatchmentMaxBoost = 1.6f;
    public int rainUpstreamSearchRadius = 6;
    public float rainUpstreamMaxBoost = 1.5f;
    public float rainIntensityDrizzleMultiplier = 0.55f;
    public float rainIntensitySteadyMultiplier = 1.0f;
    public float rainIntensityHeavyMultiplier = 1.65f;
    public float rainIntensityThunderstormMultiplier = 2.25f;

    public float rainPrecipJungle = 1.5f;
    public float rainPrecipSwamp = 1.25f;
    public float rainPrecipDesert = 0.15f;
    public float rainPrecipSavanna = 0.6f;
    public float rainPrecipPlains = 1.0f;
    public float rainPrecipForest = 1.0f;
    public float rainPrecipTaiga = 0.8f;

    // Snowmelt system settings
    public boolean enableSnowmeltSystem = true;
    public boolean snowmeltDaytimeOnly = true;
    public boolean snowmeltPlacesWater = true;
    public int snowmeltChunkRadius = 2;
    public int snowmeltIntervalTicks = 160;
    public int snowmeltAttemptsPerChunk = 2;
    public int snowmeltMaxChunksPerTick = 16;
    public float snowmeltBaseChance = 0.08f;
    public int snowmeltWaterAmount = 1;
    public int snowmeltMinSkyLight = 10;
    public float snowmeltMinTemperature = 0.2f;

    // Flood event settings
    public boolean enableFloodEvents = true;
    public float floodStartChancePerDay = 0.04f;
    public boolean floodRequiresRain = true;
    public float floodThunderstormChanceMultiplier = 2.5f;
    public boolean announceFloodEvents = true;
    public int floodDefaultRadius = 48;
    public int floodDefaultDurationTicks = 20 * 90;
    public int floodPulseIntervalTicks = 5;
    public int floodPlacementsPerPulse = 28;
    public int floodWaterAmountPerPlacement = 4;
    public int floodShoreSearchRadius = 4;
    public int floodMaxWaterRise = 3;
    public float floodLowlandBias = 1.0f;
    public float floodRainAmountMultiplier = 2.0f;
    public boolean enableNetherLavaEvents = true;
    public float netherLavaEventStartChancePerDay = 0.35f;
    public int netherLavaEventMinDurationTicks = 20 * 75;
    public int netherLavaEventMaxDurationTicks = 20 * 180;
    public int netherLavaEventDefaultRadius = 44;
    public int netherLavaEventPulseIntervalTicks = 8;
    public boolean enableSpringRandomBreakage = false;
    public float springRandomBreakChance = 0.0025f;
    public float overworldSpringSpawnMultiplier = 1.0f;
    public float netherSpringSpawnMultiplier = 1.0f;
    public float deepLavaSpringSpawnMultiplier = 0.58f;
    public float deepLavaSpringExtraRejectChance = 0.16f;
    public int deepLavaSpringMaxPlacementsPerFeature = 2;
    public boolean enableCappedSpringPressureHead = true;
    public float waterSpringEmissionMultiplier = 1.0f;
    public float lavaSpringEmissionMultiplier = 1.0f;
    public float waterSpringPulseIntervalMultiplier = 1.0f;
    public float lavaSpringPulseIntervalMultiplier = 1.0f;
    public boolean enableAnalyticPoolDormancy = false;


    // create mod options
    public CreateWaterWheelMode create_waterWheelMode = CreateWaterWheelMode.REQUIRE_FLOW_OR_RIVER;
    public boolean create_infinitePipes = false;

    // fluid blacklist
    public ObjectOpenHashSet<String> fluidBlacklist = new ObjectOpenHashSet<>();
    // dimension blacklist
    public ObjectOpenHashSet<String> excludedDimensions = new ObjectOpenHashSet<>();
    // sea level overrides used by fluid behaviour checks
    public int defaultSeaLevelOverride = Integer.MIN_VALUE;
    public Int2IntOpenHashMap dimensionSeaLevelOverrides = new Int2IntOpenHashMap();

    public boolean isFluidAllowed(Fluid fluid){
        if (fluid == null) return false;
        // quick most likely exits to avoid searching the blacklist
        if (fluidBlacklist.isEmpty() || fluid == Fluids.EMPTY) return true;
        return !fluidBlacklist.contains(BuiltInRegistries.FLUID.getKey(fluid).toString());
    }
    public boolean isFluidAllowed(FluidState fluid){
        return isFluidAllowed(fluid.getType());
    }

    public boolean isWaterAllowed(){
        return isFluidAllowed(Fluids.WATER);
    }

    public boolean isDimensionExcluded(LevelAccessor level) {
        if (excludedDimensions == null || excludedDimensions.isEmpty()) return false;
        if (level instanceof net.minecraft.world.level.Level lvl) {
            return excludedDimensions.contains(lvl.dimension().location().toString());
        }
        return false;
    }

    public boolean dontTickAtLocation(BlockPos pos, LevelAccessor level) {
        if (playerBlockDistanceForFlowing == 0) return false;

        return isOutsidePlayerDistance(pos, level, playerBlockDistanceForFlowing, false);
    }

    public boolean dontMaintainFluidVisualsAtLocation(BlockPos pos, LevelAccessor level) {
        int visualDistance = FluidRegressionLogic.getPlayerVisualMaintenanceDistance(playerBlockDistanceForFlowing);
        if (visualDistance == 0) {
            return false;
        }
        return isOutsidePlayerDistance(pos, level, visualDistance, true);
    }

    private boolean isOutsidePlayerDistance(BlockPos pos, LevelAccessor level, int distance, boolean visualRange) {
        int sqrDist = distance * distance;

        if (level instanceof net.minecraft.world.level.Level lvl) {
            if (lvl.getNearestPlayer(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, distance, false) != null) {
                return false;
            }
        } else {
            // if any player is within distance
            for(Player player2 : level.players()) {
                double i = player2.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
                if (i < sqrDist) return false;
            }
        }

        if (visualRange) {
            return !FlowingFluidsPlatform.hasVisualFlowAnchorInRange(level, pos);
        }
        return !FlowingFluidsPlatform.hasProcessingFlowAnchorInRange(level, pos);
    }

    public FFConfig() {
    }

    //color range from red to blue over 8 steps
    public static int[] waterLevelColours ={

            #if MC > MC_21
            ARGB.color(255,0,0,255),
            ARGB.color(255,0,128,255),
            ARGB.color(255,0,255,192),
            ARGB.color(255,0,255,0),
            ARGB.color(255,255,255,0),
            ARGB.color(255,255,128,0),
            ARGB.color(255,255,0,0),
            ARGB.color(255,255,255,255)
            #else
            FastColor.ARGB32.color(255,0,0,255),
            FastColor.ARGB32.color(255,0,128,255),
            FastColor.ARGB32.color(255,0,255,192),
            FastColor.ARGB32.color(255,0,255,0),
            FastColor.ARGB32.color(255,255,255,0),
            FastColor.ARGB32.color(255,255,128,0),
            FastColor.ARGB32.color(255,255,0,0),
            FastColor.ARGB32.color(255,255,255,255)
            #endif
    };

    public FFConfig(FriendlyByteBuf buffer) {

        FlowingFluids.info("- Decoding server config packet from server.");
        //PRESERVE WRITE ORDER IN READ
        /////////////////////////////////////////
        flowToEdges = buffer.readBoolean();
        enableMod = buffer.readBoolean();
        enableDisplacement = buffer.readBoolean();
        enablePistonPushing = buffer.readBoolean();
        rainRefillChance = buffer.readFloat();
        oceanRiverSwampRefillChance = buffer.readFloat();
        evaporationChanceV2 = buffer.readFloat();
        evaporationChanceMultiplier = buffer.readFloat();
        evaporationIntervalTicks = buffer.readVarInt();
        evaporationThinWaterMaxLevel = buffer.readVarInt();
        evaporationDaytimeOnly = buffer.readBoolean();
        evaporationRequiresSky = buffer.readBoolean();
        evaporationNetherChance = buffer.readFloat();
        evaporationNetherChanceMultiplier = buffer.readFloat();
        evaporationNetherIntervalTicks = buffer.readVarInt();
        enableSeaLevelOverflowEvaporation = buffer.readBoolean();
        seaLevelOverflowEvaporationInstant = buffer.readBoolean();
        seaLevelOverflowEvaporationChance = buffer.readFloat();
        seaLevelOverflowEvaporationHeightScale = buffer.readFloat();
        seaLevelOverflowEvaporationMinExcess = buffer.readVarInt();
        seaLevelOverflowEvaporationMaxExcess = buffer.readVarInt();
        seaLevelOverflowInfiniteBiomeBufferRadius = buffer.readVarInt();
        enableHeatwaveEvents = buffer.readBoolean();
        heatwaveStartChancePerDay = buffer.readFloat();
        heatwaveMinDurationTicks = buffer.readVarInt();
        heatwaveMaxDurationTicks = buffer.readVarInt();
        heatwaveEvaporationMultiplier = buffer.readFloat();
        heatwaveRainRefillMultiplier = buffer.readFloat();
        heatwaveDaytimeOnly = buffer.readBoolean();
        enableDrySeasonEvents = buffer.readBoolean();
        drySeasonStartChancePerDay = buffer.readFloat();
        drySeasonMinDurationTicks = buffer.readVarInt();
        drySeasonMaxDurationTicks = buffer.readVarInt();
        drySeasonEvaporationMultiplier = buffer.readFloat();
        drySeasonRainRefillMultiplier = buffer.readFloat();
        enableHotBlockEvaporation = buffer.readBoolean();
        hotBlockEvaporationChance = buffer.readFloat();
        hotBlockEvaporationChanceMultiplier = buffer.readFloat();
        hotBlockEvaporationIntervalTicks = buffer.readVarInt();
        hotBlockEvaporationRadius = buffer.readVarInt();
        hotBlockEvaporationVerticalRange = buffer.readVarInt();
        hotBlockEvaporationDrainAmount = buffer.readVarInt();
        enableShadeProtection = buffer.readBoolean();
        shadeRoofSearchHeight = buffer.readVarInt();
        enableRiverDroughts = buffer.readBoolean();
        riverDroughtRefillMultiplier = buffer.readFloat();
        riverDroughtDrainChance = buffer.readFloat();
        riverDroughtMaxAffectedLevel = buffer.readVarInt();
        riverDroughtHeatwaveDrainBonus = buffer.readFloat();
        printRandomTicks = buffer.readBoolean();
        hideFlowingTexture = buffer.readBoolean();
        fullLiquidHeight = buffer.readEnum(LiquidHeight.class);
        farmlandDrainWaterChance = buffer.readFloat();
        debugWaterLevelColours = buffer.readBoolean();
        waterLogFlowMode = buffer.readEnum(WaterLogFlowMode.class);
        waterFlowDistance = buffer.readVarInt();
        lavaFlowDistance = buffer.readVarInt();
        lavaNetherFlowDistance = buffer.readVarInt();
        waterTickDelay = buffer.readFloat();
        lavaTickDelay = buffer.readFloat();
        lavaNetherTickDelay = buffer.readFloat();
        randomTickLevelingDistance = buffer.readVarInt();
        waterProcessingMode = buffer.readEnum(WaterProcessingMode.class);

        // Advanced water flow distance settings
        maxWaterFlowDistance = buffer.readVarInt();
        bfsMaxSearchDistance = buffer.readVarInt();
        slopeFindDistanceMultiplier = buffer.readFloat();
        enableAdaptiveFlowDistance = buffer.readBoolean();
        riverFlowDistance = buffer.readVarInt();
        oceanFlowDistance = buffer.readVarInt();
        canalFlowDistance = buffer.readVarInt();
        broadSurfaceSuppressionEnabled = buffer.readBoolean();
        broadSurfaceStableTicks = buffer.readVarInt();
        broadSurfaceSlopeClamp = buffer.readVarInt();

        // Performance monitoring settings
        enablePerformanceMonitoring = buffer.readBoolean();
        performanceLogInterval = buffer.readVarInt();
        enableDistanceBasedOptimization = buffer.readBoolean();
        enableAutoTickDelay = buffer.readBoolean();
        autoTickDelayUpdateRateTicks = buffer.readVarInt();
        autoTickDelayTargetMsptMultiplier = buffer.readFloat();
        autoTickDelayWaterMaxExtraDelay = buffer.readVarInt();
        autoTickDelayLavaMaxExtraDelay = buffer.readVarInt();
        autoTickDelayLogAdjustments = buffer.readBoolean();
        enableFluidComponentGraph = buffer.readBoolean();
        fluidComponentGraphAssistEqualizer = buffer.readBoolean();
        fluidComponentGraphMaxUpdatesPerTick = buffer.readVarInt();
        fluidComponentGraphMaxScanNodes = buffer.readVarInt();
        enableRouteSolver = buffer.readBoolean();
        routeSolverIterations = buffer.readVarInt();
        routeSolverMaxTransferPerEdge = buffer.readVarInt();
        routeSolverDownhillBias = buffer.readFloat();
        waterAffinityStrength = buffer.readFloat();
        flowInertiaStrength = buffer.readFloat();
        flowInertiaMaxAgeTicks = buffer.readVarInt();
        enableFlowSpeedControl = buffer.readBoolean();
        flowSpeedStrength = buffer.readFloat();
        flowActivationTicks = buffer.readVarInt();
        forceTickWhenAdjacentAir = buffer.readBoolean();
        forceFlowLevelDifference = buffer.readVarInt();
        pressureFlowBonusStrength = buffer.readFloat();
        downwardPressureStrength = buffer.readFloat();
        downwardPressureMaxColumn = buffer.readVarInt();
        connectedFlowDelayMultiplier = buffer.readFloat();
        channelBoostDelayMultiplier = buffer.readFloat();
        downwardTickDelayMultiplier = buffer.readFloat();
        activeFlowDistanceBudgetBoost = buffer.readFloat();
        enableHydraulicGradientFlow = buffer.readBoolean();
        hydraulicSampleDistance = buffer.readVarInt();
        hydraulicDepthWeight = buffer.readFloat();
        hydraulicUpstreamWeight = buffer.readFloat();
        hydraulicIntakeWeight = buffer.readFloat();
        hydraulicChannelVelocityWeight = buffer.readFloat();
        hydraulicChannelCapacityWeight = buffer.readFloat();
        hydraulicTickAcceleration = buffer.readFloat();
        enableCavityPressureRise = buffer.readBoolean();
        cavityPressureStrength = buffer.readFloat();
        connectedHeadStrength = buffer.readFloat();
        enableMudification = buffer.readBoolean();
        mudificationStrength = buffer.readFloat();
        mudificationAffectsBanks = buffer.readBoolean();
        enableHydraulicBlocks = buffer.readBoolean();
        enableSiphons = buffer.readBoolean();
        hydraulicSiphonMaxSearchNodes = buffer.readVarInt();
        hydraulicSiphonMaxPathLength = buffer.readVarInt();
        hydraulicSiphonMaxLift = buffer.readVarInt();
        hydraulicSiphonSourceSurfaceScanNodes = buffer.readVarInt();
        hydraulicSiphonMaxPressureHead = buffer.readVarInt();
        hydraulicSiphonMaxTransferPerTick = buffer.readVarInt();
        enableNaturalTerrainSiphons = buffer.readBoolean();
        naturalSiphonMaxSearchNodes = buffer.readVarInt();
        naturalSiphonMaxPathLength = buffer.readVarInt();
        naturalSiphonMaxLift = buffer.readVarInt();
        naturalSiphonMinFilledAmount = buffer.readVarInt();
        naturalSiphonMaxTransferPerTick = buffer.readVarInt();
        naturalSiphonCooldownTicks = buffer.readVarInt();
        naturalSiphonRequireEnclosedPath = buffer.readBoolean();
        naturalSiphonAllowOpenSurface = buffer.readBoolean();
        siphonSameLevelOutletsAnywhere = buffer.readBoolean();

        drinkWaterToBreedAnimalChance = buffer.readFloat();
        encloseAllFluidOnWorldGen = buffer.readBoolean();
        announceWorldGenActions = buffer.readBoolean();
        easyPistonPump = buffer.readBoolean();
        waterFlowAffectsBoats = buffer.readBoolean();
        waterFlowAffectsEntities = buffer.readBoolean();
        waterFlowAffectsPlayers = buffer.readBoolean();
        waterFlowAffectsItems = buffer.readBoolean();
        enableWaterPressure = buffer.readBoolean();
        applyPressureToDoors = buffer.readBoolean();
        applyPressureToTrapdoors = buffer.readBoolean();
        applyPressureToFenceGates = buffer.readBoolean();
        waterPressureAccumulationRate = buffer.readFloat();
        waterPressureBreakThreshold = buffer.readFloat();
        waterPressureOpenStateMultiplier = buffer.readFloat();
        waterPressureMetalResistance = buffer.readFloat();
        waterPressureDecayTicks = buffer.readVarInt();
        waterPressureUpdatesPerTick = buffer.readVarInt();
        waterPressureScanInterval = buffer.readVarInt();
        waterPressureScanAttempts = buffer.readVarInt();
        waterPressureChunkRadius = buffer.readVarInt();
        waterPressureMaxTracked = buffer.readVarInt();
        waterPressureDataTtl = buffer.readVarInt();
        infiniteWaterBiomeNonConsumeChance = buffer.readFloat();
        infiniteWaterBiomeDrainSurfaceChance = buffer.readFloat();
        infiniteWaterBiomeFlowingRefillChance = buffer.readFloat();
        infiniteWaterBiomeFlowingRefillInterval = buffer.readVarInt();
        infiniteWaterBiomeFlowingRefillMaxAmount = buffer.readVarInt();
        minWaterLevelForIce = buffer.readVarInt();
        rainFillsWaterHigherV2 = buffer.readBoolean();
        rainBfsCooldownTicks = buffer.readVarInt();
        rainSurfaceSpawnChance = buffer.readFloat();
        rainSurfaceSpawnLevel = buffer.readVarInt();
        rainLevelJumpChance = buffer.readFloat();
        infiniteBiomeRainFillMaxLevel = buffer.readVarInt();
        minLavaLevelForObsidian = buffer.readVarInt();
        fastBiomeRefillAtSeaLevelOnly = buffer.readBoolean();
        playerBlockDistanceForFlowing = buffer.readVarInt();
        concreteDrainsWaterChance = buffer.readFloat();

        autoDetectWaterBiomes = buffer.readBoolean();
        extraOceanBiomes = buffer.readCollection(ObjectOpenHashSet::new, FriendlyByteBuf::readUtf);
        extraRiverBiomes = buffer.readCollection(ObjectOpenHashSet::new, FriendlyByteBuf::readUtf);
        extraBeachBiomes = buffer.readCollection(ObjectOpenHashSet::new, FriendlyByteBuf::readUtf);
        extraInfiniteBiomeEntries = buffer.readCollection(ObjectOpenHashSet::new, FriendlyByteBuf::readUtf);
        enableAutomaticInfiniteBiomeAddition = buffer.readBoolean();
        automaticInfiniteBiomeAdditionModdedOnly = buffer.readBoolean();
        automaticInfiniteBiomeTagHints = buffer.readCollection(ObjectOpenHashSet::new, FriendlyByteBuf::readUtf);
        automaticInfiniteBiomeKeywordHints = buffer.readCollection(ObjectOpenHashSet::new, FriendlyByteBuf::readUtf);

        enableExtendedWaterlogging = buffer.readBoolean();
        extendedWaterloggingAllowFences = buffer.readBoolean();

        adaptiveSchedulerChunkExpiryMs = buffer.readVarLong();
        adaptiveSchedulerMaxEntries = buffer.readVarInt();


        //create mod options
        create_waterWheelMode = buffer.readEnum(CreateWaterWheelMode.class);
        create_infinitePipes = buffer.readBoolean();

        //blacklist
        fluidBlacklist = buffer.readCollection(ObjectOpenHashSet::new, FriendlyByteBuf::readUtf);

        // dimension blacklist
        excludedDimensions = buffer.readCollection(ObjectOpenHashSet::new, FriendlyByteBuf::readUtf);

        defaultSeaLevelOverride = buffer.readVarInt();
        int seaLevelOverrideCount = buffer.readVarInt();
        dimensionSeaLevelOverrides = new Int2IntOpenHashMap(Math.max(0, seaLevelOverrideCount));
        for (int i = 0; i < seaLevelOverrideCount; i++) {
            dimensionSeaLevelOverrides.put(buffer.readVarInt(), buffer.readVarInt());
        }

        enableRainSystem = buffer.readBoolean();
        rainChunkRadius = buffer.readVarInt();
        rainGenerateIntervalTicks = buffer.readVarInt();
        rainAttemptsPerChunk = buffer.readVarInt();
        rainBaseGenerateChance = buffer.readFloat();
        rainBaseWaterAmount = buffer.readVarInt();
        rainMaxChunksPerTick = buffer.readVarInt();
        rainEnableBiomeFiltering = buffer.readBoolean();
        rainSkipInfiniteWaterBiomes = buffer.readBoolean();
        rainEnableChunkCaching = buffer.readBoolean();
        rainCacheDurationTicks = buffer.readVarLong();
        rainMaxSurfaceSearchDepth = buffer.readVarInt();
        rainMaxWaterStackHeight = buffer.readVarInt();
        rainPlacementQueueSize = buffer.readVarInt();
        rainQueueSoftCapRatio = buffer.readFloat();
        rainQueueMinChanceMultiplier = buffer.readFloat();
        rainPlacementAggregationDistance = buffer.readVarInt();
        rainPlacementMaxCombinedAmount = buffer.readVarInt();
        rainWetnessPersistTicks = buffer.readVarInt();
        rainCatchmentRadius = buffer.readVarInt();
        rainCatchmentMaxBoost = buffer.readFloat();
        rainUpstreamSearchRadius = buffer.readVarInt();
        rainUpstreamMaxBoost = buffer.readFloat();
        rainIntensityDrizzleMultiplier = buffer.readFloat();
        rainIntensitySteadyMultiplier = buffer.readFloat();
        rainIntensityHeavyMultiplier = buffer.readFloat();
        rainIntensityThunderstormMultiplier = buffer.readFloat();

        rainPrecipJungle = buffer.readFloat();
        rainPrecipSwamp = buffer.readFloat();
        rainPrecipDesert = buffer.readFloat();
        rainPrecipSavanna = buffer.readFloat();
        rainPrecipPlains = buffer.readFloat();
        rainPrecipForest = buffer.readFloat();
        rainPrecipTaiga = buffer.readFloat();
        enableSnowmeltSystem = buffer.readBoolean();
        snowmeltDaytimeOnly = buffer.readBoolean();
        snowmeltPlacesWater = buffer.readBoolean();
        snowmeltChunkRadius = buffer.readVarInt();
        snowmeltIntervalTicks = buffer.readVarInt();
        snowmeltAttemptsPerChunk = buffer.readVarInt();
        snowmeltMaxChunksPerTick = buffer.readVarInt();
        snowmeltBaseChance = buffer.readFloat();
        snowmeltWaterAmount = buffer.readVarInt();
        snowmeltMinSkyLight = buffer.readVarInt();
        snowmeltMinTemperature = buffer.readFloat();
        enableFloodEvents = buffer.readBoolean();
        floodStartChancePerDay = buffer.readFloat();
        floodRequiresRain = buffer.readBoolean();
        floodThunderstormChanceMultiplier = buffer.readFloat();
        announceFloodEvents = buffer.readBoolean();
        floodDefaultRadius = buffer.readVarInt();
        floodDefaultDurationTicks = buffer.readVarInt();
        floodPulseIntervalTicks = buffer.readVarInt();
        floodPlacementsPerPulse = buffer.readVarInt();
        floodWaterAmountPerPlacement = buffer.readVarInt();
        floodShoreSearchRadius = buffer.readVarInt();
        floodMaxWaterRise = buffer.readVarInt();
        floodLowlandBias = buffer.readFloat();
        floodRainAmountMultiplier = buffer.readFloat();
        enableNetherLavaEvents = buffer.readBoolean();
        netherLavaEventStartChancePerDay = buffer.readFloat();
        netherLavaEventMinDurationTicks = buffer.readVarInt();
        netherLavaEventMaxDurationTicks = buffer.readVarInt();
        netherLavaEventDefaultRadius = buffer.readVarInt();
        netherLavaEventPulseIntervalTicks = buffer.readVarInt();
        enableSpringRandomBreakage = buffer.readBoolean();
        springRandomBreakChance = buffer.readFloat();
        overworldSpringSpawnMultiplier = buffer.readFloat();
        netherSpringSpawnMultiplier = buffer.readFloat();
        deepLavaSpringSpawnMultiplier = buffer.readFloat();
        deepLavaSpringExtraRejectChance = buffer.readFloat();
        deepLavaSpringMaxPlacementsPerFeature = buffer.readVarInt();
        enableCappedSpringPressureHead = buffer.readBoolean();
        waterSpringEmissionMultiplier = buffer.readFloat();
        lavaSpringEmissionMultiplier = buffer.readFloat();
        waterSpringPulseIntervalMultiplier = buffer.readFloat();
        lavaSpringPulseIntervalMultiplier = buffer.readFloat();
        enableAnalyticPoolDormancy = buffer.readBoolean();
        ///////////////////////////////////////////////
    }

    public void encodeToByteBuffer(FriendlyByteBuf buffer) {

        FlowingFluids.info("- Encoding server config packet for client.");

        //PRESERVE WRITE ORDER IN READ
        /////////////////////////////////////////
        buffer.writeBoolean(flowToEdges);
        buffer.writeBoolean(enableMod);
        buffer.writeBoolean(enableDisplacement);
        buffer.writeBoolean(enablePistonPushing);
        buffer.writeFloat(rainRefillChance);
        buffer.writeFloat(oceanRiverSwampRefillChance);
        buffer.writeFloat(evaporationChanceV2);
        buffer.writeFloat(evaporationChanceMultiplier);
        buffer.writeVarInt(evaporationIntervalTicks);
        buffer.writeVarInt(evaporationThinWaterMaxLevel);
        buffer.writeBoolean(evaporationDaytimeOnly);
        buffer.writeBoolean(evaporationRequiresSky);
        buffer.writeFloat(evaporationNetherChance);
        buffer.writeFloat(evaporationNetherChanceMultiplier);
        buffer.writeVarInt(evaporationNetherIntervalTicks);
        buffer.writeBoolean(enableSeaLevelOverflowEvaporation);
        buffer.writeBoolean(seaLevelOverflowEvaporationInstant);
        buffer.writeFloat(seaLevelOverflowEvaporationChance);
        buffer.writeFloat(seaLevelOverflowEvaporationHeightScale);
        buffer.writeVarInt(seaLevelOverflowEvaporationMinExcess);
        buffer.writeVarInt(seaLevelOverflowEvaporationMaxExcess);
        buffer.writeVarInt(seaLevelOverflowInfiniteBiomeBufferRadius);
        buffer.writeBoolean(enableHeatwaveEvents);
        buffer.writeFloat(heatwaveStartChancePerDay);
        buffer.writeVarInt(heatwaveMinDurationTicks);
        buffer.writeVarInt(heatwaveMaxDurationTicks);
        buffer.writeFloat(heatwaveEvaporationMultiplier);
        buffer.writeFloat(heatwaveRainRefillMultiplier);
        buffer.writeBoolean(heatwaveDaytimeOnly);
        buffer.writeBoolean(enableDrySeasonEvents);
        buffer.writeFloat(drySeasonStartChancePerDay);
        buffer.writeVarInt(drySeasonMinDurationTicks);
        buffer.writeVarInt(drySeasonMaxDurationTicks);
        buffer.writeFloat(drySeasonEvaporationMultiplier);
        buffer.writeFloat(drySeasonRainRefillMultiplier);
        buffer.writeBoolean(enableHotBlockEvaporation);
        buffer.writeFloat(hotBlockEvaporationChance);
        buffer.writeFloat(hotBlockEvaporationChanceMultiplier);
        buffer.writeVarInt(hotBlockEvaporationIntervalTicks);
        buffer.writeVarInt(hotBlockEvaporationRadius);
        buffer.writeVarInt(hotBlockEvaporationVerticalRange);
        buffer.writeVarInt(hotBlockEvaporationDrainAmount);
        buffer.writeBoolean(enableShadeProtection);
        buffer.writeVarInt(shadeRoofSearchHeight);
        buffer.writeBoolean(enableRiverDroughts);
        buffer.writeFloat(riverDroughtRefillMultiplier);
        buffer.writeFloat(riverDroughtDrainChance);
        buffer.writeVarInt(riverDroughtMaxAffectedLevel);
        buffer.writeFloat(riverDroughtHeatwaveDrainBonus);
        buffer.writeBoolean(printRandomTicks);
        buffer.writeBoolean(hideFlowingTexture);
        buffer.writeEnum(fullLiquidHeight);
        buffer.writeFloat(farmlandDrainWaterChance);
        buffer.writeBoolean(debugWaterLevelColours);
        buffer.writeEnum(waterLogFlowMode);
        buffer.writeVarInt(waterFlowDistance);
        buffer.writeVarInt(lavaFlowDistance);
        buffer.writeVarInt(lavaNetherFlowDistance);
        buffer.writeFloat(waterTickDelay);
        buffer.writeFloat(lavaTickDelay);
        buffer.writeFloat(lavaNetherTickDelay);
        buffer.writeVarInt(randomTickLevelingDistance);
        buffer.writeEnum(waterProcessingMode);

        // Advanced water flow distance settings
        buffer.writeVarInt(maxWaterFlowDistance);
        buffer.writeVarInt(bfsMaxSearchDistance);
        buffer.writeFloat(slopeFindDistanceMultiplier);
        buffer.writeBoolean(enableAdaptiveFlowDistance);
        buffer.writeVarInt(riverFlowDistance);
        buffer.writeVarInt(oceanFlowDistance);
        buffer.writeVarInt(canalFlowDistance);
        buffer.writeBoolean(broadSurfaceSuppressionEnabled);
        buffer.writeVarInt(broadSurfaceStableTicks);
        buffer.writeVarInt(broadSurfaceSlopeClamp);

        // Performance monitoring settings
        buffer.writeBoolean(enablePerformanceMonitoring);
        buffer.writeVarInt(performanceLogInterval);
        buffer.writeBoolean(enableDistanceBasedOptimization);
        buffer.writeBoolean(enableAutoTickDelay);
        buffer.writeVarInt(autoTickDelayUpdateRateTicks);
        buffer.writeFloat(autoTickDelayTargetMsptMultiplier);
        buffer.writeVarInt(autoTickDelayWaterMaxExtraDelay);
        buffer.writeVarInt(autoTickDelayLavaMaxExtraDelay);
        buffer.writeBoolean(autoTickDelayLogAdjustments);
        buffer.writeBoolean(enableFluidComponentGraph);
        buffer.writeBoolean(fluidComponentGraphAssistEqualizer);
        buffer.writeVarInt(fluidComponentGraphMaxUpdatesPerTick);
        buffer.writeVarInt(fluidComponentGraphMaxScanNodes);
        buffer.writeBoolean(enableRouteSolver);
        buffer.writeVarInt(routeSolverIterations);
        buffer.writeVarInt(routeSolverMaxTransferPerEdge);
        buffer.writeFloat(routeSolverDownhillBias);
        buffer.writeFloat(waterAffinityStrength);
        buffer.writeFloat(flowInertiaStrength);
        buffer.writeVarInt(flowInertiaMaxAgeTicks);
        buffer.writeBoolean(enableFlowSpeedControl);
        buffer.writeFloat(flowSpeedStrength);
        buffer.writeVarInt(flowActivationTicks);
        buffer.writeBoolean(forceTickWhenAdjacentAir);
        buffer.writeVarInt(forceFlowLevelDifference);
        buffer.writeFloat(pressureFlowBonusStrength);
        buffer.writeFloat(downwardPressureStrength);
        buffer.writeVarInt(downwardPressureMaxColumn);
        buffer.writeFloat(connectedFlowDelayMultiplier);
        buffer.writeFloat(channelBoostDelayMultiplier);
        buffer.writeFloat(downwardTickDelayMultiplier);
        buffer.writeFloat(activeFlowDistanceBudgetBoost);
        buffer.writeBoolean(enableHydraulicGradientFlow);
        buffer.writeVarInt(hydraulicSampleDistance);
        buffer.writeFloat(hydraulicDepthWeight);
        buffer.writeFloat(hydraulicUpstreamWeight);
        buffer.writeFloat(hydraulicIntakeWeight);
        buffer.writeFloat(hydraulicChannelVelocityWeight);
        buffer.writeFloat(hydraulicChannelCapacityWeight);
        buffer.writeFloat(hydraulicTickAcceleration);
        buffer.writeBoolean(enableCavityPressureRise);
        buffer.writeFloat(cavityPressureStrength);
        buffer.writeFloat(connectedHeadStrength);
        buffer.writeBoolean(enableMudification);
        buffer.writeFloat(mudificationStrength);
        buffer.writeBoolean(mudificationAffectsBanks);
        buffer.writeBoolean(enableHydraulicBlocks);
        buffer.writeBoolean(enableSiphons);
        buffer.writeVarInt(hydraulicSiphonMaxSearchNodes);
        buffer.writeVarInt(hydraulicSiphonMaxPathLength);
        buffer.writeVarInt(hydraulicSiphonMaxLift);
        buffer.writeVarInt(hydraulicSiphonSourceSurfaceScanNodes);
        buffer.writeVarInt(hydraulicSiphonMaxPressureHead);
        buffer.writeVarInt(hydraulicSiphonMaxTransferPerTick);
        buffer.writeBoolean(enableNaturalTerrainSiphons);
        buffer.writeVarInt(naturalSiphonMaxSearchNodes);
        buffer.writeVarInt(naturalSiphonMaxPathLength);
        buffer.writeVarInt(naturalSiphonMaxLift);
        buffer.writeVarInt(naturalSiphonMinFilledAmount);
        buffer.writeVarInt(naturalSiphonMaxTransferPerTick);
        buffer.writeVarInt(naturalSiphonCooldownTicks);
        buffer.writeBoolean(naturalSiphonRequireEnclosedPath);
        buffer.writeBoolean(naturalSiphonAllowOpenSurface);
        buffer.writeBoolean(siphonSameLevelOutletsAnywhere);

        buffer.writeFloat(drinkWaterToBreedAnimalChance);
        buffer.writeBoolean(encloseAllFluidOnWorldGen);
        buffer.writeBoolean(announceWorldGenActions);
        buffer.writeBoolean(easyPistonPump);
        buffer.writeBoolean(waterFlowAffectsBoats);
        buffer.writeBoolean(waterFlowAffectsEntities);
        buffer.writeBoolean(waterFlowAffectsPlayers);
        buffer.writeBoolean(waterFlowAffectsItems);
        buffer.writeBoolean(enableWaterPressure);
        buffer.writeBoolean(applyPressureToDoors);
        buffer.writeBoolean(applyPressureToTrapdoors);
        buffer.writeBoolean(applyPressureToFenceGates);
        buffer.writeFloat(waterPressureAccumulationRate);
        buffer.writeFloat(waterPressureBreakThreshold);
        buffer.writeFloat(waterPressureOpenStateMultiplier);
        buffer.writeFloat(waterPressureMetalResistance);
        buffer.writeVarInt(waterPressureDecayTicks);
        buffer.writeVarInt(waterPressureUpdatesPerTick);
        buffer.writeVarInt(waterPressureScanInterval);
        buffer.writeVarInt(waterPressureScanAttempts);
        buffer.writeVarInt(waterPressureChunkRadius);
        buffer.writeVarInt(waterPressureMaxTracked);
        buffer.writeVarInt(waterPressureDataTtl);
        buffer.writeFloat(infiniteWaterBiomeNonConsumeChance);
        buffer.writeFloat(infiniteWaterBiomeDrainSurfaceChance);
        buffer.writeFloat(infiniteWaterBiomeFlowingRefillChance);
        buffer.writeVarInt(infiniteWaterBiomeFlowingRefillInterval);
        buffer.writeVarInt(infiniteWaterBiomeFlowingRefillMaxAmount);
        buffer.writeVarInt(minWaterLevelForIce);
        buffer.writeBoolean(rainFillsWaterHigherV2);
        buffer.writeVarInt(rainBfsCooldownTicks);
        buffer.writeFloat(rainSurfaceSpawnChance);
        buffer.writeVarInt(rainSurfaceSpawnLevel);
        buffer.writeFloat(rainLevelJumpChance);
        buffer.writeVarInt(infiniteBiomeRainFillMaxLevel);
        buffer.writeVarInt(minLavaLevelForObsidian);
        buffer.writeBoolean(fastBiomeRefillAtSeaLevelOnly);
        buffer.writeVarInt(playerBlockDistanceForFlowing);
        buffer.writeFloat(concreteDrainsWaterChance);

        buffer.writeBoolean(autoDetectWaterBiomes);
        buffer.writeCollection(extraOceanBiomes, FriendlyByteBuf::writeUtf);
        buffer.writeCollection(extraRiverBiomes, FriendlyByteBuf::writeUtf);
        buffer.writeCollection(extraBeachBiomes, FriendlyByteBuf::writeUtf);
        buffer.writeCollection(extraInfiniteBiomeEntries, FriendlyByteBuf::writeUtf);
        buffer.writeBoolean(enableAutomaticInfiniteBiomeAddition);
        buffer.writeBoolean(automaticInfiniteBiomeAdditionModdedOnly);
        buffer.writeCollection(automaticInfiniteBiomeTagHints, FriendlyByteBuf::writeUtf);
        buffer.writeCollection(automaticInfiniteBiomeKeywordHints, FriendlyByteBuf::writeUtf);

        buffer.writeBoolean(enableExtendedWaterlogging);
        buffer.writeBoolean(extendedWaterloggingAllowFences);

        buffer.writeVarLong(adaptiveSchedulerChunkExpiryMs);
        buffer.writeVarInt(adaptiveSchedulerMaxEntries);

        //create mod options
        buffer.writeEnum(create_waterWheelMode);
        buffer.writeBoolean(create_infinitePipes);

        //blacklist
        buffer.writeCollection(fluidBlacklist, FriendlyByteBuf::writeUtf);

        // dimension blacklist
        buffer.writeCollection(excludedDimensions, FriendlyByteBuf::writeUtf);

        buffer.writeVarInt(defaultSeaLevelOverride);
        buffer.writeVarInt(dimensionSeaLevelOverrides.size());
        for (Int2IntMap.Entry entry : dimensionSeaLevelOverrides.int2IntEntrySet()) {
            buffer.writeVarInt(entry.getIntKey());
            buffer.writeVarInt(entry.getIntValue());
        }

        buffer.writeBoolean(enableRainSystem);
        buffer.writeVarInt(rainChunkRadius);
        buffer.writeVarInt(rainGenerateIntervalTicks);
        buffer.writeVarInt(rainAttemptsPerChunk);
        buffer.writeFloat(rainBaseGenerateChance);
        buffer.writeVarInt(rainBaseWaterAmount);
        buffer.writeVarInt(rainMaxChunksPerTick);
        buffer.writeBoolean(rainEnableBiomeFiltering);
        buffer.writeBoolean(rainSkipInfiniteWaterBiomes);
        buffer.writeBoolean(rainEnableChunkCaching);
        buffer.writeVarLong(rainCacheDurationTicks);
        buffer.writeVarInt(rainMaxSurfaceSearchDepth);
        buffer.writeVarInt(rainMaxWaterStackHeight);
        buffer.writeVarInt(rainPlacementQueueSize);
        buffer.writeFloat(rainQueueSoftCapRatio);
        buffer.writeFloat(rainQueueMinChanceMultiplier);
        buffer.writeVarInt(rainPlacementAggregationDistance);
        buffer.writeVarInt(rainPlacementMaxCombinedAmount);
        buffer.writeVarInt(rainWetnessPersistTicks);
        buffer.writeVarInt(rainCatchmentRadius);
        buffer.writeFloat(rainCatchmentMaxBoost);
        buffer.writeVarInt(rainUpstreamSearchRadius);
        buffer.writeFloat(rainUpstreamMaxBoost);
        buffer.writeFloat(rainIntensityDrizzleMultiplier);
        buffer.writeFloat(rainIntensitySteadyMultiplier);
        buffer.writeFloat(rainIntensityHeavyMultiplier);
        buffer.writeFloat(rainIntensityThunderstormMultiplier);

        buffer.writeFloat(rainPrecipJungle);
        buffer.writeFloat(rainPrecipSwamp);
        buffer.writeFloat(rainPrecipDesert);
        buffer.writeFloat(rainPrecipSavanna);
        buffer.writeFloat(rainPrecipPlains);
        buffer.writeFloat(rainPrecipForest);
        buffer.writeFloat(rainPrecipTaiga);
        buffer.writeBoolean(enableSnowmeltSystem);
        buffer.writeBoolean(snowmeltDaytimeOnly);
        buffer.writeBoolean(snowmeltPlacesWater);
        buffer.writeVarInt(snowmeltChunkRadius);
        buffer.writeVarInt(snowmeltIntervalTicks);
        buffer.writeVarInt(snowmeltAttemptsPerChunk);
        buffer.writeVarInt(snowmeltMaxChunksPerTick);
        buffer.writeFloat(snowmeltBaseChance);
        buffer.writeVarInt(snowmeltWaterAmount);
        buffer.writeVarInt(snowmeltMinSkyLight);
        buffer.writeFloat(snowmeltMinTemperature);
        buffer.writeBoolean(enableFloodEvents);
        buffer.writeFloat(floodStartChancePerDay);
        buffer.writeBoolean(floodRequiresRain);
        buffer.writeFloat(floodThunderstormChanceMultiplier);
        buffer.writeBoolean(announceFloodEvents);
        buffer.writeVarInt(floodDefaultRadius);
        buffer.writeVarInt(floodDefaultDurationTicks);
        buffer.writeVarInt(floodPulseIntervalTicks);
        buffer.writeVarInt(floodPlacementsPerPulse);
        buffer.writeVarInt(floodWaterAmountPerPlacement);
        buffer.writeVarInt(floodShoreSearchRadius);
        buffer.writeVarInt(floodMaxWaterRise);
        buffer.writeFloat(floodLowlandBias);
        buffer.writeFloat(floodRainAmountMultiplier);
        buffer.writeBoolean(enableNetherLavaEvents);
        buffer.writeFloat(netherLavaEventStartChancePerDay);
        buffer.writeVarInt(netherLavaEventMinDurationTicks);
        buffer.writeVarInt(netherLavaEventMaxDurationTicks);
        buffer.writeVarInt(netherLavaEventDefaultRadius);
        buffer.writeVarInt(netherLavaEventPulseIntervalTicks);
        buffer.writeBoolean(enableSpringRandomBreakage);
        buffer.writeFloat(springRandomBreakChance);
        buffer.writeFloat(overworldSpringSpawnMultiplier);
        buffer.writeFloat(netherSpringSpawnMultiplier);
        buffer.writeFloat(deepLavaSpringSpawnMultiplier);
        buffer.writeFloat(deepLavaSpringExtraRejectChance);
        buffer.writeVarInt(deepLavaSpringMaxPlacementsPerFeature);
        buffer.writeBoolean(enableCappedSpringPressureHead);
        buffer.writeFloat(waterSpringEmissionMultiplier);
        buffer.writeFloat(lavaSpringEmissionMultiplier);
        buffer.writeFloat(waterSpringPulseIntervalMultiplier);
        buffer.writeFloat(lavaSpringPulseIntervalMultiplier);
        buffer.writeBoolean(enableAnalyticPoolDormancy);
        ///////////////////////////////////////////////
    }

//    public enum LevelingBehaviour {
//        VANILLA_LIKE,
//        LAZY_LEVEL,
//        STRONG_LEVEL,
//        FORCE_LEVEL
//    }

    public enum WaterLogFlowMode {
        ONLY_IN,
        ONLY_OUT,
        IN_FROM_TOP_ELSE_OUT,
        OUT_DOWN_ELSE_IN,
        IGNORE;

        public boolean blocksFlowOutDown(){
            return this == ONLY_IN || this == IGNORE;
        }

        public boolean blocksFlowIn(boolean down){
            if (down) return this == ONLY_OUT || this == IGNORE;
            return this == ONLY_OUT || this == IN_FROM_TOP_ELSE_OUT || this == IGNORE;
        }

        public boolean blocksFlowOutSides(){
            return this == ONLY_IN || this == OUT_DOWN_ELSE_IN || this == IGNORE;
        }
    }

    public enum WaterProcessingMode {
        MODERN,
        LEGACY,
        HYBRID
    }

    @SuppressWarnings("unused")
    public enum CreateWaterWheelMode {
        ALWAYS,
        REQUIRE_FLOW,
        REQUIRE_FLOW_OR_RIVER,
        REQUIRE_FLUID,
        REQUIRE_FULL_FLUID,
        RIVER_ONLY,
        REQUIRE_FLOW_OR_RIVER_OPPOSITE,
        REQUIRE_FLUID_OPPOSITE,
        REQUIRE_FULL_FLUID_OPPOSITE,
        ALWAYS_OPPOSITE,
        RIVER_ONLY_OPPOSITE;

        public boolean isCounterSpin() {
            return this.ordinal() > 5;
        }

        public boolean isRiver() {
            return this == REQUIRE_FLOW_OR_RIVER || this == REQUIRE_FLOW_OR_RIVER_OPPOSITE || this == RIVER_ONLY || this == RIVER_ONLY_OPPOSITE;
        }

        public boolean isRiverOnly() {
            return this == RIVER_ONLY || this == RIVER_ONLY_OPPOSITE;
        }

        public boolean needsFullFluid() {
            return this == REQUIRE_FULL_FLUID || this == REQUIRE_FULL_FLUID_OPPOSITE;
        }

        public boolean always(){
            return this == ALWAYS || this == ALWAYS_OPPOSITE;
        }

    }

    public void ensureCollections() {
        if (fluidBlacklist == null) fluidBlacklist = new ObjectOpenHashSet<>();
        if (excludedDimensions == null) excludedDimensions = new ObjectOpenHashSet<>();
        if (dimensionSeaLevelOverrides == null) dimensionSeaLevelOverrides = new Int2IntOpenHashMap();
        if (extraOceanBiomes == null) extraOceanBiomes = new ObjectOpenHashSet<>();
        if (extraRiverBiomes == null) extraRiverBiomes = new ObjectOpenHashSet<>();
        if (extraBeachBiomes == null) extraBeachBiomes = new ObjectOpenHashSet<>();
        if (extraInfiniteBiomeEntries == null) extraInfiniteBiomeEntries = new ObjectOpenHashSet<>();
        if (automaticInfiniteBiomeTagHints == null) {
            automaticInfiniteBiomeTagHints = createStringSet(
                    "#forge:is_water",
                    "#forge:is_swamp",
                    "#forge:is_wet",
                    "#forge:is_river",
                    "#forge:is_beach",
                    "#forge:is_ocean",
                    "#c:is_water",
                    "#c:is_swamp",
                    "#c:is_wet",
                    "#c:is_river",
                    "#c:is_beach",
                    "#c:is_ocean"
            );
        }
        if (automaticInfiniteBiomeKeywordHints == null) {
            automaticInfiniteBiomeKeywordHints = createStringSet(
                    "ocean",
                    "sea",
                    "gulf",
                    "bay",
                    "coast",
                    "shore",
                    "beach",
                    "river",
                    "stream",
                    "creek",
                    "delta",
                    "swamp",
                    "marsh",
                    "mangrove",
                    "bog",
                    "wetland",
                    "lagoon",
                    "estuary",
                    "reef"
            );
        }
    }

    public void sanitizeRanges() {
        StringBuilder corrections = new StringBuilder();
        int oldWaterFlowDistance = waterFlowDistance;
        int oldLavaFlowDistance = lavaFlowDistance;
        int oldLavaNetherFlowDistance = lavaNetherFlowDistance;
        float oldWaterTickDelay = waterTickDelay;
        float oldLavaTickDelay = lavaTickDelay;
        float oldLavaNetherTickDelay = lavaNetherTickDelay;
        int oldRandomTickLevelingDistance = randomTickLevelingDistance;
        int oldAutoTickDelayUpdateRateTicks = autoTickDelayUpdateRateTicks;
        float oldAutoTickDelayTargetMsptMultiplier = autoTickDelayTargetMsptMultiplier;
        int oldAutoTickDelayWaterMaxExtraDelay = autoTickDelayWaterMaxExtraDelay;
        int oldAutoTickDelayLavaMaxExtraDelay = autoTickDelayLavaMaxExtraDelay;
        int oldActiveWakeFlushBudgetPerTick = activeWakeFlushBudgetPerTick;
        int oldActiveWakeMaxDelayTicks = activeWakeMaxDelayTicks;
        int oldMaxWaterFlowDistance = maxWaterFlowDistance;
        int oldBfsMaxSearchDistance = bfsMaxSearchDistance;
        int oldPlayerBlockDistanceForFlowing = playerBlockDistanceForFlowing;
        float oldSlopeFindDistanceMultiplier = slopeFindDistanceMultiplier;
        int oldRiverFlowDistance = riverFlowDistance;
        int oldOceanFlowDistance = oceanFlowDistance;
        int oldCanalFlowDistance = canalFlowDistance;
        long oldAdaptiveSchedulerChunkExpiryMs = adaptiveSchedulerChunkExpiryMs;
        int oldAdaptiveSchedulerMaxEntries = adaptiveSchedulerMaxEntries;
        int oldPerformanceLogInterval = performanceLogInterval;
        int oldFluidComponentGraphMaxUpdatesPerTick = fluidComponentGraphMaxUpdatesPerTick;
        int oldFluidComponentGraphMaxScanNodes = fluidComponentGraphMaxScanNodes;
        int oldRouteSolverIterations = routeSolverIterations;
        int oldRouteSolverMaxTransferPerEdge = routeSolverMaxTransferPerEdge;
        float oldRouteSolverDownhillBias = routeSolverDownhillBias;
        int oldRainChunkRadius = rainChunkRadius;
        float oldFlowSpeedStrength = flowSpeedStrength;
        float oldPressureFlowBonusStrength = pressureFlowBonusStrength;
        float oldDownwardPressureStrength = downwardPressureStrength;
        int oldDownwardPressureMaxColumn = downwardPressureMaxColumn;
        float oldConnectedFlowDelayMultiplier = connectedFlowDelayMultiplier;
        float oldChannelBoostDelayMultiplier = channelBoostDelayMultiplier;
        float oldDownwardTickDelayMultiplier = downwardTickDelayMultiplier;
        float oldActiveFlowDistanceBudgetBoost = activeFlowDistanceBudgetBoost;
        int oldHydraulicSampleDistance = hydraulicSampleDistance;
        float oldHydraulicDepthWeight = hydraulicDepthWeight;
        float oldHydraulicUpstreamWeight = hydraulicUpstreamWeight;
        float oldHydraulicIntakeWeight = hydraulicIntakeWeight;
        float oldHydraulicChannelVelocityWeight = hydraulicChannelVelocityWeight;
        float oldHydraulicChannelCapacityWeight = hydraulicChannelCapacityWeight;
        float oldHydraulicTickAcceleration = hydraulicTickAcceleration;
        float oldCavityPressureStrength = cavityPressureStrength;
        float oldConnectedHeadStrength = connectedHeadStrength;
        float oldMudificationStrength = mudificationStrength;
        int oldHydraulicSiphonMaxSearchNodes = hydraulicSiphonMaxSearchNodes;
        int oldHydraulicSiphonMaxPathLength = hydraulicSiphonMaxPathLength;
        int oldHydraulicSiphonMaxLift = hydraulicSiphonMaxLift;
        int oldHydraulicSiphonSourceSurfaceScanNodes = hydraulicSiphonSourceSurfaceScanNodes;
        int oldHydraulicSiphonMaxPressureHead = hydraulicSiphonMaxPressureHead;
        int oldHydraulicSiphonMaxTransferPerTick = hydraulicSiphonMaxTransferPerTick;
        int oldNaturalSiphonMaxSearchNodes = naturalSiphonMaxSearchNodes;
        int oldNaturalSiphonMaxPathLength = naturalSiphonMaxPathLength;
        int oldNaturalSiphonMaxLift = naturalSiphonMaxLift;
        int oldNaturalSiphonMinFilledAmount = naturalSiphonMinFilledAmount;
        int oldNaturalSiphonMaxTransferPerTick = naturalSiphonMaxTransferPerTick;
        int oldNaturalSiphonCooldownTicks = naturalSiphonCooldownTicks;
        float oldEvaporationChanceMultiplier = evaporationChanceMultiplier;
        int oldEvaporationIntervalTicks = evaporationIntervalTicks;
        int oldEvaporationThinWaterMaxLevel = evaporationThinWaterMaxLevel;
        float oldEvaporationNetherChanceMultiplier = evaporationNetherChanceMultiplier;
        int oldEvaporationNetherIntervalTicks = evaporationNetherIntervalTicks;
        float oldSeaLevelOverflowEvaporationChance = seaLevelOverflowEvaporationChance;
        float oldSeaLevelOverflowEvaporationHeightScale = seaLevelOverflowEvaporationHeightScale;
        int oldSeaLevelOverflowEvaporationMinExcess = seaLevelOverflowEvaporationMinExcess;
        int oldSeaLevelOverflowEvaporationMaxExcess = seaLevelOverflowEvaporationMaxExcess;
        int oldSeaLevelOverflowInfiniteBiomeBufferRadius = seaLevelOverflowInfiniteBiomeBufferRadius;
        float oldHotBlockEvaporationChance = hotBlockEvaporationChance;
        float oldHotBlockEvaporationChanceMultiplier = hotBlockEvaporationChanceMultiplier;
        int oldHotBlockEvaporationIntervalTicks = hotBlockEvaporationIntervalTicks;
        int oldHotBlockEvaporationRadius = hotBlockEvaporationRadius;
        int oldHotBlockEvaporationVerticalRange = hotBlockEvaporationVerticalRange;
        int oldHotBlockEvaporationDrainAmount = hotBlockEvaporationDrainAmount;
        int oldShadeRoofSearchHeight = shadeRoofSearchHeight;
        float oldRiverDroughtRefillMultiplier = riverDroughtRefillMultiplier;
        float oldRiverDroughtDrainChance = riverDroughtDrainChance;
        int oldRiverDroughtMaxAffectedLevel = riverDroughtMaxAffectedLevel;
        float oldRiverDroughtHeatwaveDrainBonus = riverDroughtHeatwaveDrainBonus;
        int oldRainGenerateIntervalTicks = rainGenerateIntervalTicks;
        int oldRainAttemptsPerChunk = rainAttemptsPerChunk;
        float oldRainBaseGenerateChance = rainBaseGenerateChance;
        int oldRainBaseWaterAmount = rainBaseWaterAmount;
        int oldRainMaxChunksPerTick = rainMaxChunksPerTick;
        long oldRainCacheDurationTicks = rainCacheDurationTicks;
        int oldRainMaxSurfaceSearchDepth = rainMaxSurfaceSearchDepth;
        int oldRainMaxWaterStackHeight = rainMaxWaterStackHeight;
        int oldRainPlacementQueueSize = rainPlacementQueueSize;
        float oldRainQueueSoftCapRatio = rainQueueSoftCapRatio;
        float oldRainQueueMinChanceMultiplier = rainQueueMinChanceMultiplier;
        int oldRainPlacementAggregationDistance = rainPlacementAggregationDistance;
        int oldRainPlacementMaxCombinedAmount = rainPlacementMaxCombinedAmount;
        int oldRainWetnessPersistTicks = rainWetnessPersistTicks;
        int oldRainCatchmentRadius = rainCatchmentRadius;
        float oldRainCatchmentMaxBoost = rainCatchmentMaxBoost;
        int oldRainUpstreamSearchRadius = rainUpstreamSearchRadius;
        float oldRainUpstreamMaxBoost = rainUpstreamMaxBoost;
        float oldRainIntensityDrizzleMultiplier = rainIntensityDrizzleMultiplier;
        float oldRainIntensitySteadyMultiplier = rainIntensitySteadyMultiplier;
        float oldRainIntensityHeavyMultiplier = rainIntensityHeavyMultiplier;
        float oldRainIntensityThunderstormMultiplier = rainIntensityThunderstormMultiplier;
        int oldSnowmeltChunkRadius = snowmeltChunkRadius;
        int oldSnowmeltIntervalTicks = snowmeltIntervalTicks;
        int oldSnowmeltAttemptsPerChunk = snowmeltAttemptsPerChunk;
        int oldSnowmeltMaxChunksPerTick = snowmeltMaxChunksPerTick;
        float oldSnowmeltBaseChance = snowmeltBaseChance;
        int oldSnowmeltWaterAmount = snowmeltWaterAmount;
        int oldSnowmeltMinSkyLight = snowmeltMinSkyLight;
        float oldSnowmeltMinTemperature = snowmeltMinTemperature;
        float oldFloodStartChancePerDay = floodStartChancePerDay;
        float oldFloodThunderstormChanceMultiplier = floodThunderstormChanceMultiplier;
        int oldFloodDefaultRadius = floodDefaultRadius;
        int oldFloodDefaultDurationTicks = floodDefaultDurationTicks;
        int oldFloodPulseIntervalTicks = floodPulseIntervalTicks;
        int oldFloodPlacementsPerPulse = floodPlacementsPerPulse;
        int oldFloodWaterAmountPerPlacement = floodWaterAmountPerPlacement;
        int oldFloodShoreSearchRadius = floodShoreSearchRadius;
        int oldFloodMaxWaterRise = floodMaxWaterRise;
        float oldFloodLowlandBias = floodLowlandBias;
        float oldFloodRainAmountMultiplier = floodRainAmountMultiplier;
        float oldNetherLavaEventStartChancePerDay = netherLavaEventStartChancePerDay;
        int oldNetherLavaEventMinDurationTicks = netherLavaEventMinDurationTicks;
        int oldNetherLavaEventMaxDurationTicks = netherLavaEventMaxDurationTicks;
        int oldNetherLavaEventDefaultRadius = netherLavaEventDefaultRadius;
        int oldNetherLavaEventPulseIntervalTicks = netherLavaEventPulseIntervalTicks;
        float oldSpringRandomBreakChance = springRandomBreakChance;
        float oldOverworldSpringSpawnMultiplier = overworldSpringSpawnMultiplier;
        float oldNetherSpringSpawnMultiplier = netherSpringSpawnMultiplier;
        float oldDeepLavaSpringSpawnMultiplier = deepLavaSpringSpawnMultiplier;
        float oldDeepLavaSpringExtraRejectChance = deepLavaSpringExtraRejectChance;
        int oldDeepLavaSpringMaxPlacementsPerFeature = deepLavaSpringMaxPlacementsPerFeature;
        float oldWaterSpringEmissionMultiplier = waterSpringEmissionMultiplier;
        float oldLavaSpringEmissionMultiplier = lavaSpringEmissionMultiplier;
        float oldWaterSpringPulseIntervalMultiplier = waterSpringPulseIntervalMultiplier;
        float oldLavaSpringPulseIntervalMultiplier = lavaSpringPulseIntervalMultiplier;

        waterFlowDistance = Math.max(1, waterFlowDistance);
        lavaFlowDistance = Math.max(1, lavaFlowDistance);
        lavaNetherFlowDistance = Math.max(1, lavaNetherFlowDistance);
        waterTickDelay = traben.flowing_fluids.performance.FluidFineTickDelay.sanitizeBaseDelay(waterTickDelay);
        lavaTickDelay = traben.flowing_fluids.performance.FluidFineTickDelay.sanitizeBaseDelay(lavaTickDelay);
        lavaNetherTickDelay = traben.flowing_fluids.performance.FluidFineTickDelay.sanitizeBaseDelay(lavaNetherTickDelay);
        randomTickLevelingDistance = Math.max(0, randomTickLevelingDistance);

        maxWaterFlowDistance = Math.max(waterFlowDistance, maxWaterFlowDistance);
        bfsMaxSearchDistance = Math.max(waterFlowDistance, Math.max(1, bfsMaxSearchDistance));
        playerBlockDistanceForFlowing = Math.max(0, Math.min(256, playerBlockDistanceForFlowing));
        slopeFindDistanceMultiplier = Math.max(0.0f, slopeFindDistanceMultiplier);
        riverFlowDistance = Math.max(1, riverFlowDistance);
        oceanFlowDistance = Math.max(1, oceanFlowDistance);
        canalFlowDistance = Math.max(1, canalFlowDistance);

        adaptiveSchedulerChunkExpiryMs = Math.max(1L, adaptiveSchedulerChunkExpiryMs);
        adaptiveSchedulerMaxEntries = Math.max(1, adaptiveSchedulerMaxEntries);

        performanceLogInterval = Math.max(20, Math.min(1200, performanceLogInterval));
        autoTickDelayUpdateRateTicks = Math.max(20, Math.min(1200, autoTickDelayUpdateRateTicks));
        autoTickDelayTargetMsptMultiplier = Math.max(0.25f, Math.min(2.0f, autoTickDelayTargetMsptMultiplier));
        autoTickDelayWaterMaxExtraDelay = Math.max(0, Math.min(64, autoTickDelayWaterMaxExtraDelay));
        autoTickDelayLavaMaxExtraDelay = Math.max(0, Math.min(64, autoTickDelayLavaMaxExtraDelay));
        activeWakeFlushBudgetPerTick = Math.max(0, Math.min(1_000_000, activeWakeFlushBudgetPerTick));
        activeWakeMaxDelayTicks = Math.max(1, Math.min(40, activeWakeMaxDelayTicks));
        fluidComponentGraphMaxUpdatesPerTick = Math.max(1, Math.min(4096, fluidComponentGraphMaxUpdatesPerTick));
        fluidComponentGraphMaxScanNodes = Math.max(16, Math.min(8192, fluidComponentGraphMaxScanNodes));
        routeSolverIterations = Math.max(1, Math.min(16, routeSolverIterations));
        routeSolverMaxTransferPerEdge = Math.max(1, Math.min(FluidAmountConverter.getMaxInternal(), routeSolverMaxTransferPerEdge));
        routeSolverDownhillBias = Math.max(1.0f, Math.min(8.0f, routeSolverDownhillBias));
        rainChunkRadius = Math.max(0, rainChunkRadius);
        flowSpeedStrength = Math.max(0.0f, Math.min(2.0f, flowSpeedStrength));
        pressureFlowBonusStrength = Math.max(0.0f, Math.min(2.0f, pressureFlowBonusStrength));
        downwardPressureStrength = Math.max(0.0f, Math.min(2.0f, downwardPressureStrength));
        downwardPressureMaxColumn = Math.max(1, Math.min(12, downwardPressureMaxColumn));
        connectedFlowDelayMultiplier = Math.max(0.1f, Math.min(2.0f, connectedFlowDelayMultiplier));
        channelBoostDelayMultiplier = Math.max(0.1f, Math.min(2.0f, channelBoostDelayMultiplier));
        downwardTickDelayMultiplier = Math.max(0.1f, Math.min(2.0f, downwardTickDelayMultiplier));
        activeFlowDistanceBudgetBoost = Math.max(0.0f, Math.min(4.0f, activeFlowDistanceBudgetBoost));
        hydraulicSampleDistance = Math.max(1, Math.min(12, hydraulicSampleDistance));
        hydraulicDepthWeight = Math.max(0.0f, Math.min(4.0f, hydraulicDepthWeight));
        hydraulicUpstreamWeight = Math.max(0.0f, Math.min(4.0f, hydraulicUpstreamWeight));
        hydraulicIntakeWeight = Math.max(0.0f, Math.min(4.0f, hydraulicIntakeWeight));
        hydraulicChannelVelocityWeight = Math.max(0.0f, Math.min(4.0f, hydraulicChannelVelocityWeight));
        hydraulicChannelCapacityWeight = Math.max(0.0f, Math.min(4.0f, hydraulicChannelCapacityWeight));
        hydraulicTickAcceleration = Math.max(0.0f, Math.min(2.0f, hydraulicTickAcceleration));
        cavityPressureStrength = Math.max(0.0f, Math.min(2.0f, cavityPressureStrength));
        connectedHeadStrength = Math.max(0.0f, Math.min(2.0f, connectedHeadStrength));
        mudificationStrength = Math.max(0.0f, Math.min(4.0f, mudificationStrength));
        hydraulicSiphonMaxSearchNodes = Math.max(64, Math.min(512, hydraulicSiphonMaxSearchNodes));
        hydraulicSiphonMaxPathLength = Math.max(16, Math.min(128, hydraulicSiphonMaxPathLength));
        hydraulicSiphonMaxLift = Math.max(0, Math.min(32, hydraulicSiphonMaxLift));
        hydraulicSiphonSourceSurfaceScanNodes = Math.max(16, Math.min(512, hydraulicSiphonSourceSurfaceScanNodes));
        hydraulicSiphonMaxPressureHead = Math.max(0, Math.min(32, hydraulicSiphonMaxPressureHead));
        hydraulicSiphonMaxTransferPerTick = Math.max(1, Math.min(16, hydraulicSiphonMaxTransferPerTick));
        naturalSiphonMaxSearchNodes = Math.max(64, Math.min(512, naturalSiphonMaxSearchNodes));
        naturalSiphonMaxPathLength = Math.max(16, Math.min(128, naturalSiphonMaxPathLength));
        naturalSiphonMaxLift = Math.max(0, Math.min(8, naturalSiphonMaxLift));
        naturalSiphonMinFilledAmount = Math.max(1, Math.min(8, naturalSiphonMinFilledAmount));
        naturalSiphonMaxTransferPerTick = Math.max(1, Math.min(8, naturalSiphonMaxTransferPerTick));
        naturalSiphonCooldownTicks = Math.max(1, Math.min(80, naturalSiphonCooldownTicks));
        evaporationChanceMultiplier = Math.max(0.0f, Math.min(8.0f, evaporationChanceMultiplier));
        evaporationIntervalTicks = Math.max(1, Math.min(1200, evaporationIntervalTicks));
        evaporationThinWaterMaxLevel = Math.max(1, Math.min(8, evaporationThinWaterMaxLevel));
        evaporationNetherChanceMultiplier = Math.max(0.0f, Math.min(8.0f, evaporationNetherChanceMultiplier));
        evaporationNetherIntervalTicks = Math.max(1, Math.min(1200, evaporationNetherIntervalTicks));
        seaLevelOverflowEvaporationChance = Math.max(0.0f, Math.min(1.0f, seaLevelOverflowEvaporationChance));
        seaLevelOverflowEvaporationHeightScale = Math.max(0.0f, Math.min(4.0f, seaLevelOverflowEvaporationHeightScale));
        seaLevelOverflowEvaporationMinExcess = Math.max(1, Math.min(16, seaLevelOverflowEvaporationMinExcess));
        seaLevelOverflowEvaporationMaxExcess = Math.max(seaLevelOverflowEvaporationMinExcess, Math.min(64, seaLevelOverflowEvaporationMaxExcess));
        seaLevelOverflowInfiniteBiomeBufferRadius = Math.max(0, Math.min(64, seaLevelOverflowInfiniteBiomeBufferRadius));
        hotBlockEvaporationChance = Math.max(0.0f, Math.min(1.0f, hotBlockEvaporationChance));
        hotBlockEvaporationChanceMultiplier = Math.max(0.0f, Math.min(8.0f, hotBlockEvaporationChanceMultiplier));
        hotBlockEvaporationIntervalTicks = Math.max(1, Math.min(1200, hotBlockEvaporationIntervalTicks));
        hotBlockEvaporationRadius = Math.max(0, hotBlockEvaporationRadius);
        hotBlockEvaporationVerticalRange = Math.max(0, hotBlockEvaporationVerticalRange);
        hotBlockEvaporationDrainAmount = Math.max(1, Math.min(8, hotBlockEvaporationDrainAmount));
        shadeRoofSearchHeight = Math.max(0, shadeRoofSearchHeight);
        riverDroughtRefillMultiplier = Math.max(0.0f, riverDroughtRefillMultiplier);
        riverDroughtDrainChance = Math.max(0.0f, Math.min(1.0f, riverDroughtDrainChance));
        riverDroughtMaxAffectedLevel = Math.max(0, Math.min(8, riverDroughtMaxAffectedLevel));
        riverDroughtHeatwaveDrainBonus = Math.max(0.0f, riverDroughtHeatwaveDrainBonus);
        rainGenerateIntervalTicks = Math.max(1, rainGenerateIntervalTicks);
        rainAttemptsPerChunk = Math.max(0, rainAttemptsPerChunk);
        rainBaseGenerateChance = Math.max(0.0f, Math.min(1.0f, rainBaseGenerateChance));
        rainBaseWaterAmount = Math.max(1, rainBaseWaterAmount);
        rainMaxChunksPerTick = Math.max(0, rainMaxChunksPerTick);
        rainCacheDurationTicks = Math.max(1L, rainCacheDurationTicks);
        rainMaxSurfaceSearchDepth = Math.max(0, rainMaxSurfaceSearchDepth);
        rainMaxWaterStackHeight = Math.max(0, rainMaxWaterStackHeight);
        rainPlacementQueueSize = Math.max(1, rainPlacementQueueSize);
        rainQueueSoftCapRatio = Math.max(0.0f, Math.min(1.0f, rainQueueSoftCapRatio));
        rainQueueMinChanceMultiplier = Math.max(0.0f, Math.min(1.0f, rainQueueMinChanceMultiplier));
        rainPlacementAggregationDistance = Math.max(0, rainPlacementAggregationDistance);
        rainPlacementMaxCombinedAmount = Math.max(1, rainPlacementMaxCombinedAmount);
        rainWetnessPersistTicks = Math.max(1, rainWetnessPersistTicks);
        rainCatchmentRadius = Math.max(0, rainCatchmentRadius);
        rainCatchmentMaxBoost = Math.max(1.0f, rainCatchmentMaxBoost);
        rainUpstreamSearchRadius = Math.max(0, rainUpstreamSearchRadius);
        rainUpstreamMaxBoost = Math.max(1.0f, rainUpstreamMaxBoost);
        rainIntensityDrizzleMultiplier = Math.max(0.1f, rainIntensityDrizzleMultiplier);
        rainIntensitySteadyMultiplier = Math.max(0.1f, rainIntensitySteadyMultiplier);
        rainIntensityHeavyMultiplier = Math.max(0.1f, rainIntensityHeavyMultiplier);
        rainIntensityThunderstormMultiplier = Math.max(0.1f, rainIntensityThunderstormMultiplier);
        snowmeltChunkRadius = Math.max(0, snowmeltChunkRadius);
        snowmeltIntervalTicks = Math.max(1, snowmeltIntervalTicks);
        snowmeltAttemptsPerChunk = Math.max(0, snowmeltAttemptsPerChunk);
        snowmeltMaxChunksPerTick = Math.max(0, snowmeltMaxChunksPerTick);
        snowmeltBaseChance = Math.max(0.0f, Math.min(1.0f, snowmeltBaseChance));
        snowmeltWaterAmount = Math.max(1, Math.min(8, snowmeltWaterAmount));
        snowmeltMinSkyLight = Math.max(0, Math.min(15, snowmeltMinSkyLight));
        snowmeltMinTemperature = Math.max(-1.0f, Math.min(4.0f, snowmeltMinTemperature));
        floodStartChancePerDay = Math.max(0.0f, Math.min(1.0f, floodStartChancePerDay));
        floodThunderstormChanceMultiplier = Math.max(0.1f, Math.min(8.0f, floodThunderstormChanceMultiplier));
        floodDefaultRadius = Math.max(12, Math.min(192, floodDefaultRadius));
        floodDefaultDurationTicks = Math.max(40, Math.min(20 * 20 * 60, floodDefaultDurationTicks));
        floodPulseIntervalTicks = Math.max(2, Math.min(80, floodPulseIntervalTicks));
        floodPlacementsPerPulse = Math.max(1, Math.min(256, floodPlacementsPerPulse));
        floodWaterAmountPerPlacement = Math.max(1, Math.min(8, floodWaterAmountPerPlacement));
        floodShoreSearchRadius = Math.max(1, Math.min(16, floodShoreSearchRadius));
        floodMaxWaterRise = Math.max(1, Math.min(16, floodMaxWaterRise));
        floodLowlandBias = Math.max(0.0f, Math.min(4.0f, floodLowlandBias));
        floodRainAmountMultiplier = Math.max(1.0f, Math.min(8.0f, floodRainAmountMultiplier));
        netherLavaEventStartChancePerDay = Math.max(0.0f, Math.min(1.0f, netherLavaEventStartChancePerDay));
        netherLavaEventMinDurationTicks = Math.max(40, netherLavaEventMinDurationTicks);
        netherLavaEventMaxDurationTicks = Math.max(netherLavaEventMinDurationTicks, netherLavaEventMaxDurationTicks);
        netherLavaEventDefaultRadius = Math.max(12, Math.min(192, netherLavaEventDefaultRadius));
        netherLavaEventPulseIntervalTicks = Math.max(2, Math.min(40, netherLavaEventPulseIntervalTicks));
        springRandomBreakChance = Math.max(0.0f, Math.min(0.25f, springRandomBreakChance));
        overworldSpringSpawnMultiplier = Math.max(0.0f, Math.min(8.0f, overworldSpringSpawnMultiplier));
        netherSpringSpawnMultiplier = Math.max(0.0f, Math.min(8.0f, netherSpringSpawnMultiplier));
        deepLavaSpringSpawnMultiplier = Math.max(0.05f, Math.min(3.0f, deepLavaSpringSpawnMultiplier));
        deepLavaSpringExtraRejectChance = Math.max(0.0f, Math.min(0.95f, deepLavaSpringExtraRejectChance));
        deepLavaSpringMaxPlacementsPerFeature = Math.max(1, Math.min(3, deepLavaSpringMaxPlacementsPerFeature));
        waterSpringEmissionMultiplier = Math.max(0.25f, Math.min(4.0f, waterSpringEmissionMultiplier));
        lavaSpringEmissionMultiplier = Math.max(0.25f, Math.min(4.0f, lavaSpringEmissionMultiplier));
        waterSpringPulseIntervalMultiplier = Math.max(0.25f, Math.min(4.0f, waterSpringPulseIntervalMultiplier));
        lavaSpringPulseIntervalMultiplier = Math.max(0.25f, Math.min(4.0f, lavaSpringPulseIntervalMultiplier));

        appendCorrection(corrections, "waterFlowDistance", oldWaterFlowDistance, waterFlowDistance);
        appendCorrection(corrections, "lavaFlowDistance", oldLavaFlowDistance, lavaFlowDistance);
        appendCorrection(corrections, "lavaNetherFlowDistance", oldLavaNetherFlowDistance, lavaNetherFlowDistance);
        appendCorrection(corrections, "waterTickDelay", oldWaterTickDelay, waterTickDelay);
        appendCorrection(corrections, "lavaTickDelay", oldLavaTickDelay, lavaTickDelay);
        appendCorrection(corrections, "lavaNetherTickDelay", oldLavaNetherTickDelay, lavaNetherTickDelay);
        appendCorrection(corrections, "randomTickLevelingDistance", oldRandomTickLevelingDistance, randomTickLevelingDistance);
        appendCorrection(corrections, "maxWaterFlowDistance", oldMaxWaterFlowDistance, maxWaterFlowDistance);
        appendCorrection(corrections, "bfsMaxSearchDistance", oldBfsMaxSearchDistance, bfsMaxSearchDistance);
        appendCorrection(corrections, "playerBlockDistanceForFlowing", oldPlayerBlockDistanceForFlowing, playerBlockDistanceForFlowing);
        appendCorrection(corrections, "slopeFindDistanceMultiplier", oldSlopeFindDistanceMultiplier, slopeFindDistanceMultiplier);
        appendCorrection(corrections, "riverFlowDistance", oldRiverFlowDistance, riverFlowDistance);
        appendCorrection(corrections, "oceanFlowDistance", oldOceanFlowDistance, oceanFlowDistance);
        appendCorrection(corrections, "canalFlowDistance", oldCanalFlowDistance, canalFlowDistance);
        appendCorrection(corrections, "adaptiveSchedulerChunkExpiryMs", oldAdaptiveSchedulerChunkExpiryMs, adaptiveSchedulerChunkExpiryMs);
        appendCorrection(corrections, "adaptiveSchedulerMaxEntries", oldAdaptiveSchedulerMaxEntries, adaptiveSchedulerMaxEntries);
        appendCorrection(corrections, "performanceLogInterval", oldPerformanceLogInterval, performanceLogInterval);
        appendCorrection(corrections, "fluidComponentGraphMaxUpdatesPerTick", oldFluidComponentGraphMaxUpdatesPerTick, fluidComponentGraphMaxUpdatesPerTick);
        appendCorrection(corrections, "fluidComponentGraphMaxScanNodes", oldFluidComponentGraphMaxScanNodes, fluidComponentGraphMaxScanNodes);
        appendCorrection(corrections, "routeSolverIterations", oldRouteSolverIterations, routeSolverIterations);
        appendCorrection(corrections, "routeSolverMaxTransferPerEdge", oldRouteSolverMaxTransferPerEdge, routeSolverMaxTransferPerEdge);
        appendCorrection(corrections, "routeSolverDownhillBias", oldRouteSolverDownhillBias, routeSolverDownhillBias);
        appendCorrection(corrections, "autoTickDelayUpdateRateTicks", oldAutoTickDelayUpdateRateTicks, autoTickDelayUpdateRateTicks);
        appendCorrection(corrections, "autoTickDelayTargetMsptMultiplier", oldAutoTickDelayTargetMsptMultiplier, autoTickDelayTargetMsptMultiplier);
        appendCorrection(corrections, "autoTickDelayWaterMaxExtraDelay", oldAutoTickDelayWaterMaxExtraDelay, autoTickDelayWaterMaxExtraDelay);
        appendCorrection(corrections, "autoTickDelayLavaMaxExtraDelay", oldAutoTickDelayLavaMaxExtraDelay, autoTickDelayLavaMaxExtraDelay);
        appendCorrection(corrections, "activeWakeFlushBudgetPerTick", oldActiveWakeFlushBudgetPerTick, activeWakeFlushBudgetPerTick);
        appendCorrection(corrections, "activeWakeMaxDelayTicks", oldActiveWakeMaxDelayTicks, activeWakeMaxDelayTicks);
        appendCorrection(corrections, "flowSpeedStrength", oldFlowSpeedStrength, flowSpeedStrength);
        appendCorrection(corrections, "rainChunkRadius", oldRainChunkRadius, rainChunkRadius);
        appendCorrection(corrections, "pressureFlowBonusStrength", oldPressureFlowBonusStrength, pressureFlowBonusStrength);
        appendCorrection(corrections, "downwardPressureStrength", oldDownwardPressureStrength, downwardPressureStrength);
        appendCorrection(corrections, "downwardPressureMaxColumn", oldDownwardPressureMaxColumn, downwardPressureMaxColumn);
        appendCorrection(corrections, "connectedFlowDelayMultiplier", oldConnectedFlowDelayMultiplier, connectedFlowDelayMultiplier);
        appendCorrection(corrections, "channelBoostDelayMultiplier", oldChannelBoostDelayMultiplier, channelBoostDelayMultiplier);
        appendCorrection(corrections, "downwardTickDelayMultiplier", oldDownwardTickDelayMultiplier, downwardTickDelayMultiplier);
        appendCorrection(corrections, "activeFlowDistanceBudgetBoost", oldActiveFlowDistanceBudgetBoost, activeFlowDistanceBudgetBoost);
        appendCorrection(corrections, "hydraulicSampleDistance", oldHydraulicSampleDistance, hydraulicSampleDistance);
        appendCorrection(corrections, "hydraulicDepthWeight", oldHydraulicDepthWeight, hydraulicDepthWeight);
        appendCorrection(corrections, "hydraulicUpstreamWeight", oldHydraulicUpstreamWeight, hydraulicUpstreamWeight);
        appendCorrection(corrections, "hydraulicIntakeWeight", oldHydraulicIntakeWeight, hydraulicIntakeWeight);
        appendCorrection(corrections, "hydraulicChannelVelocityWeight", oldHydraulicChannelVelocityWeight, hydraulicChannelVelocityWeight);
        appendCorrection(corrections, "hydraulicChannelCapacityWeight", oldHydraulicChannelCapacityWeight, hydraulicChannelCapacityWeight);
        appendCorrection(corrections, "hydraulicTickAcceleration", oldHydraulicTickAcceleration, hydraulicTickAcceleration);
        appendCorrection(corrections, "cavityPressureStrength", oldCavityPressureStrength, cavityPressureStrength);
        appendCorrection(corrections, "connectedHeadStrength", oldConnectedHeadStrength, connectedHeadStrength);
        appendCorrection(corrections, "mudificationStrength", oldMudificationStrength, mudificationStrength);
        appendCorrection(corrections, "hydraulicSiphonMaxSearchNodes", oldHydraulicSiphonMaxSearchNodes, hydraulicSiphonMaxSearchNodes);
        appendCorrection(corrections, "hydraulicSiphonMaxPathLength", oldHydraulicSiphonMaxPathLength, hydraulicSiphonMaxPathLength);
        appendCorrection(corrections, "hydraulicSiphonMaxLift", oldHydraulicSiphonMaxLift, hydraulicSiphonMaxLift);
        appendCorrection(corrections, "hydraulicSiphonSourceSurfaceScanNodes", oldHydraulicSiphonSourceSurfaceScanNodes, hydraulicSiphonSourceSurfaceScanNodes);
        appendCorrection(corrections, "hydraulicSiphonMaxPressureHead", oldHydraulicSiphonMaxPressureHead, hydraulicSiphonMaxPressureHead);
        appendCorrection(corrections, "hydraulicSiphonMaxTransferPerTick", oldHydraulicSiphonMaxTransferPerTick, hydraulicSiphonMaxTransferPerTick);
        appendCorrection(corrections, "naturalSiphonMaxSearchNodes", oldNaturalSiphonMaxSearchNodes, naturalSiphonMaxSearchNodes);
        appendCorrection(corrections, "naturalSiphonMaxPathLength", oldNaturalSiphonMaxPathLength, naturalSiphonMaxPathLength);
        appendCorrection(corrections, "naturalSiphonMaxLift", oldNaturalSiphonMaxLift, naturalSiphonMaxLift);
        appendCorrection(corrections, "naturalSiphonMinFilledAmount", oldNaturalSiphonMinFilledAmount, naturalSiphonMinFilledAmount);
        appendCorrection(corrections, "naturalSiphonMaxTransferPerTick", oldNaturalSiphonMaxTransferPerTick, naturalSiphonMaxTransferPerTick);
        appendCorrection(corrections, "naturalSiphonCooldownTicks", oldNaturalSiphonCooldownTicks, naturalSiphonCooldownTicks);
        appendCorrection(corrections, "evaporationChanceMultiplier", oldEvaporationChanceMultiplier, evaporationChanceMultiplier);
        appendCorrection(corrections, "evaporationIntervalTicks", oldEvaporationIntervalTicks, evaporationIntervalTicks);
        appendCorrection(corrections, "evaporationThinWaterMaxLevel", oldEvaporationThinWaterMaxLevel, evaporationThinWaterMaxLevel);
        appendCorrection(corrections, "evaporationNetherChanceMultiplier", oldEvaporationNetherChanceMultiplier, evaporationNetherChanceMultiplier);
        appendCorrection(corrections, "evaporationNetherIntervalTicks", oldEvaporationNetherIntervalTicks, evaporationNetherIntervalTicks);
        appendCorrection(corrections, "seaLevelOverflowEvaporationChance", oldSeaLevelOverflowEvaporationChance, seaLevelOverflowEvaporationChance);
        appendCorrection(corrections, "seaLevelOverflowEvaporationHeightScale", oldSeaLevelOverflowEvaporationHeightScale, seaLevelOverflowEvaporationHeightScale);
        appendCorrection(corrections, "seaLevelOverflowEvaporationMinExcess", oldSeaLevelOverflowEvaporationMinExcess, seaLevelOverflowEvaporationMinExcess);
        appendCorrection(corrections, "seaLevelOverflowEvaporationMaxExcess", oldSeaLevelOverflowEvaporationMaxExcess, seaLevelOverflowEvaporationMaxExcess);
        appendCorrection(corrections, "seaLevelOverflowInfiniteBiomeBufferRadius", oldSeaLevelOverflowInfiniteBiomeBufferRadius, seaLevelOverflowInfiniteBiomeBufferRadius);
        appendCorrection(corrections, "hotBlockEvaporationChance", oldHotBlockEvaporationChance, hotBlockEvaporationChance);
        appendCorrection(corrections, "hotBlockEvaporationChanceMultiplier", oldHotBlockEvaporationChanceMultiplier, hotBlockEvaporationChanceMultiplier);
        appendCorrection(corrections, "hotBlockEvaporationIntervalTicks", oldHotBlockEvaporationIntervalTicks, hotBlockEvaporationIntervalTicks);
        appendCorrection(corrections, "hotBlockEvaporationRadius", oldHotBlockEvaporationRadius, hotBlockEvaporationRadius);
        appendCorrection(corrections, "hotBlockEvaporationVerticalRange", oldHotBlockEvaporationVerticalRange, hotBlockEvaporationVerticalRange);
        appendCorrection(corrections, "hotBlockEvaporationDrainAmount", oldHotBlockEvaporationDrainAmount, hotBlockEvaporationDrainAmount);
        appendCorrection(corrections, "shadeRoofSearchHeight", oldShadeRoofSearchHeight, shadeRoofSearchHeight);
        appendCorrection(corrections, "riverDroughtRefillMultiplier", oldRiverDroughtRefillMultiplier, riverDroughtRefillMultiplier);
        appendCorrection(corrections, "riverDroughtDrainChance", oldRiverDroughtDrainChance, riverDroughtDrainChance);
        appendCorrection(corrections, "riverDroughtMaxAffectedLevel", oldRiverDroughtMaxAffectedLevel, riverDroughtMaxAffectedLevel);
        appendCorrection(corrections, "riverDroughtHeatwaveDrainBonus", oldRiverDroughtHeatwaveDrainBonus, riverDroughtHeatwaveDrainBonus);
        appendCorrection(corrections, "rainGenerateIntervalTicks", oldRainGenerateIntervalTicks, rainGenerateIntervalTicks);
        appendCorrection(corrections, "rainAttemptsPerChunk", oldRainAttemptsPerChunk, rainAttemptsPerChunk);
        appendCorrection(corrections, "rainBaseGenerateChance", oldRainBaseGenerateChance, rainBaseGenerateChance);
        appendCorrection(corrections, "rainBaseWaterAmount", oldRainBaseWaterAmount, rainBaseWaterAmount);
        appendCorrection(corrections, "rainMaxChunksPerTick", oldRainMaxChunksPerTick, rainMaxChunksPerTick);
        appendCorrection(corrections, "rainCacheDurationTicks", oldRainCacheDurationTicks, rainCacheDurationTicks);
        appendCorrection(corrections, "rainMaxSurfaceSearchDepth", oldRainMaxSurfaceSearchDepth, rainMaxSurfaceSearchDepth);
        appendCorrection(corrections, "rainMaxWaterStackHeight", oldRainMaxWaterStackHeight, rainMaxWaterStackHeight);
        appendCorrection(corrections, "rainPlacementQueueSize", oldRainPlacementQueueSize, rainPlacementQueueSize);
        appendCorrection(corrections, "rainQueueSoftCapRatio", oldRainQueueSoftCapRatio, rainQueueSoftCapRatio);
        appendCorrection(corrections, "rainQueueMinChanceMultiplier", oldRainQueueMinChanceMultiplier, rainQueueMinChanceMultiplier);
        appendCorrection(corrections, "rainPlacementAggregationDistance", oldRainPlacementAggregationDistance, rainPlacementAggregationDistance);
        appendCorrection(corrections, "rainPlacementMaxCombinedAmount", oldRainPlacementMaxCombinedAmount, rainPlacementMaxCombinedAmount);
        appendCorrection(corrections, "rainWetnessPersistTicks", oldRainWetnessPersistTicks, rainWetnessPersistTicks);
        appendCorrection(corrections, "rainCatchmentRadius", oldRainCatchmentRadius, rainCatchmentRadius);
        appendCorrection(corrections, "rainCatchmentMaxBoost", oldRainCatchmentMaxBoost, rainCatchmentMaxBoost);
        appendCorrection(corrections, "rainUpstreamSearchRadius", oldRainUpstreamSearchRadius, rainUpstreamSearchRadius);
        appendCorrection(corrections, "rainUpstreamMaxBoost", oldRainUpstreamMaxBoost, rainUpstreamMaxBoost);
        appendCorrection(corrections, "rainIntensityDrizzleMultiplier", oldRainIntensityDrizzleMultiplier, rainIntensityDrizzleMultiplier);
        appendCorrection(corrections, "rainIntensitySteadyMultiplier", oldRainIntensitySteadyMultiplier, rainIntensitySteadyMultiplier);
        appendCorrection(corrections, "rainIntensityHeavyMultiplier", oldRainIntensityHeavyMultiplier, rainIntensityHeavyMultiplier);
        appendCorrection(corrections, "rainIntensityThunderstormMultiplier", oldRainIntensityThunderstormMultiplier, rainIntensityThunderstormMultiplier);
        appendCorrection(corrections, "snowmeltChunkRadius", oldSnowmeltChunkRadius, snowmeltChunkRadius);
        appendCorrection(corrections, "snowmeltIntervalTicks", oldSnowmeltIntervalTicks, snowmeltIntervalTicks);
        appendCorrection(corrections, "snowmeltAttemptsPerChunk", oldSnowmeltAttemptsPerChunk, snowmeltAttemptsPerChunk);
        appendCorrection(corrections, "snowmeltMaxChunksPerTick", oldSnowmeltMaxChunksPerTick, snowmeltMaxChunksPerTick);
        appendCorrection(corrections, "snowmeltBaseChance", oldSnowmeltBaseChance, snowmeltBaseChance);
        appendCorrection(corrections, "snowmeltWaterAmount", oldSnowmeltWaterAmount, snowmeltWaterAmount);
        appendCorrection(corrections, "snowmeltMinSkyLight", oldSnowmeltMinSkyLight, snowmeltMinSkyLight);
        appendCorrection(corrections, "snowmeltMinTemperature", oldSnowmeltMinTemperature, snowmeltMinTemperature);
        appendCorrection(corrections, "floodStartChancePerDay", oldFloodStartChancePerDay, floodStartChancePerDay);
        appendCorrection(corrections, "floodThunderstormChanceMultiplier", oldFloodThunderstormChanceMultiplier, floodThunderstormChanceMultiplier);
        appendCorrection(corrections, "floodDefaultRadius", oldFloodDefaultRadius, floodDefaultRadius);
        appendCorrection(corrections, "floodDefaultDurationTicks", oldFloodDefaultDurationTicks, floodDefaultDurationTicks);
        appendCorrection(corrections, "floodPulseIntervalTicks", oldFloodPulseIntervalTicks, floodPulseIntervalTicks);
        appendCorrection(corrections, "floodPlacementsPerPulse", oldFloodPlacementsPerPulse, floodPlacementsPerPulse);
        appendCorrection(corrections, "floodWaterAmountPerPlacement", oldFloodWaterAmountPerPlacement, floodWaterAmountPerPlacement);
        appendCorrection(corrections, "floodShoreSearchRadius", oldFloodShoreSearchRadius, floodShoreSearchRadius);
        appendCorrection(corrections, "floodMaxWaterRise", oldFloodMaxWaterRise, floodMaxWaterRise);
        appendCorrection(corrections, "floodLowlandBias", oldFloodLowlandBias, floodLowlandBias);
        appendCorrection(corrections, "floodRainAmountMultiplier", oldFloodRainAmountMultiplier, floodRainAmountMultiplier);
        appendCorrection(corrections, "netherLavaEventStartChancePerDay", oldNetherLavaEventStartChancePerDay, netherLavaEventStartChancePerDay);
        appendCorrection(corrections, "netherLavaEventMinDurationTicks", oldNetherLavaEventMinDurationTicks, netherLavaEventMinDurationTicks);
        appendCorrection(corrections, "netherLavaEventMaxDurationTicks", oldNetherLavaEventMaxDurationTicks, netherLavaEventMaxDurationTicks);
        appendCorrection(corrections, "netherLavaEventDefaultRadius", oldNetherLavaEventDefaultRadius, netherLavaEventDefaultRadius);
        appendCorrection(corrections, "netherLavaEventPulseIntervalTicks", oldNetherLavaEventPulseIntervalTicks, netherLavaEventPulseIntervalTicks);
        appendCorrection(corrections, "springRandomBreakChance", oldSpringRandomBreakChance, springRandomBreakChance);
        appendCorrection(corrections, "overworldSpringSpawnMultiplier", oldOverworldSpringSpawnMultiplier, overworldSpringSpawnMultiplier);
        appendCorrection(corrections, "netherSpringSpawnMultiplier", oldNetherSpringSpawnMultiplier, netherSpringSpawnMultiplier);
        appendCorrection(corrections, "deepLavaSpringSpawnMultiplier", oldDeepLavaSpringSpawnMultiplier, deepLavaSpringSpawnMultiplier);
        appendCorrection(corrections, "deepLavaSpringExtraRejectChance", oldDeepLavaSpringExtraRejectChance, deepLavaSpringExtraRejectChance);
        appendCorrection(corrections, "deepLavaSpringMaxPlacementsPerFeature", oldDeepLavaSpringMaxPlacementsPerFeature, deepLavaSpringMaxPlacementsPerFeature);
        appendCorrection(corrections, "waterSpringEmissionMultiplier", oldWaterSpringEmissionMultiplier, waterSpringEmissionMultiplier);
        appendCorrection(corrections, "lavaSpringEmissionMultiplier", oldLavaSpringEmissionMultiplier, lavaSpringEmissionMultiplier);
        appendCorrection(corrections, "waterSpringPulseIntervalMultiplier", oldWaterSpringPulseIntervalMultiplier, waterSpringPulseIntervalMultiplier);
        appendCorrection(corrections, "lavaSpringPulseIntervalMultiplier", oldLavaSpringPulseIntervalMultiplier, lavaSpringPulseIntervalMultiplier);

        if (!corrections.isEmpty()) {
            FlowingFluids.warn("Adjusted invalid flowing_fluids config values: " + corrections);
        }
    }

    private void appendCorrection(StringBuilder corrections, String key, int before, int after) {
        if (before != after) {
            appendCorrection(corrections, key, Integer.toString(before), Integer.toString(after));
        }
    }

    private void appendCorrection(StringBuilder corrections, String key, long before, long after) {
        if (before != after) {
            appendCorrection(corrections, key, Long.toString(before), Long.toString(after));
        }
    }

    private void appendCorrection(StringBuilder corrections, String key, float before, float after) {
        if (Float.compare(before, after) != 0) {
            appendCorrection(corrections, key, Float.toString(before), Float.toString(after));
        }
    }

    private void appendCorrection(StringBuilder corrections, String key, String before, String after) {
        if (corrections.length() > 0) {
            corrections.append(", ");
        }
        corrections.append(key).append(": ").append(before).append(" -> ").append(after);
    }

//    public enum LevelingStrength {
//        OFF(0),
//        VERY_WEAK(0.8f),
//        WEAK(0.7f),
//        MILD(0.6f),
//        STRONG(0.5f),
//        EXTREME(0.4f);
//
//        private final float stopChance;
//
//        LevelingStrength(float stopChance) {
//            this.stopChance = stopChance;
//        }
//
//        public float getStopChance() {
//            return stopChance;
//        }
//    }

    public enum LiquidHeight {
        REGULAR,
        REGULAR_LOWER_BOUND,
        BLOCK,
        BLOCK_LOWER_BOUND,
        SLAB,
        CARPET
    }

    #if MC <= MC_20_1
    public static final ResourceLocation SERVER_CONFIG_PACKET_ID = FFFluidUtils.res("flowing_fluids:server_config_packet");


    #endif
}
