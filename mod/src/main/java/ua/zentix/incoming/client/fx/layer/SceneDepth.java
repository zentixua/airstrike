package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.render.DhDepth;

import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * Расстояние до мира за каждым пикселем кадра — одной текстурой (метры по оси взгляда, 32 бита), по глубине
 * Minecraft и глубине Distant Horizons (ближнее из двух; неба — 10⁹). Слой эффектов читает её, чтобы клуб мягко
 * таял там, где входит в землю или стену, и пропадал за LOD DH, которых аппаратная глубина не видит.
 * <p>
 * Отдельным проходом в свою текстуру, а не чтением глубины прямо в шейдере частиц: глубина Minecraft привязана
 * к кадру, в который рисуется слой, а читать текстуру, в которую рисуешь, OpenGL не разрешает.
 */
public final class SceneDepth {
    @Nullable
    static ShaderInstance shader;
    private static int fbo = -1, texture = -1, width, height;
    private static final DhDepth.View DH = new DhDepth.View();
    private static final Matrix4f INVERSE = new Matrix4f();
    private static final int[] VIEWPORT = new int[4];
    /** Чем закрыт слой в последнем кадре: 0 — ничем, 1 — миром, 2 — миром и LOD DH. */
    private static int last;
    /** Диагностика прогонов ({@code -Dincoming.nukeDiag=true}): колонка расстояний раз в секунду, см. {@link #diag}. */
    private static final boolean DIAG = Boolean.getBoolean("incoming.nukeDiag");
    private static final float[] DIAG_ROWS = {0.1f, 0.3f, 0.45f, 0.55f, 0.65f, 0.75f, 0.9f};
    private static long nextDiag;

    private SceneDepth() {}

