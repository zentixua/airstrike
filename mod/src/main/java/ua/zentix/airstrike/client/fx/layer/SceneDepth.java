package ua.zentix.airstrike.client.fx.layer;

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
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.render.DhDepth;

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
        return texture;
    }

    /** Чем закрыт слой в последнем кадре — для строки сценария. */
    static String describe() {
        return switch (last) {
            case 2 -> "мир и DH";
            case 1 -> "мир";
            default -> "нет";
        };
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
            Airstrike.LOG.error("Слой эффектов: текстура расстояний {}×{} не создалась, мягких краёв не будет", w, h);
            width = height = 0;
            return false;
        }
        return true;
    }
}
