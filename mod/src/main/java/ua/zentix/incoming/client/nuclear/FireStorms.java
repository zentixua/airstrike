package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.client.map.TerrainTiles;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.List;
import java.util.Locale;

/**
 * Огненный шторм кадра для слоя эффектов ({@link FireStorm} → {@link PallPass.Frame}): у слотов пелены ({@link NukePall})
 * — слот огня (карта и числа для {@code pall.glsl}), рамка шторма, до четырёх смерчей в кадре. Своего пакета нет:
 * всё — по возрасту подрыва, а гаснет шторм там, где сервер не жжёт (доверие {@link FireStorm#trust}).
 */
final class FireStorms {
    /** Цвет огня на экране (пламя с сажей, около 1100 K). */
    static final float FIRE_R = 1.0f, FIRE_G = 0.42f, FIRE_B = 0.11f;
    /**
     * Пламя на поверхностях города на экране ночью и днём: днём глаз привык к свету, и то же пламя на кадре тусклее
     * (как зарево пелены, {@code NukePall.light}).
     */
    static final double FLAME_NIGHT = 1.0, FLAME_DAY = 0.6;
    /**
     * Зарево снизу на дыме и пелене ночью (на среднее пламя под точкой, у земли: выше его гасит дым — {@code storm} в
     * {@code fx_pall.fsh}; у сплошного пожара низ дыма — рыжий, исподняя сторона пелены — тускло-красная, над слабыми
     * районами — темнее); днём — доля {@link #GLOW_DAY} (против неба его почти не видно).
     */
    static final double GLOW_NIGHT = 3, GLOW_DAY = 0.12;
    /**
     * Ослабление дыма при подаче 1 (сильная клетка в разгаре), 1/м, вширь по клетке: видно метров на 70; в столбе у
     * земли — гуще во столько, во сколько столб уже клетки.
     */
    static final double SMOKE_SIGMA_M = 0.07;
    /**
     * Пламя на поверхностях — дальше стольких блоков от глаза (проступает до полутора): ближе пятно краски на блоках видно,
     * а там горит настоящий огонь ванили, если сервер его зажёг.
     */
    static final float FLAME_NEAR = 96;
    /** Смерч наклонён по ветру: доля сноса столба на высоте. */
    static final double WHIRL_LEAN = 0.3;
    /** Доверие к модели огня меняется плавно: доля разницы за тик (около секунды). */
    static final double GATE_RATE = 0.05;
    /** Смерчей в кадре — не больше (по столбцу матриц). */
    static final int WHIRLS = 4;

    /** Плоскости пирамиды видимости, по которым отсекаются смерчи: все, кроме дальней (как у пелены). */
    private static final int SIDES_AND_NEAR = FrustumIntersection.PLANE_MASK_NX | FrustumIntersection.PLANE_MASK_PX
            | FrustumIntersection.PLANE_MASK_NY | FrustumIntersection.PLANE_MASK_PY | FrustumIntersection.PLANE_MASK_NZ;
    private static final Vector3f LO = new Vector3f(), HI = new Vector3f();
    private static final int CANDIDATES = 8;
    private static final double[] DIST = new double[CANDIDATES];
    private static final int[] SLOT = new int[CANDIDATES], INDEX = new int[CANDIDATES];
    private static final float[][] POS = new float[CANDIDATES][4], SHAPE = new float[CANDIDATES][4], LEAN = new float[CANDIDATES][4];
    /** Смерчей в прошлом кадре (для строки сценария). */
    private static int lastWhirls;
    /** Шторм рисуется: в игре — всегда; сценарий клиента меряет пелену с ним и без ({@link NukePall#firestorm}). */
    static volatile boolean enabled = true;

    private FireStorms() {}

    /**
     * Тик клиента: доверие модели огня — к сверке с огнём в своих чанках; что из зоны пожаров — вода, лёд или снег (по
     * своим чанкам и плиткам карты рельефа, где не знаем — как под эпицентром, {@link FireStorm#survey}).
     */
    static void tick(List<ClientNuclear.Active> all) {
        ClientLevel level = Minecraft.getInstance().level;
        for (ClientNuclear.Active a : all) {
            double was = a.storm.gate();
            a.storm.gate(was + GATE_RATE * (a.storm.trust() - was));
            if (level != null) a.storm.survey(surface(level), NukeWater.under(level, a.d).water());
        }
    }

    private static final BlockPos.MutableBlockPos AT = new BlockPos.MutableBlockPos();

