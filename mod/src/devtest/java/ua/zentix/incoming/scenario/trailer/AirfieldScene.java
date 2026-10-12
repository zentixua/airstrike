package ua.zentix.incoming.scenario.trailer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.scenario.trailer.CineCamera.Path;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;
import ua.zentix.incoming.strike.FarFlights;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Аэродром: пара бипланов Immersive Aircraft взлетает по тревоге с горящего аэродрома, у полосы рвутся снаряды «Града».
 * Площадка своя: поле, полоса, ангары, вышка, казарма, склады; сначала — настоящие удары мода по постройкам (их пожары
 * поддержаны поджогом, чтобы огонь и дым были до конца сцены), потом три дубля взлёта, у каждого свои самолёты и
 * лётчики: сбоку низко, из кабины, уход над аэродромом. Лётчики — боты Almighty в творческом (IA не тратит топливо
 * творческого пилота): садятся в самолёт щелчком, как игрок, и ведут его клавишами — тяга прыжком, штурвал
 * вперёд-назад, курс влево-вправо; держит клавиши автопилот сцены в потоке сервера ({@link #tick}), как правило
 * ведущего.
 * <p>
 * Разрывы у полосы — залпы {@code /incoming salvo rocket} с пусковой далеко за кадром. От приказа до разрыва — около
 * 200 тиков, поэтому дубль начинается с залпа, а разбег — когда до первого разрыва остаётся столько тиков, сколько
 * задумано ({@link Shell#at}); остальные залпы дубля идут с задуманными промежутками. Обломки взрывов разлетаются на
 * десятки блоков, а блок на полосе останавливает самолёт насмерть: перед каждым дублем полосу расчищают
 * ({@link #sweep}), разрывы дубля — в стороне от полосы, позади пары или когда она уже в воздухе.
 * <p>
 * Оси сцены — как {@link Scene#local}: вправо, вверх, вперёд по курсу полосы от {@code at} (середина торца полосы,
 * первый блок над землёй).
 */
final class AirfieldScene {
    /** Лётчики: ведущий (камера кабины — у него) и ведомый справа, на корпус впереди. */
    private static final String[] PILOTS = {"Hawk1", "Hawk2"};
    /** Место самолётов на старте: вправо и вперёд от середины торца. */
    private static final double[][] START = {{-6, 10}, {6, 18}};
    private static final ResourceLocation BIPLANE = ResourceLocation.fromNamespaceAndPath("immersive_aircraft", "biplane");
    /** Поле вокруг полосы: вправо от и до, вперёд от и до. */
    private static final int FIELD_R = 64, FIELD_BACK = -48, FIELD_FWD = 300;
    /** Полоса: полуширина и длина. */
    private static final int RUNWAY_HALF = 12, RUNWAY_LEN = 240;
    /** Пусковая «Града» — так далеко сбоку от точки удара, за кадром любого плана. */
    private static final double LAUNCHER_OFF = 400;

    /** Разрыв дубля: через {@code at} тиков после начала разбега, в точке {@code r} вправо, {@code f} вперёд. */
    private record Shell(int at, double r, double f) {}

    private final Trailer film;
    private final Vec3 at;
    private final float yaw;
    private final Vec3 fwd, right;
    private final List<ChunkPos> forced = new ArrayList<>();
    private final RandomSource random = RandomSource.create(24);
    /** Сдвиг обхода клеток наискось ({@link #each}): вправо, вперёд. */
    private final double[] sub = new double[2];

    /** Самолёты этого дубля (UUID на сервере), по лётчикам. */
    private final UUID[] planes = new UUID[PILOTS.length];
    /** Дубль идёт (с {@link #arm} до {@link #land}); всё ниже — поток сервера. */
    private volatile boolean armed;
    private double cruise = 14;
    private final List<Shell> shells = new ArrayList<>();
    private final List<Shell> ordered = new ArrayList<>();
    private long armTick, goTick = -1;

    private AirfieldScene(Trailer film, Vec3 at, float yaw) {
        this.film = film;
        this.at = at;
        this.yaw = yaw;
        this.fwd = Scene.local(Vec3.ZERO, yaw, 0, 0, 1);
        this.right = Scene.local(Vec3.ZERO, yaw, 1, 0, 0);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::blast);
    }

    static void stage(Film film, Vec3 at, float yaw) {
        new AirfieldScene((Trailer) film, at, yaw).stage();
    }

    private void stage() {
        film.onServer(this::build);
        film.waitTicks(40);
        // тревога: аэродром уже под ударом; ждём, пока долетит последний снаряд и лягут обломки
        film.onServer(level -> strikeBuildings());
        film.waitTicks(100);
        await(level -> FarFlights.flights(level).isEmpty(), 1200);
        film.waitTicks(120);
        film.onServer(this::ignite);

        // дубль 1: сбоку низко, чуть спереди — пара на разбеге, позади неё у ангаров рвутся снаряды
        take(14, new Shell(92, 38, 22), new Shell(110, 42, 36));
        film.shot("airfield_side").length(110).hidden()
                .camera(this::sideCamera)
                .onReady(this::arm)
                .when(() -> progress(plane(0)) > 19, 1500);
        film.run(this::land);

        // дубль 2: из кабины ведущего — разбег, разрыв слева у полосы, отрыв и уход низко над горящим аэродромом
        take(14, new Shell(108, -33, 80));
        film.shot("airfield_cockpit").length(120).hidden()
                .camera(() -> CineCamera.mount(() -> plane(0), 0.75, 2.45, -2.6, 4, 70))
                .onReady(this::arm)
                .when(() -> progress(plane(0)) > 24, 1500);
        film.run(this::land);

        // дубль 3: уход над аэродромом — камера низко у дальнего конца полосы: пара идёт на неё над горящими ангарами,
        // проходит над головой и уходит к горящему складу за полосой, у склада рвётся снаряд; первый разрыв дубля —
        // у старта, его дым — позади пары
        take(14, new Shell(105, -36, 30), new Shell(380, 16, 262));
        film.shot("airfield_departure").length(130).hidden()
                .camera(() -> CineCamera.track(point(-20, 1.6, 200), film.smoothFocus(this::pair, point(0, 4, 140), 0.2), 52))
                .onReady(this::arm)
                .when(() -> progress(plane(0)) > 150, 1800);
        film.run(this::land);

        film.onServer(level -> {
            clear(level);
            for (ChunkPos c : forced) level.setChunkForced(c.x, c.z, false);
        });
    }

    /**
     * Сбоку низко: камера едет у обочины вровень с парой, на 10 блоков впереди её середины, и смотрит на неё — ведущий
     * ближе, ведомый дальше, за ними разрывы у ангаров; при отрыве взгляд уходит вверх за парой.
     */
    private Path sideCamera() {
        Supplier<Vec3> look = film.smoothFocus(this::pair, point(0, 1.5, 14), 0.2);
        return t -> {
            Vec3 c = look.get();
            return Pose.look(point(-17, 0.9, c.subtract(at).dot(fwd) + 10), c, 0, 48);
        };
    }

    /** Шаг сценария ждёт, пока условие на сервере не станет верным (не дольше {@code timeout} тиков клиента). */
    private void await(Predicate<ServerLevel> done, int timeout) {
        AtomicBoolean asked = new AtomicBoolean(), ok = new AtomicBoolean();
        int[] waited = {0};
        film.then(() -> {
            if (ok.get()) return true;
            if (waited[0]++ > timeout) {
                Incoming.LOG.warn("TRAILER airfield: не дождались сервера за {} тиков", timeout);
                return true;
            }
            if (asked.compareAndSet(false, true)) {
                MinecraftServer server = film.mc.getSingleplayerServer();
                server.execute(() -> {
                    ok.set(done.test(server.overworld()));
                    asked.set(false);
                });
            }
            return false;
        });
    }

    // ================================================================ площадка

    /** Поле с полосой, постройки; чанки площадки грузятся сразу и держатся до конца сцены. */
    private void build(ServerLevel level) {
        long t0 = System.nanoTime();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int r : new int[]{-FIELD_R, FIELD_R}) {
            for (int f : new int[]{FIELD_BACK, FIELD_FWD}) {
                BlockPos p = pos(r, 0, f);
                minX = Math.min(minX, p.getX());
                minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX());
                maxZ = Math.max(maxZ, p.getZ());
            }
        }
        for (int cx = (minX >> 4) - 1; cx <= (maxX >> 4) + 1; cx++) {
            for (int cz = (minZ >> 4) - 1; cz <= (maxZ >> 4) + 1; cz++) {
                level.getChunk(cx, cz);
                level.setChunkForced(cx, cz, true);
                forced.add(new ChunkPos(cx, cz));
            }
        }
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(),
                air = Blocks.AIR.defaultBlockState();
        // поле: трава, под ней земля до грунта (не глубже 7), над ним — пусто
        each(-FIELD_R, FIELD_R, FIELD_BACK, FIELD_FWD, (r, f) -> {
            for (int y = 0; y <= 40; y++) level.setBlock(pos(r, y, f), air, Block.UPDATE_CLIENTS);
            level.setBlock(pos(r, -1, f), grass, Block.UPDATE_CLIENTS);
            for (int y = -2; y >= -7; y--) {
                BlockPos p = pos(r, y, f);
                if (y < -4 && !level.getBlockState(p).canBeReplaced()) break;
                level.setBlock(p, dirt, Block.UPDATE_CLIENTS);
            }
        });
        runway(level);
        hangar(level, 32, 30);
        hangar(level, 32, 72);
        tower(level, -46, 52);
        barracks(level, -60, 100);
        depot(level, 30, 130);
        warehouse(level, -11, 250);
        windsock(level, -20, 8);
        Incoming.LOG.info("TRAILER airfield: площадка за {} мс, чанков {}", (System.nanoTime() - t0) / 1_000_000, forced.size());
    }

    private void runway(ServerLevel level) {
        BlockState gray = Blocks.GRAY_CONCRETE.defaultBlockState(), white = Blocks.WHITE_CONCRETE.defaultBlockState();
        each(-RUNWAY_HALF, RUNWAY_HALF, 0, RUNWAY_LEN, (r, f) -> {
            boolean edge = Math.abs(r) == RUNWAY_HALF;
            // ось — штрихи по 6 через 6; торцы — «зебра» на 10 блоков
            boolean axis = r == 0 && f > 20 && f < RUNWAY_LEN - 20 && f % 12 < 6;
            boolean keys = (f >= 3 && f <= 12 || f >= RUNWAY_LEN - 12 && f <= RUNWAY_LEN - 3)
                    && Math.abs(r) >= 2 && Math.abs(r) <= 10 && Math.abs(r) % 2 == 0;
            set(level, r, -1, f, edge || axis || keys ? white : gray);
        });
        // рулёжки к стоянке у ангаров
        BlockState apron = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        each(RUNWAY_HALF + 1, 30, 58, 70, (r, f) -> set(level, r, -1, f, apron));
        each(22, 31, 26, 104, (r, f) -> set(level, r, -1, f, apron));
    }

    /** Ангар 24×28: стены из досок ели на тёмных столбах, ступенчатая крыша, ворота к полосе. */
    private void hangar(ServerLevel level, int r0, int f0) {
        int r1 = r0 + 24, f1 = f0 + 28;
        BlockState wall = Blocks.SPRUCE_PLANKS.defaultBlockState(), post = Blocks.DARK_OAK_LOG.defaultBlockState(),
                roof = Blocks.DARK_OAK_PLANKS.defaultBlockState(), air = Blocks.AIR.defaultBlockState(),
                floor = Blocks.SMOOTH_STONE.defaultBlockState();
        each(r0, r1, f0, f1, (r, f) -> {
            set(level, r, -1, f, floor);
            boolean side = r == r0 || r == r1 || f == f0 || f == f1;
            if (!side) return;
            boolean pillar = (r - r0) % 6 == 0 && (f - f0) % 7 == 0 || (r == r0 || r == r1) && (f == f0 || f == f1);
            for (int y = 0; y <= 8; y++) set(level, r, y, f, pillar ? post : wall);
        });
        // крыша ступенями к коньку вдоль ангара
        for (int k = 0; k <= 6; k++) {
            int y = 9 + k / 2, a = r0 + k * 2, b = r1 - k * 2;
            if (a > b) break;
            each(a, b, f0, f1, (r, f) -> set(level, r, y, f, roof));
        }
        // ворота к полосе: проём во всю стену, кроме крайних столбов
        each(r0, r0, f0 + 2, f1 - 2, (r, f) -> {
            for (int y = 0; y <= 7; y++) set(level, r, y, f, air);
        });
        // ящики и сено внутри
        for (int i = 0; i < 6; i++) {
            int r = r1 - 2 - random.nextInt(6), f = f0 + 2 + random.nextInt(f1 - f0 - 4);
            set(level, r, 0, f, i % 2 == 0 ? Blocks.BARREL.defaultBlockState() : Blocks.HAY_BLOCK.defaultBlockState());
        }
    }

    /** Вышка: кирпичный столб 7×7 с окнами, стеклянная будка с навесом, антенна. */
    private void tower(ServerLevel level, int r0, int f0) {
        int r1 = r0 + 6, f1 = f0 + 6;
        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState(), corner = Blocks.POLISHED_ANDESITE.defaultBlockState(),
                pane = Blocks.GLASS_PANE.defaultBlockState(), slab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
        each(r0, r1, f0, f1, (r, f) -> {
            boolean side = r == r0 || r == r1 || f == f0 || f == f1;
            boolean edge = (r == r0 || r == r1) && (f == f0 || f == f1);
            boolean middle = !edge && (r == r0 + 3 || f == f0 + 3);
            for (int y = 0; y <= 8; y++) {
                if (side) set(level, r, y, f, edge ? corner : middle && (y == 3 || y == 6) ? pane : brick);
                else if (y == 8) set(level, r, y, f, brick);
            }
            for (int y = 9; y <= 11; y++) if (side) set(level, r, y, f, edge ? corner : pane);
        });
        each(r0 - 1, r1 + 1, f0 - 1, f1 + 1, (r, f) -> set(level, r, 12, f, slab));
        for (int y = 13; y <= 17; y++) set(level, r0 + 3, y, f0 + 3, Blocks.IRON_BARS.defaultBlockState());
    }

    /** Казарма: дощатый барак с окнами и крышей из плит ели. */
    private void barracks(ServerLevel level, int r0, int f0) {
        int r1 = r0 + 22, f1 = f0 + 10;
        BlockState wall = Blocks.OAK_PLANKS.defaultBlockState(), log = Blocks.STRIPPED_OAK_LOG.defaultBlockState(),
                glass = Blocks.GLASS_PANE.defaultBlockState(), roof = Blocks.SPRUCE_SLAB.defaultBlockState();
        each(r0, r1, f0, f1, (r, f) -> {
            boolean side = r == r0 || r == r1 || f == f0 || f == f1;
            boolean corner = (r == r0 || r == r1) && (f == f0 || f == f1);
            if (side) {
                for (int y = 0; y <= 3; y++) {
                    boolean window = y == 2 && !corner && (r - r0) % 4 == 2;
                    set(level, r, y, f, corner ? log : window ? glass : wall);
                }
            }
            set(level, r, 4, f, roof);
        });
    }

    /** Склад горючего: цистерны из белого бетона и бочки. */
    private void depot(ServerLevel level, int r0, int f0) {
        BlockState tank = Blocks.WHITE_CONCRETE.defaultBlockState(), band = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        for (int i = 0; i < 4; i++) {
            int r = r0 + (i % 2) * 8, f = f0 + (i / 2) * 9;
            each(r, r + 3, f, f + 3, (a, b) -> {
                for (int y = 0; y <= 4; y++) set(level, a, y, b, y == 2 ? band : tank);
            });
        }
        for (int i = 0; i < 14; i++) {
            set(level, r0 + 14 + random.nextInt(4), 0, f0 + random.nextInt(18), Blocks.BARREL.defaultBlockState());
        }
    }

    /** Склад за дальним концом полосы: над ним пара уходит на малой высоте. */
    private void warehouse(ServerLevel level, int r0, int f0) {
        int r1 = r0 + 22, f1 = f0 + 16;
        BlockState wall = Blocks.SPRUCE_PLANKS.defaultBlockState(), post = Blocks.SPRUCE_LOG.defaultBlockState(),
                roof = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        each(r0, r1, f0, f1, (r, f) -> {
            boolean side = r == r0 || r == r1 || f == f0 || f == f1;
            boolean pillar = (r - r0) % 8 == 0 && (f == f0 || f == f1) || (r == r0 || r == r1) && (f - f0) % 8 == 0;
            if (side) for (int y = 0; y <= 5; y++) set(level, r, y, f, pillar ? post : wall);
            set(level, r, 6, f, roof);
        });
        for (int i = 0; i < 10; i++) {
            set(level, r0 + 2 + random.nextInt(r1 - r0 - 4), 0, f0 + 2 + random.nextInt(f1 - f0 - 4),
                    i % 3 == 0 ? Blocks.HAY_BLOCK.defaultBlockState() : Blocks.BARREL.defaultBlockState());
        }
    }

    /** Ветроуказатель у торца: столб и рукав. */
    private void windsock(ServerLevel level, int r, int f) {
        for (int y = 0; y <= 5; y++) set(level, r, y, f, Blocks.OAK_FENCE.defaultBlockState());
        set(level, r - 1, 5, f, Blocks.ORANGE_WOOL.defaultBlockState());
        set(level, r - 2, 5, f, Blocks.WHITE_WOOL.defaultBlockState());
        set(level, r - 3, 5, f, Blocks.ORANGE_WOOL.defaultBlockState());
    }

    /** Удары мода по постройкам перед дублями: ангары, склады, казарма. */
    private void strikeBuildings() {
        order("rocket 6 5", 44, 44);
        order("missile 1 0", 44, 86);
        order("rocket 5 6", 0, 258);
        order("drone 1 0", -49, 105);
        order("rocket 4 4", 38, 140);
    }

    /**
     * Пожары после ударов — поджог: огонь на крышах (под частью — незерак, чтобы горело всю сцену; от него занимаются
     * доски вокруг) и сигнальные костры на сене посреди крыш — столбы дыма над аэродромом. Обломки с полосы — прочь.
     */
    private void ignite(ServerLevel level) {
        roofFire(level, 34, 54, 32, 56, 12, 6, 2);
        roofFire(level, 34, 54, 74, 98, 12, 5, 2);
        roofFire(level, -10, 10, 251, 265, 6, 5, 2);
        roofFire(level, -59, -39, 101, 109, 4, 3, 1);
        // горючее: незерак с огнём на цистернах
        for (int i = 0; i < 4; i += 3) {
            int r = 31 + (i % 2) * 8, f = 131 + (i / 2) * 9;
            set(level, r, 4, f, Blocks.NETHERRACK.defaultBlockState());
            set(level, r, 5, f, Blocks.FIRE.defaultBlockState());
        }
        sweep(level);
        // третий биплан стоит у ангаров носом к полосе
        Entity spare = biplane(level, point(24, 0.05, 63), yaw + 90);
        if (spare != null) spare.addTag("airfield_spare");
    }

    /** Огонь на крыше: {@code fires} очагов, {@code lasting} из них на незераке, и {@code smoke} костров. */
    private void roofFire(ServerLevel level, int r0, int r1, int f0, int f1, int fires, int lasting, int smoke) {
        for (int i = 0; i < fires + smoke; i++) {
            int r = r0 + random.nextInt(r1 - r0 + 1), f = f0 + random.nextInt(f1 - f0 + 1);
            BlockPos top = roofTop(level, r, f);
            if (top == null) continue;
            if (i < fires) {
                if (i < lasting) level.setBlock(top.below(), Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(top, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
            } else {
                level.setBlock(top.below(), Blocks.HAY_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(top, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, true), Block.UPDATE_ALL);
            }
        }
    }

    /** Первый воздух над крышей в клетке (что осталось после ударов); крыши нет — null. */
    @Nullable
    private BlockPos roofTop(ServerLevel level, int r, int f) {
        for (int y = 16; y >= 2; y--) {
            BlockPos p = pos(r, y, f);
            if (!level.getBlockState(p).isAir()) return level.getBlockState(p.above()).isAir() ? p.above() : null;
        }
        return null;
    }

    /**
     * Полоса перед дублем: обломки, огонь и воронки на полосе, обочинах и поле слева, где ездят камеры, — прочь (блок
     * на полосе останавливает самолёт, обломок у камеры закрывает кадр), разметка и ветроуказатель заново; обломки
     * в полёте над полосой — тоже.
     */
    private void sweep(ServerLevel level) {
        BlockState air = Blocks.AIR.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(),
                grass = Blocks.GRASS_BLOCK.defaultBlockState();
        int half = RUNWAY_HALF + 3;
        each(-34, half, -8, RUNWAY_LEN + 8, (r, f) -> {
            for (int y = 0; y <= 14; y++) level.setBlock(pos(r, y, f), air, Block.UPDATE_CLIENTS);
            for (int y = -4; y <= -2; y++) level.setBlock(pos(r, y, f), dirt, Block.UPDATE_CLIENTS);
            if (Math.abs(r) > RUNWAY_HALF || f < 0 || f > RUNWAY_LEN) level.setBlock(pos(r, -1, f), grass, Block.UPDATE_CLIENTS);
        });
        runway(level);
        windsock(level, -20, 8);
        AABB box = new AABB(point(-34, -2, -8), point(half, 30, RUNWAY_LEN + 8));
        for (Entity e : level.getEntitiesOfClass(Entity.class, box, e -> e instanceof DebrisEntity || e instanceof FallingBlockEntity)) {
            e.discard();
        }
    }

    // ================================================================ дубли

    /**
     * Дубль: прежние самолёты и лётчики убраны, полоса расчищена, на старте новая пара, лётчики садятся щелчком
     * и смотрят вперёд; {@code cruise} — высота ухода над полем, {@code shells} — разрывы дубля.
     */
    private void take(double cruise, Shell... shells) {
        film.onServer(level -> {
            this.cruise = cruise;
            this.shells.clear();
            this.shells.addAll(List.of(shells));
            clear(level);
            sweep(level);
        });
        film.waitTicks(10);
        film.onServer(level -> {
            for (int i = 0; i < PILOTS.length; i++) {
                Entity plane = biplane(level, point(START[i][0], 0.05, START[i][1]), yaw);
                planes[i] = plane == null ? null : plane.getUUID();
                // лётчик — у левого борта за нижним крылом
                film.cast().spawn(PILOTS[i], point(START[i][0] - 2.2, 0, START[i][1] - 1.2), yaw + 90, null);
            }
        });
        film.waitTicks(40);
        film.onServer(level -> {
            for (int i = 0; i < PILOTS.length; i++) {
                String p = PILOTS[i];
                film.cast().command("gamemode creative " + p);
                // лётный шлем и кожанка
                film.cast().command(fmt("item replace entity %s armor.head with "
                        + "minecraft:leather_helmet[dyed_color={rgb:5124110,show_in_tooltip:false}]", p));
                film.cast().command(fmt("item replace entity %s armor.chest with "
                        + "minecraft:leather_chestplate[dyed_color={rgb:6704179,show_in_tooltip:false}]", p));
                if (planes[i] == null) continue;
                JsonArray a = Cast.steps();
                JsonObject click = Cast.step("click", "use");
                click.addProperty("at", planes[i].toString());
                a.add(click);
                film.cast().act(p, a, true);
            }
        });
        film.waitTicks(20);
        film.onServer(level -> {
            for (int i = 0; i < PILOTS.length; i++) {
                Entity plane = planes[i] == null ? null : level.getEntity(planes[i]);
                ServerPlayer pilot = film.cast().player(PILOTS[i]);
                if (plane == null || pilot == null) {
                    Incoming.LOG.warn("TRAILER airfield: нет самолёта или лётчика {}", PILOTS[i]);
                    continue;
                }
                if (pilot.getVehicle() != plane) {
                    // щелчок не посадил (далеко, промах) — как командой ride
                    Incoming.LOG.warn("TRAILER airfield: {} не сел щелчком, сажаю командой", PILOTS[i]);
                    film.cast().command("ride " + PILOTS[i] + " mount " + planes[i]);
                }
                // взгляд — вперёд по полосе
                JsonArray look = new JsonArray();
                look.add(yaw);
                look.add(2);
                JsonArray a = Cast.steps();
                a.add(Cast.step("look", look));
                film.cast().act(PILOTS[i], a, true);
            }
        });
        film.waitTicks(10);
    }

    /** Самолёты прошлого дубля — прочь (без лётчика самолёт IA падает и рвётся), лётчики выходят. */
    private void clear(ServerLevel level) {
        for (int i = 0; i < PILOTS.length; i++) {
            Entity old = planes[i] == null ? null : level.getEntity(planes[i]);
            if (old != null) old.discard();
            planes[i] = null;
            if (film.cast().player(PILOTS[i]) != null) film.cast().remove(PILOTS[i]);
        }
    }

    /** Мир пошёл (камера готова): первый залп дубля сразу, остальные — со своими промежутками; разбег — по первому. */
    private void arm() {
        MinecraftServer server = film.mc.getSingleplayerServer();
        server.execute(() -> {
            armTick = server.getTickCount();
            goTick = -1;
            ordered.clear();
            armed = true;
        });
    }

    /** Конец дубля: рули отпущены, автопилот снят (самолёты убирает следующий дубль или конец сцены). */
    private void land() {
        MinecraftServer server = film.mc.getSingleplayerServer();
        server.execute(() -> {
            armed = false;
            for (String p : PILOTS) {
                JsonArray a = Cast.steps();
                a.add(Cast.step("release", "all"));
                film.cast().act(p, a, true);
            }
        });
    }

    /**
     * Дубль в потоке сервера, как правило ведущего: залпы (первый — сразу, остальные — с промежутками, задуманными
     * между разрывами), начало разбега — когда первому снаряду до разрыва осталось задуманное число тиков; дальше
     * автопилот: курс по полосе, на земле штурвал не трогает, в воздухе держит тангаж по высоте — круче у земли,
     * полого к высоте ухода, над ней горизонт. Клавиши — с отпусканием через 2 тика (как у правила каждый тик).
     */
    private void tick(ServerTickEvent.Post e) {
        if (!armed || e.getServer() != film.mc.getSingleplayerServer() || e.getServer().tickRateManager().isFrozen()) return;
        ServerLevel level = e.getServer().overworld();
        long now = e.getServer().getTickCount();
        Shell first = shells.isEmpty() ? null : shells.getFirst();
        for (Shell s : shells) {
            if (!ordered.contains(s) && now - armTick >= s.at - first.at) {
                ordered.add(s);
                order("rocket 1 0", s.r, s.f);
            }
        }
        if (goTick < 0) {
            int eta = Integer.MAX_VALUE;
            for (StrikeProjectile p : FarFlights.flights(level)) if (p instanceof RocketEntity) eta = Math.min(eta, p.etaTicks());
            boolean late = now - armTick > 800;
            if (first == null || eta <= first.at || late) {
                if (late) Incoming.LOG.warn("TRAILER airfield: снаряд дубля не долетает, разбег без него");
                goTick = now;
                for (String p : PILOTS) {
                    JsonArray a = Cast.steps();
                    a.add(Cast.hold("jump", 12));
                    film.cast().act(p, a, true);
                }
                Incoming.LOG.info("TRAILER airfield: разбег через {} тиков после залпа", now - armTick);
            }
            return;
        }
        long t = now - goTick;
        StringBuilder line = t % 20 == 0 ? new StringBuilder() : null;
        for (int i = 0; i < PILOTS.length; i++) {
            Entity plane = planes[i] == null ? null : level.getEntity(planes[i]);
            if (plane == null) continue;
            double h = plane.getY() - at.y;
            JsonArray a = Cast.steps();
            float off = Mth.wrapDegrees(plane.getYRot() - yaw);
            if (off > 1) a.add(Cast.hold("left", 2));
            else if (off < -1) a.add(Cast.hold("right", 2));
            if (!plane.onGround() && h > 0.4) {
                double want = h < 5 ? -11 : h < cruise ? -11 + 9 * (h - 5) / (cruise - 5) : h < cruise + 3 ? 0 : 3;
                double err = plane.getXRot() - want;
                if (err > 1.5) a.add(Cast.hold("back", 2));
                else if (err < -1.5) a.add(Cast.hold("forward", 2));
            }
            if (!a.isEmpty()) film.cast().act(PILOTS[i], a, true);
            if (line != null) {
                line.append(fmt(" | %s f=%.1f h=%.2f v=%.3f pitch=%.1f yaw=%.1f%s", PILOTS[i], progress(plane), h,
                        plane.getDeltaMovement().length(), plane.getXRot(), off, plane.onGround() ? " ground" : ""));
            }
        }
        if (line != null) Incoming.LOG.info("TRAILER airfield t={}{}", t, line);
    }

    /** Приказ мода от консоли: {@code what} — оружие, сколько и разброс; пусковая — далеко в стороне от точки. */
    private void order(String what, double r, double f) {
        Vec3 target = point(r, 0, f), from = point(r + Math.signum(r) * LAUNCHER_OFF, 0, f + 30);
        film.cast().command(fmt("incoming salvo %s at %.1f %.1f %.1f from %.1f %.1f", what, target.x, target.y, target.z, from.x, from.z));
        Incoming.LOG.info("TRAILER airfield: {} по r={} f={}", what, r, f);
    }

    /** Разрывы дубля — в лог: где по осям сцены и где в этот миг ведущий (для подгонки залпов под кадр). */
    private void blast(ExplosionEvent.Start e) {
        if (!armed || e.getExplosion().radius() < 5 || !(e.getLevel() instanceof ServerLevel level)) return;
        Vec3 c = e.getExplosion().center().subtract(at);
        Entity lead = planes[0] == null ? null : level.getEntity(planes[0]);
        Incoming.LOG.info("TRAILER airfield: разрыв r={} f={} на t={}, ведущий f={}", fmt("%.1f", c.dot(right)), fmt("%.1f", c.dot(fwd)),
                goTick < 0 ? "-" : level.getServer().getTickCount() - goTick, lead == null ? "-" : fmt("%.1f", progress(lead)));
    }

    // ================================================================ кто где

    /** Самолёт лётчика {@code i}, как его видит клиент. */
    @Nullable
    private Entity plane(int i) {
        Entity p = film.bot(PILOTS[i]);
        return p == null ? null : p.getVehicle();
    }

    /** Середина пары (для взгляда камеры); пока самолётов нет — null. */
    @Nullable
    private Vec3 pair() {
        Entity a = plane(0), b = plane(1);
        float pt = CineCamera.partial();
        if (a == null || b == null) return a != null ? a.getPosition(pt).add(0, 1, 0) : null;
        return a.getPosition(pt).add(b.getPosition(pt)).scale(0.5).add(0, 1, 0);
    }

    /** Сколько блоков сущность прошла вперёд по полосе от торца. */
    private double progress(@Nullable Entity e) {
        return e == null ? -1e9 : e.position().subtract(at).dot(fwd);
    }

    @Nullable
    private Entity biplane(ServerLevel level, Vec3 p, float heading) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(BIPLANE).orElse(null);
        Entity e = type == null ? null : type.create(level);
        if (e == null) {
            Incoming.LOG.error("TRAILER airfield: нет {} — Immersive Aircraft не в модах съёмки", BIPLANE);
            return null;
        }
        e.moveTo(p.x, p.y, p.z, heading, 0);
        level.addFreshEntity(e);
        return e;
    }

    // ================================================================ блоки в осях сцены

    private interface Cell {
        void at(int r, int f);
    }

    /**
     * Все клетки прямоугольника в осях сцены. При курсе не по сторонам света клетка ложится на блоки мира наискось —
     * обход повторяется со сдвигом в полблока ({@link #sub}), чтобы между клетками не было дыр.
     */
    private void each(int r0, int r1, int f0, int f1, Cell cell) {
        double[] subs = yaw % 90 == 0 ? new double[]{0} : new double[]{0, 0.5};
        for (double sr : subs) {
            for (double sf : subs) {
                sub[0] = sr;
                sub[1] = sf;
                for (int r = r0; r <= r1; r++) for (int f = f0; f <= f1; f++) cell.at(r, f);
            }
        }
        sub[0] = sub[1] = 0;
    }

    private void set(ServerLevel level, int r, int y, int f, BlockState s) {
        level.setBlock(pos(r, y, f), s, Block.UPDATE_ALL);
    }

    /** Блок клетки: середина клетки (со сдвигом обхода), {@code y} — от первого блока над землёй. */
    private BlockPos pos(int r, int y, int f) {
        return BlockPos.containing(Scene.local(at, yaw, r + sub[0], y, f + sub[1]));
    }

    private Vec3 point(double r, double up, double f) {
        return Scene.local(at, yaw, r, up, f);
    }

    private static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }
}
