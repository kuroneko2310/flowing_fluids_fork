package traben.flowing_fluids.config;

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
import traben.flowing_fluids.FlowingFluids;

public class FFConfig {
    public boolean flowToEdges = true;
    public boolean enableMod = true;
//    public boolean debugSpread = false;
//    public boolean debugSpreadPrint = false;
    public boolean enableDisplacement = true;
    public boolean enablePistonPushing = true;
    public float rainRefillChance = 0.3f;
    public float oceanRiverSwampRefillChance = 1f;
    public float evaporationChanceV2 = 1f;
    public boolean evaporationDaytimeOnly = true;
    public boolean evaporationRequiresSky = true;
    public float evaporationNetherChance = 1f;
    public boolean printRandomTicks = false;
    public boolean hideFlowingTexture = true;
    public LiquidHeight fullLiquidHeight = LiquidHeight.REGULAR;
    public float farmlandDrainWaterChance = 0.1f;
    public boolean debugWaterLevelColours = false;
    public WaterLogFlowMode waterLogFlowMode = WaterLogFlowMode.IN_FROM_TOP_ELSE_OUT;
    public int waterFlowDistance = 4;
    public int lavaFlowDistance = 2;
    public int lavaNetherFlowDistance = 4;
    public int waterTickDelay = 2;
    public int lavaTickDelay= 15;
    public int lavaNetherTickDelay = 5;
    public int randomTickLevelingDistance = 32;

    // Advanced water flow distance settings
    public int maxWaterFlowDistance = 8; // Maximum horizontal flow distance (can be higher than base flow distance)
    public int bfsMaxSearchDistance = 16; // Maximum BFS search distance for equalization
    public float slopeFindDistanceMultiplier = 1.0f; // Multiplier for slope finding distance (1.0 = default, higher = farther search)
    public boolean enableAdaptiveFlowDistance = true; // Adjust flow distance based on terrain type
    public int riverFlowDistance = 64; // Flow distance in river biomes
    public int oceanFlowDistance = 128; // Flow distance in ocean biomes
    public int canalFlowDistance = 32; // Flow distance for artificial canals (flat terrain)

    // Performance monitoring settings
    public boolean enablePerformanceMonitoring = false; // Enable detailed performance tracking
    public int performanceLogInterval = 200; // Log performance data every N ticks (20 ticks = 1 second)
    public boolean enableDistanceBasedOptimization = true; // Apply optimizations based on flow distance

    public float drinkWaterToBreedAnimalChance = 0.1f;
    public boolean encloseAllFluidOnWorldGen = true;
    public boolean announceWorldGenActions = false;
    public boolean easyPistonPump = true;
    public boolean waterFlowAffectsBoats = false;
    public boolean waterFlowAffectsEntities = true;
    public boolean waterFlowAffectsPlayers = false;
    public boolean waterFlowAffectsItems = true;
    public float infiniteWaterBiomeNonConsumeChance = 0.01f;
    public float infiniteWaterBiomeDrainSurfaceChance = 0.1f;
    public int minWaterLevelForIce = 4;
    public boolean rainFillsWaterHigherV2 = false;
    public int rainBfsCooldownTicks = 5;
    public float rainSurfaceSpawnChance = 0.05f;
    public int rainSurfaceSpawnLevel = 1;
    public float rainLevelJumpChance = 0.05f;
    public int infiniteBiomeRainFillMaxLevel = 6;
    public int minLavaLevelForObsidian = 6;
    public boolean fastBiomeRefillAtSeaLevelOnly = false;
    public int playerBlockDistanceForFlowing = 0;
    public float concreteDrainsWaterChance = 0.5f;
    public boolean autoDetectWaterBiomes = true;
    public ObjectOpenHashSet<String> extraOceanBiomes = new ObjectOpenHashSet<>();
    public ObjectOpenHashSet<String> extraRiverBiomes = new ObjectOpenHashSet<>();
    public ObjectOpenHashSet<String> extraBeachBiomes = new ObjectOpenHashSet<>();

    // Adaptive scheduler settings
    public long adaptiveSchedulerChunkExpiryMs = 60_000; // 1 minute by default
    public int adaptiveSchedulerMaxEntries = 10_000; // Max cached stability entries


    // create mod options
    public CreateWaterWheelMode create_waterWheelMode = CreateWaterWheelMode.REQUIRE_FLOW_OR_RIVER;
    public boolean create_infinitePipes = false;

    // fluid blacklist
    public ObjectOpenHashSet<String> fluidBlacklist = new ObjectOpenHashSet<>();

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

