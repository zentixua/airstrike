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
    /** Сценарий эффектов: очередь ударов, текущий снаряд, тик его взрыва. */
    private java.util.ArrayDeque<String> fx;
    private Vec3 eye = Vec3.ZERO;
    private StrikeProjectile watched;
    private String current;
    private int flightShots;
    /** Сценарий моделей: частицы не нужны. */
    private boolean models;

    public ClientScenario(IEventBus modBus) {
        if (System.getProperty("airstrike.scenario") == null) return;
        NeoForge.EVENT_BUS.addListener(this::onScreen);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        String mode = System.getProperty("airstrike.scenario");
        if ("nuke".equals(mode)) planNuke();
        else if ("fx".equals(mode) || "fx-night".equals(mode)) planFx("fx-night".equals(mode));
        else if ("launch".equals(mode)) planLaunch();
        else if ("models".equals(mode)) planModels();
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
        if (fx != null) fxEvents();
        // модели крупным планом: дым выхлопа и шлейфы закрыли бы их
        if (models) mc.particleEngine.setLevel(mc.level);
        if (tick % 10 == 0) logSound();
        if (tick % 20 == 0 && !ua.zentix.airstrike.client.hud.ClientFlights.all().isEmpty()) logFlights();
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
            // здесь проверяются удары, а не пуск: заход издалека и самый короткий полёт (пуск — сценарий launch)
            quickFlights();
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

    /**
     * Эффекты крупным планом: зритель висит в воздухе в 70 блоках от цели и в 25 над ней (полёт в творческом режиме,
     * взрыв его не сдувает), по очереди шахед, ракета, бомба; кадры — от настоящего взрыва (снаряд пропал), а не
     * по счётчику. В конце — старт МБР в 80 блоках: факел, шлейф, облако у стола. {@code fx-night} — то же ночью.
     */
    private void planFx(boolean night) {
        fx = new java.util.ArrayDeque<>(List.of("drone", "missile", "bunker", "icbm"));
        at(40, () -> {
            cmd(night ? "time set 18000" : "time set 6000");
            cmd("weather clear");
            Minecraft.getInstance().options.hideGui = true;
            // ровное место: ближайшая равнина (на холмах цель и стол МБР прячутся за склонами)
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> {
                var found = server.overworld().findClosestBiome3d(b -> b.is(net.minecraft.world.level.biome.Biomes.PLAINS),
                        net.minecraft.core.BlockPos.ZERO.atY(64), 6400, 32, 64);
                var at = found == null ? net.minecraft.core.BlockPos.ZERO : found.getFirst();
                Minecraft.getInstance().execute(() -> cmd(String.format(java.util.Locale.ROOT, "tp @s %d 170 %d 0 40", at.getX(), at.getZ())));
            });
        });
        at(240, () -> {
            quickFlights();
            aimAhead(90);
            eye = new Vec3(target.x + 0.5, target.y + 18, target.z - 50);
            Minecraft.getInstance().player.getAbilities().flying = true;
            view();
        });
        at(300, this::nextFx);
    }

    private void view() {
        cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", eye.x, eye.y, eye.z, target.x, target.y + 8, target.z));
    }

    private void nextFx() {
        current = fx.poll();
        watched = null;
        flightShots = 0;
        if (current == null) {
            at(tick + 20, () -> {
                Airstrike.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.player.getAbilities().flying = true;
        view();
        if (current.equals("icbm")) {
            // МБР со стола в 80 блоках сбоку от зрителя, цель далеко: улетает за потолок мира без подрыва
            Vec3 pad = new Vec3(eye.x + 60, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) eye.x + 60, (int) eye.z), eye.z);
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                var icbm = ua.zentix.airstrike.registry.ModEntities.ICBM.get().create(level);
                icbm.prepare(pad, pad.add(0, 0, 40_000), null);
                level.addFreshEntity(icbm);
            });
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", eye.x, eye.y, eye.z, pad.x, pad.y + 20, pad.z));
            for (int dt : new int[]{8, 20, 40, 60, 90}) shot(tick + dt, "icbm");
            at(tick + 100, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", eye.x, eye.y, eye.z, pad.x, pad.y + 120, pad.z)));
            for (int dt : new int[]{110, 140, 200, 300, 500}) shot(tick + dt, "icbm");
            at(tick + 520, this::nextFx);
            return;
        }
        cmd(String.format(java.util.Locale.ROOT, "airstrike %s at %.1f %.1f %.1f", current, target.x, target.y, target.z));
    }

    /** Ждём снаряд, снимаем подлёт, затем взрыв — от тика, когда снаряд пропал. */
    private void fxEvents() {
        if (current == null || current.equals("icbm") || current.equals("wait")) return;
        Minecraft mc = Minecraft.getInstance();
        if (watched == null) {
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof StrikeProjectile p && p.isActive()) watched = p;
            }
            return;
        }
        if (!watched.isRemoved()) {
            if (tick % 8 == 0 && flightShots < 6 && watched.distanceTo(mc.player) < 150) {
                flightShots++;
                shot(tick + 1, current + "_flight");
            }
            return;
        }
        String name = current;
        current = "wait";
        Airstrike.LOG.info("SCENARIO {} impact at tick {}", name, tick);
        for (int dt : new int[]{1, 2, 4, 7, 12, 20, 35, 60, 100, 160, 240, 320}) shot(tick + dt, name);
        at(tick + 340, this::nextFx);
    }

    /**
     * Модели крупным планом: каждый снаряд — неподвижная клиентская копия в небе в нужной фазе (на пусковой с
     * ускорителем, в полёте с раскрытыми крыльями, B-2 с открытым и закрытым бомболюком); камера облетает его и
     * снимает с трёх сторон. Сервер о копиях не знает — они не летят и не взрываются.
     */
    private void planModels() {
        record Pose(String name, java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends StrikeProjectile>> type,
                    ua.zentix.airstrike.entity.FlightPhase phase, double size, boolean aimHere) {}
        List<Pose> poses = List.of(
                new Pose("drone_ready", ua.zentix.airstrike.registry.ModEntities.DRONE, ua.zentix.airstrike.entity.FlightPhase.READY, 9, false),
                new Pose("drone_cruise", ua.zentix.airstrike.registry.ModEntities.DRONE, ua.zentix.airstrike.entity.FlightPhase.CRUISE, 9, false),
                new Pose("missile_boost", ua.zentix.airstrike.registry.ModEntities.CRUISE_MISSILE, ua.zentix.airstrike.entity.FlightPhase.BOOST, 12, false),
                new Pose("missile_cruise", ua.zentix.airstrike.registry.ModEntities.CRUISE_MISSILE, ua.zentix.airstrike.entity.FlightPhase.CRUISE, 12, false),
                new Pose("bomb", ua.zentix.airstrike.registry.ModEntities.BUNKER_BUSTER, ua.zentix.airstrike.entity.FlightPhase.TERMINAL, 10, false),
                new Pose("icbm", ua.zentix.airstrike.registry.ModEntities.ICBM, ua.zentix.airstrike.entity.FlightPhase.CRUISE, 20, false),
                new Pose("b2_closed", ua.zentix.airstrike.registry.ModEntities.BOMBER, ua.zentix.airstrike.entity.FlightPhase.CRUISE, 42, false),
                new Pose("b2_open", ua.zentix.airstrike.registry.ModEntities.BOMBER, ua.zentix.airstrike.entity.FlightPhase.CRUISE, 42, true));
        at(40, () -> {
            cmd("time set 5000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
            Minecraft.getInstance().options.hideGui = true;
            Minecraft.getInstance().options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            Minecraft.getInstance().player.getAbilities().flying = true;
            models = true;
        });
        int t = 300;
        for (Pose pz : poses) {
            int start = t;
            at(start, () -> {
                Minecraft mc = Minecraft.getInstance();
                mc.player.getAbilities().flying = true;
                if (watched != null) watched.discard();
                Vec3 m = new Vec3(0, 200, 0);
                StrikeProjectile e = pz.type().get().create(mc.level);
                e.moveTo(m.x, m.y, m.z, 0, 0);
                e.yRotO = 0;
                e.xRotO = 0;
                showPhase(e, pz.phase(), pz.aimHere() ? m : m.add(0, 0, 5000));
                mc.level.addEntity(e);
                watched = e;
            });
            // слева спереди сверху, сбоку, справа сзади снизу
            double d = pz.size();
            double[][] cams = {{-0.8, 0.35, 0.8}, {-1.0, 0.05, 0.0}, {0.7, -0.35, -0.8}};
            for (int k = 0; k < cams.length; k++) {
                double[] c = cams[k];
                at(start + 5 + k * 25, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f facing %.2f %.2f %.2f",
                        c[0] * d, 200 + c[1] * d - 1.62, c[2] * d, 0.0, 200.0, 0.0)));
                // второй раз — первая телепортация после падения игрока не всегда встаёт точно
                at(start + 15 + k * 25, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f facing %.2f %.2f %.2f",
                        c[0] * d, 200 + c[1] * d - 1.62, c[2] * d, 0.0, 200.0, 0.0)));
                at(start + 24 + k * 25, () -> Airstrike.LOG.info("SCENARIO model {} at {} phase {}", pz.name(), watched.position(), watched.flightPhase()));
                shot(start + 25 + k * 25, pz.name() + "_" + k);
            }
            t += 95;
        }
        at(t + 20, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /** Фаза и точка прицеливания у клиентской копии снаряда (синхронные поля — только через отражение). */
    private static void showPhase(StrikeProjectile e, ua.zentix.airstrike.entity.FlightPhase phase, Vec3 aim) {
        try {
            var phaseField = StrikeProjectile.class.getDeclaredField("DATA_PHASE");
            var aimField = StrikeProjectile.class.getDeclaredField("DATA_AIM");
            phaseField.setAccessible(true);
            aimField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var ph = (net.minecraft.network.syncher.EntityDataAccessor<Byte>) phaseField.get(null);
            @SuppressWarnings("unchecked")
            var am = (net.minecraft.network.syncher.EntityDataAccessor<org.joml.Vector3f>) aimField.get(null);
            e.getEntityData().set(ph, (byte) phase.ordinal());
            e.getEntityData().set(am, new org.joml.Vector3f((float) aim.x, (float) aim.y, (float) aim.z));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Короткие полёты издалека, без пусковой (для сценария ударов: снаряд приходит за ~5 с). */
    private static void quickFlights() {
        var c = ua.zentix.airstrike.AirstrikeConfig.SERVER;
        c.launchNearPlayer.set(false);
        c.droneFlightTime.set(5);
        c.missileFlightTime.set(5);
        c.bomberFlightTime.set(5);
    }

    /**
     * Пуск с пусковой у игрока: площадка в небе (ровно и твёрдо), пусковая шахедов разворачивается позади, пуск,
     * разгон, отделение ускорителя, камера шахеда до удара; потом то же для крылатой ракеты. Полёты укорочены
     * (шахед 20 с, ракета 12 с), чтобы сценарий шёл минуты, а не часы.
     */
    private void planLaunch() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
        });
        at(200, () -> {
            var c = ua.zentix.airstrike.AirstrikeConfig.SERVER;
            c.launchNearPlayer.set(true);
            c.droneFlightTime.set(20);
            c.missileFlightTime.set(12);
            cmd("fill -30 199 -30 30 199 30 minecraft:smooth_stone");
            // лицом к цели: пусковая встанет за спиной
            cmd("tp @s 0.5 200 0.5 0 5");
        });
        at(230, () -> {
            aimAhead(150);
            cmd(String.format(java.util.Locale.ROOT, "airstrike drone at %.1f %.1f %.1f", target.x, target.y, target.z));
        });
        at(234, () -> cmd("tp @s 0.5 200 0.5 180 5"));
        // смотрим назад, на пусковую: подъём пакета, поджиг, сход, отделение ускорителя
        for (int t = 240; t <= 360; t += 6) shot(t, "launch_drone");
        at(370, () -> cmd("tp @s 0.5 200 0.5 0 5"));
        at(380, ua.zentix.airstrike.client.cam.ProjectileCamera::cycle);
        for (int t = 400; t <= 820; t += 20) shot(t, "camera_drone");
        at(830, ua.zentix.airstrike.client.cam.ProjectileCamera::exit);
        at(840, () -> cmd(String.format(java.util.Locale.ROOT, "airstrike missile at %.1f %.1f %.1f", target.x, target.y, target.z)));
        at(844, () -> cmd("tp @s 0.5 200 0.5 180 5"));
        for (int t = 850; t <= 960; t += 5) shot(t, "launch_missile");
        at(965, () -> cmd("tp @s 0.5 200 0.5 0 5"));
        at(970, ua.zentix.airstrike.client.cam.ProjectileCamera::cycle);
        for (int t = 980; t <= 1240; t += 10) shot(t, "camera_missile");
        at(1250, ua.zentix.airstrike.client.cam.ProjectileCamera::exit);
        for (int t = 1260; t <= 1300; t += 20) shot(t, "after");
        at(1320, () -> {
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

    /** Полёты по данным сервера: оружие, фаза, время до удара — по ним видно, что пуск и полёт идут по плану. */
    private static void logFlights() {
        StringBuilder sb = new StringBuilder();
        for (var f : ua.zentix.airstrike.client.hud.ClientFlights.all()) {
            sb.append(String.format(java.util.Locale.ROOT, " %s#%d %s eta=%.0fs;", f.weapon().getSerializedName(), f.number,
                    f.phase().getSerializedName(), f.etaSeconds(0)));
        }
        Airstrike.LOG.info("SCENARIO flights{} camera={}", sb, ua.zentix.airstrike.client.cam.ProjectileCamera.isViewing());
    }

    /** Что сейчас летит и насколько громко / каким тоном звучит (для проверки Доплера и задержки по логу). */
    private void logSound() {
        Minecraft mc = Minecraft.getInstance();
        StringBuilder sb = new StringBuilder();
        for (var e : mc.level.entitiesForRendering()) {
            if (e instanceof StrikeProjectile p) {
                sb.append(String.format(" %s d=%.0f v=%.1f ph=%s;", p.getType().toShortString(), p.distanceTo(mc.player), p.speed(), p.flightPhase().getSerializedName()));
            }
        }
        Airstrike.LOG.info("SCENARIO t={} [{}] flying:{} engines:{}", tick, mc.getSoundManager().getDebugString(), sb, ClientSounds.describe());
    }
}
