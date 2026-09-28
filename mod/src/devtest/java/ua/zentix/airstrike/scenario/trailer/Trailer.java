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
    private int detonationTick = -1;
    /** Время, на котором закончился прошлый план: камера между планами стоит там. */
    private double idleTime;
    private int clientTick;
    private java.util.Map<Integer, String> seen = java.util.Map.of();

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
        }).hidden();

        // --- наводчик: пульт, бинокль, пуск
        run(() -> cmd("time set 1000"));
        run(this::placeActor);
        run(() -> cmd("give @s airstrike:strike_designator[airstrike:loadout={weapon:\"drone\",count:3,spread:6}]"));
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
        run(() -> mc.options.keyUse.setDown(false));

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
        // удар по деревне: с пригорка у крайних домов, замедленно
        shot("impact_drone").length(150).speed(0.5).hidden().camera(() -> {
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
        run(this::placeActor);
        waitTicks(40);
        run(() -> fire("missile", target(side.scale(-4)).add(toPost.scale(-20))));
        shot("missile_camera").after(() -> !ua.zentix.airstrike.client.hud.ClientFlights.all().isEmpty(), 400)
                .prepare(() -> {
                    if (!ProjectileCamera.isActive()) ProjectileCamera.cycle();
                })
                .when(() -> mc.getCameraEntity() instanceof CruiseMissileEntity m && (m.flightPhase() == FlightPhase.POP_UP
                        || m.flightPhase() == FlightPhase.TERMINAL), 3000)
                // горка, пикирование, «сигнал потерян» и план попадания (облёт) — до конца
                .length(160).hud().projectileCamera()
                .cueEnd(ProjectileCamera::exit);

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
        shot("loiter_strike").noPrep().length(260).speed(0.8)
                // за «Ланцетом» вплотную: круг над деревней, пике и взрыв прямо перед камерой (с земли пике закрывают дома)
                .camera(() -> chaseOf(nearest(LoiterEntity.class, target(side.scale(8)).add(toPost.scale(-10)), 200), 5.5, 1.8, 2.2, 8, 0, 60))
                .when(() -> nearest(LoiterEntity.class, village, 120) instanceof LoiterEntity e && e.flightPhase() == FlightPhase.LOITER, 1600)
                .endWhen(() -> nearest(LoiterEntity.class, village, 300) == null, 50);

        // --- B-2 и бетонобойная бомба: с высоты у деревни — пролёт, падение, бурение, подземный взрыв
        shot("bomber").onReady(() -> fire("bunker", target(side.scale(-12)))).length(260).speed(0.75).hidden().camera(() -> {
            Vec3 t = target(side.scale(-12));
            Vec3 from = ground(t.add(side.scale(-45)).add(toPost.scale(30))).add(0, 14, 0);
            return CineCamera.track(from, smoothFocus(this::bomberFocus, t.add(0, 8, 0), 0.3), 66);
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
            Vec3 a = ground(at.add(right.scale(13)).add(fwd.scale(7))).add(0, 1.2, 0);
            Vec3 b = ground(at.add(right.scale(11)).add(fwd.scale(11))).add(0, 1.0, 0);
            return CineCamera.dolly(a, b, 150, () -> at.add(fwd.scale(4)).add(0, 3, 0), 72);
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
            return CineCamera.track(mushroomView(), () -> village.add(0, 1800, 0), 60);
        }).when(() -> detonationTick >= 0 && clientTick - detonationTick > 420, 3000);
        // чёрный дождь в следе осадков, счётчик Гейгера в руке
        run(() -> placeInFallout(false));
        waitTicks(40);
        run(() -> placeInFallout(true));
        run(() -> cmd("give @s airstrike:geiger_counter"));
        shot("fallout").length(170).hud().player(t -> new Pose(Vec3.ZERO, yawTo(mc.player.position(), village) + 150 - (float) t * 0.35f,
                        -18 + (float) Math.sin(t / 40) * 4, 0, 70))
                .when(() -> detonationTick >= 0 && clientTick - detonationTick > 3900, 6000);

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

    /**
     * В след осадков, куда они придут к ~2.5 мин после подрыва (как в ядерном сценарии): сначала невидимкой
     * (прогрузить), потом на землю, на площадку из камня (вдруг там вода).
     */
    private void placeInFallout(boolean land) {
        var list = ClientNuclear.detonations();
        if (list.isEmpty()) return;
        var d = list.getLast().d;
        double m = Math.min(3000, d.windSpeed() * 150);
        double x = d.burst().x + Math.cos(d.windDir()) * m * d.scale(), z = d.burst().z + Math.sin(d.windDir()) * m * d.scale();
        if (!land) {
            placeHidden(new Vec3(x, 200, z));
            return;
        }
        Vec3 g = ground(new Vec3(x, 0, z));
        BlockPos b = BlockPos.containing(g);
        // поляна: чёрный дождь виден на фоне неба, а не в листве
        MinecraftServer server = mc.getSingleplayerServer();
        BlockPos c = b;
        int y = server.submit(() -> {
            clearAround(server.overworld(), c, 18, true);
            return server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, c.getX(), c.getZ());
        }).join();
        g = new Vec3(g.x, y, g.z);
        b = BlockPos.containing(g);
        cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", b.getX() - 1, b.getY(), b.getZ() - 1, b.getX() + 1, b.getY(), b.getZ() + 1));
        placeActor(g.add(0, 1, 0), village);
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
                    if (!prep && atReady != null) atReady.run();
                    Airstrike.LOG.info("TRAILER {}: подготовка", name);
                }
                case 1 -> {
                    followCamera();
                    readyFor = worldReady() ? readyFor + 1 : 0;
                    if (readyFor >= 10 && waited >= 40 || waited > 1200) {
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
                        rec.start(name, speed);
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
            rec = new Recorder(mc.gameDirectory.toPath().resolve("trailer"));
            mc.getSoundManager().addListener(rec);
            mc.options.hideGui = false;
        }
        clientTick++;
        if (detonationTick < 0 && !ClientNuclear.detonations().isEmpty()) {
            detonationTick = clientTick;
            if (rec.recording()) rec.mark("detonation");
        }
        if (rec.recording()) markImpacts();
        while (!steps.isEmpty()) {
            if (!steps.peek().tick()) break;
            steps.poll();
        }
    }

    /**
     * Перед кадром: при записи — доля тика ровно под момент кадра (кадр после тика n показывает мир между тиками
     * n−1 и n); если клиент ещё не дотикал до момента (кадр отрисовки не в счёт) — кадр не снимается.
     */
    /** Снаряды, что были в кадре и пропали (взрыв): отметки для монтажа — звук приходит позже картинки. */
    private void markImpacts() {
        java.util.Map<Integer, String> now = new java.util.HashMap<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof StrikeProjectile p && p.isActive() && !(p instanceof BomberEntity)) {
                now.put(e.getId(), e.getType().toShortString());
            }
        }
        for (var e : seen.entrySet()) {
            if (!now.containsKey(e.getKey())) rec.mark("gone:" + e.getValue());
        }
        seen = now;
    }

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

    private void setTickRate(float rate) {
        if (rate == tickRate) return;
        tickRate = rate;
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> server.tickRateManager().setTickRate(rate));
    }

    /** Мир вокруг камеры получен и собран в секции для отрисовки. */
    private boolean worldReady() {
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        int cx = Mth.floor(cam.x) >> 4, cz = Mth.floor(cam.z) >> 4;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
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
