package ua.zentix.airstrike.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Счётчик Гейгера: пока в руке, клиент щёлкает с частотой по мощности дозы и показывает её на экране
 * (client.nuclear.Geiger). Мощность считает сервер и присылает пакетом {@code Radiation}.
 */
public class GeigerCounterItem extends Item {
    public GeigerCounterItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.airstrike.geiger_counter.tip").withStyle(ChatFormatting.GRAY));
    }
}
