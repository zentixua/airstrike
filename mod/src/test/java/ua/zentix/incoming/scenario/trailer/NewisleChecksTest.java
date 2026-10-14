package ua.zentix.incoming.scenario.trailer;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверки дублей Newisle без игры: предел поворота камеры вывода ({@link ReplayFilm#turnRate}, Артём, 14.10 — камеры
 * дёргаются) и зазор разносчика пусков ({@link Newisle#launchAt}, Артём, 15.10 — установки не стреляют в одну
 * миллисекунду).
 */
class NewisleChecksTest {
    private static Pose pose(float yaw, float pitch) {
        return new Pose(new Vec3(0, 0, 0), yaw, pitch, 0, 70);
    }

    @Test
    void turnRateOfASteadyPanIsDegreesPerSecond() {
        // 4° за тик курса — 80°/с, тангаж ровный
        CineCamera.Path path = t -> pose(4 * (float) t, 0);
        assertEquals(80, ReplayFilm.turnRate(path, 0, 40), 0.5);
    }

    @Test
    void turnRateTakesTheFasterAxisAndTheWorstFrame() {
        CineCamera.Path path = t -> pose(0, (float) Math.max(0, 7 * (t - 10)));
        // 7° за тик тангажа — 140°/с (рывок), даже если большая часть плана ровная
        assertEquals(140, ReplayFilm.turnRate(path, 0, 40), 0.5);
    }

    @Test
    void turnRateGoesAcrossTheYawSeam() {
        // курс через ±180°: непрерывный поворот 4° за тик — 80°/с, не скачок на 350°
        CineCamera.Path path = t -> pose(179 + 4 * (float) t, 0);
        assertEquals(80, ReplayFilm.turnRate(path, 0, 40), 0.5);
    }

    @Test
    void launchGapSpreadsSamePlaceAndDifferentPlaces() {
        RandomSource r = RandomSource.create(1);
        Vec3 at = new Vec3(100, 0, 100);
        // первого пуска ничто не держит
        assertEquals(500, Newisle.launchAt(500, Long.MIN_VALUE, null, null, WeaponType.MISSILE, at, r));
        // то же место и то же оружие — очередь одной трубы: 9–13 тиков
        for (int i = 0; i < 200; i++) {
            long gap = Newisle.launchAt(500, 500, at, WeaponType.MISSILE, WeaponType.MISSILE, at, r) - 500;
            assertTrue(gap >= 9 && gap <= 13, "зазор того же места " + gap);
        }
        // другое место (или другое оружие) — разные расчёты: 20–60 тиков
        Vec3 elsewhere = new Vec3(200, 0, 100);
        for (int i = 0; i < 200; i++) {
            long gap = Newisle.launchAt(500, 500, at, WeaponType.MISSILE, WeaponType.MISSILE, elsewhere, r) - 500;
            assertTrue(gap >= 20 && gap <= 60, "зазор разных мест " + gap);
            long w = Newisle.launchAt(500, 500, at, WeaponType.MISSILE, WeaponType.DRONE, at, r) - 500;
            assertTrue(w >= 20 && w <= 60, "зазор другого оружия " + w);
        }
        // близко к прошлому месту, но дальше порога 40 блоков — тоже «разные места»
        assertTrue(Newisle.launchAt(500, 500, at, WeaponType.MISSILE, WeaponType.MISSILE, new Vec3(141, 0, 100), r) - 500 >= 20);
        // зазор уже прошёл — пуск сейчас
        assertEquals(600, Newisle.launchAt(600, 500, at, WeaponType.MISSILE, WeaponType.MISSILE, at, r));
    }
}
