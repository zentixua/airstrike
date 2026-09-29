package ua.zentix.airstrike.stress;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.strike.SalvoData;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.util.Terrain;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Нагрузочный режиссёр для выделенного сервера (только devtest, включается свойством {@code airstrike.stress}):
 * когда зайдут игроки, по расписанию пускает то, что Артём делает в игре — залпы по 30 по игрокам, точкам и аппаратам
 * в 300–1000 блоках, перенацеливание, гибель целей, дробление аппаратов, уход игрока за край загрузки, ядерку,
 * сохранение и «Отбой» посреди полёта. Каждые 5 с пишет строку {@code STRESS stat}: время тика, снаряды в мире
 * и вне мира, тикеты, память. Следит за каждым снарядом по UUID: пропал без взрыва рядом — {@code STRESS lost}.
 * Ошибки и предупреждения в логе считаются. В конце — {@code STRESS summary} и остановка сервера.
 * <p>
 * {@code airstrike.stress=run} — весь сценарий; {@code resume} — после перезапуска посреди полёта (проверка, что
 * полёты и залпы пережили остановку сервера и долетели).
 */
@Mod(Airstrike.MOD_ID)
public final class StressDirector {
    private static final String MODE = System.getProperty("airstrike.stress");
    private static final int PLAYERS = Integer.getInteger("airstrike.stress.players", 2);

    private record Step(int at, String what, Consumer<MinecraftServer> action) {}

    /** Снаряд под наблюдением: где был в прошлом тике, куда летел. */
    private static final class Watch {
        final String type;
        final UUID owner;
        Vec3 pos;
        Vec3 aim;
        boolean virtual;
        /** Полёт РСЗО вне мира сейчас растянут (темп времени траектории меньше 1). */
        boolean stretched;
        int seen;
        /** Сам объект: сущность в чанке, который не выдаётся (граница загрузки), в переборе не видна, но жива. */
        StrikeProjectile ref;

        Watch(StrikeProjectile p) {
            type = p.getType().getDescriptionId().replace("entity.airstrike.", "");
            owner = p.ownerId();
            update(p);
        }

        void update(StrikeProjectile p) {
            pos = p.position();
            aim = p.aimPoint();
            virtual = p.isVirtual();
            ref = p;
        }
    }

    private final List<Step> steps = new ArrayList<>();
    private final Map<UUID, Watch> watched = new HashMap<>();
    private final Set<UUID> cleared = new HashSet<>();
    private final Set<UUID> overdueSeen = new HashSet<>();
    private final List<Vec3> blastsThisTick = new ArrayList<>();
    private final Map<String, Integer> outcomes = new TreeMap<>();
    private final Map<String, Integer> launchedByType = new TreeMap<>();
    // подрывы, замеченные за прогон: «Отбой» и забывание убирают их из NuclearEvents, а в сводку идут все
    private final Set<String> detonationsSeen = new HashSet<>();
    private final ConcurrentLinkedQueue<String> problems = new ConcurrentLinkedQueue<>();
    private int warnings, errors;

    private int tick = -1;
    private long tickStart;
    private long windowMax, windowSum;
    private int windowTicks;
    private long worstTick;
    private int worstTickAt;
    private final List<Long> allTicks = new ArrayList<>();
    private long heapBaseline = -1;
    private boolean finishing;
    private int quietSince = -1;
    private int finishingSince = -1;
    /** Остановка ждёт, пока закончится генерация чанков: с какого тика и сколько тиков подряд её нет. */
    private int settlingSince = -1;
    private int settleQuiet;
    /** Сколько тиков подряд без генерации — «улеглось», и сколько ждать самое большее. */
    private static final int SETTLE_QUIET = 100, SETTLE_MAX = 6000;
    /** С какого возраста снаряд попадает в строки «долго летит» (раз в 10 с). */
    private static final int LONG_LIVED = 2400;
    /** Начало текущего тика для сторожа (поток сторожа читает). */
    private volatile long watchdogTickStart;

    public StressDirector(IEventBus modBus) {
        if (MODE == null) return;
        NeoForge.EVENT_BUS.addListener(this::onStarted);
        NeoForge.EVENT_BUS.addListener(this::onTickPre);
        NeoForge.EVENT_BUS.addListener(this::onTickPost);
        NeoForge.EVENT_BUS.addListener(this::onExplosion);
        NeoForge.EVENT_BUS.addListener(this::onLeave);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        NeoForge.EVENT_BUS.addListener(this::onStopping);
        LogWatch.install(problems, () -> warnings++, () -> errors++);
        if ("resume".equals(MODE)) planResume();
        else plan();
    }

