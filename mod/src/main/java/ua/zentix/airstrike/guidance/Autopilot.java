package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.strike.WeaponSpec;

/**
 * Общие поведения управляемого полёта — один раз для всех законов ({@link DroneAutopilot}, {@link MissileAutopilot},
 * B-2): точка внутри круга разворота и уход на повторный заход, курс на точку маршрута. Чистый код без мира Minecraft.
 */
public final class Autopilot {
    private Autopilot() {}

    /**
     * Разворот по курсу на участке полёта.
     *
     * @param gain   усиление поворота к курсу на точку
     * @param rate   предельная угловая скорость, °/тик (паспорт)
     * @param accel  предельное угловое ускорение, °/тик²
     * @param settle угловое ускорение, с которым гасится поворот при уходе на повторный заход
     */
    public record Turn(double gain, double rate, double accel, double settle) {}

    /** Ближе этого по горизонтали курс на точку не трогаем: над самой точкой он скачет. */
    private static final double HOLD_COURSE = 8;

    /**
     * Точка внутри круга разворота: с предельной угловой скоростью {@code maxRateDeg} °/тик снаряд до неё не довернёт
     * и кружил бы вокруг неё, пока не кончится запас хода (крылатая ракета на 4 блоках/тик и 3°/тик разворачивается
     * по кругу радиусом ~80 блоков: цель, сместившаяся вбок на атаке, оставалась внутри). Такой снаряд сначала уходит
     * прямо, пока точка не выйдет из круга, и заходит снова. С запасом на разгон угловой скорости — {@link WeaponSpec#TURN_MARGIN}.
     */
    public static boolean insideTurn(Vec3 pos, Vec3 forward, double speed, Vec3 point, double maxRateDeg) {
        double fl = Math.sqrt(forward.x * forward.x + forward.z * forward.z);
        if (fl < 1e-6 || speed <= 0) return false;
        double fx = forward.x / fl, fz = forward.z / fl;
        double dx = point.x - pos.x, dz = point.z - pos.z;
        double r = WeaponSpec.turnRadius(speed * fl, maxRateDeg) * WeaponSpec.TURN_MARGIN;
        // центр разворота — сбоку, в сторону точки
        double nx = -fz, nz = fx;
        if (nx * dx + nz * dz < 0) {
            nx = -nx;
            nz = -nz;
        }
        double cx = dx - nx * r, cz = dz - nz * r;
        return cx * cx + cz * cz < r * r;
    }

    /**
     * Точка маршрута {@code nav} внутри круга разворота (промах в пике, цель ушла вбок, точка в воздухе, где цель
     * пропала): до неё не довернуть — атака отменяется, снаряд уходит прямо и заходит снова. Ближе {@code reattackMin}
     * по горизонтали — нет: небольшой промах добирает неконтактный взрыватель, а пролетев, снаряд зайдёт снова.
     */
    public static boolean outOfTurn(Craft c, Vec3 nav, Bearing toNav, double reattackMin, double rate) {
        return toNav.horizontal() > reattackMin && insideTurn(c.position(), c.flight().forward(), c.speed(), nav, rate);
    }

    /** Курс: на повторном заходе — прямо, гася поворот; иначе — на точку маршрута (над самой точкой курс не трогаем). */
    public static void steer(Craft c, boolean outOfTurn, Bearing toNav, Turn turn) {
        if (outOfTurn) {
            c.flight().settleYaw(turn.settle());
        } else if (toNav.horizontal() > HOLD_COURSE) {
            c.flight().steerYaw(toNav.yaw(), turn.gain(), turn.rate(), turn.accel());
        }
    }
}
