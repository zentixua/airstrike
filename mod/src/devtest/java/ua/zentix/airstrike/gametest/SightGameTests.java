package ua.zentix.airstrike.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
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
        target.setHealth(0);
        h.assertFalse(Sight.sees(viewer, target), "видно убитого");

        // дальность: настройка клиента, зажатая дальностью сервера, не меньше двух чанков
        h.assertValueEqual(Sight.viewRange(viewer), 32, "дальность при настройке 2");
        viewer.updateOptions(withView(64));
        int view = Sight.viewRange(viewer);
        int server = Mth.clamp(level.getServer().getPlayerList().getViewDistance(), 2, 32) * 16;
        h.assertValueEqual(view, server, "дальность не зажата сервером");
        Vec3 at = viewer.position();
        h.assertTrue(Sight.within(viewer, at.add(view - 1, 50, 0)), "точка в дальности — вне");
        h.assertFalse(Sight.within(viewer, at.add(view * 0.75, 0, view * 0.75)), "точка за дальностью (по диагонали) — в ней");
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
        h.assertFalse(Sides.friendly(level.getServer(), ua, null), "ничей — свой");
        h.assertFalse(Sides.friendly(level.getServer(), null, null), "два ничьих — свои");
        h.assertFalse(Sides.side(level.getServer(), ua).equals(Sides.side(level.getServer(), c.getUUID())), "одна сторона у разных игроков");
        h.succeed();
    }

    private static FakePlayer player(ServerLevel level, String name, Vec3 at) {
        FakePlayer p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
        p.moveTo(at);
        return p;
    }

    private static ClientInformation withView(int chunks) {
        ClientInformation d = ClientInformation.createDefault();
        return new ClientInformation(d.language(), chunks, d.chatVisibility(), d.chatColors(), d.modelCustomisation(), d.mainHand(),
                d.textFilteringEnabled(), d.allowsListing());
    }
}
