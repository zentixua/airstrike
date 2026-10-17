package ua.zentix.incoming.client.fx;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraShakeTest {
    /**
     * Пока ядерная волна подходит, земля дрожит всё сильнее: до {@link CameraShake#approachTicks} — нет, потом размах
     * только растёт и к приходу волны — доля {@link CameraShake#APPROACH_SHARE} удара; слабее 0,5 psi не трясёт.
     */
    @Test
    void shakeGrowsAsWaveApproaches() {
        for (double psi : new double[]{0.6, 1, 3, 10, 40}) {
            double total = CameraShake.approachTicks(psi);
            assertEquals(0, CameraShake.approachAmplitude(psi, total + 1));
            double prev = 0;
            for (double eta = total; eta >= 0; eta -= 1) {
                double a = CameraShake.approachAmplitude(psi, eta);
                assertTrue(a >= prev, psi + " psi, за " + eta + " тиков: размах падает");
                prev = a;
            }
            assertEquals(CameraShake.nukeAmplitude(psi) * CameraShake.APPROACH_SHARE, prev, 1e-9, psi + " psi");
        }
        assertEquals(0, CameraShake.approachAmplitude(0.4, 0));
        assertTrue(CameraShake.approachTicks(7) > CameraShake.approachTicks(1), "сильная волна слышна раньше");
    }

    /**
     * Толчок грунта от наземного взрыва: у 15 кт в километре — около 4°, спадает с расстоянием, дальше 7 км
     * не ощущается; меньший взрыв — слабее; с километра доходит раньше волны в воздухе.
     */
    @Test
    void seismicJoltBeforeTheWave() {
        assertEquals(4, CameraShake.seismicAmplitude(1000, 15), 0.5);
        double prev = Double.MAX_VALUE;
        for (double m = 300; m < 9000; m += 100) {
            double a = CameraShake.seismicAmplitude(m, 15);
            assertTrue(a <= prev, m + " м");
            assertTrue(a <= 6);
            prev = a;
        }
        assertEquals(0, CameraShake.seismicAmplitude(7500, 15));
        assertTrue(CameraShake.seismicAmplitude(1000, 1) < CameraShake.seismicAmplitude(1000, 15));
        Detonation d = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 5, 20_000, 7, 1f, false, 0.42f);
        // у самого шара волна в воздухе сверхзвуковая и обгоняет толчок; с километра — толчок раньше
        for (double m = 1000; m <= 7000; m += 500) {
            assertTrue(CameraShake.seismicTicks(m, d.scale()) < d.arrivalTicks(d.blocks(m)) - 10, m + " м: толчок не раньше волны");
        }
    }
}
