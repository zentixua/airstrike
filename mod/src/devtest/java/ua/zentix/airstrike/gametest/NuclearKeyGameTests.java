package ua.zentix.airstrike.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.IcbmEntity;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.gametest.MunitionGameTests.Listener;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.registry.ModItems;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.Munitions;
import ua.zentix.airstrike.strike.NuclearKeys;
import ua.zentix.airstrike.strike.ServerActions;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.Waypoints;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;

import java.util.List;
import java.util.UUID;

import static ua.zentix.airstrike.gametest.MunitionGameTests.args;
import static ua.zentix.airstrike.gametest.MunitionGameTests.give;
import static ua.zentix.airstrike.gametest.MunitionGameTests.held;

/**
 * Ядерный удар не оператора ({@link NuclearKeys}, {@code nuclear.ops_only = false}): второй ключ, когда в сети есть
 * другие, — от игрока рядом, не издалека и не от себя; срок ключа вышел — пуска нет, оплаченное вернулось; один в сети —
 * без ключа; МБР летит не меньше 90 с (команда оператора — по настройке); носитель стартует через 90 с после тревоги
 * у цели, и снять его может только ядерный отбой; пуск видят все. Игроки — {@code FakePlayer} NeoForge: в списке
 * игроков их нет, поэтому «в сети» передаётся явно. Каждому тесту — своя партия: ядерный отбой и
 * {@link NuclearStrikes#clear} общие для мира.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NuclearKeyGameTests {
    /** Место запускающего на полосе (за ним, в 30 блоках, — площадка МБР) и цель далеко впереди. */
    private static final BlockPos LAUNCHER = new BlockPos(16, 4, 60);
    private static final BlockPos TARGET = new BlockPos(16, 3, 200);
    /** Время полёта МБР в настройке на время проверок — короче тревоги в 90 с. */
    private static final int SHORT_FLIGHT = 200;

    private NuclearKeyGameTests() {}

    /**
     * В сети есть другие: приказ оплачен и ждёт; звать — только тех, кто рядом. Ключ издалека и свой не считаются; ключ
     * игрока рядом пускает МБР с полётом не меньше 90 с (в настройке — 10 с), и всем в сети — «Обнаружен пуск МБР из
     * района X, Z» по клетке 256 блоков.
     */
    @GameTest(template = "runway", batch = "nuclear_keys", skyAccess = true)
    public static void secondKeyFromNearbyPlayerLaunches(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        setUp(h);
        Listener a = at(h, "keys_launcher", LAUNCHER);
        Listener near = at(h, "keys_near", LAUNCHER.east(3));
        Listener far = at(h, "keys_far", LAUNCHER.south(40));
        List<Listener> online = List.of(a, near, far);
        Item icbm = ModItems.ICBM.get();
        give(a, icbm, 1);
        Loadout l = nuke(WeaponType.NUKE, false);
        int scheduled = NuclearEvents.get(level).scheduled().size();

        h.assertTrue(order(a, online, l, h), "приказ не принят");
        h.assertValueEqual(held(a, icbm), 0, "МБР не оплачена при отдаче");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 1, "приказов ждёт ключа");
        h.assertValueEqual(StrikeWorld.active(level.getServer(), a.getUUID()), 1, "ждущий ключа приказ не в работе (max_active_per_player)");
        h.assertValueEqual(NuclearEvents.get(level).scheduled().size(), scheduled, "пуск без второго ключа");
        h.assertTrue(a.last("airstrike.nuke.key.wait") != null, "запускающему не сказано ждать ключа");
        h.assertTrue(near.last("airstrike.nuke.key.prompt") != null, "игрока рядом не позвали");
        h.assertTrue(far.last("airstrike.nuke.key.prompt") == null, "позвали игрока в 40 блоках");

        h.assertFalse(NuclearKeys.confirm(far, online), "ключ из 40 блоков принят");
        h.assertTrue(far.last("airstrike.nuke.key.none") != null, "дальнему не сказано, что подтверждать нечего");
        h.assertFalse(NuclearKeys.confirm(a, online), "запускающий повернул свой второй ключ");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 1, "приказ ушёл без ключа рядом");

        h.assertTrue(NuclearKeys.confirm(near, online), "ключ игрока рядом не принят");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 0, "приказ ждёт после ключа");
        NuclearEvents.ScheduledStrike s = mine(level, a.getUUID());
        h.assertTrue(s != null, "МБР не запущена после ключа");
        h.assertValueEqual(s.detonateTime() - s.launchTime(), (long) NuclearKeys.MIN_WARNING, "полёт МБР не оператора, тиков");
        h.assertTrue(a.last("airstrike.nuke.key.confirmed") != null && near.last("airstrike.nuke.key.confirmed") != null, "нет строки о ключе");
        List<Object> area = List.of(NuclearKeys.cell(a.getX()), NuclearKeys.cell(a.getZ()));
        for (Listener p : online) h.assertValueEqual(args(p.last("airstrike.nuke.launch_detected")), area, "строка «обнаружен пуск» у " + p.getGameProfile().getName());
        h.succeed();
    }

    /**
     * Один в сети (сервер GameTest без игроков): приказ с пульта идёт сразу, без ключа, но МБР летит 90 с, а не 10 с из
     * настройки. Команда оператора ({@code rules = false}) — без ключа и по настройке.
     */
    @GameTest(template = "runway", batch = "nuclear_keys_alone", skyAccess = true)
    public static void aloneNoKeyButFullWarning(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        setUp(h);
        h.assertTrue(level.getServer().getPlayerList().getPlayers().isEmpty(), "на сервере GameTest есть игроки");
        Listener a = at(h, "keys_alone", LAUNCHER);
        give(a, ModItems.ICBM.get(), 1);
        ServerActions.Aim aim = aim(h);
        h.assertTrue(ServerActions.strike(a, WeaponType.NUKE, 1, 0, aim, new Loadout.Nuke(15, true), Waypoints.NONE, null, true), "пуск одного в сети не принят");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 0, "один в сети ждёт ключа");
        NuclearEvents.ScheduledStrike s = mine(level, a.getUUID());
        h.assertTrue(s != null, "МБР одного в сети не запущена");
        h.assertValueEqual(s.detonateTime() - s.launchTime(), (long) NuclearKeys.MIN_WARNING, "полёт МБР не оператора, тиков");
        h.assertTrue(a.last("airstrike.nuke.launch_detected") != null, "нет строки «обнаружен пуск»");

        Listener op = at(h, "keys_op_command", LAUNCHER.east(8));
        h.assertTrue(ServerActions.strike(op, WeaponType.NUKE, 1, 0, aim, new Loadout.Nuke(15, true), Waypoints.NONE, null, false), "команда оператора не принята");
        NuclearEvents.ScheduledStrike o = mine(level, op.getUUID());
        h.assertTrue(o != null, "МБР команды оператора не запущена");
        h.assertValueEqual(o.detonateTime() - o.launchTime(), (long) SHORT_FLIGHT, "полёт МБР команды оператора, тиков");
        h.assertTrue(op.last("airstrike.nuke.key.wait") == null, "команда оператора ждёт ключа");
        h.succeed();
    }

    /** Второго ключа нет за срок (5 с): пуска нет, МБР вернулась, запускающему — строка. */
    @GameTest(template = "range", timeoutTicks = 300, batch = "nuclear_keys_expiry", skyAccess = true)
    public static void keyWindowExpiresAndRefunds(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        setUp(h);
        int window = AirstrikeConfig.SERVER.nukeSecondKeyWindow.get();
        StrikeGameTests.afterTest(h, () -> AirstrikeConfig.SERVER.nukeSecondKeyWindow.set(window));
        AirstrikeConfig.SERVER.nukeSecondKeyWindow.set(5);
        Listener a = at(h, "keys_expiry", new BlockPos(32, 12, 32));
        Listener other = at(h, "keys_expiry_other", new BlockPos(34, 12, 32));
        Item icbm = ModItems.ICBM.get();
        give(a, icbm, 1);
        h.assertTrue(order(a, List.of(a, other), nuke(WeaponType.NUKE, false), h), "приказ не принят");
        h.assertValueEqual(held(a, icbm), 0, "МБР не оплачена при отдаче");
        long started = level.getGameTime();
        h.onEachTick(() -> {
            if (NuclearKeys.get(level).pending() > 0) return;
            long waited = level.getGameTime() - started;
            if (waited < 5 * 20) throw new GameTestAssertException("приказ снят через " + waited + " тиков, раньше срока ключа");
            if (held(a, icbm) != 1) throw new GameTestAssertException("МБР после срока ключа: " + held(a, icbm));
            if (a.last("airstrike.nuke.key.expired") == null) throw new GameTestAssertException("нет строки о сроке ключа");
            if (mine(level, a.getUUID()) != null) throw new GameTestAssertException("МБР запущена без ключа");
            h.succeed();
        });
    }

    /**
     * Носитель (крылатая ракета с ядерной БЧ) после ключа не стартует сразу: у цели тревога — строка всем в её радиусе, —
     * пуск через 90 с. Обычный отбой (не оператора) его не снимает и не снимает ничего ядерного; ядерный отбой
     * (оператор, хост) снимает и возвращает ракету и БЧ. Не оператор — не «свой» для ядерного отбоя.
     */
    @GameTest(template = "range", batch = "nuclear_keys_carrier", skyAccess = true)
    public static void carrierWaitsOutAlarmAndOnlyNuclearClearStopsIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        setUp(h);
        Listener a = at(h, "keys_carrier", new BlockPos(32, 12, 32));
        Listener near = at(h, "keys_carrier_near", new BlockPos(35, 12, 32));
        List<Listener> online = List.of(a, near);
        Item missile = ModItems.CRUISE_MISSILE.get(), warhead = Munitions.warhead();
        give(a, missile, 1);
        give(a, warhead, 1);
        Loadout l = nuke(WeaponType.MISSILE, true);
        h.assertTrue(l.nuclear(), "ракета без ядерной БЧ (carrier_nukes?)");
        h.assertTrue(order(a, online, l, h), "приказ не принят");
        h.assertTrue(held(a, missile) == 0 && held(a, warhead) == 0, "ракета и БЧ не оплачены");
        h.assertTrue(NuclearKeys.confirm(near, online), "ключ не принят");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 1, "носитель не ждёт конца тревоги");
        h.assertValueEqual(StrikeWorld.active(level.getServer(), a.getUUID()), 1, "носитель до конца тревоги не в работе (max_active_per_player)");
        h.assertTrue(near.last("airstrike.nuke.alarm.carrier") != null, "у цели нет тревоги");
        h.assertTrue(a.last("airstrike.nuke.alarm.armed") != null, "запускающему не сказано о тревоге");
        h.assertTrue(StrikeWorld.projectiles(level).stream().noneMatch(p -> a.getUUID().equals(p.ownerId())), "носитель стартовал до конца тревоги");

        h.assertFalse(NuclearKeys.trusted(a), "не оператор — «свой» для ядерного отбоя");
        ServerActions.clearAll(level.getServer(), false, "проверка: отбой не оператора");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 1, "обычный отбой снял ядерный пуск");
        ServerActions.clearAll(level.getServer(), true, "проверка: ядерный отбой");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 0, "ядерный отбой не снял пуск");
        h.assertTrue(held(a, missile) == 1 && held(a, warhead) == 1, "ракета и БЧ не вернулись: " + held(a, missile) + ", " + held(a, warhead));
        h.assertTrue(a.last("airstrike.nuke.key.cancelled") != null, "запускающему не сказано об отбое");
        h.succeed();
    }

    // ---------------------------------------------------------------- помощники

    /**
     * Ядерка не только операторам, МБР в настройке летит 10 с (короче тревоги). После теста — прежние настройки, ни
     * ждущих приказов (носитель после тревоги стартовал бы в мир GameTest), ни ядерных ударов, ни МБР и пусковых.
     */
    private static void setUp(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean opsOnly = AirstrikeConfig.SERVER.nukeOpsOnly.get();
        int flight = AirstrikeConfig.SERVER.nukeFlightTime.get();
        StrikeGameTests.afterTest(h, () -> {
            NuclearKeys.cancelAll(level.getServer(), List.of(), null, "проверка окончена");
            NuclearStrikes.clear(level);
            VirtualFlights.get(level).clear(level, p -> p instanceof IcbmEntity);
            level.getEntities(EntityTypeTest.forClass(IcbmEntity.class), e -> true).forEach(Entity::discard);
            level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64)).forEach(Entity::discard);
            AirstrikeConfig.SERVER.nukeOpsOnly.set(opsOnly);
            AirstrikeConfig.SERVER.nukeFlightTime.set(flight);
        });
        AirstrikeConfig.SERVER.nukeOpsOnly.set(false);
        AirstrikeConfig.SERVER.nukeFlightTime.set(SHORT_FLIGHT);
    }

    private static Listener at(GameTestHelper h, String name, BlockPos pos) {
        Listener p = MunitionGameTests.player(h, name);
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(pos)), 0, 0);
        return p;
    }

    private static ServerActions.Aim aim(GameTestHelper h) {
        Vec3 point = Vec3.atCenterOf(h.absolutePos(TARGET)).add(0, 0.5, 0);
        return new ServerActions.Aim(new Target.Point(point), point, null);
    }

    /** Ядерный приказ в пределах настроек: МБР или носитель с ядерной БЧ, 15 кт. */
    private static Loadout nuke(WeaponType weapon, boolean onCarrier) {
        return ServerActions.clamp(new Loadout(weapon, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(15, true, onCarrier)));
    }

    /** Как {@code ServerActions.strike} для не оператора: оплатить и отдать приказ, «в сети» — {@code online}. */
    private static boolean order(Listener owner, List<Listener> online, Loadout l, GameTestHelper h) {
        Munitions.Bill bill = Munitions.Bill.of(l);
        if (!Munitions.pay(owner, bill)) throw new GameTestAssertException("не оплачено: " + l);
        return NuclearKeys.order(owner, online, l, aim(h), Waypoints.NONE, bill);
    }

    @Nullable
    private static NuclearEvents.ScheduledStrike mine(ServerLevel level, UUID owner) {
        for (NuclearEvents.ScheduledStrike s : NuclearEvents.get(level).scheduled()) {
            if (s.owner().map(owner::equals).orElse(false)) return s;
        }
        return null;
    }
}
