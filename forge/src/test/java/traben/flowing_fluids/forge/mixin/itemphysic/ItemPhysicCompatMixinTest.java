package traben.flowing_fluids.forge.mixin.itemphysic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemPhysicCompatMixinTest {
    @Test
    void itemPhysicFluidReadsUseEffectiveFlowingFluidsState() throws IOException {
        String commonPhysicMixin = Files.readString(sourcePath("src/main/java/traben/flowing_fluids/forge/mixin/itemphysic/MixinCommonPhysic.java"));
        String serverMixin = Files.readString(sourcePath("src/main/java/traben/flowing_fluids/forge/mixin/itemphysic/MixinItemPhysicServer.java"));
        String compatHelper = Files.readString(sourcePath("src/main/java/traben/flowing_fluids/forge/compat/itemphysic/ItemPhysicFluidCompat.java"));
        String forgeMixins = Files.readString(sourcePath("src/main/resources/flowing_fluids_forge.mixins.json"));
        String plugin = Files.readString(sourcePath("src/main/java/traben/flowing_fluids/forge/mixin/FFPluginForge.java"));

        assertTrue(commonPhysicMixin.contains("FFFluidUtils.getEffectiveFluidState"),
                "ItemPhysic's buoyancy fluid probe should see partial and virtual Flowing Fluids water.");
        assertTrue(serverMixin.contains("FFFluidUtils.getEffectiveFluidState"),
                "ItemPhysic's fluid-height and pushing scan should use the same effective water state.");
        assertTrue(commonPhysicMixin.contains("ItemPhysicFluidCompat.findEffectiveFluid"),
                "ItemPhysic's primary buoyancy probe should scan the item bounds, not only the block position.");
        assertTrue(serverMixin.contains("applyEffectiveBuoyancyIfMissing"),
                "ItemPhysic updatePre should receive a fallback buoyancy pass when the original probe missed effective water.");
        assertTrue(compatHelper.contains("getBoundingBox().inflate(WATER_SWIM_REACH)")
                        && compatHelper.contains("ItemPhysicServer.fluid.set(fluid)")
                        && compatHelper.contains("setDeltaMovement"),
                "The ItemPhysic helper must detect nearby effective water and feed ItemPhysic's floating state.");
        assertTrue(commonPhysicMixin.contains("traben.flowing_fluids.forge.compat.itemphysic.ItemPhysicFluidCompat")
                        && serverMixin.contains("traben.flowing_fluids.forge.compat.itemphysic.ItemPhysicFluidCompat"),
                "ItemPhysic helper code must live outside the owned mixin package so world ticks can load it directly.");
        assertTrue(commonPhysicMixin.contains("m_6425_")
                        && serverMixin.contains("m_6425_"),
                "ItemPhysic production jars call the obfuscated Forge Level#getFluidState name.");
        assertTrue(commonPhysicMixin.contains("require = 0")
                        && serverMixin.contains("require = 0"),
                "ItemPhysic compatibility redirects should never hard-crash mod loading if ItemPhysic changes.");
        assertTrue(forgeMixins.contains("itemphysic.MixinCommonPhysic")
                        && forgeMixins.contains("itemphysic.MixinItemPhysicServer"),
                "The ItemPhysic compatibility mixins must be listed in the Forge mixin config.");
        assertTrue(plugin.contains("ITEMPHYSIC_MIXIN_PACKAGE")
                        && plugin.contains("isModLoadedDuringMixinSetup(\"itemphysic\")"),
                "ItemPhysic mixins should only apply when ItemPhysic is actually loaded.");
    }

    private static Path sourcePath(String path) {
        Path fromForgeProject = Path.of(path);
        if (Files.exists(fromForgeProject)) {
            return fromForgeProject;
        }
        return Path.of("forge", path);
    }
}
