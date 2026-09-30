package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.warhead.ExplosionTimer;
import ua.zentix.airstrike.work.UnitQueue;

import java.util.Arrays;
import java.util.Locale;

/**
 * Сколько потока сервера заняли попадания обычных боевых частей в тике мира: всё (таймлайны, единицы полосы попаданий
 * {@link ua.zentix.airstrike.work.WorkScheduler}, отложенные взрывы) и по видам работы. Тик дольше {@link #SLOW_NANOS} — строка в лог (не чаще раза
 * в {@link #LOG_PERIOD} тиков, для {@code tools/logscan.py}): по ней видно, какая часть попадания держит сервер
 * в игре хоста, где профилировщик не запустишь. Не сохраняется, живёт в {@link StrikeWorld}.
 */
public final class ImpactCost {
    /** Вид работы попадания. */
    public enum Kind {
        /** Ванильные взрывы, первая единица: лучи, урон и отбрасывание сущностей, события взрыва. */
        RAYS("взрывы: лучи и урон"),
        /** Ванильные взрывы, остальные единицы: снятие блоков порциями, выпадение, огонь. */
        BLOCKS("взрывы: блоки"),
        /** Выбитые ударной волной стёкла и листва. */
        GLASS("стёкла"),
        /** Обломки-сущности. */
        DEBRIS("обломки"),
        /** Прочее: огонь по кольцу, щебень и обрушение свода бомбы. */
        GROUND("огонь и грунт");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Медленный тик попаданий: больше целого тика сервера. */
    static final long SLOW_NANOS = 50_000_000L;
    /** Строка о медленном тике — не чаще, тиков. */
    static final int LOG_PERIOD = 100;
    /** Работа попаданий ждёт в очереди дольше, тиков, — строка в лог (не чаще {@link #LOG_PERIOD}): залп копит разрушения. */
    static final int LONG_WAIT_TICKS = 20;

    private final long[] nanos = new long[Kind.values().length];
    private final int[] counts = new int[Kind.values().length];
    /** Из чего сложились лучи взрывов ({@link Kind#RAYS}): шаги {@code Explosion.explode} по {@link ExplosionTimer}. */
    private final long[] rayStages = new long[ExplosionTimer.Stage.values().length];
    private long total;
    private long lastLog = Long.MIN_VALUE / 2;
    private int slowSinceLog;
    private long lastWaitLog = Long.MIN_VALUE / 2;

    /** Серия попаданий (залп): от первой единицы до {@link #SERIES_QUIET_TICKS} тиков без работы — одна строка итога. */
    static final int SERIES_QUIET_TICKS = 100;
    private long seriesStart = -1;
    private long seriesLastWork;
    private int seriesStrikes;
    private int seriesUnits;
    private long seriesNanos;
    private long seriesMaxUnit;
    private long seriesMaxTick;
    private int seriesSlowTicks;
    private long tickUnitsNanos;

    /** Работа вида {@code kind} заняла {@code took} нс и сделала {@code count} (взрывов, блоков, обломков). */
    public void add(Kind kind, long took, int count) {
        nanos[kind.ordinal()] += took;
        counts[kind.ordinal()] += count;
        seriesUnits++;
        seriesNanos += took;
        seriesMaxUnit = Math.max(seriesMaxUnit, took);
        tickUnitsNanos += took;
    }

    /** Удар (район взрыва) отпущен — в итог серии. */
    public void strikeDone() {
        seriesStrikes++;
    }

    /** Шаги лучей одного взрыва, нс ({@link ExplosionTimer}). */
    public void addRayStages(long[] stages) {
        for (int i = 0; i < stages.length; i++) rayStages[i] += stages[i];
    }

    /** Шаг попадания (начало взрыва или тик таймлайна) занял {@code took} нс — всё вместе, с видами работы внутри. */
    public void step(long took) {
        total += took;
    }

    private void endSeriesTick(ServerLevel level, long now, boolean idle) {
        if (tickUnitsNanos > 0) {
            if (seriesStart < 0) seriesStart = now;
            seriesLastWork = now;
            seriesMaxTick = Math.max(seriesMaxTick, total);
            if (total > SLOW_NANOS) seriesSlowTicks++;
        }
        if (seriesStart < 0 || !idle || now - seriesLastWork < SERIES_QUIET_TICKS) return;
        Airstrike.LOG.info(String.format(Locale.ROOT,
                "Итог серии попаданий (%s): ударов %d, единиц %d, всего %.1f мс, самая долгая единица %.1f мс, самый долгий тик %.1f мс, тиков дольше 50 мс %d, за %d тиков",
                level.dimension().location(), seriesStrikes, seriesUnits, seriesNanos / 1e6, seriesMaxUnit / 1e6, seriesMaxTick / 1e6,
                seriesSlowTicks, seriesLastWork - seriesStart + 1));
        seriesStart = -1;
        seriesStrikes = seriesUnits = seriesSlowTicks = 0;
        seriesNanos = seriesMaxUnit = seriesMaxTick = 0;
    }

    /** Конец тика сервера: медленный — в лог, счёт — заново; очередь попаданий ждёт долго — тоже в лог. */
    void endTick(ServerLevel level, UnitQueue queue) {
        long now = level.getGameTime();
        endSeriesTick(level, now, queue.isEmpty());
        long wait = queue.oldestWaitTicks(level);
        if (wait > LONG_WAIT_TICKS && now - lastWaitLog >= LOG_PERIOD) {
            Airstrike.LOG.info("Попадания ({}): в очереди {} работ, самая старая ждёт {} тиков", level.dimension().location(), queue.size(), wait);
            lastWaitLog = now;
        }
        if (total > SLOW_NANOS) {
            if (now - lastLog >= LOG_PERIOD) {
                StringBuilder parts = new StringBuilder();
                for (Kind k : Kind.values()) {
                    if (parts.length() > 0) parts.append(", ");
                    parts.append(k.label).append(' ').append(nanos[k.ordinal()] / 1_000_000).append(" мс (").append(counts[k.ordinal()]).append(')');
                    if (k == Kind.RAYS && counts[k.ordinal()] > 0) {
                        parts.append(" [");
                        for (ExplosionTimer.Stage st : ExplosionTimer.Stage.values()) {
                            if (st.ordinal() > 0) parts.append(", ");
                            parts.append(st.label).append(' ').append(rayStages[st.ordinal()] / 1_000_000);
                        }
                        parts.append(" мс]");
                    }
                }
                Airstrike.LOG.warn("Попадания ({}): {} мс за тик — {}; в очереди {}; таких тиков после прошлой строки: {}",
                        level.dimension().location(), total / 1_000_000, parts, queue.size(), slowSinceLog);
                lastLog = now;
                slowSinceLog = 0;
            } else {
                slowSinceLog++;
            }
        }
        total = 0;
        tickUnitsNanos = 0;
        Arrays.fill(nanos, 0);
        Arrays.fill(counts, 0);
        Arrays.fill(rayStages, 0);
    }
}
