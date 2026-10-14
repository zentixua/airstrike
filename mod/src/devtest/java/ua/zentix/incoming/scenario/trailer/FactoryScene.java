package ua.zentix.incoming.scenario.trailer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.List;

/**
 * Цех Запада: механические крафтеры Create собирают крылатую ракету по настоящему рецепту мода
 * ({@code data/incoming/recipe/cruise_missile.json}: IMI/BTB/IMI — железо, прецизионный механизм, огненный порошок,
 * динамит). Сетка 3×3 крафтеров стоит стеной, лицом к залу; детали передаются стрелками к нижнему среднему, он
 * собирает ракету и кладёт её на ленту под собой — лента везёт ракеты к очереди готовых. Детали закладывают руками
 * два рабочих (боты Almighty, щелчок по лицу крафтера — как игрок), по бокам зала — прессы на лентах (слитки из
 * воронок становятся листами), наверху — вал с шестернями. Всё крутят творческие моторы; цех работает сам, сцена
 * только снимает.
 * <p>
 * Блоки стоят по осям: курс округляется до 90°. Перед съёмкой цех делает {@link #PRE_RUN} ракет — это и есть ракеты
 * в очереди на ленте.
 */
final class FactoryScene {
    /** Ракет до первого плана: очередь готовых на ленте. */
    static final int PRE_RUN = 3;
    /** Обороты крафтеров: при 64 об/мин передача детали ~0.8 с, сборка ~1.6 с — сведение видно в одном плане. */
    static final int CRAFTER_RPM = 64;
    /** Обороты лент: 48 об/мин — 2 блока в секунду, ракета доезжает от крафтера до очереди за пару секунд. */
    static final int BELT_RPM = 48;

    private final Film film;
    private final BlockPos o;
    private final Direction fwd, right;
    /** Цех за работой: ставится на сервере, сценарий читает его поля из потока клиента. */
    private volatile Works works;

    private FactoryScene(Film film, Vec3 at, float yaw) {
        this.film = film;
        this.o = BlockPos.containing(at);
        this.fwd = Direction.fromYRot(yaw);
        this.right = fwd.getClockWise();
    }

    /** Цех от точки {@code at} (пол — блок под ней) курсом {@code yaw}: вход сзади, стена крафтеров впереди. */
    static void stage(Film film, Vec3 at, float yaw) {
        new FactoryScene(film, at, yaw).script();
    }

    /** Статисты сервера: берутся в шагах, когда сервер уже есть (при постановке сцены его ещё нет). */
    private Cast cast() {
        return ((Trailer) film).cast();
    }

    private void script() {
        BlockPos a = p(-11, 0, -16), b = p(11, 0, 16);
        film.onServer(level -> cast().command(fmt("forceload add %d %d %d %d", a.getX(), a.getZ(), b.getX(), b.getZ())));
        waitLoaded();
        film.onServer(level -> build(cast()));
        film.run(() -> flywheel("flywheel:instancing"));
        film.waitTicks(20);
        film.onServer(level -> {
            for (Worker w : workers()) {
                cast().spawn(w.name, w.rest, Film.yawTo(w.rest, grid()), null);
                cast().steady(w.name);  // ставит и ломает блоки по взгляду
            }
        });
        film.waitTicks(60);
        film.onServer(level -> {
            works = new Works(cast(), level, workers());
            for (Worker w : works.workers) dress(cast(), w.name);
            works.on = true;
        });
        // цех делает первые ракеты сам (между планами мир идёт со скоростью 20 тиков в секунду)
        film.then(() -> works != null && (works.made >= PRE_RUN || works.age > 20 * 120));
        film.run(() -> Incoming.LOG.info("TRAILER factory: готово ракет {}", works.made));

        // 1. Крупно сбоку: рабочие докладывают детали, крафтеры сводят их к середине, ракета собрана
        film.shot("p05").length(120).hidden()
                .camera(() -> CineCamera.dolly(v(1.5, 3.3, 4.8), v(0.7, 2.9, 5.5), 120, () -> v(0, 2.2, 8.5), 58))
                .when(() -> works.loaded >= 6 && works.loaded < 9, 900);
        // 2. Через цех: прессы штампуют листы, лента с ракетами, у дальней стены рабочие закладывают новый цикл
        film.shot("p05b").length(110).hidden()
                .camera(() -> CineCamera.glide(v(-3.2, 4.2, -13.2), v(-1.4, 3.6, -7.4), 110, () -> v(0, 1.4, 7.0), 70))
                .when(() -> works.cycleAge >= 0 && works.cycleAge < 6, 900);
        // 3. Выход: вдоль ленты над очередью готовых ракет — нижний крафтер кладёт новую, она едет к очереди
        film.shot("p05c").length(100).hidden()
                .camera(() -> CineCamera.dolly(v(1.3, 1.6, 1.4), v(1.0, 1.5, 2.4), 100, () -> v(0, 0.9, 8.2), 55))
                .when(() -> works.craftingAge >= 0 && works.craftingAge < 4, 900);
        film.onServer(level -> {
            works.on = false;
            for (Worker w : works.workers) cast().remove(w.name);
        });
        film.run(() -> flywheel("DEFAULT"));
    }

