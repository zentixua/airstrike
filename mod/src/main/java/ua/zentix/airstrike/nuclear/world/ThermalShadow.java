package ua.zentix.airstrike.nuclear.world;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.util.Terrain;

/**
 * Видит ли точка огненный шар (DESIGN-nuke §3.4): 16 выборок карты высот вдоль горизонтальной проекции луча —
 * если рельеф или постройка в выборке выше луча, точка в тени. Одно чтение массива на выборку, без {@code clip};
 * отсюда «хиросимские тени» и уцелевшие склоны за холмом.
 */
public final class ThermalShadow {
    private static final int SAMPLES = 16;

    private ThermalShadow() {}

    public static boolean visible(Level level, Vec3 fireball, Vec3 point) {
        return visible(fireball, point, (x, z) -> Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x, z));
    }

    /** То же по своей карте высот (первый воздух над {@code MOTION_BLOCKING}): руины видят мир до волны. */
    static boolean visible(Vec3 fireball, Vec3 point, java.util.function.IntBinaryOperator height) {
        for (int i = 1; i <= SAMPLES; i++) {
            double f = i / (SAMPLES + 1.0);
            double x = point.x + (fireball.x - point.x) * f;
            double y = point.y + (fireball.y - point.y) * f;
            double z = point.z + (fireball.z - point.z) * f;
            if (height.applyAsInt(Mth.floor(x), Mth.floor(z)) > y + 0.5) return false;
        }
        return true;
    }
}
