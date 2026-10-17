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
 * шума). Своих текстур — три кадра пониженного разрешения (RGBA16F), таблица 128×4, шум 64³ (RG8) и 64² (R8): всё
 * вместе меньше 2 МБ на 1080p. Шум считается в фоне при первом подрыве (около секунды); пока он не готов,
 * пелены нет.
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
    /** Блок текстуры трёхмерного шума: ни один шейдер Minecraft и слоя столько двумерных не берёт. */
    private static final int NOISE_UNIT = 7;
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
        /** Свет солнца, неба и пожаров на экране. */
        public final Vector3f sunLight = new Vector3f(), skyLight = new Vector3f(), fireLight = new Vector3f();
        /** Средний свет пелены (rgb) — для квадратов слоя перед частицами; w — 1, если пелена есть. */
        public final Vector4f ambient = new Vector4f();
        /** Дальше стольких блоков луч не идёт (в густой пыли у глаза — что за ней, всё равно не видно). */
        public float clip;
        public Quality quality = Quality.MEDIUM;
    }

    @Nullable
    static ShaderInstance march, resolve, composite;
    private static final Frame EMPTY = new Frame();
    private static final Detail DETAIL = new Detail();
    /** Шум не вышел (ошибка в фоне): пелены нет до перезапуска. */
    private static boolean failed;

    private static int fullW, fullH, lowW, lowH, factor;
    /** Кадр луча и два накопленных (по очереди: прошлый и этот). */
    private static final int[] TEXTURES = {-1, -1, -1}, FBOS = {-1, -1, -1};
    private static int latest;
    private static int table = -1, noise = -1, blue = -1;
    @Nullable
    private static CompletableFuture<byte[][]> textures;
    private static FloatBuffer tableBuffer;
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

    /** Пелену можно рисовать: шум не сломался (до его готовности — тоже да: он ещё будет). */
    public static boolean available() {
        return !failed;
    }

    /** Шум готовится в фоне (первый подрыв): до готовности пелены нет. */
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

    /**
     * Пелена этого кадра после накопления ({@code Sampler6} шейдера {@code fx}: свет и пропускание до мира за пикселем —
     * свет пелены перед квадратом слоя берётся из него).
     */
    static int frame() {
        return TEXTURES[1 + latest];
    }

    /**
     * Нарисовать пелену кадра в текущий кадр (после мира, до квадратов слоя); false — не вышло (нет шейдеров, шум не
     * готов). {@code view} — вид кадра от глаза {@code eye}, {@code scene} — расстояния до мира ({@link SceneDepth}).
     */
    static boolean draw(Frame f, int scene, Matrix4f view, Matrix4f projection, Vec3 eye) {
        ShaderInstance m = march, r = resolve, c = composite;
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

        // 1. лучи
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, FBOS[0]);
        RenderSystem.viewport(0, 0, lowW, lowH);
        RenderSystem.setShaderTexture(0, scene);
        RenderSystem.setShaderTexture(1, table);
        RenderSystem.setShaderTexture(2, blue);
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
        m.safeGetUniform("PallFrame").set((float) (frame % 4096), step.steps, factor, f.clip);
        quad(m, true);

        // 2. накопление: из прошлого накопленного — в другое
        int next = 1 + (1 - latest);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, FBOS[next]);
        RenderSystem.setShaderTexture(0, TEXTURES[0]);
        RenderSystem.setShaderTexture(1, TEXTURES[1 + latest]);
        RenderSystem.setShaderTexture(2, scene);
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
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        RenderSystem.setShaderTexture(0, TEXTURES[1 + latest]);
        RenderSystem.setShaderTexture(1, scene);
        RenderSystem.setShaderTexture(2, table);
        c.safeGetUniform("InvViewProj").set(INVERSE);
        c.safeGetUniform("PallForward").set(FORWARD.x, FORWARD.y, FORWARD.z);
        c.safeGetUniform("PallSlot0").set(f.slot0);
        c.safeGetUniform("PallSlot1").set(f.slot1);
        c.safeGetUniform("PallSun").set(f.sun.x, f.sun.y, f.sun.z, f.sun.w);
        c.safeGetUniform("PallFrame").set(0f, 0f, factor, 0f);
        quad(c, false);
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
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RG8, n, n, n, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, buf);
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
        } finally {
            MemoryUtil.memFree(buf);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        }
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
    }
}
