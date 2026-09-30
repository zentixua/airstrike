package ua.zentix.airstrike.gametest.scenario;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Сторож синхронной загрузки: код мода прочитал неготовый чанк, и сервер ждал его прямо в тике (CLAUDE.md, «Ничего
 * не читать в незагруженных чанках из тика»). Чанк, который ждёт {@code ServerChunkCache.getChunk}, становится
 * полным в задаче, выполненной внутри ожидания ({@code managedBlock}), — событие {@link ChunkEvent.Load} приходит
 * со стеком, где под {@code getChunk} стоит тот, кто ждал. Если это код мода (не проверки), — нарушение.
 * Загрузки в фоне приходят из очереди задач сервера, без {@code getChunk} в стеке.
 */
final class SyncLoadWatch {
    private static final String MOD = "ua.zentix.airstrike.";
    /** Код проверок: сам грузит чанки площадок сразу, это не нарушение. */
    private static final List<String> RIGS = List.of(MOD + "gametest.", MOD + "stress.", MOD + "scenario.", MOD + "rig.");
    private static final String CHUNK_CACHE = ServerChunkCache.class.getName();
    private static final String EVENT_LOOP = BlockableEventLoop.class.getName();

    /** Синхронная загрузка по вине мода: игровой тик, чанк и кадр мода, который ждал. */
    record Violation(long gameTime, String chunk, String frame) {}

    private static final List<Violation> VIOLATIONS = new ArrayList<>();
    private static boolean registered;

    private SyncLoadWatch() {}

    /** Слушать с первого сценария до конца прогона: события загрузки идут в потоке сервера. */
    static synchronized void ensureRegistered() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(SyncLoadWatch::onLoad);
    }

    private static void onLoad(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level) || level.getServer().getRunningThread() != Thread.currentThread()) return;
        Optional<String> frame = StackWalker.getInstance().walk(frames -> {
            boolean inWait = false;
            for (var it = frames.iterator(); it.hasNext(); ) {
                StackWalker.StackFrame f = it.next();
                String cls = f.getClassName();
                if (!inWait) {
                    // самое внутреннее ожидание чанка
                    inWait = cls.equals(CHUNK_CACHE) && f.getMethodName().equals("getChunk");
                    continue;
                }
                // до внешнего ожидания (задача, выполненная внутри него, — не тот, кто ждал этот чанк)
                if (cls.equals(EVENT_LOOP) && f.getMethodName().equals("managedBlock")) return Optional.<String>empty();
                if (cls.startsWith(MOD) && RIGS.stream().noneMatch(cls::startsWith)) return Optional.of(cls + "." + f.getMethodName() + ":" + f.getLineNumber());
            }
            return Optional.<String>empty();
        });
        frame.ifPresent(f -> {
            synchronized (VIOLATIONS) {
                VIOLATIONS.add(new Violation(level.getGameTime(), e.getChunk().getPos().toString(), f));
            }
        });
    }

    /** Нарушения с игрового тика {@code from} включительно. */
    static List<Violation> since(long from) {
        synchronized (VIOLATIONS) {
            return VIOLATIONS.stream().filter(v -> v.gameTime >= from).toList();
        }
    }
}
