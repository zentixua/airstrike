package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontProfileTest {
    private static final ArrivalTable MODEL = ArrivalTable.of(15, 20000);

    /** Скорость дальней зоны по умолчанию ({@code nuclear.wave_speed} 0.5) и прежняя, 3 блока за тик. */
    private static final double HALF = 0.5 * FrontProfile.SOUND_SPEED, OLD = 3;

    /** Профиль 15 кт в масштабе {@code scale} со скоростью дальней зоны {@code far}, как его строит {@code Detonation}. */
    private record Case(double scale, double from, double sound, double far, FrontProfile p) {
        static Case of(double scale, boolean surface, double far) {
            double from = FrontProfile.slowFrom(FireballModel.maxRadius(15, surface) * scale);
            double sound = BlastModel.rangeForOverpressure(BlastModel.kpa(0.3), 15) * scale;
            return new Case(scale, from, sound, far, FrontProfile.of(MODEL, scale, from, sound, sound * 1.5, far));
        }

        double model(double blocks) {
            return MODEL.arrivalSeconds(blocks / scale) * 20 * scale;
        }
    }

    private static final Case SMALL = Case.of(0.3, false, HALF), LIFE = Case.of(1, false, HALF), LIFE_SURFACE = Case.of(1, true, HALF),
            LIFE_OLD = Case.of(1, false, OLD), SMALL_OLD = Case.of(0.3, false, OLD), LIFE_REAL = Case.of(1, false, FrontProfile.SOUND_SPEED);
    private static final Case[] ALL = {SMALL, LIFE, LIFE_SURFACE, LIFE_OLD, SMALL_OLD, LIFE_REAL};

    /** Два радиуса шара, но не дальше 200 блоков: в масштабе 1 фронт к 600 блокам уже идёт со скоростью дальней зоны. */
    @Test
    void slowFromIsTwoFireballsCapped() {
        assertEquals(2 * FireballModel.maxRadius(15, false) * 0.3, SMALL.from, 1e-9);
        assertEquals(FrontProfile.SLOW_FROM_MAX, LIFE.from, 1e-9);
        assertEquals(FrontProfile.SLOW_FROM_MAX, LIFE_SURFACE.from, 1e-9);
    }

    /** У эпицентра — как у модели: вспышка, шар и первый удар не меняются. */
    @Test
    void nearGroundZeroFollowsModel() {
        for (Case c : ALL) {
            for (double b = 1; b <= c.from; b += 7) assertEquals(c.model(b), c.p.arrivalTicks(b), Math.max(0.05, c.model(b) * 0.01), "на " + b);
        }
    }

    /** Дальше не быстрее модели, монотонно, без скачков скорости, и от {@link FrontProfile#SLOW_TO} до края стены — скорость дальней зоны. */
    @Test
    void slowsDownSmoothlyToFarSpeed() {
        for (Case c : new Case[]{SMALL, LIFE, LIFE_SURFACE, LIFE_OLD, SMALL_OLD}) {
            double prev = 0, prevSpeed = Double.NaN;
            for (double b = 1; b < c.sound - 2; b += 1) {
                double t = c.p.arrivalTicks(b);
                assertTrue(t > prev, "время не растёт на " + b);
                assertTrue(t >= c.model(b) - 1e-6, "быстрее модели на " + b);
                double speed = 1 / (t - prev);
                if (b > c.from + 2 && !Double.isNaN(prevSpeed)) assertTrue(speed <= prevSpeed * 1.05 + 0.05, "скорость скачет на " + b);
                if (b >= FrontProfile.SLOW_TO + 5) assertEquals(c.far, speed, 0.02 * c.far, "на " + b + " (масштаб " + c.scale + ")");
                prev = t;
                prevSpeed = speed;
            }
        }
    }

    /**
     * Масштаб 1, 15 кт, половина звука: на 3 км удар через ~15 с после вспышки (в жизни 7, при прежних 3 блоках за тик
     * — 50), последние 200 блоков стена идёт дольше секунды — видно, как она катится.
     */
    @Test
    void wallIsVisibleApproaching() {
        double to3km = LIFE.p.arrivalTicks(3000) / 20;
        assertTrue(to3km >= 14 && to3km <= 20, "до 3 км " + to3km + " с");
        for (Case c : new Case[]{SMALL, LIFE, LIFE_SURFACE}) {
            assertTrue(c.p.arrivalTicks(1200) - c.p.arrivalTicks(1200 - 200) >= 20, "масштаб " + c.scale);
        }
        assertTrue(LIFE_OLD.p.arrivalTicks(3000) / 20 > 2 * to3km, "прежняя скорость не медленнее");
    }

    /** Скорость 1.0 — как в жизни: приход везде как у модели (её фронт вдали не медленнее звука). */
    @Test
    void fullSpeedIsTheModel() {
        Case c = LIFE_REAL;
        for (double b = 1; b < c.sound; b += 13) assertEquals(c.model(b), c.p.arrivalTicks(b), Math.max(0.05, c.model(b) * 0.01), "на " + b);
    }

    /**
     * За стеной пыли — звук: со скоростью звука от её края (у 15 кт — ~3.5 км), и дальний удар в 5 км слышен через
     * десятки секунд, а не минуты, как если бы и там шла стена.
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
        for (Case c : ALL) {
            for (double b = 0; b <= 9000; b += 37) assertEquals(b, c.p.radiusAt(c.p.arrivalTicks(b)), 1e-3 + b * 1e-6);
            assertEquals(0, c.p.radiusAt(0), 0);
        }
    }
}
