package ua.zentix.airstrike.launcher;

import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import ua.zentix.airstrike.registry.ModDataComponents;
import ua.zentix.airstrike.strike.ServerActions;
import ua.zentix.airstrike.target.Sides;

import java.util.ArrayList;
import java.util.List;

/**
 * Пульт и стационарные пусковые: ПКМ пультом по пусковой привязывает её к пульту (компонент
 * {@link ModDataComponents#LAUNCHERS} — места пусковых), повторный — отвязывает. Пока у пульта есть привязанные,
 * его «Огонь» не пускает сам, а ставит им задачу — ту же цель, что выбрана прицелом или на карте, тем же оружием,
 * числом, разбросом и маршрутом ({@link Mission}). Привязать можно свою пусковую или пусковую своей команды
 * ({@link Sides}), ничью (поставленную командой) — только оператору.
 */
public final class LauncherLinks {
    /** Больше пусковых к одному пульту не привязать. */
    public static final int MAX = 16;

    private LauncherLinks() {}

    /** Привязанные к пульту {@code designator} пусковые. */
    public static List<GlobalPos> of(ItemStack designator) {
        return designator.getOrDefault(ModDataComponents.LAUNCHERS.get(), List.of());
    }

    /** Игрок может командовать пусковой: ему можно пульт, и пусковая его, его команды или (оператору) ничья. */
    public static boolean mayCommand(ServerPlayer player, FixedLauncherBlockEntity be) {
        if (!ServerActions.mayUse(player)) return false;
        return be.owner() == null ? player.hasPermissions(2) : Sides.friendly(player.server, be.owner(), player.getUUID());
    }

    /** Привязать пусковую {@code be} к пульту {@code designator} или отвязать; строка — что вышло. */
    public static Component toggle(ServerPlayer player, ItemStack designator, FixedLauncherBlockEntity be) {
        GlobalPos at = GlobalPos.of(player.level().dimension(), be.getBlockPos());
        List<GlobalPos> links = new ArrayList<>(of(designator));
        if (links.remove(at)) {
            set(designator, links);
            return Component.translatable("airstrike.fixed_launcher.unlinked", links.size()).withStyle(ChatFormatting.GRAY);
        }
        if (!mayCommand(player, be)) return Component.translatable("airstrike.fixed_launcher.not_yours").withStyle(ChatFormatting.RED);
        if (links.size() >= MAX) return Component.translatable("airstrike.fixed_launcher.links_full", MAX).withStyle(ChatFormatting.RED);
        links.add(at);
        set(designator, links);
        return Component.translatable("airstrike.fixed_launcher.linked", links.size()).withStyle(ChatFormatting.GOLD);
    }

    private static void set(ItemStack designator, List<GlobalPos> links) {
        if (links.isEmpty()) designator.remove(ModDataComponents.LAUNCHERS.get());
        else designator.set(ModDataComponents.LAUNCHERS.get(), List.copyOf(links));
    }
}
