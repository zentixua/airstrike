package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.work.UnitQueue;

import java.util.Arrays;

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
    }

    /** Медленный тик попаданий: больше целого тика сервера. */
    static final long SLOW_NANOS = 50_000_000L;
    /** Строка о медленном тике — не чаще, тиков. */
    static final int LOG_PERIOD = 100;
    /** Работа попаданий ждёт в очереди дольше, тиков, — строка в лог (не чаще {@link #LOG_PERIOD}): залп копит разрушения. */
    static final int LONG_WAIT_TICKS = 20;

    private final long[] nanos = new long[Kind.values().length];
    private final int[] counts = new int[Kind.values().length];
    private long total;
    private long lastLog = Long.MIN_VALUE / 2;
    private int slowSinceLog;
    private long lastWaitLog = Long.MIN_VALUE / 2;

    /** Работа вида {@code kind} заняла {@code took} нс и сделала {@code count} (взрывов, блоков, обломков). */
    public void add(Kind kind, long took, int count) {
        nanos[kind.ordinal()] += took;
        counts[kind.ordinal()] += count;
    }

    /** Шаг попадания (начало взрыва или тик таймлайна) занял {@code took} нс — всё вместе, с видами работы внутри. */
    public void step(long took) {
        total += took;
    }

    /** Конец тика сервера: медленный — в лог, счёт — заново; очередь попаданий ждёт долго — тоже в лог. */
    void endTick(ServerLevel level, UnitQueue queue) {
        long now = level.getGameTime();
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
        Arrays.fill(nanos, 0);
        Arrays.fill(counts, 0);
    }
}
