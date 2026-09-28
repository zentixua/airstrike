package ua.zentix.airstrike.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Круг над точкой — векторное поле курсов (Nelson et al., «Vector Field Path Following for Miniature Air Vehicles»,
 * IEEE Trans. Robotics, 2007): касательная, повёрнутая к кругу на atan(k·(r − R)). Издалека это почти прямо на центр,
 * у круга — плавно по касательной, поэтому выход на круг идёт без перелёта. К поправке по ошибке курса добавляется
 * упреждение v/r — с какой скоростью поворачивает сама касательная: без него курс на круге отстаёт на ω/gain,
 * и аппарат сползает наружу.
 *
 * @param radius радиус круга, блоков
 * @param side   направление облёта: +1 или −1
 */
public record Orbit(double radius, int side) {
    /** Крутизна поля: у самого круга — 2° поворота к нему на блок отклонения. */
    private static final double CONVERGENCE = Math.toRadians(2);
    /** Поправка по ошибке курса и пределы поворота (°/тик, °/тик²) — под барражирующий на 1.6 блока/тик. */
    private static final double GAIN = 0.1, MAX_RATE = 3.0, MAX_ACCEL = 0.3;
    /** Вышел на круг: ближе этого к его линии и курсом по касательной с точностью {@link #CAPTURE_YAW}. */
    private static final double CAPTURE_DISTANCE = 4, CAPTURE_YAW = 15;
    /** На сколько блоков аппарат на круге может отойти от его линии (с учётом выхода на круг). */
    public static final double TOLERANCE = 8;

    /** Курс поля в точке {@code pos} вокруг центра {@code center}, градусы. */
    public double heading(Vec3 pos, Vec3 center) {
        double r = horizontal(pos, center);
        double toCenter = FlightController.anglesTo(pos, center)[0];
        return toCenter - side * (90 - Math.toDegrees(Math.atan(CONVERGENCE * (r - radius))));
    }

    /** Повернуть по полю: курс к {@link #heading}, упреждение — поворот касательной при скорости {@code speed}. */
    public void steer(FlightController flight, Vec3 pos, Vec3 center, double speed) {
        double r = Math.max(1, horizontal(pos, center));
        flight.trackYaw(heading(pos, center), side * Math.toDegrees(speed / r), GAIN, MAX_RATE, MAX_ACCEL);
    }

    /** На круге: у его линии и курсом по касательной. */
    public boolean captured(Vec3 pos, Vec3 center, float yaw) {
        double tangent = FlightController.anglesTo(pos, center)[0] - side * 90.0;
        return Math.abs(horizontal(pos, center) - radius) < CAPTURE_DISTANCE && Math.abs(Mth.wrapDegrees(tangent - yaw)) < CAPTURE_YAW;
    }

    /** Отклонение от линии круга по горизонтали: > 0 — снаружи. */
    public double offset(Vec3 pos, Vec3 center) {
        return horizontal(pos, center) - radius;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
