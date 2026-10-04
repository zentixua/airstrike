package ua.zentix.airstrike.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.ServerActions;
import ua.zentix.airstrike.target.Sides;
import ua.zentix.airstrike.target.Sightings;
import ua.zentix.airstrike.target.Target;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Разведка ({@link Sightings}): кого видит сторона и что из этого следует для пульта ({@link ServerActions#scouted}) и
 * карты. {@code FakePlayer} NeoForge в мир не добавляется: осмотр и карта получают список игроков сами.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SpottingGameTests {
    private static final BlockPos A = new BlockPos(1, 1, 1), B = new BlockPos(6, 1, 6);

    private SpottingGameTests() {}

    /**
     * Глаза: чужой в поле зрения и без преград замечен за сторону смотрящего — его знает вся команда; спиной, за стеной
     * и своих — нет.
     */
    @GameTest(template = "pad", batch = "spotting")
    public static void eyesSpotForTheWholeTeam(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Scoreboard board = level.getScoreboard();
        PlayerTeam blue = board.addPlayerTeam("spot_blue");
        StrikeGameTests.afterTest(h, () -> board.removePlayerTeam(blue));
        FakePlayer a = player(h, "spot_a", A), b = player(h, "spot_b", B), mate = player(h, "spot_mate", new BlockPos(1, 1, 6));
        board.addPlayerToTeam(a.getScoreboardName(), blue);
        board.addPlayerToTeam(mate.getScoreboardName(), blue);
        List<ServerPlayer> all = List.of(a, b, mate);
        mate.lookAt(EntityAnchorArgument.Anchor.EYES, mate.getEyePosition().add(-5, 0, 0));
        b.lookAt(EntityAnchorArgument.Anchor.EYES, b.getEyePosition().add(5, 0, 5));

        a.lookAt(EntityAnchorArgument.Anchor.EYES, a.getEyePosition().subtract(b.getEyePosition().subtract(a.getEyePosition())));
        Sightings.scan(level, all);
        h.assertTrue(Sightings.contact(level, Sides.side(a), b.getUUID()) == null, "заметил спиной");

        a.lookAt(EntityAnchorArgument.Anchor.EYES, b.getEyePosition());
        Sightings.scan(level, all);
        Sightings.Contact c = Sightings.contact(level, Sides.side(mate), b.getUUID());
        h.assertTrue(c != null && c.source() == Sightings.Source.EYES && c.kind() == Sightings.Kind.PLAYER
                && c.name().equals("spot_b") && c.pos().equals(b.position()), "команда не знает замеченного: " + c);
        h.assertTrue(Sightings.contact(level, Sides.side(a), mate.getUUID()) == null, "своего отметил как чужого");
        h.assertTrue(Sightings.contact(level, Sides.side(b), a.getUUID()) == null, "отвернувшийся заметил");

        // за стеной не видно и в упор
        FakePlayer d = player(h, "spot_d", A);
        d.lookAt(EntityAnchorArgument.Anchor.EYES, b.getEyePosition());
        for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(3, y, z), Blocks.STONE);
        Sightings.scan(level, List.of(d, b));
        h.assertTrue(Sightings.contact(level, Sides.side(d), b.getUUID()) == null, "заметил сквозь стену");
        h.succeed();
    }

    /** Камера своего снаряда: пока игрок смотрит его глазами, снаряд замечает того, кого сам игрок из-за стены не видит. */
    @GameTest(template = "pad", batch = "spotting")
    public static void projectileCameraSpots(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer a = player(h, "spot_cam", A), b = player(h, "spot_cam_b", B);
        for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(3, y, z), Blocks.STONE);
        a.lookAt(EntityAnchorArgument.Anchor.EYES, b.getEyePosition());
        Vec3 rail = Vec3.atCenterOf(h.absolutePos(new BlockPos(5, 3, 1)));
        CruiseMissileEntity cam = ModEntities.CRUISE_MISSILE.get().create(level);
        h.assertTrue(cam != null, "нет ракеты");
        cam.placeOnLauncher(rail, 0, 0, 100_000, 0, new Target.Point(rail.add(0, 0, 3000)), rail.add(0, 0, 3000), a.getUUID());
        level.addFreshEntity(cam);
        StrikeGameTests.afterTest(h, cam::discard);

        Sightings.scan(level, List.of(a, b));
        h.assertTrue(Sightings.contact(level, Sides.side(a), b.getUUID()) == null, "увидел сквозь стену");
        // взгляд камеры — свой (клиент шлёт поворот камеры), а не последний поворот игрока на сервере: тот смотрит на b
        Vec3 toB = b.getEyePosition().subtract(cam.position());
        float yaw = (float) (Mth.atan2(toB.z, toB.x) * Mth.RAD_TO_DEG) - 90;
        float pitch = (float) -(Mth.atan2(toB.y, toB.horizontalDistance()) * Mth.RAD_TO_DEG);
        Sightings.watch(a, cam.getUUID(), yaw + 180, 0);
        Sightings.scan(level, List.of(a, b));
        h.assertTrue(Sightings.contact(level, Sides.side(a), b.getUUID()) == null, "камера, повёрнутая прочь, заметила");
        Sightings.watch(a, cam.getUUID(), yaw, pitch);
        Sightings.scan(level, List.of(a, b));
        Sightings.Contact c = Sightings.contact(level, Sides.side(a), b.getUUID());
        h.assertTrue(c != null && c.source() == Sightings.Source.CAMERA, "камера снаряда не заметила: " + c);

        // чужой снаряд — не камера игрока
        Sightings.watch(a, null, 0, 0);
        Sightings.watch(b, cam.getUUID(), yaw, pitch);
        h.assertTrue(Sightings.camera(b) == null, "чужой снаряд принят камерой");
        h.succeed();
    }

    /**
     * Пульт по чужому игроку: незамеченный — отказ; видят сейчас — сам игрок; видели раньше — место, где видели; свои и
     * мобы — без правил.
     */
    @GameTest(template = "pad", batch = "spotting", timeoutTicks = 60)
    public static void strikesOnlyAtSpotted(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer a = player(h, "scout_a", A), b = player(h, "scout_b", B);
        for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(3, y, z), Blocks.STONE);
        a.lookAt(EntityAnchorArgument.Anchor.EYES, b.getEyePosition());
        ServerActions.Aim live = ServerActions.atPlayer(b);
        h.assertTrue(ServerActions.scouted(a, live, b) == null, "удар по незамеченному");

        Vec3 seenAt = b.position();
        Sightings.spot(level, Sides.side(a), b, Sightings.Source.EYES);
        h.assertTrue(ServerActions.scouted(a, live, b) == live, "только что видели — не по нему самому");
        b.moveTo(seenAt.add(0, 0, -3));

        Pig pig = EntityType.PIG.create(level);
        h.assertTrue(pig != null, "нет свиньи");
        pig.moveTo(Vec3.atBottomCenterOf(h.absolutePos(B)));
        ServerActions.Aim atPig = ServerActions.atPlayer(pig);
        h.assertTrue(ServerActions.scouted(a, atPig, pig) == atPig, "моб под правилом разведки");

        h.runAfterDelay(Sightings.CURRENT + 5, () -> {
            ServerActions.Aim old = ServerActions.scouted(a, live, b);
            h.assertTrue(old != null && old.target() instanceof Target.Point p && p.pos().equals(seenAt.add(0, 1, 0)),
                    "не по последнему месту: " + (old == null ? null : old.target()));

            Scoreboard board = level.getScoreboard();
            PlayerTeam red = board.addPlayerTeam("scout_red");
            board.addPlayerToTeam(a.getScoreboardName(), red);
            board.addPlayerToTeam(b.getScoreboardName(), red);
            boolean friendly = ServerActions.scouted(a, live, b) == live;
            board.removePlayerTeam(red);
            h.assertTrue(friendly, "по своему — правило разведки");
            h.succeed();
        });
    }

    /** Забытое: через {@code sight_memory} замеченного нет ни для пульта, ни для карты. */
    @GameTest(template = "pad", batch = "spotting_memory", timeoutTicks = 60)
    public static void spottedIsForgotten(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int memory = AirstrikeConfig.SERVER.sightMemory.get();
        StrikeGameTests.afterTest(h, () -> AirstrikeConfig.SERVER.sightMemory.set(memory));
        AirstrikeConfig.SERVER.sightMemory.set(1);
        FakePlayer a = player(h, "forget_a", A), b = player(h, "forget_b", B);
        Sightings.spot(level, Sides.side(a), b, Sightings.Source.EYES);
        h.assertTrue(Sightings.contact(level, Sides.side(a), b.getUUID()) != null, "не замечен");
        h.runAfterDelay(25, () -> {
            h.assertTrue(Sightings.contact(level, Sides.side(a), b.getUUID()) == null, "не забыт через секунду");
            h.assertTrue(Sightings.contacts(level, Sides.side(a)).isEmpty(), "в списке после забвения");
            h.succeed();
        });
    }

    /**
     * Карта с разведкой: свои по команде — где они сейчас; чужой — только замеченный и там, где его видели; без
     * разведки в настройках — все.
     */
    @GameTest(template = "pad", batch = "spotting_map")
    public static void mapShowsTeamAndSpotted(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean everyone = AirstrikeConfig.SERVER.designatorForEveryone.get(), rules = AirstrikeConfig.SERVER.sightRules.get();
        Scoreboard board = level.getScoreboard();
        PlayerTeam green = board.addPlayerTeam("map_green");
        StrikeGameTests.afterTest(h, () -> {
            AirstrikeConfig.SERVER.designatorForEveryone.set(everyone);
            AirstrikeConfig.SERVER.sightRules.set(rules);
            board.removePlayerTeam(green);
        });
        AirstrikeConfig.SERVER.designatorForEveryone.set(true);
        AirstrikeConfig.SERVER.sightRules.set(true);
        FakePlayer viewer = player(h, "smap_viewer", A), mate = player(h, "smap_mate", new BlockPos(6, 1, 1)),
                enemy = player(h, "smap_enemy", B);
        board.addPlayerToTeam(viewer.getScoreboardName(), green);
        board.addPlayerToTeam(mate.getScoreboardName(), green);
        List<ServerPlayer> all = List.of(viewer, mate, enemy);

        Map<String, S2C.MapPlayer> marks = byName(ServerActions.mapPlayers(fresh(viewer), all));
        h.assertValueEqual(marks.keySet(), java.util.Set.of("smap_mate"), "без разведки на карте");
        h.assertTrue(marks.get("smap_mate").friendly(), "свой не отмечен своим");

        Vec3 seenAt = enemy.position();
        Sightings.spot(level, Sides.side(viewer), enemy, Sightings.Source.EYES);
        enemy.moveTo(seenAt.add(0, 0, -4));
        marks = byName(ServerActions.mapPlayers(fresh(viewer), all));
        S2C.MapPlayer e = marks.get("smap_enemy");
        h.assertTrue(e != null && !e.friendly() && e.kind() == Sightings.Kind.PLAYER && e.x() == seenAt.x && e.z() == seenAt.z && e.age() == 0,
                "замеченный не там: " + e);

        AirstrikeConfig.SERVER.sightRules.set(false);
        marks = byName(ServerActions.mapPlayers(fresh(viewer), all));
        h.assertTrue(marks.containsKey("smap_enemy") && marks.get("smap_enemy").z() == enemy.getZ(), "без правил — не где он сейчас");
        h.succeed();
    }

    private static FakePlayer player(GameTestHelper h, String name, BlockPos at) {
        FakePlayer p = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), name));
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(at)));
        return p;
    }

    /** Снять паузу между запросами карты. */
    private static ServerPlayer fresh(ServerPlayer p) {
        p.removeData(ModAttachments.LAST_MAP_PLAYERS.get());
        return p;
    }

    private static Map<String, S2C.MapPlayer> byName(@Nullable S2C.MapPlayers marks) {
        if (marks == null) throw new AssertionError("ответа нет");
        return marks.players().stream().collect(Collectors.toMap(S2C.MapPlayer::name, m -> m));
    }
}
