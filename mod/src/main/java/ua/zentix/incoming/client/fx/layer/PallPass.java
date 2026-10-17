package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.nuclear.MushroomField;
import ua.zentix.incoming.compat.FlashbackReplay;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Пелена после ударной волны — объёмом, тремя проходами в слое эффектов (до квадратов частиц):
 * <ol>
 *     <li>{@code fx_pall} — луч на пиксель кадра пониженного разрешения (вдвое или вчетверо, {@link Quality}) по полю
 *     подрыва ({@code client/nuclear/PallField}: таблица по радиусу) × трёхмерный шум ({@link VolumeNoise}); начало шагов
 *     сдвинуто синим шумом ({@link BlueNoise}) и от кадра к кадру;</li>
 *     <li>{@code fx_pall_resolve} — накопление по кадрам: прошлое переносится по глубине мира за пикселем, зажато
 *     в пределы соседей (зерно уходит, след за движением — нет);</li>
 *     <li>{@code fx_pall_composite} — на кадр: свет пелены плюс мир за ней, ослабленный и в тени пелены (тень — по
 *     полю без шума в каждом пикселе); из пониженного разрешения — с весом по сходству расстояния до мира (края домов
 *     чёткие).</li>
 * </ol>
 * Квадраты слоя после этого рисуются со своей долей пелены перед ними ({@code fx.vsh}: те же поле и таблица, без
 * шума).
 * <p>
 * Над горящим городом в тех же проходах — огненный шторм ({@code client/nuclear/FireStorm}): у слота подрыва своя
 * карта огня ({@link Fire}, RGBA16F по клеткам источников с уменьшенными копиями — скопления источников света и дыма):
 * в лучах — столбы дыма и зарево снизу на пелене и дыме, огненные смерчи; на кадре — пламя на поверхностях города
 * (по расстоянию до мира за пикселем, и на LOD Distant Horizons); у квадратов слоя — свет пожаров снизу.
 * <p>
 * В тех же лучах — грибы кадра объёмом ({@code mushroom.glsl}, {@code client/nuclear/MushroomField}): таблица грибов
 * (RGBA32F, строка на гриб и строка света чёрного тела) — у лучей, накопления (перенос по расстоянию гриба) и квадратов
 * слоя (что гриб закрывает перед частицей). Гриб рисуется и без пелены, и при пелене, выключенной в настройке (тогда —
 * на ступени LOW): другого гриба нет.
 * <p>
 * Днём грибы кладут тень на мир: до лучей — карта тени ({@code fx_mush_shadow}, плитка
 * {@link MushroomField#SHADOW}² на гриб в плоскости поперёк солнца: доля света солнца, что гриб пропускает к трём
 * высотам, — прямым пучком и рассеянным вперёд), на кадре — выборка из неё в каждом пикселе мира ({@code fx_pall_composite}).
 * <p>
 * Своих текстур — три кадра пониженного разрешения (RGBA16F), таблица 128×4, таблица грибов 256×5 (RGBA32F), карта тени
 * грибов 128×512 (RGBA16F, 0,5 МБ), шум 64³ (RG8) и 64² (R8): всё вместе меньше 2,5 МБ на 1080p; карты огня — до 0,7 МБ на слот (сторона до 256 клеток), карты рельефа и воды под пеленой — 64 КБ на слот. Шум считается в фоне при первом
 * подрыве (около секунды); пока он не готов, пелены нет.
 * <p>
 * Подробность сама держит бюджет ({@link Detail}): время проходов на видеокарте меряют метки времени OpenGL, читаемые
 * через кадры (без ожидания), и пока оно дольше {@link Detail#BUDGET_MS} мс, кадр лучей мельчает и шагов меньше
 * ({@link #LADDER}, не ниже LOW); есть запас — подробность возвращается к выбранной в настройке. В сценарии клиента
 * и пока Flashback выводит видео кадры не в реальном времени: медленный кадр там ничего не стоит, и подробность —
 * всегда из настройки ({@link Detail#pin}). Строка {@code SCENARIO pall} — {@link #describe}.
 */
public final class PallPass {
    /** Узлов таблицы по радиусу (ширина текстуры). */
    public static final int WIDTH = 128;
    /** Строк таблицы: пыль и мгла двух подрывов. */
    public static final int ROWS = 4;
    /**
     * Блок текстуры трёхмерного шума: за двенадцатью двумерными, которые привязывает {@code ShaderInstance}
     * ({@code Sampler0}–{@code Sampler11}).
     */
    private static final int NOISE_UNIT = 12;
    /** Перенос накопленного считается не дальше стольких блоков (небо за пеленой). */
    private static final float REPROJECT_MAX = 600;
    /** Глаз сдвинулся за кадр дальше — накопленное не годится (телепорт, камера снаряда). */
    private static final double JUMP = 30;

    /** Качество пелены из настройки — самая подробная ступень {@link #LADDER}: во сколько раз кадр мельче полного и шагов на луч. */
    public enum Quality {
        OFF(0, 0), LOW(4, 20), MEDIUM(2, 28), HIGH(2, 48);

        final int factor, steps;

        Quality(int factor, int steps) {
            this.factor = factor;
            this.steps = steps;
        }

        /** Ступень {@link #LADDER} этого качества (OFF — −1). */
        int step() {
            for (int i = 0; i < LADDER.length; i++) if (LADDER[i].factor == factor && LADDER[i].steps == steps) return i;
            return -1;
        }
    }

    /** Ступень подробности: во сколько раз кадр лучей мельче полного и шагов на луч. */
    record Step(int factor, int steps) {
        /** Цена кадра в долях: лучей — пиксели / фактор², у каждого — шаги. */
        double cost() {
            return steps / (double) (factor * factor);
        }
    }

    /**
     * Ступени подробности от HIGH до LOW: каждая следующая — от 0,5 до 0,85 цены прошлой. Шаги меняются без нового
     * кадра, фактор — с новыми кадрами пониженного разрешения (накопленное сначала).
     */
    static final Step[] LADDER = {new Step(2, 48), new Step(2, 36), new Step(2, 28), new Step(2, 22), new Step(3, 26),
            new Step(3, 20), new Step(4, 24), new Step(4, 20)};

    /**
     * Ступень подробности по времени проходов на видеокарте: дольше бюджета в среднем за полсекунды (и не меньше
     * четырёх замеров) — сразу на ступень, что по цене в него влезет; запас — на ступень вверх, если и с ней по цене
     * выйдет не больше {@link #UP_SHARE} бюджета два окна подряд и после прошлой смены прошло {@link #HOLD_NS} (после
     * подъёма, за которым сразу пришлось опуститься, — вчетверо дольше). Не выше выбранного в настройке и не ниже LOW;
     * прибитая ({@link #pin}) — ступень настройки, замеры её не трогают. Без мира и OpenGL — юнит-тест.
     */
    static final class Detail {
        /** Бюджет пелены на кадр на видеокарте, мс. */
        static final double BUDGET_MS = 2;
        static final double UP_SHARE = 0.7;
        static final long WINDOW_NS = 500_000_000L, HOLD_NS = 3_000_000_000L;
        static final int MIN_SAMPLES = 4, CALM_WINDOWS = 2;

        /** Ступень сейчас и самая подробная (из настройки); −1 — ещё не задана. */
        int level = -1, ceiling = -1;
        /** Номер ступени по счёту смен: замеры, снятые до смены, не считаются. */
        int generation;
        /** Сколько раз ступень менялась по замерам. */
        int changes;
        /** Среднее последнего окна, мс. */
        double lastMean;
        /** Ступень прибита к настройке ({@link #pin}). */
        boolean pinned;
        private double sum;
        private int samples, calm;
        private long windowStart, holdUntil, raisedAt = Long.MIN_VALUE;
        private boolean skip;

        /** Потолок из настройки: сменился — сразу на него. */
        void ceiling(int c) {
            if (c == ceiling) return;
            ceiling = c;
            set(c);
        }

        /**
         * Прибить ступень к настройке или отпустить: кадры не в реальном времени (сценарий клиента, вывод видео
         * Flashback) — медленный кадр не должен снижать подробность. true — сменилось (ступень — потолок, замеры —
         * заново).
         */
        boolean pin(boolean on) {
            if (on == pinned) return false;
            pinned = on;
            calm = 0;
            if (ceiling >= 0) set(ceiling);
            return true;
        }

        /**
         * Замеры до этого — не в счёт (пелены не было в кадре, сменился размер кадра): пары меток в полёте сняты
         * на прежнем поколении, первый кадр после — с холодными кэшами.
         */
        void restart() {
            calm = 0;
            if (ceiling >= 0) set(level);
        }

        /** Замер кадра, снятый на ступени поколения {@code gen}, в момент {@code now} (нс); true — ступень сменилась. */
        boolean sample(double ms, int gen, long now) {
            if (gen != generation || ceiling < 0 || pinned) return false;
            // первый кадр ступени — с новыми кадрами и холодными кэшами
            if (skip) {
                skip = false;
                return false;
            }
            if (samples == 0) windowStart = now;
            sum += ms;
            samples++;
            if (samples < MIN_SAMPLES || now - windowStart < WINDOW_NS) return false;
            double mean = sum / samples;
            lastMean = mean;
            sum = 0;
            samples = 0;
            int floor = LADDER.length - 1;
            if (mean > BUDGET_MS && level < floor) {
                int to = level + 1;
                while (to < floor && mean * LADDER[to].cost() / LADDER[level].cost() > BUDGET_MS) to++;
                // подняли, а ступень не по силам — следующий подъём не скоро
                holdUntil = now + (raisedAt != Long.MIN_VALUE && now - raisedAt < 2 * HOLD_NS ? 4 * HOLD_NS : HOLD_NS);
                calm = 0;
                set(to);
                changes++;
                return true;
            }
            if (level > ceiling && now >= holdUntil && mean * LADDER[level - 1].cost() / LADDER[level].cost() <= UP_SHARE * BUDGET_MS) {
                if (++calm >= CALM_WINDOWS) {
                    calm = 0;
                    holdUntil = now + HOLD_NS;
                    raisedAt = now;
                    set(level - 1);
                    changes++;
                    return true;
                }
            } else {
                calm = 0;
            }
            return false;
        }

        private void set(int to) {
            level = Math.max(ceiling, Math.min(LADDER.length - 1, to));
            generation++;
            skip = true;
            sum = 0;
            samples = 0;
        }
    }

    /** Огненный шторм слота ({@code client/nuclear/FireStorm}): числа для шейдера и карта огня. */
    public static final class Fire {
        /** Слот огня ({@code pall.glsl}); нули — у слота шторма нет. */
        public final Matrix4f params = new Matrix4f().zero();
        /** Карта огня: RGBA по клеткам, сторона {@link #side}, x быстрее; null — шторма нет. */
        @Nullable
        public float[] map;
        public int side;
        /** Чья карта и какое её заполнение: текстура грузится заново, только когда они другие. */
        @Nullable
        public Object source;
        public int version;

        public void clear() {
            params.zero();
            map = null;
            source = null;
            side = version = 0;
        }

        boolean on() {
            return map != null && side > 0 && params.m00() > 0;
        }
    }

    /**
     * Рельеф и вода под пеленой слота ({@code client/nuclear/PallGround}): где кончается луч, за которым нет мира, и из
     * чего пелена (пыль над сушей, брызги над водой). Карта высот над землёй под эпицентром (R) и доли воды (G), RG16F с
     * билинейной выборкой, края — как у края.
     */
    public static final class Ground {
        /** Полсторона карты, низ и верх карты (над землёй под эпицентром), клетка — блоки ({@code pall.glsl}); нули — карты нет. */
        public final Vector4f params = new Vector4f();
        /** По клеткам высота и доля воды (два числа на клетку), сторона {@link #side}, x быстрее; null — карты нет. */
        @Nullable
        public float[] map;
        public int side;
        /** Чья карта и какое её заполнение: текстура грузится заново, только когда они другие. */
        @Nullable
        public Object source;
        public int version;

        public void clear() {
            params.zero();
            map = null;
            source = null;
            side = version = 0;
        }

        boolean on() {
            return map != null && side > 0 && params.x > 0;
        }
    }

    /** Пелена кадра — что её рисует ({@code client/nuclear/NukePall#frame}). */
    public static final class Frame {
        /** Слоты подрывов ({@code pall.glsl}): не больше двух самых заметных. */
        public final Matrix4f slot0 = new Matrix4f().zero(), slot1 = new Matrix4f().zero();
        /** Таблица поля: {@link #ROWS} строк по {@link #WIDTH} узлов, RGBA. */
        public final float[] table = new float[ROWS * WIDTH * 4];
        /** Рамка всех слотов от глаза, блоки. */
        public final Vector3f min = new Vector3f(), max = new Vector3f();
        /** Направление на солнце и его сила днём 0..1. */
        public final Vector4f sun = new Vector4f();
        /**
         * Свет солнца и неба — линейный (белая стена в полдень — 1, {@code client/nuclear/PallLight}); зарево пожаров под
         * мглой — на белой стене на экране, поверх {@link #base}.
         */
        public final Vector3f sunLight = new Vector3f(), skyLight = new Vector3f(), fireLight = new Vector3f();
        /** Свет неба кадра на экране — белая стена: днём 1, ночью — как у клубов стены пыли ({@code FarView.ambient}). */
        public float base = 1;
        /**
         * Свет стены пыли на пелене слотов ({@code client/nuclear/CloudPuffs#pallLight}): glow — свет шара сверху,
         * накал гриба, шесть радиусов шара и край зоны пожаров (блоки); hot — цвет накала (rgb) и высота шара над
         * землёй под эпицентром (блоки, w). Нули — без отсвета.
         */
        public final Vector4f glow0 = new Vector4f(), glow1 = new Vector4f(), hot0 = new Vector4f(), hot1 = new Vector4f();
        /** Сила отсвета стены на пыли при свете неба кадра ({@code CloudPuffs#glowOnSmoke}). */
        public float glowOnSmoke;
        /** Цвет тонкой пелены на экране (rgb) — для квадратов слоя перед частицами; w — 1, если пелена есть. */
        public final Vector4f ambient = new Vector4f();
        /** Дальше стольких блоков луч не идёт (в густой пыли у глаза — что за ней, всё равно не видно). */
        public float clip;
        public Quality quality = Quality.MEDIUM;
        /** Огненный шторм слотов. */
        public final Fire fire0 = new Fire(), fire1 = new Fire();
        /** Рамка шторма от глаза (столбы, зарево, смерчи), блоки: там шаги луча гуще; min > max — шторма нет. */
        public final Vector3f fireMin = new Vector3f(), fireMax = new Vector3f();
        /**
         * Огненные смерчи кадра (до четырёх, по столбцу на смерч): основание (x, z от глаза, земля от глаза по y) и радиус
         * ядра; высота, сила 0..1 (0 — смерча нет), секунды с его начала, закрутка ±1; наклон по ветру (x, z на блок
         * высоты), пламя, зерно.
         */
        public final Matrix4f whirlPos = new Matrix4f().zero(), whirlShape = new Matrix4f().zero(), whirlLean = new Matrix4f().zero();
        /** Рельеф и вода под пеленой слотов. */
        public final Ground ground0 = new Ground(), ground1 = new Ground();
        /**
         * Вода слотов ({@code pall.glsl}): радиус и верх базисной волны (блоки), её ослабление в самой густой части
         * (1/блок; нули — волны нет), 1 — под пеленой есть вода (шейдер читает её карту), 0 — нет.
         */
        public final Vector4f water0 = new Vector4f(), water1 = new Vector4f();
        /**
         * Стена пыли слотов ({@code client/nuclear/BlastWall#slot}, {@code pall.glsl}): голова-вал (нули — головы нет)
         * и облако Вильсона (z = 0 — облака нет).
         */
        public final Matrix4f wall0 = new Matrix4f().zero(), wall1 = new Matrix4f().zero();
        public final Vector4f wilson0 = new Vector4f(), wilson1 = new Vector4f();
        /**
         * Мир кадра кончается не ближе стольких блоков по земле от глаза (дальность прорисовки): луч в пустоту прошёл его,
         * ничего не задев, — рельеф под пеленой ({@link Ground}) режет его только дальше.
         */
        public float floorFrom;
        /**
         * Грибы кадра ({@code mushroom.glsl}): строки грибов таблицы {@link MushroomField#WIDTH} × {@link MushroomField#ROWS}
         * (строка света чёрного тела здесь не нужна — она в видеопамяти с начала) и их число.
         */
        public final float[] mushrooms = new float[MushroomField.WIDTH * MushroomField.ROWS * 4];
        public int mushroomCount;
        /** Во сколько раз ярче кажется свет при свете неба кадра ({@code Sight.adapted}): свечение грибов. */
        public float exposure = 1;
        /**
         * Огненные шары, что светят на мир кадра ({@code MushroomField.balls}): середины и радиусы, свет и ослабление
         * воздуха по столбцам; светит ли хоть один (иначе вывод на кадр их не считает и кадр не копирует).
         */
        public final Matrix4f balls = new Matrix4f().zero(), ballLight = new Matrix4f().zero();
        public boolean ballsLit;
        /** Свет открытого неба на белом блоке, как его рисует Minecraft (карта освещения: небо 15, блоки 0), на экране. */
        public final Vector3f openSky = new Vector3f(1);
        /**
         * Затенение граней блоков Minecraft ({@code ClientLevel.getShade}): верх, низ, север и юг, запад и восток. Оно уже
         * в пикселе мира, и вывод на кадр снимает его с цвета блока под светом шара.
         */
        public final Vector4f faceShade = new Vector4f(1, 0.5f, 0.8f, 0.6f);
    }

    @Nullable
    static ShaderInstance march, resolve, composite, shadow;
    private static final Frame EMPTY = new Frame();
    private static final Detail DETAIL = new Detail();
    /** Шум не вышел (ошибка в фоне): пелены нет до перезапуска. */
    private static boolean failed;

    private static int fullW, fullH, lowW, lowH, factor;
    /** Кадр луча и два накопленных (по очереди: прошлый и этот). */
    private static final int[] TEXTURES = {-1, -1, -1}, FBOS = {-1, -1, -1};
    private static int latest;
    private static int table = -1, noise = -1, blue = -1, mushrooms = -1;
    /** Карта тени грибов от солнца (плитка {@link MushroomField#SHADOW}² на гриб) и её кадр; −1 — не вышла, тени нет. */
    private static int shadowMap = -1, shadowFbo = -1;
    /** Копия кадра для света шаров на мире ({@link #copyFrame}) и её размер; −1 — ещё не нужна была. */
    private static int frameCopy = -1, copyW, copyH;
    /** Карты огня слотов (текстура, её сторона, чья и какое заполнение) и карты этого кадра (−1 — нет). */
    private static final int[] FIRE = {-1, -1}, FIRE_SIDE = new int[2], FIRE_VERSION = new int[2], FIRE_NOW = {-1, -1};
    private static final Object[] FIRE_SOURCE = new Object[2];
    /** Карты рельефа под пеленой слотов (текстура, сторона, чья и какое заполнение) и карты этого кадра (−1 — нет). */
    private static final int[] GROUND = {-1, -1}, GROUND_SIDE = new int[2], GROUND_VERSION = new int[2], GROUND_NOW = {-1, -1};
    private static final Object[] GROUND_SOURCE = new Object[2];
    @Nullable
    private static FloatBuffer groundBuffer;
    private static final Vector4f NO_GROUND = new Vector4f();
    @Nullable
    private static FloatBuffer fireBuffer;
    private static final Matrix4f NO_FIRE = new Matrix4f().zero();
    @Nullable
    private static CompletableFuture<byte[][]> textures;
    private static FloatBuffer tableBuffer, mushroomBuffer;
    private static boolean history;
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(), INVERSE = new Matrix4f(), PREVIOUS = new Matrix4f();
    private static final Vector3f FORWARD = new Vector3f();
    @Nullable
    private static Vec3 previousEye;
    private static long frame;
    private static final int[] VIEWPORT = new int[4];

    // время на видеокарте: метки времени (glQueryCounter) до и после проходов, по кольцу из RING пар — читаются через
    // кадры, когда готовы, без ожидания. Не запрос GL_TIME_ELAPSED: он у OpenGL один на всех, а Minecraft держит его на
    // весь кадр, пока на экране F3 с загрузкой видеокарты (TimerQuery), — вложенный не начался бы
    private static final int RING = 4;
    private static final int[] QUERIES = new int[2 * RING];
    /** Поколение ступени ({@link Detail#generation}), на которой снята пара; −1 — пара свободна. */
    private static final int[] ISSUED = new int[RING];
    private static int ring;
    /** Метки времени: 0 — не проверяли, 1 — есть, −1 — нет (тогда подробность — из настройки). */
    private static int timer;
    /** Сценарий клиента ({@code tools/client_scenario.sh}): кадры не в реальном времени, подробность — из настройки. */
    private static final boolean SCENARIO = System.getProperty("incoming.scenario") != null;
    private static double gpuSum, gpuMax, lastGpuMean, lastGpuMax;
    private static int gpuFrames, drawn, lastDrawn;

    private PallPass() {}

    /** Пелена без подрывов: квадраты слоя её не берут. */
    public static Frame empty() {
        return EMPTY;
    }

    /**
     * Пелену можно рисовать: её шейдеры собрались и шум не сломался (до его готовности — тоже да: он ещё будет). Нет —
     * голову стены у глаза показывает пелена на экране ({@code NukeDust.veil}).
     */
    public static boolean available() {
        return !failed && march != null && resolve != null && composite != null;
    }

    /** Шум готовится в фоне со входа в мир ({@code IncomingClient}; и с первого подрыва): до готовности пелены и гриба нет. */
    public static void prepare() {
        if (textures == null) {
            textures = CompletableFuture.supplyAsync(() -> new byte[][]{
                    VolumeNoise.generate(VolumeNoise.SIZE, 0x9A11L), BlueNoise.generate(BlueNoise.SIZE, 0xB1E5L)});
        }
    }

    /** Таблица поля для квадратов слоя ({@code Sampler5} шейдера {@code fx}); −1 — пелены в кадре нет. */
    static int table() {
        return table;
    }

    /** Таблица грибов ({@code Sampler11} шейдера {@code fx}): в кадре, где пелена нарисована, — грибы этого кадра. */
    static int mushroomMap() {
        return mushrooms;
    }

    /** Карта огня слота {@code k} этого кадра ({@code Sampler7}/{@code Sampler8} шейдера {@code fx}); −1 — нет. */
    static int fireMap(int k) {
        return FIRE_NOW[k];
    }

    /** Карта рельефа и воды слота {@code k} этого кадра ({@code Sampler9}/{@code Sampler10} шейдера {@code fx}); −1 — нет. */
    static int groundMap(int k) {
        return GROUND_NOW[k];
    }

    /** Числа карты рельефа слота {@code k} кадра {@code f} ({@code pall.glsl}); карты нет — нули. */
    static Vector4f groundParams(Frame f, int k) {
        return GROUND_NOW[k] < 0 ? NO_GROUND : k == 0 ? f.ground0.params : f.ground1.params;
    }

    /**
     * Пелена, стена пыли и грибы кадра перед точкой ({@code pall_front.glsl}) — шейдеру {@code s}: карты в блоки 5, 9, 10,
     * 11 и числа кадра {@code f}. Пелена не нарисована ({@code drawn} — нет) — пустой кадр (шейдер её не читает), карты —
     * {@code fallback}. Одни числа квадратам слоя и пробам источников ({@link SourceProbes}).
     */
    static void bindFront(ShaderInstance s, Frame f, boolean drawn, int fallback) {
        Frame front = drawn ? f : empty();
        int ground0 = drawn ? GROUND_NOW[0] : -1, ground1 = drawn ? GROUND_NOW[1] : -1;
        RenderSystem.setShaderTexture(5, drawn ? table : fallback);
        RenderSystem.setShaderTexture(9, ground0 >= 0 ? ground0 : fallback);
        RenderSystem.setShaderTexture(10, ground1 >= 0 ? ground1 : fallback);
        RenderSystem.setShaderTexture(11, drawn ? mushrooms : fallback);
        s.safeGetUniform("PallSlot0").set(front.slot0);
        s.safeGetUniform("PallSlot1").set(front.slot1);
        s.safeGetUniform("PallMin").set(front.min.x, front.min.y, front.min.z);
        s.safeGetUniform("PallMax").set(front.max.x, front.max.y, front.max.z);
        s.safeGetUniform("PallAmbient").set(front.ambient.x, front.ambient.y, front.ambient.z, front.ambient.w);
        Vector4f g0 = groundParams(front, 0), g1 = groundParams(front, 1);
        s.safeGetUniform("PallGround0").set(g0.x, g0.y, g0.z, g0.w);
        s.safeGetUniform("PallGround1").set(g1.x, g1.y, g1.z, g1.w);
        s.safeGetUniform("PallWater0").set(front.water0.x, front.water0.y, front.water0.z, front.water0.w);
        s.safeGetUniform("PallWater1").set(front.water1.x, front.water1.y, front.water1.z, front.water1.w);
        s.safeGetUniform("PallWall0").set(front.wall0);
        s.safeGetUniform("PallWall1").set(front.wall1);
        s.safeGetUniform("PallWilson0").set(front.wilson0.x, front.wilson0.y, front.wilson0.z, front.wilson0.w);
        s.safeGetUniform("PallWilson1").set(front.wilson1.x, front.wilson1.y, front.wilson1.z, front.wilson1.w);
    }

    /**
     * Пелена этого кадра после накопления ({@code Sampler6} шейдера {@code fx}: свет и пропускание до мира за пикселем —
     * свет пелены перед квадратом слоя берётся из него).
     */
    static int frame() {
        return TEXTURES[1 + latest];
    }

    /**
     * Нарисовать пелену кадра в текущий кадр (после мира, до квадратов слоя); false — не вышло (нет шейдеров, шум не
     * готов). {@code view} — вид кадра от глаза {@code eye}, {@code scene} — расстояния до мира ({@link SceneDepth}),
     * {@code haze} — дымка воздуха и туман Minecraft ({@link FxFrame.Fog#haze}): тень на мире за ними тает.
     */
    static boolean draw(Frame f, int scene, Matrix4f view, Matrix4f projection, Vec3 eye, Vector4f haze) {
        ShaderInstance m = march, r = resolve, c = composite, sh = shadow;
        if (m == null || r == null || c == null || f.quality == Quality.OFF || !upload()) return false;
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getMainRenderTarget().width, h = mc.getMainRenderTarget().height;
        DETAIL.ceiling(f.quality.step());
        if (DETAIL.pin(SCENARIO || FlashbackReplay.exporting())) {
            Step at = LADDER[Math.max(0, DETAIL.level)];
            if (DETAIL.pinned) {
                Incoming.LOG.info("Nuclear dust pall: {}, detail pinned at the setting 1/{} × {} steps", SCENARIO ? "client scenario" : "Flashback video export",
                        at.factor, at.steps);
            } else {
                Incoming.LOG.info("Nuclear dust pall: Flashback video export over, detail follows the GPU budget again from 1/{} × {} steps", at.factor, at.steps);
            }
        }
        if (timer()) poll(System.nanoTime());
        Step step = LADDER[DETAIL.level];
        if (!ensure(w, h, step.factor)) return false;
        tableBuffer.clear();
        tableBuffer.put(f.table).flip();
        GlStateManager._bindTexture(table);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, WIDTH, ROWS, GL11.GL_RGBA, GL11.GL_FLOAT, tableBuffer);
        // тень грибов — днём, если карта есть; иначе плиток нет (их тексели — нули): вывод на кадр карту не читает
        boolean shade = sh != null && shadowMap >= 0 && f.sun.w > 0 && f.mushroomCount > 0;
        if (!shade) {
            for (int k = 0; k < MushroomField.MUSHROOMS; k++) {
                int at = (k * MushroomField.WIDTH + MushroomField.SHADOW_RECT) * 4;
                Arrays.fill(f.mushrooms, at, at + 4, 0f);
            }
        }
        // строки грибов (свет чёрного тела под ними — с начала)
        mushroomBuffer.clear();
        mushroomBuffer.put(f.mushrooms, 0, MushroomField.WIDTH * MushroomField.MUSHROOMS * 4).flip();
        GlStateManager._bindTexture(mushrooms);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, MushroomField.WIDTH, MushroomField.MUSHROOMS, GL11.GL_RGBA, GL11.GL_FLOAT, mushroomBuffer);
        FIRE_NOW[0] = fire(0, f.fire0);
        FIRE_NOW[1] = fire(1, f.fire1);
        int fire0 = FIRE_NOW[0] >= 0 ? FIRE_NOW[0] : table, fire1 = FIRE_NOW[1] >= 0 ? FIRE_NOW[1] : table;
        Matrix4f fireSlot0 = FIRE_NOW[0] >= 0 ? f.fire0.params : NO_FIRE, fireSlot1 = FIRE_NOW[1] >= 0 ? f.fire1.params : NO_FIRE;
        GROUND_NOW[0] = ground(0, f.ground0);
        GROUND_NOW[1] = ground(1, f.ground1);
        int ground0 = GROUND_NOW[0], ground1 = GROUND_NOW[1];
        Vector4f groundSlot0 = groundParams(f, 0), groundSlot1 = groundParams(f, 1);
        if (ground0 < 0) ground0 = table;
        if (ground1 < 0) ground1 = table;

        VIEW_PROJECTION.set(projection).mul(view);
        VIEW_PROJECTION.invert(INVERSE);
        view.positiveZ(FORWARD).negate();
        if (previousEye == null || previousEye.distanceToSqr(eye) > JUMP * JUMP) history = false;
        float dx = (float) (previousEye == null ? 0 : eye.x - previousEye.x), dy = (float) (previousEye == null ? 0 : eye.y - previousEye.y),
                dz = (float) (previousEye == null ? 0 : eye.z - previousEye.z);
        frame++;
        int timing = begin();

        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.colorMask(true, true, true, true);

        // 0. карта тени грибов от солнца — плитки грибов кадра одна над другой
        if (shade) {
            GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, shadowFbo);
            RenderSystem.viewport(0, 0, MushroomField.SHADOW, MushroomField.SHADOW * f.mushroomCount);
            RenderSystem.setShaderTexture(0, mushrooms);
            sh.safeGetUniform("PallSun").set(f.sun.x, f.sun.y, f.sun.z, f.sun.w);
            quad(sh, false);
        }

        // 1. лучи
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, FBOS[0]);
        RenderSystem.viewport(0, 0, lowW, lowH);
        RenderSystem.setShaderTexture(0, scene);
        RenderSystem.setShaderTexture(1, table);
        RenderSystem.setShaderTexture(2, blue);
        RenderSystem.setShaderTexture(3, fire0);
        RenderSystem.setShaderTexture(4, fire1);
        RenderSystem.setShaderTexture(5, ground0);
        RenderSystem.setShaderTexture(6, ground1);
        RenderSystem.setShaderTexture(7, mushrooms);
        m.safeGetUniform("InvViewProj").set(INVERSE);
        m.safeGetUniform("PallForward").set(FORWARD.x, FORWARD.y, FORWARD.z);
        m.safeGetUniform("PallSlot0").set(f.slot0);
        m.safeGetUniform("PallSlot1").set(f.slot1);
        m.safeGetUniform("PallMin").set(f.min.x, f.min.y, f.min.z);
        m.safeGetUniform("PallMax").set(f.max.x, f.max.y, f.max.z);
        m.safeGetUniform("PallSun").set(f.sun.x, f.sun.y, f.sun.z, f.sun.w);
        m.safeGetUniform("PallSunLight").set(f.sunLight.x, f.sunLight.y, f.sunLight.z);
        m.safeGetUniform("PallSkyLight").set(f.skyLight.x, f.skyLight.y, f.skyLight.z);
        m.safeGetUniform("PallFireLight").set(f.fireLight.x, f.fireLight.y, f.fireLight.z);
        m.safeGetUniform("PallBase").set(f.base);
        m.safeGetUniform("PallGlow0").set(f.glow0.x, f.glow0.y, f.glow0.z, f.glow0.w);
        m.safeGetUniform("PallGlow1").set(f.glow1.x, f.glow1.y, f.glow1.z, f.glow1.w);
        m.safeGetUniform("PallHot0").set(f.hot0.x, f.hot0.y, f.hot0.z, f.hot0.w);
        m.safeGetUniform("PallHot1").set(f.hot1.x, f.hot1.y, f.hot1.z, f.hot1.w);
        m.safeGetUniform("PallGlowOnSmoke").set(f.glowOnSmoke);
        m.safeGetUniform("PallFrame").set((float) (frame % 4096), step.steps, factor, f.clip);
        m.safeGetUniform("PallFire0").set(fireSlot0);
        m.safeGetUniform("PallFire1").set(fireSlot1);
        m.safeGetUniform("PallFireMin").set(f.fireMin.x, f.fireMin.y, f.fireMin.z);
        m.safeGetUniform("PallFireMax").set(f.fireMax.x, f.fireMax.y, f.fireMax.z);
        m.safeGetUniform("PallWhirlPos").set(f.whirlPos);
        m.safeGetUniform("PallWhirlShape").set(f.whirlShape);
        m.safeGetUniform("PallWhirlLean").set(f.whirlLean);
        m.safeGetUniform("PallGround0").set(groundSlot0.x, groundSlot0.y, groundSlot0.z, groundSlot0.w);
        m.safeGetUniform("PallGround1").set(groundSlot1.x, groundSlot1.y, groundSlot1.z, groundSlot1.w);
        m.safeGetUniform("PallWater0").set(f.water0.x, f.water0.y, f.water0.z, f.water0.w);
        m.safeGetUniform("PallWater1").set(f.water1.x, f.water1.y, f.water1.z, f.water1.w);
        m.safeGetUniform("PallWall0").set(f.wall0);
        m.safeGetUniform("PallWall1").set(f.wall1);
        m.safeGetUniform("PallWilson0").set(f.wilson0.x, f.wilson0.y, f.wilson0.z, f.wilson0.w);
        m.safeGetUniform("PallWilson1").set(f.wilson1.x, f.wilson1.y, f.wilson1.z, f.wilson1.w);
        m.safeGetUniform("PallFloorFrom").set(f.floorFrom);
        m.safeGetUniform("PallExposure").set(f.exposure);
        quad(m, true);

        // 2. накопление: из прошлого накопленного — в другое
        int next = 1 + (1 - latest);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, FBOS[next]);
        RenderSystem.setShaderTexture(0, TEXTURES[0]);
        RenderSystem.setShaderTexture(1, TEXTURES[1 + latest]);
        RenderSystem.setShaderTexture(2, scene);
        RenderSystem.setShaderTexture(3, mushrooms);
        r.safeGetUniform("InvViewProj").set(INVERSE);
        r.safeGetUniform("PrevViewProj").set(PREVIOUS);
        r.safeGetUniform("PallForward").set(FORWARD.x, FORWARD.y, FORWARD.z);
        r.safeGetUniform("PallReproject").set(dx, dy, dz, history ? 1f : 0f);
        r.safeGetUniform("PallFrame").set(0f, 0f, factor, REPROJECT_MAX);
        quad(r, false);
        latest = next - 1;

        // 3. на кадр
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        RenderSystem.viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        // свет огненных шаров делает мир ярче, а смешивание только ослабляет: копия кадра, вывод пишет пиксель мира целиком
        boolean balls = f.ballsLit && copyFrame();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        RenderSystem.setShaderTexture(0, TEXTURES[1 + latest]);
        RenderSystem.setShaderTexture(1, scene);
        RenderSystem.setShaderTexture(2, table);
        RenderSystem.setShaderTexture(3, fire0);
        RenderSystem.setShaderTexture(4, fire1);
        RenderSystem.setShaderTexture(5, ground0);
        RenderSystem.setShaderTexture(6, ground1);
        RenderSystem.setShaderTexture(7, mushrooms);
        RenderSystem.setShaderTexture(8, shade ? shadowMap : table);
        RenderSystem.setShaderTexture(9, balls ? frameCopy : table);
        c.safeGetUniform("InvViewProj").set(INVERSE);
        c.safeGetUniform("PallForward").set(FORWARD.x, FORWARD.y, FORWARD.z);
        c.safeGetUniform("PallSlot0").set(f.slot0);
        c.safeGetUniform("PallSlot1").set(f.slot1);
        c.safeGetUniform("PallSun").set(f.sun.x, f.sun.y, f.sun.z, f.sun.w);
        c.safeGetUniform("PallSunLight").set(f.sunLight.x, f.sunLight.y, f.sunLight.z);
        c.safeGetUniform("PallSkyLight").set(f.skyLight.x, f.skyLight.y, f.skyLight.z);
        c.safeGetUniform("PallFrame").set(0f, 0f, factor, 0f);
        c.safeGetUniform("PallFire0").set(fireSlot0);
        c.safeGetUniform("PallFire1").set(fireSlot1);
        c.safeGetUniform("PallGround0").set(groundSlot0.x, groundSlot0.y, groundSlot0.z, groundSlot0.w);
        c.safeGetUniform("PallGround1").set(groundSlot1.x, groundSlot1.y, groundSlot1.z, groundSlot1.w);
        c.safeGetUniform("PallWater0").set(f.water0.x, f.water0.y, f.water0.z, f.water0.w);
        c.safeGetUniform("PallWater1").set(f.water1.x, f.water1.y, f.water1.z, f.water1.w);
        c.safeGetUniform("PallFloorFrom").set(f.floorFrom);
        c.safeGetUniform("Haze").set(haze.x, haze.y, haze.z, haze.w);
        c.safeGetUniform("PallBalls").set(balls ? f.balls : NO_FIRE);
        c.safeGetUniform("PallBallLight").set(f.ballLight);
        c.safeGetUniform("PallOpenSky").set(f.openSky.x, f.openSky.y, f.openSky.z);
        c.safeGetUniform("PallFaceShade").set(f.faceShade.x, f.faceShade.y, f.faceShade.z, f.faceShade.w);
        // пламя на поверхностях мерцает по трёхмерному шуму
        quad(c, true);
        RenderSystem.enableDepthTest();
        end(timing);

        PREVIOUS.set(VIEW_PROJECTION);
        previousEye = eye;
        history = true;
        drawn++;
        return true;
    }

    /** Квадрат на весь кадр своим шейдером; у прохода лучей — ещё и трёхмерный шум на своём блоке текстуры. */
    private static void quad(ShaderInstance s, boolean withNoise) {
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh == null) return;
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                Minecraft.getInstance().getWindow());
        s.apply();
        int unit = GlStateManager._getActiveTexture();
        if (withNoise) {
            // трёхмерную текстуру ShaderInstance не привязывает (у него все — двумерные): свой блок, своя привязка
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, noise);
            GlStateManager._activeTexture(unit);
            Uniform.uploadInteger(Uniform.glGetUniformLocation(s.getId(), "PallNoise"), NOISE_UNIT);
        }
        BufferUploader.draw(mesh);
        s.clear();
        if (withNoise) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GlStateManager._activeTexture(unit);
        }
    }

    /**
     * Карта огня слота {@code k} в видеопамять, если она другая (другой подрыв или новое заполнение), с уменьшенными
     * копиями — скопления источников; вне карты — нули (край — прозрачная рамка). −1 — у слота шторма нет.
     */
    private static int fire(int k, Fire f) {
        if (!f.on()) return -1;
        float[] map = f.map;
        if (FIRE[k] < 0) {
            FIRE[k] = GlStateManager._genTexture();
            GlStateManager._bindTexture(FIRE[k]);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
            FIRE_SIDE[k] = 0;
        }
        if (f.source == FIRE_SOURCE[k] && f.version == FIRE_VERSION[k] && f.side == FIRE_SIDE[k]) return FIRE[k];
        int n = f.side * f.side * 4;
        if (map == null || map.length < n) return -1;
        if (fireBuffer == null || fireBuffer.capacity() < n) {
            if (fireBuffer != null) MemoryUtil.memFree(fireBuffer);
            fireBuffer = MemoryUtil.memAllocFloat(n);
        }
        FloatBuffer buf = fireBuffer;
        buf.clear();
        buf.put(map, 0, n).flip();
        GlStateManager._bindTexture(FIRE[k]);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, f.side, f.side, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buf);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        FIRE_SOURCE[k] = f.source;
        FIRE_VERSION[k] = f.version;
        FIRE_SIDE[k] = f.side;
        return FIRE[k];
    }

    /** Карта рельефа и воды под пеленой слота {@code k} в видеопамять, если она другая (другой подрыв или новое заполнение); −1 — карты нет. */
    private static int ground(int k, Ground g) {
        if (!g.on()) return -1;
        float[] map = g.map;
        int n = g.side * g.side * 2;
        if (map == null || map.length < n) return -1;
        if (GROUND[k] < 0) {
            GROUND[k] = GlStateManager._genTexture();
            GlStateManager._bindTexture(GROUND[k]);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
            GROUND_SIDE[k] = 0;
        }
        if (g.source == GROUND_SOURCE[k] && g.version == GROUND_VERSION[k] && g.side == GROUND_SIDE[k]) return GROUND[k];
        if (groundBuffer == null || groundBuffer.capacity() < n) {
            if (groundBuffer != null) MemoryUtil.memFree(groundBuffer);
            groundBuffer = MemoryUtil.memAllocFloat(n);
        }
        FloatBuffer buf = groundBuffer;
        buf.clear();
        buf.put(map, 0, n).flip();
        GlStateManager._bindTexture(GROUND[k]);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG16F, g.side, g.side, 0, GL30.GL_RG, GL11.GL_FLOAT, buf);
        GROUND_SOURCE[k] = g.source;
        GROUND_VERSION[k] = g.version;
        GROUND_SIDE[k] = g.side;
        return GROUND[k];
    }

    /** Шум из фона — в видеопамять (один раз); false — ещё не готов. */
    private static boolean upload() {
        if (noise >= 0) return true;
        CompletableFuture<byte[][]> ready = textures;
        if (ready == null) {
            prepare();
            return false;
        }
        if (!ready.isDone()) return false;
        if (ready.isCompletedExceptionally()) {
            if (!failed) {
                failed = true;
                Throwable cause = null;
                try {
                    ready.join();
                } catch (CompletionException | CancellationException ex) {
                    cause = ex.getCause() != null ? ex.getCause() : ex;
                }
                Incoming.LOG.error("Nuclear dust pall: noise textures failed, no dust pall", cause);
            }
            return false;
        }
        byte[][] data = ready.getNow(null);
        if (data == null) return false;
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        ByteBuffer buf = MemoryUtil.memAlloc(Math.max(data[0].length, data[1].length));
        try {
            int n = VolumeNoise.SIZE;
            noise = GlStateManager._genTexture();
            int unit = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, noise);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
            buf.clear();
            buf.put(data[0]).flip();
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGB8, n, n, n, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GlStateManager._activeTexture(unit);

            int b = BlueNoise.SIZE;
            blue = GlStateManager._genTexture();
            GlStateManager._bindTexture(blue);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            buf.clear();
            buf.put(data[1]).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, b, b, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, buf);

            table = GlStateManager._genTexture();
            GlStateManager._bindTexture(table);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, WIDTH, ROWS, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
            tableBuffer = MemoryUtil.memAllocFloat(ROWS * WIDTH * 4);

            // таблица грибов: числа по текселям — без смешивания (texelFetch), свет чёрного тела — линейно по температуре
            mushrooms = GlStateManager._genTexture();
            GlStateManager._bindTexture(mushrooms);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
            float[] start = new float[MushroomField.WIDTH * MushroomField.ROWS * 4];
            MushroomField.blackBodyRow(start);
            mushroomBuffer = MemoryUtil.memAllocFloat(start.length);
            mushroomBuffer.put(start).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, MushroomField.WIDTH, MushroomField.ROWS, 0, GL11.GL_RGBA, GL11.GL_FLOAT, mushroomBuffer);
            shadowMap();
        } finally {
            MemoryUtil.memFree(buf);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        }
        return true;
    }

    /**
     * Карта тени грибов: плитки {@link MushroomField#SHADOW}² грибов одна над другой, линейно между текселями (тень на земле
     * гладкая), без повтора на краю; не вышла — тени нет.
     */
    private static void shadowMap() {
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        shadowMap = GlStateManager._genTexture();
        GlStateManager._bindTexture(shadowMap);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, MushroomField.SHADOW, MushroomField.SHADOW * MushroomField.MUSHROOMS, 0,
                GL11.GL_RGBA, GL11.GL_FLOAT, null);
        shadowFbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, shadowFbo);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, shadowMap, 0);
        boolean ok = GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        if (!ok) {
            Incoming.LOG.error("Mushroom shadow map not created: no mushroom shadow on the ground");
            GlStateManager._glDeleteFramebuffers(shadowFbo);
            GlStateManager._deleteTexture(shadowMap);
            shadowMap = shadowFbo = -1;
        }
    }

    /**
     * Копия кадра, в который рисует вывод (привязанного сейчас), — до вывода, своей текстурой во весь кадр от его угла
     * (место под размер кадра — заново при смене): свет шаров на мире ({@code fx_pall_composite}). false — копии нет.
     */
    private static boolean copyFrame() {
        int w = VIEWPORT[0] + VIEWPORT[2], h = VIEWPORT[1] + VIEWPORT[3];
        if (w <= 0 || h <= 0) return false;
        if (frameCopy < 0) {
            frameCopy = GlStateManager._genTexture();
            GlStateManager._bindTexture(frameCopy);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        }
        GlStateManager._bindTexture(frameCopy);
        if (w != copyW || h != copyH) {
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null);
            copyW = w;
            copyH = h;
        }
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        return true;
    }

    /** Кадры пониженного разрешения под размер кадра игры и качество (заново при смене). */
    private static boolean ensure(int w, int h, int f) {
        if (TEXTURES[0] >= 0 && w == fullW && h == fullH && f == factor) return true;
        fullW = w;
        fullH = h;
        factor = f;
        lowW = Math.max(1, (w + f - 1) / f);
        lowH = Math.max(1, (h + f - 1) / f);
        history = false;
        // замеры прежнего размера кадра — не этой цены
        DETAIL.restart();
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean ok = true;
        for (int i = 0; i < TEXTURES.length; i++) {
            if (TEXTURES[i] < 0) TEXTURES[i] = GlStateManager._genTexture();
            if (FBOS[i] < 0) FBOS[i] = GlStateManager.glGenFramebuffers();
            GlStateManager._bindTexture(TEXTURES[i]);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, lowW, lowH, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
            GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, FBOS[i]);
            GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, TEXTURES[i], 0);
            ok &= GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        }
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        if (!ok) {
            Incoming.LOG.error("Nuclear dust pall: render targets {}×{} not created, no dust pall", lowW, lowH);
            fullW = fullH = 0;
            return false;
        }
        return true;
    }

    /** Есть ли метки времени OpenGL (GL 3.3 или ARB_timer_query, и счётчик не пустой); пары заводятся при первой проверке. */
    private static boolean timer() {
        if (timer == 0) {
            GLCapabilities caps = GL.getCapabilities();
            boolean ok = (caps.OpenGL33 || caps.GL_ARB_timer_query) && GL15.glGetQueryi(GL33.GL_TIMESTAMP, GL15.GL_QUERY_COUNTER_BITS) > 0;
            if (ok) {
                for (int i = 0; i < QUERIES.length; i++) QUERIES[i] = GL15.glGenQueries();
                Arrays.fill(ISSUED, -1);
            } else {
                Incoming.LOG.info("Nuclear dust pall: no GPU timer queries, detail stays as set");
            }
            timer = ok ? 1 : -1;
        }
        return timer > 0;
    }

    /** Прочитать готовые пары меток (от старых к новым, без ожидания) — в сводку и в {@link Detail}. */
    private static void poll(long now) {
        for (int k = 0; k < RING; k++) {
            int i = (ring + k) % RING;
            if (ISSUED[i] < 0 || GL15.glGetQueryObjecti(QUERIES[2 * i + 1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            long start = GL33.glGetQueryObjecti64(QUERIES[2 * i], GL15.GL_QUERY_RESULT), stop = GL33.glGetQueryObjecti64(QUERIES[2 * i + 1], GL15.GL_QUERY_RESULT);
            double ms = Math.max(0, stop - start) / 1e6;
            gpuSum += ms;
            gpuMax = Math.max(gpuMax, ms);
            gpuFrames++;
            int was = DETAIL.level;
            if (DETAIL.sample(ms, ISSUED[i], now)) {
                Step from = LADDER[was], to = LADDER[DETAIL.level];
                // в игре — DEBUG: смена ступени бывает раз в несколько секунд; в сценарии ступень прибита (draw)
                Incoming.LOG.debug(String.format(Locale.ROOT, "Nuclear dust pall: %.2f ms on the GPU (budget %.1f), detail 1/%d × %d steps → 1/%d × %d",
                        DETAIL.lastMean, Detail.BUDGET_MS, from.factor, from.steps, to.factor, to.steps));
            }
            ISSUED[i] = -1;
        }
    }

    /** Метка до проходов; −1 — кадр не меряется (меток нет или все пары ещё в полёте). */
    private static int begin() {
        if (!timer() || ISSUED[ring] >= 0) return -1;
        GL33.glQueryCounter(QUERIES[2 * ring], GL33.GL_TIMESTAMP);
        return ring;
    }

    /** Метка после проходов: пара в полёте, читается через кадры ({@link #poll}). */
    private static void end(int pair) {
        if (pair < 0) return;
        GL33.glQueryCounter(QUERIES[2 * pair + 1], GL33.GL_TIMESTAMP);
        ISSUED[pair] = DETAIL.generation;
        ring = (pair + 1) % RING;
    }

    /** Раз в секунду (тик слоя): сводка времени. */
    static void second() {
        lastGpuMean = gpuFrames > 0 ? gpuSum / gpuFrames : 0;
        lastGpuMax = gpuMax;
        lastDrawn = drawn;
        gpuSum = gpuMax = 0;
        gpuFrames = drawn = 0;
    }

    /** Строка сценария: кадров с пеленой за секунду, разрешение, ступень подробности, время на видеокарте. */
    public static String describe() {
        if (lastDrawn == 0) return failed ? "failed" : "off";
        Step step = LADDER[Math.max(0, DETAIL.level)];
        Step top = LADDER[Math.max(0, DETAIL.ceiling)];
        String gpu = timer < 0 ? "gpu n/a" : String.format(Locale.ROOT, "gpu %.2f ms (longest %.2f, budget %.1f)", lastGpuMean, lastGpuMax, Detail.BUDGET_MS);
        return String.format(Locale.ROOT, "%d frames, %d×%d, detail 1/%d × %d steps (step %d of %d, setting 1/%d × %d, %s, %d changes), %s",
                lastDrawn, lowW, lowH, step.factor, step.steps, DETAIL.level, LADDER.length - 1, top.factor, top.steps,
                DETAIL.pinned ? "pinned" : "by GPU budget", DETAIL.changes, gpu);
    }

    /**
     * Перемотка повтора, смена мира, кадр без пелены: накопленное — от другого кадра; метки времени, снятые до этого,
     * подробность не считает (пелена вернётся — может быть, в другом месте и через минуты).
     */
    public static void reset() {
        if (previousEye != null) DETAIL.restart();
        history = false;
        previousEye = null;
        // карты огня прежних подрывов не держим (текстуры остаются — под следующий)
        FIRE_SOURCE[0] = FIRE_SOURCE[1] = null;
        FIRE_NOW[0] = FIRE_NOW[1] = -1;
        GROUND_SOURCE[0] = GROUND_SOURCE[1] = null;
        GROUND_NOW[0] = GROUND_NOW[1] = -1;
    }
}
