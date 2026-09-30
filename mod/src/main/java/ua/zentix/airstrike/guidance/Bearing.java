package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;

/**
 * Где точка относительно снаряда.
 *
 * @param distance   расстояние, блоков
 * @param horizontal расстояние по горизонтали, блоков
 * @param yaw        курс на точку, °
 * @param pitch      угол точки под горизонтом, ° (> 0 — ниже)
 */
public record Bearing(double distance, double horizontal, float yaw, float pitch) {
    public static Bearing of(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dz = to.z - from.z;
        float[] a = FlightController.anglesTo(from, to);
        return new Bearing(from.distanceTo(to), Math.sqrt(dx * dx + dz * dz), a[0], a[1]);
    }
}
