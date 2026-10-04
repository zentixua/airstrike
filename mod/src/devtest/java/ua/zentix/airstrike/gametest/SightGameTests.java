package ua.zentix.airstrike.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.target.Sides;
import ua.zentix.airstrike.target.Sight;

import java.util.Map;
import java.util.UUID;

/** Прямая видимость ({@link Sight}) и свои по командам ({@link Sides}). */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SightGameTests {
    private SightGameTests() {}

    /**
     * Видно на открытом месте и сквозь стекло; не видно сквозь камень, невидимого (зелье), наблюдателя и в другом
     * мире; дальность — прорисовка игрока, зажатая сервером.
     */
    @GameTest(template = "pad", batch = "sight")
    public static void seesOnlyWhatEyesSee(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer viewer = player(level, "sight_viewer", Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
        FakePlayer target = player(level, "sight_target", Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(6, 1, 6))));
        h.assertTrue(Sight.sees(viewer, target), "на открытом месте не видно");
        h.assertFalse(Sight.sees(viewer, viewer), "видит сам себя");

        for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(4, y, z), Blocks.STONE);
        h.assertFalse(Sight.sees(viewer, target), "видно сквозь камень");
        for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(4, y, z), Blocks.GLASS);
        h.assertTrue(Sight.sees(viewer, target), "не видно сквозь стекло");

        target.setInvisible(true);
        h.assertFalse(Sight.sees(viewer, target), "видно невидимого");
        target.setInvisible(false);
        target.setGameMode(GameType.SPECTATOR);
        h.assertFalse(Sight.sees(viewer, target), "видно наблюдателя");
        target.setGameMode(GameType.SURVIVAL);
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "нет Незера");
        h.assertFalse(Sight.sees(viewer, player(nether, "sight_nether", target.position())), "видно в другом мире");
        target.setHealth(0);
        h.assertFalse(Sight.sees(viewer, target), "видно убитого");

        Vec3 at = viewer.position();
        h.assertTrue(Sight.within(viewer, at.add(Sight.viewRange(viewer) - 1, 50, 0)), "точка в дальности — вне");
        h.assertFalse(Sight.within(viewer, at.add(Sight.viewRange(viewer) * 0.75, 0, Sight.viewRange(viewer) * 0.75)),
                "точка за дальностью (по диагонали) — в ней");
        h.succeed();
    }

    /**
     * Дальность — как у Minecraft: настройка клиента (у {@code FakePlayer} её нет — настоящий {@code ServerPlayer} вне
     * списка игроков), зажатая дальностью сервера, не меньше двух чанков; у сущности — ещё и дальностью её отслеживания.
     */
    @GameTest(template = "pad", batch = "sight_range")
    public static void rangeIsClampedLikeVanilla(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        PlayerList list = level.getServer().getPlayerList();
        int before = list.getViewDistance();
        StrikeGameTests.afterTest(h, () -> list.setViewDistance(before));
        list.setViewDistance(16);
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1)));
        h.assertValueEqual(Sight.viewRange(viewer(level, at, 64)), 256, "настройка 64 при сервере 16");
        h.assertValueEqual(Sight.viewRange(viewer(level, at, 5)), 80, "настройка 5 при сервере 16");
        h.assertValueEqual(Sight.viewRange(viewer(level, at, 1)), 32, "настройка 1 — не меньше двух чанков");

        ServerPlayer far = viewer(level, at, 64);
        Pig pig = EntityType.PIG.create(level);
        h.assertTrue(pig != null, "нет свиньи");
        // свинья отслеживается на 10 чанков (160 блоков) с поправкой сервера — ближе прорисовки (256); игрок — на 32
        // чанка, т. е. до прорисовки
        int pigRange = level.getServer().getScaledTrackingDistance(EntityType.PIG.clientTrackingRange() * 16);
        h.assertTrue(pigRange < 256, "дальность свиньи должна быть меньше прорисовки: " + pigRange);
        h.assertValueEqual(Sight.range(far, pig), (double) pigRange, "дальность свиньи");
        h.assertValueEqual(Sight.range(far, far), 256.0, "дальность игрока — не дальше прорисовки");
        h.succeed();
    }

    /** Взгляд в неготовый чанк — «не видно», и чанк ради него не грузится. */
    @GameTest(template = "pad", batch = "sight")
    public static void clearNeverLoadsChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 from = Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 3, 1)));
        Vec3 far = from.add(200_000, 0, 200_000);
        ChunkPos chunk = new ChunkPos(BlockPos.containing(far));
        h.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null, "дальний чанк уже загружен");
        h.assertFalse(Sight.clear(level, from, far), "видно сквозь неготовые чанки");
        h.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null, "взгляд загрузил чанк");
        h.assertTrue(Sight.clear(level, from, Vec3.atCenterOf(h.absolutePos(new BlockPos(6, 3, 6)))), "на площадке не видно");
        h.succeed();
    }

    /** Свои — тот же игрок или одна команда {@code /team}; без команды и ничьи (консоль) — чужие. */
    @GameTest(template = "pad", batch = "sight")
    public static void sidesFollowTeams(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1)));
        FakePlayer a = player(level, "sides_a", at), b = player(level, "sides_b", at), c = player(level, "sides_c", at);
        Scoreboard board = level.getScoreboard();
        PlayerTeam red = board.addPlayerTeam("sight_red"), blue = board.addPlayerTeam("sight_blue");
        StrikeGameTests.afterTest(h, () -> {
            board.removePlayerTeam(red);
            board.removePlayerTeam(blue);
        });

        h.assertFalse(Sides.friendly(a, b), "без команд — свои");
        h.assertTrue(Sides.friendly(a, a), "сам себе чужой");
        board.addPlayerToTeam(a.getScoreboardName(), red);
        board.addPlayerToTeam(b.getScoreboardName(), red);
        board.addPlayerToTeam(c.getScoreboardName(), blue);
        h.assertTrue(Sides.friendly(a, b), "одна команда — чужие");
        h.assertFalse(Sides.friendly(a, c), "разные команды — свои");

        UUID ua = a.getUUID();
        h.assertTrue(Sides.friendly(level.getServer(), ua, ua), "тот же игрок по UUID — чужой");
        // по UUID (владелец не в сети): имена — из кэша профилей, у сервера GameTest его нет — свои имена
        Map<UUID, String> names = Map.of(ua, a.getScoreboardName(), b.getUUID(), b.getScoreboardName(), c.getUUID(), c.getScoreboardName());
        h.assertTrue(Sides.friendly(board, ua, b.getUUID(), names::get), "одна команда по UUID — чужие");
        h.assertFalse(Sides.friendly(board, ua, c.getUUID(), names::get), "разные команды по UUID — свои");
        h.assertFalse(Sides.friendly(board, ua, UUID.randomUUID(), names::get), "игрок без имени — свой");
        h.assertValueEqual(Sides.side(board, ua, names::get), Sides.side(a), "сторона по UUID и у игрока в сети разные");
        h.assertFalse(Sides.friendly(level.getServer(), ua, null), "ничей — свой");
        h.assertFalse(Sides.friendly(level.getServer(), null, null), "два ничьих — свои");
        h.assertFalse(Sides.side(level.getServer(), ua).equals(Sides.side(level.getServer(), c.getUUID())), "одна сторона у разных игроков");
        h.assertValueEqual(Sides.side(a), Sides.side(b), "у команды разные стороны");
        h.assertFalse(Sides.side(a).equals(Sides.side(c)), "у разных команд одна сторона");
        h.succeed();
    }

    private static FakePlayer player(ServerLevel level, String name, Vec3 at) {
        FakePlayer p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
        p.moveTo(at);
        return p;
    }

    /** Настоящий игрок вне списка игроков с настройкой прорисовки {@code chunks}: {@code FakePlayer} её не принимает. */
    private static ServerPlayer viewer(ServerLevel level, Vec3 at, int chunks) {
        ServerPlayer p = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "sight_range"), withView(chunks));
        p.moveTo(at);
        return p;
    }

    private static ClientInformation withView(int chunks) {
        ClientInformation d = ClientInformation.createDefault();
        return new ClientInformation(d.language(), chunks, d.chatVisibility(), d.chatColors(), d.modelCustomisation(), d.mainHand(),
                d.textFilteringEnabled(), d.allowsListing());
    }
}
