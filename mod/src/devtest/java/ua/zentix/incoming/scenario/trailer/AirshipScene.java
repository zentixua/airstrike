package ua.zentix.incoming.scenario.trailer;

import com.google.gson.JsonArray;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.CloudStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.client.flight.FlightTracks;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.scenario.trailer.CineCamera.Path;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.TargetPicker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static ua.zentix.incoming.scenario.trailer.Cast.fmt;

/**
 * Дирижабль (план 23 сценария): FPV между дымными следами зенитных ракет, в кадр входит дирижабль Create Aeronautics,
 * крылатая ракета попадает в него, он раскалывается, обломки падают. Запасной план того же удара — с земли длинным
 * фокусом (второй дирижабль, второй удар).
 * <p>
 * Всё настоящее. Дирижабль — аппарат Sable из блоков Aeronautics, собранный командой {@code sable assemble area}:
 * оболочка-сигара, две горелки горячего воздуха под ней (подъём), два винта на подшипниках Aeronautics с парусами Create,
 * их крутят переносные моторы Simulated (ход ~1.7 блока в секунду). Горелки ставятся на подъём, равный массе аппарата
 * при давлении на его высоте (Sable): дирижабль держит высоту. Ракета — приказ бота-наводчика по взгляду на аппарат
 * (цель — аппарат Sable, как с бинокля). Следы — ЗРК наводчика (ракета наводчика им своя) против шахедов с консоли
 * (без хозяина — чужие всем).
 */
final class AirshipScene {
    private static final String GUNNER = "SkyGunner";
    /** Сколько тиков ждать, пока под прицелом наводчика дирижабль, а не сущность на луче. */
    private static final int AIM_TRIES = 100;
    /** Масса дирижабля по меткам Sable (оболочка 0.25, доски 0.5, …): горелки ставятся по ней, после сборки — по настоящей. */
    private static final double MASS = 212;
    /** Подъём на единицу горячего воздуха (Aeronautics, {@code hot_air_strength}). */
    private static final double LIFT_PER_GAS = 1.5;
    /** Оболочка — эллипсоид: полуоси вдоль, вверх, поперёк. */
    private static final double HALF_LENGTH = 14, HALF_HEIGHT = 5, HALF_WIDTH = 5;
    /** Гондола: пол на столько ниже середины оболочки, середина — на столько вперёд по ходу (против веса оперения). */
    private static final int FLOOR = -9, GONDOLA = 3;
    /** Середина оболочки от точки сцены (вправо, вверх, вперёд); ход дирижабля — вправо. */
    private static final double[] AIRSHIP = {8, 60, 50};
    /** Второй дубль левее: дым первого удара висит минуты, второй дирижабль в нём бы и стоял. */
    private static final double SECOND_TAKE = -80;
    /**
     * Камера с земли — от середины оболочки (вправо, вперёд): справа сзади, дирижабль сверху слева. Дым первого дубля
     * (правее) — за спиной камеры, ракета второго дубля заходит слева, из-за дирижабля.
     */
    private static final double[] GROUND_EYE = {63, -70};

    private final Film film;
    private final Vec3 at;
    private final float yaw;
    /** Оси постройки: вдоль дирижабля (его ход) и поперёк (вперёд по сцене). */
    private final Direction along, across;
    /** Дирижабль этого дубля (UUID аппарата Sable) и где была середина его оболочки при сборке. */
    @Nullable
    private volatile UUID airship;
    private volatile Vec3 anchor;
    /** Сдвиг дирижабля этого дубля вправо от {@link #AIRSHIP}. */
    private volatile double shift;
    /** Камера второго плана на земле. */
    private volatile Vec3 groundEye;
    private final Set<Integer> interceptors = new HashSet<>();
    /** Ступицы и подшипники винтов, моторы — на участке аппарата (места на участке не меняются, когда аппарат летит). */
    private final List<BlockPos> hubs = new ArrayList<>(), bearings = new ArrayList<>(), motors = new ArrayList<>();
    @Nullable
    private CloudStatus cloudsBefore;

    private AirshipScene(Film film, Vec3 at, float yaw) {
        this.film = film;
        this.at = at;
        this.yaw = yaw;
        this.along = Direction.fromYRot(yaw + 90);
        this.across = Direction.fromYRot(yaw);
        this.anchor = ship(0, 0, 0);
        this.groundEye = ship(GROUND_EYE[0], 1.6 - AIRSHIP[1], GROUND_EYE[1]);
    }

