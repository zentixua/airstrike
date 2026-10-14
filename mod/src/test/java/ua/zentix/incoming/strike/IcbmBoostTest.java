package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разгон МБР ({@link WeaponSpec.Ascent} паспорта {@link WeaponSpec#NUKE}) по тикам совпадает с формулой ракеты
 * постоянной тяги: a(t) = T / (m₀ − ṁ·t) − g. Без мира и без тангажа — отвесно.
 */
class IcbmBoostTest {
    private static final WeaponSpec.Ascent A = WeaponSpec.NUKE.ascent();
    private static final double G = 9.81;

    /** Высота по формуле через {@code t} с, м: h = (T/ṁ)·(t − (m₁/ṁ)·ln(m₀/m₁)) − g·t²/2, m₁ = m₀ − ṁ·t. */
    private static double height(double t) {
        double mdot = A.propellant() / A.burnSeconds(), m1 = A.launchMass() - mdot * t;
        return A.thrust() / mdot * (t - m1 / mdot * Math.log(A.launchMass() / m1)) - G * t * t / 2;
    }

    /** Скорость по формуле через {@code t} с, м/с: v = (T/ṁ)·ln(m₀/m₁) − g·t. */
    private static double velocity(double t) {
        double mdot = A.propellant() / A.burnSeconds(), m1 = A.launchMass() - mdot * t;
        return A.thrust() / mdot * Math.log(A.launchMass() / m1) - G * t;
    }

    @Test
    void liftOffIsAboutOneAndHalfG() {
        double expected = A.thrust() / A.launchMass() - G;
        assertEquals(expected, A.accel(0), expected * 0.05);
        double g = A.accel(0) / G;
        assertTrue(g >= 1.2 && g <= 1.8, "на отрыве " + g + " g");
    }

    @Test
    void accelerationGrowsAsFuelBurns() {
        double prev = A.accel(0);
        for (int tick = 1; tick <= 200; tick++) {
            double a = A.accel(tick / 20.0);
            assertTrue(a > prev, "ускорение на " + tick + "-м тике не выросло: " + a + " после " + prev);
            prev = a;
        }
    }

    @Test
    void tenSecondsMatchTheFormula() {
        double speed = 0, y = 0, cruise = WeaponSpec.NUKE.airframe().cruiseSpeed();
        for (int tick = 1; tick <= 200; tick++) {
            speed = Math.min(cruise, A.step(speed, tick));
            y += speed;
        }
        // блок = метр, тик = 0,05 с: блоков/тик × 20 = м/с
        assertEquals(height(10), y, height(10) * 0.05, "высота через 10 с");
        assertEquals(velocity(10) / 20, speed, velocity(10) / 20 * 0.05, "скорость через 10 с");
        assertEquals(8.2, speed, 8.2 * 0.05, "скорость через 10 с, блоков/тик");
        assertTrue(speed < cruise, "предел паспорта достигнут за 10 с");
    }
}
