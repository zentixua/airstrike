package ua.zentix.airstrike.mixin.chunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import ua.zentix.airstrike.util.BlockTicking;

/**
 * Готовность соседей чанка для тика его блок-сущностей ({@link BlockTicking}, спрашивает
 * {@link BoundTickingBlockEntityMixin}): ответ держится на тик у самого чанка — вопрос задаёт каждая его блок-сущность.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkTickingMixin implements BlockTicking.ChunkGate {
    @Unique
    private long airstrike$readyTick = Long.MIN_VALUE;
    @Unique
    private boolean airstrike$ready;

    @Override
    public boolean airstrike$neighboursReady(ServerLevel server) {
        long now = server.getGameTime();
        if (airstrike$readyTick != now) {
            airstrike$readyTick = now;
            airstrike$ready = BlockTicking.neighboursReady(server, ((LevelChunk) (Object) this).getPos());
        }
        return airstrike$ready;
    }
}
