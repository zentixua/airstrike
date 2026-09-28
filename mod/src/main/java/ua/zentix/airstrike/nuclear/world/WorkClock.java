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
 */
public final class WorkClock {
    /** Затухание оценки за одну единицу работы. */
    private static final double DECAY = 0.95;

    private final LongSupplier time;
    /** У считающих часов — цена одной единицы работы, нс; 0 — настоящие часы. */
    private final long unitCost;
    private long fakeNow;

    private long deadline;
    /** Оценка длительности следующей единицы, нс. */
    private double estimate;
    /** В этом тике уже что-то сделано (одну единицу делаем всегда, иначе после очень долгой очередь встала бы). */
    private boolean worked;
    private int unitsThisTick, maxUnitsPerTick, ticksWorked;

    public WorkClock() {
        this(System::nanoTime, 0);
    }

    private WorkClock(LongSupplier time, long unitCost) {
        this.time = time == null ? () -> fakeNow : time;
        this.unitCost = unitCost;
    }

    /** Часы для проверок: стоят, пока нет работы; каждая единица двигает их на {@code unitNanos}. */
    public static WorkClock counting(long unitNanos) {
        return new WorkClock(null, unitNanos);
    }

    /** Новый тик: срок — через {@code budgetNanos} от сейчас. Оценка переходит из тика в тик. */
    public void start(long budgetNanos) {
        deadline = time.getAsLong() + budgetNanos;
        worked = false;
        unitsThisTick = 0;
    }

    /** Успеем ли ещё одну единицу работы до срока. */
    public boolean canStart() {
        long now = time.getAsLong();
        return now < deadline && (!worked || now + (long) estimate < deadline);
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

    /** Единица работы заняла {@code nanos}. */
    public void record(long nanos) {
        estimate = Math.max(nanos, estimate * DECAY);
        if (!worked) ticksWorked++;
        worked = true;
        maxUnitsPerTick = Math.max(maxUnitsPerTick, ++unitsThisTick);
    }

    /** Больше всего единиц за один тик (для проверок). */
    public int maxUnitsPerTick() {
        return maxUnitsPerTick;
    }

    /** В скольких тиках была работа (для проверок). */
    public int ticksWorked() {
        return ticksWorked;
    }
}
