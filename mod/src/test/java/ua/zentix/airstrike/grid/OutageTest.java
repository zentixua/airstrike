package ua.zentix.airstrike.grid;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutageTest {
    /** Подстанция в 0 0, район 512 блоков, каскад 3 блока за тик, свет через 1000 тиков за 200. */
    private static final Outage O = new Outage(1, 0, 0, 512, 100, 3, 1100, 200, 7);

    @Test
    void cascadeGoesOutwardWithSpread() {
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                long d = Districts.of(x, z);
                if (!O.covers(d)) continue;
                double r = Math.hypot(Districts.seedX(d), Districts.seedZ(d));
                long at = O.darkAt(d);
                // ±25 % от прихода каскада
                assertTrue(at >= 100 + Math.floor(r / 3 * 0.75) && at <= 100 + Math.ceil(r / 3 * 1.25), "квартал в " + r + ": " + at);
                assertTrue(at <= O.lastDark());
                assertFalse(O.dark(d, at - 1));
                assertTrue(O.dark(d, at) || O.lightAt(d) <= at);
            }
        }
    }

    @Test
    void lightReturnsWithinSpreadAndOutageEnds() {
        for (int x = -30; x <= 30; x++) {
            long d = Districts.of(x, 0);
            if (!O.covers(d)) continue;
            long light = O.lightAt(d);
            assertTrue(light >= 1100 && light <= 1300);
            assertFalse(O.dark(d, light));
        }
        assertFalse(O.over(1299));
        assertTrue(O.over(1300));
    }

    @Test
    void districtOutsideRadiusStaysLit() {
        long far = Districts.of(100, 0);
        assertFalse(O.covers(far));
        assertFalse(O.dark(far, 500));
    }

    @Test
    void neverRestoredStaysDark() {
        Outage o = new Outage(2, 0, 0, 512, 0, 3, Outage.NEVER, 200, -1);
        long d = Districts.of(0, 0);
        assertEquals(Outage.NEVER, o.lightAt(d));
        assertTrue(o.dark(d, Long.MAX_VALUE - 1));
        assertFalse(o.over(Long.MAX_VALUE - 1));
    }

    /** Возврат по команде раньше, чем каскад дошёл: квартал так и не гаснет. */
    @Test
    void earlyRestoreSkipsDistrictsNotYetDark() {
        Outage o = new Outage(3, 0, 0, 512, 0, 0.5, Outage.NEVER, 0, -1).restoring(10, 0);
        long edge = Districts.of(25, 0);
        assertTrue(o.covers(edge));
        for (long t = 0; t < 2000; t++) assertFalse(o.dark(edge, t));
    }

    @Test
    void codecRoundTrip() {
        var json = Outage.CODEC.encodeStart(JsonOps.INSTANCE, O).getOrThrow();
        assertEquals(O, Outage.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        json.getAsJsonObject().remove("node");
        assertEquals(-1, Outage.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().node());
    }
}
