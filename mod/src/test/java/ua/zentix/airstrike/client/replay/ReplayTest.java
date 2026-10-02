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
 * каждого вида) и чтение от его начала; до места дальше 20 тиков — чтение разом до места − 20, последние — по тику
 * с {@code fastForwarding}; при выводе видео — одно чтение на шаг. Каждое чтение кончается тиком сервера — меткой:
 * перемотка, если был снимок или досмотр по тику ({@code doClientRendering}), при выводе видео — только досмотр. Клиент
 * разбирает пакеты одного соединения по порядку: события, потом метка; когда — не важно. Каждая проверка — и с Flashback
 * NeoForge Fixed ({@code fork}): с начала снимка он копит пакеты и отдаёт в конце тика сервера повтора, после меток всех
 * чтений ({@code MixinReplayServer.catchUpNewViewers}).
 */
class ReplayTest {
    /** Кусок записи — 5 минут (снимки — в начале каждого). */
    private static final int CHUNK = 6000;
    /** Событие снимка: последний пакет вида, а не место записи. */
    private static final int SNAPSHOT = -1;
    private static final boolean[] FORKS = {false, true};

    /** Событие, которое клиент принял: место записи, где оно было, и живое ли. */
    private record Event(int recorded, boolean live) {}

    private static final class Rig {
        final Replay.Markers markers = new Replay.Markers();
        final List<Integer> pending = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        /** Куски, в начале которых снимок ставится и при чтении подряд (стык склеенных записей). */
        final List<Integer> forced = new ArrayList<>();
        /** Flashback NeoForge Fixed: что он копит до конца тика сервера. */
        final List<Integer> backlog = new ArrayList<>();
        final boolean fork;
        boolean deferring;
        int target, current;
        boolean paused = true, export, seeking;
        int rewinds;

        Rig(boolean fork) {
            this.fork = fork;
        }

        /** Тик сервера: шаг повтора, перемотка на {@code jump} или стоим; в конце — накопленное. */
        void tick(Integer jump) {
            step(jump);
            pending.addAll(backlog);
            backlog.clear();
            deferring = false;
        }

