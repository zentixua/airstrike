package ua.zentix.airstrike.work;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.grid.Blackouts;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.strike.StrikeWorld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Общий бюджет тяжёлой работы мода на тик сервера ({@code performance.work_ms_per_tick}): полосы по порядку важности,
 * у каждой свои часы ({@link WorkClock}: своя оценка единицы — ванильный взрыв и лампа блэкаута разные величины),
 * срок полосы — то, что оставили полосы выше, не больше её собственного предела. Идёт после тика миров, так что
 * попадание, случившееся в тике, в пустой очереди делается в том же тике (картинка клиента начинается с пакета удара).
 * <p>
 * Одна единица за тик у каждой полосы с работой — всегда ({@link WorkClock#canStart}): иначе после долгой единицы
 * полоса встала бы. Отсюда и граница тика: общий срок плюс по одной единице на полосу. Миры в полосе — по кругу:
 * первым каждый тик идёт следующий.
 * <p>
 * Ядерные очереди пока на своих часах ({@code NuclearStrikes.onServerTick}, {@code destruction_ms_per_tick}); полосой
 * они станут отдельным шагом.
 */
public final class WorkScheduler {
    /** Полоса работы; порядок — порядок в тике. */
    public enum Lane {
        /** Попадания: взрывы, стёкла, обломки, щебень бомбы ({@link StrikeWorld#impacts}) — видно сразу. */
        IMPACT,
        /** Блэкаут ({@link Blackouts#work}) — фон. */
        GRID
    }

    /** Доля общего бюджета у попаданий, пока у полос ниже есть работа: блэкаут не стоит весь залп. */
    static final double IMPACT_SHARE_WHEN_SHARED = 2.0 / 3.0;

    private WorkScheduler() {}

    /** Часы полосы попаданий — одни на сервер (в верхнем мире). */
    public static WorkClock impactClock(MinecraftServer server) {
        return server.overworld().getData(ModAttachments.IMPACT_CLOCK);
    }

    /** Часы полосы попаданий в игре: оценка тает к каждому тику, иначе один взрыв на сотни мс держал бы очередь на единице за тик. */
    public static WorkClock newImpactClock() {
        return WorkClock.decaying(0.5);
    }

    /** Подменить часы попаданий (проверки — считающими, {@link WorkClock#counting}; вернуть — {@link #newImpactClock}). */
    public static void useImpactClock(MinecraftServer server, WorkClock clock) {
        server.overworld().setData(ModAttachments.IMPACT_CLOCK, clock);
    }

    /** Общий бюджет тика, нс. */
    public static long totalNanos() {
        return AirstrikeConfig.SERVER.workBudgetMs.get() * 1_000_000L;
    }

    /**
     * Бюджет полосы попаданий: весь общий, если полосам ниже нечего делать, иначе {@link #IMPACT_SHARE_WHEN_SHARED}.
     */
    static long impactBudget(long total, boolean lowerLanesPending) {
        return lowerLanesPending ? (long) (total * IMPACT_SHARE_WHEN_SHARED) : total;
    }

    /** Бюджет полосы ниже: что осталось от общего после полос выше, не больше её предела и не меньше нуля. */
    static long remaining(long total, long usedAbove, long cap) {
        return Math.min(cap, Math.max(0, total - usedAbove));
    }

    public static void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        List<ServerLevel> levels = new ArrayList<>();
        server.getAllLevels().forEach(levels::add);
        Collections.rotate(levels, -(server.getTickCount() % levels.size()));
        long total = totalNanos();

        WorkClock impact = impactClock(server);
        boolean gridPending = Blackouts.pending(server);
        impact.start(impactBudget(total, gridPending));
        for (ServerLevel level : levels) {
            // «/tick freeze» останавливает и попадания (как снаряды и таймлайны)
            if (level.tickRateManager().runsNormally() && level.hasData(ModAttachments.STRIKE_WORLD)) {
                StrikeWorld.get(level).workImpacts(level, impact);
            }
        }
        for (ServerLevel level : levels) {
            if (level.hasData(ModAttachments.STRIKE_WORLD)) StrikeWorld.get(level).endImpactTick(level);
        }

        WorkClock grid = Blackouts.clock(server);
        grid.start(remaining(total, impact.usedThisTickNanos(), AirstrikeConfig.SERVER.gridTimeBudgetMs.get() * 1_000_000L));
        Blackouts.work(server, levels, grid);
    }
}
