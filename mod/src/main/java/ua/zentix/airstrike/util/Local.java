package ua.zentix.airstrike.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Локальные координаты, как «^x ^y ^z» в командах: x — влево, y — вверх, z — вперёд по направлению взгляда.
 * Формулы те же, что у ванильного {@code LocalCoordinates}, поэтому смещения из датапака переносятся один в один.
 */
public final class Local {
    private Local() {}

    public static Vec3 offset(float yRot, float xRot, double left, double up, double forward) {
        float f = Mth.cos((yRot + 90.0F) * Mth.DEG_TO_RAD);
        float f1 = Mth.sin((yRot + 90.0F) * Mth.DEG_TO_RAD);
        float f2 = Mth.cos(-xRot * Mth.DEG_TO_RAD);
        float f3 = Mth.sin(-xRot * Mth.DEG_TO_RAD);
        float f4 = Mth.cos((-xRot + 90.0F) * Mth.DEG_TO_RAD);
        float f5 = Mth.sin((-xRot + 90.0F) * Mth.DEG_TO_RAD);
        Vec3 fwd = new Vec3(f * f2, f3, f1 * f2);
        Vec3 upv = new Vec3(f * f4, f5, f1 * f4);
        Vec3 leftv = fwd.cross(upv).scale(-1.0);
        return new Vec3(
                fwd.x * forward + upv.x * up + leftv.x * left,
                fwd.y * forward + upv.y * up + leftv.y * left,
                fwd.z * forward + upv.z * up + leftv.z * left);
    }

    public static Vec3 at(Vec3 base, float yRot, float xRot, double left, double up, double forward) {
        return base.add(offset(yRot, xRot, left, up, forward));
    }

    /** Горизонтальное направление курса (как «rotated ~ 0»). */
    public static Vec3 horizontal(float yRot) {
        return new Vec3(-Mth.sin(yRot * Mth.DEG_TO_RAD), 0, Mth.cos(yRot * Mth.DEG_TO_RAD));
    }
}