    public boolean dontTickAtLocation(BlockPos pos, LevelAccessor level) {
        if (playerBlockDistanceForFlowing == 0) return false;

        int sqrDist = playerBlockDistanceForFlowing * playerBlockDistanceForFlowing;

        if (level instanceof net.minecraft.world.level.Level lvl) {
            return lvl.getNearestPlayer(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, playerBlockDistanceForFlowing, false) == null;
        }

        // if any player is within distance
        for(Player player2 : level.players()) {
            double i = player2.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
            if (i < sqrDist) return false;
        }
        return true;
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
        evaporationDaytimeOnly = buffer.readBoolean();
        evaporationRequiresSky = buffer.readBoolean();
        evaporationNetherChance = buffer.readFloat();
        printRandomTicks = buffer.readBoolean();
        hideFlowingTexture = buffer.readBoolean();
        fullLiquidHeight = buffer.readEnum(LiquidHeight.class);
        farmlandDrainWaterChance = buffer.readFloat();
        debugWaterLevelColours = buffer.readBoolean();
        waterLogFlowMode = buffer.readEnum(WaterLogFlowMode.class);
        waterFlowDistance = buffer.readVarInt();
        lavaFlowDistance = buffer.readVarInt();
        lavaNetherFlowDistance = buffer.readVarInt();
        waterTickDelay = buffer.readVarInt();
        lavaTickDelay = buffer.readVarInt();
        lavaNetherTickDelay = buffer.readVarInt();
        randomTickLevelingDistance = buffer.readVarInt();

        // Advanced water flow distance settings
        maxWaterFlowDistance = buffer.readVarInt();
        bfsMaxSearchDistance = buffer.readVarInt();
        slopeFindDistanceMultiplier = buffer.readFloat();
        enableAdaptiveFlowDistance = buffer.readBoolean();
        riverFlowDistance = buffer.readVarInt();
        oceanFlowDistance = buffer.readVarInt();
        canalFlowDistance = buffer.readVarInt();

        // Performance monitoring settings
        enablePerformanceMonitoring = buffer.readBoolean();
        performanceLogInterval = buffer.readVarInt();
        enableDistanceBasedOptimization = buffer.readBoolean();

        drinkWaterToBreedAnimalChance = buffer.readFloat();
        encloseAllFluidOnWorldGen = buffer.readBoolean();
        announceWorldGenActions = buffer.readBoolean();
        easyPistonPump = buffer.readBoolean();
        waterFlowAffectsBoats = buffer.readBoolean();
        waterFlowAffectsEntities = buffer.readBoolean();
        waterFlowAffectsPlayers = buffer.readBoolean();
        waterFlowAffectsItems = buffer.readBoolean();
        infiniteWaterBiomeNonConsumeChance = buffer.readFloat();
        infiniteWaterBiomeDrainSurfaceChance = buffer.readFloat();
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

        adaptiveSchedulerChunkExpiryMs = buffer.readVarLong();
        adaptiveSchedulerMaxEntries = buffer.readVarInt();


        //create mod options
        create_waterWheelMode = buffer.readEnum(CreateWaterWheelMode.class);
        create_infinitePipes = buffer.readBoolean();

        //blacklist
        fluidBlacklist = buffer.readCollection(ObjectOpenHashSet::new, FriendlyByteBuf::readUtf);
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
        buffer.writeBoolean(evaporationDaytimeOnly);
        buffer.writeBoolean(evaporationRequiresSky);
        buffer.writeFloat(evaporationNetherChance);
        buffer.writeBoolean(printRandomTicks);
        buffer.writeBoolean(hideFlowingTexture);
        buffer.writeEnum(fullLiquidHeight);
        buffer.writeFloat(farmlandDrainWaterChance);
        buffer.writeBoolean(debugWaterLevelColours);
        buffer.writeEnum(waterLogFlowMode);
        buffer.writeVarInt(waterFlowDistance);
        buffer.writeVarInt(lavaFlowDistance);
        buffer.writeVarInt(lavaNetherFlowDistance);
        buffer.writeVarInt(waterTickDelay);
        buffer.writeVarInt(lavaTickDelay);
        buffer.writeVarInt(lavaNetherTickDelay);
        buffer.writeVarInt(randomTickLevelingDistance);

        // Advanced water flow distance settings
        buffer.writeVarInt(maxWaterFlowDistance);
        buffer.writeVarInt(bfsMaxSearchDistance);
        buffer.writeFloat(slopeFindDistanceMultiplier);
        buffer.writeBoolean(enableAdaptiveFlowDistance);
        buffer.writeVarInt(riverFlowDistance);
        buffer.writeVarInt(oceanFlowDistance);
        buffer.writeVarInt(canalFlowDistance);

        // Performance monitoring settings
        buffer.writeBoolean(enablePerformanceMonitoring);
        buffer.writeVarInt(performanceLogInterval);
        buffer.writeBoolean(enableDistanceBasedOptimization);

        buffer.writeFloat(drinkWaterToBreedAnimalChance);
        buffer.writeBoolean(encloseAllFluidOnWorldGen);
        buffer.writeBoolean(announceWorldGenActions);
        buffer.writeBoolean(easyPistonPump);
        buffer.writeBoolean(waterFlowAffectsBoats);
        buffer.writeBoolean(waterFlowAffectsEntities);
        buffer.writeBoolean(waterFlowAffectsPlayers);
        buffer.writeBoolean(waterFlowAffectsItems);
        buffer.writeFloat(infiniteWaterBiomeNonConsumeChance);
        buffer.writeFloat(infiniteWaterBiomeDrainSurfaceChance);
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

        buffer.writeVarLong(adaptiveSchedulerChunkExpiryMs);
        buffer.writeVarInt(adaptiveSchedulerMaxEntries);

        //create mod options
        buffer.writeEnum(create_waterWheelMode);
        buffer.writeBoolean(create_infinitePipes);

        //blacklist
        buffer.writeCollection(fluidBlacklist, FriendlyByteBuf::writeUtf);
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
        if (extraOceanBiomes == null) extraOceanBiomes = new ObjectOpenHashSet<>();
        if (extraRiverBiomes == null) extraRiverBiomes = new ObjectOpenHashSet<>();
        if (extraBeachBiomes == null) extraBeachBiomes = new ObjectOpenHashSet<>();
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
