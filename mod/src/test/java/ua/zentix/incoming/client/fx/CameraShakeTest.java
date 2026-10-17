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

    /**
     * Ядерная волна толкает, а не крутит: камера отклоняется от взрыва (взрыв впереди — запрокидывает, справа — наклоняет
     * влево, позади — клонит вниз) на размах {@link CameraShake#nukeAmplitude} и не поворачивается быстрее 300°/с даже
     * при 40 psi. 2 кт в 430 блоках (как в жизни, финал трейлера) — не быстрее 200°/с; прежде было ~700°/с и больше.
     */
    @Test
    void nukeWaveShovesInsteadOfSpinning() {
        Detonation d = new Detonation(1, new Vec3(0, 64, 0), 64, 2, true, 0, 0, 5, 20_000, 7, 1f, false, 0.42f);
        Vec3 ear = new Vec3(430, 66, 0);
        double psi = d.psi(ear);
        // камера смотрит на взрыв (курс 90° — на −x)
        assertTrue(fastest(psi, ear.subtract(d.burst()), 90) < 200, psi + " psi: " + fastest(psi, ear.subtract(d.burst()), 90));
        for (double p : new double[]{0.5, 1, 3, 10, 40}) {
            double amp = CameraShake.nukeAmplitude(p);
            double[] ahead = extreme(p, new Vec3(0, 0, -1), 0), right = extreme(p, new Vec3(1, 0, 0), 0);
            double[] behind = extreme(p, new Vec3(0, 0, 1), 0);
            assertTrue(ahead[0] < -0.75 * amp, p + " psi, взрыв впереди: тангаж " + ahead[0]);
            assertTrue(behind[1] > 0.75 * amp, p + " psi, взрыв позади: тангаж " + behind[1]);
            assertTrue(right[2] < -0.75 * amp, p + " psi, взрыв справа: крен " + right[2]);
            assertTrue(fastest(p, new Vec3(1, -0.3, 0.4), 30) < 300, p + " psi: " + fastest(p, new Vec3(1, -0.3, 0.4), 30));
        }
    }

    /** Самая быстрая тряска от одного удара волны давлением psi, °/с (кадры по 1/10 тика). */
    private static double fastest(double psi, Vec3 away, double cameraYaw) {
        CameraShake.reset();
        CameraShake.nuke(psi, away);
        double[] prev = CameraShake.offsets(0, cameraYaw);
        double most = 0;
        for (int tick = 0; tick < 200; tick++) {
            for (int f = 1; f <= 10; f++) {
                double[] o = CameraShake.offsets(f / 10.0, cameraYaw);
                double turn = Math.sqrt(sq(o[0] - prev[0]) + sq(o[1] - prev[1]) + sq(o[2] - prev[2]));
                most = Math.max(most, turn / (0.1 / 20));
                prev = o;
            }
            CameraShake.tick();
        }
        CameraShake.reset();
        return most;
    }

    /** Наименьшие и наибольшие тангаж и крен за первую секунду волны: {мин. тангаж, макс. тангаж, мин. крен}. */
    private static double[] extreme(double psi, Vec3 away, double cameraYaw) {
        CameraShake.reset();
        CameraShake.nuke(psi, away);
        double[] e = {0, 0, 0};
        for (int tick = 0; tick < 20; tick++) {
            for (int f = 0; f < 10; f++) {
                double[] o = CameraShake.offsets(f / 10.0, cameraYaw);
                e[0] = Math.min(e[0], o[1]);
                e[1] = Math.max(e[1], o[1]);
                e[2] = Math.min(e[2], o[2]);
            }
            CameraShake.tick();
        }
        CameraShake.reset();
        return e;
    }

    private static double sq(double x) {
        return x * x;
    }
}
