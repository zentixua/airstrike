package ua.zentix.incoming.mixin.xaero;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import ua.zentix.incoming.Incoming;

/**
 * Кэш цветов блоков xaerolib (Xaero's World Map и Minimap) — один объект на клиент с обычными картами без замка
 * ({@code Object2IntOpenHashMap blockTintIndices}, {@code blockColors}), а читают его и поток игры (плитки карты перед
 * выгрузкой), и фоновые задачи карты. Два потока, вставляющих в него одновременно, ломают таблицу: клиент падает
 * с {@code ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 2049} в {@code Object2IntOpenHashMap.rehash}
 * (xaerolib 1.7.3, Xaero's World Map 1.46.0), например сразу после переноса к руинам: руины — сотни новых для кэша
 * состояний, и вставки с ростом таблицы идут пачкой. −1 — место в таблице, а не номер состояния блока. Здесь обращения
 * к кэшу идут под замком самого объекта. Без Xaero миксин не применяется ({@code require = 0}, как у Sable).
 */
@Pseudo
@Mixin(targets = "xaero.lib.client.level.block.BlockTextureColorUtils", remap = false)
public abstract class BlockTextureColorLockMixin {
    private static volatile boolean incoming$reported;

    @WrapMethod(method = "getBlockTextureColor", require = 0)
    private int incoming$lockColor(BlockState state, boolean flag, Level level, Registry<Block> blocks, BlockPos pos, Operation<Integer> original) {
        if (!incoming$reported) {
            incoming$reported = true;
            Incoming.LOG.info("Xaero: xaerolib block colour cache is guarded by a lock (first access, thread {})", Thread.currentThread().getName());
        }
        synchronized (this) {
            return original.call(state, flag, level, blocks, pos);
        }
    }

    @WrapMethod(method = "getBlockTintIndex", require = 0)
    private int incoming$lockTint(BlockState state, Operation<Integer> original) {
        synchronized (this) {
            return original.call(state);
        }
    }
}
