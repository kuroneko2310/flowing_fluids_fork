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
        String forgeMixins = Files.readString(sourcePath("src/main/resources/flowing_fluids_forge.mixins.json"));
        String plugin = Files.readString(sourcePath("src/main/java/traben/flowing_fluids/forge/mixin/FFPluginForge.java"));

        assertTrue(commonPhysicMixin.contains("FFFluidUtils.getEffectiveFluidState"),
                "ItemPhysic's buoyancy fluid probe should see partial and virtual Flowing Fluids water.");
        assertTrue(serverMixin.contains("FFFluidUtils.getEffectiveFluidState"),
                "ItemPhysic's fluid-height and pushing scan should use the same effective water state.");
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
