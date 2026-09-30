package ua.zentix.airstrike.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
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

    /** Готовы чанк и все восемь соседей: цепочка обновлений от блока в нём (до 16 блоков) не выходит за готовые. */
    public static boolean neighbourhoodReady(Level level, int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) if (!ready(level, chunkX + dx, chunkZ + dz)) return false;
        }
        return true;
    }

    /** Готовы все чанки, которых касается квадрат со стороной {@code 2r} вокруг точки. */
    public static boolean readyAround(Level level, Vec3 centre, double r) {
        for (int cx = Mth.floor(centre.x - r) >> 4; cx <= Mth.floor(centre.x + r) >> 4; cx++) {
            for (int cz = Mth.floor(centre.z - r) >> 4; cz <= Mth.floor(centre.z + r) >> 4; cz++) {
                if (!ready(level, cx, cz)) return false;
            }
        }
        return true;
    }

    /**
     * Докуда отрезок {@code from → to} идёт по готовым чанкам: точка чуть до входа в первый неготовый чанк (или
     * {@code to}, если весь путь готов). Луч {@code Level.clip} читает каждый блок на пути, а на сервере чтение
     * незагруженного чанка грузит и генерирует его прямо в тике — длинный луч (прицел до 1024 блоков, нос снаряда)
     * вставал бы на секунды. На клиенте незагруженный чанк — пустой, обрезать нечего.
     */
    public static Vec3 readyUntil(Level level, Vec3 from, Vec3 to) {
        if (!(level instanceof ServerLevel)) return to;
        double t = readyFraction(from.x, from.z, to.x, to.z, (x, z) -> ready(level, x, z));
        return t >= 1 ? to : from.lerp(to, t);
    }

    /** Весь отрезок идёт по готовым чанкам (см. {@link #readyUntil}). */
    public static boolean readyAlong(Level level, Vec3 from, Vec3 to) {
        return !(level instanceof ServerLevel) || readyFraction(from.x, from.z, to.x, to.z, (x, z) -> ready(level, x, z)) >= 1;
    }

    /** Готов ли чанк (x, z). */
    @FunctionalInterface
    public interface ChunkReady {
        boolean test(int chunkX, int chunkZ);
    }

    /**
     * Доля отрезка (0..1) до первой неготовой колонки чанков на его пути. Колонки обходятся по сетке
     * (Amanatides–Woo, 2D): ни одна пересечённая колонка не пропускается, даже если отрезок срезает угол.
     * Конец берётся на {@link #EDGE} блока раньше границы, чтобы последний проверяемый блок остался в готовом чанке.
     */
    public static double readyFraction(double fromX, double fromZ, double toX, double toZ, ChunkReady ready) {
        double dx = toX - fromX, dz = toZ - fromZ;
        int cx = SectionPos.posToSectionCoord(fromX), cz = SectionPos.posToSectionCoord(fromZ);
        int endX = SectionPos.posToSectionCoord(toX), endZ = SectionPos.posToSectionCoord(toZ);
        int stepX = dx > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        // доля пути до следующей границы чанка по x и по z и прирост этой доли на один чанк
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? cx + 1 : cx) * 16.0 - fromX) / dx;
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? cz + 1 : cz) * 16.0 - fromZ) / dz;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dx);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dz);
        double edge = EDGE / Math.max(1.0e-9, Math.sqrt(dx * dx + dz * dz));
        double t = 0;
        while (true) {
            if (!ready.test(cx, cz)) return Math.max(0, t - edge);
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

    private static final double EDGE = 0.01;

    /** Первый воздух над картой высот (как {@link Level#getHeight}). */
    public static int height(Level level, Heightmap.Types type, int x, int z) {
        if (level instanceof ServerLevel server) {
            LevelChunk chunk = server.getChunkSource().getChunkNow(x >> 4, z >> 4);
            return chunk == null ? level.getMinBuildHeight() : chunk.getHeight(type, x & 15, z & 15) + 1;
        }
        return level.getHeight(type, x, z);
    }

    /**
     * Первый воздух над поверхностью, не загружая чанк: чанк готов — по карте высот {@code type}, иначе — рельеф,
     * каким его строит генератор мира ({@code ChunkGenerator.getBaseHeight}: шум, без деревьев и построек), не ниже
     * его уровня моря (над водой поверхность — сама вода). У мира, построенного не этим генератором, оценка бывает
     * далека от поверхности: мир 1.17, поднятый до 1.21, — новый рельеф на месте старого, генератор давал и дно мира
     * (Newisle 30.09.2026: удар «по 83 -64 -370»); уровень моря держит её хотя бы у суши. Точная высота — когда чанк готов.
     * Не высота цели: цель бывает в воздухе (игрок в полёте, аппарат), а поверхность под ней — нет.
     */
    public static int surface(ServerLevel level, Heightmap.Types type, int x, int z) {
        if (ready(level, x >> 4, z >> 4)) return height(level, type, x, z);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        return Math.max(generator.getSeaLevel(),
                generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState()));
    }
}
