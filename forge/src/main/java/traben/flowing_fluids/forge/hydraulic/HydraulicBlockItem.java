package traben.flowing_fluids.forge.hydraulic;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class HydraulicBlockItem extends BlockItem {
    private static final int MAX_TOOLTIP_LINES = 4;

    private final String tooltipPrefix;

    public HydraulicBlockItem(Block block, Item.Properties properties, String tooltipPrefix) {
        super(block, properties);
        this.tooltipPrefix = tooltipPrefix;
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
