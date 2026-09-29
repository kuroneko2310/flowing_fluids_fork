package traben.flowing_fluids.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Whole-mod starting points that change a handful of related settings at once, in the spirit of the original mod's
 * presets. Each only touches the settings it names and leaves everything else as the player set it.
 */
public final class FFPlaystylePresets {

    public record Preset(String description, Consumer<FFConfig> apply) {
    }

    public static final Map<String, Preset> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("create_industrial", new Preset(
                "Create を中心にした工業パック向け。水車は実際の流れと流量で回り、パイプは水を消費し、扇風機の洗浄と蒸気ボイラーも水を使います（復水器で回収可能）。地下水と井戸を有効にし、侵食は切ります。",
                config -> {
                    config.create_waterWheelMode = FFConfig.CreateWaterWheelMode.REQUIRE_FLOW_OR_RIVER;
                    config.create_infinitePipes = false;
                    config.create_waterWheelMaxSpeedMultiplier = 2.0f;
                    config.create_waterWheelFlowMaxTickInterval = 80;
                    config.create_fanWashingWaterUseChance = 0.25f;
                    config.create_condenserRecoveryFraction = 0.5f;
                    config.enableGroundwater = true;
                    config.enableErosion = false;
                }));
        PRESETS.put("realistic_cycle", new Preset(
                "水循環をすべて有効にします。豪雨セル、干ばつ、地下水、季節、増水、侵食と堆積、押しのけ水位を ON にし、Create の水利用も強めにします。重めです。",
                config -> {
                    config.enableRainSystem = true;
                    config.enableHeavyRainCells = true;
                    config.enableDroughtIndex = true;
                    config.enableRiverDroughts = true;
                    config.enableGroundwater = true;
                    config.enableSeasonIntegration = true;
                    config.enableRiverFloodStage = true;
                    config.enableFloodWarnings = true;
                    config.enableErosion = true;
                    config.enableEntityWaterDisplacement = true;
                    config.create_waterWheelMode = FFConfig.CreateWaterWheelMode.REQUIRE_FLOW;
                    config.create_infinitePipes = false;
                    config.create_waterWheelMaxSpeedMultiplier = 2.5f;
                    config.create_fanWashingWaterUseChance = 0.5f;
                    config.create_condenserRecoveryFraction = 0.4f;
                }));
        PRESETS.put("lightweight_server", new Preset(
                "サーバー負荷を下げる設定です。流体処理の範囲をプレイヤー周辺96ブロックに絞り、自動遅延と負荷ガードを ON、豪雨セル・侵食・自然サイフォン・押しのけ水位を OFF にします。",
                config -> {
                    config.playerBlockDistanceForFlowing = 96;
                    config.enableAutoTickDelay = true;
                    config.enableFluidWorkloadGovernor = true;
                    config.enableLoadReduction = true;
                    config.enableHeavyRainCells = false;
                    config.enableErosion = false;
                    config.enableNaturalTerrainSiphons = false;
                    config.enableEntityWaterDisplacement = false;
                    config.groundwaterSamplesPerTick = Math.min(config.groundwaterSamplesPerTick, 4);
                }));
        PRESETS.put("relaxed", new Preset(
                "水の管理を気楽にします。Create のパイプは水を減らさず、水車は水があれば回り、農地・繁殖・コンクリート・洗浄による水の消費と干ばつ系イベントを止めます。",
                config -> {
                    config.create_infinitePipes = true;
                    config.create_waterWheelMode = FFConfig.CreateWaterWheelMode.REQUIRE_FLUID;
                    config.create_waterWheelMaxSpeedMultiplier = 1.0f;
                    config.create_fanWashingWaterUseChance = 0.0f;
                    config.farmlandDrainWaterChance = 0.0f;
                    config.drinkWaterToBreedAnimalChance = 0.0f;
                    config.concreteDrainsWaterChance = 0.0f;
                    config.enableDroughtIndex = false;
                    config.enableRiverDroughts = false;
                    config.enableHeatwaveEvents = false;
                    config.enableDrySeasonEvents = false;
                }));
    }

    private FFPlaystylePresets() {
    }
}
