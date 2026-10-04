package ua.zentix.airstrike.strike;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.net.C2S;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.registry.ModSounds;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ядерный удар игрока, который не оператор ({@code nuclear.ops_only = false}; приказ по правилам игрока — {@code rules}
 * у {@link ServerActions#strike}):
 * <ul>
 * <li><b>второй ключ</b>: оплаченный приказ ждёт, пока другой игрок не дальше {@code second_key_distance} блоков от
 *   запускающего нажмёт свою клавишу «Второй ключ» ({@link C2S.NuclearKey}) за {@code second_key_window} секунд. Тем,
 *   кто рядом, — строка: кто, чем, куда и какая клавиша. Не дождались — пуска нет, оплаченное возвращается. Один в сети —
 *   ключ не нужен. Оператор, хост, консоль и командный блок — без ключа ({@link #trusted});
 * <li><b>тревога не меньше 90 с</b> ({@link #MIN_WARNING}): МБР летит не меньше 90 с (её пуск — тревога и отсчёт у цели),
 *   носитель (крылатая ракета, B-2) стартует через 90 с после тревоги у цели — сирены и строки игрокам в радиусе тревоги;
 *   перенацелить ядерный снаряд из камеры не оператор не может;
 * <li><b>пуск обнаружен</b>: всем игрокам — строка «Обнаружен пуск МБР из района X, Z» (клетка {@link #AREA_CELL} блоков)
 *   и сирена воздушной тревоги.
 * </ul>
 * Приказы не сохраняются (живут секунды): их отменяют с возвратом ядерный отбой (оператор, хост), остановка сервера и
 * выход запускающего до второго ключа. Сам запускающий ядерный удар не отменяет: ядерный отбой — только у оператора и хоста.
 * Свои у каждого мира (в {@link StrikeWorld}); игроки в сети — параметром: {@code FakePlayer} GameTest в списке игроков нет.
 */
public final class NuclearKeys {
    /** Тревога у цели не меньше стольких тиков до удара (90 с). */
    public static final int MIN_WARNING = 90 * 20;
    /** Клетка района пуска в строке «Обнаружен пуск», блоков. */
    public static final int AREA_CELL = 256;
    /** Клавиша «Второй ключ» (клиент, {@code client.Keys}); в строках игрокам клиент рисует её назначение. */
    public static final String KEY = "key.airstrike.second_key";
    /** Как часто звать второй ключ тех, кто подошёл к запускающему уже после приказа, тиков. */
    private static final int PROMPT_EVERY = 20;

    private final List<Order> orders = new ArrayList<>();

    /** Для {@link StrikeWorld}: свои у каждого мира, не сохраняются. */
    NuclearKeys() {}

    public static NuclearKeys get(ServerLevel level) {
        return StrikeWorld.get(level).keys();
    }

    /** Приказ, который ждёт второго ключа или, у носителя, конца тревоги перед пуском. */
    private static final class Order {
        final ServerLevel level;
        final UUID ownerId;
        final String name;
        final Loadout loadout;
        final ServerActions.Aim aim;
        final Waypoints via;
        @Nullable
        final Munitions.Bill paid;
        final long created;
        final long deadline;
        /** Кого уже звали повернуть ключ. */
        final Set<UUID> prompted = new HashSet<>();
        /** Последний известный объект запускающего (после возрождения — другой, ищется по UUID среди игроков в сети). */
        ServerPlayer owner;
        Vec3 lastPos;
        float lastYaw;
        /** Тик пуска носителя после тревоги; -1 — ждёт второго ключа. */
        long launchAt = -1;
        @Nullable
        String confirmer;

        Order(ServerPlayer owner, Loadout loadout, ServerActions.Aim aim, Waypoints via, @Nullable Munitions.Bill paid, long deadline) {
            this.level = owner.serverLevel();
            this.ownerId = owner.getUUID();
            this.name = owner.getGameProfile().getName();
            this.loadout = loadout;
            this.aim = aim;
            this.via = via;
            this.paid = paid;
            this.created = level.getGameTime();
            this.deadline = deadline;
            this.owner = owner;
            this.lastPos = owner.position();
            this.lastYaw = owner.getYRot();
        }

        boolean icbm() {
            return loadout.weapon().spec().launch() == WeaponSpec.Launch.ICBM;
        }

        /** Запускающий в игре: среди игроков в сети по UUID, иначе прежний объект, если он ещё в мире (GameTest). */
        @Nullable
        ServerPlayer owner(Collection<? extends ServerPlayer> online) {
            ServerPlayer found = null;
            for (ServerPlayer p : online) {
                if (p.getUUID().equals(ownerId)) found = p;
            }
            if (found == null && !owner.isRemoved() && !owner.hasDisconnected()) found = owner;
            if (found != null) {
                owner = found;
                lastPos = found.position();
                lastYaw = found.getYRot();
            }
            return found;
        }
    }

    /** Без второго ключа и тревоги заранее: оператор и хост одиночной или открытой в сеть игры. Им же — ядерный отбой. */
    public static boolean trusted(ServerPlayer player) {
        return player.hasPermissions(2) || player.server.isSingleplayerOwner(player.getGameProfile());
    }

    /**
     * Ядерный приказ не оператора (права и оплата уже проверены): другие в сети — ждать второго ключа, один — сразу,
     * но с тревогой не меньше 90 с.
     *
     * @param online игроки в сети (сервер — его список; GameTest — свои)
     * @param paid   что запускающий заплатил ({@link Munitions}); null — не платил (творческий режим)
     * @return приказ принят: пуск состоялся, ждёт второго ключа или идёт тревога перед пуском носителя
     */
    public static boolean order(ServerPlayer owner, Collection<? extends ServerPlayer> online, Loadout loadout, ServerActions.Aim aim, Waypoints via,
                                @Nullable Munitions.Bill paid) {
        ServerLevel level = owner.serverLevel();
        NuclearKeys keys = get(level);
        Order o = new Order(owner, loadout, aim, via, paid, level.getGameTime() + window());
        boolean alone = true;
        for (ServerPlayer p : online) {
            if (!p.getUUID().equals(owner.getUUID())) alone = false;
        }
        if (alone) return keys.arm(o, online);
        keys.orders.add(o);
        owner.sendSystemMessage(Component.translatable("airstrike.nuke.key.wait", what(loadout), distance(), Component.keybind(KEY),
                window() / 20).withStyle(ChatFormatting.GOLD));
        keys.prompt(o, online, owner);
        Airstrike.LOG.info("Ядерный пуск ждёт второго ключа {} с: {} по {} {} {} — {}", window() / 20, loadout.weapon().getSerializedName(),
                Mth.floor(aim.point().x), Mth.floor(aim.point().y), Mth.floor(aim.point().z), o.name);
        return true;
    }

    /** Пакет клавиши «Второй ключ». */
    public static void onKey(C2S.NuclearKey p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) confirm(player, player.server.getPlayerList().getPlayers());
    }

    /**
     * {@code by} повернул второй ключ: самый ранний приказ запускающего не дальше {@code second_key_distance} от него
     * (свой приказ подтвердить нельзя).
     *
     * @return приказ подтверждён
     */
    public static boolean confirm(ServerPlayer by, Collection<? extends ServerPlayer> online) {
        double d = distance();
        Order best = null;
        NuclearKeys where = null;
        for (ServerLevel level : by.server.getAllLevels()) {
            if (!level.hasData(ModAttachments.STRIKE_WORLD)) continue;
            NuclearKeys keys = get(level);
            for (Order o : keys.orders) {
                if (o.launchAt >= 0 || o.ownerId.equals(by.getUUID())) continue;
                ServerPlayer owner = o.owner(online);
                if (owner == null || owner.level() != by.level() || owner.distanceToSqr(by) > d * d) continue;
                if (best == null || o.created < best.created) {
                    best = o;
                    where = keys;
                }
            }
        }
        if (best == null) {
            by.displayClientMessage(Component.translatable("airstrike.nuke.key.none", distance()).withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        best.confirmer = by.getGameProfile().getName();
        Airstrike.LOG.info("Второй ключ: {} подтвердил ядерный пуск {} — {}", best.confirmer, best.loadout.weapon().getSerializedName(), best.name);
        Component done = Component.translatable("airstrike.nuke.key.confirmed", by.getDisplayName(), what(best.loadout)).withStyle(ChatFormatting.GOLD);
        by.sendSystemMessage(done);
        ServerPlayer owner = best.owner(online);
        if (owner != null) owner.sendSystemMessage(done);
        where.arm(best, online);
        return true;
    }

    /**
     * Ключи повёрнуты (или запускающий один в сети): МБР — сразу, с полётом не меньше 90 с; носитель — тревога у цели
     * сейчас, пуск через 90 с.
     */
    private boolean arm(Order o, Collection<? extends ServerPlayer> online) {
        if (o.icbm()) {
            orders.remove(o);
            return launch(o, online).ok();
        }
        o.launchAt = o.level.getGameTime() + MIN_WARNING;
        if (!orders.contains(o)) orders.add(o);
        alarm(o, online);
        ServerPlayer owner = o.owner(online);
        if (owner != null) {
            owner.sendSystemMessage(Component.translatable("airstrike.nuke.alarm.armed", MIN_WARNING / 20).withStyle(ChatFormatting.GOLD));
        }
        Airstrike.LOG.info("Ядерная тревога у цели {} {} {}: {} — пуск через {} с — {}", Mth.floor(o.aim.point().x), Mth.floor(o.aim.point().y),
                Mth.floor(o.aim.point().z), o.loadout.weapon().getSerializedName(), MIN_WARNING / 20, o.name);
        return true;
    }

    /** Тревога у цели перед пуском носителя: сирена (как у снаряда на подлёте) и строка всем в радиусе ядерной тревоги. */
    private static void alarm(Order o, Collection<? extends ServerPlayer> online) {
        Vec3 at = o.aim.point();
        StrikeService.siren(o.level, o.loadout.weapon(), at);
        double r = AirstrikeConfig.SERVER.nukeWarningRadius.get();
        Component line = Component.translatable("airstrike.nuke.alarm.carrier", what(o.loadout), cell(at.x), cell(at.z), MIN_WARNING / 20)
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        for (ServerPlayer p : online) {
            if (p.level() == o.level && p.position().distanceToSqr(at) <= r * r) p.sendSystemMessage(line);
        }
    }

    /** Пуск приказа: строка в лог, пуск, итог запускающему; не удался — возврат, удался — всем «обнаружен пуск». */
    private static StrikeService.Result launch(Order o, Collection<? extends ServerPlayer> online) {
        ServerPlayer owner = o.owner(online);
        Loadout l = o.loadout;
        ServerActions.Aim aim = o.aim;
        String who = o.name + (o.confirmer == null ? "" : " (второй ключ — " + o.confirmer + ")");
        StrikeService.log(o.level, who, l.weapon(), 1, 0, aim.target(), aim.point(), o.via);
        StrikeService.Result r;
        String why = "ядерный пуск не удался";
        try {
            if (o.icbm()) {
                r = StrikeService.launchIcbm(o.level, aim.target(), aim.point(), l.nuke(), owner, flightTicks());
            } else if (owner != null) {
                r = StrikeService.launchBy(o.level, l.weapon(), aim.target(), aim.point(), owner.getYRot(), owner, true, l.nuke(), o.via);
            } else {
                // носитель после тревоги, запускающий вышел: пуск, как у залпа без владельца в сети, — издалека
                r = StrikeService.launch(o.level, l.weapon(), aim.target(), aim.point(), o.lastYaw, o.ownerId, true, l.nuke(), o.via);
            }
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Ядерный пуск {} упал с ошибкой — {}", l.weapon().getSerializedName(), who, e);
            r = StrikeService.Result.FAILED;
            why = "ядерный пуск упал с ошибкой";
        }
        if (owner != null) {
            if (r.ok()) StrikeService.confirm(owner, l.weapon(), r.eta());
            else owner.displayClientMessage(Component.translatable("airstrike.launch_failed").withStyle(ChatFormatting.RED), true);
        }
        if (!r.ok()) {
            if (o.paid != null) Munitions.refund(owner != null ? List.of(owner) : online, o.ownerId, o.paid, why);
        } else {
            detected(o, online);
        }
        return r;
    }

    /** МБР не оператора летит не меньше 90 с: столько длится тревога у цели. */
    static int flightTicks() {
        return Math.max(AirstrikeConfig.SERVER.nukeFlightTime.get(), MIN_WARNING);
    }

    /**
     * Пуск обнаружен: всем в сети — строка с районом пуска (клетка {@link #AREA_CELL}) и сирена воздушной тревоги; у кого
     * пуск МБР уже поднял ядерную тревогу (тот же мир, радиус тревоги от цели), — без второй сирены.
     */
    private static void detected(Order o, Collection<? extends ServerPlayer> online) {
        Vec3 from = o.lastPos;
        int x = cell(from.x), z = cell(from.z);
        Component line = (o.icbm() ? Component.translatable("airstrike.nuke.launch_detected", x, z)
                : Component.translatable("airstrike.nuke.launch_detected.carrier", item(o.loadout).getDescription(), x, z)).withStyle(ChatFormatting.RED);
        double r = AirstrikeConfig.SERVER.nukeWarningRadius.get();
        List<ServerPlayer> all = new ArrayList<>(online);
        ServerPlayer owner = o.owner(online);
        if (owner != null && !all.contains(owner)) all.add(owner);
        for (ServerPlayer p : all) {
            p.sendSystemMessage(line);
            boolean alarmed = o.icbm() && p.level() == o.level && p.position().distanceToSqr(o.aim.point()) <= r * r;
            if (!alarmed) p.playNotifySound(ModSounds.SIREN.get(), SoundSource.MASTER, 1.0f, 1.0f);
        }
        Airstrike.LOG.info("Ядерный пуск обнаружен: {} из района {} {} — {}", o.loadout.weapon().getSerializedName(), x, z, o.name);
    }

    /** Координата района: ближайшая кратная {@link #AREA_CELL}. */
    public static int cell(double v) {
        return (int) (Math.round(v / AREA_CELL) * AREA_CELL);
    }

    /** Позвать второй ключ тех, кто рядом с запускающим и ещё не звался. */
    private void prompt(Order o, Collection<? extends ServerPlayer> online, ServerPlayer owner) {
        double d = distance();
        long left = Math.max(1, (o.deadline - o.level.getGameTime() + 19) / 20);
        Vec3 at = o.aim.point();
        for (ServerPlayer p : online) {
            if (p.getUUID().equals(o.ownerId) || p.level() != owner.level() || p.distanceToSqr(owner) > d * d || !o.prompted.add(p.getUUID())) continue;
            p.sendSystemMessage(Component.translatable("airstrike.nuke.key.prompt", owner.getDisplayName(), what(o.loadout), Mth.floor(at.x), Mth.floor(at.z),
                    Component.keybind(KEY), left).withStyle(ChatFormatting.GOLD));
            p.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 1.0f, 1.2f);
        }
    }

    /** Конец тика мира: срок второго ключа, пуск носителя после тревоги, зов подошедших. */
    void tick(ServerLevel level, Collection<? extends ServerPlayer> online) {
        if (orders.isEmpty()) return;
        long now = level.getGameTime();
        for (Order o : List.copyOf(orders)) {
            if (o.launchAt >= 0) {
                if (now < o.launchAt) continue;
                orders.remove(o);
                launch(o, online);
            } else if (now >= o.deadline) {
                orders.remove(o);
                ServerPlayer owner = o.owner(online);
                if (owner != null) {
                    owner.sendSystemMessage(Component.translatable("airstrike.nuke.key.expired", window() / 20).withStyle(ChatFormatting.RED));
                }
                if (o.paid != null) Munitions.refund(owner != null ? List.of(owner) : online, o.ownerId, o.paid, "второй ключ не получен");
                Airstrike.LOG.info("Второй ключ не получен за {} с: ядерный пуск {} отменён — {}", window() / 20, o.loadout.weapon().getSerializedName(), o.name);
            } else if ((now - o.created) % PROMPT_EVERY == 0) {
                ServerPlayer owner = o.owner(online);
                if (owner != null) prompt(o, online, owner);
            }
        }
    }

    /** Сколько приказов ждёт второго ключа или пуска после тревоги. */
    public int pending() {
        return orders.size();
    }

    /**
     * Отменить все ждущие ядерные приказы с возвратом владельцам в сети: ядерный отбой оператора или хоста, остановка сервера.
     *
     * @param notice строка владельцу, или null
     * @return сколько отменено
     */
    public static int cancelAll(MinecraftServer server, Collection<? extends ServerPlayer> online, @Nullable Component notice, String why) {
        int n = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.hasData(ModAttachments.STRIKE_WORLD)) continue;
            NuclearKeys keys = get(level);
            for (Order o : List.copyOf(keys.orders)) {
                keys.orders.remove(o);
                n++;
                ServerPlayer owner = o.owner(online);
                if (owner != null && notice != null) owner.sendSystemMessage(notice);
                if (o.paid != null) Munitions.refund(owner != null ? List.of(owner) : online, o.ownerId, o.paid, why);
                Airstrike.LOG.info("Ядерный пуск {} отменён ({}) — {}", o.loadout.weapon().getSerializedName(), why, o.name);
            }
        }
        return n;
    }

    /**
     * Запускающий вышел до второго ключа: приказ отменён, оплаченное — ему (инвентарь сохраняется после события). Носитель,
     * у цели которого уже тревога, стартует и без него.
     */
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        for (ServerLevel level : player.server.getAllLevels()) {
            if (!level.hasData(ModAttachments.STRIKE_WORLD)) continue;
            NuclearKeys keys = get(level);
            for (Order o : List.copyOf(keys.orders)) {
                if (o.launchAt >= 0 || !o.ownerId.equals(player.getUUID())) continue;
                keys.orders.remove(o);
                if (o.paid != null) Munitions.refund(List.of(player), o.ownerId, o.paid, "запускающий вышел до второго ключа");
                Airstrike.LOG.info("Ядерный пуск {} отменён: {} вышел до второго ключа", o.loadout.weapon().getSerializedName(), o.name);
            }
        }
    }

    /** Чем бьёт приказ — для строк: «МБР, 15 кт», «Крылатая ракета с ядерной БЧ, 15 кт». */
    private static Component what(Loadout l) {
        return Component.translatable(l.weapon().spec().warhead().always() ? "airstrike.nuke.key.what" : "airstrike.nuke.key.what.carrier",
                item(l).getDescription(), l.nuke().yieldKt());
    }

    private static Item item(Loadout l) {
        return l.weapon().spec().munition().item().get();
    }

    private static int window() {
        return AirstrikeConfig.SERVER.nukeSecondKeyWindow.get() * 20;
    }

    private static int distance() {
        return AirstrikeConfig.SERVER.nukeSecondKeyDistance.get();
    }
}
