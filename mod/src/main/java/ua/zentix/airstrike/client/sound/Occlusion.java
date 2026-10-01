package ua.zentix.airstrike.client.sound;

import net.minecraft.world.phys.Vec3;

/** Открыт ли путь звука снаряда к уху (1) или за холмом/домом (0), сглажено; считается раз в тик на все слои снаряда. */
final class Occlusion {
    private float open = 1;
    private long tick = -1;

    /** Путь от «запаздывающего» положения {@code at} до уха открыт (1) или закрыт (0); плавно, без щелчков. */
    float open(double now, Vec3 ear, Vec3 at) {
        long t = (long) now;
        if (t != tick) {
            tick = t;
            open += (SoundFilters.open(ear, at) - open) * 0.3f;
        }
        return open;
    }
}
