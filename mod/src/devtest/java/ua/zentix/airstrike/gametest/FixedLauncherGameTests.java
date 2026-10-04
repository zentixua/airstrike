package ua.zentix.airstrike.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
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
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.launcher.FixedLauncherBlockEntity;
import ua.zentix.airstrike.launcher.LauncherLinks;
import ua.zentix.airstrike.launcher.LauncherOrders;
import ua.zentix.airstrike.launcher.Mission;
import ua.zentix.airstrike.registry.ModBlocks;
import ua.zentix.airstrike.registry.ModItems;
import ua.zentix.airstrike.strike.LaunchOrigin;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.Munitions;
import ua.zentix.airstrike.strike.SalvoData;
import ua.zentix.airstrike.strike.ServerActions;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.strike.Waypoints;
import ua.zentix.airstrike.target.Target;

import java.util.List;
import java.util.UUID;

/**
 * Стационарная пусковая ({@link FixedLauncherBlockEntity}): заряжается воронкой и способностью предметов (вынуть так
 * нельзя), ломается с запасом; ПКМ пультом привязывает, «Огонь» привязанного пульта ставит задачу ({@link LauncherOrders#assign});
 * передний фронт редстоуна — один приказ: снаряд на направляющей её пакета от имени хозяина, боеприпас — из её запаса
 * по снаряду; отказы — задачи нет, запаса мало, занята, сектор закрыт, хозяину нельзя пульт, цель дальше дальности
 * карты. Пуски — на полосе «runway» (земля — y 3) вдоль +Z: там пусто, сектор пуска свободен.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FixedLauncherGameTests {
    /** Пусковая в начале полосы «runway»: курс пуска — +Z вдоль полосы. */
    private static final BlockPos PAD = new BlockPos(16, 4, 8);
    /** Середина площадки «range» (первый воздух — y 11). */
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private FixedLauncherGameTests() {}

    // ---------------------------------------------------------------- пуск по сигналу

    /**
     * Сигнал редстоуна (блок красного камня рядом) — шахед на направляющей пакета пусковой через 4 тика, от имени
     * хозяина, один шахед ушёл из запаса; строка состояния — «приказ отдан». Снять и поставить сигнал снова, пока
     * снаряд на направляющей, — отказ «занята», запас тот же. Пакет смотрит на цель.
     */
    @GameTest(template = "runway", timeoutTicks = 100, batch = "fixed_fire", skyAccess = true)
    public static void redstoneFiresMission(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = UUID.randomUUID();
        FixedLauncherBlockEntity be = launcher(h, PAD, owner);
        Vec3 far = Vec3.atBottomCenterOf(h.absolutePos(PAD)).add(0, -1, 1000);
        be.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(far), far, Waypoints.NONE));
        h.assertTrue(be.hasRack() && be.weapon() == WeaponType.DRONE, "пакета шахедов нет");
        h.assertTrue(Math.abs(be.yaw()) < 1, "пакет не на цели: " + be.yaw());
        load(h, be, new ItemStack(ModItems.SHAHED.get(), 2));
        h.setBlock(PAD.east(), Blocks.REDSTONE_BLOCK);
        h.runAfterDelay(6, () -> {
            List<StrikeProjectile> onRails = ours(level, be, owner);
            h.assertTrue(onRails.size() == 1 && onRails.getFirst().flightPhase().onLauncher(), "на направляющей не один шахед: " + onRails);
            h.assertTrue(be.stockCount() == 1, "в запасе " + be.stockCount() + " вместо 1");
            h.assertValueEqual(key(be.lastReport()), "airstrike.fixed_launcher.fired", "строка состояния");
            h.setBlock(PAD.east(), Blocks.AIR);
        });
        h.runAfterDelay(8, () -> h.setBlock(PAD.east(), Blocks.REDSTONE_BLOCK));
        h.runAfterDelay(14, () -> {
            h.assertValueEqual(key(be.lastReport()), "airstrike.fixed_launcher.busy", "второй сигнал, пока снаряд на направляющей");
            h.assertTrue(be.stockCount() == 1 && ours(level, be, owner).size() == 1, "второй сигнал пустил снаряд");
            h.succeed();
        });
    }

    /**
     * Отказы без пуска и без расхода запаса: задачи нет; запаса меньше, чем на весь приказ (строка — сколько нужно и
     * сколько есть); цель дальше {@code map_range} от пусковой; хозяину нельзя пульт (пульт только операторам), а ничья
     * пусковая (поставлена командой) — без правил хозяина; сектор пуска закрыт стенами со всех сторон — пуск не удался
     * с причиной «сектор закрыт», снятый снаряд вернулся в запас.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "fixed_refuse", skyAccess = true)
    public static void refusesWithoutSpending(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = UUID.randomUUID();
        FixedLauncherBlockEntity be = launcher(h, PAD, owner);
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(PAD));
        Vec3 far = site.add(0, -1, 1000);
        load(h, be, new ItemStack(ModItems.SHAHED.get(), 2));
        h.assertValueEqual(key(LauncherOrders.fire(level, be)), "airstrike.fixed_launcher.no_mission.short", "без задачи");
        be.setMission(new Mission(WeaponType.DRONE, 3, 10, new Target.Point(far), far, Waypoints.NONE));
        h.assertValueEqual(key(LauncherOrders.fire(level, be)), "airstrike.munitions.short", "запаса на 2 из 3");
        int range = AirstrikeConfig.SERVER.mapRange.get();
        Vec3 beyond = site.add(0, -1, range + 100);
        be.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(beyond), beyond, Waypoints.NONE));
        h.assertValueEqual(key(LauncherOrders.fire(level, be)), "airstrike.fixed_launcher.out_of_range", "цель дальше дальности карты");
        boolean everyone = AirstrikeConfig.SERVER.designatorForEveryone.get();
        StrikeGameTests.afterTest(h, () -> AirstrikeConfig.SERVER.designatorForEveryone.set(everyone));
        AirstrikeConfig.SERVER.designatorForEveryone.set(false);
        be.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(far), far, Waypoints.NONE));
        h.assertValueEqual(key(LauncherOrders.fire(level, be)), "airstrike.fixed_launcher.no_rights", "хозяину нельзя пульт");
        // стены вокруг пусковой в 4 блоках, выше набора шахеда до взведения: любой курс упирается в них
        List<BlockPos> walls = new java.util.ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 4) continue;
                for (int y = PAD.getY(); y <= 50; y++) walls.add(new BlockPos(PAD.getX() + dx, y, PAD.getZ() + dz));
            }
        }
        walls.forEach(p -> h.setBlock(p, Blocks.STONE));
        StrikeGameTests.afterTest(h, () -> walls.forEach(p -> h.setBlock(p, Blocks.AIR)));
        be.setOwner(null);
        h.assertValueEqual(key(LauncherOrders.fire(level, be)), "airstrike.fixed_launcher.sector", "ничья пусковая в стенах");
        h.assertTrue(be.stockCount() == 2 && ours(level, be, null).isEmpty(), "отказ потратил запас или пустил снаряд");
        h.succeed();
    }

    /**
     * Залп «Града» с пусковой: запас — один пакет (40 снарядов), задача — 3; залп идёт с её труб, пока он идёт —
     * пусковая занята; в конце в пакете 37. Отбой хозяина посреди залпа шахедов отменяет его: что не встало на
     * направляющую, в запасе и осталось.
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "fixed_salvo", skyAccess = true)
    public static void salvoPaysPerShot(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = UUID.randomUUID();
        FixedLauncherBlockEntity be = launcher(h, PAD, owner);
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(PAD));
        Vec3 target = site.add(0, -1, 400);
        be.setMission(new Mission(WeaponType.ROCKET, 3, 10, new Target.Point(target), target, Waypoints.NONE));
        load(h, be, new ItemStack(ModItems.GRAD_ROCKETS.get()));
        LaunchOrigin.Fixed origin = new LaunchOrigin.Fixed(h.absolutePos(PAD));
        h.assertTrue(LauncherOrders.fire(level, be) == null, "залп не начался");
        h.assertTrue(SalvoData.busy(level, origin), "залп пусковой не в списке");
        h.assertValueEqual(key(LauncherOrders.fire(level, be)), "airstrike.fixed_launcher.busy", "второй приказ посреди залпа");
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(!SalvoData.busy(level, origin), "залп ещё идёт"))
                .thenExecute(() -> {
                    int left = Munitions.held(be.store().items(), ModItems.GRAD_ROCKETS.get(), 40);
                    h.assertTrue(left == 37, "в пакете " + left + " вместо 37");
                    discard(level, be, owner);
                    // залп шахедов и отбой после первого
                    Vec3 far = site.add(0, -1, 1000);
                    be.setMission(new Mission(WeaponType.DRONE, 4, 20, new Target.Point(far), far, Waypoints.NONE));
                    load(h, be, new ItemStack(ModItems.SHAHED.get(), 4));
                })
                .thenWaitUntil(() -> h.assertTrue(be.queue().silent(level.getGameTime()), "пакет ещё не молчит"))
                .thenExecute(() -> h.assertTrue(LauncherOrders.fire(level, be) == null, "залп шахедов не начался"))
                .thenWaitUntil(() -> h.assertTrue(SalvoData.get(level).remaining(owner) == 3, "первый шахед не встал"))
                .thenExecute(() -> {
                    h.assertTrue(be.stockCount() == 3, "после первого шахеда в запасе " + be.stockCount());
                    ServerActions.recall(level.getServer(), owner, "проверка");
                    h.assertTrue(!SalvoData.busy(level, origin) && SalvoData.get(level).remaining(owner) == 0, "отбой не отменил залп");
                    h.assertTrue(be.stockCount() == 3, "отбой тронул запас: " + be.stockCount());
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------- пульт

    /**
     * ПКМ пультом: своя пусковая привязывается, повторный — отвязывает; чужая — нет, а своя команда — да. «Огонь»
     * привязанного пульта ставит задачу: оружие, число, разброс, место; пакет поворачивается к цели. Ядерного — нет;
     * цель дальше дальности карты от пусковой — нет.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "fixed_assign", skyAccess = true)
    public static void designatorAssignsMission(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(RANGE_CENTER));
        FakePlayer owner = player(level, "Хозяин", c.add(3, 0, 0));
        FakePlayer stranger = player(level, "Чужой", c.add(-3, 0, 0));
        FixedLauncherBlockEntity be = launcher(h, RANGE_CENTER, owner.getUUID());
        ItemStack designator = new ItemStack(ModItems.DESIGNATOR.get());
        LauncherLinks.toggle(owner, designator, be);
        h.assertTrue(LauncherLinks.of(designator).size() == 1, "своя не привязалась");
        LauncherLinks.toggle(owner, designator, be);
        h.assertTrue(LauncherLinks.of(designator).isEmpty(), "повторный ПКМ не отвязал");
        ItemStack theirs = new ItemStack(ModItems.DESIGNATOR.get());
        LauncherLinks.toggle(stranger, theirs, be);
        h.assertTrue(LauncherLinks.of(theirs).isEmpty(), "чужая привязалась");
        // команда игрока по UUID — по имени из списка игроков или кэша профилей: FakePlayer в списке нет, в игре оба в кэше
        var profiles = level.getServer().getProfileCache();
        if (profiles != null) {
            profiles.add(owner.getGameProfile());
            profiles.add(stranger.getGameProfile());
        }
        Scoreboard board = level.getScoreboard();
        PlayerTeam team = board.addPlayerTeam("fixed_" + UUID.randomUUID().toString().substring(0, 8));
        StrikeGameTests.afterTest(h, () -> board.removePlayerTeam(team));
        board.addPlayerToTeam(owner.getScoreboardName(), team);
        board.addPlayerToTeam(stranger.getScoreboardName(), team);
        LauncherLinks.toggle(stranger, theirs, be);
        h.assertTrue(LauncherLinks.of(theirs).size() == 1, "пусковая своей команды не привязалась");
        LauncherLinks.toggle(owner, designator, be);

        Vec3 east = c.add(400, 0, 0);
        Loadout drones = new Loadout(WeaponType.DRONE, 2, 5, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT);
        LauncherOrders.assign(owner, LauncherLinks.of(designator), drones, new ServerActions.Aim(new Target.Point(east), east, null), Waypoints.NONE);
        Mission m = be.mission();
        h.assertTrue(m != null && m.weapon() == WeaponType.DRONE && m.count() == 2 && m.spread() == 5 && m.point().equals(east), "задача: " + m);
        h.assertTrue(be.hasRack() && Math.abs(be.yaw() - be.yawTo(east)) < 1, "пакет не к цели: " + be.yaw());
        Loadout nuke = new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT);
        LauncherOrders.assign(owner, LauncherLinks.of(designator), nuke, new ServerActions.Aim(new Target.Point(c), c, null), Waypoints.NONE);
        h.assertTrue(be.mission() == m, "ядерная задача принята");
        Vec3 beyond = c.add(0, 0, AirstrikeConfig.SERVER.mapRange.get() + 100);
        owner.moveTo(beyond.add(0, 0, -50));
        LauncherOrders.assign(owner, LauncherLinks.of(designator), drones, new ServerActions.Aim(new Target.Point(beyond), beyond, null), Waypoints.NONE);
        h.assertTrue(be.mission() == m, "цель дальше дальности карты от пусковой принята");
        h.succeed();
    }

    // ---------------------------------------------------------------- блок

    /**
     * Запас: способность предметов с любой стороны и без стороны берёт боеприпасы, а не землю и не МБР; вынуть ею
     * нельзя; воронка сверху кладёт, снизу — не тянет; компаратор видит запас. Сохранение: хозяин, задача, запас,
     * курс; клиенту — только пакет, курс и подъём. Сломанная — выпадает сама и роняет запас. Кирка, каменная и лучше;
     * рецепт — по тому, стоит ли Create.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "fixed_block", skyAccess = true)
    public static void blockLoadsSavesAndBreaks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = UUID.randomUUID();
        BlockPos at = new BlockPos(20, 11, 20);
        FixedLauncherBlockEntity be = launcher(h, at, owner);
        BlockPos abs = h.absolutePos(at);
        h.assertTrue(level.getBlockState(abs).is(BlockTags.MINEABLE_WITH_PICKAXE) && level.getBlockState(abs).is(BlockTags.NEEDS_STONE_TOOL),
                "пусковая не для каменной кирки");
        for (Direction side : new Direction[]{Direction.UP, Direction.NORTH, null}) {
            IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, abs, side);
            h.assertTrue(cap != null && cap.getSlots() == FixedLauncherBlockEntity.SLOTS, "способность со стороны " + side);
        }
        IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, abs, Direction.UP);
        h.assertTrue(insert(cap, new ItemStack(Items.DIRT, 5)).getCount() == 5, "пусковая взяла землю");
        h.assertTrue(insert(cap, new ItemStack(ModItems.ICBM.get())).getCount() == 1, "пусковая взяла МБР");
        h.assertTrue(insert(cap, new ItemStack(ModItems.CRUISE_MISSILE.get(), 3)).isEmpty(), "ракеты не вошли");
        for (int i = 0; i < cap.getSlots(); i++) h.assertTrue(cap.extractItem(i, 64, false).isEmpty(), "боеприпас вынули способностью");
        h.assertTrue(level.getBlockState(abs).getAnalogOutputSignal(level, abs) > 0, "компаратор не видит запас");
        Vec3 east = Vec3.atBottomCenterOf(abs).add(300, 0, 0);
        be.setMission(new Mission(WeaponType.MISSILE, 2, 0, new Target.Point(east), east, Waypoints.NONE));

        CompoundTag saved = be.saveWithFullMetadata(level.registryAccess());
        BlockEntity copy = BlockEntity.loadStatic(abs, level.getBlockState(abs), saved, level.registryAccess());
        h.assertTrue(copy instanceof FixedLauncherBlockEntity f && owner.equals(f.owner()) && be.mission().equals(f.mission())
                && f.stockCount() == 3 && f.yaw() == be.yaw() && f.weapon() == WeaponType.MISSILE, "сохранение: " + saved);
        CompoundTag update = be.getUpdateTag(level.registryAccess());
        h.assertTrue(!update.contains("owner") && !update.contains("mission") && !update.contains("stock") && update.contains("rack"),
                "клиенту ушло лишнее: " + update);

        BlockPos below = at.below(), above = at.above();
        h.setBlock(below, Blocks.HOPPER);
        h.setBlock(above, Blocks.HOPPER);
        HopperBlockEntity top = h.getBlockEntity(above);
        top.setItem(0, new ItemStack(ModItems.SHAHED.get(), 2));
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(top.getItem(0).isEmpty(), "воронка сверху не отдала шахеды"))
                .thenIdle(20)
                .thenExecute(() -> {
                    HopperBlockEntity bottom = h.getBlockEntity(below);
                    h.assertTrue(bottom.isEmpty(), "воронка под пусковой вытащила боеприпас");
                    h.assertTrue(be.stockCount() == 5, "в запасе " + be.stockCount() + " вместо 5");
                    level.setBlock(h.absolutePos(above), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(h.absolutePos(below), Blocks.STONE.defaultBlockState(), 3);
                    AABB box = new AABB(abs).inflate(3);
                    level.getEntitiesOfClass(ItemEntity.class, box).forEach(ItemEntity::discard);
                    level.destroyBlock(abs, true);
                    h.assertTrue(level.getBlockState(abs).isAir(), "пусковая не сломалась");
                    h.assertTrue(dropped(level, box, ModItems.FIXED_LAUNCHER.get().getDefaultInstance()) == 1, "сломанная пусковая не выпала");
                    h.assertTrue(dropped(level, box, ModItems.SHAHED.get().getDefaultInstance()) == 2
                            && dropped(level, box, ModItems.CRUISE_MISSILE.get().getDefaultInstance()) == 3, "запас не выпал");
                    level.getEntitiesOfClass(ItemEntity.class, box).forEach(ItemEntity::discard);
                    var recipes = level.getServer().getRecipeManager();
                    boolean create = ModList.get().isLoaded("create");
                    h.assertTrue(recipes.byKey(Airstrike.id("fixed_launcher")).isPresent() == create, "рецепт с Create: " + create);
                    h.assertTrue(recipes.byKey(Airstrike.id("fixed_launcher_vanilla")).isPresent() == !create, "рецепт без Create: " + !create);
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------- помощники

    /**
     * Поставить пусковую с хозяином {@code owner}, курс пакета — на +Z. После проверки — убрать без выпадения, и всё,
     * что с неё ушло: снаряды, залпы.
     */
    private static FixedLauncherBlockEntity launcher(GameTestHelper h, BlockPos at, @Nullable UUID owner) {
        ServerLevel level = h.getLevel();
        h.setBlock(at, ModBlocks.FIXED_LAUNCHER.get());
        FixedLauncherBlockEntity be = h.getBlockEntity(at);
        BlockPos abs = h.absolutePos(at);
        be.setOwner(owner);
        be.setYaw(0);
        StrikeGameTests.afterTest(h, () -> {
            SalvoData.get(level).cancel(level, owner, List.of());
            discard(level, be, owner);
            VirtualFlights.get(level).clear(level, p -> owner != null && owner.equals(p.ownerId()));
            level.removeBlockEntity(abs);
            level.setBlock(abs, Blocks.AIR.defaultBlockState(), 3);
        });
        return be;
    }

    private static void load(GameTestHelper h, FixedLauncherBlockEntity be, ItemStack stack) {
        h.assertTrue(be.load(stack).isEmpty(), "боеприпас не вошёл в запас");
    }

    /** Снаряды хозяина {@code owner} у пусковой (на направляющих и только что сошедшие). */
    private static List<StrikeProjectile> ours(ServerLevel level, FixedLauncherBlockEntity be, @Nullable UUID owner) {
        AABB box = new AABB(be.getBlockPos()).inflate(12);
        return StrikeWorld.projectiles(level).stream().filter(p -> box.contains(p.position()) && java.util.Objects.equals(owner, p.ownerId())).toList();
    }

    private static void discard(ServerLevel level, FixedLauncherBlockEntity be, @Nullable UUID owner) {
        for (StrikeProjectile p : StrikeWorld.projectiles(level)) if (java.util.Objects.equals(owner, p.ownerId())) p.discard();
    }

    /** Ключ строки перевода (null — строки нет). */
    @Nullable
    private static String key(@Nullable Component c) {
        return c != null && c.getContents() instanceof TranslatableContents t ? t.getKey() : null;
    }

    private static FakePlayer player(ServerLevel level, String name, Vec3 at) {
        FakePlayer p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
        p.moveTo(at);
        return p;
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
}
