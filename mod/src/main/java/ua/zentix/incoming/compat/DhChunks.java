package ua.zentix.incoming.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.coreapi.DependencyInjection.WorldGeneratorInjector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import ua.zentix.incoming.Incoming;

/**
 * Вызов API Distant Horizons: чанк — в LOD сразу, а не при следующем сохранении чанка (DH сам видит изменения блоков
 * только по сохранению чанка: у свободного сервера через ~10 с, у занятого — автосохранением раз в 5 минут).
 * {@code DhApi.Delayed.terrainRepo.overwriteChunkDataAsync} кладёт чанк в очередь обновлений DH (та же, что у его
 * собственных обновлений; разбор — в потоках DH не раньше чем через 250 мс, по живой ссылке на чанк, без копии), DH
 * пишет LOD в свою базу и рассылает его игрокам с DH (одиночная игра, хост с DH, сервер с DH: «real-time updates»).
 * Принимает любой {@link ChunkAccess} с миром — и загруженный чанк, и собранный модом чанк не из мира
 * ({@link DhUpdates#offer}). Темп и повторы — {@link DhUpdates}: позицию, которая уже в очереди DH, он молча
 * пропускает. Без DH или с другой мажорной версией API ничего не делает; классы API трогает только вложенный
 * {@link Api}, и только если DH стоит.
 */
public final class DhChunks {
    /** Мажорная версия API, против которой собран мод (7.2.0, DH 3.3.x). */
    private static final int API_MAJOR = 7;
    /** 0 — ещё не проверяли, 1 — DH с нашим API, −1 — нет (или его классы не те, что знает мод). */
    private static volatile int state;
    /** Ошибка или отказ API уже в логе (с запуска сервера): дальше — молча. Классы DH здесь не нужны. */
    private static boolean logged, refused;
    /** Подписка на записи LOD ({@link #listen}) есть: события API DH живут, пока жива игра, — одна на запуск игры. */
    private static volatile boolean listening;

    private DhChunks() {
    }

    /** Запуск сервера (в одиночной игре — каждый мир): проверить DH заново, ошибки прошлого мира не в счёт. */
    public static void onServerStarting(ServerStartingEvent e) {
        state = 0;
        logged = false;
        refused = false;
    }

    /**
     * Сервер остановлен (в одиночной игре — выход из мира): забыть генераторы DH по мирам. DH 3.3.3 заводит генератор
     * каждому миру сервера в статической карте {@code WorldGeneratorInjector} (ключ — обёртка мира с самим
     * {@code ServerLevel}) и не убирает его никогда, так что без очистки все миры, открытые за запуск игры, остаются
     * в памяти целиком с чанками (сценарий leak: в меню 1,3 ГБ до мира, 2,4 ГБ после двух; держатель — эта карта,
     * без e4all так же). После остановки миров в ней нет живых: DH заводит генератор заново при загрузке
     * мира, а чужой генератор регистрируется на обёртку загруженного мира — у следующего мира обёртки новые. Метод
     * очистки — публичный и в jar API DH.
     * Прошлый мир DH держит ещё в {@code ThreadWorldGenParams} (статическое поле и поля потоков генерации) до генерации
     * в следующем мире — мод туда не лезет.
     */
    public static void onServerStopped(ServerStoppedEvent e) {
        DhUpdates.forgetLevels();
        if (!available()) return;
        try {
            Api.forgetGenerators();
        } catch (LinkageError ex) {
            state = -1;
            Incoming.LOG.warn("Distant Horizons: could not drop generators of previous worlds — they stay in memory until the game exits", ex);
        }
    }

    /** Distant Horizons стоит, и его API — то, под которое собран мод. */
    public static boolean available() {
        if (state == 0) state = ModList.get().isLoaded("distanthorizons") && supported() ? 1 : -1;
        return state > 0;
    }

    /** Чанк мира {@code level} (загруженный или собранный модом): DH перестроит по нему LOD. Поток сервера. */
    static void overwrite(ServerLevel level, ChunkAccess chunk) {
        if (!available()) return;
        try {
            Api.overwrite(level, chunk);
        } catch (LinkageError e) {
            // API не то, под которое собран мод: до конца игры не трогаем
            state = -1;
            Incoming.LOG.warn("Distant Horizons: API differs from the one the mod was built for — LODs of changed chunks will update when they are saved", e);
        } catch (RuntimeException e) {
            // разовый отказ (мир DH ещё не готов и т. п.): этот чанк — при сохранении, следующие — снова через API
            if (!logged) {
                logged = true;
                Incoming.LOG.warn("Distant Horizons: LOD update of changed chunk {} failed — it will update when saved", chunk.getPos(), e);
            }
        }
    }

    /**
     * Потоки разбора обновлений DH ({@code numberOfThreads} в его настройках): по ним DH считает предел своей очереди
     * обновлений ({@link DhQueue}). Без DH — 1.
     */
    static int threads() {
        if (!available()) return 1;
        try {
            return Math.max(1, Api.threads());
        } catch (LinkageError | RuntimeException e) {
            return 1;
        }
    }

    /**
     * Подписка на записи LOD чанков в базу DH ({@code DhApiChunkModifiedEvent} — после записи участка, по чанку) —
     * подтверждения держанных копий ({@link DhUpdates#saved}). Один раз за запуск игры. Поток сервера.
     */
    static void listen() {
        if (listening || !available()) return;
        listening = true;
        try {
            Api.listen();
        } catch (LinkageError | RuntimeException e) {
            Incoming.LOG.warn("Distant Horizons: no notice of saved LODs — LODs sent ahead of the blast wave are paced by a timeout instead", e);
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
        static int major() {
            return DhApi.getApiMajorVersion();
        }

        static int threads() {
            IDhApiConfig configs = DhApi.Delayed.configs;
            return configs == null ? 1 : configs.multiThreading().threadCount().getValue();
        }

        static void listen() {
            DhApiEventRegister.on(DhApiChunkModifiedEvent.class, new DhApiChunkModifiedEvent() {
                @Override
                public void onChunkModified(DhApiEventParam<EventParam> input) {
                    DhUpdates.saved(input.value.levelWrapper.getWrappedMcObject(), input.value.chunkX, input.value.chunkZ);
                }
            });
        }

        static void forgetGenerators() {
            WorldGeneratorInjector.INSTANCE.clear();
        }

        static void overwrite(ServerLevel level, ChunkAccess chunk) {
            IDhApiWorldProxy world = DhApi.Delayed.worldProxy;
            IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;
            if (world == null || repo == null || !world.worldLoaded()) return;
            for (IDhApiLevelWrapper w : world.getAllLoadedLevelWrappers()) {
                if (w.getWrappedMcObject() == level) {
                    DhApiResult<Void> r = repo.overwriteChunkDataAsync(w, new Object[] {chunk, level});
                    // отказ API (мир DH не тот, DH только читает) — один раз в лог: в LOD руины придут при сохранении чанка
                    if (!r.success && !refused) {
                        refused = true;
                        Incoming.LOG.warn("Distant Horizons rejected changed chunk {}: {}", chunk.getPos(), r.message);
                    }
                    return;
                }
            }
        }
    }
}
