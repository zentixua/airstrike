package ua.zentix.airstrike.mixin.server;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ua.zentix.airstrike.util.StopPump;

import java.util.function.BooleanSupplier;

/**
 * Выход из мира не зависает в выгрузке чанков: каждый круг цикла выгрузки в {@code MinecraftServer.stopServer} —
 * {@link StopPump#round} (прокачка с пределом, затем задачи чанков мира). Почему — там. Проверка — GameTest
 * {@code stopRoundsFinishWhileGenerationHoldsUnloadingChunk}: тот же круг на держателе, которого генерация вернула из
 * выгрузки.
 */
@Mixin(MinecraftServer.class)
public abstract class StopServerChunksMixin {
    @WrapOperation(method = "stopServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"))
    private void airstrike$stopRound(ServerChunkCache cache, BooleanSupplier hasTime, boolean tickChunks, Operation<Void> original) {
        StopPump.round(cache, hasTime, bounded -> original.call(cache, bounded, tickChunks));
    }
}
