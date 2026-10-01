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
import net.minecraft.core.BlockPos;
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
import ua.zentix.airstrike.strike.WeaponType;

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
    /** Эффекты по заданным точкам ({@code airstrike.fx.targets}): точка удара на каждый пункт {@link #fx}. */
    private java.util.ArrayDeque<double[]> fxTargets;
    private Vec3 eye = Vec3.ZERO;
    private StrikeProjectile watched;
    private String current;
    private int flightShots;
    /**
     * Подлёт с земли ({@code airstrike.fx.approach=true}, только с {@code airstrike.fx.targets}): зритель стоит в 40 блоках
     * к востоку от цели и смотрит на север, откуда заходит снаряд, кадры подлёта — каждые 8 тиков до самого взрыва.
     */
    private final boolean approach = Boolean.getBoolean("airstrike.fx.approach");
    /**
     * Удар по заданной точке ({@link #fxTargets}): пуск от консоли встроенного сервера и взрыв своего снаряда. Снаряд у
     * клиента ищется по UUID и заново после ухода в полёт вне мира; следующая цель — только после взрыва (или срока).
     */
    private volatile StrikeWatch strike;
    /** Тик клиента, когда ушла команда удара ({@link #strike}), и снаряд уже был у клиента. */
    private int strikeTick = -1;
    private boolean reached;
    /** Удара нет за столько тиков после команды — «no impact», следующая цель. */
    private static final int FX_IMPACT_LIMIT = 2400;
    /** Зритель подлёта: столько блоков над самым высоким столбом в {@link #FX_VIEW_RADIUS} от цели, и сколько ждать готовых чанков. */
    private static final int FX_VIEW_ABOVE = 35, FX_VIEW_RADIUS = 64, FX_VIEW_WAIT = 600;
    /** Видео с борта: текущий вариант («dry», потом «wet»), тик пуска и снятые кадры. */
    private String onboard;
    private int onboardFired = -1, onboardFrames;
    /** Больше всего частиц перед объективом в кадрах с прошлой строки лога (считается в кадре, с камерой этого кадра). */
    private int lensMax;
    /** Сценарий моделей: частицы не нужны. */
    private boolean models;
    /** Раз в сколько тиков звук в лог. */
    private int soundEvery = 10;

    public ClientScenario(IEventBus modBus) {
        String scenario = System.getProperty("airstrike.scenario");
        if (scenario == null) return;
        FrameTimes.startIfRequested();
        if (scenario.startsWith("mp-")) {
            new MultiplayerScenario("mp-a".equals(scenario)); // свой сервер, без мира сценария (tools/mp_scenario.sh)
            return;
        }
        NeoForge.EVENT_BUS.addListener(this::onScreen);
        if ("trailer".equals(scenario)) {
            new ua.zentix.airstrike.scenario.trailer.Trailer(); // свой сценарий и запись (tools/trailer)
            return;
        }
        if ("strike-profile".equals(scenario)) {
            new StrikeProfile(); // шаги и замер тиков сервера — свои (StrikeProfile); мир — копия игрока (onScreen)
            return;
        }
        if (scenario.startsWith("flyby-")) {
            new FlybySound(scenario.substring("flyby-".length())); // случаи звука по очереди, итоги в лог (FlybySound)
            return;
        }
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevel);
        String mode = scenario;
        if ("nuke".equals(mode)) planNuke();
        else if ("fx".equals(mode) || "fx-night".equals(mode)) planFx("fx-night".equals(mode));
        else if ("launch".equals(mode)) planLaunch();
        else if ("rocket".equals(mode)) planRocket();
        else if ("loiter".equals(mode)) planLoiter();
        else if ("models".equals(mode)) planModels();
        else if ("hud".equals(mode)) planHud();
        else if ("map".equals(mode)) planMap();
        else if ("target-map".equals(mode)) planTargetMap();
        else if ("salvo-map".equals(mode)) planSalvoMap();
        else if ("occlusion".equals(mode)) planOcclusion();
        else if ("nuke-profile".equals(mode)) planNukeProfile();
        else if ("onboard".equals(mode)) planOnboard();
        else if ("salvo-bench".equals(mode)) planSalvoBench();
        else if ("flyby".equals(mode)) planFlyby();
        else if ("commands".equals(mode)) planCommands();
        else plan();
    }

    private void onScreen(ScreenEvent.Init.Post e) {
        if (started || !(e.getScreen() instanceof TitleScreen)) return;
        started = true;
        Minecraft mc = Minecraft.getInstance();
        // копия мира игрока (tools/prod_client.py --world): открыть её, а не создавать мир сценария
        String world = System.getProperty("airstrike.world");
        if (world != null) {
            mc.createWorldOpenFlows().openWorld(world, () -> Airstrike.LOG.error("SCENARIO не открылся мир {}", world));
            return;
        }
        deleteOldWorld(mc.gameDirectory.toPath().resolve("saves").resolve(WORLD));
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20260927L, "trailer".equals(System.getProperty("airstrike.scenario")), false),
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
        if (onboard != null) onboardEvents();
        // модели крупным планом: дым выхлопа и шлейфы закрыли бы их
        if (models) mc.particleEngine.setLevel(mc.level);
        if (tick % soundEvery == 0) logSound();
        if (tick % 10 == 0 && (!ua.zentix.airstrike.client.hud.ClientFlights.all().isEmpty() || ua.zentix.airstrike.client.cam.ProjectileCamera.isActive())) logFlights();
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
        String spec = System.getProperty("airstrike.fx.targets");
        if (spec != null) {
            planFxAt(spec, night);
            return;
        }
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

    /**
     * Замер подрыва на копии мира игрока (tools/prod_client.py nuke-profile --world …): 15 кт в воздухе в 300 блоках
     * от игрока, потом второй — в 600 с другой стороны. Строка подрыва в логе даёт разбивку времени; рядом — сколько
     * чанков загружено и сущностей в мире в этот момент.
     */
    private void planNukeProfile() {
        for (int[] shot : new int[][]{{600, 300}, {1800, -600}}) {
            at(shot[0], () -> {
                var server = Minecraft.getInstance().getSingleplayerServer();
                var player = Minecraft.getInstance().player;
                double x = player.getX(), z = player.getZ() + shot[1];
                server.execute(() -> {
                    var level = server.overworld();
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) x, (int) z);
                    int entities = 0;
                    for (var ignored : level.getAllEntities()) entities++;
                    Airstrike.LOG.info("SCENARIO nuke-profile before: chunks={} entities={} players={}", level.getChunkSource().getLoadedChunksCount(),
                            entities, level.players().size());
                    ua.zentix.airstrike.nuclear.NuclearWarhead.detonate(level, new Vec3(x, y, z), 15, true, null);
                });
            });
        }
        at(2400, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Нагрузка больших залпов на копии мира игрока (tools/prod_client.py salvo-bench --world … --prop
     * airstrike.frametimes=true): РСЗО 30 штук с разбросом 150 по точке в 500 блоках впереди, потом 30 шахедов туда же.
     * Команды — от имени игрока с правами сервера (в мире игрока читы могут быть выключены). Раз в секунду в лог —
     * среднее время тика сервера (mspt); время кадров — в logs/frametimes.txt.
     */
    private void planSalvoBench() {
        String[] salvo = {"rocket", "drone"};
        for (int i = 0; i < salvo.length; i++) {
            String weapon = salvo[i];
            at(600 + i * 2400, () -> {
                var mc = Minecraft.getInstance();
                var p = mc.player;
                String c = String.format(java.util.Locale.ROOT, "execute as %s at @s run airstrike salvo %s 30 150 at %.1f ~ %.1f",
                        p.getGameProfile().getName(), weapon, p.getX(), p.getZ() + 500);
                var server = mc.getSingleplayerServer();
                server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c));
                Airstrike.LOG.info("SCENARIO salvo-bench fire {}", weapon);
            });
        }
        for (int t = 20; t <= 6000; t += 20) {
            at(t, () -> {
                var server = Minecraft.getInstance().getSingleplayerServer();
                Airstrike.LOG.info("SCENARIO salvo-bench mspt={} fps={}", String.format(java.util.Locale.ROOT, "%.1f", server.getAverageTickTimeNanos() / 1e6),
                        Minecraft.getInstance().getFps());
            });
        }
        at(6000, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Эффекты по заданным точкам мира игрока ({@code tools/prod_client.py fx --world … --prop airstrike.fx.targets=…}):
     * пункты через «;», каждый {@code оружие@x,z} (удар в верх колонки — крышу постройки) или {@code оружие@x,y,z}.
     * Зритель висит в 60 блоках к северу и в 25 над точкой, удар — через 200 тиков после переноса (прогрузка чанков),
     * кадры — от настоящего взрыва, как у {@code fx}; имена кадров — {@code оружие-номер_тик.png}. С {@link #approach} —
     * зритель на земле сбоку от цели, снаряд заходит с севера.
     */
    private void planFxAt(String spec, boolean night) {
        fx = new java.util.ArrayDeque<>();
        fxTargets = new java.util.ArrayDeque<>();
        int n = 0;
        for (String item : spec.split(";")) {
            String[] a = item.strip().split("@");
            String[] c = a.length == 2 ? a[1].split(",") : new String[0];
            if (c.length != 2 && c.length != 3) {
                Airstrike.LOG.warn("SCENARIO fx.targets: «{}» пропущено — нужно оружие@x,z или оружие@x,y,z", item);
                continue;
            }
            double[] p = new double[3];
            p[0] = Double.parseDouble(c[0]);
            p[1] = c.length == 3 ? Double.parseDouble(c[1]) : Double.NaN;
            p[2] = Double.parseDouble(c[c.length - 1]);
            fx.add(a[0].strip() + "-" + ++n);
            fxTargets.add(p);
        }
        at(40, () -> {
            // в мире игрока режим выживания: полёт клиента сервер не разрешает, и зритель падал к цели
            cmd("gamemode spectator");
            cmd(night ? "time set 18000" : "time set 6000");
            cmd("weather clear");
            Minecraft.getInstance().options.hideGui = true;
            quickFlights();
        });
        NeoForge.EVENT_BUS.addListener(this::onServerBlast);
        at(100, this::nextFx);
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
        if (fxTargets != null) {
            double[] p = fxTargets.poll();
            String weapon = current.substring(0, current.lastIndexOf('-'));
            eye = new Vec3(p[0] + 0.5, (Double.isNaN(p[1]) ? mc.player.getY() : p[1]) + 25, p[2] - 60);
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f", eye.x, Math.max(eye.y, 150), eye.z));
            mc.player.getAbilities().flying = true;
            String label = current;
            current = "wait";
            // чанки вокруг цели у клиента — через 200 тиков после переноса или позже: пробовать, пока не готовы
            int from = tick;
            boolean[] aimed = {false};
            for (int t = 200; t <= 200 + FX_VIEW_WAIT; t += 20) {
                boolean last = t == 200 + FX_VIEW_WAIT;
                at(from + t, () -> {
                    if (!aimed[0]) aimed[0] = fxAim(p, weapon, label, last);
                });
            }
            return;
        }
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

    /**
     * Точка удара, зритель и пуск для пункта {@code fx.targets}. {@code false} — чанки вокруг цели у клиента ещё не
     * готовы (повторить позже; {@code force} — последний раз: без готовых чанков пункт пропускается).
     */
    private boolean fxAim(double[] p, String weapon, String label, boolean force) {
        Minecraft mc = Minecraft.getInstance();
        int cx = (int) Math.floor(p[0]), cz = (int) Math.floor(p[2]);
        // высота без готового чанка — низ мира: зритель встал бы в постройку, цель ушла бы на дно мира (v3: ракета 1
        // на y −64, карта высот чанка ещё пустая) — столб у дна мира тоже «не готов»
        int floor = mc.level.getMinBuildHeight() + 4;
        int top = Integer.MIN_VALUE;
        for (int x = cx - FX_VIEW_RADIUS; x <= cx + FX_VIEW_RADIUS; x += 4) {
            for (int z = cz - FX_VIEW_RADIUS; z <= cz + FX_VIEW_RADIUS; z += 4) {
                int h = mc.level.hasChunk(x >> 4, z >> 4) ? mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : Integer.MIN_VALUE;
                if (h <= floor) {
                    if (force) {
                        Airstrike.LOG.warn("SCENARIO {} skipped: chunks around target not loaded by client (column {} {} at {}) — FAIL", label, x, z,
                                h == Integer.MIN_VALUE ? "no chunk" : h);
                        current = "wait";
                        at(tick + 1, this::nextFx);
                        return true;
                    }
                    return false;
                }
                top = Math.max(top, h);
            }
        }
        int y = Double.isNaN(p[1]) ? mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz) : (int) p[1];
        target = new Vec3(cx + 0.5, y, cz + 0.5);
        String command = String.format(java.util.Locale.ROOT, "airstrike %s at %.1f %.1f %.1f", weapon, target.x, target.y, target.z);
        if (approach) {
            // над крышами и кронами в 40 блоках к востоку: в кадре и цель, и последние сотни блоков захода с севера
            eye = new Vec3(cx + 40.5, Math.max(top, y) + FX_VIEW_ABOVE, cz + 0.5);
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", eye.x, eye.y, eye.z,
                    target.x, target.y + 15, target.z - 40));
            // курс захода: yaw 0 — на юг, снаряд приходит с севера
            command = "execute rotated 0 0 run " + command;
        } else {
            eye = target.add(0, 25, -60);
            view();
        }
        BlockPos eyeBlock = BlockPos.containing(eye);
        Airstrike.LOG.info("SCENARIO fx target {} at {}, viewer {} ({} at eye)", label, target, eye, mc.level.getBlockState(eyeBlock));
        current = label;
        strike = new StrikeWatch();
        strikeTick = tick;
        reached = false;
        // от консоли: стреляющего нет, заход — прямая с севера (fromAfar), пусковая у зрителя не ставится
        String run = command;
        StrikeWatch w = strike;
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            var level = server.overworld();
            var before = StrikeWatch.projectiles(level);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), run);
            if (!w.launched(before, StrikeWatch.projectiles(level))) Airstrike.LOG.warn("SCENARIO {}: пуск не найден — снарядов нового нет или несколько", label);
        });
        return true;
    }

    /** Взрыв на встроенном сервере: удар снаряда {@link #strike}, если он его. */
    private void onServerBlast(net.neoforged.neoforge.event.level.ExplosionEvent.Start e) {
        StrikeWatch w = strike;
        if (w == null || e.getLevel().isClientSide()) return;
        Vec3 at = e.getExplosion().center();
        if (w.onBlast(ua.zentix.airstrike.stress.StressDirector.blastBy(e.getExplosion()), at, tick, e.getLevel().getGameTime())) {
            Airstrike.LOG.info("SCENARIO {} blast at {} ({} blocks from target)", current, at, Math.round(at.distanceTo(target)));
        }
    }

    /** Удар по заданной точке: снаряд по UUID, кадры подлёта, взрыв — по взрыву своего снаряда на сервере. */
    private void fxTargetEvents() {
        StrikeWatch w = strike;
        if (w == null || current.equals("wait")) return;
        Minecraft mc = Minecraft.getInstance();
        String name = current;
        if (w.impactTick() >= 0) {
            strike = null;
            current = "wait";
            int at = w.impactTick();
            Airstrike.LOG.info("SCENARIO {} impact at tick {} (server {})", name, at, w.impactGameTime());
            for (int dt : new int[]{1, 2, 4, 7, 12, 20, 35, 60, 100, 160, 240, 320}) shot(Math.max(tick + 1, at + dt), name);
            at(Math.max(tick + 1, at + 340), this::nextFx);
            return;
        }
        if (tick - strikeTick > FX_IMPACT_LIMIT) {
            strike = null;
            current = "wait";
            Airstrike.LOG.warn("SCENARIO {} no impact by tick {} — FAIL", name, tick);
            at(tick + 1, this::nextFx);
            return;
        }
        // сущность у клиента пропадает при потере отслеживания и при уходе в полёт вне мира — искать заново по UUID
        if (watched != null && (watched.isRemoved() || !watched.getUUID().equals(w.projectile()))) watched = null;
        if (watched == null && w.projectile() != null) {
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof StrikeProjectile p && p.getUUID().equals(w.projectile())) watched = p;
            }
            if (watched != null && !reached) {
                reached = true;
                // время мира сервера: до удара — его тики, часы клиента их не повторяют (догоняют сервер рывками)
                Airstrike.LOG.info("SCENARIO {} reached client at tick {} (server {}), {} blocks from viewer, {} from target", name, tick,
                        mc.getSingleplayerServer().overworld().getGameTime(),
                        Math.round(watched.distanceTo(mc.player)), Math.round(watched.position().distanceTo(target)));
            }
        }
        if (watched != null && tick % 8 == 0 && (approach || flightShots < 6 && watched.distanceTo(mc.player) < 150)) {
            flightShots++;
            shot(tick + 1, name + "_flight");
        }
    }

    /** Ждём снаряд, снимаем подлёт, затем взрыв — от тика, когда снаряд пропал. */
    private void fxEvents() {
        if (fxTargets != null) {
            fxTargetEvents();
            return;
        }
        if (current == null || current.equals("icbm") || current.equals("wait")) return;
        Minecraft mc = Minecraft.getInstance();
        if (watched == null) {
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof StrikeProjectile p && p.isActive()) watched = p;
            }
            // с этого тика снаряд есть у клиента (на экране он или нет — смотреть кадры): до «impact» — сколько тиков
            // его можно увидеть
            if (watched != null) {
                Airstrike.LOG.info("SCENARIO {} reached client at tick {}, {} blocks from viewer, {} from target", current, tick,
                        Math.round(watched.distanceTo(mc.player)), Math.round(watched.position().distanceTo(target)));
            }
            return;
        }
        if (!watched.isRemoved()) {
            if (tick % 8 == 0 && (approach || flightShots < 6 && watched.distanceTo(mc.player) < 150)) {
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
                new Pose("drone_ready", ua.zentix.airstrike.registry.ModEntities.DRONE, ua.zentix.airstrike.entity.FlightPhase.READY, 5, false),
                new Pose("drone_cruise", ua.zentix.airstrike.registry.ModEntities.DRONE, ua.zentix.airstrike.entity.FlightPhase.CRUISE, 5, false),
                new Pose("missile_boost", ua.zentix.airstrike.registry.ModEntities.CRUISE_MISSILE, ua.zentix.airstrike.entity.FlightPhase.BOOST, 7, false),
                new Pose("missile_cruise", ua.zentix.airstrike.registry.ModEntities.CRUISE_MISSILE, ua.zentix.airstrike.entity.FlightPhase.CRUISE, 7, false),
                new Pose("bomb", ua.zentix.airstrike.registry.ModEntities.BUNKER_BUSTER, ua.zentix.airstrike.entity.FlightPhase.TERMINAL, 7, false),
                new Pose("rocket", ua.zentix.airstrike.registry.ModEntities.ROCKET, ua.zentix.airstrike.entity.FlightPhase.CRUISE, 4, false),
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
        // пусковые с полным пакетом на площадке, рядом — житель (1.95 м) для масштаба
        at(t, () -> {
            watched.discard();
            cmd("fill -16 199 -16 16 199 16 minecraft:smooth_stone");
        });
        t += 20;
        for (WeaponType w : List.of(WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET, WeaponType.LOITER)) {
            int start = t;
            List<net.minecraft.world.entity.Entity> shown = new java.util.ArrayList<>();
            at(start, () -> shown.addAll(showLauncher(w)));
            double[][] cams = {{-11, 4, 9}, {-13, 2.5, -1}, {9, 5, -11}};
            for (int k = 0; k < cams.length; k++) {
                double[] c = cams[k];
                for (int d : new int[]{5, 15}) {
                    at(start + d + k * 25, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f facing %.2f %.2f %.2f",
                            c[0], 200 + c[1], c[2], 0.0, 202.0, 0.0)));
                }
                shot(start + 25 + k * 25, "launcher_" + w.getSerializedName() + "_" + k);
            }
            at(start + 90, () -> shown.forEach(net.minecraft.world.entity.Entity::discard));
            t += 95;
        }
        at(t + 20, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /** Фаза и точка прицеливания у клиентской копии снаряда (синхронные поля — только через отражение). */
    /** Клиентская пусковая с поднятым пакетом у (0, 200, 0), снаряды во всех ячейках и житель рядом. */
    private static List<net.minecraft.world.entity.Entity> showLauncher(WeaponType w) {
        Minecraft mc = Minecraft.getInstance();
        List<net.minecraft.world.entity.Entity> out = new java.util.ArrayList<>();
        var l = ua.zentix.airstrike.registry.ModEntities.LAUNCHER.get().create(mc.level);
        l.moveTo(0.5, 200, 0.5, 0, 0);
        l.yRotO = 0;
        try {
            for (String f : new String[]{"DATA_WEAPON", "DATA_DEPLOYED"}) {
                var field = ua.zentix.airstrike.entity.LauncherEntity.class.getDeclaredField(f);
                field.setAccessible(true);
                if (f.equals("DATA_WEAPON")) {
                    @SuppressWarnings("unchecked")
                    var a = (net.minecraft.network.syncher.EntityDataAccessor<Byte>) field.get(null);
                    l.getEntityData().set(a, (byte) w.id());
                } else {
                    @SuppressWarnings("unchecked")
                    var a = (net.minecraft.network.syncher.EntityDataAccessor<Long>) field.get(null);
                    l.getEntityData().set(a, mc.level.getGameTime() - 200);
                }
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        mc.level.addEntity(l);
        out.add(l);
        var type = switch (w) {
            case MISSILE -> ua.zentix.airstrike.registry.ModEntities.CRUISE_MISSILE.get();
            case ROCKET -> ua.zentix.airstrike.registry.ModEntities.ROCKET.get();
            case LOITER -> ua.zentix.airstrike.registry.ModEntities.LOITER.get();
            default -> ua.zentix.airstrike.registry.ModEntities.DRONE.get();
        };
        for (int slot = 0; slot < ua.zentix.airstrike.entity.LauncherEntity.slots(w); slot++) {
            Vec3 rail = l.railPoint(slot);
            StrikeProjectile e = type.create(mc.level);
            e.moveTo(rail.x, rail.y, rail.z, 0, -l.elevation());
            e.yRotO = 0;
            e.xRotO = -l.elevation();
            showPhase(e, ua.zentix.airstrike.entity.FlightPhase.READY, rail.add(0, 0, 500));
            mc.level.addEntity(e);
            out.add(e);
        }
        var v = net.minecraft.world.entity.EntityType.VILLAGER.create(mc.level);
        v.moveTo(-2.6, 200, 3.0, 200, 0);
        v.yHeadRot = 200;
        v.yBodyRot = 200;
        mc.level.addEntity(v);
        out.add(v);
        Airstrike.LOG.info("SCENARIO launcher {} slots {}", w.getSerializedName(), out.size() - 2);
        return out;
    }

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
     * Пролёт над головой: зритель стоит на земле на пути снарядов, пущенных издалека (без пусковой рядом), — залп РСЗО
     * из-за спины (снаряды проходят над ним к цели в 200 блоках впереди), крылатая ракета и шахед (последний прямой
     * участок маршрута — тоже из-за спины). Снаряды долго летят вне мира и дальше дальности сущностей: звук с первых
     * слышимых сотен блоков идёт по пакетам сервера ({@code s} в строках звука), вблизи — по сущности ({@code e}).
     * Звук — в строках лога раз в 5 тиков и в audio.wav.
     */
    private void planFlyby() {
        soundEvery = 5;
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
        });
        at(200, () -> {
            var c = ua.zentix.airstrike.AirstrikeConfig.SERVER;
            c.launchNearPlayer.set(false);
            c.droneFlightTime.set(12);
            c.missileFlightTime.set(12);
            aimAhead(200);
            Minecraft mc = Minecraft.getInstance();
            int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
            cmd(String.format(java.util.Locale.ROOT, "tp @s 0.5 %d 0.5 facing %.1f %.1f %.1f", y, target.x, target.y, target.z));
        });
        at(240, () -> cmd(String.format(java.util.Locale.ROOT, "airstrike salvo rocket 6 12 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        at(700, () -> cmd(String.format(java.util.Locale.ROOT, "airstrike missile at %.1f %.1f %.1f", target.x, target.y, target.z)));
        at(1100, () -> cmd(String.format(java.util.Locale.ROOT, "airstrike drone at %.1f %.1f %.1f", target.x, target.y, target.z)));
        for (int t = 250; t <= 1700; t += 50) at(t, ClientScenario::dumpFlights);
        at(1750, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Пуск с пусковой у игрока: площадка в небе (ровно и твёрдо), пусковая шахедов разворачивается позади; камера
     * снаряда с самого пуска — план сбоку от пусковой, разгон, отделение ускорителя, борт, попадание и облёт взрыва;
     * потом то же для крылатой ракеты. Полёты укорочены (шахед 20 с, ракета 12 с), чтобы сценарий шёл минуты.
     */
    private void planLaunch() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
            // площадка длиннее дальности прорисовки сценария: без этого fill иногда отвечает «не загружено»
            cmd("forceload add -32 -48 32 136");
        });
        at(200, () -> {
            var c = ua.zentix.airstrike.AirstrikeConfig.SERVER;
            c.launchNearPlayer.set(true);
            c.droneFlightTime.set(20);
            c.missileFlightTime.set(12);
            // площадка в небе: ровная и твёрдая под пусковую, цель — на ней же в 120 блоках впереди
            cmd("fill -30 199 -40 30 199 130 minecraft:smooth_stone");
            cmd("fill -2 200 118 2 202 122 minecraft:oak_planks");
            cmd("tp @s 0.5 200 0.5 0 5");
            target = new Vec3(0.5, 203, 120.5);
        });
        // пуск, и сразу камера: план пуска сбоку, борт, попадание
        at(230, () -> cmd(String.format(java.util.Locale.ROOT, "airstrike drone at %.1f %.1f %.1f", target.x, target.y, target.z)));
        // сервер под llvmpipe отстаёт: снаряда в списке может ещё не быть — пробуем ещё раз
        for (int t = 236; t <= 266; t += 10) {
            at(t, () -> {
                if (!ua.zentix.airstrike.client.cam.ProjectileCamera.isActive()) ua.zentix.airstrike.client.cam.ProjectileCamera.cycle();
            });
        }
        for (int t = 240; t <= 1000; t += 8) shot(t, "drone");
        at(1010, () -> {
            ua.zentix.airstrike.client.cam.ProjectileCamera.exit();
            cmd(String.format(java.util.Locale.ROOT, "airstrike missile at %.1f %.1f %.1f", target.x, target.y, target.z));
        });
        at(1016, ua.zentix.airstrike.client.cam.ProjectileCamera::cycle);
        for (int t = 1020; t <= 1500; t += 6) shot(t, "missile");
        at(1510, ua.zentix.airstrike.client.cam.ProjectileCamera::exit);
        at(1520, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * HUD ударов: десять снарядов по пяти целям сразу — стойка с именем (залп из 4), зомби (3), свинья (гибнет в полёте:
     * серый крестик), точка рядом со стойкой (крестики сливаются) и ракета в дальнюю точку. Зритель — над пусковой,
     * смотрит на цели: метки, пунктиры, подписи (соседние не наезжают друг на друга) и список справа; потом — вблизи.
     */
    private void planHud() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
            cmd("forceload add -32 -48 32 136");
        });
        at(200, () -> {
            var c = ua.zentix.airstrike.AirstrikeConfig.SERVER;
            c.launchNearPlayer.set(true);
            c.droneFlightTime.set(30);
            c.missileFlightTime.set(20);
            cmd("fill -30 199 -40 30 199 130 minecraft:smooth_stone");
            cmd("tp @s 0.5 200 0.5 0 5");
            // мирный режим убирает враждебных мобов: цели — стойка с именем, житель и свинья (метки — теги hud)
            cmd("summon minecraft:armor_stand -11.5 200 105.5 {CustomName:'\"ENOTzRPG\"',Tags:[\"hud_named\"]}");
            cmd("summon minecraft:villager 12.5 200 112.5 {NoAI:1b,Tags:[\"hud_type\"]}");
            cmd("summon minecraft:pig 0.5 200 122.5 {NoAI:1b,Tags:[\"hud_lost\"]}");
            target = new Vec3(0.5, 200, 122.5);
        });
        at(215, () -> {
            cmd("airstrike salvo drone 4 0 @e[tag=hud_named,limit=1]");
            cmd("airstrike salvo drone 3 0 @e[tag=hud_type,limit=1]");
            cmd("airstrike drone @e[tag=hud_lost,limit=1]");
            cmd("airstrike drone at -9.5 200 106.5");
            cmd("airstrike missile at 20.5 200 128.5");
        });
        // зритель над пусковой, за спиной у линии огня: видны и снаряды, и цели
        at(225, () -> cmd("tp @s 0.5 222 -14.5 0 9"));
        at(420, () -> cmd("kill @e[tag=hud_lost]"));
        for (int t = 240; t <= 900; t += 20) shot(t, "hud");
        // вблизи целей: подписи крупнее на экране
        at(905, () -> cmd("tp @s 0.5 214 70.5 0 20"));
        for (int t = 920; t <= 1100; t += 15) shot(t, "hud_near");
        at(1110, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Камера снаряда дальше прорисовки (12 чанков, как у Артёма): ракета на 800 блоков и три шахеда на 600 с пусковой
     * рядом. Видео — пока снаряд близко, дальше карта по телеметрии сервера; ракета пропадает у цели — «цель поражена»
     * на карте, потом камера переходит к шахедам.
     */
    private void planMap() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
            cmd("forceload add -32 -48 32 64");
        });
        at(200, () -> {
            var c = ua.zentix.airstrike.AirstrikeConfig.SERVER;
            c.launchNearPlayer.set(true);
            c.droneFlightTime.set(30);
            c.missileFlightTime.set(20);
            cmd("fill -30 199 -40 30 199 60 minecraft:smooth_stone");
            cmd("tp @s 0.5 200 0.5 0 5");
        });
        at(215, () -> {
            cmd("airstrike missile at 0.5 80 800.5");
            cmd("airstrike salvo drone 3 8 at 300.5 80 520.5");
        });
        for (int t = 225; t <= 265; t += 10) {
            at(t, () -> {
                if (!ua.zentix.airstrike.client.cam.ProjectileCamera.isActive()) ua.zentix.airstrike.client.cam.ProjectileCamera.cycle();
            });
        }
        for (int t = 240; t <= 1400; t += 12) shot(t, "map");
        for (int t = 600; t <= 1400; t += 100) at(t, ClientScenario::dumpFlights);
        at(1410, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Карта наведения: открыть с пульта, отдалить колесом, выбрать место кликом в 60 и 40 пикселей от центра (северо-восток),
     * огонь по Enter — всё через ввод экрана, как у игрока. В лог — выбранное место и цель снаряда по данным сервера
     * (высота — поверхность); кадры target-map_* — карта с рельефом, с меткой цели, потом снаряд на ней.
     * Свойства: {@code airstrike.mapWeapon} — оружие пульта (по умолчанию ракета), {@code airstrike.mapAt=x,z} — место
     * на карте задано точкой, а не кликом (проверка удара по известной дальней крыше), {@code airstrike.mapFrom=x,y,z} —
     * откуда бить (и в копии мира игрока).
     */
    private void planTargetMap() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            // в копии мира игрока (prod_client.py --world) — там, где он стоит
            String from = System.getProperty("airstrike.mapFrom");
            if (from != null) {
                // высота земли там заранее не известна: сверху, в творческом — без урона от падения
                cmd("gamemode creative");
                cmd("tp @s " + from.replace(',', ' ') + " 0 30");
            }
            else if (System.getProperty("airstrike.world") == null) cmd("tp @s 0.5 120 0.5 0 30");
            // новый пульт в руке — настройки по умолчанию: одна ракета без разброса, промах меряется от точки
            String weapon = System.getProperty("airstrike.mapWeapon");
            cmd("item replace entity @s weapon.mainhand with airstrike:strike_designator"
                    + (weapon == null ? "" : "[airstrike:loadout={weapon:\"" + java.util.Objects.requireNonNull(WeaponType.parse(weapon), "airstrike.mapWeapon: " + weapon).getSerializedName() + "\"}]"));
        });
        // карту открывают, поиграв: DH к этому времени загрузил свои LOD вокруг. Пока он их грузит, чтение рельефа
        // через его API стоит в очереди за ними (пул ввода-вывода DH ниже по приоритету, чем загрузка LOD)
        for (int t = 300; t <= 300 + MAP_DH_WAIT; t += 20) {
            at(t, () -> {
                if (mapOpened < 0 && (dhIdle() || tick >= 300 + MAP_DH_WAIT)) mapBegin(tick);
            });
        }
        // попадание — первый взрыв на сервере (встроенном): где он и насколько далеко от выбранной точки
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ExplosionEvent.Detonate e) -> {
            Vec3 at = e.getExplosion().center(), aim = mapTarget;
            if (aim == null || mapImpactTick >= 0 || e.getLevel().isClientSide()) return;
            double miss = Math.hypot(at.x - aim.x, at.z - aim.z);
            BlockPos hit = BlockPos.containing(at), target = BlockPos.containing(aim);
            // неуправляемому (РСЗО) — его рассеивание из паспорта: три СКО по дальности от стреляющего (пакет у него)
            WeaponType weapon = WeaponType.parse(System.getProperty("airstrike.mapWeapon", "missile"));
            var shooter = e.getLevel().players().isEmpty() ? null : e.getLevel().players().getFirst();
            double range = shooter == null ? 0 : Math.hypot(shooter.getX() - aim.x, shooter.getZ() - aim.z);
            double allowed = MAP_MAX_MISS + 3 * (weapon == null ? 0 : weapon.spec().route().sigma(range));
            Airstrike.LOG.info("SCENARIO map-target impact {} miss {} (допуск {}) — {}; земля под взрывом {} (с листвой {}), у цели {} (с листвой {})", at,
                    Math.round(miss), Math.round(allowed), miss <= allowed ? "OK" : "FAIL",
                    e.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hit.getX(), hit.getZ()),
                    e.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, hit.getX(), hit.getZ()),
                    e.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ()),
                    e.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, target.getX(), target.getZ()));
            mapImpactTick = tick;
        });
    }

    /**
     * Залп на карте наведения — кадры salvo-map_* (штриховые пути снарядов к целям, район разброса) и время, за которое
     * карта открылась, в логе («Карта наведения: рельеф вида готов за …»): в пульте шесть шахедов с разбросом 40,
     * место кликом в ~130 блоках, огонь по Enter, карта снова открыта на весь полёт. В копии мира игрока
     * (prod_client.py --world) — там, где он стоит; карту открывают, когда DH загрузил свои LOD (как в target-map).
     */
    private void planSalvoMap() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            if (System.getProperty("airstrike.world") == null) cmd("tp @s 0.5 120 0.5 0 30");
            cmd("item replace entity @s weapon.mainhand with airstrike:strike_designator");
        });
        at(60, () -> {
            // настройки пульта — как с его экрана: в предмет у клиента и на сервер
            var salvo = new ua.zentix.airstrike.strike.Loadout(WeaponType.DRONE, 6, 40, ua.zentix.airstrike.strike.TargetMode.MAP, "",
                    ua.zentix.airstrike.strike.Loadout.DEFAULT.nuke());
            Minecraft.getInstance().player.getMainHandItem().set(ua.zentix.airstrike.registry.ModDataComponents.LOADOUT.get(), salvo);
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new ua.zentix.airstrike.net.C2S.SetLoadout(net.minecraft.world.InteractionHand.MAIN_HAND, salvo));
        });
        for (int t = 300; t <= 300 + MAP_DH_WAIT; t += 20) {
            at(t, () -> {
                if (mapOpened < 0 && (dhIdle() || tick >= 300 + MAP_DH_WAIT)) salvoMapBegin(tick);
            });
        }
    }

    private void salvoMapBegin(int o) {
        mapOpened = o;
        Airstrike.LOG.info("SCENARIO salvo-map open at tick {}", o);
        ua.zentix.airstrike.AirstrikeConfig.SERVER.droneFlightTime.set(20);
        Minecraft.getInstance().setScreen(new ua.zentix.airstrike.client.screen.MapScreen(new RemoteScreen()));
        shot(o + 10, "salvo-map");
        shot(o + 40, "salvo-map");
        at(o + 60, () -> {
            var screen = Minecraft.getInstance().screen;
            double x = screen.width / 2.0 + 120, y = screen.height / 2.0 - 50;
            screen.mouseClicked(x, y, 0);
            screen.mouseReleased(x, y, 0);
        });
        shot(o + 70, "salvo-map");
        at(o + 80, () -> Minecraft.getInstance().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0));
        at(o + 100, () -> Minecraft.getInstance().setScreen(new ua.zentix.airstrike.client.screen.MapScreen(new RemoteScreen())));
        for (int t = o + 120; t <= o + 520; t += 40) shot(t, "salvo-map");
        at(o + 540, () -> {
            logFlights();
            Airstrike.LOG.info("SCENARIO salvo-map terrain {}", ua.zentix.airstrike.client.map.TerrainTiles.stats());
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /** Карта наведения с тика {@code o}: отдалить, выбрать точку в ~700 блоках, огонь, ждать попадания. */
    private void mapBegin(int o) {
        mapOpened = o;
        Airstrike.LOG.info("SCENARIO map-target open at tick {}", o);
        if (net.neoforged.fml.ModList.get().isLoaded("distanthorizons")) logDhThreads();
        ua.zentix.airstrike.AirstrikeConfig.SERVER.droneFlightTime.set(20);
        Minecraft.getInstance().setScreen(new ua.zentix.airstrike.client.screen.MapScreen(new RemoteScreen()));
        shot(o + 40, "target-map");
        // отдалить карту: точка далеко за дальностью прорисовки — рельеф там есть только у DH
        at(o + 60, () -> {
            var screen = Minecraft.getInstance().screen;
            screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, 0, -8);
        });
        shot(o + 100, "target-map");
        at(o + 120, () -> {
            var screen = Minecraft.getInstance().screen;
            String at = System.getProperty("airstrike.mapAt");
            if (at != null) {
                String[] xz = at.split(",");
                ua.zentix.airstrike.client.map.MapTarget.set(Minecraft.getInstance().level, new ua.zentix.airstrike.client.map.MapTarget.Place(
                        Double.parseDouble(xz[0].strip()) + 0.5, Double.parseDouble(xz[1].strip()) + 0.5));
            } else {
                double x = screen.width / 2.0 + 100, y = screen.height / 2.0 - 70;
                screen.mouseClicked(x, y, 0);
                screen.mouseReleased(x, y, 0);
            }
            var place = ua.zentix.airstrike.client.map.MapTarget.get(Minecraft.getInstance().level).orElseThrow();
            mapTarget = new Vec3(place.x(), 0, place.z());
            Airstrike.LOG.info("SCENARIO map-target selected X {} Z {} distance {} terrain height {} far {}", Math.round(place.x()), Math.round(place.z()),
                    Math.round(Math.hypot(place.x() - Minecraft.getInstance().player.getX(), place.z() - Minecraft.getInstance().player.getZ())),
                    ua.zentix.airstrike.client.map.TerrainTiles.height((int) Math.floor(place.x()), (int) Math.floor(place.z())),
                    ua.zentix.airstrike.client.map.TerrainTiles.farTerrain());
            Airstrike.LOG.info("SCENARIO map-target terrain {}", ua.zentix.airstrike.client.map.TerrainTiles.stats());
        });
        shot(o + 130, "target-map");
        at(o + 140, () -> {
            // высота с карты, которую увезёт приказ (C2S.AimHint.mapSurface): плитка у места могла достроиться после выбора
            Vec3 aim = mapTarget;
            Airstrike.LOG.info("SCENARIO map-target order terrain height {}", aim == null ? "—"
                    : ua.zentix.airstrike.client.map.TerrainTiles.height((int) Math.floor(aim.x), (int) Math.floor(aim.z)));
            Minecraft.getInstance().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
        });
        at(o + 200, () -> {
            for (var f : ua.zentix.airstrike.client.hud.ClientFlights.all()) {
                Airstrike.LOG.info("SCENARIO map-target flight {} target {}", f.weapon(), f.target());
            }
            Minecraft.getInstance().setScreen(new ua.zentix.airstrike.client.screen.MapScreen(new RemoteScreen()));
        });
        for (int t = o + 220; t <= o + 700; t += 80) shot(t, "target-map");
        for (int t = o + 300; t <= o + MAP_FLIGHT_WAIT; t += 200) {
            at(t, () -> {
                Airstrike.LOG.info("SCENARIO map-target terrain {}", ua.zentix.airstrike.client.map.TerrainTiles.stats());
                if (net.neoforged.fml.ModList.get().isLoaded("distanthorizons")) logDhThreads();
            });
        }
        // конец — через 3 с после попадания (кадры карты после удара) или по сроку
        for (int t = o + 220; t <= o + MAP_FLIGHT_WAIT; t += 20) {
            at(t, () -> {
                if (mapDone) return;
                if (mapImpactTick < 0 || tick < mapImpactTick + 60) {
                    if (tick < o + MAP_FLIGHT_WAIT) return;
                    Airstrike.LOG.warn("SCENARIO map-target no impact by tick {} — FAIL", tick);
                }
                mapDone = true;
                Airstrike.LOG.info("SCENARIO map-target terrain {}", ua.zentix.airstrike.client.map.TerrainTiles.stats());
                Airstrike.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
        }
    }

    /** DH не стоит или уже загрузил свои LOD вокруг: очередь загрузки пуста. */
    private static boolean dhIdle() {
        if (!net.neoforged.fml.ModList.get().isLoaded("distanthorizons")) return true;
        try {
            Object ex = Class.forName("com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil").getMethod("getRenderLoadingExecutor").invoke(null);
            return ex == null || (int) ex.getClass().getMethod("getQueueSize").invoke(ex) == 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /**
     * Очереди потоков Distant Horizons (его внутренние классы, только для разбора в сценарии): чтение рельефа через API
     * идёт в пул ввода-вывода DH ({@code ThreadPoolUtil.getFileHandlerExecutor}).
     */
    private static void logDhThreads() {
        try {
            Class<?> util = Class.forName("com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil");
            StringBuilder b = new StringBuilder();
            for (String getter : new String[] {"getFileHandlerExecutor", "getRenderLoadingExecutor", "getChunkToLodBuilderExecutor",
                    "getUpdatePropagatorExecutor", "getWorldGenExecutor"}) {
                Object ex = util.getMethod(getter).invoke(null);
                if (ex == null) continue;
                Class<?> c = ex.getClass();
                b.append(String.format(java.util.Locale.ROOT, "%s: очередь %s, идёт %s, сделано %s; ", getter.substring(3),
                        c.getMethod("getQueueSize").invoke(ex), c.getMethod("getRunningTaskCount").invoke(ex), c.getMethod("getCompletedTaskCount").invoke(ex)));
            }
            java.lang.reflect.Field picker = util.getDeclaredField("taskPicker");
            picker.setAccessible(true);
            Object tp = picker.get(null);
            java.lang.reflect.Field occupied = tp.getClass().getDeclaredField("occupiedThreadsRef");
            occupied.setAccessible(true);
            b.append("занято потоков ").append(occupied.get(tp));
            Airstrike.LOG.info("SCENARIO map-target dh threads {}", b);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Airstrike.LOG.info("SCENARIO map-target dh threads не прочитаны: {}", e.toString());
        }
    }

    /** Сценарий target-map ждёт, пока DH загрузит свои LOD, не дольше этого (тиков), и попадания — не дольше второго. */
    private static final int MAP_DH_WAIT = 6000, MAP_FLIGHT_WAIT = 2100;
    /** Одна ракета без разброса: взрыв не дальше этого от выбранной точки (по горизонтали), блоков; неуправляемому — ещё три СКО его рассеивания. */
    private static final double MAP_MAX_MISS = 2;
    /** Точка, выбранная на карте (сценарий target-map); её читает и поток сервера. */
    @org.jetbrains.annotations.Nullable
    private volatile Vec3 mapTarget;
    private volatile int mapImpactTick = -1;
    private int mapOpened = -1;
    private boolean mapDone;

    /** Самое долгое {@code wait:N} сценария commands — час игры, тиков. */
    private static final int COMMANDS_MAX_WAIT = 72_000;

    /**
     * Команды из свойства {@code airstrike.commands} (через «;», кроме «;» в скобках и кавычках — {@link ScenarioCommands}) в открытом мире — проверить, что моды сборки отвечают
     * (например копия для съёмки: {@code /dh pregen status}, {@code /chunky}); ответы идут в чат, чат — в лог клиента.
     * Кроме команд: {@code wait:N} — ещё N тиков (0…{@value #COMMANDS_MAX_WAIT}), {@code shot:имя} — снимок экрана
     * {@code имя_тик.png}, {@code hud:off}/{@code hud:on} — скрыть и вернуть интерфейс (как F1: чат с ответами команд
     * не закрывает кадр, а в лог клиента идёт как прежде). Шаги идут друг за другом: после команды — 40 тиков
     * ({@code airstrike.commands.gap}, от 1: счёт сущностей раз в секунду), после
     * снимка — 20, после {@code hud:} — 1 (снимок берёт уже нарисованный кадр: в тот же тик он был бы ещё с интерфейсом),
     * и {@code wait:N}
     * прибавляется к ним ({@code cmd;wait:1200;shot:x} снимает через 1240 тиков после команды). Неверный {@code wait:}
     * пишется в лог и пропускается: исключение здесь остановило бы загрузку модов, и «SCENARIO done» не пришёл бы.
     */
    private void planCommands() {
        int t = 100;
        int gap = Math.max(1, Integer.getInteger("airstrike.commands.gap", 40));
        for (String c : ScenarioCommands.split(System.getProperty("airstrike.commands", ""))) {
            if (c.startsWith("wait:")) {
                String n = c.substring("wait:".length()).strip();
                int ticks;
                try {
                    ticks = Integer.parseInt(n);
                } catch (NumberFormatException e) {
                    ticks = -1;
                }
                if (ticks < 0 || ticks > COMMANDS_MAX_WAIT) {
                    Airstrike.LOG.warn("SCENARIO commands: «{}» пропущено — нужно целое число тиков от 0 до {}", c,
                        COMMANDS_MAX_WAIT);
                    continue;
                }
                t += ticks;
            } else if (c.startsWith("shot:")) {
                shot(t, c.substring("shot:".length()).strip());
                t += 20;
            } else if (c.equals("hud:off") || c.equals("hud:on")) {
                boolean hide = c.equals("hud:off");
                at(t, () -> Minecraft.getInstance().options.hideGui = hide);
                t += 1;
            } else {
                at(t, () -> cmd(c));
                t += gap;
            }
        }
        at(t + 100, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Видео с борта ракеты (камера снаряда) с наводчиком на суше, потом — с наводчиком под водой (в стеклянном бассейне):
     * картинка с борта не должна зависеть от того, где стоит игрок; потом — утром, как снимался план трейлера (цель
     * к востоку). Кадры onboard-dry_*, onboard-wet_*, onboard-dawn_*; в лог — среда камеры и игрока
     * и сколько частиц перед объективом. Мир идёт медленно (/tick rate 5): видео у цели — лишь пара десятков тиков.
     */
    private void planOnboard() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0.5 150 0.5 0 20");
            // дерево команд мода на месте: справка приходит в чат (и в лог клиента)
            cmd("airstrike help");
        });
        at(200, () -> {
            ua.zentix.airstrike.AirstrikeConfig.SERVER.launchNearPlayer.set(true);
            ua.zentix.airstrike.AirstrikeConfig.SERVER.missileFlightTime.set(15);
            cmd("fill -3 149 -3 3 149 3 minecraft:smooth_stone");
            cmd("fill 37 145 -3 43 152 3 minecraft:glass");
            cmd("fill 38 146 -2 42 152 2 minecraft:water");
            cmd("tp @s 0.5 150 0.5 0 20");
            onboard = "dry";
        });
    }

    private void onboardEvents() {
        Minecraft mc = Minecraft.getInstance();
        if (onboardFired < 0) {
            if (tick < 240 || tick % 20 != 0) return;
            boolean dawn = "dawn".equals(onboard);
            double x = mc.player.getX() + (dawn ? 90 : 0), z = mc.player.getZ() + (dawn ? 0 : 90);
            int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            cmd(String.format(java.util.Locale.ROOT, "airstrike missile at %.1f %d %.1f", x, y, z));
            cmd("tick rate 5");
            onboardFired = tick;
            onboardFrames = 0;
            return;
        }
        if (!ua.zentix.airstrike.client.cam.ProjectileCamera.isActive()) {
            if (tick - onboardFired < 200 && tick % 5 == 0) ua.zentix.airstrike.client.cam.ProjectileCamera.cycle();
        }
        if (ua.zentix.airstrike.client.cam.ProjectileCamera.isViewing() && tick % 2 == 0) {
            var camera = mc.gameRenderer.getMainCamera();
            Airstrike.LOG.info("SCENARIO onboard {} frame={} camera={} fluid={} player={} underwater={} eyeLight={} lens={}", onboard, onboardFrames,
                    xyz(camera.getPosition()), camera.getFluidInCamera(), xyz(mc.player.position()), mc.player.isUnderWater(),
                    mc.level.getMaxLocalRawBrightness(mc.getCameraEntity().blockPosition()), lensMax);
            lensMax = 0;
            Screenshot.grab(mc.gameDirectory, String.format("onboard-%s_%04d.png", onboard, tick), mc.getMainRenderTarget(), c -> {});
            onboardFrames++;
        }
        boolean over = tick - onboardFired > 60 && ua.zentix.airstrike.client.hud.ClientFlights.all().isEmpty()
                && !ua.zentix.airstrike.client.cam.ProjectileCamera.isActive();
        if (!over && tick - onboardFired < 1500) return;
        Airstrike.LOG.info("SCENARIO onboard {} done: {} frames", onboard, onboardFrames);
        cmd("tick rate 20");
        if ("dry".equals(onboard)) {
            onboard = "wet";
            onboardFired = -1;
            cmd("tp @s 40.5 146 0.5 0 20");
        } else if ("wet".equals(onboard)) {
            onboard = "dawn";
            onboardFired = -1;
            cmd("time set 1000");
            cmd("tp @s 0.5 150 0.5 0 20");
        } else {
            onboard = null;
            Airstrike.LOG.info("SCENARIO done");
            mc.stop();
        }
    }

    /**
     * РСЗО: залп из 12 по площади 12 блоков в 200 блоках впереди. Сначала камера снаряда — план у пакета на всю
     * очередь; второй залп — глазами стреляющего: дуги дымных следов над головой и разрывы у цели.
     */
    private void planRocket() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
            cmd("forceload add -32 -48 32 240");
        });
        at(200, () -> {
            ua.zentix.airstrike.AirstrikeConfig.SERVER.launchNearPlayer.set(true);
            cmd("fill -30 199 -40 30 199 230 minecraft:smooth_stone");
            cmd("fill -3 200 197 3 203 203 minecraft:oak_planks");
            cmd("tp @s 0.5 200 0.5 0 5");
            target = new Vec3(0.5, 204, 200.5);
        });
        at(230, () -> cmd(String.format(java.util.Locale.ROOT, "airstrike salvo rocket 12 12 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        // сервер под llvmpipe отстаёт: снаряда в списке может ещё не быть — пробуем ещё раз
        for (int t = 236; t <= 266; t += 10) {
            at(t, () -> {
                if (!ua.zentix.airstrike.client.cam.ProjectileCamera.isActive()) ua.zentix.airstrike.client.cam.ProjectileCamera.cycle();
            });
        }
        for (int t = 240; t <= 640; t += 6) shot(t, "rocket_cam");
        at(650, () -> {
            ua.zentix.airstrike.client.cam.ProjectileCamera.exit();
            // стоим сбоку от линии огня, смотрим на цель: видно и пакет, и дуги, и разрывы
            cmd("tp @s 24.5 200 50.5 12 -14");
        });
        at(1500, () -> cmd(String.format(java.util.Locale.ROOT, "airstrike salvo rocket 12 12 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        for (int t = 1510; t <= 1900; t += 8) shot(t, "rocket_view");
        at(1910, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Барражирующие: рой из трёх с катапульты по цели в 150 блоках (барраж 8 с). Камера первого: катапульта,
     * подлёт, круг — «оператор» смотрит на цель; потом со стороны: круги над целью и пике по очереди.
     */
    private void planLoiter() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
            cmd("forceload add -48 -48 48 240");
        });
        at(200, () -> {
            ua.zentix.airstrike.AirstrikeConfig.SERVER.launchNearPlayer.set(true);
            ua.zentix.airstrike.AirstrikeConfig.SERVER.loiterTime.set(8);
            cmd("fill -46 199 -40 46 199 230 minecraft:smooth_stone");
            cmd("fill -3 200 147 3 203 153 minecraft:oak_planks");
            cmd("tp @s 0.5 200 0.5 0 5");
            target = new Vec3(0.5, 204, 150.5);
        });
        at(230, () -> cmd(String.format(java.util.Locale.ROOT, "airstrike salvo loiter 3 6 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        for (int t = 236; t <= 266; t += 10) {
            at(t, () -> {
                if (!ua.zentix.airstrike.client.cam.ProjectileCamera.isActive()) ua.zentix.airstrike.client.cam.ProjectileCamera.cycle();
            });
        }
        for (int t = 240; t <= 700; t += 6) {
            at(t, () -> {
                // оператор на круге смотрит на цель
                Minecraft mc = Minecraft.getInstance();
                if (mc.getCameraEntity() instanceof StrikeProjectile p && p.flightPhase() == ua.zentix.airstrike.entity.FlightPhase.LOITER) {
                    Vec3 d = target.subtract(mc.gameRenderer.getMainCamera().getPosition());
                    mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                    mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                }
            });
            shot(t, "loiter_cam");
        }
        at(710, () -> {
            ua.zentix.airstrike.client.cam.ProjectileCamera.exit();
            cmd("tp @s 40.5 200 95.5 38 -32");
        });
        at(720, () -> cmd(String.format(java.util.Locale.ROOT, "airstrike salvo loiter 3 6 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        for (int t = 730; t <= 1400; t += 8) shot(t, "loiter_view");
        at(1410, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Эффекты за стеной: зритель в 40 блоках перед каменной стеной 60×14, за ней в 90 блоках — большие залпы РСЗО
     * и ракет. Над стеной видно дым, за стеной — ничего. Потом камера водит взглядом влево-вправо: клубы у края
     * кадра не должны пропадать. В лог — сколько частиц в движке (по слоям).
     */
    private void planOcclusion() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("forceload add -64 -16 64 256");
            Minecraft.getInstance().options.hideGui = true;
            Minecraft.getInstance().player.getAbilities().flying = true;
        });
        // площадка выше любого рельефа (fill — не больше 32768 блоков за раз): зритель и цель на одной плоскости
        at(200, () -> {
            cmd("fill -60 259 0 60 259 250 minecraft:smooth_stone");
            cmd("fill -30 260 80 30 273 81 minecraft:stone_bricks");
            cmd("tp @s 0.5 261 40.5 0 -8");
            target = new Vec3(0.5, 260, 170.5);
        });
        at(210, this::occlusionWhenReady);
    }

    /** Сервер под llvmpipe отстаёт: залпы — когда зритель уже на площадке и стена построена. */
    private void occlusionWhenReady() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player.getY() < 259 || !mc.level.getBlockState(new net.minecraft.core.BlockPos(0, 265, 80)).is(net.minecraft.world.level.block.Blocks.STONE_BRICKS)) {
            at(tick + 5, this::occlusionWhenReady);
            return;
        }
        int t0 = tick;
        Airstrike.LOG.info("SCENARIO occlusion ready at tick {}", t0);
        at(t0 + 1, () -> cmd(String.format(java.util.Locale.ROOT, "airstrike salvo rocket 40 20 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        at(t0 + 30, () -> cmd(String.format(java.util.Locale.ROOT, "airstrike salvo missile 6 16 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        for (int dt = 10; dt <= 1100; dt += 6) {
            int t = t0 + dt, d = dt;
            at(t, () -> {
                // с 550-го тика после залпа — водим взглядом: ±35° за 3 с
                if (d >= 550) {
                    float yaw = (float) (35 * Math.sin((d - 550) / 60.0 * Math.PI));
                    cmd(String.format(java.util.Locale.ROOT, "tp @s 0.5 261 40.5 %.1f -8", yaw));
                }
                Airstrike.LOG.info("SCENARIO particles t={} {}", d, particleLayers());
            });
            shot(t, "occlusion");
        }
        at(t0 + 1110, () -> {
            Airstrike.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    private void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent e) {
        if (onboard != null && e.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES
                && ua.zentix.airstrike.client.cam.ProjectileCamera.isViewing()) {
            lensMax = Math.max(lensMax, particlesAtLens(e.getCamera()));
        }
    }

    /** Частицы перед объективом: ближе 12 блоков и в конусе 40° вокруг взгляда — такие закрывают собой весь кадр. */
    private static int particlesAtLens(net.minecraft.client.Camera camera) {
        try {
            var f = net.minecraft.client.particle.ParticleEngine.class.getDeclaredField("particles");
            f.setAccessible(true);
            var map = (java.util.Map<?, ?>) f.get(Minecraft.getInstance().particleEngine);
            var eye = camera.getPosition();
            var look = new net.minecraft.world.phys.Vec3(camera.getLookVector());
            int n = 0;
            for (var q : map.values()) {
                for (var o : (java.util.Collection<?>) q) {
                    var d = ((net.minecraft.client.particle.Particle) o).getBoundingBox().getCenter().subtract(eye);
                    double len = d.length();
                    if (len < 12 && d.dot(look) > len * 0.766) n++;
                }
            }
            return n;
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /**
     * Частицы по слоям движка (очередь слоя — не больше 16384, лишние вытесняют самые старые) и по группам эффектов:
     * {@code cloud=счётчик/живых} — расходятся, если кто-то убирает частицы мимо счётчика движка.
     */
    private static String particleLayers() {
        try {
            var f = net.minecraft.client.particle.ParticleEngine.class.getDeclaredField("particles");
            f.setAccessible(true);
            var map = (java.util.Map<?, ?>) f.get(Minecraft.getInstance().particleEngine);
            StringBuilder sb = new StringBuilder();
            map.forEach((type, q) -> sb.append(type).append('=').append(((java.util.Collection<?>) q).size()).append(' '));
            // группы эффектов: счётчик движка (по нему предел и прореживание) и сколько частиц группы живёт на деле
            var group = ua.zentix.airstrike.client.fx.particle.FxBudget.class.getDeclaredField("group");
            group.setAccessible(true);
            var tracked = net.minecraft.client.particle.ParticleEngine.class.getDeclaredField("trackedParticleCounts");
            tracked.setAccessible(true);
            var counts = (it.unimi.dsi.fastutil.objects.Object2IntMap<?>) tracked.get(Minecraft.getInstance().particleEngine);
            java.util.Map<Object, Integer> live = new java.util.HashMap<>();
            map.values().forEach(q -> ((java.util.Collection<?>) q).forEach(o -> ((net.minecraft.client.particle.Particle) o)
                    .getParticleGroup().ifPresent(g -> live.merge(g, 1, Integer::sum))));
            for (var b : ua.zentix.airstrike.client.fx.particle.FxBudget.values()) {
                var g = ((java.util.Optional<?>) group.get(b)).orElseThrow();
                sb.append(b.name().toLowerCase(java.util.Locale.ROOT)).append('=').append(counts.getInt(g)).append('/')
                        .append(live.getOrDefault(g, 0)).append(' ');
            }
            return sb.toString();
        } catch (ReflectiveOperationException e) {
            return e.toString();
        }
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

    /** Снаряды на сервере: в мире и вне его, где, с какой скоростью, тикает ли их чанк. */
    private static void dumpFlights() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> {
            var level = server.overworld();
            java.util.List<StrikeProjectile> all = new ArrayList<>(level.getEntitiesOfClass(StrikeProjectile.class,
                    new net.minecraft.world.phys.AABB(-30_000_000, -1000, -30_000_000, 30_000_000, 1000, 30_000_000)));
            all.addAll(ua.zentix.airstrike.strike.VirtualFlights.get(level).flights());
            for (StrikeProjectile p : all) {
                var bp = p.blockPosition();
                Airstrike.LOG.info("SCENARIO dump {} virtual={} phase={} pos={} speed={} age={} ticking={} loaded={}",
                        p.weapon(), p.isVirtual(), p.flightPhase(), bp.toShortString(), String.format(java.util.Locale.ROOT, "%.2f", p.speed()), p.age(),
                        level.isPositionEntityTicking(bp), level.getChunkSource().getChunkNow(bp.getX() >> 4, bp.getZ() >> 4) != null);
            }
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
            sb.append(String.format(java.util.Locale.ROOT, " %s#%d %s eta=%.0fs", f.weapon().getSerializedName(), f.number,
                    f.phase().getSerializedName(), f.etaSeconds(0)));
            if (f.entity() != null) sb.append(" at ").append(xyz(f.entity().position()));
            sb.append(';');
        }
        var cam = Minecraft.getInstance().getCameraEntity();
        Airstrike.LOG.info("SCENARIO flights{} camera={} filming={} eye={}", sb, ua.zentix.airstrike.client.cam.ProjectileCamera.isViewing(),
                ua.zentix.airstrike.client.cam.ProjectileCamera.isFilming(), cam == null ? "-" : xyz(cam.position()));
    }

    private static String xyz(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "%.0f %.0f %.0f", v.x, v.y, v.z);
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
