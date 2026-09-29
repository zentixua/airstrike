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
 * Трейлер мода, снятый в игре: сценарий по планам (как у оператора) — рассвет над деревней, наводчик с биноклем,
 * пульт, пусковая шахедов за спиной, разгон и отделение ускорителя, погоня за шахедом, удар; крылатая ракета с
 * земли и глазами ракеты, B-2 и бетонобойная бомба, залпы на закате, МБР и ядерный удар, гриб издалека, чёрный дождь.
 * <p>
 * Каждый план: камера встаёт на место и ждёт, пока прогрузится мир (игра идёт на обычной скорости), потом —
 * ждёт свой момент (снаряд подлетел, подрыв), и тогда пишется: игра замедляется под скорость отрисовки
 * ({@link Recorder}), кадры — по времени игры. Кадры и журнал звуков — в {@code run/scenario/trailer/}, монтаж —
 * {@code tools/trailer/edit.py}. Запуск — {@code tools/trailer/record.sh}.
 */
public final class Trailer {
    private static final Set<ResourceLocation> ALWAYS_LAYERS = Set.of(Airstrike.id("flash"), Airstrike.id("nuke_flash"));

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

    // места съёмки (ищутся в начале на сервере)
    private Vec3 village = Vec3.ZERO;
    private Vec3 post = Vec3.ZERO;
    private Vec3 toPost = new Vec3(0, 0, 1);
    private Vec3 side = new Vec3(1, 0, 0);
    private Vec3 viewpoint = Vec3.ZERO;
    /** Вторая вышка (подрыв снимается с неё). */
    private Vec3 watch = Vec3.ZERO;
    /** От деревни к смотровой точке ядерного удара. */
    private Vec3 toView = new Vec3(0, 0, -1);
    /** Время игры подрыва (-1 — ещё не было): мир замирает на подготовку планов, тики клиента идут и тогда. */
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
    private java.util.Map<Integer, String> seen = java.util.Map.of();
    private final java.util.Set<Integer> released = new java.util.HashSet<>();
    /** Где наводчик стоит под чёрным дождём (выбирается, пока он невидимкой прогружает место). */
    private Vec3 falloutSpot = Vec3.ZERO;

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
        script();
    }

    // ================================================================ сценарий

    private void script() {
        then(this::exactFrame);
        run(() -> {
            cmd("gamerule doDaylightCycle false");
            cmd("gamerule doWeatherCycle false");
            cmd("gamerule doMobSpawning false");
            cmd("weather clear");
            cmd("time set 23900");
            var c = AirstrikeConfig.SERVER;
            c.launchNearPlayer.set(true);
            c.droneFlightTime.set(30);
            c.missileFlightTime.set(20);
            c.bomberFlightTime.set(20);
            c.nukeFlightTime.set(600);
            c.loiterTime.set(8);
            c.siren.set(false);
        });
        onServer(this::findLocations);
        waitTicks(5);
        run(this::placeActor);
        waitTicks(60);

        // --- рассвет над деревней
        shot("dawn").length(200).camera(() -> {
            Vec3 a = village.add(toPost.scale(-70)).add(side.scale(-30)).add(0, 55, 0);
            Vec3 b = village.add(toPost.scale(40)).add(side.scale(10)).add(0, 38, 0);
            Vec3 look = village.add(toPost.scale(-40)).add(0, 5, 0);
            return CineCamera.glide(a, b, 200, () -> look, 60);
        }).hidden().farView();

        // --- наводчик: пульт, бинокль, пуск
        run(() -> cmd("time set 1000"));
        run(this::placeActor);
        run(() -> cmd("give @s airstrike:strike_designator[airstrike:loadout={weapon:\"drone\",count:3,spread:6}]"));
        // мишени в деревне: метки целей в HUD подписывают их именами (стойки — живые игроки в кадре не нужны)
        run(() -> {
            // мишени — по краям деревни: метки и пунктиры в кадре наводчика не сливаются с главной целью
            summonTarget("ENOTzRPG", target(side.scale(26)).add(toPost.scale(4)));
            summonTarget("WallyFillmark", target(side.scale(-24)).add(toPost.scale(-3)));
        });
        waitTicks(30);
        shot("remote").length(70).player(t -> new Pose(Vec3.ZERO, yawTo(post, village), 8, 0, 70)).hud()
                .cue(0, () -> mc.setScreen(new RemoteScreen()))
                .cue(69, () -> mc.setScreen(null));
        shot("operator").length(110).camera(() -> {
            Vec3 eye = post.add(0, 1.4, 0);
            double start = Math.toDegrees(Math.atan2(toPost.x, toPost.z)) + 180 - 40;
            return CineCamera.orbit(() -> eye, 3.6, 0.1, start, 0.55, 55);
        }).cue(15, () -> mc.options.keyUse.setDown(true));
        shot("scope").length(80).hud().player(t -> {
            float yaw = yawTo(post, village), pitch = pitchTo(post.add(0, 1.62, 0), village.add(0, 2, 0));
            double s = CineCamera.smooth(t / 60);
            return new Pose(Vec3.ZERO, yaw - 14 * (float) (1 - s), pitch - 3 * (float) (1 - s), 0, 70);
        }).cue(0, () -> mc.options.keyUse.setDown(true)).cue(76, Designator::fire);
        run(() -> {
            mc.options.keyUse.setDown(false);
            cmd("airstrike salvo drone 2 0 @e[tag=trailer_ENOTzRPG,limit=1]");
            cmd("airstrike drone @e[tag=trailer_WallyFillmark,limit=1]");
        });

        // --- пусковая за спиной: подъём пакета, поджиг, сход
        shot("launch_drone").after(() -> launcher(WeaponType.DRONE) != null, 100).noPrep().length(190).speed(0.5).camera(() -> {
            LauncherEntity l = launcher(WeaponType.DRONE);
            Vec3 at = l.position();
            Vec3 fwd = Vec3.directionFromRotation(0, l.getYRot());
            Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
            // сбоку и чуть сзади: видно подъём пакета, облако старта и уход шахеда в небо
            Vec3 a = ground(at.add(right.scale(11)).add(fwd.scale(-6))).add(0, 1.5, 0);
            Vec3 b = ground(at.add(right.scale(9)).add(fwd.scale(-3))).add(0, 1.9, 0);
            return CineCamera.dolly(a, b, 190, () -> at.add(fwd.scale(4)).add(0, 2.6, 0), 64);
        }).endWhen(() -> {
            // шахед отошёл от пусковой — сразу за ним, пока он в загруженном мире
            DroneEntity d = newest(DroneEntity.class);
            LauncherEntity l = launcher(WeaponType.DRONE);
            return d != null && l != null && d.distanceTo(l) > 45;
        }, 0);
        // разгон и отделение ускорителя — замедленно, вплотную, сверху (земля в кадре — видна скорость)
        shot("boost").after(() -> newest(DroneEntity.class) != null, 200).noPrep().length(56).speed(0.4).hidden()
                .camera(() -> chaseOf(newest(DroneEntity.class), 7.5, 1.6, 2.6, 6, 0, 58));
        // шахед на маршруте
        shot("cruise").after(() -> newest(DroneEntity.class) != null, 200).noPrep().length(110).hidden()
                .camera(() -> chaseOf(newest(DroneEntity.class), 8, 3, -3.5, 22, 6, 58));
        // глазами наводчика: стая заходит из-за спины и уходит к деревне; метки целей с номерами снарядов, пунктиры
        run(this::placeActor);
        shot("targets").length(110).speed(0.6).hud()
                .player(t -> new Pose(Vec3.ZERO, yawTo(post, village) + 6 - (float) CineCamera.smooth(t / 110) * 6,
                        pitchTo(post.add(0, 1.62, 0), village.add(0, 10, 0)), 0, 46))
                .when(() -> nearest(DroneEntity.class, post, 90) != null, 2400);
        // удар по деревне: с пригорка у крайних домов, замедленно
        shot("impact_drone").length(190).speed(0.5).hidden().camera(() -> {
            Vec3 from = ground(village.add(toPost.scale(30)).add(side.scale(14))).add(0, 7, 0);
            // пока шахеды далеко — узко, чтобы они не терялись точками в небе; к удару — широко
            return CineCamera.track(from, smoothFocus(() -> nearest(DroneEntity.class, village, 300), village.add(0, 3, 0), 0.2),
                    rangeFov(from, () -> nearest(DroneEntity.class, village, 300), 64, 24, 45));
        }).when(() -> nearest(DroneEntity.class, village, 170) != null, 2400)
                .endWhen(() -> nearest(DroneEntity.class, village, 600) == null, 70);

        // --- крылатая ракета: пуск, удар с земли; вторая — глазами ракеты
        run(this::placeActor);
        waitTicks(40);
        run(() -> fire("missile", target(side.scale(14))));
        shot("launch_missile").after(() -> launcher(WeaponType.MISSILE) != null, 100).noPrep().length(170).camera(() -> {
            LauncherEntity l = launcher(WeaponType.MISSILE);
            Vec3 at = l.position();
            Vec3 fwd = Vec3.directionFromRotation(0, l.getYRot());
            Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
            // впереди-сбоку: ракета сходит с направляющей и уходит над камерой
            Vec3 from = ground(at.add(fwd.scale(12)).add(right.scale(-7))).add(0, 2.4, 0);
            return CineCamera.track(from, smoothFocus(() -> nearest(CruiseMissileEntity.class, at, 150), at.add(0, 2.5, 0), 0.35), 68);
        });
        shot("impact_missile").length(90).speed(0.35).hidden().camera(() -> {
            Vec3 t = target(side.scale(14));
            Vec3 from = ground(t.add(toPost.scale(28)).add(side.scale(-16))).add(0, 4, 0);
            return CineCamera.track(from, smoothFocus(() -> nearest(CruiseMissileEntity.class, t, 300), t.add(0, 3, 0), 0.5),
                    rangeFov(from, () -> nearest(CruiseMissileEntity.class, t, 300), 62, 28, 45));
        }).when(() -> nearest(CruiseMissileEntity.class, target(side.scale(14)), 260) != null, 2400)
                .endWhen(() -> nearest(CruiseMissileEntity.class, target(side.scale(14)), 60) == null, 45);
        // наводчик у деревни: цель в зоне видео (дальность симуляции), ракета заходит издалека — сначала карта
        run(() -> placeActor(operatorNearVillage(), village));
        waitTicks(40);
        run(() -> fire("missile", target(side.scale(-4)).add(toPost.scale(-20))));
        shot("missile_camera").after(() -> !ua.zentix.airstrike.client.hud.ClientFlights.all().isEmpty(), 400)
                .prepare(() -> {
                    // утром лучи света Complementary (объёмный свет) в разы сильнее, чем в полдень, и на пути к солнцу
                    // заливают весь кадр с борта розовато-белой пеленой — видео снимается в полдень (дальше снова утро)
                    cmd("time set 6000");
                    // туман шейдерпака по дальности — от прорисовки: при 24 чанках земля видна раньше
                    renderDistance = mc.options.renderDistance().get();
                    mc.options.renderDistance().set(ONBOARD_RENDER_DISTANCE);
                    if (!ProjectileCamera.isActive()) ProjectileCamera.cycle();
                })
                // карта оператора, пока ракета дальше прорисовки: запись — за ~2 с до перехода на видео
                .when(() -> onMap() && missileRange() < 700, 3000)
                // карта, видео с борта: горка, пикирование, «сигнал потерян» и план попадания (облёт)
                // замедленно: ракета в мире (а значит, и видео с борта) — лишь последние ~250 блоков, это ~20 тиков
                .length(220).speed(0.2).hud().projectileCamera().readyChunks(ONBOARD_RENDER_DISTANCE - 2)
                .cueEnd(() -> {
                    ProjectileCamera.exit();
                    cmd("time set 1000");
                    mc.options.renderDistance().set(renderDistance);
                });

        // --- «Ланцет»: катапульта у поста, круг над деревней, пике
        run(this::placeActor);
        // пусковые шахедов и ракет убраны: катапульта встанет на их место, и в кадре будет только она
        run(() -> cmd("kill @e[type=airstrike:launcher]"));
        waitTicks(40);
        run(() -> fire("loiter", target(side.scale(8)).add(toPost.scale(-10))));
        shot("loiter_launch").after(() -> launcher(WeaponType.LOITER) != null, 100).noPrep().length(160).speed(0.5).camera(() -> {
            LauncherEntity l = launcher(WeaponType.LOITER);
            Vec3 at = l.position();
            Vec3 fwd = Vec3.directionFromRotation(0, l.getYRot());
            Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
            // сбоку от катапульты: рывок по направляющей, раскрытие крыльев, уход вверх
            Vec3 from = ground(at.add(right.scale(8)).add(fwd.scale(2))).add(0, 1.9, 0);
            return CineCamera.track(from, smoothFocus(() -> newest(LoiterEntity.class), at.add(fwd.scale(3)).add(0, 2, 0), 0.35), 62);
        }).endWhen(() -> {
            LoiterEntity e = newest(LoiterEntity.class);
            LauncherEntity l = launcher(WeaponType.LOITER);
            return e != null && l != null && e.distanceTo(l) > 40;
        }, 0);
        // игрок ближе к деревне: круг «Ланцета» выходит за дальность симуляции от поста, и снаряд уходил
        // в виртуальный полёт — пропадал из кадра до пике
        run(() -> placeActor(operatorNearVillage(), village));
        shot("loiter_strike").noPrep().length(260).speed(0.8)
                // за «Ланцетом» вплотную: круг над деревней, пике и взрыв прямо перед камерой (с земли пике закрывают дома)
                .camera(() -> chaseOf(nearest(LoiterEntity.class, target(side.scale(8)).add(toPost.scale(-10)), 200), 5.5, 1.8, 2.2, 8, 0, 60))
                .when(() -> nearest(LoiterEntity.class, village, 120) instanceof LoiterEntity e && e.flightPhase() == FlightPhase.LOITER, 1600)
                .endWhen(() -> nearest(LoiterEntity.class, village, 300) == null, 50);

        // --- B-2: снизу-сзади под брюхом — створки отсека открываются, бомба уходит вниз (замедленно). B-2 заходит
        // из-за спины стреляющего и до сброса летит вне мира; невидимка стоит на курсе за 150 блоков до цели
        // (и камера ждёт там же) — тикающие чанки вокруг него накрывают и открытие створок, и сброс. Сброс клиент
        // видит по фазе EGRESS.
        run(() -> placeHidden(bayWatch(), bayTarget()));
        shot("bomb_bay").onReady(() -> fire("bunker", bayTarget())).length(400).speed(0.25).hidden()
                .camera(() -> CineCamera.chase(() -> nearest(BomberEntity.class, bayTarget(), 900), bayWatch(), toPost.scale(-1),
                        26, -7, 9, 14, 0, 58))
                .when(() -> nearest(BomberEntity.class, bayTarget(), 900) instanceof BomberEntity b && b.flightPhase() != FlightPhase.EGRESS, 2400)
                .endWhen(() -> nearest(BomberEntity.class, bayTarget(), 900) instanceof BomberEntity b && b.flightPhase() == FlightPhase.EGRESS, 16);

        // --- B-2 и бетонобойная бомба: с высоты у деревни — пролёт, падение, бурение, подземный взрыв
        shot("bomber").onReady(() -> fire("bunker", target(side.scale(-12)))).length(260).speed(0.75).hidden().camera(() -> {
            Vec3 t = target(side.scale(-12));
            Vec3 from = ground(t.add(side.scale(-32)).add(toPost.scale(20))).add(0, 8, 0);
            // B-2 на 170 блоках — узко, чтобы был крупным; бомба у земли — широко, весь разрыв в кадре
            return CineCamera.track(from, smoothFocus(this::bomberFocus, t.add(0, 8, 0), 0.3), rangeFov(from, this::bomberSubject, 66, 16, 55));
        }).when(() -> bomberFocus() != null, 2400);

        // --- РСЗО: пакет из 40 труб у поста, залп очередью; разрывы накрывают деревню
        run(this::placeActor);
        waitTicks(40);
        run(() -> fire("salvo rocket 24 34", village.add(side.scale(-6))));
        shot("rocket_launch").after(() -> launcher(WeaponType.ROCKET) != null, 100).noPrep().length(150).speed(0.7).camera(() -> {
            LauncherEntity l = launcher(WeaponType.ROCKET);
            Vec3 at = l.position();
            Vec3 fwd = Vec3.directionFromRotation(0, l.getYRot());
            Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
            // низко сбоку и впереди пакета: трубы, поджиг, снаряды уходят над камерой дугами
            Vec3 a = ground(at.add(right.scale(9)).add(fwd.scale(5))).add(0, 2.2, 0);
            Vec3 b = ground(at.add(right.scale(8)).add(fwd.scale(8))).add(0, 2.0, 0);
            return CineCamera.dolly(a, b, 150, () -> at.add(fwd.scale(3)).add(0, 2.2, 0), 72);
        });
        shot("rocket_impact").noPrep().length(200).speed(0.6).camera(() -> {
            Vec3 t = village.add(side.scale(-6));
            Vec3 a = ground(t.add(toPost.scale(38)).add(side.scale(40))).add(0, 11, 0);
            Vec3 b = ground(t.add(toPost.scale(34)).add(side.scale(30))).add(0, 9, 0);
            return CineCamera.dolly(a, b, 200, () -> t.add(0, 3, 0), 60);
        }).when(() -> nearest(RocketEntity.class, village, 90) != null, 600)
                .endWhen(() -> nearest(RocketEntity.class, village, 600) == null, 60);

        // --- залпы на закате
        run(() -> {
            cmd("time set 12650");
            var c = AirstrikeConfig.SERVER;
            c.launchNearPlayer.set(false);
            c.droneFlightTime.set(8);
            c.missileFlightTime.set(6);
        });
        shot("salvo").onReady(() -> fire("salvo drone 8 24", village)).length(280).hidden().camera(() -> {
            // деревня в кадре неподвижно, камера чуть наезжает: стая заходит в кадр и накрывает дома
            Vec3 a = ground(village.add(toPost.scale(95)).add(side.scale(-25))).add(0, 22, 0);
            Vec3 b = ground(village.add(toPost.scale(80)).add(side.scale(-18))).add(0, 19, 0);
            return CineCamera.dolly(a, b, 280, () -> village.add(0, 8, 0), 58);
        }).when(() -> nearest(DroneEntity.class, village, 330) != null, 2400)
                .endWhen(() -> nearest(DroneEntity.class, village, 800) == null, 120);
        shot("salvo_missiles").onReady(() -> fire("salvo missile 5 26", village)).length(170).speed(0.6).hidden().camera(() -> {
            Vec3 a = ground(village.add(toPost.scale(70)).add(side.scale(45))).add(0, 30, 0);
            Vec3 b = ground(village.add(toPost.scale(62)).add(side.scale(30))).add(0, 26, 0);
            return CineCamera.dolly(a, b, 170, () -> village.add(0, 4, 0), 60);
        }).when(() -> nearest(CruiseMissileEntity.class, village, 350) != null, 2400);

        // --- кадр для иконки мода: шахед в разгоне снизу-сбоку, целиком, на фоне дневного неба. До МБР: удар
        // приходится по деревне, и после него у поста всё горит и дымит
        run(() -> {
            cmd("time set 6000");
            cmd("weather clear");
        });
        run(this::placeActor);
        waitTicks(100);
        run(() -> fire("drone", target(Vec3.ZERO)));
        shot("icon").after(() -> newest(DroneEntity.class) instanceof DroneEntity d && !d.flightPhase().onLauncher(), 1600).noPrep()
                .length(40).speed(0.3).hidden()
                .camera(() -> chaseOf(newest(DroneEntity.class), 11, -3.5, 4.5, 10, 0, 40));

        // --- МБР: старт за наводчиком в ~2 км от деревни, подрыв из-за его плеча, гриб издалека
        run(() -> {
            cmd("time set 12500");
            buildTower(viewpoint);
            placeActor(viewpoint.add(0, 12, 0), village);
        });
        shot("icbm").onReady(() -> cmd(String.format(Locale.ROOT, "airstrike nuke at %.1f %.1f %.1f 15 ground", village.x, village.y, village.z))).length(260).camera(() -> {
            Vec3 pad = ground(viewpoint.add(toView.scale(30)));
            Vec3 sideV = new Vec3(-toView.z, 0, toView.x);
            // в 70 блоках сбоку, чтобы облако старта не накрыло камеру; вслед за ракетой — наезд
            Vec3 from = ground(pad.add(sideV.scale(70)).add(toView.scale(-20))).add(0, 3, 0);
            Path p = CineCamera.track(from, smoothFocus(() -> newest(IcbmEntity.class), pad.add(0, 8, 0), 0.4), 50);
            return t -> t < 100 ? p.at(t) : CineCamera.zoom(p, 50, 22, 160).at(t - 100);
        });
        // подрыв из-за плеча наводчика: вспышка, шар, фронт доходит до вышки. Наводчик уже на второй вышке в 150 блоках
        // вбок — у первой висит облако старта
        run(() -> {
            watch = ground(viewpoint.add(new Vec3(-toView.z, 0, toView.x).scale(150)));
            buildTower(watch);
            placeActor(watch.add(0, 12, 0), village);
        });
        shot("nuke").noPrep().length(480).hud().camera(() -> {
            Vec3 sideV = new Vec3(-toView.z, 0, toView.x);
            Vec3 back = watch.add(0, 12, 0).add(toView.scale(3.2)).add(sideV.scale(1.3)).add(0, 1.9, 0);
            return CineCamera.track(back, () -> village.add(0, 600, 0), 68);
        }).when(() -> warningTicks() <= 200, 2400);
        // гриб издалека, ускоренно
        run(() -> {
            placeHidden(mushroomView());
        });
        shot("mushroom").length(1600).speed(4).hidden().camera(() -> {
            // шапка поднимается выше 5 км: кадр шире и выше, основание ствола — над нижней полосой кинокаше
            return CineCamera.track(mushroomView(), () -> village.add(0, 2800, 0), 75);
        }).when(() -> sinceDetonation() > 420, 3000);
        // чёрный дождь в следе осадков, счётчик Гейгера в руке
        run(() -> placeInFallout(false));
        waitTicks(40);
        run(() -> placeInFallout(true));
        run(() -> cmd("item replace entity @s weapon.mainhand with airstrike:geiger_counter"));
        shot("fallout").length(170).hud().player(t -> new Pose(Vec3.ZERO, yawTo(mc.player.position(), village) + 150 - (float) t * 0.35f,
                        -18 + (float) Math.sin(t / 40) * 4, 0, 70))
                .when(() -> sinceDetonation() > 3900, 6000);

        run(() -> {
            rec.finish();
            Airstrike.LOG.info("TRAILER done");
            // съёмочный мир одноразовый, а его сохранение после дальних перелётов (9 км) идёт минутами — выходим сразу
            Runtime.getRuntime().halt(0);
        });
    }

    // ================================================================ места

    /** Деревня поблизости и пост наводчика в ~150 блоках от неё на ровной сухой земле, лицом к деревне. */
    private void findLocations(ServerLevel level) {
        // равнинные деревни (открытое место, видно далеко; деревни есть только в мире со строениями — ClientScenario
        // включает их для трейлера): из нескольких ближайших — та, у которой лучший пост
        var structures = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var plains = net.minecraft.core.HolderSet.direct(structures.getHolderOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.VILLAGE_PLAINS));
        List<BlockPos> villages = new ArrayList<>();
        for (int[] o : new int[][]{{0, 0}, {1600, 0}, {0, 1600}, {-1600, 0}, {0, -1600}}) {
            var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, plains, new BlockPos(o[0], 64, o[1]), 60, false);
            if (found != null && villages.stream().noneMatch(v -> v.distManhattan(found.getFirst()) < 300)) villages.add(found.getFirst());
        }
        if (villages.isEmpty()) villages.add(BlockPos.ZERO);
        double best = Double.MAX_VALUE;
        for (BlockPos v : villages) {
            Vec3 center = new Vec3(v.getX() + 0.5, height(level, v.getX(), v.getZ()), v.getZ() + 0.5);
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI / 12;
                for (int r = 120; r <= 200; r += 20) {
                    int x = Mth.floor(center.x + Math.sin(a) * r), z = Mth.floor(center.z + Math.cos(a) * r);
                    double score = postScore(level, center, x, z);
                    if (score < best) {
                        best = score;
                        village = center;
                        post = new Vec3(x + 0.5, height(level, x, z), z + 0.5);
                    }
                }
            }
            Airstrike.LOG.info("TRAILER village {} best so far {}", center, best);
        }
        Vec3 d = post.subtract(village);
        toPost = new Vec3(d.x, 0, d.z).normalize();
        side = new Vec3(-toPost.z, 0, toPost.x);
        // съёмочная площадка: пост без травы и деревьев, прямой вид на деревню
        clearAround(level, BlockPos.containing(post), 34, true);
        for (double k = 0; k <= 1; k += 2.0 / post.distanceTo(village)) {
            Vec3 p = post.lerp(village, k);
            if (p.distanceTo(village) > 45) clearAround(level, BlockPos.containing(p.x, height(level, Mth.floor(p.x), Mth.floor(p.z)), p.z), 5, false);
        }
        Airstrike.LOG.info("TRAILER village {} post {} (score {})", village, post, best);
        findViewpoint(level);
    }

    /**
     * Чем меньше, тем лучше пост: ровно и сухо (пусковая), мало леса (уберём, но лес вокруг голой поляны странен),
     * деревню видно поверх рельефа (луч от глаз к деревне не уходит под землю), пост немного выше деревни.
     */
    private static double postScore(ServerLevel level, Vec3 village, int x, int z) {
        int y = height(level, x, z);
        Vec3 eye = new Vec3(x + 0.5, y + 1.6, z + 0.5), to = village.add(0, 3, 0);
        double blocked = 0;
        for (double k = 0.08; k < 0.9; k += 0.02) {
            Vec3 p = eye.lerp(to, k);
            blocked = Math.max(blocked, height(level, Mth.floor(p.x), Mth.floor(p.z)) - p.y);
        }
        double rise = y - village.y;
        return groundScore(level, x, z) + Math.max(0, blocked) * 30 + Math.max(0, 5 - rise) * 8;
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
     * Смотровая точка для ядерного удара в ~2 км от деревни (не в сторону поста — там полигон) и площадка МБР
     * в 30 блоках за ней ({@code NuclearStrikes.launchFrom}): обе ровные, сухие, без леса.
     */
    private void findViewpoint(ServerLevel level) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            Vec3 dir = new Vec3(Math.sin(a), 0, Math.cos(a));
            if (dir.dot(toPost) > 0.8) continue;
            for (int r = 1700; r <= 2100; r += 400) {
                Vec3 p = village.add(dir.scale(r)), pad = p.add(dir.scale(30));
                double score = groundScore(level, Mth.floor(p.x), Mth.floor(p.z)) + groundScore(level, Mth.floor(pad.x), Mth.floor(pad.z));
                if (score < best) {
                    best = score;
                    toView = dir;
                    viewpoint = p;
                }
            }
        }
        int x = Mth.floor(viewpoint.x), z = Mth.floor(viewpoint.z);
        viewpoint = new Vec3(x + 0.5, height(level, x, z), z + 0.5);
        Vec3 pad = viewpoint.add(toView.scale(30));
        clearAround(level, BlockPos.containing(viewpoint), 40, true);
        clearAround(level, BlockPos.containing(pad.x, height(level, Mth.floor(pad.x), Mth.floor(pad.z)), pad.z), 14, true);
        Airstrike.LOG.info("TRAILER viewpoint {} (score {})", viewpoint, best);
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
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** Точка на земле деревни со сдвигом (земля — по карте высот клиента, если чанк есть). */
    private Vec3 target(Vec3 offset) {
        return ground(village.add(offset));
    }

    /** Земля под точкой: по карте высот клиента, а если чанка у клиента нет — спросить сервер (он догрузит). */
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

    private void placeActor() {
        placeActor(post, village);
    }

    /** Наводчик у края деревни: снаряды над деревней — в дальности симуляции от него (в мире, видео с борта). */
    private Vec3 operatorNearVillage() {
        return ground(village.add(toPost.scale(60)).add(side.scale(30)));
    }

    /** Где невидимка ждёт B-2: на курсе захода, за 150 блоков до цели. */
    private Vec3 bayWatch() {
        return bayTarget().add(toPost.scale(150)).add(0, 40, 0);
    }

    /** Куда бьёт B-2 в плане с отсеком (в стороне от цели второго B-2 — воронки не совпадают). */
    private Vec3 bayTarget() {
        return target(side.scale(14)).add(toPost.scale(-28));
    }

    /** Мишень — стойка с именем (метка цели в HUD подписывает её так же, как игрока). */
    private static void summonTarget(String name, Vec3 at) {
        cmd(String.format(Locale.ROOT, "summon minecraft:armor_stand %.1f %.1f %.1f {CustomName:'\"%s\"',Tags:[\"trailer_%s\"],NoGravity:1b}",
                at.x, at.y, at.z, name, name));
    }

    /**
     * Откуда гриб виден целиком: 5 км к югу, взгляд на север — луна и солнце ходят с востока на запад и в кадр
     * не попадают.
     */
    private Vec3 mushroomView() {
        return new Vec3(village.x, 240, village.z + 5000);
    }

    /** Смотровая площадка 3×3 в 12 блоках над землёй (наводчик стоит на ней, видно поверх леса и холмов). */
    private static void buildTower(Vec3 base) {
        BlockPos p = BlockPos.containing(base);
        cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", p.getX() - 1, p.getY() + 11, p.getZ() - 1,
                p.getX() + 1, p.getY() + 11, p.getZ() + 1));
    }

    /** Наводчик стоит на посту лицом к цели, в творческом режиме, виден в кадре. */
    private void placeActor(Vec3 at, Vec3 face) {
        actor = true;
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

    /** То же, но лицом к точке: по взгляду стреляющего выбирается курс захода B-2. */
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
        placeActor(g.add(0, 1, 0), village);
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

    /** За кем следит зум плана B-2: бомба, пока она есть, иначе сам B-2. */
    @Nullable
    private Entity bomberSubject() {
        Vec3 t = target(side.scale(-12));
        BunkerBusterEntity bomb = nearest(BunkerBusterEntity.class, t, 600);
        return bomb != null ? bomb : nearest(BomberEntity.class, t, 450);
    }

    /** B-2 — пока он в 450 блоках; после сброса — бомба. */
    @Nullable
    private Vec3 bomberFocus() {
        float pt = CineCamera.partial();
        Vec3 t = target(side.scale(-12));
        BunkerBusterEntity bomb = nearest(BunkerBusterEntity.class, t, 600);
        if (bomb != null && bomb.flightPhase() != FlightPhase.DRILL) return bomb.getPosition(pt);
        if (bomb != null) return t.add(0, 1, 0);
        BomberEntity b = nearest(BomberEntity.class, t, 450);
        return b == null ? null : b.getPosition(pt);
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

    private Path chaseOf(@Nullable Entity e, double back, double up, double side, double lead, float roll, double fov) {
        final Entity target = e;
        return CineCamera.chase(() -> target != null && !target.isRemoved() ? target : null, back, up, side, lead, roll, fov);
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
        private Supplier<Path> camera;
        /** Вид глазами игрока: путь задаёт только взгляд. */
        private Path playerView;
        private boolean projectileCamera;
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
        private Path path;

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

        Shot camera(Supplier<Path> c) {
            camera = c;
            return this;
        }

        Shot player(Path view) {
            playerView = view;
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
            return recording == this ? rec.frames() * rec.step() : Math.max(0, t - 1 + CineCamera.partial());
        }

        @Override
        public boolean tick() {
            switch (phase) {
                case 0 -> {
                    if (after != null && !after.getAsBoolean() && waited++ < afterTimeout) return false;
                    if (after != null && waited > afterTimeout) {
                        Airstrike.LOG.warn("TRAILER {}: не дождались условия, план пропущен", name);
                        return true;
                    }
                    waited = 0;
                    hud = hudOn;
                    if (hiddenActor && actor) {
                        actor = false;
                        withPlayer((level, p) -> p.setGameMode(GameType.SPECTATOR));
                    }
                    if (camera != null) {
                        path = camera.get();
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
                        if (++waited % 200 == 0) Airstrike.LOG.info("TRAILER {}: ждём момент ({} тиков)", name, waited);
                        if (waited < whenTimeout) return false;
                        Airstrike.LOG.warn("TRAILER {}: момент не наступил, снимаем как есть", name);
                    }
                    if (selected()) {
                        rec.start(name, speed, hudOn);
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

        private void runCues() {
            for (Cue c : cues) if (c.at == t) c.action.run();
        }

        /** Камера на кадр: кинокамера — по пути, вид игрока — поворот головы. */
        void applyCamera() {
            double time = time();
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
        if (!started) {
            started = true;
            // папка записи: дубль отдельных планов (AIRSTRIKE_TRAILER_SHOTS) — в свою папку, монтаж берёт их поверх основной
            rec = new Recorder(mc.gameDirectory.toPath().resolve(System.getenv().getOrDefault("AIRSTRIKE_TRAILER_DIR", "trailer")));
            mc.getSoundManager().addListener(rec);
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
        java.util.Map<Integer, String> now = new java.util.HashMap<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof BomberEntity b) {
                if (b.flightPhase() == FlightPhase.EGRESS && released.add(b.getId()) && mark) rec.mark("release");
            } else if (e instanceof StrikeProjectile p && p.isActive()) {
                now.put(e.getId(), e.getType().toShortString());
            }
        }
        for (var e : seen.entrySet()) {
            if (mark && !now.containsKey(e.getKey())) rec.mark("gone:" + e.getValue());
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
        if (recording != null) {
            double partial = recording.time() - (recording.t - 1);
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
        rec.frame(recording.time(), mc.gameRenderer.getMainCamera());
        FrameClock.advanceNext(rec.step());
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
        float want = (float) Mth.clamp(fps * rec.step(), 1, 20);
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
