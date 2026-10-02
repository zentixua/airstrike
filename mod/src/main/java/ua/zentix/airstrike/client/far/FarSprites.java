package ua.zentix.airstrike.client.far;

import net.minecraft.client.renderer.LightTexture;
import org.joml.Vector3f;
import ua.zentix.airstrike.client.fx.layer.FxAtlas;
import ua.zentix.airstrike.client.fx.layer.FxQuads;
import ua.zentix.airstrike.client.render.FarDraw;
import ua.zentix.airstrike.client.render.FarModels;
import ua.zentix.airstrike.client.render.ProjectilePose;
import ua.zentix.airstrike.client.render.WeaponModels;

import java.util.Arrays;

/**
 * Всё дальнее — квадратами в общий кадр слоя эффектов ({@link FxQuads}): шлейфы (лентой), клубы дыма, модели снарядов
 * (плитки атласа {@link FarModels}), огненные шары и пожары — кадрами анимации и клубами пламени, тела мельче
 * нескольких пикселей — круги (корпуса снарядов мельче модели, далёкие шары и факелы), свет — ореолы (блик и вуаль
 * вокруг вспышек, шаров и факелов, зарево). Слой сортирует их вместе с ближними частицами от
 * дальних к ближним по настоящему расстоянию, поэтому огненный шар и модель за ближним столбом дыма закрыты дымом,
 * а пусковая в десятке блоков — впереди дальних столбов.
 * Координаты — относительно камеры; дальше дальней плоскости точка переносится ближе с тем же угловым размером,
 * а слою передаётся, во сколько раз (по настоящему расстоянию он прячет дальнее за рельефом и LOD Distant Horizons).
 * <p>
 * Цвет вершины — свет, уже умноженный на непрозрачность, альфа — сколько закрыто того, что за спрайтом. Дым и корпус
 * закрывают и светят своим цветом, раскалённое закрывает и светит, ореол только светит (альфа 0 — сложение). Дымка
 * воздуха — в непрозрачности ({@link Sight}: L = L₀·t + небо·(1 − t)).
 * <p>
 * Текстуры ({@code tools/gen_particles.py}, на листе слоя): круг {@code far/disc} — сплошной, край сглажен; ореол
 * {@code far/glow} — гауссов, без ядра; ленты — его средняя строка; огненный шар — кадры {@code fx/particle/fireball},
 * пламя — {@code fx/particle/fire}. Сколько квадрата закрывает текстура, столько света она и несёт — множители
 * {@link #DISC}, {@link #GLOW}, {@link #FIREBALL}, {@link #FIRE} переводят радиус круга того же светового потока
 * в полуразмер квадрата ({@code FarSpritesTest}).
 */
public final class FarSprites {
    /**
     * Полуразмер квадрата на радиус круга того же потока: круг {@code far/disc} закрывает 0,632 квадрата (√(π/(4·0,632))),
     * ореол {@code far/glow} — 0,133 (пик в середине — 1).
     */
    public static final double DISC = 1.115, GLOW = 2.43;
    /**
     * Полуразмер кадра огненного шара на его радиус: светящийся шар в кадре — 0,72–0,79 полуразмера (к концу бугры
     * дыма шире); вблизи частицы берут тот же размер ({@code Explosions#fireball}). Клуб пламени закрывает 0,27 квадрата.
     */
    public static final double FIREBALL = 1.3, FIRE = 1.7;
    /**
     * Огненный шар — объём: его кадр стоит на столько радиусов ближе середины, у передней половины ({@link #front}).
     * Плоскостью через середину он сверху уходил ближней к глазу половиной в землю под собой (наземный шар — полусфера,
     * середина на 0,45 радиуса над землёй: с 54° срезало треть шара), а настоящий купол сверху виден целиком.
     */
    public static final double BALL_FRONT = 0.5;
    /** Тело мельче стольких пикселей (радиус) — круг: формы не видно, а свет точки верен; крупнее — кадр анимации. */
    private static final double SHAPE_FROM = 2, SHAPE_FULL = 4;
    /**
     * Блик и вуаль — свет, рассеянный в самом глазу: их квадрат переносится к глазу на столько блоков с тем же угловым
     * размером, и ближние рельеф и постройки его не режут (у края загруженного мира ореол обрывался прямой линией).
     * Закрыто ли само тело, решает вызывающий: дальше прорисовки — луч по рельефу, в ней — луч по блокам.
     */
    static final double EYE = 0.25;
    /**
     * Ореол у глаза — в плоскости экрана на глубине {@link #EYE}·cos θ; сбоку (θ больше ~78°) это ближе ближней плоскости
     * Minecraft (0,05), и квадрат срезало бы целиком. Глубина — не меньше этой.
     */
    static final double EYE_DEPTH = 0.1;
    /** Ореол в воздухе ({@link #glow}) — не ближе стольких блоков по оси взгляда. */
    static final double AIR_NEAR = 1.5;
    /** Полуширина ленты на половину следа: середина {@code far/glow} поперёк закрывает 0,365 ширины. */
    public static final double RIBBON = 2.74;
    /** Мягкость края у рельефа: клуб — {@link FxQuads#SOFT} полуразмера, тело — четверть радиуса. */
    private static final float BODY_SOFT = 0.25f;

