package ua.zentix.incoming.scenario.trailer;

import net.minecraft.core.BlockPos;
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
        String scene = System.getenv("INCOMING_TRAILER_SCENE");
        if (scene != null && !scene.isBlank()) testScene(scene);
        else script();
    }

    private void script() {
        if (Newisle.selected()) {
            setup();
            new Newisle(this).stage();
            return;
        }
        setup();
        new War(this).stage();
        finish();
    }

    /** Общее начало: кадр в размер, правила мира для съёмки, игрок — невидимка. */
    private void setup() {
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
    }

    private void finish() {
        run(() -> {
            rec.finish();
            ua.zentix.incoming.Incoming.LOG.info("TRAILER done");
            // съёмочный мир одноразовый, а его сохранение после дальних перелётов идёт минутами — выходим сразу
            Runtime.getRuntime().halt(0);
        });
    }

    /**
     * Только одна сцена ({@code INCOMING_TRAILER_SCENE}): ровная площадка из камня 160×160 на высоте 150 в новом мире
     * (вокруг — только небо), сцена ставится в её середине курсом на +Z.
     */
    private void testScene(String name) {
        Scene scene = Scene.ALL.get(name);
        if (scene == null) throw new IllegalArgumentException("нет сцены " + name + ", есть " + Scene.ALL.keySet());
        setup();
        onServer(level -> {
            cast().command("forceload add -96 -96 96 96");
            for (int x = -80; x < 80; x += 32) {
                for (int z = -80; z < 80; z += 32) {
                    cast().command(fmt("fill %d 150 %d %d 150 %d minecraft:smooth_stone", x, z, x + 31, z + 31));
                    cast().command(fmt("fill %d 151 %d %d 182 %d minecraft:air", x, z, x + 31, z + 31));
                }
            }
        });
        scene.stage(this, new Vec3(0.5, 151, 0.5), 0);
        finish();
    }

    /** Статисты (боты Almighty) этого сервера. */
    Cast cast() {
        if (cast == null) {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server == null) throw new IllegalStateException("статисты — только в шагах, после входа в мир");
            cast = new Cast(server);
        }
        return cast;
    }

    Fight fight() {
        if (fight == null) fight = new Fight(cast(), mc.getSingleplayerServer());
        return fight;
    }

    /** Уличный бой: два отряда по шесть бойцов за укрытиями друг против друга, облёт и вид из-за плеча. */
    void streetFight(Vec3 c, float yaw) {
        onServer(level -> {
            BlockPos o = BlockPos.containing(c);
            cast().command(fmt("fill %d %d %d %d %d %d minecraft:gray_concrete", o.getX() - 30, o.getY() - 1, o.getZ() - 30,
                    o.getX() + 30, o.getY() - 1, o.getZ() + 30));
            // укрытия: блоки камня по двум линиям в 9 блоках от середины
            for (int k = -12; k <= 12; k += 4) {
                for (int side : new int[]{-9, 9}) {
                    BlockPos a = BlockPos.containing(Scene.local(c, yaw, k, 0, side)), b = BlockPos.containing(Scene.local(c, yaw, k + 1, 0, side));
                    cast().command(fmt("fill %d %d %d %d %d %d minecraft:stone_bricks", a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
                }
            }
            Vec3 fwd = Scene.local(Vec3.ZERO, yaw, 0, 0, 1);
            for (int i = 0; i < 6; i++) {
                spawnSoldier("West" + i, 0, Scene.local(c, yaw, -10 + i * 4, 0, -11), fwd);
                spawnSoldier("East" + i, 1, Scene.local(c, yaw, -10 + i * 4, 0, 11), fwd.scale(-1));
            }
        });
        waitTicks(60);
        onServer(level -> {
            for (Fight.Soldier s : fight().soldiers()) armSoldier(s.name, s.side);
        });
        waitTicks(40);
        run(() -> withServer(server -> fight().start()));
        waitTicks(60);
        shot("test_fight").length(100).hidden()
                .camera(() -> CineCamera.orbit(() -> c, 22, 7, 60, 0.35, 60));
        // из-за плеча бойца West2
        shot("test_fight_shoulder").length(80).hidden()
                .camera(() -> CineCamera.shoulder(() -> bot("West2"), 3.2, 0.3, 1.6, null, 55));
        run(() -> withServer(server -> fight().stop()));
    }

    private void spawnSoldier(String name, int side, Vec3 at, Vec3 facing) {
        cast().spawn(name, at, yawTo(Vec3.ZERO, facing), null);
        fight().add(name, side, at, facing);
    }

    /** Форма стороны (кожа в цвет; 0 — Запад, 1 — Восток), каска, автомат и патроны. */
    void armSoldier(String name, int side) {
        String color = side == 0 ? "5597744" : "6181203";
        String[][] parts = {{"chest", "chestplate"}, {"legs", "leggings"}, {"feet", "boots"}};
        for (String[] part : parts) {
            cast().command(fmt("item replace entity %s armor.%s with minecraft:leather_%s[dyed_color={rgb:%s,show_in_tooltip:false}]",
                    name, part[0], part[1], color));
        }
        cast().command(fmt("item replace entity %s armor.head with minecraft:iron_helmet", name));
        cast().command(fmt("item replace entity %s hotbar.0 with pointblank:ak47", name));
        cast().command(fmt("give %s pointblank:ammo762 512", name));
        // бессмертны: кто падает, решает сценарий, а не случайная очередь до нужного плана
        cast().command(fmt("effect give %s minecraft:resistance infinite 4 true", name));
    }

    void withServer(java.util.function.Consumer<MinecraftServer> work) {
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> work.accept(server));
    }

    private static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }
}
