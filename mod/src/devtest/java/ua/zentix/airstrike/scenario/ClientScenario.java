package ua.zentix.airstrike.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.screen.RemoteScreen;
import ua.zentix.airstrike.client.sound.ClientSounds;
import ua.zentix.airstrike.entity.StrikeProjectile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Сценарий для клиента без окна (./gradlew runClientScenario в виртуальном дисплее): создаёт мир, по очереди
 * пускает шахед, ракету, бомбу и залп, снимает кадры (run/scenario/screenshots) и пишет в лог, что слышно
 * (громкость и тон моторов), затем выходит. Включается только системным свойством airstrike.scenario.
 */
@Mod(value = Airstrike.MOD_ID, dist = Dist.CLIENT)
public final class ClientScenario {
    private static final String WORLD = "airstrike_scenario";

    private record Step(int at, Runnable action) {}

    private final List<Step> steps = new ArrayList<>();
    private boolean started;
    private int tick = -1;
    private Vec3 target = Vec3.ZERO;
    private boolean nuke;
    private int detTick = -1;

    public ClientScenario(IEventBus modBus) {
        if (System.getProperty("airstrike.scenario") == null) return;
        NeoForge.EVENT_BUS.addListener(this::onScreen);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        if ("nuke".equals(System.getProperty("airstrike.scenario"))) planNuke();
        else plan();
    }

