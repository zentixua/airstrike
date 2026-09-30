package ua.zentix.airstrike.work;

import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.nuclear.world.WorkClock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Очередь единиц без мира: работы получают {@code null} вместо мира, время мира — числом. */
class UnitQueueTest {
    private static final long MS = 1_000_000L;

    /** Работа из {@code units} единиц; пишет в журнал «имя:номер» и «имя:end». */
    private static final class Fake implements UnitQueue.Job {
        final String name;
        final List<String> log;
        int units;
        boolean ready = true;
        UnitQueue.Job waitsFor;
        int ended;
        Runnable onStep = () -> {};

        Fake(String name, int units, List<String> log) {
            this.name = name;
            this.units = units;
            this.log = log;
        }

        @Override
        public boolean ready(ServerLevel level) {
            return ready;
        }

        @Override
        public boolean blocked() {
            return waitsFor instanceof Fake f && f.ended == 0;
        }

        @Override
        public boolean step(ServerLevel level) {
            log.add(name + ":" + units);
            onStep.run();
            return --units > 0;
        }

        @Override
        public void end(ServerLevel level) {
            ended++;
            log.add(name + ":end");
        }

        @Override
        public String describe() {
            return name;
        }
    }

    private static void tick(UnitQueue q, WorkClock clock, long budget, long now) {
        clock.start(budget);
        q.work(null, now, clock);
    }

    /** Первые единицы новых работ — раньше продолжений начатых: подрыв второго удара не ждёт порций первого. */
    @Test
    void headsBeforeTails() {
        List<String> log = new ArrayList<>();
        UnitQueue q = new UnitQueue();
        q.add(0, new Fake("a", 5, log));
        q.add(0, new Fake("b", 3, log));
        tick(q, WorkClock.counting(MS), 4 * MS, 0);
        assertEquals(List.of("a:5", "b:3", "a:4"), log);
        tick(q, WorkClock.counting(MS), 100 * MS, 1);
        assertEquals(List.of("a:5", "b:3", "a:4", "a:3", "a:2", "a:1", "a:end", "b:2", "b:1", "b:end"), log);
        assertTrue(q.isEmpty());
    }

    /** Работа, которая ждёт другую, пропускается, пока та не кончится; следующие идут. */
    @Test
    void blockedWaitsForItsJob() {
        List<String> log = new ArrayList<>();
        UnitQueue q = new UnitQueue();
        Fake main = new Fake("main", 3, log);
        Fake fireball = new Fake("fire", 1, log);
        fireball.waitsFor = main;
        Fake other = new Fake("other", 1, log);
        q.add(0, main);
        q.add(0, fireball);
        q.add(0, other);
        tick(q, WorkClock.counting(MS), 3 * MS, 0);
        assertEquals(List.of("main:3", "other:1", "other:end"), log);
        tick(q, WorkClock.counting(MS), 100 * MS, 1);
        assertEquals(List.of("main:3", "other:1", "other:end", "main:2", "main:1", "main:end"), log);
        tick(q, WorkClock.counting(MS), 100 * MS, 2);
        assertEquals("fire:end", log.getLast());
        assertTrue(q.isEmpty());
    }

    /** Неготовая работа ждёт до {@link UnitQueue#GIVE_UP_TICKS}, потом отменяется: отпускается один раз, без единиц. */
    @Test
    void unreadyJobGivesUpOnce() {
        List<String> log = new ArrayList<>();
        UnitQueue q = new UnitQueue();
        Fake far = new Fake("far", 2, log);
        far.ready = false;
        q.add(0, far);
        tick(q, WorkClock.counting(MS), 30 * MS, UnitQueue.GIVE_UP_TICKS - 1);
        assertEquals(List.of(), log);
        tick(q, WorkClock.counting(MS), 30 * MS, UnitQueue.GIVE_UP_TICKS);
        assertEquals(List.of("far:end"), log);
        assertEquals(1, far.ended);
        assertTrue(q.isEmpty());
    }

    /** Работа, поставленная из работы (взрыв будит обработчики), встаёт в конец и идёт в том же обходе. */
    @Test
    void jobAddedFromJobRunsInSameTick() {
        List<String> log = new ArrayList<>();
        UnitQueue q = new UnitQueue();
        Fake first = new Fake("first", 1, log);
        first.onStep = () -> q.add(0, new Fake("child", 1, log));
        q.add(0, first);
        tick(q, WorkClock.counting(MS), 30 * MS, 0);
        assertEquals(List.of("first:1", "first:end", "child:1", "child:end"), log);
    }

    /** Остановка: всё, что может идти, доделывается без срока; неготовое и ждущее его — отпускаются. */
    @Test
    void finishDoesReadyWorkAndReleasesTheRest() {
        List<String> log = new ArrayList<>();
        UnitQueue q = new UnitQueue();
        Fake big = new Fake("big", 500, log);
        Fake far = new Fake("far", 2, log);
        far.ready = false;
        Fake after = new Fake("after", 1, log);
        after.waitsFor = far;
        q.add(0, big);
        q.add(0, far);
        q.add(0, after);
        tick(q, WorkClock.counting(MS), 3 * MS, 0);
        q.finish(null, 1);
        assertTrue(q.isEmpty());
        assertEquals(0, big.units);
        assertEquals(1, big.ended);
        assertEquals(1, far.ended);
        assertEquals(1, after.ended);
        assertTrue(!log.contains("far:2") && !log.contains("after:1"), log.toString());
    }

    /** Дорогой вид единиц не стартует впритык к сроку после дешёвых: у каждого вида своя оценка. */
    @Test
    void kindsHaveOwnEstimates() {
        long[] now = {0};
        WorkClock clock = WorkClock.decaying(() -> now[0], 1);
        clock.start(100 * MS);
        long b = clock.begin();
        now[0] += 40 * MS;
        clock.end(b, 1);
        for (int i = 0; i < 20; i++) {
            b = clock.begin();
            now[0] += MS / 10;
            clock.end(b, 2);
        }
        clock.start(30 * MS);
        b = clock.begin();
        now[0] += MS / 10;
        clock.end(b, 2);
        assertTrue(clock.canStart(2), "дешёвая порция влезает");
        assertTrue(!clock.canStart(1), "дорогие лучи (40 мс) в срок 30 мс не влезают");
    }
}