    /**
     * Ленты Create рисует Flywheel. Его выбор по умолчанию на программном OpenGL записи (llvmpipe) — «indirect» — рисует
     * ленту обрывками не на своих местах, «instancing» — как в игре; на время цеха — он, потом снова выбор Flywheel.
     * Переключает клиентская команда самого Flywheel ({@code /flywheel backend}).
     */
    private static void flywheel(String backend) {
        if (!ClientCommandHandler.runCommand("flywheel backend " + backend)) {
            Incoming.LOG.warn("TRAILER factory: нет команды Flywheel, ленты рисует его выбор по умолчанию");
        }
    }

    /** Ждать, пока чанки цеха загружены: {@code fill} в незагруженном месте не делает ничего. */
    private void waitLoaded() {
        final boolean[] asked = {false};
        final boolean[] ready = {false};
        film.then(() -> {
            if (!asked[0]) {
                asked[0] = true;
                MinecraftServer server = film.mc.getSingleplayerServer();
                server.execute(() -> {
                    BlockPos a = p(-11, 0, -16), b = p(11, 0, 16);
                    boolean all = true;
                    for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
                        for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                            if (server.overworld().getChunkSource().getChunkNow(cx, cz) == null) all = false;
                        }
                    }
                    ready[0] = all;
                    film.mc.execute(() -> asked[0] = false);
                });
            }
            return ready[0];
        });
    }

    // ================================================================ цех

    /** Середина лиц крафтеров (на неё смотрят рабочие, когда ждут). */
    private Vec3 grid() {
        return v(0, 2.5, 8.5);
    }

    private List<Worker> workers() {
        // левый — левый столбец, верх и середина среднего; правый — правый столбец и нижний средний (собирающий)
        Worker left = new Worker("Fitter", v(-2.9, 0, 7.4), v(-4.6, 0, 5.4));
        Worker rightW = new Worker("Turner", v(2.9, 0, 7.4), v(4.6, 0, 5.4));
        // раскладка рецепта: I M I / B T B / I M I (ряд u=3 — верхний); ячейки хотбара: 0 железо, 1 механизм, 2 порошок, 3 динамит
        left.load(p(-1, 3, 9), 0);
        left.load(p(0, 3, 9), 1);
        left.load(p(-1, 2, 9), 2);
        left.load(p(0, 2, 9), 3);
        left.load(p(-1, 1, 9), 0);
        rightW.load(p(1, 3, 9), 0);
        rightW.load(p(1, 2, 9), 2);
        rightW.load(p(1, 1, 9), 0);
        rightW.load(p(0, 1, 9), 1);
        return List.of(left, rightW);
    }

    private void build(Cast cast) {
        Direction back = fwd.getOpposite(), left = right.getOpposite();
        String fa = fwd.getAxis().getSerializedName();
        // площадка: фундамент, пол, пустой зал
        fill(cast, -11, -3, -16, 11, -2, 16, "minecraft:stone");
        fill(cast, -10, -1, -15, 10, -1, 15, "minecraft:polished_andesite");
        fill(cast, -11, 0, -16, 11, 8, 16, "minecraft:air");
        // стены из кирпича, столбы — корпус Create, окна по длинным стенам
        fill(cast, -10, 0, -15, -10, 6, 15, "minecraft:bricks");
        fill(cast, 10, 0, -15, 10, 6, 15, "minecraft:bricks");
        fill(cast, -10, 0, -15, 10, 6, -15, "minecraft:bricks");
        fill(cast, -10, 0, 15, 10, 6, 15, "minecraft:bricks");
        String pane = fmt("minecraft:glass_pane[%s=true,%s=true]", fwd.getSerializedName(), back.getSerializedName());
        for (int side : new int[]{-10, 10}) {
            fill(cast, side, 2, -14, side, 4, 14, pane);
            for (int f = -15; f <= 15; f += 5) fill(cast, side, 0, f, side, 6, f, "create:andesite_casing");
        }
        // ворота в торце за спиной зрителя
        fill(cast, -2, 0, -15, 2, 3, -15, "minecraft:air");
        // крыша с полосами стекла (свет неба) и морскими фонарями между ними
        fill(cast, -10, 7, -15, 10, 7, 15, "minecraft:spruce_planks");
        for (int f = -12; f <= 12; f += 4) fill(cast, -9, 7, f, 9, 7, f, "minecraft:glass");
        for (int f = -14; f <= 14; f += 4) {
            for (int r : new int[]{-7, 7}) set(cast, r, 7, f, "minecraft:sea_lantern");
        }
        // фонари на цепях над проходами
        for (int f = -11; f <= 4; f += 5) {
            for (int r : new int[]{-3, 3}) {
                set(cast, r, 6, f, "minecraft:chain[axis=y]");
                set(cast, r, 5, f, "minecraft:lantern[hanging=true]");
            }
        }

        // ---- стена крафтеров: лицом к залу (назад по курсу), детали идут к нижнему среднему
        for (int r = -1; r <= 1; r++) {
            for (int u = 1; u <= 3; u++) {
                Direction target = r < 0 ? right : r > 0 ? left : Direction.DOWN;
                set(cast, r, u, 9, fmt("create:mechanical_crafter[facing=%s,pointing=%s]", back.getSerializedName(), pointing(target, back)));
            }
        }
        // рама: столбы и балка из латунного корпуса
        fill(cast, -3, 0, 9, -3, 4, 9, "create:brass_casing");
        fill(cast, 3, 0, 9, 3, 4, 9, "create:brass_casing");
        fill(cast, -2, 4, 9, 2, 4, 9, "create:brass_casing");
        set(cast, -1, 0, 9, "create:andesite_casing");
        set(cast, -2, 0, 9, "create:andesite_casing");
        set(cast, 2, 0, 9, "create:andesite_casing");
        // шестерни по бокам сетки (крафтеры — шестерни между собой), левую крутит мотор за стеной
        set(cast, -2, 2, 9, fmt("create:cogwheel[axis=%s]", fa));
        set(cast, 2, 2, 9, fmt("create:cogwheel[axis=%s]", fa));
        set(cast, -2, 2, 10, fmt("create:creative_motor[facing=%s]{ScrollValue:%d}", back.getSerializedName(), CRAFTER_RPM));

        // ---- лента ракет: из-под нижнего среднего крафтера к залу, в конце — упор (очередь готовых)
        belt(cast, 0, 9, 1);
        set(cast, 0, 0, 0, "create:andesite_casing");
        set(cast, 1, 0, 9, fmt("create:creative_motor[facing=%s]{ScrollValue:%d}", left.getSerializedName(),
                beltMotor(left, back, BELT_RPM)));

        // ---- прессы по бокам: воронка со слитками → лента → пресс → жёлоб → бочка в полу
        pressLine(cast, -7, "minecraft:iron_ingot", left);
        pressLine(cast, 7, "minecraft:copper_ingot", right);

        // ---- вал под крышей с шестернями
        set(cast, 0, 6, -14, fmt("create:creative_motor[facing=%s]{ScrollValue:32}", fwd.getSerializedName()));
        for (int f = -13; f <= 7; f++) {
            set(cast, 0, 6, f, fmt(f % 4 == 2 ? "create:cogwheel[axis=%s]" : "create:shaft[axis=%s]", fa));
        }

        // ---- склад у рабочих: бочки и ящики
        for (int r : new int[]{-6, 6}) {
            set(cast, r, 0, 12, "minecraft:barrel[facing=up]");
            set(cast, r, 1, 12, "minecraft:barrel[facing=up]");
            set(cast, r + Integer.signum(r), 0, 12, "minecraft:barrel[facing=up]");
            set(cast, r, 0, 13, "minecraft:chest[facing=" + back.getSerializedName() + "]");
        }
        // шкафы вдоль торцевой стены за крафтерами
        fill(cast, -9, 0, 14, 9, 2, 14, "create:andesite_casing");
        Incoming.LOG.info("TRAILER factory: цех построен у {} курсом {}", o, fwd);
    }

    /**
     * Линия пресса вдоль зала на {@code r}: воронка со слитками сыплет их на ленту, пресс посреди зала штампует листы,
     * жёлоб в конце ленты ссыпает их в бочку в полу. Мотор ленты — сбоку от её начала.
     */
    private void pressLine(Cast cast, int r, String ingot, Direction outward) {
        String fa = fwd.getAxis().getSerializedName();
        belt(cast, r, -9, 6);
        Direction motor = outward.getOpposite();
        set(cast, r + Integer.signum(r), 0, -9, fmt("create:creative_motor[facing=%s]{ScrollValue:%d}", motor.getSerializedName(),
                beltMotor(motor, fwd, BELT_RPM)));
        StringBuilder items = new StringBuilder();
        for (int s = 0; s < 5; s++) items.append(s == 0 ? "" : ",").append(fmt("{Slot:%db,id:\"%s\",count:64}", s, ingot));
        set(cast, r, 1, -9, "minecraft:hopper[facing=down]{Items:[" + items + "]}");
        // пресс над лентой (зазор в блок — ход головы), вал от мотора над воронкой
        set(cast, r, 2, -9, fmt("create:creative_motor[facing=%s]{ScrollValue:96}", fwd.getSerializedName()));
        for (int f = -8; f <= 0; f++) set(cast, r, 2, f, fmt("create:shaft[axis=%s]", fa));
        set(cast, r, 2, 1, fmt("create:mechanical_press[facing=%s]", fwd.getSerializedName()));
        set(cast, r, 0, 7, "create:chute");
        set(cast, r, -1, 7, "minecraft:barrel[facing=up]");
    }

    /**
     * Прокрутка творческого мотора, который смотрит {@code motor} на шкив ленты, чтобы лента везла к {@code to}. Знаки —
     * как у Create: мотор отдаёт прокрутку со знаком своей стороны ({@code KineticBlockEntity.convertToDirection}),
     * лента с положительной скоростью везёт к +Z, а вдоль X — к −X ({@code BeltBlockEntity.getMovementFacing}).
     */
    static int beltMotor(Direction motor, Direction to, int rpm) {
        int belt = to.getAxisDirection().getStep() * (to.getAxis() == Direction.Axis.X ? -1 : 1);
        return rpm * belt * motor.getAxisDirection().getStep();
    }

    /** Лента Create вдоль курса на {@code r} от {@code f0} (начало) до {@code f1} (конец) на полу. */
    private void belt(Cast cast, int r, int f0, int f1) {
        Direction facing = f1 > f0 ? fwd : fwd.getOpposite();
        String state = "create:belt[facing=%s,slope=horizontal,part=%s,casing=false]";
        fill(cast, r, 0, f0, r, 0, f1, fmt(state, facing.getSerializedName(), "middle"));
        set(cast, r, 0, f0, fmt(state, facing.getSerializedName(), "start"));
        set(cast, r, 0, f1, fmt(state, facing.getSerializedName(), "end"));
    }

    /** Рабочая одежда: синий комбинезон и жёлтая каска. */
    private static void dress(Cast cast, String name) {
        String[][] parts = {{"head", "helmet", "15250432"}, {"chest", "chestplate", "3100543"}, {"legs", "leggings", "3100543"},
                {"feet", "boots", "4007700"}};
        for (String[] part : parts) {
            cast.command(fmt("item replace entity %s armor.%s with minecraft:leather_%s[dyed_color={rgb:%s,show_in_tooltip:false}]",
                    name, part[0], part[1], part[2]));
        }
        cast.command(fmt("item replace entity %s hotbar.0 with minecraft:iron_ingot 64", name));
        cast.command(fmt("item replace entity %s hotbar.1 with create:precision_mechanism 32", name));
        cast.command(fmt("item replace entity %s hotbar.2 with minecraft:blaze_powder 32", name));
        cast.command(fmt("item replace entity %s hotbar.3 with minecraft:tnt 32", name));
    }

    /**
     * Свойство {@code pointing} крафтера, который передаёт детали в сторону {@code target}, при лице {@code facing} —
     * обратное к {@code MechanicalCrafterBlock.pointingFromFacing} (так Create ставит крафтер, приставленный к другому).
     */
    static String pointing(Direction target, Direction facing) {
        Direction face = target.getOpposite();
        boolean positive = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        return switch (face) {
            case DOWN -> "up";
            case UP -> "down";
            case EAST, NORTH -> positive ? "left" : "right";
            case WEST, SOUTH -> positive ? "right" : "left";
        };
    }

    // ================================================================ место

    /** Блок цеха: {@code r} вправо, {@code u} вверх от пола (0 — первый блок над полом), {@code f} вперёд по курсу. */
    BlockPos p(int r, int u, int f) {
        return o.relative(right, r).relative(Direction.UP, u).relative(fwd, f);
    }

    /** Точка цеха: {@code r}, {@code f} — от середины блока {@code at}, {@code u} — от пола. */
    Vec3 v(double r, double u, double f) {
        Vec3 c = Vec3.atBottomCenterOf(o);
        return c.add(right.getStepX() * r + fwd.getStepX() * f, u, right.getStepZ() * r + fwd.getStepZ() * f);
    }

    private void set(Cast cast, int r, int u, int f, String block) {
        BlockPos b = p(r, u, f);
        cast.command(fmt("setblock %d %d %d %s", b.getX(), b.getY(), b.getZ(), block));
    }

    private void fill(Cast cast, int r1, int u1, int f1, int r2, int u2, int f2, String block) {
        BlockPos a = p(r1, u1, f1), b = p(r2, u2, f2);
        cast.command(fmt("fill %d %d %d %d %d %d %s", a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ(), block));
    }

    private static String fmt(String f, Object... a) {
        return Cast.fmt(f, a);
    }

    // ================================================================ работа цеха

    /** Рабочий: где стоит, когда закладывает, где ждёт, и что куда кладёт (крафтер, ячейка хотбара). */
    final class Worker {
        final String name;
        final Vec3 post, rest;
        final List<BlockPos> crafters = new ArrayList<>();
        final List<Integer> slots = new ArrayList<>();
        int next = -1;
        long busyUntil;
        boolean walkingIn;

        Worker(String name, Vec3 post, Vec3 rest) {
            this.name = name;
            this.post = post;
            this.rest = rest;
        }

        void load(BlockPos crafter, int slot) {
            crafters.add(crafter);
            slots.add(slot);
        }
    }

    /**
     * Цех за работой: решает поток сервера раз в тик и только пока мир не стоит (как бой {@link Fight}). Все крафтеры
     * пусты и стоят — рабочие идут к сетке и закладывают детали по одной (взгляд на ячейку за пару тиков, щелчок); девятая
     * деталь запускает сборку (так устроен крафтер Create), рабочие отходят. Сценарий смотрит на поля ниже (из потока
     * клиента).
     */
    final class Works {
        final Cast cast;
        final ServerLevel level;
        final List<Worker> workers;
        final List<BlockPos> crafters = new ArrayList<>();
        final BlockPos output;
        volatile boolean on;
        long ticks, restUntil;
        boolean cycle;
        String lastOutPhase = "IDLE";
        /**
         * Деталей в крафтерах сейчас; ракет сделано; тиков работы цеха; тиков от начала закладки (-1 — не закладывают);
         * тиков сборки (-1 — нет).
         */
        volatile int loaded, made, age, cycleAge = -1, craftingAge = -1;

        Works(Cast cast, ServerLevel level, List<Worker> workers) {
            this.cast = cast;
            this.level = level;
            this.workers = workers;
            for (Worker w : workers) crafters.addAll(w.crafters);
            output = p(0, 1, 9);
            NeoForge.EVENT_BUS.addListener(this::tick);
        }

        private void tick(ServerTickEvent.Post e) {
            if (!on || e.getServer() != level.getServer() || level.getServer().tickRateManager().isFrozen()) return;
            age = (int) ++ticks;
            int held = 0;
            boolean idle = true;
            for (BlockPos c : crafters) {
                IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, c, null);
                if (h != null && !h.getStackInSlot(0).isEmpty()) held++;
                if (!phase(c).equals("IDLE")) idle = false;
            }
            loaded = held;
            String out = phase(output);
            craftingAge = out.equals("CRAFTING") ? craftingAge + 1 : -1;
            if (!out.equals(lastOutPhase)) {
                Incoming.LOG.info("TRAILER factory: сборщик {} → {} (деталей {})", lastOutPhase, out, held);
                if (lastOutPhase.equals("CRAFTING") || lastOutPhase.equals("INSERTING")) {
                    if (out.equals("IDLE")) {
                        made++;
                        Incoming.LOG.info("TRAILER factory: ракета {}, на ленте {}", made, onBelt());
                    }
                }
                lastOutPhase = out;
            }
            if (cycle && !idle) {
                // девятая деталь легла — крафтеры работают, закладка кончилась
                cycle = false;
                cycleAge = -1;
                restUntil = ticks + 30;
            }
            if (!cycle && idle && held == 0 && ticks >= restUntil) startCycle();
            if (cycle) cycleAge++;
            for (Worker w : workers) work(w);
        }

        /** Ракет на ленте: предметы в инвентаре её начала (оно — главный блок ленты). */
        private int onBelt() {
            BlockEntity be = level.getBlockEntity(p(0, 0, 9));
            return be == null ? -1 : be.saveWithoutMetadata(level.registryAccess()).getCompound("Inventory").getList("Items", Tag.TAG_COMPOUND).size();
        }

        private String phase(BlockPos c) {
            BlockEntity be = level.getBlockEntity(c);
            return be == null ? "?" : be.saveWithoutMetadata(level.registryAccess()).getString("Phase");
        }

        private void startCycle() {
            cycle = true;
            cycleAge = 0;
            for (Worker w : workers) {
                w.next = 0;
                w.walkingIn = true;
                w.busyUntil = ticks + 60;
                JsonArray a = Cast.steps();
                a.add(walk(w.post));
                a.add(Cast.lookAt(grid()));
                cast.act(w.name, a, true);
            }
        }

        private void work(Worker w) {
            ServerPlayer p = cast.player(w.name);
            if (p == null || w.next < 0) return;
            if (w.walkingIn) {
                if (p.position().distanceToSqr(w.post.x, p.getY(), w.post.z) > 0.35 * 0.35 && ticks < w.busyUntil) return;
                w.walkingIn = false;
                w.busyUntil = ticks + 4;
            }
            if (ticks < w.busyUntil) return;
            if (w.next < w.crafters.size()) {
                BlockPos c = w.crafters.get(w.next);
                int slot = w.slots.get(w.next);
                w.next++;
                // точка на лице крафтера: луч от глаз рабочего попадает в лицо, а не в соседа
                Vec3 face = Vec3.atCenterOf(c).add(Vec3.atLowerCornerOf(fwd.getOpposite().getNormal()).scale(0.45));
                Vec3 eye = p.getEyePosition();
                Vec3 aim = eye.add(p.getLookAngle().scale(eye.distanceTo(face)));
                JsonArray a = Cast.steps();
                a.add(Cast.step("slot", slot));
                // взгляд переводится за три тика, как у игрока мышью
                a.add(Cast.lookAt(aim.lerp(face, 0.4)));
                a.add(Cast.step("wait", 1));
                a.add(Cast.lookAt(aim.lerp(face, 0.8)));
                a.add(Cast.step("wait", 1));
                a.add(Cast.lookAt(face));
                a.add(Cast.step("wait", 2));
                JsonObject click = Cast.step("click", "use");
                click.add("at", Cast.vec(face));
                a.add(click);
                cast.act(w.name, a, true);
                w.busyUntil = ticks + 14;
            } else {
                // всё заложено — отойти и смотреть на сборку
                w.next = -1;
                JsonArray a = Cast.steps();
                a.add(Cast.step("wait", 4));
                a.add(walk(w.rest));
                a.add(Cast.lookAt(grid()));
                cast.act(w.name, a, true);
            }
        }

        private JsonObject walk(Vec3 to) {
            JsonArray xz = new JsonArray();
            xz.add(to.x);
            xz.add(to.z);
            JsonObject o = Cast.step("walk_to", xz);
            o.addProperty("within", 0.3);
            return o;
        }
    }
}
