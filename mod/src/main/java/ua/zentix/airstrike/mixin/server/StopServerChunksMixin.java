package ua.zentix.airstrike.mixin.server;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.BooleanSupplier;

/**
 * Выход из мира не зависает в выгрузке чанков. В {@code MinecraftServer.stopServer} ваниль крутит цикл «пока у карты
 * чанков есть работа» и в каждом круге зовёт {@code ServerChunkCache.tick(() -> true, false)}: время без предела. Внутри
 * {@code ChunkMap.processUnloads} очередь выгрузки ({@code unloadQueue}) тогда разбирается, пока не опустеет, а задача
 * выгрузки держателя, которого снова взяла генерация (держатель вернули из выгрузки в работу — его соседу нужен был
 * чанк), кладёт себя обратно в ту же очередь сразу же ({@code scheduleUnload}: {@code isReadyForSaving} ложно, пока
 * генерация его держит). Генерация же доходит до конца только задачами потока сервера, а он из этой очереди не выходит:
 * цикл без конца (ноутбук 30.09.2026: «Server thread» 632 с процессора в {@code processUnloads}, после ядерки, где
 * зона за волной грузила квадраты с соседями).
 * <p>
 * Здесь круг получает предел времени {@link #BUDGET_NANOS}, как обычный тик: очередь выгрузки после него разбирается
 * только сверх 2000 задач (сама ваниль), и поток сервера доходит до задач генерации ({@code waitUntilNextTick}), а
 * держатель отпускается. Сколько кругов — решает та же ванильная проверка работы. Встал ли миксин — GameTest
 * {@code stopPumpBounded}.
 */
@Mixin(MinecraftServer.class)
public abstract class StopServerChunksMixin {
    /** Предел одного круга выгрузки при остановке. */
    @Unique
    private static final long BUDGET_NANOS = 20_000_000L;

    @ModifyArg(method = "stopServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"), index = 0)
    private BooleanSupplier airstrike$boundStopPump(BooleanSupplier hasTime) {
        long end = System.nanoTime() + BUDGET_NANOS;
        return () -> System.nanoTime() < end && hasTime.getAsBoolean();
    }
}
