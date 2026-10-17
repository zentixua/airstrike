package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.FrustumIntersection;
import org.joml.Vector3f;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Пелена после ударной волны у клиента ({@link PallField}): что рисует слой эффектов ({@link #frame} — до двух самых
 * заметных подрывов, таблица и свет кадра для {@link PallPass}), и что пелена делает у камеры — глушит звук
 * ({@link #muffle}) и сыплет пепел ({@link NukeAsh}). Своего пакета нет: поле — по модели подрыва.
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
    private static final Vector3f SKY = new Vector3f();
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

    /** Тик клиента: пелена у камеры — звук и пепел; шум для объёма — в фоне с первого подрыва. */
    static void tick(ClientLevel level, Vec3 cam) {
        List<ClientNuclear.Active> all = ClientNuclear.detonations();
        FireStorms.tick(all);
        if (all.isEmpty()) {
            sigma = mist = muffle = 0;
            NukeAsh.tick(level, cam, 0, 0, null);
            return;
        }
        if (quality() != PallPass.Quality.OFF) PallPass.prepare();
        double s = 0, wet = 0, densest = 0, dust = 0;
        // пепел и ветер — от подрыва, чья пелена у камеры гуще всех
        ClientNuclear.Active nearest = null;
        for (ClientNuclear.Active a : all) {
            double ticks = a.ticks(0);
            if (!a.pall.visible(ticks)) continue;
            Detonation d = a.d;
            PallField.Water water = water(a);
            double dx = cam.x - d.burst().x, dz = cam.z - d.burst().z;
            double here = a.pall.sigma(dx, dz, cam.y - d.groundY(), ticks, water);
            if (here > densest) {
                densest = here;
                nearest = a;
            }
            s += here;
            wet += a.pall.mist(dx, dz, cam.y - d.groundY(), ticks, water);
            dust = Math.max(dust, a.pall.ashRate(dx, dz, ticks, water));
        }
        sigma = (float) s;
        mist = (float) wet;
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
        NukeAsh.tick(level, cam, dust, s, nearest);
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
     * Пелена кадра для слоя эффектов: до двух подрывов, ближайших к камере по краю их пелены, из тех, у кого в таблице
     * хоть что-то есть (до фронта и после — пусто) и чья рамка в кадре ({@code frustum} — пирамида видимости от глаза,
     * без дальней плоскости); null — пелены в кадре нет (или выключена): тогда слой не тратит на неё ни одного прохода.
     */
    @Nullable
    public static PallPass.Frame frame(ClientLevel level, Vec3 eye, float partial, FrustumIntersection frustum) {
        PallPass.Quality q = quality();
        if (q == PallPass.Quality.OFF || !PallPass.available()) return null;
        List<ClientNuclear.Active> visible = new ArrayList<>();
        for (ClientNuclear.Active a : ClientNuclear.detonations()) if (a.pall.visible(a.ticks(partial))) visible.add(a);
        if (visible.isEmpty()) return null;
        visible.sort(Comparator.comparingDouble(a -> outside(a, eye, partial)));
        PallPass.Frame f = FRAME;
        f.quality = q;
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
        // мир кадра — чанки в дальности прорисовки (с запасом на край по чанкам): луч в пустоту прошёл их, ничего не задев
        f.floorFrom = Math.max(16, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16 - 24);
        f.min.set(Float.MAX_VALUE);
        f.max.set(-Float.MAX_VALUE);
        int k = 0;
        float bottom = (float) (level.getMinBuildHeight() - eye.y);
        for (int i = 0; i < visible.size() && k < 2; i++) if (slot(visible.get(i), k, eye, partial, frustum, bottom, f)) SLOTTED[k++] = visible.get(i);
        if (k == 0) return null;
        light(level, eye, partial, f);
        float clouds = level.effects().getCloudHeight();
        FireStorms.frame(SLOTTED, k, eye, partial, frustum, Float.isNaN(clouds) ? 1e6f : (float) (clouds - eye.y), f);
        float s = sigma;
        f.clip = s > 1e-4f ? 12 / s : 1e6f;
        return f;
    }

    /** Насколько камера вне пелены подрыва (по земле), блоки; 0 — внутри. */
    private static double outside(ClientNuclear.Active a, Vec3 eye, float partial) {
        double r = a.pall.extent(a.ticks(partial));
        return Math.max(0, Math.hypot(eye.x - a.d.burst().x, eye.z - a.d.burst().z) - r);
    }

    /**
     * Слот k ({@code pall.glsl}): поле подрыва, его строки таблицы, базисная волна и рамка; false — слот не нужен (в таблице
     * пусто и волны нет, или рамка вне кадра), он остаётся свободным для следующего подрыва. Чего под пеленой нет — суши
     * или воды ({@link PallGround}), — того нет и в таблице: подрыв посреди моря, когда брызги выпали, а волна растаяла,
     * слота не занимает. {@code bottom} — низ мира от глаза: в кадре подрыв и тогда, когда видна только земля под пеленой,
     * — днём на ней тень пелены ({@code fx_pall_composite}), а долина или карьер под эпицентром бывают глубже низа пелены.
     */
    private static boolean slot(ClientNuclear.Active a, int k, Vec3 eye, float partial, FrustumIntersection frustum, float bottom, PallPass.Frame f) {
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
        if (!any) return false;
        p.drift(ticks, DRIFT);
        double plume = p.plume(ticks);
        float cx = (float) (d.burst().x - eye.x), cz = (float) (d.burst().z - eye.z), ground = (float) (d.groundY() - eye.y);
        // рамка: снос пыли и шлейф мглы — в любую сторону от эпицентра, ниже земли под ним — долины на всю высоту
        float r = (float) p.extent(ticks);
        LO.set(cx - r, (float) (ground - top), cz - r);
        HI.set(cx + r, (float) (ground + top * 1.05), cz + r);
        CULL.set(LO.x, Math.min(LO.y, bottom), LO.z);
        if (frustum.intersectAab(CULL, HI, SIDES_AND_NEAR) >= 0) return false;
        System.arraycopy(ROW, 0, f.table, k * ROW.length, ROW.length);
        double ys = Math.cbrt(d.yieldKt() / 15);
        org.joml.Matrix4f m = k == 0 ? f.slot0 : f.slot1;
        m.m00(cx).m01(cz).m02(ground).m03((float) p.reach());
        m.m10((float) DRIFT[0]).m11((float) DRIFT[1]).m12((float) plume).m13(1);
        m.m20((float) Math.cos(d.windDir())).m21((float) Math.sin(d.windDir())).m22((2 * k + 0.5f) / PallPass.ROWS).m23((2 * k + 1.5f) / PallPass.ROWS);
        m.m30((float) (PallField.SIGMA_DENSE / d.scale())).m31((float) Math.max(48, d.blocks(TILE_M * ys))).m32((float) (ticks / 20.0)).m33(0);
        // вода слота: базисная волна и есть ли под пеленой вода (иначе шейдер карту воды не читает)
        (k == 0 ? f.water0 : f.water1).set((float) SURGE[0], (float) SURGE[1], (float) SURGE[2], water ? 1 : 0);
        // свет стены пыли: отсвет шара, шапки и пожаров — тот же, что у её клубов
        a.puffs.pallLight(a.seconds(partial), GLOW);
        (k == 0 ? f.glow0 : f.glow1).set(GLOW[0], GLOW[1], GLOW[2], GLOW[3]);
        (k == 0 ? f.hot0 : f.hot1).set(GLOW[4], GLOW[5], GLOW[6], GLOW[7]);
        a.ground.frame(k == 0 ? f.ground0 : f.ground1);
        f.min.min(LO);
        f.max.max(HI);
        return true;
    }

    /** Солнце, небо и пожары кадра — в долях экрана, как мир вокруг. */
    private static void light(ClientLevel level, Vec3 eye, float partial, PallPass.Frame f) {
        float angle = level.getTimeOfDay(partial) * Mth.TWO_PI;
        float sx = -Mth.sin(angle), sy = Mth.cos(angle);
        boolean sky = level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
        float day = sky ? smooth(-0.06f, 0.18f, sy) * (1 - 0.55f * level.getRainLevel(partial)) * (1 - 0.35f * level.getThunderLevel(partial)) : 0;
        f.sun.set(sx, sy, 0, day);
        // низкое солнце — краснее: длинный путь через воздух
        float low = Mth.clamp(sy / 0.35f, 0, 1);
        f.sunLight.set(1.05f * day, (0.62f + 0.36f * low) * day, (0.38f + 0.52f * low) * day);
        Vec3 skyColor = level.getSkyColor(eye, partial);
        float night = 1 - day;
        f.skyLight.set((float) skyColor.x * 0.7f + 0.03f * night, (float) skyColor.y * 0.7f + 0.035f * night, (float) skyColor.z * 0.7f + 0.05f * night);
        // ночью — не темнее света неба на клубах стены пыли и гриба (FarView.ambient, 0,17): цвет неба ночью почти чёрный,
        // и пелена за фронтом была бы чёрным занавесом над городом, где стена пыли и мир ещё видны
        float ambient = FarView.ambient(level, partial);
        f.skyLight.max(SKY.set(nightSky(ambient, night)));
        f.glowOnSmoke = (float) CloudPuffs.glowOnSmoke(ambient);
        // зарево пожаров снизу на белой пелене (пыль и мгла берут его по своей белизне, fx_pall): днём его почти не видно,
        // ночью ярче ночного неба в десяток раз, но не как огонь (ярче — и вся мгла над горящим городом выходила
        // ровно-оранжевой)
        float fire = 0.6f * (1 - 0.75f * day);
        f.fireLight.set(fire, fire * 0.45f, fire * 0.16f);
        f.ambient.set(0.60f * (0.45f * f.sunLight.x + 0.8f * f.skyLight.x), 0.53f * (0.45f * f.sunLight.y + 0.8f * f.skyLight.y),
                0.45f * (0.45f * f.sunLight.z + 0.8f * f.skyLight.z), 1);
    }

    /** Свет неба на пелене ночью — не меньше этого ({@code night} — доля ночи 0..1): как свет неба на клубах стены. */
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
            double[] w = new double[2];
            a.pall.drift(a.ticks(0), w);
            under = String.format(java.util.Locale.ROOT, "%.2f water (land %b, water %b), wind to %.0f° drift %.0f blocks, water where the pall at the camera rose %.2f",
                    a.ground.waterShare(), a.ground.hasLand(), a.ground.hasWater(), Math.toDegrees(a.d.windDir()), Math.hypot(w[0], w[1]),
                    a.ground.water(cam.x - w[0], cam.z - w[1]));
        }
        return String.format(java.util.Locale.ROOT, "sigma at camera %.4f /block (visibility %.0f blocks; mist %.4f), muffle %.2f, ash %d, quality %s, "
                        + "light: sky %.3f, wall %.2f × (overhead %.2f, glow %.2f, hot %.2f %.2f %.2f), under the pall: %s, surge r %.0f top %.0f sigma %.4f, "
                        + "frames: %s; firestorm: %s",
                sigma, sigma > 1e-5f ? 3.912 / sigma : Double.POSITIVE_INFINITY, mist, muffle, NukeAsh.alive(), quality(),
                f.skyLight.y, f.glowOnSmoke, f.glow0.x, f.glow0.y, f.hot0.x, f.hot0.y, f.hot0.z, under, f.water0.x, f.water0.y, f.water0.z,
                mc.level == null ? "-" : PallPass.describe(), FireStorms.describe());
    }
}