        private void step(Integer jump) {
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
                    snapshot();
                    snapshot = true;
                    current = target / CHUNK * CHUNK;
                }
                while (current < target) {
                    current++;
                    if (forced.contains(current)) {
                        snapshot();
                        snapshot = true;
                    }
                    deliver(current);
                }
            }
            Replay.Markers.Verdict v = markers.next(target, export ? seeking : seeking || snapshot);
            if (v == Replay.Markers.Verdict.SETTLED) rewinds++;
            for (int t : pending) events.add(new Event(t, v == Replay.Markers.Verdict.LIVE));
            pending.clear();
        }

        private void snapshot() {
            deferring = fork;
            deliver(SNAPSHOT);
        }

        private void deliver(int recorded) {
            (deferring ? backlog : pending).add(recorded);
        }

        List<Event> take() {
            List<Event> e = new ArrayList<>(events);
            events.clear();
            return e;
        }
    }

    /** Повтор открыт, стоит на {@code at}, ещё три тика — и метки устоялись. */
    private static Rig at(int at, boolean fork) {
        Rig r = new Rig(fork);
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
        for (boolean fork : FORKS) {
            Rig r = at(1000, fork);
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
    }

    /**
     * Открытие повтора: пакеты снимка до первой метки или сразу после неё (Flashback NeoForge Fixed — в конце тика)
     * и первые два тика хода — прошлое; дальше повтор живой.
     */
    @Test
    void openingIsPast() {
        for (boolean late : new boolean[] {false, true}) {
            Rig r = new Rig(late);
            if (!late) r.pending.add(SNAPSHOT);
            r.tick();
            if (late) r.pending.add(SNAPSHOT);
            r.paused = false;
            r.tick();
            r.tick();
            List<Event> e = r.take();
            assertEquals(3, e.size());
            assertEquals(0, live(e));
            r.tick();
            r.tick();
            assertEquals(2, live(r.take()));
            assertEquals(1, r.rewinds);
        }
    }

    /**
     * Перемотка назад (на тик, в том же куске, в начало куска, в прошлый) и вперёд (на 2–3 тика, на 20, на 21, дальше,
     * в другой кусок): всё прочитанное и первый тик хода после неё — прошлое, одна перемотка; на паузе и на ходу; со
     * снимком через Flashback NeoForge Fixed прочитанное приходит после метки последнего чтения. Третий тик хода — живой.
     */
    @Test
    void seekEventsArePast() {
        int[] targets = {8999, 8990, 6500, 6001, 6000, 3000, 9003, 9020, 9021, 9030, 9100, 11000, 14000, 16000};
        for (boolean fork : FORKS) {
            for (boolean playing : new boolean[] {false, true}) {
                for (int to : targets) {
                    String what = "→ " + to + (playing ? " на ходу" : " на паузе") + (fork ? ", Fixed" : "");
                    Rig r = at(9000, fork);
                    r.paused = !playing;
                    if (playing) r.tick();
                    r.take();
                    r.seek(to);
                    r.paused = false;
                    r.tick();
                    List<Event> e = r.take();
                    assertTrue(e.size() > 1, what);
                    assertEquals(0, live(e), what);
                    r.tick();
                    r.tick();
                    List<Event> next = r.take();
                    assertEquals(2, next.size(), what);
                    assertTrue(next.get(1).live(), what + ": после перемотки повтор снова живой");
                    assertEquals(1, r.rewinds, what);
                }
            }
        }
    }

    /** На паузе шаг на один тик вперёд — ход повтора: его события живые. */
    @Test
    void singleStepIsLive() {
        for (boolean fork : FORKS) {
            Rig r = at(9000, fork);
            r.seek(9001);
            List<Event> e = r.take();
            assertEquals(1, e.size());
            assertEquals(1, live(e));
            assertEquals(0, r.rewinds);
        }
    }

    /**
     * Вывод видео: сервер стоит между шагами (клиент тикает сам), каждый шаг — одно чтение. Сперва перемотка на 40 тиков
     * до начала — прошлое, и первые два шага после неё; шаги по тику и с ускорением (до 20 тиков за кадр) — живые;
     * дальше шаг — прошлое.
     */
    @Test
    void exportStepsAreLive() {
        for (boolean fork : FORKS) {
            Rig r = at(9000, fork);
            r.export = true;
            r.seek(2960);
            r.seek(2961);
            assertEquals(0, live(r.take()), "перемотка к началу видео");
            for (int t = 2962; t <= 3000; t++) r.seek(t);
            List<Event> warm = r.take();
            assertEquals(39, warm.size());
            assertEquals(38, live(warm), "второй шаг после перемотки — ещё прошлое");
            for (int i = 0; i < 100; i++) r.tick(); // сервер ждёт шага
            for (int t = 3001; t <= 3200; t++) r.seek(t);
            assertEquals(200, live(r.take()));
            for (int t = 3220; t <= 3600; t += 20) r.seek(t);
            assertEquals(400, live(r.take()), "ускорение: по 20 тиков за шаг");
            r.seek(3700);
            assertEquals(0, live(r.take()));
            assertEquals(1, r.rewinds);
        }
    }

    /** Повтор ускорен, кадры клиента редки: метки идут по одной на тик сервера, события живые. */
    @Test
    void fastPlaybackIsLive() {
        for (boolean fork : FORKS) {
            Rig r = at(1000, fork);
            r.paused = false;
            for (int i = 0; i < 2000; i++) r.tick();
            assertEquals(2000, live(r.take()));
        }
    }

    /** Повтор дошёл до конца куска: следующий кусок читается подряд, без снимка, — живой. */
    @Test
    void chunkBoundaryIsLive() {
        for (boolean fork : FORKS) {
            Rig r = at(CHUNK - 5, fork);
            r.paused = false;
            for (int i = 0; i < 10; i++) r.tick();
            List<Event> e = r.take();
            assertEquals(10, live(e));
            assertTrue(e.stream().noneMatch(ev -> ev.recorded() == SNAPSHOT));
        }
    }

    /**
     * Стык склеенных записей: снимок посреди хода повтора — его тик прошлое (в снимке последние пакеты прошлой записи),
     * и два следующих, дальше живое.
     */
    @Test
    void forcedSnapshotIsPast() {
        for (boolean fork : FORKS) {
            Rig r = at(CHUNK - 5, fork);
            r.forced.add(CHUNK);
            r.paused = false;
            for (int i = 0; i < 4; i++) r.tick();
            assertEquals(4, live(r.take()));
            for (int i = 0; i < 3; i++) r.tick();
            List<Event> e = r.take();
            assertTrue(e.stream().anyMatch(ev -> ev.recorded() == SNAPSHOT));
            assertEquals(0, live(e), "тик со снимком и два следующих");
            r.tick();
            assertEquals(1, live(r.take()));
            assertEquals(1, r.rewinds);
        }
    }

    /** Место назад или вперёд дальше {@link Replay.Markers#STEP} без флага перемотки — тоже прошлое. */
    @Test
    void jumpWithoutFlagIsPast() {
        Replay.Markers m = new Replay.Markers();
        assertEquals(Replay.Markers.Verdict.PAST, m.next(100, false), "первая метка");
        assertEquals(Replay.Markers.Verdict.SETTLED, m.next(101, false));
        assertFalse(m.steady());
        assertEquals(Replay.Markers.Verdict.PAST, m.next(102, false));
        assertTrue(m.steady());
        assertEquals(Replay.Markers.Verdict.LIVE, m.next(103, false));
        assertEquals(Replay.Markers.Verdict.PAST, m.next(102, false));
        assertEquals(Replay.Markers.Verdict.SETTLED, m.next(103, false));
        assertEquals(Replay.Markers.Verdict.PAST, m.next(104, false));
        assertEquals(Replay.Markers.Verdict.LIVE, m.next(105, false));
        assertEquals(Replay.Markers.Verdict.PAST, m.next(105 + Replay.Markers.STEP + 1, false));
        assertEquals(Replay.Markers.Verdict.SETTLED, m.next(126 + Replay.Markers.STEP, false));
        assertEquals(Replay.Markers.Verdict.PAST, m.next(146, false));
        assertEquals(Replay.Markers.Verdict.LIVE, m.next(146, false));
        assertEquals(Replay.Markers.Verdict.PAST, m.next(200, true));
        m.reset();
        assertEquals(Replay.Markers.Verdict.PAST, m.next(200, false), "после выхода из мира — первая метка");
        assertFalse(m.steady());
    }
}
