package ua.zentix.airstrike.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import ua.zentix.airstrike.Airstrike;

import java.util.Arrays;

/**
 * Какая доля яркого источника (огненный шар, пожар, факел) видна в кадре — для его блика и вуали. Они — свет,
 * рассеянный в самом глазу: их квадрат стоит у глаза ({@code FarSprites#eye}), и ни глубина, ни расстояние до мира за
 * его пикселями их не режут — закрыть их может только то, что закрывает сам источник. Луч по рельефу дальше прорисовки
 * ({@code Sightline}: шаг от 16 блоков, высоты карты) мимо высоток проходит, а LOD Distant Horizons не видит вовсе:
 * пожар за городом вдали, сам закрытый домами, светил сквозь них бликом и вуалью.
 * <p>
 * Поэтому видимость — по тому, что на экране: на каждый источник кадра проба — пиксель своей текстуры, шейдер
 * {@code fx_probe} читает расстояние до мира ({@link SceneDepth}: глубина Minecraft и LOD DH) в середине источника на
 * экране и по верхней половине круга вокруг неё (как лучи по блокам в прорисовке, {@code FarBlasts#clear}: нижнюю
 * часть диска у земли закрывает земля под самим источником) и сравнивает с расстоянием до его передней половины, с той
 * же мягкостью, что у тела в слое. Квадрат блика берёт свою пробу в вершинном шейдере слоя ({@code fx.vsh}) по номеру
 * ({@link FxQuads#seenBy}). Источник вне кадра пробе не виден — там видимость решают лучи вызывающего.
 */
public final class SourceProbes {
    /** Проб в строке текстуры. */
    static final int WIDTH = 256;
    /** Проб в кадре не больше: номер пробы — в 16-битном поле вершины слоя ({@link FxQuads#vertex}). */
    static final int MAX = Short.MAX_VALUE;
    /** Вершина пробы: середина источника от глаза, радиус и номер, угол квадрата на её пикселе. */
    static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1)
            .build();
    @Nullable
    static ShaderInstance shader;
    private static final int[] VIEWPORT = new int[4];
    private int fbo = -1, texture = -1, rows;
    /** Источники кадра: середина от глаза (xyz) и радиус, блоков. */
    private float[] sources = new float[4 * 64];
    private int n;

    /** Новый кадр: источников нет. */
    void begin() {
        n = 0;
    }

    /**
     * Источник с серединой (x, y, z) от глаза радиуса r, блоков — номер его пробы (с 1); 0 — проб в кадре уже
     * {@link #MAX}: такой источник видимость берёт только от лучей вызывающего.
     */
    public int add(double x, double y, double z, double r) {
        if (n >= MAX) return 0;
        if (4 * (n + 1) > sources.length) sources = Arrays.copyOf(sources, sources.length * 2);
        int i = 4 * n;
        sources[i] = (float) x;
        sources[i + 1] = (float) y;
        sources[i + 2] = (float) z;
        sources[i + 3] = (float) r;
        return ++n;
    }

    /**
     * Посчитать пробы кадра по текстуре расстояний до мира {@code scene} ({@link SceneDepth#update}): вид от глаза,
     * проекция кадра, угол пикселя, рад. Текстура видимости (доля, по номеру пробы − 1: x — остаток от {@link #WIDTH},
     * y — частное); −1 — проб нет или не вышло.
     */
    int update(int scene, Matrix4f view, Matrix4f projection, double pixel) {
        ShaderInstance s = shader;
        if (n == 0 || s == null || scene < 0 || !ensure((n + WIDTH - 1) / WIDTH)) return -1;
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        RenderSystem.viewport(0, 0, WIDTH, rows);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> s);
        RenderSystem.setShaderTexture(0, scene);
        s.safeGetUniform("SourceView").set(view);
        s.safeGetUniform("SourceProj").set(projection);
        s.safeGetUniform("ProbeSize").set((float) WIDTH, (float) rows);
        s.safeGetUniform("Pixel").set((float) pixel);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, FORMAT);
        for (int i = 0; i < n; i++) {
            float x = sources[4 * i], y = sources[4 * i + 1], z = sources[4 * i + 2], r = sources[4 * i + 3];
            // квадрат ровно на свой пиксель, против часовой стрелки
            b.addVertex(x, y, z).setUv(r, i).setNormal(-1, -1, 0);
            b.addVertex(x, y, z).setUv(r, i).setNormal(1, -1, 0);
            b.addVertex(x, y, z).setUv(r, i).setNormal(1, 1, 0);
            b.addVertex(x, y, z).setUv(r, i).setNormal(-1, 1, 0);
        }
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        RenderSystem.viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        RenderSystem.enableDepthTest();
        return texture;
    }

    /** Текстура видимости и её кадр — не меньше {@code need} строк (растёт, не убывает). */
    private boolean ensure(int need) {
        if (fbo >= 0 && need <= rows) return true;
        if (texture < 0) texture = GlStateManager._genTexture();
        if (fbo < 0) fbo = GlStateManager.glGenFramebuffers();
        rows = Math.max(need, 2 * rows);
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_R8, WIDTH, rows, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, null);
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, texture, 0);
        boolean ok = GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        if (!ok) {
            Airstrike.LOG.error("Слой эффектов: текстура проб источников {}×{} не создалась, блик закрытого огня не гаснет", WIDTH, rows);
            rows = 0;
            return false;
        }
        return true;
    }
}