    // ---------------------------------------------------------------- сценарий

    private void at(int t, String what, Consumer<MinecraftServer> action) {
        steps.add(new Step(t, what, action));
    }

    /** Команда от имени игрока (как если бы он её набрал): пусковая встаёт у него, снаряды — его. */
    private void as(int t, String player, String command) {
        at(t, player + ": /" + command, s -> {
            ServerPlayer p = need(s, player, "/" + command);
            if (p == null) return;
            run(s, p.createCommandSourceStack().withPermission(4), command);
        });
    }

    private void plan() {
        at(0, "подготовка", s -> {
            run(s, "gamerule doDaylightCycle false");
            run(s, "gamerule doWeatherCycle false");
            run(s, "gamerule doMobSpawning false");
            run(s, "gamerule sendCommandFeedback false");
            run(s, "time set 6000");
            for (ServerPlayer p : s.getPlayerList().getPlayers()) s.getPlayerList().op(p.getGameProfile());
            place(s, "Host", 0, 0);
            place(s, "Friend1", 480, 320);
            place(s, "Friend2", -620, 420);
        });
        // волна 1: залпы по 30 — РСЗО по игроку, шахеды по точке в 800 блоках, барраж по игроку, ракеты по игроку
        as(300, "Host", "airstrike salvo rocket 30 150 Friend1");
        as(320, "Host", "airstrike salvo drone 30 150 at 800 ~ -200");
        as(340, "Friend1", "airstrike salvo loiter 30 150 Friend2");
        as(360, "Friend2", "airstrike salvo missile 20 80 Friend1");
        // аппарат Sable у второго друга: по нему ракеты, потом его дробит
        at(500, "аппарат у Friend2", s -> buildCraft(s, "Friend2"));
        at(560, "ракеты и шахеды по аппарату", s -> strikeCraft(s, "Host", 10, 30));
        // цели, которые гибнут на подлёте: жители в 900 блоках, половину убиваем
        at(700, "жители-цели у Friend2", s -> villagers(s, "Host", "Friend2", 8));
        at(1100, "гибель целей", s -> killHalfVillagers(s));
        // перенацеливание всего, что в мире, — каждые 150 тиков несколько снарядов
        for (int t = 800; t <= 3000; t += 150) at(t, "перенацеливание", this::retargetSome);
        // игрок уходит за край загрузки посреди удара по нему и возвращается
        at(1300, "Friend1 улетает на 3000 блоков", s -> tp(s, "Friend1", 3480, 320));
        at(1900, "Friend1 возвращается", s -> tp(s, "Friend1", 480, 320));
        // бомбардировщики и ракеты в другой мир и по игроку в полёте
        as(1500, "Friend2", "airstrike salvo bunker 6 60 Host");
        at(1700, "Friend2 в Незер и обратно", s -> run(s, "execute in minecraft:the_nether run tp Friend2 0 80 0"));
        at(2100, "Friend2 из Незера", s -> tp(s, "Friend2", -620, 420));
        // ядерка в 1000 блоках, пока идут залпы: полёт МБР 1800 тиков — подрыв на 3000, до «Отбоя» (он отменяет и её)
        as(1200, "Host", "airstrike nuke at 0 ~ -1000 15 air");
        as(2320, "Friend1", "airstrike salvo drone 30 150 Host");
        as(2340, "Host", "airstrike salvo rocket 30 150 Friend2");
        // Friend1 выходит посреди удара и возвращается (клиент — через 300 своих тиков); выход — по команде режиссёра,
        // а не по часам клиента: иначе он попадал на шаг, где Friend1 пускает сам (VPS 29.09.2026: залп ракет пропущен)
        at(2400, "Friend1 выходит посреди удара", s -> leave(s, "Friend1"));
        at(2600, "сохранение мира посреди полёта", s -> run(s, "save-all"));
        // «Отбой» посреди волны 2, затем волна 3
        at(3200, "Отбой", s -> {
            markCleared(s);
            as(s, "Host", "airstrike clear");
        });
        as(3300, "Host", "airstrike salvo loiter 30 150 at 600 ~ 600");
        as(3320, "Friend1", "airstrike salvo missile 30 150 Friend2");
        as(3340, "Friend2", "airstrike salvo rocket 30 150 Host");
        at(3400, "Friend1 выходит посреди удара", s -> leave(s, "Friend1"));
        // волна 4 — для перезапуска: остановка сервера посреди полёта, продолжение — режим resume
        if (Boolean.getBoolean("airstrike.stress.restart")) {
            as(4600, "Host", "airstrike salvo drone 20 100 at 700 ~ 700");
            as(4620, "Host", "airstrike salvo missile 10 100 at -700 ~ 700");
            at(5200, "остановка посреди полёта", s -> {
                summary(s, "restart");
                s.halt(false);
            });
        } else {
            at(4200, "ждём, пока всё долетит", s -> finishing = true);
        }
    }

