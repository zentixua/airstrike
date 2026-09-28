package ua.zentix.airstrike.compat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Защита от краша Sable 2.0.5 при дроблении аппарата (взрывом — нашим или чужим).
 * <p>
 * Когда аппарат распадается на много кусков, Sable собирает куски по одному; кусок без массы тут же удаляется,
 * а его плот отдаётся следующему. Если в том же тике делится кусок в таком плоте, поиск «чей это плот» находит
 * удалённый аппарат, и {@code SubLevelAssemblyHelper.assembleBlocks} бросает RuntimeException — падает тик мира
 * и вся игра («Exception ticking world», 27.09.2026). Миксин {@code SubLevelSplitGuardMixin} оборачивает
 * {@code SubLevelHeatMapManager.split}: именно это исключение пишется в лог, дробление куска откладывается
 * (его блоки остаются одним аппаратом), мир живёт дальше. Остальные исключения идут дальше как были.
 * <p>
 * Интерфейс — метка: миксин добавляет его классу Sable, и GameTest по нему видит, что обёртка встала.
 */
public interface SplitGuard {
    Logger LOG = LogUtils.getLogger();
    String REMOVED_PLOT = "Sub-level assembly attempted inside plot of already removed sub-level";

    /** Это тот самый сбой Sable, который можно пропустить. */
    static boolean isRemovedPlot(RuntimeException e) {
        return REMOVED_PLOT.equals(e.getMessage());
    }

    static void swallowed(RuntimeException e) {
        LOG.warn("Sable: дробление аппарата пропущено, иначе упал бы мир ({})", e.getMessage());
    }
}