    /** Поверхность колонки у клиента: верхний блок своего чанка, иначе плитка карты рельефа ({@link TerrainTiles#wet}). */
    static FireStorm.Surface surface(ClientLevel level) {
        return (x, z) -> {
            LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
            if (chunk == null) return TerrainTiles.wet(x, z);
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
            if (y < level.getMinBuildHeight()) return -1;
            BlockState state = chunk.getBlockState(AT.set(x, y, z));
            return !state.getFluidState().isEmpty() || state.is(BlockTags.ICE) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW) ? 1 : 0;
        };
    }

    /**
     * Шторм кадра: слоты огня у {@code k} слотов пелены ({@code slotted}, после света кадра — {@code f.sun}), рамка и
     * смерчи. Рамка пелены растёт до рамки шторма: столбы со сносом и смерчи — внутри неё.
     */
    static void frame(ClientNuclear.Active[] slotted, int k, Vec3 eye, float partial, FrustumIntersection frustum, float clouds, PallPass.Frame f) {
        f.fire0.clear();
        f.fire1.clear();
        f.fireMin.set(Float.MAX_VALUE);
        f.fireMax.set(-Float.MAX_VALUE);
        f.whirlPos.zero();
        f.whirlShape.zero();
        f.whirlLean.zero();
        if (!enabled) {
            lastWhirls = 0;
            return;
        }
        for (int j = 0; j < k; j++) slot(slotted[j], j, eye, partial, clouds, f);
        lastWhirls = whirls(slotted, k, eye, partial, frustum, f);
    }

    /**
     * Слот огня {@code k}: числа шторма подрыва ({@code pall.glsl}), карта огня, рамка. {@code clouds} — облака Minecraft
     * от глаза по y (их нет — очень высоко): пламя на поверхностях ниже них — облака в расстоянии до мира тоже есть.
     */
    private static void slot(ClientNuclear.Active a, int k, Vec3 eye, float partial, float clouds, PallPass.Frame f) {
        FireStorm s = a.storm;
        double ticks = a.ticks(partial);
        s.refresh(ticks);
        double gate = s.gate();
        if (s.half() <= 0 || s.version() == 0 || gate < 0.01) return;
        Detonation d = a.d;
        double night = 1 - f.sun.w;
        // снос столба: ветер против подъёма дыма
        double u = d.blocks(d.windSpeed()), w = d.blocks(FireStorm.UPDRAFT_MS);
        double bx = Math.cos(d.windDir()) * u / w, bz = Math.sin(d.windDir()) * u / w;
        double top = a.pall.hazeTop(), hb = d.blocks(FireStorm.BEND_HEIGHT), half = s.half(), cell = s.cell();
        PallPass.Fire fire = k == 0 ? f.fire0 : f.fire1;
        Matrix4f m = fire.params;
        m.m00((float) half).m01((float) cell).m02((float) top).m03((float) hb);
        m.m10((float) bx).m11((float) bz).m12((float) w).m13((float) (gate * GLOW_NIGHT * (GLOW_DAY + (1 - GLOW_DAY) * night)));
        m.m20((float) (gate * (FLAME_DAY + (FLAME_NIGHT - FLAME_DAY) * night))).m21((float) (SMOKE_SIGMA_M / d.scale()))
                .m22((float) (ticks / 20.0)).m23(FLAME_NEAR);
        m.m30(FIRE_R).m31(FIRE_G).m32(FIRE_B).m33(clouds);
        fire.map = s.map();
        fire.side = s.side();
        fire.source = s;
        fire.version = s.version();
        // зарево мглы по таблице (ровное по кольцам) уступает зареву по карте огня: строка мглы слота, канал b
        int base = (2 * k + 1) * PallPass.WIDTH * 4;
        for (int i = 0; i < PallPass.WIDTH; i++) f.table[base + 4 * i + 2] *= (float) (1 - gate);
        // рамка: карта, столбы со сносом по ветру, низины под эпицентром
        double bend = FireStorm.bend(top, hb), r = half + FireStorm.width(top, cell);
        float cx = (float) (d.burst().x - eye.x), cz = (float) (d.burst().z - eye.z), ground = (float) (d.groundY() - eye.y);
        float ox = (float) (bx * bend), oz = (float) (bz * bend);
        LO.set((float) (cx - r + Math.min(0, ox)), ground - 40, (float) (cz - r + Math.min(0, oz)));
        HI.set((float) (cx + r + Math.max(0, ox)), (float) (ground + top), (float) (cz + r + Math.max(0, oz)));
        grow(f, LO, HI);
    }

    /** Смерчи кадра: идущие, в пирамиде видимости, ближайшие к глазу — до {@link #WHIRLS}; сколько вышло. */
    private static int whirls(ClientNuclear.Active[] slotted, int k, Vec3 eye, float partial, FrustumIntersection frustum, PallPass.Frame f) {
        int n = 0;
        for (int j = 0; j < k; j++) {
            PallPass.Fire fire = j == 0 ? f.fire0 : f.fire1;
            if (fire.params.m00() <= 0) continue;
            ClientNuclear.Active a = slotted[j];
            double seconds = a.ticks(partial) / 20.0, gate = a.storm.gate();
            float leanX = (float) (WHIRL_LEAN * fire.params.m10()), leanZ = (float) (WHIRL_LEAN * fire.params.m11());
            List<FireStorm.Whirl> list = a.storm.whirls();
            for (int i = 0; i < list.size(); i++) {
                FireStorm.Whirl w = list.get(i);
                double strength = w.strength(seconds) * gate * a.storm.dryAt(w.x(), w.z());
                if (strength < 0.01) continue;
                float x = (float) (w.baseX(seconds) - eye.x), z = (float) (w.baseZ(seconds) - eye.z), y = (float) (a.storm.whirlGround(i) - eye.y);
                float h = (float) w.height(), reach = (float) (w.radius() * 3);
                LO.set(x - reach + Math.min(0, leanX * h), y - 4, z - reach + Math.min(0, leanZ * h));
                HI.set(x + reach + Math.max(0, leanX * h), y + h, z + reach + Math.max(0, leanZ * h));
                if (frustum.intersectAab(LO, HI, SIDES_AND_NEAR) >= 0) continue;
                double dist = Math.hypot(x, z);
                // место среди CANDIDATES ближайших (вставкой), дальнее выпадает; в кадр — первые WHIRLS
                int at = Math.min(n, CANDIDATES - 1);
                if (n == CANDIDATES && dist >= DIST[at]) continue;
                while (at > 0 && DIST[at - 1] > dist) {
                    DIST[at] = DIST[at - 1];
                    SLOT[at] = SLOT[at - 1];
                    INDEX[at] = INDEX[at - 1];
                    copy(at - 1, at);
                    at--;
                }
                DIST[at] = dist;
                SLOT[at] = j;
                INDEX[at] = i;
                set(POS[at], x, z, y, (float) w.radius());
                set(SHAPE[at], h, (float) strength, (float) (seconds - w.startS()), w.spin());
                set(LEAN[at], leanX, leanZ, fire.params.m20(), (float) w.phase());
                grow(f, LO, HI);
                n = Math.min(CANDIDATES, n + 1);
            }
        }
        int shown = Math.min(n, WHIRLS);
        for (int c = 0; c < shown; c++) {
            column(f.whirlPos, c, POS[c]);
            column(f.whirlShape, c, SHAPE[c]);
            column(f.whirlLean, c, LEAN[c]);
        }
        return shown;
    }

    private static void copy(int from, int to) {
        System.arraycopy(POS[from], 0, POS[to], 0, 4);
        System.arraycopy(SHAPE[from], 0, SHAPE[to], 0, 4);
        System.arraycopy(LEAN[from], 0, LEAN[to], 0, 4);
    }

    private static void set(float[] v, float a, float b, float c, float d) {
        v[0] = a;
        v[1] = b;
        v[2] = c;
        v[3] = d;
    }

    private static void column(Matrix4f m, int c, float[] v) {
        switch (c) {
            case 0 -> m.m00(v[0]).m01(v[1]).m02(v[2]).m03(v[3]);
            case 1 -> m.m10(v[0]).m11(v[1]).m12(v[2]).m13(v[3]);
            case 2 -> m.m20(v[0]).m21(v[1]).m22(v[2]).m23(v[3]);
            default -> m.m30(v[0]).m31(v[1]).m32(v[2]).m33(v[3]);
        }
    }

    /** Рамка шторма и пелены — до {@code lo..hi}. */
    private static void grow(PallPass.Frame f, Vector3f lo, Vector3f hi) {
        f.fireMin.min(lo);
        f.fireMax.max(hi);
        f.min.min(lo);
        f.max.max(hi);
    }

    /** Строка сценария: шторм первого подрыва, где он есть. */
    static String describe() {
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            FireStorm s = a.storm;
            if (s.cells() == 0) continue;
            double seconds = a.ticks(0) / 20.0;
            int live = 0;
            for (FireStorm.Whirl w : s.whirls()) if (w.strength(seconds) > 0) live++;
            return String.format(Locale.ROOT, "strike #%d at %.0f s: %d sources (%.0f-block cells, map %d²), burning %.0f of %.0f, gate %.2f, whirls %d of %d now (%d in frame)",
                    a.d.id(), seconds, s.cells(), s.cell(), s.side(), s.burning(), s.total(), s.gate(), live, s.whirls().size(), lastWhirls);
        }
        return "none";
    }
}
