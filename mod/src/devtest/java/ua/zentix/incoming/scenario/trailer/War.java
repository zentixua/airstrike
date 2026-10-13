package ua.zentix.incoming.scenario.trailer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.Keys;
import ua.zentix.incoming.client.cam.ProjectileCamera;
import ua.zentix.incoming.client.hud.Alerts;
import ua.zentix.incoming.client.hud.ClientFlights;
import ua.zentix.incoming.client.nuclear.ClientNuclear;
import ua.zentix.incoming.client.nuclear.NukeArming;
import ua.zentix.incoming.client.map.MapProjection;
import ua.zentix.incoming.client.map.MapTarget;
import ua.zentix.incoming.client.screen.MapScreen;
import ua.zentix.incoming.client.screen.RemoteScreen;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModDataComponents;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;
import ua.zentix.incoming.strike.WeaponType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * «Война двух городов» на карте Zearth: Запад (батарея на полосе западного аэропорта) бьёт по Востоку (город у 3100 400),
 * Восток отвечает; в конце — МБР с Запада и воздушный ядерный взрыв над Востоком. Планы названы как в монтаже
 * ({@code tools/trailer/edit.py}: pNN и буква дубля) и снимаются не по порядку ролика, а по порядку постановки:
 * разрушения копятся, поэтому спокойная площадь снимается раньше горящей. Удары — командами мода от игрока: пуски на
 * Западе летят настоящий путь через карту, а удары в кадре на Востоке приходят с пусковых в 700–1500 блоках к западу,
 * за кадром. Статисты — боты Almighty ({@link Cast}): жители ({@link Crowd}) и бойцы ({@link Fight}).
 * <p>
 * Каждый план длиной в свой отрезок монтажа с запасом и начинается за секунду-две до главного: монтаж берёт план с
 * начала или с отметки ({@code siren} — тревога над игроком, {@code front} — фронт ядерного взрыва у камеры, и
 * отметки {@link Film}).
 * <p>
 * {@code INCOMING_WAR_PART} — снять только часть: {@code east}, {@code west}, {@code nuke}; {@code nukeB} и {@code nukeC} —
 * ещё по ядерке, каждая на свежей копии мира (планы волны с других мест), {@code icbm} — своя МБР для p42; по умолчанию
 * — всё, кроме {@code nukeB}, {@code nukeC} и {@code icbm}. Один план — {@code p03}, {@code p13}, {@code p16}, {@code p21}, {@code p33}, {@code p37b}, {@code p01e};
 * {@code streets} — уличный бой (площадь и проспект, около сотни ботов) с планами 26–30.
 */
final class War {
    // ================================================================ места Zearth (y = 0 — земля этой колонки)

    static final Vec3 SQUARE = new Vec3(3175.5, 0, 274.5);
    /**
     * p26: путь FPV над площадью (точки — от середины площади, высота — над её землёй) и «Град» впереди камеры: камера
     * идёт {@link #P26_SPEED} блока за тик, ракеты ложатся на {@link #P26_HIT}-м тике плана в {@link #P26_AHEAD} блоках
     * пути впереди неё, на ничейной полосе между линиями боя: камера летит на удар и сквозь его дым.
     */
    static final List<Vec3> P26_PATH = List.of(new Vec3(-45, 7, 26), new Vec3(-25, 4.2, 16), new Vec3(-2, 3.6, 13), new Vec3(22, 4.5, 6),
            new Vec3(38, 6, -12), new Vec3(32, 7, -34));
    static final double P26_SPEED = 1.4, P26_AHEAD = 26;
    static final int P26_HIT = 26;
    /**
     * p28: пакет «Града» по оси проспекта (z 331,5) — 24 точки через 2,4 блока от x 3064,5, между линиями второго боя;
     * пуски батареи — через {@link #P28_GAP} тика: разрывы бегут от камеры по блоку за тик, камера (1,6) их догоняет.
     * Первый — на {@link #P28_FIRST}-м тике плана, в 40 блоках перед камерой.
     */
    static final List<Vec3> P28_PACK = java.util.stream.IntStream.range(0, 24).mapToObj(i -> new Vec3(3064.5 + 2.4 * i, 0, 331.5)).toList();
    static final double P28_GAP = 2.4;
    static final int P28_FIRST = 6;
    static final Vec3 GOV = new Vec3(3225.5, 100, 128.5);
    /** Крыша высотки у площади (верх 161), южный край: вид на небоскрёбы центра. */
    static final Vec3 ROOF = new Vec3(3175.5, 162, 361.5);
    /** Небоскрёбы центра: верх крыши (цель удара — крыша). */
    static final Vec3 T1 = new Vec3(3151.5, 214, 461.5), T2 = new Vec3(3072.5, 255, 504.5), T3 = new Vec3(2977.5, 230, 602.5),
            T4 = new Vec3(3154.5, 234, 566.5), T5 = new Vec3(3061.5, 228, 715.5);
    /** Спуск в метро у депо: лестница вниз по +x от улицы до платформы. */
    static final Vec3 SUB_TOP = new Vec3(3362.5, 63, 269.5), SUB_BOTTOM = new Vec3(3392.5, 47, 269.5);
    /** Станция метро под ними по карте (светокамень, факелы и красные факелы). */
    static final BlockPos STATION_MIN = new BlockPos(3330, 20, 240), STATION_MAX = new BlockPos(3470, 64, 300);
    /** Лампы станции в кадре p16 — ближе этого; поле зрения камеры по вертикали. */
    private static final double STATION_RANGE = 64, STATION_FOV = 72;
    /** Улица у южного края площади (площадь — x 3110–3240, z 238–310, земля 62). */
    static final Vec3 AVENUE = new Vec3(3200.5, 0, 292.5);
    /** Эпицентр воздушного взрыва; крыша финала в ~900 блоках от него. */
    static final Vec3 GZ = new Vec3(3300.5, 64, 640.5);
    static final Vec3 FINAL_ROOF = new Vec3(2849.5, 116, -157.5);
    /**
     * Наводчик на крыше Востока смотрит в бинокль на башни центра (план 33): бинокль берёт цель до 400 блоков, отсюда
     * (perch.py по файлам регионов) верхи T2, T1, T4 видны в 325, 233 и 325 блоках; по курсу справа налево — T2 (42,5°),
     * T1 (34,8°), T4 (23,3°), в открытый квадрат окуляра (±8° при зуме 4) входят две соседние.
     */
    static final Vec3 SKYLINE_POST = new Vec3(3277.5, 138, 280.5);
    /** Наводчик плана 7: крыша Востока в ~390 блоках от T2 (perch.py; бинокль берёт цель до 400 блоков). */
    static final Vec3 OBSERVER = new Vec3(2872.5, 104, 181.5);
    /** Удары по башням — под слоем облаков (ваниль — 192, Complementary — около 190): верх башен центра в облаках. */
    static final double UNDER_CLOUDS = 172;
    /** Улица между домами в ~890 блоках от эпицентра, вдоль неё — на эпицентр. */
    static final Vec3 WAVE_STREET = new Vec3(2950.5, 64, -178.5);
    /** Взгорок к северо-западу, ~1250 блоков от эпицентра. */
    static final Vec3 WAVE_HILL = new Vec3(2600.5, 0, -400.5);
    /**
     * Камера над аэропортом в ~1075 блоках от эпицентра, высоко; под ней — городок в ~780 блоках, через него идёт стена;
     * взгляд — на ~620 блоков от эпицентра, вверху кадра горизонт.
     */
    static final Vec3 WAVE_HIGH = new Vec3(2780, 330, -300), WAVE_TOWN = new Vec3(2923, 0, -42), WAVE_LOOK = new Vec3(3000.5, 0, 98.5);
    /** Малый аэропорт Востока: торец полосы, полоса на восток. */
    static final Vec3 AIRFIELD = new Vec3(2836.5, 67, -216.5);

    /** Полоса западного аэропорта, на ней батарея: ряд крылатых ракет, ряд «Градов» и шахедов. */
    static final double BATTERY_Y = 68, ROW_MISSILE = 516.5, ROW_ROCKET = 534.5;
    static final Vec3 BATTERY = new Vec3(-1490.5, 68, 525.5);
    /** Место расчёта у батареи ночью. */
    static final Vec3 NIGHT_POST = new Vec3(-1460.5, 68, 525.5);
    /** Расчёт пусковой МБР; первый — командир, его имя — в строке второго ключа (план 40). */
    static final String[] CREW = {"Volkov", "Crew1", "Crew2", "Crew3"};
    /** Цель «Градов» батареи: позиции Востока за рекой (дальность «Града» — сотни блоков, не километры). */
    static final Vec3 ROCKET_TARGET = new Vec3(-650.5, 0, 560.5);

    private final Trailer film;
    private final Minecraft mc;
    private Crowd crowd, roof;
    private Fight street, avenue;
    private final List<BlockPos> missileRack = new ArrayList<>(), rocketRack = new ArrayList<>(), droneRack = new ArrayList<>();
    private static Field bannerField;
    private int lastBanner;
    /** Узлы сети, которые поставила съёмка ({@link #gridNode}). */
    private final List<Integer> gridNodes = new ArrayList<>();
    /** Проверка ламп в кадре идущего плана ({@link #lampsFrom}). */
    @Nullable
    private LampsQa lampsQa;
    /** Камера p16 ({@link #stationView}): у укрывшихся, где в кадре больше всего горящих ламп станции. */
    @Nullable
    private LampsQa.View stationView;
    @Nullable
    private FightQa fightQa;
    private Object frontMarkedIn;

    /** Действие через столько тиков клиента. */
    private static final class Delayed {
        int ticks;
        final Runnable action;

        Delayed(int ticks, Runnable action) {
            this.ticks = ticks;
            this.action = action;
        }
    }

    private final List<Delayed> delayed = new ArrayList<>();

    /** Через {@code ticks} тиков клиента: пуски вразбивку (маршрут ракеты — на время полёта из настроек, залпы пришли бы разом). */
    void later(int ticks, Runnable action) {
        if (ticks <= 0) action.run();
        else delayed.add(new Delayed(ticks, action));
    }

    War(Trailer film) {
        this.film = film;
        this.mc = film.mc;
        NeoForge.EVENT_BUS.addListener(this::watch);
        NeoForge.EVENT_BUS.addListener(Cursor::render);
    }

    void stage() {
        String part = System.getenv().getOrDefault("INCOMING_WAR_PART", "all");
        boolean all = part.equals("all");
        config();
        if (all || part.equals("east")) {
            dawn();
            firstStrike();
        }
        if (all || part.equals("west")) battery();
        if (all || part.equals("east")) {
            sky();
            streets();
            throughFire();
        }
        if (part.equals("nuke")) {
            // своя копия мира: пусковые и бой на площади — как после частей west и east
            racks();
            fight();
        }
        if (all || part.equals("nuke")) nuke();
        if (part.equals("nukeB")) nukeFar();
        if (part.equals("nukeC")) nukeNear();
        if (part.equals("icbm")) icbm();
        // один план для проверки съёмки из повтора (ReplayFilm)
        if (part.equals("p03")) dawnFlight();
        if (part.equals("p13")) p13();
        if (part.equals("p16")) {
            film.run(() -> Film.cmd("time set 1000"));  // днём, как в части east
            metroCrowd();
            film.waitTicks(120);  // люди внизу, как после p15
            p16();
            film.run(() -> crowd().removeAll());
            gridRestore();
        }
        if (part.equals("p21")) ridge();
        if (part.equals("streets")) {
            film.run(() -> Film.cmd("time set 1000"));  // днём, как в части east
            streets();
        }
        if (part.equals("p01e")) {
            film.run(() -> Film.cmd("time set 1000"));  // днём, как в части east
            carpet();
        }
        if (part.equals("p33")) skyline();
        if (part.equals("p37b")) {
            // своя копия мира: город на горизонте уже горит, как к закату после частей west и east
            film.run(() -> Film.cmd("time set 12400"));
            burnSkyline();
            summit();
        }
    }

    // ================================================================ мир и помощники

    private void config() {
        film.run(() -> {
            Film.cmd("difficulty peaceful");
            Film.cmd("gamerule doFireTick false");
            Film.cmd("gamerule mobGriefing false");
            var c = IncomingConfig.SERVER;
            c.launchNearPlayer.set(false);
            // полёт от пусковой до цели — по прямой: пусковые за кадром в 700–1500 блоках
            c.droneFlightTime.set(5);
            c.missileFlightTime.set(5);
            c.bomberFlightTime.set(5);
            c.nukeFlightTime.set(1100);
            // ядерный удар не оператора (командир расчёта — бот, план 40): по правилам игрока и со вторым ключом
            c.nukeOpsOnly.set(false);
            c.designatorForEveryone.set(true);
            c.siren.set(true);
            c.maxSalvo.set(100);
        });
    }

    Cast cast() {
        return film.cast();
    }

    MinecraftServer server() {
        return mc.getSingleplayerServer();
    }

    Crowd crowd() {
        if (crowd == null) crowd = new Crowd(cast(), server());
        return crowd;
    }

    /** Земля (где стоять) в этой колонке; чанк грузится сразу (сценарию можно). Из любого потока. */
    Vec3 g(double x, double z) {
        MinecraftServer s = server();
        if (s.isSameThread()) return new Vec3(x, groundY(s.overworld(), x, z), z);
        int y = s.submit(() -> groundY(s.overworld(), x, z)).join();
        return new Vec3(x, y, z);
    }

    Vec3 g(Vec3 p) {
        return g(p.x, p.z);
    }

    /** Точка как есть, а с y = 0 — на земле. */
    Vec3 at(Vec3 p) {
        return p.y <= 0 ? g(p) : p;
    }

    /** Первый блок над землёй; чанк грузится сразу ({@code Level.getHeight} у незагруженного даёт низ мира). */
    static int groundY(ServerLevel level, double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        return level.getChunk(bx >> 4, bz >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx & 15, bz & 15) + 1;
    }

    /** Игрок — на месте, взгляд на точку: сервер шлёт мир вокруг игрока. */
    void place(Vec3 p, Vec3 look) {
        Vec3 q = at(p), l = at(look);
        float yaw = Film.yawTo(q, l), pitch = Film.pitchTo(q.add(0, 1.62, 0), l);
        film.withPlayer((level, sp) -> sp.teleportTo(level, q.x, q.y, q.z, yaw, pitch));
    }

    /** Игрок в кадре сам (его глаза, интерфейс мода, предмет в руке): творческий режим. */
    void actor(Vec3 p, Vec3 look, String item) {
        actor(() -> p, () -> look, item);
    }

    /** То же, место и взгляд — к моменту шага (их находит сам сценарий по миру). */
    void actor(java.util.function.Supplier<Vec3> p, java.util.function.Supplier<Vec3> look, String item) {
        film.run(() -> {
            film.actor = true;
            film.withPlayer((level, sp) -> sp.setGameMode(GameType.CREATIVE));
            place(p.get(), look.get());
            if (item != null) Film.cmd("item replace entity @s weapon.mainhand with " + item);
        });
        film.waitTicks(30);
    }

    /** Невидимка-зритель — над местом следующего плана: мир там начинает грузиться заранее. */
    void hiddenAt(Vec3 p, Vec3 look) {
        film.run(() -> place(at(p).add(0, 20, 0), look));
        film.waitTicks(20);
    }

    /** Программа бота из одного шага. */
    static JsonArray one(JsonObject step) {
        JsonArray a = Cast.steps();
        a.add(step);
        return a;
    }

