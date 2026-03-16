package traben.flowing_fluids;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.resources.ResourceKey;
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
import traben.flowing_fluids.rain.RainWaterSystem;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

public final class FlowingFluids {
    public static final String MOD_ID = "flowing_fluids";
    private static final DateTimeFormatter CONFIG_BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public final static Logger LOG = LoggerFactory.getLogger("FlowingFluids");
    public static boolean isManeuveringFluids = false;
    public static boolean pistonTick = false;
    public static long debug_killFluidUpdatesUntilTime = 0;
    public static int waterPluggedThisSession = 0;

    public static Set<Pair<Fluid, TagKey<Block>>> nonDisplacerTags = new HashSet<>();
    public static Set<Pair<Fluid, Block>> nonDisplacers = new HashSet<>();
    public static Set<TagKey<Biome>> infiniteBiomeTags = new HashSet<>();
    public static Set<ResourceKey<Biome>> infiniteBiomes = new HashSet<>();

    public static FFConfig config = new FFConfig();

    public static void info(String str) { LOG.info("[Flowing Fluids] {}", str); }
    public static void warn(String str) { LOG.warn("[Flowing Fluids] {}", str); }
    public static void error(String str) { LOG.error("[Flowing Fluids] {}", str); }

    public static void init() {
        info("initialising");

        infiniteBiomeTags.clear();
        infiniteBiomes.clear();
        nonDisplacerTags.clear();
        nonDisplacers.clear();

        infiniteBiomeTags.add(BiomeTags.IS_OCEAN);
        infiniteBiomeTags.add(BiomeTags.IS_RIVER);
        infiniteBiomeTags.add(BiomeTags.IS_BEACH);
        infiniteBiomes.add(Biomes.SWAMP);
        infiniteBiomes.add(Biomes.MANGROVE_SWAMP);

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
                warn("Failed to load config, backing it up and regenerating defaults: " + e.getMessage());
                backupBrokenConfig(configFile);
                rewriteConfig = true;
            }
        } else {
            rewriteConfig = true;
        }

        config = loadedConfig != null ? loadedConfig : new FFConfig();
        config.ensureCollections();
        RainWaterSystem.reloadConfig();

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
            warn("Failed to save config: " + e.getMessage());
        }
    }

    private static void backupBrokenConfig(File configFile) {
        if (!configFile.exists()) {
            return;
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
        } catch (IOException backupException) {
            warn("Failed to back up broken config: " + backupException.getMessage());
        }
    }
}