    /**
     * Заполнить текстуру расстояний для этого кадра по проекции мира; −1 — не вышло (шейдер не загрузился, кадр
     * без глубины): тогда слой рисуется без мягких краёв.
     */
    static int update(Matrix4f projection) {
        ShaderInstance s = shader;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int depth = main.getDepthTextureId();
        last = 0;
        if (s == null || depth <= 0) return -1;
        if (!ensure(main.width, main.height)) return -1;
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        boolean dh = DhDepth.current(DH);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        RenderSystem.viewport(0, 0, width, height);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> s);
        RenderSystem.setShaderTexture(0, depth);
        RenderSystem.setShaderTexture(1, dh ? DH.texture : depth);
        s.safeGetUniform("InvProj").set(projection.invert(INVERSE));
        s.safeGetUniform("DhInvProj").set(dh ? DH.inverseProjection : INVERSE);
        s.safeGetUniform("DhDepth").set(dh ? 1f : 0f, DH.negativeOneToOne ? 1f : 0f, DH.empty);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        RenderSystem.viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        RenderSystem.enableDepthTest();
        last = dh ? 2 : 1;
        if (DIAG) diag(depth, main.width, main.height, previous, main.frameBufferId);
        return texture;
    }

    /**
     * Строка лога по средней колонке кадра сверху вниз: глубина Minecraft и её расстояние, глубина DH и её расстояние,
     * итог текстуры расстояний. Читает три текстуры обратно (ждёт видеокарту) — только при диагностике прогонов.
     */
    private static void diag(int worldDepth, int w, int h, int drawn, int mainFbo) {
        long now = System.currentTimeMillis();
        if (now < nextDiag) return;
        nextDiag = now + 1000;
        // во что на самом деле рисовался мир: кадр, привязанный к AFTER_LEVEL, и его глубина — против кадра Minecraft
        int[] drawnDepth = attachment(drawn), mainDepth = attachment(mainFbo);
        Incoming.LOG.info(String.format(Locale.ROOT,
                "Effects layer depth source: main target depth texture %d, drawn framebuffer %d depth %s %d, main framebuffer %d depth %s %d",
                worldDepth, drawn, kind(drawnDepth[0]), drawnDepth[1], mainFbo, kind(mainDepth[0]), mainDepth[1]));
        if (drawnDepth[0] == GL11.GL_TEXTURE && drawnDepth[1] != worldDepth) {
            float[] other = read(drawnDepth[1], GL11.GL_DEPTH_COMPONENT);
            int ow = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int oh = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "Effects layer drawn depth column %dx%d", ow, oh));
            for (float row : DIAG_ROWS) {
                int y = Math.round((1 - row) * (oh - 1));
                float raw = other != null && ow > 0 ? other[y * ow + ow / 2] : Float.NaN;
                b.append(String.format(Locale.ROOT, "; %.2f: %.6f→%.1f", row, raw, along(INVERSE, 1 - 2 * row, raw * 2 - 1)));
            }
            Incoming.LOG.info(b.toString());
        }
        float[] scene = read(texture, GL11.GL_RED);
        float[] world = read(worldDepth, GL11.GL_DEPTH_COMPONENT);
        float[] lod = last == 2 ? read(DH.texture, GL11.GL_DEPTH_COMPONENT) : null;
        int lodW = last == 2 ? GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) : 0;
        int lodH = last == 2 ? GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) : 0;
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT,
                "Effects layer depth column: frame %dx%d, layer %dx%d, DH %dx%d, inverse projection z row %.6f %.6f, w row %.6f %.6f",
                w, h, width, height, lodW, lodH, INVERSE.m22(), INVERSE.m32(), INVERSE.m23(), INVERSE.m33()));
        for (float row : DIAG_ROWS) {
            float ny = 1 - 2 * row;
            int x = width / 2, y = Math.round((1 - row) * (height - 1));
            float raw = world != null ? world[y * width + x] : Float.NaN;
            b.append(String.format(Locale.ROOT, "; %.2f: world %.6f→%.1f", row, raw, along(INVERSE, ny, raw * 2 - 1)));
            if (lod != null && lodW > 0) {
                int lx = lodW / 2, ly = Math.round((1 - row) * (lodH - 1));
                float l = lod[ly * lodW + lx];
                b.append(String.format(Locale.ROOT, " lod %.6f→%.1f", l,
                        along(DH.inverseProjection, ny, DH.negativeOneToOne ? l * 2 - 1 : l)));
            }
            b.append(String.format(Locale.ROOT, " scene %.1f", scene != null ? scene[y * width + x] : Float.NaN));
        }
        Incoming.LOG.info(b.toString());
    }

    /** Тип «кадр окна» в {@link #attachment}: у него вложений нет, спрашивать о них — ошибка OpenGL. */
    private static final int WINDOW = -1;

    /**
     * Глубина кадра {@code fbo}: тип вложения (текстура, буфер, нет) и его имя; у кадра окна (0) — {@link #WINDOW}, без
     * вопросов OpenGL: {@code GL_DEPTH_ATTACHMENT} у него нет (ошибка {@code GL_INVALID_OPERATION} осталась бы тому, кто
     * спросит после).
     */
    private static int[] attachment(int fbo) {
        if (fbo == 0) return new int[]{WINDOW, 0};
        int bound = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int name = type == GL11.GL_NONE ? 0
                : GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, bound);
        return new int[]{type, name};
    }

    private static String kind(int type) {
        return switch (type) {
            case GL11.GL_TEXTURE -> "texture";
            case GL30.GL_RENDERBUFFER -> "renderbuffer";
            case GL11.GL_NONE -> "none";
            case WINDOW -> "window";
            default -> "0x" + Integer.toHexString(type);
        };
    }

    /** Текстура целиком в память (размер — её собственный); null — не вышло. */
    private static float @Nullable [] read(int id, int format) {
        if (id <= 0) return null;
        GlStateManager._bindTexture(id);
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (w <= 0 || h <= 0) return null;
        FloatBuffer f = MemoryUtil.memAllocFloat(w * h);
        try {
            texImage(format, f);
            float[] out = new float[w * h];
            f.get(out);
            return out;
        } finally {
            MemoryUtil.memFree(f);
        }
    }

    /**
     * Привязанная текстура (уровень 0) в {@code out} числами. Оставь другой код привязанным {@code GL_PIXEL_PACK_BUFFER},
     * OpenGL писал бы в него (адрес {@code out} — как смещение в нём), а не в память: на время чтения он снимается
     * и потом возвращается.
     */
    private static void texImage(int format, FloatBuffer out) {
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        if (pack != 0) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        try {
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, format, GL11.GL_FLOAT, out);
        } finally {
            if (pack != 0) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
        }
    }

    /** Расстояние по оси взгляда в середине кадра по горизонтали на высоте ny (NDC) — как {@code along} в {@code fx_depth}. */
    private static float along(Matrix4f inverse, float ny, float z) {
        Vector4f v = inverse.transform(new Vector4f(0, ny, z, 1));
        return -v.z / v.w;
    }

    /** Чем закрыт слой в последнем кадре — для строки сценария. */
    static String describe() {
        return switch (last) {
            case 2 -> "world and DH (" + DH.describe() + lodShare() + ")";
            case 1 -> "world";
            default -> "none";
        };
    }

    /**
     * Доля кадра, где за пикселем LOD дальше прорисовки Minecraft, и ближний из них — по текстуре расстояний,
     * прочитанной обратно (ждёт видеокарту: только для строки сценария).
     */
    private static String lodShare() {
        if (texture < 0 || width <= 0) return "";
        int n = width * height;
        FloatBuffer b = MemoryUtil.memAllocFloat(n);
        try {
            GlStateManager._bindTexture(texture);
            texImage(GL11.GL_RED, b);
            float beyond = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
            int lod = 0;
            float nearest = Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                float d = b.get(i);
                if (d > beyond && d < 1e8f) {
                    lod++;
                    nearest = Math.min(nearest, d);
                }
            }
            if (lod == 0) return ", no LOD beyond the render distance";
            return String.format(Locale.ROOT, ", LOD beyond the render distance in %.1f%% of the frame, nearest %.0f blocks",
                    100.0 * lod / n, nearest);
        } finally {
            MemoryUtil.memFree(b);
        }
    }

    /** Своя текстура и кадр под размер кадра игры (заново при смене размера окна). */
    private static boolean ensure(int w, int h) {
        if (fbo >= 0 && w == width && h == height) return true;
        if (texture < 0) texture = GlStateManager._genTexture();
        if (fbo < 0) fbo = GlStateManager.glGenFramebuffers();
        width = w;
        height = h;
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_R32F, w, h, 0, GL11.GL_RED, GL11.GL_FLOAT, null);
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, texture, 0);
        boolean ok = GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        if (!ok) {
            Incoming.LOG.error("Effects layer: depth texture {}×{} not created, no soft edges", w, h);
            width = height = 0;
            return false;
        }
        return true;
    }
}
