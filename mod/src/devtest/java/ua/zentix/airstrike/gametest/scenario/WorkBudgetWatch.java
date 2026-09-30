package ua.zentix.airstrike.gametest.scenario;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.airstrike.grid.Blackouts;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.work.WorkScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Бюджет тяжёлой работы за тик ({@link WorkScheduler}, корень 1) во время сценариев. Часы полос — считающие
 * ({@link WorkClock#counting}, единица работы = 1 мс): решения очереди не зависят от скорости машины, и граница
 * проверяется точно. В конце каждого тика (после планировщика): полоса попаданий — не больше общего срока и одной
 * единицы, попадания с блэкаутом — не больше общего срока и одной единицы на полосу; и в каком тике очередь попаданий
 * была пуста. Часы — одни на сервер, поэтому подменяются, пока идёт хоть один сценарий (партии сценариев — только
 * из сценариев), и возвращаются игровые после последнего.
 */
final class WorkBudgetWatch {
    /** Цена единицы работы у считающих часов. */
    static final long UNIT = 1_000_000L;

    /** Тик сервера, в котором граница нарушена, и чем. */
    record Violation(int tick, String what) {}

    private static int users;
    private static boolean registered;
    private static final List<Violation> violations = new ArrayList<>();
    /** Последний тик сервера, в конце которого очереди попаданий всех миров были пусты. */
    private static int lastEmpty = -1;

    private WorkBudgetWatch() {}

    static synchronized void acquire(MinecraftServer server) {
        if (!registered) {
            registered = true;
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, WorkBudgetWatch::onServerTick);
        }
        if (users++ == 0) {
            WorkScheduler.useImpactClock(server, WorkClock.counting(UNIT));
            Blackouts.useClock(server, WorkClock.counting(UNIT));
        }
    }

    static synchronized void release(MinecraftServer server) {
        if (--users == 0) {
            WorkScheduler.useImpactClock(server, WorkScheduler.newImpactClock());
            Blackouts.useClock(server, Blackouts.newClock());
        }
    }

    private static void onServerTick(ServerTickEvent.Post e) {
        if (users <= 0) return;
        MinecraftServer server = e.getServer();
        WorkClock impact = WorkScheduler.impactClock(server), grid = Blackouts.clock(server);
        long total = WorkScheduler.totalNanos();
        int tick = server.getTickCount();
        if (impact.usedThisTickNanos() > total + UNIT) {
            violations.add(new Violation(tick, String.format(Locale.ROOT, "попадания %d мс при сроке %d", impact.usedThisTickNanos() / UNIT, total / UNIT)));
        }
        long both = impact.usedThisTickNanos() + grid.usedThisTickNanos();
        if (both > total + 2 * UNIT) {
            violations.add(new Violation(tick, String.format(Locale.ROOT, "попадания и блэкаут %d мс при сроке %d", both / UNIT, total / UNIT)));
        }
        boolean empty = true;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.hasData(ModAttachments.STRIKE_WORLD) && !StrikeWorld.get(level).impacts().isEmpty()) empty = false;
        }
        if (empty) lastEmpty = tick;
    }

    /** Нарушения границы в тиках сервера с {@code fromTick} (включительно). */
    static synchronized List<Violation> since(int fromTick) {
        return violations.stream().filter(v -> v.tick() >= fromTick).toList();
    }

    /** Была ли очередь попаданий пуста в конце какого-нибудь тика сервера с {@code fromTick}. */
    static boolean emptiedSince(int fromTick) {
        return lastEmpty >= fromTick;
    }
}
