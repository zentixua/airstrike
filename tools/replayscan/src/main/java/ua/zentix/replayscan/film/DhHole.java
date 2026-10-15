package ua.zentix.replayscan.film;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import net.minecraft.util.Mth;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Дыра в мире в кадре вывода: что нарисовал Distant Horizons в точках лучей «вне» ({@link FrameOut.OutRay} — земля за
 * чанками повтора). Глубина LOD — текстура DH ({@code DhApi.Delayed.renderProxy.getDhDepthTextureGlId()}, размер —
 * {@link DhDepth}): читается по пикселю луча из своего буфера с этой текстурой. Формат глубины DH не документирован
 * (свои near/far и направление), поэтому соответствие «блоки → глубина» калибруется по самому плану: большинство лучей
 * «вне» смотрят на LOD на ожидаемом расстоянии до земли, и зависимость «блоки → глубина» линейна на длине луча —
 * робастная прямая (медианные наклоны) по последним парам; сошедшаяся прямая в пары пускает только согласующиеся точки,
 * чтобы кадры с дырами её не уводили. Луч с глубиной, чьё расстояние расходится с ожидаемой землёй больше
 * {@link #NEAR} блоков, — дыра: сквозь неё видна LOD-земля изнутри (пещеры) или ничего. Пока прямая не сошлась (первые
 * кадры плана), лучи — «неизвестно», не дыра.
 */
final class DhHole {
    /** LOD на ожидаемом расстоянии до земли: ближе этого расхождения блоков — земля на месте. */
    static final double NEAR = 8;
    /** Сколько пар «ожидаемая земля → глубина» держит калибровка; сколько пар нужно ей для первой прямой. */
    private static final int PAIRS = 400, NEED = 60;

    private int fbo = -1, texture = -1;
    private int width, height;
    /** Глубина DH: true — ближе больше (reverse-Z). */
    private boolean reverse;
    /** Калибровка «блоки → глубина» (z = a·D + b) и пары для неё: ожидаемая земля, глубина. */
    private double a = Double.NaN, b = Double.NaN;
    private final ArrayDeque<double[]> pairs = new ArrayDeque<>();

    private DhHole() {}

    /** null — DH не стоит или его API недоступен. */
    static DhHole create() {
        if (!FrameLog.DH) return null;
        try {
            DhHole h = new DhHole();
            h.reverse = DhApi.Delayed.renderProxy.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z;
            return h;
        } catch (RuntimeException | LinkageError e) {
            Film.LOG.warn("Film: DH depth direction is unavailable ({})", e.toString());
            return null;
        }
    }

    /**
     * Луч «вне»: глубина DH в его точке кадра — true дыра (расхождение с ожидаемой землёй больше {@link #NEAR} блоков:
     * сквозь неё видно пещеры LOD или ничего), false — LOD на месте или не известно (нет текстуры, калибровка не
     * сошлась). Согласующаяся пара идёт в калибровку.
     */
    boolean ray(double sx, double sy, double expected) {
        Float z = sample(sx, sy);
        if (z == null) return false;
        boolean hole = false;
        if (!Double.isNaN(a)) hole = Math.abs((z - b) / a - expected) > NEAR;
        // до первой прямой пары копятся все (большинство — норм LOD), после — только согласующиеся: дыры её не уводят
        if (!hole || Double.isNaN(a)) {
            pairs.addLast(new double[]{expected, reverse ? 1 - z : z});
            while (pairs.size() > PAIRS) pairs.removeFirst();
        }
        return hole;
    }

    /** Новая прямая «блоки → глубина» по парам (робастно: медиана наклонов, потом медиана остатков); раз в кадр. */
    void fit() {
        double[] line = robustLine(pairs);
        if (!Double.isNaN(line[0])) {
            a = line[0];
            b = line[1];
        }
    }

    /** Робастная прямая по парам {блоки, глубина}: {a, b} (NaN — пар мало или наклон выродился). */
    static double[] robustLine(ArrayDeque<double[]> pairs) {
        if (pairs.size() < NEED) return new double[]{Double.NaN, Double.NaN};
        double[][] p = pairs.toArray(new double[0][]);
        double[] slopes = new double[p.length * (p.length - 1) / 2];
        int n = 0;
        for (int i = 0; i < p.length; i++) {
            for (int j = i + 1; j < p.length; j++) {
                double dd = p[i][0] - p[j][0];
                if (Math.abs(dd) > 1e-3 && n < slopes.length) slopes[n++] = (p[i][1] - p[j][1]) / dd;
            }
        }
        double na = median(slopes, n);
        if (Double.isNaN(na) || Math.abs(na) < 1e-12) return new double[]{Double.NaN, Double.NaN};
        double[] resid = new double[p.length];
        for (int i = 0; i < p.length; i++) resid[i] = p[i][1] - na * p[i][0];
        return new double[]{na, median(resid, p.length)};
    }

    /** Глубина DH в точке кадра (доли полукадра, с креном); null — текстуры нет или читаться не может. */
    private Float sample(double sx, double sy) {
        DhApiResult<Integer> r = DhApi.Delayed.renderProxy.getDhDepthTextureGlId();
        if (!r.success || r.payload == null) return null;
        int t = r.payload;
        if (width == 0) {
            int[] size = DhDepth.textureSize();
            width = size[0];
            height = size[1];
        }
        if (width == 0) return null;
        if (fbo < 0 || texture != t) {
            if (fbo < 0) fbo = GL30.glGenFramebuffers();
            texture = t;
            int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, t, 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        }
        int x = Mth.clamp((int) ((sx + 1) / 2 * width), 0, width - 1), y = Mth.clamp((int) ((sy + 1) / 2 * height), 0, height - 1);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        if (read != fbo) GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        float[] px = new float[1];
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, px);
        if (read != fbo) GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        return px[0];
    }

    private static double median(double[] v, int n) {
        if (n == 0) return Double.NaN;
        Arrays.sort(v, 0, n);
        return (n & 1) == 1 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
    }
}
