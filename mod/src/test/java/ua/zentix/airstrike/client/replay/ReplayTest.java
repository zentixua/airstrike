package ua.zentix.airstrike.client.replay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Живые события повтора Flashback по меткам сервера повтора. Сервер — модель {@code ReplayServer.tickServer},
 * {@code runUpdates} и {@code handleActions} Flashback 0.39.10: место в записи ({@code targetTick}) и прочитанное
 * ({@code currentTick}); перемотка назад или вперёд дальше длины куска в другой кусок — снимок куска (с последним пакетом
 * каждого вида, Flashback NeoForge Fixed) и чтение от его начала; до места дальше 20 тиков — чтение разом до места − 20,
 * последние — по тику с {@code fastForwarding}; при выводе видео — одно чтение на шаг. Каждое чтение кончается тиком
 * сервера — меткой: перемотка, если был снимок или досмотр по тику ({@code doClientRendering}), при выводе видео — только
 * досмотр. Клиент разбирает пакеты одного соединения по порядку: события тика, потом его метка; когда — не важно.
 */
class ReplayTest {
    /** Кусок записи — 5 минут (снимки — в начале каждого). */
    private static final int CHUNK = 6000;
    /** Событие снимка: последний пакет вида, а не место записи. */
    private static final int SNAPSHOT = -1;

    /** Событие, которое клиент принял: место записи, где оно было, и живое ли. */
    private record Event(int recorded, boolean live) {}

    private static final class Rig {
        final Replay.Markers markers = new Replay.Markers();
        final List<Integer> pending = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        /** Куски, в начале которых снимок ставится и при чтении подряд (стык склеенных записей). */
        final List<Integer> forced = new ArrayList<>();
        int target, current;
        boolean paused = true, export, seeking;
        int rewinds;

        /** Тик сервера: шаг повтора, перемотка на {@code jump} или стоим. */
        void tick(Integer jump) {
            boolean normal = false;
            if (jump != null) {
                target = jump;
            } else if (!paused) {
                target++;
                normal = true;
            }
            if (export || target == current || normal) {
                runUpdates();
                return;
            }
            int real = target;
            target = target < current ? Math.max(real / CHUNK * CHUNK + 1, real - 20) : Math.max(current + 1, real - 20);
            if (target >= real) {
                target = real;
                runUpdates();
                return;
            }
            while (true) {
                seeking = target < real;
                runUpdates();
                if (target == real) break;
                target++;
            }
            seeking = false;
        }

        void tick() {
            tick(null);
        }

        void seek(int to) {
            tick(to);
        }

        /** Чтение до места и тик сервера (метка в его конце). */
        private void runUpdates() {
            boolean snapshot = false;
            if (target != current) {
                if (target < current || target > current + CHUNK && target / CHUNK != current / CHUNK) {
                    pending.add(SNAPSHOT);
                    snapshot = true;
                    current = target / CHUNK * CHUNK;
                }
                while (current < target) {
                    pending.add(++current);
                    if (forced.contains(current)) {
                        pending.add(SNAPSHOT);
                        snapshot = true;
                    }
                }
            }
            Replay.Markers.Verdict v = markers.next(target, export ? seeking : seeking || snapshot);
            if (v == Replay.Markers.Verdict.SETTLED) rewinds++;
            for (int t : pending) events.add(new Event(t, v == Replay.Markers.Verdict.LIVE));
            pending.clear();
        }

        List<Event> take() {
            List<Event> e = new ArrayList<>(events);
            events.clear();
            return e;
        }
    }

    /** Повтор открыт (снимок первого куска), стоит на {@code at}, ещё три тика — и метки устоялись. */
    private static Rig at(int at) {
        Rig r = new Rig();
        r.seek(at);
        for (int i = 0; i < 3; i++) r.tick();
        r.take();
        r.rewinds = 0;
        return r;
    }

    private static long live(List<Event> events) {
        return events.stream().filter(Event::live).count();
    }

    /** Повтор идёт: каждое событие — живое, перемоток нет; стоит и снова идёт — тоже. */
    @Test
    void playbackIsLive() {
        Rig r = at(1000);
        r.paused = false;
        for (int i = 0; i < 400; i++) r.tick();
        List<Event> e = r.take();
        assertEquals(400, e.size());
        assertEquals(400, live(e));
        r.paused = true;
        for (int i = 0; i < 200; i++) r.tick();
        r.paused = false;
        for (int i = 0; i < 50; i++) r.tick();
        assertEquals(50, live(r.take()));
        assertEquals(0, r.rewinds);
    }

    /** Открытие повтора: снимок первого куска и первый тик — прошлое; дальше повтор живой. */
    @Test
    void openingIsPast() {
        Rig r = new Rig();
        r.pending.add(SNAPSHOT); // пакеты снимка при входе — до первой метки
        r.tick();
        assertEquals(0, live(r.take()));
        r.paused = false;
        r.tick();
        assertEquals(0, live(r.take()), "первый тик хода — после первой метки, ещё прошлое");
        r.tick();
        r.tick();
        assertEquals(2, live(r.take()));
        assertEquals(1, r.rewinds);
    }

