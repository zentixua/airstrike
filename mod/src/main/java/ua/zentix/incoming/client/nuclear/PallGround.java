package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * Рельеф под пеленой подрыва — грубая карта высот для луча пелены, за которым нет мира ({@code pallFloor} в
 * {@code pall.glsl}): за дальностью прорисовки без Distant Horizons, за краем его LOD и между ними луч иначе не знал бы,
 * где кончается. Плоскость земли под эпицентром тут не годится: камера в долине ниже неё смотрит вниз сквозь пустоту,
 * и луч кончался бы у самого глаза — под горизонтом ровный срез без пелены.
 * <p>
 * Квадрат {@link #SIDE}² клеток вокруг эпицентра на всю рамку пелены ({@link PallField#extent}; растёт — карта
 * перекладывается на крупнее, известное остаётся). Тик клиента замеряет {@link #PER_TICK} клеток по кругу, от эпицентра
 * наружу, — по четырём колонкам в клетке, тем, что знает клиент ({@code heights}: свои чанки, плитки карты рельефа —
 * с Distant Horizons и вдали); раз увиденная клетка остаётся такой, какой её видели (ушёл игрок — чанки выгрузились).
 * Неизвестное заполняется по известному вокруг (pull-push: средние по уменьшенным копиям, сверху вниз — мягко);
 * земля под эпицентром (её знает сервер) — известна всегда, весом одной клетки; не знаем ничего — карта на её высоте,
 * как раньше. Карта заполняется заново не чаще раза в секунду и только если что-то узнали; в видеопамять — по
 * {@link #version} ({@code PallPass}). Цена на кадр — выборки карты по лучам в пустоту, на тик — до 512 колонок.
 */
public final class PallGround {
    /** Клеток по стороне карты. */
    static final int SIDE = 128;
    /** Клетка — не мельче стольких блоков (у малого подрыва карта меньше, а не подробнее). */
    static final double MIN_CELL = 16;
    /** Клеток за тик: вся карта — за 6,4 с. */
    static final int PER_TICK = 128;
    /** Заполнять карту заново — не чаще раза в столько тиков. */
    static final int FILL_TICKS = 20;
    /** Рамка пелены выросла за карту — новая карта с этим запасом. */
    static final double GROW = 1.25;
    private static final int LEVELS = Integer.numberOfTrailingZeros(SIDE) + 1;
    /** Клетки по удалению от середины карты: замеры идут от эпицентра наружу. */
    private static final int[] ORDER = IntStream.range(0, SIDE * SIDE).boxed()
            .sorted(Comparator.comparingDouble(i -> Mth.square(i % SIDE + 0.5 - SIDE / 2.0) + Mth.square(i / SIDE + 0.5 - SIDE / 2.0)))
            .mapToInt(Integer::intValue).toArray();

    private final PallField pall;
    private final double cx, cz, groundY;
    /** Полсторона карты и клетка, блоки. */
    private double half, cell;
    /** Высота клеток над землёй под эпицентром, блоки; {@link Float#NaN} — не знаем. */
    private float[] known = new float[SIDE * SIDE];
    /** Заполненная карта (для шейдера): высоты над землёй под эпицентром, блоки, x быстрее. */
    private final float[] map = new float[SIDE * SIDE];
    /** Низ и верх заполненной карты, блоки над землёй под эпицентром. */
    private float lo, hi;
    private int next, version, sinceFill;
    private boolean dirty;
    /** Уменьшенные копии для {@link #fill} (общие: карты заполняет один поток — тик клиента). */
    private static final float[][] V = new float[LEVELS][], W = new float[LEVELS][];

    PallGround(Detonation d, PallField pall) {
        this.pall = pall;
        this.cx = d.burst().x;
        this.cz = d.burst().z;
        this.groundY = d.groundY();
        Arrays.fill(known, Float.NaN);
        resize(pall.extent(0) * GROW);
        fill();
    }

    /** Полсторона карты, блоки. */
    double half() {
        return half;
    }

    /** Заполнение карты: меняется, когда карта заполнена заново или переложена. */
    int version() {
        return version;
    }

    /**
     * Тик клиента: рамка пелены к {@code ticks} (тики после подрыва) выросла за карту — переложить; следующие клетки —
     * по {@code heights} (null — рельефа не знать: измерение с потолком; карта остаётся как есть); узнали новое —
     * заполнить заново, но не чаще раза в {@link #FILL_TICKS} тиков.
     */
    void survey(double ticks, @Nullable Sightline.Heights heights) {
        double extent = pall.extent(ticks);
        // карта и её полсторона уходят в кадр вместе: переложенная — сразу заполнена
        boolean grew = extent > half;
        if (grew) resize(extent * GROW);
        if (heights != null) {
            for (int n = 0; n < PER_TICK; n++) {
                int i = ORDER[next];
                next = (next + 1) % ORDER.length;
                double h = measure(i, heights);
                if (Double.isNaN(h)) continue;
                float v = (float) (h - groundY);
                if (Float.isNaN(known[i]) || Math.abs(known[i] - v) > 0.5f) {
                    known[i] = v;
                    dirty = true;
                }
            }
        }
        if (grew || ++sinceFill >= FILL_TICKS && dirty) fill();
    }

    /** Верх рельефа в клетке i — среднее известных из четырёх колонок (по четвертям); NaN — ни одной не знаем. */
    private double measure(int i, Sightline.Heights heights) {
        double x0 = cx - half + (i % SIDE + 0.25) * cell, z0 = cz - half + (i / SIDE + 0.25) * cell;
        double sum = 0;
        int n = 0;
        for (int k = 0; k < 4; k++) {
            double h = heights.at(Mth.floor(x0 + (k & 1) * 0.5 * cell), Mth.floor(z0 + (k >> 1) * 0.5 * cell));
            if (Double.isNaN(h)) continue;
            sum += h;
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** Карта на полсторону {@code to} (не меньше {@link #MIN_CELL} в клетке): известное — в клетки новой карты по их середине. */
    private void resize(double to) {
        double h = Math.max(to, MIN_CELL * SIDE / 2), c = 2 * h / SIDE;
        if (half > 0) {
            float[] was = known;
            known = new float[SIDE * SIDE];
            for (int j = 0; j < SIDE; j++) {
                for (int i = 0; i < SIDE; i++) {
                    int oi = Mth.floor((-h + (i + 0.5) * c + half) / cell), oj = Mth.floor((-h + (j + 0.5) * c + half) / cell);
                    known[j * SIDE + i] = oi < 0 || oj < 0 || oi >= SIDE || oj >= SIDE ? Float.NaN : was[oj * SIDE + oi];
                }
            }
            next = 0;
        }
        half = h;
        cell = c;
    }

    /**
     * Заполнить карту (pull-push): уменьшенные копии — средние известного по числу клеток (вес копии — сколько
     * известного под ней), потом сверху вниз — неизвестное и полуизвестное (вес меньше 1) берёт копию крупнее
     * (билинейно, без ступенек). Земля под эпицентром известна всегда — в четырёх клетках вокруг него весом одной
     * клетки; не знаем ничего — её высота (0).
     */
    void fill() {
        int levels = LEVELS;
        float[][] v = V, w = W;
        if (v[0] == null) {
            for (int l = 0; l < levels; l++) {
                int n = SIDE >> l;
                v[l] = new float[n * n];
                w[l] = new float[n * n];
            }
        }
        for (int i = 0; i < SIDE * SIDE; i++) {
            boolean has = !Float.isNaN(known[i]);
            v[0][i] = has ? known[i] : 0;
            w[0][i] = has ? 1 : 0;
        }
        int mid = SIDE / 2;
        for (int k = 0; k < 4; k++) {
            int i = (mid - 1 + (k >> 1)) * SIDE + mid - 1 + (k & 1);
            if (w[0][i] == 0) w[0][i] = 0.25f;
        }
        for (int l = 1; l < levels; l++) {
            int n = SIDE >> l, m = n * 2;
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    float sv = 0, sw = 0;
                    for (int k = 0; k < 4; k++) {
                        int c = (2 * j + (k >> 1)) * m + 2 * i + (k & 1);
                        sv += v[l - 1][c] * w[l - 1][c];
                        sw += w[l - 1][c];
                    }
                    v[l][j * n + i] = sw > 0 ? sv / sw : 0;
                    w[l][j * n + i] = sw;
                }
            }
        }
        // сверху вниз: v[l] становится заполненной копией
        for (int l = levels - 2; l >= 0; l--) {
            int n = SIDE >> l;
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int c = j * n + i;
                    float a = Math.min(w[l][c], 1);
                    if (a >= 1) continue;
                    float up = bilinear(v[l + 1], n / 2, (i + 0.5f) / 2 - 0.5f, (j + 0.5f) / 2 - 0.5f);
                    v[l][c] = a * v[l][c] + (1 - a) * up;
                }
            }
        }
        System.arraycopy(v[0], 0, map, 0, map.length);
        float a = Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (float h : map) {
            a = Math.min(a, h);
            b = Math.max(b, h);
        }
        lo = a;
        hi = b;
        version++;
        sinceFill = 0;
        dirty = false;
    }

    /** Копия n × n в точке (x, y) в её клетках (середина клетки 0 — 0), края — как у края. */
    private static float bilinear(float[] t, int n, float x, float y) {
        if (n == 1) return t[0];
        x = Mth.clamp(x, 0, n - 1);
        y = Mth.clamp(y, 0, n - 1);
        int i = Math.min((int) x, n - 2), j = Math.min((int) y, n - 2);
        float fx = x - i, fy = y - j;
        float a = t[j * n + i] + (t[j * n + i + 1] - t[j * n + i]) * fx;
        float b = t[(j + 1) * n + i] + (t[(j + 1) * n + i + 1] - t[(j + 1) * n + i]) * fx;
        return a + (b - a) * fy;
    }

    /** Высота заполненной карты над землёй под эпицентром в точке (x, z) мира, блоки — как в шейдере (билинейно, края — как у края). */
    double at(double x, double z) {
        return bilinear(map, SIDE, (float) ((x - cx + half) / cell - 0.5), (float) ((z - cz + half) / cell - 0.5));
    }

    /** Для слоя эффектов: карта и её числа ({@code pall.glsl}: полсторона, низ, верх, клетка). */
    void frame(PallPass.Ground out) {
        out.params.set((float) half, lo, hi, (float) cell);
        out.map = map;
        out.side = SIDE;
        out.source = this;
        out.version = version;
    }
}
