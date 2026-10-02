package ua.zentix.airstrike.client.replay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Перемотка повтора Flashback по счётчикам сервера повтора. Сервер — модель {@code ReplayServer.tickServer}
 * и {@code handleActions} Flashback 0.39.10: место в записи, тики сервера, перемотка — снимок куска записи (назад
 * или дальше куска вперёд) и чтение до места за один тик, последние 20 тиков — по одному, пока не дойдёт.
 * Пакет события в записи отдаётся клиенту в тот тик сервера, когда прочитан.
 */
class ReplayTest {
    /** Кусок записи — 5 минут (снимки — в начале каждого). */
    private static final int CHUNK = 6000;

    /** Пакет, который клиент разобрал: место записи, где событие было, и живое ли оно для клиента. */
    private record Event(int recorded, boolean live) {}

    /**
     * Сервер повтора и клиент. Клиент разбирает пакеты после тика сервера (или прямо по ходу его, {@code eager}) и
     * тикает сам, если сервер не перематывает (Flashback тогда не даёт ему тикать).
     */
    private static final class Rig {
        final Replay.Seeks seeks = new Replay.Seeks();
        final List<Event> events = new ArrayList<>();
        int place, current, ticks;
        boolean seeking, paused;
        boolean eager;
        int jumps;

        /** Тик сервера: повтор идёт или стоит; клиент разбирает пакеты и делает свой тик. */
        void tick() {
            ticks++;
            if (!paused) read(place + 1);
            clientTick();
        }

        /** Перемотка на место {@code to} (как {@code goToReplayTick}): один тик сервера. */
        void seek(int to) {
            if (to != current) {
                int start;
                if (to < current || to > current + CHUNK) {
                    current = to / CHUNK * CHUNK; // снимок куска
                    start = Math.max(current + 1, to - 20);
                } else {
                    start = Math.max(current + 1, to - 20);
                }
                for (int t = start; t <= to; t++) {
                    seeking = t < to;
                    ticks++;
                    read(t);
                }
                seeking = false;
            }
            clientTick();
        }

        /** Сервер дочитывает запись до места {@code to}; события — по одному на каждое место записи. */
        private void read(int to) {
            place = to;
            List<Integer> sent = new ArrayList<>();
            for (int t = current + 1; t <= to; t++) sent.add(t);
            current = to;
            if (eager) for (int t : sent) handle(t);
            else pending.addAll(sent);
        }

        private final List<Integer> pending = new ArrayList<>();

        private void handle(int recorded) {
            events.add(new Event(recorded, seeks.live(place, ticks, seeking)));
        }

        private void clientTick() {
            for (int t : pending) handle(t);
            pending.clear();
            if (seeks.sample(place, ticks, seeking)) jumps++;
        }

        List<Event> take() {
            List<Event> e = new ArrayList<>(events);
            events.clear();
            return e;
        }
    }

    private static Rig playing(int from) {
        Rig r = new Rig();
        r.seek(from);
        r.tick();
        r.tick();
        r.take();
        r.jumps = 0;
        return r;
    }

    private static long live(List<Event> events) {
        return events.stream().filter(Event::live).count();
    }

    /** Повтор идёт: каждое событие — живое, перемоток нет; стоит — тоже. */
    @Test
    void playbackIsLive() {
        for (boolean eager : new boolean[] {false, true}) {
            Rig r = playing(1000);
            r.eager = eager;
            for (int i = 0; i < 400; i++) r.tick();
            List<Event> e = r.take();
            assertEquals(400, e.size());
            assertEquals(400, live(e), "живые события");
            r.paused = true;
            for (int i = 0; i < 200; i++) r.tick();
            r.paused = false;
            for (int i = 0; i < 50; i++) r.tick();
            assertEquals(50, live(r.take()));
            assertEquals(0, r.jumps, "перемоток не было");
        }
    }