    /** Модели кадра в атласе. */
    final FarModels models = new FarModels();
    private final float[] tile = new float[FarModels.OUT];
    private FxQuads out;
    private double far;
    /** Куда смотрит камера (для глубины ореола у глаза). */
    private float fx, fy, fz;
    /** Сколько клубов, кругов, света, лент и моделей записано в этом кадре. */
    private final int[] counts = new int[5];

    /** Новый кадр: писать в out. */
    void begin(FarView view, FxQuads out) {
        this.out = out;
        far = view.far();
        fx = view.forward().x();
        fy = view.forward().y();
        fz = view.forward().z();
        Arrays.fill(counts, 0);
        models.begin();
    }

    public boolean isEmpty() {
        return counts[0] + counts[1] + counts[2] + counts[3] + counts[4] == 0;
    }

    /** Сколько чего в кадре (клубы, круги, свет, ленты, модели) — в out, без выделения памяти. */
    void counts(int[] into) {
        System.arraycopy(counts, 0, into, 0, counts.length);
    }

    /**
     * Клуб дыма или пыли (кадр tex из 4×2 {@code nuke/puffs.png}) с центром (dx, dy, dz) от камеры: свой цвет
     * (r, g, b) с непрозрачностью a (уже с дымкой воздуха).
     */
    public void puff(double dx, double dy, double dz, double half, float rot, int tex, float r, float g, float b, float a) {
        if (billboard(dx, dy, dz, half, rot, FxAtlas.puff(tex), r * a, g * a, b * a, a, (float) half * FxQuads.SOFT)) counts[0]++;
    }

