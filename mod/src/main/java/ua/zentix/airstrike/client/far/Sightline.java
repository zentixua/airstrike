package ua.zentix.airstrike.client.far;

/**
 * Один луч от источника к глазу по карте высот: закрывает ли рельеф (холм, дома) источник и насколько — для картинки
 * (видно ли место взрыва или только поднявшийся над холмом дым) и для звука (дифракция через кромку, ISO 9613-2).
 * Не больше {@link #MAX_SAMPLES} точек с шагом не мельче {@link #STEP} блоков; высоты отдаёт вызывающий
 * ({@link Heights}): чанки клиента, дальше — плитки рельефа карты; неизвестная колонка преградой не считается.
 */
public final class Sightline {
    /** Точек на луч, не больше. */
    public static final int MAX_SAMPLES = 64;
    /** Шаг по горизонтали, блоков, не мельче. */
    public static final double STEP = 16;
    /** Карта высот — с точностью до блока: ниже этого над лучом — не преграда. */
    private static final double TOLERANCE = 0.5;

    private Sightline() {}

    /** Верх земли (с постройками и кронами) в колонке; {@link Double#NaN} — неизвестно. */
    @FunctionalInterface
    public interface Heights {
        double at(int x, int z);
    }

    /**
     * @param pathDifference разность хода через самую значимую кромку (a + b − d), блоков; 0 — прямая видимость
     * @param hidden         на сколько блоков выше источника начинается видимое с глаза (0 — источник виден)
     */
    public record Result(double pathDifference, double hidden) {
        public static final Result OPEN = new Result(0, 0);
    }

    public static Result trace(Heights heights, double sx, double sy, double sz, double ex, double ey, double ez) {
        double dx = ex - sx, dy = ey - sy, dz = ez - sz;
        double flat = Math.sqrt(dx * dx + dz * dz);
        int n = (int) Math.min(MAX_SAMPLES, Math.ceil(flat / STEP));
        if (n < 2) return Result.OPEN;
        double direct = Math.sqrt(flat * flat + dy * dy);
        double z = 0, hidden = 0;
        for (int i = 1; i < n; i++) {
            double f = (double) i / n;
            double top = heights.at((int) Math.floor(sx + dx * f), (int) Math.floor(sz + dz * f));
            if (Double.isNaN(top)) continue;
            if (top > sy + dy * f + TOLERANCE) {
                double a = Math.sqrt(f * flat * f * flat + (top - sy) * (top - sy));
                double b = Math.sqrt((1 - f) * flat * (1 - f) * flat + (ey - top) * (ey - top));
                z = Math.max(z, a + b - direct);
            }
            // прямая из глаза через вершину: над источником она на (top − ey)/u + ey − sy, u — доля пути от глаза
            hidden = Math.max(hidden, (top - ey) / (1 - f) + ey - sy);
        }
        return new Result(z, hidden);
    }
}