    /** Открытие повтора: снимок первого куска — прошлое; через тик клиента после него события живые. */
    @Test
    void openingIsPast() {
        Rig r = new Rig();
        r.seek(0);
        r.read(0);
        r.pending.add(-1); // пакеты снимка (последний пакет каждого вида у Flashback NeoForge Fixed)
        r.clientTick();
        assertEquals(0, live(r.take()));
        r.tick();
        r.tick();
        assertTrue(r.take().stream().skip(1).allMatch(Event::live));
    }

    /**
     * Перемотка назад (на сколько угодно, в том же куске и в прошлый) и вперёд дальше 26 тиков (повтор до неё прошёл
     * ещё два тика с 9000): всё прочитанное — прошлое.
     */
    @Test
    void seekEventsArePast() {
        int[][] seeks = {{9000, 6500}, {9000, 8990}, {9000, 8999}, {9000, 3000}, {9000, 9100}, {9000, 16000}, {9000, 9030}, {9000, 6001}};
        for (boolean eager : new boolean[] {false, true}) {
            for (int[] s : seeks) {
                Rig r = playing(s[0]);
                r.eager = eager;
                r.paused = s[0] % 2 == 0;
                r.seek(s[1]);
                List<Event> e = r.take();
                assertFalse(e.isEmpty());
                assertEquals(0, live(e), s[0] + " → " + s[1] + (eager ? " по ходу" : ""));
                assertEquals(1, r.jumps);
                // после перемотки: первый тик клиента — ещё перемотка, дальше живое
                r.paused = false;
                r.tick();
                r.tick();
                List<Event> after = r.take();
                assertTrue(after.get(after.size() - 1).live(), s[0] + " → " + s[1] + ": после перемотки повтор снова живой");
                assertEquals(1, r.jumps);
            }
        }
    }

    /** Шаг вперёд до 20 тиков сервер проходит по тику — это не перемотка: события этих тиков живые. */
    @Test
    void shortStepForwardIsLive() {
        Rig r = playing(9000);
        r.paused = true;
        r.seek(r.place + 15);
        List<Event> e = r.take();
        assertEquals(15, e.size());
        assertEquals(15, live(e));
        assertEquals(0, r.jumps);
    }

    /**
     * Перемотка на тик назад, а клиент разобрал её пакеты только через три тика сервера (кадр затянулся): место уже
     * дальше прошлого замера, но тиков сервера прошло на перемотку больше — тоже перемотка.
     */
    @Test
    void tinySeekBackSeenLate() {
        Replay.Seeks s = new Replay.Seeks();
        s.sample(1000, 50_000, false);
        s.sample(1001, 50_001, false);
        // перемотка на 1000 (21 тик сервера: досмотр последних 20 по одному), потом ещё три тика повтора
        assertFalse(s.live(1003, 50_001 + 21 + 3, false));
        // тот же сдвиг места, когда повтор просто шёл
        assertTrue(s.live(1004, 50_004, false));
    }

    /** Счётчики читаются без замка: сервер между ними сделал тик — это не перемотка. */
    @Test
    void readRaceIsNotASeek() {
        Replay.Seeks s = new Replay.Seeks();
        s.sample(1000, 50_000, false);
        s.sample(1001, 50_001, false);
        assertTrue(s.live(1002, 50_001, false));
        assertFalse(s.sample(1002, 50_001, false));
        assertFalse(s.sample(1003, 50_003, false));
        assertTrue(s.live(1003, 50_003, false));
    }

    /** Повтор ускорен, клиент отстал (кадр в полсекунды): место и тики сервера сдвинулись вместе — не перемотка. */
    @Test
    void fastPlaybackWithLagIsNotASeek() {
        Replay.Seeks s = new Replay.Seeks();
        s.sample(1000, 50_000, false);
        s.sample(1001, 50_001, false);
        assertTrue(s.live(1201, 50_201, false));
        assertFalse(s.sample(1201, 50_201, false));
        // повтор на паузе, а клиент отстал: тики сервера идут, место стоит
        assertTrue(s.live(1201, 50_401, false));
    }
}