    static String xyz(Vec3 p) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", p.x, p.y, p.z);
    }

    /** Залп по месту {@code target} (y = 0 — земля) с пусковой в {@code from} (по горизонтали): пуск и полёт настоящие. */
    void salvo(String weapon, int count, int spread, Vec3 target, Vec3 from) {
        Vec3 t = at(target);
        sightCheck(t);
        Film.cmd(String.format(Locale.ROOT, "incoming salvo %s %d %d at %s from %.1f %.1f", weapon, count, spread, xyz(t), from.x, from.z));
    }

    /**
     * Залп ракет в цель на улице среди высоток: заход — с направления, где над последним участком рельеф ниже всего
     * ({@link #clearApproach}), маршрутом через точку в {@link #APPROACH_LEG} блоках от цели на этом направлении, пуск —
     * в {@link #APPROACH_FROM} на нём же. Без точки ракета заходит по взгляду стреляющего ({@code ServerActions.strike} —
     * его поворот), а на маршруте держит 12 блоков над рельефом и с последних 160 блоков делает горку и пике: заход над
     * высотками у цели кончается ударом в их верх с недолётом (p16 на netcup: обе ракеты — в 100–130 блоках от цели,
     * на высоте 136–146, узел цел).
     * Нет направления, где весь участок у клиента загружен, — как {@link #salvo} с места {@code fallback}.
     */
    private void missileInto(String shot, int count, int spread, Vec3 target, Vec3 fallback) {
        Vec3 t = at(target);
        double rd = mc.gameRenderer.getRenderDistance();
        Vec3 dir = clearApproach(shot, t, Math.min(APPROACH_LEG, rd - 32), spread + 3);
        if (dir == null) {
            salvo("missile", count, spread, target, fallback);
            return;
        }
        Vec3 via = t.add(dir.scale(APPROACH_LEG)), from = t.add(dir.scale(APPROACH_FROM));
        sightCheck(t);
        Film.cmd(String.format(Locale.ROOT, "incoming salvo missile %d %d at %s from %.1f %.1f via %.1f %.1f", count, spread, xyz(t), from.x,
                from.z, via.x, via.z));
    }

    /** Последний участок ракеты до цели (точка маршрута) и место пуска, блоков от цели. */
    private static final double APPROACH_LEG = 400, APPROACH_FROM = 1400;

    /**
     * Направление захода (единичный вектор от цели наружу) с самым низким рельефом над участком {@code reach} блоков до
     * цели в полосе ±{@code half} блоков: по картам высот клиента ({@code MOTION_BLOCKING}), через 5°; направление, где
     * какого-то чанка участка у клиента нет, не берётся. Строка {@code SCENARIO film staging: … заход …}; нет ни одного —
     * WARN и null.
     */
    @Nullable
    Vec3 clearApproach(String shot, Vec3 target, double reach, int half) {
        if (mc.level == null) return null;
        var chunks = mc.level.getChunkSource();
        double best = Double.MAX_VALUE;
        Vec3 bestDir = null;
        int bestAz = -1, known = 0;
        for (int az = 0; az < 360; az += 5) {
            double r = Math.toRadians(az);
            Vec3 dir = new Vec3(Math.cos(r), 0, Math.sin(r)), side = new Vec3(-dir.z, 0, dir.x);
            int top = Integer.MIN_VALUE;
            boolean loaded = true;
            for (double d = 6; d <= reach && loaded; d += 2) {
                for (int k = -half; k <= half; k++) {
                    int bx = Mth.floor(target.x + dir.x * d + side.x * k), bz = Mth.floor(target.z + dir.z * d + side.z * k);
                    if (chunks.getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, false) == null) {
                        loaded = false;
                        break;
                    }
                    top = Math.max(top, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz));
                }
            }
            if (!loaded) continue;
            known++;
            if (top < best) {
                best = top;
                bestDir = dir;
                bestAz = az;
            }
        }
        int column = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(target.x), Mth.floor(target.z));
        if (bestDir == null) {
            Incoming.LOG.warn("SCENARIO film staging: {} — заход на {} не выбрать: ни одного направления, где {} блоков у клиента загружены",
                    shot, xyz(target), Math.round(reach));
            return null;
        }
        Incoming.LOG.info("SCENARIO film staging: {} — заход на {} (верх колонки {}) с азимута {}° (x+ = 0°, z+ = 90°): рельеф до {} на {} блоков ±{}, направлений с данными {}",
                shot, xyz(target), column, bestAz, Math.round(best), Math.round(reach), half, known);
        if (best > target.y + 24) {
            Incoming.LOG.warn("SCENARIO film staging: {} — над всеми заходами на {} рельеф не ниже {}: ракета может удариться с недолётом",
                    shot, xyz(target), Math.round(best));
        }
        return bestDir;
    }

    /**
     * Точка удара, которую камера видит: луч из {@code eye} на {@code wanted} по блокам у клиента (мир плана прогружен);
     * упёрся раньше — цель там, на грани, которую видит камера (на полблока внутри блока). Нужна там, где камера стоит
     * (штатив, из-за плеча): удар за домом она не снимет.
     */
    Vec3 visible(Vec3 eye, Vec3 wanted) {
        if (mc.level == null) return wanted;
        Vec3 d = wanted.subtract(eye);
        var hit = mc.level.clip(new ClipContext(eye, wanted, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) return wanted;
        Vec3 at = hit.getLocation().add(d.normalize().scale(0.5));
        Incoming.LOG.info("SCENARIO film staging: цель {} из {} — на видимой грани {}", xyz(wanted), xyz(eye), xyz(at));
        return at;
    }

    /**
     * До {@code n} точек удара на гранях домов, которые камера видит, по вееру лучей из {@code eye} в сторону {@code toward}
     * (ширина {@code span} градусов, от горизонта вверх на 8°): от {@code dmin} до {@code dmax} блоков, ближе тумана у края
     * прорисовки, не выше {@code ymax} (под слоем облаков), не ближе 25 блоков друг к другу, самые дальние — первыми.
     */
    List<Vec3> facades(Vec3 eye, Vec3 toward, double span, int n, double dmin, double dmax, double ymax) {
        List<Vec3> hits = new ArrayList<>();
        if (mc.level == null) return hits;
        float yaw0 = Film.yawTo(eye, toward);
        for (double yaw = yaw0 - span / 2; yaw <= yaw0 + span / 2; yaw += 1.5) {
            for (double pitch = 0; pitch <= 8; pitch += 2) {
                Vec3 dir = Vec3.directionFromRotation((float) -pitch, (float) yaw);
                var hit = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(dmax)), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                        CollisionContext.empty()));
                if (hit.getType() == HitResult.Type.MISS) continue;
                Vec3 at = hit.getLocation().add(dir.scale(0.5));
                if (at.distanceTo(eye) >= dmin && at.y <= ymax && !Rig.beyondFog(eye, at)) hits.add(at);
            }
        }
        hits.sort(Comparator.comparingDouble(h -> -h.distanceTo(eye)));
        List<Vec3> out = new ArrayList<>();
        for (Vec3 h : hits) {
            if (out.size() >= n) break;
            if (out.stream().allMatch(o -> o.distanceTo(h) >= 25)) out.add(h);
        }
        Incoming.LOG.info("SCENARIO film staging: грани из {} — {}", xyz(eye), out.stream().map(War::xyz).toList());
        return out;
    }

    /**
     * Цель залпа видна из кинокамеры плана? Нет — строка в лог до записи: удар за домом или за слоем облаков камера не
     * снимет, и это видно сразу, а не после вывода. Только у плана, которому удар нужен в кадре ({@link Coverage.Need}:
     * {@code key} или {@code near}; пролёт над головой, пуск, улица — нет), и с любого места камеры за план: камера в пути
     * (FPV из метро) при пуске бывает под землёй, а удар снимает с улицы. Строка — с места камеры при пуске.
     */
    void sightCheck(Vec3 target) {
        Pose cam = CineCamera.pose();
        if (cam == null || mc.level == null) return;
        // залп плана — в его onReady, на тик раньше, чем план начинает запись: план — шаг сценария, который идёт
        Film.Shot shot = film.steps.peek() instanceof Film.Shot s ? s : null;
        Coverage.Spec spec = shot == null ? null : Coverage.all().get(shot.name);
        if (spec != null && spec.need().key() == 0 && spec.need().near() == 0) return;
        if (shot != null) {
            for (Vec3 eye : shot.eyes(5)) if (sees(eye, target)) return;
        } else if (sees(cam.pos(), target)) {
            return;
        }
        String plan = shot == null ? "-" : shot.name;
        Vec3 eye = cam.pos(), d = target.subtract(eye);
        BlockHitResult hit = blocker(eye, target);
        if (hit != null) {
            Incoming.LOG.warn("SCENARIO film staging {}: цель {} закрыта от камеры {} — блок {} в {} блоках из {}",
                    plan, xyz(target), xyz(eye), hit.getBlockPos().toShortString(),
                    String.format(Locale.ROOT, "%.0f", hit.getLocation().distanceTo(eye)), String.format(Locale.ROOT, "%.0f", d.length()));
        }
        double clouds = Rig.cloudBase();
        if (Rig.throughClouds(eye, target, clouds)) {
            Incoming.LOG.warn("SCENARIO film staging {}: цель {} от камеры {} — через слой облаков ({})", plan,
                    xyz(target), xyz(eye), String.format(Locale.ROOT, "%.0f", clouds));
        }
        // луч по чанкам, которых у клиента нет, свободен — но их и не видно; у края прорисовки — туман
        boolean fog = Rig.beyondFog(eye, target), missing = !chunkHere(target);
        if (fog || missing) {
            Incoming.LOG.warn("SCENARIO film staging {}: цель {} в {} блоках от камеры {} — {} (прорисовка {} блоков)",
                    plan, xyz(target), String.format(Locale.ROOT, "%.0f", Math.hypot(d.x, d.z)), xyz(eye),
                    fog ? "в тумане у края прорисовки" : "чанка цели у клиента ещё нет",
                    String.format(Locale.ROOT, "%.0f", mc.gameRenderer.getRenderDistance()));
        }
    }

    /** Удар в {@code target} виден из {@code eye}: не за блоками, не за облаками, не в тумане, чанк цели у клиента есть. */
    private boolean sees(Vec3 eye, Vec3 target) {
        return chunkHere(target) && blocker(eye, target) == null && !Rig.throughClouds(eye, target, Rig.cloudBase())
                && !Rig.beyondFog(eye, target);
    }

    /** Блок на луче из {@code eye} к {@code target}, кроме стены самой цели (цель — середина блока за гранью). */
    @Nullable
    private BlockHitResult blocker(Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        double len = d.length(), spare = 4;
        if (len <= spare) return null;
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(d.scale((len - spare) / len)), ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceTo(target) > 8 ? hit : null;
    }

    private boolean chunkHere(Vec3 p) {
        return mc.level.getChunkSource().getChunkNow(Mth.floor(p.x) >> 4, Mth.floor(p.z) >> 4) != null;
    }

    /** Пусковая на прямой через точку {@code over} к цели, в {@code d} блоках позади точки: снаряд пройдёт над ней. */
    static Vec3 behind(Vec3 over, Vec3 target, double d) {
        Vec3 dir = target.subtract(over).multiply(1, 0, 1).normalize();
        return over.subtract(dir.scale(d));
    }

    /** Место удара p26 по горизонтали (y = 0 — земля): точка пути FPV впереди камеры. */
    static Vec3 p26Hit() {
        Vec3 p = new CineCamera.Spline(P26_PATH).at(P26_SPEED * P26_HIT + P26_AHEAD);
        return new Vec3(SQUARE.x + p.x, 0, SQUARE.z + p.z);
    }

    /**
     * Батарея: по снаряду на каждую точку {@code targets} (y = 0 — земля) с места пуска со сдвигом {@code from} от своей
     * точки: дальность у всех одна, время полёта одно, и снаряды ложатся в том порядке и с тем шагом, что пуски
     * ({@code gap} тиков). Залп с одной пусковой так не ляжет: у неё пуски через 8–12 тиков ({@code WeaponSpec.Salvo}).
     * Место пуска — дальше чанков, которые держат игрок съёмки и боты: там снаряд стартует вне мира сразу. В готовом чанке
     * встала бы пусковая (места ближе {@code LaunchSite.PLACE_REUSE} — одна на всех), и пуски пошли бы её очередью —
     * тогда строка {@code SCENARIO film staging}.
     */
    void battery(String weapon, List<Vec3> targets, Vec3 from, double gap) {
        film.withServer(server -> {
            ServerLevel level = server.overworld();
            for (Vec3 t : targets) {
                Vec3 f = t.add(from);
                if (level.getChunkSource().getChunkNow(Mth.floor(f.x) >> 4, Mth.floor(f.z) >> 4) != null) {
                    Incoming.LOG.warn("SCENARIO film staging: батарея — место пуска {} в готовом чанке: встанет пусковая, пуски — её очередью", xyz(f));
                    break;
                }
            }
        });
        for (int i = 0; i < targets.size(); i++) {
            Vec3 t = targets.get(i);
            later((int) Math.round(gap * i), () -> salvo(weapon, 1, 0, t, t.add(from)));
        }
    }

    /**
     * Перезарядка оружия Point Blank в руке игрока съёмки — его клавишей, как у игрока (нажатие через обработчик клавиатуры:
     * {@code KeyMapping} и событие клавиши NeoForge): автомат из команды приходит с пустым магазином.
     */
    private void reloadGun() {
        for (KeyMapping k : mc.options.keyMappings) {
            String id = (k.getName() + " " + k.getCategory()).toLowerCase(Locale.ROOT);
            if (!id.contains("pointblank") || !k.getName().toLowerCase(Locale.ROOT).contains("reload")) continue;
            InputConstants.Key key = k.getKey();
            if (key.getType() != InputConstants.Type.KEYSYM) break;
            long window = mc.getWindow().getWindow();
            mc.keyboardHandler.keyPress(window, key.getValue(), 0, org.lwjgl.glfw.GLFW.GLFW_PRESS, 0);
            later(3, () -> mc.keyboardHandler.keyPress(window, key.getValue(), 0, org.lwjgl.glfw.GLFW.GLFW_RELEASE, 0));
            Incoming.LOG.info("SCENARIO film staging: перезарядка — клавиша {} ({})", k.getName(), key.getName());
            return;
        }
        Incoming.LOG.warn("SCENARIO film staging: клавиши перезарядки Point Blank на клавиатуре нет");
    }

    /**
     * Курс, с которого небо над глазом {@code eye} открыто: лучи по блокам у клиента через 5° по курсу на 8–28° над
     * горизонтом до 150 блоков (луч в чанк, которого у клиента нет, — не открыт); курс — середина самого открытого
     * сектора в 35°. Шахед пикирует под 18° ({@code DroneAutopilot}) — с этого курса его видно из глаз до удара.
     */
    float openSky(Vec3 eye) {
        int n = 72;
        int[] open = new int[n];
        for (int i = 0; i < n; i++) {
            for (int el = 8; el <= 28; el += 4) {
                Vec3 end = eye.add(Vec3.directionFromRotation(-el, i * 5f).scale(150));
                if (!chunkHere(end)) continue;
                var hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
                if (hit.getType() == HitResult.Type.MISS) open[i]++;
            }
        }
        int best = 0, score = -1;
        for (int i = 0; i < n; i++) {
            int s = 0;
            for (int k = -3; k <= 3; k++) s += open[Math.floorMod(i + k, n)];
            if (s > score) {
                score = s;
                best = i;
            }
        }
        Incoming.LOG.info("SCENARIO film staging: открытое небо из {} — курс {}°, открытых лучей {} из 42", xyz(eye), best * 5, score);
        return best * 5f;
    }

    /**
     * Места стрелка Запада для плана глазами бойца: клетки за линией баррикад Запада (x от c.x − 34 до c.x − 24), где
     * можно стоять (не на баррикаде, воздух на два блока, не ближе 1,5 блока к посту бойца), — по числу бойцов Востока,
     * чья грудь видна из глаз (лучи по блокам у клиента) в секторе ±35° к востоку. {@code n} лучших не ближе 6 блоков
     * друг к другу: [место, точка взгляда — середина видимых бойцов Востока].
     */
    List<Vec3[]> firingSpots(Fight fight, Vec3 c, int n) {
        record Spot(Vec3 at, Vec3 aim, int seen) {}
        List<Spot> all = new ArrayList<>();
        int east = 0;
        for (Fight.Soldier s : fight.soldiers()) if (s.side == 1) east++;
        for (int bx = Mth.floor(c.x) - 34; bx <= Mth.floor(c.x) - 24; bx++) {
            for (int bz = Mth.floor(c.z) - 22; bz <= Mth.floor(c.z) + 24; bz++) {
                int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
                BlockPos feet = new BlockPos(bx, top, bz);
                if (mc.level.getBlockState(feet.below()).is(Blocks.PACKED_MUD) || !mc.level.getBlockState(feet).isAir()
                        || !mc.level.getBlockState(feet.above()).isAir()) continue;
                Vec3 at = new Vec3(bx + 0.5, top, bz + 0.5);
                if (fight.soldiers().stream().anyMatch(s -> Math.hypot(s.post.x - at.x, s.post.z - at.z) < 1.5)) continue;
                Vec3 eye = at.add(0, 1.62, 0), sum = Vec3.ZERO;
                int seen = 0;
                for (Fight.Soldier s : fight.soldiers()) {
                    if (s.side != 1) continue;
                    Vec3 chest = s.post.add(0, 1.2, 0), d = chest.subtract(eye);
                    if (Math.abs(Math.toDegrees(Math.atan2(d.z, d.x))) > 35) continue;
                    var hit = mc.level.clip(new ClipContext(eye, chest, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
                    if (hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceTo(chest) >= 0.6) continue;
                    seen++;
                    sum = sum.add(chest);
                }
                if (seen > 0) all.add(new Spot(at, sum.scale(1.0 / seen), seen));
            }
        }
        all.sort(Comparator.comparingInt((Spot s) -> -s.seen));
        List<Vec3[]> out = new ArrayList<>();
        for (Spot s : all) {
            if (out.size() >= n) break;
            if (out.stream().anyMatch(o -> o[0].distanceTo(s.at) < 6)) continue;
            out.add(new Vec3[]{s.at, s.aim});
            Incoming.LOG.info("SCENARIO film staging: место стрелка {} — видно бойцов Востока {} из {}", xyz(s.at), s.seen, east);
        }
        while (out.size() < n) {
            Incoming.LOG.warn("SCENARIO film staging: место стрелка — нет ни одного с видом на бойцов Востока, беру за линией у середины");
            out.add(new Vec3[]{new Vec3(c.x - 30, 0, c.z - 10 + out.size() * 9), at(new Vec3(c.x + 28, 0, c.z)).add(0, 1.4, 0)});
        }
        return out;
    }

    /**
     * Место бойца на мостовой у {@code (x, z)}: высота улицы {@code street} (±1 — не на фонаре или машине), клетка
     * свободна; иначе — ближайшее такое же вдоль улицы: до 8 блоков от середины боя ({@code away} — куда это) и до 2 к ней.
     */
    @Nullable
    private static Vec3 streetSpot(ServerLevel level, double x, double z, int street, int away, java.util.Set<BlockPos> taken) {
        for (int k : new int[]{0, 1, 2, -1, 3, -2, 4, 5, 6, 7, 8}) {
            double px = x + k * away;
            int y = groundY(level, px, z);
            if (Math.abs(y - street) <= 1 && taken.add(BlockPos.containing(px, y, z))) return new Vec3(px, y, z);
        }
        return null;
    }

    /** Снаряд этого оружия ближе {@code r} к точке: чужие полёты (шахеды прошлых планов идут через весь город) не в счёт. */
    static boolean flightNear(Vec3 p, double r, WeaponType weapon) {
        for (var f : ClientFlights.all()) if (f.weapon() == weapon && f.position(1).distanceTo(p) < r) return true;
        return false;
    }

    /** Тревога у цели крылатой ракеты — за столько секунд до удара ({@code WeaponSpec.MISSILE}). */
    static final double MISSILE_SIREN_S = WeaponType.MISSILE.spec().sirenLead() / 20.0;

    /**
     * Секунд до удара ближайшего снаряда этого оружия с целью ближе {@code r} к точке; нет такого — бесконечность.
     * Ракета летит не короче заданного времени (обходом), поэтому момент тревоги по расстоянию не угадать.
     */
    static double flightEta(Vec3 p, double r, WeaponType weapon) {
        double best = Double.POSITIVE_INFINITY;
        for (var f : ClientFlights.all()) if (f.weapon() == weapon && f.target().distanceTo(p) < r) best = Math.min(best, f.etaSeconds(1));
        return best;
    }

    /**
     * Середина стаи: среднее место {@code n} ближайших к точке снарядов этого оружия не дальше {@code r}; нет — null.
     * Шахеды идут к цели не прямо (обход и заход с тыла, {@code drone_flight_time}), поэтому камера ищет их, а не ждёт.
     */
    @Nullable
    static Vec3 swarmCentre(Vec3 p, double r, int n, WeaponType weapon) {
        List<Vec3> near = new ArrayList<>();
        for (var f : ClientFlights.all()) {
            Vec3 q = f.position(1);
            if (f.weapon() == weapon && q.distanceTo(p) < r) near.add(q);
        }
        if (near.isEmpty()) return null;
        near.sort(Comparator.comparingDouble(q -> q.distanceToSqr(p)));
        Vec3 sum = Vec3.ZERO;
        int k = Math.min(n, near.size());
        for (int i = 0; i < k; i++) sum = sum.add(near.get(i));
        return sum.scale(1.0 / k);
    }

    /**
     * Снаряд уже прошёл над камерой {@code p} в сторону взгляда на {@code look}: по горизонтали не меньше {@code ahead}
     * блоков вперёд (и не дальше 150), в стороне — не больше 40.
     */
    static boolean flightPast(Vec3 p, Vec3 look, double ahead, WeaponType weapon) {
        Vec3 dir = look.subtract(p).multiply(1, 0, 1).normalize();
        for (var f : ClientFlights.all()) {
            if (f.weapon() != weapon) continue;
            Vec3 d = f.position(1).subtract(p).multiply(1, 0, 1);
            double along = d.dot(dir), side = Math.abs(d.x * dir.z - d.z * dir.x);
            if (along >= ahead && along <= 150 && side <= 40) return true;
        }
        return false;
    }

    /** То же по горизонтали: снаряд над точкой на любой высоте. */
    static boolean flightOver(Vec3 p, double r, WeaponType weapon) {
        for (var f : ClientFlights.all()) {
            if (f.weapon() != weapon) continue;
            Vec3 q = f.position(1);
            if (Math.hypot(q.x - p.x, q.z - p.z) < r) return true;
        }
        return false;
    }

    /** Снаряд в мире был ближе {@code r} к точке и пропал (взрыв): конец плана. На каждый план — своё. */
    BooleanSupplier hit(Vec3 p, double r) {
        boolean[] seen = {false};
        Vec3[] q = {null};
        return () -> {
            if (q[0] == null) q[0] = at(p);
            boolean near = film.nearest(StrikeProjectile.class, q[0], r) != null;
            seen[0] |= near;
            return seen[0] && !near;
        };
    }

    /**
     * Тиков до прихода фронта ядерного взрыва в точку (уже прошёл — меньше нуля; подрыва нет — очень много): по
     * расстоянию до точки подрыва, как руины и стена пыли (воздушный подрыв 15 кт — в ~590 блоках над землёй).
     */
    static double frontIn(Vec3 p) {
        double best = Double.MAX_VALUE;
        for (var a : ClientNuclear.detonations()) {
            best = Math.min(best, a.d.arrivalTicks(p.distanceTo(a.d.burst())) - a.ticks(0));
        }
        return best;
    }

    /** Тиков осталось у надписи тревоги над хотбаром ({@link Alerts}): больше, чем в прошлом тике, — тревога. */
    static int banner() {
        try {
            if (bannerField == null) {
                bannerField = Alerts.class.getDeclaredField("bannerLeft");
                bannerField.setAccessible(true);
            }
            return bannerField.getInt(null);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Отметки для монтажа: тревога ({@code siren}) и фронт ядерного взрыва у камеры ({@code front}), раз за план. */
    private void watch(ClientTickEvent.Post e) {
        if (mc.level == null) return;
        if (!delayed.isEmpty()) {
            List<Delayed> due = new ArrayList<>();
            delayed.removeIf(d -> --d.ticks <= 0 && due.add(d));
            due.forEach(d -> d.action.run());
        }
        Cursor.tick();
        if (film.rec == null) return;
        if (lampsQa != null && film.marking()) lampsQa.tick(film);
        if (fightQa != null && film.marking()) fightQa.tick(film);
        int b = banner();
        boolean rec = film.marking();
        if (rec && b > lastBanner) film.mark("siren");
        lastBanner = b;
        if (rec && film.recording != null && frontMarkedIn != film.recording
                && frontIn(mc.gameRenderer.getMainCamera().getPosition()) <= 0) {
            film.mark("front");
            frontMarkedIn = film.recording;
        }
    }

    static JsonArray lookUp(Vec3 at) {
        JsonArray a = Cast.steps();
        a.add(Cast.step("wait", 4));
        a.add(Cast.lookAt(at));
        return a;
    }

    /** Бот следит глазами за ближайшим снарядом этого типа (каждые 2 тика), не дольше {@code ticks} тиков. */
    private void trackLook(String bot, Class<? extends Entity> type, int ticks) {
        MinecraftServer server = server();
        int[] left = {ticks / 2};
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            var sp = cast().player(bot);
            if (sp == null || --left[0] < 0) return;
            Entity best = null;
            double bd = 300 * 300;
            for (Entity x : sp.serverLevel().getEntitiesOfClass(type, sp.getBoundingBox().inflate(300))) {
                double d = x.distanceToSqr(sp);
                if (d < bd) {
                    bd = d;
                    best = x;
                }
            }
            if (best != null) {
                JsonArray a = Cast.steps();
                a.add(Cast.lookAt(best.position()));
                cast().act(bot, a, true);
            }
            server.tell(new TickTask(server.getTickCount() + 2, step[0]));
        };
        step[0].run();
    }

    // ================================================================ I · рассвет и утро над Востоком

    private void dawn() {
        dawnFlight();
        dawnSquare();
    }

    /** p03: пролёт на высоте над Востоком к небоскрёбам, на которые ложится первое солнце. */
    private void dawnFlight() {
        film.run(() -> Film.cmd("time set 23300"));
        // высоко, под облаками (320 у съёмки): за план камера проходит ~100 блоков к башням центра, на них первое солнце
        hiddenAt(new Vec3(2870, 280, 320), T2);
        film.shot("p03").length(60).hidden().farView().camera(() -> {
            Vec3 look = new Vec3(3060, 200, 560);
            return CineCamera.glide(new Vec3(2830, 300, 290), new Vec3(2905, 296, 352), 60, () -> look, 52);
        });
    }

    /** p04: утро, площадь у госкомплекса — жители идут по своим делам (та же точка в p44: площадь горит и пуста). */
    private void dawnSquare() {
        film.run(() -> Film.cmd("time set 1000"));
        hiddenAt(SQUARE, GOV);
        film.onServer(level -> {
            RandomSource r = RandomSource.create(4);
            for (int i = 0, tries = 0; i < 40 && tries < 900; tries++) {
                double x = 3040 + r.nextDouble() * 170, z = 222 + r.nextDouble() * 100;
                int y = groundY(level, x, z);
                if (y > 66) continue;  // крыша, а не площадь
                crowd().add("Cit" + i++, new Vec3(x, y, z), 18);
            }
        });
        settle();
        film.shot("p04").length(40).hidden().camera(() -> {
            Vec3 a = g(3116.5, 306.5).add(0, 5.5, 0);
            return CineCamera.dolly(a, a.add(4, 0.3, -3), 40, () -> GOV, 50);
        });
        film.run(() -> crowd().removeAll());
    }

    // ================================================================ II · первый удар по Востоку, тревога

    private void firstStrike() {
        // p12: окраина — шахеды проходят низко над жителем со стороны затылка, он поднимает голову
        Vec3 resident = new Vec3(2905.5, 0, 418.5), droneTarget = new Vec3(3070.5, 0, 418.5);
        hiddenAt(resident, droneTarget);
        film.run(() -> cast().spawn("Hans", g(resident), -90, null));
        settle();
        film.shot("p12").length(45).hidden()
                .onReady(() -> salvo("drone", 3, 10, droneTarget, behind(resident, droneTarget, 700)))
                .camera(() -> CineCamera.shoulder(() -> film.bot("Hans"), 2.2, -0.35, 0.7, () -> at(droneTarget).add(0, 40, 0), 62))
                .when(() -> flightOver(at(resident), 70, WeaponType.DRONE), 3000)
                .cue(8, () -> cast().act("Hans", lookUp(at(droneTarget).add(0, 60, 0)), true));
        film.run(() -> cast().remove("Hans"));

        p13();

        alarm();
    }

    /**
     * p13: житель на плоской крыше (y 98) к северо-западу от центра и грань {@link #T2} в ~378 блоках, ниже слоя облаков
     * (ваниль — 192, шейдерпак — около 190; верх башни уходит в облака): лучом по блокам мира от глаза жителя грань видна
     * ({@code tools/trailer/ray.py}). Ближе к цели крыши с видом на башни есть, но сирена воет в 350 блоках от цели, а
     * здесь тревоги нет; с крыши {@link #ROOF} башни закрывает башня у площади в ~95 блоках.
     */
    static final Vec3 P13_ROOF = new Vec3(2862.5, 99, 194.5), P13_HIT = new Vec3(3071.5, 179, 501.5);

    private void p13() {
        // p13: тихое утро, житель на крыше, впереди небоскрёбы; ракета мелькает над крышами и входит в башню
        hiddenAt(P13_ROOF, P13_HIT);
        film.run(() -> cast().spawn("Ilya", P13_ROOF, Film.yawTo(P13_ROOF, P13_HIT), null));
        settle();
        film.shot("p13").length(140).hidden()
                // из-за спины камеры: ракета проходит над крышей и уходит в грань, которую видит камера
                .onReady(() -> salvo("missile", 1, 0, P13_HIT, behind(P13_ROOF, P13_HIT, 1200)))
                .camera(() -> {
                    Vec3 eye = P13_ROOF.add(0, 1.62, 0);
                    Vec3 back = eye.subtract(P13_HIT.subtract(eye).multiply(1, 0, 1).normalize().scale(3.4)).add(0.9, 0.35, 0);
                    // взгляд ниже грани: житель внизу кадра, удар — в верхней трети
                    return CineCamera.track(back, () -> P13_HIT.add(0, -40, 0), 38);
                })
                .when(() -> flightNear(P13_HIT, 340, WeaponType.MISSILE), 3000)
                .endWhen(hit(P13_HIT, 120), 40);
        film.run(() -> cast().remove("Ilya"));
    }

    /** p14–p19: тревога на улицах, метро, удары в городе. */
    private void alarm() {
        // p14: на улице сирена — следующие ракеты идут сюда; первый кадр с интерфейсом: ⚠ MISSILE THREAT ⚠ (отметка siren)
        Vec3 hit = new Vec3(3140.5, 0, 300.5);
        film.run(() -> place(AVENUE, at(hit).add(0, 40, 0)));
        film.waitTicks(40);
        film.shot("p14").length(110).hud()
                .onReady(() -> salvo("missile", 4, 30, hit, new Vec3(1500, 0, 300)))
                .player(t -> new Pose(Vec3.ZERO, Film.yawTo(AVENUE, hit) - 25 + (float) CineCamera.smooth(t / 110) * 30,
                        -30 + (float) CineCamera.smooth(t / 110) * 12, 0, 70))
                .when(() -> flightEta(at(hit), 60, WeaponType.MISSILE) <= MISSILE_SIREN_S + 1.5, 3000);  // тревога — через 1,5 с

        // p15: люди бегут к спуску в метро, FPV ныряет за ними вниз по лестнице
        metroCrowd();
        film.waitTicks(10);
        film.shot("p15").length(55).hidden()
                .camera(() -> CineCamera.fpv(List.of(
                        SUB_TOP.add(-24, 4, 0.2), SUB_TOP.add(-10, 2.4, 0), SUB_TOP.add(-1, 1.4, 0), SUB_TOP.add(6, -0.6, 0),
                        SUB_TOP.add(12, -4.6, 0), SUB_TOP.add(20, -10, 0), SUB_BOTTOM.add(0, 1.6, 0), SUB_BOTTOM.add(10, 1.6, 0)),
                        1.0, 5, 0.6, 0.05, 74));

        p16();

        // p18: FPV вырывается из метро на улицу, где ложится «Град», разворот над перекрёстком
        Vec3 crossing = new Vec3(3330.5, 0, 300.5);
        film.shot("p18").length(65).hidden()
                .onReady(() -> salvo("rocket", 12, 16, crossing, new Vec3(2750, 0, 300)))
                .camera(() -> {
                    Vec3 c = at(crossing);
                    return CineCamera.fpv(List.of(
                            SUB_BOTTOM.add(4, 1.6, 0), SUB_BOTTOM.add(-2, 1.6, 0), SUB_TOP.add(18, -8, 0), SUB_TOP.add(6, -1.4, 0),
                            SUB_TOP.add(-2, 2.5, 0), SUB_TOP.add(-12, 7, 8), c.add(-4, 9, -10), c.add(12, 11, 6),
                            c.add(30, 12, 2)), 1.6, 7, 0.9, 0.08, 78);
                })
                .when(() -> film.nearest(RocketEntity.class, at(crossing), 220) != null, 3000);
        film.run(() -> crowd().removeAll());
        gridRestore();

        // p17: из квартиры через окно: вспышка удара, стекло влетает в комнату
        Vec3 room = new Vec3(3060.5, 0, 352.5);
        // на тротуаре перед окном; ракета идёт вдоль улицы с востока (с запада её пике задевает дом в 98 блоков высотой)
        Vec3 blast = new Vec3(3062.5, 0, 338.5);
        hiddenAt(room, blast);
        film.onServer(level -> buildRoom(level, room));
        film.shot("p17").length(40).hidden()
                .onReady(() -> salvo("missile", 1, 0, blast, new Vec3(3900, 0, 338.5)))
                .camera(() -> {
                    Vec3 floor = new Vec3(room.x, roomFloor, room.z);
                    return CineCamera.dolly(floor.add(0.2, 1.55, 1.4), floor.add(0.1, 1.57, 1.1), 40, () -> at(blast).add(0, 2, 0), 70);
                })
                .when(() -> flightNear(at(blast), 60, WeaponType.MISSILE), 3000);

        // p19: со спины человека на крыше: горизонт вспыхивает ударами (a), ракеты проходят над головой (b). Крыша — та же,
        // что в p13 (оттуда башни видны ниже облаков); удары — в грани, которые камера видит, по лучам в момент пуска
        Vec3 roof19 = P13_ROOF, farA = T2.add(0, -75, 0);
        Vec3 farB = roof19.add(farA.subtract(roof19).multiply(1, 0, 1).normalize().scale(700)).multiply(1, 0, 1);  // за башнями на той же линии
        List<Vec3> towers = new ArrayList<>();
        hiddenAt(roof19, farA);
        film.run(() -> cast().spawn("Oleg", roof19, Film.yawTo(roof19, farA), null));
        settle();
        film.shot("p19a").length(50).hidden()
                .onReady(() -> {
                    Pose cam = CineCamera.pose();
                    towers.clear();
                    towers.addAll(facades(cam == null ? roof19.add(0, 2, 0) : cam.pos(), farA, 40, 4, 200, 380, 185));
                    // сбоку от камеры, не над ней (над головой — в b): подлёт виден поперёк кадра; 900–1035 блоков — удары
                    // один за другим через ~10 тиков
                    Vec3 side = new Vec3(-(farA.z - roof19.z), 0, farA.x - roof19.x).normalize();
                    for (int i = 0; i < towers.size(); i++) {
                        Vec3 t = towers.get(i);
                        salvo("missile", 1, 0, t, behind(roof19, t, 900 + i * 45).add(side.scale((i % 2 == 0 ? 1 : -1) * 350)));
                    }
                })
                // 50 мм: камера в 5 блоках за спиной (плечо у края кадра, горизонт крупнее вдвое против 26 мм)
                .camera(() -> CineCamera.shoulder(() -> film.bot("Oleg"), 5, 0.8, 1.6, () -> farA, Rig.fov(50)))
                .when(() -> !towers.isEmpty() && flightNear(towers.get(0), 160, WeaponType.MISSILE), 3000);
        film.run(() -> cast().act("Oleg", lookUp(farA), true));
        film.shot("p19b").length(50).hidden()
                .onReady(() -> salvo("missile", 4, 20, farB, behind(roof19, at(farB), 900)))
                .camera(() -> CineCamera.shoulder(() -> film.bot("Oleg"), 5, 0.8, 1.6, () -> farA.add(0, 30, 0), Rig.fov(50)))
                .when(() -> flightNear(roof19, 160, WeaponType.MISSILE), 3000)
                .cue(10, () -> cast().act("Oleg", lookUp(farA.add(0, 120, 0)), true));
        film.run(() -> cast().remove("Oleg"));
    }

    // ================================================================ Запад: батарея, пуски

    /** Батарея на полосе: ряд крылатых ракет и ряд «Градов» с шахедами; ракеты и шахеды — по Востоку за 4,5 км. */
    /** p15–p16: люди на улице у спуска в метро бегут вниз; станция переведена на лампы сети ({@link #wireStation}). */
    private void metroCrowd() {
        hiddenAt(SUB_TOP.add(-30, 0, 0), SUB_TOP);
        film.onServer(level -> {
            RandomSource r = RandomSource.create(15);
            for (int i = 0, tries = 0; i < 25 && tries < 500; tries++) {
                double x = SUB_TOP.x - 12 - r.nextDouble() * 24, z = SUB_TOP.z - 6 + r.nextDouble() * 12;
                int y = groundY(level, x, z);
                if (y > SUB_TOP.y + 2) continue;
                crowd().add("Run" + i++, new Vec3(x, y, z), 3);
            }
            wireStation(level);
        });
        settle();
        film.run(() -> film.withServer(s -> crowd().flee(SUB_TOP, 60, SUB_BOTTOM.add(6, 0, 0))));
    }

    /**
     * p16: метро изнутри, среди укрывшихся: удары наверху доходят глухо, ракета выбивает узел сети над станцией —
     * станция гаснет (её квартал сети — один), светит только спуск с улицы. Лампы в кадре — по данным (LampsQa).
     * Узел — на улице над станцией, среди высоток: ракета заходит туда, где над последним участком рельеф ниже всего
     * ({@link #clearApproach}, маршрутом через точку), иначе бьёт в верх высоток с недолётом и узел цел. Камера — у
     * укрывшихся, туда, где в кадре больше всего горящих ламп станции ({@link #stationView}): от спуска к лестнице их в
     * кадре две (запись на ноутбуке и netcup), гаснущей станции так не видно.
     */
    private void p16() {
        Vec3 above = SUB_BOTTOM.add(4, 17, 0);
        gridNode(above, 128);
        film.run(this::stationView);
        film.shot("p16").length(60).hidden()
                .onReady(() -> missileInto("p16", 2, 4, above, new Vec3(2000, 0, 270)))
                .camera(this::stationCamera)
                .when(() -> flightNear(above, 110, WeaponType.MISSILE), 3000)
                .cue(0, () -> lampsFrom("p16", STATION_RANGE, 0.9))
                .cueEnd(this::lampsEnd);
    }

    /**
     * Место камеры p16 у укрывшихся (до 16 блоков от места, куда они сбежали) и направление, где в кадре горящих ламп
     * сети станции с запасом и больше всего людей ({@link LampsQa#bestView}); меньше {@link LampsQa#MIN} — WARN.
     */
    private void stationView() {
        // люди — там, куда бегут (metroCrowd): в части east план идёт сразу за p15, и часть их ещё на лестнице
        Vec3 shelter = SUB_BOTTOM.add(6, 1.2, 0);
        List<Vec3> people = new ArrayList<>();
        for (var p : mc.level.players()) {
            if (!p.getGameProfile().getName().startsWith("Run")) continue;
            Vec3 chest = p.position().add(0, 1.2, 0);
            people.add(chest.distanceTo(shelter) < 4 ? chest : shelter);
        }
        stationView = LampsQa.bestView(mc, shelter, 16, STATION_RANGE, STATION_FOV, people);
        if (stationView == null || stationView.lamps() < LampsQa.MIN) {
            Incoming.LOG.warn("SCENARIO film staging: p16 — ни с одного места у укрывшихся в кадре нет {} горящих ламп сети (лучший вид {})",
                    LampsQa.MIN, stationView);
        } else {
            Incoming.LOG.info("SCENARIO film staging: p16 — камера {} курс {} наклон {}: ламп сети в кадре {}, людей {}", stationView.eye(),
                    stationView.yaw(), stationView.pitch(), stationView.lamps(), stationView.people());
        }
    }

    /** Камера p16: из выбранного места отъезд на 1,5 блока назад (вперёд, если сзади стена); мест нет — прежняя, к лестнице. */
    private CineCamera.Path stationCamera() {
        LampsQa.View v = stationView;
        if (v == null) {
            return CineCamera.dolly(SUB_BOTTOM.add(6, 1.6, 0), SUB_BOTTOM.add(7.5, 1.55, 0), 60, () -> SUB_BOTTOM.add(-8, 3, 0), STATION_FOV);
        }
        Vec3 f = v.forward(), at = v.eye().add(f.scale(12)), flat = new Vec3(f.x, 0, f.z).normalize().scale(1.5);
        Vec3 to = open(v.eye(), v.eye().subtract(flat)) ? v.eye().subtract(flat) : open(v.eye(), v.eye().add(flat)) ? v.eye().add(flat) : v.eye();
        return CineCamera.dolly(v.eye(), to, 60, () -> at, STATION_FOV);
    }

    /** Между точками нет блоков (с запасом на ширину камеры). */
    private boolean open(Vec3 a, Vec3 b) {
        Vec3 d = b.subtract(a).normalize().scale(0.4);
        return mc.level.clip(new ClipContext(a, b.add(d), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getType()
                == HitResult.Type.MISS;
    }

    private void racks() {
        hiddenAt(BATTERY, BATTERY.add(60, 0, 0));
        film.onServer(level -> {
            missileRack.clear();
            rocketRack.clear();
            droneRack.clear();
            for (int i = 0; i < 10; i++) missileRack.add(BlockPos.containing(-1528.5 + i * 9, BATTERY_Y, ROW_MISSILE));
            for (int i = 0; i < 5; i++) rocketRack.add(BlockPos.containing(-1528.5 + i * 18, BATTERY_Y, ROW_ROCKET));
            for (int i = 0; i < 5; i++) droneRack.add(BlockPos.containing(-1519.5 + i * 18, BATTERY_Y, ROW_ROCKET));
            Vec3 rocketAt = new Vec3(ROCKET_TARGET.x, groundY(level, ROCKET_TARGET.x, ROCKET_TARGET.z), ROCKET_TARGET.z);
            for (int i = 0; i < missileRack.size(); i++) {
                double x = 3000.5 + i * 25, z = 380.5 + (i % 3) * 60;
                setLauncher(missileRack.get(i), "missile 1 0", new Vec3(x, groundY(level, x, z), z), 6);
            }
            for (BlockPos p : rocketRack) setLauncher(p, "rocket 40 40", rocketAt, 3);
            for (BlockPos p : droneRack) setLauncher(p, "drone 2 30", new Vec3(3250.5, groundY(level, 3250.5, 350.5), 350.5), 6);
        });
    }

    private void battery() {
        racks();
        // солдаты батареи
        film.run(() -> {
            for (int i = 0; i < 8; i++) {
                Vec3 at = new Vec3(-1538.5 - (i % 2) * 1.2 - i * 1.8, BATTERY_Y, 524.5 + (i % 2) * 1.6);
                cast().spawn("Gunner" + i, at, -90, null);
            }
        });
        settle();
        film.onServer(level -> {
            for (int i = 0; i < 8; i++) film.armSoldier("Gunner" + i, 0);
        });
        film.waitTicks(40);

        // p06: колонна идёт вдоль ряда пусковых
        film.run(() -> film.withServer(s -> {
            for (int i = 0; i < 8; i++) {
                JsonArray a = Cast.steps();
                a.add(Cast.walkTo(-1440.5 - i * 1.8, 524.5 + (i % 2) * 1.6, false));
                cast().act("Gunner" + i, a, true);
            }
        }));
        film.shot("p06").length(40).hidden()
                .camera(() -> CineCamera.glide(new Vec3(-1532, 71.5, 509), new Vec3(-1520, 71.1, 509.8), 40,
                        () -> new Vec3(-1480, 69, 525), 58));

        // p07, p08, p09: наводчик на крыше Востока — бинокль на небоскрёб в ~390 блоках (бинокль берёт цель до 400), карта
        // наведения, пульт. Пульт привязан к 10 пусковым ракет Запада: ЛКМ и кнопка пульта ставят им задачу (LauncherLinks)
        Vec3 eye07 = OBSERVER.add(0, 1.62, 0);
        Vec3[] aim07 = {new Vec3(T2.x, UNDER_CLOUDS, T2.z)};
        actor(OBSERVER, aim07[0], "incoming:strike_designator[incoming:loadout={weapon:\"missile\",count:10,spread:30}]");
        film.run(() -> film.withPlayer((level, sp) -> sp.getMainHandItem().set(ModDataComponents.LAUNCHERS.get(),
                missileRack.stream().map(p -> GlobalPos.of(Level.OVERWORLD, p)).toList())));
        film.waitTicks(10);
        film.shot("p07").length(45).hud().readyChunks(26).distant()
                .onReady(() -> aim07[0] = towerFace(eye07, T2))
                .player(t -> new Pose(Vec3.ZERO, Film.yawTo(OBSERVER, aim07[0]), Film.pitchTo(eye07, aim07[0]), 0, 70))
                .cue(0, () -> mc.options.keyUse.setDown(true))
                .cueEnd(() -> mc.options.keyUse.setDown(false));
        // p08: карта открывается на небоскрёбе (метка — на нём), курсор подходит к метке и щёлкает: цель пульта — место на карте
        film.shot("p08").length(50).hud().player(t -> new Pose(Vec3.ZERO, Film.yawTo(OBSERVER, aim07[0]), 4, 0, 70))
                .prepare(() -> {
                    MapTarget.set(mc.level, new MapTarget.Place(aim07[0].x, aim07[0].z));
                    MapScreen.reset();  // иначе карта открылась бы там, где её открывали в этом мире раньше
                })
                .cue(0, () -> {
                    mc.setScreen(new MapScreen(new RemoteScreen()));
                    Cursor.show(new double[]{mc.getWindow().getGuiScaledWidth() * 0.72, mc.getWindow().getGuiScaledHeight() * 0.78});
                })
                .cue(6, () -> Cursor.moveTo(mapPoint(aim07[0]), 22))
                .cue(34, () -> Cursor.click(mc))
                .cueEnd(() -> {
                    Cursor.hide();
                    mc.setScreen(null);
                });
        // p09: пульт — курсор идёт к кнопке задачи пусковым («MISSION (10)»: «Огонь» пульта с привязанными пусковыми) и
        // встаёт на ней, кнопка подсвечена. Щелчка нет: он дал бы пусковым новую цель, а пуски плана 10 и удары дальше
        // идут по целям, которые им дал сценарий (racks)
        film.shot("p09").length(50).hud().player(t -> new Pose(Vec3.ZERO, Film.yawTo(OBSERVER, aim07[0]), 4, 0, 70))
                .cue(0, () -> {
                    mc.setScreen(new RemoteScreen());
                    Cursor.show(new double[]{mc.getWindow().getGuiScaledWidth() * 0.62, mc.getWindow().getGuiScaledHeight() * 0.42});
                })
                .cue(4, () -> {
                    double[] b = Cursor.widget(mc, Component.translatable("incoming.remote.assign", "").getString().split("\\(")[0]);
                    if (b == null) Incoming.LOG.warn("SCENARIO film staging p09: на пульте нет кнопки задачи пусковым");
                    else Cursor.moveTo(new double[]{b[0] - 18, b[1] + 3}, 20);
                })
                .cueEnd(() -> {
                    Cursor.hide();
                    mc.setScreen(null);
                });

        // p10a: крылатые ракеты сходят с пусковых и сбрасывают ускорители — длинный фокус (85 мм) с конца ряда, взгляд
        // ведёт новейшую ракету: ряд сжат в кадре, ракеты уходят одна за другой
        film.shot("p10a").length(40).hidden()
                .onReady(() -> fireAll(missileRack))
                .camera(() -> CineCamera.track(new Vec3(-1552, 70.2, 507),
                        film.smoothFocus(() -> film.newest(CruiseMissileEntity.class), new Vec3(-1470, 76, ROW_MISSILE), 0.25), Rig.fov(85)))
                .when(() -> film.nearest(CruiseMissileEntity.class, BATTERY, 120) != null, 400);
        // p10b: «Град» выпускает 40 ракет
        film.shot("p10b").length(40).hidden()
                .onReady(() -> fireAll(rocketRack))
                .camera(() -> {
                    // издали вдоль полосы (по x), длинный фокус: пакет — снизу кадра, ракеты уходят вверх через кадр
                    Vec3 l = Vec3.atBottomCenterOf(rocketRack.get(2));
                    return CineCamera.track(l.add(-110, 2.5, -12), () -> l.add(8, 12, 0), Rig.fov(100));
                })
                .when(() -> film.nearest(RocketEntity.class, BATTERY, 120) != null, 400);
        // p10c и p11: шахеды уходят со стойки; FPV за шахедом
        film.shot("p10c").length(40).hidden()
                .onReady(() -> fireAll(droneRack))
                .camera(() -> {
                    // издали вдоль полосы, длинный фокус (85 мм): взгляд ведёт новейший шахед со стойки
                    Vec3 l = Vec3.atBottomCenterOf(droneRack.get(2));
                    return CineCamera.track(l.add(-90, 2, -10), film.smoothFocus(() -> film.newest(DroneEntity.class), l.add(3, 3, 0), 0.3), Rig.fov(85));
                })
                .when(() -> film.nearest(DroneEntity.class, BATTERY, 120) != null, 600);
        film.shot("p11").length(60).hidden().noPrep()
                .after(() -> film.newest(DroneEntity.class) != null, 200)
                .camera(() -> film.chaseOf(film.newest(DroneEntity.class), 7, 2.4, -2.6, 16, 4, 62));

        // p20: вся линия разом — кран над батареей с юго-востока, выше холма и дыма пусков (ряды идут по диагонали кадра)
        film.run(this::reloadAll);
        film.waitTicks(60);
        film.shot("p20").length(50).hidden()
                .onReady(() -> {
                    fireAll(missileRack);
                    fireAll(rocketRack);
                    fireAll(droneRack);
                })
                .camera(() -> CineCamera.dolly(new Vec3(-1440, 108, 575), new Vec3(-1446, 122, 580), 50,
                        () -> new Vec3(-1490, 70, 525), 64))
                .when(() -> film.nearest(RocketEntity.class, BATTERY, 140) != null, 400);
        // p01c: «Град» изнутри — сорок огненных хвостов вокруг камеры
        film.run(this::reloadAll);
        film.waitTicks(60);
        film.shot("p01c").length(35).hidden()
                .onReady(() -> fireAll(List.of(rocketRack.get(1))))
                .camera(() -> {
                    Vec3 l = Vec3.atBottomCenterOf(rocketRack.get(1));
                    return CineCamera.track(l.add(-6, 5, 2), () -> l.add(30, 20, 0), 84);
                })
                .when(() -> film.nearest(RocketEntity.class, BATTERY, 140) != null, 400);

        // p35: камера низко между рядами, к востоку от стреляющих: ракеты проносятся над ней и роняют ускорители
        // (стреляет западная половина ряда — дым пусков остаётся в 20 блоках от камеры)
        film.shot("p35").length(35).hidden()
                .onReady(() -> fireAll(missileRack.subList(0, 4)))
                .camera(() -> CineCamera.track(new Vec3(-1470, 69.5, BATTERY.z), () -> new Vec3(-1510, 85, ROW_MISSILE), 70))
                .when(() -> film.nearest(CruiseMissileEntity.class, BATTERY, 120) != null, 400);

        // p36: ответ Востока — шахеды накрывают батарею, расчёт бежит от пусковых
        film.shot("p36").length(50).hidden()
                .onReady(() -> salvo("drone", 6, 24, BATTERY.add(10, 0, 0), new Vec3(-800, 0, 560)))
                // восточный край полосы, 70 мм: взгляд ведёт шахед, ближайший к батарее, после удара — на батарею
                .camera(() -> CineCamera.track(new Vec3(-1445.5, 72, 540.5),
                        film.smoothFocus(() -> film.nearest(DroneEntity.class, BATTERY, 400), BATTERY.add(0, 4, 0), 0.25), Rig.fov(70)))
                .when(() -> film.nearest(DroneEntity.class, BATTERY, 70) != null, 3000)
                .cue(0, () -> film.withServer(s -> {
                    for (int i = 0; i < 8; i++) {
                        JsonArray a = Cast.steps();
                        a.add(Cast.walkTo(-1560.5 - i * 2, 545.5 + i, true));
                        cast().act("Gunner" + i, a, true);
                    }
                }));
        film.run(() -> {
            for (int i = 0; i < 8; i++) cast().remove("Gunner" + i);
        });
        stageScene("factory", new Vec3(-1500.5, 68, 620.5), -90);
    }

    /**
     * Узел сети на копии карты для съёмки (своей сети у Zearth нет): взрыв рядом выбивает его, и район {@code radius}
     * гаснет каскадом мода ({@code Blackouts}). Высота y = 0 — земля. Узлы снимает {@link #gridRestore}.
     */
    private void gridNode(Vec3 p, int radius) {
        film.onServer(level -> {
            BlockPos at = BlockPos.containing(p.x, p.y <= 0 ? groundY(level, p.x, p.z) : p.y, p.z);
            gridNodes.add(PowerGrid.get(level).addNode(at, radius, false).id());
        });
    }

    /** Свет — обратно (за 10 с, как командой {@code grid restore}), узлы съёмки — прочь: дальше город снова в огнях. */
    private void gridRestore() {
        film.onServer(level -> {
            Blackouts.restore(level, null, 0);
            gridNodes.forEach(id -> PowerGrid.get(level).removeNode(id));
            gridNodes.clear();
        });
    }

    /** Лампы сети в кадре плана — с его начала ({@link LampsQa}); к концу должна погаснуть доля {@code need}. */
    private void lampsFrom(String shot, double range, double need) {
        Pose pose = CineCamera.pose();
        if (pose == null) return;
        lampsQa = new LampsQa(shot, need);
        lampsQa.scan(mc, pose, range);
    }

    private void lampsEnd() {
        if (lampsQa == null) return;
        lampsQa.close(film.recordDir());
        lampsQa = null;
    }

    /**
     * Свет станции метро — в сети: факелы и красные факелы при блэкауте горят (не в сети мода), поэтому на копии карты
     * для съёмки факел на полу становится фонарём, на стене — стержнем Края из той же стены. Без обновления соседей:
     * фонарь не проверяет опору, красные цепи не трогаются. Чанки станции грузятся сразу (до записи плана).
     */
    private static int wireStation(ServerLevel level) {
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int cx = STATION_MIN.getX() >> 4; cx <= STATION_MAX.getX() >> 4; cx++) {
            for (int cz = STATION_MIN.getZ() >> 4; cz <= STATION_MAX.getZ() >> 4; cz++) {
                LevelChunk c = level.getChunk(cx, cz);
                for (int x = Math.max(cx * 16, STATION_MIN.getX()); x <= Math.min(cx * 16 + 15, STATION_MAX.getX()); x++) {
                    for (int z = Math.max(cz * 16, STATION_MIN.getZ()); z <= Math.min(cz * 16 + 15, STATION_MAX.getZ()); z++) {
                        for (int y = STATION_MIN.getY(); y <= STATION_MAX.getY(); y++) {
                            BlockState s = c.getBlockState(p.set(x, y, z));
                            BlockState to = s.is(Blocks.TORCH) || s.is(Blocks.REDSTONE_TORCH) ? Blocks.LANTERN.defaultBlockState()
                                    : s.is(Blocks.WALL_TORCH) || s.is(Blocks.REDSTONE_WALL_TORCH)
                                    ? Blocks.END_ROD.defaultBlockState().setValue(DirectionalBlock.FACING, s.getValue(HorizontalDirectionalBlock.FACING))
                                    : null;
                            if (to == null) continue;
                            level.setBlock(p, to, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                            n++;
                        }
                    }
                }
            }
        }
        Incoming.LOG.info("SCENARIO film staging: станция метро — {} факелов заменено лампами сети", n);
        return n;
    }

    /**
     * Ждать, пока вошли все боты: вход с местом ждёт чанков в фоне (до 30 с), и шаги ботам до входа уходят в пустоту.
     * Бот считается вошедшим, когда клиент его видит (все боты сцен — рядом с камерой).
     */
    private void settle() {
        film.waitUntil(() -> {
            Cast.joining.removeIf(n -> film.bot(n) != null);
            return Cast.joining.isEmpty();
        }, 900);
        film.run(Cast.joining::clear);  // не вошёл за 45 с — не ждём его и в следующий раз
        film.waitTicks(20);
    }

    /** ЗРК сцены убираются после неё: иначе они сбивают шахеды следующих планов (вокруг — весь Восток). */
    private void clearSams(Vec3 center, int radius) {
        film.onServer(level -> {
            int cx = Mth.floor(center.x) >> 4, cz = Mth.floor(center.z) >> 4, rc = (radius >> 4) + 1;
            for (int x = cx - rc; x <= cx + rc; x++) {
                for (int z = cz - rc; z <= cz + rc; z++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                    if (chunk == null) continue;
                    for (BlockPos p : List.copyOf(chunk.getBlockEntities().keySet())) {
                        if (chunk.getBlockEntity(p) instanceof SamBlockEntity) level.removeBlock(p, false);
                    }
                }
            }
        });
    }

    private void setLauncher(BlockPos p, String mission, Vec3 target, int load) {
        String at = p.getX() + " " + p.getY() + " " + p.getZ();
        cast().command("setblock " + at + " incoming:fixed_launcher");
        cast().command("incoming launcher " + at + " mission " + mission + " " + xyz(target));
        cast().command("incoming launcher " + at + " load " + load);
    }

    private void fireAll(List<BlockPos> rack) {
        for (BlockPos p : rack) Film.cmd("incoming launcher " + p.getX() + " " + p.getY() + " " + p.getZ() + " fire");
    }

    private void reloadAll() {
        for (List<BlockPos> rack : List.of(missileRack, rocketRack, droneRack)) {
            for (BlockPos p : rack) Film.cmd("incoming launcher " + p.getX() + " " + p.getY() + " " + p.getZ() + " load 6");
        }
    }

    /**
     * Сцена из {@link Scene#ALL} (цех, дирижабль, аэродром), если она есть в этой сборке. Шаги сцены встают в очередь
     * при постановке, поэтому место — готовыми числами (первый блок над землёй), а не по миру.
     */
    private void stageScene(String name, Vec3 at, float yaw) {
        Scene scene = Scene.ALL.get(name);
        if (scene == null) return;
        hiddenAt(at, at.add(0, 0, 20));
        scene.stage(film, at, yaw);
    }

    // ================================================================ волна 2: небо

    private void sky() {
        // p22: ЗРК у площади: перехватчики уходят вертикально, шахед разлетается; второй дубль — с другой стороны
        Vec3 samA = new Vec3(3200.5, 0, 242.5), samB = new Vec3(3206.5, 0, 242.5);
        hiddenAt(samA, SQUARE);
        film.onServer(level -> {
            for (Vec3 s : List.of(samA, samB)) {
                BlockPos p = BlockPos.containing(s.x, groundY(level, s.x, s.z), s.z);
                level.setBlockAndUpdate(p, ModBlocks.SAM.get().defaultBlockState());
                if (level.getBlockEntity(p) instanceof SamBlockEntity sam) sam.load(new ItemStack(ModItems.INTERCEPTOR.get(), 32));
            }
        });
        // 12 шахедов на два ЗРК (по 4 ракеты на направляющих, перезарядка 8 с): часть сбита, часть проходит и бьёт у площади.
        // Залп шахедов выходит по одному раз в 1–2 с, поэтому — четыре залпа по три с пусковых рядом (параллельные потоки):
        // подходят почти разом, направляющие пустеют раньше, чем подходит последний
        Vec3 drones = new Vec3(3170.5, 0, 250.5);
        for (String take : new String[]{"a", "b"}) {
            // a — у самих стаканов, широко: пуск и уход вверх; b — издали, длинный фокус: перехват
            boolean near = take.equals("a");
            Vec3 from = near ? samA.add(-8, 0, 10) : new Vec3(3218.5, 0, 270.5);  // площадь, без домов и деревьев
            film.shot("p22" + take).length(50).hidden()
                    .onReady(() -> {
                        for (int i = 0; i < 4; i++) salvo("drone", 3, 30, drones, new Vec3(2400, 0, 190 + i * 40));
                    })
                    .camera(() -> {
                        Vec3 c = g(from).add(0, near ? 1.2 : 1.6, 0);
                        // a — за перехватчиком; b — за шахедом, ближайшим к цели: перехваты вокруг него и прорыв до земли
                        return CineCamera.track(c, film.smoothFocus(() -> {
                            InterceptorEntity i = film.nearest(InterceptorEntity.class, at(drones), 300);
                            DroneEntity d = film.nearest(DroneEntity.class, at(drones), 300);
                            return near ? (i != null ? i : d) : (d != null ? d : i);
                        }, near ? at(samA).add(0, 3, 0) : at(drones).add(0, 20, 0), 0.25), near ? 62 : 34);
                    })
                    .when(() -> film.nearest(InterceptorEntity.class, at(drones), 300) != null, 3000);
        }

        clearSams(SQUARE, 96);

        // p23: дирижабль (своя сцена)
        Vec3 airship = new Vec3(3000.5, 150, 380.5);  // южнее T2: заход ракеты не задевает башню
        stageScene("airship", airship, -90);
        clearSams(airship, 160);

        // p25: из двора, длинный фокус на рой: шахеды идут над крышами к камере. Залп шахедов выходит по одному раз
        // в 1–2 с, поэтому рой — пять залпов с пусковых рядом (параллельные потоки), а не один в 30 штук; камера
        // держит середину ближайших шести
        Vec3 swarm = new Vec3(3250.5, 0, 560.5), street = new Vec3(3058.5, 0, 378.5);
        hiddenAt(street, swarm);
        film.shot("p25").length(60).hidden()
                .onReady(() -> {
                    Vec3 dir = at(swarm).subtract(at(street)).multiply(1, 0, 1).normalize(), side = new Vec3(-dir.z, 0, dir.x);
                    for (int i = -2; i <= 2; i++) salvo("drone", 6, 40, swarm, behind(street, at(swarm), 900).add(side.scale(i * 40)));
                })
                .camera(() -> {
                    Vec3 c = g(street).add(0, 1.4, 0);
                    Vec3 dir = at(swarm).subtract(c).multiply(1, 0, 1).normalize();
                    return CineCamera.track(c, film.smoothFocus(() -> swarmCentre(c, 400, 6, WeaponType.DRONE),
                            c.subtract(dir.scale(100)).add(0, 36, 0), 0.2), 34);
                })
                .when(() -> flightOver(at(street), 220, WeaponType.DRONE), 3000);

        // p01d: шахеды над головой игрока со стороны затылка
        // поле к западу от центра: над ним открытое небо
        Vec3 man = new Vec3(2905.5, 0, 300.5), past = new Vec3(3085.5, 0, 300.5);
        hiddenAt(man, past);
        film.run(() -> cast().spawn("Pyotr", g(man), Film.yawTo(man, past), null));
        settle();
        film.shot("p01d").length(40).hidden()
                .onReady(() -> salvo("drone", 5, 14, past, behind(man, at(past), 700)))
                .camera(() -> CineCamera.shoulder(() -> film.bot("Pyotr"), 1.9, -0.2, 0.6, () -> at(past).add(0, 55, 0), 70))
                .when(() -> flightOver(at(man), 60, WeaponType.DRONE), 3000)
                .cue(6, () -> cast().act("Pyotr", lookUp(at(past).add(0, 90, 0)), true));
        film.run(() -> cast().remove("Pyotr"));

        // p01a, p01b: FPV с крылатой ракетой между небоскрёбами — и удар в башню
        hiddenAt(T2.add(-200, -150, 0), T2);
        film.shot("p01a").length(45).hidden()
                .onReady(() -> salvo("missile", 1, 0, T2, new Vec3(2500, 0, 520)))
                .camera(() -> CineCamera.chase(() -> film.nearest(CruiseMissileEntity.class, T2, 500), T2.add(-250, -40, 20),
                        new Vec3(1, 0, 0), 9, 1.8, 2.4, 20, 0, 66))
                .when(() -> film.nearest(CruiseMissileEntity.class, T2, 200) != null, 3000)
                .endWhen(hit(T2, 300), 10);
        hiddenAt(T3.add(70, -150, -80), T3);
        film.shot("p01b").length(60).hidden()
                .onReady(() -> salvo("missile", 1, 0, T3, new Vec3(2350, 0, 600)))
                .camera(() -> CineCamera.track(new Vec3(T3.x + 70, T3.y - 30, T3.z - 80), () -> T3, 42))
                .when(() -> flightNear(T3, 200, WeaponType.MISSILE), 3000);

        ridge();

        // p24 и пик: аэродром — бипланы взлетают по тревоге (своя площадка)
        stageScene("airfield", AIRFIELD, -90);
    }

    /**
     * Хребет к востоку от города (плато ~109 между городом и морем): камера поднимается из травы на краю плато и
     * открывает северо-восток города, рой шахедов идёт из-за хребта над камерой к домам. Начало подъёма: склон и кусты
     * закрывают город (лучи: трава в 2–11 блоках), конец — над кронами, город целиком. Камера в начале — в 0,6 блока над
     * травой (проверка камеры не пускает её ближе полублока к твёрдому блоку). Рой идёт на 45 и 72 блока выше хребта,
     * поэтому взгляд — на 12° вверх ({@code RIDGE_SKY}): с 35 мм в кадре от −7° до 31°, внизу дома в огне (от ~105 до
     * ~190), выше — небо, куда рой входит сверху в 60–90 блоках за камерой.
     */
    static final Vec3 RIDGE_LOW = new Vec3(3614, 110.6, -60), RIDGE_HIGH = new Vec3(3585, 132, -63), RIDGE_LOOK = new Vec3(3360, 136, -35),
            RIDGE_SKY = new Vec3(3360, 180, -35);

    /**
     * p21: дома сперва поджигают две ракеты (до плана: в кадре уже огонь и дым), рой — 12 шахедов; все — из-за хребта
     * через камеру, иначе ракета с запада бьёт в обратную сторону дома. Места ударов — грани домов, которые камера видит
     * из конца подъёма.
     */
    private void ridge() {
        hiddenAt(RIDGE_HIGH, RIDGE_LOOK);
        List<Vec3> marks = new ArrayList<>();
        BooleanSupplier[] landed = {null};
        film.shot("p21").length(170).hidden()
                .onReady(() -> {
                    marks.addAll(facades(RIDGE_HIGH, RIDGE_LOOK, 40, 8, 120, 330, 185));
                    if (marks.isEmpty()) marks.add(RIDGE_LOOK);
                    for (int i = 0; i < Math.min(2, marks.size()); i++) salvo("missile", 1, 0, marks.get(i), behind(RIDGE_HIGH, marks.get(i), 900));
                    // рой — одиночными пусками: у залпа каждый снаряд заходит с курса ±35° и с разбросом бьёт в землю под
                    // точкой, а одиночный идёт ровно с места пуска на свою грань — через камеру; пуски через 4 тика — поток
                    List<Vec3> swarm = marks.size() > 2 ? marks.subList(2, marks.size()) : marks;
                    for (int i = 0; i < 12; i++) {
                        Vec3 face = swarm.get(i % swarm.size());
                        Vec3 dir = face.subtract(RIDGE_HIGH).multiply(1, 0, 1).normalize(), side = new Vec3(-dir.z, 0, dir.x);
                        Vec3 from = behind(RIDGE_HIGH, face, 900).add(side.scale((i % 3 - 1) * 12));
                        later(4 * i, () -> salvo("drone", 1, 0, face, from));
                    }
                })
                // начало — первый шахед в 50 блоках за камерой (над головой он выше кадра), подъём над кронами — 2 с;
                // монтаж берёт 2 с до первого удара роя: по журналу записи в кадре сверху входят 4–12 шахедов, удар —
                // чуть ниже середины кадра
                .camera(() -> CineCamera.dolly(RIDGE_LOW, RIDGE_HIGH, 40, () -> RIDGE_SKY, 37.8))
                .when(() -> flightPast(RIDGE_HIGH, RIDGE_LOOK, 50, WeaponType.DRONE), 3000)
                .endWhen(() -> {
                    if (marks.isEmpty()) return false;
                    if (landed[0] == null) landed[0] = hit(marks.get(marks.size() > 2 ? 2 : 0), 150);
                    return landed[0].getAsBoolean();
                }, 40);
    }

    // ================================================================ волна 3: улица

    /** Бой у южного края площади: Запад с запада, Восток с востока, укрытия — баррикады. */
    private void fight() {
        Vec3 c = SQUARE;
        hiddenAt(c, c.add(30, 0, 0));
        film.onServer(level -> {
            // бой и статисты — с сервером, а он есть только после входа в мир
            street = new Fight(cast(), server());
            street.spread = 4;
            // три линии на сторону по 8 бойцов: задние — на 6 и 12 блоков дальше и сдвинуты вдоль фронта (стреляют мимо
            // своих); вся линия — на открытой площади (x 3130–3220, z 240–300)
            for (int rank = 0, n = 0; rank < 3; rank++) {
                for (int i = 0; i < 8; i++, n++) {
                    double z = c.z - 24 + i * 7 + (i % 2) * 1.5 + rank * 7 / 3.0;
                    double back = 26 + (i % 3) * 3 + rank * 6;
                    double wx = c.x - back, ex = c.x + back;
                    Vec3 w = new Vec3(wx, groundY(level, wx, z), z), e = new Vec3(ex, groundY(level, ex, z + 2), z + 2);
                    barricade(w.add(2, 0, 0));
                    barricade(e.add(-2, 0, 0));
                    cast().spawn("West" + n, w, -90, null);
                    cast().spawn("East" + n, e, 90, null);
                    street.add("West" + n, 0, w, new Vec3(1, 0, 0));
                    street.add("East" + n, 1, e, new Vec3(-1, 0, 0));
                }
            }
        });
        settle();
        film.onServer(level -> {
            for (Fight.Soldier s : street.soldiers()) film.armSoldier(s.name, s.side);
        });
        film.waitTicks(40);
        film.run(() -> film.withServer(s -> street.start()));
        film.waitTicks(40);
    }

    /**
     * Второй квартал боя — проспект под FPV плана 28 (z 327–335): Запад от перекрёстка, Восток у дальнего конца, по 24
     * бойца — четыре в ряд поперёк улицы, шесть рядов; пакет «Града» плана 28 ложится между ними ({@code line} ± 30), бойцы
     * — не ближе 12 блоков к нему. Место бойца — на мостовой (высота улицы у {@code line}), не на фонаре или машине.
     */
    private void avenueFight(Vec3 line) {
        hiddenAt(line, line.add(60, 0, 0));
        film.onServer(level -> {
            avenue = new Fight(cast(), server());
            avenue.spread = 4;
            avenue.range = 150;
            int street = groundY(level, line.x, line.z);
            int n = 0;
            java.util.Set<BlockPos> taken = new java.util.HashSet<>();
            for (int side = 0; side < 2; side++) {
                int placed = 0;
                for (int rank = 0; rank < 6; rank++) {
                    for (int k = 0; k < 4; k++) {
                        double z = 328.0 + k * 2.1 + (rank % 2) * 0.7;
                        double x = side == 0 ? line.x - 42 - rank * 4 - (k % 2) : line.x + 42 + rank * 4 + (k % 2);
                        Vec3 at = streetSpot(level, x, z, street, side == 0 ? -1 : 1, taken);
                        if (at == null) continue;
                        placed++;
                        // у стен перед первым рядом — баррикады, середина улицы открыта: ряды стреляют друг мимо друга
                        if (rank == 0 && (k == 0 || k == 3)) barricade(at.add(side == 0 ? 2 : -2, 0, 0));
                        String name = (side == 0 ? "AvW" : "AvE") + n++;
                        cast().spawn(name, at, side == 0 ? -90 : 90, null);
                        avenue.add(name, side, at, new Vec3(side == 0 ? 1 : -1, 0, 0));
                    }
                }
                if (placed < 24) Incoming.LOG.warn("SCENARIO film staging: проспект — {} бойцов на стороне {} из 24", placed, side == 0 ? "запада" : "востока");
            }
        });
        settle();
        film.onServer(level -> {
            for (Fight.Soldier s : avenue.soldiers()) film.armSoldier(s.name, s.side);
        });
        film.waitTicks(40);
        film.run(() -> film.withServer(s -> avenue.start()));
        film.waitTicks(40);
    }

    private void streets() {
        fight();
        Vec3 c = SQUARE;

        // p26: FPV низко над уличным боем, рядом ложится «Град», камеру бросает. Три ракеты батареей (ложатся разом,
        // рассеивание «Града» — своё) в точку пути впереди камеры (P26_PATH); план начинается за P26_HIT тиков до удара
        Vec3 hit26 = p26Hit();
        film.shot("p26").length(100).hidden()
                .onReady(() -> battery("rocket", List.of(hit26, hit26.add(-4, 0, 3), hit26.add(3, 0, -4)), new Vec3(-700, 0, 30), 0))
                .camera(() -> {
                    double y = g(c).y;
                    // по открытой площади (x 3130–3220, z 240–300) южнее башни-фонтана: дома и башня — вне пути
                    return CineCamera.fpv(P26_PATH.stream().map(q -> new Vec3(c.x + q.x, y + q.y, c.z + q.z)).toList(), P26_SPEED, 7, 1.0, 0.12, 78);
                })
                .when(() -> flightEta(at(hit26), 20, WeaponType.ROCKET) <= P26_HIT / 20.0, 3000);

        // p27: твоими глазами — перестрелка через площадь (a, c), на дороге рвутся снаряды (b). Игрок съёмки — стрелок
        // Запада за линией баррикад: места — где из глаз видно больше всего бойцов Востока (firingSpots), с патронами и
        // перезарядкой клавишей мода; проверка по данным — FightQa (автомат в руке, выстрелы по звуку, Восток в кадре)
        List<Vec3[]> spots27 = new ArrayList<>();
        film.run(() -> spots27.addAll(firingSpots(street, c, 3)));
        List<String> foes = new ArrayList<>();
        film.run(() -> street.soldiers().stream().filter(s -> s.side == 1).forEach(s -> foes.add(s.name)));
        for (String take : new String[]{"a", "b", "c"}) {
            int k = take.charAt(0) - 'a';
            actor(() -> spots27.get(k)[0], () -> spots27.get(k)[1], "pointblank:ak47");
            film.run(() -> {
                Film.cmd("give @s pointblank:ammo762 512");
                reloadGun();
            });
            film.waitTicks(70);
            Film.Shot shot = film.shot("p27" + take).length(40).hud()
                    .player(t -> {
                        Vec3 a = spots27.get(k)[1];
                        return new Pose(Vec3.ZERO, Film.yawTo(mc.player.position(), a) + (float) Math.sin(t / 13) * 3,
                                Film.pitchTo(mc.player.getEyePosition(), a) + (float) Math.sin(t / 7) * 1.2f, 0, 70);
                    })
                    .cue(0, () -> fightQa = new FightQa("p27" + take, "pointblank:ak47", foes, 70))
                    .cue(3, () -> mc.options.keyAttack.setDown(true))
                    .cue(22, () -> mc.options.keyAttack.setDown(false))
                    .cue(27, () -> mc.options.keyAttack.setDown(true))
                    .cueEnd(() -> {
                        mc.options.keyAttack.setDown(false);
                        if (fightQa != null) fightQa.close(film.recordDir());
                        fightQa = null;
                    });
            if (k == 1) {
                // три ракеты батареей ложатся на дорогу перед стрелком на 12-м тике плана
                Vec3 road = c.add(10, 0, 4);
                shot.onReady(() -> battery("rocket", List.of(road, road.add(-5, 0, 4), road.add(4, 0, -5)), new Vec3(-700, 0, 25), 4))
                        .when(() -> flightEta(at(road), 20, WeaponType.ROCKET) <= 0.6, 3000);
            }
        }

        // p28: FPV над перекрёстком, по улице бежит пакет «Града», разрывы прямо под камерой
        // вдоль проспекта (z 327–335, открыт от x 3012 на восток): пакет — батареей по оси проспекта (P28_PACK)
        Vec3 cross = new Vec3(3022.5, 0, 331.5), line = new Vec3(3090.5, 0, 331.5);
        avenueFight(line);
        hiddenAt(cross, line);
        film.shot("p28").length(85).hidden()
                .onReady(() -> battery("rocket", P28_PACK, new Vec3(-690, 0, 6.5), P28_GAP))
                .camera(() -> {
                    double y = g(cross).y;
                    return CineCamera.fpv(List.of(new Vec3(cross.x - 8, y + 16, cross.z), new Vec3(cross.x + 20, y + 14, cross.z + 0.5),
                            new Vec3(cross.x + 50, y + 13, cross.z - 0.5), new Vec3(cross.x + 90, y + 12, cross.z + 0.5),
                            new Vec3(cross.x + 150, y + 14, cross.z)), 1.6, 8, 1.1, 0.1, 76);
                })
                .when(() -> flightEta(at(P28_PACK.get(0)), 25, WeaponType.ROCKET) <= P28_FIRST / 20.0, 3000);

        // p29: боец прижался к стене; из-за плеча — шахед проносится над ним и входит в дом напротив
        Vec3 wall = new Vec3(3080.5, 0, 334.5), house = new Vec3(3175.5, 120, 353.5);
        hiddenAt(wall, house);
        film.run(() -> cast().spawn("Vlad", g(wall), Film.yawTo(wall, house), null));
        settle();
        film.onServer(level -> film.armSoldier("Vlad", 1));
        // удар — в грань дома напротив, которую камера из-за плеча видит в 40–110 блоках (веер лучей в момент пуска):
        // луч прямо на высотку за улицей упирался в стену, у которой стоит боец, и шахед входил в неё у камеры
        Vec3[] house29 = {house};
        film.shot("p29").length(50).hidden()
                .onReady(() -> {
                    Pose cam = CineCamera.pose();
                    List<Vec3> faces = cam == null ? List.of() : facades(cam.pos(), house, 50, 1, 40, 110, Rig.cloudBase() - 6);
                    if (!faces.isEmpty()) house29[0] = faces.get(0);
                    else Incoming.LOG.warn("SCENARIO film staging p29: грани дома напротив из камеры нет, цель {}", xyz(house));
                    salvo("drone", 1, 0, house29[0], behind(wall, house29[0], 700));
                })
                .camera(() -> CineCamera.shoulder(() -> film.bot("Vlad"), 2.3, 0.2, 1.2, () -> house29[0], 60))
                .when(() -> film.nearest(DroneEntity.class, house29[0], 70) != null, 3000);
        film.run(() -> {
            cast().remove("Vlad");
            if (avenue != null) for (Fight.Soldier s : avenue.soldiers()) cast().remove(s.name);
        });

        // p30: глазами бойца — он поднимает голову, шахед пикирует прямо в камеру (монтаж режет на ударе). Боец смотрит
        // туда, где небо над ним открыто (openSky), шахед заходит оттуда же: пике видно из глаз до самого удара
        Vec3 eyeSpot = new Vec3(3150.5, 0, 300.5);
        float[] yaw30 = {-90};
        hiddenAt(eyeSpot, eyeSpot.add(30, 0, 0));
        film.run(() -> {
            yaw30[0] = openSky(g(eyeSpot).add(0, 1.62, 0));
            cast().spawn("Sasha", g(eyeSpot), yaw30[0], null);
        });
        settle();
        film.shot("p30").length(80).hidden()
                .onReady(() -> salvo("drone", 1, 0, eyeSpot, eyeSpot.add(Vec3.directionFromRotation(0, yaw30[0]).scale(900))))
                .camera(() -> CineCamera.eyes(() -> film.bot("Sasha"), 72))
                .when(() -> flightOver(at(eyeSpot), 120, WeaponType.DRONE), 3000)
                .cue(0, () -> film.withServer(s -> trackLook("Sasha", DroneEntity.class, 80)))
                .endWhen(hit(eyeSpot, 200), 4);
        film.run(() -> cast().remove("Sasha"));

        carpet();
    }

    /** p01e: ковёр разрывов бежит прямо на камеру. */
    private void carpet() {
        Vec3 carpet = new Vec3(3175.5, 0, 252.5);
        hiddenAt(carpet.add(0, 0, 55), carpet);
        film.shot("p01e").length(60).hidden()
                .onReady(() -> salvo("rocket", 30, 34, carpet, new Vec3(2450, 0, 255)))
                .camera(() -> CineCamera.track(g(carpet.add(0, 0, 55)).add(0, 1.3, 0), () -> at(carpet).add(0, 4, 0), 62))
                .when(() -> film.nearest(RocketEntity.class, at(carpet), 220) != null, 3000);
    }

    // ================================================================ волна 4: сквозь огонь, закат, ночь, пик

    private void throughFire() {
        // p31: FPV за шахедом между небоскрёбами — шахед входит в башню, камера влетает в огненный шар
        Vec3 into = T4.add(0, -50, -4);
        hiddenAt(T4.add(-150, -150, 0), T4);
        film.shot("p31").length(50).hidden()
                .onReady(() -> salvo("drone", 1, 0, into, new Vec3(2500, 0, 560)))
                .camera(() -> CineCamera.chase(() -> film.nearest(DroneEntity.class, into, 400), into.add(-140, 30, 0),
                        new Vec3(1, 0, 0), 6, 1.2, -1.6, 12, 0, 70))
                .when(() -> film.nearest(DroneEntity.class, into, 90) != null, 3000)
                .endWhen(hit(into, 400), 14);
        // p32: …и вылетает над другой улицей, куда как раз ложится «Град»
        Vec3 other = new Vec3(3010.5, 0, 640.5);
        film.shot("p32").length(35).hidden()
                .onReady(() -> salvo("rocket", 16, 20, other, new Vec3(2400, 0, 640)))
                .camera(() -> {
                    Vec3 o = at(other);
                    return CineCamera.fpv(List.of(o.add(40, 34, -50), o.add(14, 26, -20), o.add(-6, 22, 4), o.add(-30, 20, 30)), 1.5, 8, 0.8, 0.1, 76);
                })
                .when(() -> film.nearest(RocketEntity.class, at(other), 180) != null, 3000);

        skyline();

        // p34: с борта ракеты — пике, цель растёт, SIGNAL LOST (монтаж режет по отметке gone)
        Vec3 dive = new Vec3(3130.5, 0, 560.5);
        film.shot("p34").length(260).hud().projectileCamera().readyChunks(12)
                .onReady(() -> salvo("missile", 1, 0, dive, new Vec3(2200, 0, 560)))
                .prepare(() -> {
                    if (!ProjectileCamera.isActive()) ProjectileCamera.cycle();
                })
                .when(() -> Film.onMap() && film.missileRange() < 600, 3000)
                .cueEnd(ProjectileCamera::exit);

        // p37: B-2 на закате — створки, бомба уходит вниз, земля вспучивается (монтаж — от отметки release)
        film.run(() -> Film.cmd("time set 12400"));
        Vec3 bombAt = new Vec3(3360.5, 0, 470.5);
        Vec3 bayWatch = new Vec3(3190.5, 230, 470.5);
        hiddenAt(bayWatch, bombAt);
        film.shot("p37").length(120).hidden()
                .onReady(() -> Film.cmd("incoming salvo bunker 1 0 at " + xyz(at(bombAt)) + " from 2400 470"))
                .camera(() -> CineCamera.chase(() -> film.nearest(BomberEntity.class, at(bombAt), 900), bayWatch, new Vec3(1, 0, 0),
                        26, -7, 9, 14, 0, 56))
                .when(() -> film.nearest(BomberEntity.class, at(bombAt), 500) != null, 3000)
                .endWhen(() -> film.nearest(BomberEntity.class, at(bombAt), 900) instanceof BomberEntity b && b.flightPhase() == FlightPhase.EGRESS, 70);

        summit();

        // p38: ночь, дождь: рой шахедов, перехваты в небе, ночной «Град» подсвечивает облака
        film.run(() -> {
            Film.cmd("time set 13300");  // сразу после заката: небо ещё синее, город уже в огнях (в полночь под шейдером кадр чёрный)
            Film.cmd("weather rain");
        });
        Vec3 night = new Vec3(3180.5, 0, 300.5);
        // за северным краем крыши ROOF (край — z 347), над площадью: площадь под камерой видна (над крышей край её закрывал)
        Vec3 nightCam = new Vec3(3175.5, 172, 343.5);
        hiddenAt(nightCam, night);
        // узел сети посреди площади: шахед роя без разброса бьёт в него, город гаснет кварталами по 60 блоков в секунду
        // (каскад мода); план — с 1,5 с до этого удара (шахеды роя летят столько же и приходят с ним)
        gridNode(night, 300);
        film.shot("p38").length(90).hidden()
                .onReady(() -> {
                    salvo("drone", 12, 60, night, new Vec3(2300, 0, 260));
                    salvo("drone", 1, 0, night, new Vec3(2300, 0, 330));
                    salvo("rocket", 24, 50, night.add(40, 0, -50), new Vec3(2600, 0, 250));
                })
                .camera(() -> CineCamera.track(nightCam, () -> new Vec3(3180.5, 110, 260.5), 40))
                .when(() -> flightEta(at(night), 2, WeaponType.DRONE) <= 1.5, 3000)
                .cue(0, () -> lampsFrom("p38", 400, 0.5))
                .cueEnd(this::lampsEnd);

        // p39: пик — короткие вспышки ночного боя (монтаж режет каждый дубль на несколько склеек)
        peak("p39a", new Vec3(2930.5, 0, 260.5), "rocket", 30, 30, new Vec3(-30, 2, 40), 70);
        peak("p39b", new Vec3(3110.5, 0, 410.5), "drone", 8, 30, new Vec3(-24, 30, -20), 60);
        peak("p39c", T1.add(0, -40, 0), "missile", 4, 12, new Vec3(-60, -80, -50), 40);  // ниже облаков: верх T1 — над ними
        peak("p39d", new Vec3(3200.5, 0, 260.5), "rocket", 24, 24, new Vec3(-40, 14, 30), 50);
        film.run(() -> Film.cmd("weather clear"));
        gridRestore();  // ядерный удар гасит город сам (план 46)
    }

    /**
     * p33: в бинокль с крыши Востока — удары по башням центра за ~700 блоков, дальше прорисовки. Ракеты с запада идут
     * поперёк кадра и бьют справа налево (T2, T4, T1) пусками через 8 тиков — три удара за секунду монтажа; звук удара
     * идёт до крыши 2 с — монтаж режет раньше.
     * Каждый кадр проверяется по путям ракет ({@link HudQa}): ракета перед ударом и удар в окуляре, не за рельефом.
     */
    private void skyline() {
        Vec3 eye = SKYLINE_POST.add(0, 1.62, 0);
        Vec3[] towers = {T2, T1, T4};  // справа налево
        Vec3[] aims = new Vec3[towers.length];
        for (int i = 0; i < towers.length; i++) aims[i] = new Vec3(towers[i].x, UNDER_CLOUDS, towers[i].z);
        actor(SKYLINE_POST, aims[0].lerp(aims[1], 0.5), "incoming:strike_designator[incoming:loadout={weapon:\"missile\",count:1,spread:0}]");
        // окуляр сперва — на T2 и T1, через 12 тиков после удара по T2 (удар виден, KEY в HudQa) за 6 тиков переходит на T1
        // и T4: удар по T1 в кадре в обоих положениях, ракета на T4 — последние 11 тиков до удара (через 14 тиков после T1)
        int[] hitAt = {-1};
        boolean[] flying = {false};
        film.shot("p33").length(100).hud().readyChunks(26).distant()
                .onReady(() -> {
                    for (int i = 0; i < towers.length; i++) {
                        Vec3 t = aims[i] = towerFace(eye, towers[i]);
                        later(14 * i, () -> salvo("missile", 1, 0, t, new Vec3(t.x - 1500, 0, t.z)));
                    }
                })
                .player(t -> {
                    boolean now = flightEta(aims[0], 6, WeaponType.MISSILE) < Double.POSITIVE_INFINITY;
                    if (flying[0] && !now && hitAt[0] < 0) hitAt[0] = (int) t;
                    flying[0] |= now;
                    double s = hitAt[0] < 0 ? 0 : CineCamera.smooth(Mth.clamp((t - hitAt[0] - 12) / 6, 0, 1));
                    Vec3 look = aims[0].lerp(aims[1], 0.5).lerp(aims[1].lerp(aims[2], 0.5), s);
                    return new Pose(Vec3.ZERO, Film.yawTo(SKYLINE_POST, look), Film.pitchTo(eye, look), 0, 70);
                })
                .cue(0, () -> mc.options.keyUse.setDown(true))
                .cueEnd(() -> mc.options.keyUse.setDown(false))
                .watch(WeaponType.MISSILE, 40, towers.length, aims)
                .when(() -> flightEta(aims[0], 6, WeaponType.MISSILE) <= 1.5, 3000);
    }

    /**
     * Точка удара на башне {@code tower}, которую видит глаз {@code eye}, — под слоем облаков: луч на ось башни с высоты
     * {@link #UNDER_CLOUDS} и выше через 4 блока до низа облаков без 6; первая грань ближе 30 блоков к оси — башня (дальше —
     * дом перед ней). Не нашлась — строка {@code SCENARIO film staging} и ось башни на {@link #UNDER_CLOUDS}.
     */
    Vec3 towerFace(Vec3 eye, Vec3 tower) {
        double clouds = Rig.cloudBase(), top = Math.min(tower.y, (Double.isNaN(clouds) ? 192 : clouds) - 6);
        for (double y = UNDER_CLOUDS; y <= top; y += 4) {
            Vec3 axis = new Vec3(tower.x, y, tower.z), d = axis.subtract(eye).normalize();
            var hit = mc.level.clip(new ClipContext(eye, axis, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
            Vec3 at = hit.getType() == HitResult.Type.MISS ? axis : hit.getLocation().add(d.scale(0.5));
            if (Math.hypot(at.x - tower.x, at.z - tower.z) > 30) continue;
            Incoming.LOG.info("SCENARIO film staging: башня {} из {} — грань {} в {} блоках", xyz(tower), xyz(eye), xyz(at),
                    String.format(Locale.ROOT, "%.0f", at.distanceTo(eye)));
            return at;
        }
        Vec3 at = new Vec3(tower.x, UNDER_CLOUDS, tower.z);
        Incoming.LOG.warn("SCENARIO film staging: башня {} из {} — под облаками закрыта домами, удар в {}", xyz(tower), xyz(eye), xyz(at));
        return at;
    }

    /** Точка мира на открытой карте наведения — в координатах GUI (проекция карты; закрыта — отражением). */
    private double[] mapPoint(Vec3 p) {
        try {
            var m = MapScreen.class.getDeclaredMethod("projection");
            m.setAccessible(true);
            int[] at = ((MapProjection) m.invoke(mc.screen)).at(p.x, p.z);
            return new double[]{at[0], at[1]};
        } catch (ReflectiveOperationException | ClassCastException e) {
            Incoming.LOG.warn("SCENARIO film staging: точка карты — {}", e.toString());
            return new double[]{mc.getWindow().getGuiScaledWidth() / 2.0, mc.getWindow().getGuiScaledHeight() / 2.0};
        }
    }

    /**
     * Снежная вершина к северо-западу от Востока (2386, −511, верх 175) в ~1,25 км от башен центра. Камера идёт к ней по
     * линии взгляда из-за гребня: плоский гребень (175 на 20 блоков вдоль взгляда) внизу кадра уходит вниз и к концу
     * плана из него выходит. Высоты гребня — сетка 2 блока по файлам регионов, наибольшая в клетке: линии взгляда на
     * центр и на башни проходят над гребнем на 1,2–3 блока; дальше — лучи ray.py по блокам: стены башен T1, T2, T3
     * открыты (луч упирается в них за 1–8 блоков до цели), T4 — до самой цели. Башни в кадре справа налево — T3, T2, T1.
     */
    static final Vec3 SUMMIT_FROM = new Vec3(2375.8, 176.5, -525.9), SUMMIT_TO = new Vec3(2380.4, 177.5, -519.3),
            SUMMIT_LOOK = new Vec3(3090, 157, 520);

    /**
     * p37b: закат с вершины — город на горизонте горит, ракеты с запада идут через кадр справа налево и бьют в верх башен
     * одна за другой. Это дальше прорисовки (32 чанка): горы и город рисует LOD Distant Horizons, ракеты со шлейфами,
     * огненные шары и столбы дыма — дальняя картинка мода; звук ударов идёт до вершины 3,6 с — монтаж режет раньше.
     * Камера медленно идёт к вершине (снег гребня — внизу кадра), 70 мм. Каждый кадр проверяется по путям ракет
     * ({@link HudQa}): ракета перед ударом и удар в кадре, не за рельефом.
     */
    private void summit() {
        hiddenAt(SUMMIT_TO, SUMMIT_LOOK);
        Vec3[] towers = {T3, T2, T1};
        film.shot("p37b").length(140).hidden().live().distant()
                .onReady(() -> {
                    for (int i = 0; i < towers.length; i++) {
                        Vec3 t = towers[i];
                        later(12 * i, () -> salvo("missile", 1, 0, t, new Vec3(t.x - 1500, 0, t.z)));
                    }
                })
                .camera(() -> CineCamera.glide(SUMMIT_FROM, SUMMIT_TO, 140, () -> SUMMIT_LOOK, Rig.fov(70)))
                .watch(WeaponType.MISSILE, 40, towers.length, towers)
                .when(() -> flightNear(towers[0], 300, WeaponType.MISSILE), 3000);
    }

    /** Город на горизонте горит: удары по башням и улицам центра до плана с вершины, столбы дыма стоят минуты. */
    private void burnSkyline() {
        hiddenAt(SUMMIT_TO, SUMMIT_LOOK);
        film.run(() -> {
            for (Vec3 t : new Vec3[]{T4, T5, T1.add(0, -60, 0), T2.add(0, -90, 0)}) salvo("missile", 1, 0, t, new Vec3(t.x - 1500, 0, t.z));
            salvo("rocket", 16, 40, new Vec3(3110.5, 0, 410.5), new Vec3(2500, 0, 410));
            salvo("drone", 8, 60, new Vec3(3060.5, 0, 620.5), new Vec3(2400, 0, 620));
        });
        // шахед идёт до города ~30 с, ракета ~20 с
        film.waitTicks(900);
    }

    /**
     * План пика: удар {@code weapon} по месту, камера смотрит на него со сдвига {@code cam} (y > 0 — над землёй); удар — в
     * грань, которую камера видит (по лучу в момент пуска).
     */
    private void peak(String name, Vec3 target, String weapon, int count, int spread, Vec3 cam, double fov) {
        hiddenAt(target.add(cam.x, 0, cam.z), target);
        Vec3[] aim = {null};
        film.shot(name).length(80).hidden()
                .onReady(() -> {
                    Pose p = CineCamera.pose();
                    aim[0] = p == null ? at(target) : visible(p.pos(), at(target));
                    salvo(weapon, count, spread, aim[0], behind(at(target).add(cam), aim[0], 900));
                })
                .camera(() -> {
                    Vec3 t = at(target);
                    Vec3 c = cam.y > 0 ? g(t.add(cam)).add(0, cam.y, 0) : t.add(cam);
                    return CineCamera.track(c, () -> (aim[0] != null ? aim[0] : t).add(0, 6, 0), fov);
                })
                .when(() -> flightNear(aim[0] != null ? aim[0] : at(target), weapon.equals("rocket") ? 300 : 120,
                        WeaponType.valueOf(weapon.toUpperCase(Locale.ROOT))), 3000);
    }

    // ================================================================ IV: МБР и ядерный удар

    private void nuke() {
        film.run(() -> Film.cmd("time set 17000"));
        // расчёт у пусковых ночью
        hiddenAt(NIGHT_POST, GZ);
        // командир — на одной линии со столом МБР (в 30 блоках за его спиной) между рядами пусковых, z рядов ± 9
        Vec3 cmdr = new Vec3(NIGHT_POST.x - 4, BATTERY_Y, NIGHT_POST.z), keyPost = cmdr.add(10, 0, 0), pad = cmdr.add(-30, 0, 0);
        film.run(() -> {
            for (int i = 0; i < CREW.length; i++) {
                Vec3 at = i == 0 ? cmdr : new Vec3(NIGHT_POST.x - 2 + i * 1.6, BATTERY_Y, NIGHT_POST.z - 3 + (i % 2) * 1.2);
                cast().spawn(CREW[i], at, -90, null);
            }
        });
        settle();
        film.onServer(level -> {
            for (String c : CREW) film.armSoldier(c, 0);
            // командир расчёта: в руке пульт с ядерным ударом, в запасе МБР (приказ не оператора оплачивается предметом)
            cast().command("item replace entity " + CREW[0]
                    + " hotbar.0 with incoming:strike_designator[incoming:loadout={weapon:\"nuke\",count:1,spread:0}]");
            cast().command("give " + CREW[0] + " incoming:icbm 1");
        });
        // p40b, p40c: вспышки ночного боя у батареи (между цифрами отсчёта); пусковые поставила часть «west»
        film.run(this::reloadAll);
        film.waitTicks(40);
        film.shot("p40b").length(30).hidden()
                .onReady(() -> fireAll(rocketRack.subList(Math.min(3, rocketRack.size()), Math.min(4, rocketRack.size()))))
                .camera(() -> {
                    Vec3 l = new Vec3(-1474.5, BATTERY_Y, ROW_ROCKET);
                    return CineCamera.track(l.add(-10, 1.6, -7), () -> l.add(10, 8, 0), 60);
                })
                .when(() -> film.nearest(RocketEntity.class, BATTERY, 140) != null, 400);
        film.shot("p40c").length(30).hidden()
                .onReady(() -> fireAll(missileRack.subList(0, Math.min(3, missileRack.size()))))
                .camera(() -> {
                    Vec3 l = new Vec3(-1519.5, BATTERY_Y, ROW_MISSILE);
                    return CineCamera.track(l.add(4, 1.0, -9), () -> l.add(14, 10, 0), 64);
                })
                .when(() -> film.nearest(CruiseMissileEntity.class, BATTERY, 120) != null, 400);
        // p53 (после титров): ключ, ☢ LAUNCH IN 3, отмена — отсчёт показывает пульт того, кто запускает
        actor(NIGHT_POST, NIGHT_POST.add(100, 4, 0), "incoming:strike_designator[incoming:loadout={weapon:\"nuke\",count:1,spread:0}]");
        film.shot("p53").length(70).hud().player(t -> new Pose(Vec3.ZERO, -90, -6, 0, 70))
                .cue(14, () -> NukeArming.toggle(() -> {}))
                .cue(58, NukeArming::cancel);
        // p40a: второй ключ. Приказ на ядерный удар отдаёт командир расчёта — он не оператор, и мод ждёт второй ключ
        // игрока рядом (NuclearKeys): глазами бойца в 10 блоках — просьба в чате, клавиша «Второй ключ», строка «повернул»,
        // и за спиной командира со стола в 30 блоках уходит МБР (полёт не оператора — не меньше 90 с). Доли трека — на 1, 2
        // и 3 с плана (edit.py): просьба, ключ, МБР.
        actor(keyPost, cmdr.add(0, 1.4, 0), "incoming:strike_designator");
        film.run(() -> {
            // подпись клавиши в строке мода — буквой, а не «.» по умолчанию
            Keys.SECOND_KEY.setKey(InputConstants.getKey("key.keyboard.k"));
            KeyMapping.resetMapping();
            cast().act(CREW[0], one(Cast.lookAt(keyPost.add(0, 1.6, 0))), true);
        });
        film.shot("p40a").length(90).chat()
                .player(t -> {
                    float yaw = Film.yawTo(keyPost, pad);
                    // до пуска — командир и стол за ним; после — взгляд за МБР вверх
                    IcbmEntity icbm = film.newest(IcbmEntity.class);
                    float up = icbm == null ? -3 : Math.min(-3, Film.pitchTo(keyPost.add(0, 1.62, 0), icbm.position()) + 6);
                    return new Pose(Vec3.ZERO, yaw, up, 0, 70);
                })
                .cue(14, () -> cast().act(CREW[0], one(Cast.step("chat", "/incoming nuke at " + xyz(at(GZ)) + " 15 air")), true))
                .cue(36, () -> PacketDistributor.sendToServer(new C2S.NuclearKey()));
        // p41: тишина; МБР уходит в ночь за спиной расчёта, кран вверх
        film.shot("p41").length(100).hidden().noPrep()
                .after(() -> film.newest(IcbmEntity.class) != null, 300)
                .camera(() -> {
                    IcbmEntity icbm = film.newest(IcbmEntity.class);
                    Vec3 from = icbm != null ? icbm.position() : pad;
                    return CineCamera.dolly(NIGHT_POST.add(16, 1.2, 6), NIGHT_POST.add(17, 12, 8), 100,
                            film.smoothFocus(() -> film.newest(IcbmEntity.class), from.add(0, 10, 0), 0.25), 62);
                });
        film.run(() -> {
            for (String c : CREW) cast().remove(c);
        });

        // p43: на Востоке ☢ MISSILE ATTACK ☢ и отсчёт; бой замирает, бойцы опускают оружие и смотрят вверх
        Vec3 c = SQUARE;
        film.shot("p43").length(120).hud().hidden()
                .cue(0, () -> film.withServer(s -> {
                    if (street != null) street.watch(at(c).add(-600, 500, 300), 40);
                }))
                .camera(() -> {
                    double y = g(c).y;
                    return CineCamera.dolly(new Vec3(c.x - 40, y + 2, c.z + 10), new Vec3(c.x - 34, y + 1.9, c.z + 8), 120,
                            () -> new Vec3(c.x, y + 3, c.z), 58);
                })
                .when(() -> film.warningTicks() < 560, 4000);

        // p44: та же площадь, что в p04: горит и пуста; камера поднимает взгляд в небо
        film.run(() -> {
            if (street != null) for (Fight.Soldier s : street.soldiers()) cast().remove(s.name);
        });
        film.shot("p44").length(75).hidden()
                .camera(() -> {
                    Vec3 a = g(3116.5, 306.5).add(0, 5.5, 0);
                    return CineCamera.pan(a, a.add(1, 0.15, -0.8), () -> GOV, () -> GOV.add(-60, 220, 100), 75, 50);
                })
                .when(() -> film.warningTicks() < 300, 4000);

        // p45, p50: крыша в ~900 блоках: вспышка — люди отворачиваются; волна закрывает небо и накрывает их
        hiddenAt(FINAL_ROOF, GZ);
        film.onServer(level -> {
            roof = new Crowd(cast(), server());
            for (int i = 0; i < 6; i++) {
                Vec3 p = FINAL_ROOF.add((i % 3) * 1.6 - 1.6, 0, (i / 3) * 1.6);
                roof.add((i < 3 ? "Watch" : "Guard") + i, p, 0.5);
            }
        });
        settle();
        film.onServer(level -> {
            for (int i = 3; i < 6; i++) film.armSoldier("Guard" + i, 1);
            roof.watch(GZ.add(0, 300, 0), 30);
        });
        film.shot("p45").length(80).hidden()
                .camera(() -> CineCamera.track(FINAL_ROOF.add(-7, 3.2, -6), () -> GZ.add(0, 250, 0), 60))
                .when(() -> film.warningTicks() < 30, 4000)
                .cue(31, () -> film.withServer(s -> roof.cower(GZ)));
        film.shot("p50").length(320).hidden().noPrep()
                .camera(() -> {
                    Vec3 a = FINAL_ROOF.add(-9, 5.5, -8), b = FINAL_ROOF.add(-5, 2.6, -10);
                    return CineCamera.dolly(a, b, 320, () -> GZ.add(0, 330, 0), 64);
                })
                .cue(120, () -> film.withServer(s -> roof.watch(GZ.add(0, 600, 0), 40)));
    }

    /**
     * Волна после ядерки — ещё два подрыва, каждый на свежей копии мира: вдали фронт идёт ~3 блока за тик, и планы,
     * которым нужна стена у разных мест, в одном подрыве не помещаются. Часть nukeB: гриб над городом с той же крыши (p46),
     * стена пыли катится через городок с высоты (p47).
     */
    private void nukeFar() {
        detonate(FINAL_ROOF);
        film.shot("p46").length(100).hidden()
                // 35 мм: город у нижнего края кадра, огненный шар (~590 блоков над землёй, 15 кт в воздухе) — у верхнего
                .camera(() -> CineCamera.track(FINAL_ROOF.add(3, 3, -10), () -> GZ.add(0, 275, 0), Rig.fov(35)))
                .when(() -> film.sinceDetonation() >= 0, 600);
        film.shot("p47").length(70).hidden()
                .camera(() -> CineCamera.glide(WAVE_HIGH, WAVE_HIGH.add(6, -4, 8), 70, () -> at(WAVE_LOOK), 70))
                .when(() -> frontIn(at(WAVE_TOWN)) < 25, 4000);
    }

    /** Часть nukeC: стена между домами на камеру (p49), волна закрывает горизонт с взгорка (p48) — по порядку прихода. */
    private void nukeNear() {
        detonate(WAVE_STREET);
        film.shot("p49").length(70).hidden()
                .camera(() -> {
                    Vec3 dir = GZ.subtract(WAVE_STREET).multiply(1, 0, 1).normalize();
                    return CineCamera.track(WAVE_STREET.add(0, 1.5, 0), () -> WAVE_STREET.add(dir.scale(80)).add(0, 12, 0), 72);
                })
                .when(() -> frontIn(WAVE_STREET) < 45, 4000);
        film.shot("p48").length(100).hidden()
                .camera(() -> CineCamera.track(g(WAVE_HILL).add(0, 18, 0), () -> GZ.add(-150, 150, -150), 24))
                .when(() -> frontIn(at(WAVE_HILL)) < 70, 4000);
    }

    /**
     * Часть icbm: огненная точка МБР уходит в ночное небо, издали с реки (p42). Своим пуском на свежей копии мира: в части
     * nuke МБР, за которой смотрит p41, выходит за небо ({@code IcbmEntity}: выше потолка мира на 256) раньше, чем p41
     * кончается. План начинается, когда МБР оторвалась от стола (выше потолка мира на 256 она пропадает через ~2 с
     * разгона); подрыва не ждёт.
     */
    private void icbm() {
        film.run(() -> Film.cmd("time set 17000"));
        hiddenAt(NIGHT_POST, GZ);  // стол МБР — в 30 блоках позади запустившего
        film.waitTicks(100);
        film.run(() -> Film.cmd("incoming nuke at " + xyz(at(GZ)) + " 15 air"));
        // над рекой в 150 блоках от стола: МБР остаётся в дальности слежения и симуляции у невидимки, которая ходит
        // за камерой (в 200 блоках при прорисовке 12 клиент её то терял, то находил);
        // фокус сужается с дальностью — МБР в кадре не тает в точку; взгляд прямо на МБР без сглаживания: она
        // набирает до 25 блоков за тик, и сглаженный взгляд отстаёт за край кадра
        Vec3 riverCam = NIGHT_POST.add(70.5, 0, -115.5);
        film.shot("p42").length(60).hidden()
                .after(() -> {
                    IcbmEntity e = film.newest(IcbmEntity.class);
                    return e != null && e.getY() > NIGHT_POST.y + 30;
                }, 3000)  // ждём по тикам клиента, а сервер после пуска готовит руины и отстаёт
                .camera(() -> {
                    Vec3 from = g(riverCam).add(0, 3, 0);
                    return CineCamera.track(from, film.smoothFocus(() -> film.newest(IcbmEntity.class), NIGHT_POST.add(0, 200, 0), 1),
                            film.rangeFov(from, () -> film.newest(IcbmEntity.class), 20, 7, 250));
                });
    }

    /** Ночь, невидимка у места первого плана, подрыв 15 кт над эпицентром (без полёта). */
    private void detonate(Vec3 watch) {
        film.run(() -> Film.cmd("time set 17000"));
        hiddenAt(watch, GZ);
        film.waitTicks(100);
        film.run(() -> Film.cmd("incoming nuke now at " + xyz(at(GZ)) + " 15 air"));
    }

    // ================================================================ постройки

    /** Мешки поперёк линии огня (по x): 3 блока шириной, 2 высотой. */
    private void barricade(Vec3 at) {
        BlockPos p = BlockPos.containing(at);
        cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:packed_mud", p.getX(), p.getY(), p.getZ() - 1,
                p.getX(), p.getY() + 1, p.getZ() + 1));
    }

    private double roomFloor = 64;

    /** Квартира: комната 7×4×7 из бетона на земле, окно во всю стену к −z (к удару), внутри темно. */
    private void buildRoom(ServerLevel level, Vec3 room) {
        int x = Mth.floor(room.x), z = Mth.floor(room.z);
        int y = groundY(level, x, z);
        roomFloor = y;
        cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:spruce_planks hollow", x - 3, y - 1, z - 3, x + 3, y + 4, z + 3));
        cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:dark_oak_planks", x - 2, y - 1, z - 2, x + 2, y - 1, z + 2));
        cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glass_pane", x - 2, y, z - 3, x + 2, y + 3, z - 3));
        cast().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:lantern[hanging=true]", x, y + 3, z));
        cast().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:red_bed[facing=east,part=foot]", x - 2, y, z + 1));
        cast().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:bookshelf", x + 2, y, z + 2));
        cast().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:potted_fern", x + 2, y, z - 2));
        // двор перед окном: деревья и живая изгородь закрывали бы вид
        for (String tag : new String[]{"#minecraft:leaves", "#minecraft:logs"})
            cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air replace %s", x - 8, y - 1, z - 14,
                    x + 8, y + 12, z - 4, tag));
    }
}
