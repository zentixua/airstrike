package ua.zentix.airstrike.mixin.net;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.zentix.airstrike.nuclear.world.ChunkSendGate;

/**
 * Чанки, чьи руины уже должны стоять, а не стоят, не уходят игроку ({@link ChunkSendGate}): на время отбора чанков
 * для отправки они убраны из очереди игрока и возвращаются в неё после — уйдут, когда руины встанут.
 * <p>
 * Почему миксин. Отложить отправку чанка публичным путём нельзя: {@code ChunkWatchEvent.Watch} NeoForge приходит, когда
 * чанк уже в очереди игрока, и убрать его оттуда можно ({@code PlayerChunkSender.dropChunk}), но тогда ваниль считает,
 * что чанк у игрока есть ({@code ChunkMap.isChunkTracked}: в обзоре и не в очереди), — и шлёт ему сущности и изменения
 * блоков чанка, которого у клиента нет, для всех модов сборки. Отдать чанк целым, а руины пакетами после — игрок видит
 * целый город, который у него на глазах разом становится руинами. Поставить руины до того, как чанк станет полным,
 * тоже нельзя: план читает соседей (с диска) и строится в фоновых потоках. Миксин не меняет отправку остальных чанков:
 * без подрыва — одна проверка пустой очереди руин; убирает он только чанки, до которых волна дошла, а руины мод ещё
 * не поставил, и не дольше {@code ScarQueue.WITHHOLD_LIMIT} тиков, — в очереди игрока они остаются.
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin implements ChunkSendGate.Gated {
    @Shadow
    @Final
    private LongSet pendingChunks;

    @Unique
    private LongArrayList airstrike$held;

    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void airstrike$withhold(ServerPlayer player, CallbackInfo ci) {
        // прошлый вызов не дошёл до конца (исключение между HEAD и RETURN): убранные тогда чанки — назад в очередь
        if (airstrike$held != null) pendingChunks.addAll(airstrike$held);
        airstrike$held = ChunkSendGate.withhold(player.serverLevel(), pendingChunks);
    }

    @Inject(method = "sendNextChunks", at = @At("RETURN"))
    private void airstrike$restore(ServerPlayer player, CallbackInfo ci) {
        if (airstrike$held == null) return;
        pendingChunks.addAll(airstrike$held);
        airstrike$held = null;
    }
}
