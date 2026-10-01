package ua.zentix.airstrike.gametest.scenario;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.util.Terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Мгновенная генерация на время сценариев: в конце каждого тика сервера всё, что тикеты требуют загрузить полностью
 * (уровень ≤ 33), догружается сразу ({@code level.getChunk}).
 * <p>
 * Зачем. Районы мода ({@code AreaLoader}: свой чанк и чанк впереди снаряда, район цели) начинают тикать, когда готов
 * квадрат вокруг, а фоновая генерация готовит его за разное число тиков — от прогона к прогону и от машины к машине.
 * Снаряд у края тикающих чанков уходит из мира или остаётся в нём по тому, успел ли сосед, и траектория расходится
 * с эталоном. С мгновенной генерацией готовность — функция тикетов, а тикеты — функция полёта: полёт повторяется точно.
 * <p>
 * Место в тике одно ({@link ServerTickEvent.Post} — после уровней и тестов GameTest): порядок тестов в партии на него
 * не влияет. Чтение неготовых чанков мода при этом видно по-прежнему ({@link SyncLoadWatch}): чанк, которого тикеты
 * не просили, сам не появится.
 * <p>
 * Слепые пятна: загрузка здесь идеальная. {@link SyncLoadWatch} ловит только чтение чанков, которых не просил ни один
 * тикет; чанк, который тикет просил, но фоновая генерация ещё не дала, к чтению уже готов — синхронное ожидание
 * такого чанка здесь не видно. Ожидание района мода (снаряд ждёт загрузки цели, {@code AREA_WAIT_LIMIT}), уход из мира
 * на краю тикающих чанков из-за медленной генерации и возврат в мир тоже не проходят. Это проверяет прогон
 * с настоящей загрузкой ({@code -PscenarioRealChunks}, {@link ScenarioMode}): свойства те же, без эталона.
 * <p>
 * Запись чанков на диск. Выгруженный чанк уходит в фоновую очередь {@link IOWorker} готовым NBT, а в ней чтения
 * (генерация сначала ищет чанк на диске) идут вперёд записей: сервер GameTest тикает без пауз, и за партией сценариев
 * очередь не успевала — замер с Lithium: до 27,5 тыс. ждущих записи чанков, 3 млн {@code CompoundTag} и 2,5 ГБ живой
 * кучи при 11 тыс. чанков в памяти, CI (куча 4 ГБ) падал {@code OutOfMemoryError}. Поэтому раз в {@link #WRITE_PERIOD}
 * тиков сервер ждёт, пока очередь допишет: игровой сервер это успевает между тиками.
 */
final class InstantChunks {
    private static final int FULL = ChunkLevel.byStatus(FullChunkStatus.FULL);
    /** Раз во сколько тиков дождаться записи выгруженных чанков. */
    private static final int WRITE_PERIOD = 100;

    private static int users;
    private static boolean registered;

    private InstantChunks() {}

    /** Сценарий начался: генерация мгновенная, пока идёт хоть один. */
    static synchronized void acquire() {
        if (!registered) {
            registered = true;
            NeoForge.EVENT_BUS.addListener(InstantChunks::onServerTick);
        }
        users++;
    }

    static synchronized void release() {
        users--;
    }

    private static void onServerTick(ServerTickEvent.Post e) {
        if (users <= 0) return;
        boolean write = e.getServer().getTickCount() % WRITE_PERIOD == 0;
        for (ServerLevel level : e.getServer().getAllLevels()) {
            settle(level);
            if (write) awaitWrites(level);
        }
    }

    /** Дождаться, пока фоновая очередь допишет выгруженные чанки (без сброса файлов на диск). */
    private static void awaitWrites(ServerLevel level) {
        long t0 = System.nanoTime();
        ((IOWorker) level.getChunkSource().chunkMap.chunkScanner()).synchronize(false).join();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (ms >= 1000) Airstrike.LOG.info("SCENARIO запись чанков ({}) дождалась за {} мс", level.dimension().location(), ms);
    }

    /** Догрузить всё, что тикеты требуют полностью. */
    static void settle(ServerLevel level) {
        ServerChunkCache cache = level.getChunkSource();
        // тикеты, взятые в этом тике, — в уровни чанков сейчас (открыт AT)
        cache.runDistanceManagerUpdates();
        List<ChunkPos> pending = new ArrayList<>();
        for (ChunkHolder holder : cache.chunkMap.getChunks()) {
            if (holder.getTicketLevel() <= FULL && !Terrain.ready(level, holder.getPos().x, holder.getPos().z)) pending.add(holder.getPos());
        }
        for (ChunkPos c : pending) level.getChunk(c.x, c.z);
    }
}
