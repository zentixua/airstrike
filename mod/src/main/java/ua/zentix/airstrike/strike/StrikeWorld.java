package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.ArrayList;
import java.util.List;

/**
 * Всё, что длится несколько тиков, но не является сущностью в мире: таймлайны взрывов (живут секунды, не
 * сохраняются), залпы ({@link SalvoData}) и полёты вне загруженного мира ({@link VirtualFlights}) — последние два
 * сохраняются в мире. Тикает в конце тика мира.
 * <p>
 * Мир без игроков через 300 тиков перестаёт тикать сущности ({@code ServerLevel.tick}, {@code emptyTime}), если в нём
 * нет принудительно загруженных чанков, а тикеты регионов ({@link ChunkTickets}, {@link FlightTickets}) ими не считаются:
 * снаряды повисли бы в воздухе, пока залпы и полёты вне мира шли бы дальше. Пока в мире идёт удар, он не засыпает —
 * так же, как ванильный переход сущности между мирами ({@code Entity.changeDimension}) будит мир назначения.
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
        if (level.players().isEmpty() && busy(level)) level.resetEmptyTime();
        // «/tick freeze» останавливает сущности — снаряды вне мира, залпы и взрывы стоят вместе с ними
        if (!level.tickRateManager().runsNormally()) return;
        SalvoData.get(level).tick(level);
        VirtualFlights.get(level).tick(level);
        if (level.hasData(ModAttachments.STRIKE_WORLD)) get(level).tick(level);
    }

    /** В мире идёт удар: снаряды в мире и вне его, залпы, взрывы. */
    private static boolean busy(ServerLevel level) {
        if (SalvoData.get(level).size() > 0 || VirtualFlights.get(level).size() > 0) return true;
        if (level.hasData(ModAttachments.STRIKE_WORLD) && !get(level).idle()) return true;
        List<StrikeProjectile> any = new ArrayList<>(1);
        level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> !p.isRemoved(), any, 1);
        return !any.isEmpty();
    }

    private boolean idle() {
        return timelines.isEmpty() && pending.isEmpty();
    }

    private void tick(ServerLevel level) {
        timelines.addAll(pending);
        pending.clear();
        timelines.removeIf(t -> {
            boolean done;
            try {
                done = !t.tick(level);
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Таймлайн удара упал с ошибкой и убран", e);
                done = true;
            }
            if (done) t.end(level);
            return done;
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
}
