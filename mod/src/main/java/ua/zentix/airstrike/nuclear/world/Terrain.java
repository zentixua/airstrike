package ua.zentix.airstrike.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

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

    /** Первый воздух над картой высот (как {@link Level#getHeight}). */
    public static int height(Level level, Heightmap.Types type, int x, int z) {
        if (level instanceof ServerLevel server) {
            LevelChunk chunk = server.getChunkSource().getChunkNow(x >> 4, z >> 4);
            return chunk == null ? level.getMinBuildHeight() : chunk.getHeight(type, x & 15, z & 15) + 1;
        }
        return level.getHeight(type, x, z);
    }
}
