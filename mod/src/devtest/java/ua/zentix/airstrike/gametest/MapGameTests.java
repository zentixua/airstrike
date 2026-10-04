package ua.zentix.airstrike.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.strike.ServerActions;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Карта пульта на сервере: кого она показывает. */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MapGameTests {
    private MapGameTests() {}

    /**
     * Игроки на карте без разведки ({@link ServerActions#mapPlayers}; {@code FakePlayer} NeoForge — он пакетов не шлёт
     * и в список игроков не входит, поэтому список передаётся сам): только в измерении спросившего и не дальше {@code map_range};
     * не он сам, не наблюдатель, не невидимый; без прав на пульт и чаще раза в 4 тика — без ответа; выключено
     * в настройках мира — пустой список.
     */
    @GameTest(template = "pad", batch = "map_players")
    public static void mapPlayersShowsOnlyWhomItShould(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "нет Незера");
        int range = AirstrikeConfig.SERVER.mapRange.get();
        boolean everyone = AirstrikeConfig.SERVER.designatorForEveryone.get(), shown = AirstrikeConfig.SERVER.mapPlayers.get();
        boolean rules = AirstrikeConfig.SERVER.sightRules.get();
        StrikeGameTests.afterTest(h, () -> {
            AirstrikeConfig.SERVER.mapRange.set(range);
            AirstrikeConfig.SERVER.designatorForEveryone.set(everyone);
            AirstrikeConfig.SERVER.mapPlayers.set(shown);
            AirstrikeConfig.SERVER.sightRules.set(rules);
        });
        // без разведки — все игроки там, где они сейчас (с разведкой — SpottingGameTests)
        AirstrikeConfig.SERVER.sightRules.set(false);
        AirstrikeConfig.SERVER.mapRange.set(256);
        AirstrikeConfig.SERVER.designatorForEveryone.set(true);
        AirstrikeConfig.SERVER.mapPlayers.set(true);

        Vec3 at = Vec3.atCenterOf(h.absolutePos(new net.minecraft.core.BlockPos(8, 2, 8)));
        FakePlayer viewer = player(level, "map_viewer", at);
        FakePlayer near = player(level, "map_near", at.add(200, 0, -100));
        FakePlayer far = player(level, "map_far", at.add(250, 0, 100)); // √(250² + 100²) ≈ 269 > 256
        FakePlayer spectator = player(level, "map_spectator", at.add(10, 0, 0));
        spectator.setGameMode(GameType.SPECTATOR);
        FakePlayer invisible = player(level, "map_invisible", at.add(0, 0, 10));
        invisible.setInvisible(true);
        FakePlayer elsewhere = player(nether, "map_nether", at);
        List<ServerPlayer> all = List.of(viewer, near, far, spectator, invisible, elsewhere);

        h.assertValueEqual(names(ServerActions.mapPlayers(viewer, all)), Set.of("map_near"), "на карте");
        S2C.MapPlayer mark = ServerActions.mapPlayers(fresh(viewer), all).getFirst();
        h.assertTrue(mark.id().equals(near.getUUID()) && mark.x() == near.getX() && mark.z() == near.getZ(), "метка не там: " + mark);

        viewer.setData(ModAttachments.LAST_MAP_PLAYERS.get(), level.getGameTime());
        h.assertTrue(ServerActions.mapPlayers(viewer, all) == null, "ответ чаще раза в 4 тика");
        viewer.setData(ModAttachments.LAST_MAP_PLAYERS.get(), level.getGameTime() - 4);
        h.assertTrue(ServerActions.mapPlayers(viewer, all) != null, "через 4 тика ответа нет");

        AirstrikeConfig.SERVER.mapRange.set(300);
        h.assertValueEqual(names(ServerActions.mapPlayers(fresh(viewer), all)), Set.of("map_near", "map_far"), "дальность карты 300");

        AirstrikeConfig.SERVER.mapPlayers.set(false);
        h.assertValueEqual(names(ServerActions.mapPlayers(fresh(viewer), all)), Set.of(), "выключено в настройках мира");
        AirstrikeConfig.SERVER.mapPlayers.set(true);

        // не оператор (FakePlayer без прав), пульт — только операторам
        AirstrikeConfig.SERVER.designatorForEveryone.set(false);
        h.assertTrue(ServerActions.mapPlayers(fresh(viewer), all) == null, "без прав на пульт — ответ");
        h.succeed();
    }

    private static FakePlayer player(ServerLevel level, String name, Vec3 at) {
        FakePlayer p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
        p.moveTo(at);
        return p;
    }

    /** Снять паузу между запросами: следующий спрашивает, как через секунду. */
    private static ServerPlayer fresh(ServerPlayer p) {
        p.removeData(ModAttachments.LAST_MAP_PLAYERS.get());
        return p;
    }

    private static Set<String> names(@Nullable List<S2C.MapPlayer> marks) {
        if (marks == null) throw new AssertionError("ответа нет");
        return marks.stream().map(S2C.MapPlayer::name).collect(Collectors.toSet());
    }
}
