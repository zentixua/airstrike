package ua.zentix.airstrike.strike;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.work.UnitQueue;
import ua.zentix.airstrike.work.WorkScheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private final AreaLoader areas = new AreaLoader();
    private final ImpactCost impactCost = new ImpactCost();
    private final FlightLog flightLog = new FlightLog();
    /** Единицы работы попаданий — полоса {@link WorkScheduler.Lane#IMPACT} общего бюджета тика. */
    private final UnitQueue impacts = new UnitQueue();
    /** Районы полос подлёта ({@link FlightTickets#holdApproach}): центр → снаряды, чьи полосы через него проходят. */
    private final Map<ChunkPos, Set<UUID>> approach = new HashMap<>();
    /**
     * Отказы в месте пусковой у игрока в нынешнем приказе ({@link StrikeService}): «игрок оружие» → чанк, где места
     * не нашлось. Новый приказ (одиночный удар, первый снаряд залпа) ищет заново, ушедший с того чанка — тоже; строка
     * игроку — раз на приказ.
     */
    private final Map<String, Long> noLaunchSite = new HashMap<>();

    /** Для {@link ModAttachments#STRIKE_WORLD}: своё у каждого мира, живёт, пока мир загружен, не сохраняется. */
    public StrikeWorld() {}

    public static StrikeWorld get(ServerLevel level) {
        return level.getData(ModAttachments.STRIKE_WORLD);
    }

    /** Районы, которые мод грузит заранее: районы целей и взрывов, чанки снарядов, подсказки карты, ядерный удар. */
    public AreaLoader areas() {
        return areas;
    }

    /** Районы полос подлёта и снаряды, которые их держат ({@link FlightTickets}). */
    Map<ChunkPos, Set<UUID>> approach() {
        return approach;
    }

    /** Сколько потока сервера заняли попадания в этом тике (строка в лог о медленном). */
    public ImpactCost impactCost() {
        return impactCost;
    }

    /**
     * Работа попаданий (взрывы, стёкла, обломки): таймлайн в свой тик ставит сюда единицы, а делает их планировщик
     * ({@link WorkScheduler}) под общим бюджетом тика — в том же тике, если очередь пуста.
     */
    public UnitQueue impacts() {
        return impacts;
    }

    /** Полоса попаданий в этом мире: единицы, пока часы разрешают. */
    public void workImpacts(ServerLevel level, WorkClock clock) {
        if (impacts.isEmpty()) return;
        long t0 = System.nanoTime();
        impacts.work(level, clock);
        impactCost.step(System.nanoTime() - t0);
    }

    /** Конец тика сервера для попаданий этого мира: медленный тик и долгая очередь — в лог. */
    public void endImpactTick(ServerLevel level) {
        impactCost.endTick(level, impacts);
    }

    /** Место пусковой у игрока в этом приказе на этом чанке уже не нашлось: остаток приказа — издалека. */
    boolean noLaunchSite(UUID player, WeaponType weapon, ChunkPos at) {
        Long chunk = noLaunchSite.get(noSiteKey(player, weapon));
        return chunk != null && chunk == at.toLong();
    }

    /** Места пусковой нет; true — впервые в этом приказе (строка игроку). */
    boolean rememberNoLaunchSite(UUID player, WeaponType weapon, ChunkPos at) {
        return noLaunchSite.put(noSiteKey(player, weapon), at.toLong()) == null;
    }

    /** Новый приказ игрока этим оружием: место пусковой ищется заново. */
    void newOrder(UUID player, WeaponType weapon) {
        noLaunchSite.remove(noSiteKey(player, weapon));
    }

    private static String noSiteKey(UUID player, WeaponType weapon) {
        return player + " " + weapon.getSerializedName();
    }

    /** Концы полётов не по плану за этот тик: в лог — в конце тика мира. */
    public FlightLog flightLog() {
        return flightLog;
    }

    /** Добавить таймлайн; первый тик — в конце текущего тика мира. */
    public void add(Timeline timeline) {
        pending.add(timeline);
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.players().isEmpty() && busy(level)) level.resetEmptyTime();
        // загрузка районов — не симуляция: идёт и в замороженном мире (трейлер ждёт прогрузки плана)
        if (level.hasData(ModAttachments.STRIKE_WORLD)) get(level).areas.tick(level);
        // «/tick freeze» останавливает сущности — снаряды вне мира, залпы и взрывы стоят вместе с ними
        if (!level.tickRateManager().runsNormally()) return;
        SalvoData.get(level).tick(level);
        VirtualFlights.get(level).tick(level);
        if (level.hasData(ModAttachments.STRIKE_WORLD)) {
            StrikeWorld world = get(level);
            world.tick(level);
            world.flightLog.flush();
        }
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
        return timelines.isEmpty() && pending.isEmpty() && impacts.isEmpty();
    }

    private void tick(ServerLevel level) {
        timelines.addAll(pending);
        pending.clear();
        long t0 = System.nanoTime();
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
        impactCost.step(System.nanoTime() - t0);
    }

    /**
     * Убрать всё без работы: таймлайны и очередь попаданий отпускают свои районы. Для проверок, которые изображают
     * остановку сервера ({@code WorkGameTests.stopFinishesQueue}): их остатки не должны доставаться следующим.
     */
    public void dropAll(ServerLevel level) {
        timelines.addAll(pending);
        pending.clear();
        List<Timeline> all = new ArrayList<>(timelines);
        timelines.clear();
        for (Timeline t : all) t.end(level);
        impacts.clear(level);
    }

    /** Все снаряды мира: в мире и вне его ({@link VirtualFlights}). */
    public static List<StrikeProjectile> projectiles(ServerLevel level) {
        List<StrikeProjectile> all = new ArrayList<>(level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> !p.isRemoved()));
        all.addAll(VirtualFlights.get(level).flights());
        return all;
    }

    /**
     * Сколько снарядов игрока в работе во всех мирах: в полёте (в мире и вне его) и ещё не выпущенных в залпах —
     * для предела {@code max_active_per_player}.
     */
    public static int active(MinecraftServer server, UUID owner) {
        int n = 0;
        for (ServerLevel level : server.getAllLevels()) {
            n += SalvoData.get(level).remaining(owner);
            for (StrikeProjectile p : projectiles(level)) {
                if (owner.equals(p.ownerId())) n++;
            }
        }
        return n;
    }

    /** Отбой: взрывы в процессе доигрываются (это уже случилось), залпы отменяются. */
    public static void clearSalvos(ServerLevel level) {
        SalvoData.get(level).clear();
    }

    /**
     * Сервер останавливается (в том числе «Сохранить и выйти» в одиночной игре): снаряды в мире уходят в полёт вне
     * мира и сохраняются с ним, а после запуска летят дальше сами — иначе они ждали бы в файлах чанков, пока кто-то
     * не придёт туда снова, и тикеты района цели после запуска были бы потеряны. Очередь попаданий доделывается
     * до этого, без бюджета ({@link UnitQueue#finish(ServerLevel)}).
     */
    public static void onServerStopping(ServerStoppingEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            // работа попаданий не сохраняется: начатое и поставленное доделать сейчас, иначе в мире остались бы взрывы,
            // снятые наполовину (таймлайны, которые ещё не поставили свои подрывы, теряются, как раньше)
            if (level.hasData(ModAttachments.STRIKE_WORLD)) get(level).impacts.finish(level);
            for (StrikeProjectile p : level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> !p.isRemoved())) {
                p.parkForShutdown(level);
            }
            releaseAreas(level);
        }
    }

    /**
     * Остановка: отпустить все районы мода в мире (цели, полосы подлёта, вход игрока, подсказки карты, взрывы).
     * Они не сохраняются — после запуска их берут заново владельцы (снаряды вне мира, игроки), — а взятые при остановке
     * запускали бы генерацию, которую ждёт {@code util/StopDrain} (после залпа — дольше его предела).
     */
    public static void releaseAreas(ServerLevel level) {
        if (level.hasData(ModAttachments.STRIKE_WORLD)) get(level).areas.releaseAll(level);
    }
}
