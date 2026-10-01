package ua.zentix.airstrike.guidance;

import ua.zentix.airstrike.util.GridWalk;

/**
 * Полоса над путём по земле: какой высоты рельеф надо перелететь, во всех колонках блоков, которые задевает полоса
 * заданной полуширины вдоль ломаной пути. Колонка не пропускается, даже тонкая (столб, мачта, фонарь): полоса
 * проходится параллельными линиями чаще, чем через блок ({@link #LANE}), и каждая линия — по всем колонкам, которые
 * она пересекает ({@link GridWalk}); колонка, задетая полосой, но не задетая ни одной линией, целиком лежала бы между
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
     * Набор высоты снаряда до колонки впереди: первые {@code reaction} блоков пути — без набора (снаряд только выходит
     * на него), дальше — {@code gradient} блоков высоты на блок пути.
     */
    public record Climb(double reaction, double gradient) {
        /** Без набора: колонка учитывается целиком, как бы далеко ни была. */
        public static final Climb NONE = new Climb(0, 0);

        /** Сколько высоты снаряд успеет набрать за {@code along} блоков пути. */
        public double gain(double along) {
            return gradient * Math.max(0, along - reaction);
        }
    }

    /**
     * Высота, на которой снаряд должен быть сейчас, чтобы, набирая высоту по {@code climb}, пройти над рельефом всей
     * полосы: наибольшее по колонкам «рельеф − набор до колонки». С {@link Climb#NONE} — просто наибольшая высота
     * рельефа под полосой. Путь до колонки — проекция её середины на ломаную (не меньше 0).
     *
     * @param track     путь по земле: x0, z0, x1, z1, … (одна точка — квадрат вокруг неё)
     * @param halfWidth полуширина полосы, блоков
     */
    public static double highest(double[] track, double halfWidth, Climb climb, Relief relief) {
        double[] max = {Double.NEGATIVE_INFINITY};
        int lanes = Math.max(1, (int) Math.ceil(2 * halfWidth / LANE));
        double travelled = 0;
        for (int i = 0; i == 0 || i + 3 < track.length; i += 2) {
            double ax = track[i], az = track[i + 1];
            double bx = i + 3 < track.length ? track[i + 2] : ax, bz = i + 3 < track.length ? track[i + 3] : az;
            double dx = bx - ax, dz = bz - az, len = Math.hypot(dx, dz);
            boolean point = len < 1e-9;
            if (point) {
                // точка: отрезок поперёк самой полосы
                az -= halfWidth;
                bz += halfWidth;
                dx = 0;
                dz = 1;
                len = 1;
            }
            double ux = dx / len, uz = dz / len, nx = -uz, nz = ux;
            double sx = ax, sz = az, base = travelled;
            GridWalk.Visitor visit = (x, z) -> {
                double along = point ? 0 : Math.max(0, base + (x + 0.5 - sx) * ux + (z + 0.5 - sz) * uz);
                max[0] = Math.max(max[0], relief.at(x, z) - climb.gain(along));
                return true;
            };
            for (int k = 0; k <= lanes; k++) {
                double off = -halfWidth + 2 * halfWidth * k / lanes;
                GridWalk.walk(ax + nx * off, az + nz * off, bx + nx * off, bz + nz * off, 1, visit);
            }
            if (!point) travelled += len;
        }
        return max[0];
    }
}
