package ua.zentix.airstrike.strike;

import net.minecraft.core.BlockPos;
import org.slf4j.event.Level;
import ua.zentix.airstrike.Airstrike;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Конец полёта не по плану — в лог одной строкой за тик на группу: залп из 30 шахедов по погибшему игроку теряет цель
 * в одном тике, и строка на каждый снаряд давала десятки строк (logscan держит 40 последних в разделе). Группа — вид
 * снаряда и событие; у снарядов залпа с разбросом свои точки, поэтому строка даёт их середину и разброс. Каждый снаряд
 * с UUID — строкой DEBUG там, где событие случилось. Пишется в конце тика мира ({@link StrikeWorld}).
 */
public final class FlightLog {
    /** Что случилось с полётом. */
    public enum Event {
        /** Цель пропала (умерла, вышла, другое измерение, аппарат разобран): снаряд идёт в её последнюю точку. */
        LOST_GONE(Level.INFO, "потеряли цель (пропала), идут в её последнюю точку"),
        /** Цель ушла дальше запаса погони. */
        LOST_OUT_OF_REACH(Level.INFO, "потеряли цель (ушла дальше запаса погони), идут в её последнюю точку"),
        /** Запас хода кончился в мире: самоликвидация. */
        EXPIRED(Level.INFO, "не долетели (кончился запас хода) и самоликвидировались, цель"),
        /** Запас хода кончился вне мира: снаряд убран без взрыва. */
        EXPIRED_VIRTUAL(Level.WARN, "не долетели (кончился запас хода) вне мира и убраны, цель"),
        /** Столкновение до взведения взрывателя (на старте): разбились без подрыва боевой части. */
        CRASHED(Level.WARN, "разбились до взведения взрывателя у"),
        /** Сбиты уроном до взведения (подробность — тип урона): разбились без подрыва боевой части. */
        SHOT_DOWN(Level.WARN, "сбиты до взведения взрывателя у"),
        /** Убраны не модом (команда, чистильщик сущностей другого мода; подробность — кем). */
        REMOVED(Level.WARN, "убраны не модом у"),
        /** Сбиты зенитной ракетой (подробность — какой ЗРК): боевая часть не сработала, корпус упал обломками. */
        INTERCEPTED(Level.INFO, "сбиты зенитной ракетой у");

        private final Level level;
        private final String text;

        Event(Level level, String text) {
            this.level = level;
            this.text = text;
        }
    }

    /** Строка лога: уровень и текст. */
    record Line(Level level, String text) {}

    private record Key(String weapon, Event event, boolean targetLost, String detail) {}

    /** Сколько снарядов в группе, самый большой оставшийся запас хода (секунд полёта на маршевой) и рамка их точек. */
    private static final class Group {
        int count;
        int seconds;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        void add(BlockPos p, int s) {
            count++;
            seconds = Math.max(seconds, s);
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }

        /** Середина рамки и её полуразмер по горизонтали — «x y z (±r)», у одной точки без разброса. */
        String where() {
            int r = (Math.max(maxX - minX, maxZ - minZ) + 1) / 2;
            String at = Math.floorDiv(minX + maxX, 2) + " " + Math.floorDiv(minY + maxY, 2) + " " + Math.floorDiv(minZ + maxZ, 2);
            return r == 0 ? at : at + " (±" + r + ")";
        }
    }

    private final Map<Key, Group> groups = new LinkedHashMap<>();
    /** Сколько снарядов отмечено за всё время, по событиям (для проверок). */
    private final int[] totals = new int[Event.values().length];

    /**
     * Отметить снаряд.
     *
     * @param weapon     вид снаряда (id типа сущности)
     * @param point      последняя точка цели (для потери) или точка, куда он шёл
     * @param targetLost цель к этому времени потеряна
     * @param seconds    на сколько секунд полёта на маршевой скорости ему осталось запаса хода (для потери цели; иначе 0)
     */
    public void note(String weapon, Event event, BlockPos point, boolean targetLost, int seconds) {
        note(weapon, event, point, targetLost, seconds, "");
    }

    /** То же с подробностью (курс, тип урона, кем убран): своя строка на каждую. */
    public void note(String weapon, Event event, BlockPos point, boolean targetLost, int seconds, String detail) {
        groups.computeIfAbsent(new Key(weapon, event, targetLost, detail), k -> new Group()).add(point, seconds);
        totals[event.ordinal()]++;
    }

    /** Сколько снарядов отмечено событием {@code event} за жизнь мира в памяти. */
    public int total(Event event) {
        return totals[event.ordinal()];
    }

    /** Строки за тик, по одной на группу; отмеченное забывается. */
    List<Line> drain() {
        List<Line> lines = new ArrayList<>(groups.size());
        groups.forEach((k, g) -> {
            String tail = switch (k.event) {
                case LOST_GONE, LOST_OUT_OF_REACH -> ", запас хода ≤ " + g.seconds + " с";
                case EXPIRED, EXPIRED_VIRTUAL -> k.targetLost ? " (потеряна)" : "";
                case CRASHED -> k.detail.isEmpty() ? "" : ", " + k.detail;
                case SHOT_DOWN -> ", урон " + k.detail;
                case REMOVED -> ", кем: " + k.detail;
                case INTERCEPTED -> ", " + k.detail;
            };
            lines.add(new Line(k.event.level, String.format(Locale.ROOT, "Снаряды: %d × %s %s %s%s",
                    g.count, k.weapon, k.event.text, g.where(), tail)));
        });
        groups.clear();
        return lines;
    }

    /** В лог — в конце тика мира. */
    void flush() {
        if (groups.isEmpty()) return;
        for (Line line : drain()) Airstrike.LOG.atLevel(line.level()).log(line.text());
    }
}