    /**
     * Модель снаряда в позе pose с центром (dx, dy, dz) от камеры: плитка атласа {@link FarModels} квадратом поперёк луча
     * на неё, закрывает и светит долю a (дымка воздуха, видимость над рельефом, переход от точки).
     *
     * @param size  размер модели ({@code FarLook.size})
     * @param camUp верх экрана: плитка повёрнута, как экран
     * @return false — модели нет (атлас полон, модель вплотную к камере): нужна точка
     */
    public boolean model(WeaponModels.Look look, ProjectilePose pose, double dx, double dy, double dz, double size, double pixel, Vector3f camUp,
                         float a) {
        if (!models.add(look, pose, dx, dy, dz, size, pixel, camUp, tile)) return false;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), k = FarDraw.fold(d, far);
        float x = (float) (dx * k), y = (float) (dy * k), z = (float) (dz * k), h = (float) (tile[FarModels.HALF] * k);
        float rx = tile[FarModels.RIGHT] * h, ry = tile[FarModels.RIGHT + 1] * h, rz = tile[FarModels.RIGHT + 2] * h;
        float ux = tile[FarModels.UP] * h, uy = tile[FarModels.UP + 1] * h, uz = tile[FarModels.UP + 2] * h;
        float u0 = tile[FarModels.U0], v0 = tile[FarModels.V0], u1 = tile[FarModels.U1], v1 = tile[FarModels.V1];
        float c = Math.min(1, a), soft = tile[FarModels.HALF] * BODY_SOFT, unfold = (float) k;
        int light = LightTexture.FULL_BRIGHT;
        // нижний левый угол — (u0, v0) атласа: низ атласа — низ плитки
        out.modelTiles(true);
        out.quad((float) d);
        out.vertex(x - rx - ux, y - ry - uy, z - rz - uz, u0, v0, c, c, c, c, soft, unfold, light, 0);
        out.vertex(x + rx - ux, y + ry - uy, z + rz - uz, u1, v0, c, c, c, c, soft, unfold, light, 0);
        out.vertex(x + rx + ux, y + ry + uy, z + rz + uz, u1, v1, c, c, c, c, soft, unfold, light, 0);
        out.vertex(x - rx + ux, y - ry + uy, z - rz + uz, u0, v1, c, c, c, c, soft, unfold, light, 0);
        out.modelTiles(false);
        counts[4]++;
        return true;
    }

    /**
     * Круг радиуса radius (того же потока: квадрат — в {@link #DISC} раз больше) своего цвета с непрозрачностью a поверх
     * того, что за ним: корпус снаряда, огненный шар, ядро вспышки.
     */
    public void disc(double dx, double dy, double dz, double radius, float r, float g, float b, float a) {
        if (billboard(dx, dy, dz, radius * DISC, 0, FxAtlas.disc(), r * a, g * a, b * a, a, (float) radius * BODY_SOFT)) counts[1]++;
    }

    /**
     * Ореол в воздухе, который несёт свет круга радиуса radius яркостью a (пик в середине — a): складывается с тем, что
     * за ним — зарево из-за гребня и на облаках. Светится воздух глубиной deep перед (dx, dy, dz): квадрат стоит там, где
     * этот воздух начинается, — что ближе, его закрывает, а что внутри — закрывает плавно (мягкий край по расстоянию до
     * мира, с LOD DH). На месте источника квадрат резал бы прямо по себе постройки на том же расстоянии: ночью зарево
     * из-за гребня шириной в экран ложилось на дома пятнами и полосами, а на LOD DH, которых аппаратная глубина не видит, —
     * поверх. Блик и вуаль — в глазу ({@link #EYE}).
     */
    public void glow(double dx, double dy, double dz, double radius, double deep, float r, float g, float b, float a) {
        if (r * a + g * a + b * a < 0.006f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double cos = (dx * fx + dy * fy + dz * fz) / Math.max(d, 1e-9);
        // не ближе, чем у камеры гаснет любой квадрат слоя (fx.fsh: полностью виден с 1,5 блока по оси взгляда)
        double front = Math.max(d - deep, AIR_NEAR / Math.max(cos, 0.1));
        if (front >= d) {
            if (billboard(dx, dy, dz, radius * GLOW, 0, FxAtlas.glow(), r * a, g * a, b * a, 0, 0)) counts[2]++;
            return;
        }
        double unfold = FarDraw.fold(front, far), k = front / d * unfold;
        out.billboard((float) (dx * k), (float) (dy * k), (float) (dz * k), (float) d, (float) (radius * GLOW * k), 0, FxAtlas.glow(), Math.min(1, r * a),
                Math.min(1, g * a), Math.min(1, b * a), 0, (float) (d - front), (float) unfold, LightTexture.FULL_BRIGHT, 0);
        counts[2]++;
    }

    /**
     * Яркое тело с центром (dx, dy, dz) от камеры радиуса radius, яркостью seen против белого экрана (с привыканием глаза
     * и воздухом), цвета (r, g, b) ({@link Sight#light}): ядро — свой свет поверх того, что за ним (пересвет — к белому,
     * тусклее белого — гаснет и тает: остывший шар уже стал дымом), блик и вуаль — ореолами того же цвета.
     *
     * @param t     доля света, дошедшая через воздух: насколько тело закрывает то, что за ним
     * @param w     доля ядра (видимое над рельефом, переход к ближней картинке)
     * @param glare доля блика и вуали: их глубина не режет, поэтому закрытое блоками в прорисовке — здесь
     * @param out   числа {@link Sight#light} (5) — для лога
     */
    public void light(double dx, double dy, double dz, double radius, double seen, double t, double pixel, float r, float g, float b, double w,
                      double glare, double[] out) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Sight.light(radius, seen, d, pixel, out);
        core(dx, dy, dz, out, t, r, g, b, w);
        glare(dx, dy, dz, out, seen, r, g, b, glare, 1);
    }

    /**
     * Яркое тело с формой, как {@link #light}: крупнее {@link #SHAPE_FULL} пикселей — картинкой полуразмера half (кадр
     * огненного шара, клуб пламени: цвет — в ней), мельче {@link #SHAPE_FROM} — кругом с тем же светом, между ними —
     * смесь. Тело и свет в глазу — каждый своей долей: вблизи тело рисуют частицы, а блик и вуаль — здесь.
     *
     * @param radius радиус светящегося тела сейчас (свет, блик, вуаль, точка), блоков
     * @param half   полуразмер картинки, блоков (растёт ли тело — в её кадрах)
     * @param body   доля тела (видимое над рельефом, переход к частицам, таяние)
     * @param glare  доля блика и вуали
     * @param shown  тело на экране в картинке (доля белого): блик и вуаль не ярче — остывший в сажу шар не слепит
     */
    public void shaped(double dx, double dy, double dz, double radius, FxAtlas.Sprite sprite, double half, float rot, double seen, double t, double pixel,
                       float r, float g, float b, double body, double glare, double shown, double[] out) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Sight.light(radius, seen, d, pixel, out);
        double shape = smoothstep(SHAPE_FROM, SHAPE_FULL, radius / Math.max(d * pixel, 1e-12));
        if (shape < 1) core(dx, dy, dz, out, t, r, g, b, body * (1 - shape));
        float a = (float) (t * body * shape);
        if (shape > 0 && billboard(dx, dy, dz, half, rot, sprite, a, a, a, a, (float) (radius * BODY_SOFT), BALL_FRONT * radius)) counts[1]++;
        glare(dx, dy, dz, out, seen, r, g, b, glare, shown);
    }

    /** Ядро {@link #light} кругом: свой свет поверх того, что за ним; пересвет — к белому, тусклее белого — тает. */
    private void core(double dx, double dy, double dz, double[] o, double t, float r, float g, float b, double w) {
        double s = o[2], k = Math.min(1, s) * w, white = s > 1 ? Math.min(1, Math.log(s) / Math.log(Sight.WHITE)) : 0;
        float a = (float) (o[1] * t * Math.min(1, 4 * s) * w);
        if (billboard(dx, dy, dz, o[0] * DISC, 0, FxAtlas.disc(), (float) ((r + (1 - r) * white) * k), (float) ((g + (1 - g) * white) * k),
                (float) ((b + (1 - b) * white) * k), a, (float) (o[0] * BODY_SOFT))) counts[1]++;
    }

    /**
     * Блик и вуаль {@link #light} ореолами цвета тела у самого глаза ({@link #EYE}), не ярче тела на экране shown; видно ли
     * тело — решает вызывающий.
     */
    private void glare(double dx, double dy, double dz, double[] o, double seen, float r, float g, float b, double w, double shown) {
        if (w <= 0) return;
        eye(dx, dy, dz, o[3], r, g, b, (float) (Math.min(Math.min(Sight.HALO, Sight.SCATTER * seen), shown) * w));
        if (o[4] > 0) eye(dx, dy, dz, o[4], r, g, b, (float) (Math.min(Sight.VEIL_PEAK, shown) * w));
    }

    /**
     * Ореол {@link #glow}, перенесённый к глазу: те же лучи от глаза, сортировка — по настоящему расстоянию; глубина —
     * не меньше {@link #EYE_DEPTH} (сбоку — дальше от глаза).
     */
    private void eye(double dx, double dy, double dz, double radius, float r, float g, float b, float a) {
        if (r * a + g * a + b * a < 0.006f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double cos = (dx * fx + dy * fy + dz * fz) / Math.max(d, 1e-9);
        double k = Math.max(EYE, EYE_DEPTH / Math.max(cos, 1e-3)) / Math.max(d, 1e-9);
        if (k >= 1) {
            glow(dx, dy, dz, radius, 0, r, g, b, a);
            return;
        }
        out.billboard((float) (dx * k), (float) (dy * k), (float) (dz * k), (float) d, (float) (radius * GLOW * k), 0, FxAtlas.glow(), Math.min(1, r * a),
                Math.min(1, g * a), Math.min(1, b * a), 0, 0, (float) k, LightTexture.FULL_BRIGHT, 0);
        counts[2]++;
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /**
     * Отрезок шлейфа лентой лицом к камере: от (ax..) до (bx..), полуширины (уже с {@link #RIBBON}), непрозрачности и
     * свет мира концов (упакованный, для карты освещения), свой цвет. Поперёк ленты — перпендикуляр к отрезку и к лучу
     * зрения (на любую его точку — тот же); joint на входе — поперёк у конца a, общий с прошлым отрезком той же ленты
     * (NaN в [0] — нет: по этому отрезку), на выходе — поперёк у конца b.
     *
     * @return отрезок записан (joint — новый)
     */
    public boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz, double halfB,
                          float alphaB, int lightB, float r, float g, float b, float[] joint) {
        if (alphaA < 0.002f && alphaB < 0.002f) return false;
        double da = Math.sqrt(ax * ax + ay * ay + az * az), db = Math.sqrt(bx * bx + by * by + bz * bz);
        double ka = FarDraw.fold(da, far), kb = FarDraw.fold(db, far);
        float x0 = (float) (ax * ka), y0 = (float) (ay * ka), z0 = (float) (az * ka);
        float x1 = (float) (bx * kb), y1 = (float) (by * kb), z1 = (float) (bz * kb);
        float tx = x1 - x0, ty = y1 - y0, tz = z1 - z0;
        float mx = (x0 + x1) * 0.5f, my = (y0 + y1) * 0.5f, mz = (z0 + z1) * 0.5f;
        float nx = ty * mz - tz * my, ny = tz * mx - tx * mz, nz = tx * my - ty * mx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return false;
        nx /= len;
        ny /= len;
        nz /= len;
        float px = nx, py = ny, pz = nz;
        if (!Float.isNaN(joint[0]) && joint[0] * nx + joint[1] * ny + joint[2] * nz > 0) {
            px = joint[0];
            py = joint[1];
            pz = joint[2];
        }
        float ha = (float) (halfA * ka), hb = (float) (halfB * kb);
        float aa = Math.min(1, alphaA), ab = Math.min(1, alphaB), cr = Math.min(1, r), cg = Math.min(1, g), cb = Math.min(1, b);
        float soft = (float) Math.max(halfA, halfB), ua = (float) ka, ub = (float) kb;
        FxAtlas.Sprite s = FxAtlas.glow();
        float v = s.vMid();
        out.quad((float) Math.max(da, db));
        out.vertex(x0 - px * ha, y0 - py * ha, z0 - pz * ha, s.u0(), v, cr * aa, cg * aa, cb * aa, aa, soft, ua, lightA, 0);
        out.vertex(x0 + px * ha, y0 + py * ha, z0 + pz * ha, s.u1(), v, cr * aa, cg * aa, cb * aa, aa, soft, ua, lightA, 0);
        out.vertex(x1 + nx * hb, y1 + ny * hb, z1 + nz * hb, s.u1(), v, cr * ab, cg * ab, cb * ab, ab, soft, ub, lightB, 0);
        out.vertex(x1 - nx * hb, y1 - ny * hb, z1 - nz * hb, s.u0(), v, cr * ab, cg * ab, cb * ab, ab, soft, ub, lightB, 0);
        counts[3]++;
        joint[0] = nx;
        joint[1] = ny;
        joint[2] = nz;
        return true;
    }

    /** Квадрат: свет (r, g, b) уже умножен на непрозрачность a; ни света, ни заслона — не пишется. */
    private boolean billboard(double dx, double dy, double dz, double half, float rot, FxAtlas.Sprite sprite, float r, float g, float b, float a,
                              float soft) {
        return billboard(dx, dy, dz, half, rot, sprite, r, g, b, a, soft, 0);
    }

    /** Квадрат с тем же угловым размером, перенесённый на {@code ahead} блоков ближе к глазу ({@link #front}). */
    private boolean billboard(double dx, double dy, double dz, double half, float rot, FxAtlas.Sprite sprite, float r, float g, float b, float a,
                              float soft, double ahead) {
        if (a < 0.002f && r + g + b < 0.006f) return false;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), shift = front(d, ahead), unfold = FarDraw.fold(d * shift, far), k = shift * unfold;
        // цвет вершины — байт: больше 1 переполнился бы
        out.billboard((float) (dx * k), (float) (dy * k), (float) (dz * k), (float) d, (float) (half * k), rot, sprite, Math.min(1, r), Math.min(1, g),
                Math.min(1, b), Math.min(1, a), soft, (float) unfold, LightTexture.FULL_BRIGHT, 0);
        return true;
    }

    /**
     * Во сколько раз ближе к глазу поставить квадрат на расстоянии d, чтобы он стоял на ahead блоков ближе, с тем же
     * угловым размером (размер × то же число); не ближе {@link #AIR_NEAR}, у самого глаза — на месте.
     */
    public static double front(double d, double ahead) {
        if (ahead <= 0 || d <= AIR_NEAR) return 1;
        return Math.max(d - ahead, AIR_NEAR) / d;
    }
}
