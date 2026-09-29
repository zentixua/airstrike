package ua.zentix.airstrike.grid.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.airstrike.grid.BlackoutWorld;

import java.util.function.Supplier;

/**
 * Обесточенный двойник лампы сети: та же модель, те же свойства состояния, звук, прочность и добыча, что у лампы,
 * только без света. Так лампа гаснет везде — и в свете мира, и у шейдеров (Complementary светит лампы по id блока,
 * а не по освещению вокруг), и в дальних LOD Distant Horizons (он считает свет по тому, светится ли блок).
 * Предмета у двойника нет: в мире он только от блэкаута, и возврат света однозначен ({@link ua.zentix.airstrike.grid.GridLights}).
 */
public interface Unlit {
    /** Лампа, двойником которой блок является. */
    Block lit();

    /** «Светокамень (нет питания)» — по имени лампы, без своего перевода на каждого двойника. */
    static MutableComponent name(Supplier<Block> lit) {
        return Component.translatable("block.airstrike.unlit", lit.get().getName());
    }

    /**
     * Двойник появился не от блэкаута — его сдвинул поршень, собрал и разобрал аппарат Create, перенёс в плот
     * аппарат Sable: в светлом квартале (в плоте — всегда: у аппарата своё питание) лампа снова зажигается
     * ({@link BlackoutWorld#relightLater}; не тиком блока — в плоте Sable тики блоков не идут).
     */
    static void placed(BlockState state, Level level, BlockPos pos, BlockState oldState) {
        if (level instanceof ServerLevel server && !oldState.is(state.getBlock()) && BlackoutWorld.powered(server, pos)) {
            BlackoutWorld.get(server).relightLater(pos);
        }
    }

    /**
     * Страховка: случайный тик двойника там, где ток есть, — снова лампа. Так зажигаются двойники, которых не
     * видят ни очередь блэкаута, ни {@link #placed}: в плоте аппарата Sable (сборка ставит блоки прямо в секции),
     * в чанке, оставшемся тёмным по любой другой причине.
     */
    static void randomTick(BlockState state, ServerLevel level, BlockPos pos) {
        if (BlackoutWorld.powered(level, pos)) BlackoutWorld.get(level).relightLater(pos);
    }

    /** Выбор блока (средняя кнопка) даёт лампу. */
    static ItemStack clone(Supplier<Block> lit) {
        return new ItemStack(lit.get());
    }
}
