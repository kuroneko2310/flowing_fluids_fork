package traben.flowing_fluids.forge.debug;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import traben.flowing_fluids.FlowingFluids;

public final class ForgeDebugItemRegistry {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, FlowingFluids.MOD_ID);

    public static final RegistryObject<Item> FLUID_DEBUG_PROBE = ITEMS.register(
            "fluid_debug_probe",
            () -> new FluidDebugProbeItem(
                    new Item.Properties().stacksTo(1),
                    "tooltip.flowing_fluids.fluid_debug_probe"
            )
    );

    private ForgeDebugItemRegistry() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(ForgeDebugItemRegistry::addToCreativeTabs);
    }

    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(FLUID_DEBUG_PROBE.get());
        }
    }
}
