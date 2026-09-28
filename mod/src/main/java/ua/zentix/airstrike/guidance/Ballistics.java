package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;

/**
 * Баллистика неуправляемого снаряда по тикам: {@code p(n) = start + n·v0 + g·n(n−1)/2} (g вниз). Решение
 * «из точки в точку за N тиков» приходит в цель ровно на тике N, без накопления ошибки шага.
 */
public final class Ballistics {
    /** Ускорение свободного падения, блоков/тик²: 9.8 м/с² при 20 тиках в секунду. */
    public static final double GRAVITY = 9.8 / 400;

    private Ballistics() {}

    /**
     * Время полёта при угле возвышения {@code elevation}: N(N−1) = 2(d·tgθ − dy)/g. Если цель так близко или так
     * высоко, что выходит меньше {@code minTicks}, — угол круче (шагом 5°, до 80°), как у миномётной траектории.
     */
    public static int ticksFor(Vec3 from, Vec3 to, float elevation, int minTicks) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double d = Math.sqrt(dx * dx + dz * dz), dy = to.y - from.y;
        for (float theta = elevation; theta <= 80; theta += 5) {
            double rhs = 2 * (d * Math.tan(Math.toRadians(theta)) - dy) / GRAVITY;
            if (rhs <= 0) continue;
            int ticks = (int) Math.ceil((1 + Math.sqrt(1 + 4 * rhs)) / 2);
            if (ticks >= minTicks) return ticks;
        }
        return minTicks;
    }

    /** Начальная скорость, с которой снаряд из {@code from} будет в {@code to} ровно через {@code ticks}. */
    public static Vec3 launchVelocity(Vec3 from, Vec3 to, int ticks) {
        return to.subtract(from).scale(1.0 / ticks).add(0, GRAVITY * (ticks - 1) / 2, 0);
    }

    /** Точка траектории на тике {@code k}. */
    public static Vec3 at(Vec3 start, Vec3 v0, int k) {
        return start.add(v0.scale(k)).add(0, -GRAVITY * k * (k - 1) / 2, 0);
    }
}
