package ua.zentix.airstrike.grid;

/**
 * Кварталы сети: неровные районы около 80 блоков, из целых чанков. Центры — по сетке из клеток по {@link #CELL} чанков,
 * каждый сдвинут внутри своей клетки на случайную (по координатам клетки, одну и ту же всегда) величину; чанк
 * принадлежит кварталу с ближайшим центром (ячейки Вороного). Так границы кварталов вдали не складываются в сетку
 * квадратов. Всё выводится из координат: ничего не хранится, и сервер, и проверки получают одно и то же.
 */
public final class Districts {
    /** Шаг сетки центров, чанков (5 чанков = 80 блоков). */
    public static final int CELL = 5;
    /** Сдвиг центра не дальше этой доли клетки от её середины: кварталы разного размера, но без вырожденных. */
    private static final double JITTER = 0.8;

    private Districts() {}

    /** Квартал чанка: ключ клетки его центра (как {@code ChunkPos.asLong} по клеткам). */
    public static long of(int chunkX, int chunkZ) {
        double px = chunkX * 16 + 8, pz = chunkZ * 16 + 8;
        int cx = Math.floorDiv(chunkX, CELL), cz = Math.floorDiv(chunkZ, CELL);
        long best = 0;
        double bestD = Double.MAX_VALUE;
        // свой центр бывает дальше 1,27 клетки (угол клетки, сдвинутый центр), а центр через клетку — ближе: смотрим 5×5
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = cx + dx, z = cz + dz;
                double sx = seedX(x, z) - px, sz = seedZ(x, z) - pz;
                double d = sx * sx + sz * sz;
                if (d < bestD) {
                    bestD = d;
                    best = key(x, z);
                }
            }
        }
        return best;
    }

    public static long key(int cellX, int cellZ) {
        return (long) cellX & 0xFFFFFFFFL | ((long) cellZ & 0xFFFFFFFFL) << 32;
    }

    public static int cellX(long district) {
        return (int) district;
    }

    public static int cellZ(long district) {
        return (int) (district >>> 32);
    }

    /** Центр квартала, блоки. */
    public static double seedX(long district) {
        return seedX(cellX(district), cellZ(district));
    }

    public static double seedZ(long district) {
        return seedZ(cellX(district), cellZ(district));
    }

    private static double seedX(int cellX, int cellZ) {
        return (cellX + 0.5 + JITTER * (unit(cellX, cellZ, 1) - 0.5)) * CELL * 16;
    }

    private static double seedZ(int cellX, int cellZ) {
        return (cellZ + 0.5 + JITTER * (unit(cellX, cellZ, 2) - 0.5)) * CELL * 16;
    }

    /** Случайное число квартала в [0, 1): одно и то же для той же клетки и того же {@code salt}. */
    public static double unit(long district, int salt) {
        return unit(cellX(district), cellZ(district), salt);
    }

    private static double unit(int x, int z, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }
}
