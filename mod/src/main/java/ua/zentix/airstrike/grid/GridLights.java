package ua.zentix.airstrike.grid;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.registry.ModBlocks;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Лампы сети и их обесточенные двойники ({@link ModBlocks#UNLIT}). Перевод — в обе стороны с переносом всех свойств
 * состояния (подвешен, в воде, направление, ось, уровень, «горит», «под сигналом»): после блэкаута каждая лампа
 * возвращается ровно такой, какой была. Двойника игрок не получит (предмета нет), поэтому любой двойник в мире —
 * погашенная нами лампа, и возврат не требует записей о том, что где стояло.
 */
public final class GridLights {
    private static volatile Map<Block, Block> toUnlit, toLit;

    private GridLights() {}

    private static void init() {
        if (toUnlit != null) return;
        Map<Block, Block> unlit = new IdentityHashMap<>(), lit = new IdentityHashMap<>();
        for (ModBlocks.UnlitPair p : ModBlocks.UNLIT) {
            unlit.put(p.lit().get(), p.unlit().get());
            lit.put(p.unlit().get(), p.lit().get());
        }
        toLit = lit;
        toUnlit = unlit;
    }

    /** Горящая лампа сети (гаснет при блэкауте). */
    public static boolean isLit(BlockState state) {
        init();
        return toUnlit.containsKey(state.getBlock());
    }

    /** Погашенная лампа (двойник). */
    public static boolean isUnlit(BlockState state) {
        init();
        return toLit.containsKey(state.getBlock());
    }

    /** Двойник лампы с теми же свойствами; null — не лампа сети. */
    @Nullable
    public static BlockState unlit(BlockState state) {
        init();
        Block to = toUnlit.get(state.getBlock());
        return to == null ? null : copy(state, to.defaultBlockState());
    }

    /** Лампа по двойнику с теми же свойствами; null — не двойник. */
    @Nullable
    public static BlockState lit(BlockState state) {
        init();
        Block to = toLit.get(state.getBlock());
        return to == null ? null : copy(state, to.defaultBlockState());
    }

    /** Перевод к нужному состоянию сети: {@code dark} — погасить, иначе зажечь; null — менять нечего. */
    @Nullable
    public static BlockState toward(BlockState state, boolean dark) {
        return dark ? unlit(state) : lit(state);
    }

    /** Лампа, которую надо перевести к состоянию сети ({@code dark} — горящая, иначе погашенная). */
    public static boolean needs(BlockState state, boolean dark) {
        return dark ? isLit(state) : isUnlit(state);
    }

    private static BlockState copy(BlockState from, BlockState to) {
        for (Property<?> p : from.getProperties()) {
            if (to.hasProperty(p)) to = with(to, p, from);
        }
        return to;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState to, Property<T> p, BlockState from) {
        return to.setValue(p, from.getValue(p));
    }
}
