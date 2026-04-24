package traben.flowing_fluids.forge.hydraulic;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import traben.flowing_fluids.FlowingFluids;
import traben.flowing_fluids.block.PressureNozzleBlock;
import traben.flowing_fluids.block.WaterwayLinerBlock;

public final class ForgeHydraulicBlockRegistry {
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(ForgeRegistries.BLOCKS, FlowingFluids.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, FlowingFluids.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, FlowingFluids.MOD_ID);

    public static final RegistryObject<Block> WATERWAY_LINER = BLOCKS.register(
        "waterway_liner",
        WaterwayLinerBlock::new
    );
    public static final RegistryObject<Block> PRESSURE_NOZZLE = BLOCKS.register(
        "pressure_nozzle",
        PressureNozzleBlock::new
    );
    public static final RegistryObject<Block> WATER_LEVEL_SENSOR = BLOCKS.register(
        "water_level_sensor",
        WaterLevelSensorBlock::new
    );
    public static final RegistryObject<Block> RAIN_COLLECTOR = BLOCKS.register(
        "rain_collector",
        RainCollectorBlock::new
    );
    public static final RegistryObject<Block> WATER_ABSORBER = BLOCKS.register(
        "water_absorber",
        WaterAbsorberBlock::new
    );
    public static final RegistryObject<Item> FLOW_ANCHOR_SURVEYOR = ITEMS.register(
        "flow_anchor_surveyor",
        () -> new FlowAnchorSurveyorItem(
            new Item.Properties().stacksTo(1),
            "tooltip.flowing_fluids.flow_anchor_surveyor"
        )
    );
    public static final RegistryObject<FlowAnchorBlock> FLOW_ANCHOR_DROPLET = registerFlowAnchor(FlowAnchorTier.DROPLET);
    public static final RegistryObject<FlowAnchorBlock> FLOW_ANCHOR_BROOK = registerFlowAnchor(FlowAnchorTier.BROOK);
    public static final RegistryObject<FlowAnchorBlock> FLOW_ANCHOR_CHANNEL = registerFlowAnchor(FlowAnchorTier.CHANNEL);
    public static final RegistryObject<FlowAnchorBlock> FLOW_ANCHOR_WELLSPRING = registerFlowAnchor(FlowAnchorTier.WELLSPRING);
    public static final RegistryObject<FlowAnchorBlock> FLOW_ANCHOR_LAKEHEART = registerFlowAnchor(FlowAnchorTier.LAKEHEART);
    public static final RegistryObject<BlockEntityType<FlowAnchorBlockEntity>> FLOW_ANCHOR_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
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
    public static final RegistryObject<BlockEntityType<RainCollectorBlockEntity>> RAIN_COLLECTOR_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
        "rain_collector",
        () -> BlockEntityType.Builder.of(
            RainCollectorBlockEntity::new,
            RAIN_COLLECTOR.get()
        ).build(null)
    );
    public static final RegistryObject<BlockEntityType<WaterAbsorberBlockEntity>> WATER_ABSORBER_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
        "water_absorber",
        () -> BlockEntityType.Builder.of(
            WaterAbsorberBlockEntity::new,
            WATER_ABSORBER.get()
        ).build(null)
    );

    private ForgeHydraulicBlockRegistry() {
    }

    public static void register(IEventBus modBus) {
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
        }
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(WATER_LEVEL_SENSOR.get());
        }
    }

    private static RegistryObject<FlowAnchorBlock> registerFlowAnchor(FlowAnchorTier tier) {
        RegistryObject<FlowAnchorBlock> block = BLOCKS.register(
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
}