    private void planResume() {
        at(0, "после перезапуска", s -> {
            int virt = 0, salvos = 0;
            for (ServerLevel l : s.getAllLevels()) {
                virt += VirtualFlights.get(l).flights().size();
                salvos += SalvoData.get(l).size();
            }
            log("resume: вне мира %d, залпов %d", virt, salvos);
        });
        at(40, "ждём, пока всё долетит", s -> finishing = true);
    }

    // ---------------------------------------------------------------- действия

    private void place(MinecraftServer s, String name, int x, int z) {
        tp(s, name, x, z);
    }

    /** Игрок для шага; нет его — шаг пропущен, и это проблема в сводке (стенд не проходит с меньшим числом шагов). */
    @Nullable
    private ServerPlayer need(MinecraftServer s, String name, String what) {
        ServerPlayer p = s.getPlayerList().getPlayerByName(name);
        if (p == null) {
            log("skip %s: нет игрока %s", what, name);
            problems.add("шаг пропущен, нет игрока " + name + ": " + what);
        }
        return p;
    }

    /** Строка, по которой клиент стенда выходит из игры сам, как игрок посреди удара ({@link StressClient}). */
    static final String LEAVE = "airstrike-stress: leave";

    private void leave(MinecraftServer s, String name) {
        ServerPlayer p = need(s, name, "выход");
        if (p == null) return;
        p.sendSystemMessage(Component.literal(LEAVE));
        log("%s выходит", name);
    }

    private void tp(MinecraftServer s, String name, int x, int z) {
        ServerPlayer p = need(s, name, "телепорт");
        if (p == null) return;
        ServerLevel level = s.overworld();
        // без загрузки чанка ради высоты: сначала высоко, потом игра сама опустит в полёте творческого режима
        p.teleportTo(level, x + 0.5, 200, z + 0.5, p.getYRot(), 0);
        log("tp %s → %d %d", name, x, z);
    }

