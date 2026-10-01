package ua.zentix.airstrike.util;

/**
 * Обход клеток квадратной сетки, которые пересекает отрезок на плоскости XZ (Amanatides–Woo, 2D): ни одна
 * пересечённая клетка не пропускается, даже если отрезок срезает угол. Чистый код без мира Minecraft: клетка — колонка
 * блоков (размер 1) или колонка чанков (16).
 */
public final class GridWalk {
    private GridWalk() {}

    /** Клетка (x, z): продолжать ли обход. */
    @FunctionalInterface
    public interface Visitor {
        boolean visit(int x, int z);
    }

    /**
     * Обходит клетки размера {@code cell} от начала отрезка к концу.
     *
     * @return доля отрезка (0..1), на которой он входит в клетку, где {@code visitor} остановил обход; 1 — обойдены все
     */
    public static double walk(double fromX, double fromZ, double toX, double toZ, double cell, Visitor visitor) {
        double dx = toX - fromX, dz = toZ - fromZ;
        int cx = cell(fromX, cell), cz = cell(fromZ, cell);
        int endX = cell(toX, cell), endZ = cell(toZ, cell);
        int stepX = dx > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        // доля пути до следующей границы клетки по x и по z и прирост этой доли на одну клетку
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? cx + 1 : cx) * cell - fromX) / dx;
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? cz + 1 : cz) * cell - fromZ) / dz;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : cell / Math.abs(dx);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : cell / Math.abs(dz);
        double t = 0;
        while (true) {
            if (!visitor.visit(cx, cz)) return t;
            if (cx == endX && cz == endZ) return 1;
            if (tMaxX < tMaxZ) {
                t = tMaxX;
                tMaxX += tDeltaX;
                cx += stepX;
            } else {
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                cz += stepZ;
            }
            if (t >= 1) return 1;
        }
    }

    private static int cell(double v, double cell) {
        return (int) Math.floor(v / cell);
    }
}