    /**
     * Перемотка назад (на тик, в том же куске, в начало куска, в прошлый) и вперёд (на 2–3 тика, на 20, на 21, дальше,
     * в другой кусок): всё прочитанное — прошлое, одна перемотка; на паузе и на ходу; потом повтор снова живой (после
     * перемотки одним чтением — в начало куска — первый тик хода ещё прошлое).
     */
    @Test
    void seekEventsArePast() {
        int[] targets = {8999, 8990, 6500, 6001, 6000, 3000, 9003, 9020, 9021, 9030, 9100, 11000, 14000, 16000};
        for (boolean playing : new boolean[] {false, true}) {
            for (int to : targets) {
                Rig r = at(9000);
                r.paused = !playing;
                if (playing) r.tick();
                r.take();
                r.seek(to);
                List<Event> e = r.take();
                assertFalse(e.isEmpty(), "→ " + to);
                assertEquals(0, live(e), "→ " + to + (playing ? " на ходу" : " на паузе"));
                r.paused = false;
                r.tick();
                r.tick();
                List<Event> next = r.take();
                assertEquals(2, next.size());
                assertTrue(next.get(1).live(), "→ " + to + ": после перемотки повтор снова живой");
                assertEquals(1, r.rewinds, "→ " + to);
            }
        }
    }

    /** На паузе шаг на один тик вперёд — ход повтора: его события живые. */
    @Test
    void singleStepIsLive() {
        Rig r = at(9000);
        r.seek(9001);
        List<Event> e = r.take();
        assertEquals(1, e.size());
        assertEquals(1, live(e));
        assertEquals(0, r.rewinds);
    }

    /**
     * Вывод видео: сервер стоит между шагами (клиент тикает сам), каждый шаг — одно чтение. Сперва перемотка на 40 тиков
     * до начала — прошлое; шаги по тику и с ускорением (до 20 тиков за кадр) — живые; дальше шаг — прошлое.
     */
    @Test
    void exportStepsAreLive() {
        Rig r = at(9000);
        r.export = true;
        r.seek(2960);
        assertEquals(0, live(r.take()), "перемотка к началу видео");
        for (int t = 2961; t <= 3000; t++) r.seek(t);
        List<Event> warm = r.take();
        assertEquals(40, warm.size());
        assertEquals(39, live(warm), "первый шаг после перемотки — ещё прошлое");
        for (int i = 0; i < 100; i++) r.tick(); // сервер ждёт шага
        for (int t = 3001; t <= 3200; t++) r.seek(t);
        assertEquals(200, live(r.take()));
        for (int t = 3220; t <= 3600; t += 20) r.seek(t);
        assertEquals(400, live(r.take()), "ускорение: по 20 тиков за шаг");
        r.seek(3700);
        assertEquals(0, live(r.take()));
        assertEquals(1, r.rewinds);
    }

    /** Повтор ускорен, кадры клиента редки: метки идут по одной на тик сервера, события живые. */
    @Test
    void fastPlaybackIsLive() {
        Rig r = at(1000);
        r.paused = false;
        for (int i = 0; i < 2000; i++) r.tick();
        assertEquals(2000, live(r.take()));
    }

    /** Повтор дошёл до конца куска: следующий кусок читается подряд, без снимка, — живой. */
    @Test
    void chunkBoundaryIsLive() {
        Rig r = at(CHUNK - 5);
        r.paused = false;
        for (int i = 0; i < 10; i++) r.tick();
        List<Event> e = r.take();
        assertEquals(10, live(e));
        assertTrue(e.stream().noneMatch(ev -> ev.recorded() == SNAPSHOT));
    }

    /**
     * Стык склеенных записей: снимок посреди хода повтора — его тик прошлое (в снимке последние пакеты прошлой записи),
     * следующий — первый тик хода, дальше живое.
     */
    @Test
    void forcedSnapshotIsPast() {
        Rig r = at(CHUNK - 5);
        r.forced.add(CHUNK);
        r.paused = false;
        for (int i = 0; i < 4; i++) r.tick();
        assertEquals(4, live(r.take()));
        r.tick();
        assertEquals(0, live(r.take()), "тик со снимком");
        r.tick();
        assertEquals(0, live(r.take()));
        r.tick();
        assertEquals(1, live(r.take()));
        assertEquals(1, r.rewinds);
    }

    /** Место назад или вперёд дальше {@link Replay.Markers#STEP} без флага перемотки — тоже прошлое. */
    @Test
    void jumpWithoutFlagIsPast() {
        Replay.Markers m = new Replay.Markers();
        m.next(100, false);
        m.next(101, false);
        assertEquals(Replay.Markers.Verdict.LIVE, m.next(102, false));
        assertEquals(Replay.Markers.Verdict.PAST, m.next(101, false));
        assertEquals(Replay.Markers.Verdict.SETTLED, m.next(102, false));
        assertEquals(Replay.Markers.Verdict.PAST, m.next(102 + Replay.Markers.STEP + 1, false));
        assertEquals(Replay.Markers.Verdict.SETTLED, m.next(123 + Replay.Markers.STEP, false));
        assertEquals(Replay.Markers.Verdict.LIVE, m.next(123 + Replay.Markers.STEP, false));
        assertEquals(Replay.Markers.Verdict.PAST, m.next(200, true));
        m.reset();
        assertEquals(Replay.Markers.Verdict.PAST, m.next(200, false), "после выхода из мира — первая метка");
    }
}
