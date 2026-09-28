package ua.zentix.airstrike.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Высота рельефа без ожидания: на сервере {@code Level.getHeight} для чанка, который загружен, но ещё не готов
 * к выдаче, ждёт его в {@code managedBlock} — и выполняет в это время чужие задачи загрузки (до сотен мс за вызов).
 * Здесь берётся только уже готовый чанк ({@code getChunkNow}); нет чанка — как у ванили, нижняя граница мира.
 */
public final class Terrain {
    private Terrain() {}

    /**
     * Чанк уже загружен и готов: на сервере — {@code getChunkNow}, а не {@code hasChunk}/{@code isLoaded}
     * (те верны и для чанка, который ещё грузится, и чтение из него ждёт загрузку).
     */
    public static boolean ready(Level level, int chunkX, int chunkZ) {
        if (level instanceof ServerLevel server) return server.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
        return level.hasChunk(chunkX, chunkZ);
    }

    public static boolean ready(Level level, BlockPos pos) {
        return ready(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Докуда отрезок {@code from → to} идёт по готовым чанкам: точка чуть до входа в первый неготовый чанк (или
     * {@code to}, если весь путь готов). Луч {@code Level.clip} читает каждый блок на пути, а на сервере чтение
     * незагруженного чанка грузит и генерирует его прямо в тике — длинный луч прицела (400 блоков) вставал бы
     * на секунды. Чанки перебираются по сетке вдоль луча (как в {@code BlockGetter.traverse}, только шагом в чанк),
     * так что угол чанка между соседними точками не пропускается. На клиенте — {@code to}: он ничего не грузит.
     */
    public static Vec3 readyUntil(Level level, Vec3 from, Vec3 to) {
        double t = readyFraction(level, from, to);
        if (t >= 1) return to;
        // чуть не доходя до границы: конечный блок луча должен остаться в готовом чанке
        double back = 1.0e-3 / Math.max(1.0e-9, from.distanceTo(to));
        return from.lerp(to, Math.max(0, t - back));
    }

    /** Весь отрезок идёт по готовым чанкам (см. {@link #readyUntil}). */
    public static boolean readyAlong(Level level, Vec3 from, Vec3 to) {
        return readyFraction(level, from, to) >= 1;
    }

    /** Доля отрезка до входа в первый неготовый чанк; 1 — весь готов. */
    private static double readyFraction(Level level, Vec3 from, Vec3 to) {
        if (!(level instanceof ServerLevel)) return 1;
        double dx = to.x - from.x, dz = to.z - from.z;
        int cx = Mth.floor(from.x) >> 4, cz = Mth.floor(from.z) >> 4;
        int endX = Mth.floor(to.x) >> 4, endZ = Mth.floor(to.z) >> 4;
        int stepX = (int) Math.signum(dx), stepZ = (int) Math.signum(dz);
        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : 16 / Math.abs(dx);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : 16 / Math.abs(dz);
        double tMaxX = stepX == 0 ? Double.MAX_VALUE : ((stepX > 0 ? (cx + 1) * 16 : cx * 16) - from.x) / dx;
        double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : ((stepZ > 0 ? (cz + 1) * 16 : cz * 16) - from.z) / dz;
        double t = 0;
        while (true) {
            if (!ready(level, cx, cz)) return t;
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
            if (t > 1) return 1;
        }
    }

    /** Первый воздух над картой высот (как {@link Level#getHeight}). */
    public static int height(Level level, Heightmap.Types type, int x, int z) {
        if (level instanceof ServerLevel server) {
            LevelChunk chunk = server.getChunkSource().getChunkNow(x >> 4, z >> 4);
            return chunk == null ? level.getMinBuildHeight() : chunk.getHeight(type, x & 15, z & 15) + 1;
        }
        return level.getHeight(type, x, z);
    }
}
