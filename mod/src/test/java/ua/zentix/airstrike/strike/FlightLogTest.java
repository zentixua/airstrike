package ua.zentix.airstrike.strike;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightLogTest {
    private static final String DRONE = "entity.airstrike.drone";

    /**
     * Залп из 30 шахедов по игроку с разбросом теряет цель в одном тике: у каждого своя последняя точка (разброс залпа),
     * а в лог — одна строка с серединой и разбросом, а не 30.
     */
    @Test
    void salvoWithSpreadIsOneLine() {
        FlightLog log = new FlightLog();
        for (int i = 0; i < 30; i++) {
            log.note(DRONE, FlightLog.Event.LOST_GONE, new BlockPos(-300 + i * 2, 70, 100 - i), true, 10 + i % 3);
        }
        List<FlightLog.Line> lines = log.drain();
        assertEquals(1, lines.size(), lines.toString());
        assertEquals(Level.INFO, lines.getFirst().level());
        String text = lines.getFirst().text();
        assertTrue(text.startsWith("Projectiles: 30 × " + DRONE + " lost the target (gone)"), text);
        assertTrue(text.endsWith(" -271 70 85 (±29), endurance ≤ 12 s"), text);
        assertTrue(log.drain().isEmpty(), "строки за тик не забыты");
    }

    /** Разные события и виды — разные строки; одна точка — без разброса. */
    @Test
    void eventsAndWeaponsAreSeparate() {
        FlightLog log = new FlightLog();
        log.note(DRONE, FlightLog.Event.LOST_GONE, new BlockPos(0, 64, 0), true, 5);
        log.note(DRONE, FlightLog.Event.EXPIRED_VIRTUAL, new BlockPos(10, 64, 10), true, 0);
        log.note("entity.airstrike.cruise_missile", FlightLog.Event.LOST_GONE, new BlockPos(0, 64, 0), true, 3);
        List<FlightLog.Line> lines = log.drain();
        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.get(0).text().endsWith(" 0 64 0, endurance ≤ 5 s"), lines.get(0).text());
        assertEquals(Level.WARN, lines.get(1).level());
        assertTrue(lines.get(1).text().endsWith(" 10 64 10 (lost)"), lines.get(1).text());
    }

    /** Разбились до взведения: WARN с местом (середина и разброс), счёт по событию не сбрасывается строками тика. */
    @Test
    void crashedIsWarnedWithPlace() {
        FlightLog log = new FlightLog();
        for (int i = 0; i < 30; i++) log.note(DRONE, FlightLog.Event.CRASHED, new BlockPos(-250 + i % 3, 90, -1140), false, 0, "heading 90°, phase boost");
        List<FlightLog.Line> lines = log.drain();
        assertEquals(1, lines.size(), lines.toString());
        assertEquals(Level.WARN, lines.get(0).level());
        assertTrue(lines.get(0).text().startsWith("Projectiles: 30 × "), lines.get(0).text());
        assertTrue(lines.get(0).text().endsWith(" crashed before fuze arming at -249 90 -1140 (±1), heading 90°, phase boost"), lines.get(0).text());
        assertEquals(30, log.total(FlightLog.Event.CRASHED));
    }

    /** Сбиты до взведения: строка на тип урона. */
    @Test
    void shotDownSplitsByDamage() {
        FlightLog log = new FlightLog();
        log.note(DRONE, FlightLog.Event.SHOT_DOWN, new BlockPos(0, 80, 0), false, 0, "onFire");
        log.note(DRONE, FlightLog.Event.SHOT_DOWN, new BlockPos(2, 80, 0), false, 0, "onFire");
        log.note(DRONE, FlightLog.Event.SHOT_DOWN, new BlockPos(0, 80, 0), false, 0, "player");
        List<FlightLog.Line> lines = log.drain();
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).text().startsWith("Projectiles: 2 × "), lines.get(0).text());
        assertTrue(lines.get(0).text().endsWith(" shot down before fuze arming at 1 80 0 (±1), damage onFire"), lines.get(0).text());
        assertEquals(Level.WARN, lines.get(1).level());
    }

    /** Сбиты зенитной ракетой: штатный исход (INFO), в строке — какой ЗРК. */
    @Test
    void interceptedNamesLauncher() {
        FlightLog log = new FlightLog();
        log.note(DRONE, FlightLog.Event.INTERCEPTED, new BlockPos(500, 120, -40), false, 0, "SAM 10, 64, -3");
        List<FlightLog.Line> lines = log.drain();
        assertEquals(1, lines.size(), lines.toString());
        assertEquals(Level.INFO, lines.getFirst().level());
        assertTrue(lines.getFirst().text().endsWith(" shot down by a SAM at 500 120 -40, SAM 10, 64, -3"), lines.getFirst().text());
        assertEquals(1, log.total(FlightLog.Event.INTERCEPTED));
    }
}
