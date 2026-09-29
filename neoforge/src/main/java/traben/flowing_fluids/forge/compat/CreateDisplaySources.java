package traben.flowing_fluids.forge.compat;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.SingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.drying.DryingEventSystem;
import traben.flowing_fluids.forge.hydraulic.ForgeHydraulicBlockRegistry;
import traben.flowing_fluids.forge.hydraulic.SteamCondenserBlockEntity;
import traben.flowing_fluids.forge.hydraulic.WaterLevelSensorBlock;
import traben.flowing_fluids.rain.HeavyRainCellSystem;
import traben.flowing_fluids.water.GroundwaterSystem;
import traben.flowing_fluids.water.RiverFloodStage;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Create display link sources: the rain collector reports the weather (rain strength, drought, groundwater), the water
 * level sensor its reading, and the steam condenser its stored condensate. Only loaded when Create is present.
 */
public final class CreateDisplaySources {
    public static final DeferredRegister<DisplaySource> SOURCES = DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, FlowingFluids.MOD_ID);

    public static final Supplier<DisplaySource> RAIN_INTENSITY = SOURCES.register("rain_intensity", RainIntensity::new);
    public static final Supplier<DisplaySource> DROUGHT = SOURCES.register("drought", Drought::new);
    public static final Supplier<DisplaySource> GROUNDWATER = SOURCES.register("groundwater", Groundwater::new);
    public static final Supplier<DisplaySource> WATER_LEVEL = SOURCES.register("water_level", WaterLevel::new);
    public static final Supplier<DisplaySource> CONDENSATE = SOURCES.register("condensate", Condensate::new);

    private CreateDisplaySources() {
    }

    public static void register(final IEventBus modBus) {
        SOURCES.register(modBus);
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(CreateDisplaySources::bindToBlocks));
    }

    private static void bindToBlocks() {
        DisplaySource.BY_BLOCK.add(ForgeHydraulicBlockRegistry.RAIN_COLLECTOR.get(), RAIN_INTENSITY.get());
        DisplaySource.BY_BLOCK.add(ForgeHydraulicBlockRegistry.RAIN_COLLECTOR.get(), DROUGHT.get());
        DisplaySource.BY_BLOCK.add(ForgeHydraulicBlockRegistry.RAIN_COLLECTOR.get(), GROUNDWATER.get());
        DisplaySource.BY_BLOCK.add(ForgeHydraulicBlockRegistry.WATER_LEVEL_SENSOR.get(), WATER_LEVEL.get());
        DisplaySource.BY_BLOCK.add(ForgeHydraulicBlockRegistry.STEAM_CONDENSER.get(), CONDENSATE.get());
    }

    private static String percent(final double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", Math.max(0.0, Math.min(1.0, fraction)) * 100.0);
    }

    private abstract static class FluidsSource extends SingleLineDisplaySource {
        @Override
        protected boolean allowsLabeling(final DisplayLinkContext context) {
            return true;
        }

        @Override
        public int getPassiveRefreshTicks() {
            return 40;
        }

        @Override
        protected MutableComponent provideLine(final DisplayLinkContext context, final DisplayTargetStats stats) {
            Level level = context.level();
            if (!(level instanceof ServerLevel serverLevel)) {
                return EMPTY_LINE;
            }
            return line(serverLevel, context.getSourcePos());
        }

        protected abstract MutableComponent line(ServerLevel level, BlockPos pos);
    }

    private static final class RainIntensity extends FluidsSource {
        @Override
        protected MutableComponent line(final ServerLevel level, final BlockPos pos) {
            MutableComponent flood = RiverFloodStage.isHighWater(level)
                    ? Component.translatable("flowing_fluids.display_source.rain_intensity.high_water")
                    : Component.empty();
            if (!level.isRainingAt(pos.above()) && !level.isRainingAt(level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pos))) {
                return Component.translatable("flowing_fluids.display_source.rain_intensity.dry").append(flood);
            }
            float multiplier = HeavyRainCellSystem.getRainMultiplier(level, pos.getX() + 0.5, pos.getZ() + 0.5);
            if (level.isThundering()) {
                return Component.translatable("flowing_fluids.display_source.rain_intensity.storm",
                        String.format(Locale.ROOT, "%.1f", multiplier)).append(flood);
            }
            return Component.translatable("flowing_fluids.display_source.rain_intensity.rain",
                    String.format(Locale.ROOT, "%.1f", multiplier)).append(flood);
        }
    }

    private static final class Drought extends FluidsSource {
        @Override
        protected MutableComponent line(final ServerLevel level, final BlockPos pos) {
            if (!FlowingFluids.config.enableDroughtIndex) {
                return Component.translatable("flowing_fluids.display_source.drought.off");
            }
            return Component.translatable("flowing_fluids.display_source.drought.value",
                    percent(DryingEventSystem.getDroughtIndex(level)));
        }
    }

    private static final class Groundwater extends FluidsSource {
        @Override
        protected MutableComponent line(final ServerLevel level, final BlockPos pos) {
            double saturation = GroundwaterSystem.getSaturation(level, pos);
            if (saturation < 0) {
                return Component.translatable("flowing_fluids.display_source.groundwater.off");
            }
            return Component.translatable("flowing_fluids.display_source.groundwater.value",
                    percent(saturation), GroundwaterSystem.getWaterTableY(level, pos));
        }
    }

    private static final class WaterLevel extends FluidsSource {
        @Override
        protected MutableComponent line(final ServerLevel level, final BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof WaterLevelSensorBlock)) {
                return EMPTY_LINE;
            }
            int power = state.getValue(WaterLevelSensorBlock.POWER);
            if (state.getValue(WaterLevelSensorBlock.FACING) == Direction.DOWN) {
                return power <= 0
                        ? Component.translatable("flowing_fluids.display_source.water_level.no_surface")
                        : Component.translatable("flowing_fluids.display_source.water_level.depth", 15 - power);
            }
            return Component.translatable("flowing_fluids.display_source.water_level.value", power);
        }
    }

    private static final class Condensate extends FluidsSource {
        @Override
        protected MutableComponent line(final ServerLevel level, final BlockPos pos) {
            if (!(level.getBlockEntity(pos) instanceof SteamCondenserBlockEntity condenser)) {
                return EMPTY_LINE;
            }
            return Component.translatable("flowing_fluids.display_source.condensate.value", condenser.storedMilliBuckets());
        }
    }
}
