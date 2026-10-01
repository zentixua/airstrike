package ua.zentix.airstrike.nuclear.world;

import java.util.function.LongSupplier;

/**
 * Бюджет времени на тик для очередей разрушений. Проверять только «время ещё не вышло» мало: столбец у эпицентра
 * (десятки блоков, соседи, свет) идёт 3–5 мс, и начатый впритык к сроку уводил тик за бюджет на всю свою длину.
 * Здесь за работу берутся, только если по недавней самой долгой единице она успеет до срока. Оценка — затухающий
 * максимум: после тяжёлого столбца осторожнее, дальше за полсотни единиц снова смелее.
 * <p>
 * Время — из источника часов ({@link System#nanoTime} в игре). {@link #counting} — часы для проверок: время
 * идёт только работой, каждая единица стоит одинаково, поэтому решения очереди не зависят от скорости машины.
 * <p>
 * Оценка затухает по единицам, поэтому при одной единице за тик (тяжёлая единица, пауза GC, чужой затык посреди
 * единицы) она спадает медленно: всплеск в 20 мс держал очередь блэкаута на одной единице за тик десятки тиков.
 * Очередям из дешёвых и ровных единиц — {@link #decaying}: оценка ещё и тает с каждым тиком.
 */
public final class WorkClock {
    /** Затухание оценки за одну единицу работы. */
    private static final double DECAY = 0.95;

    private final LongSupplier time;
    /** У считающих часов — цена одной единицы работы, нс; 0 — настоящие часы. */
    private final long unitCost;
    /** Во сколько раз оценка тает к каждому новому тику (1 — не тает). */
    private final double tickDecay;
    /** Для статуса: единиц в прошлом тике и самая долгая единица за последние 5–10 с (два окна по 100 тиков), нс. */
    private int unitsLastTick;
    private long largest, largestBefore;
    private int ticks;
    /** Для проверок: время работы в этом тике и наибольшее за тик, нс. */
    private long usedThisTick, maxTickNanos;
    private long fakeNow;
    /** Счётчик {@link #sampler} считающих часов. */
    private long sampled;

    private long deadline;
    /** Оценка длительности следующей единицы, нс. */
    private double estimate;
    /** Оценки по видам единиц ({@link #canStart(int)}), нс: дешёвая порция не делает смелее перед дорогой единицей. */
    private double[] kindEstimate = new double[0];
    /** В этом тике уже что-то сделано (одну единицу делаем всегда, иначе после очень долгой очередь встала бы). */
    private boolean worked;
    private int unitsThisTick, maxUnitsPerTick, ticksWorked, units;

    public WorkClock() {
        this(System::nanoTime, 0, 1);
    }

    private WorkClock(LongSupplier time, long unitCost, double tickDecay) {
        this.time = time == null ? () -> fakeNow : time;
        this.unitCost = unitCost;
        this.tickDecay = tickDecay;
    }

    /** Часы для проверок: стоят, пока нет работы; каждая единица двигает их на {@code unitNanos}. */
    public static WorkClock counting(long unitNanos) {
        return new WorkClock(null, unitNanos, 1);
    }

    /** Настоящие часы, чья оценка к каждому тику тает в {@code tickDecay} раз (0.5 — вдвое). */
    public static WorkClock decaying(double tickDecay) {
        return decaying(System::nanoTime, tickDecay);
    }

    /** То же на своём источнике времени (проверки). */
    public static WorkClock decaying(LongSupplier time, double tickDecay) {
        if (!(tickDecay > 0 && tickDecay <= 1)) throw new IllegalArgumentException("tickDecay " + tickDecay + " вне (0, 1]");
        return new WorkClock(time, 0, tickDecay);
    }

    /** Новый тик: срок — через {@code budgetNanos} от сейчас. Оценка переходит из тика в тик. */
    public void start(long budgetNanos) {
        deadline = time.getAsLong() + budgetNanos;
        worked = false;
        unitsLastTick = unitsThisTick;
        unitsThisTick = 0;
        usedThisTick = 0;
        estimate *= tickDecay;
        for (int i = 0; i < kindEstimate.length; i++) kindEstimate[i] *= tickDecay;
        if (++ticks % 100 == 0) {
            largestBefore = largest;
            largest = 0;
        }
    }

