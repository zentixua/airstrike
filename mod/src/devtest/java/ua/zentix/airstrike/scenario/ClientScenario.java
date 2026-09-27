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

    public ClientScenario(IEventBus modBus) {
        if (System.getProperty("airstrike.scenario") == null) return;
        NeoForge.EVENT_BUS.addListener(this::onScreen);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        plan();
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
        for (Step s : steps) {
            if (s.at == tick) s.action.run();
        }
        if (tick % 10 == 0) logSound();
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
