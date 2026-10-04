package ua.zentix.airstrike.strike;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.registry.ModDataComponents;
import ua.zentix.airstrike.registry.ModItems;

import java.util.Collection;
import java.util.UUID;

/**
 * Боеприпасы из инвентаря. Приказ игрока по его правилам ({@code bound} у {@link ServerActions#strike}: пульт у всех,
 * команда не оператора) оплачивается сразу и целиком — одиночный пуск и весь залп: по предмету боеприпаса на снаряд
 * (паспорт, {@link WeaponSpec#munition}; пакет «Града» — по снаряду из пакета), ядерная БЧ на носителе — ещё предметом
 * {@link #warhead()}. Не хватает — пуска нет, строка над хотбаром: сколько нужно и сколько есть; приказ не урезается.
 * Неудачный пуск и невыпущенные снаряды отменённого залпа возвращаются владельцу, если он в сети (полный инвентарь —
 * к ногам; погибшему, пока он не возродился, — на место гибели); не в сети — не возвращаются (строка в лог).
 * Творческий режим, команда оператора, консоль и командный блок не платят.
 */
public final class Munitions {
    private Munitions() {}

    /** Ядерная боевая часть на носитель — крылатую ракету или бомбу B-2: один предмет на всех носителей. */
    public static Item warhead() {
        return ModItems.NUCLEAR_WARHEAD.get();
    }

    /**
     * Счёт приказа.
     *
     * @param weapon   чем
     * @param shots    сколько снарядов (у пакета — снарядов, а не пакетов)
     * @param warheads сколько ядерных БЧ на носители
     */
    public record Bill(WeaponType weapon, int shots, int warheads) {
        /** Счёт приказа {@code l}: каждый снаряд — боеприпас, ядерная БЧ на носителе — ещё по предмету. */
        public static Bill of(Loadout l) {
            boolean carrier = l.nuclear() && !l.weapon().spec().warhead().always();
            return new Bill(l.weapon(), l.count(), carrier ? l.count() : 0);
        }
    }

    /** Сколько снарядов в одном предмете {@code item}: у пакета — из паспорта, у остального — один. */
    public static int perItem(Item item) {
        for (WeaponType w : WeaponType.values()) {
            WeaponSpec.Munition m = w.spec().munition();
            if (m.item().get() == item) return m.rounds();
        }
        return 1;
    }

    /** Сколько снарядов осталось в предмете стопки {@code s}: у начатого пакета — из компонента, у целого — полный. */
    public static int left(ItemStack s, int perItem) {
        Integer n = s.get(ModDataComponents.ROUNDS.get());
        return n == null ? perItem : Math.clamp(n, 1, perItem);
    }

    /** Начатый пакет с {@code left} снарядами из {@code perItem} (полный — без компонента: складывается с целыми). */
    public static ItemStack pack(Item item, int perItem, int left) {
        ItemStack s = new ItemStack(item);
        setLeft(s, perItem, left);
        return s;
    }

    private static void setLeft(ItemStack s, int perItem, int left) {
        if (left >= perItem) s.remove(ModDataComponents.ROUNDS.get());
        else s.set(ModDataComponents.ROUNDS.get(), left);
    }

