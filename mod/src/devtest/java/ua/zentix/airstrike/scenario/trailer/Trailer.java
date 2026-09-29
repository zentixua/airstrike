package ua.zentix.airstrike.scenario.trailer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.client.aim.Designator;
import ua.zentix.airstrike.client.cam.ProjectileCamera;
import ua.zentix.airstrike.client.nuclear.ClientNuclear;
import ua.zentix.airstrike.client.screen.RemoteScreen;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.BunkerBusterEntity;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.entity.DroneEntity;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.IcbmEntity;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.LoiterEntity;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.scenario.trailer.CineCamera.Path;
import ua.zentix.airstrike.scenario.trailer.CineCamera.Pose;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Трейлер мода, снятый в игре на копии карты города Greenfield (v0.5.4): один день внешнего удара по городу.
 * Холодное начало — шахед у самого объектива; рассвет над центром; наводчик на холмах западной окраины в ~4 км
 * (бинокль, удар по точке на карте), пуски шахедов, «Ланцета», ракеты и «Града», удары по башням с застывшим
 * взрывом; B-2 на закате; ночные залпы; сирена, МБР из-за 8 км, вспышка превращает ночь в день, ударная волна
 * приходит в три места по очереди; серое утро, чёрный дождь и счётчик Гейгера.
 * <p>
 * Каждый план: камера встаёт на место и ждёт, пока прогрузится мир (мир при этом стоит), потом — ждёт свой момент
 * (снаряд подлетел, подрыв), и тогда пишется: игра замедляется под скорость отрисовки ({@link Recorder}), кадры — по
 * времени игры; скорость может меняться по ходу плана, а мир — замирать, пока камера облетает застывший взрыв.
 * Кадры и журнал звуков — в {@code run/scenario/trailer/}, монтаж — {@code tools/trailer/edit.py}. Запуск —
 * {@code tools/trailer/record.sh} (копия карты — {@code AIRSTRIKE_WORLD}).
 */
public final class Trailer {
    private static final Set<ResourceLocation> ALWAYS_LAYERS = Set.of(Airstrike.id("flash"), Airstrike.id("nuke_flash"));

    // Greenfield v0.5.4: места по карте высот и точкам телепорта автора карты (y — земля, уточняется по миру)
    /** Центр города и главная башня (253 блока). */
    private static final Vec3 DOWNTOWN = new Vec3(141.5, 69, -472.5);
    private static final Vec3 TOWER = new Vec3(136.5, 69, -495.5);
    /** Башня к северу от центра (195) — цель холодного начала. */
    private static final Vec3 NORTH_TOWER = new Vec3(20.5, 69, -639.5);
    /** Башня на востоке (228) — цель ракеты, снятой с борта. */
    private static final Vec3 EAST_TOWER = new Vec3(968.5, 69, -487.5);
    /** Башни на юге (226) — цель B-2. */
    private static final Vec3 SOUTH_TOWERS = new Vec3(744.5, 69, 92.5);
    private static final Vec3 STADIUM = new Vec3(-304.5, 69, -846.5);
    private static final Vec3 PORT = new Vec3(-564.5, 69, 770.5);
    /** Холмы западной окраины, напротив аэропорта: пост наводчика в ~4 км от центра. */
    private static final Vec3 HILLS = new Vec3(-3800.5, 75, -500.5);
    /** Откуда уходит МБР: в 8,3 км от центра (карта бьёт до 10 км). */
    private static final Vec3 SILO = new Vec3(-8200.5, 70, -600.5);
    /** Улица в ~900 блоках от эпицентра: сюда ударная волна приходит первой. */
    private static final Vec3 STREET = new Vec3(-760.5, 69, -440.5);
    private static final int NUKE_KT = 40;

    private final Minecraft mc = Minecraft.getInstance();
    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private Recorder rec;
    private boolean started;

    /** Интерфейс мода в кадре (прицел, камера снаряда, счётчик Гейгера); вспышки видны всегда. */
    private boolean hud;
    /** Игрок виден как наводчик; иначе он невидимкой ходит за камерой, чтобы вокруг неё был загружен мир. */
    private boolean actor = true;
    @Nullable
    private Shot recording;
    private float tickRate = 20;
    private long frameClock;
    /** Этот кадр отрисовки — кадр видео (доля тика поставлена под его момент). */
    private boolean frameReady;
    /** Попыток поставить окно в размер кадра ({@link #exactFrame}). */
    private int frameTries;
    private int frameCount;

    // места съёмки (уточняются в начале на сервере)
    private Vec3 post = HILLS;
    private Vec3 silo = SILO;
    /** От центра к посту (на запад) и поперёк. */
    private Vec3 toPost = new Vec3(-1, 0, 0);
    private Vec3 side = new Vec3(0, 0, -1);
    /** Точки на фасадах башен, куда бьют шахед холодного начала и ракета с борта (снаряд входит в стену, а не в крышу). */
    private Vec3 northFacade = NORTH_TOWER;
    /** Середина крыши главной башни (цель с карты). */
    private Vec3 towerTop = TOWER;
    /** Крыша средней высоты у намеченного места удара шахедов (см. {@link #roofNear}). */
    private Vec3 droneRoof = DOWNTOWN.add(90, 0, 60);
    /** Площадка для бетонобойной бомбы у южных башен (см. {@link #plazaNear}). */
    private Vec3 bombPlaza = SOUTH_TOWERS.add(50, 0, -70);
    private Vec3 eastFacade = EAST_TOWER;
    /** Время игры подрыва (-1 — ещё не было). */
    private long detonationTime = -1;
    /** Время, на котором закончился прошлый план: камера между планами стоит там. */
    private double idleTime;
    /** Камера снаряда показывала видео с борта на прошлом тике (переход с карты — отметка «video» для монтажа). */
    private boolean wasViewing, cameraWasActive, closeMarked;
    /** Прорисовка из options.txt (record.sh): план с борта поднимает свою и потом возвращает эту. */
    private int renderDistance;
    private static final int ONBOARD_RENDER_DISTANCE = 24;
    /** С борта ближе этого к цели земля видна и под шейдерами: монтаж берёт видео с отметки «close». */
    private static final double CLOSE_RANGE = 150;
    /** Снаряды в кадре на прошлом тике: тип и где были (пропал — взрыв: отметка для монтажа и толчок камеры). */
    private java.util.Map<Integer, Seen> seen = java.util.Map.of();
    private final java.util.Set<Integer> released = new java.util.HashSet<>();
    /** Где наводчик стоит под чёрным дождём (выбирается, пока он невидимкой прогружает место). */
    private Vec3 falloutSpot = Vec3.ZERO;

    private record Seen(String type, Class<?> cls, Vec3 pos) {}

    /** Истребители: 10 блоков за тик (200 м/с), план — 150 тиков (7,5 с); сколько крена берёт камера (знак проверен кадрами). */
    private static final double FIGHTER_SPEED = 10;
    private static final int FIGHTER_TICKS = 150;
    private static final float FIGHTER_CAMERA_FALL = 0.6f;

    /** Планы, не прошедшие проверку кадров ({@link ShotCheck}). */
    private final List<String> failedChecks = new ArrayList<>();

    /** Пара истребителей плана fighters. */
    private final Fighters fighters = new Fighters();

    /** Когда пропал последний снаряд каждого типа (время игры). */
    private final java.util.Map<Class<?>, Long> goneAt = new java.util.HashMap<>();

    private interface Step {
        /** @return шаг закончен */
        boolean tick();
    }

