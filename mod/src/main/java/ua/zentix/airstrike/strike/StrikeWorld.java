package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.StrikeProjectile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Всё, что длится несколько тиков, но не является сущностью в мире: таймлайны взрывов (живут секунды, не
 * сохраняются), залпы ({@link SalvoData}) и полёты вне загруженного мира ({@link VirtualFlights}) — последние два
 * сохраняются в мире. Тикает в конце тика мира.
 */
public final class StrikeWorld {
    private static final Map<ServerLevel, StrikeWorld> WORLDS = new WeakHashMap<>();

    private final List<Timeline> timelines = new ArrayList<>();
    private final List<Timeline> pending = new ArrayList<>();

    private StrikeWorld() {}

    public static StrikeWorld get(ServerLevel level) {
        return WORLDS.computeIfAbsent(level, l -> new StrikeWorld());
    }

    /** Добавить таймлайн; первый тик — в конце текущего тика мира. */
    public void add(Timeline timeline) {
        pending.add(timeline);
    }

    public int activeTimelines() {
        return timelines.size() + pending.size();
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        SalvoData.get(level).tick(level);
        VirtualFlights.get(level).tick(level);
        StrikeWorld w = WORLDS.get(level);
        if (w != null) w.tick(level);
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

    /**
     * Сервер останавливается (в том числе «Сохранить и выйти» в одиночной игре): снаряды в мире уходят в полёт вне
     * мира и сохраняются с ним, а после запуска летят дальше сами — иначе они ждали бы в файлах чанков, пока кто-то
     * не придёт туда снова, и тикеты района цели после запуска были бы потеряны.
     */
    public static void onServerStopping(ServerStoppingEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (StrikeProjectile p : level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> !p.isRemoved())) {
                p.parkForShutdown(level);
            }
        }
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level l && l instanceof ServerLevel level) WORLDS.remove(level);
    }
}