    private void onScreen(ScreenEvent.Init.Post e) {
        if (started || !(e.getScreen() instanceof TitleScreen)) return;
        started = true;
        Minecraft mc = Minecraft.getInstance();
        deleteOldWorld(mc.gameDirectory.toPath().resolve("saves").resolve(WORLD));
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20260927L, false, false),
                WorldPresets::createNormalWorldDimensions, e.getScreen());
    }

    private static void deleteOldWorld(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ex) {
            Airstrike.LOG.warn("SCENARIO не удалось удалить старый мир сценария", ex);
        }
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        tick++;
        for (Step s : List.copyOf(steps)) {
            if (s.at == tick) s.action.run();
        }
        if (nuke) nukeEvents();
        if (tick % 10 == 0) logSound();
        if (tick % 100 == 0) Airstrike.LOG.info("SCENARIO fps {}", mc.getFps());
    }

    private void plan() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 170 0 0 40");
        });
        // цель — земля в 90 блоках по +Z; зритель — на столбике в 45 блоках от неё и в 25 над ней
        at(240, () -> {
            aimAhead(90);
            cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glass", (int) Math.floor(target.x), (int) target.y + 24,
                    (int) Math.floor(target.z) - 45, (int) Math.floor(target.x), (int) target.y + 24, (int) Math.floor(target.z) - 45));
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", target.x, target.y + 25, target.z - 44.5,
                    target.x, target.y, target.z));
        });
        strike(300, "drone", 240);
        strike(560, "missile", 260);
        strike(840, "bunker", 300);
        at(1160, () -> {
            cmd(String.format(java.util.Locale.ROOT, "airstrike salvo drone 3 12 at %.1f %.1f %.1f", target.x, target.y, target.z));
        });
        for (int t = 1200; t <= 1460; t += 20) shot(t, "salvo");
        at(1500, () -> {
            cmd("give @s airstrike:strike_designator");
            Minecraft.getInstance().options.keyUse.setDown(true);
        });
        shot(1530, "scope");
        at(1540, () -> {
            Minecraft.getInstance().options.keyUse.setDown(false);
            Minecraft.getInstance().setScreen(new RemoteScreen());
        });
        shot(1550, "remote");
        at(1560, () -> Minecraft.getInstance().setScreen(null));
        at(1580, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Ядерный удар (DESIGN-nuke §12): МБР стартует у игрока и летит 90 с к цели в 2 км (наземный 15 кт),
     * кадры старта, входа боеголовки, вспышки, шара, фронта и гриба; потом — в следе осадков, под чёрным дождём,
     * со счётчиком Гейгера в руке.
     */
    private void planNuke() {
        nuke = true;
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 170 0 0 0");
        });
        at(200, () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            int x = (int) Math.floor(p.getX()), z = (int) Math.floor(p.getZ());
            int ground = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            // смотровая площадка над окрестными холмами; цель — в 2 км на север (−Z)
            int y = Math.max(ground, 170);
            cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", x - 1, y + 29, z - 1, x + 1, y + 29, z + 1));
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 %d %d.5 180 -8", x, y + 30, z));
            cmd("give @s airstrike:geiger_counter");
            target = new Vec3(x + 0.5, 320, z - 2000 + 0.5); // высота 320 — сервер опустит на землю
        });
        at(260, () -> cmd(String.format(java.util.Locale.ROOT, "airstrike nuke at %.1f %.1f %.1f 15 ground", target.x, target.y, target.z)));
        // ракета стартует за спиной: оглядываемся на старт, потом снова на цель
        at(262, () -> cmd("tp @s ~ ~ ~ 0 -25"));
        for (int t : new int[]{270, 300, 340, 400, 500}) shot(t, "launch");
        at(520, () -> cmd("tp @s ~ ~ ~ 180 -8"));
        // дальше — от настоящих событий: сервер может отставать от счётчика кадров клиента
    }

    /** Ядерный сценарий: кадры входа боеголовки и после подрыва — от момента, когда клиент получил подрыв. */
    private void nukeEvents() {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level.getGameTime();
        for (var w : ua.zentix.airstrike.client.nuclear.ClientNuclear.warnings()) {
            long left = w.detonateTime() - now;
            // боеголовка приходит со стороны пуска (из-за спины) почти отвесно: на 2 с смотрим назад и вверх
            if (left == 45) cmd("tp @s ~ ~ ~ 0 -65");
            if (left == 38 || left == 30) shot(tick + 1, "reentry");
            if (left == 22) cmd("tp @s ~ ~ ~ 180 -8");
        }
        if (detTick >= 0 || ua.zentix.airstrike.client.nuclear.ClientNuclear.detonations().isEmpty()) return;
        detTick = tick;
        Airstrike.LOG.info("SCENARIO detonation at tick {}", tick);
        for (int dt : new int[]{1, 2, 4, 10, 20, 40, 70, 90, 100, 112, 118, 125, 140, 160, 200, 400}) shot(tick + dt, "nuke");
        // гриб целиком виден издалека: 9 км к югу от эпицентра, взгляд на 30° вверх
        at(tick + 560, () -> {
            var d = ua.zentix.airstrike.client.nuclear.ClientNuclear.detonations().getLast().d;
            int x = (int) Math.floor(d.burst().x), z = (int) Math.floor(d.burst().z + 9_000);
            cmd(String.format(java.util.Locale.ROOT, "fill %d 229 %d %d 229 %d minecraft:stone_bricks", x - 1, z - 1, x + 1, z + 1));
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 230 %d.5 180 -30", x, z));
        });
        for (int dt : new int[]{600, 1200, 2400, 3600}) shot(tick + dt, "cloud");
        // в след осадков, пока там идёт чёрный дождь (2.5 игровых часа = 2 мин после прихода осадков), счётчик в руке
        at(tick + 3900, () -> {
            var d = ua.zentix.airstrike.client.nuclear.ClientNuclear.detonations().getLast().d;
            // по ветру туда, куда осадки придут к ~2.5 мин после подрыва (скорость ветра у подрыва своя)
            double m = Math.min(3000, d.windSpeed() * 150);
            double x = d.burst().x + Math.cos(d.windDir()) * m * d.scale(), z = d.burst().z + Math.sin(d.windDir()) * m * d.scale();
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f 200 %.1f 0 0", x, z));
        });
        at(tick + 3960, () -> {
            LocalPlayer p = Minecraft.getInstance().player;
            int x = (int) Math.floor(p.getX()), z = (int) Math.floor(p.getZ());
            int y = Minecraft.getInstance().level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            // площадка: вдруг там вода
            cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", x - 1, y, z - 1, x + 1, y, z + 1));
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 %d %d.5 0 0", x, y + 1, z));
        });
        for (int dt : new int[]{4040, 4080, 4120}) shot(tick + dt, "fallout");
        at(tick + 4140, () -> Airstrike.LOG.info("SCENARIO radiation {}", ua.zentix.airstrike.client.nuclear.ClientNuclear.radiationState()));
        at(tick + 4160, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /** Пуск по точке на земле впереди и кадры каждые 10 тиков, пока летит и горит. */
    private void strike(int start, String weapon, int frames) {
        at(start, () -> {
            cmd(String.format(java.util.Locale.ROOT, "airstrike %s at %.1f %.1f %.1f", weapon, target.x, target.y, target.z));
        });
        for (int t = start + 10; t <= start + frames; t += 10) shot(t, weapon);
    }

    private void aimAhead(double distance) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        double x = Math.floor(p.getX()) + 0.5, z = Math.floor(p.getZ() + distance) + 0.5;
        int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        if (y <= mc.level.getMinBuildHeight()) {
            Airstrike.LOG.warn("SCENARIO чанк цели ещё не загружен у клиента");
            y = 64;
        }
        target = new Vec3(x, y, z);
    }

    private void at(int t, Runnable r) {
        steps.add(new Step(t, r));
    }

    private void shot(int t, String name) {
        at(t, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (name.equals("nuke") || name.equals("fallout")) {
                var list = ua.zentix.airstrike.client.nuclear.ClientNuclear.detonations();
                Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
                String rain = list.isEmpty() ? "-" : String.format(java.util.Locale.ROOT, "trail=%b sky=%b",
                        list.getLast().d.blackRain(cam.x, cam.z, mc.level.getGameTime() - list.getLast().d.gameTime()),
                        ua.zentix.airstrike.nuclear.Detonation.underOpenSky(mc.level, cam));
                Airstrike.LOG.info("SCENARIO {} white={} rain={} {} fps={}", name, ua.zentix.airstrike.client.nuclear.NukeFlash.whiteness(0),
                        ua.zentix.airstrike.client.nuclear.NukeSky.blackRain(), rain, mc.getFps());
            }
            Screenshot.grab(mc.gameDirectory, String.format("%s_%04d.png", name, tick), mc.getMainRenderTarget(), c -> {});
        });
    }

    private static void cmd(String c) {
        Minecraft.getInstance().player.connection.sendCommand(c);
        Airstrike.LOG.info("SCENARIO /{}", c);
    }

    /** Что сейчас летит и насколько громко / каким тоном звучит (для проверки Доплера и задержки по логу). */
    private void logSound() {
        Minecraft mc = Minecraft.getInstance();
        StringBuilder sb = new StringBuilder();
        for (var e : mc.level.entitiesForRendering()) {
            if (e instanceof StrikeProjectile p) {
                sb.append(String.format(" %s d=%.0f v=%.1f ph=%d;", p.getType().toShortString(), p.distanceTo(mc.player), p.speed(), p.phase()));
            }
        }
        Airstrike.LOG.info("SCENARIO t={} [{}] flying:{} engines:{}", tick, mc.getSoundManager().getDebugString(), sb, ClientSounds.describe());
    }
}
