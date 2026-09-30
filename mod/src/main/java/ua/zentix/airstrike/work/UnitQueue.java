package ua.zentix.airstrike.work;

import net.minecraft.server.level.ServerLevel;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.nuclear.world.WorkClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Очередь работ одной полосы в одном мире ({@link WorkScheduler}): каждая работа — цепочка коротких единиц. Порядок —
 * порядок постановки: работа идёт до конца, прежде чем начнётся следующая (главный взрыв удара — раньше его огненного
 * шара и стёкол). Работа, чей район ещё не готов ({@link Job#ready}), пропускается, пока не станет готов, а следующие
 * за ней идут; ждёт не дольше {@link #GIVE_UP_TICKS}. Не сохраняется (как таймлайны взрывов).
 */
public final class UnitQueue {
    /** Работа: единицы по одной, пока {@link #step} отвечает {@code true}. */
    public interface Job {
        /** Можно ли делать единицу сейчас (район готов). */
        default boolean ready(ServerLevel level) {
            return true;
        }

        /** Одна единица работы; {@code false} — работа кончилась. */
        boolean step(ServerLevel level);

        /** Работа убрана — кончилась, отменена или упала: отпустить то, что она держит (тикеты). */
        default void end(ServerLevel level) {}

        /** Для строки в лог: что за работа. */
        String describe();
    }

    /** Сколько работа ждёт готовности района, тиков; дальше отменяется (мир не грузится). */
    public static final int GIVE_UP_TICKS = 6000;

    private static final class Entry {
        final Job job;
        final long added;

        Entry(Job job, long added) {
            this.job = job;
            this.added = added;
        }
    }

    private final List<Entry> jobs = new ArrayList<>();

    public void add(ServerLevel level, Job job) {
        jobs.add(new Entry(job, level.getGameTime()));
    }

    public boolean isEmpty() {
        return jobs.isEmpty();
    }

    public int size() {
        return jobs.size();
    }

    /** Сколько тиков ждёт самая старая работа. */
    public long oldestWaitTicks(ServerLevel level) {
        return jobs.isEmpty() ? 0 : level.getGameTime() - jobs.getFirst().added;
    }

    /**
     * Единицы, пока часы разрешают: работы по порядку, неготовые пропускаются. Работа может поставить новую (взрыв
     * будит обработчики модов, те — свои взрывы): она встаёт в конец и идёт в этом же обходе, если хватит срока.
     */
    public void work(ServerLevel level, WorkClock clock) {
        long now = level.getGameTime();
        for (int i = 0; i < jobs.size() && clock.canStart(); ) {
            Entry e = jobs.get(i);
            boolean done;
            try {
                if (!e.job.ready(level)) {
                    if (now - e.added < GIVE_UP_TICKS) {
                        i++;
                        continue;
                    }
                    Airstrike.LOG.warn("{} отменено: район не загрузился за {} тиков", e.job.describe(), GIVE_UP_TICKS);
                    done = true;
                } else {
                    done = false;
                    while (!done && clock.canStart()) {
                        long c0 = clock.begin();
                        try {
                            done = !e.job.step(level);
                        } finally {
                            clock.end(c0);
                        }
                    }
                }
            } catch (RuntimeException ex) {
                Airstrike.LOG.error("{} упало с ошибкой и убрано", e.job.describe(), ex);
                done = true;
            }
            if (!done) return; // бюджет вышел посреди работы: следующие ждут её конца
            jobs.remove(i);
            try {
                e.job.end(level);
            } catch (RuntimeException ex) {
                Airstrike.LOG.error("{}: не вышло отпустить", e.job.describe(), ex);
            }
        }
    }

    /** Мир выгружается или сервер останавливается: отпустить всё (работа не сохраняется). */
    public void clear(ServerLevel level) {
        List<Entry> all = new ArrayList<>(jobs);
        jobs.clear();
        for (Entry e : all) {
            try {
                e.job.end(level);
            } catch (RuntimeException ex) {
                Airstrike.LOG.error("{}: не вышло отпустить", e.job.describe(), ex);
            }
        }
    }
}
