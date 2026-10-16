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

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Пелена после ударной волны — объёмом, тремя проходами в слое эффектов (до квадратов частиц):
 * <ol>
 *     <li>{@code fx_pall} — луч на пиксель кадра пониженного разрешения (вдвое или вчетверо, {@link Quality}) по полю
 *     подрыва ({@code client/nuclear/PallField}: таблица по радиусу) × трёхмерный шум ({@link VolumeNoise}); начало шагов
 *     сдвинуто синим шумом ({@link BlueNoise}) и от кадра к кадру;</li>
 *     <li>{@code fx_pall_resolve} — накопление по кадрам: прошлое переносится по глубине мира за пикселем, зажато
 *     в пределы соседей (зерно уходит, след за движением — нет);</li>
 *     <li>{@code fx_pall_composite} — на кадр: свет пелены плюс мир за ней, ослабленный; из пониженного разрешения —
 *     с весом по сходству расстояния до мира (края домов чёткие).</li>
 * </ol>
 * Квадраты слоя после этого рисуются со своей долей пелены перед ними ({@code fx.vsh}: те же поле и таблица, без
 * шума). Своих текстур — три кадра пониженного разрешения (RGBA16F), таблица 128×4, шум 64³ (RG8) и 64² (R8): всё
 * вместе меньше 2 МБ на 1080p. Шум считается в фоне при первом подрыве (около секунды); пока он не готов,
 * пелены нет. Время на видеокарте — запрос OpenGL ({@link #describe}, строка {@code SCENARIO pall}).
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

    /** Качество пелены: во сколько раз кадр мельче полного и сколько шагов на луч. */
    public enum Quality {
        OFF(0, 0), LOW(4, 20), MEDIUM(2, 28), HIGH(2, 48);

        final int factor, steps;

        Quality(int factor, int steps) {
            this.factor = factor;
            this.steps = steps;
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

    // время на видеокарте: запрос в полёте и сумма за секунду. Только в сценариях клиента: запрос времени у OpenGL один
    // на всех, и чужой замер (профайлер, мод) в тот же миг сломал бы и его, и наш
    private static final boolean TIMING = System.getProperty("incoming.scenario") != null;
    private static int query = -1;
    private static boolean querying;
    private static double gpuSum, gpuMax, lastGpuMean, lastGpuMax;
    private static int gpuFrames, drawn, lastDrawn;

    private PallPass() {}

    /** Пелена без подрывов: квадраты слоя её не берут. */
    public static Frame empty() {
        return EMPTY;
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
        if (!ensure(w, h, f.quality.factor)) return false;
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
        boolean timing = beginQuery();

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
        m.safeGetUniform("PallFrame").set((float) (frame % 4096), f.quality.steps, factor, f.clip);
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
        c.safeGetUniform("PallFrame").set(0f, 0f, factor, 0f);
        quad(c, false);
        RenderSystem.enableDepthTest();
        if (timing) GL15.glEndQuery(GL33.GL_TIME_ELAPSED);

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

    /** Начать замер времени на видеокарте, если прошлый уже прочитан; false — не замеряем. */
    private static boolean beginQuery() {
        if (!TIMING) return false;
        GLCapabilities caps = GL.getCapabilities();
        if (!caps.OpenGL33 && !caps.GL_ARB_timer_query) return false;
        if (query < 0) query = GL15.glGenQueries();
        if (querying) {
            if (GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) return false;
            double ms = GL33.glGetQueryObjecti64(query, GL15.GL_QUERY_RESULT) / 1e6;
            gpuSum += ms;
            gpuMax = Math.max(gpuMax, ms);
            gpuFrames++;
            querying = false;
        }
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
        querying = true;
        return true;
    }

    /** Раз в секунду (тик слоя): сводка времени. */
    static void second() {
        lastGpuMean = gpuFrames > 0 ? gpuSum / gpuFrames : 0;
        lastGpuMax = gpuMax;
        lastDrawn = drawn;
        gpuSum = gpuMax = 0;
        gpuFrames = drawn = 0;
    }

    /** Строка сценария: кадров с пеленой за секунду, разрешение, шаги, время на видеокарте. */
    public static String describe() {
        if (lastDrawn == 0) return "off";
        return String.format(Locale.ROOT, "%d frames, %d×%d (1/%d), gpu %.2f ms (longest %.2f)", lastDrawn, lowW, lowH, factor, lastGpuMean, lastGpuMax);
    }

    /** Перемотка повтора, смена мира: накопленное — от другого кадра. */
    public static void reset() {
        history = false;
        previousEye = null;
    }
}
