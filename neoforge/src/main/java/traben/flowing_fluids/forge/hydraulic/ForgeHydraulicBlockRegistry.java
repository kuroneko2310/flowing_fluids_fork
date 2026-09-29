package traben.flowing_fluids.forge.hydraulic;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.block.PressureNozzleBlock;
import traben.flowing_fluids.block.WaterwayLinerBlock;

public final class ForgeHydraulicBlockRegistry {
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(Registries.BLOCK, FlowingFluids.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Registries.ITEM, FlowingFluids.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FlowingFluids.MOD_ID);

    public static final DeferredHolder<Block, Block> WATERWAY_LINER = BLOCKS.register(
        "waterway_liner",
        WaterwayLinerBlock::new
    );
    public static final DeferredHolder<Block, Block> PRESSURE_NOZZLE = BLOCKS.register(
        "pressure_nozzle",
        () -> new PressureNozzleBlock()
    );
    public static final DeferredHolder<Block, Block> WATER_LEVEL_SENSOR = BLOCKS.register(
        "water_level_sensor",
        () -> new WaterLevelSensorBlock()
    );
    public static final DeferredHolder<Block, Block> RAIN_COLLECTOR = BLOCKS.register(
        "rain_collector",
        () -> new RainCollectorBlock()
    );
    public static final DeferredHolder<Block, Block> WATER_ABSORBER = BLOCKS.register(
        "water_absorber",
        () -> new WaterAbsorberBlock()
    );
    public static final DeferredHolder<Block, Block> OCEAN_REFILL_SUPPRESSOR = BLOCKS.register(
        "ocean_refill_suppressor",
        () -> new OceanRefillSuppressorBlock()
    );
    public static final DeferredHolder<Item, Item> FLOW_ANCHOR_SURVEYOR = ITEMS.register(
        "flow_anchor_surveyor",
        () -> new FlowAnchorSurveyorItem(
            new Item.Properties().stacksTo(1),
            "tooltip.flowing_fluids.flow_anchor_surveyor"
        )
    );
    public static final DeferredHolder<Block, FlowAnchorBlock> FLOW_ANCHOR_DROPLET = registerFlowAnchor(FlowAnchorTier.DROPLET);
    public static final DeferredHolder<Block, FlowAnchorBlock> FLOW_ANCHOR_BROOK = registerFlowAnchor(FlowAnchorTier.BROOK);
    public static final DeferredHolder<Block, FlowAnchorBlock> FLOW_ANCHOR_CHANNEL = registerFlowAnchor(FlowAnchorTier.CHANNEL);
    public static final DeferredHolder<Block, FlowAnchorBlock> FLOW_ANCHOR_WELLSPRING = registerFlowAnchor(FlowAnchorTier.WELLSPRING);
    public static final DeferredHolder<Block, FlowAnchorBlock> FLOW_ANCHOR_LAKEHEART = registerFlowAnchor(FlowAnchorTier.LAKEHEART);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FlowAnchorBlockEntity>> FLOW_ANCHOR_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
        "flow_anchor",
        () -> BlockEntityType.Builder.of(
            FlowAnchorBlockEntity::new,
            FLOW_ANCHOR_DROPLET.get(),
            FLOW_ANCHOR_BROOK.get(),
            FLOW_ANCHOR_CHANNEL.get(),
            FLOW_ANCHOR_WELLSPRING.get(),
            FLOW_ANCHOR_LAKEHEART.get()
        ).build(null)
    );
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RainCollectorBlockEntity>> RAIN_COLLECTOR_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
        "rain_collector",
        () -> BlockEntityType.Builder.of(
            RainCollectorBlockEntity::new,
            RAIN_COLLECTOR.get()
        ).build(null)
    );
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WaterAbsorberBlockEntity>> WATER_ABSORBER_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
        "water_absorber",
        () -> BlockEntityType.Builder.of(
            WaterAbsorberBlockEntity::new,
            WATER_ABSORBER.get()
        ).build(null)
    );
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OceanRefillSuppressorBlockEntity>> OCEAN_REFILL_SUPPRESSOR_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
        "ocean_refill_suppressor",
        () -> BlockEntityType.Builder.of(
            OceanRefillSuppressorBlockEntity::new,
            OCEAN_REFILL_SUPPRESSOR.get()
        ).build(null)
    );

    private ForgeHydraulicBlockRegistry() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(ForgeHydraulicBlockRegistry::registerCapabilities);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        ITEMS.register("waterway_liner", () -> new HydraulicBlockItem(
            WATERWAY_LINER.get(),
            new Item.Properties(),
            "tooltip.flowing_fluids.waterway_liner"
        ));
        ITEMS.register("pressure_nozzle", () -> new HydraulicBlockItem(
            PRESSURE_NOZZLE.get(),
            new Item.Properties(),
            "tooltip.flowing_fluids.pressure_nozzle"
        ));
        ITEMS.register("water_level_sensor", () -> new HydraulicBlockItem(
            WATER_LEVEL_SENSOR.get(),
            new Item.Properties(),
            "tooltip.flowing_fluids.water_level_sensor"
        ));
        ITEMS.register("rain_collector", () -> new HydraulicBlockItem(
            RAIN_COLLECTOR.get(),
            new Item.Properties(),
            "tooltip.flowing_fluids.rain_collector"
        ));
        ITEMS.register("water_absorber", () -> new HydraulicBlockItem(
            WATER_ABSORBER.get(),
            new Item.Properties(),
            "tooltip.flowing_fluids.water_absorber"
        ));
        ITEMS.register("ocean_refill_suppressor", () -> new HydraulicBlockItem(
            OCEAN_REFILL_SUPPRESSOR.get(),
            new Item.Properties(),
            "tooltip.flowing_fluids.ocean_refill_suppressor"
        ));
        modBus.addListener(ForgeHydraulicBlockRegistry::addToCreativeTabs);
    }

    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(WATERWAY_LINER.get());
        }
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(PRESSURE_NOZZLE.get());
            event.accept(FLOW_ANCHOR_SURVEYOR.get());
            event.accept(FLOW_ANCHOR_DROPLET.get());
            event.accept(FLOW_ANCHOR_BROOK.get());
            event.accept(FLOW_ANCHOR_CHANNEL.get());
            event.accept(FLOW_ANCHOR_WELLSPRING.get());
            event.accept(FLOW_ANCHOR_LAKEHEART.get());
            event.accept(RAIN_COLLECTOR.get());
            event.accept(WATER_ABSORBER.get());
            event.accept(OCEAN_REFILL_SUPPRESSOR.get());
        }
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(WATER_LEVEL_SENSOR.get());
        }
    }

    private static DeferredHolder<Block, FlowAnchorBlock> registerFlowAnchor(FlowAnchorTier tier) {
        DeferredHolder<Block, FlowAnchorBlock> block = BLOCKS.register(
            tier.blockName(),
            () -> new FlowAnchorBlock(tier)
        );
        ITEMS.register(tier.blockName(), () -> new HydraulicBlockItem(
            block.get(),
            new Item.Properties(),
            "tooltip.flowing_fluids." + tier.blockName()
        ));
        return block;
    }

    private static void registerCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, RAIN_COLLECTOR_BLOCK_ENTITY.get(), (be, side) -> be.energyStorage());
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, RAIN_COLLECTOR_BLOCK_ENTITY.get(), (be, side) -> be.fluidStorage());
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, WATER_ABSORBER_BLOCK_ENTITY.get(), (be, side) -> be.energyStorage());
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, WATER_ABSORBER_BLOCK_ENTITY.get(), (be, side) -> be.fluidStorage());
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, OCEAN_REFILL_SUPPRESSOR_BLOCK_ENTITY.get(), (be, side) -> be.energyStorage());
    }
}
