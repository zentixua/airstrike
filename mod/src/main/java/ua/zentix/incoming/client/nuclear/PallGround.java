package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarTerrain;
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
 * и луч кончался бы у самого глаза — под горизонтом ровный срез без пелены. Второй канал — доля воды в клетке: из чего
 * пелена ({@link PallField}: над сушей пыль, над водой брызги; шейдер берёт её там, откуда вещество подняло).
 * <p>
 * Квадрат {@link #SIDE}² клеток вокруг эпицентра на всю рамку пелены ({@link PallField#extent}; растёт — карта
 * перекладывается на крупнее, известное остаётся). Тик клиента замеряет {@link #PER_TICK} клеток по кругу, от эпицентра
 * наружу, — по четырём колонкам в клетке, тем, что знает клиент ({@code heights}, {@code water}: свои чанки, плитки карты
 * рельефа — с Distant Horizons и вдали); раз увиденная клетка остаётся такой, какой её видели (ушёл игрок — чанки
 * выгрузились). Что под эпицентром, известно всегда (землю знает сервер, воду — {@code NukeWater.under}: чанк, плитка карты
 * или уровень моря). Неизвестное заполняется по известному — каждый канал по своей природе:
 * <ul>
 * <li>высота — поле сплошное: средним известного вокруг (pull-push, {@link #pullPush}: средние по уменьшенным копиям,
 * сверху вниз — мягко), под эпицентром — его земля весом одной клетки; вдали — средний уровень увиденного;</li>
 * <li>вода — признак клетки (суша или вода), и он тянется: берег продолжается, открытое море остаётся морем. Средним
 * известного он стал бы «полуводой», которой нигде нет (камера у пруда — бледная пыль на всю неизвестную сушу, у берега —
 * полпыли над морем вокруг подрыва), поэтому — ближайшим известным ({@link #nearest}), и эпицентр в нём — известная
 * точка наравне с увиденными клетками.</li>
 * </ul>
 * Не знаем ничего — вся карта как под эпицентром: на его высоте, и подрыв в море — над морем (как у огненного шторма,
 * {@link FireStorm#survey}). Карта заполняется заново не чаще раза в секунду и только если что-то узнали (вода под
 * эпицентром решилась иначе — сразу); в видеопамять — по {@link #version} ({@code PallPass}, RG16F: высота и вода). Цена на
 * кадр — выборки карты по лучам в пустоту и выборка воды на шаг луча пелены там, где под ней есть вода; на тик — до 1024
 * колонок.
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
    /** Воды или суши меньше этой доли во всей карте — её под пеленой нет ({@link #hasWater}, {@link #hasLand}). */
    static final float TRACE = 1e-3f;
    private static final int LEVELS = Integer.numberOfTrailingZeros(SIDE) + 1;
    /** Клетки по удалению от середины карты: замеры идут от эпицентра наружу. */
    private static final int[] ORDER = IntStream.range(0, SIDE * SIDE).boxed()
            .sorted(Comparator.comparingDouble(i -> Mth.square(i % SIDE + 0.5 - SIDE / 2.0) + Mth.square(i / SIDE + 0.5 - SIDE / 2.0)))
            .mapToInt(Integer::intValue).toArray();

    /** Что знает клиент о колонке: число или {@link Double#NaN} — не знает. */
    @FunctionalInterface
    private interface Column {
        double at(int x, int z);
    }

    private final PallField pall;
    private final double cx, cz, groundY;
    /** Полсторона карты и клетка, блоки. */
    private double half, cell;
    /**
     * Высота клеток над землёй под эпицентром, блоки, и доля воды в них 0..1; {@link Float#NaN} — не знаем (высоту и воду
     * клиент знает порознь: плитка карты бывает без глубины воды).
     */
    private float[] known = new float[SIDE * SIDE], knownWet = new float[SIDE * SIDE];
    /** Заполненная карта (для шейдера): по клеткам, x быстрее, — высота над землёй под эпицентром (блоки) и доля воды. */
    private final float[] map = new float[2 * SIDE * SIDE];
    /** Низ и верх заполненной карты, блоки над землёй под эпицентром. */
    private float lo, hi;
    /** Вода под эпицентром, с которой карта заполнена ({@link PallField#under}). */
    private float filledUnder;
    /** Есть ли в заполненной карте вода и суша. */
    private boolean water, land;
    private int next, version, sinceFill;
    private boolean dirty;
    /** Уменьшенные копии для {@link #fill} (общие: карты заполняет один поток — тик клиента). */
    private static final float[][] V = new float[LEVELS][], W = new float[LEVELS][];
    /**
     * Для {@link #nearest}: источник ближайшего известного по клеткам (индекс клетки или {@link #EPICENTRE}) и квадрат
     * расстояния до него, клетки (общие, как {@link #V}).
     */
    private static final int[] SOURCE = new int[SIDE * SIDE];
    private static final float[] DIST = new float[SIDE * SIDE];
    /** Источник «эпицентр» — середина карты ({@link #nearest}). */
    private static final int EPICENTRE = SIDE * SIDE;

    PallGround(Detonation d, PallField pall) {
        this.pall = pall;
        this.cx = d.burst().x;
        this.cz = d.burst().z;
        this.groundY = d.groundY();
        Arrays.fill(known, Float.NaN);
        Arrays.fill(knownWet, Float.NaN);
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

    /** Есть ли под пеленой вода (хоть где-то в заполненной карте): иначе шейдер воду не читает. */
    boolean hasWater() {
        return water;
    }

    /** Есть ли под пеленой суша: иначе пыли и мглы нет (подрыв посреди моря). */
    boolean hasLand() {
        return land;
    }

    /**
     * Тик клиента: рамка пелены к {@code ticks} (тики после подрыва) выросла за карту — переложить; следующие клетки —
     * по {@code heights} и {@code water} (null — не знать: измерение с потолком; карта остаётся как есть); узнали новое —
     * заполнить заново, но не чаще раза в {@link #FILL_TICKS} тиков; вода под эпицентром решилась иначе — сразу.
     */
    void survey(double ticks, @Nullable Sightline.Heights heights, @Nullable FarTerrain.Water water) {
        double extent = pall.extent(ticks);
        // карта и её полсторона уходят в кадр вместе: переложенная — сразу заполнена
        boolean grew = extent > half;
        if (grew) resize(extent * GROW);
        if (heights != null || water != null) {
            for (int n = 0; n < PER_TICK; n++) {
                int i = ORDER[next];
                next = (next + 1) % ORDER.length;
                if (heights != null) {
                    double h = measure(i, heights::at);
                    if (!Double.isNaN(h)) dirty |= learn(known, i, (float) (h - groundY), 0.5f);
                }
                if (water != null) {
                    double w = measure(i, water::at);
                    if (!Double.isNaN(w)) dirty |= learn(knownWet, i, (float) w, 0.01f);
                }
            }
        }
        if (grew || (float) pall.under() != filledUnder || ++sinceFill >= FILL_TICKS && dirty) fill();
    }

    /** Клетка i теперь {@code v}: новое (не знали или разница больше {@code tolerance}) — true. */
    private static boolean learn(float[] cells, int i, float v, float tolerance) {
        if (!Float.isNaN(cells[i]) && Math.abs(cells[i] - v) <= tolerance) return false;
        cells[i] = v;
        return true;
    }

    /** Среднее известного в клетке i из четырёх колонок (по четвертям); NaN — ни одной не знаем. */
    private double measure(int i, Column column) {
        double x0 = cx - half + (i % SIDE + 0.25) * cell, z0 = cz - half + (i / SIDE + 0.25) * cell;
        double sum = 0;
        int n = 0;
        for (int k = 0; k < 4; k++) {
            double h = column.at(Mth.floor(x0 + (k & 1) * 0.5 * cell), Mth.floor(z0 + (k >> 1) * 0.5 * cell));
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
            known = moved(known, h, c);
            knownWet = moved(knownWet, h, c);
            next = 0;
        }
        half = h;
        cell = c;
    }

    /** Известное {@code was} — на карту с полстороной {@code h} и клеткой {@code c}. */
    private float[] moved(float[] was, double h, double c) {
        float[] now = new float[SIDE * SIDE];
        for (int j = 0; j < SIDE; j++) {
            for (int i = 0; i < SIDE; i++) {
                int oi = Mth.floor((-h + (i + 0.5) * c + half) / cell), oj = Mth.floor((-h + (j + 0.5) * c + half) / cell);
                now[j * SIDE + i] = oi < 0 || oj < 0 || oi >= SIDE || oj >= SIDE ? Float.NaN : was[oj * SIDE + oi];
            }
        }
        return now;
    }

    /**
     * Заполнить карту: высоты — средним известного вокруг ({@link #pullPush}; под эпицентром — его земля, 0), воду —
     * ближайшим известным ({@link #nearest}; эпицентр — {@link PallField#under}).
     */
    void fill() {
        float under = (float) pall.under();
        pullPush(known, 0);
        float a = Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (int i = 0; i < SIDE * SIDE; i++) {
            float h = V[0][i];
            map[2 * i] = h;
            a = Math.min(a, h);
            b = Math.max(b, h);
        }
        lo = a;
        hi = b;
        nearest(knownWet, under);
        float most = 0, least = 1;
        for (int i = 0; i < SIDE * SIDE; i++) {
            float w = Mth.clamp(map[2 * i + 1], 0, 1);
            map[2 * i + 1] = w;
            most = Math.max(most, w);
            least = Math.min(least, w);
        }
        water = most > TRACE;
        land = least < 1 - TRACE;
        filledUnder = under;
        version++;
        sinceFill = 0;
        dirty = false;
    }

    /**
     * Заполнить {@code cells} (NaN — неизвестно) в {@code V[0]} (pull-push): уменьшенные копии — средние известного по
     * числу клеток (вес копии — сколько известного под ней), потом сверху вниз — неизвестное и полуизвестное (вес меньше 1)
     * берёт копию крупнее (билинейно, без ступенек). Под эпицентром известно всегда — {@code centre} в четырёх клетках
     * вокруг него весом одной клетки; не знаем ничего — всюду {@code centre}.
     */
    private static void pullPush(float[] cells, float centre) {
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
            boolean has = !Float.isNaN(cells[i]);
            v[0][i] = has ? cells[i] : 0;
            w[0][i] = has ? 1 : 0;
        }
        int mid = SIDE / 2;
        for (int k = 0; k < 4; k++) {
            int i = (mid - 1 + (k >> 1)) * SIDE + mid - 1 + (k & 1);
            if (w[0][i] == 0) {
                v[0][i] = centre;
                w[0][i] = 0.25f;
            }
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
                    float up = bilinear(v[l + 1], n / 2, 1, 0, (i + 0.5f) / 2 - 0.5f, (j + 0.5f) / 2 - 0.5f);
                    v[l][c] = a * v[l][c] + (1 - a) * up;
                }
            }
        }
    }

    /**
     * Заполнить {@code cells} (NaN — неизвестно) в канал воды карты ближайшим известным: известная клетка — как есть,
     * неизвестная — как ближайшая к ней известная или эпицентр (точка в середине карты, {@code centre}), что ближе, — по
     * расстоянию между серединами клеток. Два прохода с переносом источника (8SSEDT, Danielsson 1980; Leymarie &amp; Levine
     * 1992): клетка берёт источник соседа, если он к ней ближе её собственного, — расстояния почти евклидовы, 16 тысяч клеток
     * — доли миллисекунды раз в секунду. Граница между источниками разной воды — посередине между ними (на ней карта
     * переходит за клетку: билинейно в шейдере).
     */
    private void nearest(float[] cells, float centre) {
        int n = SIDE * SIDE;
        int[] src = SOURCE;
        float[] dist = DIST;
        for (int i = 0; i < n; i++) {
            boolean has = !Float.isNaN(cells[i]);
            src[i] = has ? i : EPICENTRE;
            dist[i] = has ? 0 : distance(i, EPICENTRE);
        }
        // вперёд: строка слева направо (соседи слева и сверху), потом справа налево (сосед справа); назад — зеркально
        for (int j = 0; j < SIDE; j++) {
            for (int i = 0; i < SIDE; i++) {
                int c = j * SIDE + i;
                if (i > 0) take(c, c - 1);
                if (j > 0) {
                    take(c, c - SIDE);
                    if (i > 0) take(c, c - SIDE - 1);
                    if (i < SIDE - 1) take(c, c - SIDE + 1);
                }
            }
            for (int i = SIDE - 2; i >= 0; i--) take(j * SIDE + i, j * SIDE + i + 1);
        }
        for (int j = SIDE - 1; j >= 0; j--) {
            for (int i = SIDE - 1; i >= 0; i--) {
                int c = j * SIDE + i;
                if (i < SIDE - 1) take(c, c + 1);
                if (j < SIDE - 1) {
                    take(c, c + SIDE);
                    if (i > 0) take(c, c + SIDE - 1);
                    if (i < SIDE - 1) take(c, c + SIDE + 1);
                }
            }
            for (int i = 1; i < SIDE; i++) take(j * SIDE + i, j * SIDE + i - 1);
        }
        for (int i = 0; i < n; i++) map[2 * i + 1] = src[i] == EPICENTRE ? centre : cells[src[i]];
    }

    /** Клетка c берёт источник соседа {@code from}, если он ей ближе её собственного ({@link #nearest}). */
    private static void take(int c, int from) {
        int s = SOURCE[from];
        if (s == SOURCE[c]) return;
        float d = distance(c, s);
        if (d < DIST[c]) {
            DIST[c] = d;
            SOURCE[c] = s;
        }
    }

    /** Квадрат расстояния от середины клетки c до источника s (клетки или эпицентра — середины карты), в клетках. */
    private static float distance(int c, int s) {
        float x = c % SIDE + 0.5f, y = c / SIDE + 0.5f;
        float sx = s == EPICENTRE ? SIDE / 2f : s % SIDE + 0.5f, sy = s == EPICENTRE ? SIDE / 2f : s / SIDE + 0.5f;
        return (x - sx) * (x - sx) + (y - sy) * (y - sy);
    }

    /**
     * Копия n × n в точке (x, y) в её клетках (середина клетки 0 — 0), края — как у края; клетка — {@code stride} чисел,
     * берётся {@code offset}-е.
     */
    private static float bilinear(float[] t, int n, int stride, int offset, float x, float y) {
        if (n == 1) return t[offset];
        x = Mth.clamp(x, 0, n - 1);
        y = Mth.clamp(y, 0, n - 1);
        int i = Math.min((int) x, n - 2), j = Math.min((int) y, n - 2);
        float fx = x - i, fy = y - j;
        float t00 = t[(j * n + i) * stride + offset], t10 = t[(j * n + i + 1) * stride + offset];
        float t01 = t[((j + 1) * n + i) * stride + offset], t11 = t[((j + 1) * n + i + 1) * stride + offset];
        float a = t00 + (t10 - t00) * fx;
        float b = t01 + (t11 - t01) * fx;
        return a + (b - a) * fy;
    }

    /** Высота заполненной карты над землёй под эпицентром в точке (x, z) мира, блоки — как в шейдере (билинейно, края — как у края). */
    double at(double x, double z) {
        return bilinear(map, SIDE, 2, 0, (float) ((x - cx + half) / cell - 0.5), (float) ((z - cz + half) / cell - 0.5));
    }

    /** Доля воды в заполненной карте в точке (x, z) мира 0..1 — как в шейдере ({@code pallWater}). */
    double water(double x, double z) {
        return bilinear(map, SIDE, 2, 1, (float) ((x - cx + half) / cell - 0.5), (float) ((z - cz + half) / cell - 0.5));
    }

    /** Средняя доля воды в заполненной карте (для строки сценария). */
    double waterShare() {
        double sum = 0;
        for (int i = 0; i < SIDE * SIDE; i++) sum += map[2 * i + 1];
        return sum / (SIDE * SIDE);
    }

    /** Низ и верх карты над землёй под эпицентром, блоки (как в шейдере). */
    float low() {
        return lo;
    }

    float high() {
        return hi;
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
