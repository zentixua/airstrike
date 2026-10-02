package ua.zentix.airstrike.util;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.function.Predicate;

/** Вопросы к блокам секции по её палитре — без прохода по всем 4096 местам, где палитра отвечает точно. */
public final class Palettes {
    private Palettes() {}

    /**
     * Есть ли в блоках {@code states} состояние по {@code filter}. Сперва отвечает палитра ({@code maybeHas}): у малых
     * палитр «нет» точно. Глобальная палитра (больше 256 разных состояний в секции — обычное дело в детальном городе)
     * отвечает «может быть» всегда ({@code GlobalPalette.maybeHas}), а малая помнит и ушедшие состояния, — тогда
     * точный подсчёт по блокам, как у ванили ({@code LevelChunkSection.recalcBlockCounts}): он быстрее прохода с
     * проверкой каждого места (фильтр — по разным состояниям, а не по местам). Подсчёт может назвать и состояние
     * с нулём блоков (Lithium считает по записям палитры) — такое не в счёт.
     */
    public static boolean contains(PalettedContainer<BlockState> states, Predicate<BlockState> filter) {
        if (!states.maybeHas(filter)) return false;
        boolean[] found = {false};
        states.count((state, n) -> found[0] |= n > 0 && filter.test(state));
        return found[0];
    }
}
