package ua.zentix.airstrike.nuclear;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.IcbmEntity;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.radiation.RadiationTicker;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.nuclear.world.NuclearWorld;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.AreaLoader;
import ua.zentix.airstrike.strike.FlightTickets;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.util.Local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Пуск МБР и запланированные удары (DESIGN-nuke §7): ракета стартует у запустившего (виден разгон), уходит за
 * потолок мира, дальше полёт — таймер в {@link NuclearEvents}; тревога у тех, кто рядом с целью, отсчёт у всех.
 * Здесь же подписки на события мира, которые нужны ядерной части.
 */
public final class NuclearStrikes {
    private NuclearStrikes() {}

    /**
     * Пуск. Если есть кто запускает — ракета видна: старт в 30 блоках позади него; иначе (консоль, командный блок)
     * пуск «издалека» — сразу таймер.
     */
    public static boolean launch(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable ServerPlayer owner) {
        return launch(level, target, false, yieldKt, airBurst, owner);
    }

    /** @param surface цель — место на земле (с карты): подрыв на поверхности, высота {@code target} — только оценка */
    public static boolean launch(ServerLevel level, Vec3 target, boolean surface, double yieldKt, boolean airBurst, @Nullable ServerPlayer owner) {
        return owner == null ? launchFrom(level, target, surface, yieldKt, airBurst, null, 0, null)
                : launchFrom(level, target, surface, yieldKt, airBurst, owner.position(), owner.getYRot(), owner.getUUID());
    }

    /**
     * @param launcher где стоит запустивший (ракета стартует в 30 блоках позади него по курсу {@code yaw}),
     *                 null — пуск «издалека», сразу таймер
     */
    public static boolean launchFrom(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable Vec3 launcher, float yaw,
                                     @Nullable UUID owner) {
        return launchFrom(level, target, false, yieldKt, airBurst, launcher, yaw, owner);
    }

