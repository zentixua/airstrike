package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.util.noise.FastNoiseLite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Огненный шторм над горящим городом (план «ядерка уровня кино», шаг 4): что горит вдали и как — модель клиента без
 * мира и без пакета, как и пелена ({@link PallField}). Её рисует объём пелены ({@code fx/layer/PallPass}: столбы дыма,
 * зарево снизу, огненные смерчи) и пламя на поверхностях ({@code fx_pall_composite}).
 * <p>
 * <b>Источники.</b> Очаги поджига собраны в источники по сетке {@link #CELL} блоков. Доля горящего в клетке — по тому же
 * закону, что у руин и звука ({@link FireField}, {@code RuinPlanner.fire}): min(1, q/20), меньше там, где волна сбивает
 * огонь (от 2 psi), на {@link FireField#SHARE} места есть чему гореть; свет — не порогом, а с горючестью района: районы
 * (шум мира, пятна по {@link #DISTRICT_BLOCKS}, зерно — подрыва) горят по-разному, клетки внутри — тоже
 * (логнормальный разброс {@link #SIGMA_SPOT}), и край зоны поджига рваный. Сила клетки — сколько её площади горит
 * (0..несколько), время — приход волны к ней и разброс поджига до {@link #IGNITE_SPREAD_S}. Настоящих блоков огня у
 * сервера — не больше 20 000 на подрыв (их видит {@link FireWatch}), а визуально горит вся зона поджига: «живой огонь
 * ванили по всей карте» план не делает.
 * <p>
 * <b>Время.</b> По настоящим секундам после поджига клетки, как пелена: занимается за {@link #FLAME_RISE_S} с с долей
 * {@link #START} от полной, разгорается в шторм e-кратно за {@link #GROW_S} с, с {@link #PEAK_S} с (у каждой клетки
 * своё) стихает e-кратно за {@link #DECAY_S} с до тления {@link #EMBERS}, к утру — как мгла пелены ({@link PallField#end})
 * — гаснет за последнюю минуту. Дым живёт дольше пламени ({@link #SMOKE_EMBERS}). Всё — от возраста подрыва: повтор после
 * перемотки и вошедший позже видят то же.
 * <p>
 * <b>Карта.</b> Для видеокарты — текстура по сетке ({@link #fill}): R — пламя, G — дым столбов (сильные клетки — сильнее:
 * {@link #COLUMN_POWER}), B — дым × доля подъёма столба ({@link #COLUMN_RISE_S}), с уменьшенными копиями. Копии и есть
 * скопления источников: свет и дым на высоте h берутся с уровня, где клетка карты — ширина столба или зарева на этой
 * высоте (как деревья источников света), — сколько ни горит клеток, выборка на шаг луча одна. Ниже, пока столб уже
 * клетки ({@link #PILLAR}), у каждой клетки — свой столб на её месте (четыре ближние клетки на шаг луча): у земли столбы
 * стоят порознь и сливаются выше. Вода, лёд и снег, что знает клиент, не горят ({@link #survey}).
 * <p>
 * <b>Смерчи.</b> У самых сильных источников — несколько огненных смерчей ({@link Whirl}), редких и коротких: событие, а не
 * обои. Всё из зерна подрыва.
 * <p>
 * Сетка считается в фоне (десятки тысяч клеток с шумом), до готовности шторма нет.
 */
public final class FireStorm {
    /** Клетка источников, блоков. */
    static final double CELL = 32;
    /** Сторона карты не больше стольких клеток (у 15 кт воздушного — около 170). */
    static final int MAX_SIDE = 256;
    /** Районы горят по-разному: пятна такого размера, блоков (кварталы, парки, промзона). */
    static final double DISTRICT_BLOCKS = 360;
    /** Разброс горючести районов (σ логарифма) и клеток внутри района. */
    static final double SIGMA_DISTRICT = 0.75, SIGMA_SPOT = 0.65;
    /** Свет на земле с горючестью района: σ логарифма — край зоны поджига рваный, а не круг. */
    static final double SIGMA_EDGE = 0.35;
    /** Порог поджига ({@code RuinPlanner.fire}). */
    static final double FULL_Q = 20;
    /** Клетки загораются в течение стольких секунд после волны (большинство — сразу). */
    static final double IGNITE_SPREAD_S = 30;
    /** Пламя в клетке занимается за столько секунд — до доли {@link #START} от полной силы. */
    static final double FLAME_RISE_S = 12, START = 0.3;
    /** Разгорается в шторм e-кратно за столько секунд. */
    static final double GROW_S = 80;
    /** С этой секунды после поджига (±30 % по клетке) стихает e-кратно за {@link #DECAY_S} до тления {@link #EMBERS}. */
    static final double PEAK_S = 360, DECAY_S = 540, EMBERS = 0.2;
    /** Дым стихает слабее пламени: тлеющее дымит. */
    static final double SMOKE_EMBERS = 0.45;
    /** Подача дыма в столбы — сила клетки в этой степени: сильные очаги дают столбы, слабые — низкую мглу. */
    static final double COLUMN_POWER = 1.4;
    /** Столб от поджига встаёт до верха за столько секунд. */
    static final double COLUMN_RISE_S = 25;
    /** Каналов на клетку карты (RGBA). */
    static final int CHANNELS = 4;
    /** Карта — заново через столько тиков, пока где-то занимается огонь, и через столько — потом. */
    static final int FILL_FAST = 4, FILL_SLOW = 20;

    // ---------------------------------------------------------------- законы (общие с шейдером, pall.glsl)

    /** Снос столба ветром растёт с высотой: на высоте h — h·(1 + h/hb), в долях скорости ветра против подъёма. */
    static final double BEND_HEIGHT = 200;
    /**
     * Полуширина столба у земли — такая доля клетки, дальше растёт на {@link #SPREAD} блока за блок подъёма (вовлечение
     * воздуха): у земли столбы клеток стоят порознь, выше сливаются в общую мглу.
     */
    static final double PILLAR = 0.3, SPREAD = 0.22;
    /** Скорость подъёма дыма в столбе, м/с (в огненном шторме — десятки). */
    static final double UPDRAFT_MS = 25;

    /** Снос столба на высоте {@code h} над землёй, блоки на единицу отношения ветра к подъёму ({@code fireBend}). */
    static double bend(double h, double hb) {
        double x = Math.max(h, 0);
        return x * (1 + x / hb);
    }

    /** Полуширина столба на высоте {@code h}, блоки ({@code fireWidth}). */
    static double width(double h, double cell) {
        return PILLAR * cell + SPREAD * Math.max(h, 0);
    }

    /** Уровень карты, где клетка — ширина {@code w} ({@code fireLod}). */
    static double lod(double w, double cell) {
        return log2(Math.max(2 * w / cell, 1));
    }

    /** Уровень карты для зарева на высоте {@code h}: свет со всей площади шириной ~2h ({@code fireGlowLod}). */
    static double glowLod(double h, double cell) {
        return log2(Math.max(2 * Math.max(h, 6) / cell, 1));
    }

    /** Столб по высоте: от низин (до 40 блоков ниже земли под эпицентром) до верха, где растекается в мглу ({@code fireColumn}). */
    static double column(double h, double top) {
        return smooth(-40, 0, h) * (1 - smooth(0.65 * top, top, h));
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    // ---------------------------------------------------------------- время

    /** Пламя клетки через {@code ageS} секунд после её поджига, доля полной силы ({@code peakS} — её начало спада). */
    static double flame(double ageS, double peakS) {
        if (ageS <= 0) return 0;
        double grow = START + (1 - START) * (1 - Math.exp(-ageS / GROW_S));
        return smooth(0, FLAME_RISE_S, ageS) * grow * decay(ageS, peakS, EMBERS);
    }

    /** Дым клетки (подача в столб), доля полной. */
    static double smoke(double ageS, double peakS) {
        if (ageS <= 0) return 0;
        double grow = START + (1 - START) * (1 - Math.exp(-ageS / GROW_S));
        return smooth(0, 1.5 * FLAME_RISE_S, ageS) * grow * decay(ageS, peakS, SMOKE_EMBERS);
    }

    private static double decay(double ageS, double peakS, double floor) {
        return ageS <= peakS ? 1 : floor + (1 - floor) * Math.exp(-(ageS - peakS) / DECAY_S);
    }

    // ---------------------------------------------------------------- сетка

    /** Огненный смерч: где, когда, какой (всё — блоки и секунды после подрыва). */
    public record Whirl(double x, double z, double startS, double lengthS, double radius, double height, int spin, double driftDir, double phase) {
        /** Сила 0..1 через {@code seconds} после подрыва: встаёт за 8 с, держится, опадает за 12 с. */
        public double strength(double seconds) {
            double age = seconds - startS;
            if (age <= 0 || age >= lengthS) return 0;
            return smooth(0, 8, age) * (1 - smooth(lengthS - 12, lengthS, age));
        }

        /** Где основание через {@code seconds} после подрыва: смерч бредёт по пожару (1,5 блока/с). */
        public double baseX(double seconds) {
            return x + Math.cos(driftDir) * DRIFT * Math.max(0, seconds - startS);
        }

        public double baseZ(double seconds) {
            return z + Math.sin(driftDir) * DRIFT * Math.max(0, seconds - startS);
        }

        static final double DRIFT = 1.5;
    }

    /** Сетка источников: клетки зоны (номер в карте), сила, поджиг (тики после подрыва), начало спада (с). */
    record Grid(int side, double cell, int[] index, float[] strength, float[] column, float[] start, float[] peak, double total,
                List<Whirl> whirls) {
        /** Последний поджиг, тики после подрыва. */
        double lastStart() {
            double last = 0;
            for (float s : start) last = Math.max(last, s);
            return last;
        }
    }

    final Detonation d;
    private final PallField pall;
    @Nullable
    private CompletableFuture<Grid> building;
    @Nullable
    private Grid grid;
    private boolean failed;
    /** Карта последнего {@link #fill}: RGBA по клеткам, x быстрее; номер версии растёт с каждым заполнением. */
    private float[] map = new float[0];
    private int version;
    /** Сколько горит в последнем заполнении (сумма пламени по клеткам). */
    private double burning;
    private double filledAt = Double.NaN, lastStart = Double.NaN;
    /** Доверие модели огня (пожары у сервера включены): 0..1, пишет тик клиента ({@link #trust}, плавно). */
    private double gate;
    /**
     * Сколько горит в своих чанках против модели там же, пока огонь молод ({@code NukeAfter}): 1 — как в модели, 0 —
     * сервер не жжёт или погасили. Живёт со штормом и после перемотки повтора: огонь, сверенный молодым, позже не
     * сверить — ванильный прогорает по-своему, а шторм горит до утра.
     */
    private double seen = 1;
    /** Верх рельефа под смерчами, блоки (NaN — не замеряли: тогда земля под эпицентром); пишет тик клиента. */
    private double[] whirlGround = new double[0];
    /**
     * Доля клетки, где есть чему гореть, по рельефу у клиента ({@link #survey}): вода, лёд и снег не горят; где не знаем
     * (дальше прорисовки без карты рельефа) — как под эпицентром. По номеру клетки зоны, как у сетки.
     */
    private float[] dry = new float[0];
    /** Клетку хоть раз видели ({@link #survey}): ушёл игрок, чанки выгрузились — она остаётся такой, какой её видели. */
    private boolean[] surveyed = new boolean[0];
    private int surveyAt;
    /** Сухость менялась после последнего заполнения карты: заполнить снова не позже чем через {@link #FILL_FAST} тиков. */
    private boolean dirty;

    FireStorm(Detonation d, PallField pall) {
        this.d = d;
        this.pall = pall;
        this.gate = d.fires() ? 1 : 0;
    }

    /** Сетка готова (в первый раз — запуск счёта в фоне); null — ещё нет или шторма нет (не горит ничего). */
    @Nullable
    Grid grid() {
        if (grid != null || failed) return grid;
        if (building == null) building = CompletableFuture.supplyAsync(() -> build(d, pall.fireReach()));
        if (!building.isDone()) return null;
        try {
            grid = building.join();
        } catch (RuntimeException ex) {
            failed = true;
            Incoming.LOG.error("Nuclear firestorm: fire sources of strike #{} failed, no firestorm", d.id(), ex);
            return null;
        }
        if (grid.index.length == 0) failed = true;
        return grid;
    }

    /** Сколько всего горит (сумма сил клеток) — для строки сценария; 0 — сетка не готова. */
    double total() {
        Grid g = grid;
        return g == null ? 0 : g.total;
    }

    /** Клеток-источников (0 — сетка не готова или не горит ничего). */
    int cells() {
        Grid g = grid;
        return g == null ? 0 : g.index.length;
    }

    /** Сколько горит сейчас (сумма пламени по клеткам последней карты) — для строки сценария. */
    double burning() {
        return burning;
    }

    /** Среднее пламя по зоне пожаров в последней карте, доля полной силы (0 — сетка не готова или не горит ничего). */
    double meanFlame() {
        int n = cells();
        return n == 0 ? 0 : burning / n;
    }

    /** Смерчи подрыва (пусто — сетка не готова). */
    public List<Whirl> whirls() {
        Grid g = grid;
        return g == null ? List.of() : g.whirls;
    }

    /** Сверка с огнём в своих чанках, пока он молод (пишет {@code NukeAfter}). */
    void seen(double trust) {
        seen = trust;
    }

    /**
     * Горят ли пожары у сервера на деле, 0..1: нет, если сервер их не зажигает ({@link Detonation#fires}) или в своих
     * чанках, где модель ждёт огня, его не было, пока он молод.
     */
    double trust() {
        return d.fires() ? Math.min(1, seen / 0.3) : 0;
    }

    /** Доверие модели, к которому плавно идёт тик клиента ({@link #trust}). */
    void gate(double g) {
        gate = g;
    }

    double gate() {
        return gate;
    }

    /**
     * Тик клиента: верх рельефа под смерчами, что идут или вот-вот встанут ({@code seconds} — после подрыва), — в пяти
     * точках по ядру, как пыль у земли ({@code CloudPuffs.track}): смерч бредёт по крышам и склонам, а не по высоте
     * эпицентра. Где рельефа не знать — прежняя высота.
     */
    void track(double seconds, Sightline.Heights heights) {
        Grid g = grid;
        if (g == null) return;
        if (whirlGround.length != g.whirls.size()) {
            whirlGround = new double[g.whirls.size()];
            Arrays.fill(whirlGround, Double.NaN);
        }
        for (int i = 0; i < whirlGround.length; i++) {
            Whirl w = g.whirls.get(i);
            if (seconds < w.startS() - 10 || seconds > w.startS() + w.lengthS()) continue;
            double x = w.baseX(seconds), z = w.baseZ(seconds), sum = 0;
            int n = 0;
            for (int k = 0; k < 5; k++) {
                double ox = k == 1 ? w.radius() : k == 2 ? -w.radius() : 0, oz = k == 3 ? w.radius() : k == 4 ? -w.radius() : 0;
                double h = heights.at(Mth.floor(x + ox), Mth.floor(z + oz));
                if (Double.isNaN(h)) continue;
                sum += h;
                n++;
            }
            if (n == 0) continue;
            double h = sum / n;
            // плавно: смерч не прыгает с крыши на улицу
            whirlGround[i] = Double.isNaN(whirlGround[i]) ? h : whirlGround[i] + 0.2 * (h - whirlGround[i]);
        }
    }

    /** Что знает клиент о поверхности в колонке. */
    @FunctionalInterface
    interface Surface {
        /** −1 — не знаем, 0 — есть чему гореть, 1 — вода, лёд или снег. */
        int wet(int x, int z);
    }

    /** Клеток зоны за тик {@link #survey}: у 15 кт вся зона — за 7 секунд. */
    static final int SURVEY_CELLS = 128;
    /** Точек на клетку по стороне (3 × 3). */
    static final int SURVEY_POINTS = 3;

    /**
     * Тик клиента: доля сухой земли в следующих {@link #SURVEY_CELLS} клетках зоны по кругу — по точкам 3 × 3 в клетке,
     * что знает клиент ({@code surface}: свои чанки и плитки карты рельефа). Модель огня рельефа не знает, а горящее море
     * или ледник видно сразу. Где не знаем ничего и не знали, клетка — как под эпицентром ({@code unknownWet}: доля воды там,
     * {@code NukeWater.under}, у подрыва над морем — и догадка по высоте земли): подрыв над морем вдали иначе жёг бы море.
     */
    void survey(Surface surface, double unknownWet) {
        Grid g = grid;
        if (g == null || g.index.length == 0) return;
        if (dry.length != g.index.length) {
            dry = new float[g.index.length];
            Arrays.fill(dry, (float) (1 - unknownWet));
            surveyed = new boolean[g.index.length];
            surveyAt = 0;
            // карта, заполненная до первого обхода, считала всё сушей: заполнить заново сразу
            filledAt = Double.NaN;
        }
        double half = g.side * g.cell / 2, step = g.cell / SURVEY_POINTS;
        Vec3 b = d.burst();
        int n = Math.min(SURVEY_CELLS, g.index.length);
        for (int s = 0; s < n; s++) {
            int k = surveyAt;
            surveyAt = (surveyAt + 1) % g.index.length;
            int c = g.index[k];
            double x0 = b.x + (c % g.side) * g.cell - half, z0 = b.z + (double) (c / g.side) * g.cell - half;
            int known = 0, wet = 0;
            for (int j = 0; j < SURVEY_POINTS; j++) {
                for (int i = 0; i < SURVEY_POINTS; i++) {
                    int w = surface.wet(Mth.floor(x0 + (i + 0.5) * step), Mth.floor(z0 + (j + 0.5) * step));
                    if (w < 0) continue;
                    known++;
                    wet += w;
                }
            }
            if (known == 0 && surveyed[k]) continue;
            surveyed[k] |= known > 0;
            float v = known == 0 ? (float) (1 - unknownWet) : 1 - (float) wet / known;
            if (v != dry[k]) {
                dry[k] = v;
                dirty = true;
            }
        }
    }

    /** Доля сухой земли в клетке под точкой (блоки мира); вне зоны пожаров — 1. */
    double dryAt(double x, double z) {
        Grid g = grid;
        if (g == null || dry.length != g.index.length) return 1;
        double half = g.side * g.cell / 2;
        int i = Mth.floor((x - d.burst().x + half) / g.cell), j = Mth.floor((z - d.burst().z + half) / g.cell);
        if (i < 0 || j < 0 || i >= g.side || j >= g.side) return 1;
        int k = Arrays.binarySearch(g.index, j * g.side + i);
        return k < 0 ? 1 : dry[k];
    }

    /** Земля под смерчем {@code i}, блоки (не замеряли — под эпицентром). */
    double whirlGround(int i) {
        return i < whirlGround.length && !Double.isNaN(whirlGround[i]) ? whirlGround[i] : d.groundY();
    }

    /** Половина стороны карты, блоки (0 — сетки нет). */
    double half() {
        Grid g = grid;
        return g == null ? 0 : g.side * g.cell / 2;
    }

    int side() {
        Grid g = grid;
        return g == null ? 0 : g.side;
    }

    double cell() {
        Grid g = grid;
        return g == null ? CELL : g.cell;
    }

    float[] map() {
        return map;
    }

    int version() {
        return version;
    }

    /**
     * Карта на момент {@code ticks} после подрыва (тики клиента), если пора: раз в {@link #FILL_FAST} тиков, пока где-то
     * занимается огонь и встают столбы, потом — раз в {@link #FILL_SLOW}; после перемотки (время назад) — сразу. true —
     * карта новая ({@link #map}, {@link #version}).
     */
    boolean refresh(double ticks) {
        Grid g = grid();
        if (g == null) return false;
        if (!Double.isNaN(filledAt) && ticks >= filledAt) {
            double every = young(g, ticks) || dirty ? FILL_FAST : FILL_SLOW;
            if (ticks - filledAt < every) return false;
        }
        if (map.length != g.side * g.side * CHANNELS) map = new float[g.side * g.side * CHANNELS];
        fill(g, ticks, pall.end(), dry.length == g.index.length ? dry : null, map);
        dirty = false;
        double sum = 0;
        for (int i = 0; i < map.length; i += CHANNELS) sum += map[i];
        burning = sum;
        filledAt = ticks;
        version++;
        return true;
    }

    /** Где-то огонь ещё занимается или встаёт столб: с последнего поджига прошло меньше двух подъёмов столба. */
    private boolean young(Grid g, double ticks) {
        if (Double.isNaN(lastStart)) lastStart = g.lastStart();
        return ticks < lastStart + 20 * 2 * Math.max(COLUMN_RISE_S, FLAME_RISE_S);
    }

    /** Утро: за последнюю минуту до конца мглы пелены всё гаснет. */
    static double morning(double ticks, double end) {
        return 1 - smooth(end - PallField.HAZE_FADE_TICKS, end, ticks);
    }

    /**
     * Карта момента {@code ticks} (утро — {@code end}) в {@code out}: RGBA по клеткам, вне зоны — нули; {@code dry} — доля
     * сухой земли по клеткам зоны ({@link #survey}; null — вся суша).
     */
    static void fill(Grid g, double ticks, double end, @Nullable float[] dry, float[] out) {
        Arrays.fill(out, 0);
        double night = morning(ticks, end);
        if (night <= 0) return;
        for (int k = 0; k < g.index.length; k++) {
            double age = (ticks - g.start[k]) / 20.0;
            if (age <= 0) continue;
            double peak = g.peak[k];
            int i = g.index[k] * CHANNELS;
            double wet = dry == null ? 1 : dry[k];
            double sm = g.column[k] * smoke(age, peak) * night * wet;
            out[i] = (float) (g.strength[k] * flame(age, peak) * night * wet);
            out[i + 1] = (float) sm;
            out[i + 2] = (float) (sm * Math.min(1, age / COLUMN_RISE_S));
        }
    }

    /**
     * Сетка источников подрыва: клетки в зоне поджига {@code reach} (блоки по земле от эпицентра), сила по закону руин
     * с горючестью района и клетки, поджиг — по приходу волны; смерчи. Чистая функция подрыва (зерно — его).
     */
    static Grid build(Detonation d, double reach) {
        if (reach <= 0) return new Grid(0, CELL, new int[0], new float[0], new float[0], new float[0], new float[0], 0, List.of());
        // клетка — 32 блока, у малого масштаба удара — мельче: в зоне не меньше 24 клеток поперёк
        double cell = Math.max(2, Math.min(CELL, 2 * reach / 24));
        int side = Math.min(MAX_SIDE, 2 * (int) Math.ceil(reach * 1.05 / cell) + 2);
        double half = side * cell / 2;
        double inner = d.surface() ? d.fireballRadius() * 0.8 : 0;
        long seed = d.seed() ^ 0xF12E5702L;
        FastNoiseLite noise = new FastNoiseLite((int) (seed ^ seed >>> 32)), warp = new FastNoiseLite((int) (seed * 31 + 11));
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        noise.SetFrequency((float) (1 / DISTRICT_BLOCKS));
        noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        noise.SetFractalOctaves(3);
        warp.SetDomainWarpType(FastNoiseLite.DomainWarpType.OpenSimplex2);
        warp.SetDomainWarpAmp((float) (DISTRICT_BLOCKS * 0.25));
        warp.SetFrequency((float) (1 / (DISTRICT_BLOCKS * 1.6)));
        Vec3 b = d.burst();
        int n = side * side;
        double[] raw = new double[n];
        boolean[] in = new boolean[n];
        double sum = 0, sum2 = 0;
        int count = 0;
        FastNoiseLite.Vector2 v = new FastNoiseLite.Vector2(0, 0);
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                double x = (i + 0.5) * cell - half, z = (j + 0.5) * cell - half;
                double r = Math.hypot(x, z);
                if (r > reach * 1.05 || r < inner) continue;
                v.x = b.x + x;
                v.y = b.z + z;
                warp.DomainWarp(v);
                double nv = noise.GetNoise(v.x, v.y);
                raw[j * side + i] = nv;
                in[j * side + i] = true;
                sum += nv;
                sum2 += nv * nv;
                count++;
            }
        }
        if (count == 0) return new Grid(side, cell, new int[0], new float[0], new float[0], new float[0], new float[0], 0, List.of());
        // районы — к среднему 0 и разбросу 1 по клеткам зоны: z ~ N(0, 1)
        double mean = sum / count, std = Math.sqrt(Math.max(1e-9, sum2 / count - mean * mean));
        int[] index = new int[count];
        float[] strength = new float[count], column = new float[count], start = new float[count], peak = new float[count];
        int k = 0;
        double total = 0;
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                int c = j * side + i;
                if (!in[c]) continue;
                double x = (i + 0.5) * cell - half, z = (j + 0.5) * cell - half;
                double r = Math.hypot(x, z);
                double district = (raw[c] - mean) / std;
                Vec3 p = new Vec3(b.x + x, d.groundY() + 1, b.z + z);
                double q = d.fluence(p) * Math.exp(SIGMA_EDGE * district - SIGMA_EDGE * SIGMA_EDGE / 2);
                if (q < ThermalModel.IGNITE) continue;
                // волна сбивает часть огня (у руин — половину от 2 psi): плавно, без кольца на карте
                double chance = Math.min(1, q / FULL_Q) * (1 - 0.5 * smooth(1.5, 2.5, d.psi(p)));
                double fuel = Math.exp(SIGMA_DISTRICT * district - SIGMA_DISTRICT * SIGMA_DISTRICT / 2);
                long h = hash(seed, i, j);
                double spot = Math.exp(SIGMA_SPOT * gauss(h) - SIGMA_SPOT * SIGMA_SPOT / 2);
                double s = FireField.SHARE * chance * fuel * spot;
                if (s < 0.02) continue;
                index[k] = c;
                strength[k] = (float) s;
                column[k] = (float) Math.pow(s, COLUMN_POWER);
                double u = unit(h >>> 17);
                start[k] = (float) (d.arrivalTicks(Math.hypot(r, b.y - d.groundY())) + 20 * IGNITE_SPREAD_S * u * u);
                peak[k] = (float) (PEAK_S * (0.7 + 0.6 * unit(h >>> 34)));
                total += s;
                k++;
            }
        }
        index = Arrays.copyOf(index, k);
        strength = Arrays.copyOf(strength, k);
        column = Arrays.copyOf(column, k);
        start = Arrays.copyOf(start, k);
        peak = Arrays.copyOf(peak, k);
        return new Grid(side, cell, index, strength, column, start, peak, total, whirls(d, seed, side, cell, half, reach, index, strength, start, total));
    }

    /** Смерчей на подрыв: от суммы сил (у 15 кт — три, у 1 кт — один), не больше {@link #MAX_WHIRLS}. */
    static final int MAX_WHIRLS = 4;
    /** Смерчи — не ближе стольких блоков друг к другу. */
    static final double WHIRL_GAP = 250;
    /** Первый смерч — через столько секунд после поджига его клетки, следующие — через {@link #WHIRL_EVERY_S}. */
    static final double WHIRL_FIRST_S = 150, WHIRL_EVERY_S = 140;

    /**
     * Смерчи у самых сильных клеток (не дальше 0,8 зоны, не ближе {@link #WHIRL_GAP} друг к другу): по одному на окно
     * в пару минут, когда шторм в силе, каждый — 45–90 с; радиус ядра по силе клетки, высота — 90–130 блоков.
     */
    private static List<Whirl> whirls(Detonation d, long seed, int side, double cell, double half, double reach, int[] index, float[] strength,
                                      float[] start, double total) {
        int n = (int) Math.max(1, Math.min(MAX_WHIRLS, Math.round(Math.sqrt(total) / 25)));
        Integer[] order = new Integer[index.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(strength[b], strength[a]));
        List<Whirl> out = new ArrayList<>();
        java.util.Random random = new java.util.Random(seed ^ 0x3A11L);
        for (int o = 0; o < order.length && out.size() < n; o++) {
            int k = order[o], c = index[k];
            double x = (c % side + 0.5) * cell - half, z = (c / side + 0.5) * cell - half;
            if (Math.hypot(x, z) > 0.8 * reach) continue;
            boolean near = false;
            for (Whirl w : out) near |= Math.hypot(w.x() - d.burst().x - x, w.z() - d.burst().z - z) < WHIRL_GAP * Math.min(1, reach / 1000);
            if (near) continue;
            int at = out.size();
            double startS = start[k] / 20.0 + WHIRL_FIRST_S + WHIRL_EVERY_S * at + 60 * random.nextDouble();
            double radius = Math.min(14, 6 + 3 * strength[k]) * Math.min(1, cell / CELL * 1.5);
            double height = (90 + 40 * random.nextDouble()) * Math.min(1, cell / CELL * 1.5);
            out.add(new Whirl(d.burst().x + x, d.burst().z + z, startS, 45 + 45 * random.nextDouble(), radius, height,
                    random.nextBoolean() ? 1 : -1, random.nextDouble() * 2 * Math.PI, random.nextDouble() * 100));
        }
        out.sort((a, b) -> Double.compare(a.startS(), b.startS()));
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- случайное

    private static long hash(long seed, int i, int j) {
        long h = seed ^ (i * 0x9E3779B97F4A7C15L) ^ (j * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    /** Число 0..1 из младших 17 бит. */
    private static double unit(long bits) {
        return ((bits & 0x1FFFF) + 0.5) / 0x20000;
    }

    /** Нормальное N(0, 1) из хеша (Бокс — Мюллер), в пределах ±3. */
    private static double gauss(long h) {
        double u1 = unit(h), u2 = unit(h >>> 20);
        return Math.max(-3, Math.min(3, Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2)));
    }

    static double smooth(double e0, double e1, double x) {
        double f = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return f * f * (3 - 2 * f);
    }
}
