package ua.zentix.airstrike.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;

import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;

/**
 * Обновление LOD через публичный API DH: {@code IDhApiTerrainDataRepo.overwriteChunkDataAsync} с чанком и его миром
 * (javadoc: «если чанк потом изменится иначе, данные заменятся тем, что в чанке», — то есть это та же дорога, что
 * у сохранения чанка, только сразу). Игрокам на сервере DH сам разошлёт изменения (обновления в реальном времени).
 * Грузится только при стоящем DH ({@link DistantHorizons#present}).
 * <p>
 * Вызов только ставит чанк в очередь DH (DH 3.3.3 {@code SharedApi.applyChunkUpdate} → {@code ChunkUpdateQueueManager}),
 * а у очереди предел ({@code 1000 × потоки DH × игроки}): переполненная молча вытесняет чанк дальше всех от игрока, то
 * есть как раз дальние LOD, ради которых всё и затевается; чанк, который уже стоит в очереди, второй раз не берётся.
 * Поэтому вызывающий держит в работе ограниченное число чанков, а DH подтверждает каждый сохранённый чанк событием
 * {@link DhApiChunkModifiedEvent} (после записи в хранилище LOD; дальше DH сам пересчитывает крупные LOD вдали).
 */
final class DistantHorizonsLod {
    private static boolean failureLogged;
    private static volatile boolean subscribed;
    /** Мир → чанки, отданные DH и ещё не подтверждённые (ключ — {@code ServerLevel}, мир не держит). */
    private static final Map<Object, Set<Long>> WATCHED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Queue<Long>> CONFIRMED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Мир → чанки, чей LOD погашен нами (сохранение подтверждено) и должен таким остаться, пока квартал тёмный. */
    private static final Map<Object, Set<Long>> DARK = Collections.synchronizedMap(new WeakHashMap<>());
    /** Мир → чанки из {@link #DARK}, чей LOD DH после этого переписал сам. */
    private static final Map<Object, Queue<Long>> REWRITTEN = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Пробы читают базу DH по одной в своём потоке: чтение API ждёт пул ввода-вывода DH без срока, и в общем пуле
     * фоновых задач Minecraft (генерация, свет, копии с диска) оно держало бы его рабочие потоки.
     */
    private static final ExecutorService SAMPLER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Airstrike DH LOD probe");
        t.setDaemon(true);
        return t;
    });

    private DistantHorizonsLod() {}

    static int apiMajor() {
        return DhApi.getApiMajorVersion();
    }

    static String modVersion() {
        return DhApi.getModVersion();
    }

    /** @return DH принял чанк в очередь (его сохранение придёт в {@link #confirmed}) */
    static boolean update(ServerLevel level, ChunkAccess chunk) {
        IDhApiWorldProxy world = DhApi.Delayed.worldProxy;
        IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;
        if (world == null || repo == null || !world.worldLoaded()) return false;
        IDhApiLevelWrapper wrapper = wrapper(world, level);
        if (wrapper == null) return false;
        if (!subscribed) subscribe();
        long pos = chunk.getPos().toLong();
        // до вызова: подтверждение приходит из потока DH
        Set<Long> watched = WATCHED.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet());
        watched.add(pos);
        String failure;
        try {
            DhApiResult<Void> r = repo.overwriteChunkDataAsync(wrapper, new Object[]{chunk, level});
            failure = r.success ? null : r.message;
        } catch (RuntimeException e) {
            failure = e.toString();
        }
        if (failure == null) return true;
        watched.remove(pos);
        if (!failureLogged) {
            failureLogged = true;
            Airstrike.LOG.warn("Distant Horizons не принял чанк {} для LOD: {}", chunk.getPos(), failure);
        }
        return false;
    }

    /** Чанки мира, которые DH сохранил с последнего вызова (поток сервера). */
    static void confirmed(ServerLevel level, LongConsumer consumer) {
        Queue<Long> q = CONFIRMED.get(level);
        if (q == null) return;
        Long c;
        while ((c = q.poll()) != null) consumer.accept(c);
    }

    /** LOD чанка погашен нами ({@code dark}) или больше не должен быть тёмным (поток сервера). */
    static void dark(ServerLevel level, long chunk, boolean dark) {
        if (dark) DARK.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet()).add(chunk);
        else {
            Set<Long> set = DARK.get(level);
            if (set != null) set.remove(chunk);
        }
    }

    /** Погашенные нами чанки, чей LOD DH с тех пор переписал сам (поток сервера). */
    static void rewritten(ServerLevel level, LongConsumer consumer) {
        Queue<Long> q = REWRITTEN.get(level);
        if (q == null) return;
        Long c;
        while ((c = q.poll()) != null) consumer.accept(c);
    }

    static boolean anyRewritten(ServerLevel level) {
        Queue<Long> q = REWRITTEN.get(level);
        return q != null && !q.isEmpty();
    }

    /** Подтверждения чанка больше не ждать (DH его пропустил: не изменился или уже стоял в очереди). */
    static void forget(ServerLevel level, long chunk) {
        Set<Long> watched = WATCHED.get(level);
        if (watched != null) watched.remove(chunk);
    }

    /**
     * Что лежит в хранилище LOD DH на месте блока (самая мелкая детализация): id блока и свет, или null — данных нет.
     * Читает базу DH в своём потоке, по одной.
     */
    static CompletableFuture<String> sample(ServerLevel level, BlockPos pos) {
        IDhApiWorldProxy world = DhApi.Delayed.worldProxy;
        IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;
        if (world == null || repo == null || !world.worldLoaded()) return CompletableFuture.completedFuture(null);
        IDhApiLevelWrapper wrapper = wrapper(world, level);
        if (wrapper == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> {
            try (IDhApiTerrainDataCache cache = repo.createSoftCache()) {
                DhApiResult<DhApiTerrainDataPoint> r = repo.getSingleDataPointAtBlockPos(wrapper, pos.getX(), pos.getY(), pos.getZ(), cache);
                if (!r.success || r.payload == null || r.payload.blockStateWrapper == null) return null;
                return r.payload.blockStateWrapper.getSerialString() + ", свет " + r.payload.blockLightLevel;
            } catch (Exception e) {
                return null;
            }
        }, SAMPLER);
    }

    private static synchronized void subscribe() {
        if (subscribed) return;
        subscribed = true;
        DhApiResult<Void> r = DhApiEventRegister.on(DhApiChunkModifiedEvent.class, new DhApiChunkModifiedEvent() {
            @Override
            public void onChunkModified(DhApiEventParam<EventParam> input) {
                Object level = input.value.levelWrapper.getWrappedMcObject();
                long pos = ChunkPos.asLong(input.value.chunkX, input.value.chunkZ);
                Set<Long> watched = WATCHED.get(level);
                if (watched != null && watched.remove(pos)) {
                    CONFIRMED.computeIfAbsent(level, k -> new ConcurrentLinkedQueue<>()).add(pos);
                    return;
                }
                // DH обновил чанк сам (генерация по файлам регионов, где лампы горят, сохранение): погашенный нами LOD
                // мог снова загореться
                Set<Long> dark = DARK.get(level);
                if (dark != null && dark.contains(pos)) REWRITTEN.computeIfAbsent(level, k -> new ConcurrentLinkedQueue<>()).add(pos);
            }
        });
        // без подписки каждая отдача ждёт полный срок: блэкаут в LOD идёт, но медленно
        if (!r.success) Airstrike.LOG.warn("Distant Horizons: подписка на сохранения LOD не вышла ({}), блэкаут в LOD — без подтверждений", r.message);
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
