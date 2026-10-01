package ua.zentix.airstrike.guidance;

import ua.zentix.airstrike.util.GridWalk;

/**
 * Полоса над путём по земле: наибольшая высота рельефа во всех колонках блоков, которые задевает полоса заданной
 * полуширины вдоль ломаной пути. Колонка не пропускается, даже тонкая (столб, мачта, фонарь): полоса проходится
 * параллельными линиями чаще, чем через блок ({@link #LANE}), и каждая линия — по всем колонкам, которые она
 * пересекает ({@link GridWalk}); колонка, задетая полосой, но не задетая ни одной линией, целиком лежала бы между
 * соседними линиями, а она шире промежутка. Чистый код без мира Minecraft.
 */
public final class Corridor {
    /** Промежуток между линиями обхода, блоков: меньше ширины колонки. */
    static final double LANE = 0.9;

    private Corridor() {}

    /** Высота рельефа (первый воздух над препятствиями) в колонке блоков. */
    @FunctionalInterface
    public interface Relief {
        double at(int x, int z);
    }

    /**
     * Наибольшая высота рельефа под полосой.
     *
     * @param track     путь по земле: x0, z0, x1, z1, … (одна точка — квадрат вокруг неё)
     * @param halfWidth полуширина полосы, блоков
     */
    public static double highest(double[] track, double halfWidth, Relief relief) {
        double[] max = {Double.NEGATIVE_INFINITY};
        GridWalk.Visitor visit = (x, z) -> {
            max[0] = Math.max(max[0], relief.at(x, z));
            return true;
        };
        int lanes = Math.max(1, (int) Math.ceil(2 * halfWidth / LANE));
        for (int i = 0; i == 0 || i + 3 < track.length; i += 2) {
            double ax = track[i], az = track[i + 1];
            double bx = i + 3 < track.length ? track[i + 2] : ax, bz = i + 3 < track.length ? track[i + 3] : az;
            double dx = bx - ax, dz = bz - az, len = Math.hypot(dx, dz);
            if (len < 1e-9) {
                // точка: отрезок поперёк самой полосы
                az -= halfWidth;
                bz += halfWidth;
                dx = 0;
                dz = 1;
                len = 1;
            }
            double nx = -dz / len, nz = dx / len;
            for (int k = 0; k <= lanes; k++) {
                double off = -halfWidth + 2 * halfWidth * k / lanes;
                GridWalk.walk(ax + nx * off, az + nz * off, bx + nx * off, bz + nz * off, 1, visit);
            }
        }
        return max[0];
    }
}
