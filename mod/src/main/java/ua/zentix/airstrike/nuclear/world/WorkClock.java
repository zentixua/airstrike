package ua.zentix.airstrike.nuclear.world;

/**
 * Бюджет времени на тик для очередей разрушений. Проверять только «время ещё не вышло» мало: столбец у эпицентра
 * (десятки блоков, соседи, свет) идёт 3–5 мс, и начатый впритык к сроку уводил тик за бюджет на всю свою длину.
 * Здесь за работу берутся, только если по недавней самой долгой единице она успеет до срока. Оценка — затухающий
 * максимум: после тяжёлого столбца осторожнее, дальше за полсотни единиц снова смелее.
 */
public final class WorkClock {
    /** Затухание оценки за одну единицу работы. */
    private static final double DECAY = 0.95;

    private long deadline;
    /** Оценка длительности следующей единицы, нс. */
    private double estimate;
    /** В этом тике уже что-то сделано (одну единицу делаем всегда, иначе после очень долгой очередь встала бы). */
    private boolean worked;

    /** Новый тик: срок — через {@code budgetNanos} от сейчас. Оценка переходит из тика в тик. */
    public void start(long budgetNanos) {
        deadline = System.nanoTime() + budgetNanos;
        worked = false;
    }

    /** Успеем ли ещё одну единицу работы до срока. */
    public boolean canStart() {
        long now = System.nanoTime();
        return now < deadline && (!worked || now + (long) estimate < deadline);
    }

    /** Единица работы заняла {@code nanos}. */
    public void record(long nanos) {
        estimate = Math.max(nanos, estimate * DECAY);
        worked = true;
    }
}