    /**
     * Успеем ли ещё одну единицу работы до срока. Первая единица тика — всегда, даже при сроке, который уже прошёл
     * ({@link ua.zentix.airstrike.work.WorkScheduler}: полосе ниже по порядку общего бюджета может не остаться).
     */
    public boolean canStart() {
        if (!worked) return true;
        long now = time.getAsLong();
        return now + (long) estimate < deadline;
    }

    /**
     * То же для единицы вида {@code kind} (0, 1, …) — по оценке этого вида: у полосы с единицами разной цены (лучи
     * взрыва и порция блоков) общая оценка после дешёвых пускала дорогую единицу впритык к сроку.
     */
    public boolean canStart(int kind) {
        if (!worked) return true;
        long now = time.getAsLong();
        return now + (long) kindEstimate(kind) < deadline;
    }

    private double kindEstimate(int kind) {
        return kind < kindEstimate.length ? kindEstimate[kind] : 0;
    }

    /**
     * Часы внутри единицы (порция блоков взрыва идёт, пока короче срока): настоящие часы — те же; считающие — свой
     * счётчик, каждое чтение двигает его на цену единицы (порция из «срок / цена» шагов), время очереди он не трогает.
     */
    public LongSupplier sampler() {
        return unitCost > 0 ? () -> sampled += unitCost : time;
    }

    /** Начало единицы работы: отметка для {@link #end}. */
    public long begin() {
        return time.getAsLong();
    }

    /** Единица работы, начатая в {@code began}, закончена; возвращает её длительность, нс. */
    public long end(long began) {
        if (unitCost > 0) fakeNow += unitCost;
        long took = time.getAsLong() - began;
        record(took);
        return took;
    }

    /** Единица вида {@code kind}, начатая в {@code began}, закончена ({@link #canStart(int)}). */
    public long end(long began, int kind) {
        long took = end(began);
        if (kind >= kindEstimate.length) kindEstimate = java.util.Arrays.copyOf(kindEstimate, kind + 1);
        kindEstimate[kind] = Math.max(took, kindEstimate[kind] * DECAY);
        return took;
    }

    /**
     * Срок тика вышел. Для работы, которая не единица ({@link #spent}): {@link #canStart} до первой единицы тика
     * пускает всегда, и очередь, где все только ждут, выгребалась бы за тик.
     */
    public boolean overdue() {
        return time.getAsLong() >= deadline;
    }

    /**
     * Работа, начатая в {@code began}, — не единица (осмотр работы, которая ждёт): её время — в счёт тика
     * ({@link #usedThisTickNanos}), но не в оценку и не в число единиц. Считающие часы ею не двигаются — у них время
     * идёт только единицами. Возвращает длительность, нс.
     */
    public long spent(long began) {
        long took = time.getAsLong() - began;
        usedThisTick += took;
        maxTickNanos = Math.max(maxTickNanos, usedThisTick);
        return took;
    }

    /** Единица работы заняла {@code nanos}. */
    public void record(long nanos) {
        estimate = Math.max(nanos, estimate * DECAY);
        largest = Math.max(largest, nanos);
        usedThisTick += nanos;
        maxTickNanos = Math.max(maxTickNanos, usedThisTick);
        if (!worked) ticksWorked++;
        worked = true;
        maxUnitsPerTick = Math.max(maxUnitsPerTick, ++unitsThisTick);
        units++;
    }

    /** Для статуса: единиц в прошлом тике. */
    public int unitsLastTick() {
        return unitsLastTick;
    }

    /** Для статуса: оценка следующей единицы, нс. */
    public long estimateNanos() {
        return (long) estimate;
    }

    /** Для статуса: самая долгая единица за последние 5–10 с (прошлое окно в 100 тиков и текущее), нс. */
    public long largestRecentNanos() {
        return Math.max(largest, largestBefore);
    }

    /** Время работы в этом тике, нс (по этим часам: у считающих — единицы × цену). */
    public long usedThisTickNanos() {
        return usedThisTick;
    }

    /** Есть ли работа в этом тике. */
    public boolean workedThisTick() {
        return worked;
    }

    /** Больше всего времени работы за один тик, нс (для проверок). */
    public long maxTickNanos() {
        return maxTickNanos;
    }

    /** Больше всего единиц за один тик (для проверок). */
    public int maxUnitsPerTick() {
        return maxUnitsPerTick;
    }

    /** Всего единиц работы (для проверок). */
    public int units() {
        return units;
    }

    /** В скольких тиках была работа (для проверок). */
    public int ticksWorked() {
        return ticksWorked;
    }
}
