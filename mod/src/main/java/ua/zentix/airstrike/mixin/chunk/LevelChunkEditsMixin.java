package ua.zentix.airstrike.mixin.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.zentix.airstrike.nuclear.world.RuinPlan;

/**
 * Счётчик изменений блоков чанка через {@code LevelChunk.setBlockState} ({@link RuinPlan.Edits}): план руин по нему
 * видит, что чанк меняли после плана не только в местах плана (блок, поставленный внутри дома), и тогда считает карты
 * высот и источники неба по всему чанку.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkEditsMixin implements RuinPlan.Edits {
    @Unique
    private long airstrike$edits;

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void airstrike$countEdit(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> cir) {
        airstrike$edits++;
    }

    @Override
    public long airstrike$edits() {
        return airstrike$edits;
    }
}
