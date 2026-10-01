package ua.zentix.airstrike.util;

import net.minecraft.server.level.ServerChunkCache;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Один круг выгрузки мира при остановке сервера ({@code MinecraftServer.stopServer}, миксин
 * {@code mixin/server/StopServerChunksMixin}): прокачка карты чанков с пределом времени, затем задачи потока сервера
 * у чанков этого мира.
 * <p>
 * Зависание ванильное (1.21.1): круг висит и на обычном тикете игрока, мод лишь делает случай частым. Как это выходит.
 * Ваниль крутит «пока у карты чанков есть работа»: в круге ставит срок следующего тика через 1 мс, зовёт
 * {@code ServerChunkCache.tick(() -> true, false)} и ждёт тика ({@code waitUntilNextTick}), а задачи чанков
 * ({@code ServerChunkCache.pollTask}: разбор чанка с диска, шаг FULL генерации) {@code pollTaskInternal} выполняет,
 * только пока срок не прошёл. Генерация, начатая до выхода, держит своих соседей ({@code acquireGeneration}); держатель,
 * которого уже поставили на выгрузку, а генерация вернула в работу, в очереди выгрузки ({@code unloadQueue}) кладёт
 * себя обратно сразу же, пока она его держит ({@code scheduleUnload}: {@code isReadyForSaving} ложно). С
 * {@code () -> true} ваниль в этой очереди и остаётся; с пределом круг длится дольше 1 мс, и задачи чанков не
 * выполняются ни разу — генерация не кончается, держатель не отпускается: круги без конца (ноутбук 30.09.2026, после
 * ядерки: «Server thread» в {@code ChunkMap.processUnloads}, потоки генерации простаивают). Поэтому после прокачки
 * круг сам выполняет задачи чанков этого мира, тоже с пределом. Сколько кругов — решает ванильная проверка работы.
 */
public final class StopPump {
    /** Предел прокачки круга и отдельно — задач чанков круга. */
    public static final long BUDGET_NANOS = 20_000_000L;

    private StopPump() {}

    /**
     * Круг выгрузки мира.
     *
     * @param hasTime ванильное «есть время» круга
     * @param tick    сама прокачка ({@code ServerChunkCache.tick}) с данным «есть время»
     * @return выполнено задач чанков
     */
    public static int round(ServerChunkCache cache, BooleanSupplier hasTime, Consumer<BooleanSupplier> tick) {
        long pumpEnd = System.nanoTime() + BUDGET_NANOS;
        tick.accept(() -> System.nanoTime() < pumpEnd && hasTime.getAsBoolean());
        long tasksEnd = System.nanoTime() + BUDGET_NANOS;
        int n = 0;
        while (System.nanoTime() < tasksEnd && cache.pollTask()) n++;
        return n;
    }
}