    public Trailer() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::beforeFrame);
        NeoForge.EVENT_BUS.addListener(this::afterFrame);
        NeoForge.EVENT_BUS.addListener(CineCamera::angles);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, CineCamera::fov);
        NeoForge.EVENT_BUS.addListener(this::layer);
        NeoForge.EVENT_BUS.addListener(this::hideActor);
        NeoForge.EVENT_BUS.addListener(this::hideHand);
        NeoForge.EVENT_BUS.addListener(this::hideName);
        NeoForge.EVENT_BUS.addListener(this::hideWallOverlay);
        NeoForge.EVENT_BUS.addListener(fighters::fly);
        NeoForge.EVENT_BUS.addListener(fighters::fx);
        NeoForge.EVENT_BUS.addListener(fighters::render);
        script();
    }

    // ================================================================ сценарий

    private void script() {
        then(this::exactFrame);
        run(() -> {
            cmd("gamerule doDaylightCycle false");
            cmd("gamerule doWeatherCycle false");
            cmd("gamerule doMobSpawning false");
            // автосохранение: надпись «Saving world» в кадре и остановки сервера на копии, которую потом выбросят
            // (команда save-off — уровня 4, игроку одиночной игры её нет: выключаем прямо на сервере)
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> server.getAllLevels().forEach(l -> l.noSave = true));
            cmd("weather clear");
            cmd("time set 6000");
            var c = AirstrikeConfig.SERVER;
            c.droneFlightTime.set(12);
            c.missileFlightTime.set(8);
            c.bomberFlightTime.set(20);
            c.nukeFlightTime.set(600);
            c.loiterTime.set(8);
            c.siren.set(false);
        });
        onServer(this::findLocations);
        waitTicks(5);

        part("coldOpen", this::coldOpen);
        part("dawn", this::dawn);
        part("operator", this::operator);
        part("dayStrikes", this::dayStrikes);
        part("fighters", this::fighters);
        part("onboard", this::onboard);
        part("dusk", this::dusk);
        part("night", this::night);
        part("nuke", this::nuke);
        part("morning", this::morning);

        run(() -> {
            rec.finish();
            if (failedChecks.isEmpty()) Airstrike.LOG.info("TRAILER проверки кадров: все планы прошли");
            else Airstrike.LOG.error("TRAILER проверки кадров: ПРОВАЛ — {}", String.join(", ", failedChecks));
            Airstrike.LOG.info("TRAILER done");
            // съёмочный мир одноразовый, а его сохранение после дальних перелётов (8 км) идёт минутами — выходим сразу
            Runtime.getRuntime().halt(0);
        });
    }

    /**
     * Раздел сценария; AIRSTRIKE_TRAILER_PARTS=fighters,onboard — только эти разделы (проверка одного плана без
     * часа прочих; разделы ставят время суток и места сами, но руины ждут подрыва из раздела nuke).
     */
    private static void part(String name, Runnable section) {
        String only = System.getenv("AIRSTRIKE_TRAILER_PARTS");
        if (only == null || only.isBlank() || java.util.Arrays.asList(only.split(",")).contains(name)) section.run();
    }

    /** Холодное начало: шахед заходит на башню и проносится в паре метров от объектива (дальше — затемнение). */
    private void coldOpen() {
        fromAfar(true);
        // невидимка ближе к стене: шахед должен попасть в дальность слежения клиента (не дальше прорисовки)
        run(() -> placeHidden(northFacade.add(-90, 20, 0), northFacade));
        waitTicks(100);
        run(() -> fire("drone", northFacade));
        shot("cold_open").after(() -> nearest(DroneEntity.class, northFacade, 130) != null, 3000).noPrep().length(70).hidden()
                .speed(1, slowNear(DroneEntity.class, () -> CineCamera.pose() == null ? northFacade : CineCamera.pose().pos(), 14, 0.3))
                .shake(0.12)
                .camera(() -> pastLens(nearest(DroneEntity.class, northFacade, 130), 26, 3.2, 1.2, 52))
                .endWhen(() -> nearest(DroneEntity.class, northFacade, 400) == null, 6);
    }

    /** Рассвет: пролёт над центром и башня против встающего солнца (длинный фокус). */
    private void dawn() {
        run(() -> cmd("time set 23300"));
        shot("dawn_city").length(170).hidden().farView().shake(0.05).camera(() -> CineCamera.spline(true,
                CineCamera.Key.at(0, new Vec3(-560, 300, -300), TOWER.add(0, 140, 0), 50),
                CineCamera.Key.at(85, new Vec3(-160, 285, -400), TOWER.add(0, 170, 0), 46),
                CineCamera.Key.at(170, new Vec3(190, 290, -650), EAST_TOWER.add(0, 120, 0), 44)));
        run(() -> cmd("time set 23500"));
        // сверху вниз, на фоне города, а не неба: в небе за башней висят два самолёта карты Greenfield (постройки),
        // и в дубле 1 они читались как зависшие; верх кадра — ниже горизонта. В пределах прорисовки съёмки (24 чанка)
        shot("dawn_tower").length(130).hidden().farView().shake(0.04).camera(() -> CineCamera.spline(true,
                CineCamera.Key.at(0, new Vec3(-190, 330, -470), TOWER.add(0, 140, 0), 34),
                CineCamera.Key.at(130, new Vec3(-150, 320, -486), TOWER.add(0, 145, 0), 28)))
                .subject(() -> TOWER.add(0, 140, 0), 40, 0.15);
    }

    /** Наводчик на холмах: со спины на фоне города, бинокль, место удара на карте — пуск ракеты. */
    /** Сколько тиков клиента карта уже получает рельеф Distant Horizons (план «map»). */
    private final int[] mapTilesFor = {0};

    private void operator() {
        fromAfar(false);
        // почти полдень: наводчик смотрит на восток, в город, и с утра низкое солнце заливало кадр и бинокль
        // (на восходе — прямо в объектив, в 7:30 — белая засветка горизонта под шейдерами)
        run(() -> cmd("time set 5000"));
        standAt(() -> post, () -> TOWER);
        run(() -> cmd("give @s airstrike:strike_designator[airstrike:loadout={weapon:\"missile\",count:1,spread:0}]"));
        waitTicks(40);
        // через плечо: голова и плечо наводчика слева внизу, город (рельеф DH за 4 км) — посередине; наезд
        shot("operator").length(120).farView().shake(0.1).camera(() -> {
            // от ног самого наводчика: он стоит не ровно на отметке поста (в прошлом облёте голова была у края кадра)
            Vec3 feet = mc.player.position();
            Vec3 city = TOWER.add(0, 110, 0);
            // в 2,8 блока голова закрывала пятую часть кадра: дальше и выше, плечо — в нижнем левом углу
            // и не ниже 2 блоков над землёй под самой камерой: наводчик стоит в ямке или склон за ним выше ног —
            // камера от ног уходила в землю (дубль 1 на ноутбуке, облачная проверка)
            Vec3 from = overGround(feet.add(toPost.scale(4.6)).add(side.scale(-1.7)).add(0, 2.35, 0), 2.0);
            Vec3 to = overGround(feet.add(toPost.scale(3.4)).add(side.scale(-1.25)).add(0, 2.15, 0), 2.0);
            BlockPos under = BlockPos.containing(feet).below();
            Airstrike.LOG.info("TRAILER operator: ноги {} (пост {}), под ногами {}, камера {} (земля там {})", feet, post,
                    mc.level.getBlockState(under), from, ground(from).y);
            return CineCamera.spline(true, CineCamera.Key.at(0, from, city, 38), CineCamera.Key.at(120, to, city, 33));
        }).subject(() -> mc.player, 1.8, 0.06).cue(20, () -> mc.options.keyUse.setDown(true));
        shot("scope").length(80).hud().player(t -> {
            Vec3 eye = post.add(0, 1.62, 0);
            float yaw = yawTo(post, TOWER), pitch = pitchTo(eye, TOWER.add(0, 150, 0));
            double s = CineCamera.smooth(t / 60);
            return new Pose(Vec3.ZERO, yaw - 9 * (float) (1 - s), pitch - 2 * (float) (1 - s), 0, 70);
        }).cue(0, () -> mc.options.keyUse.setDown(true)).cueEnd(() -> mc.options.keyUse.setDown(false));
        // карта: открывается на главной башне, отъезжает на весь центр; Enter — пуск
        // карта открывается ещё при стоящем мире: плитки рельефа Distant Horizons читаются в его фоне (пока он грузит
        // свои LOD — десятки секунд), запись — когда они пришли
        shot("map").length(100).hud().player(t -> new Pose(Vec3.ZERO, yawTo(post, TOWER), 10, 0, 70))
                .prepare(() -> {
                    ua.zentix.airstrike.client.screen.MapScreen.reset();
                    ua.zentix.airstrike.client.map.MapTarget.set(mc.level, new ua.zentix.airstrike.client.map.MapTarget.Place(towerTop.x, towerTop.z));
                    mc.setScreen(new ua.zentix.airstrike.client.screen.MapScreen(new RemoteScreen()));
                })
                // первая плитка DH — ещё не карта: после неё ещё 10 с на остальные (без DH ждать нечего)
                .when(() -> !ua.zentix.airstrike.client.map.TerrainTiles.farTerrain()
                        || !ua.zentix.airstrike.client.map.TerrainTiles.farPending() && ++mapTilesFor[0] > 200, 2400)
                // Enter — после последнего кадра: экран карты закрывается, и конец плана был уже от первого лица
                .cueEnd(() -> {
                    if (mc.screen != null) mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
                    mc.setScreen(null);
                });
        // пусковая у поста: сход ракеты, уход над камерой
        shot("launch_missile").after(() -> launcher(WeaponType.MISSILE) != null, 200).noPrep().length(150).shake(0.15).camera(() -> {
            LauncherEntity l = launcher(WeaponType.MISSILE);
            Vec3 at = l.position();
            Vec3 fwd = Vec3.directionFromRotation(0, l.getYRot());
            Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
            Vec3 from = ground(at.add(fwd.scale(12)).add(right.scale(-7))).add(0, 2.2, 0);
            return CineCamera.track(from, smoothFocus(() -> nearest(CruiseMissileEntity.class, at, 200), at.add(0, 2.5, 0), 0.35), 66);
        });
        // ракета в крышу главной башни: с соседней крыши, на подлёте замедление, взрыв замирает, камера его облетает
        Supplier<Vec3> roof = () -> towerTop;
        shot("missile_tower").hidden().length(200).shake(0.08)
                .speed(1, slowNear(CruiseMissileEntity.class, roof, 220, 0.2))
                .bulletTime(CruiseMissileEntity.class, roof, 170, 0.35)
                .camera(() -> {
                    Vec3 top = roof.get();
                    Vec3 from = top.add(toPost.scale(120)).add(side.scale(90)).add(0, -25, 0);
                    return bulletTimeCamera(from, smoothFocus(() -> nearest(CruiseMissileEntity.class, top, 900), top, 0.45), 38, 70, 40);
                })
                .when(() -> nearest(CruiseMissileEntity.class, roof.get(), 900) != null, 3000)
                .endWhen(() -> nearest(CruiseMissileEntity.class, roof.get(), 900) == null, 60)
                // камера ведёт ракету по небу, крыша в кадре — только под конец: цель — ракета, после взрыва — крыша
                .subjectAnyway(() -> {
                    Entity m = nearest(CruiseMissileEntity.class, roof.get(), 900);
                    return m != null ? m : roof.get();
                }, 20, 0.003);
    }

    /** День: шахеды, «Ланцет» и «Град» с поста, удары по городу. */
    private void dayStrikes() {
        run(() -> cmd("time set 3000"));
        standAt(() -> post, () -> TOWER);
        waitTicks(40);
        // три шахеда в одну крышу: с разбросом 40 взрыв облёта уходил в соседнюю улицу
        run(() -> fire("salvo drone 3 8", droneRoof));
        shot("launch_drone").after(() -> launcher(WeaponType.DRONE) != null, 200).noPrep().length(190).speed(0.5).shake(0.12).camera(() -> {
            LauncherEntity l = launcher(WeaponType.DRONE);
            Vec3 at = l.position();
            Vec3 fwd = Vec3.directionFromRotation(0, l.getYRot());
            Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
            Vec3 a = ground(at.add(right.scale(11)).add(fwd.scale(-6))).add(0, 1.5, 0);
            Vec3 b = ground(at.add(right.scale(8)).add(fwd.scale(-2))).add(0, 1.9, 0);
            return CineCamera.spline(true, CineCamera.Key.at(0, a, at.add(fwd.scale(4)).add(0, 2.6, 0), 64),
                    CineCamera.Key.at(190, b, at.add(fwd.scale(6)).add(0, 4, 0), 58));
        }).endWhen(() -> newest(DroneEntity.class) instanceof DroneEntity d && launcher(WeaponType.DRONE) instanceof LauncherEntity l
                && d.distanceTo(l) > 45, 0);
        // разгон и отделение ускорителя вплотную
        shot("boost").after(() -> newest(DroneEntity.class) != null, 200).noPrep().length(56).speed(0.4).hidden()
                // снизу-сбоку, на фоне неба: у земли за шахедом — плоская равнина с полосами LOD, камера не ниже земли
                // ниже шахеда и вверх, на небо: равнина под ним вдали — LOD Distant Horizons с полосами швов
                .camera(() -> lifted(chaseOf(newest(DroneEntity.class), 7, -3.2, 3.2, 6, 0, 56), 0.8))
                .subject(() -> newest(DroneEntity.class), 3, 0.08);
        // над городом низко, между башнями: невидимка идёт за камерой, шахед остаётся в мире
        shot("drone_city").after(() -> newest(DroneEntity.class) != null, 200).noPrep().length(150).hidden().shake(0.06)
                .camera(() -> chaseOf(newest(DroneEntity.class), 9, 2.5, -4, 24, 8, 56))
                .when(() -> newest(DroneEntity.class) instanceof DroneEntity d && d.position().distanceTo(droneRoof) < 700, 6000);
        Supplier<Vec3> hit = () -> droneRoof;
        shot("impact_drone").hidden().length(200).shake(0.08)
                .speed(1, slowNear(DroneEntity.class, hit, 70, 0.3))
                .bulletTime(DroneEntity.class, hit, 150, 0.3)
                .camera(() -> {
                    Vec3 at = hit.get();
                    // место с чистым видом на попадание и на всю дугу облёта (в центре камера упиралась в стены)
                    View v = openView(at, new double[]{75, 95, 120, 150}, new double[]{18, 30, 45, 60, 80}, side.scale(-70).add(toPost.scale(-40)), -80, 40);
                    return bulletTimeCamera(v.from(), smoothFocus(() -> nearest(DroneEntity.class, at, 400), at.add(0, 3, 0), 0.3), 50, v.arc(), v.radius(), at);
                })
                .when(() -> nearest(DroneEntity.class, hit.get(), 260) != null, 3000)
                .endWhen(() -> nearest(DroneEntity.class, hit.get(), 600) == null, 80)
                // до попадания камера ведёт шахед по небу: цель — он, после взрыва — место удара
                .subject(() -> {
                    Entity d = nearest(DroneEntity.class, hit.get(), 400);
                    return d != null ? d : hit.get().add(0, 3, 0);
                }, 10, 0.003);

        // «Ланцет»: катапульта у поста, круг над стадионом, пике
        standAt(() -> post, () -> TOWER);
        run(() -> cmd("kill @e[type=airstrike:launcher]"));
        waitTicks(40);
        run(() -> fire("loiter", ground(STADIUM)));
        shot("loiter_launch").after(() -> launcher(WeaponType.LOITER) != null, 200).noPrep().length(160).speed(0.5).shake(0.12).camera(() -> {
            LauncherEntity l = launcher(WeaponType.LOITER);
            Vec3 at = l.position();
            Vec3 fwd = Vec3.directionFromRotation(0, l.getYRot());
            Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
            Vec3 from = ground(at.add(right.scale(8)).add(fwd.scale(2))).add(0, 1.9, 0);
            return CineCamera.track(from, smoothFocus(() -> newest(LoiterEntity.class), at.add(fwd.scale(3)).add(0, 2, 0), 0.35), 62);
        }).endWhen(() -> newest(LoiterEntity.class) instanceof LoiterEntity e && launcher(WeaponType.LOITER) instanceof LauncherEntity l
                && e.distanceTo(l) > 40, 0);
        shot("loiter_strike").after(() -> newest(LoiterEntity.class) != null, 200).noPrep().length(260).speed(0.8).hidden()
                .camera(() -> chaseOf(newest(LoiterEntity.class), 5.5, 1.8, 2.2, 8, 0, 60))
                .when(() -> nearest(LoiterEntity.class, STADIUM, 200) instanceof LoiterEntity e && e.flightPhase() == FlightPhase.LOITER, 8000)
                .endWhen(() -> nearest(LoiterEntity.class, STADIUM, 400) == null, 50);

        // «Град»: пакет из 40 труб у поста, залп очередью по стадиону
        standAt(() -> post, () -> TOWER);
        waitTicks(40);
        run(() -> fire("salvo rocket 40 60", ground(STADIUM)));
        shot("rocket_launch").after(() -> launcher(WeaponType.ROCKET) != null, 200).noPrep().length(170).speed(0.7).shake(0.2).camera(() -> {
            LauncherEntity l = launcher(WeaponType.ROCKET);
            Vec3 at = l.position();
            Vec3 fwd = Vec3.directionFromRotation(0, l.getYRot());
            Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
            Vec3 a = ground(at.add(right.scale(9)).add(fwd.scale(5))).add(0, 2.2, 0);
            Vec3 b = ground(at.add(right.scale(8)).add(fwd.scale(8))).add(0, 2.0, 0);
            return CineCamera.dolly(a, b, 170, () -> at.add(fwd.scale(3)).add(0, 2.2, 0), 72);
        });
        shot("rocket_impact").hidden().length(220).speed(0.6).shake(0.08).camera(() -> {
            Vec3 t = ground(STADIUM);
            Vec3 a = ground(t.add(side.scale(160)).add(toPost.scale(-60))).add(0, 60, 0);
            Vec3 b = ground(t.add(side.scale(130)).add(toPost.scale(-40))).add(0, 52, 0);
            return CineCamera.spline(true, CineCamera.Key.at(0, a, t.add(0, 10, 0), 50), CineCamera.Key.at(220, b, t, 46));
        }).when(() -> nearest(RocketEntity.class, STADIUM, 250) != null, 6000)
                .endWhen(() -> nearest(RocketEntity.class, STADIUM, 1200) == null, 80);
    }

    /**
     * Истребители из аэропорта (он на востоке) идут на запад навстречу крылатой ракете: пара на форсаже в вираже
     * с креном над городом, камера в строю. Истребители — аппараты Sable из блоков ({@link Aircraft}), путь —
     * координированный вираж на 200 м/с ({@link FlightPath}), факел и след — {@link Fighters}.
     */
    private void fighters() {
        fromAfar(true);
        Supplier<Vec3> roofCam = () -> ground(EAST_TOWER.add(-330, 0, 130)).add(0, 6, 0);
        FlightPath[] route = new FlightPath[1];
        run(() -> {
            Vec3 c = roofCam.get();
            // конец — над крышей к западу, курсом на запад; до него левый вираж с креном 70°
            route[0] = FlightPath.turn(c.add(-600, 45, -10), 90, FIGHTER_SPEED, FIGHTER_TICKS, -70, 20, 125, 18);
            placeHidden(route[0].start().add(-25, 6, 0), EAST_TOWER);
            // аппарат Sable, вошедший в чанк без тика, выгружается: коридор пролёта держим загруженным
            Vec3[] pts = route[0].points();
            for (int i = 0; i < pts.length; i += 8) forceload(pts[i], 48);
        });
        shot("fighters").hidden().length(FIGHTER_TICKS).shake(0.08)
                .onReady(() -> {
                    fire("missile", eastFacade);
                    fighters.scramble(mc.getSingleplayerServer(), route[0], 24);
                })
                .cue(0, fighters::go)
                // в строю: камера по позе ведущего, какой её рисует клиент (плавно между тиками)
                .camera(() -> t -> {
                    Pose p = fighters.camera(t / FIGHTER_TICKS, FIGHTER_CAMERA_FALL);
                    Vec3 s = route[0] == null ? roofCam.get() : route[0].start();
                    return p != null ? p : Pose.look(s.add(-25, 6, 0), s, 0, 55);
                })
                .when(() -> missileRange() < 1100 && fighters.visible(), 6000)
                .subject(fighters::leadOnScreen, 16, 0.05)
                .cueEnd(() -> {
                    fighters.land(mc.getSingleplayerServer());
                    cmd("forceload remove all");
                });
    }

    /**
     * Глазами ракеты: удар по восточной башне в стену, наводчик рядом с городом (видео с борта — только в дальности
     * симуляции). Снимается в полдень: утром лучи шейдерпака заливают кадр с борта белой пеленой.
     */
    private void onboard() {
        // ракеты «Града» прошлых планов, не долетевшие до цели, ещё в списке полётов: камера снаряда выбирала их,
        // а конец плана (список пуст) не наступал
        run(() -> cmd("airstrike clear"));
        run(() -> cmd("time set 6000"));
        // пусковая у наводчика, в городе: ракета, пущенная издалека, возвращалась в мир клиента (зона видео — 256 блоков
        // от наводчика) лишь у самой цели и на кадр не успевала; своя ракета с соседней улицы в мире с пуска
        fromAfar(false);
        // видео с борта — только в 256 блоках от наводчика: в 290 ракета так и дошла до цели на карте
        standAt(() -> ground(eastFacade.add(-150, 0, 70)), () -> EAST_TOWER);
        waitTicks(60);
        run(() -> fire("missile", eastFacade));
        shot("missile_camera").after(() -> !ua.zentix.airstrike.client.hud.ClientFlights.all().isEmpty(), 400)
                .prepare(() -> {
                    renderDistance = mc.options.renderDistance().get();
                    mc.options.renderDistance().set(ONBOARD_RENDER_DISTANCE);
                    if (!ProjectileCamera.isActive()) ProjectileCamera.cycle();
                })
                // весь полёт в обычной скорости, последние 300 блоков до стены — в 5 раз медленнее; конец — после попадания
                .length(1400).speed(1, slowNear(CruiseMissileEntity.class, () -> eastFacade, 300, 0.2))
                .hud().projectileCamera().readyChunks(ONBOARD_RENDER_DISTANCE - 2)
                // запись — с первого кадра видео с борта (в 256 блоках от наводчика): до того на экране карта «нет видео»
                .when(ProjectileCamera::isViewing, 4000)
                // монтаж берёт видео с отметки «close»: она ставится только при видео с борта
                .requires("видео с борта в " + CLOSE_RANGE + " блоках от цели", () -> closeMarked)
                .endWhen(() -> ua.zentix.airstrike.client.hud.ClientFlights.all().isEmpty(), 25)
                .cueEnd(() -> {
                    ProjectileCamera.exit();
                    mc.options.renderDistance().set(renderDistance);
                });
    }

    /** Закат: B-2 — створки отсека и сброс снизу-сзади, потом бомба у южных башен с застывшим подземным взрывом. */
    private void dusk() {
        run(() -> cmd("time set 12400"));
        fromAfar(true);
        Supplier<Vec3> bay = () -> ground(SOUTH_TOWERS.add(-60, 0, 40));
        Supplier<Vec3> bayWatch = () -> bay.get().add(toPost.scale(150)).add(0, 40, 0);
        run(() -> placeHidden(bayWatch.get(), bay.get()));
        shot("bomb_bay").onReady(() -> fire("bunker", bay.get())).length(400).speed(0.25).hidden()
                .camera(() -> CineCamera.chase(() -> nearest(BomberEntity.class, bay.get(), 900), () -> flightNear(WeaponType.BUNKER, bay.get()),
                        bayWatch.get(), toPost.scale(-1), 26, -7, 9, 14, 0, 58))
                // B-2 у клиента появляется поздно (дальность слежения — прорисовка), а телеметрия владельцу идёт всегда
                .when(() -> nearest(BomberEntity.class, bay.get(), 900) instanceof BomberEntity b ? b.flightPhase() != FlightPhase.EGRESS
                        : flightNear(WeaponType.BUNKER, bay.get()) instanceof Vec3 f && f.distanceTo(bay.get()) < 500, 3000)
                .endWhen(() -> nearest(BomberEntity.class, bay.get(), 900) instanceof BomberEntity b && b.flightPhase() == FlightPhase.EGRESS, 16);
        // место найдено заранее (findLocations): ground() после подрыва — дно воронки, и цель проверки уходила под землю
        Supplier<Vec3> pit = () -> bombPlaza;
        // B-2 прошлого плана (bomb_bay) после #126 может уйти на второй заход и сбросить бомбу позже, уже в этом плане:
        // облёт замирал на её взрыве в 160 блоках от площадки (ноутбук, kfcheck4c) — до пуска убираем все полёты
        run(() -> cmd("airstrike clear"));
        Vec3[] bombAt = {null};
        run(() -> placeHidden(pit.get().add(toPost.scale(120)).add(0, 30, 0), pit.get()));
        shot("bomb_impact").onReady(() -> fire("bunker", pit.get())).hidden().length(320).shake(0.08)
                .speed(0.75, slowNear(BunkerBusterEntity.class, pit, 60, 0.3))
                .bulletTime(BunkerBusterEntity.class, pit, 150, 0.3)
                .camera(() -> {
                    Vec3 at = pit.get();
                    // выше крон: с 16 блоков над землёй взрыв закрывала листва соседних деревьев
                    View v = openView(at, new double[]{70, 95, 120, 150}, new double[]{28, 40, 55, 75}, side.scale(-60).add(toPost.scale(50)), 60, 40);
                    return bulletTimeCamera(v.from(), smoothFocus(() -> bomberFocus(at), at.add(0, 8, 0), 0.3), 55, v.arc(), v.radius(), at);
                })
                .when(() -> bomberFocus(pit.get()) != null, 3000)
                .endWhen(() -> sinceGone(BunkerBusterEntity.class) > 100, 0)
                // до сброса и в падении камера ведёт B-2 и бомбу (bomberFocus): цель — то, за чем она следит
                .subject(() -> {
                    BunkerBusterEntity b = nearest(BunkerBusterEntity.class, pit.get(), 600);
                    if (b != null) bombAt[0] = b.position();
                    if (b != null && b.flightPhase() != FlightPhase.DRILL) return b;
                    BomberEntity plane = b == null && bombAt[0] == null ? nearest(BomberEntity.class, pit.get(), 450) : null;
                    // после подрыва — шар над тем местом, где бомба ушла в землю (B-2 кладёт её в стороне от намеченного)
                    Vec3 blast = bombAt[0] != null ? new Vec3(bombAt[0].x, Math.max(bombAt[0].y, pit.get().y) + 6, bombAt[0].z) : pit.get().add(0, 6, 0);
                    return plane != null ? plane : blast;
                }, 12, 0.003);
    }

    /** Ночь: стая над городом, ночной «Град», шквал по центру с высоты. */
    private void night() {
        run(() -> {
            cmd("time set 15000");
            AirstrikeConfig.SERVER.droneFlightTime.set(20);
        });
        fromAfar(true);
        // камеры ночью — в 120–180 блоках от попаданий: дальше прорисовки клиент не видит ни снарядов, ни частиц,
        // а из-за домов на переднем плане не видно улиц
        Vec3 roofView = DOWNTOWN.add(-120, 0, 95);
        run(() -> placeHidden(ground(roofView).add(0, 55, 0), DOWNTOWN));
        shot("swarm_night").onReady(() -> fire("salvo drone 16 120", ground(DOWNTOWN))).hidden().length(320).farView().shake(0.05)
                .camera(() -> {
                    Vec3 from = ground(roofView).add(0, 55, 0);
                    return CineCamera.spline(true, CineCamera.Key.at(0, from, DOWNTOWN.add(0, 50, 0), 44),
                            CineCamera.Key.at(320, from.add(DOWNTOWN.subtract(from).normalize().scale(12)), DOWNTOWN.add(0, 25, 0), 38));
                })
                .when(() -> nearest(DroneEntity.class, DOWNTOWN, 260) != null, 6000)
                .endWhen(() -> nearest(DroneEntity.class, DOWNTOWN, 400) == null, 80);
        Vec3 gradAim = DOWNTOWN.add(260, 0, 180);
        Vec3 gradView = gradAim.add(-70, 0, 150);
        run(() -> placeHidden(ground(gradView).add(0, 70, 0), gradAim));
        shot("grad_night").onReady(() -> fire("salvo rocket 30 90", ground(gradAim))).hidden().length(260).speed(0.7).shake(0.1)
                .camera(() -> CineCamera.track(ground(gradView).add(0, 70, 0), () -> gradAim.add(0, 10, 0), 52))
                .when(() -> nearest(RocketEntity.class, gradAim, 260) != null, 6000)
                .endWhen(() -> nearest(RocketEntity.class, gradAim, 400) == null, 100);
        run(() -> placeHidden(DOWNTOWN.add(0, 120, 0), DOWNTOWN));
        shot("barrage").onReady(() -> {
                    fire("salvo missile 12 160", ground(DOWNTOWN));
                    fire("salvo drone 12 180", ground(DOWNTOWN));
                }).hidden().length(300).farView().shake(0.04)
                .camera(() -> CineCamera.orbit(() -> DOWNTOWN.add(0, 30, 0), 190, 110, 250, 0.12, 50))
                .when(() -> nearest(CruiseMissileEntity.class, DOWNTOWN, 300) != null || nearest(DroneEntity.class, DOWNTOWN, 260) != null, 6000)
                .subjectAnyway(() -> DOWNTOWN.add(0, 30, 0), 150, 0.2);
        blackout();
    }

    /** Подстанция у края центра и камера за ней (план «blackout»); ставятся после шквала — его ракеты её не заденут. */
    private Vec3 substation = DOWNTOWN, substationView = DOWNTOWN, blackoutCore = DOWNTOWN;
    /** С какой стороны центра подстанция и камера: улицы, которые видны и в ночном рое. */
    private static final Vec3 BLACKOUT_SIDE = new Vec3(-120, 0, 95).normalize();
    /** Камера блэкаута: над землёй, от подстанции, угол кадра по вертикали; докуда земля в кадре (меньше 24 чанков). */
    private static final double BLACKOUT_HEIGHT = 150, BLACKOUT_BACK = 120, BLACKOUT_FOV = 44, BLACKOUT_REACH = 340;

    /**
     * Блэкаут: одна крылатая ракета в подстанцию у края центра в полночь, кварталы гаснут от неё вглубь города и дальше
     * в рельефе DH. Общий план с высоты, подстанция в нижней трети, камера почти стоит (медленный наезд).
     */
    private void blackout() {
        run(() -> {
            cmd("airstrike clear");
            cmd("time set 18000");
            cmd("weather clear");
            AirstrikeConfig.SERVER.gridNodeRadius.set(900);
            AirstrikeConfig.SERVER.gridCascadeSpeed.set(130.0);
            AirstrikeConfig.SERVER.gridRestoreMinutes.set(15);
            MinecraftServer server = mc.getSingleplayerServer();
            server.submit(() -> findSubstation(server.overworld())).join();
            Vec3 d = substation.subtract(substationView);
            String facing = net.minecraft.core.Direction.getNearest(d.x, 0, d.z).getOpposite().getSerializedName();
            cmd(String.format(Locale.ROOT, "setblock %d %d %d airstrike:substation[facing=%s]",
                    Mth.floor(substation.x), Mth.floor(substation.y), Mth.floor(substation.z), facing));
            forceload(substation, 48);
            placeHidden(substationView, DOWNTOWN);
        });
        waitTicks(60);
        Supplier<Vec3> sub = () -> substation;
        shot("blackout").onReady(() -> fire("salvo missile 1 0", substation.add(0, 1, 0))).hidden().length(400).farView().shake(0.02)
                .camera(() -> {
                    // сверху, круто вниз: верх кадра упирается в землю в BLACKOUT_REACH блоках — дальше прорисовки рельеф DH,
                    // а его огни пока не гаснут (ноутбук, 29.09); подстанция в нижней трети, самый густой квартал — над центром
                    Vec3 flat = blackoutCore.subtract(substationView).multiply(1, 0, 1).normalize();
                    double h = substationView.y - substation.y;
                    double pitch = Math.toDegrees(Math.atan2(h, BLACKOUT_REACH)) + BLACKOUT_FOV / 2;
                    double reach = h / Math.tan(Math.toRadians(pitch));
                    Vec3 look = new Vec3(substationView.x, substation.y, substationView.z).add(flat.scale(reach));
                    return CineCamera.spline(true, CineCamera.Key.at(0, substationView, look, BLACKOUT_FOV),
                            CineCamera.Key.at(300, substationView.add(flat.scale(8)), look.add(flat.scale(8)), BLACKOUT_FOV - 2));
                })
                .when(() -> nearest(CruiseMissileEntity.class, substation, 350) != null, 4000)
                .subject(() -> sub.get().add(0, 1.5, 0), 8, 0.004);
    }

    /**
     * Где снимать блэкаут: кварталы гаснут только у ламп сети (светокамень, лампы из красного камня, стержни края…),
     * а факелы, свечи и маяки горят дальше; дальше прорисовки (24 чанка) — рельеф DH. Поэтому: плотность ламп сети
     * по чанкам в 24 чанках от центра, самое густое место (окно 5×5 чанков, ~80 блоков) — середина кадра; подстанция —
     * на открытом месте в 70 блоках от него к камере, камера — ещё в BLACKOUT_BACK за ней и в BLACKOUT_HEIGHT над ней,
     * с той стороны, откуда в кадре больше всего ламп.
     */
    private void findSubstation(ServerLevel level) {
        int cx0 = Mth.floor(DOWNTOWN.x) >> 4, cz0 = Mth.floor(DOWNTOWN.z) >> 4, r = 24, n = 2 * r + 1;
        int[][] lamps = new int[n][n];
        long total = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i - r) * (i - r) + (j - r) * (j - r) > r * r) continue;
                var chunk = level.getChunk(cx0 + i - r, cz0 + j - r);
                int c = 0;
                for (var section : chunk.getSections()) {
                    if (section.hasOnlyAir() || !section.getStates().maybeHas(ua.zentix.airstrike.grid.GridLights::isLit)) continue;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (ua.zentix.airstrike.grid.GridLights.isLit(section.getBlockState(x, y, z))) c++;
                            }
                        }
                    }
                }
                lamps[i][j] = c;
                total += c;
            }
        }
        int bi = r, bj = r, best = -1;
        for (int i = 2; i < n - 2; i++) {
            for (int j = 2; j < n - 2; j++) {
                int sum = 0;
                for (int di = -2; di <= 2; di++) for (int dj = -2; dj <= 2; dj++) sum += lamps[i + di][j + dj];
                if (sum > best) {
                    best = sum;
                    bi = i;
                    bj = j;
                }
            }
        }
        Vec3 core = new Vec3(((cx0 + bi - r) << 4) + 8, 0, ((cz0 + bj - r) << 4) + 8);
        core = new Vec3(core.x, height(level, Mth.floor(core.x), Mth.floor(core.z)), core.z);
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < 16; k++) {
            double a = Math.toRadians(22.5 * k);
            Vec3 dir = new Vec3(Math.sin(a), 0, Math.cos(a));
            Vec3 at = openSpot(level, core.add(dir.scale(70)), 30);
            if (at == null) continue;
            Vec3 view = at.add(dir.scale(BLACKOUT_BACK));
            view = new Vec3(view.x, at.y + BLACKOUT_HEIGHT, view.z);
            if (!roomy(level, view, 3) || !sees(level, view, at.add(0, 1.5, 0))) continue;
            // ламп в кадре: конус ±30° от камеры к ядру, от 60 до BLACKOUT_REACH блоков
            Vec3 look = core.subtract(view).multiply(1, 0, 1).normalize();
            double inView = 0;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (lamps[i][j] == 0) continue;
                    Vec3 c = new Vec3(((cx0 + i - r) << 4) + 8 - view.x, 0, ((cz0 + j - r) << 4) + 8 - view.z);
                    double d = c.horizontalDistance();
                    if (d < 60 || d > BLACKOUT_REACH || c.normalize().dot(look) < Math.cos(Math.toRadians(30))) continue;
                    inView += lamps[i][j];
                }
            }
            if (inView > bestScore) {
                bestScore = inView;
                substation = at;
                substationView = view;
            }
        }
        if (bestScore == Double.NEGATIVE_INFINITY) {
            Airstrike.LOG.error("TRAILER blackout: нет места под подстанцию и камеру у {}", core);
            substation = core;
            substationView = substation.add(BLACKOUT_SIDE.scale(BLACKOUT_BACK)).add(0, BLACKOUT_HEIGHT, 0);
            bestScore = 0;
        }
        blackoutCore = core;
        Airstrike.LOG.info(String.format(Locale.ROOT, "TRAILER blackout: ламп сети в 24 чанках %d, ядро %s (%d в 80 блоках), подстанция %s, камера %s, ламп в кадре %.0f",
                total, core, best, substation, substationView, bestScore));
    }

    /** Открытое место (7×7 без построек, сухо) ближе всего к точке в радиусе {@code r}, или null. */
    @Nullable
    private static Vec3 openSpot(ServerLevel level, Vec3 near, int r) {
        Vec3 found = null;
        double best = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx += 3) {
            for (int dz = -r; dz <= r; dz += 3) {
                double d = Math.hypot(dx, dz);
                if (d > r || d >= best) continue;
                int x = Mth.floor(near.x) + dx, z = Mth.floor(near.z) + dz;
                int g = height(level, x, z);
                boolean open = true;
                for (int ox = -3; ox <= 3 && open; ox++) {
                    for (int oz = -3; oz <= 3 && open; oz++) {
                        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + ox, z + oz);
                        if (Math.abs(h - g) > 1 || !level.getFluidState(new BlockPos(x + ox, h - 1, z + oz)).isEmpty()) open = false;
                    }
                }
                if (open) {
                    best = d;
                    found = new Vec3(x + 0.5, g, z + 0.5);
                }
            }
        }
        return found;
    }

    /**
     * Тишина, сирена, МБР из-за 8 км с отсчётом; вспышка с холма, и ударная волна — на улице, в порту и снова на
     * холме: план за планом по времени прихода фронта (мир стоит, пока камера переезжает). Районы улицы и порта
     * держатся загруженными: в снимок подрыва входят только загруженные чанки, и волна ломает их в свой срок.
     */
    private void nuke() {
        run(() -> {
            // шахеды ночного роя, сбившиеся с пути, не должны висеть в списке полётов под отсчётом
            cmd("airstrike clear");
            cmd("time set 18000");
            AirstrikeConfig.SERVER.siren.set(true);
            forceload(STREET, 160);
            forceload(PORT, 128);
        });
        fromAfar(false);
        standAt(() -> silo, () -> TOWER);
        waitTicks(80);
        shot("icbm").onReady(() -> cmd(String.format(Locale.ROOT, "airstrike nuke at %.1f %.1f %.1f %d ground", TOWER.x, TOWER.y, TOWER.z, NUKE_KT)))
                .length(260).shake(0.1).camera(() -> {
                    Vec3 dir = TOWER.subtract(silo).multiply(1, 0, 1).normalize();
                    Vec3 sideV = new Vec3(-dir.z, 0, dir.x);
                    // стол МБР — в 30 блоках ЗА спиной наводчика (NuclearStrikes.launchFrom: назад от взгляда), а не впереди:
                    // в дубле 1 камера смотрела мимо, и ракета едва мелькала в углу кадра
                    Vec3 pad = ground(silo.subtract(dir.scale(30)));
                    // сбоку и чуть сзади стола, низко: ракета поднимается на фоне неба; в 65 блоках ночью она была мелкой
                    // в 36 блоках камеру накрывало облако старта (ноутбук, круг 2): дальше и выше, уже по углу
                    // в 74 блоках при сглаживании взгляда 0.4 ракета уходила из кадра во всех 269 кадрах (ноутбук,
                    // kfcheck3): взгляд отставал от разгона на десятки блоков, кадр занимало облако старта. Дальше
                    // (ракета поднимается под меньшим углом), шире и почти без сглаживания
                    Vec3 from = ground(pad.add(sideV.scale(140)).add(dir.scale(-25))).add(0, 10, 0);
                    // взгляд — между столом и ракетой, ближе к ракете: облако старта остаётся внизу кадра, пока ракета
                    // невысоко, потом кадр уходит в небо за ней. Взгляд прямо на ракету (облако, kf4b) упирался в чёрное
                    // небо, а сама ракета при FOV 40 и в 140 блоках была мельче 2 % кадра
                    Vec3 base = pad.add(0, 15, 0);
                    Path p = CineCamera.track(from, smoothFocus(() -> {
                        IcbmEntity m = newest(IcbmEntity.class);
                        return m == null ? null : base.lerp(m.getPosition(CineCamera.partial()), 0.85);
                    }, base, 0.85), 30);
                    // длинный объектив за ракетой: чем выше, тем уже кадр (в 300 блоках при 30° она мельче 2 % кадра)
                    final double[] zoom = {30, Double.NaN};
                    return t -> {
                        IcbmEntity m = newest(IcbmEntity.class);
                        double want = m == null ? 30 : Mth.clampedMap(m.getY() - pad.y, 40, 220, 30, 14);
                        // сглажено по времени плана, а не по вызовам (подкадры зовут путь много раз за тик)
                        double dt = Double.isNaN(zoom[1]) ? 0 : Math.max(0, t - zoom[1]);
                        zoom[1] = t;
                        zoom[0] += (want - zoom[0]) * (1 - Math.pow(0.9, dt));
                        Pose q = p.at(t);
                        return new Pose(q.pos(), q.yaw(), q.pitch(), q.roll(), zoom[0]);
                    };
                })
                .subject(() -> newest(IcbmEntity.class), 3, 0.02);
        // сирена над пустой улицей, отсчёт на экране
        run(() -> placeHidden(ground(STREET).add(0, 3, 0), TOWER));
        shot("siren").hidden().hud().length(180).shake(0.05).camera(() -> {
            Vec3 from = ground(STREET).add(0, 3, 0);
            return CineCamera.spline(true, CineCamera.Key.at(0, from, TOWER.add(0, 120, 0), 42),
                    CineCamera.Key.at(180, from.add(TOWER.subtract(from).normalize().scale(12)), TOWER.add(0, 200, 0), 36));
        }).when(() -> warningTicks() <= 420, 3000);
        // вспышка с холма из-за плеча наводчика: ночь становится днём
        standAt(() -> post, () -> TOWER);
        shot("flash").hud().length(200).shake(0.06).camera(() -> {
            Vec3 back = overGround(post.add(toPost.scale(2.6)).add(side.scale(1.2)).add(0, 1.9, 0), 1.7);
            return CineCamera.track(back, () -> TOWER.add(0, 400, 0), 60);
        }).when(() -> warningTicks() <= 60, 3000).endWhen(() -> sinceDetonation() > 30, 0);
        // волна приходит: улица (~900), порт (~1450), холм (~4000 блоков)
        wave("wave_street", () -> ground(STREET).add(0, 26, 0), 0.5);
        wave("wave_port", () -> ground(PORT.add(-40, 0, 30)).add(0, 20, 0), 0.5);
        standAt(() -> post, () -> TOWER);
        shot("wave_hill").hud().length(140).shake(0.06).camera(() -> {
            Vec3 back = overGround(post.add(toPost.scale(2.6)).add(side.scale(-1.3)).add(0, 1.8, 0), 1.7);
            return CineCamera.track(back, () -> TOWER.add(0, 250, 0), 64);
        }).when(() -> sinceDetonation() >= arrivalAt(post) - 60, 6000);
        // гриб издалека, ускоренно: из-за порта, над заливом
        run(() -> placeHidden(new Vec3(TOWER.x - 900, 200, TOWER.z + 5200)));
        shot("mushroom").length(1600).speed(4).hidden()
                .camera(() -> CineCamera.track(new Vec3(TOWER.x - 900, 200, TOWER.z + 5200), () -> TOWER.add(0, 2600, 0), 75))
                .when(() -> sinceDetonation() > 420, 4000);
    }

    /** План прихода фронта в точку: запись с запасом до прихода, замедленно, с толчком камеры в миг прихода. */
    private void wave(String name, Supplier<Vec3> eye, double speed) {
        run(() -> placeHidden(eye.get(), TOWER));
        final double lead = 24;
        Shot s = shot(name).hidden().length(110).speed(speed).shake(0.05);
        s.camera(() -> {
            s.shakeKick(lead, 3.5, 14);
            return CineCamera.track(eye.get(), () -> TOWER.add(0, 120, 0), 62);
        }).when(() -> sinceDetonation() >= arrivalAt(eye.get()) - lead, 6000);
    }

    /** Серое утро: руины центра, чёрный дождь в следе осадков, счётчик Гейгера в руке. */
    private void morning() {
        run(() -> {
            cmd("time set 1000");
            cmd("weather rain");
            AirstrikeConfig.SERVER.siren.set(false);
        });
        run(() -> placeHidden(overGround(TOWER.add(-300, 150, 120), 35), TOWER));
        // над крышами: башни у эпицентра в полторы сотни блоков, камера на постоянной высоте уходила в стены
        shot("ruins").hidden().length(220).shake(0.05).camera(() -> CineCamera.spline(true,
                CineCamera.Key.at(0, overGround(TOWER.add(-300, 150, 120), 35), TOWER.add(0, 20, 0), 50),
                CineCamera.Key.at(110, overGround(TOWER.add(-180, 110, 60), 35), TOWER.add(0, 10, 0), 48),
                CineCamera.Key.at(220, overGround(TOWER.add(-110, 85, -40), 35), TOWER, 46)))
                // пыль гриба и разрушения в только что загруженных чанках (они идут под бюджетом) должны улечься
                .when(() -> sinceDetonation() > 3000, 6000);
        run(() -> placeInFallout(false));
        waitTicks(40);
        run(() -> placeInFallout(true));
        run(() -> cmd("item replace entity @s weapon.mainhand with airstrike:geiger_counter"));
        // в творческом доза не копится (RadiationTicker), а в приключении за ожидание набегали смертельные десятки Гр:
        // приключение и чистая доза — только на время записи
        shot("fallout").length(170).hud().cue(0, () -> {
                    cmd("airstrike radiation clear");
                    cmd("gamemode adventure @s");
                }).player(t -> new Pose(Vec3.ZERO, yawTo(mc.player.position(), TOWER) + 150 - (float) t * 0.35f,
                        -18 + (float) Math.sin(t / 40) * 4, 0, 70))
                .when(() -> sinceDetonation() > 3900, 8000);
    }

    // ================================================================ места

    /** Пост наводчика и площадка МБР — ровные сухие места рядом с намеченными; точки на фасадах башен. */
    private void findLocations(ServerLevel level) {
        post = flatNear(level, HILLS, 240);
        silo = flatNear(level, SILO, 240);
        Vec3 d = post.subtract(DOWNTOWN);
        toPost = new Vec3(d.x, 0, d.z).normalize();
        side = new Vec3(-toPost.z, 0, toPost.x);
        clearAround(level, BlockPos.containing(post), 34, true);
        clearAround(level, BlockPos.containing(silo), 40, true);
        // после расчистки — земля заново (под убранным могло быть ниже)
        post = new Vec3(post.x, height(level, Mth.floor(post.x), Mth.floor(post.z)), post.z);
        silo = new Vec3(silo.x, height(level, Mth.floor(silo.x), Mth.floor(silo.z)), silo.z);
        // шахед холодного начала и ракета с борта заходят с запада — в западные стены
        towerTop = top(level, TOWER);
        droneRoof = roofNear(level, DOWNTOWN.add(90, 0, 60), 48);
        bombPlaza = plazaNear(level, SOUTH_TOWERS.add(50, 0, -70), 90, 14);
        northFacade = facade(level, NORTH_TOWER, 120, new Vec3(-1, 0, 0));
        eastFacade = facade(level, EAST_TOWER, 150, new Vec3(-1, 0, 0));
        Airstrike.LOG.info("TRAILER post {} silo {} tower {} facades {} {}", post, silo, towerTop, northFacade, eastFacade);
    }

    /** Самое ровное сухое место без леса в радиусе {@code r} от точки (площадка под пусковую). */
    private static Vec3 flatNear(ServerLevel level, Vec3 around, int r) {
        double best = Double.MAX_VALUE;
        int bx = Mth.floor(around.x), bz = Mth.floor(around.z);
        for (int dx = -r; dx <= r; dx += 24) {
            for (int dz = -r; dz <= r; dz += 24) {
                double score = groundScore(level, bx + dx, bz + dz) + Math.hypot(dx, dz) * 0.02;
                if (score < best) {
                    best = score;
                    around = new Vec3(bx + dx + 0.5, 0, bz + dz + 0.5);
                }
            }
        }
        int x = Mth.floor(around.x), z = Mth.floor(around.z);
        return new Vec3(x + 0.5, height(level, x, z), z + 0.5);
    }

    /** Площадка 48×48 вокруг точки: перепад высот, вода, кроны (чем меньше, тем лучше). */
    private static double groundScore(ServerLevel level, int x, int z) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, canopy = 0;
        boolean dry = true;
        for (int dx = -24; dx <= 24; dx += 8) {
            for (int dz = -24; dz <= 24; dz += 8) {
                int h = height(level, x + dx, z + dz);
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
                if (!level.getFluidState(new BlockPos(x + dx, h - 1, z + dz)).isEmpty()) dry = false;
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + dx, z + dz) > h + 1) canopy++;
            }
        }
        return (hi - lo) * 10 + (dry ? 0 : 1000) + canopy * 15;
    }

    /**
     * Плоская крыша дома в 12–45 блоках над улицей (площадка 7×7 одной высоты) ближе всего к точке: шахед, пущенный
     * в улицу между башнями, взрывался в каньоне, и облёт его застывшего взрыва не находил чистого вида (круги 1–3);
     * над крышей средней высоты взрыв виден со всех сторон.
     */
    private static Vec3 roofNear(ServerLevel level, Vec3 near, int r) {
        int bx = Mth.floor(near.x), bz = Mth.floor(near.z);
        int street = height(level, bx, bz);
        Vec3 found = new Vec3(near.x, street, near.z);
        double best = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                double d = Math.hypot(dx, dz);
                if (d > r || d >= best) continue;
                int x = bx + dx, z = bz + dz;
                level.getChunk(x >> 4, z >> 4);
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                if (h < street + 12 || h > street + 45) continue;
                boolean flat = true;
                for (int ox = -3; ox <= 3 && flat; ox++) {
                    for (int oz = -3; oz <= 3 && flat; oz++) {
                        level.getChunk((x + ox) >> 4, (z + oz) >> 4);
                        if (Math.abs(level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + ox, z + oz) - h) > 1) flat = false;
                    }
                }
                if (flat) {
                    best = d;
                    found = new Vec3(x + 0.5, h, z + 0.5);
                }
            }
        }
        Airstrike.LOG.info("TRAILER крыша для шахедов: {} (улица {})", found, street);
        return found;
    }

    /**
     * Открытое место (площадь, стоянка, сквер) ближе всего к точке: в {@code clear} блоках вокруг ничего не выше земли
     * больше чем на 3 блока. Бомба среди домов рвалась так, что облёт не видел землю под шаром ни с одной дуги (облако, kf4e: 2/9).
     */
    private static Vec3 plazaNear(ServerLevel level, Vec3 near, int r, int clear) {
        int bx = Mth.floor(near.x), bz = Mth.floor(near.z);
        Vec3 found = null;
        double best = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx += 3) {
            for (int dz = -r; dz <= r; dz += 3) {
                double d = Math.hypot(dx, dz);
                if (d > r || d >= best) continue;
                int x = bx + dx, z = bz + dz;
                int g = height(level, x, z);
                boolean open = level.getFluidState(new BlockPos(x, g - 1, z)).isEmpty();
                for (int ox = -clear; ox <= clear && open; ox += 2) {
                    for (int oz = -clear; oz <= clear && open; oz += 2) {
                        if (ox * ox + oz * oz > clear * clear) continue;
                        level.getChunk((x + ox) >> 4, (z + oz) >> 4);
                        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + ox, z + oz) > g + 3) open = false;
                    }
                }
                if (open) {
                    best = d;
                    found = new Vec3(x + 0.5, g, z + 0.5);
                }
            }
        }
        Airstrike.LOG.info("TRAILER площадка для бомбы: {}", found);
        return found != null ? found : new Vec3(near.x, height(level, bx, bz), near.z);
    }

    /** Самая высокая колонка в 32 блоках от точки (координаты башен по карте высот — с шагом 4 блока): верх её крыши. */
    private static Vec3 top(ServerLevel level, Vec3 near) {
        int bx = Mth.floor(near.x), bz = Mth.floor(near.z), top = Integer.MIN_VALUE;
        Vec3 axis = near;
        for (int dx = -32; dx <= 32; dx += 2) {
            for (int dz = -32; dz <= 32; dz += 2) {
                level.getChunk((bx + dx) >> 4, (bz + dz) >> 4);
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx + dx, bz + dz);
                if (h > top) {
                    top = h;
                    axis = new Vec3(bx + dx + 0.5, h, bz + dz + 0.5);
                }
            }
        }
        return axis;
    }

    /**
     * Точка в полутора блоках перед стеной башни на высоте {@code y} (не выше, чем за 20 блоков до её верха): ось
     * башни — {@link #top}, от неё идём с {@code from}-стороны к оси до первого твёрдого блока.
     */
    private static Vec3 facade(ServerLevel level, Vec3 near, int y, Vec3 from) {
        Vec3 axis = top(level, near);
        int top = Mth.floor(axis.y);
        double at = Math.min(y, top - 20);
        for (int k = 120; k >= 0; k--) {
            BlockPos p = BlockPos.containing(axis.x + from.x * k, at, axis.z + from.z * k);
            level.getChunk(p.getX() >> 4, p.getZ() >> 4);
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                return Vec3.atCenterOf(p).add(from.scale(1.5));
            }
        }
        Airstrike.LOG.warn("TRAILER нет стены у {} на высоте {} (верх {})", axis, at, top);
        return new Vec3(axis.x, top, axis.z);
    }

    /**
     * Убрать деревья (и траву с цветами, если {@code plants}) в круге: сценарий снимает, а не играет — лес на
     * пути взгляда и трава по пояс у камеры только мешают. Блоки ставятся без обновлений соседей и без дропа.
     */
    private static void clearAround(ServerLevel level, BlockPos c, int r, boolean plants) {
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                int x = c.getX() + dx, z = c.getZ() + dz;
                level.getChunk(x >> 4, z >> 4);
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                for (int y = ground - 8; y <= Math.max(top, ground + 3) + 1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    var st = level.getBlockState(p);
                    boolean tree = st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS) && y >= ground - 8;
                    boolean plant = plants && !st.isAir() && st.getFluidState().isEmpty() && (st.canBeReplaced()
                            || st.is(net.minecraft.tags.BlockTags.FLOWERS) || st.is(net.minecraft.tags.BlockTags.REPLACEABLE_BY_TREES));
                    if (tree || plant) level.setBlock(p, air, 2 | 16);
                }
            }
        }
    }

    /** Высота земли на сервере; чанк грузится (и генерируется) сразу — в сценарии это можно. */
    private static int height(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        // карта высот без листвы считает стволы землёй: пост в лесу вставал на верх ствола, а clearAround ствол
        // убирал — наводчик оказывался в яме, камера за ним в холме
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y - 1, z);
        while (p.getY() > level.getMinBuildHeight()) {
            var st = level.getBlockState(p);
            if (!st.is(net.minecraft.tags.BlockTags.LOGS) && !st.is(net.minecraft.tags.BlockTags.LEAVES) && !st.canBeReplaced()) break;
            p.move(0, -1, 0);
        }
        return p.getY() + 1;
    }

    /** Верх того, что стоит в точке (земля, крыша), — по карте высот клиента, а если чанка у клиента нет — спросить сервер. */
    /** Точка не ниже {@code clearance} над рельефом: камера за спиной наводчика на склоне уходила в холм. */
    private Vec3 overGround(Vec3 p, double clearance) {
        return new Vec3(p.x, Math.max(p.y, ground(p).y + clearance), p.z);
    }

    private Vec3 ground(Vec3 p) {
        int x = Mth.floor(p.x), z = Mth.floor(p.z);
        if (mc.level != null && mc.level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
            return new Vec3(p.x, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), p.z);
        }
        MinecraftServer server = mc.getSingleplayerServer();
        int y = server.submit(() -> {
            ServerLevel level = server.overworld();
            level.getChunk(x >> 4, z >> 4);
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        }).join();
        return new Vec3(p.x, y, p.z);
    }

    /** Держать загруженными чанки квадрата со стороной 2r вокруг точки ({@code /forceload}, не больше 256 чанков за раз). */
    private static void forceload(Vec3 c, int r) {
        int x0 = Mth.floor(c.x) - r, z0 = Mth.floor(c.z) - r, step = 15 * 16;
        for (int x = x0; x < x0 + 2 * r; x += step) {
            for (int z = z0; z < z0 + 2 * r; z += step) {
                cmd(String.format(Locale.ROOT, "forceload add %d %d %d %d", x, z, Math.min(x + step - 1, x0 + 2 * r), Math.min(z + step - 1, z0 + 2 * r)));
            }
        }
    }

    /** Удары издалека (снаряд заходит из-за спины невидимки) или с пусковой у наводчика. */
    private void fromAfar(boolean far) {
        run(() -> AirstrikeConfig.SERVER.launchNearPlayer.set(!far));
    }

    private void placeActor() {
        placeActor(post, TOWER);
    }

    /**
     * Наводчик на место без провала: сперва невидимкой (наблюдатель не падает), пока чанк места не придёт клиенту,
     * потом — на землю. Телепорт сразу в творческом режиме в неприсланный чанк ронял его на 2–8 блоков в землю.
     */
    private void standAt(Supplier<Vec3> at, Supplier<Vec3> face) {
        final Vec3[] spot = new Vec3[1];
        run(() -> {
            spot[0] = at.get();
            placeHidden(spot[0].add(0, 1, 0), face.get());
        });
        final int[] waited = {0};
        then(() -> {
            Vec3 p = spot[0];
            boolean ready = mc.level != null && mc.player != null
                    && mc.level.getChunkSource().hasChunk(Mth.floor(p.x) >> 4, Mth.floor(p.z) >> 4)
                    && mc.player.position().distanceTo(p) < 4;
            return ready || ++waited[0] > 400;
        });
        run(() -> placeActor(spot[0], face.get()));
    }

    /** Куда поставлен наводчик и сколько тиков назад (см. {@link #keepActorOnSpot}). */
    @Nullable
    private Vec3 actorSpot, actorFace;
    private int actorSince;

    /**
     * Телепорт в ещё не присланный клиенту чанк: игрок падает сквозь пустоту, пока чанк не пришёл, и застревает
     * в земле (наводчик стоял на 2–8 блоков ниже поста — камера за ним в холме). Первые 10 с после постановки: чанк
     * у клиента есть, а игрок не на месте — поставить снова.
     */
    private void keepActorOnSpot() {
        if (!actor || actorSpot == null || ++actorSince > 200 || mc.player == null || mc.level == null) return;
        Vec3 at = actorSpot;
        if (!mc.level.getChunkSource().hasChunk(Mth.floor(at.x) >> 4, Mth.floor(at.z) >> 4)) return;
        Vec3 p = mc.player.position();
        // под постом у клиента пусто — значит, неверна высота поста, а не игрок провалился: переставлять бесполезно
        if (mc.level.getBlockState(BlockPos.containing(at).below()).isAir()) return;
        if (Math.abs(p.y - at.y) > 0.6 && Math.hypot(p.x - at.x, p.z - at.z) < 3 && actorSince % 10 == 0) {
            Airstrike.LOG.info("TRAILER наводчик не на месте ({} вместо {}) — снова на пост", p, at);
            Vec3 face = actorFace;
            float yaw = yawTo(at, face);
            withPlayer((level, sp) -> sp.teleportTo(level, at.x, at.y, at.z, yaw, 0));
        }
    }

    /** Наводчик стоит на посту лицом к цели, в творческом режиме, виден в кадре. */
    private void placeActor(Vec3 at, Vec3 face) {
        actor = true;
        actorSpot = at;
        actorFace = face;
        actorSince = 0;
        float yaw = yawTo(at, face);
        withPlayer((level, p) -> {
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.teleportTo(level, at.x, at.y, at.z, yaw, 0);
        });
    }

    /** Игрок-невидимка (наблюдатель) там, где будет камера: чтобы сервер прислал этот район. */
    private void placeHidden(Vec3 at) {
        actor = false;
        withPlayer((level, p) -> {
            p.setGameMode(GameType.SPECTATOR);
            p.teleportTo(level, at.x, at.y, at.z, p.getYRot(), p.getXRot());
        });
    }

    /** То же, но лицом к точке: снаряд издалека заходит из-за спины стреляющего. */
    private void placeHidden(Vec3 at, Vec3 facing) {
        actor = false;
        withPlayer((level, p) -> {
            p.setGameMode(GameType.SPECTATOR);
            p.teleportTo(level, at.x, at.y, at.z, yawTo(at, facing), 0);
        });
    }

    /**
     * В след осадков, куда они придут к ~2.5 мин после подрыва (как в ядерном сценарии): сначала невидимкой
     * (прогрузить), потом на землю, на площадку из камня (вдруг там вода). При слабом ветре эта точка бывает в
     * воронке или в овраге — тогда дальше по ветру, до открытого места.
     */
    private void placeInFallout(boolean land) {
        var list = ClientNuclear.detonations();
        if (list.isEmpty()) return;
        MinecraftServer server = mc.getSingleplayerServer();
        if (!land) {
            var d = list.getLast().d;
            double m = Math.min(3000, d.windSpeed() * 150) * d.scale();
            double dx = Math.cos(d.windDir()), dz = Math.sin(d.windDir());
            falloutSpot = server.submit(() -> openGround(server.overworld(), d.burst().x, d.burst().z, dx, dz, m)).join();
            placeHidden(new Vec3(falloutSpot.x, 200, falloutSpot.z));
            return;
        }
        BlockPos c = BlockPos.containing(falloutSpot);
        // поляна: чёрный дождь виден на фоне неба, а не в листве
        int y = server.submit(() -> {
            clearAround(server.overworld(), c, 18, true);
            return server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, c.getX(), c.getZ());
        }).join();
        Vec3 g = new Vec3(falloutSpot.x, y, falloutSpot.z);
        BlockPos b = BlockPos.containing(g);
        cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", b.getX() - 1, b.getY(), b.getZ() - 1, b.getX() + 1, b.getY(), b.getZ() + 1));
        placeActor(g.add(0, 1, 0), TOWER);
    }

    /** Первая точка по ветру от {@code from} блоков, где земля не ниже окрестностей в 20 блоках (не яма). */
    private static Vec3 openGround(ServerLevel level, double x0, double z0, double dx, double dz, double from) {
        for (int i = 0; i < 40; i++) {
            double r = from + i * 24;
            int x = Mth.floor(x0 + dx * r), z = Mth.floor(z0 + dz * r);
            int h = height(level, x, z), around = Integer.MIN_VALUE;
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                around = Math.max(around, height(level, x + (int) (Math.cos(a) * 20), z + (int) (Math.sin(a) * 20)));
            }
            if (h >= around - 3) return new Vec3(x + 0.5, h, z + 0.5);
        }
        return new Vec3(x0 + dx * from, 0, z0 + dz * from);
    }

    // ================================================================ кто в кадре

    @Nullable
    private LauncherEntity launcher(WeaponType weapon) {
        LauncherEntity best = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof LauncherEntity l && l.weapon() == weapon && (best == null || l.distanceToSqr(post) < best.distanceToSqr(post))) best = l;
        }
        return best;
    }

    /** Самый молодой снаряд этого типа (только что сошёл с пусковой). */
    @Nullable
    private <T extends StrikeProjectile> T newest(Class<T> type) {
        T best = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (type.isInstance(e) && !e.isRemoved() && ((StrikeProjectile) e).isActive() && (best == null || e.tickCount < best.tickCount)) {
                best = type.cast(e);
            }
        }
        return best;
    }

    /** Для журнала ожидания: где снаряды у сервера (в мире и вне его) и сколько их видит клиент. */
    private String flightsNow() {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return "?";
        StringBuilder b = new StringBuilder();
        ServerLevel level = server.overworld();
        Vec3 me = mc.gameRenderer.getMainCamera().getPosition();
        int n = 0;
        for (StrikeProjectile e : level.getEntitiesOfClass(StrikeProjectile.class, new net.minecraft.world.phys.AABB(me, me).inflate(30000))) {
            if (n++ < 4) b.append(String.format(Locale.ROOT, "%s в мире (%.0f %.0f %.0f) в %.0f; ", e.getType().toShortString(), e.getX(), e.getY(), e.getZ(), e.position().distanceTo(me)));
        }
        for (StrikeProjectile e : ua.zentix.airstrike.strike.VirtualFlights.get(level).flights()) {
            if (n++ < 6) b.append(String.format(Locale.ROOT, "%s вне мира (%.0f %.0f %.0f) в %.0f; ", e.getType().toShortString(), e.getX(), e.getY(), e.getZ(), e.position().distanceTo(me)));
        }
        int client = 0;
        for (Entity e : mc.level.entitiesForRendering()) if (e instanceof StrikeProjectile) client++;
        return b.append("у клиента ").append(client).toString();
    }

    @Nullable
    private <T extends Entity> T nearest(Class<T> type, Vec3 p, double radius) {
        T best = null;
        double bd = radius * radius;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!type.isInstance(e) || e.isRemoved()) continue;
            if (e instanceof StrikeProjectile s && !s.isActive()) continue;
            double d = e.distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = type.cast(e);
            }
        }
        return best;
    }

    /** B-2 — пока он в 450 блоках от цели {@code t}; после сброса — бомба, в бурении — точка входа. */
    @Nullable
    private Vec3 bomberFocus(Vec3 t) {
        float pt = CineCamera.partial();
        BunkerBusterEntity bomb = nearest(BunkerBusterEntity.class, t, 600);
        if (bomb != null && bomb.flightPhase() != FlightPhase.DRILL) return bomb.getPosition(pt);
        if (bomb != null) return t.add(0, 1, 0);
        BomberEntity b = nearest(BomberEntity.class, t, 450);
        return b == null ? null : b.getPosition(pt);
    }

    /**
     * Скорость плана: {@code 1} (базовая плана), пока снаряд этого типа дальше {@code near} блоков от точки, ближе —
     * {@code slow} (подлёт в замедлении); без снаряда в 600 блоках — снова обычная.
     */
    private DoubleSupplier slowNear(Class<? extends Entity> type, Supplier<Vec3> at, double near, double slow) {
        return () -> {
            Vec3 p = at.get();
            Entity e = nearest(type, p, 600);
            if (e == null) return 1;
            double d = e.position().distanceTo(p);
            return d > near ? 1 : Mth.lerp(Mth.clamp(d / near, 0, 1), slow, 1);
        };
    }

    /**
     * Снаряд у самого объектива: камера встаёт на его пути на {@code ahead} тиков впереди, сбоку и выше, и
     * провожает его взглядом (быстро, как оператор, который не успевает).
     */
    private Path pastLens(@Nullable Entity e, double ahead, double sideOff, double up, double fov) {
        if (e == null) return t -> Pose.look(Vec3.ZERO, new Vec3(0, 0, 1), 0, fov);
        Vec3 p = e.position(), v = p.subtract(e.xo, e.yo, e.zo);
        if (v.lengthSqr() < 1e-4) v = new Vec3(1, 0, 0);
        Vec3 flat = new Vec3(v.x, 0, v.z).lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : new Vec3(v.x, 0, v.z).normalize();
        Vec3 right = new Vec3(-flat.z, 0, flat.x);
        Vec3 cam = p.add(v.scale(ahead)).add(right.scale(sideOff)).add(0, up, 0);
        final Entity target = e;
        return CineCamera.track(cam, smoothFocus(() -> target.isRemoved() ? null : target, p, 0.6), fov);
    }

    /**
     * Камера удара с застывшим взрывом: до заморозки стоит в {@code from} и следит за снарядом, с заморозки облетает
     * место взрыва по дуге {@code arcDeg} (за время облёта — {@code radius} блоков от него, чуть выше) и остаётся там,
     * когда мир снова пошёл. Место взрыва — где снаряд видели последним ({@link Shot#bulletTime}).
     */
    private Path bulletTimeCamera(Vec3 from, Supplier<Vec3> focus, double fov, double arcDeg, double radius) {
        return bulletTimeCamera(from, focus, fov, arcDeg, radius, null);
    }

    private Path bulletTimeCamera(Vec3 from, Supplier<Vec3> focus, double fov, double arcDeg, double radius, @Nullable Vec3 arcCenter) {
        Shot shot = (Shot) steps.peek();
        Path before = CineCamera.track(from, focus, fov);
        final View[] arc = {null};
        return t -> {
            double f = shot.frozenAt;
            if (Double.isNaN(f) || t < f || shot.impact == null) return before.at(t);
            Vec3 c = shot.impact;
            // взгляд — на огненный шар над местом, а не на точку подрыва: бетонобойная бомба рвётся под землёй
            Vec3 look = arcCenter == null ? c : new Vec3(c.x, Math.max(c.y, arcCenter.y) + 6, c.z);
            if (arc[0] == null) {
                // дугу облёта выбираем в миг взрыва вокруг настоящего места: openView проверял её вокруг намеченного,
                // а B-2 кладёт бомбу в десятках блоков от него — дуга кончалась над крышей, взрыв за домом (облако, kf4b/kf4c)
                Vec3 start = before.at(f).pos();
                arc[0] = arcNear(look, start, arcDeg, radius);
            }
            double arcTicks = Math.max(1, shot.freezeFrames * shot.freezeCamSpeed * Recorder.TPS / Recorder.VIDEO_FPS);
            double u = CineCamera.smooth(Math.min(1, (t - f) / arcTicks));
            Vec3 center = new Vec3(look.x, c.y, look.z);
            return Pose.look(arcPoint(center, arc[0].from(), u, arc[0].arc(), arc[0].radius()), look, 0, fov);
        };
    }

    /**
     * Дуга облёта вокруг {@code eye} из {@code start}: из вариантов дуги и радиуса — та, с которой {@code eye} виден во всех
     * точках и камере просторно; нет такой — та, где чистых точек больше всего, последний вариант — почти на месте.
     */
    private View arcNear(Vec3 eye, Vec3 start, double arcDeg, double radius) {
        MinecraftServer server = mc.getSingleplayerServer();
        double[] arcs = {arcDeg, -arcDeg, arcDeg * 0.5, -arcDeg * 0.5, arcDeg * 0.15, 0};
        final int samples = 8;
        return server.submit(() -> {
            ServerLevel level = server.overworld();
            Vec3 c = eye.subtract(0, 6, 0);
            double here = start.subtract(c).horizontalDistance();
            double[] radii = {radius, radius * 1.6, radius * 2.2, here};
            View best = new View(start, 0, here);
            int bestClear = -1;
            for (double a : arcs) {
                for (double r : radii) {
                    int clear = 0;
                    for (int k = 0; k <= samples; k++) {
                        Vec3 p = arcPoint(c, start, k / (double) samples, a, r);
                        // виден и сам шар, и земля под ним: иначе дуга кончалась над краем крыши, и взрыв закрывал
                        // соседний дом (облако, kf4d — проверка прошла, но в кадре были крыша и дым из-за дома)
                        if (sees(level, eye, p) && sees(level, eye.subtract(0, 5, 0), p) && roomy(level, p, 2)) clear++;
                    }
                    if (clear > bestClear) {
                        bestClear = clear;
                        best = new View(start, a, r);
                    }
                    if (clear > samples) break;
                }
                if (bestClear > samples) break;
            }
            Airstrike.LOG.info("TRAILER облёт взрыва {}: {} ({}/{} чистых)", eye, best, bestClear, samples + 1);
            return best;
        }).join();
    }

    /**
     * Точка облёта застывшего взрыва: доля {@code u} дуги {@code arcDeg} от {@code from} вокруг {@code c}, радиус — от
     * начального к {@code radius}, высота — к половине начальной (не к земле: у земли в городе камера входила в стены).
     */
    private static Vec3 arcPoint(Vec3 c, Vec3 from, double u, double arcDeg, double radius) {
        Vec3 d = from.subtract(c);
        double a = Math.atan2(d.x, d.z) + Math.toRadians(arcDeg) * u;
        double r = Mth.lerp(u, d.horizontalDistance(), radius), h = Mth.lerp(u, d.y, Math.max(radius * 0.3, d.y * 0.5));
        return c.add(Math.sin(a) * r, h, Math.cos(a) * r);
    }

    /**
     * Откуда смотреть на точку: из 24 направлений, нескольких расстояний и высот над ней — то, откуда видна сама точка
     * и вся дуга облёта ({@link #bulletTimeCamera}), и у камеры на всём пути нет блоков ближе 3; из годных — ниже,
     * ближе к первому расстоянию и к желаемому направлению {@code prefer}. Лучи — на сервере (чанки там грузятся
     * сразу; в сценарии это можно).
     */
    /** Откуда и как облетать место удара ({@link #openView}). */
    private record View(Vec3 from, double arc, double radius) {}

    private View openView(Vec3 at, double[] dists, double[] ups, Vec3 prefer, double arcDeg, double radius) {
        MinecraftServer server = mc.getSingleplayerServer();
        Vec3 want = new Vec3(prefer.x, 0, prefer.z).normalize();
        // дуга и радиус облёта — тоже на выбор: в плотном центре дуга к 40 блокам от места уходила в стены
        // последний вариант — почти без облёта: неподвижный чистый вид лучше облёта сквозь стены
        double[] arcs = {arcDeg, arcDeg * 0.5, -arcDeg * 0.5, -arcDeg, arcDeg * 0.15};
        double[] radii = {radius, radius * 1.6, radius * 2.5};
        final int samples = 8;
        return server.submit(() -> {
            ServerLevel level = server.overworld();
            // середина огненного шара, а не земля под ним: из улицы между башнями у земли не видно почти ничего
            Vec3 eye = at.add(0, 6, 0);
            View best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            int bestClear = 0;
            for (double dist : dists) {
                for (double up : ups) {
                    for (int i = 0; i < 24; i++) {
                        double a = Math.toRadians(15 * i);
                        Vec3 dir = new Vec3(Math.sin(a), 0, Math.cos(a));
                        Vec3 start = at.add(dir.scale(dist)).add(0, up, 0);
                        if (!(sees(level, eye, start) && roomy(level, start, 3))) continue;
                        for (int ai = 0; ai < arcs.length; ai++) {
                            for (int ri = 0; ri < radii.length; ri++) {
                                int clear = 0;
                                for (int k = 0; k <= samples; k++) {
                                    Vec3 p = arcPoint(at, start, k / (double) samples, arcs[ai], radii[ri]);
                                    if (sees(level, eye, p) && roomy(level, p, 3)) clear++;
                                }
                                double score = clear * 100 - up * 0.5 - Math.abs(dist - dists[0]) * 0.2 + dir.dot(want) * 5
                                        - ai * 8 - ri * 6;
                                if (score > bestScore) {
                                    bestScore = score;
                                    best = new View(start, arcs[ai], radii[ri]);
                                    bestClear = clear;
                                }
                            }
                        }
                    }
                }
            }
            if (best == null) best = new View(at.add(want.scale(dists[0])).add(0, ups[ups.length - 1], 0), arcDeg, radius);
            if (bestClear <= samples) {
                Airstrike.LOG.warn("TRAILER вид на {}: {} — чистого облёта нет ({}/{})", at, best, bestClear, samples + 1);
            } else {
                Airstrike.LOG.info("TRAILER вид на {}: {}", at, best);
            }
            return best;
        }).join();
    }

    /** Вокруг точки в кубе ±r нет твёрдых блоков (камера не упирается в стену и не цепляет её краем кадра). */
    private static boolean roomy(ServerLevel level, Vec3 p, int r) {
        BlockPos c = BlockPos.containing(p);
        for (BlockPos b : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
            if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()) return false;
        }
        return true;
    }

    /** Между точками нет блоков (листва тоже заслоняет). */
    private static boolean sees(ServerLevel level, Vec3 from, Vec3 to) {
        var hit = level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.VISUAL,
                net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getLocation().distanceTo(to) < 1;
    }

    /** Камера пути не ниже {@code clearance} над рельефом (карта высот клиента). */
    private Path lifted(Path p, double clearance) {
        return t -> {
            Pose q = p.at(t);
            if (q == null) return null;
            double floor = ground(q.pos()).y + clearance;
            return q.pos().y >= floor ? q : new Pose(new Vec3(q.pos().x, floor, q.pos().z), q.yaw(), q.pitch(), q.roll(), q.fov());
        };
    }

    /** Тиков игры с тех пор, как пропал последний снаряд этого типа (много — если он ещё в кадре или его не было). */
    private long sinceGone(Class<? extends Entity> type) {
        Long at = goneAt.get(type);
        return at == null || nearest(type, Vec3.ZERO, 1e7) != null ? -1 : mc.level.getGameTime() - at;
    }

    /** Тиков после подрыва, когда фронт дойдёт до точки (по модели подрыва; до подрыва — очень много). */
    private double arrivalAt(Vec3 p) {
        var list = ClientNuclear.detonations();
        if (list.isEmpty()) return Double.MAX_VALUE / 4;
        var d = list.getLast().d;
        return d.arrivalTicks(p.distanceTo(d.burst()));
    }

    /** Тиков игры после подрыва (-1 — подрыва ещё не было). */
    private long sinceDetonation() {
        return detonationTime < 0 ? -1 : mc.level.getGameTime() - detonationTime;
    }

    /** Камера снаряда включена и показывает карту оператора (снаряд дальше зоны видео). */
    private static boolean onMap() {
        return ProjectileCamera.isActive() && !ProjectileCamera.isViewing() && !ProjectileCamera.isFilming();
    }

    /** До ближайшего снаряда в полёте от игрока, блоков (по данным сервера — и для снарядов вне мира). */
    private double missileRange() {
        double best = Double.MAX_VALUE;
        for (var f : ua.zentix.airstrike.client.hud.ClientFlights.all()) best = Math.min(best, f.position(1).distanceTo(mc.player.position()));
        return best;
    }

    /** До подрыва по тревоге, тиков (много — если тревоги нет). */
    private long warningTicks() {
        long now = mc.level.getGameTime();
        long left = Long.MAX_VALUE;
        for (var w : ClientNuclear.warnings()) left = Math.min(left, w.detonateTime() - now);
        return left;
    }

    /**
     * Погоня за снарядом по его UUID: снаряд, ушедший из мира и вернувшийся (полёт вне мира), — уже другая сущность
     * с тем же UUID; по ссылке камера оставалась бы у старой, невидимка за ней не шёл, и снаряд не возвращался.
     */
    private Path chaseOf(@Nullable Entity e, double back, double up, double side, double lead, float roll, double fov) {
        final java.util.UUID id = e == null ? null : e.getUUID();
        final Vec3 start = e == null ? Vec3.ZERO : e.position();
        final Vec3 heading = e == null ? new Vec3(0, 0, 1) : e.position().subtract(e.xo, e.yo, e.zo);
        return CineCamera.chase(() -> id == null ? null : byId(id), () -> id == null ? null : flightAt(id), start,
                heading.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : heading, back, up, side, lead, roll, fov);
    }

    /** Ближайший к точке снаряд этого оружия по данным сервера (в том числе вне мира клиента), или null. */
    @Nullable
    private static Vec3 flightNear(WeaponType weapon, Vec3 at) {
        Vec3 best = null;
        for (var f : ua.zentix.airstrike.client.hud.ClientFlights.all()) {
            if (f.weapon() != weapon) continue;
            Vec3 p = f.position(CineCamera.partial());
            if (best == null || p.distanceToSqr(at) < best.distanceToSqr(at)) best = p;
        }
        return best;
    }

    /** Где снаряд по данным сервера (полёт вне мира клиента), или null. */
    @Nullable
    private static Vec3 flightAt(java.util.UUID id) {
        var f = ua.zentix.airstrike.client.hud.ClientFlights.find(id);
        return f == null ? null : f.position(CineCamera.partial());
    }

    @Nullable
    private Entity byId(java.util.UUID id) {
        for (Entity e : mc.level.entitiesForRendering()) if (id.equals(e.getUUID()) && !e.isRemoved()) return e;
        return null;
    }

    /**
     * Куда смотрит камера: на то, что вернул {@code focus} (снаряд, стая), иначе на {@code rest}; переход сглажен
     * по времени игры ({@code rate} — доля пути за тик), чтобы камера не дёргалась при смене цели.
     */
    private Supplier<Vec3> smoothFocus(Supplier<?> focus, Vec3 rest, double rate) {
        final Vec3[] look = {rest};
        final double[] lastT = {Double.NaN};
        return () -> {
            Object f = focus.get();
            Vec3 want = f instanceof Entity e ? e.getPosition(CineCamera.partial()) : f instanceof Vec3 v ? v : rest;
            double t = shotTime();
            double dt = Double.isNaN(lastT[0]) ? 1e9 : Math.max(0, t - lastT[0]);
            lastT[0] = t;
            double k = 1 - Math.pow(1 - rate, dt);
            look[0] = look[0].lerp(want, Mth.clamp(k, 0, 1));
            return look[0];
        };
    }

    /**
     * Поле зрения, при котором снаряд {@code focus} виден с {@code from} одного размера: {@code wide} ближе
     * {@code near} блоков, дальше — уже (не меньше {@code narrow}); без снаряда (взрыв) — снова {@code wide}.
     * Сглажено по времени игры, как {@link #smoothFocus}.
     */
    private DoubleSupplier rangeFov(Vec3 from, Supplier<? extends Entity> focus, double wide, double narrow, double near) {
        final double[] fov = {Double.NaN};
        final double[] lastT = {Double.NaN};
        return () -> {
            Entity e = focus.get();
            double want = e == null ? wide
                    : Mth.clamp(wide * near / Math.max(near, e.getPosition(CineCamera.partial()).distanceTo(from)), narrow, wide);
            double t = shotTime();
            double dt = Double.isNaN(lastT[0]) ? 1e9 : Math.max(0, t - lastT[0]);
            lastT[0] = t;
            fov[0] = Double.isNaN(fov[0]) ? want : Mth.lerp(Mth.clamp(1 - Math.pow(0.85, dt), 0, 1), fov[0], want);
            return fov[0];
        };
    }

    // ================================================================ шаги

    private void then(Step s) {
        steps.add(s);
    }

    private void run(Runnable r) {
        then(() -> {
            r.run();
            return true;
        });
    }

    private void waitTicks(int n) {
        final int[] left = {n};
        then(() -> --left[0] <= 0);
    }

    /** Работа на сервере (поиск мест, высоты): сценарий ждёт, пока она закончится. */
    private void onServer(Consumer<ServerLevel> work) {
        final boolean[] done = {false};
        final boolean[] sent = {false};
        then(() -> {
            if (!sent[0]) {
                sent[0] = true;
                MinecraftServer server = mc.getSingleplayerServer();
                server.execute(() -> {
                    work.accept(server.overworld());
                    mc.execute(() -> done[0] = true);
                });
            }
            return done[0];
        });
    }

    private void withPlayer(java.util.function.BiConsumer<ServerLevel, ServerPlayer> work) {
        MinecraftServer server = mc.getSingleplayerServer();
        var id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) work.accept(server.overworld(), p);
        });
    }

    private Shot shot(String name) {
        Shot s = new Shot(name);
        then(s);
        return s;
    }

    /** План: подготовка (камера на месте, мир прогружен), ожидание момента, запись. */
    private final class Shot implements Step {
        private record Cue(int at, Runnable action) {}

        final String name;
        private int length = 100;
        private double speed = 1;
        /** Скорость по ходу плана (замедление на подлёте); null — постоянная {@link #speed}. */
        @Nullable
        private DoubleSupplier speedCurve;
        /** Замереть, когда условие станет верным: кадров с неподвижным миром и скорость камеры в это время. */
        @Nullable
        private BooleanSupplier freezeWhen;
        private int freezeFrames;
        private double freezeCamSpeed;
        private boolean freezeArmed;
        private Supplier<Path> camera;
        /** Вид глазами игрока: путь задаёт только взгляд. */
        private Path playerView;
        private boolean projectileCamera;
        /** Что должно быть в кадре (для {@link ShotCheck}) или null. */
        @Nullable
        private ShotCheck.Subject subject;
        @Nullable
        private String mustHappen;
        @Nullable
        private BooleanSupplier happens;
        @Nullable
        private ShotCheck check;
        private boolean hudOn;
        private boolean hiddenActor;
        private boolean prep = true;
        /** Ждать прогрузки почти на всю дальность прорисовки (общий план с высоты), а не только рядом с камерой. */
        private int readyChunks = 4;
        private BooleanSupplier after, when, end;
        private int afterTimeout, whenTimeout, endDelay;
        private final List<Cue> cues = new ArrayList<>();
        private Runnable atEnd, atPrepare, atReady;

        private int phase, waited, readyFor, t, endAt = -1;
        /** Время игры, с которого план ждёт условия (-1 — ещё не ждал). */
        private long since = -1;
        private Path path;
        /** Тряска камеры плана (null — без неё): дрожь с рук и толчки от взрывов. */
        @Nullable
        private CineCamera.Shake shake;
        /** Время камеры, когда мир замер (NaN — ещё нет), и где был взрыв, на котором он замер. */
        private double frozenAt = Double.NaN;
        @Nullable
        private Vec3 impact;

        Shot(String name) {
            this.name = name;
        }

        Shot length(int ticks) {
            length = ticks;
            return this;
        }

        Shot speed(double s) {
            speed = s;
            return this;
        }

        /**
         * Скорость, меняющаяся по ходу плана ({@code base} — для заголовка плана и подготовки): например, замедление,
         * пока снаряд подлетает к цели, и разгон на ударной волне. Переход сглаживает {@link Recorder}.
         */
        Shot speed(double base, DoubleSupplier curve) {
            speed = base;
            speedCurve = curve;
            return this;
        }

        /**
         * Застывший мир: когда условие станет верным, мир замирает на ближайшем целом тике, и {@code frames} кадров
         * видео камера идёт по своему пути (облёт застывшего взрыва) со скоростью {@code camSpeed}; потом мир идёт дальше.
         */
        Shot freeze(BooleanSupplier when, int frames, double camSpeed) {
            freezeWhen = when;
            freezeFrames = frames;
            freezeCamSpeed = camSpeed;
            return this;
        }

        Shot camera(Supplier<Path> c) {
            camera = c;
            return this;
        }

        Shot player(Path view) {
            playerView = view;
            return this;
        }

        /**
         * Что снимаем — для проверки кадров ({@link ShotCheck}): сущность или точка ({@code size} блоков), в кадре
         * не мельче {@code minScreen} высоты кадра и не закрыта блоками.
         */
        /** Что должно случиться за план хоть раз, иначе проверка кадров — провал. */
        Shot requires(String what, BooleanSupplier when) {
            mustHappen = what;
            happens = when;
            return this;
        }

        Shot subject(Supplier<Object> what, double size, double minScreen) {
            subject = new ShotCheck.Subject(what, size, minScreen, true);
            return this;
        }

        /** То же, но цель может быть за постройками (видно облако над ними, не саму точку). */
        Shot subjectAnyway(Supplier<Object> what, double size, double minScreen) {
            subject = new ShotCheck.Subject(what, size, minScreen, false);
            return this;
        }

        Shot projectileCamera() {
            projectileCamera = true;
            return this;
        }

        Shot hud() {
            hudOn = true;
            return this;
        }

        Shot hidden() {
            hiddenActor = true;
            return this;
        }

        Shot farView() {
            return readyChunks(10);
        }

        /** Перед съёмкой ждать мир на {@code chunks} чанков вокруг камеры (не дальше прорисовки). */
        Shot readyChunks(int chunks) {
            readyChunks = chunks;
            return this;
        }

        Shot noPrep() {
            prep = false;
            return this;
        }

        /** Условие до постановки камеры (например, пусковая уже появилась — от неё строится путь). */
        Shot after(BooleanSupplier c, int timeout) {
            after = c;
            afterTimeout = timeout;
            return this;
        }

        /** Момент начала записи (камера уже на месте). */
        Shot when(BooleanSupplier c, int timeout) {
            when = c;
            whenTimeout = timeout;
            return this;
        }

        /** Конец раньше длины: через {@code delay} тиков после того, как условие стало верным. */
        Shot endWhen(BooleanSupplier c, int delay) {
            end = c;
            endDelay = delay;
            return this;
        }

        /** Камера с рук: дрожь {@code handheld} градусов, взрывы в плане толкают её (ближе — сильнее). */
        Shot shake(double handheld) {
            shake = new CineCamera.Shake(handheld);
            return this;
        }

        /** Толчок камеры в момент {@code at} (время камеры плана). */
        void shakeKick(double at, double deg, double decay) {
            if (shake != null) shake.kick(at, deg, decay);
        }

        /**
         * Застывший взрыв: когда снаряд этого типа у точки пропадёт (взрыв), мир замирает на {@code frames} кадров,
         * камера идёт со скоростью {@code camSpeed} ({@link #bulletTimeCamera} облетает место взрыва).
         */
        Shot bulletTime(Class<? extends Entity> type, Supplier<Vec3> at, int frames, double camSpeed) {
            final Vec3[] last = {null};
            return freeze(() -> {
                Entity e = nearest(type, at.get(), 900);
                if (e != null) {
                    last[0] = e.getPosition(CineCamera.partial());
                    return false;
                }
                // пропал далеко от цели — ушёл из дальности сущностей (полёт вне мира), а не взорвался
                if (last[0] == null || last[0].distanceTo(at.get()) > 40) {
                    last[0] = null;
                    return false;
                }
                impact = last[0];
                return true;
            }, frames, camSpeed);
        }

        /** Одно действие каждые {@code step} тиков с {@code from} по {@code to} включительно. */
        Shot cues(int from, int to, int step, Runnable action) {
            for (int at = from; at <= to; at += step) cues.add(new Cue(at, action));
            return this;
        }

        Shot cue(int at, Runnable action) {
            cues.add(new Cue(at, action));
            return this;
        }

        /** Действие, когда камера ставится на место (до ожидания момента). */
        Shot prepare(Runnable action) {
            atPrepare = action;
            return this;
        }

        /** Действие, когда камера на месте и мир прогружен (пуск, чей подлёт снимаем, — чтобы не прозевать). */
        Shot onReady(Runnable action) {
            atReady = action;
            return this;
        }

        Shot cueEnd(Runnable action) {
            atEnd = action;
            return this;
        }

        /** AIRSTRIKE_TRAILER_SHOTS=a,b — снимать только эти планы (остальные проигрываются без записи, для отладки). */
        boolean selected() {
            String only = System.getenv("AIRSTRIKE_TRAILER_SHOTS");
            return only == null || only.isBlank() || java.util.Arrays.asList(only.split(",")).contains(name);
        }

        boolean player() {
            return playerView != null;
        }

        /** Время плана с долей тика: кадр после тика n показывает мир между тиками n−1 и n. */
        double time() {
            if (phase < 4) return 0;
            return recording == this ? rec.worldTime() : Math.max(0, t - 1 + CineCamera.partial());
        }

        /** Время камеры: то же, что у мира, но у замершего мира камера идёт дальше. */
        double camTime() {
            return recording == this && phase >= 4 ? rec.camTime() : time();
        }

        @Override
        public boolean tick() {
            switch (phase) {
                case 0 -> {
                    if (after != null && !after.getAsBoolean() && worldWaited() < afterTimeout) return false;
                    if (after != null && worldWaited() >= afterTimeout) {
                        Airstrike.LOG.warn("TRAILER {}: не дождались условия, план пропущен", name);
                        return true;
                    }
                    waited = 0;
                    since = -1;
                    hud = hudOn;
                    if (hiddenActor && actor) {
                        actor = false;
                        withPlayer((level, p) -> p.setGameMode(GameType.SPECTATOR));
                    }
                    if (camera != null) {
                        path = camera.get();
                        if (shake != null) path = shake.on(path);
                        CineCamera.use(path);
                    } else {
                        CineCamera.release();
                    }
                    if (atPrepare != null) atPrepare.run();
                    setTickRate(20);
                    phase = prep ? 1 : 2;
                    // мир стоит, пока камера ждёт прогрузки: снаряд не долетает до цели раньше, чем начнётся запись
                    if (prep) setFrozen(true);
                    if (!prep && atReady != null) atReady.run();
                    Airstrike.LOG.info("TRAILER {}: подготовка", name);
                }
                case 1 -> {
                    followCamera();
                    readyFor = worldReady(readyChunks) ? readyFor + 1 : 0;
                    if (readyFor >= 10 && waited >= 40 || waited > 1200) {
                        setFrozen(false);
                        phase = 2;
                        waited = 0;
                        if (atReady != null) atReady.run();
                    } else {
                        waited++;
                    }
                }
                case 2 -> {
                    followCamera();
                    if (when != null && !when.getAsBoolean()) {
                        long w = worldWaited();
                        if (w > 0 && w % 200 == 0) Airstrike.LOG.info("TRAILER {}: ждём момент ({} тиков); снаряды {}", name, w, flightsNow());
                        if (w < whenTimeout) return false;
                        // без своего момента план пуст (снаряд не долетел): не тратить минуты записи и гигабайты кадров
                        Airstrike.LOG.warn("TRAILER {}: момент не наступил, план пропущен", name);
                        idleTime = 0;
                        setTickRate(20);
                        return true;
                    }
                    // попадания прошлых планов не в счёт: sinceGone в условиях этого плана — только о его снарядах
                    goneAt.clear();
                    if (selected()) {
                        check = new ShotCheck(name, subject, projectileCamera ? ProjectileCamera::isViewing : null).requires(mustHappen, happens);
                        if (speedCurve != null) rec.start(name, speed, speedCurve, hudOn);
                        else rec.start(name, speed, hudOn);
                        freezeArmed = false;
                        recording = this;
                        frameClock = 0;
                        setTickRate(Math.max(1, (float) (8 * rec.step())));
                    }
                    phase = 4;
                    t = 0;
                    runCues();
                }
                case 4 -> {
                    followCamera();
                    t++;
                    runCues();
                    if (end != null && endAt < 0 && end.getAsBoolean()) endAt = t + endDelay;
                    if (t >= length || endAt >= 0 && t >= endAt) {
                        rec.stop();
                        if (check != null && !check.report()) failedChecks.add(name);
                        check = null;
                        recording = null;
                        idleTime = time();
                        if (atEnd != null) atEnd.run();
                        setTickRate(20);
                        return true;
                    }
                }
                default -> throw new IllegalStateException();
            }
            return false;
        }

        /**
         * Сколько тиков игры план ждёт условия: сроки — по времени мира, а не по тикам клиента (у отстающего
         * сервера — генерация, обновление старых чанков — снаряд летит медленнее, чем идут тики клиента).
         */
        private long worldWaited() {
            long now = mc.level.getGameTime();
            if (since < 0) since = now;
            return now - since;
        }

        private void runCues() {
            for (Cue c : cues) if (c.at == t) c.action.run();
        }

        /** Камера на кадр: кинокамера — по пути, вид игрока — поворот головы. */
        void applyCamera() {
            double time = camTime();
            if (path != null) CineCamera.apply(time);
            if (playerView != null && mc.player != null) {
                Pose p = playerView.at(time);
                CineCamera.viewFov = p.fov();
                mc.player.setYRot(p.yaw());
                mc.player.yRotO = p.yaw();
                mc.player.setXRot(p.pitch());
                mc.player.xRotO = p.pitch();
                mc.player.setYHeadRot(p.yaw());
                mc.player.yHeadRotO = p.yaw();
            }
        }
    }

    // ================================================================ события игры

    private void onTick(ClientTickEvent.Post e) {
        if (mc.player == null || mc.level == null) return;
        keepActorOnSpot();
        if (!started) {
            started = true;
            // папка записи: дубль отдельных планов (AIRSTRIKE_TRAILER_SHOTS) — в свою папку, монтаж берёт их поверх основной
            rec = new Recorder(mc.gameDirectory.toPath().resolve(System.getenv().getOrDefault("AIRSTRIKE_TRAILER_DIR", "trailer")));
            mc.getSoundManager().addListener(rec);
            // размытие движения: AIRSTRIKE_SUBFRAMES подкадров на кадр видео, затвор 180° (съёмка начисто — 8, проба — 1)
            rec.motionBlur(Integer.getInteger("airstrike.subframes", 1), 0.5);
            mc.options.hideGui = false;
        }
        if (detonationTime < 0 && !ClientNuclear.detonations().isEmpty()) {
            detonationTime = mc.level.getGameTime();
            if (rec.recording()) rec.mark("detonation");
        }
        boolean viewing = ProjectileCamera.isViewing(), camera = ProjectileCamera.isActive();
        markImpacts(rec.recording());
        if (rec.recording() && viewing && !wasViewing) rec.mark("video");
        if (!viewing) {
            closeMarked = false;
        } else if (rec.recording() && !closeMarked && mc.getCameraEntity() instanceof StrikeProjectile p
                && ua.zentix.airstrike.client.hud.ClientFlights.find(p.getUUID()) instanceof ua.zentix.airstrike.client.hud.ClientFlights.Tracked f
                && p.position().distanceTo(f.target()) < CLOSE_RANGE) {
            rec.mark("close");
            closeMarked = true;
        }
        // камера снаряда вернулась к игроку: дальше в плане — вид от первого лица, монтаж режет до этой отметки
        if (rec.recording() && !camera && cameraWasActive) rec.mark("exit");
        wasViewing = viewing;
        cameraWasActive = camera;
        while (!steps.isEmpty()) {
            if (!steps.peek().tick()) break;
            steps.poll();
        }
    }

    /**
     * Снаряды, что были в кадре и пропали (взрыв), и сброс бомбы B-2: отметки для монтажа — звук приходит позже
     * картинки. Список обновляется и между планами, иначе снаряды, пропавшие до записи, отмечались бы в начале
     * следующего плана.
     */
    private void markImpacts(boolean mark) {
        java.util.Map<Integer, Seen> now = new java.util.HashMap<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof BomberEntity b) {
                if (b.flightPhase() == FlightPhase.EGRESS && released.add(b.getId()) && mark) rec.mark("release");
            } else if (e instanceof StrikeProjectile p && p.isActive()) {
                now.put(e.getId(), new Seen(e.getType().toShortString(), e.getClass(), e.position()));
            }
        }
        for (var e : seen.entrySet()) {
            if (now.containsKey(e.getKey())) continue;
            Seen gone = e.getValue();
            goneAt.put(gone.cls(), mc.level.getGameTime());
            if (!mark) continue;
            rec.mark("gone:" + gone.type());
            // взрыв толкает камеру: чем ближе, тем сильнее
            Pose cam = CineCamera.pose();
            if (recording != null && recording.shake != null && cam != null) {
                double d = Math.max(1, cam.pos().distanceTo(gone.pos()));
                recording.shakeKick(recording.camTime(), Math.min(2.5, 40 / d), 8);
            }
        }
        seen = now;
    }

    /**
     * Перед кадром: при записи — доля тика ровно под момент кадра (кадр после тика n показывает мир между тиками
     * n−1 и n); если клиент ещё не дотикал до момента (кадр отрисовки не в счёт) — кадр не снимается.
     */
    private void beforeFrame(RenderFrameEvent.Pre e) {
        if (!started) return;
        frameReady = false;
        // всплывашки (достижение за моба, убитого взрывом) — не для кадра
        mc.getToasts().clear();
        if (recording != null) {
            double partial = recording.time() - (recording.t - 1);
            if (partial < -0.02 && rec.catchUp(-partial)) partial = 0;
            if (partial > 1.0001) {
                FrameClock.advanceNext(partial - 1 + 0.01);
            } else {
                if (partial < -0.02) Airstrike.LOG.warn("TRAILER {}: клиент обогнал кадр на {} тика", recording.name, -partial);
                FrameClock.setPartial((float) Mth.clamp(partial, 0, 1));
                frameReady = true;
            }
        }
        if (steps.peek() instanceof Shot shot) shot.applyCamera();
        else CineCamera.apply(idleTime); // между планами камера стоит, где закончила
        holdTarget();
    }

    /**
     * Видео с борта: камера на подвесе смотрит туда, куда игрок ведёт мышью; в трейлере мышь не двигается, и подвес
     * оставался на курсе входа — в пике в кадре был горизонт. Оператор держит цель в центре кадра.
     */
    private void holdTarget() {
        if (!ProjectileCamera.isViewing() || !(mc.getCameraEntity() instanceof StrikeProjectile p)
                || !(ua.zentix.airstrike.client.hud.ClientFlights.find(p.getUUID()) instanceof ua.zentix.airstrike.client.hud.ClientFlights.Tracked f)) {
            return;
        }
        Vec3 d = f.target().subtract(p.getPosition(mc.getTimer().getGameTimeDeltaPartialTick(false)));
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90;
        float pitch = (float) (-Mth.atan2(d.y, d.horizontalDistance()) * Mth.RAD_TO_DEG);
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.setXRot(pitch);
        mc.player.xRotO = pitch;
    }

    private void afterFrame(RenderFrameEvent.Post e) {
        if (recording == null || !frameReady) return;
        Shot s = recording;
        double before = s.time();
        boolean wasFrozen = rec.frozen();
        if (!rec.frame(before, mc.gameRenderer.getMainCamera())) {
            FrameClock.advanceNext(s.time() - before); // следующий подкадр того же кадра
            return;
        }
        if (s.check != null) {
            Pose p = CineCamera.active() ? CineCamera.pose() : null;
            s.check.frame(mc.gameRenderer.getMainCamera(), p != null ? p.fov() : Double.isNaN(CineCamera.viewFov) ? mc.options.fov().get() : CineCamera.viewFov);
        }
        if (s.freezeWhen != null && !s.freezeArmed && s.freezeWhen.getAsBoolean()) {
            s.freezeArmed = true;
            rec.freeze(rec.worldTime() + 1e-6, s.freezeFrames, s.freezeCamSpeed);
        }
        if (rec.freezeNow()) {
            s.frozenAt = rec.camTime();
            setFrozen(true);
            rec.mark("freeze");
        } else if (wasFrozen && !rec.frozen()) {
            setFrozen(false);
            rec.mark("unfreeze");
        }
        FrameClock.advanceNext(s.time() - before);
        adaptTickRate();
    }

    /** Время текущего плана (для сглаживания взгляда). */
    private double shotTime() {
        return steps.peek() instanceof Shot s ? s.time() : 0;
    }

    /**
     * Сервер идёт в том же темпе, что и запись (тиков в секунду = кадров отрисовки в секунду × шаг кадра): тогда
     * данные о сущностях приходят к клиенту вовремя, а не рывками.
     */
    private void adaptTickRate() {
        long now = System.nanoTime();
        if (frameClock == 0) {
            frameClock = now;
            frameCount = 0;
            return;
        }
        if (++frameCount < 10) return;
        double fps = frameCount / ((now - frameClock) / 1e9);
        frameClock = now;
        frameCount = 0;
        if (rec.frozen()) return; // мир стоит: темп сервера — после
        float want = (float) Mth.clamp(fps * rec.step(), 1, 20); // fps — кадров видео (подкадры не в счёт)
        if (Math.abs(want - tickRate) / tickRate > 0.1) setTickRate(want);
    }

    private void setFrozen(boolean frozen) {
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> server.tickRateManager().setFrozen(frozen));
    }

    /**
     * Кадр ровно {@code AIRSTRIKE_SIZE}: окно без рамки и ровно этого размера. В KWin окно с заголовком ужималось под
     * экран (кадр 1920×1052); не вышло — съёмка не начинается: кадры другого размера монтажу не годятся.
     */
    private boolean exactFrame() {
        String[] want = System.getProperty("airstrike.frame", "").split("x");
        if (want.length != 2) return true;
        int w = Integer.parseInt(want[0]), h = Integer.parseInt(want[1]);
        var target = mc.getMainRenderTarget();
        if (target.width == w && target.height == h) {
            Airstrike.LOG.info("TRAILER кадр {}x{}", w, h);
            return true;
        }
        long window = mc.getWindow().getWindow();
        if (frameTries == 0) {
            Airstrike.LOG.info("TRAILER окно {}x{}, нужно {}x{}: убираю рамку и ставлю размер", target.width, target.height, w, h);
            org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(window, org.lwjgl.glfw.GLFW.GLFW_DECORATED, org.lwjgl.glfw.GLFW.GLFW_FALSE);
        }
        if (frameTries % 20 == 0) org.lwjgl.glfw.GLFW.glfwSetWindowSize(window, w, h);
        if (++frameTries > 200) {
            Airstrike.LOG.error("TRAILER кадр {}x{} вместо {}x{}: экран меньше окна? Съёмка остановлена", target.width, target.height, w, h);
            Runtime.getRuntime().halt(3);
        }
        return false;
    }

    private void setTickRate(float rate) {
        if (rate == tickRate) return;
        tickRate = rate;
        FrameClock.requestTickRate(rate);
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> server.tickRateManager().setTickRate(rate));
    }

    /**
     * Мир на {@code chunks} чанков вокруг камеры получен и собран в секции для отрисовки. Общим планам с высоты — 10:
     * первый план в свежем мире иначе снимал деревню на «острове» над пустотой; видео с борта — вся прорисовка (ракета
     * смотрит за деревню). Остальным хватает 4: пока ждём дальние, снаряд уже долетает, и план удара снимался бы без удара.
     */
    private boolean worldReady(int chunks) {
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        int cx = Mth.floor(cam.x) >> 4, cz = Mth.floor(cam.z) >> 4;
        int r = Math.max(4, Math.min(chunks, mc.options.getEffectiveRenderDistance() - 2));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) return false;
            }
        }
        return mc.levelRenderer.hasRenderedAllSections();
    }

    /** Невидимый игрок держится рядом с кинокамерой: сервер шлёт мир вокруг игрока, а не вокруг камеры. */
    private void followCamera() {
        Pose p = CineCamera.pose();
        if (actor || p == null || mc.player == null) return;
        if (mc.player.position().distanceToSqr(p.pos()) < 24 * 24) return;
        Vec3 at = p.pos();
        withPlayer((level, sp) -> sp.teleportTo(level, at.x, at.y, at.z, sp.getYRot(), sp.getXRot()));
    }

    /** Интерфейс игры в кадре не нужен; интерфейс мода — только в планах «с интерфейсом», вспышки — всегда. */
    private void layer(RenderGuiLayerEvent.Pre e) {
        if (!started) return;
        ResourceLocation id = e.getName();
        if (ALWAYS_LAYERS.contains(id)) return;
        if (hud && id.getNamespace().equals(Airstrike.MOD_ID)) return;
        e.setCanceled(true);
    }

    private void hideActor(RenderPlayerEvent.Pre e) {
        if (started && !actor) e.setCanceled(true);
    }

    private void hideName(net.neoforged.neoforge.client.event.RenderNameTagEvent e) {
        if (started) e.setCanRender(net.neoforged.neoforge.common.util.TriState.FALSE);
    }

    /**
     * Затемнение «голова в блоке» ваниль считает по глазам игрока, а не камеры: у кинокамеры в стороне от наводчика
     * оно закрывало весь кадр текстурой земли, стоило ему встать у склона.
     */
    private void hideWallOverlay(net.neoforged.neoforge.client.event.RenderBlockScreenEffectEvent e) {
        if (CineCamera.active()) e.setCanceled(true);
    }

    private void hideHand(RenderHandEvent e) {
        if (CineCamera.active()) e.setCanceled(true);
    }

    // ================================================================ мелочи

    private void fire(String what, Vec3 at) {
        cmd(String.format(Locale.ROOT, "airstrike %s at %.1f %.1f %.1f", what, at.x, at.y, at.z));
    }

    private static void cmd(String c) {
        Minecraft.getInstance().player.connection.sendCommand(c);
        Airstrike.LOG.info("TRAILER /{}", c);
    }

    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90f;
    }

    private static float pitchTo(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        return (float) (-Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
    }
}
