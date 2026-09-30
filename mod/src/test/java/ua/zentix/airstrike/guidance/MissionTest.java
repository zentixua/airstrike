package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionTest {
    /** Тиков до конца запаса на постоянной скорости: запас проверяется в начале тика, расход — в конце. */
    private static int ticksToExhaust(Mission m, double speed) {
        int t = 0;
        while (!m.exhausted()) {
            m.spend(speed);
            t++;
        }
        return t;
    }

    /** План: путь × 1.5 и резерв 600 тиков на маршевой — на маршевой скорости полёт кончается там же, где кончался срок жизни. */
    @Test
    void planOnCruiseMatchesOldLifetime() {
        for (double cruise : new double[]{1.6, 2.1, 4, 12}) {
            for (double path : new double[]{0, 100, 1234.5, 6000}) {
                Mission m = Mission.plan(path, cruise);
                assertEquals(path * 1.5 + 600 * cruise, m.range(), 1e-9);
                // прежний срок: (int) (путь / маршевая × 1.5) + 600 тиков (старта здесь нет)
                int old = (int) (path / cruise * 1.5) + 600;
                assertEquals(old, ticksToExhaust(m, cruise), 1, "маршевая " + cruise + ", путь " + path);
            }
        }
    }

    /**
     * Стоянка на пусковой и ожидание района цели не тратят запас: снаряд, который ждёт (скорость 0), так и не
     * самоликвидируется — у ожидания свой предел. Медленный участок (разгон на ускорителе, набор ракеты после #149)
     * тратит ровно пройденный путь: запаса хватает на путь по плану, как бы медленно он ни шёл.
     */
    @Test
    void waitsAndSlowLegsSpendOnlyTheirPath() {
        Mission m = Mission.plan(1000, 4);
        for (int t = 0; t < 100_000; t++) m.spend(0);
        assertEquals(1000 * 1.5 + 600 * 4, m.range(), 1e-9);
        // ракета: 40 тиков ускорителя до 3.4 блока/тик, дальше турбина + 0.09 до маршевой 4, путь 1000 блоков по плану
        Mission slow = Mission.plan(1000, 4);
        double flown = 0, speed = 0;
        int t = 0;
        while (flown < 1000) {
            speed = t < 40 ? Math.min(3.4, speed + 0.09) : Math.min(4, speed + 0.09);
            assertFalse(slow.exhausted(), "запас кончился на " + flown + " блоках из 1000");
            slow.spend(speed);
            flown += speed;
            t++;
        }
        assertEquals(1000 * 1.5 + 600 * 4 - flown, slow.range(), 1e-6);
    }

    /**
     * РСЗО вне мира: пока район цели не готов, время траектории растягивается (темп < 1) — снаряд проходит ту же дугу
     * за большее число тиков мира и тратит ровно её длину: запас «дуга + резерв» не кончается до точки падения.
     */
    @Test
    void stretchedBallisticsSpendsTheArc() {
        Random rnd = new Random(7);
        for (int i = 0; i < 200; i++) {
            Vec3 from = new Vec3(0, 70, 0);
            Vec3 to = new Vec3(200 + rnd.nextDouble() * 600, 60 + rnd.nextDouble() * 20, rnd.nextDouble() * 200 - 100);
            int n = 60 + rnd.nextInt(200);
            Vec3 v0 = Ballistics.launchVelocity(from, to, n);
            double arc = 0;
            for (int k = 0; k < n; k++) arc += Ballistics.at(from, v0, k + 1).subtract(Ballistics.at(from, v0, k)).length();
            Mission m = Mission.of(arc + 200 * 4);
            double t = 0;
            int worldTicks = 0;
            while (t < n) {
                // темп плавает между почти остановкой и 1, как у растяжения RocketEntity.stretch
                double rate = Math.max(0.02, Math.min(1, 0.5 + 0.5 * Math.sin(worldTicks * 0.05)));
                double step = Math.min(rate, n - t);
                assertFalse(m.exhausted(), "запас кончился на дуге, t = " + t + " из " + n);
                m.spend(Ballistics.at(from, v0, t + step).subtract(Ballistics.at(from, v0, t)).length());
                t += step;
                worldTicks++;
            }
            assertTrue(worldTicks > n, "траектория не растягивалась");
            // дробные шаги по дуге — хорды, их сумма не длиннее самой дуги (с точностью до разницы хорд целых тиков и дуги)
            assertEquals(200 * 4, m.range(), 1, "растянутый полёт потратил не длину дуги");
        }
    }

    /** Погоня: сдвиг цели × 1.5 — без дробей и округлений. Сдвиг 0 или отрицательный ничего не меняет. */
    @Test
    void chaseAddsMovedPath() {
        Mission m = Mission.plan(100, 2.1);
        double before = m.range();
        for (int i = 0; i < 1000; i++) m.chase(0.01);
        assertEquals(before + 1000 * 0.01 * 1.5, m.range(), 1e-9);
        m.chase(0);
        m.chase(-5);
        assertEquals(before + 15, m.range(), 1e-9);
    }

    /**
     * Потеря цели: запас — на путь до последней точки × 1.5 и 200 тиков на маршевой (прежние LOST_GRACE), один раз;
     * погоня после этого запас не растит сверх урезанного. Запас меньше этого не растёт. Перенацеливание снимает флаг.
     */
    @Test
    void loseCapsOnce() {
        Mission m = Mission.plan(3000, 2.1);
        m.chase(2000);
        assertTrue(m.lose(100, 2.1));
        double capped = 100 * 1.5 + 200 * 2.1;
        assertEquals(capped, m.range(), 1e-9);
        // на маршевой — те же тики, что у прежнего срока после потери: ceil(время до удара × 1.5) + 200
        assertEquals((int) Math.ceil(100 / 2.1 * 1.5) + 200, ticksToExhaust(Mission.load(m.save()), 2.1), 1);
        assertFalse(m.lose(10, 2.1), "урезан второй раз");
        assertEquals(capped, m.range(), 1e-9);

        Mission low = Mission.of(50);
        assertTrue(low.lose(1000, 2.1));
        assertEquals(50, low.range(), 1e-9);

        m.retarget(40);
        assertEquals(capped + 60, m.range(), 1e-9);
        assertTrue(m.lose(10, 2.1), "после перенацеливания новая цель ещё не потеряна");
    }

    @Test
    void saveRoundTrip() {
        Mission m = Mission.plan(777, 4).extend(123);
        m.spend(55.5);
        m.lose(10_000, 4);
        Mission back = Mission.load(m.save());
        assertEquals(m.range(), back.range());
        assertFalse(back.lose(1, 4), "флаг «урезан» не сохранился");
    }

    /**
     * Сохранение со сроком жизни (2.3.x): остаток срока (ожидание района в нём не считалось) и дробные тики погони —
     * на маршевой скорости сохранения; ракета 2.3.0 летела 11.5 блока/тик, и её остаток пути — 11.5 блока на тик.
     */
    @Test
    void migratesLifetime() {
        // срок 400, возраст 100, из них 20 — ожидание района: 320 тиков и полтика погони
        Mission m = Mission.fromLifetime(400 - (100 - 20), 0.5, false, 4);
        assertEquals(320.5 * 4, m.range(), 1e-9);
        assertEquals(320.5 * 11.5, Mission.fromLifetime(320, 0.5, false, 11.5).range(), 1e-9);
        Mission capped = Mission.fromLifetime(320, 0, true, 4);
        assertFalse(capped.lose(1, 4), "флаг «урезан» не перенесён");
        assertTrue(Mission.fromLifetime(-3, 0, false, 4).exhausted(), "просроченный снаряд получил запас");
    }
}