    static void stage(Film film, Vec3 at, float yaw) {
        new AirshipScene(film, at, yaw).script();
    }

    /** Статисты сервера: только из шагов — при постановке сцены (в конструкторе съёмки) сервера ещё нет. */
    private Cast cast() {
        return ((Trailer) film).cast();
    }

    private Vec3 p(double right, double up, double fwd) {
        return Scene.local(at, yaw, right, up, fwd);
    }

    /** Точка от середины оболочки дирижабля этого дубля. */
    private Vec3 ship(double right, double up, double fwd) {
        return p(AIRSHIP[0] + shift + right, AIRSHIP[1] + up, AIRSHIP[2] + fwd);
    }

    private void script() {
        // невидимка — над сценой: мир вокруг и снаряды приходят к клиенту
        film.run(() -> {
            Vec3 v = p(0, 30, -40);
            film.withPlayer((level, pl) -> pl.teleportTo(level, v.x, v.y, v.z, yaw, 10));
        });
        film.onServer(level -> {
            forceload(level, true);
            Vec3 g = p(40, 0, 30);
            cast().spawn(GUNNER, new Vec3(g.x, ground(level, g), g.z), yaw + 180, null);
        });
        waitServer(level -> cast().player(GUNNER) != null && areaReady(level), 1200, "наводчик и район сцены");
        // ЗРК заранее: направляющие заряжаются по одной за 8 с
        film.onServer(level -> {
            for (double[] s : new double[][]{{-8, -45}, {8, -22}, {-6, 2}}) sam(level, p(s[0], 0, s[1]));
        });
        film.run(() -> clouds(true));
        take("p23", 0, 1);
        // шахеды первого дубля долетают, направляющие ЗРК заряжаются заново
        film.waitTicks(600);
        take("p23b", SECOND_TAKE, -1);
        film.run(() -> clouds(false));
        film.onServer(level -> {
            cast().remove(GUNNER);
            forceload(level, false);
        });
    }

    /**
     * Облака игры (слой на высоте 192) — между камерой и дирижаблем, когда сцена высоко (ровная площадка отдельной
     * съёмки на 151): на время сцены их нет. На карте сцена ниже облаков, и настройка не трогается.
     */
    private void clouds(boolean hide) {
        var option = film.mc.options.cloudStatus();
        if (hide) {
            float height = film.mc.level == null ? Float.NaN : film.mc.level.effects().getCloudHeight();
            if (!Float.isNaN(height) && anchor.y + 12 > height) {
                cloudsBefore = option.get();
                option.set(CloudStatus.OFF);
                Incoming.LOG.info("TRAILER airship: дирижабль у облаков ({}), облака скрыты", height);
            }
        } else if (cloudsBefore != null) {
            option.set(cloudsBefore);
            cloudsBefore = null;
        }
    }