    /**
     * Аппарат Sable в 24 блоках от игрока, на земле: игрок летает на высоте 200, и аппарат, собранный в воздухе,
     * падал к удару на десятки блоков. Ищется потом по своему UUID, а не по месту.
     */
    private void buildCraft(MinecraftServer s, String near) {
        ServerPlayer p = need(s, near, "аппарат");
        if (p == null) return;
        ServerLevel level = p.serverLevel();
        BlockPos at = p.blockPosition().offset(24, 0, 0);
        // fill и Sable грузили бы неготовые чанки прямо в тике (стенд сам вставал на десятки секунд): ждём готовых
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!Terrain.ready(level, (at.getX() >> 4) + dx, (at.getZ() >> 4) + dz)) {
                    log("чанки под аппаратом ещё грузятся — через секунду");
                    at(tick + 20, "аппарат у " + near + " (повтор)", sv -> buildCraft(sv, near));
                    return;
                }
            }
        }
        BlockPos c = new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()) + 1, at.getZ());
        run(s, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_planks", c.getX() - 4, c.getY(), c.getZ() - 3, c.getX() + 4, c.getY() + 2, c.getZ() + 3));
        run(s, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glass", c.getX() - 2, c.getY() + 3, c.getZ() - 1, c.getX() + 2, c.getY() + 3, c.getZ() + 1));
        run(s, s.createCommandSourceStack(), String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", c.getX() - 4, c.getY(), c.getZ() - 3, c.getX() + 4, c.getY() + 3, c.getZ() + 3));
        craftCenter = Vec3.atCenterOf(c);
        findCraft(s, 0);
    }

    /** UUID собранного аппарата: Sable может достроить его не в том же тике — ищем до секунды. */
    private void findCraft(MinecraftServer s, int attempt) {
        var subs = ua.zentix.airstrike.compat.SubLevels.near(s.overworld(), craftCenter, 8);
        if (!subs.isEmpty()) {
            craftId = subs.get(0).getUniqueId();
            log("аппарат %s собран у %.0f %.0f %.0f", craftId, craftCenter.x, craftCenter.y, craftCenter.z);
        } else if (attempt < 20) {
            at(tick + 1, "поиск аппарата", sv -> findCraft(sv, attempt + 1));
        } else {
            problems.add(String.format(Locale.ROOT, "аппарат не собрался у %.0f %.0f %.0f", craftCenter.x, craftCenter.y, craftCenter.z));
        }
    }

    private Vec3 craftCenter;
    @Nullable
    private UUID craftId;

    private void strikeCraft(MinecraftServer s, String shooter, int missiles, int drones) {
        ServerPlayer p = need(s, shooter, "удар по аппарату");
        if (p == null) return;
        if (craftId == null) {
            problems.add("шаг пропущен: удар по аппарату, аппарат не построен");
            return;
        }
        var sub = ua.zentix.airstrike.compat.SubLevels.byId(s.overworld(), craftCenter, craftId);
        if (sub == null) {
            problems.add("шаг пропущен: удар по аппарату, аппарата " + craftId + " больше нет");
            return;
        }
        Vec3 c = ua.zentix.airstrike.compat.SubLevels.center(sub);
        log("аппарат %s у %.0f %.0f %.0f", craftId, c.x, c.y, c.z);
        var aim = new ua.zentix.airstrike.strike.ServerActions.Aim(new Target.OfSubLevel(ua.zentix.airstrike.compat.SubLevels.toPlot(sub, c)), c, null);
        ua.zentix.airstrike.strike.ServerActions.strike(p, ua.zentix.airstrike.strike.WeaponType.MISSILE, missiles, 6, aim,
                ua.zentix.airstrike.strike.Loadout.Nuke.DEFAULT);
        ua.zentix.airstrike.strike.ServerActions.strike(p, ua.zentix.airstrike.strike.WeaponType.DRONE, drones, 10, aim,
                ua.zentix.airstrike.strike.Loadout.Nuke.DEFAULT);
    }

    private final List<UUID> villagers = new ArrayList<>();

    private void villagers(MinecraftServer s, String shooter, String near, int n) {
        ServerLevel level = s.overworld();
        ServerPlayer p = need(s, shooter, "жители-цели");
        ServerPlayer host = need(s, near, "жители-цели");
        if (host == null) return;
        Vec3 at = host.position().add(-40, 0, 30);
        for (int i = 0; i < n; i++) {
            BlockPos pos = BlockPos.containing(at.add(i * 12, 0, 0));
                        Entity e = EntityType.VILLAGER.create(level);
            if (e == null) continue;
            if (!ua.zentix.airstrike.util.Terrain.ready(level, pos)) continue; // чанк ради цели не грузим
            e.moveTo(pos.getX() + 0.5, ua.zentix.airstrike.util.Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()), pos.getZ() + 0.5);
            level.addFreshEntity(e);
            villagers.add(e.getUUID());
            if (p != null) run(s, p.createCommandSourceStack().withPermission(4), "airstrike drone " + e.getStringUUID());
        }
        log("жителей-целей: %d", villagers.size());
    }

    private void killHalfVillagers(MinecraftServer s) {
        int k = 0;
        for (int i = 0; i < villagers.size(); i += 2) {
            Entity e = s.overworld().getEntity(villagers.get(i));
            if (e != null) {
                e.kill();
                k++;
            }
        }
        log("убито целей: %d из %d", k, villagers.size());
    }

    private void retargetSome(MinecraftServer s) {
        ServerLevel level = s.overworld();
        List<StrikeProjectile> inWorld = new ArrayList<>(level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> true));
        int n = 0;
        for (int i = 0; i < inWorld.size() && n < 4; i += 3) {
            StrikeProjectile p = inWorld.get(i);
            List<ServerPlayer> players = s.getPlayerList().getPlayers();
            boolean atPlayer = level.random.nextBoolean() && !players.isEmpty();
            Target t;
            Vec3 point;
            if (atPlayer) {
                ServerPlayer v = players.get(level.random.nextInt(players.size()));
                if (v.level() != level) continue;
                t = new Target.OfEntity(v.getUUID(), new Vec3(0, 1, 0));
                point = v.position().add(0, 1, 0);
            } else {
                point = p.position().add(level.random.nextInt(401) - 200, 0, level.random.nextInt(401) - 200);
                BlockPos col = BlockPos.containing(point);
                int y = Terrain.ready(level, col) ? Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, col.getX(), col.getZ()) : 70;
                point = new Vec3(point.x, y, point.z);
                t = new Target.Point(point);
            }
            if (p.retarget(t, point)) n++;
        }
        log("перенацелено: %d из %d в мире", n, inWorld.size());
    }

    private void markCleared(MinecraftServer s) {
        cleared.addAll(watched.keySet());
    }

    // ---------------------------------------------------------------- наблюдение

    private void onStarted(ServerStartedEvent e) {
        log("сервер запущен, режим %s, ждём игроков: %d", MODE, PLAYERS);
        startWatchdog(e.getServer());
    }

    private void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        log("вход: %s (игроков %d)", e.getEntity().getGameProfile().getName(), e.getEntity().getServer().getPlayerCount());
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        log("выход: %s", e.getEntity().getGameProfile().getName());
    }

    /** Как снаряд ушёл из мира (причина удаления, фаза, возраст) — для разбора пропавших. */
    private final Map<UUID, String> leftHow = new HashMap<>();

    private void onLeave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof StrikeProjectile p)) return;
        leftHow.put(p.getUUID(), p.getRemovalReason() + " фаза " + p.flightPhase().getSerializedName() + " возраст " + p.age()
                + " тикает " + ((ServerLevel) e.getLevel()).isPositionEntityTicking(p.blockPosition()));
    }

    private void onExplosion(ExplosionEvent.Start e) {
        blastsThisTick.add(e.getExplosion().center());
    }

    private void onTickPre(ServerTickEvent.Pre e) {
        tickStart = System.nanoTime();
        watchdogTickStart = tickStart;
    }

    /**
     * Сторож: тик идёт дольше 0,5 с — стек потока сервера в лог (потом раз в 2 с, до 5 снимков на остановку). Так видно,
     * кто держит тик: ванильная загрузка чанков, мод или сам стенд.
     */
    private void startWatchdog(MinecraftServer s) {
        Thread server = s.getRunningThread();
        Thread dog = new Thread(() -> {
            long dumpedFor = 0;
            int dumps = 0;
            while (s.isRunning()) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ex) {
                    return;
                }
                long start = watchdogTickStart;
                long ms = (System.nanoTime() - start) / 1_000_000;
                // первый стек — на 500 мс (чей долгий тик: наш, Sable или телепорт), дальше — каждые 2 с
                if (start == 0 || ms < 500) continue;
                if (dumpedFor != start) {
                    dumpedFor = start;
                    dumps = 0;
                }
                if (dumps >= 5 || ms < (dumps == 0 ? 500 : 2000L * dumps)) continue;
                dumps++;
                StringBuilder sb = new StringBuilder();
                StackTraceElement[] st = server.getStackTrace();
                for (int i = 0; i < Math.min(60, st.length); i++) sb.append("\n    at ").append(st[i]);
                Airstrike.LOG.info("STRESS тик идёт {} мс, стек сервера:{}", ms, sb);
            }
        }, "airstrike-stress-watchdog");
        dog.setDaemon(true);
        dog.start();
    }

    private void onTickPost(ServerTickEvent.Post e) {
        MinecraftServer s = e.getServer();
        long took = System.nanoTime() - tickStart;
        if (tick < 0) {
            if (s.getPlayerCount() < PLAYERS) return;
            log("все игроки на месте, начинаем");
        }
        tick++;
        windowSum += took;
        windowTicks++;
        windowMax = Math.max(windowMax, took);
        allTicks.add(took);
        if (took > worstTick) {
            worstTick = took;
            worstTickAt = tick;
        }
        if (took > 250_000_000L) log("долгий тик %d мс", took / 1_000_000);
        for (Step st : List.copyOf(steps)) {
            if (st.at == tick) {
                log("шаг: %s", st.what);
                try {
                    st.action.accept(s);
                } catch (RuntimeException ex) {
                    problems.add("шаг «" + st.what + "» упал: " + ex);
                    Airstrike.LOG.error("STRESS шаг {} упал", st.what, ex);
                }
            }
        }
        track(s);
        if (tick % 100 == 0) stat(s);
        if (tick % 200 == 0) {
            // долгожители: по этим строкам видно, кружит снаряд, ждёт района цели или летит далеко
            for (var en : watched.entrySet()) if (en.getValue().ref.age() >= LONG_LIVED) describe("долго летит", en.getKey(), en.getValue());
        }
        if (settlingSince >= 0) settle(s);
        else if (finishing) finishWhenQuiet(s);
    }

    private void seeDetonations(MinecraftServer s) {
        for (ServerLevel l : s.getAllLevels())
            for (var d : NuclearEvents.get(l).detonations()) detonationsSeen.add(l.dimension().location() + "#" + d.id());
    }

    /** Все снаряды по UUID: новые, живые (в мире или вне его), пропавшие — со взрывом рядом или без. */
    private void track(MinecraftServer s) {
        seeDetonations(s);
        Map<UUID, StrikeProjectile> now = new LinkedHashMap<>();
        for (ServerLevel level : s.getAllLevels()) {
            for (StrikeProjectile p : level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> !p.isRemoved())) now.put(p.getUUID(), p);
            for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
                if (!p.isRemoved() && now.put(p.getUUID(), p) != null) problems.add("дубль UUID в мире и вне мира: " + p.getUUID());
            }
        }
        for (Map.Entry<UUID, StrikeProjectile> en : now.entrySet()) {
            Watch w = watched.get(en.getKey());
            if (w == null) {
                w = new Watch(en.getValue());
                watched.put(en.getKey(), w);
                launchedByType.merge(w.type, 1, Integer::sum);
            } else {
                w.update(en.getValue());
            }
            w.seen = tick;
            // путь бетонобойных бомб целиком и всех снарядов вне мира: вне мира пропадали бомба (в 375 блоках от цели)
            // и ракеты РСЗО (под миром) — VPS, 29.09.2026
            // растяжение полёта РСЗО вне мира (район цели не готов): начало и конец — отдельной строкой
            if (en.getValue() instanceof RocketEntity r) {
                boolean stretched = r.timeRate() < 0.999;
                if (stretched != w.stretched) {
                    log("растяжение %s %s: %s, темп %.2f, у %d %d %d, до цели %.0f", w.type, en.getKey(), stretched ? "началось" : "кончилось",
                            r.timeRate(), (int) w.pos.x, (int) w.pos.y, (int) w.pos.z, w.pos.distanceTo(w.aim));
                    w.stretched = stretched;
                }
            }
            if ((w.virtual || "bunker_buster".equals(w.type)) && tick % 10 == 0) {
                log("путь %s %s: %d %d %d, вне мира %b, фаза %s, возраст %d, до цели %.0f по горизонтали, %.0f по высоте",
                        w.type, en.getKey(), (int) w.pos.x, (int) w.pos.y, (int) w.pos.z, w.virtual, w.ref.flightPhase().getSerializedName(), w.ref.age(),
                        Math.hypot(w.aim.x - w.pos.x, w.aim.z - w.pos.z), w.pos.y - w.aim.y);
            }
        }
        for (var it = watched.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Watch w = en.getValue();
            if (w.seen == tick) continue;
            if (!w.ref.isRemoved()) {
                if (tick - w.seen == 200) log("снаряд %s %s не виден в переборе 200 тиков, но не убран (у %d %d %d)", w.type, en.getKey(),
                        (int) w.pos.x, (int) w.pos.y, (int) w.pos.z);
                continue;
            }
            it.remove();
            String how = leftHow.remove(en.getKey());
            String outcome;
            if (cleared.contains(en.getKey())) outcome = "cleared";
            else if (blastsThisTick.stream().anyMatch(b -> b.distanceToSqr(w.pos) < 48 * 48)) outcome = "impact";
            else if ("bomber".equals(w.type)) outcome = "bomber-gone";
            // МБР — только разгон: над небом она убирается сама, удар дальше ведёт NuclearStrikes по таймеру
            else if ("icbm".equals(w.type) && w.pos.y > s.overworld().getMaxBuildHeight()) outcome = "boost-done";
            else {
                outcome = "lost";
                // конец — по самому объекту: снаряд, убранный вне мира (срок жизни), не шлёт события ухода, и «ушёл»
                // тогда говорит о его последнем уходе из мира в полёт вне мира, раньше конца
                log("lost %s %s у %d %d %d (цель %d %d %d, вне мира %b, конец: %s фаза %s возраст %d; последний уход из мира: %s)",
                        w.type, en.getKey(), (int) w.pos.x, (int) w.pos.y, (int) w.pos.z, (int) w.aim.x, (int) w.aim.y, (int) w.aim.z, w.virtual,
                        w.ref.getRemovalReason(), w.ref.flightPhase().getSerializedName(), w.ref.age(), how);
            }
            outcomes.merge(w.type + ":" + outcome, 1, Integer::sum);
        }
        blastsThisTick.clear();
    }

    private void stat(MinecraftServer s) {
        int inWorld = 0, virt = 0, salvos = 0, nukes = 0;
        for (ServerLevel l : s.getAllLevels()) {
            inWorld += l.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> true).size();
            virt += VirtualFlights.get(l).flights().size();
            salvos += SalvoData.get(l).size();
            nukes += NuclearEvents.get(l).detonations().size();
        }
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        log("stat t=%d mspt avg %.1f max %.1f | в мире %d вне %d залпов %d подрывов %d | тикеты %s | чанков %d | heap %d МБ | warn %d err %d",
                tick, windowSum / 1e6 / Math.max(1, windowTicks), windowMax / 1e6, inWorld, virt, salvos, nukes, tickets(s.overworld()),
                s.overworld().getChunkSource().getLoadedChunksCount(), used, warnings, errors);
        windowSum = windowMax = 0;
        windowTicks = 0;
    }

    /** Тикеты мода в верхнем мире по типам (утечка — тикеты, которые остаются, когда всё долетело). */
    private static String tickets(ServerLevel level) {
        Map<String, Integer> byType = new TreeMap<>();
        try {
            Field dm = net.minecraft.server.level.ChunkMap.class.getDeclaredField("distanceManager");
            dm.setAccessible(true);
            DistanceManager d = (DistanceManager) dm.get(level.getChunkSource().chunkMap);
            Field tf = DistanceManager.class.getDeclaredField("tickets");
            tf.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>>) tf.get(d);
            for (SortedArraySet<Ticket<?>> set : map.values()) {
                for (Ticket<?> t : set) {
                    String type = t.getType().toString();
                    if (type.contains("airstrike") || type.contains("neoforge") || type.contains("forced")) byType.merge(type, 1, Integer::sum);
                }
            }
        } catch (ReflectiveOperationException ex) {
            return "?";
        }
        return byType.toString();
    }

    /** Срок жизни снаряда вышел (ожидание района цели в него не входит), а он всё ещё летит. */
    private static boolean overdue(StrikeProjectile p) {
        CompoundTag tag = new CompoundTag();
        p.saveWithoutId(tag);
        int lifetime = tag.getInt("lifetime");
        return lifetime > 0 && p.age() - tag.getInt("area_wait") > lifetime + 20;
    }

    /** Состояние снаряда: срок жизни, погоня и ожидание района цели — из NBT, этого снаружи больше нигде не видно. */
    private void describe(String what, UUID id, Watch w) {
        StrikeProjectile p = w.ref;
        CompoundTag tag = new CompoundTag();
        p.saveWithoutId(tag);
        log("%s %s %s у %s фаза %s (%d тиков) возраст %d срок %d погоня %.0f ждал района %d цель %s у %s вне мира %b убран %s тикает %b",
                what, w.type, id, p.blockPosition().toShortString(), p.flightPhase().getSerializedName(), tag.getInt("phase_age"),
                p.age(), tag.getInt("lifetime"), tag.getCompound("tracker").getDouble("chased"), tag.getInt("area_wait"), p.target(),
                BlockPos.containing(p.aimPoint()).toShortString(), p.isVirtual(), p.getRemovalReason(),
                p.level() instanceof ServerLevel l && l.isPositionEntityTicking(p.blockPosition()));
    }

    private void finishWhenQuiet(MinecraftServer s) {
        int active = watched.size();
        for (ServerLevel l : s.getAllLevels()) {
            active += SalvoData.get(l).size();
        }
        if (active > 0) {
            quietSince = -1;
            if (finishingSince < 0) finishingSince = tick;
            // снаряд может честно лететь дольше окна (поздний пуск из залпа, ожидание района цели, погоня за целью),
            // провал — только просроченный: живой после своего срока жизни; или всё окно вышло целиком
            boolean overdue = false;
            for (var en : watched.entrySet()) {
                if (!overdue(en.getValue().ref)) continue;
                overdue = true;
                if (overdueSeen.add(en.getKey())) {
                    describe("просрочен", en.getKey(), en.getValue());
                    problems.add("снаряд " + en.getValue().type + " " + en.getKey() + " жив после срока жизни");
                }
            }
            int waited = tick - finishingSince;
            if (waited > 3600 && overdue || waited > 12000) {
                log("не долетели за отведённое время: %d", active);
                for (var en : watched.entrySet()) describe("  остался", en.getKey(), en.getValue());
                summary(s, "timeout");
                stopWhenSettled(s);
            }
            return;
        }
        if (quietSince < 0) quietSince = tick;
        // дать доиграть таймлайнам и ядерным очередям, потом посмотреть на тикеты и память
        if (tick - quietSince == 400) {
            System.gc();
            summary(s, "done");
            stopWhenSettled(s);
        }
    }

    /**
     * Остановить сервер, когда закончится генерация чанков. Ванильная остановка (1.21.1) снимает тикеты и выгружает
     * чанки в {@code ChunkMap.processUnloads} с {@code hasMoreTime = () -> true}: выгрузка чанка, на который ещё
     * держит ссылку генерация соседа ({@code generationRefCount > 0}), тут же ставит себя в очередь выгрузки снова,
     * и цикл по этой очереди не кончается. Поток сервера крутится в нём и не доходит до задач, которыми генерация
     * закончилась бы, — остановка висит без конца (VPS и облако 29.09.2026: 4272 чанка со ссылками генерации,
     * в очереди потока сервера 756). Поэтому сперва игроки выходят, и сервер останавливается, когда
     * {@link #SETTLE_QUIET} тиков подряд ни у одного чанка нет ссылок генерации.
     */
    private void stopWhenSettled(MinecraftServer s) {
        settlingSince = tick;
        for (ServerPlayer p : List.copyOf(s.getPlayerList().getPlayers())) p.connection.disconnect(Component.literal("Стенд закончен"));
        log("остановка: игроки отключены, ждём конца генерации чанков");
    }

    private void settle(MinecraftServer s) {
        int generating = 0;
        for (ServerLevel l : s.getAllLevels()) {
            for (ChunkHolder holder : l.getChunkSource().chunkMap.getChunks()) if (holder.getGenerationRefCount() > 0) generating++;
        }
        settleQuiet = generating == 0 ? settleQuiet + 1 : 0;
        int waited = tick - settlingSince;
        if (settleQuiet >= SETTLE_QUIET || waited >= SETTLE_MAX) {
            if (generating == 0) log("генерация чанков закончилась, остановка через %d тиков", waited);
            else log("генерация чанков не закончилась за %d тиков (у %d чанков), остановка может зависнуть", waited, generating);
            s.halt(false);
        }
    }

    private void summary(MinecraftServer s, String why) {
        List<Long> sorted = new ArrayList<>(allTicks);
        sorted.sort(null);
        long p50 = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        long p99 = sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.99)));
        Runtime rt = Runtime.getRuntime();
        seeDetonations(s);
        int nukes = detonationsSeen.size();
        // МБР пускали, а подрыва нет: удар потерян (или отменён раньше срока — тогда расписание стенда неверно)
        if (nukes == 0 && launchedByType.containsKey("icbm")) problems.add("МБР пущена, а ядерного подрыва нет");
        log("summary %s: тиков %d, mspt p50 %.1f p99 %.1f худший %.0f на t=%d | запущено %s | итоги %s | ядерных подрывов %d | в полёте %d | тикеты %s | heap %d МБ | warn %d err %d",
                why, tick, p50 / 1e6, p99 / 1e6, worstTick / 1e6, worstTickAt, launchedByType, outcomes, nukes, watched.size(), tickets(s.overworld()),
                (rt.totalMemory() - rt.freeMemory()) >> 20, warnings, errors);
        for (String p : problems) log("problem: %s", p);
    }

    private void onStopping(ServerStoppingEvent e) {
        log("сервер останавливается");
    }

    // ---------------------------------------------------------------- служебное

    private static void run(MinecraftServer s, String command) {
        run(s, s.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private static void run(MinecraftServer s, CommandSourceStack src, String command) {
        s.getCommands().performPrefixedCommand(src, command);
    }

    private void as(MinecraftServer s, String player, String command) {
        ServerPlayer p = need(s, player, "/" + command);
        if (p != null) run(s, p.createCommandSourceStack().withPermission(4), command);
    }

    private static void log(String fmt, Object... args) {
        Airstrike.LOG.info("STRESS " + String.format(Locale.ROOT, fmt, args));
    }
}
