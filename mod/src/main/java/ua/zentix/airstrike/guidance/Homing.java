package ua.zentix.airstrike.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Погоня зенитной ракеты: курс на нынешнее место цели с пределом скорости поворота (чистое преследование, без
 * упреждения) и ближайшее сближение двух точек за тик — для неконтактного взрывателя. Без мира Minecraft
 * (юнит-тест {@code HomingTest}).
 */
public final class Homing {
    private Homing() {}

    /**
     * Повернуть единичное направление {@code dir} к единичному {@code want} не больше чем на {@code maxDeg} градусов —
     * по большому кругу, в плоскости обоих. Цель точно сзади — поворот через верх (у вертикального курса — через +X).
     */
    public static Vec3 turn(Vec3 dir, Vec3 want, double maxDeg) {
        double cos = Mth.clamp(dir.dot(want), -1, 1);
        double max = Math.toRadians(maxDeg);
        if (Math.acos(cos) <= max) return want;
        // перпендикуляр к dir в сторону want
        Vec3 side = want.subtract(dir.scale(cos));
        if (side.lengthSqr() < 1.0e-12) {
            Vec3 up = Math.abs(dir.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            side = up.subtract(dir.scale(up.dot(dir)));
        }
        side = side.normalize();
        return dir.scale(Math.cos(max)).add(side.scale(Math.sin(max))).normalize();
    }

    /**
     * Доля тика {@code s} ∈ [0, 1], в которую точки, идущие равномерно из {@code a0} в {@code a1} и из {@code b0}
     * в {@code b1}, ближе всего друг к другу: быстрая ракета за тик проходит цель насквозь, и расстояние в концах тика
     * его не ловит.
     */
    public static double closest(Vec3 a0, Vec3 a1, Vec3 b0, Vec3 b1) {
        Vec3 r0 = a0.subtract(b0);
        Vec3 dv = a1.subtract(a0).subtract(b1.subtract(b0));
        double dd = dv.lengthSqr();
        if (dd < 1.0e-12) return 0;
        return Mth.clamp(-r0.dot(dv) / dd, 0, 1);
    }

    /** Расстояние между теми же точками в долю тика {@code s}. */
    public static double distanceAt(Vec3 a0, Vec3 a1, Vec3 b0, Vec3 b1, double s) {
        return a0.lerp(a1, s).distanceTo(b0.lerp(b1, s));
    }
}
