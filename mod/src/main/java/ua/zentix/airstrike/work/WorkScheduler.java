package ua.zentix.airstrike.work;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.grid.Blackouts;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.nuclear.world.NuclearWorld;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.strike.StrikeWorld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Общий бюджет тяжёлой работы мода на тик сервера ({@code performance.work_ms_per_tick}): полосы по порядку важности,
 * у каждой свои часы ({@link WorkClock}: своя оценка единицы — ванильный взрыв и лампа блэкаута разные величины),
 * срок полосы — то, что оставили полосы выше, не больше её собственного предела. Идёт после тика миров, так что
 * попадание, случившееся в тике, в пустой очереди делается в том же тике (картинка клиента начинается с пакета удара).
 * <p>
 * Бюджет — потолок, а не обещание: полосы работают, только пока у сервера есть время в тике ({@code hasTime}
 * события — ванильное «до следующего тика ещё есть время», по которому и ваниль выгружает чанки). Сервер, который сам
 * не успевает, получает по одной единице на полосу за тик, а не бюджет поверх своей работы: залп ведущего по всей карте
 * на занятом сервере прибавлял к каждому тику до 30 мс (Zearth 06.10.2026). В ускорении ({@code /tick sprint}) тики
 * идут без пауз и времени в тике у ванили нет никогда — там полосам весь их бюджет, как в обычном тике.
 * <p>
 * Одна единица за тик у каждой полосы с работой — всегда ({@link WorkClock#canStart}): иначе после долгой единицы
 * полоса встала бы. Отсюда и граница тика: общий срок плюс по одной единице на полосу. Миры в полосе — по кругу:
 * первым каждый тик идёт следующий.
 * <p>
 * Фоновый пул руин ядерки ({@code RuinWorkers}) — вне часов: в полосе NUCLEAR только работа потока сервера.
 */
public final class WorkScheduler {
    /** Полоса работы; порядок — порядок в тике. */
    public enum Lane {
        /** Попадания: взрывы, стёкла, обломки, щебень бомбы ({@link StrikeWorld#impacts}) — видно сразу. */
        IMPACT,
        /** Очереди ядерки ({@link NuclearStrikes#work}): фронт, свет, руины, воронки — идут по волне, терпят тики. */
        NUCLEAR,
        /** Блэкаут ({@link Blackouts#work}) — фон. */
        GRID
    }

    /** Доля общего бюджета у попаданий, пока у полос ниже есть работа: блэкаут не стоит весь залп. */
    static final double IMPACT_SHARE_WHEN_SHARED = 2.0 / 3.0;

    /**
     * Время полос мода по тикам сервера, нс (кольцо по номеру тика, как у ванильных {@code tickTimesNanos}): из тика
     * вычитает его подстройка фоновых потоков руин ({@link #ownNanos}). Не состояние мира — замер последних тиков,
     * каждый тик переписывается, поэтому статика.
     */
    private static final long[] OWN = new long[100];
    /** Сводка полос раз в {@link #REPORT_TICKS}: тиков, их время, наибольший, время полос (попадания, ядерка, блэкаут). */
    private static final int REPORT_TICKS = 600;
    private static final long[] WINDOW = new long[6];

    private WorkScheduler() {}

    /** Сколько заняли полосы мода в тике с этим номером ({@code MinecraftServer.getTickCount}), нс. */
    public static long ownNanos(int tick) {
        return OWN[Math.floorMod(tick, OWN.length)];
    }

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

    /**
     * Бюджет ядерки: что осталось после попаданий, не больше её предела, и за вычетом предела блэкаута, пока у него
     * есть работа ({@code gridReserve}): иначе во время волны ядерка брала весь срок, а каскад блэкаута ядерки шёл по
     * единице за тик вместо своих мс.
     */
    static long nuclearBudget(long total, long usedAbove, long cap, long gridReserve) {
        return remaining(total, usedAbove + gridReserve, cap);
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
        BooleanSupplier spare = server.tickRateManager().isSprinting() ? () -> true : e::hasTime;

        WorkClock impact = impactClock(server);
        impact.start(impactBudget(total, Blackouts.pending(server) || NuclearStrikes.pending(server)), spare);
        for (ServerLevel level : levels) {
            // «/tick freeze» останавливает и попадания (как снаряды и таймлайны)
            if (level.tickRateManager().runsNormally() && level.hasData(ModAttachments.STRIKE_WORLD)) {
                StrikeWorld.get(level).workImpacts(level, impact);
            }
        }
        for (ServerLevel level : levels) {
            if (level.hasData(ModAttachments.STRIKE_WORLD)) StrikeWorld.get(level).endImpactTick(level);
        }

        WorkClock nuclear = NuclearWorld.clock(server);
        long gridCap = AirstrikeConfig.SERVER.gridTimeBudgetMs.get() * 1_000_000L;
        nuclear.start(nuclearBudget(total, impact.usedThisTickNanos(), AirstrikeConfig.SERVER.nukeTimeBudgetMs.get() * 1_000_000L,
                Blackouts.pending(server) ? gridCap : 0), spare);
        NuclearStrikes.work(levels, nuclear);

        WorkClock grid = Blackouts.clock(server);
        grid.start(remaining(total, impact.usedThisTickNanos() + nuclear.usedThisTickNanos(), gridCap), spare);
        Blackouts.work(server, levels, grid);

        OWN[Math.floorMod(server.getTickCount(), OWN.length)] = impact.usedThisTickNanos() + nuclear.usedThisTickNanos() + grid.usedThisTickNanos();
        report(server, impact.usedThisTickNanos(), nuclear.usedThisTickNanos(), grid.usedThisTickNanos());
    }

    /**
     * Раз в 30 с, если полосы работали: средний и наибольший тик сервера (прошлого — нынешний ещё идёт), сколько из
     * него в среднем заняли полосы и сколько — всё остальное (ваниль и другие моды). По ней видно, чей тик: нашего
     * бюджета или чужой работы (строка «Работа мода за 30 с»).
     */
    private static void report(MinecraftServer server, long impact, long nuclear, long grid) {
        long[] times = server.getTickTimesNanos();
        long last = times[Math.floorMod(server.getTickCount() - 1, times.length)];
        WINDOW[0]++;
        WINDOW[1] += last;
        WINDOW[2] = Math.max(WINDOW[2], last);
        WINDOW[3] += impact;
        WINDOW[4] += nuclear;
        WINDOW[5] += grid;
        if (WINDOW[0] < REPORT_TICKS) return;
        long n = WINDOW[0];
        if (WINDOW[3] + WINDOW[4] + WINDOW[5] > 0) {
            ua.zentix.airstrike.Airstrike.LOG.info("Работа мода за 30 с: тик сервера в среднем {} мс (наибольший {}), из него полосы мода в среднем — "
                            + "попадания {}, ядерка {}, блэкаут {} мс; остальное (ваниль, другие моды) {} мс",
                    ms(WINDOW[1] / n), ms(WINDOW[2]), ms(WINDOW[3] / n), ms(WINDOW[4] / n), ms(WINDOW[5] / n),
                    ms(Math.max(0, WINDOW[1] - WINDOW[3] - WINDOW[4] - WINDOW[5]) / n));
        }
        java.util.Arrays.fill(WINDOW, 0);
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1e6);
    }
}
