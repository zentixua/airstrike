package ua.zentix.airstrike.guidance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Ориентация и поворот снаряда. Углы в градусах в соглашении Minecraft: yaw 0 = +Z (юг), растёт к -X (запад);
 * pitch > 0 — нос вниз. Угловая скорость (°/тик) и угловое ускорение (°/тик²) ограничены — поэтому полёт
 * без рывков: снаряд не «ломается» в точке, а выходит на курс плавной дугой (так было и в датапаке).
 */
public final class FlightController {
    private float yaw;
    private float pitch;
    private float yawRate;
    private float pitchRate;

    public FlightController(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** Копия с теми же углами и угловыми скоростями: проиграть поворот вперёд, не трогая снаряд. */
    public FlightController copy() {
        FlightController c = new FlightController(yaw, pitch);
        c.yawRate = yawRate;
        c.pitchRate = pitchRate;
        return c;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public void set(float yaw, float pitch) {
        this.yaw = Mth.wrapDegrees(yaw);
        this.pitch = Mth.clamp(pitch, -89f, 89f);
    }

    /** Направление носа (единичный вектор). */
    public Vec3 forward() {
        return Vec3.directionFromRotation(pitch, yaw);
    }

    /**
     * Тангаж: командная угловая скорость → ограничения |ω| ≤ maxRate, |Δω| ≤ maxAccel.
     *
     * @param commandRate желаемая угловая скорость, °/тик (> 0 — нос вниз)
     */
    public void steerPitch(double commandRate, double maxRate, double maxAccel) {
        double w = Mth.clamp(commandRate, -maxRate, maxRate);
        double dw = Mth.clamp(w - pitchRate, -maxAccel, maxAccel);
        pitchRate += (float) dw;
        pitch = Mth.clamp(pitch + pitchRate, -89f, 89f);
    }

    /** Держать тангаж: угловая скорость пропорциональна ошибке. */
    public void holdPitch(double targetPitch, double gain, double maxRate, double maxAccel) {
        steerPitch((targetPitch - pitch) * gain, maxRate, maxAccel);
    }

    /**
     * Наведение по дуге (пропорциональное): ω = 2·G·v·(угол на цель − тангаж)/дальность, G = 1.6.
     * На большой дальности поворот мягкий, у цели — энергичный; снаряд входит в цель по дуге.
     */
    public void arcPitch(double losPitch, double speed, double distance, double maxRate, double maxAccel) {
        double w = 3.2 * speed * (losPitch - pitch) / Math.max(distance, 1.0);
        steerPitch(w, maxRate, maxAccel);
    }

    /**
     * Курс на цель: ω = gain·ошибка, |ω| ≤ maxRate °/тик, |Δω| ≤ maxAccel °/тик².
     *
     * @param targetYaw курс на цель
     */
    public void steerYaw(double targetYaw, double gain, double maxRate, double maxAccel) {
        trackYaw(targetYaw, 0, gain, maxRate, maxAccel);
    }

    /**
     * Курс по кривой: к упреждению {@code feedForward} (°/тик — с какой скоростью поворачивает сама кривая,
     * например v/R на круге) добавляется поправка gain·ошибка. Без упреждения на установившемся развороте курс
     * отстаёт на ω/gain, и снаряд сползает с кривой наружу.
     */
    public void trackYaw(double targetYaw, double feedForward, double gain, double maxRate, double maxAccel) {
        double err = Mth.wrapDegrees(targetYaw - yaw);
        double w = Mth.clamp(feedForward + err * gain, -maxRate, maxRate);
        double dw = Mth.clamp(w - yawRate, -maxAccel, maxAccel);
        yawRate += (float) dw;
        yaw = Mth.wrapDegrees(yaw + yawRate);
    }

    /** Гасим курсовую скорость (над самой целью курс не трогаем). */
    public void settleYaw(double maxAccel) {
        yawRate -= (float) Mth.clamp(yawRate, -maxAccel, maxAccel);
        yaw = Mth.wrapDegrees(yaw + yawRate);
    }

    /** Крен для красоты: пропорционален скорости разворота, как у настоящего самолёта в координированном вираже. */
    public float bankAngle(double speed) {
        // tan(крен) = v·ω/g; v в блоках/тик, ω в рад/тик, g ≈ 0.08 блока/тик² (как в Minecraft)
        double w = Math.toRadians(yawRate);
        return (float) Mth.clamp(Math.toDegrees(Math.atan(speed * w / 0.08)), -70, 70);
    }

    public void save(CompoundTag tag) {
        tag.putFloat("yaw", yaw);
        tag.putFloat("pitch", pitch);
        tag.putFloat("yaw_rate", yawRate);
        tag.putFloat("pitch_rate", pitchRate);
    }

    public void load(CompoundTag tag) {
        yaw = tag.getFloat("yaw");
        pitch = tag.getFloat("pitch");
        yawRate = tag.getFloat("yaw_rate");
        pitchRate = tag.getFloat("pitch_rate");
    }

    /** Угол на точку: [yaw, pitch] в градусах. */
    public static float[] anglesTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) -(Mth.atan2(dy, h) * Mth.RAD_TO_DEG);
        return new float[]{Mth.wrapDegrees(yaw), pitch};
    }
}
