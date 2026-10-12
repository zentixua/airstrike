package ua.zentix.incoming.scenario.trailer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;

import java.util.Locale;

/**
 * Трейлер «Война двух городов»: Запад и Восток на карте Zearth, статисты — боты Almighty с оружием Point Blank, удары —
 * командами мода. Планы — по сценарию трейлера (страница сценария, план за планом); каждый план ставит своё: бойцов,
 * удары, камеру. {@code INCOMING_TRAILER_SHOTS} — снять только эти планы.
 */
public final class Trailer extends Film {
    private Cast cast;
    private Fight fight;

    public Trailer() {
        script();
    }

    private void script() {
        then(this::exactFrame);
        run(() -> {
            cmd("gamerule doDaylightCycle false");
            cmd("gamerule doWeatherCycle false");
            cmd("gamerule doMobSpawning false");
            cmd("gamerule doImmediateRespawn true");
            cmd("gamerule showDeathMessages false");
            cmd("gamerule announceAdvancements false");
            cmd("weather clear");
            cmd("time set 1000");
            withPlayer((level, p) -> p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR));
        });
        streetFightTest();
        run(() -> {
            rec.finish();
            ua.zentix.incoming.Incoming.LOG.info("TRAILER done");
            // съёмочный мир одноразовый, а его сохранение после дальних перелётов идёт минутами — выходим сразу
            Runtime.getRuntime().halt(0);
        });
    }

    /** Проба боя статистов: площадка в воздухе, два отряда в укрытиях, облёт камерой. */
    private void streetFightTest() {
        Vec3 c = new Vec3(0.5, 151, 0.5);
        onServer(level -> {
            MinecraftServer server = level.getServer();
            cast = new Cast(server);
            fight = new Fight(cast, server);
            cast.command(fmt("forceload add %d %d %d %d", -48, -48, 48, 48));
            cast.command(fmt("fill %d %d %d %d %d %d minecraft:gray_concrete", -30, 150, -30, 30, 150, 30));
            cast.command(fmt("fill %d %d %d %d %d %d minecraft:air", -30, 151, -30, 30, 160, 30));
            // укрытия: блоки бетона по линиям z = ±9
            for (int x = -12; x <= 12; x += 4) {
                cast.command(fmt("fill %d 151 %d %d 151 %d minecraft:stone_bricks", x, -9, x + 1, -9));
                cast.command(fmt("fill %d 151 %d %d 151 %d minecraft:stone_bricks", x, 9, x + 1, 9));
            }
            for (int i = 0; i < 6; i++) {
                spawnSoldier("West" + i, 0, new Vec3(-10 + i * 4 + 0.5, 151, -11.5), new Vec3(0, 0, 1));
                spawnSoldier("East" + i, 1, new Vec3(-10 + i * 4 + 0.5, 151, 11.5), new Vec3(0, 0, -1));
            }
        });
        waitTicks(60);
        onServer(level -> {
            for (Fight.Soldier s : fight.soldiers()) arm(s);
        });
        waitTicks(40);
        run(() -> withServer(server -> fight.start()));
        waitTicks(60);
        shot("test_fight").length(100).hidden()
                .camera(() -> CineCamera.orbit(() -> c, 22, 7, 60, 0.35, 60));
        // из-за плеча бойца West2: видно ли вспышку у ствола, анимацию огня и слышно ли выстрелы
        Vec3 w2 = new Vec3(-1.5, 151, -11.5);
        shot("test_fight_shoulder").length(80).hidden()
                .camera(() -> t -> Pose.look(w2.add(1.1, 1.9, -2.4), w2.add(0, 1.4, 20), 0, 60));
        run(() -> withServer(server -> fight.stop()));
    }

    private void spawnSoldier(String name, int side, Vec3 at, Vec3 facing) {
        cast.spawn(name, at, side == 0 ? 0 : 180, null);
        fight.add(name, side, at, facing);
    }

    /** Форма стороны (кожа в цвет), каска, автомат и патроны. */
    private void arm(Fight.Soldier s) {
        String color = s.side == 0 ? "5597744" : "6181203";
        String[][] parts = {{"chest", "chestplate"}, {"legs", "leggings"}, {"feet", "boots"}};
        for (String[] part : parts) {
            cast.command(fmt("item replace entity %s armor.%s with minecraft:leather_%s[dyed_color={rgb:%s,show_in_tooltip:false}]",
                    s.name, part[0], part[1], color));
        }
        cast.command(fmt("item replace entity %s armor.head with minecraft:iron_helmet", s.name));
        cast.command(fmt("item replace entity %s hotbar.0 with pointblank:ak47", s.name));
        cast.command(fmt("give %s pointblank:ammo762 512", s.name));
        // бессмертны: кто падает, решает сценарий, а не случайная очередь до нужного плана
        cast.command(fmt("effect give %s minecraft:resistance infinite 4 true", s.name));
    }

    private void withServer(java.util.function.Consumer<MinecraftServer> work) {
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> work.accept(server));
    }

    private static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }
}