    private static boolean launchFrom(ServerLevel level, Vec3 target, boolean surface, double yieldKt, boolean airBurst, @Nullable Vec3 launcher,
                                      float yaw, @Nullable UUID owner) {
        if (!AirstrikeConfig.SERVER.nukeEnabled.get()) return false;
        yieldKt = Math.min(yieldKt, AirstrikeConfig.SERVER.nukeMaxYield.get());
        if (launcher == null) {
            schedule(level, target, surface, yieldKt, airBurst, target, owner);
            return true;
        }
        Vec3 back = Local.horizontal(yaw).scale(-30);
        int x = Mth.floor(launcher.x + back.x), z = Mth.floor(launcher.z + back.z);
        Vec3 pad = new Vec3(x + 0.5, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
        IcbmEntity icbm = ModEntities.ICBM.get().create(level);
        if (icbm == null) return false;
        icbm.prepare(pad, target, owner);
        if (!level.addFreshEntity(icbm)) return false;
        schedule(level, target, surface, yieldKt, airBurst, pad, owner);
        return true;
    }

    /** Записать удар: момент подрыва = сейчас + время полёта; всем в измерении — тревога и отсчёт. */
    private static void schedule(ServerLevel level, Vec3 target, boolean surface, double yieldKt, boolean airBurst, Vec3 launchPos, @Nullable UUID owner) {
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        NuclearEvents.ScheduledStrike s = new NuclearEvents.ScheduledStrike(events.nextId(), target, yieldKt, airBurst, now,
                now + AirstrikeConfig.SERVER.nukeFlightTime.get(), launchPos, Optional.ofNullable(owner), surface);
        events.schedule(s);
        holdGround(level, s, true);
        Airstrike.LOG.info("МБР №{}: {} кт по {} {} {}, подрыв через {} с", s.id(), Math.round(yieldKt), Mth.floor(target.x), Mth.floor(target.y),
                Mth.floor(target.z), (s.detonateTime() - now) / 20);
        for (ServerPlayer p : level.players()) PacketDistributor.sendToPlayer(p, warning(s, p));
    }

    /** Предупреждение для конкретного игрока: тревога — если он в радиусе {@code warning_radius} от цели. */
    public static S2C.NukeWarning warning(NuclearEvents.ScheduledStrike s, ServerPlayer p) {
        double r = AirstrikeConfig.SERVER.nukeWarningRadius.get();
        boolean alarm = p.position().distanceToSqr(s.target()) <= r * r;
        boolean mine = s.owner().map(p.getUUID()::equals).orElse(false);
        return new S2C.NukeWarning(s.id(), s.target(), s.launchPos(), s.launchTime(), s.detonateTime(), s.yieldKt(), s.airBurst(), alarm, mine,
                AirstrikeConfig.SERVER.nukeEffectsScale.get().floatValue());
    }

    /**
     * Подрыв без полёта (команда «nuke now»). Если место не загружено — подрыв запланирован на сейчас и случится,
     * как только чанк догрузится (без остановки сервера на генерацию), тогда вернётся null.
     */
    @Nullable
    public static Detonation detonateNow(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable UUID owner) {
        yieldKt = Math.min(yieldKt, AirstrikeConfig.SERVER.nukeMaxYield.get());
        if (groundLoaded(level, target)) return NuclearWarhead.detonate(level, target, yieldKt, airBurst, owner);
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        events.schedule(new NuclearEvents.ScheduledStrike(events.nextId(), target, yieldKt, airBurst, now, now, target, Optional.ofNullable(owner), false));
        return null;
    }

    /** Отбой: запланированные удары отменены, следы осадков убраны, очереди остановлены. */
    public static int clear(ServerLevel level) {
        for (NuclearEvents.ScheduledStrike s : NuclearEvents.get(level).scheduled()) holdGround(level, s, false);
        int n = NuclearEvents.get(level).clear();
        NuclearWorld.get(level).clear(level);
        sync(level);
        return n;
    }

    /**
     * Если чанк так и не загрузился (мир без генерации?), подрыв всё равно происходит — по высоте цели. Столько же
     * клиент показывает «подрыв задерживается» после нуля отсчёта.
     */
    public static final int GIVE_UP_TICKS = 600;
    /**
     * Район эпицентра, который грузится с пуска, — как район цели снаряда ({@link FlightTickets#DISTANCE}): нужны 3×3
     * чанка (высота земли, грунт воронки, соседи для Sable), но очередь генерации идёт по уровню тикета, и с уровнем 32
     * (радиус 1) место не догружалось за все 90 с полёта, пока залпы держали районы целей уровня 29 (облако 29.09.2026).
     */
    private static final int GROUND_AREA = FlightTickets.DISTANCE;
    private static final TicketType<UUID> GROUND = TicketType.create("airstrike_nuclear_ground", Comparator.<UUID>naturalOrder());

    private static boolean groundLoaded(ServerLevel level, Vec3 target) {
        return Terrain.ready(level, BlockPos.containing(target));
    }

    /**
     * Место подрыва грузится с пуска (полёт МБР — полторы минуты), как район цели снаряда ({@link AreaLoader}): за 10 с
     * до подрыва тикет уровня 32 стоял в очереди генерации за районами целей залпов, и на слабом сервере отсчёт доходил
     * до нуля раньше, чем готова земля (облако 29.09.2026: подрыв ждал, пока его не отменил «Отбой»).
     * Район только грузится, но не тикает: подрыву нужна земля, а не жизнь в ней, и полторы минуты тика 7×7 чанков
     * вокруг цели были бы лишней работой. Ключ — номер удара (тикеты не сохраняются: после загрузки мира район
     * берётся заново в тике).
     */
    private static void holdGround(ServerLevel level, NuclearEvents.ScheduledStrike s, boolean hold) {
        AreaLoader.Area area = new AreaLoader.Area(GROUND, new ChunkPos(BlockPos.containing(s.target())), GROUND_AREA, new UUID(0L, s.id()), false);
        if (hold) StrikeWorld.get(level).areas().hold(level, area);
        else StrikeWorld.get(level).areas().release(level, area);
    }

    // ---------------------------------------------------------------- события мира

    /**
     * Ядерная часть всех измерений — после тика миров, под одним бюджетом на тик сервера ({@code destruction_ms_per_tick}).
     * Каждый тик первым идёт следующее измерение: первому достаётся весь бюджет (и одна единица работы — всегда),
     * так что очередь одного измерения не держит работу другого вечно.
     */
    public static void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        WorkClock clock = NuclearWorld.clock(server);
        clock.start(AirstrikeConfig.SERVER.nukeTimeBudgetMs.get() * 1_000_000L);
        List<ServerLevel> levels = new ArrayList<>();
        server.getAllLevels().forEach(levels::add);
        Collections.rotate(levels, -(server.getTickCount() % levels.size()));
        for (ServerLevel level : levels) tick(level, clock);
    }