    /**
     * Дубль: дирижабль строится и собирается, шахеды, ЗРК бьют их, ракета наводчика — в дирижабль, план. {@code right} —
     * сдвиг дирижабля вправо, {@code side} — откуда заходит ракета (1 — справа спереди, −1 — слева спереди).
     */
    private void take(String shot, double right, int side) {
        film.onServer(level -> {
            clearWrecks(level);
            shift = right;
            groundEye = groundEye(level);
            build(level);
        });
        // горелки наполняют оболочку ещё до сборки (до 180 тиков), ЗРК заряжают направляющие
        film.waitTicks(260);
        film.onServer(this::assemble);
        film.waitTicks(20);
        film.onServer(level -> {
            interceptors.clear();
            // шахеды слева и справа издалека: к зоне огня ЗРК подходят уже на высоте полёта
            Vec3 target = p(0, 0, -75);
            for (int flank : new int[]{-1, 1}) {
                Vec3 from = p(flank * 2100, 0, 250), via = p(flank * 1000, 0, 120);
                cast().command(fmt("incoming salvo drone 5 30 at %.1f %.1f %.1f from %.1f %.1f via %.1f %.1f",
                        target.x, target.y, target.z, from.x, from.z, via.x, via.z));
            }
        });
        // ракету — когда ЗРК уже стреляют: к началу плана в небе следы
        waitServer(level -> countInterceptors(level) >= 3, 1600, "пусков ЗРК");
        // моторы — только теперь: до них дирижабль висит на месте сборки, и время ожидания ЗРК его не уводит
        film.onServer(this::startEngines);
        film.waitTicks(20);
        film.onServer(this::propellers);
        film.waitTicks(20);
        film.onServer(this::propellers);
        film.waitTicks(10);
        film.onServer(level -> {
            propellers(level);
            aim(centre(level));
        });
        // взгляд бот поворачивает в своём тике: приказ «по взгляду» — после него. Приказ бьёт в то, что под прицелом
        // ({@code TargetPicker}): сущность на луче (шахед, перехватчик ЗРК) берётся раньше аппарата, и ракета p23b уходила
        // мимо дирижабля (13.10). Пуск — когда под прицелом аппарат; нет — взгляд снова на середину оболочки
        film.waitTicks(3);
        waitServer(level -> {
            ServerPlayer gunner = cast().player(GUNNER);
            if (gunner == null) return false;
            TargetPicker.Pick pick = TargetPicker.pick(level, gunner, gunner.getEyePosition(), gunner.getLookAngle(),
                    IncomingConfig.SERVER.aimRange.get());
            if (pick != null && pick.kind() == TargetPicker.Kind.AIRCRAFT) return true;
            aim(centre(level));
            return false;
        }, AIM_TRIES, "дирижабль под прицелом наводчика");
        film.onServer(level -> {
            // в кадре FPV ракета приходит сверху справа и пикирует на аппарат, с земли — из-за дирижабля
            Vec3 c = centre(level), site = ship(side * 300, 0, 330), via = ship(side * 170, 0, 187);
            cast().command(fmt("execute as %s run incoming salvo missile 1 0 look from %.1f %.1f via %.1f %.1f", GUNNER, site.x, site.z, via.x, via.z));
            Incoming.LOG.info("TRAILER airship: ракета по дирижаблю у {}", fmt("%.1f %.1f %.1f", c.x, c.y, c.z));
        });
        if (shot.equals("p23")) {
            film.shot(shot).length(110).hidden()
                    .camera(this::fpv)
                    .when(() -> missileWithin(235), 900);
        } else {
            film.shot(shot).length(110).hidden()
                    .camera(this::fromGround)
                    .when(() -> missileWithin(190), 900);
        }
    }

    // ================================================================ камера

    /**
     * FPV: снижение влево к ЗРК между следами, разворот и подъём — дирижабль входит в кадр сверху справа, удар
     * примерно на 60-м тике в ~55 блоках, пролёт ниже и левее облака взрыва (~40 блоков) и снижение за обломками. Точки — от середины оболочки на начало
     * записи (дирижабль идёт своим ходом, пока ждём ракету); взгляд — по пути, потом на дирижабль и обломки.
     */
    private Path fpv() {
        final Vec3[] base = {null};
        final Path[] flight = {null};
        final CineCamera.Spline[] spline = {null};
        Look look = new Look(5);
        final double speed = 1.4;
        return t -> {
            if (t <= 0 || flight[0] == null) {
                Vec3 a = airshipNow();
                if (base[0] == null || !a.equals(base[0])) {
                    base[0] = a;
                    List<Vec3> pts = fpvPoints(a.subtract(ship(0, 0, 0)));
                    flight[0] = CineCamera.fpv(pts, speed, 14, 3, 0.05, 78);
                    spline[0] = new CineCamera.Spline(pts);
                    look.reset();
                }
            }
            Pose q = flight[0].at(t);
            double d = t * speed;
            Vec3 ahead = spline[0].at(d + 14);
            Vec3 wreck = wreck(base[0]);
            double w = CineCamera.smooth((t - 8) / 30);
            Vec3 want = ahead.lerp(wreck != null ? wreck : base[0], w);
            return Pose.look(q.pos(), look.toward(q.pos(), want, t), q.roll(), q.fov());
        };
    }

