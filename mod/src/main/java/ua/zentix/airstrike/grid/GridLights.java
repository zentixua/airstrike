package ua.zentix.airstrike.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.registry.ModBlocks;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Лампы сети и их обесточенные двойники ({@link ModBlocks#UNLIT}). Перевод — в обе стороны с переносом всех свойств
 * состояния (подвешен, в воде, направление, ось, уровень, «горит», «под сигналом»): после блэкаута каждая лампа
 * возвращается ровно такой, какой была. Двойника игрок не получит (предмета нет), поэтому любой двойник в мире —
 * погашенная нами лампа, и возврат не требует записей о том, что где стояло.
 */
public final class GridLights {
    /** Состояние лампы → состояние двойника и обратно (все состояния всех пар; перевод — один поиск, без копирования свойств). */
    private static volatile Map<BlockState, BlockState> toUnlit, toLit;
    /** Пары состояний, которые нельзя менять прямо в палитре секции (см. {@link #inPlace}); у ламп сети таких нет. */
    private static volatile Set<BlockState> notInPlace;

    private GridLights() {}

    private static void init() {
        if (toUnlit != null) return;
        Map<BlockState, BlockState> unlit = new IdentityHashMap<>(), lit = new IdentityHashMap<>();
        Set<BlockState> bad = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ModBlocks.UnlitPair p : ModBlocks.UNLIT) {
            Block on = p.lit().get(), off = p.unlit().get();
            for (BlockState s : on.getStateDefinition().getPossibleStates()) unlit.put(s, copy(s, off.defaultBlockState()));
            for (BlockState s : off.getStateDefinition().getPossibleStates()) lit.put(s, copy(s, on.defaultBlockState()));
        }
        unlit.forEach((from, to) -> {
            if (!sameInWorld(from, to)) bad.add(from);
        });
        lit.forEach((from, to) -> {
            if (!sameInWorld(from, to)) bad.add(from);
        });
        notInPlace = bad;
        toLit = lit;
        toUnlit = unlit;
    }

    /**
     * Для мира лампа и двойник — один и тот же блок, кроме света: ни блок-сущности, ни другой формы (столкновения,
     * контура, затенения), ни другого воздуха, ни другой непрозрачности, ни другого пропуска неба, ни другого места
     * в картах высот. Тогда их можно менять прямо в палитре секции, без {@code setBlock} (он же обновляет карты высот
     * и источники неба чанка): соседям, картам высот, физике аппаратов и столкновениям знать не о чем, остаётся свет.
     */
    private static boolean sameInWorld(BlockState a, BlockState b) {
        var at = EmptyBlockGetter.INSTANCE;
        for (Heightmap.Types type : Heightmap.Types.values()) {
            if (type.isOpaque().test(a) != type.isOpaque().test(b)) return false;
        }
        return !a.hasBlockEntity() && !b.hasBlockEntity() && a.isAir() == b.isAir() && a.canOcclude() == b.canOcclude()
                && a.getFluidState().equals(b.getFluidState())
                && a.propagatesSkylightDown(at, BlockPos.ZERO) == b.propagatesSkylightDown(at, BlockPos.ZERO)
                && a.getLightBlock(at, BlockPos.ZERO) == b.getLightBlock(at, BlockPos.ZERO)
                && a.useShapeForLightOcclusion() == b.useShapeForLightOcclusion()
                && a.getCollisionShape(at, BlockPos.ZERO).toAabbs().equals(b.getCollisionShape(at, BlockPos.ZERO).toAabbs())
                && a.getShape(at, BlockPos.ZERO).toAabbs().equals(b.getShape(at, BlockPos.ZERO).toAabbs())
                && a.getOcclusionShape(at, BlockPos.ZERO).toAabbs().equals(b.getOcclusionShape(at, BlockPos.ZERO).toAabbs());
    }

    /** Перевод {@code state} (лампы или двойника) можно делать прямо в палитре ({@link #sameInWorld}). */
    public static boolean inPlace(BlockState state) {
        init();
        return !notInPlace.contains(state);
    }

    /** Горящая лампа сети (гаснет при блэкауте). */
    public static boolean isLit(BlockState state) {
        init();
        return toUnlit.containsKey(state);
    }

    /** Погашенная лампа (двойник). */
    public static boolean isUnlit(BlockState state) {
        init();
        return toLit.containsKey(state);
    }

    /** Двойник лампы с теми же свойствами; null — не лампа сети. */
    @Nullable
    public static BlockState unlit(BlockState state) {
        init();
        return toUnlit.get(state);
    }

    /** Лампа по двойнику с теми же свойствами; null — не двойник. */
    @Nullable
    public static BlockState lit(BlockState state) {
        init();
        return toLit.get(state);
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
