package traben.flowing_fluids.forge.mixin;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import traben.flowing_fluids.FlowingFluids;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class FFPluginForge implements IMixinConfigPlugin {

    private static final String CREATE_MIXIN_PACKAGE = "traben.flowing_fluids.forge.mixin.create.";
    private static final String MEKANISM_MIXIN_PACKAGE = "traben.flowing_fluids.forge.mixin.mekanism.";
    private static final String SODIUM_MIXIN_PACKAGE = "traben.flowing_fluids.forge.mixin.sodium.";

    @Override
    public void onLoad(final String s) {

        // crash if the mod triggered any conditional issues
        // e.g. wrong create version loaded
        FMLLoader.getLoadingModList().getErrors().forEach(
                error -> error.getAllData().forEach(
                        data -> {
                            // only currently fulfilled if create is present and the wrong version
                            if (data.getModInfo().getModId().equals(FlowingFluids.MOD_ID) && error.getMessage().startsWith("failure to validate mod list"))
                                // has to be an error to trip up forge loading, if it's just an exception it will be caught and loading will continue,
                                // only to then fail due to access transformer failure caused by this, which leaves end users wonderfully confused as
                                // to why their game crashes as that crash log won't really tell you what happened
                                throw new VerifyError("[Flowing Fluids] [ERROR]: flowing fluids encountered an error during loading. This is most likely due to a wrong Create Mod version being present");
                        }
                )
        );

        System.out.println("[Flowing Fluids] Forge: init MixinExtras");
        MixinExtrasBootstrap.init();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String s, final String s1) {
        if (s1 != null && s1.startsWith(CREATE_MIXIN_PACKAGE)) {
            return isModLoadedDuringMixinSetup("create");
        }
        if (s1 != null && s1.startsWith(MEKANISM_MIXIN_PACKAGE)) {
            return isModLoadedDuringMixinSetup("mekanism");
        }
        if (s1 != null && s1.startsWith(SODIUM_MIXIN_PACKAGE)) {
            return isAnyModLoadedDuringMixinSetup("embeddium", "rubidium", "sodium");
        }
        return true;
    }

    private static boolean isAnyModLoadedDuringMixinSetup(final String... modIds) {
        for (String modId : modIds) {
            if (isModLoadedDuringMixinSetup(modId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isModLoadedDuringMixinSetup(final String modId) {
        try {
            LoadingModList loadingModList = FMLLoader.getLoadingModList();
            if (loadingModList == null) {
                return false;
            }
            for (ModInfo mod : loadingModList.getMods()) {
                if (modId.equals(mod.getModId())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    public void acceptTargets(final Set<String> set, final Set<String> set1) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String s, final ClassNode classNode, final String s1, final IMixinInfo iMixinInfo) {

    }

    @Override
    public void postApply(final String s, final ClassNode classNode, final String s1, final IMixinInfo iMixinInfo) {

    }
}
