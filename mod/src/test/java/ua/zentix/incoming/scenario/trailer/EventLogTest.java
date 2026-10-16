package ua.zentix.incoming.scenario.trailer;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ua.zentix.incoming.entity.FlightPhase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Журнал плана без игры: где удар видно ({@link EventLog.Flight#seen}). Бетонобойная бомба рвётся в породе, и луч к
 * заряду закрыт из любой камеры (n24, 15.10: «удар виден 0 %» у всех кандидатов) — удар в кадре ищется у устья.
 */
class EventLogTest {
    private static String row(int t, int index, double x, double y, double z, FlightPhase phase) {
        return String.format(Locale.ROOT, "{\"t\":%d,\"f\":[[%d,%.2f,%.2f,%.2f,%d,1]]}", t, index, x, y, z, phase.ordinal());
    }

    @Test
    void drillingBombIsSeenAtTheBoreholeMouth(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("n24.events.jsonl");
        Files.write(file, List.of(
                "{\"t\":0,\"new\":0,\"id\":\"7c799703-4516-4b04-960e-67f649920835\",\"weapon\":\"bunker\",\"bomber\":false}",
                row(0, 0, -439.3, 84.9, -1140.2, FlightPhase.TERMINAL),
                row(1, 0, -439.3, 72.9, -1140.0, FlightPhase.TERMINAL),
                row(2, 0, -439.3, 64.0, -1139.8, FlightPhase.DRILL),
                row(3, 0, -439.3, 56.0, -1139.6, FlightPhase.DRILL),
                row(4, 0, -439.3, 48.0, -1139.5, FlightPhase.DRILL),
                "{\"t\":5,\"impact\":0,\"at\":6,\"pos\":[-439.34,40.01,-1139.26]}"), StandardCharsets.UTF_8);
        EventLog.Data d = EventLog.read(file, false);
        Rig.Subject s = Rig.impact("bunker", null, 0).apply(d);
        assertEquals(5, s.key);
        assertEquals(64.0, s.keyPos.y, 1e-6);
        assertEquals(-1139.8, s.keyPos.z, 1e-6);
        // место взрыва — по-прежнему в породе: толчки камеры и порядок ударов по месту считаются от него
        assertEquals(40.01, d.flights.get(0).impactPos.y, 1e-6);
    }

    @Test
    void otherWeaponsAreSeenWhereTheyBlew(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("n13.events.jsonl");
        Files.write(file, List.of(
                "{\"t\":0,\"new\":0,\"id\":\"ee6af10e-8d88-48b7-ad27-a1633b35c1e5\",\"weapon\":\"missile\",\"bomber\":false}",
                row(0, 0, 10, 90, 0, FlightPhase.TERMINAL),
                row(1, 0, 14, 80, 0, FlightPhase.TERMINAL),
                "{\"t\":2,\"impact\":0,\"at\":2,\"pos\":[18.0,70.0,0.0]}"), StandardCharsets.UTF_8);
        Rig.Subject s = Rig.impact("missile", null, 0).apply(EventLog.read(file, false));
        assertEquals(new Vec3(18, 70, 0), s.keyPos);
    }
}
