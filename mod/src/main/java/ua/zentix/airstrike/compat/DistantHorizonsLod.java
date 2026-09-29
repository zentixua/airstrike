package ua.zentix.airstrike.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;

/**
 * Обновление LOD через публичный API DH: {@code IDhApiTerrainDataRepo.overwriteChunkDataAsync} с чанком и его миром
 * (javadoc: «если чанк потом изменится иначе, данные заменятся тем, что в чанке», — то есть это та же дорога, что
 * у сохранения чанка, только сразу). Игрокам на сервере DH сам разошлёт изменения (обновления в реальном времени).
 * Грузится только при стоящем DH ({@link DistantHorizons#present}).
 */
final class DistantHorizonsLod {
    private static boolean failureLogged;

    private DistantHorizonsLod() {}

    static int apiMajor() {
        return DhApi.getApiMajorVersion();
    }

    static String modVersion() {
        return DhApi.getModVersion();
    }

    static void update(ServerLevel level, ChunkAccess chunk) {
        IDhApiWorldProxy world = DhApi.Delayed.worldProxy;
        IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;
        if (world == null || repo == null || !world.worldLoaded()) return;
        IDhApiLevelWrapper wrapper = wrapper(world, level);
        if (wrapper == null) return;
        String failure;
        try {
            DhApiResult<Void> r = repo.overwriteChunkDataAsync(wrapper, new Object[]{chunk, level});
            failure = r.success ? null : r.message;
        } catch (RuntimeException e) {
            failure = e.toString();
        }
        if (failure != null && !failureLogged) {
            failureLogged = true;
            Airstrike.LOG.warn("Distant Horizons не принял чанк {} для LOD: {}", chunk.getPos(), failure);
        }
    }

    /** Мир DH для мира сервера (в одиночной игре у DH свои обёртки клиента и сервера — нужна серверная). */
    @Nullable
    private static IDhApiLevelWrapper wrapper(IDhApiWorldProxy world, ServerLevel level) {
        for (IDhApiLevelWrapper w : world.getAllLoadedLevelWrappers()) {
            if (w.getWrappedMcObject() == level) return w;
        }
        return null;
    }
}
