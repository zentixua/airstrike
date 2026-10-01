package ua.zentix.airstrike.client.far;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.render.FarDraw;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Всё дальнее за кадр — четырьмя вызовами отрисовки: шлейфы (лентой), клубы дыма (по дальности, от дальних к ближним),
 * тела — круги (корпуса снарядов, огненные шары, ядра вспышек; так же), свет — ореолы (блик и вуаль вокруг вспышек,
 * шаров и факелов, зарево; складываются, порядок не важен). Числа копятся в массивах, которые живут между кадрами: кадр
 * ничего не выделяет. Координаты — относительно камеры; дальше дальней плоскости точка переносится ближе с тем же
 * угловым размером.
 * <p>
 * Смешивание одно на всё — с умноженной альфой ({@code ONE, ONE_MINUS_SRC_ALPHA}): цвет вершины — свет, уже умноженный
 * на непрозрачность, альфа — сколько закрыто того, что за спрайтом. Дым и корпус закрывают и светят своим цветом,
 * раскалённое закрывает и светит, ореол только светит (альфа 0 — сложение). Дымка воздуха — в непрозрачности
 * ({@link Sight}: L = L₀·t + небо·(1 − t)). Шейдер свой ({@code shaders/core/far_sprite}): ванильный
 * {@code position_tex_color} отбрасывает всё с альфой меньше 0,1, а дальний дым, зарево и точки мельче пикселя — как раз
 * бледные (в прогоне 131b6bd зарева ночью не было вовсе).
 * <p>
 * Текстуры ({@code tools/gen_particles.py}, сглаженные, без повтора): круг {@code far/disc} — сплошной, край сглажен;
 * ореол {@code far/glow} — гауссов, без ядра; ленты — его средняя строка. Сколько квадрата закрывает текстура, столько
 * света она и несёт — множители {@link #DISC}, {@link #GLOW} переводят радиус круга того же светового потока
 * в полуразмер квадрата ({@code FarSpritesTest}).
 */
public final class FarSprites {
    static final ResourceLocation PUFFS = Airstrike.id("textures/nuke/puffs.png");
    static final ResourceLocation DISC_TEXTURE = Airstrike.id("textures/far/disc.png"), GLOW_TEXTURE = Airstrike.id("textures/far/glow.png");
    /**
     * Полуразмер квадрата на радиус круга того же потока: круг {@code far/disc} закрывает 0,632 квадрата (√(π/(4·0,632))),
     * ореол {@code far/glow} — 0,133 (пик в середине — 1).
     */
    public static final double DISC = 1.115, GLOW = 2.43;
    /** Полуширина ленты на половину следа: середина {@code far/glow} поперёк закрывает 0,365 ширины. */
    public static final double RIBBON = 2.74;

    /** Квадраты лицом к камере: x, y, z, полуразмер, поворот, кадр атласа клубов, r, g, b, a (свет умножен на a). */
    private static final int BILLBOARD = 10;
    /** Отрезок ленты: два конца (x, y, z), полуширины, r, g, b, непрозрачности концов. */
    private static final int SEGMENT = 13;

    @Nullable
    private static ShaderInstance shader;
    /** Свой шейдер; не загрузился — ванильный (бледное он отбрасывает, в логе ошибка). */
    private static final Supplier<ShaderInstance> SHADER = () -> shader != null ? shader : GameRenderer.getPositionTexColorShader();

    final Batch puffs = new Batch(BILLBOARD), discs = new Batch(BILLBOARD), glows = new Batch(BILLBOARD), ribbons = new Batch(SEGMENT);
    private double far;

    /** Шина мода: свой шейдер дальних спрайтов (и заново при перезагрузке ресурсов). */
    public static void registerShaders(RegisterShadersEvent e) {
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Airstrike.id("far_sprite"), DefaultVertexFormat.POSITION_TEX_COLOR),
                    s -> shader = s);
        } catch (IOException ex) {
            shader = null;
            Airstrike.LOG.error("Шейдер дальних снарядов и взрывов не загрузился: рисую ванильным, бледный дым и зарево пропадут", ex);
        }
    }

    void begin(FarView view) {
        far = view.far();
        puffs.clear();
        discs.clear();
        glows.clear();
        ribbons.clear();
    }

    public boolean isEmpty() {
        return puffs.n == 0 && discs.n == 0 && glows.n == 0 && ribbons.n == 0;
    }

    /** Сколько чего в кадре (клубы, круги, свет, ленты) — в out, без выделения памяти. */
    void counts(int[] out) {
        out[0] = puffs.n;
        out[1] = discs.n;
        out[2] = glows.n;
        out[3] = ribbons.n;
    }

    /**
     * Клуб дыма или пыли (атлас 4×2 {@code nuke/puffs.png}, кадр tex) с центром (dx, dy, dz) от камеры: свой цвет
     * (r, g, b) с непрозрачностью a (уже с дымкой воздуха).
     */
    public void puff(double dx, double dy, double dz, double half, float rot, int tex, float r, float g, float b, float a) {
        billboard(puffs, dx, dy, dz, half, rot, tex, r * a, g * a, b * a, a);
    }

    /**
     * Круг радиуса radius (того же потока: квадрат — в {@link #DISC} раз больше) своего цвета с непрозрачностью a поверх
     * того, что за ним: корпус снаряда, огненный шар, ядро вспышки.
     */
    public void disc(double dx, double dy, double dz, double radius, float r, float g, float b, float a) {
        billboard(discs, dx, dy, dz, radius * DISC, 0, 0, r * a, g * a, b * a, a);
    }

    /**
     * Ореол, который несёт свет круга радиуса radius яркостью a (пик в середине — a): складывается с тем, что за ним —
     * блик и вуаль вокруг вспышки, шара и факела, зарево, пожар.
     */
    public void glow(double dx, double dy, double dz, double radius, float r, float g, float b, float a) {
        billboard(glows, dx, dy, dz, radius * GLOW, 0, 0, r * a, g * a, b * a, 0);
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
        billboard(discs, dx, dy, dz, out[0] * DISC, 0, 0, (float) ((r + (1 - r) * white) * k), (float) ((g + (1 - g) * white) * k),
                (float) ((b + (1 - b) * white) * k), a);
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
        int o = ribbons.add(Math.max(da, db));
        float[] s = ribbons.data;
        s[o] = (float) (ax * ka);
        s[o + 1] = (float) (ay * ka);
        s[o + 2] = (float) (az * ka);
        s[o + 3] = (float) (bx * kb);
        s[o + 4] = (float) (by * kb);
        s[o + 5] = (float) (bz * kb);
        s[o + 6] = (float) (halfA * ka);
        s[o + 7] = (float) (halfB * kb);
        s[o + 8] = Math.min(1, r);
        s[o + 9] = Math.min(1, g);
        s[o + 10] = Math.min(1, b);
        s[o + 11] = Math.min(1, alphaA);
        s[o + 12] = Math.min(1, alphaB);
    }

    /** Записать квадрат: свет (r, g, b) уже умножен на непрозрачность a; ни света, ни заслона — не рисуется. */
    private void billboard(Batch batch, double dx, double dy, double dz, double half, float rot, int tex, float r, float g, float b, float a) {
        if (a < 0.002f && r + g + b < 0.006f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), k = FarDraw.fold(d, far);
        int o = batch.add(d);
        float[] s = batch.data;
        s[o] = (float) (dx * k);
        s[o + 1] = (float) (dy * k);
        s[o + 2] = (float) (dz * k);
        s[o + 3] = (float) (half * k);
        s[o + 4] = rot;
        s[o + 5] = tex;
        // цвет вершины — байт: больше 1 переполнился бы
        s[o + 6] = Math.min(1, r);
        s[o + 7] = Math.min(1, g);
        s[o + 8] = Math.min(1, b);
        s[o + 9] = Math.min(1, a);
    }

    // ---------------------------------------------------------------- отрисовка

    void draw(FarView view) {
        Vector3f left = view.left(), up = view.up();
        if (ribbons.n > 0) {
            setup(GLOW_TEXTURE);
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (int i = 0; i < ribbons.n; i++) segment(b, ribbons.data, ribbons.at(i));
            FarDraw.draw(b);
        }
        billboards(puffs, PUFFS, true, left, up, true);
        billboards(discs, DISC_TEXTURE, true, left, up, false);
        billboards(glows, GLOW_TEXTURE, false, left, up, false);
    }

    /** Свой шейдер и смешивание с умноженной альфой; глубина проверяется, но не пишется (прозрачное поверх мира). */
    private static void setup(ResourceLocation texture) {
        RenderSystem.setShader(SHADER);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
    }

    private static void billboards(Batch batch, ResourceLocation texture, boolean sorted, Vector3f left, Vector3f up, boolean atlas) {
        if (batch.n == 0) return;
        if (sorted) batch.sortFarFirst();
        setup(texture);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float[] s = batch.data;
        for (int i = 0; i < batch.n; i++) {
            int o = batch.at(i);
            float u0 = 0, v0 = 0, u1 = 1, v1 = 1;
            if (atlas) {
                int tex = (int) s[o + 5];
                u0 = (tex % 4) * 0.25f;
                v0 = (tex / 4) * 0.5f;
                u1 = u0 + 0.25f;
                v1 = v0 + 0.5f;
            }
            FarDraw.billboard(b, left, up, s[o], s[o + 1], s[o + 2], s[o + 3], s[o + 4], u0, v0, u1, v1, s[o + 6], s[o + 7], s[o + 8], s[o + 9]);
        }
        FarDraw.draw(b);
    }

    /** Лента от a до b, повёрнутая к камере (камера в начале координат); поперёк — середина {@code far/glow}. */
    private static void segment(BufferBuilder b, float[] s, int o) {
        float ax = s[o], ay = s[o + 1], az = s[o + 2], bx = s[o + 3], by = s[o + 4], bz = s[o + 5];
        float tx = bx - ax, ty = by - ay, tz = bz - az;
        float mx = (ax + bx) * 0.5f, my = (ay + by) * 0.5f, mz = (az + bz) * 0.5f;
        // поперёк ленты: перпендикуляр к отрезку и к лучу зрения
        float nx = ty * mz - tz * my, ny = tz * mx - tx * mz, nz = tx * my - ty * mx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return;
        nx /= len;
        ny /= len;
        nz /= len;
        float ha = s[o + 6], hb = s[o + 7], r = s[o + 8], g = s[o + 9], bl = s[o + 10], aa = s[o + 11], ab = s[o + 12];
        b.addVertex(ax - nx * ha, ay - ny * ha, az - nz * ha).setUv(0, 0.5f).setColor(r * aa, g * aa, bl * aa, aa);
        b.addVertex(ax + nx * ha, ay + ny * ha, az + nz * ha).setUv(1, 0.5f).setColor(r * aa, g * aa, bl * aa, aa);
        b.addVertex(bx + nx * hb, by + ny * hb, bz + nz * hb).setUv(1, 0.5f).setColor(r * ab, g * ab, bl * ab, ab);
        b.addVertex(bx - nx * hb, by - ny * hb, bz - nz * hb).setUv(0, 0.5f).setColor(r * ab, g * ab, bl * ab, ab);
    }

    /** Записи одного вида: числа подряд и ключи сортировки (дальность и номер в одном long). */
    static final class Batch {
        private final int stride;
        float[] data = new float[0];
        private long[] keys = new long[0];
        private boolean sorted;
        int n;

        Batch(int stride) {
            this.stride = stride;
        }

        void clear() {
            n = 0;
            sorted = false;
        }

        /** Новая запись на дальности d: смещение её чисел в {@link #data}. */
        int add(double d) {
            if (n == keys.length) {
                int cap = Math.max(64, n * 2);
                data = Arrays.copyOf(data, cap * stride);
                keys = Arrays.copyOf(keys, cap);
            }
            keys[n] = ((long) Float.floatToIntBits((float) d) << 32) | n;
            return stride * n++;
        }

        /** От дальних к ближним (прозрачное поверх мира). */
        void sortFarFirst() {
            Arrays.sort(keys, 0, n);
            for (int i = 0, j = n - 1; i < j; i++, j--) {
                long t = keys[i];
                keys[i] = keys[j];
                keys[j] = t;
            }
            sorted = true;
        }

        /** Смещение i-й записи (после сортировки — в её порядке). */
        int at(int i) {
            return stride * (sorted ? (int) keys[i] : i);
        }
    }
}
