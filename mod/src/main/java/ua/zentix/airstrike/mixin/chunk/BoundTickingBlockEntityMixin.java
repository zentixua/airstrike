package ua.zentix.airstrike.mixin.chunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.zentix.airstrike.util.BlockTicking;

/**
 * Блок-сущность тикает, только когда готовы соседи её чанка — см. {@link BlockTicking}. Условие — в начале
 * {@code BoundTickingBlockEntity.tick}, а не в {@code LevelChunk.isTicking}: Lithium (у хоста 0.15.4) заменяет вызов
 * {@code isTicking} в этом методе своим ({@code @Redirect}, {@code world.block_entity_ticking.world_border}), и условие
 * в {@code isTicking} не спрашивалось ни разу. Отменённый в начале тик — то же, что {@code isTicking} = false: ваниль
 * тогда тоже ничего не делает. Что миксин встал, проверяют GameTest {@code blockEntitiesWaitForNeighbours} и
 * {@link BlockTicking#onServerTick} в игре.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
    /** Чанк блок-сущности (внешний объект вложенного класса). */
    @Shadow(aliases = "this$0")
    @Final
    LevelChunk field_27223;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void airstrike$neighboursReady(CallbackInfo ci) {
        if (!(field_27223.getLevel() instanceof ServerLevel server)) return;
        BlockTicking.checked();
        if (!((BlockTicking.ChunkGate) field_27223).airstrike$neighboursReady(server)) ci.cancel();
    }
}
