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
        assertTrue(text.startsWith("Снаряды: 30 × " + DRONE + " потеряли цель (пропала)"), text);
        assertTrue(text.endsWith(" -271 70 85 (±29), срок ≤ 12 с"), text);
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
        assertTrue(lines.get(0).text().endsWith(" 0 64 0, срок ≤ 5 с"), lines.get(0).text());
        assertEquals(Level.WARN, lines.get(1).level());
        assertTrue(lines.get(1).text().endsWith(" 10 64 10 (потеряна)"), lines.get(1).text());
    }
}
