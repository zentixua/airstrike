package ua.zentix.airstrike.mixin.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import ua.zentix.airstrike.nuclear.world.RuinPlan;

/**
 * Счётчик изменений блоков чанка через {@code LevelChunk.setBlockState} ({@link RuinPlan.Edits}): план руин по нему
 * видит, что чанк меняли после плана не только в местах плана (блок, поставленный внутри дома), и тогда считает карты
 * высот и источники неба в столбцах плана заново. Не {@code @Inject}: тот на каждый {@code setBlockState} в игре создавал
 * бы объект обратного вызова, а здесь — одно сложение. Встал ли он, проверяет {@code RuinPlan.edits} на деле.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkEditsMixin implements RuinPlan.Edits {
    @Unique
    private long airstrike$edits;

    @ModifyVariable(method = "setBlockState", at = @At("HEAD"), argsOnly = true)
    private BlockPos airstrike$countEdit(BlockPos pos) {
        airstrike$edits++;
        return pos;
    }

    @Override
    public long airstrike$edits() {
        return airstrike$edits;
    }
}
