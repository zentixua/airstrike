package ua.zentix.airstrike.strike;

import net.minecraft.core.BlockPos;
import org.slf4j.event.Level;
import ua.zentix.airstrike.Airstrike;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Конец полёта не по плану — в лог одной строкой за тик на группу: залп из 30 шахедов по погибшему игроку теряет цель
 * в одном тике, и строка на каждый снаряд давала десятки строк (logscan держит 40 последних в разделе). Группа — вид
 * снаряда, событие и точка; каждый снаряд с UUID — строкой DEBUG там, где событие случилось. Пишется в конце тика мира
 * ({@link StrikeWorld}).
 */
public final class FlightLog {
    /** Что случилось с полётом. */
    public enum Event {
        /** Цель пропала (умерла, вышла, другое измерение, аппарат разобран): снаряд идёт в её последнюю точку. */
        LOST_GONE(Level.INFO, "потеряли цель (пропала), идут в её последнюю точку"),
        /** Цель ушла дальше запаса погони. */
        LOST_OUT_OF_REACH(Level.INFO, "потеряли цель (ушла дальше запаса погони), идут в её последнюю точку"),
        /** Срок жизни вышел в мире: самоликвидация. */
        EXPIRED(Level.INFO, "не долетели за срок жизни и самоликвидировались, цель"),
        /** Срок жизни вышел вне мира: снаряд убран без взрыва. */
        EXPIRED_VIRTUAL(Level.WARN, "не долетели за срок жизни вне мира и убраны, цель");

        private final Level level;
        private final String text;

        Event(Level level, String text) {
            this.level = level;
            this.text = text;
        }
    }

    private record Key(String weapon, Event event, BlockPos point, boolean targetLost) {}

    /** Сколько снарядов в группе и самый долгий оставшийся срок, секунд. */
    private static final class Group {
        int count;
        int seconds;
    }

    private final Map<Key, Group> groups = new LinkedHashMap<>();

    /**
     * Отметить снаряд.
     *
     * @param weapon     вид снаряда (id типа сущности)
     * @param point      последняя точка цели (для потери) или точка, куда он шёл
     * @param targetLost цель к этому времени потеряна
     * @param seconds    сколько ему осталось лететь, секунд (для потери цели; иначе 0)
     */
    public void note(String weapon, Event event, BlockPos point, boolean targetLost, int seconds) {
        Group g = groups.computeIfAbsent(new Key(weapon, event, point.immutable(), targetLost), k -> new Group());
        g.count++;
        g.seconds = Math.max(g.seconds, seconds);
    }

    /** Строки за тик: по одной на группу. */
    void flush() {
        if (groups.isEmpty()) return;
        groups.forEach((k, g) -> {
            BlockPos p = k.point;
            switch (k.event) {
                case LOST_GONE, LOST_OUT_OF_REACH -> Airstrike.LOG.atLevel(k.event.level).log("Снаряды: {} × {} {} {} {} {}, срок ≤ {} с",
                        g.count, k.weapon, k.event.text, p.getX(), p.getY(), p.getZ(), g.seconds);
                case EXPIRED, EXPIRED_VIRTUAL -> Airstrike.LOG.atLevel(k.event.level).log("Снаряды: {} × {} {} {} {} {}{}",
                        g.count, k.weapon, k.event.text, p.getX(), p.getY(), p.getZ(), k.targetLost ? " (потеряна)" : "");
            }
        });
        groups.clear();
    }
}
