package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;

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
}