    private static void tick(ServerLevel level, WorkClock clock) {
        long t0 = System.nanoTime();
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        List<NuclearEvents.ScheduledStrike> due = new ArrayList<>();
        for (NuclearEvents.ScheduledStrike s : events.scheduled()) {
            boolean loaded = groundLoaded(level, s.target());
            // место подрыва держится с пуска (и после загрузки мира: тикеты не сохраняются): высоту земли и грунт
            // воронки нужно знать без остановки сервера
            holdGround(level, s, true);
            if (now >= s.detonateTime() && (loaded || now - s.detonateTime() > GIVE_UP_TICKS)) due.add(s);
        }
        for (NuclearEvents.ScheduledStrike s : due) {
            events.unschedule(s);
            holdGround(level, s, false);
            Vec3 at = s.surface() ? NuclearWarhead.surfaceAt(level, s.target()) : s.target();
            NuclearWarhead.detonate(level, at, s.yieldKt(), s.airBurst(), s.owner().orElse(null));
        }
        if (now % 1200 == 0) events.prune(now);
        long t1 = System.nanoTime();
        NuclearWorld world = NuclearWorld.get(level);
        world.tick(level, clock);
        long t2 = System.nanoTime();
        RadiationTicker.tick(level);
        long t3 = System.nanoTime();
        if (t3 - t0 > SLOW_TICK_NS && world.slowLogDue(now)) {
            long[] w = world.lastNanos();
            Airstrike.LOG.warn("Ядерный тик {} мс: удары {} мс, фронт {} мс, свет {} мс, воронки {} мс, чанки {} мс, осадки у мобов {} мс, радиация {} мс",
                    (t3 - t0) / 1_000_000, (t1 - t0) / 1_000_000, w[0] / 1_000_000, w[1] / 1_000_000, w[2] / 1_000_000, w[3] / 1_000_000, w[4] / 1_000_000,
                    (t3 - t2) / 1_000_000);
        }
    }

    private static final long SLOW_TICK_NS = 50_000_000L;

    public static void onChunkLoad(ChunkEvent.Load e) {
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
            NuclearWorld.get(level).onChunkLoad(level, chunk);
        }
    }

    public static void onChunkUnload(ChunkEvent.Unload e) {
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
            NuclearWorld.get(level).onChunkUnload(chunk);
        }
    }

    /** Вход и смена измерения: действующие подрывы и летящие ракеты этого измерения. */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            sync(p);
            RadiationTicker.sync(p);
        }
    }

    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            sync(p);
            RadiationTicker.sync(p);
        }
    }

    /**
     * После смерти доза сброшена (attachment не копируется) — счётчику это нужно сказать. Возрождение может быть
     * в другом измерении (умер в Незере — встал в верхнем мире), а {@code PlayerChangedDimensionEvent} при этом
     * не приходит: подрывы и летящие МБР — тоже заново.
     */
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            sync(p);
            RadiationTicker.sync(p);
        }
    }

    private static void sync(ServerPlayer p) {
        NuclearEvents events = NuclearEvents.get(p.serverLevel());
        PacketDistributor.sendToPlayer(p, new S2C.NukeSync(List.copyOf(events.detonations()),
                events.scheduled().stream().map(s -> warning(s, p)).toList()));
    }

    private static void sync(ServerLevel level) {
        for (ServerPlayer p : level.players()) sync(p);
    }

    /** Точка на поверхности в месте цели, выше она или ниже оценки (цель с карты); чанк не готов — оценка. */

    /** Точка на земле под целью (для пуска по игроку или сущности — по их позиции). */
    public static Vec3 ground(ServerLevel level, Vec3 at) {
        BlockPos p = BlockPos.containing(at);
        if (!Terrain.ready(level, p)) return at;
        return new Vec3(at.x, Math.min(at.y, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ())), at.z);
    }
}
