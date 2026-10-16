package ua.zentix.incoming.scenario.trailer;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;
import ua.zentix.incoming.strike.WeaponType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверки дублей Newisle без игры: предел поворота камеры вывода ({@link ReplayFilm#turnRate}, Артём, 14.10 — камеры
 * дёргаются), разносчик пусков ({@link Newisle.Launches}, Артём, 15.10 — установки не стреляют в одну
 * миллисекунду) и папка кандидата пробы ({@link ReplayFilm#freshCandidate}, ночь 15.10 — ни одна проба не выведена).
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
    void launchesQueuePerPlaceAndNeverShareATick() {
        RandomSource r = RandomSource.create(1);
        Vec3 a = new Vec3(100, 0, 100), b = new Vec3(400, 0, 100), c = new Vec3(100, 0, 400);
        for (int k = 0; k < 100; k++) {
            Newisle.Launches q = new Newisle.Launches();
            // первого пуска ничто не держит
            assertEquals(500, q.next(500, WeaponType.ROCKET, a, r));
            // другое место — свой расчёт: только не в тот же тик, 3–6 тиков после любого пуска
            long tb = q.next(500, WeaponType.ROCKET, b, r);
            assertTrue(tb - 500 >= 3 && tb - 500 <= 6, "зазор разных мест " + (tb - 500));
            long tc = q.next(500, WeaponType.DRONE, c, r);
            assertTrue(tc - tb >= 3 && tc - tb <= 6, "зазор другого оружия " + (tc - tb));
            // то же место и оружие — очередь одной трубы: 9–13 тиков после своего прошлого пуска, и общий зазор
            long ta = q.next(500, WeaponType.ROCKET, a, r);
            assertTrue(ta - 500 >= 9 && ta - tc >= 3 && ta <= Math.max(513, tc + 6), "очередь места " + (ta - 500) + ", после прошлого " + (ta - tc));
            // то же место, но другое оружие — своя очередь
            long td = q.next(ta, WeaponType.DRONE, a, r);
            assertTrue(td - ta >= 3 && td - ta <= 6, "другое оружие того же места " + (td - ta));
            // близко к месту, но дальше порога 40 блоков — другое место
            long te = q.next(td, WeaponType.ROCKET, new Vec3(141, 0, 100), r);
            assertTrue(te - td >= 3 && te - td <= 6, "дальше порога " + (te - td));
            // зазоры уже прошли — пуск сейчас
            assertEquals(te + 100, q.next(te + 100, WeaponType.ROCKET, a, r));
        }
    }

    @Test
    void probeCandidateHasItsFolderForTheFlashbackJob(@TempDir Path dir) throws IOException {
        Path cand = dir.resolve("n41").resolve("own-20mm");
        Files.createDirectories(cand.resolve("frames").resolve("n41"));
        Files.writeString(cand.resolve("old.txt"), "прежний вывод");
        Path frames = ReplayFilm.freshCandidate(cand, "n41", true);
        // задание Flashback пишется в папку кандидата и у пробы, кадров пробы на диске нет, прежнего вывода — тоже
        assertTrue(Files.isDirectory(cand));
        assertFalse(Files.exists(frames));
        assertFalse(Files.exists(cand.resolve("old.txt")));
        Path full = ReplayFilm.freshCandidate(dir.resolve("n24").resolve("chase"), "n24", false);
        assertTrue(Files.isDirectory(full));
    }
}
