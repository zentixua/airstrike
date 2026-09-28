package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.ArrayList;
import java.util.List;

/**
 * Всё, что длится несколько тиков, но не является сущностью в мире: таймлайны взрывов (живут секунды, не
 * сохраняются), залпы ({@link SalvoData}) и полёты вне загруженного мира ({@link VirtualFlights}) — последние два
 * сохраняются в мире. Тикает в конце тика мира.
 */
public final class StrikeWorld {
    private final List<Timeline> timelines = new ArrayList<>();
    private final List<Timeline> pending = new ArrayList<>();

    /** Для {@link ModAttachments#STRIKE_WORLD}: своё у каждого мира, живёт, пока мир загружен, не сохраняется. */
    public StrikeWorld() {}

    public static StrikeWorld get(ServerLevel level) {
        return level.getData(ModAttachments.STRIKE_WORLD);
    }

    /** Добавить таймлайн; первый тик — в конце текущего тика мира. */
    public void add(Timeline timeline) {
        pending.add(timeline);
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // «/tick freeze» останавливает сущности — снаряды вне мира, залпы и взрывы стоят вместе с ними
        if (!level.tickRateManager().runsNormally()) return;
        SalvoData.get(level).tick(level);
        VirtualFlights.get(level).tick(level);
        if (level.hasData(ModAttachments.STRIKE_WORLD)) get(level).tick(level);
    }

    private void tick(ServerLevel level) {
        timelines.addAll(pending);
        pending.clear();
        timelines.removeIf(t -> {
            try {
                return !t.tick(level);
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Таймлайн удара упал с ошибкой и убран", e);
                return true;
            }
        });
    }

    /** Отбой: взрывы в процессе доигрываются (это уже случилось), залпы отменяются. */
    public static void clearSalvos(ServerLevel level) {
        SalvoData.get(level).clear();
    }
}
