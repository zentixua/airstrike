package ua.zentix.airstrike.stress;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.decoration.ArmorStand;
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
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.BunkerBusterEntity;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.strike.SalvoData;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.warhead.Warheads;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
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
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    /** Залпы-пробы РСЗО по свежим районам ({@link #probe}); {@code false} — без них (замер A/B остановок сервера). */
    private static final boolean PROBES = flag("airstrike.stress.probes", true);
    /** Волна 4 и остановка сервера посреди полёта (продолжение — режим {@code resume}). */
    private static final boolean RESTART = flag("airstrike.stress.restart", false);
    /** Район, который стенд грузит под телепорт игрока (ключ — игрок). */
    private static final TicketType<UUID> TELEPORT = TicketType.create("airstrike_stress_teleport", Comparator.<UUID>naturalOrder());
    /** Сколько ждать района телепорта, пока это не стало проблемой в сводке, и сколько самое большее держать его после. */
    private static final int TELEPORT_WAIT = 1200, TELEPORT_HOLD = 100;
    /**
     * Радиус района телепорта в чанках: дистанция симуляции сервера + 2. Чанки игрока тикают блоками, как только
     * полностью загружены, не дожидаясь соседей ({@code DistanceManager.tickingTicketsTracker}): тикет симуляции
     * игрока — уровня {@code 31 − sim}, блоки тикают до уровня 32, то есть в радиусе {@code sim + 1}. Хранилище
     * испытаний или улей у края читает соседний чанк, который ещё генерируется, — синхронно: у свежего места игрока
     * сервер стоял 10–12 с (облако 29.09.2026, «остановка:» без тикетов мода рядом). Это ваниль, а стенд меряет
     * остановки мода, поэтому игрок переносится в район, где готовы все тикающие блоками чанки и их соседи.
     * Уровень тикета {@code 33 − радиус}: квадрат грузится полностью и в очереди генерации идёт впереди районов целей
     * (уровни 29–33); с уровнем 33 телепорт ждал 2353 тика, и сценарий шёл без игроков на своих местах.
     */
    public static int teleportArea(MinecraftServer s) {
        return s.getPlayerList().getSimulationDistance() + 2;
    }

    private record Step(int at, String what, Consumer<MinecraftServer> action) {}

    /** Снаряд под наблюдением: где был в прошлом тике, куда летел. */
    private static final class Watch {
        final String type;
        final UUID owner;
        /** С ядерной БЧ (ракета, бомба): рвётся подрывом в {@code NuclearEvents}, а не ванильным взрывом. */
        final boolean nuclear;
        Vec3 pos;
        Vec3 aim;
        boolean virtual;
        /** Полёт РСЗО вне мира сейчас растянут (темп времени траектории меньше 1). */
        boolean stretched;
        /** Залп-проба растяжения, к которому относится снаряд РСЗО (null — не проба). */
        @Nullable
        Probe probe;
        /** Проба пути вне мира до поверхности, к которой относится снаряд (null — не проба). */
        @Nullable
        GroundProbe ground;
        int seen;
        /** Сам объект: сущность в чанке, который не выдаётся (граница загрузки), в переборе не видна, но жива. */
        StrikeProjectile ref;

        Watch(StrikeProjectile p) {
            type = p.getType().getDescriptionId().replace("entity.airstrike.", "");
            owner = p.ownerId();
            nuclear = p.isNuclear();
            update(p);
        }

        void update(StrikeProjectile p) {
            pos = p.position();
            aim = p.aimPoint();
            virtual = p.isVirtual();
            ref = p;
        }
    }

    /**
     * Залп РСЗО по свежему, ни разу не сгенерированному району (растяжение полёта вне мира, #104): снаряды — по точке
     * цели ближе {@link #PROBE_RADIUS}; счёт начал и концов растяжения и итогов — в сводку.
     */
    private static final class Probe {
        final String name;
        final Vec3 center;
        /** Замысел: полёт короче загрузки района (растяжение будет) или длиннее. Судит сводка — по готовности района. */
        final boolean shortFlight;
        final ServerLevel level;
        final int fired;
        int launched, stretchOn, stretchOff;
        /** Через сколько тиков после пуска чанк цели готов и в нём тикают сущности (-1 — ещё нет). */
        int readyAfter = -1;
        /** Расчётное прибытие первого снаряда без растяжения, в тиках после пуска. */
        int arrival = Integer.MAX_VALUE;
        /**
         * Самый низкий темп полёта среди снарядов пробы и сколько снарядов опускались ниже {@link #DEEP}: растяжение
         * из-за неготового района глубокое, а сервер, вставший на секунды (ванильная синхронная загрузка), даёт
         * строки с темпом 0,99 в любом залпе.
         */
        double minRate = 1;
        final Set<UUID> deep = new HashSet<>();
        final Map<String, Integer> outcomes = new TreeMap<>();

        Probe(String name, Vec3 center, boolean shortFlight, ServerLevel level, int fired) {
            this.name = name;
            this.center = center;
            this.shortFlight = shortFlight;
            this.level = level;
            this.fired = fired;
        }
    }

    /** Разброс залпа-пробы и радиус, по которому снаряд относится к пробе (по точке цели). */
    private static final int PROBE_SPREAD = 20, PROBE_RADIUS = 60;
    /** Запас к окну растяжения ({@link RocketEntity#STRETCH_TICKS} до черты у цели): черта — за десяток тиков до прибытия. */
    private static final int PROBE_SLACK = 30;
    /** Темп ниже — растяжение из-за района цели, а не из-за отставания сервера. */
    private static final double DEEP = 0.5;
    private final List<Probe> probes = new ArrayList<>();

    /**
     * Проба пути вне мира до поверхности (#112): снаряды вне мира вдали от игроков, в районах, которые никто не грузит,
     * — как в GameTest #112. Снаряды относятся к пробе по точке цели ближе {@link #PROBE_RADIUS}; в сводку — сколько
     * дошли до поверхности вне мира и как далеко от цели по горизонтали, сколько не дождались загрузки, итоги.
     */
    private static final class GroundProbe {
        final String name;
        /** Как пущены снаряды пробы — в строку сводки, чтобы итог не прочли как путь боевого пуска. */
        final String how;
        /** Управляемый снаряд (ракета) или прицельный (РСЗО): до поверхности он может дойти только у цели. */
        final boolean steering;
        /** Бомба на точку позади: путь до поверхности и должен проверяться. */
        final boolean mustGround;
        Vec3 center;
        /**
         * Сколько снарядов проба пускает: пущенных меньше — часть пуска потерялась до перебора стенда (0 — не сверяется:
         * у B-2 в пробе и он сам, и его бомба, которую считает {@code bombs}).
         */
        final int ordered;
        int launched;
        /** Снаряды пробы, которых застали вне мира: без них путь вне мира не проверен. */
        final Set<UUID> flewVirtual = new HashSet<>();
        /** Бомбы, сброшенные в воздухе сразу вне мира на точку позади (bunker-behind): путь — по курсу от точки сброса. */
        boolean dropBehind;
        /** Где снаряды дошли до поверхности вне мира и их цель: x, z, цель x, цель z. */
        final List<double[]> groundAt = new ArrayList<>();
        /** При подъёме цели ракета шла ниже пола, который подъём поставил выше неё (как в GameTest #112). */
        boolean underFloor;
        final List<Double> groundOff = new ArrayList<>();
        int gaveUp;
        final Map<String, Integer> outcomes = new TreeMap<>();
        /** Цель, которая поднимается посреди полёта (null — неподвижная точка). */
        @Nullable
        ArmorStand stand;
        @Nullable
        ChunkPos forced;
        /** Тик постановки пробы: сколько грузился чанк стойки. */
        int setupTick;
        boolean raised;
        /** B-2 с боевого пуска, перенацеленный вне мира на точку позади себя (#107/#108): заход снова и сброс. */
        boolean b2Behind;
        boolean retargeted;
        int bombs;
        /** Попадания бомб: от цели по горизонтали (ранний сброс или сброс не с эшелона — бомба далеко от точки). */
        final List<Double> impactOff = new ArrayList<>();

        GroundProbe(String name, String how, Vec3 center, int ordered, boolean steering, boolean mustGround) {
            this.name = name;
            this.ordered = ordered;
            this.how = how;
            this.center = center;
            this.steering = steering;
            this.mustGround = mustGround;
        }
    }

    private final List<GroundProbe> groundProbes = new ArrayList<>();
    /** Строки мода из лога (поток логера): разбираются в тике стенда. */
    private final ConcurrentLinkedQueue<String> modLines = new ConcurrentLinkedQueue<>();
    private static final Pattern GROUNDED = Pattern.compile(
            "Снаряд \\S+ ([0-9a-f-]{36}) вне мира дошёл до поверхности у BlockPos\\{x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\}, цель BlockPos\\{x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\}");
    private static final Pattern GAVE_UP = Pattern.compile("Снаряд \\S+ ([0-9a-f-]{36}) не дождался загрузки");
    /** Дальше от цели по горизонтали управляемый снаряд до поверхности не доходит — иначе путь кончился не там. */
    private static final double GROUND_OFF_LIMIT = 32;

    private final List<Step> steps = new ArrayList<>();
    /** Ожидания, которые проверяются каждый тик (телепорт ждёт район), до {@code true}. */
    private final List<BooleanSupplier> waits = new ArrayList<>();
    /** Игроки, чей телепорт ещё ждёт район. */
    private final Set<String> pendingTeleports = new HashSet<>();
    /** Телепорт по сценарию: тик сценария, когда его попросили, и как его начать. */
    private record TpRequest(int requestedAt, Runnable start) {}
    /** Телепорты игрока, которые ещё не начались (ждут прибытия прежнего или своего тика), по порядку сценария. */
    private final Map<String, ArrayDeque<TpRequest>> deferredTeleports = new HashMap<>();
    /** Последний телепорт игрока, который его перенёс: тик сценария, когда его попросили, и когда перенёс. */
    private record LastTeleport(int requestedAt, int landedAt) {}
    private final Map<String, LastTeleport> lastTeleports = new HashMap<>();
    /** Тикеты районов телепорта, которые ещё стоят: стенд кончается, только когда их нет. */
    private int teleportTickets;
    /** Как отпустить каждый стоящий тикет района телепорта (ключ — его ключ): сводка отпускает оставшиеся. */
    private final Map<UUID, Runnable> teleportReleases = new HashMap<>();
    /** Сводка напечатана: новые телепорты не начинаются, ожидания сняты. */
    private boolean closed;
    private final Map<UUID, Watch> watched = new HashMap<>();
    private final Set<UUID> cleared = new HashSet<>();
    private final Set<UUID> overdueSeen = new HashSet<>();
    /** Взрывы этого тика по снаряду, чей это взрыв ({@link #blastBy}): первый у каждого снаряда. */
    private final Map<UUID, Vec3> blastsThisTick = new HashMap<>();
    private final Map<String, Integer> outcomes = new TreeMap<>();
    private final Map<String, Integer> launchedByType = new TreeMap<>();
    // подрывы, замеченные за прогон: «Отбой» и забывание убирают их из NuclearEvents, а в сводку идут все
    private final Set<String> detonationsSeen = new HashSet<>();
    private final ConcurrentLinkedQueue<String> problems = new ConcurrentLinkedQueue<>();
    private int warnings, errors;

    private int tick = -1;
    /** С какого тика сервера часы сценария ждут, пока игроки встанут на места ({@code MAX_VALUE} — больше не ждут). */
    private int placingSince = -1;
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
    /** Сервер внутри тика (между {@code ServerTickEvent.Pre} и {@code Post}), а не ждёт следующего. */
    private volatile boolean inTick;

    public StressDirector(IEventBus modBus) {
        if (MODE == null) return;
        checkCollector();
        // поле источника урона взрыва ищется сейчас: не нашлось — стенд не стартует, а не падает на первом взрыве
        ExplosionSource.init();
        NeoForge.EVENT_BUS.addListener(this::onStarted);
        NeoForge.EVENT_BUS.addListener(this::onTickPre);
        NeoForge.EVENT_BUS.addListener(this::onTickPost);
        NeoForge.EVENT_BUS.addListener(this::onExplosion);
        NeoForge.EVENT_BUS.addListener(this::onLeave);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        NeoForge.EVENT_BUS.addListener(this::onStopping);
        LogWatch.install(problems, () -> warnings++, () -> errors++, modLines::add);
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

    /** Ожидания этого тика; новые, добавленные из них, проверяются со следующего. Открыт для проверок. */
    public void runWaits() {
        for (BooleanSupplier w : List.copyOf(waits)) if (w.getAsBoolean()) waits.remove(w);
    }

    /** Сборщики мусора JVM (имена MXBean: «G1 Young Generation», «ZGC Major Cycles»…). */
    private static String collectors() {
        return String.join(", ", ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).toList());
    }

    /**
     * Сборщик и куча, которые заказал {@code build.gradle} ({@code AIRSTRIKE_RIG_JVM=artem} → {@code airstrike.stress.gc=ZGC Minor},
     * {@code airstrike.stress.heap=8192}), — те, с которыми JVM и работает: иначе прогон молча мерил бы другую JVM (VPS
     * 29.09.2026: прогон с G1 тулчейна вместо ZGC). Сборщик — по началу имени MXBean: «ZGC Minor» есть только
     * у поколенческого ZGC (у прежнего — «ZGC Cycles»).
     */
    private static void checkCollector() {
        String want = System.getProperty("airstrike.stress.gc");
        if (want != null && !want.isBlank() && ManagementFactory.getGarbageCollectorMXBeans().stream().noneMatch(b -> b.getName().startsWith(want)))
            throw new IllegalStateException("стенд: заказан сборщик " + want + ", а JVM работает с " + collectors());
        Integer heapMb = Integer.getInteger("airstrike.stress.heap");
        long maxMb = Runtime.getRuntime().maxMemory() >> 20;
        if (heapMb != null && maxMb < heapMb)
            throw new IllegalStateException("стенд: заказана куча " + heapMb + " МБ, а JVM работает с " + maxMb + " МБ");
    }

    /** Выключатель стенда из переменной окружения (через {@code build.gradle}): true/1/yes или false/0/no. */
    private static boolean flag(String property, boolean fallback) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) return fallback;
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException("стенд: " + property + "=" + v + " — ожидается true/false или 1/0");
        };
    }

    private void plan() {
        at(0, "подготовка", s -> {
            run(s, "gamerule doDaylightCycle false");
            run(s, "gamerule doWeatherCycle false");
            run(s, "gamerule doMobSpawning false");
            run(s, "gamerule sendCommandFeedback false");
            run(s, "time set 6000");
            for (ServerPlayer p : s.getPlayerList().getPlayers()) s.getPlayerList().op(p.getGameProfile());
            // стенд меряет нагрузку ударов, а не разведку: пульт Host бьёт по друзьям за сотни блоков, не видя их
            ua.zentix.airstrike.AirstrikeConfig.SERVER.sightRules.set(false);
            place(s, "Host", 0, 0);
            place(s, "Friend1", 480, 320);
            place(s, "Friend2", -620, 420);
        });
        // РСЗО по свежему району в 1500 блоках, вдали от игроков, до волны 1: полёт (~400 тиков) длиннее загрузки района
        // (в одиночку 80–120 тиков, облако, 4 ядра) — глубокого растяжения быть не должно, мелкое допустимо (сервер
        // встал на секунды). Следом за волной 1 проба мерила очередь генерации, а не растяжение: десятки свежих районов
        // волны впереди, район готов через ~360 тиков на VPS и ~900 в облаке (29.09.2026)
        if (PROBES) at(200, "РСЗО по свежему району в 1500 блоках", s -> probe(s, "Host", "fresh-1500", -1060, -1060, false));
        // волна 1: залпы по 30 — РСЗО по игроку, шахеды по точке в 800 блоках, барраж по игроку, ракеты по игроку
        as(300, "Host", "airstrike salvo rocket 30 150 Friend1");
        as(320, "Host", "airstrike salvo drone 30 150 at 800 ~ -200");
        as(340, "Friend1", "airstrike salvo loiter 30 150 Friend2");
        as(360, "Friend2", "airstrike salvo missile 20 80 Friend1");
        // РСЗО по свежему району в 250 блоках: полёт короче загрузки района — растяжение ожидается глубокое
        if (PROBES) at(380, "РСЗО по свежему району в 250 блоках", s -> probe(s, "Host", "fresh-250", -180, -175, true));
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
        at(1700, "Friend2 в Незер и обратно", s -> tp(s, "Friend2", s.getLevel(Level.NETHER), 0, 80, 0));
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
        // путь вне мира до поверхности (#112) вдали от всех, в районах, которые никто не грузит, — после «Отбоя» (он убрал
        // бы их в полёте): бомба на точку позади, сброшенная сразу вне мира (без B-2), РСЗО и ракеты по цели ниже рельефа (y 40)
        // из-под рельефа над путём, ракеты по цели, которая посреди полёта вне мира поднимается на 40 блоков. Не при
        // перезапуске: остановка посреди полёта (t=5200) застала бы пробы недосмотренными
        if (!RESTART) {
            at(3500, "бомбы на точку позади, вне мира", s -> groundBombs(s, "bunker-behind", -2200, 2200, 3));
            at(3520, "РСЗО по цели ниже рельефа", s -> groundDeep(s, "deep-rocket", WeaponType.ROCKET, 10, 1100, -1300));
            at(3540, "ракеты по цели ниже рельефа", s -> groundDeep(s, "deep-missile", WeaponType.MISSILE, 5, -1600, -300));
            at(3560, "ракеты по цели, которая поднимется", s -> groundRising(s, "rising-aim", 1500, 1500));
            at(3580, "B-2 с перенацеливанием на точку позади, вне мира", s -> groundB2Behind(s, "b2-behind", 2600, -2400));
        }
        // волна 4 — для перезапуска: остановка сервера посреди полёта, продолжение — режим resume
        if (RESTART) {
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

    /** Телепорт в верхний мир на высоту 200: высота без загрузки чанка, дальше игрок в творческом режиме летит. */
    private void tp(MinecraftServer s, String name, int x, int z) {
        tp(s, name, s.overworld(), x, 200, z);
    }

    /**
     * Телепорт игрока, как у игры с загрузкой в фоне: сначала район ({@link #teleportArea}) грузится (тикет загрузки,
     * без тика: {@code DistanceManager.addTicket} не трогает счёт тика, и блок-сущности района не тикают рядом
     * с неготовыми соседями), игрок переносится, когда все готовы. Сразу в неготовый район
     * {@code teleportTo} грузил чанки синхронно прямо в тике — 12 из 18 остановок сервера на 2–8 с на VPS 29.09.2026
     * были самого стенда. Телепорт игрока начинается через столько тиков после прибытия прежнего, сколько сценарий
     * отводил между ними, а пока прежний ждёт район, ждёт и он ({@link #nextTeleport}): отменённый прежний оставлял
     * игрока без места (облако: возврат из дальнего полёта и из Незера отменял сам полёт), начатый сразу превращал
     * полёт или Незер в один тик, а оба сразу дождались бы района в одном тике, и игрок оказался бы там, куда его
     * послали раньше.
     */
    private void tp(MinecraftServer s, String name, @Nullable ServerLevel level, int x, int y, int z) {
        if (closed) return;
        ServerPlayer p = need(s, name, "телепорт");
        if (p == null) return;
        if (level == null) {
            problems.add("шаг пропущен: телепорт " + name + ", мира назначения нет");
            return;
        }
        ArrayDeque<TpRequest> queue = deferredTeleports.computeIfAbsent(name, k -> new ArrayDeque<>());
        int requestedAt = tick;
        queue.add(new TpRequest(requestedAt, () -> begin(s, name, level, x, y, z, requestedAt)));
        if (queue.size() == 1 && !pendingTeleports.contains(name)) nextTeleport(name);
        else log("tp %s → %d %d: в очереди за прежним телепортом", name, x, z);
    }

    /**
     * Первый отложенный телепорт игрока — через столько тиков после прибытия прежнего, сколько сценарий отводил между
     * ними (не раньше, чем его попросили). До начала он остаётся в очереди: следующий телепорт встаёт за ним, а не
     * начинается сам.
     */
    private void nextTeleport(String name) {
        ArrayDeque<TpRequest> queue = deferredTeleports.get(name);
        TpRequest next = queue == null ? null : queue.peek();
        if (next == null) return;
        LastTeleport last = lastTeleports.get(name);
        int start = last == null ? tick : Math.max(tick, last.landedAt() + next.requestedAt() - last.requestedAt());
        Runnable run = () -> {
            queue.poll();
            if (tick > next.requestedAt()) {
                log("tp %s начинается на %d тиков позже сценария: прежний телепорт ждал район", name, tick - next.requestedAt());
            }
            next.start().run();
        };
        // шаг в этом тике уже не выполнится (шаги тика перебираются по копии): сейчас — сразу, иначе — шагом
        if (start <= tick) run.run();
        else at(start, "отложенный телепорт " + name, sv -> run.run());
    }

    /** Начать телепорт: район грузится, игрок переносится, когда он готов. Открыт для проверок. */
    public void begin(MinecraftServer s, String name, ServerLevel level, int x, int y, int z, int requestedAt) {
        if (closed) return;
        ChunkPos centre = new ChunkPos(x >> 4, z >> 4);
        DistanceManager tickets = level.getChunkSource().chunkMap.getDistanceManager();
        UUID key = UUID.randomUUID();
        int area = teleportArea(s), ticketLevel = ChunkLevel.byStatus(FullChunkStatus.FULL) - area;
        tickets.addTicket(TELEPORT, centre, ticketLevel, key);
        teleportTickets++;
        Runnable release = () -> {
            if (teleportReleases.remove(key) == null) return;
            tickets.removeTicket(TELEPORT, centre, ticketLevel, key);
            teleportTickets--;
        };
        teleportReleases.put(key, release);
        // тики сервера, а не сценария: пока игроки встают на места, часы сценария стоят
        int since = s.getTickCount();
        // с какого тика сервера игрока нет в игре (-1 — он в игре)
        int[] offlineSince = {-1};
        pendingTeleports.add(name);
        log("tp %s → %s %d %d: грузим район", name, level.dimension().location(), x, z);
        waits.add(() -> {
            boolean ready = areaReady(level, centre, area);
            // не предел: телепорт ждёт дальше (и держит тикет), но в сводке это проблема
            if (!ready && s.getTickCount() - since == TELEPORT_WAIT) {
                log("tp %s → %d %d: район не готов за %d тиков, ждём дальше", name, x, z, TELEPORT_WAIT);
                problems.add(String.format(Locale.ROOT, "телепорт %s ждёт район %d %d дольше %d тиков", name, x, z, TELEPORT_WAIT));
            }
            // игрока нет — вышел: выход идёт по часам сценария, а телепорт мог сдвинуться за прежним и выпасть на время,
            // пока он вне игры (VPS 29.09.2026, 68eda03: возврат Friend1 отложен на 505 тиков и пришёлся на его выход —
            // «шаг пропущен»). Телепорт ждёт его входа, район держит; вход игрока стенда — сам, через 300 тиков клиента
            ServerPlayer now = s.getPlayerList().getPlayerByName(name);
            if (now == null) {
                if (offlineSince[0] < 0) {
                    offlineSince[0] = s.getTickCount();
                    log("tp %s → %d %d: игрока нет в игре, ждём его входа", name, x, z);
                } else if (s.getTickCount() - offlineSince[0] == TELEPORT_WAIT) {
                    problems.add(String.format(Locale.ROOT, "телепорт %s ждёт входа игрока дольше %d тиков", name, TELEPORT_WAIT));
                }
                return false;
            }
            offlineSince[0] = -1;
            if (!ready) return false;
            pendingTeleports.remove(name);
            now.teleportTo(level, x + 0.5, y, z + 0.5, now.getYRot(), 0);
            LastTeleport last = lastTeleports.get(name);
            log("tp %s → %d %d, район ждали %d тиков%s", name, x, z, s.getTickCount() - since, last == null ? ""
                : String.format(Locale.ROOT, "; на прежнем месте %d тиков, по сценарию %d", tick - last.landedAt(), requestedAt - last.requestedAt()));
            lastTeleports.put(name, new LastTeleport(requestedAt, tick));
            nextTeleport(name);
            // дальше место держат тикеты самого игрока: наш отпускается, когда они стоят на квадрате вокруг чанка, где игрок
            // сейчас (ChunkMap.move — после подтверждения телепорта клиентом, и тикеты игрока встают по нескольку за тик,
            // DistanceManager.ticketThrottler: отпущенный по одному центру квадрат терял полную загрузку по краям), — или
            // через TELEPORT_HOLD, если игрок ушёл из района (в другом мире, улетел дальше). Вышедшего район ждёт до входа,
            // и отсчёт идёт с входа: отпущенный за ним район выгружался, и тик вошедшего грузил свой чанк синхронно за
            // очередью генерации залпа (облако 30.09.2026: Friend1 вышел через 1 с после переноса — остановка 26 с). Тикеты
            // игрока — по дальности обзора сервера (ChunkMap.setServerViewDistance → updatePlayerTickets). Отпустить
            // раньше — игрок в неготовом районе: isInWall в его тике грузит чанк синхронно. Покрытие — вокруг игрока,
            // а не центра: друзья стенда летают и за тики уходят из центрального чанка (fix-e, облако 29.09.2026: у них
            // все районы отпущены через TELEPORT_HOLD «без тикета игрока», у стоящего Host — через 6 тиков)
            int placed = s.getTickCount();
            // с какого тика сервера считать срок: перенос или вход игрока, вышедшего после него
            int[] from = {placed};
            boolean[] away = {false};
            waits.add(() -> {
                ServerPlayer there = s.getPlayerList().getPlayerByName(name);
                if (there == null) {
                    if (!away[0]) log("район телепорта %s держится, пока игрок не войдёт", name);
                    away[0] = true;
                    from[0] = s.getTickCount();
                    return false;
                }
                away[0] = false;
                boolean inArea = there.serverLevel() == level && there.chunkPosition().getChessboardDistance(centre) <= area;
                boolean arrived = inArea && playerTicketsCover(level, there.chunkPosition(), Math.min(area, s.getPlayerList().getViewDistance()));
                // игрок в районе ждёт своих тикетов (до TELEPORT_WAIT), ушедший — не дольше TELEPORT_HOLD
                if (!arrived && s.getTickCount() - from[0] < (inArea ? TELEPORT_WAIT : TELEPORT_HOLD)) return false;
                release.run();
                log("район телепорта %s отпущен через %d тиков%s", name, s.getTickCount() - placed, arrived ? "" : ", тикета игрока на нём нет");
                return true;
            });
            return true;
        });
    }

    /**
     * Сводка: стоящие тикеты районов телепорта отпустить, ожидания и отложенные телепорты снять — тикет в строке сводки
     * был бы утечкой, а ожидание добавило бы проблему после неё (игрок, который так и не вошёл: упал клиент, отключён
     * по таймауту). Новые телепорты после этого не начинаются. Сколько тикетов отпущено. Открыт для проверок.
     */
    public int closeTeleports() {
        closed = true;
        int left = teleportReleases.size();
        for (Runnable release : List.copyOf(teleportReleases.values())) release.run();
        waits.clear();
        deferredTeleports.clear();
        pendingTeleports.clear();
        return left;
    }

    /** Стоящих тикетов районов телепорта (проверки). */
    public int teleportTicketCount() {
        return teleportTickets;
    }

    /** Проблемы прогона (проверки). */
    public List<String> problemList() {
        return List.copyOf(problems);
    }

    /**
     * На каждом чанке квадрата {@code radius} вокруг {@code centre} стоит тикет игрока ({@code TicketType.PLAYER}, в дальности
     * обзора): район держит сам игрок.
     */
    private static boolean playerTicketsCover(ServerLevel level, ChunkPos centre, int radius) {
        var map = ticketMap(level.getChunkSource().chunkMap.getDistanceManager());
        if (map == null) return false;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                var set = map.get(ChunkPos.asLong(centre.x + dx, centre.z + dz));
                if (set == null || set.stream().noneMatch(t -> t.getType() == TicketType.PLAYER)) return false;
            }
        }
        return true;
    }

    private static boolean areaReady(ServerLevel level, ChunkPos centre, int area) {
        for (int dx = -area; dx <= area; dx++) {
            for (int dz = -area; dz <= area; dz++) if (!Terrain.ready(level, centre.x + dx, centre.z + dz)) return false;
        }
        return true;
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
        ua.zentix.airstrike.strike.ServerActions.strike(p, false, ua.zentix.airstrike.strike.WeaponType.MISSILE, missiles, 6, aim,
                ua.zentix.airstrike.strike.Loadout.Nuke.DEFAULT, ua.zentix.airstrike.strike.Waypoints.NONE);
        ua.zentix.airstrike.strike.ServerActions.strike(p, false, ua.zentix.airstrike.strike.WeaponType.DRONE, drones, 10, aim,
                ua.zentix.airstrike.strike.Loadout.Nuke.DEFAULT, ua.zentix.airstrike.strike.Waypoints.NONE);
    }

    /**
     * Залп РСЗО из 10 по точке на земле (x, z): высота — без загрузки чанка ({@link Target.Ground#at}), район свежий,
     * если рядом никто не был. Снаряды относятся к пробе по точке цели.
     */
    private void probe(MinecraftServer s, String shooter, String name, int x, int z, boolean shortFlight) {
        ServerPlayer p = need(s, shooter, "залп-проба " + name);
        if (p == null) return;
        ServerLevel level = p.serverLevel();
        // «свежий» — чанк цели не готов (ещё не сгенерирован или не загружен): тогда снаряд ждёт район; держатель чанка
        // в памяти есть и далеко за дальностью прорисовки, по нему не судить
        boolean fresh = !Terrain.ready(level, x >> 4, z >> 4);
        Target.Ground ground = Target.Ground.at(level, x, z);
        Vec3 point = ground.pos();
        probes.add(new Probe(name, point, shortFlight, level, tick));
        log("проба %s: РСЗО 10 по %d %d %d, %.0f блоков от %s, район %s", name, x, (int) point.y, z, Math.hypot(x - p.getX(), z - p.getZ()),
                shooter, fresh ? "не готов" : "уже готов");
        if (!fresh) problems.add("проба " + name + ": район уже готов — растяжение не проверено");
        ua.zentix.airstrike.strike.ServerActions.strike(p, false, ua.zentix.airstrike.strike.WeaponType.ROCKET, 10, PROBE_SPREAD,
                new ua.zentix.airstrike.strike.ServerActions.Aim(ground, point, null), ua.zentix.airstrike.strike.Loadout.Nuke.DEFAULT,
                ua.zentix.airstrike.strike.Waypoints.NONE);
    }

    /** Бомбы сразу вне мира (без B-2: сброс {@code drop} в воздухе) на точку в 300 блоках позади и на 150 ниже (как GameTest virtualMissNeverFallsBelowGround). */
    private void groundBombs(MinecraftServer s, String name, int x, int z, int count) {
        ServerLevel level = s.overworld();
        Vec3 behind = null;
        for (int i = 0; i < count; i++) {
            Vec3 from = new Vec3(x + 48 * i + 0.5, level.getSeaLevel() + 200, z + 0.5);
            Vec3 aim = from.add(0, -150, -300);
            if (behind == null) behind = aim;
            BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
            if (bomb == null) continue;
            bomb.drop(from, 0, aim, null, null);
            VirtualFlights.launch(level, bomb);
        }
        if (behind != null) {
            GroundProbe pr = new GroundProbe(name, "бомба сразу вне мира, без B-2 и его захода; баллистика, не управляемая",
                    behind.add(48 * (count - 1) / 2.0, 0, 0), count, false, true);
            pr.dropBehind = true;
            groundProbes.add(pr);
        }
        log("проба %s: %d бомб вне мира у %d %d на точку позади", name, count, x, z);
    }

    /** Залп издалека (без игрока: пуск вне мира) по точке на y 40 — ниже рельефа, над путём рельеф. */
    private void groundDeep(MinecraftServer s, String name, WeaponType weapon, int count, int x, int z) {
        ServerLevel level = s.overworld();
        Vec3 aim = new Vec3(x + 0.5, 40, z + 0.5);
        groundProbes.add(new GroundProbe(name, "боевой пуск издалека (dispatch)", aim, count, true, false));
        log("проба %s: %s %d по %d 40 %d (рельеф над целью %d), район %s", name, weapon.name().toLowerCase(Locale.ROOT), count, x, z,
                (int) Target.Ground.at(level, x, z).pos().y, Terrain.ready(level, x >> 4, z >> 4) ? "уже готов" : "не готов");
        ua.zentix.airstrike.strike.ServerActions.dispatch(level, "стенд " + name, 0, weapon, count, 8,
                new ua.zentix.airstrike.strike.ServerActions.Aim(new Target.Point(aim), aim, null), ua.zentix.airstrike.strike.Loadout.Nuke.DEFAULT);
    }

    /** Держит чанк стойки пробы: уровень 31 — сущности тикают (как принудительная загрузка), грузится в фоне. */
    private static final TicketType<ChunkPos> PROBE_TICKET = TicketType.create("airstrike_stress_probe", Comparator.comparingLong(ChunkPos::toLong));

    /** Высота стойки пробы rising-aim: ниже моря, как цель ниже рельефа у проб deep. */
    private static final int RISING_AIM_Y = 40;
    /** Ракет по стойке пробы rising-aim. */
    private static final int RISING_SHOTS = 3;

    /**
     * Ракеты издалека по стойке (цель-сущность) в своём принудительно загруженном чанке: стойка ставится, когда чанк
     * готов (без синхронной загрузки), на y {@link #RISING_AIM_Y} — ниже моря, и ракета вне мира идёт ниже уровня моря
     * (пол полёта над неготовыми чанками); когда ракета ещё вне мира, далеко от стойки, та поднимается на 40 блоков
     * и пол встаёт выше ракеты — как GameTest virtualMissileClimbsAfterRisingAim.
     */
    private void groundRising(MinecraftServer s, String name, int x, int z) {
        ServerLevel level = s.overworld();
        GroundProbe pr = new GroundProbe(name, "боевой пуск издалека (dispatch) по стойке", new Vec3(x + 0.5, level.getSeaLevel(), z + 0.5), RISING_SHOTS, true, false);
        pr.forced = new ChunkPos(x >> 4, z >> 4);
        pr.setupTick = tick;
        // тикет в фоне, как у района цели: setChunkForced грузит свежий чанк сразу (VPS 29.09.2026: тик 8 с)
        level.getChunkSource().addRegionTicket(PROBE_TICKET, pr.forced, 2, pr.forced);
        groundProbes.add(pr);
        log("проба %s: цель-стойка у %d %d, ждём загрузки её чанка", name, x, z);
    }

    /**
     * B-2 боевым пуском издалека (вне мира, вдали от игроков); когда он вне мира в 400–1000 блоках от точки, его
     * перенацеливают на точку в 60 блоках позади себя, ближе дальности сброса (как GameTest
     * bomberRetargetedJustBehindHitsOnReattack): он уходит, заходит снова и сбрасывает — бомба у точки, не раньше.
     */
    private void groundB2Behind(MinecraftServer s, String name, int x, int z) {
        ServerLevel level = s.overworld();
        Target.Ground aim = Target.Ground.at(level, x + 0.5, z + 0.5);
        GroundProbe pr = new GroundProbe(name, "боевой пуск B-2 издалека (dispatch), перенацеливание вне мира на 60 блоков позади", aim.pos(), 0, true, false);
        pr.b2Behind = true;
        groundProbes.add(pr);
        log("проба %s: B-2 по %d %d %d", name, x, (int) aim.pos().y, z);
        ua.zentix.airstrike.strike.ServerActions.dispatch(level, "стенд " + name, 0, WeaponType.BUNKER, 1, 0,
                new ua.zentix.airstrike.strike.ServerActions.Aim(aim, aim.pos(), null), ua.zentix.airstrike.strike.Loadout.Nuke.DEFAULT);
    }

    /** Стойка пробы — когда её чанк готов; подъём — когда ракета вне мира в 300–1000 блоках от неё. */
    private void tickGroundProbes(MinecraftServer s) {
        ServerLevel level = s.overworld();
        for (GroundProbe pr : groundProbes) {
            if (pr.b2Behind && !pr.retargeted) retargetBehind(level, pr);
            if (pr.forced == null) continue;
            BlockPos c = BlockPos.containing(pr.center);
            if (pr.stand == null && Terrain.ready(level, c) && level.isPositionEntityTicking(c)) {
                ArmorStand stand = EntityType.ARMOR_STAND.create(level);
                if (stand == null) continue;
                int y = RISING_AIM_Y;
                stand.setNoGravity(true);
                stand.moveTo(pr.center.x, y, pr.center.z);
                level.addFreshEntity(stand);
                pr.stand = stand;
                pr.center = stand.position();
                log("проба %s: стойка у %d %d %d (чанк грузился %d тиков), пуск %d ракет издалека", pr.name, c.getX(), y, c.getZ(), tick - pr.setupTick, RISING_SHOTS);
                ua.zentix.airstrike.strike.ServerActions.dispatch(level, "стенд " + pr.name, 0, WeaponType.MISSILE, RISING_SHOTS, 0,
                        new ua.zentix.airstrike.strike.ServerActions.Aim(Target.OfEntity.center(stand), stand.position(), null),
                        ua.zentix.airstrike.strike.Loadout.Nuke.DEFAULT);
                continue;
            }
            if (pr.stand == null) continue;
            if (pr.raised) {
                // пол полёта вне мира: уровень моря над неготовыми чанками, но не ниже цели на 16 блоков
                for (Watch w : watched.values())
                    if (w.ground == pr && w.virtual && w.pos.y < Math.min(level.getSeaLevel(), w.aim.y - 16)) pr.underFloor = true;
                continue;
            }
            for (Watch w : watched.values()) {
                if (w.ground != pr || !w.virtual) continue;
                double d = Math.hypot(w.pos.x - pr.stand.getX(), w.pos.z - pr.stand.getZ());
                if (d > 300 && d < 1000) {
                    pr.raised = true;
                    pr.stand.teleportTo(pr.stand.getX(), pr.stand.getY() + 40, pr.stand.getZ());
                    pr.center = pr.stand.position();
                    log("проба %s: цель поднята на 40 блоков, ракета вне мира в %.0f блоках", pr.name, d);
                    break;
                }
            }
        }
    }

    private void retargetBehind(ServerLevel level, GroundProbe pr) {
        for (Watch w : watched.values()) {
            if (w.ground != pr || !(w.ref instanceof BomberEntity b) || !w.virtual || b.hasReleased()) continue;
            Vec3 to = new Vec3(w.aim.x - w.pos.x, 0, w.aim.z - w.pos.z);
            double d = to.length();
            if (d < 400 || d > 1000) continue;
            Vec3 back = w.pos.subtract(to.scale(60 / d));
            Target.Ground behind = Target.Ground.at(level, back.x, back.z);
            if (!b.retarget(behind, behind.pos())) {
                problems.add("проба-поверхность " + pr.name + ": B-2 не принял перенацеливание");
                pr.retargeted = true;
                return;
            }
            pr.retargeted = true;
            pr.center = behind.pos();
            log("проба %s: B-2 вне мира в %.0f блоках от точки перенацелен на %d %d %d (60 блоков позади)", pr.name, d,
                    (int) behind.pos().x, (int) behind.pos().y, (int) behind.pos().z);
            return;
        }
    }

    /** Строки мода: путь вне мира дошёл до поверхности, не дождался загрузки района. */
    private void readModLines() {
        for (String line; (line = modLines.poll()) != null; ) {
            Matcher m = GROUNDED.matcher(line);
            if (m.find()) {
                Watch w = watched.get(UUID.fromString(m.group(1)));
                if (w != null && w.ground != null) {
                    double dx = Integer.parseInt(m.group(2)) - Integer.parseInt(m.group(5));
                    double dz = Integer.parseInt(m.group(4)) - Integer.parseInt(m.group(7));
                    w.ground.groundOff.add(Math.hypot(dx, dz));
                    w.ground.groundAt.add(new double[] {Integer.parseInt(m.group(2)) + 0.5, Integer.parseInt(m.group(4)) + 0.5,
                            Integer.parseInt(m.group(5)) + 0.5, Integer.parseInt(m.group(7)) + 0.5});
                }
                continue;
            }
            m = GAVE_UP.matcher(line);
            if (m.find()) {
                gaveUpIds.add(UUID.fromString(m.group(1)));
                Watch w = watched.get(UUID.fromString(m.group(1)));
                if (w != null && w.ground != null) w.ground.gaveUp++;
            }
        }
    }

    @Nullable
    private GroundProbe groundProbeFor(Vec3 aim) {
        for (GroundProbe pr : groundProbes)
            if (Math.hypot(aim.x - pr.center.x, aim.z - pr.center.z) < PROBE_RADIUS) return pr;
        return null;
    }

    @Nullable
    private Probe probeFor(Vec3 aim) {
        for (Probe pr : probes) if (Math.hypot(aim.x - pr.center.x, aim.z - pr.center.z) < PROBE_RADIUS) return pr;
        return null;
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
        log("сервер запущен, режим %s, пробы %s, перезапуск %s, ждём игроков: %d; JVM: сборщик %s, куча до %d МБ", MODE, PROBES ? "вкл" : "выкл",
                RESTART ? "вкл" : "выкл", PLAYERS, collectors(), Runtime.getRuntime().maxMemory() >> 20);
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

    /** Снаряд убран, итог — по его взрыву, который может прийти позже ({@link #BLAST_WAIT}). */
    private record Gone(UUID id, Watch w, Vec3 end, @Nullable String how, int at, Level level, long gameTime) {}

    /**
     * Сколько тиков убранный снаряд ждёт своего взрыва: у неготового района ванильный взрыв откладывается до его
     * загрузки ({@code Warheads.whenReady}) — до {@link Warheads#DEFERRED_GIVE_UP_TICKS}, потом отменяется; снаряд,
     * вернувшийся в мир и сбитый в том же тике, взрывается позже. Чей взрыв — по источнику урона ({@link #blastBy}),
     * соседний его не заберёт, поэтому ждать можно весь срок: чанк района на стенде грузился и 1441 тик (a65d778).
     */
    private static final int BLAST_WAIT = Warheads.DEFERRED_GIVE_UP_TICKS + 20;
    private final List<Gone> awaitingBlast = new ArrayList<>();
    /** Снаряды, которые мод убрал, не дождавшись загрузки района цели ({@link #GAVE_UP}). */
    private final Set<UUID> gaveUpIds = new HashSet<>();

    /**
     * Чей взрыв: снаряд — прямой источник урона взрыва (боевая часть {@code Warheads.explode}, в том числе отложенная до
     * готовности района, и крушение на старте). Не по месту: снаряд рвётся у цели в 20 блоках впереди места уборки
     * (шаг и дальность подрыва), а в залпе рядом рвутся соседи. Поля с источником урона у {@link Explosion} открытого нет.
     */
    @Nullable
    public static UUID blastBy(Explosion x) {
        StrikeProjectile p = blastProjectile(x);
        return p == null ? null : p.getUUID();
    }

    /** Снаряд, чей это взрыв ({@link #blastBy}): сам объект — уже убранный из мира, с местом и фазой на момент попадания. */
    @Nullable
    public static StrikeProjectile blastProjectile(Explosion x) {
        DamageSource source;
        try {
            source = (DamageSource) ExplosionSource.FIELD.get(x);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return source != null && source.getDirectEntity() instanceof StrikeProjectile p ? p : null;
    }

    /** {@code Explosion.damageSource}: ищется при запуске стенда ({@link #init}) или при первом вопросе теста, чей взрыв. */
    private static final class ExplosionSource {
        static final Field FIELD;

        /** Класс загружен — поле найдено (иначе ошибка инициализации здесь, при запуске). */
        static void init() {}

        static {
            try {
                FIELD = Explosion.class.getDeclaredField("damageSource");
                FIELD.setAccessible(true);
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private void ended(Watch w, String outcome) {
        outcomes.merge(w.type + ":" + outcome, 1, Integer::sum);
        if (w.probe != null) w.probe.outcomes.merge(outcome, 1, Integer::sum);
        if (w.ground != null) w.ground.outcomes.merge(outcome, 1, Integer::sum);
    }

    /** Взрыв засчитан; у бомбы пробы — ещё и как далеко от точки она попала (место уборки). */
    private void impact(Gone g) {
        ended(g.w, "impact");
        if (g.w.ground != null && "bunker_buster".equals(g.w.type))
            g.w.ground.impactOff.add(Math.hypot(g.end.x - g.w.aim.x, g.end.z - g.w.aim.z));
    }

    private void lost(Gone g, String note) {
        Watch w = g.w;
        // конец — по самому объекту: снаряд, убранный вне мира (кончился запас хода), не шлёт события ухода, и «ушёл»
        // тогда говорит о его последнем уходе из мира в полёт вне мира, раньше конца
        log("lost %s %s у %d %d %d, замечен последний раз у %d %d %d (цель %d %d %d, вне мира %b, конец: %s фаза %s возраст %d, взрыва нет %d тиков%s; "
                        + "последний уход из мира: %s)",
                w.type, g.id, (int) g.end.x, (int) g.end.y, (int) g.end.z, (int) w.pos.x, (int) w.pos.y, (int) w.pos.z,
                (int) w.aim.x, (int) w.aim.y, (int) w.aim.z, w.virtual,
                w.ref.getRemovalReason(), w.ref.flightPhase().getSerializedName(), w.ref.age(), tick - g.at, note, g.how);
        ended(w, "lost");
    }

    private void onExplosion(ExplosionEvent.Start e) {
        UUID by = blastBy(e.getExplosion());
        if (by != null) blastsThisTick.putIfAbsent(by, e.getExplosion().center());
    }

    private void onTickPre(ServerTickEvent.Pre e) {
        tickStart = System.nanoTime();
        watchdogTickStart = tickStart;
        inTick = true;
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
                if (dumps == 0) diagnoseStall(s, start);
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

    /**
     * Что ждёт стоящий тик: задача в очередь потока чанков каждого мира ({@code ServerChunkCache.mainThreadProcessor}).
     * Синхронная загрузка ({@code ServerChunkCache.getChunk}) ждёт в {@code managedBlock} и выполняет задачи этой
     * очереди — отчёт пишется из потока сервера посреди остановки, без гонок с картами тикетов. Задача, дошедшая до
     * потока уже после этого тика, молчит.
     */
    private void diagnoseStall(MinecraftServer s, long stallStart) {
        for (ServerLevel level : s.getAllLevels()) {
            try {
                Field f = net.minecraft.server.level.ServerChunkCache.class.getDeclaredField("mainThreadProcessor");
                f.setAccessible(true);
                ((java.util.concurrent.Executor) f.get(level.getChunkSource())).execute(() -> {
                    // только посреди той же остановки: после неё очередь выполняет задачу между тиками или в следующем
                    if (inTick && watchdogTickStart == stallStart) log("остановка: %s", stallReport(level));
                });
            } catch (ReflectiveOperationException ex) {
                log("остановка: очередь потока чанков недоступна (%s)", ex);
                return;
            }
        }
    }

    /**
     * Строка для замера остановок: какой чанк грузится синхронно (неготовый чанк с тикетом {@code unknown} — его
     * ставит {@code ServerChunkCache.getChunk}), уровни тикетов 5×5 вокруг него (33 — полностью загружен, 32 — тикают
     * блоки, 31 — сущности; «·» — чанка нет) и уровни счёта тика там же (блоки тикают, где оба не больше 32), игроки
     * в 16 чанках (смещение от него), тикеты мода в 8 чанках, сколько чанков мир ждёт до полной загрузки.
     */
    private static String stallReport(ServerLevel level) {
        var map = ticketMap(level.getChunkSource().chunkMap.getDistanceManager());
        if (map == null) return "тикеты недоступны";
        var chunkMap = level.getChunkSource().chunkMap;
        net.minecraft.server.level.TickingTracker ticking = tickingTracker(chunkMap.getDistanceManager());
        StringBuilder sb = new StringBuilder(level.dimension().location().toString());
        int sync = 0;
        for (var en : map.long2ObjectEntrySet()) {
            if (en.getValue().stream().noneMatch(t -> t.getType() == TicketType.UNKNOWN)) continue;
            ChunkPos c = new ChunkPos(en.getLongKey());
            // тикет unknown живёт до конца тика: у готовых чанков он остался от прошлых синхронных загрузок
            if (Terrain.ready(level, c.x, c.z)) continue;
            ChunkHolder h = chunkMap.getVisibleChunkIfPresent(c.toLong());
            sb.append(String.format(Locale.ROOT, " | синхронно %d %d (блок %d %d), статус %s, уровни:", c.x, c.z, c.getMinBlockX(), c.getMinBlockZ(),
                    h == null ? "-" : h.getLatestStatus()));
            for (int dz = -2; dz <= 2; dz++) {
                sb.append(' ');
                for (int dx = -2; dx <= 2; dx++) {
                    ChunkHolder n = chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(c.x + dx, c.z + dz));
                    sb.append(n == null || n.getTicketLevel() > 33 ? " ·" : String.format(Locale.ROOT, "%3d", n.getTicketLevel()).substring(1));
                    if (dx < 2) sb.append(',');
                }
                if (dz < 2) sb.append(" /");
            }
            // уровни счёта тика (32 и меньше — тикают блоки): кто пустил тикать соседа неготового чанка
            if (ticking != null) {
                sb.append(", тик:");
                for (int dz = -2; dz <= 2; dz++) {
                    sb.append(' ');
                    for (int dx = -2; dx <= 2; dx++) {
                        sb.append(String.format(Locale.ROOT, "%3d", ticking.getLevel(new ChunkPos(c.x + dx, c.z + dz))).substring(1));
                        if (dx < 2) sb.append(',');
                    }
                    if (dz < 2) sb.append(" /");
                }
            }
            for (ServerPlayer p : level.players()) {
                ChunkPos pc = p.chunkPosition();
                if (Math.max(Math.abs(pc.x - c.x), Math.abs(pc.z - c.z)) <= 16) {
                    sb.append(String.format(Locale.ROOT, ", игрок %s в %d %d", p.getGameProfile().getName(), pc.x - c.x, pc.z - c.z));
                }
            }
            Map<String, Integer> near = new TreeMap<>();
            for (int dx = -8; dx <= 8; dx++) {
                for (int dz = -8; dz <= 8; dz++) {
                    var set = map.get(ChunkPos.asLong(c.x + dx, c.z + dz));
                    if (set == null) continue;
                    for (Ticket<?> t : set) if (t.getType().toString().contains("airstrike")) near.merge(t.getType().toString(), 1, Integer::sum);
                }
            }
            sb.append(", тикеты мода в 8 чанках ").append(near);
            if (++sync >= 8) break;
        }
        // тикет unknown ставит и getChunk на низкий статус, который уже вернулся: неготовых таких чанков бывает несколько,
        // синхронно грузится один из них — отчёт показывает все (до 8), а не первый
        if (sync == 0) sb.append(" | синхронной загрузки нет");
        int waiting = 0;
        for (ChunkHolder h : chunkMap.getChunks()) {
            if (h.getTicketLevel() <= 33 && h.getLatestStatus() != net.minecraft.world.level.chunk.status.ChunkStatus.FULL) waiting++;
        }
        return sb.append(" | ждут полной загрузки ").append(waiting).append(" чанков").toString();
    }

    /** Счёт тика {@code DistanceManager} (блоки тикают при уровне 32 и меньше), для отчёта об остановке. */
    @Nullable
    private static net.minecraft.server.level.TickingTracker tickingTracker(DistanceManager d) {
        try {
            Field f = DistanceManager.class.getDeclaredField("tickingTicketsTracker");
            f.setAccessible(true);
            return (net.minecraft.server.level.TickingTracker) f.get(d);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> ticketMap(DistanceManager d) {
        try {
            Field tf = DistanceManager.class.getDeclaredField("tickets");
            tf.setAccessible(true);
            return (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>>) tf.get(d);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private void onTickPost(ServerTickEvent.Post e) {
        inTick = false;
        MinecraftServer s = e.getServer();
        long took = System.nanoTime() - tickStart;
        if (tick < 0) {
            if (s.getPlayerCount() < PLAYERS) return;
            log("все игроки на месте, начинаем");
        }
        if (tick == 0 && placingSince != Integer.MAX_VALUE && !pendingTeleports.isEmpty()) {
            // часы сценария стоят, пока игроки не встанут на свои места: волна 1 бьёт по ним; но не дольше
            // 2 × TELEPORT_WAIT — иначе при районе, который так и не готов, стенд не дошёл бы до сводки
            if (placingSince < 0) placingSince = s.getTickCount();
            runWaits();
            if (pendingTeleports.isEmpty()) {
                // ожидания этого тика уже прошли — сценарий со следующего
                log("игроки на местах, часы сценария идут");
                return;
            } else if (s.getTickCount() - placingSince >= 2 * TELEPORT_WAIT) {
                problems.add("игроки не на местах за " + 2 * TELEPORT_WAIT + " тиков (" + pendingTeleports + "), сценарий идёт без них");
                log("игроки %s не на местах, часы сценария идут", pendingTeleports);
                placingSince = Integer.MAX_VALUE;
            }
            return;
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
        runWaits();
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
            for (var d : NuclearEvents.get(l).detonations())
                if (detonationsSeen.add(l.dimension().location() + "#" + d.id())) unclaimedDetonations.add(new Seen(l, d));
    }

    /** Подрыв, замеченный стендом, в своём мире. */
    private record Seen(ServerLevel level, Detonation d) {}

    /** Подрывы, которые ещё не засчитаны ни одному снаряду (у МБР — ни одному: её удар ведёт таймер). */
    private final List<Seen> unclaimedDetonations = new ArrayList<>();
    /** Дальше этого по горизонтали от места уборки подрыв не его: точка удара — не дальше шага и дальности подрыва. */
    private static final double NUKE_MATCH = 32;

    /**
     * Ядерный подрыв снаряда: ракета и бомба с ядерной БЧ ({@code Loadout.Nuke.onCarrier}) рвутся через
     * {@code NuclearWarhead.detonate} — подрыв в {@code NuclearEvents} в тике уборки у точки удара, ванильного
     * {@link Explosion} нет, и без этого такой снаряд засчитывался бы потерянным.
     */
    @Nullable
    private Detonation nuclearBy(Gone g) {
        // обычный снаряд, убранный рядом с подрывом, его не заберёт; из подходящих — ближайший к месту уборки
        if (!g.w().nuclear) return null;
        Seen best = null;
        double bestDist = NUKE_MATCH;
        for (Seen seen : unclaimedDetonations) {
            Detonation d = seen.d();
            if (seen.level() != g.level() || d.gameTime() < g.gameTime() - 1) continue;
            double dist = Math.hypot(d.burst().x - g.end().x, d.burst().z - g.end().z);
            if (dist <= bestDist) {
                best = seen;
                bestDist = dist;
            }
        }
        if (best == null) return null;
        unclaimedDetonations.remove(best);
        return best.d();
    }

    /** Все снаряды по UUID: новые, живые (в мире или вне его), пропавшие — со взрывом рядом или без. */
    private void track(MinecraftServer s) {
        seeDetonations(s);
        readModLines();
        tickGroundProbes(s);
        for (Probe pr : probes) {
            BlockPos c = BlockPos.containing(pr.center);
            if (pr.readyAfter < 0 && Terrain.ready(pr.level, c) && pr.level.isPositionEntityTicking(c)) pr.readyAfter = tick - pr.fired;
        }
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
                w.ground = groundProbeFor(w.aim);
                if (w.ground != null) {
                    w.ground.launched++;
                    if ("bunker_buster".equals(w.type)) w.ground.bombs++;
                }
                if (en.getValue() instanceof RocketEntity && w.ground == null) {
                    w.probe = probeFor(w.aim);
                    if (w.probe != null) {
                        w.probe.launched++;
                        w.probe.arrival = Math.min(w.probe.arrival, tick - w.probe.fired + en.getValue().etaTicks());
                    }
                }
            } else {
                w.update(en.getValue());
            }
            w.seen = tick;
            if (w.ground != null && w.virtual) w.ground.flewVirtual.add(en.getKey());
            // путь бетонобойных бомб целиком и всех снарядов вне мира: вне мира пропадали бомба (в 375 блоках от цели)
            // и ракеты РСЗО (под миром) — VPS, 29.09.2026
            // растяжение полёта РСЗО вне мира (район цели не готов): начало и конец — отдельной строкой
            if (en.getValue() instanceof RocketEntity r) {
                if (w.probe != null) {
                    w.probe.minRate = Math.min(w.probe.minRate, r.timeRate());
                    if (r.timeRate() < DEEP) w.probe.deep.add(en.getKey());
                }
                boolean stretched = r.timeRate() < 0.999;
                if (stretched != w.stretched) {
                    log("растяжение %s %s: %s, темп %.2f, у %d %d %d, до цели %.0f", w.type, en.getKey(), stretched ? "началось" : "кончилось",
                            r.timeRate(), (int) w.pos.x, (int) w.pos.y, (int) w.pos.z, w.pos.distanceTo(w.aim));
                    w.stretched = stretched;
                    if (w.probe != null) {
                        if (stretched) w.probe.stretchOn++;
                        else w.probe.stretchOff++;
                    }
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
            // где кончился — по самому объекту: вернувшись в мир, снаряд встаёт над рельефом (materialize, тот же объект)
            // и может взорваться в том же тике, не попав в перебор стенда; последнее замеченное место — ещё вне мира,
            // под рельефом (облако 29.09.2026: ракета РСЗО поднята с y 51 до ~95 и взорвалась — засчитана «lost»)
            Gone g = new Gone(en.getKey(), w, w.ref.position(), leftHow.remove(en.getKey()), tick, w.ref.level(), w.ref.level().getGameTime());
            if (cleared.contains(en.getKey())) ended(w, "cleared");
            else if ("bomber".equals(w.type)) ended(w, "bomber-gone");
            // МБР — только разгон: над небом она убирается сама, удар дальше ведёт NuclearStrikes по таймеру
            else if ("icbm".equals(w.type) && w.pos.y > s.overworld().getMaxBuildHeight()) ended(w, "boost-done");
            else awaitingBlast.add(g);
        }
        // убранный снаряд ждёт своего взрыва BLAST_WAIT тиков: взрыв у неготового района откладывается до его загрузки
        Detonation nuke;
        for (var it = awaitingBlast.iterator(); it.hasNext(); ) {
            Gone g = it.next();
            Vec3 blast = blastsThisTick.get(g.id);
            if (gaveUpIds.contains(g.id)) {
                // мод сам убрал его, не дождавшись района цели (WARN с UUID): не пропажа, а отказ по сроку ожидания
                it.remove();
                ended(g.w, "gave-up");
            } else if (blast != null) {
                it.remove();
                // взрывы проб и отложенные — строкой: по ним видно, чей взрыв засчитан и где
                if (tick > g.at || g.w.probe != null || g.w.ground != null)
                    log("взрыв %s %s у %d %d %d на t=%d (через %d тиков после уборки), до места уборки %.0f, до замеченного последним %.0f",
                            g.w.type, g.id, (int) blast.x, (int) blast.y, (int) blast.z, tick, tick - g.at, blast.distanceTo(g.end), blast.distanceTo(g.w.pos));
                impact(g);
            } else if ((nuke = nuclearBy(g)) != null) {
                it.remove();
                log("ядерный подрыв №%d %s %s у %d %d %d, до места уборки %.0f", nuke.id(), g.w.type, g.id,
                        (int) nuke.burst().x, (int) nuke.burst().y, (int) nuke.burst().z, nuke.burst().distanceTo(g.end));
                ended(g.w, "nuclear");
            } else if (tick - g.at >= BLAST_WAIT) {
                it.remove();
                lost(g, "");
            }
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
                tick, windowSum / 1e6 / Math.max(1, windowTicks), windowMax / 1e6, inWorld, virt, salvos, nukes, tickets(s),
                s.overworld().getChunkSource().getLoadedChunksCount(), used, warnings, errors);
        windowSum = windowMax = 0;
        windowTicks = 0;
    }

    /**
     * Тикеты мода во всех мирах по типам (утечка — тикеты, которые остаются, когда всё долетело); не в верхнем мире —
     * с именем мира впереди.
     */
    private static String tickets(MinecraftServer s) {
        Map<String, Integer> byType = new TreeMap<>();
        for (ServerLevel level : s.getAllLevels()) {
            var map = ticketMap(level.getChunkSource().chunkMap.getDistanceManager());
            if (map == null) return "?";
            String prefix = level.dimension() == Level.OVERWORLD ? "" : level.dimension().location().getPath() + "/";
            for (SortedArraySet<Ticket<?>> set : map.values()) {
                for (Ticket<?> t : set) {
                    String type = t.getType().toString();
                    if (type.contains("airstrike") || type.contains("neoforge") || type.contains("forced")) byType.merge(prefix + type, 1, Integer::sum);
                }
            }
        }
        return byType.toString();
    }

    /** Запас хода снаряда кончился больше секунды полёта назад, а он всё ещё летит. */
    private static boolean overdue(StrikeProjectile p) {
        return p.rangeLeft() < -20 * p.cruiseSpeed();
    }

    /** Состояние снаряда: запас хода, погоня и ожидание района цели — из NBT, этого снаружи больше нигде не видно. */
    private void describe(String what, UUID id, Watch w) {
        StrikeProjectile p = w.ref;
        CompoundTag tag = new CompoundTag();
        p.saveWithoutId(tag);
        log("%s %s %s у %s фаза %s (%d тиков) возраст %d запас %.0f погоня %.0f ждал района %d цель %s у %s вне мира %b убран %s тикает %b",
                what, w.type, id, p.blockPosition().toShortString(), p.flightPhase().getSerializedName(), tag.getInt("phase_age"),
                p.age(), p.rangeLeft(), tag.getCompound("tracker").getDouble("chased"), tag.getInt("area_wait"), p.target(),
                BlockPos.containing(p.aimPoint()).toShortString(), p.isVirtual(), p.getRemovalReason(),
                p.level() instanceof ServerLevel l && l.isPositionEntityTicking(p.blockPosition()));
    }

    private void finishWhenQuiet(MinecraftServer s) {
        // и телепорты: отложенный возврат и тикет района после сводки — ложный TELEPORT в «тикеты» и проблема мимо сводки;
        // и убранные снаряды, ждущие своего взрыва (BLAST_WAIT)
        int flying = watched.size() + awaitingBlast.size(), teleports = teleportTickets;
        for (ArrayDeque<TpRequest> queue : deferredTeleports.values()) teleports += queue.size();
        for (ServerLevel l : s.getAllLevels()) {
            flying += SalvoData.get(l).size();
        }
        // проба, чья стойка ещё ждёт своего чанка (генерация в фоне), ещё не пускала: иначе сводка отпустила бы тикет
        // и проба провалилась бы как «ни одного снаряда»
        for (GroundProbe pr : groundProbes) if (pr.forced != null && pr.stand == null) flying++;
        if (flying + teleports > 0) {
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
                log("не закончились за отведённое время: снаряды, залпы и пробы %d, телепорты %d (тикетов районов %d, в очереди %d)",
                        flying, teleports, teleportTickets, teleports - teleportTickets);
                if (teleports > 0) problems.add("телепорты не закончились к концу прогона: " + teleports);
                for (var en : watched.entrySet()) describe("  остался", en.getKey(), en.getValue());
                for (GroundProbe pr : groundProbes)
                    if (pr.forced != null && pr.stand == null) log("  проба %s: чанк стойки так и не загрузился за %d тиков", pr.name, tick - pr.setupTick);
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
        log("завершение: игроки отключены, ждём конца генерации чанков");
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
        // тикеты проб отпустить до строки сводки: в ней тикеты, которые остались, — утечка
        for (GroundProbe pr : groundProbes) {
            if (pr.forced == null) continue;
            s.overworld().getChunkSource().removeRegionTicket(PROBE_TICKET, pr.forced, 2, pr.forced);
        }
        int teleportsLeft = closeTeleports();
        if (teleportsLeft > 0) log("сводка отпускает тикеты районов телепорта: %d", teleportsLeft);
        // сводка раньше конца ожидания (timeout, перезапуск): не дождавшиеся своего взрыва — потеряны
        // подрыв мог прийти в этом же тике: ядерный снаряд засчитывается ему, а не потерей
        seeDetonations(s);
        for (Gone g : awaitingBlast) {
            if (nuclearBy(g) != null) ended(g.w(), "nuclear");
            else lost(g, ", сводка раньше конца ожидания");
        }
        awaitingBlast.clear();
        int nukes = detonationsSeen.size();
        // МБР пускали, а подрыва нет: удар потерян (или отменён раньше срока — тогда расписание стенда неверно)
        if (nukes == 0 && launchedByType.containsKey("icbm")) problems.add("МБР пущена, а ядерного подрыва нет");
        // пробы до поверхности (с #118, t=3500+): их снаряды в «запущено» и «итогах», их дальние районы — в тиках
        // (генерация); с прогонами до #118 сравнивать с поправкой
        int probeShots = groundProbes.stream().mapToInt(pr -> pr.launched).sum();
        log("summary %s: тиков %d, mspt p50 %.1f p99 %.1f худший %.0f на t=%d | запущено %s | итоги %s | ядерных подрывов %d | в полёте %d | тикеты %s | heap %d МБ | warn %d err %d"
                        + " | в том числе пробы до поверхности (с #118): снарядов %d, дальних районов %d",
                why, tick, p50 / 1e6, p99 / 1e6, worstTick / 1e6, worstTickAt, launchedByType, outcomes, nukes, watched.size(), tickets(s),
                (rt.totalMemory() - rt.freeMemory()) >> 20, warnings, errors, probeShots, groundProbes.size());
        for (Probe pr : probes) {
            // судим по готовности района к прибытию, а не по замыслу: сервер, вставший на секунды, тиков не считает, а
            // генерация в это время идёт — район короткого полёта успевал (VPS 29.09.2026), а после волны залпов длинный
            // полёт ждал очереди генерации
            int ready = pr.readyAfter < 0 ? Integer.MAX_VALUE : pr.readyAfter;
            String expect;
            List<String> fails = new ArrayList<>();
            if (ready >= pr.arrival) {
                expect = "глубокое растяжение: район готов позже прибытия";
                if (pr.deep.isEmpty()) fails.add("район готов позже прибытия, а глубокого растяжения нет");
            } else if (ready <= pr.arrival - RocketEntity.STRETCH_TICKS - PROBE_SLACK) {
                expect = "без глубокого растяжения: район готов задолго до прибытия";
                if (!pr.deep.isEmpty()) fails.add("район готов задолго до прибытия, а глубокое растяжение у " + pr.deep.size());
            } else {
                expect = "любое: район готов незадолго до прибытия";
            }
            if (pr.launched == 0) fails.add("ни одного снаряда");
            if (pr.outcomes.getOrDefault("lost", 0) > 0) fails.add("потеряно " + pr.outcomes.get("lost"));
            // отказ мода по сроку ожидания района — как у проб до поверхности: не провал, а «не проверено»
            log("проба %s (%s): пущено %d, прибытие без растяжения через %d тиков после пуска, район готов через %d, растяжение началось %d, "
                            + "кончилось %d, глубоко (темп < %.1f) у %d, наименьший темп %.2f — ожидалось %s; итоги %s — %s",
                    pr.name, pr.shortFlight ? "полёт короче загрузки района" : "полёт длиннее загрузки района", pr.launched, pr.arrival, pr.readyAfter,
                    pr.stretchOn, pr.stretchOff, DEEP, pr.deep.size(), pr.minRate, expect, pr.outcomes,
                    probeVerdict(fails, pr.outcomes.getOrDefault("gave-up", 0)));
            for (String f : fails) problems.add("проба " + pr.name + ": " + f);
        }
        for (GroundProbe pr : groundProbes) groundSummary(pr);
        for (String p : problems) log("problem: %s", p);
    }

    /** Строка пробы пути вне мира до поверхности и её вердикт (пробы идут только в прогоне до конца, без перезапуска). */
    private void groundSummary(GroundProbe pr) {
        List<Double> off = new ArrayList<>(pr.groundOff);
        off.sort(null);
        double max = off.isEmpty() ? 0 : off.get(off.size() - 1);
        double median = off.isEmpty() ? 0 : off.get(off.size() / 2);
        int vanished = pr.outcomes.getOrDefault("lost", 0);
        List<String> fails = new ArrayList<>();
        if (pr.launched == 0) fails.add("ни одного снаряда");
        else if (pr.ordered > 0 && pr.launched != pr.ordered) fails.add("пущено " + pr.launched + " из " + pr.ordered);
        if (vanished > 0) fails.add("пропали без взрыва: " + vanished);
        if (pr.steering && max > GROUND_OFF_LIMIT) fails.add(String.format(Locale.ROOT, "до поверхности в %.0f блоках от цели (> %.0f)", max, GROUND_OFF_LIMIT));
        if (pr.mustGround && off.isEmpty()) fails.add("ни одна не дошла до поверхности вне мира — путь не проверен");
        if (pr.forced != null && !pr.raised) fails.add("цель не поднялась — ракета вне мира не застала подъём");
        else if (pr.forced != null && !pr.underFloor) fails.add("после подъёма цели ракета вне мира ни разу не шла ниже пола — подъём пола не проверен");
        if (pr.flewVirtual.size() < pr.launched)
            fails.add("вне мира застали " + pr.flewVirtual.size() + " из " + pr.launched + " — путь вне мира проверен не у всех");
        int ended = pr.outcomes.values().stream().mapToInt(Integer::intValue).sum();
        if (ended < pr.launched) fails.add("итог есть у " + ended + " из " + pr.launched);
        if (pr.dropBehind) {
            // сброс в воздухе на 200 над морем, цель — на 300 позади и на 150 ниже: бомба не рулит и падает по курсу (+z)
            // от точки сброса, круче чем вдвое дальше высоты сброса она не уходит, вбок — нет
            for (double[] g : pr.groundAt) {
                double ahead = g[1] - (g[3] + 300), side = g[0] - g[2];
                if (ahead <= 0 || ahead > 400 || Math.abs(side) >= 8)
                    fails.add(String.format(Locale.ROOT, "до поверхности не по курсу от точки сброса: вперёд %.0f, вбок %.0f", ahead, side));
            }
        }
        double impactMax = pr.impactOff.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (pr.b2Behind) {
            if (!pr.retargeted) fails.add("B-2 не застали вне мира в 400–1000 блоках от точки — перенацеливания не было");
            else if (pr.bombs == 0) fails.add("бомба не сброшена");
            if (impactMax > GROUND_OFF_LIMIT) fails.add(String.format(Locale.ROOT, "бомба попала в %.0f блоках от точки (> %.0f)", impactMax, GROUND_OFF_LIMIT));
        }
        log("проба-поверхность %s (%s): пущено %d, дошли до поверхности вне мира %d (от цели по горизонтали: наибольшее %.0f, медиана %.0f), "
                        + "не дождались загрузки %d, вне мира застали %d%s, итоги %s — %s",
                pr.name, pr.how, pr.launched, off.size(), max, median, pr.gaveUp, pr.flewVirtual.size(),
                pr.b2Behind ? String.format(Locale.ROOT, ", бомб %d, попадания бомб от точки: наибольшее %.0f", pr.bombs, impactMax) : "",
                pr.outcomes, probeVerdict(fails, pr.outcomes.getOrDefault("gave-up", 0)));
        for (String f : fails) problems.add("проба-поверхность " + pr.name + ": " + f);
    }

    /**
     * Вердикт пробы (растяжения и до поверхности): провал — по проверкам; снаряд, который мод убрал, не дождавшись района
     * цели (предел ожидания при медленной генерации), то, ради чего проба, не проверил — «не проверено», не ok и не провал.
     */
    public static String probeVerdict(List<String> fails, int gaveUps) {
        if (!fails.isEmpty()) return "провал: " + String.join("; ", fails);
        return gaveUps > 0 ? "не проверено: не дождались района цели " + gaveUps : "ok";
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
