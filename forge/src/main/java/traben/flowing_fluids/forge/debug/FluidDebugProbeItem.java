package traben.flowing_fluids.forge.debug;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class FluidDebugProbeItem extends Item {
    private static final int MAX_TOOLTIP_LINES = 3;
    private final String tooltipPrefix;

    public FluidDebugProbeItem(Properties properties, String tooltipPrefix) {
        super(properties);
        this.tooltipPrefix = tooltipPrefix;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level) || context.getPlayer() == null) {
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
        }

        ForgeDebugCommands.sendProbeReport(level, context.getClickedPos(), message ->
                context.getPlayer().sendSystemMessage(message));
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        for (int line = 1; line <= MAX_TOOLTIP_LINES; line++) {
            String key = tooltipPrefix + "." + line;
            MutableComponent translated = Component.translatable(key);
            if (key.equals(translated.getString())) {
                break;
            }
            tooltip.add(translated.withStyle(ChatFormatting.GRAY));
        }
    }
}
