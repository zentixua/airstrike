package ua.zentix.airstrike.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import ua.zentix.airstrike.registry.ModDataComponents;
import ua.zentix.airstrike.strike.Munitions;

import java.util.List;

/**
 * Боеприпас: пуск с пульта снимает его из инвентаря ({@link Munitions}); какой у оружия — его паспорт
 * ({@code WeaponSpec.munition}). У пакета (несколько снарядов в предмете, «Град») начатый пакет показывает полосой,
 * сколько в нём осталось.
 */
public class MunitionItem extends Item {
    private static final int BAR_COLOR = 0xE0A030;

    public MunitionItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(getDescriptionId() + ".tip").withStyle(ChatFormatting.GRAY));
        int perItem = Munitions.perItem(this);
        if (perItem > 1) {
            tooltip.add(Component.translatable("item.airstrike.munition.rounds", Munitions.left(stack, perItem), perItem).withStyle(ChatFormatting.GOLD));
        }
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return stack.has(ModDataComponents.ROUNDS.get());
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int perItem = Munitions.perItem(this);
        return Math.round(13f * Munitions.left(stack, perItem) / perItem);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return BAR_COLOR;
    }
}
