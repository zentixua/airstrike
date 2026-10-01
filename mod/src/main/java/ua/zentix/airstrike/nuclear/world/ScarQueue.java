package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Очередь чанков на повреждения (DESIGN-nuke §3.1). Чанк ставится в очередь, когда он загружен и в радиусе
 * подрыва, чей номер новее отметки {@code chunk_scar} на чанке. Обрабатывается не раньше, чем до него дошла волна
 * (загруженные позже — сразу), целиком одной единицей работы под общим бюджетом времени: руины ставятся записью
 * плана в секции ({@link RuinPlan}) — по плану, построенному ещё во время полёта МБР ({@link NuclearPrep}), или по плану
 * на месте. После руин ставится отметка; выгрузился до них — отметки нет, при следующей загрузке пройдёт заново.
 * <p>
 * Чанк в очереди, пока он в памяти, а не пока он полностью загружен: у края видимости чанк то и дело опускается
 * ниже полной загрузки и поднимается обратно, не выгружаясь, — и {@code ChunkEvent.Load} при этом больше не
 * приходит (он один на жизнь чанка в памяти). Такой чанк ждёт в очереди, как и чанк без загруженных соседей;
 * снимок подрыва берёт и его. Иначе он выпадал из очереди навсегда — полосы нетронутых чанков в зоне.
 * <p>
 * Чанк на краю загруженного мира (сам загружен, соседи — нет) соседей не ждёт: готовому плану они не нужны, план
 * в фоне берёт их снимки с диска ({@link #work}). Ждут только подмена через мир у края и у аппаратов (радиус 1) и
 * план в потоке сервера (окно): такой чанк держит тикет с соседями ({@link NuclearTickets#holdForScar}) — ваниль
 * грузит их в фоне, чанк проходится, тикет снимается. Чанки под живым тикетом ({@link #underHold}: квадрат ± радиус
 * тикета) сами тикет не берут — иначе загрузка расползлась бы на весь радиус.
 * <p>
 * В момент подрыва загружены тысячи чанков: и их снимок, и постановка в очередь идут уже под бюджетом
 * ({@link #scanLoaded}), в тике подрыва — ничего.
 */
public final class ScarQueue {
    /** Работа по одному чанку: подрывы по порядку номеров. */
    private static final class Job {
        final long chunk;
        final List<Detonation> events = new ArrayList<>();
        int event;
        long due;
        /** Когда до чанка дошла волна текущего подрыва (срок без повторов «ждём соседей») — от него отставание. */
        long wave;
        /** Может держать тикет с соседями: загружен не нашим тикетом. */
        final boolean mayHold;
        /** Держит тикет с соседями: радиус тикета (0 — не держит). */
        int held;
        /** Срок пришёл: в очереди готовых ({@link #readySeen}/{@link #readyUnseen}), а не в {@link #byDue}. */
        boolean ready;
        /** Ждёт соседей (край загруженного мира): работы у подрыва от него может не быть никогда. */
        boolean waitsNeighbours;
        /** Какой радиус соседей ждёт ({@link #waitNeighbours}; 0 — сам ниже полной загрузки). */
        int needs;
        /** Тикет с соседями взят за отпущенный квадрат зоны ({@link #holdForTile}): в счёте {@link #tileHolds}. */
        boolean tileHold;
        /**
         * Когда чанк без руин впервые попросился к игроку после волны ({@link #withholds}; {@link Long#MIN_VALUE} — не
         * просился): от этого — срок удержания и сколько игрок ждал руин.
         */
        long asked = Long.MIN_VALUE;

        Job(long chunk, boolean mayHold) {
            this.chunk = chunk;
            this.mayHold = mayHold;
        }
    }

    /** Загруженные при подрыве чанки в его радиусе (снимок — первой единицей работы); {@code next} — первый не поставленный. */
    private static final class Scan {
        final Detonation d;
        /** Чанки с готовыми руинами — ближние к эпицентру первыми: в очередь раньше всех. */
        final long[] first;
        @Nullable
        long[] chunks;
        int next;

        Scan(Detonation d, long[] first) {
            this.d = d;
            this.first = first;
        }
    }

    /** Сколько чанков ставить в очередь за одну единицу работы (постановка — микросекунды). */
    private static final int OFFERS_PER_UNIT = 64;
    /**
     * За сколько тиков до прихода стены к центру чанка его уже можно разрушать: у эпицентра фронт идёт быстро (сотни
     * чанков за тик), и тик раньше под стеной пыли не видно, а работа растягивается на лишний тик; дальше стена идёт
     * 3 блока за тик — тик раньше это 3 блока.
     */
    private static final int EARLY = 1;
    /** Через сколько тиков снова проверить чанк, который (или чьи соседи) сейчас ниже полной загрузки. */
    private static final int NEIGHBOUR_RETRY = 10;
    /** Итог единицы {@link #ruin}: руины стоят; ещё единица (посчитан разлом соседа, план устарел); ждать соседей ({@link #waitRadius}). */
    private static final int RUINED = 0, AGAIN = 1, WAIT = 2;

    /** Тикетов с соседями за отпущенные квадраты зоны ({@link #holdForTile}) сразу, не больше. */
    private static final int TILE_HOLDS = 64;
    private int tileHolds;
    /** Самое большое число {@link #tileHolds} разом (строка «ДИАГ»). */
    private int tileHoldsPeak;

    /** Радиус соседей, которых ждёт подмена, вернувшая {@code WAIT} ({@link RuinPlan#waitsNeighbours}). */
    private int waitRadius;

    /**
     * Готовые планы, чья подмена ждёт соседей ({@link RuinPlan#neighbourRadius}): план, собранный из фоновой
     * задачи, не теряется, пока соседи грузятся.
     */
    private final Long2ObjectOpenHashMap<Parked> parked = new Long2ObjectOpenHashMap<>();

    private record Parked(int detonation, RuinPlan plan) {}

    private final Long2ObjectOpenHashMap<Job> jobs = new Long2ObjectOpenHashMap<>();
    /** Чанки, которые держат живые тикеты с соседями (квадрат держащего ± {@link RuinPlanner#REACH}): сколько тикетов. */
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap underHold = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private long lastSlowChunk = Long.MIN_VALUE / 2;
    private final PriorityQueue<Job> byDue = new PriorityQueue<>(Comparator.comparingLong(j -> j.due));
    /**
     * Чанки, чей срок пришёл: сперва те, что видит игрок (их руины — ровно с фронтом, изменения уходят ему в том же
     * тике), потом остальные (их никто не видит — отставание на несколько тиков не заметно; они всё равно доходят
     * до руин, на диск и в LOD Distant Horizons).
     */
    private record Log(long at, net.minecraft.world.level.block.state.BlockState state) {}

    private final ArrayDeque<Job> readySeen = new ArrayDeque<>(), readyUnseen = new ArrayDeque<>();
    /** Стволы, упавшие в чанк, чьи руины ещё впереди: кладутся после его руин (иначе они старили бы его план). */
    private final Long2ObjectOpenHashMap<List<Log>> logs = new Long2ObjectOpenHashMap<>();
    private final Map<Integer, ColumnScar.Budget> budgets = new HashMap<>();
    private final ArrayDeque<Scan> scans = new ArrayDeque<>();
    /** Руины, построенные во время полёта МБР ({@link NuclearPrep}): номер подрыва → чанк → план. */
    private final Map<Integer, Long2ObjectOpenHashMap<RuinPlan>> prepared = new HashMap<>();
    /** Для проверок и статуса: чанков с руинами по готовому плану и построенных в момент прихода волны. */
    private int appliedPrepared, appliedFresh, stalePlans;
    /**
     * По подрыву: [0] руин по готовому плану, [1] устаревших планов, [2] наибольшее отставание от прихода волны
     * у чанков, которые видит игрок (тики), [3] время подмен по готовому плану всего и [4] самой долгой (нс),
     * [5] наибольшее отставание у остальных, [6] руин по плану на месте, [7] время плана и подмены на месте всего
     * и [8] самых долгих (нс, в потоке сервера), [9] чанков в памяти при подрыве, [10] из них с загруженными соседями
     * ({@link RuinPlanner#REACH}), [11] из них по готовому плану, [12] чанков, ушедших игроку до своих руин,
     * [13] чанков, загруженных после подрыва, [14] и [15] их отставание от волны всего и наибольшее (тики),
     * [16] загруженных после подрыва с готовым планом (с диска), [17] и [18] их отставание всего и наибольшее, [19] чанков
     * в памяти при подрыве, которые видел игрок, без загруженных соседей (плана заранее нет — окно не снять), [20] чанков
     * с загруженными соседями ([10]) по готовому плану — доля [20]/[10] из одного множества ([11] — из всех [9]),
     * [21] соседей окон очереди, прочитанных с диска ({@link RuinContext#requestWindow}), [22] из них нет на диске целыми,
     * [23] наибольшее ожидание игрока: от первой просьбы чанка к игроку после волны ({@link #withholds}) до его руин (тики).
     */
    private final Map<Integer, long[]> preparedStats = new HashMap<>();
    private static final int STATS = 24;
    /**
     * Чанки в памяти при подрыве, чьи руины ещё не встали (по номеру подрыва): когда пусто — строки критериев в лог сразу,
     * не дожидаясь, пока очередь разойдётся вся (сценарий проверки мог кончиться раньше).
     */
    private final Map<Integer, it.unimi.dsi.fastutil.longs.LongOpenHashSet> awaitingMemory = new HashMap<>();
    /** Чанки, ушедшие игроку до своих руин (по номеру подрыва): в сводку — каждый один раз. */
    private final Map<Integer, it.unimi.dsi.fastutil.longs.LongOpenHashSet> sentEarly = new HashMap<>();
    /** Чанки, чьи руины встали (по номеру подрыва): ход зоны за волной ({@link NuclearPrep}). */
    private final Map<Integer, it.unimi.dsi.fastutil.longs.LongOpenHashSet> ruinedBy = new HashMap<>();
    /** Чанки в памяти при подрыве (по номеру подрыва): для доли руин по готовому плану. */
    private final Map<Integer, it.unimi.dsi.fastutil.longs.LongOpenHashSet> atDetonation = new HashMap<>();
    /** Из них — с загруженными соседями при подрыве ({@link RuinPlanner#REACH}): знаменатель доли критерия А. */
    private final Map<Integer, it.unimi.dsi.fastutil.longs.LongOpenHashSet> withNeighbours = new HashMap<>();
    /** Работа, чей план строят фоновые потоки ({@link RuinContext#submit}): в очередь готовых — когда план готов. */
    private final List<Job> background = new ArrayList<>();
    /**
     * Сколько тиков от первой просьбы чанка к игроку после прихода волны чанк без руин не уходит игроку ({@link #withholds}):
     * дольше — уходит как есть
     * (и считается в сводке «ушло игроку до руин»), чтобы у игрока не оставалось дыр, если руины не встают.
     */
    private static final int WITHHOLD_LIMIT = 200;

    public int size() {
        return jobs.size();
    }

    /**
     * Подрыв: загруженные сейчас чанки в его радиусе поставит в очередь {@link #work} — и снимок их координат, и сами
     * чанки (и их отметки) читаются уже под бюджетом. В тике подрыва — ничего.
     */
    public void scanLoaded(Detonation d) {
        scans.add(new Scan(d, new long[0]));
    }

    /**
     * Подрыв, для которого руины построены заранее: их чанки (ближние первыми) встают в очередь раньше остальных
     * загруженных — к приходу волны они уже там.
     */
    public void scanLoaded(Detonation d, Long2ObjectOpenHashMap<RuinPlan> plans, long[] order) {
        prepared.put(d.id(), plans);
        scans.add(new Scan(d, order));
    }

    /** Остались ли неиспользованные руины подрыва (их чанки держит {@link NuclearPrep}). */
    public boolean hasPrepared(int detonation) {
        Long2ObjectOpenHashMap<RuinPlan> plans = prepared.get(detonation);
        return plans != null && !plans.isEmpty();
    }

    /** Руины подрыва больше не нужны (их чанки отпущены); сводка — в лог. */
    public void dropPrepared(int detonation) {
        Long2ObjectOpenHashMap<RuinPlan> left = prepared.remove(detonation);
        long[] st = preparedStats.remove(detonation);
        if (st == null) st = new long[STATS];
        Airstrike.LOG.info("Руины подрыва №{}: по готовому плану {}, план устарел {}, на месте {}, не дождались {}; отставание от волны до {} тиков "
                        + "(у игроков до {}, у остальных до {}); подмена чанка в среднем {} мкс, самая долгая {} мс",
                detonation, st[0], st[1], st[6], left == null ? 0 : left.size(), Math.max(st[2], st[5]), st[2], st[5],
                st[0] == 0 ? 0 : st[3] / st[0] / 1000, String.format(java.util.Locale.ROOT, "%.1f", st[4] / 1e6));
        if (st[6] > 0) {
            Airstrike.LOG.info("Руины подрыва №{}: план и подмена на месте — в среднем {} мс, самые долгие {} мс", detonation, ms(st[7] / st[6]), ms(st[8]));
        }
        criteria(detonation, st, "всё");
        // строка «встали руины всех чанков, бывших в памяти» после сводки уже не пишется: её счётчики сняты вместе со сводкой
        var unplaced = awaitingMemory.remove(detonation);
        if (unplaced != null && !unplaced.isEmpty()) {
            Airstrike.LOG.info("Руины подрыва №{}: из чанков в памяти при подрыве руин ещё нет у {} (ждут соседей у края загруженного мира)", detonation, unplaced.size());
        }
        sentEarly.remove(detonation);
        atDetonation.remove(detonation);
        withNeighbours.remove(detonation);
        ruinedBy.remove(detonation);
        if (left != null && !left.isEmpty()) {
            // почему не дождались: чанк так и стоял в очереди (соседи не загрузились) или в очередь не попал (выгружен)
            StringBuilder some = new StringBuilder();
            int queued = 0, shown = 0;
            for (long c : left.keySet()) {
                boolean inQueue = jobs.containsKey(c);
                if (inQueue) queued++;
                if (shown++ < 5) some.append(shown > 1 ? ", " : "").append(new ChunkPos(c)).append(inQueue ? " в очереди" : "");
            }
            Airstrike.LOG.info("Руины подрыва №{}: не дождались {} чанков, из них в очереди {}: {}", detonation, left.size(), queued, some);
        }
        long[] ph = RuinPlan.PHASES;
        Airstrike.LOG.info("Руины: подмены по частям (всего с запуска, мс) — проверка {}, секции {}, карты высот {}, свет и пакеты {}, блок-сущности {}",
                ph[0] / 1_000_000, ph[1] / 1_000_000, ph[2] / 1_000_000, ph[3] / 1_000_000, ph[4] / 1_000_000);
        if (RuinPlan.slowestWorldNanos > 0) {
            Airstrike.LOG.info("Руины: дольше всего через мир — чанк {}: {} мест, {} мс (блок-сущности, POI, LOD Distant Horizons); "
                            + "самый долгий блок {} — {} мс, LOD Distant Horizons {} мс",
                    new ChunkPos(RuinPlan.slowestWorldChunk), RuinPlan.slowestWorldCells, ms(RuinPlan.slowestWorldNanos),
                    RuinPlan.slowestWorldBlock == null ? "—" : BuiltInRegistries.BLOCK.getKey(RuinPlan.slowestWorldBlock.getBlock()),
                    ms(RuinPlan.slowestWorldBlockNanos), ms(RuinPlan.slowestWorldDhNanos));
            RuinPlan.slowestWorldNanos = 0;
            RuinPlan.slowestWorldBlock = null;
        }
        // стволы, отложенные до руин чанка, который так и не встал в очередь (выгрузился с готовым планом)
        if (left != null) {
            for (long c : left.keySet()) if (!jobs.containsKey(c)) logs.remove(c);
        }
    }

    /**
     * Строки критериев проверки (А: доля руин по готовому плану у чанков в памяти при подрыве, отставание у игроков;
     * Б: ушло игроку до руин): когда встали руины всех чанков, бывших в памяти при подрыве, и в конце.
     */
    private static void criteria(int detonation, long[] st, String when) {
        Airstrike.LOG.info("Руины подрыва №{} ({}): отставание от волны у игроков до {} тиков, у остальных до {}; план устарел {}; "
                + "игрок ждал руин чанка до {} тиков (от первой просьбы чанка после волны)", detonation, when, st[2], st[5], st[1], st[23]);
        Airstrike.LOG.info("Руины подрыва №{}: в памяти при подрыве {} чанков, из них с загруженными соседями {}; по готовому плану {} ({} % от всех, "
                        + "{} % из чанков с соседями); на экране игрока без соседей {}",
                detonation, st[9], st[10], st[11], percent(st[11], st[9]), percent(st[20], st[10]), st[19]);
        Airstrike.LOG.info("Руины подрыва №{}: ушло игроку до руин: {} (разных чанков)", detonation, st[12]);
        Airstrike.LOG.info("Руины подрыва №{}: окна с диска — прочитано соседей {}, нет на диске целыми {} (в окне — сплошной массив, как край мира)",
                detonation, st[21], st[22]);
        Airstrike.LOG.info("Руины подрыва №{}: загружены после подрыва {} чанков по плану на месте — руины после волны в среднем через {} тиков, самое большее через {}; "
                        + "{} по готовому плану — в среднем через {}, самое большее через {}",
                detonation, st[13], st[13] == 0 ? 0 : st[14] / st[13], st[15], st[16], st[16] == 0 ? 0 : st[17] / st[16], st[18]);
    }

    private static String percent(long part, long whole) {
        return whole == 0 ? "—" : String.format(java.util.Locale.ROOT, "%.1f", 100.0 * part / whole);
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1e6);
    }

    /**
     * У подрыва ещё есть работа: готовые руины, снимок загруженных чанков или чанки в очереди, кроме ждущих соседей
     * (край загруженного мира может ждать их, пока игрок не подойдёт, — часами).
     */
    public boolean pending(int detonation) {
        if (prepared.containsKey(detonation)) return true;
        for (Scan s : scans) if (s.d.id() == detonation) return true;
        for (Job j : jobs.values()) {
            if (j.waitsNeighbours) continue;
            for (int e = j.event; e < j.events.size(); e++) if (j.events.get(e).id() == detonation) return true;
        }
        return false;
    }

    /** Руины чанка от подрыва встали (только у подрывов с руинами заранее, пока они помнятся). */
    public boolean ruined(int detonation, long chunk) {
        var set = ruinedBy.get(detonation);
        return set != null && set.contains(chunk);
    }

    /** Чанк ждёт в очереди повреждений. */
    public boolean queued(long chunk) {
        return jobs.containsKey(chunk);
    }

    /** Для {@link NukeDiag}: что сейчас с работой чанка в очереди (null — работы нет). */
    @Nullable
    public String diagState(long chunk, long now) {
        Job j = jobs.get(chunk);
        if (j == null) return null;
        if (background.contains(j)) return "план в фоне";
        if (parked.containsKey(chunk)) return "план ждёт соседей, свой тикет r" + j.held;
        if (j.waitsNeighbours) return "ждёт соседей, свой тикет r" + j.held + (j.mayHold ? "" : " (под чужим тикетом)");
        if (j.ready) return "в очереди готовых";
        return j.due > now ? "срок через " + (j.due - now) : "срок пришёл";
    }

    /**
     * Квадрат зоны за волной ({@link NuclearPrep}) отпускает свой тикет, а чанк в нём ещё ждёт соседей: дальше он держит
     * себя и соседей сам — тикетом радиуса, которого ждёт, — пока его руины не встанут. Иначе чанк, загруженный под
     * чужим тикетом с соседями ({@link Job#mayHold} — нет), ждал соседей, которых никто не грузит, а квадрат ждал его:
     * все слоты зоны стояли (диагностика 01.10.2026: шесть готовых квадратов по 500–7000 тиков, остальные 463 ждали).
     *
     * @return чанк ждёт соседей и держит их сам (или ему нечего ждать); false — ждёт другого (план, срок, свою загрузку)
     */
    public boolean holdForTile(ServerLevel level, long chunk) {
        Job j = jobs.get(chunk);
        if (j == null) return true;
        if (!j.waitsNeighbours || j.needs <= 0) return false;
        if (j.held < j.needs) {
            unhold(level, j);
            hold(level, j, j.needs);
            j.tileHold = true;
            tileHolds++;
            tileHoldsPeak = Math.max(tileHoldsPeak, tileHolds);
        }
        return true;
    }

    /** Удержаний за отпущенные квадраты сейчас и самое большее разом (строка «ДИАГ»). */
    public String tileHoldsDiag() {
        return "сейчас " + tileHolds + ", пик " + tileHoldsPeak + " из " + TILE_HOLDS;
    }

    /**
     * Сколько ещё тикетов {@link #holdForTile} можно взять: у каждого до 25 чанков в памяти (радиус до
     * {@link RuinPlanner#REACH}), и держится он, пока соседи грузятся и руины встают, — обычно десятки тиков. Квадрат,
     * которому не хватает, ждёт, пока прежние отпустят свои.
     */
    public int tileHoldsLeft() {
        return TILE_HOLDS - tileHolds;
    }

    /** Ждёт ли работа чанка соседей ({@link #holdForTile} может отпустить его квадрат). */
    public boolean waitsNeighbours(long chunk) {
        Job j = jobs.get(chunk);
        return j != null && j.waitsNeighbours && j.needs > 0;
    }

    /** Не поставленные ещё готовые руины подрыва: есть ли план у чанка. */
    public boolean pendingPlan(int detonation, long chunk) {
        Long2ObjectOpenHashMap<RuinPlan> plans = prepared.get(detonation);
        return plans != null && plans.containsKey(chunk);
    }

    /** По готовому плану, построенных на месте, устаревших планов (проверки, статус). */
    public int[] ruinStats() {
        return new int[]{appliedPrepared, appliedFresh, stalePlans};
    }

    /**
     * Координаты чанков в памяти в радиусе подрыва. Чанк, который ещё ни разу не был полностью загружен, поставит
     * {@code onChunkLoad}, когда загрузится.
     */
    private static long[] loadedInRange(ServerLevel level, Detonation d, long[] except) {
        double radius = d.ruinRadius();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet skip = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(except);
        LongArrayList in = new LongArrayList();
        var chunkMap = level.getChunkSource().chunkMap;
        for (ChunkHolder holder : chunkMap.getChunks()) {
            ChunkPos p = holder.getPos();
            if (nearest(p, d) <= radius && skip.add(p.toLong())) in.add(p.toLong());
        }
        // ждущие выгрузки — тоже в памяти: вернувшийся тикет оставит их без ChunkEvent.Load (NuclearTickets.inMemory)
        for (long c : chunkMap.pendingUnloads.keySet()) {
            if (nearest(new ChunkPos(c), d) <= radius && skip.add(c)) in.add(c);
        }
        return in.toLongArray();
    }

    /** Поставить чанк в очередь по подрыву (если чанк в радиусе и подрыв новее отметки на чанке). */
    public void offer(LevelChunk chunk, Detonation d) {
        if (!inRange(chunk.getPos(), d)) return;
        // без отметки — ни одного подрыва ещё не было; getData повесил бы отметку 0 на каждый чанк в радиусе
        int applied = chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0);
        if (d.id() <= applied) return;
        long key = chunk.getPos().toLong();
        Job job = jobs.get(key);
        if (job != null && job.events.stream().anyMatch(e -> e.id() == d.id())) return;
        if (job == null) {
            job = new Job(key, !underHold.containsKey(key));
            jobs.put(key, job);
        } else if (!job.ready) {
            byDue.remove(job);
        }
        if (job.events.stream().noneMatch(e -> e.id() == d.id())) {
            job.events.add(d);
            job.events.sort(Comparator.comparingInt(Detonation::id));
        }
        // в очереди готовых — срок уже пришёл; новый подрыв он возьмёт следующим
        if (job.ready) return;
        job.due = job.wave = due(job.events.get(job.event), chunk.getPos());
        byDue.add(job);
    }

    /**
     * Не отдавать чанк игроку: волна до него дошла, а руин ещё нет (поток сервера, {@code PlayerChunkSenderMixin}).
     * Не дольше {@link #WITHHOLD_LIMIT} тиков от первой просьбы после волны (не от самой волны: чанк, до которого зона
     * за волной ещё не дошла, игрок загружает и через тысячи тиков после неё): руины, которые не встают, не оставляют
     * у игрока дыру.
     */
    public boolean withholds(long chunk, long now) {
        Job job = jobs.get(chunk);
        if (job == null || job.event >= job.events.size() || job.wave > now) return false;
        if (job.asked == Long.MIN_VALUE) job.asked = now;
        return now - job.asked < WITHHOLD_LIMIT;
    }

    /** Есть ли чанки, которые, может быть, нельзя отдавать игрокам (быстрая проверка перед перебором). */
    public boolean mayWithhold() {
        return !jobs.isEmpty();
    }

    /** Чанк ушёл игроку: если волна до него дошла, а руин нет — в сводку подрыва. */
    public void sent(long chunk, long now) {
        Job job = jobs.get(chunk);
        if (job == null || job.event >= job.events.size() || job.wave > now) return;
        int id = job.events.get(job.event).id();
        if (sentEarly.computeIfAbsent(id, k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet()).add(chunk)) preparedStats.computeIfAbsent(id, k -> new long[STATS])[12]++;
    }

    /** Чанки, держащие тикет с соседями (проверки). */
    public long[] heldChunks() {
        return jobs.values().stream().filter(j -> j.held > 0).mapToLong(j -> j.chunk).toArray();
    }

    private void hold(ServerLevel level, Job job, int radius) {
        NuclearTickets.holdForScar(level, new ChunkPos(job.chunk), true, radius);
        job.held = radius;
        mark(job.chunk, 1, radius);
    }

    /** Работа снята или кончилась: тикет с соседями и собранный план — отпустить. */
    private void release(ServerLevel level, Job job) {
        parked.remove(job.chunk);
        unhold(level, job);
    }

    private void unhold(ServerLevel level, Job job) {
        if (job.tileHold) {
            job.tileHold = false;
            tileHolds--;
        }
        if (job.held == 0) return;
        NuclearTickets.holdForScar(level, new ChunkPos(job.chunk), false, job.held);
        mark(job.chunk, -1, job.held);
        job.held = 0;
    }

    /** Квадрат тикета держащего чанка (± радиус тикета): учёт в {@link #underHold}. */
    private void mark(long chunk, int delta, int r) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long k = ChunkPos.asLong(cx + dx, cz + dz);
                if (underHold.addTo(k, delta) + delta <= 0) underHold.remove(k);
            }
        }
    }

    /** Руины чанка встали (или чанк ушёл из памяти): все чанки в памяти при подрыве стоят — строки критериев в лог. */
    private void placedFromMemory(int detonation, long chunk) {
        var waiting = awaitingMemory.get(detonation);
        if (waiting == null) return;
        waiting.remove(chunk);
        if (!waiting.isEmpty()) return;
        awaitingMemory.remove(detonation);
        criteria(detonation, preparedStats.computeIfAbsent(detonation, k -> new long[STATS]), "встали руины всех чанков, бывших в памяти при подрыве");
    }

    /** Выгружен: работа снимается (чанк с тикетом не выгружается — тикет снят раньше, в {@link #clear}). */
    public void drop(ServerLevel level, ChunkPos pos) {
        for (int id : new ArrayList<>(awaitingMemory.keySet())) placedFromMemory(id, pos.toLong());
        Job job = jobs.remove(pos.toLong());
        if (job != null) release(level, job);
        // из очереди готовых снимется сам: работа берётся, только если она ещё в jobs
        if (job != null && !job.ready) byDue.remove(job);
        logs.remove(pos.toLong());
    }

    /** Счётчики пожаров только для подрывов, которые ещё помнятся. */
    public void retainBudgets(java.util.Set<Integer> detonations) {
        budgets.keySet().retainAll(detonations);
        // сводка — только у подрывов с готовыми руинами (её пишет dropPrepared), у остальных забывается
        preparedStats.keySet().removeIf(id -> !detonations.contains(id) && !prepared.containsKey(id));
        atDetonation.keySet().removeIf(id -> !detonations.contains(id) && !prepared.containsKey(id));
        withNeighbours.keySet().retainAll(atDetonation.keySet());
        ruinedBy.keySet().removeIf(id -> !detonations.contains(id) && !prepared.containsKey(id));
        sentEarly.keySet().removeIf(id -> !detonations.contains(id) && !prepared.containsKey(id));
        awaitingMemory.keySet().removeIf(id -> !detonations.contains(id) && !prepared.containsKey(id));
    }

    public void clear(ServerLevel level) {
        jobs.values().forEach(j -> release(level, j));
        underHold.clear();
        tileHolds = 0;
        tileHoldsPeak = 0;
        scans.clear();
        prepared.clear();
        preparedStats.clear();
        atDetonation.clear();
        withNeighbours.clear();
        ruinedBy.clear();
        sentEarly.clear();
        awaitingMemory.clear();
        background.clear();
        jobs.clear();
        byDue.clear();
        readySeen.clear();
        readyUnseen.clear();
        logs.clear();
        budgets.clear();
    }

    private static boolean inRange(ChunkPos p, Detonation d) {
        return nearest(p, d) <= d.ruinRadius();
    }

    /** Наклонная дальность от точки подрыва до ближайшего места чанка на уровне земли. */
    private static double nearest(ChunkPos p, Detonation d) {
        double x = Math.max(p.getMinBlockX(), Math.min(d.burst().x, p.getMaxBlockX() + 1));
        double z = Math.max(p.getMinBlockZ(), Math.min(d.burst().z, p.getMaxBlockZ() + 1));
        double dx = x - d.burst().x, dz = z - d.burst().z, dy = d.burst().y - d.groundY();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Когда до чанка доходит стена: по его центру, не по ближнему краю — фронт идёт 3 блока за тик, и по краю дальняя
     * сторона чанка менялась бы секунду до стены.
     */
    private static long due(Detonation d, ChunkPos p) {
        double dx = p.getMiddleBlockX() + 0.5 - d.burst().x, dz = p.getMiddleBlockZ() + 0.5 - d.burst().z, dy = d.burst().y - d.groundY();
        return d.gameTime() + (long) d.arrivalTicks(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    /**
     * Обработать, что успеем за бюджет. Чанк, упавший с ошибкой, снимается с очереди один (со своим тикетом), без
     * отметки: при следующей загрузке он пройдёт заново.
     *
     * @param clock бюджет тика: за столбец берёмся, только если он успеет
     */
    public void work(ServerLevel level, long now, WorkClock clock) {
        // сначала — в очередь чанки, загруженные при подрывах. Порядок здесь не важен: очередь сама идёт по приходу
        // волны, а на постановку тысяч чанков уходит несколько тиков — волна за это время проходит пару чанков
        while (!scans.isEmpty() && clock.canStart()) {
            Scan scan = scans.peek();
            long c0 = clock.begin();
            try {
                if (scan.chunks == null) {
                    long[] loaded = loadedInRange(level, scan.d, scan.first);
                    scan.chunks = new long[scan.first.length + loaded.length];
                    System.arraycopy(scan.first, 0, scan.chunks, 0, scan.first.length);
                    System.arraycopy(loaded, 0, scan.chunks, scan.first.length, loaded.length);
                } else {
                    // выгрузился после подрыва — пропускаем; загрузится снова — поставит onChunkLoad
                    int end = Math.min(scan.chunks.length, scan.next + OFFERS_PER_UNIT);
                    long[] st = preparedStats.computeIfAbsent(scan.d.id(), k -> new long[STATS]);
                    var memory = atDetonation.computeIfAbsent(scan.d.id(), k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
                    while (scan.next < end) {
                        long c = scan.chunks[scan.next++];
                        LevelChunk chunk = NuclearTickets.inMemory(level, c);
                        if (chunk == null) continue;
                        offer(chunk, scan.d);
                        if (!inRange(chunk.getPos(), scan.d) || !memory.add(c)) continue;
                        st[9]++;
                        if (NuclearTickets.neighbourhoodLoaded(level, chunk.getPos(), RuinPlanner.REACH)) {
                            st[10]++;
                            withNeighbours.computeIfAbsent(scan.d.id(), k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet()).add(c);
                        }
                        else if (!level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false).isEmpty()) st[19]++;
                    }
                }
            } catch (RuntimeException e) {
                // чанки, загруженные потом, поставит onChunkLoad
                Airstrike.LOG.error("Постановка чанков подрыва №{} в очередь упала с ошибкой; снята", scan.d.id(), e);
                scans.poll();
                continue;
            } finally {
                clock.end(c0);
            }
            if (scan.next >= scan.chunks.length) {
                scans.poll();
                // чанки в памяти при подрыве, чьи руины ещё впереди: строки критериев — когда встанут все
                var memory = atDetonation.get(scan.d.id());
                if (memory != null) {
                    var waiting = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                    for (long c : memory) {
                        Job j = jobs.get(c);
                        if (j != null && j.event < j.events.size() && j.events.subList(j.event, j.events.size()).contains(scan.d)) waiting.add(c);
                    }
                    awaitingMemory.put(scan.d.id(), waiting);
                    placedFromMemory(scan.d.id(), Long.MIN_VALUE);
                }
            }
        }
        // фоновые планы, которые готовы, — первыми в очередь готовых: их чанки ждут с прихода волны
        for (java.util.Iterator<Job> it = background.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (jobs.get(job.chunk) != job) {
                it.remove();
                continue;
            }
            // план в работе — ждём; готов или задачи уже нет (план забрала подготовка руин или задачу бросили) — в очередь
            RuinContext ctx = NuclearWorld.get(level).ruins(job.events.get(job.event), now);
            if (ctx.running(job.chunk) && !ctx.done(job.chunk)) continue;
            it.remove();
            job.ready = true;
            (level.getChunkSource().chunkMap.getPlayers(new ChunkPos(job.chunk), false).isEmpty() ? readyUnseen : readySeen).addFirst(job);
        }
        var chunkMap = level.getChunkSource().chunkMap;
        while (!byDue.isEmpty() && byDue.peek().due <= now + EARLY) {
            Job job = byDue.poll();
            job.ready = true;
            (chunkMap.getPlayers(new ChunkPos(job.chunk), false).isEmpty() ? readyUnseen : readySeen).add(job);
        }
        while (clock.canStart()) {
            Job job = !readySeen.isEmpty() ? readySeen.poll() : readyUnseen.poll();
            if (job == null) return;
            job.ready = false;
            if (jobs.get(job.chunk) != job) continue; // выгружен или снят
            try {
                work(level, job, now, clock);
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Повреждения чанка {} упали с ошибкой; чанк снят с очереди", new ChunkPos(job.chunk), e);
                jobs.remove(job.chunk);
                logs.remove(job.chunk);
                release(level, job);
            }
        }
    }

    /**
     * Руины в чанке: по готовому плану, если он ещё верен, иначе — план по чанку как есть. План на месте ждёт разломов
     * окна: холодный разлом соседа — своя единица работы; false — посчитан он, руины — следующей единицей.
     */
    private int ruin(ServerLevel level, Detonation d, LevelChunk chunk, ColumnScar.Budget budget, long lag, boolean seen) {
        long key = chunk.getPos().toLong();
        Long2ObjectOpenHashMap<RuinPlan> plans = prepared.get(d.id());
        RuinPlan plan = plans != null ? plans.get(key) : null;
        if (plan != null && (waitRadius = plan.waitsNeighbours(level, chunk)) > 0) return WAIT;
        if (plan != null) plans.remove(key);
        long[] st = preparedStats.computeIfAbsent(d.id(), k -> new long[STATS]);
        if (seen) st[2] = Math.max(st[2], lag);
        else st[5] = Math.max(st[5], lag);
        if (plan != null) {
            long t0 = System.nanoTime();
            if (plan.apply(level, chunk, budget)) {
                long took = System.nanoTime() - t0;
                appliedPrepared++;
                st[0]++;
                st[3] += took;
                st[4] = Math.max(st[4], took);
                var memory = atDetonation.get(d.id());
                if (memory != null && memory.contains(key)) {
                    st[11]++;
                    var near = withNeighbours.get(d.id());
                    if (near != null && near.contains(key)) st[20]++;
                } else {
                    // загружен после подрыва, план — готовый (с диска, фаза 2)
                    st[16]++;
                    st[17] += lag;
                    st[18] = Math.max(st[18], lag);
                }
                deferLogs(level, d, plan);
                return RUINED;
            }
            stalePlans++;
            st[1]++;
            // устарел: план на месте — следующими единицами (фоновый или разломы окна)
            return AGAIN;
        }
        long t0 = System.nanoTime();
        RuinContext ctx = NuclearWorld.get(level).ruins(d, level.getGameTime());
        Parked kept = parked.get(key);
        if (kept != null && kept.detonation() == d.id()) {
            plan = kept.plan();
            if ((waitRadius = plan.waitsNeighbours(level, chunk)) > 0) return WAIT;
            parked.remove(key);
        } else if (background(ctx, key)) {
            // фоновый план готов ({@link #work} берёт его, только когда готов): достроить и поставить
            plan = ctx.collect(level, chunk);
            if (plan == null) return AGAIN;
            if ((waitRadius = plan.waitsNeighbours(level, chunk)) > 0) {
                parked.put(key, new Parked(d.id(), plan));
                return WAIT;
            }
        } else {
            if (!RuinPlanner.blastsReady(level, d, chunk)) return AGAIN;
            plan = RuinPlanner.plan(level, d, chunk);
        }
        plan.apply(level, chunk, budget);
        var memory = atDetonation.get(d.id());
        if (memory == null || !memory.contains(key)) {
            st[13]++;
            st[14] += lag;
            st[15] = Math.max(st[15], lag);
        }
        long took = System.nanoTime() - t0;
        appliedFresh++;
        st[6]++;
        st[7] += took;
        st[8] = Math.max(st[8], took);
        deferLogs(level, d, plan);
        return RUINED;
    }

    /** Есть готовый план чанка по подрыву: построенный заранее или собранный и ждущий соседей. */
    private boolean hasPlan(int detonation, long chunk) {
        Parked p = parked.get(chunk);
        return pendingPlan(detonation, chunk) || p != null && p.detonation() == detonation;
    }

    /** План чанка строят фоновые потоки: разрушения включены и его фоновый план не падал. */
    private static boolean background(RuinContext ctx, long chunk) {
        return ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeBlockDamage.get() && !ctx.failed(chunk);
    }

    /**
     * Чанк ждёт соседей (или сам поднимется до полной загрузки): снова через {@link #NEIGHBOUR_RETRY}. Полностью
     * загруженный край сам просит соседей тикетом радиуса {@code radius} (0 — не просит); под чужим живым тикетом — не
     * берёт: он и так стоит в загруженном квадрате, а свой растянул бы загрузку.
     */
    private void waitNeighbours(ServerLevel level, Job job, long now, int radius) {
        job.needs = radius;
        // свой тикет (обычный или за отпущенный квадрат) расширяется всегда: план устарел и ждёт r2 вместо r1, а
        // отметка самого тикета в underHold и mayHold «под чужим тикетом» иначе оставили бы его на r1 — соседей r2
        // никто не грузит, чанк держит себя и счёт квадратов вечно; рост ограничен REACH, новой цепочки нет
        boolean own = job.held > 0;
        if (radius > 0 && job.held < radius && (own || job.mayHold && !underHold.containsKey(job.chunk))) {
            // тикет за квадрат и после расширения в счёте удержаний (unhold его снимает)
            boolean tile = job.tileHold;
            unhold(level, job);
            hold(level, job, radius);
            if (tile) {
                job.tileHold = true;
                tileHolds++;
                tileHoldsPeak = Math.max(tileHoldsPeak, tileHolds);
            }
        }
        job.waitsNeighbours = true;
        job.due = now + NEIGHBOUR_RETRY;
        byDue.add(job);
    }

    /** Стволы, упавшие в соседние чанки: сразу — если руины соседа уже стоят или его нет в очереди, иначе — после них. */
    private void deferLogs(ServerLevel level, Detonation d, RuinPlan plan) {
        for (int k = 0; k < plan.outsideCount(); k++) {
            long at = plan.outsidePos(k);
            long chunk = ChunkPos.asLong(BlockPos.getX(at) >> 4, BlockPos.getZ(at) >> 4);
            Job job = jobs.get(chunk);
            // сосед ещё впереди: в очереди с этим подрывом или с готовым планом, который очередь ещё не взяла
            if (job != null ? job.events.stream().anyMatch(e -> e.id() == d.id()) && job.events.indexOf(d) >= job.event
                    : pendingPlan(d.id(), chunk)) {
                logs.computeIfAbsent(chunk, c -> new ArrayList<>()).add(new Log(at, plan.outsideState(k)));
            } else {
                RuinPlan.placeLog(level, at, plan.outsideState(k));
            }
        }
    }

    /**
     * Первый в очереди чанк (его срок пришёл): руины одной единицей работы. Что нужно от соседей, зависит от плана:
     * готовому (заранее или из фоновой задачи) — ничего, кроме радиуса 1 у подмены через мир у края и у аппаратов
     * и радиуса {@link RuinPlanner#REACH} у блок-сущностей не из ванили ({@link RuinPlan#neighbourRadius}); плану
     * в фоне — снимки окна 5×5 ({@link RuinPlanner#REACH}): соседи в памяти —
     * снимком, остальные — с диска ({@link RuinContext#requestWindow}), не загружаясь в мир; соседа нет на диске целым
     * (не сгенерирован) — в окне он сплошной массив, как край мира; плану в потоке сервера (разрушения выключены или
     * фоновый план упал) — загруженное окно, соседи грузятся тикетом.
     */
    private void work(ServerLevel level, Job job, long now, WorkClock clock) {
        if (NuclearTickets.inMemory(level, job.chunk) == null) {
            // выгружен (onChunkUnload уже убрал бы работу) — загрузится снова, поставит onChunkLoad
            jobs.remove(job.chunk);
            release(level, job);
            logs.remove(job.chunk);
            return;
        }
        ChunkPos pos = new ChunkPos(job.chunk);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        Detonation d = job.events.get(job.event);
        RuinContext ctx = NuclearWorld.get(level).ruins(d, now);
        boolean ready = hasPlan(d.id(), job.chunk), background = background(ctx, job.chunk);
        if (chunk == null) {
            // сам ниже полной загрузки (край видимости): разрушим, когда поднимется
            NukeDiag.waited(NukeDiag.Wait.BELOW_FULL);
            waitNeighbours(level, job, now, 0);
            return;
        }
        if (!ready && !background && !NuclearTickets.neighbourhoodLoaded(level, pos, RuinPlanner.REACH)) {
            // план в потоке сервера читает мир окна: соседи грузятся тикетом
            NukeDiag.waited(NukeDiag.Wait.SERVER_PLAN);
            waitNeighbours(level, job, now, RuinPlanner.REACH);
            return;
        }
        job.waitsNeighbours = false;
        boolean submit = !ready && background && !ctx.running(job.chunk);
        if (!ready && background && ctx.running(job.chunk) && !ctx.done(job.chunk)) {
            NukeDiag.waited(NukeDiag.Wait.BACKGROUND);
            this.background.add(job);
            return;
        }
        int reading = submit ? ctx.requestWindow(level, pos) : 0;
        long[] st = preparedStats.get(d.id());
        if (st != null) {
            st[21] = ctx.windowReads;
            st[22] = ctx.windowAbsent;
        }
        if (submit && (reading > 0 || !RuinWorkers.admit())) {
            // снимки соседей ещё читаются с диска или задач у подрыва много — через тик
            NukeDiag.waited(reading > 0 ? NukeDiag.Wait.WINDOW : NukeDiag.Wait.ADMIT);
            job.due = now + 1;
            byDue.add(job);
            return;
        }
        // забытый подрыв (чанк впервые загрузился спустя дни) выжигает, но не поджигает: пожары давно бы догорели
        ColumnScar.Budget budget = budgets.computeIfAbsent(d.id(), k -> new ColumnScar.Budget(!NuclearEvents.get(level).isPast(k)));
        long c0 = clock.begin();
        boolean seen = !level.getChunkSource().chunkMap.getPlayers(pos, false).isEmpty();
        try {
            if (submit) {
                // плана нет: снимки — этой единицей, план — в фоне; работа ждёт его в background
                if (ctx.submit(level, chunk)) this.background.add(job);
                else {
                    NukeDiag.waited(NukeDiag.Wait.SUBMIT);
                    job.due = now + 1;
                    byDue.add(job);
                }
                return;
            }
            int r = ruin(level, d, chunk, budget, Math.max(0, now - job.wave), seen);
            if (r == AGAIN) {
                // посчитан разлом соседа или план устарел: следующей единицей, первым в той же очереди
                NukeDiag.waited(NukeDiag.Wait.AGAIN);
                job.ready = true;
                (seen ? readySeen : readyUnseen).addFirst(job);
                return;
            }
            if (r == WAIT) {
                NukeDiag.waited(waitRadius > 1 ? NukeDiag.Wait.PLAN_R2 : NukeDiag.Wait.PLAN_R1);
                waitNeighbours(level, job, now, waitRadius);
                return;
            }
        } finally {
            long took = clock.end(c0);
            // один чанк дольше 50 мс — это чужая задержка (загрузка чанка, сборщик мусора): в лог, не чаще раза в 5 с
            if (took > 50_000_000L && now - lastSlowChunk >= 100) {
                lastSlowChunk = now;
                Airstrike.LOG.warn("Медленный чанк руин {}: {} мс", pos, took / 1_000_000);
            }
        }
        chunk.setData(ModAttachments.CHUNK_SCAR, d.id());
        chunk.setUnsaved(true);
        if (job.asked != Long.MIN_VALUE) {
            long[] waited = preparedStats.computeIfAbsent(d.id(), k -> new long[STATS]);
            waited[23] = Math.max(waited[23], now - job.asked);
            job.asked = Long.MIN_VALUE;
        }
        placedFromMemory(d.id(), job.chunk);
        if (prepared.containsKey(d.id())) ruinedBy.computeIfAbsent(d.id(), k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet()).add(job.chunk);
        List<Log> fallen = logs.remove(job.chunk);
        if (fallen != null) fallen.forEach(l -> RuinPlan.placeLog(level, l.at(), l.state()));
        job.event++;
        if (job.event >= job.events.size()) {
            release(level, job);
            jobs.remove(job.chunk);
        } else {
            job.due = job.wave = due(job.events.get(job.event), chunk.getPos());
            byDue.add(job);
        }
    }
}