    /** Точки FPV в координатах сцены; точки у дирижабля сдвинуты за ним ({@code drift} — его уход от места сборки). */
    private List<Vec3> fpvPoints(Vec3 drift) {
        double[][] pts = {
                {24, 32, -70}, {12, 22, -56}, {2, 18, -38}, {-2, 22, -16}, {-4, 30, 4},
                {-14, 38, 20}, {-26, 36, 38}, {-30, 26, 56}, {-24, 14, 72}, {-14, 8, 84}};
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < pts.length; i++) {
            double k = Mth.clamp((i - 3) / 2.0, 0, 1);
            out.add(p(pts[i][0] + shift, pts[i][1], pts[i][2]).add(drift.scale(k)));
        }
        return out;
    }

    /** С земли длинным фокусом: дирижабль, удар, обломки до земли (взгляд за ними, кадр шире после удара). */
    private Path fromGround() {
        Look look = new Look(3);
        final double[] hit = {-1};
        final Vec3[] hitAt = {null};
        return t -> {
            Vec3 eye = groundEye;
            Vec3 a = airshipNow();
            if (t <= 0) {
                look.reset();
                hit[0] = -1;
                hitAt[0] = null;
            } else if (hit[0] < 0 && pieces(a) > 1) {
                hit[0] = t;
                hitAt[0] = a;
            }
            // после удара — обломки у места удара: имя аппарата может остаться за куском, который отбросило вверх
            Vec3 wreck = wreck(hitAt[0] != null ? hitAt[0] : a);
            double fov = hit[0] < 0 ? 20 : Mth.lerp(CineCamera.smooth((t - hit[0]) / 40), 20, 32);
            return Pose.look(eye, look.toward(eye, wreck != null ? wreck : a, t), 0, fov);
        };
    }

    /**
     * Взгляд камеры с пределом поворота ({@code degPerTick}) и сглаживанием: куда смотреть, решают обломки и путь, а
     * камера не дёргается, когда аппарат раскалывается и середина обломков прыгает.
     */
    private static final class Look {
        private final double maxStep;
        private Vec3 dir;
        private double last = Double.NaN;

        Look(double degPerTick) {
            this.maxStep = Math.toRadians(degPerTick);
        }

        void reset() {
            dir = null;
            last = Double.NaN;
        }

        Vec3 toward(Vec3 eye, Vec3 target, double t) {
            Vec3 want = target.subtract(eye).normalize();
            double dt = Double.isNaN(last) ? 0 : Math.max(0, t - last);
            last = t;
            if (dir == null || dt == 0 && t <= 0) {
                dir = want;
            } else {
                // сглаживание (доля пути за тик) и предел угловой скорости
                Vec3 smooth = dir.lerp(want, Mth.clamp(1 - Math.pow(0.82, dt), 0, 1)).normalize();
                double angle = Math.acos(Mth.clamp(dir.dot(smooth), -1, 1));
                double limit = maxStep * dt;
                dir = angle <= limit || angle < 1e-6 ? smooth : dir.lerp(smooth, limit / angle).normalize();
            }
            return eye.add(dir.scale(50));
        }
    }

    // ================================================================ клиент: что сейчас в мире

    /** Середина оболочки дирижабля у клиента; до сборки и когда его нет — место сборки. */
    private Vec3 airshipNow() {
        UUID id = airship;
        Vec3 a = anchor;
        if (id == null || film.mc.level == null) return a;
        SubLevelAccess s = SubLevels.byId(film.mc.level, a, id);
        return s == null ? a : SubLevels.center(s);
    }

    /**
     * Середина обломков (аппаратов у места удара) по объёму рамок: до удара — сам дирижабль. Куски выше места удара не
     * в счёт: падение — то, что снимаем, а отброшенный взрывом вверх винт увёл бы взгляд в дым.
     */
    @Nullable
    private Vec3 wreck(Vec3 around) {
        if (film.mc.level == null) return null;
        Vec3 sum = Vec3.ZERO;
        double w = 0;
        for (SubLevelAccess s : SubLevels.near(film.mc.level, around, 90)) {
            if (SubLevels.center(s).y > around.y + 6) continue;
            BoundingBox3dc b = s.boundingBox();
            double v = Math.max(1, (b.maxX() - b.minX()) * (b.maxY() - b.minY()) * (b.maxZ() - b.minZ()));
            sum = sum.add(SubLevels.center(s).scale(v));
            w += v;
        }
        return w == 0 ? null : sum.scale(1 / w);
    }

    private int pieces(Vec3 around) {
        return film.mc.level == null ? 0 : SubLevels.near(film.mc.level, around, 70).size();
    }

    /** Крылатая ракета в полёте ближе {@code range} блоков к дирижаблю (по путям снарядов у клиента — и вне мира). */
    private boolean missileWithin(double range) {
        Vec3 a = airshipNow();
        double[] p = new double[3];
        for (FlightTrack t : FlightTracks.all()) {
            if (t.weapon != WeaponType.MISSILE || t.isDead() || t.lastTick() < 0) continue;
            t.at(t.lastTick(), p);
            if (a.distanceToSqr(p[0], p[1], p[2]) < range * range) return true;
        }
        return false;
    }

    // ================================================================ сервер: постройка

    /** Дирижабль из блоков в мире: оболочка, оперение, подвешенная гондола с горелками, моторы и подшипники винтов. */
    private void build(ServerLevel level) {
        anchor = ship(0, 0, 0);
        airship = null;
        List<String> cmds = new ArrayList<>();
        for (int i = -15; i <= 15; i++) {
            for (int j = -6; j <= 6; j++) {
                for (int k = -6; k <= 6; k++) {
                    if (!inside(i, j, k) || inside(i + 1, j, k) && inside(i - 1, j, k) && inside(i, j + 1, k) && inside(i, j - 1, k)
                            && inside(i, j, k + 1) && inside(i, j, k - 1)) {
                        continue;
                    }
                    // полосы темнее у носа, хвоста и посередине
                    String color = Math.abs(i) >= 12 || Math.abs(i) == 6 ? "light_gray" : "white";
                    cmds.add(set(i, j, k, "aeronautics:" + color + "_envelope"));
                }
            }
        }
        // оперение — крестом на хвосте
        for (int i = -14; i <= -10; i++) {
            int h = i <= -13 ? 3 : i <= -11 ? 2 : 1;
            int top = 0;
            while (inside(i, top + 1, 0)) top++;
            for (int d = 1; d <= h; d++) {
                cmds.add(set(i, top + d, 0, "aeronautics:gray_envelope"));
                cmds.add(set(i, -top - d, 0, "aeronautics:gray_envelope"));
                cmds.add(set(i, 0, top + d, "aeronautics:gray_envelope"));
                cmds.add(set(i, 0, -top - d, "aeronautics:gray_envelope"));
            }
        }
        // гондола на столбах ниже оболочки: пол, перила
        for (int i = GONDOLA - 5; i <= GONDOLA + 5; i++) {
            for (int k = -2; k <= 2; k++) {
                cmds.add(set(i, FLOOR, k, "minecraft:dark_oak_planks"));
                if (Math.abs(i - GONDOLA) == 5 || Math.abs(k) == 2) cmds.add(set(i, FLOOR + 1, k, "minecraft:dark_oak_fence"));
            }
        }
        for (int i : new int[]{GONDOLA - 4, GONDOLA + 4}) {
            for (int k : new int[]{-2, 2}) {
                for (int j = FLOOR + 2; j < bottom(i, k); j++) cmds.add(set(i, j, k, "minecraft:dark_oak_fence"));
            }
        }
        // горелки на блоках редстоуна (сигнал 15), луч вверх — в оболочку через отверстие в её дне
        int gas = gasPerBurner(level, MASS);
        for (int i : new int[]{GONDOLA - 3, GONDOLA + 3}) {
            cmds.add(set(i, bottom(i, 0), 0, "minecraft:air"));
            cmds.add(set(i, FLOOR, 0, "minecraft:redstone_block"));
            cmds.add(set(i, FLOOR + 1, 0, "aeronautics:adjustable_burner"));
            BlockPos b = pos(i, FLOOR + 1, 0);
            cmds.add(fmt("data merge block %d %d %d {ScrollValue:%d}", b.getX(), b.getY(), b.getZ(), gas));
        }
        // моторы на штангах по бокам; подшипник смотрит назад и толкает вперёд, ступица с парусами — после сборки
        String back = along.getOpposite().getSerializedName();
        for (int s : new int[]{-1, 1}) {
            for (int k = 5; k <= 8; k++) cmds.add(set(1, 0, s * k, "minecraft:dark_oak_planks"));
            cmds.add(set(1, 0, s * 9, "simulated:gray_portable_engine[facing=" + back + "]"));
            cmds.add(set(0, 0, s * 9, "aeronautics:propeller_bearing[facing=" + back + "]"));
        }
        cmds.forEach(cast()::command);
        Incoming.LOG.info("TRAILER airship: дирижабль из {} команд у {}, горелки по {}", cmds.size(), anchor, gas);
    }

    /**
     * Сборка в аппарат Sable, горелки — по его настоящей массе, ступицы с парусами перед подшипниками (на участке: в мире
     * до сборки Sable оставляет их на месте), топливо в моторы.
     */
    private void assemble(ServerLevel level) {
        // новый аппарат — по списку Sable до и после команды (поиск по рамкам видит его только со следующего тика)
        Set<UUID> before = new HashSet<>();
        for (SubLevel s : SubLevelContainer.getContainer(level).getAllSubLevels()) before.add(s.getUniqueId());
        BlockPos a = pos(-16, FLOOR - 1, -13), b = pos(16, 7, 13);
        cast().command(fmt("sable assemble area %d %d %d %d %d %d", Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
        SubLevelAccess sub = null;
        for (SubLevel s : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            if (!before.contains(s.getUniqueId())) sub = s;
        }
        if (sub == null) {
            Incoming.LOG.warn("TRAILER airship: сборка не дала аппарата");
            return;
        }
        airship = sub.getUniqueId();
        double mass = SubLevelContainer.getContainer(level).getSubLevel(airship) instanceof ServerSubLevel craft
                ? craft.getMassTracker().getMass() : MASS;
        int gas = gasPerBurner(level, mass);
        for (int i : new int[]{GONDOLA - 3, GONDOLA + 3}) {
            BlockPos q = plot(sub, pos(i, FLOOR + 1, 0));
            cast().command(fmt("data merge block %d %d %d {ScrollValue:%d}", q.getX(), q.getY(), q.getZ(), gas));
        }
        String back = along.getOpposite().getSerializedName();
        hubs.clear();
        bearings.clear();
        motors.clear();
        for (int s : new int[]{-1, 1}) {
            BlockPos hub = plot(sub, pos(-1, 0, s * 9));
            hubs.add(hub);
            bearings.add(plot(sub, pos(0, 0, s * 9)));
            cast().command(fmt("setblock %d %d %d create:andesite_casing", hub.getX(), hub.getY(), hub.getZ()));
            for (int d = 1; d <= 3; d++) {
                for (BlockPos q : new BlockPos[]{hub.above(d), hub.below(d), plot(sub, pos(-1, 0, s * (9 + d))), plot(sub, pos(-1, 0, s * (9 - d)))}) {
                    cast().command(fmt("setblock %d %d %d create:white_sail[facing=%s]", q.getX(), q.getY(), q.getZ(), back));
                }
            }
            motors.add(plot(sub, pos(1, 0, s * 9)));
        }
        Incoming.LOG.info("TRAILER airship: аппарат {} у {}, масса {}, горелки по {}", airship, anchor, fmt("%.1f", mass), gas);
    }

    /** Уголь в моторы: мотор крутит подшипник, а подшипник Create, когда его начинают крутить, сам собирает винт. */
    private void startEngines(ServerLevel level) {
        for (BlockPos m : motors) {
            IItemHandler fuel = level.getCapability(Capabilities.ItemHandler.BLOCK, m, null);
            if (fuel == null || !fuel.insertItem(0, new ItemStack(Items.COAL_BLOCK, 64), false).isEmpty()) {
                Incoming.LOG.warn("TRAILER airship: мотор у {} не взял топливо", m);
            }
        }
    }

    /**
     * Винты: подшипник Create сам собирает винт, когда его начинают крутить; не собрал (мотор раскрутился раньше, чем
     * подшипник увидел паруса) — щелчок пустой рукой, как игрок на гондоле (наводчик — игрок, его щелчок и проверяется).
     * Собран — ступицы на участке нет: её забрало вращающееся тело винта.
     */
    private void propellers(ServerLevel level) {
        ServerPlayer gunner = cast().player(GUNNER);
        int ready = 0;
        for (int n = 0; n < hubs.size(); n++) {
            if (level.getBlockState(hubs.get(n)).isAir()) {
                ready++;
            } else if (gunner != null) {
                BlockPos b = bearings.get(n);
                level.getBlockState(b).useItemOn(ItemStack.EMPTY, level, gunner, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(b), Direction.UP, b, false));
            }
        }
        Incoming.LOG.info("TRAILER airship: винтов собрано {} из {}", ready, hubs.size());
    }

    /** Горячего воздуха на горелку, чтобы подъём на высоте оболочки равнялся массе: V·1.5·p = M (Aeronautics, Sable). */
    private int gasPerBurner(ServerLevel level, double mass) {
        Vec3 c = anchor;
        double pressure = DimensionPhysicsData.getAirPressure(level, new Vector3d(c.x, c.y, c.z));
        return Mth.clamp((int) Math.round(mass / (LIFT_PER_GAS * Math.max(0.05, pressure)) / 2), 5, 500);
    }

    /** Середина дирижабля на сервере (по рамке аппарата), без аппарата — место сборки. */
    private Vec3 centre(ServerLevel level) {
        UUID id = airship;
        SubLevelAccess s = id == null ? null : SubLevels.byId(level, anchor, id);
        return s == null ? anchor : SubLevels.center(s);
    }

    /**
     * Камера с земли: у точки {@link #GROUND_EYE} — первое место (по кольцам через 8 блоков), откуда видно и дирижабль,
     * и небо под ним, куда он упадёт: не под деревом, не за крышей. Стоит на верхнем блоке (листва и крыши — тоже).
     * Дирижабля ещё нет (строится после), так что луч — до самого места.
     */
    private Vec3 groundEye(ServerLevel level) {
        Vec3 nominal = ship(GROUND_EYE[0], 0, GROUND_EYE[1]), craft = ship(0, 0, 0), fall = ship(0, -40, 0);
        Vec3 first = null;
        for (int ring = 0; ring <= 3; ring++) {
            for (int a = 0; a < (ring == 0 ? 1 : 8); a++) {
                double ang = a * Math.PI / 4;
                Vec3 c = nominal.add(Math.cos(ang) * ring * 8, 0, Math.sin(ang) * ring * 8);
                LevelChunk chunk = level.getChunkSource().getChunkNow(Mth.floor(c.x) >> 4, Mth.floor(c.z) >> 4);
                if (chunk == null) continue;
                Vec3 eye = new Vec3(c.x, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(c.x) & 15, Mth.floor(c.z) & 15) + 2.6, c.z);
                if (first == null) first = eye;
                if (clear(level, eye, craft) && clear(level, eye, fall)) return eye;
            }
        }
        Incoming.LOG.warn("TRAILER airship: камере с земли не нашлось открытого места у {}", nominal);
        return first != null ? first : new Vec3(nominal.x, at.y + 1.6, nominal.z);
    }

    private static boolean clear(ServerLevel level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()))
                .getType() == HitResult.Type.MISS;
    }

    /** Аппараты прошлого дубля (обломки на земле и в воздухе) убираются перед новым. */
    private void clearWrecks(ServerLevel level) {
        for (SubLevelAccess s : SubLevels.near(level, p(AIRSHIP[0], 0, AIRSHIP[2]), 200)) {
            cast().command("sable remove " + s.getUniqueId());
        }
    }

    /** ЗРК наводчика (свой ему и его ракете) с полным запасом. */
    private void sam(ServerLevel level, Vec3 at) {
        BlockPos pos = BlockPos.containing(at.x, ground(level, at), at.z);
        ServerPlayer owner = cast().player(GUNNER);
        level.setBlockAndUpdate(pos, ModBlocks.SAM.get().defaultBlockState());
        if (level.getBlockEntity(pos) instanceof SamBlockEntity sam && owner != null) {
            sam.setOwner(owner.getUUID());
            sam.setChanged();
        }
        IItemHandler stock = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (stock != null) {
            for (int slot = 0; slot < stock.getSlots(); slot++) stock.insertItem(slot, new ItemStack(ModItems.INTERCEPTOR.get(), 16), false);
        }
    }

    /** Сколько зенитных ракет уже вышло в этом дубле (вид ракеты в мире над тикающими чанками). */
    private int countInterceptors(ServerLevel level) {
        for (InterceptorEntity e : level.getEntities(ModEntities.INTERCEPTOR.get(), new AABB(BlockPos.containing(at)).inflate(400), x -> true)) {
            interceptors.add(e.getId());
        }
        return interceptors.size();
    }

    /** Высота земли (первый воздух над твёрдым, без листвы) в готовом чанке; не готов — уровень сцены. */
    private double ground(ServerLevel level, Vec3 v) {
        int x = Mth.floor(v.x), z = Mth.floor(v.z);
        LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        return c == null ? at.y : c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
    }

    /** Район сцены держится загруженным (дирижабль и ЗРК тикают и без игрока рядом). */
    private void forceload(ServerLevel level, boolean on) {
        // оба дубля и куда падают обломки второго (левее всех)
        double left = AIRSHIP[0] + SECOND_TAKE - 50;
        double[][] corners = {{left, -100}, {70, -100}, {left, 120}, {70, 120}};
        double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (double[] c : corners) {
            Vec3 v = p(c[0], 0, c[1]);
            x0 = Math.min(x0, v.x);
            z0 = Math.min(z0, v.z);
            x1 = Math.max(x1, v.x);
            z1 = Math.max(z1, v.z);
        }
        cast().command(fmt("forceload %s %d %d %d %d", on ? "add" : "remove", Mth.floor(x0), Mth.floor(z0), Mth.floor(x1), Mth.floor(z1)));
    }

    private boolean areaReady(ServerLevel level) {
        double second = AIRSHIP[0] + SECOND_TAKE;
        for (double[] rf : new double[][]{{0, 0}, {-60, -90}, {60, -90}, {-60, 110}, {60, 110}, {AIRSHIP[0], AIRSHIP[2]},
                {second, AIRSHIP[2]}, {second + GROUND_EYE[0], AIRSHIP[2] + GROUND_EYE[1]}}) {
            Vec3 v = p(rf[0], 0, rf[1]);
            ChunkPos c = new ChunkPos(BlockPos.containing(v));
            if (level.getChunkSource().getChunkNow(c.x, c.z) == null) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- координаты дирижабля

    private boolean inside(int i, int j, int k) {
        double x = i / HALF_LENGTH, y = j / HALF_HEIGHT, z = k / HALF_WIDTH;
        return x * x + y * y + z * z <= 1.0;
    }

    /** Нижний блок оболочки в столбце (i, k). */
    private int bottom(int i, int k) {
        int j = 0;
        while (inside(i, j - 1, k)) j--;
        return j;
    }

    /** Блок дирижабля в мире: {@code i} — вдоль (по ходу), {@code j} — вверх, {@code k} — поперёк, от середины оболочки. */
    private BlockPos pos(int i, int j, int k) {
        return BlockPos.containing(ship(0, 0, 0)).relative(along, i).above(j).relative(across, k);
    }

    private String set(int i, int j, int k, String block) {
        BlockPos b = pos(i, j, k);
        return fmt("setblock %d %d %d %s", b.getX(), b.getY(), b.getZ(), block);
    }

    /** Место блока на участке аппарата (сразу после сборки поворота нет — место точное). */
    private static BlockPos plot(SubLevelAccess sub, BlockPos world) {
        return BlockPos.containing(SubLevels.toPlot(sub, Vec3.atCenterOf(world)));
    }

    // ---------------------------------------------------------------- шаги

    /** Шаг ждёт условия на сервере (проверка — в потоке сервера, раз в тик клиента). */
    private void waitServer(Predicate<ServerLevel> cond, int timeout, String what) {
        final boolean[] asked = {false}, done = {false};
        final int[] waited = {0};
        film.then(() -> {
            if (done[0]) return true;
            if (++waited[0] > timeout) {
                Incoming.LOG.warn("TRAILER airship: не дождались: {}", what);
                return true;
            }
            if (!asked[0]) {
                asked[0] = true;
                MinecraftServer server = film.mc.getSingleplayerServer();
                server.execute(() -> {
                    boolean ok = cond.test(server.overworld());
                    film.mc.execute(() -> {
                        asked[0] = false;
                        if (ok) done[0] = true;
                    });
                });
            }
            return false;
        });
    }

    /** Наводчик смотрит на точку ({@code bot.act}): голову бот поворачивает в свой следующий тик. */
    private void aim(Vec3 point) {
        JsonArray steps = Cast.steps();
        steps.add(Cast.lookAt(point));
        cast().act(GUNNER, steps, true);
    }
}