    /** Сколько снарядов {@code item} в инвентаре (у пакетов — в сумме по пакетам). */
    public static int held(Inventory inv, Item item, int perItem) {
        long n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) n += (long) s.getCount() * left(s, perItem);
        }
        return (int) Math.min(n, Integer.MAX_VALUE);
    }

    /**
     * Оплатить счёт из инвентаря игрока — всё или ничего: не хватает боеприпаса или ядерной БЧ — ничего не снято,
     * игроку строка над хотбаром, что нужно и сколько есть.
     *
     * @return true, если оплачено
     */
    public static boolean pay(ServerPlayer player, Bill bill) {
        Inventory inv = player.getInventory();
        WeaponSpec.Munition m = bill.weapon().spec().munition();
        Item item = m.item().get();
        if (!enough(player, item, m.rounds(), bill.shots()) || !enough(player, warhead(), 1, bill.warheads())) return false;
        take(inv, item, m.rounds(), bill.shots());
        take(inv, warhead(), 1, bill.warheads());
        return true;
    }

    private static boolean enough(ServerPlayer player, Item item, int perItem, int need) {
        if (need <= 0) return true;
        int have = held(player.getInventory(), item, perItem);
        if (have >= need) return true;
        player.displayClientMessage((perItem > 1
                ? Component.translatable("airstrike.munitions.short.rounds", item.getDescription(), need, have, perItem)
                : Component.translatable("airstrike.munitions.short", item.getDescription(), need, have)).withStyle(ChatFormatting.RED), true);
        return false;
    }

    /** Снять {@code rounds} снарядов: сперва из начатых пакетов (новых начатых не плодить), потом из целых. */
    private static void take(Inventory inv, Item item, int perItem, int rounds) {
        for (boolean opened : new boolean[]{true, false}) {
            for (int i = 0; i < inv.getContainerSize() && rounds > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.is(item) || s.has(ModDataComponents.ROUNDS.get()) != opened) continue;
                int left = left(s, perItem);
                while (rounds > 0 && !s.isEmpty()) {
                    if (rounds >= left) {
                        s.shrink(1);
                        rounds -= left;
                    } else if (s.getCount() == 1) {
                        setLeft(s, perItem, left - rounds);
                        rounds = 0;
                    } else {
                        // вскрыть один пакет из стопки целых: остаток — отдельным предметом
                        s.shrink(1);
                        inv.placeItemBackInInventory(pack(item, perItem, left - rounds));
                        rounds = 0;
                    }
                }
            }
        }
        if (rounds > 0) throw new IllegalStateException("снято меньше, чем проверено: не хватило " + rounds);
    }

    /** Вернуть оплаченное игроку: в инвентарь, полный — к ногам, погибшему — на место гибели; строка ему в чат. */
    private static void refund(ServerPlayer player, Bill bill) {
        WeaponSpec.Munition m = bill.weapon().spec().munition();
        give(player, m.item().get(), m.rounds(), bill.shots());
        give(player, warhead(), 1, bill.warheads());
        if (!keeps(player)) player.sendSystemMessage(Component.translatable("airstrike.munitions.refunded.dead").withStyle(ChatFormatting.GRAY));
    }

    /**
     * Инвентарь игрока переживёт возрождение: жив, или правило {@code keepInventory}. Погибший на экране смерти — в списке
     * игроков, но его инвентарь при возрождении без {@code keepInventory} не переносится ({@code ServerPlayer.restoreFrom}):
     * положенное туда пропало бы.
     */
    private static boolean keeps(ServerPlayer player) {
        return player.isAlive() || player.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
    }

    /** Отдать стопку: в инвентарь (полный — к ногам) или, погибшему, — выбросить на месте гибели. */
    private static void put(ServerPlayer player, ItemStack s) {
        if (keeps(player)) player.getInventory().placeItemBackInInventory(s);
        // вокруг, как добыча при гибели; без ItemTossEvent — это не бросок игрока
        else player.drop(s, true, false);
    }

    /**
     * Вернуть оплаченное владельцу {@code owner}, если он среди {@code online} (игроки сервера; GameTest передаёт своих),
     * иначе — строка в лог: владельцу не в сети не возвращается.
     *
     * @param why за что возврат — для лога
     */
    public static void refund(Collection<? extends ServerPlayer> online, @Nullable UUID owner, Bill bill, String why) {
        if (owner == null || bill.shots() <= 0 && bill.warheads() <= 0) return;
        for (ServerPlayer p : online) {
            if (p.getUUID().equals(owner)) {
                refund(p, bill);
                Airstrike.LOG.info("Возврат боеприпасов ({}): {} ×{}{} — {}", why, bill.weapon().getSerializedName(), bill.shots(),
                        bill.warheads() > 0 ? ", ядерных БЧ ×" + bill.warheads() : "", p.getGameProfile().getName());
                return;
            }
        }
        Airstrike.LOG.info("Возврат боеприпасов ({}): {} ×{}{} не возвращены — владелец {} не в сети", why, bill.weapon().getSerializedName(),
                bill.shots(), bill.warheads() > 0 ? ", ядерных БЧ ×" + bill.warheads() : "", owner);
    }

    /** Отдать {@code rounds} снарядов: сперва дополнить начатые пакеты (отменённый залп возвращает пакет целым), затем предметами. */
    private static void give(ServerPlayer player, Item item, int perItem, int rounds) {
        if (rounds <= 0) return;
        player.sendSystemMessage((perItem > 1 ? Component.translatable("airstrike.munitions.refunded.rounds", item.getDescription(), rounds)
                : Component.translatable("airstrike.munitions.refunded", item.getDescription(), rounds)).withStyle(ChatFormatting.GRAY));
        Inventory inv = player.getInventory();
        if (perItem > 1 && keeps(player)) {
            for (int i = 0; i < inv.getContainerSize() && rounds > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.is(item) || s.getCount() != 1 || !s.has(ModDataComponents.ROUNDS.get())) continue;
                int left = left(s, perItem), add = Math.min(perItem - left, rounds);
                setLeft(s, perItem, left + add);
                rounds -= add;
            }
        }
        int whole = rounds / perItem;
        int max = new ItemStack(item).getMaxStackSize();
        while (whole > 0) {
            int n = Math.min(whole, max);
            put(player, new ItemStack(item, n));
            whole -= n;
        }
        if (rounds % perItem > 0) put(player, pack(item, perItem, rounds % perItem));
    }
}
