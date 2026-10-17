package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Модель горящего города: где и сколько горит, когда разгорается и гаснет, вторичные взрывы, тишина животных. */
class FireFieldTest {
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 1, false, 1);
    private static final Detonation AIR = new Detonation(2, new Vec3(0, 64 + 500, 0), 64, 15, false, 0, 0, 0, 20_000, 9, 1, false, 1);

    @Test
    void firesStopAtTheServerCapAndStartOutsideTheFireball() {
        for (Detonation d : List.of(GROUND, AIR)) {
            FireField f = new FireField(d);
            assertTrue(f.total > 1000 && f.total <= FireField.MAX_FIRES + 1e-6, "пожаров " + f.total);
            int first = 0;
            while (f.fires[first] == 0) first++;
            if (d.surface()) assertTrue(FireField.radius(first) >= d.fireballRadius() * 0.8 - FireField.RING, "в шаре не горит");
            for (int i = 0; i < f.fires.length; i++) assertTrue(f.fires[i] >= 0);
            for (int i = 1; i < f.arrival.length; i++) assertTrue(f.arrival[i] >= f.arrival[i - 1], "волна зажигает изнутри наружу");
        }
    }

    @Test
    void fireRisesWithTheWaveThenDiesDown() {
        assertEquals(0, FireField.envelope(-1), 1e-9);
        assertEquals(0.5, FireField.envelope(FireField.RISE / 2), 1e-9);
        assertEquals(1, FireField.envelope(FireField.RISE), 1e-9);
        assertEquals(Math.exp(-1), FireField.envelope(FireField.RISE + FireField.TAU), 1e-9);
        FireField f = new FireField(GROUND);
        assertEquals(0, f.burning(0), 1e-9, "до волны не горит");
        double peak = f.burning(f.arrival[f.arrival.length - 1] + FireField.RISE);
        assertTrue(peak > 0.5 * f.total, "разгорелось: " + peak);
        assertTrue(f.burning(f.endTicks()) < 0.03 * f.total, "к концу почти погасло");
    }

    @Test
    void secondaryBlastsAreDeterministicSpreadOverTimeAndInsideTheFire() {
        FireField f = new FireField(GROUND);
        List<FireField.Secondary> a = f.secondaries(), b = new FireField(GROUND).secondaries();
        assertEquals(a, b, "из зерна подрыва — одни и те же");
        assertTrue(a.size() >= 4 && a.size() <= 24, "взрывов " + a.size());
        double reach = 0;
        for (int i = 0; i < f.fires.length; i++) if (f.fires[i] > 0) reach = (i + 1) * FireField.RING;
        double last = 0;
        for (FireField.Secondary s : a) {
            assertTrue(s.ticks() >= last, "по времени");
            last = s.ticks();
            assertTrue(s.ticks() >= FireField.SECONDARY_AFTER && s.ticks() <= f.arrival[f.arrival.length - 1] + FireField.SECONDARY_AFTER + FireField.SECONDARY_END,
                    "время " + s.ticks());
            assertTrue(Math.hypot(s.x(), s.z()) <= reach + 1e-6, "там, где горит: " + Math.hypot(s.x(), s.z()));
        }
        assertTrue(a.get(a.size() - 1).ticks() - a.get(0).ticks() > 60 * 20, "не разом, а минутами");
        assertTrue(!new FireField(AIR).secondaries().equals(a), "у другого подрыва — другие");
    }

    @Test
    void animalsFallSilentThenComeBack() {
        assertEquals(1, AnimalSilence.share(10), 1e-9);
        assertEquals(1, AnimalSilence.share(AnimalSilence.FULL - 1), 1e-9);
        assertEquals(0.5, AnimalSilence.share((AnimalSilence.FULL + AnimalSilence.BACK) / 2), 1e-9);
        assertEquals(0, AnimalSilence.share(AnimalSilence.BACK + 1), 1e-9);
    }
}
