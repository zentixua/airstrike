package ua.zentix.incoming.scenario.trailer;

import com.google.gson.JsonArray;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.cam.ProjectileCamera;
import ua.zentix.incoming.client.hud.Alerts;
import ua.zentix.incoming.client.hud.ClientFlights;
import ua.zentix.incoming.client.nuclear.ClientNuclear;
import ua.zentix.incoming.client.nuclear.NukeArming;
import ua.zentix.incoming.client.screen.MapScreen;
import ua.zentix.incoming.client.screen.RemoteScreen;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;
import ua.zentix.incoming.strike.WeaponType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * «Война двух городов» на карте Zearth: Запад (батарея на полосе западного аэропорта) бьёт по Востоку (город у 3100 400),
 * Восток отвечает; в конце — МБР с Запада и воздушный ядерный взрыв над Востоком. Планы названы как в монтаже
 * ({@code tools/trailer/edit.py}: pNN и буква дубля) и снимаются не по порядку ролика, а по порядку постановки:
 * разрушения копятся, поэтому спокойная площадь снимается раньше горящей. Удары — командами мода от игрока: пуски на
 * Западе летят настоящий путь через карту, а удары в кадре на Востоке приходят с пусковых в 700–1500 блоках к западу,
 * за кадром. Статисты — боты Almighty ({@link Cast}): жители ({@link Crowd}) и бойцы ({@link Fight}).
 * <p>
 * Каждый план длиной в свой отрезок монтажа с запасом и начинается за секунду-две до главного: монтаж берёт план с
 * начала или с отметки ({@code siren} — тревога над игроком, {@code front} — фронт ядерного взрыва у камеры, и
 * отметки {@link Film}).
 * <p>
 * {@code INCOMING_WAR_PART} — снять только часть: {@code east}, {@code west}, {@code nuke}; {@code nukeB} и {@code nukeC} —
 * ещё по ядерке, каждая на свежей копии мира (планы волны с других мест); по умолчанию — всё, кроме {@code nukeB} и
 * {@code nukeC}.
 */
final class War {
    // ================================================================ места Zearth (y = 0 — земля этой колонки)

    static final Vec3 SQUARE = new Vec3(3175.5, 0, 274.5);
    static final Vec3 GOV = new Vec3(3225.5, 100, 128.5);
    /** Крыша высотки у площади (верх 161), южный край: вид на небоскрёбы центра. */
    static final Vec3 ROOF = new Vec3(3175.5, 162, 361.5);
    /** Небоскрёбы центра: верх крыши (цель удара — крыша). */
    static final Vec3 T1 = new Vec3(3151.5, 214, 461.5), T2 = new Vec3(3072.5, 255, 504.5), T3 = new Vec3(2977.5, 230, 602.5),
            T4 = new Vec3(3154.5, 234, 566.5), T5 = new Vec3(3061.5, 228, 715.5);
    /** Спуск в метро у депо: лестница вниз по +x от улицы до платформы. */
    static final Vec3 SUB_TOP = new Vec3(3362.5, 63, 269.5), SUB_BOTTOM = new Vec3(3392.5, 47, 269.5);
    /** Улица у южного края площади (площадь — x 3110–3240, z 238–310, земля 62). */
    static final Vec3 AVENUE = new Vec3(3200.5, 0, 292.5);
    /** Эпицентр воздушного взрыва; крыша финала в ~900 блоках от него. */
    static final Vec3 GZ = new Vec3(3300.5, 64, 640.5);
    static final Vec3 FINAL_ROOF = new Vec3(2849.5, 116, -157.5);
    /** Улица между домами в ~890 блоках от эпицентра, вдоль неё — на эпицентр. */
    static final Vec3 WAVE_STREET = new Vec3(2950.5, 64, -178.5);
    /** Взгорок к северо-западу, ~1250 блоков от эпицентра. */
    static final Vec3 WAVE_HILL = new Vec3(2600.5, 0, -400.5);
    /**
     * Камера над аэропортом в ~1075 блоках от эпицентра, высоко; под ней — городок в ~780 блоках, через него идёт стена;
     * взгляд — на ~620 блоков от эпицентра, вверху кадра горизонт.
     */
    static final Vec3 WAVE_HIGH = new Vec3(2780, 330, -300), WAVE_TOWN = new Vec3(2923, 0, -42), WAVE_LOOK = new Vec3(3000.5, 0, 98.5);
    /** Малый аэропорт Востока: торец полосы, полоса на восток. */
    static final Vec3 AIRFIELD = new Vec3(2836.5, 67, -216.5);

    /** Полоса западного аэропорта, на ней батарея: ряд крылатых ракет, ряд «Градов» и шахедов. */
    static final double BATTERY_Y = 68, ROW_MISSILE = 516.5, ROW_ROCKET = 534.5;
    static final Vec3 BATTERY = new Vec3(-1490.5, 68, 525.5);
    /** Наводчик батареи и место расчёта ночью. */
    static final Vec3 POST = new Vec3(-1545.5, 68, 525.5), NIGHT_POST = new Vec3(-1460.5, 68, 525.5);
    /** Цель «Градов» батареи: позиции Востока за рекой (дальность «Града» — сотни блоков, не километры). */
    static final Vec3 ROCKET_TARGET = new Vec3(-650.5, 0, 560.5);

    private final Trailer film;
    private final Minecraft mc;
    private Crowd crowd, roof;
    private Fight street;
    private final List<BlockPos> missileRack = new ArrayList<>(), rocketRack = new ArrayList<>(), droneRack = new ArrayList<>();
    private static Field bannerField;
    private int lastBanner;
    private Object frontMarkedIn;

    War(Trailer film) {
        this.film = film;
        this.mc = film.mc;
        NeoForge.EVENT_BUS.addListener(this::watch);
    }

    void stage() {
        String part = System.getenv().getOrDefault("INCOMING_WAR_PART", "all");
        boolean all = part.equals("all");
        config();
        if (all || part.equals("east")) {
            dawn();
            firstStrike();
        }
        if (all || part.equals("west")) battery();
        if (all || part.equals("east")) {
            sky();
            streets();
            throughFire();
        }
        if (part.equals("nuke")) {
            // своя копия мира: пусковые и бой на площади — как после частей west и east
            racks();
            fight();
        }
        if (all || part.equals("nuke")) nuke();
        if (part.equals("nukeB")) nukeFar();
        if (part.equals("nukeC")) nukeNear();
    }

    // ================================================================ мир и помощники

    private void config() {
        film.run(() -> {
            Film.cmd("difficulty peaceful");
            Film.cmd("gamerule doFireTick false");
            Film.cmd("gamerule mobGriefing false");
            var c = IncomingConfig.SERVER;
            c.launchNearPlayer.set(false);
            // полёт от пусковой до цели — по прямой: пусковые за кадром в 700–1500 блоках
            c.droneFlightTime.set(5);
            c.missileFlightTime.set(5);
            c.bomberFlightTime.set(5);
            c.nukeFlightTime.set(1100);
            c.siren.set(true);
            c.maxSalvo.set(100);
        });
    }

    Cast cast() {
        return film.cast();
    }

    MinecraftServer server() {
        return mc.getSingleplayerServer();
    }

    Crowd crowd() {
        if (crowd == null) crowd = new Crowd(cast(), server());
        return crowd;
    }

    /** Земля (где стоять) в этой колонке; чанк грузится сразу (сценарию можно). Из любого потока. */
    Vec3 g(double x, double z) {
        MinecraftServer s = server();
        if (s.isSameThread()) return new Vec3(x, groundY(s.overworld(), x, z), z);
        int y = s.submit(() -> groundY(s.overworld(), x, z)).join();
        return new Vec3(x, y, z);
    }

    Vec3 g(Vec3 p) {
        return g(p.x, p.z);
    }

    /** Точка как есть, а с y = 0 — на земле. */
    Vec3 at(Vec3 p) {
        return p.y <= 0 ? g(p) : p;
    }

    /** Первый блок над землёй; чанк грузится сразу ({@code Level.getHeight} у незагруженного даёт низ мира). */
    static int groundY(ServerLevel level, double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        return level.getChunk(bx >> 4, bz >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx & 15, bz & 15) + 1;
    }

