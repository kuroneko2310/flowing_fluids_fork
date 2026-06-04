package traben.flowing_fluids;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import traben.flowing_fluids.config.FFConfig;
import traben.flowing_fluids.drying.DryingEventSystem;
import traben.flowing_fluids.flood.FloodEventSystem;
import traben.flowing_fluids.performance.FluidAutoTickDelay;
import traben.flowing_fluids.performance.FluidTickWorkloadGovernor;
import traben.flowing_fluids.performance.InfiniteBiomeRefillFallbackController;
import traben.flowing_fluids.rain.RainWaterSystem;
import traben.flowing_fluids.water.WaterPressureSystem;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FlowingFluids {
    public static final String MOD_ID = "flowing_fluids";
    private static final DateTimeFormatter CONFIG_BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String LOG_PREFIX = "[Flowing Fluids] ";

    public final static Logger LOG = LoggerFactory.getLogger("FlowingFluids");
    private static final ThreadLocal<Boolean> IS_MANEUVERING_FLUIDS = ThreadLocal.withInitial(() -> Boolean.FALSE);
    public static volatile boolean pistonTick = false;
    public static volatile long debug_killFluidUpdatesUntilTime = 0;
    public static int waterPluggedThisSession = 0;
    public static final TagKey<Block> HYDRAULIC_FLOW_GUIDE_BLOCKS =
            TagKey.create(Registries.BLOCK, FFFluidUtils.res(MOD_ID, "hydraulic_flow_guides"));
    public static final TagKey<Block> HYDRAULIC_NOZZLE_BLOCKS =
            TagKey.create(Registries.BLOCK, FFFluidUtils.res(MOD_ID, "hydraulic_nozzles"));

    public static Set<Pair<Fluid, TagKey<Block>>> nonDisplacerTags = new HashSet<>();
    public static Set<Pair<Fluid, Block>> nonDisplacers = new HashSet<>();
    public static Set<TagKey<Biome>> infiniteBiomeTags = new HashSet<>();
    public static Set<ResourceKey<Biome>> infiniteBiomes = new HashSet<>();

    public static FFConfig config = new FFConfig();

    public static void info(String str) { LOG.info("{}{}", LOG_PREFIX, str); }
    public static void warn(String str) { LOG.warn("{}{}", LOG_PREFIX, str); }
    public static void warn(String str, Throwable throwable) { LOG.warn(LOG_PREFIX + str, throwable); }
    public static void error(String str) { LOG.error("{}{}", LOG_PREFIX, str); }
    public static void error(String str, Throwable throwable) { LOG.error(LOG_PREFIX + str, throwable); }
    public static synchronized void recordWaterPluggedDuringWorldgen() { waterPluggedThisSession++; }
    public static boolean isManeuveringFluids() { return IS_MANEUVERING_FLUIDS.get(); }
    public static void setManeuveringFluids(boolean value) { IS_MANEUVERING_FLUIDS.set(value); }

    public static void init() {
        info("initialising");

        rebuildInfiniteBiomeDefaults();
        nonDisplacerTags.clear();
        nonDisplacers.clear();

        nonDisplacerTags.add(Pair.of(Fluids.WATER, BlockTags.ICE));
        nonDisplacers.add(Pair.of(Fluids.WATER, Blocks.SPONGE));
        nonDisplacers.add(Pair.of(Fluids.LAVA, Blocks.OBSIDIAN));

        loadConfig();
    }

    public static void loadConfig() {
        File configFile = new File(FlowingFluidsPlatform.getConfigDirectory().toFile(), "flowing_fluids.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().setLenient().create();

        FFConfig loadedConfig = null;
        boolean rewriteConfig = false;

        if (configFile.exists()) {
            try (FileReader fileReader = new FileReader(configFile)) {
                loadedConfig = gson.fromJson(fileReader, FFConfig.class);
                if (loadedConfig == null) {
                    warn("Config file was empty, regenerating defaults.");
                    rewriteConfig = true;
                }
            } catch (IOException | RuntimeException e) {
                File backupFile = backupBrokenConfig(configFile);
                String backupMessage = backupFile != null
                        ? " Backed up broken config to " + backupFile.getAbsolutePath() + "."
                        : "";
                warn("Failed to load config from " + configFile.getAbsolutePath()
                        + ", regenerating defaults." + backupMessage, e);
                rewriteConfig = true;
            }
        } else {
            rewriteConfig = true;
        }

        config = loadedConfig != null ? loadedConfig : new FFConfig();
        applyConfigRuntime();

        if (rewriteConfig) {
            saveConfig();
        }
    }

    public static void saveConfig() {
        File configFile = new File(FlowingFluidsPlatform.getConfigDirectory().toFile(), "flowing_fluids.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            warn("Could not create config directory: " + parent.getAbsolutePath());
            return;
        }

        try (FileWriter fileWriter = new FileWriter(configFile)) {
            fileWriter.write(gson.toJson(config));
        } catch (IOException e) {
            warn("Failed to save config to " + configFile.getAbsolutePath() + ".", e);
        }
    }

    public static void rebuildInfiniteBiomeDefaults() {
        infiniteBiomeTags.clear();
        infiniteBiomes.clear();
        infiniteBiomeTags.add(BiomeTags.IS_OCEAN);
        infiniteBiomeTags.add(BiomeTags.IS_RIVER);
        infiniteBiomeTags.add(BiomeTags.IS_BEACH);
        infiniteBiomes.add(Biomes.SWAMP);
        infiniteBiomes.add(Biomes.MANGROVE_SWAMP);
    }

    public static void applyConfigRuntime() {
        if (config == null) {
            config = new FFConfig();
        }
        config.ensureCollections();
        config.sanitizeRanges();
        rebuildInfiniteBiomeDefaults();
        RainWaterSystem.reloadConfig();
        FluidAutoTickDelay.reloadConfig();
        InfiniteBiomeRefillFallbackController.reloadConfig();
    }

    public static int autoAddDetectedInfiniteBiomes(MinecraftServer server) {
        if (server == null || config == null) {
            return 0;
        }

        config.ensureCollections();
        if (!config.enableAutomaticInfiniteBiomeAddition) {
            return 0;
        }

        ServerLevel level = server.overworld();
        if (level == null) {
            return 0;
        }

        List<FFFluidUtils.AutoInfiniteBiomeCandidate> candidates = FFFluidUtils.collectAutoInfiniteBiomeCandidates(
                level,
                config.automaticInfiniteBiomeAdditionModdedOnly
        );

        int added = 0;
        for (FFFluidUtils.AutoInfiniteBiomeCandidate candidate : candidates) {
            if (config.extraInfiniteBiomeEntries.add(candidate.biomeId())) {
                added++;
            }
        }

        if (added <= 0) {
            return 0;
        }

        saveConfig();
        applyConfigRuntime();
        refreshFluidRuntime(server);
        server.getPlayerList().getPlayers().forEach(FlowingFluidsPlatform::sendConfigToClient);
        info("Auto-added " + added + " infinite biome entries from loaded biome registry.");
        return added;
    }

    public static void refreshFluidRuntime(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            refreshFluidRuntime(level);
        }
    }

    public static void refreshFluidRuntime(ServerLevel level) {
        if (level == null) {
            return;
        }
        // Runtime config changes should drop any cached event state so the next tick
        // reflects the new rules instead of finishing an old scenario first.
        FloodEventSystem.clearDimension(level);
        DryingEventSystem.clearDimension(level);
        WaterPressureSystem.clearDimension(level);
        FlowingFluidsPlatform.clearPlatformRuntime(level);
        AdaptiveTickScheduler.clearDimension(level);
        FluidSpatialGrid.clearDimension(level);
        ChunkLocalSlopeCache.clearDimension(level);
        SiphonFlowSystem.clearDimension(level);
        FluidActivityTracker.clearDimension(level);
        FluidTickBuffer.clearDimension(level);
        FluidComponentGraph.clearDimension(level);
        ParallelFluidEqualizer.clearDimension(level);
        ParallelFluidTickManager.clearDimension(level);
        FluidTickWorkloadGovernor.clearDimension(level);
        ExtendedWaterlogStore.clearDimension(level);
        traben.flowing_fluids.optimization.HierarchicalDistanceManager.getInstance().clearDimension(level);
    }

    private static File backupBrokenConfig(File configFile) {
        if (!configFile.exists()) {
            return null;
        }

        String fileName = configFile.getName();
        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex >= 0 ? fileName.substring(0, extensionIndex) : fileName;
        String extension = extensionIndex >= 0 ? fileName.substring(extensionIndex) : "";
        File backupFile = new File(
                configFile.getParentFile(),
                baseName + ".invalid-" + CONFIG_BACKUP_TIMESTAMP.format(LocalDateTime.now()) + extension
        );

        try {
            Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return backupFile;
        } catch (IOException backupException) {
            warn("Failed to back up broken config from " + configFile.getAbsolutePath() + ".", backupException);
            return null;
        }
    }
}
