package ua.zentix.airstrike.grid.block;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

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

    /** Выбор блока (средняя кнопка) даёт лампу. */
    static ItemStack clone(Supplier<Block> lit) {
        return new ItemStack(lit.get());
    }
}
