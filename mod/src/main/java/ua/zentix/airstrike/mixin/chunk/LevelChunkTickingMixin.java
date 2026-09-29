package ua.zentix.airstrike.mixin.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.zentix.airstrike.util.BlockTicking;

/**
 * Блок-сущности тикают, только когда готовы соседи чанка — см. {@link BlockTicking}. {@code LevelChunk.isTicking}
 * зовёт только {@code BoundTickingBlockEntity.tick}. Что миксин встал, проверяет GameTest
 * {@code blockEntitiesWaitForNeighbours}.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkTickingMixin {
    @Shadow
    @Final
    Level level;

    @Inject(method = "isTicking", at = @At("RETURN"), cancellable = true)
    private void airstrike$neighboursReady(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && level instanceof ServerLevel server && !BlockTicking.neighboursReady(server, ((LevelChunk) (Object) this).getPos())) {
            cir.setReturnValue(false);
        }
    }
}
