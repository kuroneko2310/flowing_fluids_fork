package traben.flowing_fluids.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import traben.flowing_fluids.FFFluidUtils;
import traben.flowing_fluids.FlowingFluids;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only murky tint for rivers and seas in rain: runoff carries silt, so flood water turns brown.
 *
 * <p>Water colour is baked into chunk meshes, so a smoothly changing tint would force a mesh rebuild every frame.
 * Instead the rain level is quantised into three steps (clear, cloudy, muddy); only a step change clears the tint
 * cache and rebuilds the meshes once, and rebuilds are rate-limited so flickering weather cannot cause repeated
 * rebuild storms.</p>
 */
public final class FloodWaterTint {
    static final int MURKY_COLOR = 0x7A6A45;
    private static final int STEPS = 2;
    private static final long MIN_TICKS_BETWEEN_REBUILDS = 200L;

    private static volatile float murk;
    private static long lastRebuildTick = Long.MIN_VALUE;
    private static ClientLevel cachedLevel;
    private static final ConcurrentHashMap<Biome, Boolean> FLOODABLE = new ConcurrentHashMap<>();

    private FloodWaterTint() {
    }

    public static void onClientLevelTick(ClientLevel level) {
        if (level != cachedLevel) {
            cachedLevel = level;
            FLOODABLE.clear();
            murk = 0.0f;
        }
        float target = FlowingFluids.config != null
                && FlowingFluids.config.enableMod
                && FlowingFluids.config.enableMurkyFloodWater
                ? Math.max(quantize(level.getRainLevel(1.0f)),
                        traben.flowing_fluids.season.SeasonClimate.isSpringFreshet(level) ? 0.5f : 0.0f)
                : 0.0f;
        if (target == murk) {
            return;
        }
        long now = level.getGameTime();
        if (lastRebuildTick != Long.MIN_VALUE && now - lastRebuildTick < MIN_TICKS_BETWEEN_REBUILDS && target != 0.0f) {
            return;
        }
        murk = target;
        lastRebuildTick = now;
        level.clearTintCaches();
        Minecraft.getInstance().levelRenderer.allChanged();
    }

    static float quantize(float rainLevel) {
        if (!(rainLevel > 0.0f)) {
            return 0.0f;
        }
        return Math.round(Math.min(1.0f, rainLevel) * STEPS) / (float) STEPS;
    }

    /**
     * Called for every water colour lookup (possibly on chunk-meshing threads).
     */
    public static int tint(Biome biome, int original) {
        float current = murk;
        if (current <= 0.0f || !isFloodable(biome)) {
            return original;
        }
        float strength = FlowingFluids.config == null ? 0.65f : FlowingFluids.config.murkyFloodWaterStrength;
        return blend(original, MURKY_COLOR, Math.max(0.0f, Math.min(1.0f, current * strength)));
    }

    static int blend(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * t);
        int g = Math.round(((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * t);
        int b = Math.round((from & 255) + ((to & 255) - (from & 255)) * t);
        return (from & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static boolean isFloodable(Biome biome) {
        Boolean cached = FLOODABLE.get(biome);
        if (cached != null) {
            return cached;
        }
        boolean floodable = false;
        ClientLevel level = cachedLevel;
        if (level != null) {
            Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
            floodable = registry.getResourceKey(biome)
                    .flatMap(registry::getHolder)
                    .map(holder -> FFFluidUtils.isRiverBiome(holder) || FFFluidUtils.isOceanBiome(holder))
                    .orElse(false);
        }
        FLOODABLE.put(biome, floodable);
        return floodable;
    }
}
