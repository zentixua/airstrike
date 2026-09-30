package ua.zentix.airstrike.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontProfileTest {
    private static final ArrivalTable MODEL = ArrivalTable.of(15, 20000);

    /** Профиль 15 кт в масштабе {@code scale}, как его строит {@code Detonation}. */
    private record Case(double scale, double from, double sound, FrontProfile p) {
        static Case of(double scale, boolean surface) {
            double from = FrontProfile.slowFrom(FireballModel.maxRadius(15, surface) * scale);
            double sound = BlastModel.rangeForOverpressure(BlastModel.kpa(0.3), 15) * scale;
            return new Case(scale, from, sound, FrontProfile.of(MODEL, scale, from, sound, sound * 1.5));
        }

        double model(double blocks) {
            return MODEL.arrivalSeconds(blocks / scale) * 20 * scale;
        }
    }

    private static final Case SMALL = Case.of(0.3, false), LIFE = Case.of(1, false), LIFE_SURFACE = Case.of(1, true);

    /** Два радиуса шара, но не дальше 200 блоков: в масштабе 1 фронт к 600 блокам уже идёт 3 блока за тик. */
    @Test
    void slowFromIsTwoFireballsCapped() {
        assertEquals(2 * FireballModel.maxRadius(15, false) * 0.3, SMALL.from, 1e-9);
        assertEquals(FrontProfile.SLOW_FROM_MAX, LIFE.from, 1e-9);
        assertEquals(FrontProfile.SLOW_FROM_MAX, LIFE_SURFACE.from, 1e-9);
    }

    /** У эпицентра — как у модели: вспышка, шар и первый удар не меняются. */
    @Test
    void nearGroundZeroFollowsModel() {
        for (Case c : new Case[]{SMALL, LIFE}) {
            for (double b = 1; b <= c.from; b += 7) assertEquals(c.model(b), c.p.arrivalTicks(b), Math.max(0.05, c.model(b) * 0.01), "на " + b);
        }
    }

    /** Дальше не быстрее модели, монотонно, без скачков скорости, и от {@link FrontProfile#SLOW_TO} до края стены — 3 блока за тик. */
    @Test
    void slowsDownSmoothlyToMinSpeed() {
        for (Case c : new Case[]{SMALL, LIFE, LIFE_SURFACE}) {
            double prev = 0, prevSpeed = Double.NaN;
            for (double b = 1; b < c.sound - 2; b += 1) {
                double t = c.p.arrivalTicks(b);
                assertTrue(t > prev, "время не растёт на " + b);
                assertTrue(t >= c.model(b) - 1e-6, "быстрее модели на " + b);
                double speed = 1 / (t - prev);
                if (b > c.from + 2 && !Double.isNaN(prevSpeed)) assertTrue(speed <= prevSpeed * 1.05 + 0.05, "скорость скачет на " + b);
                if (b >= FrontProfile.SLOW_TO + 5) assertEquals(FrontProfile.MIN_SPEED, speed, 0.05, "на " + b + " (масштаб " + c.scale + ")");
                prev = t;
                prevSpeed = speed;
            }
        }
    }

    /**
     * Масштаб 1, 15 кт: стена пыли идёт до зоны руин (1 км) секунды — видно, как она подходит; прорисовка в 8 чанков
     * (128 блоков) за 600 блоками проходится дольше 2 с.
     */
    @Test
    void wallIsVisibleApproaching() {
        for (Case c : new Case[]{SMALL, LIFE, LIFE_SURFACE}) {
            assertTrue(c.p.arrivalTicks(800) - c.p.arrivalTicks(800 - 128) >= 40, "масштаб " + c.scale);
        }
        double toKm = LIFE.p.arrivalTicks(1000) / 20;
        assertTrue(toKm >= 7 && toKm <= 12, "до 1 км " + toKm + " с");
        assertTrue(LIFE.p.arrivalTicks(600) / 20 <= 3, "до 600 блоков " + LIFE.p.arrivalTicks(600) / 20 + " с");
    }

    /**
     * За стеной пыли — звук: со скоростью звука от её края (у 15 кт — ~3.5 км, стена доходит туда за ~50 с), дальний
     * удар в 5 км — через ~75 с, а не через 9 минут, если бы фронт и там шёл 3 блока за тик.
     */
    @Test
    void soundBeyondWallTravelsAtSoundSpeed() {
        Case c = LIFE;
        double edge = c.p.arrivalTicks(c.sound);
        for (double b = c.sound + 50; b <= 8000; b += 250) {
            assertEquals(edge + (b - c.sound) / FrontProfile.SOUND_SPEED, c.p.arrivalTicks(b), 2, "на " + b);
        }
        assertTrue(c.p.arrivalTicks(5000) / 20 < 90, "5 км — " + c.p.arrivalTicks(5000) / 20 + " с");
    }

    @Test
    void radiusIsInverseOfArrival() {
        for (Case c : new Case[]{SMALL, LIFE}) {
            for (double b = 0; b <= 9000; b += 37) assertEquals(b, c.p.radiusAt(c.p.arrivalTicks(b)), 1e-3 + b * 1e-6);
            assertEquals(0, c.p.radiusAt(0), 0);
        }
    }
}
