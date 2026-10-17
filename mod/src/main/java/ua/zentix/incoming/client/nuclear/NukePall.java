package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.FrustumIntersection;
import org.joml.Vector3f;
import org.joml.Vector4f;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Пелена после ударной волны у клиента ({@link PallField}): что рисует слой эффектов ({@link #frame} — до двух самых
 * заметных подрывов, таблица и свет кадра для {@link PallPass}, и в тех же лучах — грибы кадра, {@link MushroomField}),
 * и что пелена делает у камеры — глушит звук ({@link #muffle}) и сыплет пепел ({@link NukeAsh}). Своего пакета нет:
 * поле — по модели подрыва.
 */
public final class NukePall {
    /** Плитка шума у 15 кт, блоков на метр масштаба: клубы — по 60 м, мелкие края — по 20. */
    static final double TILE_M = 256;
    /** Плотность пыли у камеры (1/блок) → глухость: в самой густой (видно на 40 м) — 0,9. */
    static final double MUFFLE_PER_SIGMA = 25;

    private static final PallPass.Frame FRAME = new PallPass.Frame();
    private static final float[] ROW = new float[2 * PallPass.WIDTH * 4];
    private static final double[] DRIFT = new double[2], SURGE = new double[3];
    private static final Vector3f LO = new Vector3f(), HI = new Vector3f(), CULL = new Vector3f();
    private static final float[] GLOW = new float[8];
    /** Числа стены пыли слота ({@link BlastWall#slot}) — в кадр и у камеры. */
    private static final float[] WALL = new float[BlastWall.SLOT], WALL_TICK = new float[BlastWall.SLOT];
    /** Цвет толстой пыли ({@code DUST} в {@code fx_pall.fsh}, цвет стены пыли). */
    static final double[] DUST = {0.60, 0.53, 0.45};
    /** Цвет толстого дыма над пожарами ({@code SMOKE} в {@code fx_pall.fsh}) и пламени пожаров на экране ({@code FIRE}). */
    static final double[] SMOKE = {0.30, 0.27, 0.24}, FIRE = {1.0, 0.541, 0.180};
    /** Асимметрия пыли после дельта-Эддингтона и альбедо пыли и дыма по каналам ({@code PALL_DUST_G}, {@code PALL_DUST_W}, {@code PALL_SMOKE_W}). */
    private static final double DUST_G = PallLight.truncated(PallLight.DUST_G);
    private static final double[] DUST_W = albedo(DUST), SMOKE_W = albedo(SMOKE);
    /** Свет кадра и стена у пыли камеры ({@link #dustColor}). */
    private static final Sky SKY = new Sky();
    private static final float[] DUST_WALL = new float[BlastWall.SLOT], DUST_GLOW = new float[8];
    /** Подрывы слотов кадра — для огненного шторма после света кадра. */
    private static final ClientNuclear.Active[] SLOTTED = new ClientNuclear.Active[2];
    /**
     * Плоскости пирамиды видимости, по которым пелена отсекается, — все, кроме дальней: дальняя плоскость — 4 прорисовки,
     * а пелена видна и дальше (над LOD Distant Horizons, на горизонте).
     */
    private static final int SIDES_AND_NEAR = FrustumIntersection.PLANE_MASK_NX | FrustumIntersection.PLANE_MASK_PX
            | FrustumIntersection.PLANE_MASK_NY | FrustumIntersection.PLANE_MASK_PY | FrustumIntersection.PLANE_MASK_NZ;
    /** Ослабление у камеры (1/блок), из него — водяной пыли, и глухость 0..1 — для звукового потока. */
    private static volatile float sigma, mist, muffle;
    private static volatile double earX, earY, earZ;

    private NukePall() {}

    /** Качество из настройки игрока. */
    static PallPass.Quality quality() {
        return switch (IncomingConfig.CLIENT.nukeDustQuality.get()) {
            case OFF -> PallPass.Quality.OFF;
            case LOW -> PallPass.Quality.LOW;
            case MEDIUM -> PallPass.Quality.MEDIUM;
            case HIGH -> PallPass.Quality.HIGH;
        };
    }

    /**
     * Глушит ли пелена звуки: только пока она есть на экране (не OFF) и звук вдали глушится по настройке «Глушить звук
     * вдали и за преградой» (её и Sound Physics Remastered не отменяет: пыли он не знает).
     */
    static boolean muffles() {
        return quality() != PallPass.Quality.OFF && IncomingConfig.CLIENT.soundMuffling.get();
    }

    /** Тик клиента: пелена у камеры — звук и пепел; шум для объёма (пелены и гриба) — в фоне с первого подрыва. */
    static void tick(ClientLevel level, Vec3 cam) {
        List<ClientNuclear.Active> all = ClientNuclear.detonations();
        FireStorms.tick(all);
        if (all.isEmpty()) {
            sigma = mist = muffle = 0;
            NukeAsh.tick(level, cam, 0, 0, null);
            return;
        }
        PallPass.prepare();
        double s = 0, wet = 0, densest = 0, dust = 0, headMax = 0, headWet = 0;
        // пепел и ветер — от подрыва, чья пелена у камеры гуще всех; пелена на экране — от самой густой головы стены
        ClientNuclear.Active nearest = null, headAt = null;
        for (ClientNuclear.Active a : all) {
            double ticks = a.ticks(0);
            Detonation d = a.d;
            double dx = cam.x - d.burst().x, dz = cam.z - d.burst().z;
            // голова стены пыли у камеры — средняя, как в кадре (рельеф и вода под камерой — по карте пелены)
            double head = 0, wetHere = 0;
            if (a.wall.slot(a.seconds(0), WALL_TICK) && WALL_TICK[3] > 0) {
                wetHere = a.ground.hasWater() ? a.ground.water(cam.x, cam.z) : 0;
                head = BlastWall.sigma(WALL_TICK, dx, dz, cam.y - d.groundY() - a.ground.at(cam.x, cam.z), wetHere);
                if (head > headMax) {
                    headMax = head;
                    headWet = wetHere;
                    headAt = a;
                }
            }
            boolean pall = a.pall.visible(ticks);
            if (!pall && head <= 0) continue;
            PallField.Water water = water(a);
            double here = head + (pall ? a.pall.sigma(dx, dz, cam.y - d.groundY(), ticks, water) : 0);
            if (here > densest) {
                densest = here;
                nearest = a;
            }
            s += here;
            // над водой голова — брызги
            wet += head * wetHere + (pall ? a.pall.mist(dx, dz, cam.y - d.groundY(), ticks, water) : 0);
            dust = Math.max(dust, a.pall.ashRate(dx, dz, ticks, water));
        }
        sigma = (float) s;
        mist = (float) wet;
        if (headAt != null && !drawsHead(headAt, cam)) NukeDust.veil(headAt, cam, headMax, headWet > 0.5);
        if (muffles()) {
            // глухость меняется плавно: пелена накрывает за секунды
            float target = (float) (1 - Math.exp(-MUFFLE_PER_SIGMA * s));
            muffle += (target - muffle) * 0.1f;
        } else {
            muffle = 0;
        }
        earX = cam.x;
        earY = cam.y;
        earZ = cam.z;
        // обломки — из пыли: мгла брызг их не несёт
        NukeAsh.tick(level, cam, dust, s - wet, nearest);
    }

    /** Доля воды под пеленой подрыва (dx, dz — от эпицентра) — по его карте {@link PallGround}. */
    static PallField.Water water(ClientNuclear.Active a) {
        double x0 = a.d.burst().x, z0 = a.d.burst().z;
        return (dx, dz) -> a.ground.water(x0 + dx, z0 + dz);
    }

    static void reset() {
        sigma = mist = muffle = 0;
        PallPass.reset();
        NukeAsh.reset();
    }

    /**
     * Насколько пелена глушит этот звук: 0 — никак, до 0,9 в самой густой пыли — по плотности у уха и пути через неё
     * (звук у самого уха и интерфейс — нет; пелена выключена или звук вдали не глушится — {@link #muffles}). Зовут из
     * звукового потока.
     */
    public static float muffle(SoundInstance sound) {
        float m = muffle;
        if (m <= 0.01f || sound.getSource() == SoundSource.MUSIC || sound.getSource() == SoundSource.RECORDS
                || sound.getSource() == SoundSource.MASTER && sound.isRelative()) return 0;
        double d = sound.isRelative() ? Math.sqrt(sound.getX() * sound.getX() + sound.getY() * sound.getY() + sound.getZ() * sound.getZ())
                : Math.sqrt(Mth.square(sound.getX() - earX) + Mth.square(sound.getY() - earY) + Mth.square(sound.getZ() - earZ));
        return m * (float) (1 - Math.exp(-sigma * Math.max(8, d)));
    }

    /**
     * Пелена и грибы кадра для слоя эффектов: пелена — до двух подрывов, ближайших к камере по краю их пелены или головы
     * стены ({@link #outside}), из тех, у
     * кого в таблице хоть что-то есть (до фронта и после — пусто) и чья рамка в кадре ({@code frustum} — пирамида
     * видимости от глаза, без дальней плоскости); грибы — {@link MushroomField#frame}. Пелена выключена в настройке —
     * только грибы, на ступени LOW (другого гриба нет). null — ни пелены, ни гриба в кадре, ни шара, что светит на мир
     * ({@link MushroomField#balls}): тогда слой не тратит на них ни одного прохода.
     */
    @Nullable
    public static PallPass.Frame frame(ClientLevel level, Vec3 eye, float partial, FrustumIntersection frustum) {
        if (!PallPass.available()) return null;
        List<ClientNuclear.Active> all = ClientNuclear.detonations();
        if (all.isEmpty()) return null;
        PallPass.Quality q = quality();
        PallPass.Frame f = FRAME;
        double air = Sight.KOSCHMIEDER / Sight.range(level.getRainLevel(partial), level.getThunderLevel(partial));
        // свет кадра — до грибов: днём в кадр идёт и гриб, чья тень в нём (MushroomField.frame)
        light(level, eye, partial, f);
        f.mushroomCount = MushroomField.frame(all, eye, partial, frustum, air, f.sun, level.getMinBuildHeight(), level.getMaxBuildHeight(), f.mushrooms);
        // свет шаров на мире — от всех подрывов: шар за спиной освещает землю перед глазом
        openSky(level, f.openSky, f.faceShade);
        double open = 0.2126 * PallLight.linear(f.openSky.x) + 0.7152 * PallLight.linear(f.openSky.y) + 0.0722 * PallLight.linear(f.openSky.z);
        f.ballsLit = MushroomField.balls(all, eye, partial, air, f.exposure, open, f.balls, f.ballLight);
        List<ClientNuclear.Active> visible = new ArrayList<>();
        if (q != PallPass.Quality.OFF) for (ClientNuclear.Active a : all) if (visible(a, partial)) visible.add(a);
        if (visible.isEmpty() && f.mushroomCount == 0 && !f.ballsLit) return null;
        visible.sort(Comparator.comparingDouble(a -> outside(a, eye, partial)));
        f.quality = q == PallPass.Quality.OFF ? PallPass.Quality.LOW : q;
        f.slot0.zero();
        f.slot1.zero();
        f.glow0.zero();
        f.glow1.zero();
        f.hot0.zero();
        f.hot1.zero();
        f.ground0.clear();
        f.ground1.clear();
        f.water0.zero();
        f.water1.zero();
        f.wall0.zero();
        f.wall1.zero();
        f.wilson0.zero();
        f.wilson1.zero();
        // мир кадра — чанки в дальности прорисовки (с запасом на край по чанкам): луч в пустоту прошёл их, ничего не задев
        f.floorFrom = Math.max(16, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16 - 24);
        f.min.set(Float.MAX_VALUE);
        f.max.set(-Float.MAX_VALUE);
        int k = 0;
        float bottom = (float) (level.getMinBuildHeight() - eye.y);
        for (int i = 0; i < visible.size() && k < 2; i++) if (slot(visible.get(i), k, eye, partial, frustum, bottom, air, f)) SLOTTED[k++] = visible.get(i);
        if (k == 0 && f.mushroomCount == 0 && !f.ballsLit) return null;
        // без пелены — рамка нулевая: луч в неё не входит (перевёрнутая рамка вышла бы всем лучом)
        if (k == 0) {
            f.min.zero();
            f.max.zero();
        }
        MushroomField.fire(partial, f);
        float clouds = level.effects().getCloudHeight();
        FireStorms.frame(SLOTTED, k, eye, partial, frustum, Float.isNaN(clouds) ? 1e6f : (float) (clouds - eye.y), f);
        // дальше 12 толщ пыли у глаза луч не идёт; выключенная пелена не рисуется — и гриб за ней не прячет
        float s = q == PallPass.Quality.OFF ? 0 : sigma;
        f.clip = s > 1e-4f ? 12 / s : 1e6f;
        return f;
    }

    /**
     * Свет открытого неба на белом блоке, как его рисует Minecraft, на экране — в {@code out}: карта освещения кадра
     * (небо 15, свет блоков 0; время суток, яркость в настройках, зелья, измерение), и затенение граней блоков измерения
     * в {@code shade} (верх, низ, север и юг, запад и восток). По ним вывод на кадр узнаёт цвет мира под светом шара:
     * пиксель, делённый на свет неба и затенение своей грани, — цвет самого блока.
     */
    private static void openSky(ClientLevel level, Vector3f out, Vector4f shade) {
        int c = Minecraft.getInstance().gameRenderer.lightTexture().lightPixels.getPixelRGBA(0, 15);
        out.set((c & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f, (c >> 16 & 0xFF) / 255f);
        shade.set(level.getShade(Direction.UP, true), level.getShade(Direction.DOWN, true), level.getShade(Direction.NORTH, true),
                level.getShade(Direction.EAST, true));
    }

    /** Есть ли что рисовать у подрыва: пелена, голова стены пыли или облако Вильсона. */
    private static boolean visible(ClientNuclear.Active a, float partial) {
        double t = a.seconds(partial);
        return a.pall.visible(a.ticks(partial)) || a.wall.reach(t) > 0 || a.wall.wilsonReach(t) > 0;
    }

    /**
     * Насколько камера вне пелены подрыва и головы его стены (по земле), блоки; 0 — внутри. По одной пелене голова,
     * которая дошла до камеры дальше пелены, уступала бы слот подрывам, в чьей пелене камера, и у глаза не рисовалась.
     */
    private static double outside(ClientNuclear.Active a, Vec3 eye, float partial) {
        double r = Math.max(a.pall.extent(a.ticks(partial)), a.wall.reach(a.seconds(partial)));
        return Math.max(0, Math.hypot(eye.x - a.d.burst().x, eye.z - a.d.burst().z) - r);
    }

    /**
     * Слот k ({@code pall.glsl}): поле подрыва, его строки таблицы, базисная волна и рамка; false — слот не нужен (в таблице
     * пусто и волны нет, или рамка вне кадра), он остаётся свободным для следующего подрыва. Чего под пеленой нет — суши
     * или воды ({@link PallGround}), — того нет и в таблице: подрыв посреди моря, когда брызги выпали, а волна растаяла,
     * слота не занимает. {@code bottom} — низ мира от глаза: в кадре подрыв и тогда, когда видна только земля под пеленой,
     * — днём на ней тень пелены ({@code fx_pall_composite}), а долина или карьер под эпицентром бывают глубже низа пелены.
     * {@code air} — ослабление воздуха по погоде, 1/м ({@code Sight}): пелена вдали тает в дымке, как гриб.
     */
    private static boolean slot(ClientNuclear.Active a, int k, Vec3 eye, float partial, FrustumIntersection frustum, float bottom, double air,
                                PallPass.Frame f) {
        Detonation d = a.d;
        PallField p = a.pall;
        double ticks = a.ticks(partial);
        boolean water = a.ground.hasWater();
        p.row(ticks, a.ground.hasLand(), water, ROW);
        double top = 1;
        boolean any = false;
        for (int i = 0; i < 2 * PallPass.WIDTH; i++) {
            // пыль или мгла; брызги (у узлов мглы — нули)
            any |= ROW[4 * i] > 0 || ROW[4 * i + 3] > 0;
            top = Math.max(top, ROW[4 * i + 1]);
        }
        if (p.surge(ticks, SURGE)) {
            any = true;
            top = Math.max(top, SURGE[1]);
        }
        // стена пыли: голова и облако Вильсона — в том же объёме
        double seconds = a.seconds(partial);
        boolean wall = a.wall.slot(seconds, WALL);
        any |= wall;
        if (!any) return false;
        p.drift(ticks, DRIFT);
        double plume = p.plume(ticks);
        float cx = (float) (d.burst().x - eye.x), cz = (float) (d.burst().z - eye.z), ground = (float) (d.groundY() - eye.y);
        // рамка: снос пыли и шлейф мглы — в любую сторону от эпицентра, ниже земли под ним — долины на всю высоту; голова
        // стены — на рельефе карты (pallWallSpans), облако Вильсона — вокруг точки подрыва
        float r = (float) Math.max(p.extent(ticks), Math.max(a.wall.reach(seconds) + 1, a.wall.wilsonReach(seconds)));
        LO.set(cx - r, (float) (ground - top), cz - r);
        HI.set(cx + r, (float) (ground + top * 1.05), cz + r);
        if (WALL[3] > 0) {
            LO.y = Math.min(LO.y, ground + Math.min(a.ground.low(), 0) - 0.5f * WALL[2]);
            HI.y = Math.max(HI.y, ground + Math.max(a.ground.high(), 0) + WALL[2]);
        }
        if (WALL[18] > 0) HI.y = Math.max(HI.y, (float) (ground + WALL[19] + a.wall.wilsonReach(seconds)));
        CULL.set(LO.x, Math.min(LO.y, bottom), LO.z);
        if (frustum.intersectAab(CULL, HI, SIDES_AND_NEAR) >= 0) return false;
        System.arraycopy(ROW, 0, f.table, k * ROW.length, ROW.length);
        double ys = Math.cbrt(d.yieldKt() / 15);
        org.joml.Matrix4f m = k == 0 ? f.slot0 : f.slot1;
        m.m00(cx).m01(cz).m02(ground).m03((float) p.reach());
        m.m10((float) DRIFT[0]).m11((float) DRIFT[1]).m12((float) plume).m13(1);
        m.m20((float) Math.cos(d.windDir())).m21((float) Math.sin(d.windDir())).m22((2 * k + 0.5f) / PallPass.ROWS).m23((2 * k + 1.5f) / PallPass.ROWS);
        m.m30((float) (PallField.SIGMA_DENSE / d.scale())).m31((float) Math.max(48, d.blocks(TILE_M * ys))).m32((float) (ticks / 20.0)).m33((float) (air / d.scale()));
        // вода слота: базисная волна и есть ли под пеленой вода (иначе шейдер карту воды не читает)
        (k == 0 ? f.water0 : f.water1).set((float) SURGE[0], (float) SURGE[1], (float) SURGE[2], water ? 1 : 0);
        // свет шара и пожаров на пыли (CloudPuffs.pallLight)
        a.puffs.pallLight(a.seconds(partial), GLOW);
        (k == 0 ? f.glow0 : f.glow1).set(GLOW[0], GLOW[1], GLOW[2], GLOW[3]);
        (k == 0 ? f.hot0 : f.hot1).set(GLOW[4], GLOW[5], GLOW[6], GLOW[7]);
        a.ground.frame(k == 0 ? f.ground0 : f.ground1);
        (k == 0 ? f.wall0 : f.wall1).set(WALL);
        (k == 0 ? f.wilson0 : f.wilson1).set(WALL[16], WALL[17], WALL[18], WALL[19]);
        f.min.min(LO);
        f.max.max(HI);
        return true;
    }

    /**
     * Солнце, небо и пожары кадра. Свет солнца и неба — линейный ({@link PallLight}), белая горизонтальная стена под
     * полуденным солнцем и небом — 1, как верх белого блока днём. Небо — каким его рисует Minecraft, в линейном
     * ({@link PallLight#SKY}: в полдень — около пятой части дневного света), солнце — остальное до белого: синее неба взято
     * из его пучка, вместе они — белый дневной свет. Свет солнца — что от него на белой стене к солнцу, неба — на белой
     * стене под всем небом.
     */
    private static void light(ClientLevel level, Vec3 eye, float partial, PallPass.Frame f) {
        sky(level, eye, partial, SKY);
        f.sun.set(SKY.sx, SKY.sy, 0, SKY.day);
        f.skyLight.set(SKY.sky[0], SKY.sky[1], SKY.sky[2]);
        f.sunLight.set(SKY.sun[0], SKY.sun[1], SKY.sun[2]);
        f.base = SKY.base;
        f.exposure = SKY.exposure;
        f.glowOnSmoke = SKY.glowOnSmoke;
        // зарево пожаров снизу на белой пелене, на экране поверх света неба (fx_pall берёт его разностью, pallLift; пыль
        // и мгла — по своему альбедо): днём его почти не видно,
        // ночью ярче ночного неба в десяток раз, но не как огонь (ярче — и вся мгла над горящим городом выходила
        // ровно-оранжевой)
        float fire = 0.6f * (1 - 0.75f * SKY.day);
        f.fireLight.set(fire, fire * 0.45f, fire * 0.16f);
        // цвет тонкой пыли на экране (PallLight.thin) — квадратам слоя, перед которыми пелены почти нет
        float mu = Math.max(SKY.sy, 0.12f);
        f.ambient.set(thinDust(0, mu, f), thinDust(1, mu, f), thinDust(2, mu, f), 1);
    }

    /** Солнце и небо кадра ({@link #light}): направление на солнце и сила дня, свет солнца и неба, свет неба на экране. */
    static final class Sky {
        /** Направление на солнце (x, y; по z — 0) и сила дня 0..1. */
        float sx, sy, day;
        /** Свет солнца и неба по каналам — линейный: белая горизонтальная стена в полдень — 1. */
        final float[] sun = new float[3], sky = new float[3];
        /**
         * Свет неба на экране (белая стена), привыкание глаза к нему ({@code Sight.adapted}: свечение гриба в кадр) и сила
         * отсвета шара на пыли при нём ({@link CloudPuffs#glowOnSmoke}).
         */
        float base, exposure, glowOnSmoke;
    }

    /** Солнце и небо в глазу {@code eye} — в {@code into} (как {@link #light}). */
    static void sky(ClientLevel level, Vec3 eye, float partial, Sky into) {
        float angle = level.getTimeOfDay(partial) * Mth.TWO_PI;
        into.sx = -Mth.sin(angle);
        into.sy = Mth.cos(angle);
        boolean sky = level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
        float day = sky ? smooth(-0.06f, 0.18f, into.sy) * (1 - 0.55f * level.getRainLevel(partial)) * (1 - 0.35f * level.getThunderLevel(partial)) : 0;
        into.day = day;
        Vec3 skyColor = level.getSkyColor(eye, partial);
        float night = 1 - day;
        // ночью — не темнее света неба на клубах стены пыли и гриба (FarView.ambient, 0,17 на экране): цвет неба ночью почти
        // чёрный, и пелена за фронтом была бы чёрным занавесом над городом, где стена пыли и мир ещё видны
        float ambient = FarView.ambient(level, partial);
        float floor = (float) PallLight.linear(nightSky(ambient, night));
        into.sky[0] = skyLight(skyColor.x, floor);
        into.sky[1] = skyLight(skyColor.y, floor);
        into.sky[2] = skyLight(skyColor.z, floor);
        // низкое солнце — краснее (длинный путь через воздух): на экране 1, 0,63, 0,42 к полуденному
        float low = Mth.clamp(into.sy / 0.35f, 0, 1);
        into.sun[0] = sunLight(1, into.sky[0], day);
        into.sun[1] = sunLight((0.62 + 0.36 * low) / 0.98, into.sky[1], day);
        into.sun[2] = sunLight((0.38 + 0.52 * low) / 0.9, into.sky[2], day);
        // отсвет стены и зарево пожаров (на экране) прибавляются к свету неба на экране — к этому
        into.base = ambient;
        // свечение гриба — чёрное тело в настоящей яркости (белая стена в полдень — 1): в кадр — с привыканием глаза к небу
        into.exposure = (float) Sight.adapted(1, ambient);
        into.glowOnSmoke = (float) CloudPuffs.glowOnSmoke(ambient);
    }

    /**
     * Пелена рисует голову стены подрыва {@code a} и пыль у глаза {@code eye} объёмом: качество не OFF, её шейдеры собрались
     * и шум не сломался, и подрыв среди двух, что пелена берёт в кадр ({@link #frame}: ближайшие по краю пелены или головы,
     * при равенстве — раньше в списке; без пирамиды видимости — клубы живут дольше взгляда). Нет — голову у глаза
     * показывают пелена на экране и клубы у камеры ({@link NukeDust}).
     */
    static boolean drawsHead(ClientNuclear.Active a, Vec3 eye) {
        if (quality() == PallPass.Quality.OFF || !PallPass.available()) return false;
        double mine = outside(a, eye, 0);
        int before = 0;
        boolean earlier = true;
        for (ClientNuclear.Active o : ClientNuclear.detonations()) {
            if (o == a) {
                earlier = false;
                continue;
            }
            if (!visible(o, 0)) continue;
            double theirs = outside(o, eye, 0);
            if (theirs < mine || theirs == mine && earlier) before++;
        }
        return before < 2;
    }

    /**
     * Цвет пыли головы стены в точке (x, y, z) мира на экране, r, g, b в 0..1 — в {@code into} ({@link #dustLight}): пыль у
     * камеры ({@link NukeDust}) — цвета самой стены там. false — стены нет.
     */
    static boolean dustColor(ClientNuclear.Active a, Sky sky, double x, double y, double z, float[] into) {
        double t = a.seconds(0);
        if (!a.wall.slot(t, DUST_WALL) || DUST_WALL[3] <= 0) return false;
        a.puffs.pallLight(t, DUST_GLOW);
        Detonation d = a.d;
        double gy = y - d.groundY();
        dustLight(DUST_WALL, DUST_GLOW, sky, x - d.burst().x, gy, z - d.burst().z, gy - a.ground.at(x, z), into);
        return true;
    }

    /**
     * Цвет пыли головы стены на экране — как её светит {@code fx_pall} ({@code wall}, свет в {@code main}), по средней
     * голове, без клубов (их тень — в обе стороны, в среднем ноль) и вне воды (над водой у камеры — брызги,
     * {@link NukeWater}): рассеянный свет солнца, неба и шара — на глубине под меньшей из толщ к верху головы и к её
     * переднему краю, пучки солнца и шара — сквозь толщу головы к ним, пожары — снизу. Днём пыль поэтому бурая в свете
     * солнца и неба, а не в отсвете шара за стеной, ночью сторона стены от шара — в его тени, верх — в отсвете. Фаза
     * пучков — средняя по направлениям: клуб виден со всех сторон.
     *
     * @param wall  стена слота ({@link BlastWall#slot})
     * @param glow  свет стены ({@link CloudPuffs#pallLight})
     * @param gx    точка от эпицентра по x (блоки), gy — над землёй под эпицентром, gz — по z
     * @param up    точка над рельефом под ней (блоки)
     */
    static void dustLight(float[] wall, float[] glow, Sky sky, double gx, double gy, double gz, double up, float[] into) {
        double rx = gx, rz = gz, len = Math.hypot(rx, rz);
        double top = wall[2], reach = 4.5 * wall[1] + top, s = wall[0] - len;
        double ox = len > 1e-3 ? rx / len : 1, oz = len > 1e-3 ? rz / len : 0;
        double above = chord(wall, rx, rz, up, 0, 1, 0, Math.max(top - up, 0), 4);
        double side = chord(wall, rx, rz, up, ox, 0, oz, Math.max(s, 0) + 1, 4);
        double below = chord(wall, rx, rz, up, 0, -1, 0, Math.max(up + 0.5 * top, 0), 2);
        double depth = Math.min(above, side), slab = depth + below;
        double toSun = chord(wall, rx, rz, up, sky.sx, sky.sy, 0, reach, 4);
        double lx = -gx, ly = glow[7] - gy, lz = -gz, ll = Math.max(Math.sqrt(lx * lx + ly * ly + lz * lz), 1e-3);
        double toBall = chord(wall, rx, rz, up, lx / ll, ly / ll, lz / ll, reach, 4);
        double r = Math.hypot(gx, gz), fireR = Math.max(glow[3], 1e-3);
        double lit = CloudPuffs.lit(r, 1, glow[0], glow[2]);
        double burn = CloudPuffs.burn(r, 0, glow[1], glow[3]) * (1 - 0.75 * sky.day);
        double smoke = 0.77 * (1 - CloudPuffs.smooth(0.92 * fireR, 1.04 * fireR, r));
        double muS = Math.max(sky.sy, 0.12), muB = Mth.clamp(ly / ll, 0.12, 1);
        double[] lin = new double[3];
        for (int c = 0; c < 3; c++) {
            double w = Mth.lerp(smoke, DUST_W[c], SMOKE_W[c]);
            double fire = FIRE[c];
            double ball = PallLight.lift(sky.base, glow[4 + c] * lit * sky.glowOnSmoke / DUST[c]);
            double field = PallLight.diffuse(w, DUST_G, depth, slab, PallLight.LAND, muS, sky.sun[c], muB, ball, sky.sky[c], 0, 0);
            double beams = 0.25 * (sky.sun[c] * Math.exp(-toSun) + ball * Math.exp(-toBall));
            double from = 0;
            if (burn > 0) {
                double flame = PallLight.flame(sky.base, fire * burn * sky.glowOnSmoke / DUST[c], fire);
                from = Math.min(Math.max(PallLight.diffuse(w, DUST_G, depth, slab, PallLight.LAND, 1, 0, 1, 0, 0, flame, 0), 0), PallLight.linear(fire));
            }
            lin[c] = PallLight.display(w * (Math.max(field, 0) + beams + from));
        }
        // плечо, как у пелены на экране (fx_pall: shoulder)
        double m = Math.max(lin[0], Math.max(lin[1], lin[2])), k = m <= 0.6 ? 1 : (0.6 + 0.4 * (1 - Math.exp(-(m - 0.6) / 0.4))) / m;
        for (int c = 0; c < 3; c++) into[c] = (float) Math.min(1, lin[c] * k);
    }

    /** Толща средней головы стены ({@link BlastWall#sigma}) от точки по направлению (vx, vy, vz) на длину len, n шагов ({@code wallChord}). */
    private static double chord(float[] wall, double rx, double rz, double up, double vx, double vy, double vz, double len, int n) {
        double sum = 0, ds = len / n;
        for (int i = 0; i < n; i++) {
            double l = (i + 0.5) * ds;
            sum += BlastWall.sigma(wall, rx + vx * l, rz + vz * l, up + vy * l, 0);
        }
        return sum * ds;
    }

    /**
     * Цвет тонкой пыли кадра на экране в канале c: рассеянный ею свет ({@link PallLight#thin}, альбедо — по линейному
     * цвету пыли {@code fx_pall}) на единицу ослабления — её функция источника, в значение экрана.
     */
    private static float thinDust(int c, float mu, PallPass.Frame f) {
        return (float) PallLight.display(PallLight.thin(DUST_W[c], f.sunLight.get(c), f.skyLight.get(c), mu, PallLight.LAND));
    }

    /** Альбедо ω' по каналам по цвету толстого слоя на экране ({@link PallLight#albedo}). */
    private static double[] albedo(double[] display) {
        double[] w = new double[3];
        for (int c = 0; c < 3; c++) w[c] = PallLight.albedo(PallLight.linear(display[c]), DUST_G);
        return w;
    }

    /** Свет неба канала: цвет неба Minecraft на экране — в линейный, его доля {@link PallLight#SKY}, не меньше floor. */
    static float skyLight(double display, float floor) {
        return Math.max((float) (PallLight.SKY * PallLight.linear(display)), floor);
    }

    /** Свет солнца канала: остальное до белого после неба sky, покраснение red (на экране), сила дня day. */
    static float sunLight(double red, float sky, float day) {
        return day * (float) (PallLight.linear(red) * Math.max(1 - sky, 0));
    }

    /** Свет неба на пелене ночью на экране — не меньше этого ({@code night} — доля ночи 0..1): как свет неба на клубах стены. */
    static float nightSky(float ambient, float night) {
        return ambient * night;
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Mth.clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Огненный шторм в кадре — да или нет: сценарий клиента меряет пелену с ним и без (в игре он есть всегда). */
    public static void firestorm(boolean on) {
        FireStorms.enabled = on;
    }

    /** Ослабление пелены у камеры, 1/блок (для строки сценария). */
    public static float sigma() {
        return sigma;
    }

    /** Строка сценария: пелена у камеры и в кадре. */
    public static String describe() {
        Minecraft mc = Minecraft.getInstance();
        PallPass.Frame f = FRAME;
        List<ClientNuclear.Active> all = ClientNuclear.detonations();
        String under = "-";
        if (!all.isEmpty()) {
            // вода там, откуда подняло пелену у камеры (точка минус снос), — из чего она у камеры
            ClientNuclear.Active a = all.getLast();
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            double[] w = new double[2], from = new double[2];
            a.pall.drift(a.ticks(0), w);
            a.pall.source(cam.x - a.d.burst().x, cam.z - a.d.burst().z, a.ticks(0), from);
            under = String.format(java.util.Locale.ROOT, "%.2f water (land %b, water %b), wind to %.0f° drift %.0f blocks, water where the pall at the camera rose %.2f",
                    a.ground.waterShare(), a.ground.hasLand(), a.ground.hasWater(), Math.toDegrees(a.d.windDir()), Math.hypot(w[0], w[1]),
                    a.ground.water(a.d.burst().x + from[0], a.d.burst().z + from[1]));
        }
        return String.format(java.util.Locale.ROOT, "sigma at camera %.4f /block (visibility %.0f blocks; mist %.4f), muffle %.2f, ash %d, quality %s, "
                        + "light: sky %.3f, wall %.2f × (overhead %.2f, glow %.2f, hot %.2f %.2f %.2f), under the pall: %s, surge r %.0f top %.0f sigma %.4f, "
                        + "frames: %s; mushrooms %d (exposure %.0f); firestorm: %s",
                sigma, sigma > 1e-5f ? 3.912 / sigma : Double.POSITIVE_INFINITY, mist, muffle, NukeAsh.alive(), quality(),
                f.skyLight.y, f.glowOnSmoke, f.glow0.x, f.glow0.y, f.hot0.x, f.hot0.y, f.hot0.z, under, f.water0.x, f.water0.y, f.water0.z,
                mc.level == null ? "-" : PallPass.describe(), f.mushroomCount, f.exposure, FireStorms.describe());
    }
}
