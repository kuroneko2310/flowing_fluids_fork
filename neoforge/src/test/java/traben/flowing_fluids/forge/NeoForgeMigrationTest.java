package traben.flowing_fluids.forge;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import traben.flowing_fluids.forge.hydraulic.ForgeHydraulicBlockRegistry;

import static org.junit.jupiter.api.Assertions.*;

class NeoForgeMigrationTest {
    @Test
    void migratedMixinTargetsCanBeTransformed() throws ClassNotFoundException {
        Class<?> evaluator = Class.forName("net.minecraft.world.level.pathfinder.WalkNodeEvaluator");
        assertTrue(java.util.Arrays.stream(evaluator.getDeclaredMethods())
                .anyMatch(method -> method.getName().contains("ff$treatShallowWaterAsWalkable")));
        var mods = net.neoforged.fml.ModList.get();
        if (mods.isLoaded("create")) {
            for (String target : new String[] {
                    "com.simibubi.create.content.fluids.hosePulley.HosePulleyFluidHandler",
                    "com.simibubi.create.content.processing.basin.BasinRecipe",
                    "com.simibubi.create.content.processing.basin.BasinBlockEntity",
                    "com.simibubi.create.content.fluids.OpenEndedPipe",
                    "com.simibubi.create.content.fluids.transfer.GenericItemEmptying",
                    "com.simibubi.create.foundation.fluid.CombinedTankWrapper",
                    "com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity"
            }) Class.forName(target);
        }
        if (mods.isLoaded("mekanism")) {
            Class.forName("mekanism.common.tile.machine.TileEntityElectricPump");
            Class.forName("mekanism.common.item.block.machine.ItemBlockFluidTank");
            Class.forName("mekanism.common.item.block.machine.ItemBlockFluidTank$FluidTankItemDispenseBehavior");
        }
        if (mods.isLoaded("itemphysic")) {
            Class.forName("team.creative.itemphysic.common.CommonPhysic");
            Class.forName("team.creative.itemphysic.server.ItemPhysicServer");
        }
    }

    @Test
    @ExtendWith(EphemeralTestServerProvider.class)
    void migratedDataLoadsFromSingularRegistryFolders(MinecraftServer server) {
        assertTrue(server.getRecipeManager().byKey(id("rain_collector")).isPresent());
        assertTrue(server.getRecipeManager().byKey(id("water_absorber")).isPresent());
        var loot = server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id("blocks/rain_collector")));
        assertNotSame(LootTable.EMPTY, loot);
        assertTrue(ForgeHydraulicBlockRegistry.PRESSURE_NOZZLE.get().defaultBlockState()
                .is(TagKey.create(Registries.BLOCK, id("hydraulic_nozzles"))));
        assertTrue(server.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .containsKey(id("cave_floor_spring")));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("flowing_fluids", path);
    }
}
