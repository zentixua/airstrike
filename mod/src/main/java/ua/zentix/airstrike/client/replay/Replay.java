package ua.zentix.airstrike.client.replay;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.compat.FlashbackReplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Повтор Flashback и его перемотка. Перематывая, сервер повтора в одном своём тике ставит снимок куска записи и читает
 * пакеты от его начала до нужного места, а пакеты модов отдаёт клиенту сразу, как прочёл; Flashback NeoForge Fixed ещё
 * и кладёт в каждый снимок последний пакет каждого вида. Клиент принимал их как живые — и взрывы и подрывы с начала
 * куска повторялись разом, со вспышкой и звуком. Теперь событие из перемотки — прошлое: картинки и звука удара нет,
 * ядерный подрыв виден в своём возрасте.
 * <p>
 * В повторе событие ждёт метки конца тика сервера повтора ({@link FlashbackReplay}): пакеты одного соединения клиент
 * разбирает по порядку, и всё, что пришло до метки, прочитано не позже этого тика. Живое оно, если этот тик и два
 * прошлых — ход повтора ({@link Markers#SETTLE}): без перемотки и место сдвинулось вперёд не больше чем на
 * {@link Markers#STEP} (вывод видео с ускорением шагает и на несколько тиков). Решение — в разборе метки, а не по тикам
 * клиента: клиент тикает и посреди перемотки (Flashback не даёт ему только рисовать), а при выводе видео сервер стоит
 * между шагами.
 * <p>
 * Не в повторе, без Flashback или без меток — всё вживую, сразу, как раньше.
 */
public final class Replay {
    /** Тики клиента без единой метки, после которых ждущие события идут вживую: метки не приходят совсем. */
    private static final int NO_MARKERS = 600;
    private static final Markers MARKERS = new Markers();
    private static final List<Pending> PENDING = new ArrayList<>();
    /** Сколько событий каждого вида прошло живыми и прошлым (для сценария повтора). */
    private static final Map<String, int[]> COUNTS = new LinkedHashMap<>();
    private static int waited;
    private static boolean warned;

    private record Pending(String kind, BooleanConsumer action) {}

    private Replay() {}

    /**
     * Событие от сервера ({@code kind} — вид для счёта): {@code action} получает, живое ли оно. Не в повторе — сразу
     * и живое; в повторе — с меткой конца этого тика сервера повтора.
     */
    public static void event(String kind, BooleanConsumer action) {
        if (!active()) {
            action.accept(true);
            return;
        }
        PENDING.add(new Pending(kind, action));
    }

    /**
     * Метка конца тика сервера повтора: события, пришедшие до неё, — живые или прошлое; первая метка хода повтора
     * после перемотки сперва зовёт {@code rewound} (всё, что шло до перемотки, — с другого места записи).
     */
    public static void marker(int place, boolean seeking, Runnable rewound) {
        Markers.Verdict v = MARKERS.next(place, seeking);
        if (v == Markers.Verdict.SETTLED) rewound.run();
        flush(v == Markers.Verdict.LIVE);
    }

    /** Тик клиента: ждущие события, если повтор закрыт или метки не приходят совсем, — вживую. */
    public static void tick() {
        if (PENDING.isEmpty()) return;
        if (!active()) {
            flush(true);
        } else if (MARKERS.count == 0 && ++waited > NO_MARKERS) {
            if (!warned) {
                warned = true;
                Airstrike.LOG.warn("Flashback: метки сервера повтора не приходят {} тиков — события повтора идут как живые", NO_MARKERS);
            }
            flush(true);
        }
    }

    /** Выход из мира: ждущие события и метки прошлого повтора не держать. */
    public static void reset() {
        PENDING.clear();
        MARKERS.reset();
        waited = 0;
    }

    /** Клиент смотрит повтор Flashback. */
    public static boolean active() {
        return FlashbackReplay.isReplay(Minecraft.getInstance().getSingleplayerServer());
    }

    /** Меток с открытия повтора. */
    public static long markers() {
        return MARKERS.count;
    }

    /** Повтор идёт или стоит без перемотки: события следующего тика сервера будут живыми, если и он — ход. */
    public static boolean steady() {
        return MARKERS.steady();
    }

    /** Копия счёта событий: вид → {живые, прошлое}. */
    public static Map<String, int[]> counts() {
        Map<String, int[]> copy = new LinkedHashMap<>();
        COUNTS.forEach((k, v) -> copy.put(k, v.clone()));
        return copy;
    }

    private static void flush(boolean live) {
        waited = 0;
        if (PENDING.isEmpty()) return;
        List<Pending> all = new ArrayList<>(PENDING);
        PENDING.clear();
        for (Pending p : all) {
            COUNTS.computeIfAbsent(p.kind, k -> new int[2])[live ? 0 : 1]++;
            p.action.accept(live);
        }
    }

    /** Метки сервера повтора по порядку; без Minecraft — для проверок. */
    static final class Markers {
        /**
         * На сколько тиков место может уйти вперёд за тик сервера в ходе повтора: при выводе видео с ускорением сервер
         * шагает по нескольку тиков за кадр. Так Flashback отличает перемотку и сам: последние 20 тиков до места он
         * проходит по тику.
         */
        static final int STEP = 20;
        /**
         * Сколько меток хода повтора подряд события ещё прошлое. Первая — последнее чтение перемотки или первый тик после
         * неё. Вторая — потому что Flashback NeoForge Fixed с начала снимка не отдаёт пакеты сразу, а копит их и отдаёт
         * в конце тика сервера повтора ({@code MixinReplayServer.catchUpNewViewers}), уже после метки последнего чтения:
         * всё прочитанное перемоткой приходит перед второй меткой.
         */
        static final int SETTLE = 2;

        enum Verdict {
            /** События этого тика — живые. */
            LIVE,
            /** Прошлое: перемотка, первая метка повтора или вторая метка хода после перемотки. */
            PAST,
            /** Прошлое, и это первая метка хода повтора после перемотки. */
            SETTLED
        }

        /** Меток хода повтора подряд, до {@code SETTLE + 1}. */
        private int streak;
        private int place;
        long count;

        Verdict next(int place, boolean seeking) {
            int moved = place - this.place;
            boolean clean = count > 0 && !seeking && moved >= 0 && moved <= STEP;
            streak = clean ? Math.min(streak + 1, SETTLE + 1) : 0;
            this.place = place;
            count++;
            return streak > SETTLE ? Verdict.LIVE : streak == 1 ? Verdict.SETTLED : Verdict.PAST;
        }

        /** События следующей метки будут живыми, если и она — ход. */
        boolean steady() {
            return streak >= SETTLE;
        }

        void reset() {
            streak = 0;
            count = 0;
        }
    }
}
