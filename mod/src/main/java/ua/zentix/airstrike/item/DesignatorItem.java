package ua.zentix.airstrike.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import ua.zentix.airstrike.launcher.LauncherLinks;
import ua.zentix.airstrike.net.ClientHooks;
import ua.zentix.airstrike.registry.ModDataComponents;
import ua.zentix.airstrike.strike.Loadout;

import java.util.List;

/**
 * Пульт наведения. ПКМ (держать) — бинокль; колесо — оружие; ЛКМ — пуск по точке под прицелом;
 * Shift+ПКМ — экран пульта (открывает клиент через ClientHooks.openRemote); ПКМ по стационарной пусковой — привязать
 * её ({@link LauncherLinks}): тогда «Огонь» ставит задачу привязанным. Пуск всегда проверяет сервер.
 */
public class DesignatorItem extends Item {
    public DesignatorItem(Properties properties) {
        super(properties.stacksTo(1).component(ModDataComponents.LOADOUT.get(), Loadout.DEFAULT));
    }

    public static Loadout loadout(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.LOADOUT.get(), Loadout.DEFAULT);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            // экран пульта — клиентский; пуск с него всё равно проверяет сервер
            if (level.isClientSide) ClientHooks.get().openRemote();
            return InteractionResultHolder.success(stack);
        }
        player.playSound(SoundEvents.SPYGLASS_USE, 1.0f, 1.0f);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.SPYGLASS;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        entity.playSound(SoundEvents.SPYGLASS_STOP_USING, 1.0f, 1.0f);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        Loadout l = loadout(stack);
        tooltip.add(Component.translatable("item.airstrike.strike_designator.loadout", l.weapon().displayName(), l.count(), l.spread())
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.airstrike.strike_designator.hint.scope").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.airstrike.strike_designator.hint.fire").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.airstrike.strike_designator.hint.menu").withStyle(ChatFormatting.GRAY));
        int linked = LauncherLinks.of(stack).size();
        tooltip.add(linked > 0 ? Component.translatable("item.airstrike.strike_designator.launchers", linked).withStyle(ChatFormatting.GOLD)
                : Component.translatable("item.airstrike.strike_designator.hint.link").withStyle(ChatFormatting.GRAY));
    }
}
