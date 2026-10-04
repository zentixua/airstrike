package ua.zentix.airstrike.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.defense.DefenseWorld;
import ua.zentix.airstrike.defense.Interceptor;
import ua.zentix.airstrike.defense.Radar;
import ua.zentix.airstrike.defense.SamBlockEntity;
import ua.zentix.airstrike.entity.DebrisEntity;
import ua.zentix.airstrike.entity.DroneEntity;
import ua.zentix.airstrike.entity.IcbmEntity;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.registry.ModBlocks;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.registry.ModItems;
import ua.zentix.airstrike.strike.FlightLog;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.target.Sides;
import ua.zentix.airstrike.target.Sightings;
import ua.zentix.airstrike.target.Target;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * ЗРК ({@link SamBlockEntity}): сбивает чужие снаряды мода в дальности огня — по ракете на цель, с перезарядкой
 * направляющей; своих, команду, дальних, МБР и «Град» не трогает; без ракет не стреляет; цель вне загрузки бьёт, не
 * загрузив ни чанка. Блок заряжается воронкой (вынуть ею нельзя), ломается и взрывается с содержимым, рецепт — по
 * тому, стоит ли Create. У каждой проверки своя партия: ЗРК видит все снаряды мира, и соседняя проверка сбивала бы его
 * счёт ракет. Дальности — малые, поражение — наверняка ({@link #air}); всё возвращается после проверки.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SamGameTests {
    /** ЗРК на полосе «runway»: земля полосы — y 3, шахеды летят над ней на +Z к её концу (z 200). */
    private static final BlockPos SAM = new BlockPos(16, 4, 100);
    /** Высота шахедов над началом площадки. */
    private static final int DRONE_Y = 50;
    /** Середина площадки «range» и её земля (первый воздух — y 11). */
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private SamGameTests() {}

    // ---------------------------------------------------------------- сбивает чужих

    /**
     * Два чужих шахеда (без владельца) летят над ЗРК: по каждому — одна ракета, оба сбиты в воздухе (обломки, строка
     * лога «сбиты зенитной ракетой»), цели в конце полосы целы. Направляющая одна, перезарядка — секунда: первая ракета
     * — не раньше чем через секунду после установки, вторая — через секунду после первой; из трёх ракет осталась одна.
     * Ракету видно (её вид в мире). Тревога — расчёту (хозяину ЗРК), чужому рядом — нет; цели — на карте стороны расчёта.
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "sam_kill", skyAccess = true)
    public static void shootsDownHostileDrones(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 160, 96, 1, 1);
        Listener crew = listener(h, "sam_crew", SAM.east(3)), stranger = listener(h, "sam_stranger", SAM.west(3));
        long placed = level.getGameTime();
        SamBlockEntity sam = sam(h, SAM, crew.getUUID(), 3);
        FlightLog log = StrikeWorld.get(level).flightLog();
        int intercepted = log.total(FlightLog.Event.INTERCEPTED);
        DroneEntity a = drone(h, 10, null), b = drone(h, 22, null);
        Launches launches = new Launches(level, crew.getUUID());
        AABB runway = new AABB(Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(-8, 0, -8))), Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(40, 120, 264))));
        boolean[] debris = {false};
        h.onEachTick(() -> {
            launches.watch();
            if (!debris[0]) debris[0] = !level.getEntitiesOfClass(DebrisEntity.class, runway).isEmpty();
        });
        h.succeedWhen(() -> {
            h.assertTrue(a.isRemoved() && b.isRemoved(), "шахеды ещё летят: " + h.relativeVec(a.position()) + ", " + h.relativeVec(b.position()));
            h.assertTrue(log.total(FlightLog.Event.INTERCEPTED) - intercepted == 2, "сбито " + (log.total(FlightLog.Event.INTERCEPTED) - intercepted) + " из 2");
            h.assertTrue(intact(h, new BlockPos(10, 3, 200)) && intact(h, new BlockPos(22, 3, 200)), "шахед долетел до цели");
            h.assertTrue(launches.targets().equals(Set.of(a.getUUID(), b.getUUID())) && launches.count() == 2,
                    "ракет " + launches.count() + " по " + launches.targets());
            List<Long> at = launches.ticks();
            h.assertTrue(at.get(0) - placed >= 20, "первая ракета через " + (at.get(0) - placed) + " тиков после установки — без перезарядки");
            h.assertTrue(at.get(1) - at.get(0) >= 20, "вторая ракета через " + (at.get(1) - at.get(0)) + " тиков после первой — без перезарядки");
            h.assertTrue(sam.ready() + sam.stockCount() == 1, "осталось ракет " + (sam.ready() + sam.stockCount()) + " из 1");
            h.assertTrue(launches.viewed, "ракету не было видно в мире");
            h.assertTrue(debris[0], "сбитые шахеды не развалились на обломки");
            h.assertTrue(crew.alerts() >= 1, "тревоги расчёту не было");
            h.assertTrue(stranger.alerts() == 0, "тревога чужому");
            String side = Sides.side(crew);
            h.assertTrue(Sightings.contact(level, side, a.getUUID()) != null && Sightings.contact(level, side, b.getUUID()) != null,
                    "цели радара не на карте стороны");
        });
    }

    // ---------------------------------------------------------------- не трогает своих

    /**
     * Шахед хозяина ЗРК и шахед его товарища по команде ({@code /team}) пролетают над ЗРК — пусков нет, ракеты целы,
     * тревоги нет, оба долетают до цели. Тот же радар чужого видит их чужими и бил бы (проверка, что их вообще видно).
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "sam_friend", skyAccess = true)
    public static void ignoresOwnAndTeam(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 160, 96, 4, 0);
        Scoreboard board = level.getScoreboard();
        PlayerTeam blue = board.addPlayerTeam("sam_blue");
        StrikeGameTests.afterTest(h, () -> board.removePlayerTeam(blue));
        Listener owner = listener(h, "sam_owner", SAM.east(3)), mate = listener(h, "sam_mate", SAM.west(3));
        board.addPlayerToTeam(owner.getScoreboardName(), blue);
        board.addPlayerToTeam(mate.getScoreboardName(), blue);
        h.assertTrue(Sides.friendly(level.getServer(), owner.getUUID(), mate.getUUID()), "команда не своя");
        SamBlockEntity sam = sam(h, SAM, owner.getUUID(), 4);
        DroneEntity own = drone(h, 10, owner.getUUID()), team = drone(h, 22, mate.getUUID());
        Launches launches = new Launches(level, owner.getUUID());
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(SAM)).add(0, 1, 0);
        UUID stranger = UUID.randomUUID();
        Set<UUID> seenFriendly = new HashSet<>(), seenHostileByStranger = new HashSet<>();
        h.onEachTick(() -> {
            launches.watch();
            for (Radar.Track t : Radar.scan(level, antenna, 160, 96, owner.getUUID())) {
                h.assertFalse(t.hostile() || t.engageable(), "свой шахед " + t.id() + " чужой радару хозяина");
                seenFriendly.add(t.id());
            }
            for (Radar.Track t : Radar.scan(level, antenna, 160, 96, stranger)) if (t.engageable()) seenHostileByStranger.add(t.id());
        });
        h.succeedWhen(() -> {
            h.assertTrue(own.isRemoved() && team.isRemoved(), "шахеды ещё летят");
            h.assertTrue(launches.count() == 0, "пущено ракет по своим: " + launches.count());
            h.assertTrue(sam.ready() + sam.stockCount() == 4, "ракет " + (sam.ready() + sam.stockCount()) + " из 4");
            h.assertTrue(seenFriendly.containsAll(Set.of(own.getUUID(), team.getUUID())), "радар хозяина своих не видел");
            h.assertTrue(seenHostileByStranger.containsAll(Set.of(own.getUUID(), team.getUUID())), "чужой радар не бил бы их — проверка пуста");
            h.assertTrue(owner.alerts() == 0 && mate.alerts() == 0, "тревога о своих");
            h.assertFalse(intact(h, new BlockPos(10, 3, 200)) || intact(h, new BlockPos(22, 3, 200)), "свой шахед не долетел");
        });
    }

    // ---------------------------------------------------------------- дальние, МБР и «Град»

    /**
     * Чужой шахед в 200 блоках сбоку (вне мира) идёт мимо: радар (400) его видит — он на карте стороны, — но дальше
     * дальности огня (100) — пуска нет.
     */
    @GameTest(template = "runway", timeoutTicks = 100, batch = "sam_range", skyAccess = true)
    public static void ignoresOutOfRange(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 400, 100, 4, 0);
        UUID owner = UUID.randomUUID();
        SamBlockEntity sam = sam(h, SAM, owner, 4);
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(SAM)).add(0, 1, 0);
        DroneEntity far = virtualDrone(h, antenna.add(200, 60, -150), antenna.add(200, 60, 4000), null);
        Launches launches = new Launches(level, owner);
        boolean[] seen = {false};
        h.onEachTick(() -> {
            launches.watch();
            for (Radar.Track t : Radar.scan(level, antenna, 400, 100, owner)) {
                if (!t.id().equals(far.getUUID())) continue;
                h.assertTrue(t.hostile() && !t.engageable(), "дальний шахед: чужой " + t.hostile() + ", бить " + t.engageable());
                seen[0] = true;
            }
        });
        h.runAtTickTime(80, () -> {
            h.assertTrue(seen[0], "радар не видел шахед в 200 блоках");
            h.assertTrue(Sightings.contact(level, Sides.side(level.getServer(), owner), far.getUUID()) != null, "дальний шахед не на карте стороны");
            h.assertTrue(launches.count() == 0, "пуск по цели дальше дальности огня");
            h.assertTrue(sam.ready() + sam.stockCount() == 4, "ракеты потрачены");
            h.succeed();
        });
    }

    /**
     * МБР взлетает в 30 блоках от ЗРК, «Град» падает рядом: радар видит обоих чужими, но не бьёт — МБР не его цель,
     * «Град» не стоит ракеты (паспорт: {@code Signature.intercept}).
     */
    @GameTest(template = "range", timeoutTicks = 800, batch = "sam_skip", skyAccess = true)
    public static void skipsIcbmAndGrad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 400, 200, 4, 0);
        UUID owner = UUID.randomUUID();
        BlockPos at = new BlockPos(10, 11, 10);
        SamBlockEntity sam = sam(h, at, owner, 4);
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(at)).add(0, 1, 0);
        Vec3 pad = Vec3.atBottomCenterOf(h.absolutePos(RANGE_CENTER));
        IcbmEntity icbm = ModEntities.ICBM.get().create(level);
        // цель — на площадке: район цели МБР берёт с 7500 блоков, дальняя цель генерировала бы мир
        icbm.prepare(pad, pad.add(20, 0, 0), null);
        level.addFreshEntity(icbm);
        Vec3 point = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(52, 11, 52)));
        RocketEntity grad = ModEntities.ROCKET.get().create(level);
        grad.launchFrom(point.add(0, 0, -300), new Target.Point(point), point, null);
        VirtualFlights.launch(level, grad);
        StrikeGameTests.afterTest(h, () -> {
            if (!icbm.isRemoved()) icbm.discard();
            VirtualFlights.get(level).clear(level, p -> p == grad);
        });
        UUID gradId = grad.getUUID();
        Launches launches = new Launches(level, owner);
        Set<UUID> seen = new HashSet<>();
        h.onEachTick(() -> {
            launches.watch();
            for (Radar.Track t : Radar.scan(level, antenna, 400, 200, owner)) {
                if (!t.id().equals(icbm.getUUID()) && !t.id().equals(gradId)) continue;
                h.assertTrue(t.hostile() && !t.engageable(), t.projectile().weapon() + ": чужой " + t.hostile() + ", бить " + t.engageable());
                seen.add(t.id());
            }
            h.assertTrue(launches.count() == 0, "пуск по " + launches.targets());
        });
        h.succeedWhen(() -> {
            h.assertTrue(seen.contains(icbm.getUUID()), "МБР радар не видел");
            h.assertTrue(seen.contains(gradId), "«Град» радар не видел");
            h.assertTrue(level.getEntity(gradId) == null && VirtualFlights.get(level).flights().stream().noneMatch(p -> p.getUUID().equals(gradId)),
                    "«Град» ещё летит");
            h.assertTrue(sam.ready() + sam.stockCount() == 4, "ракеты потрачены");
        });
    }

    // ---------------------------------------------------------------- без ракет

    /** ЗРК без ракет: чужой шахед проходит над ним и бьёт в цель, пусков нет. */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "sam_empty", skyAccess = true)
    public static void emptyDoesNotFire(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 160, 96, 4, 0);
        UUID owner = UUID.randomUUID();
        SamBlockEntity sam = sam(h, SAM, owner, 0);
        DroneEntity d = drone(h, 16, null);
        Launches launches = new Launches(level, owner);
        boolean[] engageable = {false};
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(SAM)).add(0, 1, 0);
        h.onEachTick(() -> {
            launches.watch();
            for (Radar.Track t : Radar.scan(level, antenna, 160, 96, owner)) if (t.id().equals(d.getUUID()) && t.engageable()) engageable[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(d.isRemoved(), "шахед ещё летит");
            h.assertTrue(engageable[0], "шахед не был в дальности огня — проверка пуста");
            h.assertTrue(launches.count() == 0 && sam.ready() == 0, "пуск без ракет");
            h.assertFalse(intact(h, new BlockPos(16, 3, 200)), "шахед не долетел до цели");
        });
    }

    // ---------------------------------------------------------------- чанков не грузит

    /**
     * Чужой шахед вне мира — в 600 блоках к +X (там площадок нет), уходит дальше: ЗРК бьёт его, ракета летит вне
     * загрузки и сбивает его там. Ни один чанк полосы от ЗРК до шахеда за весь полёт не стал загруженным — ни радар,
     * ни полёт ракеты, ни разрыв чанков не грузили и тикетов не ставили. Полоса начинается в 96 блоках от ЗРК: тикеты
     * площадки (уровень 31) дают держателей чанкам до ~13 чанков вокруг, но загружены из них только ближние два.
     */
    @GameTest(template = "runway", timeoutTicks = 400, batch = "sam_far", skyAccess = true)
    public static void loadsNoChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 1500, 1000, 4, 0);
        UUID owner = UUID.randomUUID();
        SamBlockEntity sam = sam(h, SAM, owner, 1);
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(SAM)).add(0, 1, 0);
        DroneEntity far = virtualDrone(h, antenna.add(600, 100, 0), antenna.add(8000, 100, 0), null);
        FlightLog log = StrikeWorld.get(level).flightLog();
        int intercepted = log.total(FlightLog.Event.INTERCEPTED);
        // полоса чанков от 96 блоков за ЗРК до шахеда в конце проверки и за ним, в три ряда
        int cz = Math.floorDiv((int) Math.floor(antenna.z), 16);
        List<ChunkPos> strip = new ArrayList<>();
        for (int cx = Math.floorDiv((int) antenna.x + 96, 16); cx <= Math.floorDiv((int) antenna.x + 600 + 400, 16); cx++) {
            for (int dz = -1; dz <= 1; dz++) strip.add(new ChunkPos(cx, cz + dz));
        }
        List<String> loaded = loaded(level, strip);
        h.assertTrue(loaded.isEmpty(), "чанки полосы загружены до проверки: " + loaded);
        Launches launches = new Launches(level, owner);
        h.onEachTick(() -> {
            launches.watch();
            List<String> now = loaded(level, strip);
            h.assertTrue(now.isEmpty(), "ЗРК загрузил чанки: " + now);
        });
        h.succeedWhen(() -> {
            h.assertTrue(VirtualFlights.get(level).flights().stream().noneMatch(p -> p.getUUID().equals(far.getUUID())), "шахед ещё летит: "
                    + far.position().subtract(antenna));
            h.assertTrue(log.total(FlightLog.Event.INTERCEPTED) - intercepted == 1, "шахед вне мира не сбит");
            h.assertTrue(launches.count() == 1 && sam.ready() + sam.stockCount() == 0, "ракет " + launches.count());
            h.assertFalse(launches.viewed, "вид ракеты вне загрузки");
        });
    }

    // ---------------------------------------------------------------- блок

    /**
     * Блок ЗРК: воронка над ним кладёт в запас зенитные ракеты, способность принимает только их (16 в ячейку, две
     * ячейки), вынуть ею нельзя — и воронка под ним не вытаскивает; компаратор видит запас. Сломанный — выпадает сам
     * и всё, что в нём (с направляющих тоже); взорванный — роняет ракеты. Кирка, каменная и лучше. Рецепт — с точным
     * механизмом Create, если Create стоит, иначе — ванильный.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "sam_block", skyAccess = true)
    public static void blockLoadsBreaksAndCrafts(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 160, 96, 2, 0);
        BlockPos at = new BlockPos(20, 11, 20);
        SamBlockEntity sam = sam(h, at, null, 0);
        h.assertTrue(level.getBlockState(h.absolutePos(at)).is(BlockTags.MINEABLE_WITH_PICKAXE)
                && level.getBlockState(h.absolutePos(at)).is(BlockTags.NEEDS_STONE_TOOL), "ЗРК не для каменной кирки");
        IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(at), Direction.UP);
        h.assertTrue(cap != null, "у ЗРК нет способности предметов");
        h.assertTrue(cap.getSlots() == SamBlockEntity.SLOTS, "ячеек " + cap.getSlots());
        ItemStack dirt = new ItemStack(Items.DIRT, 5);
        h.assertTrue(insert(cap, dirt.copy()).getCount() == 5, "ЗРК взял землю");
        h.assertTrue(insert(cap, new ItemStack(ModItems.INTERCEPTOR.get(), 40)).getCount() == 40 - 2 * 16, "в запас вошло не 32");
        for (int i = 0; i < cap.getSlots(); i++) h.assertTrue(cap.extractItem(i, 64, false).isEmpty(), "ракету вынули способностью");
        int comparator = level.getBlockState(h.absolutePos(at)).getAnalogOutputSignal(level, h.absolutePos(at));
        h.assertTrue(comparator > 0, "компаратор не видит запас");
        // воронка под ЗРК тянет вверх — не вытащит; воронка сверху кладёт
        BlockPos below = at.below(), above = at.above();
        h.setBlock(below, Blocks.HOPPER);
        h.setBlock(above, Blocks.HOPPER);
        int before = sam.stockCount() + sam.ready();
        h.startSequence()
                .thenExecute(() -> {
                    // освободить место: ЗРК ставит ракеты на направляющие (2), запас — 30
                    h.assertTrue(sam.ready() <= 2, "направляющих больше двух");
                    HopperBlockEntity top = h.getBlockEntity(above);
                    top.setItem(0, new ItemStack(ModItems.INTERCEPTOR.get(), 2));
                })
                .thenWaitUntil(() -> {
                    HopperBlockEntity top = h.getBlockEntity(above);
                    h.assertTrue(top.getItem(0).isEmpty(), "воронка сверху не отдала ракеты");
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    HopperBlockEntity bottom = h.getBlockEntity(below);
                    h.assertTrue(bottom.isEmpty(), "воронка под ЗРК вытащила ракеты");
                    h.assertTrue(sam.stockCount() + sam.ready() == before + 2, "ракет " + (sam.stockCount() + sam.ready()) + " вместо " + (before + 2));
                    h.assertTrue(sam.ready() == 2, "на направляющих " + sam.ready());
                    level.setBlock(h.absolutePos(above), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(h.absolutePos(below), Blocks.STONE.defaultBlockState(), 3);
                    AABB box = new AABB(h.absolutePos(at)).inflate(3);
                    level.getEntitiesOfClass(ItemEntity.class, box).forEach(ItemEntity::discard);
                    int loaded = sam.stockCount() + sam.ready();
                    level.destroyBlock(h.absolutePos(at), true);
                    h.assertTrue(level.getBlockState(h.absolutePos(at)).isAir(), "ЗРК не сломался");
                    h.assertTrue(dropped(level, box, ModItems.SAM.get().getDefaultInstance()) == 1, "сломанный ЗРК не выпал");
                    h.assertTrue(dropped(level, box, ModItems.INTERCEPTOR.get().getDefaultInstance()) == loaded, "выпало ракет "
                            + dropped(level, box, ModItems.INTERCEPTOR.get().getDefaultInstance()) + " из " + loaded);
                    level.getEntitiesOfClass(ItemEntity.class, box).forEach(ItemEntity::discard);
                    // взрыв рядом: ЗРК ломается, ракеты выпадают
                    SamBlockEntity again = sam(h, at, null, 5);
                    Vec3 c = Vec3.atCenterOf(h.absolutePos(at));
                    level.explode(null, c.x + 1, c.y, c.z, 4f, Level.ExplosionInteraction.BLOCK);
                    h.assertTrue(level.getBlockState(h.absolutePos(at)).isAir(), "ЗРК пережил взрыв рядом");
                    h.assertTrue(again.isRemoved(), "блок-сущность ЗРК осталась");
                    h.assertTrue(dropped(level, box.inflate(3), ModItems.INTERCEPTOR.get().getDefaultInstance()) == 5, "взорванный ЗРК не уронил ракеты");
                    // рецепт: с Create — точный механизм, без него — ванильный; оба — ЗРК и зенитная ракета
                    var recipes = level.getServer().getRecipeManager();
                    boolean create = ModList.get().isLoaded("create");
                    for (String name : new String[]{"sam", "interceptor"}) {
                        h.assertTrue(recipes.byKey(Airstrike.id(name)).isPresent() == create, "рецепт " + name + " с Create: " + create);
                        h.assertTrue(recipes.byKey(Airstrike.id(name + "_vanilla")).isPresent() == !create, "рецепт " + name + "_vanilla без Create: " + !create);
                    }
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------- помощники

    /** Настройки ПВО на время проверки: поражение наверняка, перезарядка в секундах; возвращаются после неё. */
    private static void air(GameTestHelper h, int radar, int engage, int rails, int reloadSeconds) {
        AirstrikeConfig.Server c = AirstrikeConfig.SERVER;
        int radar0 = c.samRadarRange.get(), engage0 = c.samEngageRange.get(), rails0 = c.samRails.get(), reload0 = c.samReloadSeconds.get();
        double kill0 = c.samKillProbability.get();
        c.samRadarRange.set(radar);
        c.samEngageRange.set(engage);
        c.samRails.set(rails);
        c.samReloadSeconds.set(reloadSeconds);
        c.samKillProbability.set(1.0);
        StrikeGameTests.afterTest(h, () -> {
            c.samRadarRange.set(radar0);
            c.samEngageRange.set(engage0);
            c.samRails.set(rails0);
            c.samReloadSeconds.set(reload0);
            c.samKillProbability.set(kill0);
            DefenseWorld.get(h.getLevel()).clear();
        });
    }

    /** Поставить ЗРК (хозяин — как будто его поставил этот игрок) и положить в запас ракеты. */
    private static SamBlockEntity sam(GameTestHelper h, BlockPos at, @Nullable UUID owner, int missiles) {
        h.setBlock(at, ModBlocks.SAM.get());
        SamBlockEntity be = h.getBlockEntity(at);
        be.setOwner(owner);
        if (missiles > 0) h.assertTrue(be.load(new ItemStack(ModItems.INTERCEPTOR.get(), missiles)).isEmpty(), "ракеты не вошли в запас");
        return be;
    }

    /** Шахед над началом полосы, на линии {@code x}, — на её конец. */
    private static DroneEntity drone(GameTestHelper h, int x, @Nullable UUID owner) {
        ServerLevel level = h.getLevel();
        DroneEntity d = ModEntities.DRONE.get().create(level);
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(x, DRONE_Y, 4)));
        Vec3 aim = Vec3.atCenterOf(h.absolutePos(new BlockPos(x, 3, 200))).add(0, 0.5, 0);
        d.launch(start, new Target.Point(aim), aim, owner);
        level.addFreshEntity(d);
        StrikeGameTests.afterTest(h, () -> {
            if (!d.isRemoved()) d.discard();
        });
        return d;
    }

    /** Шахед, летящий вне мира из {@code from} в {@code to}; убирается после проверки. */
    private static DroneEntity virtualDrone(GameTestHelper h, Vec3 from, Vec3 to, @Nullable UUID owner) {
        ServerLevel level = h.getLevel();
        DroneEntity d = ModEntities.DRONE.get().create(level);
        d.launch(from, new Target.Point(to), to, owner);
        VirtualFlights.launch(level, d);
        StrikeGameTests.afterTest(h, () -> VirtualFlights.get(level).clear(level, p -> p == d));
        return d;
    }

    /** Земля у {@code at} цела: ни одного пустого блока в слое земли 5×5 (воронки нет). */
    private static boolean intact(GameTestHelper h, BlockPos at) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-2, -1, -2), at.offset(2, 0, 2))) if (h.getBlockState(p).isAir()) return false;
        return true;
    }

    private static ItemStack insert(IItemHandler cap, ItemStack stack) {
        for (int i = 0; i < cap.getSlots() && !stack.isEmpty(); i++) stack = cap.insertItem(i, stack, false);
        return stack;
    }

    private static int dropped(ServerLevel level, AABB box, ItemStack like) {
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) if (e.getItem().is(like.getItem())) n += e.getItem().getCount();
        return n;
    }

    /**
     * Чанки из списка, которые загружены или грузятся: готовый чанк или держатель с уровнем тикета загрузки (≤ 33,
     * {@link FullChunkStatus#FULL}); выше — только держатель распространения тикетов соседей, без загрузки.
     */
    private static List<String> loaded(ServerLevel level, List<ChunkPos> chunks) {
        int full = ChunkLevel.byStatus(FullChunkStatus.FULL);
        List<String> out = new ArrayList<>();
        for (ChunkPos c : chunks) {
            ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong());
            boolean ready = level.getChunkSource().getChunkNow(c.x, c.z) != null;
            if (ready || holder != null && holder.getTicketLevel() <= full) out.add(c + (holder != null ? " уровень " + holder.getTicketLevel() : ""));
        }
        return out;
    }

    /** Зенитные ракеты хозяина {@code owner}, которые были в полёте за проверку, с тиком, когда их увидели. */
    private static final class Launches {
        private final ServerLevel level;
        @Nullable
        private final UUID owner;
        private final Map<Interceptor, Long> seen = new IdentityHashMap<>();
        private final List<Interceptor> order = new ArrayList<>();
        /** Хоть раз у ракеты был вид в мире. */
        boolean viewed;

        Launches(ServerLevel level, @Nullable UUID owner) {
            this.level = level;
            this.owner = owner;
        }

        void watch() {
            for (Interceptor f : DefenseWorld.get(level).flights()) {
                if (!Objects.equals(f.owner(), owner)) continue;
                if (seen.putIfAbsent(f, level.getGameTime()) == null) order.add(f);
                if (f.view() != null) viewed = true;
            }
        }

        int count() {
            return order.size();
        }

        Set<UUID> targets() {
            Set<UUID> out = new HashSet<>();
            for (Interceptor f : order) out.add(f.target());
            return out;
        }

        /** Тики пусков по порядку. */
        List<Long> ticks() {
            List<Long> out = new ArrayList<>();
            for (Interceptor f : order) out.add(seen.get(f));
            return out;
        }
    }

    /**
     * Игрок «в сети» для проверки: в списке игроков мира (тревога, экран радара) и в карте игроков сервера по UUID
     * (по ней {@link Sides} узнаёт команду хозяина ЗРК; кэша профилей у сервера GameTest нет). Без тикетов и пакетов;
     * строки чата, которые ему пришли, — в {@link #heard}. Убирается после проверки.
     */
    private static Listener listener(GameTestHelper h, String name, BlockPos at) {
        ServerLevel level = h.getLevel();
        Listener p = new Listener(level, name);
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(at)));
        level.players().add(p);
        Map<UUID, ServerPlayer> byUuid = playersByUuid(level.getServer().getPlayerList());
        byUuid.put(p.getUUID(), p);
        StrikeGameTests.afterTest(h, () -> {
            level.players().remove(p);
            byUuid.remove(p.getUUID(), p);
        });
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerPlayer> playersByUuid(PlayerList list) {
        try {
            Field f = PlayerList.class.getDeclaredField("playersByUUID");
            f.setAccessible(true);
            return (Map<UUID, ServerPlayer>) f.get(list);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Listener extends FakePlayer {
        final List<Component> heard = new ArrayList<>();

        Listener(ServerLevel level, String name) {
            super(level, new GameProfile(UUID.randomUUID(), name));
        }

        @Override
        public void sendSystemMessage(Component component, boolean bypassHiddenChat) {
            heard.add(component);
        }

        /** Строк «Воздушная тревога». */
        int alerts() {
            int n = 0;
            for (Component c : heard) if (c.getContents() instanceof TranslatableContents t && t.getKey().startsWith("airstrike.sam.alert")) n++;
            return n;
        }
    }
}
