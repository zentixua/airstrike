package ua.zentix.airstrike.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.ModList;
import ua.zentix.airstrike.Airstrike;

/**
 * Чанк, изменённый мимо {@code setBlock} (руины ядерки прямо в секциях), — в LOD Distant Horizons сразу, а не при
 * следующем сохранении чанка: {@code DhApi.Delayed.terrainRepo.overwriteChunkDataAsync} кладёт чанк в очередь
 * обновлений DH (та же, что у его собственных обновлений чанков; разбор — в потоках DH). Без DH или с другой мажорной
 * версией API ничего не делает; классы API трогает только вложенный {@link Api}, и только если DH стоит.
 */
public final class DhChunks {
    /** Мажорная версия API, против которой собран мод (7.2.0, DH 3.3.x). */
    private static final int API_MAJOR = 7;
    /** 0 — ещё не проверяли, 1 — DH с нашим API, −1 — нет (или API отказал: один раз в лог, дальше молча). */
    private static volatile int state;

    private DhChunks() {
    }

    /** Чанк мира сервера изменён целиком: DH перестроит его LOD. */
    public static void changed(ServerLevel level, LevelChunk chunk) {
        if (state == 0) state = ModList.get().isLoaded("distanthorizons") && supported() ? 1 : -1;
        if (state < 0) return;
        try {
            Api.overwrite(level, chunk);
        } catch (RuntimeException | LinkageError e) {
            state = -1;
            Airstrike.LOG.warn("Distant Horizons: обновление LOD изменённого чанка не прошло — дальше LOD руин обновится при сохранении чанка", e);
        }
    }

    private static boolean supported() {
        try {
            return Api.major() == API_MAJOR;
        } catch (LinkageError e) {
            return false;
        }
    }

    private static final class Api {
        private static boolean refused;

        static int major() {
            return DhApi.getApiMajorVersion();
        }

        static void overwrite(ServerLevel level, LevelChunk chunk) {
            IDhApiWorldProxy world = DhApi.Delayed.worldProxy;
            IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;
            if (world == null || repo == null || !world.worldLoaded()) return;
            for (IDhApiLevelWrapper w : world.getAllLoadedLevelWrappers()) {
                if (w.getWrappedMcObject() == level) {
                    DhApiResult<Void> r = repo.overwriteChunkDataAsync(w, new Object[] {chunk, level});
                    // отказ API (мир DH не тот, DH только читает) — один раз в лог: в LOD руины придут при сохранении чанка
                    if (!r.success && !refused) {
                        refused = true;
                        Airstrike.LOG.warn("Distant Horizons не принял изменённый чанк {}: {}", chunk.getPos(), r.message);
                    }
                    return;
                }
            }
        }
    }
}
