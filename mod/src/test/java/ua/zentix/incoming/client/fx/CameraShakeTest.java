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
     * Ядерная волна толкает, а не крутит: камера отклоняется по ветру от взрыва (взрыв впереди — запрокидывает, справа —
     * наклоняет влево, позади — клонит вниз) на размах {@link CameraShake#nukeAmplitude} при любой фазе дрожи; воздушный
     * взрыв впереди под 45° — тоже назад, слабее (напор у земли дует вдоль неё).
     */
    @Test
    void nukeWaveShovesAwayFromTheBurst() {
        for (double psi : new double[]{0.5, 1, 3, 10, 40}) {
            double amp = CameraShake.nukeAmplitude(psi);
            for (int phase = 0; phase < 20; phase++) {
                String at = psi + " psi, фаза " + phase;
                assertTrue(extreme(psi, new Vec3(0, 0, -1), phase)[0] < -0.75 * amp, at + ": взрыв впереди — назад");
                assertTrue(extreme(psi, new Vec3(0, 0, 1), phase)[1] > 0.75 * amp, at + ": взрыв позади — вниз");
                assertTrue(extreme(psi, new Vec3(1, 0, 0), phase)[2] < -0.75 * amp, at + ": взрыв справа — влево");
                assertTrue(extreme(psi, new Vec3(0, -1, -1), phase)[0] < -0.5 * amp, at + ": над головой впереди — назад");
            }
        }
    }

    /**
     * Дрожь перед волной, её приход и ветер за ней — без рывков: камера не поворачивается быстрее 300°/с даже при 40 psi
     * (прежде — 670°/с уже при 1 psi), у 2 кт в 430 блоках (8 psi, финал трейлера) — не быстрее 200°/с. Толчки грунта от
     * ударов волны ({@link CameraShake#quake}) — свои, мельче, здесь их нет.
     */
    @Test
    void approachArrivalAndWindDoNotJerk() {
        Detonation d = new Detonation(1, new Vec3(0, 64, 0), 64, 2, true, 0, 0, 5, 20_000, 7, 1f, false, 0.42f);
        Vec3 ear = new Vec3(430, 66, 0);
        double psi = d.psi(ear), trailer = fastest(psi, ear.subtract(d.burst()), 90);
        assertTrue(trailer < 200, psi + " psi: " + trailer);
        for (double p : new double[]{0.6, 1, 3, 10, 40}) {
            for (int phase = 0; phase < 20; phase += 5) {
                double most = fastest(p, new Vec3(1, -0.3, 0.4).scale(phase + 1), 30 + 17 * phase);
                assertTrue(most < 300, p + " psi, фаза " + phase + ": " + most);
            }
        }
    }

    /**
     * Самый быстрый поворот, °/с, от подхода ядерной волны давлением psi и её прихода: тики, как у клиента (тряска, потом
     * подрыв), кадры по 1/10 тика (поворот за кадр × 200 — за секунду).
     */
    private static double fastest(double psi, Vec3 away, double cameraYaw) {
        CameraShake.reset();
        double most = 0;
        double[] prev = null;
        int before = (int) CameraShake.approachTicks(psi);
        for (int tick = -before; tick < 200; tick++) {
            CameraShake.tick();
            if (tick < 0) CameraShake.approach(psi, -tick);
            else if (tick == 0) CameraShake.nuke(psi, away);
            for (int f = 0; f < 10; f++) {
                double[] o = CameraShake.offsets(f / 10.0, cameraYaw);
                if (prev != null) most = Math.max(most, Math.sqrt(sq(o[0] - prev[0]) + sq(o[1] - prev[1]) + sq(o[2] - prev[2])) * 200);
                prev = o;
            }
        }
        CameraShake.reset();
        return most;
    }

    /**
     * Наименьший и наибольший тангаж и наименьший крен за первую секунду волны при курсе 0 (на +z), после {@code phase}
     * тиков дрожи: {мин. тангаж, макс. тангаж, мин. крен}.
     */
    private static double[] extreme(double psi, Vec3 away, int phase) {
        CameraShake.reset();
        for (int i = 0; i < phase; i++) CameraShake.tick();
        CameraShake.nuke(psi, away);
        double[] e = {0, 0, 0};
        for (int tick = 0; tick < 20; tick++) {
            for (int f = 0; f < 10; f++) {
                double[] o = CameraShake.offsets(f / 10.0, 0);
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
