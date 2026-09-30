package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;
import ua.zentix.airstrike.Airstrike;

import java.util.Arrays;

/**
 * Сколько потока сервера заняли попадания обычных боевых частей в тике мира: всё (начало взрыва в тике попадания,
 * таймлайны, отложенные взрывы) и по видам работы. Тик дольше {@link #SLOW_NANOS} — строка в лог (не чаще раза
 * в {@link #LOG_PERIOD} тиков, для {@code tools/logscan.py}): по ней видно, какая часть попадания держит сервер
 * в игре хоста, где профилировщик не запустишь. Не сохраняется, живёт в {@link StrikeWorld}.
 */
public final class ImpactCost {
    /** Вид работы попадания. */
    public enum Kind {
        /** Ванильные взрывы: основной, огненный шар, вторичные (и отложенные до готовности района). */
        EXPLOSIONS("взрывы"),
        /** Выбитые ударной волной стёкла и листва. */
        GLASS("стёкла"),
        /** Обломки-сущности. */
        DEBRIS("обломки");

        private final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** Медленный тик попаданий: больше целого тика сервера. */
    static final long SLOW_NANOS = 50_000_000L;
    /** Строка о медленном тике — не чаще, тиков. */
    static final int LOG_PERIOD = 100;

    private final long[] nanos = new long[Kind.values().length];
    private final int[] counts = new int[Kind.values().length];
    private long total;
    private long lastLog = Long.MIN_VALUE / 2;
    private int slowSinceLog;

    /** Работа вида {@code kind} заняла {@code took} нс и сделала {@code count} (взрывов, блоков, обломков). */
    public void add(Kind kind, long took, int count) {
        nanos[kind.ordinal()] += took;
        counts[kind.ordinal()] += count;
    }

    /** Шаг попадания (начало взрыва или тик таймлайна) занял {@code took} нс — всё вместе, с видами работы внутри. */
    public void step(long took) {
        total += took;
    }

    /** Конец тика мира: медленный — в лог, счёт — заново. */
    void endTick(ServerLevel level) {
        if (total > SLOW_NANOS) {
            long now = level.getGameTime();
            if (now - lastLog >= LOG_PERIOD) {
                StringBuilder parts = new StringBuilder();
                for (Kind k : Kind.values()) {
                    if (parts.length() > 0) parts.append(", ");
                    parts.append(k.label).append(' ').append(nanos[k.ordinal()] / 1_000_000).append(" мс (").append(counts[k.ordinal()]).append(')');
                }
                Airstrike.LOG.warn("Попадания ({}): {} мс за тик — {}; таких тиков после прошлой строки: {}", level.dimension().location(),
                        total / 1_000_000, parts, slowSinceLog);
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