    /** Игрок — на месте, взгляд на точку: сервер шлёт мир вокруг игрока. */
    void place(Vec3 p, Vec3 look) {
        Vec3 q = at(p), l = at(look);
        float yaw = Film.yawTo(q, l), pitch = Film.pitchTo(q.add(0, 1.62, 0), l);
        film.withPlayer((level, sp) -> sp.teleportTo(level, q.x, q.y, q.z, yaw, pitch));
    }

    /** Игрок в кадре сам (его глаза, интерфейс мода, предмет в руке): творческий режим. */
    void actor(Vec3 p, Vec3 look, String item) {
        film.run(() -> {
            film.actor = true;
            film.withPlayer((level, sp) -> sp.setGameMode(GameType.CREATIVE));
            place(p, look);
            if (item != null) Film.cmd("item replace entity @s weapon.mainhand with " + item);
        });
        film.waitTicks(30);
    }

    /** Невидимка-зритель — над местом следующего плана: мир там начинает грузиться заранее. */
    void hiddenAt(Vec3 p, Vec3 look) {
        film.run(() -> place(at(p).add(0, 20, 0), look));
        film.waitTicks(20);
    }

    static String xyz(Vec3 p) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", p.x, p.y, p.z);
    }

    /** Залп по месту {@code target} (y = 0 — земля) с пусковой в {@code from} (по горизонтали): пуск и полёт настоящие. */
    void salvo(String weapon, int count, int spread, Vec3 target, Vec3 from) {
        Film.cmd(String.format(Locale.ROOT, "incoming salvo %s %d %d at %s from %.1f %.1f", weapon, count, spread, xyz(at(target)), from.x, from.z));
    }

    /** Пусковая на прямой через точку {@code over} к цели, в {@code d} блоках позади точки: снаряд пройдёт над ней. */
    static Vec3 behind(Vec3 over, Vec3 target, double d) {
        Vec3 dir = target.subtract(over).multiply(1, 0, 1).normalize();
        return over.subtract(dir.scale(d));
    }

    /** Снаряд игрока (по данным сервера — и вне мира) ближе {@code r} к точке. */
    /** Снаряд этого оружия ближе {@code r} к точке: чужие полёты (шахеды прошлых планов идут через весь город) не в счёт. */
    static boolean flightNear(Vec3 p, double r, WeaponType weapon) {
        for (var f : ClientFlights.all()) if (f.weapon() == weapon && f.position(1).distanceTo(p) < r) return true;
        return false;
    }

    /** То же по горизонтали: снаряд над точкой на любой высоте. */
    static boolean flightOver(Vec3 p, double r) {
        for (var f : ClientFlights.all()) {
            Vec3 q = f.position(1);
            if (Math.hypot(q.x - p.x, q.z - p.z) < r) return true;
        }
        return false;
    }

    /** Снаряд в мире был ближе {@code r} к точке и пропал (взрыв): конец плана. На каждый план — своё. */
    BooleanSupplier hit(Vec3 p, double r) {
        boolean[] seen = {false};
        Vec3[] q = {null};
        return () -> {
            if (q[0] == null) q[0] = at(p);
            boolean near = film.nearest(StrikeProjectile.class, q[0], r) != null;
            seen[0] |= near;
            return seen[0] && !near;
        };
    }

    /**
     * Тиков до прихода фронта ядерного взрыва в точку (уже прошёл — меньше нуля; подрыва нет — очень много): по
     * расстоянию до точки подрыва, как руины и стена пыли (воздушный подрыв 15 кт — в ~590 блоках над землёй).
     */
    static double frontIn(Vec3 p) {
        double best = Double.MAX_VALUE;
        for (var a : ClientNuclear.detonations()) {
            best = Math.min(best, a.d.arrivalTicks(p.distanceTo(a.d.burst())) - a.ticks(0));
        }
        return best;
    }

    /** Тиков осталось у надписи тревоги над хотбаром ({@link Alerts}): больше, чем в прошлом тике, — тревога. */
    static int banner() {
        try {
            if (bannerField == null) {
                bannerField = Alerts.class.getDeclaredField("bannerLeft");
                bannerField.setAccessible(true);
            }
            return bannerField.getInt(null);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Отметки для монтажа: тревога ({@code siren}) и фронт ядерного взрыва у камеры ({@code front}), раз за план. */
    private void watch(ClientTickEvent.Post e) {
        if (mc.level == null || film.rec == null) return;
        int b = banner();
        boolean rec = film.rec.recording();
        if (rec && b > lastBanner) film.rec.mark("siren");
        lastBanner = b;
        if (rec && film.recording != null && frontMarkedIn != film.recording
                && frontIn(mc.gameRenderer.getMainCamera().getPosition()) <= 0) {
            film.rec.mark("front");
            frontMarkedIn = film.recording;
        }
    }

    static JsonArray lookUp(Vec3 at) {
        JsonArray a = Cast.steps();
        a.add(Cast.step("wait", 4));
        a.add(Cast.lookAt(at));
        return a;
    }

    /** Бот следит глазами за ближайшим снарядом этого типа (каждые 2 тика), не дольше {@code ticks} тиков. */
    private void trackLook(String bot, Class<? extends Entity> type, int ticks) {
        MinecraftServer server = server();
        int[] left = {ticks / 2};
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            var sp = cast().player(bot);
            if (sp == null || --left[0] < 0) return;
            Entity best = null;
            double bd = 300 * 300;
            for (Entity x : sp.serverLevel().getEntitiesOfClass(type, sp.getBoundingBox().inflate(300))) {
                double d = x.distanceToSqr(sp);
                if (d < bd) {
                    bd = d;
                    best = x;
                }
            }
            if (best != null) {
                JsonArray a = Cast.steps();
                a.add(Cast.lookAt(best.position()));
                cast().act(bot, a, true);
            }
            server.tell(new TickTask(server.getTickCount() + 2, step[0]));
        };
        step[0].run();
    }

    // ================================================================ I · рассвет и утро над Востоком

    private void dawn() {
        // p03: пролёт на высоте над Востоком к небоскрёбам, на которые ложится первое солнце
        film.run(() -> Film.cmd("time set 23300"));
        // над облаками: башни центра встают из них (с шейдерами — тёплый рассвет)
        hiddenAt(new Vec3(2890, 220, 340), T2);
        film.shot("p03").length(60).hidden().farView().camera(() -> {
            Vec3 look = new Vec3(3060, 200, 560);
            return CineCamera.glide(new Vec3(2880, 240, 330), new Vec3(2905, 237, 352), 60, () -> look, 52);
        });

        // p04: утро, площадь у госкомплекса — жители идут по своим делам (та же точка в p44: площадь горит и пуста)
        film.run(() -> Film.cmd("time set 1000"));
        hiddenAt(SQUARE, GOV);
        film.onServer(level -> {
            RandomSource r = RandomSource.create(4);
            for (int i = 0, tries = 0; i < 18 && tries < 400; tries++) {
                double x = 3040 + r.nextDouble() * 170, z = 222 + r.nextDouble() * 100;
                int y = groundY(level, x, z);
                if (y > 66) continue;  // крыша, а не площадь
                crowd().add("Cit" + i++, new Vec3(x, y, z), 18);
            }
        });
        settle();
        film.shot("p04").length(40).hidden().camera(() -> {
            Vec3 a = g(3116.5, 306.5).add(0, 5.5, 0);
            return CineCamera.dolly(a, a.add(4, 0.3, -3), 40, () -> GOV, 50);
        });
        film.run(() -> crowd().removeAll());
    }

    // ================================================================ II · первый удар по Востоку, тревога

    private void firstStrike() {
        // p12: окраина — шахеды проходят низко над жителем со стороны затылка, он поднимает голову
        Vec3 resident = new Vec3(2905.5, 0, 418.5), droneTarget = new Vec3(3070.5, 0, 418.5);
        hiddenAt(resident, droneTarget);
        film.run(() -> cast().spawn("Hans", g(resident), -90, null));
        settle();
        film.shot("p12").length(45).hidden()
                .onReady(() -> salvo("drone", 3, 10, droneTarget, behind(resident, droneTarget, 700)))
                .camera(() -> CineCamera.shoulder(() -> film.bot("Hans"), 2.2, -0.35, 0.7, () -> at(droneTarget).add(0, 40, 0), 62))
                .when(() -> flightOver(at(resident), 70), 3000)
                .cue(8, () -> cast().act("Hans", lookUp(at(droneTarget).add(0, 60, 0)), true));
        film.run(() -> cast().remove("Hans"));

        // p13: тихое утро, житель на крыше, впереди небоскрёбы; ракета мелькает над крышами и входит в башню за ~370 блоков
        hiddenAt(ROOF, T5);
        film.run(() -> cast().spawn("Ilya", ROOF, Film.yawTo(ROOF, T5), null));
        settle();
        // в башню ниже облаков (верх T5 — над ними) и с юга: взрыв на грани, которую видит камера
        Vec3 t5 = T5.add(0, -60, 0);
        film.shot("p13").length(140).hidden()
                .onReady(() -> salvo("missile", 1, 0, t5, new Vec3(2700, 0, -300)))
                .camera(() -> {
                    Vec3 eye = ROOF.add(0, 1.62, 0);
                    Vec3 back = eye.subtract(T5.subtract(eye).multiply(1, 0, 1).normalize().scale(3.4)).add(0.9, 0.35, 0);
                    return CineCamera.track(back, () -> T5.add(0, -30, 0), 46);
                })
                .when(() -> flightNear(t5, 340, WeaponType.MISSILE), 3000)
                .endWhen(hit(t5, 120), 40);
        film.run(() -> cast().remove("Ilya"));

        // p14: на улице сирена — следующие ракеты идут сюда; первый кадр с интерфейсом: ⚠ MISSILE THREAT ⚠ (отметка siren)
        Vec3 hit = new Vec3(3140.5, 0, 300.5);
        film.run(() -> place(AVENUE, at(hit).add(0, 40, 0)));
        film.waitTicks(40);
        film.shot("p14").length(110).hud()
                .onReady(() -> salvo("missile", 4, 30, hit, new Vec3(1500, 0, 300)))
                .player(t -> new Pose(Vec3.ZERO, Film.yawTo(AVENUE, hit) - 25 + (float) CineCamera.smooth(t / 110) * 30,
                        -30 + (float) CineCamera.smooth(t / 110) * 12, 0, 70))
                .when(() -> flightNear(at(hit), 1360, WeaponType.MISSILE), 3000);

        // p15: люди бегут к спуску в метро, FPV ныряет за ними вниз по лестнице
        hiddenAt(SUB_TOP.add(-30, 0, 0), SUB_TOP);
        film.onServer(level -> {
            RandomSource r = RandomSource.create(15);
            for (int i = 0, tries = 0; i < 10 && tries < 200; tries++) {
                double x = SUB_TOP.x - 12 - r.nextDouble() * 16, z = SUB_TOP.z - 6 + r.nextDouble() * 12;
                int y = groundY(level, x, z);
                if (y > SUB_TOP.y + 2) continue;
                crowd().add("Run" + i++, new Vec3(x, y, z), 3);
            }
        });
        settle();
        film.run(() -> film.withServer(s -> crowd().flee(SUB_TOP, 60, SUB_BOTTOM.add(6, 0, 0))));
        film.waitTicks(10);
        film.shot("p15").length(55).hidden()
                .camera(() -> CineCamera.fpv(List.of(
                        SUB_TOP.add(-24, 4, 0.2), SUB_TOP.add(-10, 2.4, 0), SUB_TOP.add(-1, 1.4, 0), SUB_TOP.add(6, -0.6, 0),
                        SUB_TOP.add(12, -4.6, 0), SUB_TOP.add(20, -10, 0), SUB_BOTTOM.add(0, 1.6, 0), SUB_BOTTOM.add(10, 1.6, 0)),
                        1.0, 5, 0.6, 0.05, 74));

        // p16: метро изнутри: удары наверху доходят глухо
        Vec3 above = SUB_BOTTOM.add(4, 17, 0);
        film.shot("p16").length(50).hidden()
                .onReady(() -> salvo("missile", 2, 12, above, new Vec3(2000, 0, 270)))
                .camera(() -> CineCamera.dolly(SUB_BOTTOM.add(6, 1.6, 0), SUB_BOTTOM.add(7.5, 1.55, 0), 50,
                        () -> SUB_BOTTOM.add(-8, 3, 0), 72))
                .when(() -> flightNear(above, 110, WeaponType.MISSILE), 3000);

        // p18: FPV вырывается из метро на улицу, где ложится «Град», разворот над перекрёстком
        Vec3 crossing = new Vec3(3330.5, 0, 300.5);
        film.shot("p18").length(65).hidden()
                .onReady(() -> salvo("rocket", 12, 16, crossing, new Vec3(2750, 0, 300)))
                .camera(() -> {
                    Vec3 c = at(crossing);
                    return CineCamera.fpv(List.of(
                            SUB_BOTTOM.add(4, 1.6, 0), SUB_BOTTOM.add(-2, 1.6, 0), SUB_TOP.add(18, -8, 0), SUB_TOP.add(6, -1.4, 0),
                            SUB_TOP.add(-2, 2.5, 0), SUB_TOP.add(-12, 7, 8), c.add(-4, 9, -10), c.add(12, 11, 6),
                            c.add(30, 12, 2)), 1.6, 7, 0.9, 0.08, 78);
                })
                .when(() -> film.nearest(RocketEntity.class, at(crossing), 220) != null, 3000);
        film.run(() -> crowd().removeAll());

        // p17: из квартиры через окно: вспышка удара, стекло влетает в комнату
        Vec3 room = new Vec3(3060.5, 0, 352.5);
        Vec3 blast = new Vec3(3062.5, 0, 338.5);  // на тротуаре перед окном, за стеной улицы не спрятан
        hiddenAt(room, blast);
        film.onServer(level -> buildRoom(level, room));
        film.shot("p17").length(40).hidden()
                .onReady(() -> salvo("missile", 1, 0, blast, new Vec3(2200, 0, 318)))
                .camera(() -> {
                    Vec3 floor = new Vec3(room.x, roomFloor, room.z);
                    return CineCamera.dolly(floor.add(0.2, 1.55, 1.4), floor.add(0.1, 1.57, 1.1), 40, () -> at(blast).add(0, 2, 0), 70);
                })
                .when(() -> flightNear(at(blast), 60, WeaponType.MISSILE), 3000);

        // p19: со спины человека на крыше: горизонт вспыхивает ударами (a), ракеты проходят над головой (b)
        // земли с крыши не видно за домами — горизонт вспыхивает верхними этажами башен
        List<Vec3> towers = List.of(new Vec3(3028.5, 165, 509.5), new Vec3(3154.5, 190, 566.5), new Vec3(2911.5, 175, 600.5),
                new Vec3(3061.5, 180, 715.5));
        Vec3 farA = new Vec3(3040.5, 175, 590.5), farB = new Vec3(2916.5, 0, 800.5);  // farB — за башнями на той же линии
        hiddenAt(ROOF, farA);
        film.run(() -> cast().spawn("Oleg", ROOF, Film.yawTo(ROOF, farA), null));
        settle();
        film.shot("p19a").length(50).hidden()
                .onReady(() -> {
                    // с севера, как стоит камера: удар в видимую грань; 800–920 блоков — один за другим через ~10 тиков
                    for (int i = 0; i < towers.size(); i++) salvo("missile", 1, 0, towers.get(i), towers.get(i).add(60 - i * 40, 0, -800 - i * 40));
                })
                .camera(() -> CineCamera.shoulder(() -> film.bot("Oleg"), 2.6, 0.4, 1.3, () -> farA, 55))
                .when(() -> flightNear(towers.get(0), 160, WeaponType.MISSILE), 3000);
        film.run(() -> cast().act("Oleg", lookUp(farA), true));
        film.shot("p19b").length(50).hidden()
                .onReady(() -> salvo("missile", 4, 20, farB, behind(ROOF, at(farB), 900)))
                .camera(() -> CineCamera.shoulder(() -> film.bot("Oleg"), 2.6, 0.4, 1.3, () -> farA.add(0, 30, 0), 60))
                .when(() -> flightNear(ROOF, 160, WeaponType.MISSILE), 3000)
                .cue(10, () -> cast().act("Oleg", lookUp(farA.add(0, 120, 0)), true));
        film.run(() -> cast().remove("Oleg"));
    }

    // ================================================================ Запад: батарея, пуски

    /** Батарея на полосе: ряд крылатых ракет и ряд «Градов» с шахедами; ракеты и шахеды — по Востоку за 4,5 км. */
    private void racks() {
        hiddenAt(BATTERY, BATTERY.add(60, 0, 0));
        film.onServer(level -> {
            missileRack.clear();
            rocketRack.clear();
            droneRack.clear();
            for (int i = 0; i < 10; i++) missileRack.add(BlockPos.containing(-1528.5 + i * 9, BATTERY_Y, ROW_MISSILE));
            for (int i = 0; i < 5; i++) rocketRack.add(BlockPos.containing(-1528.5 + i * 18, BATTERY_Y, ROW_ROCKET));
            for (int i = 0; i < 5; i++) droneRack.add(BlockPos.containing(-1519.5 + i * 18, BATTERY_Y, ROW_ROCKET));
            Vec3 rocketAt = new Vec3(ROCKET_TARGET.x, groundY(level, ROCKET_TARGET.x, ROCKET_TARGET.z), ROCKET_TARGET.z);
            for (int i = 0; i < missileRack.size(); i++) {
                double x = 3000.5 + i * 25, z = 380.5 + (i % 3) * 60;
                setLauncher(missileRack.get(i), "missile 1 0", new Vec3(x, groundY(level, x, z), z), 6);
            }
            for (BlockPos p : rocketRack) setLauncher(p, "rocket 40 40", rocketAt, 3);
            for (BlockPos p : droneRack) setLauncher(p, "drone 2 30", new Vec3(3250.5, groundY(level, 3250.5, 350.5), 350.5), 6);
        });
    }

    private void battery() {
        racks();
        // солдаты батареи
        film.run(() -> {
            for (int i = 0; i < 8; i++) {
                Vec3 at = new Vec3(-1538.5 - (i % 2) * 1.2 - i * 1.8, BATTERY_Y, 524.5 + (i % 2) * 1.6);
                cast().spawn("Gunner" + i, at, -90, null);
            }
        });
        settle();
        film.onServer(level -> {
            for (int i = 0; i < 8; i++) film.armSoldier("Gunner" + i, 0);
        });
        film.waitTicks(40);

        // p06: колонна идёт вдоль ряда пусковых
        film.run(() -> film.withServer(s -> {
            for (int i = 0; i < 8; i++) {
                JsonArray a = Cast.steps();
                a.add(Cast.walkTo(-1440.5 - i * 1.8, 524.5 + (i % 2) * 1.6, false));
                cast().act("Gunner" + i, a, true);
            }
        }));
        film.shot("p06").length(40).hidden()
                .camera(() -> CineCamera.glide(new Vec3(-1532, 71.5, 509), new Vec3(-1520, 71.1, 509.8), 40,
                        () -> new Vec3(-1480, 69, 525), 58));

        // p07, p08, p09: наводчик — бинокль на небоскрёб Востока за 4,6 км, карта наведения, пульт
        actor(POST, T2, "incoming:strike_designator[incoming:loadout={weapon:\"missile\",count:10,spread:30}]");
        film.shot("p07").length(45).hud()
                .player(t -> new Pose(Vec3.ZERO, Film.yawTo(POST, T2), Film.pitchTo(POST.add(0, 1.62, 0), T2) - 0.4f, 0, 70))
                .cue(0, () -> mc.options.keyUse.setDown(true))
                .cueEnd(() -> mc.options.keyUse.setDown(false));
        film.shot("p08").length(50).hud().player(t -> new Pose(Vec3.ZERO, -90, 4, 0, 70))
                .cue(0, () -> mc.setScreen(new MapScreen(new RemoteScreen())))
                .cueEnd(() -> mc.setScreen(null));
        film.shot("p09").length(50).hud().player(t -> new Pose(Vec3.ZERO, -90, 4, 0, 70))
                .cue(0, () -> mc.setScreen(new RemoteScreen()))
                .cueEnd(() -> mc.setScreen(null));

        // p10a: крылатые ракеты сходят с пусковых и сбрасывают ускорители — длинный фокус вдоль ряда
        film.shot("p10a").length(40).hidden()
                .onReady(() -> fireAll(missileRack))
                .camera(() -> CineCamera.track(new Vec3(-1552, 70.2, 507), () -> new Vec3(-1470, 76, ROW_MISSILE), 30))
                .when(() -> film.nearest(CruiseMissileEntity.class, BATTERY, 120) != null, 400);
        // p10b: «Град» выпускает 40 ракет
        film.shot("p10b").length(40).hidden()
                .onReady(() -> fireAll(rocketRack))
                .camera(() -> {
                    Vec3 l = Vec3.atBottomCenterOf(rocketRack.get(2));
                    return CineCamera.track(l.add(-14, 1.6, 9), () -> l.add(10, 6, 0), 54);
                })
                .when(() -> film.nearest(RocketEntity.class, BATTERY, 120) != null, 400);
        // p10c и p11: шахеды уходят со стойки; FPV за шахедом
        film.shot("p10c").length(40).hidden()
                .onReady(() -> fireAll(droneRack))
                .camera(() -> {
                    Vec3 l = Vec3.atBottomCenterOf(droneRack.get(2));
                    return CineCamera.track(l.add(6, 1.4, 8), film.smoothFocus(() -> film.newest(DroneEntity.class), l.add(3, 3, 0), 0.3), 60);
                })
                .when(() -> film.nearest(DroneEntity.class, BATTERY, 120) != null, 600);
        film.shot("p11").length(60).hidden().noPrep()
                .after(() -> film.newest(DroneEntity.class) != null, 200)
                .camera(() -> film.chaseOf(film.newest(DroneEntity.class), 7, 2.4, -2.6, 16, 4, 62));

        // p20: вся линия разом — кран над батареей
        film.run(this::reloadAll);
        film.waitTicks(60);
        film.shot("p20").length(50).hidden()
                .onReady(() -> {
                    fireAll(missileRack);
                    fireAll(rocketRack);
                    fireAll(droneRack);
                })
                .camera(() -> CineCamera.dolly(new Vec3(-1498, 69.6, 543), new Vec3(-1502, 86, 545), 50,
                        () -> new Vec3(-1478, 74, 522), 64))
                .when(() -> film.nearest(RocketEntity.class, BATTERY, 140) != null, 400);
        // p21: с огромной высоты — дымные следы тянутся на восток
        film.shot("p21").length(30).hidden().noPrep()
                .camera(() -> CineCamera.glide(new Vec3(-1600, 330, 470), new Vec3(-1590, 330, 472), 30,
                        () -> new Vec3(-1200, 90, 525), 64));

        // p01c: «Град» изнутри — сорок огненных хвостов вокруг камеры
        film.run(this::reloadAll);
        film.waitTicks(60);
        film.shot("p01c").length(35).hidden()
                .onReady(() -> fireAll(List.of(rocketRack.get(1))))
                .camera(() -> {
                    Vec3 l = Vec3.atBottomCenterOf(rocketRack.get(1));
                    return CineCamera.track(l.add(-6, 5, 2), () -> l.add(30, 20, 0), 84);
                })
                .when(() -> film.nearest(RocketEntity.class, BATTERY, 140) != null, 400);

        // p35: камера низко у пусковой, ракеты проносятся над ней и роняют ускорители
        film.shot("p35").length(35).hidden()
                .onReady(() -> fireAll(missileRack))
                .camera(() -> {
                    Vec3 l = Vec3.atBottomCenterOf(missileRack.get(4));
                    return CineCamera.track(l.add(16, 1.2, 8), () -> l.add(30, 18, 0), 78);
                })
                .when(() -> film.nearest(CruiseMissileEntity.class, BATTERY, 120) != null, 400);

        // p36: ответ Востока — шахеды накрывают батарею, расчёт бежит от пусковых
        film.shot("p36").length(50).hidden()
                .onReady(() -> salvo("drone", 6, 24, BATTERY.add(10, 0, 0), new Vec3(-800, 0, 560)))
                .camera(() -> CineCamera.track(new Vec3(-1430.5, 70, 540.5), () -> BATTERY.add(0, 4, 0), 30))  // восточный край полосы
                .when(() -> film.nearest(DroneEntity.class, BATTERY, 70) != null, 3000)
                .cue(0, () -> film.withServer(s -> {
                    for (int i = 0; i < 8; i++) {
                        JsonArray a = Cast.steps();
                        a.add(Cast.walkTo(-1560.5 - i * 2, 545.5 + i, true));
                        cast().act("Gunner" + i, a, true);
                    }
                }));
        film.run(() -> {
            for (int i = 0; i < 8; i++) cast().remove("Gunner" + i);
        });
        stageScene("factory", new Vec3(-1500.5, 68, 620.5), -90);
    }

    /**
     * Ждать, пока вошли все боты: вход с местом ждёт чанков в фоне (до 30 с), и шаги ботам до входа уходят в пустоту.
     * Бот считается вошедшим, когда клиент его видит (все боты сцен — рядом с камерой).
     */
    private void settle() {
        film.waitUntil(() -> {
            Cast.joining.removeIf(n -> film.bot(n) != null);
            return Cast.joining.isEmpty();
        }, 900);
        film.run(Cast.joining::clear);  // не вошёл за 45 с — не ждём его и в следующий раз
        film.waitTicks(20);
    }

    /** ЗРК сцены убираются после неё: иначе они сбивают шахеды следующих планов (вокруг — весь Восток). */
    private void clearSams(Vec3 center, int radius) {
        film.onServer(level -> {
            int cx = Mth.floor(center.x) >> 4, cz = Mth.floor(center.z) >> 4, rc = (radius >> 4) + 1;
            for (int x = cx - rc; x <= cx + rc; x++) {
                for (int z = cz - rc; z <= cz + rc; z++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                    if (chunk == null) continue;
                    for (BlockPos p : List.copyOf(chunk.getBlockEntities().keySet())) {
                        if (chunk.getBlockEntity(p) instanceof SamBlockEntity) level.removeBlock(p, false);
                    }
                }
            }
        });
    }

    private void setLauncher(BlockPos p, String mission, Vec3 target, int load) {
        String at = p.getX() + " " + p.getY() + " " + p.getZ();
        cast().command("setblock " + at + " incoming:fixed_launcher");
        cast().command("incoming launcher " + at + " mission " + mission + " " + xyz(target));
        cast().command("incoming launcher " + at + " load " + load);
    }

    private void fireAll(List<BlockPos> rack) {
        for (BlockPos p : rack) Film.cmd("incoming launcher " + p.getX() + " " + p.getY() + " " + p.getZ() + " fire");
    }

    private void reloadAll() {
        for (List<BlockPos> rack : List.of(missileRack, rocketRack, droneRack)) {
            for (BlockPos p : rack) Film.cmd("incoming launcher " + p.getX() + " " + p.getY() + " " + p.getZ() + " load 6");
        }
    }

    /**
     * Сцена из {@link Scene#ALL} (цех, дирижабль, аэродром), если она есть в этой сборке. Шаги сцены встают в очередь
     * при постановке, поэтому место — готовыми числами (первый блок над землёй), а не по миру.
     */
    private void stageScene(String name, Vec3 at, float yaw) {
        Scene scene = Scene.ALL.get(name);
        if (scene == null) return;
        hiddenAt(at, at.add(0, 0, 20));
        scene.stage(film, at, yaw);
    }

    // ================================================================ волна 2: небо

    private void sky() {
        // p22: ЗРК у площади: перехватчики уходят вертикально, шахед разлетается; второй дубль — с другой стороны
        Vec3 samA = new Vec3(3200.5, 0, 242.5), samB = new Vec3(3206.5, 0, 242.5);
        hiddenAt(samA, SQUARE);
        film.onServer(level -> {
            for (Vec3 s : List.of(samA, samB)) {
                BlockPos p = BlockPos.containing(s.x, groundY(level, s.x, s.z), s.z);
                level.setBlockAndUpdate(p, ModBlocks.SAM.get().defaultBlockState());
                if (level.getBlockEntity(p) instanceof SamBlockEntity sam) sam.load(new ItemStack(ModItems.INTERCEPTOR.get(), 32));
            }
        });
        Vec3 drones = new Vec3(3170.5, 0, 250.5);
        for (String take : new String[]{"a", "b"}) {
            // a — у самих стаканов, широко: пуск и уход вверх; b — издали, длинный фокус: перехват
            boolean near = take.equals("a");
            Vec3 from = near ? samA.add(-8, 0, 10) : new Vec3(3218.5, 0, 270.5);  // площадь, без домов и деревьев
            film.shot("p22" + take).length(50).hidden()
                    .onReady(() -> salvo("drone", 4, 30, drones, new Vec3(2400, 0, 250)))
                    .camera(() -> {
                        Vec3 c = g(from).add(0, near ? 1.2 : 1.6, 0);
                        return CineCamera.track(c, film.smoothFocus(() -> {
                            InterceptorEntity i = film.nearest(InterceptorEntity.class, at(drones), 300);
                            return i != null ? i : film.nearest(DroneEntity.class, at(drones), 300);
                        }, near ? at(samA).add(0, 3, 0) : at(drones).add(0, 40, 0), 0.25), near ? 62 : 34);
                    })
                    .when(() -> film.nearest(InterceptorEntity.class, at(drones), 300) != null, 3000);
        }

        clearSams(SQUARE, 96);

        // p23: дирижабль (своя сцена)
        Vec3 airship = new Vec3(3000.5, 150, 380.5);  // южнее T2: заход ракеты не задевает башню
        stageScene("airship", airship, -90);
        clearSams(airship, 160);

        // p25: с улицы снизу, длинный фокус: рой шахедов над крышами
        Vec3 swarm = new Vec3(3250.5, 0, 560.5), street = new Vec3(3058.5, 0, 378.5);
        hiddenAt(street, swarm);
        film.shot("p25").length(50).hidden()
                .onReady(() -> salvo("drone", 30, 70, swarm, behind(street, at(swarm), 900)))
                .camera(() -> {
                    Vec3 c = g(street).add(0, 1.4, 0);
                    return CineCamera.track(c, film.smoothFocus(() -> film.nearest(DroneEntity.class, c, 200), c.add(6, 60, 25), 0.15), 22);
                })
                .when(() -> flightOver(at(street), 150), 3000);

        // p01d: шахеды над головой игрока со стороны затылка
        // поле к западу от центра: над ним открытое небо
        Vec3 man = new Vec3(2905.5, 0, 300.5), past = new Vec3(3085.5, 0, 300.5);
        hiddenAt(man, past);
        film.run(() -> cast().spawn("Pyotr", g(man), Film.yawTo(man, past), null));
        settle();
        film.shot("p01d").length(40).hidden()
                .onReady(() -> salvo("drone", 5, 14, past, behind(man, at(past), 700)))
                .camera(() -> CineCamera.shoulder(() -> film.bot("Pyotr"), 1.9, -0.2, 0.6, () -> at(past).add(0, 55, 0), 70))
                .when(() -> flightOver(at(man), 60), 3000)
                .cue(6, () -> cast().act("Pyotr", lookUp(at(past).add(0, 90, 0)), true));
        film.run(() -> cast().remove("Pyotr"));

        // p01a, p01b: FPV с крылатой ракетой между небоскрёбами — и удар в башню
        hiddenAt(T2.add(-200, -150, 0), T2);
        film.shot("p01a").length(45).hidden()
                .onReady(() -> salvo("missile", 1, 0, T2, new Vec3(2500, 0, 520)))
                .camera(() -> CineCamera.chase(() -> film.nearest(CruiseMissileEntity.class, T2, 500), T2.add(-250, -40, 20),
                        new Vec3(1, 0, 0), 9, 1.8, 2.4, 20, 0, 66))
                .when(() -> film.nearest(CruiseMissileEntity.class, T2, 200) != null, 3000)
                .endWhen(hit(T2, 300), 10);
        hiddenAt(T3.add(70, -150, -80), T3);
        film.shot("p01b").length(60).hidden()
                .onReady(() -> salvo("missile", 1, 0, T3, new Vec3(2350, 0, 600)))
                .camera(() -> CineCamera.track(new Vec3(T3.x + 70, T3.y - 30, T3.z - 80), () -> T3, 42))
                .when(() -> flightNear(T3, 200, WeaponType.MISSILE), 3000);

        // p24 и пик: аэродром — бипланы взлетают по тревоге (своя площадка)
        stageScene("airfield", AIRFIELD, -90);
    }

    // ================================================================ волна 3: улица

    /** Бой у южного края площади: Запад с запада, Восток с востока, укрытия — баррикады. */
    private void fight() {
        Vec3 c = SQUARE;
        hiddenAt(c, c.add(30, 0, 0));
        film.onServer(level -> {
            // бой и статисты — с сервером, а он есть только после входа в мир
            street = new Fight(cast(), server());
            street.spread = 4;
            for (int i = 0; i < 8; i++) {
                double z = c.z - 24 + i * 7 + (i % 2) * 1.5;
                double wx = c.x - 26 - (i % 3) * 3, ex = c.x + 26 + (i % 3) * 3;
                Vec3 w = new Vec3(wx, groundY(level, wx, z), z), e = new Vec3(ex, groundY(level, ex, z + 2), z + 2);
                barricade(w.add(2, 0, 0));
                barricade(e.add(-2, 0, 0));
                cast().spawn("West" + i, w, -90, null);
                cast().spawn("East" + i, e, 90, null);
                street.add("West" + i, 0, w, new Vec3(1, 0, 0));
                street.add("East" + i, 1, e, new Vec3(-1, 0, 0));
            }
        });
        settle();
        film.onServer(level -> {
            for (Fight.Soldier s : street.soldiers()) film.armSoldier(s.name, s.side);
        });
        film.waitTicks(40);
        film.run(() -> film.withServer(s -> street.start()));
        film.waitTicks(40);
    }

    private void streets() {
        fight();
        Vec3 c = SQUARE;

        // p26: FPV низко над уличным боем, рядом ложится «Град», камеру бросает
        film.shot("p26").length(100).hidden()
                .onReady(() -> salvo("rocket", 6, 8, c.add(-8, 0, 14), new Vec3(2650, 0, 314)))
                .camera(() -> {
                    double y = g(c).y;
                    // по открытой площади (x 3130–3220, z 240–300) южнее башни-фонтана: дома и башня — вне пути
                    return CineCamera.fpv(List.of(new Vec3(c.x - 45, y + 7, c.z + 26), new Vec3(c.x - 25, y + 4.2, c.z + 16),
                            new Vec3(c.x - 2, y + 3.6, c.z + 13), new Vec3(c.x + 22, y + 4.5, c.z + 6), new Vec3(c.x + 38, y + 6, c.z - 12),
                            new Vec3(c.x + 32, y + 7, c.z - 34)), 1.4, 7, 1.0, 0.12, 78);
                })
                .when(() -> film.nearest(RocketEntity.class, at(c), 260) != null, 3000);

        // p27: твоими глазами — перестрелка (a, c), на дороге рвутся снаряды (b)
        for (String take : new String[]{"a", "b", "c"}) {
            int k = take.charAt(0) - 'a';
            Vec3 pos = new Vec3(c.x - 30, 0, c.z - 10 + k * 9);
            Vec3 aim = new Vec3(c.x + 28, 0, c.z - 6 + k * 6);
            actor(pos, aim, "pointblank:ak47");
            Film.Shot shot = film.shot("p27" + take).length(40).hud()
                    .player(t -> {
                        Vec3 a = at(aim).add(0, 1.4, 0);
                        return new Pose(Vec3.ZERO, Film.yawTo(mc.player.position(), a) + (float) Math.sin(t / 13) * 3,
                                Film.pitchTo(mc.player.getEyePosition(), a) + (float) Math.sin(t / 7) * 1.2f, 0, 70);
                    })
                    .cue(3, () -> mc.options.keyAttack.setDown(true))
                    .cue(22, () -> mc.options.keyAttack.setDown(false))
                    .cue(27, () -> mc.options.keyAttack.setDown(true))
                    .cueEnd(() -> mc.options.keyAttack.setDown(false));
            if (k == 1) {
                Vec3 road = c.add(10, 0, 4);
                shot.onReady(() -> salvo("rocket", 4, 10, road, new Vec3(2700, 0, 300)))
                        .when(() -> film.nearest(RocketEntity.class, at(road), 200) != null, 3000);
            }
        }

        // p28: FPV над перекрёстком, по улице бежит пакет «Града», разрывы прямо под камерой
        // вдоль проспекта (z 327–335, открыт от x 3012 на восток): пакет ложится на улицу под камерой
        Vec3 cross = new Vec3(3022.5, 0, 331.5), line = new Vec3(3090.5, 0, 331.5);
        hiddenAt(cross, line);
        film.shot("p28").length(85).hidden()
                .onReady(() -> salvo("rocket", 24, 30, line, new Vec3(2400, 0, 338)))
                .camera(() -> {
                    double y = g(cross).y;
                    return CineCamera.fpv(List.of(new Vec3(cross.x - 8, y + 16, cross.z), new Vec3(cross.x + 20, y + 14, cross.z + 0.5),
                            new Vec3(cross.x + 50, y + 13, cross.z - 0.5), new Vec3(cross.x + 90, y + 12, cross.z + 0.5),
                            new Vec3(cross.x + 150, y + 14, cross.z)), 1.6, 8, 1.1, 0.1, 76);
                })
                .when(() -> film.nearest(RocketEntity.class, at(line), 250) != null, 3000);

        // p29: боец прижался к стене; из-за плеча — шахед проносится над ним и входит в дом напротив
        Vec3 wall = new Vec3(3080.5, 0, 334.5), house = new Vec3(3175.5, 120, 353.5);
        hiddenAt(wall, house);
        film.run(() -> cast().spawn("Vlad", g(wall), Film.yawTo(wall, house), null));
        settle();
        film.onServer(level -> film.armSoldier("Vlad", 1));
        film.shot("p29").length(50).hidden()
                .onReady(() -> salvo("drone", 1, 0, house, behind(wall, house, 700)))
                .camera(() -> CineCamera.shoulder(() -> film.bot("Vlad"), 2.3, 0.2, 1.2, () -> house, 60))
                .when(() -> film.nearest(DroneEntity.class, house, 70) != null, 3000);
        film.run(() -> cast().remove("Vlad"));

        // p30: глазами бойца — он поднимает голову, шахед пикирует прямо в камеру (монтаж режет на ударе)
        Vec3 eyeSpot = new Vec3(3150.5, 0, 300.5);
        film.run(() -> cast().spawn("Sasha", g(eyeSpot), -90, null));
        settle();
        film.shot("p30").length(80).hidden()
                .onReady(() -> salvo("drone", 1, 0, eyeSpot, new Vec3(2450, 0, 318)))
                .camera(() -> CineCamera.eyes(() -> film.bot("Sasha"), 72))
                .when(() -> flightOver(at(eyeSpot), 120), 3000)
                .cue(0, () -> film.withServer(s -> trackLook("Sasha", DroneEntity.class, 80)))
                .endWhen(hit(eyeSpot, 200), 4);
        film.run(() -> cast().remove("Sasha"));

        // p01e: ковёр разрывов бежит прямо на камеру
        Vec3 carpet = new Vec3(3175.5, 0, 252.5);
        film.shot("p01e").length(60).hidden()
                .onReady(() -> salvo("rocket", 30, 34, carpet, new Vec3(2450, 0, 255)))
                .camera(() -> CineCamera.track(g(carpet.add(0, 0, 55)).add(0, 1.3, 0), () -> at(carpet).add(0, 4, 0), 62))
                .when(() -> film.nearest(RocketEntity.class, at(carpet), 220) != null, 3000);
    }

    // ================================================================ волна 4: сквозь огонь, закат, ночь, пик

    private void throughFire() {
        // p31: FPV за шахедом между небоскрёбами — шахед входит в башню, камера влетает в огненный шар
        Vec3 into = T4.add(0, -50, -4);
        hiddenAt(T4.add(-150, -150, 0), T4);
        film.shot("p31").length(50).hidden()
                .onReady(() -> salvo("drone", 1, 0, into, new Vec3(2500, 0, 560)))
                .camera(() -> CineCamera.chase(() -> film.nearest(DroneEntity.class, into, 400), into.add(-140, 30, 0),
                        new Vec3(1, 0, 0), 6, 1.2, -1.6, 12, 0, 70))
                .when(() -> film.nearest(DroneEntity.class, into, 90) != null, 3000)
                .endWhen(hit(into, 400), 14);
        // p32: …и вылетает над другой улицей, куда как раз ложится «Град»
        Vec3 other = new Vec3(3010.5, 0, 640.5);
        film.shot("p32").length(35).hidden()
                .onReady(() -> salvo("rocket", 16, 20, other, new Vec3(2400, 0, 640)))
                .camera(() -> {
                    Vec3 o = at(other);
                    return CineCamera.fpv(List.of(o.add(40, 34, -50), o.add(14, 26, -20), o.add(-6, 22, 4), o.add(-30, 20, 30)), 1.5, 8, 0.8, 0.1, 76);
                })
                .when(() -> film.nearest(RocketEntity.class, at(other), 180) != null, 3000);

        // p33: в бинокль наводчика — ракета входит в башню вдали
        Vec3 spotter = new Vec3(3175.5, 162, 352.5);
        actor(spotter, T3, "incoming:strike_designator[incoming:loadout={weapon:\"missile\",count:1,spread:0}]");
        film.shot("p33").length(40).hud()
                .onReady(() -> salvo("missile", 1, 0, T3, new Vec3(2300, 0, 610)))
                .player(t -> new Pose(Vec3.ZERO, Film.yawTo(spotter, T3), Film.pitchTo(spotter.add(0, 1.62, 0), T3), 0, 70))
                .cue(0, () -> mc.options.keyUse.setDown(true))
                .cueEnd(() -> mc.options.keyUse.setDown(false))
                .when(() -> flightNear(T3, 90, WeaponType.MISSILE), 3000);

        // p34: с борта ракеты — пике, цель растёт, SIGNAL LOST (монтаж режет по отметке gone)
        Vec3 dive = new Vec3(3130.5, 0, 560.5);
        film.shot("p34").length(260).hud().projectileCamera().readyChunks(12)
                .onReady(() -> salvo("missile", 1, 0, dive, new Vec3(2200, 0, 560)))
                .prepare(() -> {
                    if (!ProjectileCamera.isActive()) ProjectileCamera.cycle();
                })
                .when(() -> Film.onMap() && film.missileRange() < 600, 3000)
                .cueEnd(ProjectileCamera::exit);

        // p37: B-2 на закате — створки, бомба уходит вниз, земля вспучивается (монтаж — от отметки release)
        film.run(() -> Film.cmd("time set 12400"));
        Vec3 bombAt = new Vec3(3360.5, 0, 470.5);
        Vec3 bayWatch = new Vec3(3190.5, 230, 470.5);
        hiddenAt(bayWatch, bombAt);
        film.shot("p37").length(120).hidden()
                .onReady(() -> Film.cmd("incoming salvo bunker 1 0 at " + xyz(at(bombAt)) + " from 2400 470"))
                .camera(() -> CineCamera.chase(() -> film.nearest(BomberEntity.class, at(bombAt), 900), bayWatch, new Vec3(1, 0, 0),
                        26, -7, 9, 14, 0, 56))
                .when(() -> film.nearest(BomberEntity.class, at(bombAt), 500) != null, 3000)
                .endWhen(() -> film.nearest(BomberEntity.class, at(bombAt), 900) instanceof BomberEntity b && b.flightPhase() == FlightPhase.EGRESS, 70);

        // p38: ночь, дождь: рой шахедов, перехваты в небе, ночной «Град» подсвечивает облака
        film.run(() -> {
            Film.cmd("time set 13300");  // сразу после заката: небо ещё синее, город уже в огнях (в полночь под шейдером кадр чёрный)
            Film.cmd("weather rain");
        });
        Vec3 night = new Vec3(3180.5, 0, 300.5);
        Vec3 nightCam = new Vec3(3175.5, 170, 350.5);  // над северным краем крыши ROOF: площадь и небо над ней
        hiddenAt(nightCam, night);
        film.shot("p38").length(70).hidden()
                .onReady(() -> {
                    salvo("drone", 12, 60, night, new Vec3(2300, 0, 260));
                    salvo("rocket", 24, 50, night.add(40, 0, -50), new Vec3(2600, 0, 250));
                })
                .camera(() -> CineCamera.track(nightCam, () -> new Vec3(3180.5, 110, 260.5), 40))
                .when(() -> film.nearest(DroneEntity.class, at(night), 250) != null, 3000);

        // p39: пик — короткие вспышки ночного боя (монтаж режет каждый дубль на несколько склеек)
        peak("p39a", new Vec3(2930.5, 0, 260.5), "rocket", 30, 30, new Vec3(-30, 2, 40), 70);
        peak("p39b", new Vec3(3110.5, 0, 410.5), "drone", 8, 30, new Vec3(-24, 30, -20), 60);
        peak("p39c", T1, "missile", 4, 12, new Vec3(-60, -120, -50), 40);
        peak("p39d", new Vec3(3200.5, 0, 260.5), "rocket", 24, 24, new Vec3(-40, 14, 30), 50);
        film.run(() -> Film.cmd("weather clear"));
    }

    /** План пика: удар {@code weapon} по месту, камера смотрит на него со сдвига {@code cam} (y > 0 — над землёй). */
    private void peak(String name, Vec3 target, String weapon, int count, int spread, Vec3 cam, double fov) {
        hiddenAt(target.add(cam.x, 0, cam.z), target);
        film.shot(name).length(80).hidden()
                .onReady(() -> salvo(weapon, count, spread, target, behind(at(target).add(cam), at(target), 900)))
                .camera(() -> {
                    Vec3 t = at(target);
                    Vec3 c = cam.y > 0 ? g(t.add(cam)).add(0, cam.y, 0) : t.add(cam);
                    return CineCamera.track(c, () -> t.add(0, 6, 0), fov);
                })
                .when(() -> flightNear(at(target), weapon.equals("rocket") ? 300 : 120, WeaponType.valueOf(weapon.toUpperCase(Locale.ROOT))), 3000);
    }

    // ================================================================ IV: МБР и ядерный удар

    private void nuke() {
        film.run(() -> Film.cmd("time set 17000"));
        // расчёт у пусковых ночью
        hiddenAt(NIGHT_POST, GZ);
        film.run(() -> {
            for (int i = 0; i < 4; i++) {
                cast().spawn("Crew" + i, new Vec3(NIGHT_POST.x - 2 + i * 1.6, BATTERY_Y, NIGHT_POST.z - 3 + (i % 2) * 1.2), -90, null);
            }
        });
        settle();
        film.onServer(level -> {
            for (int i = 0; i < 4; i++) film.armSoldier("Crew" + i, 0);
        });
        // p40b, p40c: вспышки ночного боя у батареи (между цифрами отсчёта); пусковые поставила часть «west»
        film.run(this::reloadAll);
        film.waitTicks(40);
        film.shot("p40b").length(30).hidden()
                .onReady(() -> fireAll(rocketRack.subList(Math.min(3, rocketRack.size()), Math.min(4, rocketRack.size()))))
                .camera(() -> {
                    Vec3 l = new Vec3(-1474.5, BATTERY_Y, ROW_ROCKET);
                    return CineCamera.track(l.add(-10, 1.6, -7), () -> l.add(10, 8, 0), 60);
                })
                .when(() -> film.nearest(RocketEntity.class, BATTERY, 140) != null, 400);
        film.shot("p40c").length(30).hidden()
                .onReady(() -> fireAll(missileRack.subList(0, Math.min(3, missileRack.size()))))
                .camera(() -> {
                    Vec3 l = new Vec3(-1519.5, BATTERY_Y, ROW_MISSILE);
                    return CineCamera.track(l.add(4, 1.0, -9), () -> l.add(14, 10, 0), 64);
                })
                .when(() -> film.nearest(CruiseMissileEntity.class, BATTERY, 120) != null, 400);
        // p53 (после титров): ключ, ☢ LAUNCH IN 3, отмена; p40a — отсчёт до пуска, по нулю МБР уходит на Восток
        actor(NIGHT_POST, NIGHT_POST.add(100, 4, 0), "incoming:strike_designator[incoming:loadout={weapon:\"nuke\",count:1,spread:0}]");
        film.shot("p53").length(70).hud().player(t -> new Pose(Vec3.ZERO, -90, -6, 0, 70))
                .cue(14, () -> NukeArming.toggle(() -> {}))
                .cue(58, NukeArming::cancel);
        film.shot("p40a").length(90).hud().player(t -> new Pose(Vec3.ZERO, -90, -6 + (float) t * 0.03f, 0, 70))
                .cue(14, () -> NukeArming.toggle(() -> Film.cmd("incoming nuke at " + xyz(at(GZ)) + " 15 air")));
        // p41: тишина; МБР уходит в ночь за спиной расчёта, кран вверх
        film.shot("p41").length(100).hidden().noPrep()
                .after(() -> film.newest(IcbmEntity.class) != null, 300)
                .camera(() -> {
                    IcbmEntity icbm = film.newest(IcbmEntity.class);
                    Vec3 pad = icbm != null ? icbm.position() : NIGHT_POST.add(-30, 0, 0);
                    return CineCamera.dolly(NIGHT_POST.add(16, 1.2, 6), NIGHT_POST.add(17, 12, 8), 100,
                            film.smoothFocus(() -> film.newest(IcbmEntity.class), pad.add(0, 10, 0), 0.25), 62);
                });
        film.run(() -> {
            for (int i = 0; i < 4; i++) cast().remove("Crew" + i);
        });
        // p42: огненная точка МБР ползёт через ночное небо на восток (с Востока, длинный фокус)
        Vec3 watchE = new Vec3(2930.5, 0, 300.5);
        hiddenAt(watchE, GZ);
        film.shot("p42").length(60).hidden()
                .camera(() -> CineCamera.track(g(watchE).add(0, 28, 0), film.smoothFocus(() -> {
                    var list = ClientFlights.all();
                    return list.isEmpty() ? null : list.get(0).position(CineCamera.partial());
                }, g(watchE).add(-2000, 700, 0), 0.2), 14))
                .when(() -> film.warningTicks() < 700, 4000);

        // p43: на Востоке ☢ MISSILE ATTACK ☢ и отсчёт; бой замирает, бойцы опускают оружие и смотрят вверх
        Vec3 c = SQUARE;
        film.shot("p43").length(120).hud().hidden()
                .cue(0, () -> film.withServer(s -> {
                    if (street != null) street.watch(at(c).add(-600, 500, 300), 40);
                }))
                .camera(() -> {
                    double y = g(c).y;
                    return CineCamera.dolly(new Vec3(c.x - 40, y + 2, c.z + 10), new Vec3(c.x - 34, y + 1.9, c.z + 8), 120,
                            () -> new Vec3(c.x, y + 3, c.z), 58);
                })
                .when(() -> film.warningTicks() < 560, 4000);

        // p44: та же площадь, что в p04: горит и пуста; камера поднимает взгляд в небо
        film.run(() -> {
            if (street != null) for (Fight.Soldier s : street.soldiers()) cast().remove(s.name);
        });
        film.shot("p44").length(75).hidden()
                .camera(() -> {
                    Vec3 a = g(3116.5, 306.5).add(0, 5.5, 0);
                    return CineCamera.pan(a, a.add(1, 0.15, -0.8), () -> GOV, () -> GOV.add(-60, 220, 100), 75, 50);
                })
                .when(() -> film.warningTicks() < 300, 4000);

        // p45, p50: крыша в ~900 блоках: вспышка — люди отворачиваются; волна закрывает небо и накрывает их
        hiddenAt(FINAL_ROOF, GZ);
        film.onServer(level -> {
            roof = new Crowd(cast(), server());
            for (int i = 0; i < 6; i++) {
                Vec3 p = FINAL_ROOF.add((i % 3) * 1.6 - 1.6, 0, (i / 3) * 1.6);
                roof.add((i < 3 ? "Watch" : "Guard") + i, p, 0.5);
            }
        });
        settle();
        film.onServer(level -> {
            for (int i = 3; i < 6; i++) film.armSoldier("Guard" + i, 1);
            roof.watch(GZ.add(0, 300, 0), 30);
        });
        film.shot("p45").length(80).hidden()
                .camera(() -> CineCamera.track(FINAL_ROOF.add(-7, 3.2, -6), () -> GZ.add(0, 250, 0), 60))
                .when(() -> film.warningTicks() < 30, 4000)
                .cue(31, () -> film.withServer(s -> roof.cower(GZ)));
        film.shot("p50").length(320).hidden().noPrep()
                .camera(() -> {
                    Vec3 a = FINAL_ROOF.add(-9, 5.5, -8), b = FINAL_ROOF.add(-5, 2.6, -10);
                    return CineCamera.dolly(a, b, 320, () -> GZ.add(0, 330, 0), 64);
                })
                .cue(120, () -> film.withServer(s -> roof.watch(GZ.add(0, 600, 0), 40)));
    }

    /**
     * Волна после ядерки — ещё два подрыва, каждый на свежей копии мира: вдали фронт идёт ~3 блока за тик, и планы,
     * которым нужна стена у разных мест, в одном подрыве не помещаются. Часть nukeB: гриб над городом с той же крыши (p46),
     * стена пыли катится через городок с высоты (p47).
     */
    private void nukeFar() {
        detonate(FINAL_ROOF);
        film.shot("p46").length(100).hidden()
                .camera(() -> CineCamera.track(FINAL_ROOF.add(3, 3, -10), () -> GZ.add(0, 480, 0), 60))
                .when(() -> film.sinceDetonation() >= 0, 600);
        film.shot("p47").length(70).hidden()
                .camera(() -> CineCamera.glide(WAVE_HIGH, WAVE_HIGH.add(6, -4, 8), 70, () -> at(WAVE_LOOK), 70))
                .when(() -> frontIn(at(WAVE_TOWN)) < 25, 4000);
    }

    /** Часть nukeC: стена между домами на камеру (p49), волна закрывает горизонт с взгорка (p48) — по порядку прихода. */
    private void nukeNear() {
        detonate(WAVE_STREET);
        film.shot("p49").length(70).hidden()
                .camera(() -> {
                    Vec3 dir = GZ.subtract(WAVE_STREET).multiply(1, 0, 1).normalize();
                    return CineCamera.track(WAVE_STREET.add(0, 1.5, 0), () -> WAVE_STREET.add(dir.scale(80)).add(0, 12, 0), 72);
                })
                .when(() -> frontIn(WAVE_STREET) < 45, 4000);
        film.shot("p48").length(100).hidden()
                .camera(() -> CineCamera.track(g(WAVE_HILL).add(0, 18, 0), () -> GZ.add(-150, 150, -150), 24))
                .when(() -> frontIn(at(WAVE_HILL)) < 70, 4000);
    }

    /** Ночь, невидимка у места первого плана, подрыв 15 кт над эпицентром (без полёта). */
    private void detonate(Vec3 watch) {
        film.run(() -> Film.cmd("time set 17000"));
        hiddenAt(watch, GZ);
        film.waitTicks(100);
        film.run(() -> Film.cmd("incoming nuke now at " + xyz(at(GZ)) + " 15 air"));
    }

    // ================================================================ постройки

    /** Мешки поперёк линии огня (по x): 3 блока шириной, 2 высотой. */
    private void barricade(Vec3 at) {
        BlockPos p = BlockPos.containing(at);
        cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:packed_mud", p.getX(), p.getY(), p.getZ() - 1,
                p.getX(), p.getY() + 1, p.getZ() + 1));
    }

    private double roomFloor = 64;

    /** Квартира: комната 7×4×7 из бетона на земле, окно во всю стену к −z (к удару), внутри темно. */
    private void buildRoom(ServerLevel level, Vec3 room) {
        int x = Mth.floor(room.x), z = Mth.floor(room.z);
        int y = groundY(level, x, z);
        roomFloor = y;
        cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:spruce_planks hollow", x - 3, y - 1, z - 3, x + 3, y + 4, z + 3));
        cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:dark_oak_planks", x - 2, y - 1, z - 2, x + 2, y - 1, z + 2));
        cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glass_pane", x - 2, y, z - 3, x + 2, y + 3, z - 3));
        cast().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:lantern[hanging=true]", x, y + 3, z));
        cast().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:red_bed[facing=east,part=foot]", x - 2, y, z + 1));
        cast().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:bookshelf", x + 2, y, z + 2));
        cast().command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:potted_fern", x + 2, y, z - 2));
        // двор перед окном: деревья и живая изгородь закрывали бы вид
        for (String tag : new String[]{"#minecraft:leaves", "#minecraft:logs"})
            cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air replace %s", x - 8, y - 1, z - 14,
                    x + 8, y + 12, z - 4, tag));
    }
}
