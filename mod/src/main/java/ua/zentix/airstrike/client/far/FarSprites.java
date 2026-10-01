package ua.zentix.airstrike.client.far;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.render.FarDraw;

import java.util.Arrays;

/**
 * Всё дальнее за кадр — четырьмя вызовами отрисовки: шлейфы (лентой), клубы дыма (по дальности, от дальних к ближним),
 * тёмные точки корпусов (так же), свет (вспышки, шары, факелы — складываются, порядок не важен). Числа копятся
 * в массивах, которые живут между кадрами: кадр ничего не выделяет. Координаты — относительно камеры; дальше
 * дальней плоскости точка переносится ближе с тем же угловым размером.
 */
public final class FarSprites {
    static final ResourceLocation FLARE = Airstrike.id("textures/nuke/flare.png");
    static final ResourceLocation PUFFS = Airstrike.id("textures/nuke/puffs.png");

    /** Квадраты лицом к камере: x, y, z, полуразмер, поворот, кадр атласа клубов, r, g, b, a. */
    private static final int BILLBOARD = 10;
    /** Отрезок ленты: два конца (x, y, z), полуширины, r, g, b, непрозрачности концов. */
    private static final int SEGMENT = 13;

    final Batch puffs = new Batch(BILLBOARD), dots = new Batch(BILLBOARD), glows = new Batch(BILLBOARD), ribbons = new Batch(SEGMENT);
    private double far;

    void begin(FarView view) {
        far = view.far();
        puffs.clear();
        dots.clear();
        glows.clear();
        ribbons.clear();
    }

    public boolean isEmpty() {
        return puffs.n == 0 && dots.n == 0 && glows.n == 0 && ribbons.n == 0;
    }

    /** Сколько чего в кадре (клубы, точки, свет, ленты) — в out, без выделения памяти. */
    void counts(int[] out) {
        out[0] = puffs.n;
        out[1] = dots.n;
        out[2] = glows.n;
        out[3] = ribbons.n;
    }

    /** Клуб дыма или пыли (атлас 4×2 {@code nuke/puffs.png}, кадр tex) с центром (dx, dy, dz) от камеры. */
    public void puff(double dx, double dy, double dz, double half, float rot, int tex, float r, float g, float b, float a) {
        billboard(puffs, dx, dy, dz, half, rot, tex, r, g, b, a);
    }

    /** Тёмная мягкая точка (корпус вдали). */
    public void dot(double dx, double dy, double dz, double half, float r, float g, float b, float a) {
        billboard(dots, dx, dy, dz, half, 0, 0, r, g, b, a);
    }

    /** Свет: складывается с тем, что за ним (вспышка, огненный шар, факел). */
    public void glow(double dx, double dy, double dz, double half, float r, float g, float b, float a) {
        billboard(glows, dx, dy, dz, half, 0, 0, r, g, b, a);
    }

    /** Отрезок шлейфа лентой лицом к камере: от (ax..) до (bx..), полуширины и непрозрачности концов. */
    public void ribbon(double ax, double ay, double az, double halfA, float alphaA, double bx, double by, double bz, double halfB, float alphaB,
                       float r, float g, float b) {
        if (alphaA < 0.004f && alphaB < 0.004f) return;
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
        s[o + 8] = r;
        s[o + 9] = g;
        s[o + 10] = b;
        s[o + 11] = alphaA;
        s[o + 12] = alphaB;
    }

    private void billboard(Batch batch, double dx, double dy, double dz, double half, float rot, int tex, float r, float g, float b, float a) {
        if (a < 0.004f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), k = FarDraw.fold(d, far);
        int o = batch.add(d);
        float[] s = batch.data;
        s[o] = (float) (dx * k);
        s[o + 1] = (float) (dy * k);
        s[o + 2] = (float) (dz * k);
        s[o + 3] = (float) (half * k);
        s[o + 4] = rot;
        s[o + 5] = tex;
        s[o + 6] = r;
        s[o + 7] = g;
        s[o + 8] = b;
        s[o + 9] = a;
    }

    // ---------------------------------------------------------------- отрисовка

    void draw(FarView view) {
        Vector3f left = view.left(), up = view.up();
        if (ribbons.n > 0) {
            FarDraw.setup(FLARE, false);
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (int i = 0; i < ribbons.n; i++) segment(b, ribbons.data, ribbons.at(i));
            FarDraw.draw(b);
        }
        billboards(puffs, PUFFS, false, left, up, true);
        billboards(dots, FLARE, false, left, up, false);
        billboards(glows, FLARE, true, left, up, false);
    }

    private static void billboards(Batch batch, ResourceLocation texture, boolean additive, Vector3f left, Vector3f up, boolean atlas) {
        if (batch.n == 0) return;
        if (!additive) batch.sortFarFirst();
        FarDraw.setup(texture, additive);
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

    /** Лента от a до b, повёрнутая к камере (камера в начале координат); поперёк — середина текстуры вспышки. */
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
        float ha = s[o + 6], hb = s[o + 7], r = s[o + 8], g = s[o + 9], bl = s[o + 10];
        b.addVertex(ax - nx * ha, ay - ny * ha, az - nz * ha).setUv(0, 0.5f).setColor(r, g, bl, s[o + 11]);
        b.addVertex(ax + nx * ha, ay + ny * ha, az + nz * ha).setUv(1, 0.5f).setColor(r, g, bl, s[o + 11]);
        b.addVertex(bx + nx * hb, by + ny * hb, bz + nz * hb).setUv(1, 0.5f).setColor(r, g, bl, s[o + 12]);
        b.addVertex(bx - nx * hb, by - ny * hb, bz - nz * hb).setUv(0, 0.5f).setColor(r, g, bl, s[o + 12]);
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
