package ua.zentix.airstrike.client.render;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.StrikeProjectile;

/**
 * Всё, что нужно модели снаряда на кадр ({@link WeaponModels}): где он, как повёрнут, фаза полёта и её время, куда
 * летит. Заполняется по сущности, пока она есть у клиента, а без неё — по пути из пакетов сервера
 * ({@code client.far.FarFlightView}): модель и её анимации — один код вблизи и вдали. Тот же шаг, что у Mojang
 * в 1.21.2 ({@code EntityRenderState}). Объект живёт между кадрами: кадр ничего не выделяет.
 */
public final class ProjectilePose {
    /** Где, блоков. */
    public double x, y, z;
    /** Курс, тангаж и крен, градусы (как у сущности). */
    public float yaw, pitch, roll;
    public FlightPhase phase = FlightPhase.READY;
    /** Тиков в нынешней фазе и с начала полёта, с долей тика. */
    public float phaseAge, age;
    /** Точка цели, блоков. */
    public double aimX, aimY, aimZ;
    /** Рисовать упрощённые копии деталей ({@link WeaponModels.Mesh#lod}): модель мелкая на экране. */
    public boolean coarse;

    /** По сущности у клиента: положение и углы — между тиками, крен — последний присланный. */
    public ProjectilePose set(StrikeProjectile e, float partial) {
        Vec3 p = e.getPosition(partial);
        x = p.x;
        y = p.y;
        z = p.z;
        yaw = Mth.rotLerp(partial, e.yRotO, e.getYRot());
        pitch = Mth.lerp(partial, e.xRotO, e.getXRot());
        roll = e.roll();
        phase = e.flightPhase();
        phaseAge = e.phaseAge() + partial;
        age = e.age() + partial;
        Vec3 aim = e.aimPoint();
        aimX = aim.x;
        aimY = aim.y;
        aimZ = aim.z;
        coarse = false;
        return this;
    }

    /** Те же числа, что у o. */
    public ProjectilePose copy(ProjectilePose o) {
        x = o.x;
        y = o.y;
        z = o.z;
        yaw = o.yaw;
        pitch = o.pitch;
        roll = o.roll;
        phase = o.phase;
        phaseAge = o.phaseAge;
        age = o.age;
        aimX = o.aimX;
        aimY = o.aimY;
        aimZ = o.aimZ;
        coarse = o.coarse;
        return this;
    }

    /** Поворот модели (нос по +Z) в мир: курс, тангаж, крен. */
    public Quaternionf rotation(Quaternionf out) {
        return out.rotationYXZ(-yaw * Mth.DEG_TO_RAD, pitch * Mth.DEG_TO_RAD, roll * Mth.DEG_TO_RAD);
    }
}
