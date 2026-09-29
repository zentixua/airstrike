package ua.zentix.airstrike.mixin.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.zentix.airstrike.util.BlockTicking;

/**
 * Блок-сущности тикают, только когда готовы соседи чанка — см. {@link BlockTicking}. {@code LevelChunk.isTicking}
 * зовёт только {@code BoundTickingBlockEntity.tick}: раз на каждую блок-сущность за тик, поэтому ответ держится на тик
 * у самого чанка. Что миксин встал, проверяют GameTest {@code blockEntitiesWaitForNeighbours} и
 * {@link BlockTicking#onServerTick} в игре.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkTickingMixin {
    @Shadow
    @Final
    Level level;

    @Unique
    private long airstrike$readyTick = Long.MIN_VALUE;
    @Unique
    private boolean airstrike$ready;

    @Inject(method = "isTicking", at = @At("RETURN"), cancellable = true)
    private void airstrike$neighboursReady(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockTicking.checked();
        if (!cir.getReturnValueZ() || !(level instanceof ServerLevel server)) return;
        long now = server.getGameTime();
        if (airstrike$readyTick != now) {
            airstrike$readyTick = now;
            airstrike$ready = BlockTicking.neighboursReady(server, ((LevelChunk) (Object) this).getPos());
        }
        if (!airstrike$ready) cir.setReturnValue(false);
    }
}
