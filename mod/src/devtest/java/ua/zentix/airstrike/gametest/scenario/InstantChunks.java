package ua.zentix.airstrike.gametest.scenario;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
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
 */
final class InstantChunks {
    private static final int FULL = ChunkLevel.byStatus(FullChunkStatus.FULL);

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
        for (ServerLevel level : e.getServer().getAllLevels()) settle(level);
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
