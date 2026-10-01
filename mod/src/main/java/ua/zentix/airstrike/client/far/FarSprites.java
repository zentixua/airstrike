package ua.zentix.airstrike.client.far;

import net.minecraft.client.renderer.LightTexture;
import ua.zentix.airstrike.client.fx.layer.FxAtlas;
import ua.zentix.airstrike.client.fx.layer.FxQuads;
import ua.zentix.airstrike.client.render.FarDraw;

import java.util.Arrays;

/**
 * Всё дальнее — квадратами в общий кадр слоя эффектов ({@link FxQuads}): шлейфы (лентой), клубы дыма, тела — круги
 * (корпуса снарядов, огненные шары, ядра вспышек), свет — ореолы (блик и вуаль вокруг вспышек, шаров и факелов,
 * зарево). Слой сортирует их вместе с ближними частицами от дальних к ближним по настоящему расстоянию, поэтому
 * огненный шар за ближним столбом дыма закрыт дымом, а пусковая в десятке блоков — впереди дальних столбов.
 * Координаты — относительно камеры; дальше дальней плоскости точка переносится ближе с тем же угловым размером,
 * а слою передаётся, во сколько раз (по настоящему расстоянию он прячет дальнее за рельефом и LOD Distant Horizons).
 * <p>
 * Цвет вершины — свет, уже умноженный на непрозрачность, альфа — сколько закрыто того, что за спрайтом. Дым и корпус
 * закрывают и светят своим цветом, раскалённое закрывает и светит, ореол только светит (альфа 0 — сложение). Дымка
 * воздуха — в непрозрачности ({@link Sight}: L = L₀·t + небо·(1 − t)).
 * <p>
 * Текстуры ({@code tools/gen_particles.py}, на листе слоя): круг {@code far/disc} — сплошной, край сглажен; ореол
 * {@code far/glow} — гауссов, без ядра; ленты — его средняя строка. Сколько квадрата закрывает текстура, столько
 * света она и несёт — множители {@link #DISC}, {@link #GLOW} переводят радиус круга того же светового потока
 * в полуразмер квадрата ({@code FarSpritesTest}).
 */
public final class FarSprites {
    /**
     * Полуразмер квадрата на радиус круга того же потока: круг {@code far/disc} закрывает 0,632 квадрата (√(π/(4·0,632))),
     * ореол {@code far/glow} — 0,133 (пик в середине — 1).
     */
    public static final double DISC = 1.115, GLOW = 2.43;
    /** Полуширина ленты на половину следа: середина {@code far/glow} поперёк закрывает 0,365 ширины. */
    public static final double RIBBON = 2.74;
    /** Мягкость края у рельефа: клуб — {@link FxQuads#SOFT} полуразмера, тело — четверть радиуса. */
    private static final float BODY_SOFT = 0.25f;

    private FxQuads out;
    private double far;
    /** Сколько клубов, кругов, света и лент записано в этом кадре. */
    private final int[] counts = new int[4];

    /** Новый кадр: писать в out. */
    void begin(FarView view, FxQuads out) {
        this.out = out;
        far = view.far();
        Arrays.fill(counts, 0);
    }

    public boolean isEmpty() {
        return counts[0] + counts[1] + counts[2] + counts[3] == 0;
    }

    /** Сколько чего в кадре (клубы, круги, свет, ленты) — в out, без выделения памяти. */
    void counts(int[] into) {
        System.arraycopy(counts, 0, into, 0, 4);
    }

    /**
     * Клуб дыма или пыли (кадр tex из 4×2 {@code nuke/puffs.png}) с центром (dx, dy, dz) от камеры: свой цвет
     * (r, g, b) с непрозрачностью a (уже с дымкой воздуха).
     */
    public void puff(double dx, double dy, double dz, double half, float rot, int tex, float r, float g, float b, float a) {
        if (billboard(dx, dy, dz, half, rot, FxAtlas.puff(tex), r * a, g * a, b * a, a, (float) half * FxQuads.SOFT)) counts[0]++;
    }

    /**
     * Круг радиуса radius (того же потока: квадрат — в {@link #DISC} раз больше) своего цвета с непрозрачностью a поверх
     * того, что за ним: корпус снаряда, огненный шар, ядро вспышки.
     */
    public void disc(double dx, double dy, double dz, double radius, float r, float g, float b, float a) {
        if (billboard(dx, dy, dz, radius * DISC, 0, FxAtlas.disc(), r * a, g * a, b * a, a, (float) radius * BODY_SOFT)) counts[1]++;
    }

    /**
     * Ореол, который несёт свет круга радиуса radius яркостью a (пик в середине — a): складывается с тем, что за ним —
     * блик и вуаль вокруг вспышки, шара и факела, зарево, пожар. Блик и вуаль — в глазу, а не в воздухе: мягкого
     * края у рельефа нет (видно ли само тело, решает вызывающий).
     */
    public void glow(double dx, double dy, double dz, double radius, float r, float g, float b, float a) {
        if (billboard(dx, dy, dz, radius * GLOW, 0, FxAtlas.glow(), r * a, g * a, b * a, 0, 0)) counts[2]++;
    }

