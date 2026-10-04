package ua.zentix.airstrike.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Зенитная ракета — запас ЗРК ({@link ua.zentix.airstrike.defense.SamBlock}): кладётся рукой (ПКМ по ЗРК), воронкой
 * или воронкой Create (способность предметов блок-сущности). Сама по себе ничего не делает — подсказка говорит куда.
 */
public class InterceptorItem extends Item {
    public InterceptorItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.airstrike.interceptor.tip").withStyle(ChatFormatting.GRAY));
    }
}