    /**
     * Яркое тело с центром (dx, dy, dz) от камеры радиуса radius, яркостью seen против белого экрана (с привыканием глаза
     * и воздухом), цвета (r, g, b) ({@link Sight#light}): ядро — свой свет поверх того, что за ним (пересвет — к белому,
     * тусклее белого — гаснет и тает: остывший шар уже стал дымом), блик и вуаль — ореолами того же цвета.
     *
     * @param t   доля света, дошедшая через воздух: насколько тело закрывает то, что за ним
     * @param w   общая доля (видимая над рельефом, переход к ближней картинке)
     * @param out числа {@link Sight#light} (5) — для лога
     */
    public void light(double dx, double dy, double dz, double radius, double seen, double t, double pixel, float r, float g, float b, double w,
                      double[] out) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Sight.light(radius, seen, d, pixel, out);
        double s = out[2], k = Math.min(1, s) * w, white = s > 1 ? Math.min(1, Math.log(s) / Math.log(Sight.WHITE)) : 0;
        float a = (float) (out[1] * t * Math.min(1, 4 * s) * w);
        if (billboard(dx, dy, dz, out[0] * DISC, 0, FxAtlas.disc(), (float) ((r + (1 - r) * white) * k), (float) ((g + (1 - g) * white) * k),
                (float) ((b + (1 - b) * white) * k), a, (float) (out[0] * BODY_SOFT))) counts[1]++;
        glow(dx, dy, dz, out[3], r, g, b, (float) (Math.min(Sight.HALO, Sight.SCATTER * seen) * w));
        if (out[4] > 0) glow(dx, dy, dz, out[4], r, g, b, (float) (Sight.VEIL_PEAK * w));
    }

    /**
     * Отрезок шлейфа лентой лицом к камере: от (ax..) до (bx..), полуширины (уже с {@link #RIBBON}) и непрозрачности
     * концов, свой цвет.
     */
    public void ribbon(double ax, double ay, double az, double halfA, float alphaA, double bx, double by, double bz, double halfB, float alphaB,
                       float r, float g, float b) {
        if (alphaA < 0.002f && alphaB < 0.002f) return;
        double da = Math.sqrt(ax * ax + ay * ay + az * az), db = Math.sqrt(bx * bx + by * by + bz * bz);
        double ka = FarDraw.fold(da, far), kb = FarDraw.fold(db, far);
        float x0 = (float) (ax * ka), y0 = (float) (ay * ka), z0 = (float) (az * ka);
        float x1 = (float) (bx * kb), y1 = (float) (by * kb), z1 = (float) (bz * kb);
        float tx = x1 - x0, ty = y1 - y0, tz = z1 - z0;
        float mx = (x0 + x1) * 0.5f, my = (y0 + y1) * 0.5f, mz = (z0 + z1) * 0.5f;
        // поперёк ленты: перпендикуляр к отрезку и к лучу зрения
        float nx = ty * mz - tz * my, ny = tz * mx - tx * mz, nz = tx * my - ty * mx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return;
        nx /= len;
        ny /= len;
        nz /= len;
        float ha = (float) (halfA * ka), hb = (float) (halfB * kb);
        float aa = Math.min(1, alphaA), ab = Math.min(1, alphaB), cr = Math.min(1, r), cg = Math.min(1, g), cb = Math.min(1, b);
        float soft = (float) Math.max(halfA, halfB), ua = (float) ka, ub = (float) kb;
        FxAtlas.Sprite s = FxAtlas.glow();
        float v = s.vMid();
        int light = LightTexture.FULL_BRIGHT;
        out.quad((float) Math.max(da, db));
        out.vertex(x0 - nx * ha, y0 - ny * ha, z0 - nz * ha, s.u0(), v, cr * aa, cg * aa, cb * aa, aa, soft, ua, light, 0);
        out.vertex(x0 + nx * ha, y0 + ny * ha, z0 + nz * ha, s.u1(), v, cr * aa, cg * aa, cb * aa, aa, soft, ua, light, 0);
        out.vertex(x1 + nx * hb, y1 + ny * hb, z1 + nz * hb, s.u1(), v, cr * ab, cg * ab, cb * ab, ab, soft, ub, light, 0);
        out.vertex(x1 - nx * hb, y1 - ny * hb, z1 - nz * hb, s.u0(), v, cr * ab, cg * ab, cb * ab, ab, soft, ub, light, 0);
        counts[3]++;
    }

    /** Квадрат: свет (r, g, b) уже умножен на непрозрачность a; ни света, ни заслона — не пишется. */
    private boolean billboard(double dx, double dy, double dz, double half, float rot, FxAtlas.Sprite sprite, float r, float g, float b, float a,
                              float soft) {
        if (a < 0.002f && r + g + b < 0.006f) return false;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), k = FarDraw.fold(d, far);
        // цвет вершины — байт: больше 1 переполнился бы
        out.billboard((float) (dx * k), (float) (dy * k), (float) (dz * k), (float) d, (float) (half * k), rot, sprite, Math.min(1, r), Math.min(1, g),
                Math.min(1, b), Math.min(1, a), soft, (float) k, LightTexture.FULL_BRIGHT, 0);
        return true;
    }
}
